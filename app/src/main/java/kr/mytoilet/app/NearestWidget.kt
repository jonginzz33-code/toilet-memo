package kr.mytoilet.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

/**
 * 홈 화면 위젯: 가장 가까운 내 화장실 + 비밀번호.
 * 위치는 앱이 마지막으로 위치를 받았을 때 기준 (위젯이 따로 위치를 찾지 않아 배터리 안 씀).
 */
class NearestWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, views(ctx))
    }

    companion object {
        private const val PREF = "widget"

        /** 앱이 가장 가까운 화장실을 저장하고 위젯을 다시 그림 */
        fun save(ctx: Context, t: Toilet?, dist: Double?) {
            val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            if (t == null) p.clear()
            else p.putString("name", t.title())
                .putString("sub", "${Geo.fmt(dist ?: 0.0)} · ${t.place.ifBlank { "위치 설명 없음" }}")
                .putString("pw", t.pw)
                .putLong("at", System.currentTimeMillis())
            p.apply()
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, NearestWidget::class.java))
            if (ids.isNotEmpty()) for (id in ids) mgr.updateAppWidget(id, views(ctx))
        }

        private fun views(ctx: Context): RemoteViews {
            val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val v = RemoteViews(ctx.packageName, R.layout.widget_nearest)
            val name = p.getString("name", null)
            if (name == null) {
                v.setTextViewText(R.id.w_title, "🚻 나만의 화장실")
                v.setTextViewText(R.id.w_name, "앱을 한 번 열어 주세요")
                v.setTextViewText(R.id.w_sub, "내 위치에서 가장 가까운 화장실이 여기에 나와요")
                v.setViewVisibility(R.id.w_pw_box, View.GONE)
            } else {
                val at = p.getLong("at", 0L)
                val time = java.text.SimpleDateFormat("a h:mm", java.util.Locale.KOREA).format(java.util.Date(at))
                v.setTextViewText(R.id.w_title, "🚻 가장 가까운 화장실 · $time 위치 기준")
                v.setTextViewText(R.id.w_name, name)
                v.setTextViewText(R.id.w_sub, p.getString("sub", ""))
                val pw = p.getString("pw", "").orEmpty()
                v.setViewVisibility(R.id.w_pw_box, View.VISIBLE)
                v.setTextViewText(R.id.w_pw, "🔑 " + pw.ifBlank { "비밀번호 없음" })
            }
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            v.setOnClickPendingIntent(R.id.w_root, open)
            return v
        }
    }
}
