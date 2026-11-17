package kr.mytoilet.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * '우리 동네' 가게: GitHub가 분기마다 소상공인 상권정보로 만들어 둔 0.2도 칸 파일을 내 주변만 받아 씀.
 * 받은 칸은 폰에 30일 보관.
 */
object StoreData {
    data class Store(val id: String, val name: String, val kind: String, val sub: String,
                     val lat: Double, val lon: Double, val addr: String)

    class Area(val stores: List<Store>, val newIds: Set<String>, val gone: List<Store>, val stdrYm: String, val since: String)

    val KINDS = listOf("cafe" to "☕ 카페", "conv" to "🏪 편의점", "food" to "🍚 음식점", "bakery" to "🥐 빵집",
        "pharm" to "💊 약국", "glasses" to "👓 안경점", "flower" to "💐 꽃집")
    fun emoji(kind: String) = KINDS.firstOrNull { it.first == kind }?.second?.substringBefore(' ') ?: "🏬"

    private const val BASE = "https://github.com/${BuildConfig.GITHUB_REPO}/releases/download/store-data/"
    private const val KEEP_MS = 30L * 24 * 60 * 60 * 1000
    private const val TILE = 0.2
    lateinit var dir: File

    private fun tileName(lat: Double, lon: Double) =
        "tile_${Math.floor(lat / TILE).toInt()}_${Math.floor(lon / TILE).toInt()}.json.gz"

    /** (lat, lon) 둘레 radiusM 안을 덮는 칸들을 받아 합침 */
    suspend fun around(lat: Double, lon: Double, radiusM: Double): Area = withContext(Dispatchers.IO) {
        val dLat = radiusM / 111000.0
        val dLon = radiusM / (111000.0 * Math.cos(Math.toRadians(lat)))
        val names = linkedSetOf<String>()
        for (y in listOf(lat - dLat, lat, lat + dLat)) for (x in listOf(lon - dLon, lon, lon + dLon)) names.add(tileName(y, x))
        val stores = ArrayList<Store>(); val newIds = HashSet<String>(); val gone = ArrayList<Store>()
        var stdr = ""; var since = ""
        for (n in names) {
            val o = load(n) ?: continue
            stdr = o.optString("stdrYm"); since = o.optString("since").takeIf { it != "null" } ?: ""
            val arr = o.getJSONArray("stores")
            for (i in 0 until arr.length()) stores.add(store(arr.getJSONArray(i)))
            val nw = o.optJSONArray("new"); if (nw != null) for (i in 0 until nw.length()) newIds.add(nw.getString(i))
            val gn = o.optJSONArray("gone"); if (gn != null) for (i in 0 until gn.length()) gone.add(store(gn.getJSONArray(i)))
        }
        Area(stores, newIds, gone, stdr, since)
    }

    private fun store(a: JSONArray) = Store(a.getString(0), a.getString(1), a.getString(2), a.getString(3),
        a.getDouble(4), a.getDouble(5), a.optString(6))

    /** 칸 파일: 폰에 있으면 그걸, 없거나 30일 지났으면 새로 받음. 바다처럼 파일이 없는 칸은 null */
    private fun load(name: String): JSONObject? {
        val f = File(dir, "stores_$name")
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > KEEP_MS) {
            try { download(BASE + name, f) } catch (_: Exception) { if (!f.exists()) return null }
        }
        return try {
            JSONObject(GZIPInputStream(f.inputStream()).bufferedReader(Charsets.UTF_8).use { it.readText() })
        } catch (_: Exception) { f.delete(); null }
    }

    private fun download(start: String, f: File) {
        var url = start
        repeat(5) {
            val con = URL(url).openConnection() as HttpURLConnection
            con.instanceFollowRedirects = false
            con.connectTimeout = 15000; con.readTimeout = 60000
            when (con.responseCode) {
                in 300..399 -> { url = con.getHeaderField("Location"); con.disconnect() }
                in 200..299 -> {
                    val tmp = File(f.parentFile, f.name + ".tmp")
                    con.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                    con.disconnect(); tmp.renameTo(f); return
                }
                else -> { con.disconnect(); throw IllegalStateException("HTTP ${con.responseCode}") }
            }
        }
        throw IllegalStateException("too many redirects")
    }
}
