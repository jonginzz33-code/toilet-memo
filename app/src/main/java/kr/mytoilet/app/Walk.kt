package kr.mytoilet.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 걷는 길 찾기 (오픈스트리트맵 무료 길찾기, 키 필요 없음) */
object Walk {
    data class Route(val points: List<Pair<Double, Double>>, val meters: Double, val seconds: Double)

    suspend fun route(sLat: Double, sLon: Double, eLat: Double, eLon: Double): Route? = withContext(Dispatchers.IO) {
        val u = "https://routing.openstreetmap.de/routed-foot/route/v1/driving/$sLon,$sLat;$eLon,$eLat?overview=full&geometries=geojson"
        val con = URL(u).openConnection() as HttpURLConnection
        con.setRequestProperty("User-Agent", "MyToiletApp/1.0 (kr.mytoilet.app)")
        con.connectTimeout = 8000
        con.readTimeout = 10000
        try {
            if (con.responseCode != 200) return@withContext null
            val o = JSONObject(con.inputStream.bufferedReader().readText())
            val r = o.optJSONArray("routes")?.optJSONObject(0) ?: return@withContext null
            val c = r.getJSONObject("geometry").getJSONArray("coordinates")
            val pts = List(c.length()) { c.getJSONArray(it).let { p -> p.getDouble(1) to p.getDouble(0) } }
            if (pts.size < 2) null else Route(pts, r.optDouble("distance"), r.optDouble("duration"))
        } finally {
            con.disconnect()
        }
    }
}
