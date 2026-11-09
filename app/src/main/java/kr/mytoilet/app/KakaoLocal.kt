package kr.mytoilet.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 카카오 로컬: 그 자리 건물 이름과 근처 가게로 '건물 이름' 추천 */
object KakaoLocal {
    data class Guess(val address: String, val names: List<String>)

    // 1층에 흔한 가게 종류: 편의점, 카페, 음식점, 은행, 약국, 마트, 병원
    private val CATEGORIES = listOf("CS2", "CE7", "FD6", "BK9", "PM9", "MT1", "HP8")

    private fun get(path: String): JSONObject? {
        val key = BuildConfig.KAKAO_REST_KEY
        if (key.isBlank()) return null
        val con = URL("https://dapi.kakao.com$path").openConnection() as HttpURLConnection
        con.setRequestProperty("Authorization", "KakaoAK $key")
        con.connectTimeout = 8000
        con.readTimeout = 8000
        return try {
            if (con.responseCode != 200) null
            else JSONObject(con.inputStream.bufferedReader(Charsets.UTF_8).readText())
        } catch (_: Exception) {
            null
        } finally {
            con.disconnect()
        }
    }

    suspend fun guess(lat: Double, lon: Double): Guess? = withContext(Dispatchers.IO) {
        if (BuildConfig.KAKAO_REST_KEY.isBlank()) return@withContext null
        coroutineScope {
            val addrJob = async { get("/v2/local/geo/coord2address.json?x=$lon&y=$lat") }
            val placeJobs = CATEGORIES.map { c ->
                async { get("/v2/local/search/category.json?category_group_code=$c&x=$lon&y=$lat&radius=40&sort=distance&size=5") }
            }
            val addr = addrJob.await()?.optJSONArray("documents")?.optJSONObject(0)
            val road = addr?.optJSONObject("road_address")
            val building = road?.optString("building_name").orEmpty().trim()
            val address = road?.optString("address_name")?.takeIf { it.isNotBlank() }
                ?: addr?.optJSONObject("address")?.optString("address_name").orEmpty()

            // 가까운 가게 순으로 (같은 이름은 한 번만)
            val places = placeJobs.awaitAll().flatMap { j ->
                val arr = j?.optJSONArray("documents") ?: return@flatMap emptyList()
                List(arr.length()) { arr.getJSONObject(it) }
            }.map { it.optString("place_name") to (it.optString("distance").toIntOrNull() ?: 999) }
                .filter { it.first.isNotBlank() }
                .sortedBy { it.second }
                .map { it.first.substringBefore(" ").ifBlank { it.first } }
                .distinct()

            val names = buildList {
                if (building.isNotBlank()) add(building)
                places.take(4).forEach { add("$it 건물") }
            }.distinct()
            Guess(address, names)
        }
    }
}
