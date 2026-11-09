package kr.mytoilet.app

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
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
        if (file.exists()) file.delete()
        val dm = a.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("나만의 화장실 업데이트")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(file))
        val id = dm.enqueue(req)
        a.toast("새 버전 받는 중…")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                try { a.unregisterReceiver(this) } catch (_: Exception) {}
                val uri = FileProvider.getUriForFile(a, "${a.packageName}.files", file)
                val i = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                a.startActivity(i)
            }
        }
        ContextCompat.registerReceiver(a, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
    }
}
