package kr.mytoilet.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * 공중화장실 데이터: GitHub가 매달 공공 API에서 받아 둔 파일을 내려받아 폰에 저장해 씀.
 * 로그인·관리자 작업 없이 누구나 볼 수 있음. 7일마다 새 파일이 있는지 확인.
 */
object PublicData {
    private const val URL_FILE =
        "https://github.com/${BuildConfig.GITHUB_REPO}/releases/download/public-data/public-toilets.json.gz"
    private const val REFRESH_MS = 7L * 24 * 60 * 60 * 1000

    lateinit var dir: File
    private val lock = Mutex()
    private var byCell: Map<String, List<PublicToilet>>? = null
    private var failedAt = 0L

    private val file get() = File(dir, "public-toilets.json.gz")

    /** 그 칸의 공중화장실 (처음 한 번은 내려받느라 시간이 걸림) */
    suspend fun cell(cell: String): List<PublicToilet> {
        val idx = byCell ?: load()
        return idx[cell] ?: emptyList()
    }

    /** 폰에 받아 둔 파일이 있는지 */
    val ready: Boolean get() = byCell != null

    private suspend fun load(): Map<String, List<PublicToilet>> = lock.withLock {
        byCell?.let { return it }
        // 받기에 실패했으면 10분 동안은 다시 시도하지 않음 (그동안은 서버 데이터를 씀)
        if (System.currentTimeMillis() - failedAt < 10 * 60 * 1000) throw IllegalStateException("잠시 후 다시")
        try { withContext(Dispatchers.IO) {
            val f = file
            val stale = !f.exists() || System.currentTimeMillis() - f.lastModified() > REFRESH_MS
            if (stale) {
                try { download(f) } catch (e: Exception) { if (!f.exists()) throw e }
            }
            val idx = read(f)
            byCell = idx
            idx
        } } catch (e: Exception) {
            failedAt = System.currentTimeMillis()
            throw e
        }
    }

    private fun download(f: File) {
        var url = URL_FILE
        var con: HttpURLConnection
        var hops = 0
        while (true) {
            con = URL(url).openConnection() as HttpURLConnection
            con.instanceFollowRedirects = false
            con.connectTimeout = 15000
            con.readTimeout = 60000
            val code = con.responseCode
            if (code in 300..399 && hops < 5) {
                url = con.getHeaderField("Location"); con.disconnect(); hops++; continue
            }
            if (code !in 200..299) { con.disconnect(); throw IllegalStateException("공중화장실 파일 HTTP $code") }
            break
        }
        val tmp = File(f.parentFile, f.name + ".tmp")
        con.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
        con.disconnect()
        read(tmp) // 깨진 파일이면 여기서 실패
        tmp.renameTo(f)
    }

    private fun read(f: File): Map<String, List<PublicToilet>> {
        val text = GZIPInputStream(f.inputStream()).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONObject(text).getJSONArray("items")
        val out = HashMap<String, MutableList<PublicToilet>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val fl = o.optJSONArray("flags")
            val t = PublicToilet(
                id = o.optString("id"),
                name = o.optString("name", "공중화장실"),
                addr = o.optString("addr"),
                lat = o.getDouble("lat"),
                lon = o.getDouble("lon"),
                open = o.optString("open"),
                flags = if (fl == null) emptyList() else List(fl.length()) { fl.optString(it) }
            )
            out.getOrPut(Geo.cell(t.lat, t.lon)) { ArrayList() }.add(t)
        }
        return out
    }
}
