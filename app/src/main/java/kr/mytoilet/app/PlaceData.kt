package kr.mytoilet.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 주차장(전국 한 파일)과 전기차 충전소(0.2도 칸 파일). 파일은 StoreData 와 같은 방식으로 받아 30일 보관 */
object PlaceData {
    data class Park(val name: String, val se: String, val type: String, val spaces: Int, val lat: Double, val lon: Double,
                    val addr: String, val hours: String, val fee: String, val day: String, val phone: String)

    data class Station(val id: String, val name: String, val lat: Double, val lon: Double, val addr: String,
                       val useTime: String, val busi: String, val fast: Int, val slow: Int)

    /** 지금 상태: 빈 충전기 수, 충전 중, 고장·점검 */
    data class Live(val free: Int, val busy: Int, val broken: Int)

    // 공공데이터포털 키 (이미 공개 저장소에 있는 무료 키)
    private const val KEY = "fa4fc859cd80c582d1cbf58943b918023182ed5d6e5f8d2f00b40dfdbcc0e9da"

    private var parks: List<Park>? = null

    suspend fun parksNear(lat: Double, lon: Double, radiusM: Double): List<Park> = withContext(Dispatchers.IO) {
        val all = parks ?: (StoreData.loadFile("park.json.gz")?.getJSONArray("parks")?.let { arr ->
            List(arr.length()) { i -> arr.getJSONArray(i).let { a ->
                Park(a.getString(1), a.getString(2), a.getString(3), a.optInt(4), a.getDouble(5), a.getDouble(6),
                    a.optString(7), a.optString(8), a.optString(9), a.optString(10), a.optString(11))
            } }
        } ?: emptyList()).also { if (it.isNotEmpty()) parks = it }
        all.filter { Geo.dist(lat, lon, it.lat, it.lon) <= radiusM }
    }

    suspend fun stationsNear(lat: Double, lon: Double, radiusM: Double): List<Station> = withContext(Dispatchers.IO) {
        val out = ArrayList<Station>()
        for (n in StoreData.tilesAround(lat, lon, radiusM, "ev_tile_")) {
            val arr = StoreData.loadFile(n)?.optJSONArray("stations") ?: continue
            for (i in 0 until arr.length()) {
                val a = arr.getJSONArray(i)
                val s = Station(a.getString(0), a.getString(1), a.getDouble(2), a.getDouble(3), a.optString(4),
                    a.optString(5), a.optString(6), a.optInt(7), a.optInt(8))
                if (Geo.dist(lat, lon, s.lat, s.lon) <= radiusM) out.add(s)
            }
        }
        out
    }

    /** 충전소들의 지금 상태 (충전소마다 한 번씩 물어봄).
     *  shortcut: 무료 사용량을 아끼려고 가까운 몇 곳만 부름, 사용자가 많아지면 서버에서 모아 두기 */
    suspend fun live(ids: List<String>): Map<String, Live> = withContext(Dispatchers.IO) {
        coroutineScope {
            ids.map { id -> async { id to liveOne(id) } }.awaitAll().mapNotNull { (id, l) -> l?.let { id to it } }.toMap()
        }
    }

    private fun liveOne(id: String): Live? = try {
        val con = URL("https://apis.data.go.kr/B552584/EvCharger/getChargerInfo?serviceKey=$KEY&pageNo=1&numOfRows=100&statId=$id&dataType=JSON")
            .openConnection() as HttpURLConnection
        con.connectTimeout = 8000; con.readTimeout = 12000
        val items = JSONObject(con.inputStream.bufferedReader().use { it.readText() }).optJSONObject("items")?.optJSONArray("item") ?: JSONArray()
        con.disconnect()
        var f = 0; var b = 0; var x = 0
        val seen = HashSet<String>()
        for (i in 0 until items.length()) {
            val c = items.getJSONObject(i)
            if (!seen.add(c.optString("chgerId"))) continue
            // 2 충전대기(빈), 3 충전중, 1 통신이상 4 운영중지 5 점검중, 9 모름
            when (c.optString("stat")) { "2" -> f++; "3" -> b++; "1", "4", "5" -> x++ }
        }
        Live(f, b, x)
    } catch (_: Exception) { null }
}
