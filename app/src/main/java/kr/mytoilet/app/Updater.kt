package kr.mytoilet.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 새 버전 확인. 내 폰 버전은 앱 안에서 설치, 플레이스토어 버전은 스토어로 안내 */
object Updater {
    data class Info(val versionCode: Int, val name: String, val apkUrl: String)

    const val FIXED_APK = "https://github.com/jonginzz33-code/toilet-memo/releases/latest/download/toilet-memo.apk"

    suspend fun latest(): Info? = withContext(Dispatchers.IO) {
        try {
            val con = URL("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest").openConnection() as HttpURLConnection
            con.setRequestProperty("Accept", "application/vnd.github+json")
            con.connectTimeout = 10000
            val body = con.inputStream.bufferedReader().readText()
            con.disconnect()
            val o = JSONObject(body)
            val vc = Regex("versionCode=(\\d+)").find(o.optString("body"))?.groupValues?.get(1)?.toIntOrNull()
                ?: o.optString("tag_name").removePrefix("build-").toIntOrNull() ?: 0
            var apk = FIXED_APK
            val assets = o.optJSONArray("assets")
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) apk = a.optString("browser_download_url")
            }
            Info(vc, o.optString("name"), apk)
        } catch (e: Exception) {
            null
        }
    }

    fun install(a: Activity, info: Info) {
        if (BuildConfig.UPDATE_MODE == "store") {
            a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${a.packageName}")))
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            a.toast("'이 출처의 앱 허용'을 켜고 다시 눌러 주세요")
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
            return
        }
        val dir = File(a.getExternalFilesDir(null), "updates").apply { mkdirs() }
        val file = File(dir, "toilet-memo.apk")
        val part = File(dir, "toilet-memo.apk.part")
        if (file.exists()) file.delete()

        // 진행률 창
        val act = a as? MainActivity ?: return
        val s = Sheet(act, "새 버전 받는 중")
        val bar = com.google.android.material.progressindicator.LinearProgressIndicator(act).apply { isIndeterminate = true; max = 100 }
        val txt = act.text("연결하는 중…", 14f, C.TEXT2)
        s.add(bar); s.add(txt)
        var cancelled = false
        s.add(act.btn("취소") { cancelled = true; s.dismiss() })
        s.show()

        act.lifecycleScope.launch {
            val ok = try {
                download(info.apkUrl, part, { cancelled }) { done, total ->
                    act.runOnUiThread {
                        if (total > 0) {
                            bar.isIndeterminate = false
                            bar.progress = (done * 100 / total).toInt()
                            txt.text = "받는 중 ${done * 100 / total}% (${mb(done)} / ${mb(total)})"
                        } else txt.text = "받는 중 ${mb(done)}"
                    }
                }
            } catch (e: Exception) {
                if (!cancelled) act.toast("받지 못했어요: ${e.message ?: "인터넷 연결 확인"}")
                false
            }
            if (cancelled) return@launch
            s.dismiss()
            if (!ok) return@launch
            part.renameTo(file)
            val uri = FileProvider.getUriForFile(act, "${act.packageName}.files", file)
            act.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    private fun mb(b: Long) = "%.1fMB".format(b / 1048576.0)

    /**
     * 직접 받기: 끊기면 받은 데까지 이어서 받고, 최대 6번 다시 시도.
     * GitHub 주소는 매번 새로 열어 만료된 임시 주소를 쓰지 않음.
     */
    private suspend fun download(url: String, out: File, cancelled: () -> Boolean, progress: (Long, Long) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            if (out.exists()) out.delete()
            var total = -1L
            var lastErr: Exception? = null
            for (attempt in 0 until 6) {
                if (cancelled()) return@withContext false
                try {
                    val have = out.length()
                    val con = open(url, have)
                    val code = con.responseCode
                    if (code !in listOf(200, 206)) throw IllegalStateException("HTTP $code")
                    val resumed = code == 206 && have > 0
                    if (!resumed && have > 0) out.delete()      // 서버가 이어받기를 안 하면 처음부터
                    val len = con.contentLengthLong
                    if (len > 0) total = (if (resumed) have else 0L) + len
                    con.inputStream.use { inp ->
                        java.io.FileOutputStream(out, resumed).use { fo ->
                            val buf = ByteArray(64 * 1024)
                            var done = out.length()
                            var last = 0L
                            while (true) {
                                if (cancelled()) return@withContext false
                                val n = inp.read(buf)
                                if (n < 0) break
                                fo.write(buf, 0, n)
                                done += n
                                val now = System.currentTimeMillis()
                                if (now - last > 200) { last = now; progress(done, total) }
                            }
                        }
                    }
                    con.disconnect()
                    if (total <= 0 || out.length() >= total) { progress(out.length(), total); return@withContext true }
                } catch (e: Exception) {
                    lastErr = e
                }
                kotlinx.coroutines.delay(1500L * (attempt + 1))
            }
            throw lastErr ?: IllegalStateException("다운로드 실패")
        }

    /** 다른 주소로 넘겨주는 것(GitHub → 파일 서버)을 직접 따라감 */
    private fun open(start: String, from: Long): HttpURLConnection {
        var u = start
        repeat(6) {
            val con = URL(u).openConnection() as HttpURLConnection
            con.instanceFollowRedirects = false
            con.connectTimeout = 15000
            con.readTimeout = 30000
            con.setRequestProperty("User-Agent", "MyToiletApp")
            if (from > 0) con.setRequestProperty("Range", "bytes=$from-")
            val code = con.responseCode
            if (code in 300..399) {
                val loc = con.getHeaderField("Location") ?: throw IllegalStateException("주소 이동 실패")
                con.disconnect()
                u = URL(URL(u), loc).toString()
            } else return con
        }
        throw IllegalStateException("주소 이동이 너무 많아요")
    }
}
