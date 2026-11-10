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
import kotlinx.coroutines.launch

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

        /** 앱이 내 화장실·제휴 가게 목록을 저장해 둠 (새로고침 때 이 목록에서 가장 가까운 곳을 고름) */
        fun saveList(ctx: Context, list: List<Toilet>, partners: List<Partner> = emptyList()) {
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().put("n", it.title()).put("y", it.lat).put("x", it.lon).put("p", it.place).put("w", it.pw))
            }
            val parr = JSONArray()
            partners.forEach {
                parr.put(JSONObject().put("n", it.name).put("y", it.lat).put("x", it.lon)
                    .put("o", it.open).put("w", if (it.pwMode == "staff") "직원에게 문의" else ""))
            }
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString("list", arr.toString()).putString("plist", parr.toString()).apply()
        }

        /** 지금 위치로 두 칸(내 화장실 / 공중화장실·제휴) 다시 계산 */
        suspend fun refresh(ctx: Context, lat: Double, lon: Double) {
            val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            fun arr(k: String) = try { JSONArray(p.getString(k, "[]")) } catch (_: Exception) { JSONArray() }
            fun dist(o: JSONObject) = Geo.dist(lat, lon, o.getDouble("y"), o.getDouble("x"))
            val e = p.edit().putLong("at", System.currentTimeMillis())

            // 내 화장실 (내 것 + 그룹)
            val mine = arr("list").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.minByOrNull { dist(it) }
            if (mine == null) e.remove("m_name")
            else e.putString("m_name", mine.getString("n"))
                .putString("m_sub", "${Geo.fmt(dist(mine))} · ${mine.optString("p").ifBlank { "위치 설명 없음" }}")
                .putString("m_pw", mine.optString("w"))
                .putFloat("m_y", mine.getDouble("y").toFloat()).putFloat("m_x", mine.getDouble("x").toFloat())

            // 공중화장실 (2km 안) vs 제휴 가게 중 더 가까운 곳
            val pubs = ArrayList<PublicToilet>()
            try { for (c in Geo.cellsAround(lat, lon, 2000.0)) pubs.addAll(PublicData.cell(c)) } catch (_: Exception) {}
            val pub = pubs.minByOrNull { Geo.dist(lat, lon, it.lat, it.lon) }
            val partner = arr("plist").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.minByOrNull { dist(it) }
            val pd = pub?.let { Geo.dist(lat, lon, it.lat, it.lon) } ?: Double.MAX_VALUE
            val ptd = partner?.let { dist(it) } ?: Double.MAX_VALUE
            when {
                partner != null && ptd <= pd -> e.putString("p_name", partner.getString("n")).putBoolean("p_partner", true)
                    .putString("p_sub", Geo.fmt(ptd) + partner.optString("o").let { if (it.isBlank()) "" else " · $it" })
                    .putString("p_pw", partner.optString("w").ifBlank { "비밀번호 없음" })
                    .putFloat("p_y", partner.getDouble("y").toFloat()).putFloat("p_x", partner.getDouble("x").toFloat())
                pub != null -> e.putString("p_name", pub.name).putBoolean("p_partner", false)
                    .putString("p_sub", Geo.fmt(pd) + if (pub.open.isBlank()) "" else " · ${pub.open}")
                    .putString("p_pw", "비밀번호 없음")
                    .putFloat("p_y", pub.lat.toFloat()).putFloat("p_x", pub.lon.toFloat())
                else -> e.remove("p_name")
            }
            e.apply()
            redraw(ctx)
        }

        fun redraw(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, NearestWidget::class.java))
            for (id in ids) mgr.updateAppWidget(id, views(ctx))
        }

        private fun routeIntent(ctx: Context, code: Int, lat: Float, lon: Float, name: String): PendingIntent =
            PendingIntent.getActivity(
                ctx, code,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_LAT, lat.toDouble()).putExtra(EXTRA_LON, lon.toDouble()).putExtra(EXTRA_NAME, name),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private fun views(ctx: Context): RemoteViews {
            val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val v = RemoteViews(ctx.packageName, R.layout.widget_nearest)
            val at = p.getLong("at", 0L)
            v.setTextViewText(R.id.w_title, if (at == 0L) "🚻 나만의 화장실 · 새로고침을 눌러 주세요"
                else "🚻 가까운 화장실 · " + java.text.SimpleDateFormat("a h:mm", java.util.Locale.KOREA).format(java.util.Date(at)))

            // 내 화장실 칸
            val mName = p.getString("m_name", null)
            v.setTextViewText(R.id.w_m_name, mName ?: if (at == 0L) "⟳ 새로고침" else "저장한 화장실 없음")
            v.setTextViewText(R.id.w_m_sub, if (mName != null) p.getString("m_sub", "") else "")
            v.setTextViewText(R.id.w_m_pw, if (mName != null) "🔑 " + p.getString("m_pw", "").orEmpty().ifBlank { "없음" } else "")
            v.setViewVisibility(R.id.w_m_go, if (mName != null) View.VISIBLE else View.GONE)
            if (mName != null) v.setOnClickPendingIntent(R.id.w_m_go, routeIntent(ctx, 1, p.getFloat("m_y", 0f), p.getFloat("m_x", 0f), mName))

            // 공중화장실·제휴 칸
            val pName = p.getString("p_name", null)
            val partner = p.getBoolean("p_partner", false)
            v.setTextViewText(R.id.w_p_tag, if (partner) "🤝 제휴" else "🚻 공중화장실")
            v.setInt(R.id.w_p_tag, "setBackgroundResource", if (partner) R.drawable.widget_tag_green else R.drawable.widget_tag_gray)
            v.setInt(R.id.w_pub, "setBackgroundResource", if (partner) R.drawable.widget_card_partner else R.drawable.widget_card_pub)
            v.setTextViewText(R.id.w_p_name, pName ?: if (at == 0L) "⟳ 새로고침" else "2km 안에 없음")
            v.setTextViewText(R.id.w_p_sub, if (pName != null) p.getString("p_sub", "") else "")
            v.setTextViewText(R.id.w_p_pw, if (pName != null) p.getString("p_pw", "") else "")
            v.setViewVisibility(R.id.w_p_go, if (pName != null) View.VISIBLE else View.GONE)
            if (pName != null) v.setOnClickPendingIntent(R.id.w_p_go, routeIntent(ctx, 3, p.getFloat("p_y", 0f), p.getFloat("p_x", 0f), pName))

            v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
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
                if (l == null) {
                    Toast.makeText(this, "위치를 못 찾았어요. GPS를 켜 주세요", Toast.LENGTH_SHORT).show()
                    finish(); return@addOnSuccessListener
                }
                val app = applicationContext
                kotlinx.coroutines.MainScope().launch {
                    NearestWidget.refresh(app, l.latitude, l.longitude)
                    Toast.makeText(app, "새로고침했어요", Toast.LENGTH_SHORT).show()
                }
                finish()
            }
            .addOnFailureListener { finish() }
    }
}
