package kr.mytoilet.app

import android.app.Application
import android.graphics.Typeface
import com.kakao.vectormap.KakaoMapSdk

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        KakaoMapSdk.init(this, BuildConfig.KAKAO_KEY)
        Fonts.load(this)
    }
}

/** 프리텐다드 글씨체 (빌드 때 내려받은 파일, 없으면 기본 글씨) */
object Fonts {
    var regular: Typeface = Typeface.DEFAULT
    var semi: Typeface = Typeface.DEFAULT_BOLD
    var bold: Typeface = Typeface.DEFAULT_BOLD

    fun load(app: Application) {
        regular = tryLoad(app, "Regular") ?: Typeface.DEFAULT
        semi = tryLoad(app, "SemiBold") ?: Typeface.DEFAULT_BOLD
        bold = tryLoad(app, "Bold") ?: Typeface.DEFAULT_BOLD
    }

    private fun tryLoad(app: Application, w: String): Typeface? = try {
        Typeface.createFromAsset(app.assets, "fonts/Pretendard-$w.otf")
    } catch (e: Exception) {
        null
    }
}

/** 앱이 꺼질 때 오류 내용을 저장해 두고, 다음 실행 때 화면에 보여 줌 */
object CrashLog {
    private const val FILE = "last_crash.txt"

    fun install(app: Application) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                val head = "버전 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} · 스레드 ${t.name}\n\n"
                java.io.File(app.filesDir, FILE).writeText(head + sw.toString().take(6000))
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    /** 저장된 오류를 꺼내고 지움 (없으면 null) */
    fun take(app: android.content.Context): String? {
        val f = java.io.File(app.filesDir, FILE)
        if (!f.exists()) return null
        val txt = try { f.readText() } catch (_: Throwable) { null }
        f.delete()
        return txt
    }
}
