package kr.mytoilet.app

import android.app.Application
import android.graphics.Typeface
import com.kakao.vectormap.KakaoMapSdk

class App : Application() {
    override fun onCreate() {
        super.onCreate()
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
