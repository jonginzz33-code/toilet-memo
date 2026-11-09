package kr.mytoilet.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.util.UUID

/** 제휴 가게 (개방화장실). 관리자만 등록·수정, 모든 사용자에게 보임 */
data class Partner(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var lat: Double = 0.0,
    var lon: Double = 0.0,
    var addr: String = "",
    var place: String = "",          // 화장실 위치 설명
    var open: String = "",           // 개방 시간
    var rule: String = "",           // 이용 규칙 (맨 위에 크게)
    var pwMode: String = "staff",    // pw: 번호 입력 / staff: 직원에게 문의
    var pw: String = "",
    var pwNear: Boolean = true,      // 번호는 매장 50m 안에서만 보이기
    var coupon: Boolean = false,
    var couponText: String = "",
    var img: String = "",            // 작은 사진 (JPEG base64)
    var updated: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "name" to name, "lat" to lat, "lon" to lon, "addr" to addr, "place" to place, "open" to open,
        "rule" to rule, "pwMode" to pwMode, "pw" to pw, "pwNear" to pwNear, "coupon" to coupon,
        "couponText" to couponText, "img" to img, "updated" to updated
    )

    /** 사용불가 신고·다녀왔어요 등에 쓰는 id (공중화장실과 겹치지 않게) */
    val statusId get() = "partner_$id"

    fun bitmap(): Bitmap? = Partners.decode(img)

    companion object {
        fun from(id: String, m: Map<String, Any?>) = Partner(
            id = id,
            name = m["name"] as? String ?: "",
            lat = (m["lat"] as? Number)?.toDouble() ?: 0.0,
            lon = (m["lon"] as? Number)?.toDouble() ?: 0.0,
            addr = m["addr"] as? String ?: "",
            place = m["place"] as? String ?: "",
            open = m["open"] as? String ?: "",
            rule = m["rule"] as? String ?: "",
            pwMode = m["pwMode"] as? String ?: "staff",
            pw = m["pw"] as? String ?: "",
            pwNear = m["pwNear"] as? Boolean ?: true,
            coupon = m["coupon"] as? Boolean ?: false,
            couponText = m["couponText"] as? String ?: "",
            img = m["img"] as? String ?: "",
            updated = (m["updated"] as? Number)?.toLong() ?: 0L
        )
    }
}

object Partners {
    private val db get() = FirebaseFirestore.getInstance()
    private val bmpCache = HashMap<String, Bitmap?>()

    suspend fun all(): List<Partner> =
        db.collection("partners").get().await().documents.map { Partner.from(it.id, it.data ?: emptyMap()) }

    suspend fun save(p: Partner) {
        p.updated = System.currentTimeMillis()
        db.collection("partners").document(p.id).set(p.toMap()).await()
    }

    suspend fun delete(p: Partner) {
        db.collection("partners").document(p.id).delete().await()
    }

    // ---------- 사진: 가운데를 정사각형으로 잘라 작게 줄임 ----------

    fun loadPhoto(ctx: Context, uri: Uri, size: Int = 192): String? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size * 2 && bounds.outHeight / (sample * 2) >= size * 2) sample *= 2
        val src = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalStateException("사진을 열 수 없어요")
        val side = minOf(src.width, src.height)
        val sq = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val small = Bitmap.createScaledBitmap(sq, size, size, true)
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 82, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (_: Exception) {
        null
    }

    fun decode(b64: String): Bitmap? {
        if (b64.isBlank()) return null
        return bmpCache.getOrPut(b64.hashCode().toString() + b64.length) {
            try { Base64.decode(b64, Base64.DEFAULT).let { BitmapFactory.decodeByteArray(it, 0, it.size) } } catch (_: Exception) { null }
        }
    }

    // ---------- 통계: 폰에 모았다가 한 번에 올림 (서버 쓰기 아끼기) ----------

    const val VIEW = "views"; const val OPEN = "opens"; const val ROUTE = "routes"; const val VISIT = "visits"; const val COUPON = "coupons"
    private val pending = HashMap<String, HashMap<String, Long>>()   // 가게 id → (항목 → 횟수)
    private val viewedThisSession = HashSet<String>()

    fun count(id: String, what: String) {
        synchronized(pending) { pending.getOrPut(id) { HashMap() }.merge(what, 1L, Long::plus) }
    }

    /** 지도에 보인 건 앱 한 번 켤 때 가게당 한 번만 셈 */
    fun countView(id: String) {
        if (viewedThisSession.add(id)) count(id, VIEW)
    }

    private fun month(): String = java.text.SimpleDateFormat("yyyyMM", java.util.Locale.KOREA).format(java.util.Date())

    /** 모아 둔 횟수를 서버에 올림 (앱이 뒤로 갈 때) */
    suspend fun flush() {
        val copy = synchronized(pending) { val c = pending.mapValues { HashMap(it.value) }; pending.clear(); c }
        if (copy.isEmpty()) return
        val m = month()
        val batch = db.batch()
        for ((id, counts) in copy) {
            val data = HashMap<String, Any>()
            counts.forEach { (k, v) -> data[k] = FieldValue.increment(v) }
            data["partnerId"] = id
            data["month"] = m
            batch.set(db.collection("partnerStats").document("${id}_$m"), data, SetOptions.merge())
        }
        try { batch.commit().await() } catch (_: Exception) {
            // 실패하면 다음에 다시
            synchronized(pending) { copy.forEach { (id, c) -> c.forEach { (k, v) -> pending.getOrPut(id) { HashMap() }.merge(k, v, Long::plus) } } }
        }
    }

    suspend fun stats(id: String): Map<String, Long> {
        val d = db.collection("partnerStats").document("${id}_${month()}").get().await()
        return listOf(VIEW, OPEN, ROUTE, VISIT, COUPON).associateWith { (d.getLong(it) ?: 0L) }
    }
}
