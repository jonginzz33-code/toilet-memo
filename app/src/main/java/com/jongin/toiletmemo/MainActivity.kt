package com.jongin.toiletmemo

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.location.LocationManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var store: Store
    private lateinit var fused: FusedLocationProviderClient
    private var myOverlay: MyLocationNewOverlay? = null
    private val toiletMarkers = mutableListOf<Marker>()

    private var placing = false
    private var movingId: String? = null
    private var selected: Toilet? = null

    private lateinit var btnAdd: Button
    private lateinit var placeBar: View
    private lateinit var centerPin: ImageView
    private lateinit var detailCard: View
    private lateinit var tvRadius: TextView
    private lateinit var tvHint: TextView

    private val colorMine = 0xFF378ADD.toInt()
    private val colorGroup = 0xFF1D9E75.toInt()
    private val colorNew = 0xFFE24B4A.toInt()

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res.values.any { it }) enableMyLocation()
            else toast("위치 권한이 없으면 내 위치를 찾을 수 없어요")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, getSharedPreferences("osm", Context.MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_main)

        store = Store(this)
        fused = LocationServices.getFusedLocationProviderClient(this)

        btnAdd = findViewById(R.id.btnAdd)
        placeBar = findViewById(R.id.placeBar)
        centerPin = findViewById(R.id.centerPin)
        detailCard = findViewById(R.id.detailCard)
        tvRadius = findViewById(R.id.tvRadius)
        tvHint = findViewById(R.id.tvHint)

        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.isTilesScaledToDpi = true
        map.minZoomLevel = 6.0
        map.maxZoomLevel = 20.0
        map.controller.setZoom(17.0)
        map.controller.setCenter(GeoPoint(37.5665, 126.9780))

        // 빈 곳 누르면 상세 닫기, 꾹 누르면 그 자리에 추가
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                if (!placing) hideDetail()
                return true
            }

            override fun longPressHelper(p: GeoPoint?): Boolean {
                if (p != null && !placing) startPlacing(null, p)
                return true
            }
        }))

        map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                updateRadius(); return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                updateRadius(); return false
            }
        })

        // 가운데 고정 빨간 화장실 표시 (위치 맞추기용)
        val (pinIcon, pinAy) = toiletIcon(colorNew, "새 화장실")
        centerPin.setImageDrawable(pinIcon)
        centerPin.translationY = pinIcon.intrinsicHeight / 2f - pinIcon.intrinsicHeight * pinAy

        btnAdd.setOnClickListener { withMyLocation { p -> startPlacing(null, p) } }
        findViewById<Button>(R.id.btnZoomIn).setOnClickListener { map.controller.zoomIn() }
        findViewById<Button>(R.id.btnZoomOut).setOnClickListener { map.controller.zoomOut() }
        findViewById<Button>(R.id.btnMe).setOnClickListener { goToMe() }
        findViewById<Button>(R.id.btnList).setOnClickListener { showList() }
        findViewById<Button>(R.id.btnCancelPlace).setOnClickListener { exitPlacing() }
        findViewById<Button>(R.id.btnSavePlace).setOnClickListener { confirmPlace() }

        findViewById<Button>(R.id.btnCloseDetail).setOnClickListener { hideDetail() }
        findViewById<Button>(R.id.btnCopy).setOnClickListener { copyPw() }
        findViewById<Button>(R.id.btnEdit).setOnClickListener {
            selected?.let { showEditDialog(it, it.lat, it.lon) }
        }
        findViewById<Button>(R.id.btnMove).setOnClickListener {
            selected?.let { startPlacing(it.id, GeoPoint(it.lat, it.lon)) }
        }
        findViewById<Button>(R.id.btnDelete).setOnClickListener { selected?.let { confirmDelete(it) } }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    placing -> exitPlacing()
                    detailCard.visibility == View.VISIBLE -> hideDetail()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        refreshMarkers()
        if (hasLoc()) enableMyLocation() else askPerm()
        updateRadius()
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        myOverlay?.enableMyLocation()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        myOverlay?.disableMyLocation()
    }

    // ---------- 위치 ----------

    private fun hasLoc() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun askPerm() {
        permLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun enableMyLocation() {
        if (myOverlay == null) {
            val provider = GpsMyLocationProvider(this)
            provider.addLocationSource(LocationManager.NETWORK_PROVIDER)
            myOverlay = MyLocationNewOverlay(provider, map).apply { enableMyLocation() }
            map.overlays.add(myOverlay)
        }
        goToMe()
    }

    private fun goToMe() {
        withMyLocation { p ->
            map.controller.setZoom(max(map.zoomLevelDouble, 17.0))
            map.controller.animateTo(p)
        }
    }

    @SuppressLint("MissingPermission")
    private fun withMyLocation(cb: (GeoPoint) -> Unit) {
        if (!hasLoc()) {
            askPerm(); return
        }
        val fallback = {
            fused.lastLocation.addOnSuccessListener { l ->
                if (l != null) cb(GeoPoint(l.latitude, l.longitude))
                else {
                    val p = myOverlay?.myLocation
                    if (p != null) cb(GeoPoint(p.latitude, p.longitude))
                    else toast("위치를 찾지 못했어요. 폰의 위치(GPS)를 켜 주세요")
                }
            }
        }
        fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc ->
                if (loc != null) cb(GeoPoint(loc.latitude, loc.longitude)) else fallback()
            }
            .addOnFailureListener { fallback() }
    }

    private fun updateRadius() {
        val bb = map.boundingBox ?: return
        val r = GeoPoint(bb.centerLatitude, bb.lonWest)
            .distanceToAsDouble(GeoPoint(bb.centerLatitude, bb.lonEast)) / 2
        if (r > 0) tvRadius.text = "내 주변 약 ${fmtDist(r)}"
    }

    private fun fmtDist(m: Double): String =
        if (m < 1000) "${(m / 10).toInt() * 10}m" else String.format("%.1fkm", m / 1000)

    // ---------- 지도 표시 ----------

    /** 남녀 그림 + 아래 한글 이름표. second = 기준점(그림 가운데) 세로 위치 비율 */
    private fun toiletIcon(color: Int, label: String): Pair<BitmapDrawable, Float> {
        val d = resources.displayMetrics.density
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11 * d
            typeface = Typeface.DEFAULT_BOLD
            this.color = color
            textAlign = Paint.Align.CENTER
        }
        val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 20 * d
            textAlign = Paint.Align.CENTER
        }
        val boxW = 46 * d
        val boxH = 34 * d
        val labelW = labelPaint.measureText(label) + 12 * d
        val labelH = 18 * d
        val gap = 2 * d
        val w = max(boxW, labelW) + 4 * d
        val h = boxH + gap + labelH + 4 * d
        val bmp = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = w / 2

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2 * d
        }
        val box = RectF(cx - boxW / 2, 2 * d, cx + boxW / 2, 2 * d + boxH)
        c.drawRoundRect(box, 8 * d, 8 * d, fill)
        c.drawRoundRect(box, 8 * d, 8 * d, white)
        val em = emojiPaint.fontMetrics
        c.drawText("🚻", cx, box.centerY() - (em.ascent + em.descent) / 2, emojiPaint)

        val lb = RectF(cx - labelW / 2, box.bottom + gap, cx + labelW / 2, box.bottom + gap + labelH)
        val lbBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xF2FFFFFF.toInt() }
        val lbLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeWidth = 1 * d
        }
        c.drawRoundRect(lb, 5 * d, 5 * d, lbBg)
        c.drawRoundRect(lb, 5 * d, 5 * d, lbLine)
        val lm = labelPaint.fontMetrics
        c.drawText(label, cx, lb.centerY() - (lm.ascent + lm.descent) / 2, labelPaint)

        return Pair(BitmapDrawable(resources, bmp), box.centerY() / h)
    }

    private fun scopeName(s: String) = if (s == "group") "그룹" else "나만 보기"

    private fun refreshMarkers() {
        map.overlays.removeAll(toiletMarkers.toSet())
        toiletMarkers.clear()
        for (t in store.all()) {
            val m = Marker(map)
            m.position = GeoPoint(t.lat, t.lon)
            val (icon, ay) = toiletIcon(
                if (t.scope == "group") colorGroup else colorMine,
                scopeName(t.scope)
            )
            m.icon = icon
            m.setAnchor(0.5f, ay)
            m.title = t.title()
            m.setOnMarkerClickListener { _, _ ->
                if (!placing) showDetail(t)
                true
            }
            toiletMarkers.add(m)
            map.overlays.add(m)
        }
        map.invalidate()
    }

    // ---------- 위치 정하기 (가운데 빨간 표시) ----------

    private fun startPlacing(moveId: String?, at: GeoPoint) {
        placing = true
        movingId = moveId
        hideDetail()
        map.controller.setZoom(max(map.zoomLevelDouble, 18.0))
        map.controller.animateTo(at)
        centerPin.visibility = View.VISIBLE
        placeBar.visibility = View.VISIBLE
        btnAdd.visibility = View.GONE
        tvHint.text = "지도를 움직여 빨간 표시를 화장실 자리에 맞추세요"
    }

    private fun exitPlacing() {
        placing = false
        movingId = null
        centerPin.visibility = View.GONE
        placeBar.visibility = View.GONE
        btnAdd.visibility = View.VISIBLE
        tvHint.text = "두 손가락으로 확대·축소"
    }

    private fun confirmPlace() {
        val c = map.mapCenter
        val lat = c.latitude
        val lon = c.longitude
        val id = movingId
        if (id != null) {
            val t = store.all().firstOrNull { it.id == id }
            if (t != null) {
                t.lat = lat; t.lon = lon
                store.upsert(t)
                refreshMarkers()
                exitPlacing()
                showDetail(t)
                toast("위치를 옮겼어요")
            } else exitPlacing()
        } else {
            showEditDialog(null, lat, lon)
        }
    }

    // ---------- 추가·수정 창 ----------

    private fun showEditDialog(t: Toilet?, lat: Double, lon: Double) {
        val v = layoutInflater.inflate(R.layout.dialog_edit, null)
        val etBuilding = v.findViewById<EditText>(R.id.etBuilding)
        val etFloor = v.findViewById<EditText>(R.id.etFloor)
        val etPlace = v.findViewById<EditText>(R.id.etPlace)
        val etPw = v.findViewById<EditText>(R.id.etPw)
        val etMemo = v.findViewById<EditText>(R.id.etMemo)
        val rbGroup = v.findViewById<RadioButton>(R.id.rbGroup)

        if (t != null) {
            etBuilding.setText(t.building)
            etFloor.setText(t.floor)
            etPlace.setText(t.place)
            etPw.setText(Crypto.dec(t.pwEnc))
            etMemo.setText(t.memo)
            if (t.scope == "group") rbGroup.isChecked = true
        }

        val dlg = AlertDialog.Builder(this)
            .setTitle(if (t == null) "화장실 추가" else "화장실 수정")
            .setView(v)
            .setPositiveButton("저장", null)
            .setNegativeButton("취소", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val building = etBuilding.text.toString().trim()
                if (building.isEmpty()) {
                    etBuilding.error = "건물 이름을 적어 주세요"
                    return@setOnClickListener
                }
                val item = t ?: Toilet(
                    lat = lat, lon = lon, building = "", floor = "",
                    place = "", pwEnc = "", memo = ""
                )
                item.building = building
                item.floor = etFloor.text.toString().trim()
                item.place = etPlace.text.toString().trim()
                item.pwEnc = Crypto.enc(etPw.text.toString().trim())
                item.memo = etMemo.text.toString().trim()
                item.scope = if (rbGroup.isChecked) "group" else "mine"
                store.upsert(item)
                refreshMarkers()
                dlg.dismiss()
                if (t == null) exitPlacing()
                showDetail(item)
                toast("저장했어요")
            }
        }
        dlg.show()
    }

    // ---------- 상세 카드 ----------

    private fun showDetail(t: Toilet) {
        selected = t
        findViewById<TextView>(R.id.tvScope).text = scopeName(t.scope)
        findViewById<TextView>(R.id.tvTitle).text = t.title()
        val dist = myOverlay?.myLocation?.let {
            " · " + fmtDist(GeoPoint(it.latitude, it.longitude).distanceToAsDouble(GeoPoint(t.lat, t.lon)))
        } ?: ""
        findViewById<TextView>(R.id.tvDesc).text =
            (if (t.place.isBlank()) "위치 설명 없음" else t.place) + dist
        val pw = Crypto.dec(t.pwEnc)
        findViewById<TextView>(R.id.tvPw).text = if (pw.isEmpty()) "(비밀번호 없음)" else pw
        val memo = findViewById<TextView>(R.id.tvMemo)
        memo.text = if (t.memo.isBlank()) "" else "📝 ${t.memo}"
        memo.visibility = if (t.memo.isBlank()) View.GONE else View.VISIBLE
        detailCard.visibility = View.VISIBLE
    }

    private fun hideDetail() {
        selected = null
        detailCard.visibility = View.GONE
    }

    private fun copyPw() {
        val t = selected ?: return
        val pw = Crypto.dec(t.pwEnc)
        if (pw.isEmpty()) {
            toast("저장된 비밀번호가 없어요"); return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("비밀번호", pw))
        toast("비밀번호를 복사했어요")
    }

    private fun confirmDelete(t: Toilet) {
        AlertDialog.Builder(this)
            .setTitle("삭제할까요?")
            .setMessage("${t.title()} 화장실을 지우면 되돌릴 수 없어요.")
            .setPositiveButton("삭제") { _, _ ->
                store.delete(t.id)
                hideDetail()
                refreshMarkers()
                toast("삭제했어요")
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ---------- 목록 ----------

    private fun showList() {
        val c = map.mapCenter
        val here = myOverlay?.myLocation?.let { GeoPoint(it.latitude, it.longitude) }
            ?: GeoPoint(c.latitude, c.longitude)
        val list = store.all()
            .map { it to here.distanceToAsDouble(GeoPoint(it.lat, it.lon)) }
            .sortedBy { it.second }
        if (list.isEmpty()) {
            toast("아직 저장한 화장실이 없어요"); return
        }
        val names = list.map { (t, d) -> "${t.title()} · ${fmtDist(d)}  [${scopeName(t.scope)}]" }
            .toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("내 화장실 (가까운 순)")
            .setItems(names) { _, i ->
                val t = list[i].first
                if (placing) exitPlacing()
                map.controller.setZoom(max(map.zoomLevelDouble, 18.0))
                map.controller.animateTo(GeoPoint(t.lat, t.lon))
                showDetail(t)
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
