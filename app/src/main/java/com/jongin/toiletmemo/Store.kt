package com.jongin.toiletmemo

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Toilet(
    val id: String = UUID.randomUUID().toString(),
    var lat: Double,
    var lon: Double,
    var building: String,
    var floor: String,
    var place: String,
    var pwEnc: String,
    var memo: String,
    var scope: String = "mine",
    var updated: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("lat", lat).put("lon", lon)
        .put("building", building).put("floor", floor).put("place", place)
        .put("pw", pwEnc).put("memo", memo).put("scope", scope).put("updated", updated)

    fun title(): String = if (floor.isBlank()) building else "$building $floor"

    companion object {
        fun from(o: JSONObject) = Toilet(
            id = o.optString("id"),
            lat = o.optDouble("lat"),
            lon = o.optDouble("lon"),
            building = o.optString("building"),
            floor = o.optString("floor"),
            place = o.optString("place"),
            pwEnc = o.optString("pw"),
            memo = o.optString("memo"),
            scope = o.optString("scope", "mine"),
            updated = o.optLong("updated")
        )
    }
}

/** 내 폰에만 저장 (나만 보기) */
class Store(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("toilets", Context.MODE_PRIVATE)

    fun all(): List<Toilet> {
        val arr = try {
            JSONArray(prefs.getString("list", "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        return (0 until arr.length()).map { Toilet.from(arr.getJSONObject(it)) }
    }

    private fun saveAll(list: List<Toilet>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    fun upsert(t: Toilet) {
        t.updated = System.currentTimeMillis()
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == t.id }
        if (i >= 0) list[i] = t else list.add(t)
        saveAll(list)
    }

    fun delete(id: String) = saveAll(all().filter { it.id != id })
}

/** 비밀번호는 폰 보안 영역(키스토어) 키로 암호화해서 저장 */
object Crypto {
    private const val ALIAS = "toilet_pw_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun enc(plain: String): String {
        if (plain.isEmpty()) return ""
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun dec(s: String): String {
        if (s.isEmpty()) return ""
        return try {
            val parts = s.split(":")
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val data = Base64.decode(parts[1], Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(c.doFinal(data), Charsets.UTF_8)
        } catch (e: Exception) {
            "(읽을 수 없음)"
        }
    }
}
