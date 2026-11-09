package kr.mytoilet.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONArray
import org.json.JSONObject

/**
 * 홈 화면 위젯: 가장 가까운 내 화장실 + 비밀번호.
 * 자동으로 위치를 불러오지 않고, 오른쪽 위 '새로고침'을 누를 때만 지금 위치로 다시 계산함.
 * '앱 열기'를 누르면 앱이 열리면서 차량/도보 길찾기 고르는 창이 뜸.
 */
class NearestWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, views(ctx))
    }

    companion object {
        private const val PREF = "widget"
        const val EXTRA_LAT = "route_lat"
        const val EXTRA_LON = "route_lon"
        const val EXTRA_NAME = "route_name"

        /** 앱이 내 화장실 목록을 저장해 둠 (새로고침 때 이 목록에서 가장 가까운 곳을 고름) */
        fun saveList(ctx: Context, list: List<Toilet>) {
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().put("n", it.title()).put("y", it.lat).put("x", it.lon).put("p", it.place).put("w", it.pw))
            }
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("list", arr.toString()).apply()
        }

        /** 지금 위치로 가장 가까운 곳을 골라 위젯을 다시 그림 */
        fun refresh(ctx: Context, lat: Double, lon: Double) {
            val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val arr = try { JSONArray(p.getString("list", "[]")) } catch (_: Exception) { JSONArray() }
            val best = (0 until arr.length()).map { arr.getJSONObject(it) }
                .minByOrNull { Geo.dist(lat, lon, it.getDouble("y"), it.getDouble("x")) }
            val e = p.edit()
            if (best == null) e.remove("name")
            else e.putString("name", best.getString("n"))
                .putString("sub", "${Geo.fmt(Geo.dist(lat, lon, best.getDouble("y"), best.getDouble("x")))} · ${best.optString("p").ifBlank { "위치 설명 없음" }}")
                .putString("pw", best.optString("w"))
                .putFloat("y", best.getDouble("y").toFloat()).putFloat("x", best.getDouble("x").toFloat())
                .putLong("at", System.currentTimeMillis())
            e.apply()
            redraw(ctx)
        }

        fun redraw(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, NearestWidget::class.java))
            for (id in ids) mgr.updateAppWidget(id, views(ctx))
        }

        private fun views(ctx: Context): RemoteViews {
            val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val v = RemoteViews(ctx.packageName, R.layout.widget_nearest)
            val name = p.getString("name", null)
            val openIntent = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (name == null) {
                v.setTextViewText(R.id.w_title, "🚻 나만의 화장실")
                v.setTextViewText(R.id.w_name, "⟳ 새로고침을 눌러 주세요")
                v.setTextViewText(R.id.w_sub, "지금 위치에서 가장 가까운 내 화장실이 나와요")
                v.setViewVisibility(R.id.w_pw_box, View.GONE)
            } else {
                val at = p.getLong("at", 0L)
                val time = java.text.SimpleDateFormat("a h:mm", java.util.Locale.KOREA).format(java.util.Date(at))
                v.setTextViewText(R.id.w_title, "🚻 가장 가까운 화장실 · $time 기준")
                v.setTextViewText(R.id.w_name, name)
                v.setTextViewText(R.id.w_sub, p.getString("sub", ""))
                v.setViewVisibility(R.id.w_pw_box, View.VISIBLE)
                v.setTextViewText(R.id.w_pw, "🔑 " + p.getString("pw", "").orEmpty().ifBlank { "비밀번호 없음" })
                // 앱 열기 → 길찾기 고르기 창
                val route = Intent(openIntent)
                    .putExtra(EXTRA_LAT, p.getFloat("y", 0f).toDouble())
                    .putExtra(EXTRA_LON, p.getFloat("x", 0f).toDouble())
                    .putExtra(EXTRA_NAME, name)
                v.setOnClickPendingIntent(R.id.w_open, PendingIntent.getActivity(
                    ctx, 1, route, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(
                ctx, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            // 새로고침: 화면 없는 짧은 창이 위치를 한 번 받아서 계산하고 바로 닫힘
            v.setOnClickPendingIntent(R.id.w_refresh, PendingIntent.getActivity(
                ctx, 2, Intent(ctx, WidgetRefreshActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            return v
        }
    }
}

/** 위젯 새로고침용: 보이지 않는 창. 지금 위치를 한 번 받고 닫힘 */
class WidgetRefreshActivity : Activity() {
    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ok = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!ok) {
            Toast.makeText(this, "앱을 열어 위치 권한을 허용해 주세요", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { l ->
                if (l != null) {
                    NearestWidget.refresh(this, l.latitude, l.longitude)
                    Toast.makeText(this, "새로고침했어요", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(this, "위치를 못 찾았어요. GPS를 켜 주세요", Toast.LENGTH_SHORT).show()
                finish()
            }
            .addOnFailureListener { finish() }
    }
}
