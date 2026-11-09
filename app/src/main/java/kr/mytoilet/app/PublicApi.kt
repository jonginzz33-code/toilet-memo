package kr.mytoilet.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 행정안전부 공중화장실 API (공공데이터포털) */
object PublicApi {
    // TODO: 플레이스토어 올리기 전에 GitHub 비밀값(TOILET_API_KEY)으로 옮기기
    const val KEY = "fa4fc859cd80c582d1cbf58943b918023182ed5d6e5f8d2f00b40dfdbcc0e9da"
    const val DEFAULT_URL = "https://apis.data.go.kr/1741000/public_restroom_info_v2/info_v2"

    suspend fun fetchRaw(baseUrl: String, page: Int, rows: Int): String = withContext(Dispatchers.IO) {
        val sep = if (baseUrl.contains("?")) "&" else "?"
        val url = "$baseUrl${sep}serviceKey=$KEY&pageNo=$page&numOfRows=$rows&type=json&returnType=json&_type=json"
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 15000
        con.readTimeout = 60000
        try {
            val code = con.responseCode
            val stream = if (code in 200..299) con.inputStream else con.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
            if (code !in 200..299) "HTTP $code\n$body" else body
        } finally {
            con.disconnect()
        }
    }

    /** 응답에서 항목 목록 찾기 (구조가 달라도 찾아냄) */
    fun findItems(any: Any?): JSONArray? {
        when (any) {
            is JSONArray -> {
                if (any.length() > 0 && any.opt(0) is JSONObject && (any.getJSONObject(0).length() >= 3)) return any
                for (i in 0 until any.length()) findItems(any.opt(i))?.let { return it }
            }
            is JSONObject -> {
                val keys = any.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    findItems(any.opt(k))?.let { return it }
                }
            }
        }
        return null
    }

    fun findTotal(any: Any?): Int? {
        if (any is JSONObject) {
            val keys = any.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k.equals("totalCount", true) || k.equals("total_count", true)) {
                    return any.optString(k).toIntOrNull()
                }
                findTotal(any.opt(k))?.let { return it }
            }
        } else if (any is JSONArray) {
            for (i in 0 until any.length()) findTotal(any.opt(i))?.let { return it }
        }
        return null
    }

    private fun pick(o: JSONObject, prefer: List<Regex>, fallback: List<Regex>, exclude: Regex? = null): String {
        val keys = o.keys().asSequence().toList()
        for (list in listOf(prefer, fallback)) for (r in list) {
            val k = keys.firstOrNull { r.containsMatchIn(it) && (exclude == null || !exclude.containsMatchIn(it)) && o.optString(it).isNotBlank() }
            if (k != null) return o.optString(k).trim()
        }
        return ""
    }

    private fun yes(o: JSONObject, r: Regex): Boolean {
        val keys = o.keys().asSequence().filter { r.containsMatchIn(it) }
        return keys.any {
            val v = o.optString(it).trim()
            v.isNotEmpty() && v != "0" && !v.equals("N", true) && v != "없음" && v != "미설치" && !v.equals("null", true)
        }
    }

    fun parse(o: JSONObject): PublicToilet? {
        val lat = pick(o, listOf(Regex("(?i)wgs84.*lat"), Regex("위도")), listOf(Regex("(?i)lat"))).toDoubleOrNull()
        val lon = pick(o, listOf(Regex("(?i)wgs84.*(lot|lon|lng)"), Regex("경도")), listOf(Regex("(?i)(lot|lon|lng)")), Regex("(?i)lotno|lotn")).toDoubleOrNull()
        if (lat == null || lon == null || lat < 32 || lat > 39.5 || lon < 124 || lon > 132) return null
        val name = pick(o, listOf(Regex("(?i)(rstrm|tlt|toilet).*nm"), Regex("화장실명")), listOf(Regex("(?i)nm$"), Regex("(?i)name"), Regex("명$")))
            .ifBlank { "공중화장실" }
        val addr = pick(o, listOf(Regex("(?i)road.*addr"), Regex("도로명")), listOf(Regex("(?i)addr"), Regex("주소")))
        val open = pick(o, listOf(Regex("(?i)(opn|open).*(hr|tm|time)"), Regex("개방시간")), listOf(Regex("(?i)opn"), Regex("개방")))
        val id = pick(o, listOf(Regex("(?i)mng.*no"), Regex("관리번호")), listOf(Regex("(?i)^id$"), Regex("(?i)_id$")))
            .ifBlank { "${name}_${"%.5f".format(lat)}_${"%.5f".format(lon)}".hashCode().toString() }
        val flags = mutableListOf<String>()
        if (yes(o, Regex("(?i)(dspsn|disab|장애인)"))) flags.add("장애인용")
        if (yes(o, Regex("(?i)(diaper|기저귀)"))) flags.add("기저귀 교환대")
        if (yes(o, Regex("(?i)(emg|emrg|bell|비상벨)"))) flags.add("비상벨")
        if (yes(o, Regex("(?i)(cctv)"))) flags.add("CCTV")
        return PublicToilet(id.replace("/", "_"), name, addr, lat, lon, open, flags)
    }

    /** 전체 받기 (진행 상황 알림) */
    suspend fun fetchAll(baseUrl: String, onProgress: (Int, Int) -> Unit): List<PublicToilet> {
        val rows = 1000
        val out = ArrayList<PublicToilet>()
        var page = 1
        var total = -1
        while (true) {
            val raw = fetchRaw(baseUrl, page, rows)
            val json: Any = try {
                if (raw.trim().startsWith("[")) JSONArray(raw) else JSONObject(raw)
            } catch (e: Exception) {
                throw IllegalStateException("응답을 읽지 못했어요: " + raw.take(200))
            }
            if (total < 0) total = findTotal(json) ?: -1
            val items = findItems(json) ?: break
            if (items.length() == 0) break
            for (i in 0 until items.length()) items.optJSONObject(i)?.let { parse(it)?.let(out::add) }
            onProgress(out.size, total)
            if (items.length() < rows) break
            if (total in 1..(page * rows)) break
            page++
            if (page > 200) break
        }
        return out.distinctBy { it.id }
    }
}
