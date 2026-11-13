package kr.mytoilet.app

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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 내 화장실 / 그룹 화장실 */
data class Toilet(
    var id: String = UUID.randomUUID().toString(),
    var lat: Double = 0.0,
    var lon: Double = 0.0,
    var building: String = "",
    var floor: String = "",
    var place: String = "",
    var pw: String = "",          // 화면에서 쓰는 비밀번호(평문). 여자 칸이 따로 있으면 남자 화장실
    var pw2: String = "",         // 여자 화장실 비밀번호 (비어 있으면 pw 하나로 같이 씀)
    var memo: String = "",
    var paper: String = "",       // 휴지: 있음 / 없음 / "" (모름)
    var clean: String = "",       // 청결: 깨끗 / 보통 / 별로 / "" (모름)
    var scope: String = "mine",   // mine / group
    var groupId: String = "",
    var groupName: String = "",
    var updated: Long = System.currentTimeMillis(),
    var by: String = ""
) {
    fun title(): String = if (floor.isBlank()) building else "$building $floor"

    fun toMap(): Map<String, Any> = mapOf(
        "id" to id, "lat" to lat, "lon" to lon, "building" to building, "floor" to floor,
        "place" to place, "pw" to pw, "pw2" to pw2, "memo" to memo, "paper" to paper, "clean" to clean, "scope" to scope, "groupId" to groupId,
        "updated" to updated, "by" to by
    )

    companion object {
        fun fromMap(m: Map<String, Any?>): Toilet = Toilet(
            id = m["id"] as? String ?: UUID.randomUUID().toString(),
            lat = (m["lat"] as? Number)?.toDouble() ?: 0.0,
            lon = (m["lon"] as? Number)?.toDouble() ?: 0.0,
            building = m["building"] as? String ?: "",
            floor = m["floor"] as? String ?: "",
            place = m["place"] as? String ?: "",
            pw = m["pw"] as? String ?: "",
            pw2 = m["pw2"] as? String ?: "",
            paper = m["paper"] as? String ?: "",
            clean = m["clean"] as? String ?: "",
            memo = m["memo"] as? String ?: "",
            scope = m["scope"] as? String ?: "mine",
            groupId = m["groupId"] as? String ?: "",
            updated = (m["updated"] as? Number)?.toLong() ?: 0L,
            by = m["by"] as? String ?: ""
        )
    }
}

/** 공중화장실 */
data class PublicToilet(
    val id: String,
    val name: String,
    val addr: String,
    val lat: Double,
    val lon: Double,
    val open: String = "",
    val flags: List<String> = emptyList()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "i" to id, "n" to name, "a" to addr, "y" to lat, "x" to lon, "o" to open, "f" to flags
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(m: Map<String, Any?>): PublicToilet = PublicToilet(
            id = m["i"] as? String ?: "",
            name = m["n"] as? String ?: "공중화장실",
            addr = m["a"] as? String ?: "",
            lat = (m["y"] as? Number)?.toDouble() ?: 0.0,
            lon = (m["x"] as? Number)?.toDouble() ?: 0.0,
            open = m["o"] as? String ?: "",
            flags = (m["f"] as? List<String>) ?: emptyList()
        )
    }
}

data class Group(
    val id: String, val name: String, val owner: String, val code: String, val members: List<String>,
    val subs: List<String> = emptyList(),          // 부방장
    val names: Map<String, String> = emptyMap(),   // 멤버 이름
    val maxMembers: Int = 0                         // 0 이면 기본값(총관리자 설정)
) {
    fun isOwner(u: String?) = u != null && u == owner
    fun isSub(u: String?) = u != null && u in subs
    fun canManage(u: String?) = isOwner(u) || isSub(u)
    fun nameOf(u: String) = names[u]?.takeIf { it.isNotBlank() } ?: "멤버"
    fun limit(defaultMax: Int) = if (maxMembers > 0) maxMembers else defaultMax
}

/** 그룹 가입 요청 */
data class JoinRequest(val uid: String, val name: String, val at: Long)

/** 총관리자가 정하는 그룹 설정 */
data class GroupConfig(val defaultMax: Int = 10, val maxGroups: Int = 3)

data class MemoItem(val id: String, val toiletId: String, val uid: String, val nick: String, val text: String, val at: Long)

/** 내 폰에 저장 (로그인 안 했을 때 + 오프라인 대비) */
class LocalStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("toilets", Context.MODE_PRIVATE)

    fun all(): List<Toilet> {
        val arr = try { JSONArray(prefs.getString("list", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Toilet(
                id = o.optString("id"), lat = o.optDouble("lat"), lon = o.optDouble("lon"),
                building = o.optString("building"), floor = o.optString("floor"), place = o.optString("place"),
                pw = Crypto.dec(o.optString("pw")), pw2 = Crypto.dec(o.optString("pw2")), memo = o.optString("memo"),
                paper = o.optString("paper"), clean = o.optString("clean"),
                scope = "mine", updated = o.optLong("updated")
            )
        }
    }

    fun saveAll(list: List<Toilet>) {
        val arr = JSONArray()
        list.filter { it.scope == "mine" }.forEach {
            arr.put(JSONObject().put("id", it.id).put("lat", it.lat).put("lon", it.lon)
                .put("building", it.building).put("floor", it.floor).put("place", it.place)
                .put("pw", Crypto.enc(it.pw)).put("pw2", Crypto.enc(it.pw2)).put("memo", it.memo).put("paper", it.paper).put("clean", it.clean).put("updated", it.updated))
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    fun upsert(t: Toilet) {
        val l = all().toMutableList()
        val i = l.indexOfFirst { it.id == t.id }
        if (i >= 0) l[i] = t else l.add(t)
        saveAll(l)
    }

    fun delete(id: String) = saveAll(all().filter { it.id != id })
}

/** 설정 값 */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var character: Int
        get() = p.getInt("character", 0)
        set(v) = p.edit().putInt("character", v).apply()
    var circleOnly: Boolean
        get() = p.getBoolean("circleOnly", true)
        set(v) = p.edit().putBoolean("circleOnly", v).apply()
    var showPublic: Boolean
        get() = p.getBoolean("showPublic", true)
        set(v) = p.edit().putBoolean("showPublic", v).apply()
    /** 총관리자가 관리자 화면으로 볼지 (끄면 일반 사용자 화면) */
    /** 마지막으로 본 공지 번호 (같은 공지는 한 번만) */
    var noticeSeen: Long
        get() = p.getLong("noticeSeen", 0L)
        set(v) = p.edit().putLong("noticeSeen", v).apply()
    var adminView: Boolean
        get() = p.getBoolean("adminView", true)
        set(v) = p.edit().putBoolean("adminView", v).apply()
    // 관리자 퀵바: 지도에 보일 종류
    var fMine: Boolean
        get() = p.getBoolean("fMine", true)
        set(v) = p.edit().putBoolean("fMine", v).apply()
    var fGroup: Boolean
        get() = p.getBoolean("fGroup", true)
        set(v) = p.edit().putBoolean("fGroup", v).apply()
    var fPublic: Boolean
        get() = p.getBoolean("fPublic", true)
        set(v) = p.edit().putBoolean("fPublic", v).apply()
    var fPartner: Boolean
        get() = p.getBoolean("fPartner", true)
        set(v) = p.edit().putBoolean("fPartner", v).apply()
    var termsAgreed: Boolean
        get() = p.getBoolean("terms", false)
        set(v) = p.edit().putBoolean("terms", v).apply()
    var uploadedLocal: Boolean
        get() = p.getBoolean("uploadedLocal", false)
        set(v) = p.edit().putBoolean("uploadedLocal", v).apply()
    var blocked: Set<String>
        get() = p.getStringSet("blocked", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("blocked", v).apply()
}

/** 비밀번호는 폰 보안 영역(키스토어) 키로 암호화해서 폰에 저장 */
object Crypto {
    private const val ALIAS = "toilet_pw_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun enc(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val out = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(out, Base64.NO_WRAP)
        } catch (e: Exception) { "" }
    }

    fun dec(s: String): String {
        if (s.isEmpty()) return ""
        return try {
            val parts = s.split(":")
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) { "" }
    }
}

object Geo {
    fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    fun fmt(m: Double): String =
        if (m < 1000) "${(m / 10).toInt() * 10}m" else String.format("%.1fkm", m / 1000)

    /** 공중화장실 묶음 칸 (약 5km) */
    fun cell(lat: Double, lon: Double): String =
        "${Math.floor(lat * 20).toInt()}_${Math.floor(lon * 20).toInt()}"

    fun cellsAround(lat: Double, lon: Double, radiusM: Double): List<String> {
        val dLat = radiusM / 111000.0 + 0.05
        val dLon = radiusM / (111000.0 * cos(Math.toRadians(lat))) + 0.05
        val out = mutableListOf<String>()
        var y = Math.floor((lat - dLat) * 20).toInt()
        val y2 = Math.floor((lat + dLat) * 20).toInt()
        while (y <= y2) {
            var x = Math.floor((lon - dLon) * 20).toInt()
            val x2 = Math.floor((lon + dLon) * 20).toInt()
            while (x <= x2) { out.add("${y}_$x"); x++ }
            y++
        }
        return out
    }

    /** 지도·상세용 짧은 날짜: 오늘, 3일 전, 1주 전, 2달 전, 1년 전 */
    fun agoShort(ms: Long): String {
        // 24시간이 아니라 달력 날짜로 셈 (어제 밤에 저장한 건 '어제')
        fun dayStart(t: Long) = java.util.Calendar.getInstance().apply {
            timeInMillis = t; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val days = Math.round((dayStart(System.currentTimeMillis()) - dayStart(ms)) / 86_400_000.0)
        return when {
            days < 1 -> "오늘"
            days == 1L -> "어제"
            days < 7 -> "${days}일 전"
            days < 30 -> "${days / 7}주 전"
            days < 365 -> "${days / 30}달 전"
            else -> "${days / 365}년 전"
        }
    }

    fun date(ms: Long): String = java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.KOREA).format(java.util.Date(ms))

    fun ago(ms: Long): String {
        val m = (System.currentTimeMillis() - ms) / 60000
        return when {
            m < 1 -> "방금"
            m < 60 -> "${m}분 전"
            m < 60 * 24 -> "${m / 60}시간 전"
            else -> "${m / 1440}일 전"
        }
    }
}
