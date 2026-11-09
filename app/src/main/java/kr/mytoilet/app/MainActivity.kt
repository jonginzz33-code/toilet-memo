package kr.mytoilet.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.Label
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.label.LabelStyle
import com.kakao.vectormap.label.LabelStyles
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private var map: KakaoMap? = null
    private lateinit var circle: CircleOverlay
    private lateinit var centerPin: ImageView
    private lateinit var topBox: LinearLayout
    private lateinit var countText: TextView
    private lateinit var unavailChip: TextView
    private lateinit var addBtn: TextView
    private lateinit var placeBar: LinearLayout
    private lateinit var hint: TextView
    private lateinit var followBtn: TextView
    private lateinit var loading: LinearProgressIndicator
    private lateinit var rightCol: LinearLayout

    lateinit var local: LocalStore
    lateinit var prefs: Prefs
    private lateinit var fused: FusedLocationProviderClient

    var myLat: Double? = null
    var myLon: Double? = null
    private var meLabel: Label? = null
    private var meFrame = 0
    private var meStyles: Array<LabelStyles>? = null
    private var meScale = 0f

    /** 지도를 축소해서 넓게 볼수록 표시를 작게 (확대 단계 18 이상이면 원래 크기) */
    private fun iconScale(): Float {
        val z = map?.cameraPosition?.zoomLevel ?: 16
        return when {
            z >= 18 -> 1f
            z == 17 -> 0.85f
            z == 16 -> 0.7f
            z == 15 -> 0.58f
            else -> 0.48f
        }
    }

    var mine: List<Toilet> = emptyList()
    var groups: List<Group> = emptyList()
    var groupToilets: List<Toilet> = emptyList()
    var publics: List<PublicToilet> = emptyList()
    var unavail: Map<String, Cloud.Unavail> = emptyMap()
    var isAdmin = false

    private val labelTargets = HashMap<String, Any>()
    private val toiletLabels = mutableListOf<Label>()

    private var following = false
    private var lastProgMove = 0L
    var placing = false
    private var movingTarget: Toilet? = null
    private var loadCount = 0
    private var radiusM = 500.0

    private val handler = Handler(Looper.getMainLooper())

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res.values.any { it }) startLocation() else toast("위치 권한이 없으면 내 위치를 찾을 수 없어요")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        local = LocalStore(this)
        prefs = Prefs(this)
        fused = LocationServices.getFusedLocationProviderClient(this)
        buildUi()
        val crash = CrashLog.take(this)
        if (crash != null) showCrash(crash) else startMap()
        if (hasLoc()) startLocation() else permLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (placing) exitPlacing() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
        lifecycleScope.launch {
            if (Cloud.loggedIn) afterLogin(quiet = true) else reloadMine()
            refreshUnavailable()
            checkUpdate(silent = true)
        }
        handler.postDelayed(unavailTick, 120_000)
        handler.postDelayed(wiggle, 500)
    }

    // ---------------- 화면 ----------------

    private fun buildUi() {
        val root = FrameLayout(this)
        mapView = MapView(this)
        root.addView(mapView, FrameLayout.LayoutParams(-1, -1))

        circle = CircleOverlay(this).apply { show = prefs.circleOnly }
        root.addView(circle, FrameLayout.LayoutParams(-1, -1))

        val pinBmp = Icons.toilet(this, C.RED, "새 화장실")
        centerPin = ImageView(this).apply {
            setImageBitmap(pinBmp)
            visibility = View.GONE
            val ay = Icons.toiletAnchorY(this@MainActivity, 0)
            translationY = pinBmp.height / 2f - pinBmp.height * ay
        }
        root.addView(centerPin, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        // 위쪽
        topBox = vbox(8).apply { setPadding(dp(12), dp(10), dp(12), 0) }
        val bar = hbox(8).apply {
            background = glass(18f)
            setPadding(dp(14), dp(8), dp(8), dp(8))
            elevation = dpf(3f)
        }
        val titleBox = vbox(0)
        titleBox.add(text("🚻 나만의 화장실", 16f, C.TEXT, 700))
        countText = text("", 12f, C.TEXT2)
        titleBox.add(countText)
        bar.add(titleBox, 1f)
        unavailChip = chip("", C.RED_BG, C.RED_TEXT).apply { visibility = View.GONE }
        bar.add(unavailChip)
        bar.add(text("☰", 22f, C.TEXT, 700, Gravity.CENTER).apply {
            setPadding(dp(10), dp(2), dp(10), dp(2))
            setOnClickListener { Sheets.menu(this@MainActivity) }
        })
        topBox.add(bar)
        addBtn = primaryBtn("＋ 지금 위치에 화장실 추가") { addAtMyLocation() }.apply { elevation = dpf(3f) }
        topBox.add(addBtn)
        root.addView(topBox, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        loading = LinearProgressIndicator(this).apply {
            isIndeterminate = true
            trackThickness = dp(3)
            setIndicatorColor(C.BLUE)
            trackColor = Color.TRANSPARENT
            visibility = View.INVISIBLE
        }
        root.addView(loading, FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP))

        // 오른쪽 버튼
        rightCol = vbox(8)
        fun rbtn(s: String, onClick: () -> Unit) = text(s, 20f, C.TEXT, 700, Gravity.CENTER).apply {
            background = glass(14f)
            elevation = dpf(3f)
            setOnClickListener { onClick() }
        }
        rightCol.addView(rbtn("＋") { zoom(true) }, LinearLayout.LayoutParams(dp(46), dp(46)))
        rightCol.addView(View(this), LinearLayout.LayoutParams(1, dp(6)))
        rightCol.addView(rbtn("－") { zoom(false) }, LinearLayout.LayoutParams(dp(46), dp(46)))
        rightCol.addView(View(this), LinearLayout.LayoutParams(1, dp(6)))
        rightCol.addView(rbtn("◎") { goToMe() }, LinearLayout.LayoutParams(dp(46), dp(46)))
        rightCol.addView(View(this), LinearLayout.LayoutParams(1, dp(6)))
        followBtn = text("따라\n가기", 11f, C.TEXT, 700, Gravity.CENTER).apply {
            background = glass(14f)
            elevation = dpf(3f)
            setOnClickListener { setFollow(!following) }
        }
        rightCol.addView(followBtn, LinearLayout.LayoutParams(dp(46), dp(46)))
        rightCol.addView(View(this), LinearLayout.LayoutParams(1, dp(6)))
        rightCol.addView(rbtn("☷") { Sheets.list(this) }, LinearLayout.LayoutParams(dp(46), dp(46)))
        root.addView(rightCol, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = dp(12)
        })

        // 아래쪽
        val bottom = vbox(8).apply { setPadding(dp(12), 0, dp(12), dp(12)) }
        hint = text("두 손가락으로 확대·축소", 13f, C.BLUE_DARK, 600, Gravity.CENTER).apply {
            background = glass(14f)
            setPadding(dp(12), dp(7), dp(12), dp(7))
        }
        bottom.addView(hint, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        placeBar = hbox(8).apply { visibility = View.GONE }
        placeBar.add(btn("취소", C.GLASS) { exitPlacing() }, 1f)
        placeBar.add(btn("이 위치로 정하기", C.RED, Color.WHITE) { confirmPlace() }, 2f)
        bottom.add(placeBar)
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            topBox.setPadding(dp(12), b.top + dp(8), dp(12), 0)
            (loading.layoutParams as FrameLayout.LayoutParams).topMargin = b.top
            bottom.setPadding(dp(12), 0, dp(12), b.bottom + dp(12))
            ins
        }
    }

    fun setCircleShown(on: Boolean) {
        circle.show = on
    }

    fun busy(on: Boolean) {
        loadCount = (loadCount + if (on) 1 else -1).coerceAtLeast(0)
        loading.visibility = if (loadCount > 0) View.VISIBLE else View.INVISIBLE
    }

    // ---------------- 지도 ----------------

    /** 지난번에 앱이 꺼진 이유를 보여 주고 복사할 수 있게 함 */
    private fun showCrash(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(40, 20, 40, 20)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("지난번에 앱이 꺼진 이유")
            .setView(scroll)
            .setPositiveButton("복사") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("crash", text))
                toast("복사했어요. Claude에게 붙여 주세요")
            }
            .setNegativeButton("닫기", null)
            .setOnDismissListener { startMap() } // 어느 버튼을 눌러도 지도는 켜기
            .show()
    }

    private fun showMapError(msg: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) { runOnUiThread { showMapError(msg) }; return }
        hint.text = msg
        hint.setTextColor(C.RED_TEXT)
        hint.background = round(C.RED_BG, 14f)
        toast(msg)
    }

    private fun startMap() {
        if (BuildConfig.KAKAO_KEY.isBlank()) {
            showMapError("카카오 지도 키가 비어 있어요 (gradle.properties의 kakaoKey 확인)")
            return
        }
        mapView.start(object : MapLifeCycleCallback() {
            override fun onMapDestroy() {}
            override fun onMapError(error: Exception) {
                showMapError("지도를 열지 못했어요\n${error.message ?: error.javaClass.simpleName}")
            }
        }, object : KakaoMapReadyCallback() {
            override fun onMapReady(kakaoMap: KakaoMap) {
                runOnUiThread { mapReady(kakaoMap) }
            }

            override fun getPosition(): LatLng = LatLng.from(myLat ?: 37.5665, myLon ?: 126.9780)
            override fun getZoomLevel(): Int = 16
        })
    }

    // 이름이 카카오의 onMapReady와 같으면 자기 자신을 끝없이 불러 앱이 꺼졌음 → 다른 이름으로
    private fun mapReady(m: KakaoMap) {
        map = m
        try {
            setupMap(m)
        } catch (e: Exception) {
            showMapError("지도 준비 중 오류\n${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun setupMap(m: KakaoMap) {
        // 카카오 지도는 이 신호들을 화면 스레드가 아닌 곳에서 보낼 수 있어서, 화면 스레드로 넘겨서 처리
        m.setOnLabelClickListener { _, _, label ->
            runOnUiThread { onLabelClick(label) }
            true
        }
        m.setOnCameraMoveStartListener { _, _ ->
            runOnUiThread {
                if (following && System.currentTimeMillis() - lastProgMove > 900) setFollow(false)
            }
        }
        m.setOnCameraMoveEndListener { _, _, _ -> runOnUiThread { onCameraEnd() } }
        myLat?.let { updateMe() }
        handler.postDelayed({ onCameraEnd() }, 400)
    }

    private fun move(lat: Double, lon: Double, zoom: Int? = null) {
        val m = map ?: return
        lastProgMove = System.currentTimeMillis()
        if (zoom != null) m.moveCamera(CameraUpdateFactory.newCenterPosition(LatLng.from(lat, lon), zoom))
        else m.moveCamera(CameraUpdateFactory.newCenterPosition(LatLng.from(lat, lon)))
    }

    private fun zoom(inOut: Boolean) {
        val m = map ?: return
        lastProgMove = System.currentTimeMillis()
        m.moveCamera(if (inOut) CameraUpdateFactory.zoomIn() else CameraUpdateFactory.zoomOut())
    }

    fun center(): Pair<Double, Double> {
        val p = map?.cameraPosition?.position
        return (p?.latitude ?: myLat ?: 37.5665) to (p?.longitude ?: myLon ?: 126.978)
    }

    private fun computeRadius() {
        val m = map ?: return
        val cx = mapView.width / 2
        val cy = mapView.height / 2
        val r = circle.radiusPx().toInt()
        if (cx == 0) return
        val a = m.fromScreenPoint(cx, cy) ?: return
        val b = m.fromScreenPoint(cx + r, cy) ?: return
        radiusM = Geo.dist(a.latitude, a.longitude, b.latitude, b.longitude)
        circle.label = "반경 ${Geo.fmt(radiusM)}"
    }

    private var publicJob: kotlinx.coroutines.Job? = null

    fun onCameraEnd() {
        try {
            computeRadius()
            drawToilets()
            updateMe()
        } catch (e: Exception) {
            showMapError("화장실 표시 중 오류\n${e.javaClass.simpleName}: ${e.message}")
            return
        }
        if (!prefs.showPublic) { publics = emptyList(); drawToilets(); return }
        publicJob?.cancel()
        publicJob = lifecycleScope.launch {
            val (lat, lon) = center()
            val cells = Geo.cellsAround(lat, lon, radiusM * 1.6).take(40)
            val need = cells.filter { !Cloud.cellCache.containsKey(it) }
            if (need.isNotEmpty()) busy(true)
            val out = ArrayList<PublicToilet>()
            try {
                for (c in cells) out.addAll(Cloud.publicCell(c))
            } catch (e: Exception) {
                // 서버 연결 전이거나 권한 없음
            } finally {
                if (need.isNotEmpty()) busy(false)
            }
            publics = out
            try { drawToilets() } catch (e: Exception) {
                showMapError("화장실 표시 중 오류\n${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /** 지도에 화장실 표시 다시 그리기 (원 안만) */
    fun drawToilets() {
        val m = map ?: return
        val layer = m.labelManager?.layer ?: return
        toiletLabels.forEach { try { layer.remove(it) } catch (_: Exception) {} }
        toiletLabels.clear()
        labelTargets.keys.removeAll { it != "me" }
        val (clat, clon) = center()
        val limit = if (prefs.circleOnly) radiusM else radiusM * 2.2
        var shown = 0
        var unavailIn = 0
        val scale = iconScale()

        fun addLabel(id: String, lat: Double, lon: Double, color: Int, label: String, outline: Boolean, un: Int, target: Any) {
            val bmp = Icons.toilet(this, color, label, un, outline, scale)
            val ay = Icons.toiletAnchorY(this, un)
            val opt = LabelOptions.from(id, LatLng.from(lat, lon))
                .setStyles(LabelStyles.from(LabelStyle.from(bmp).setAnchorPoint(0.5f, ay)))
                .setClickable(true)
            val l = layer.addLabel(opt) ?: return
            toiletLabels.add(l)
            labelTargets[id] = target
        }

        val near = publics.map { it to Geo.dist(clat, clon, it.lat, it.lon) }
            .filter { it.second <= limit }.sortedBy { it.second }.take(250)
        for ((p, _) in near) {
            val u = unavail[p.id]
            val n = u?.count ?: 0
            if (n > 0) unavailIn++
            val label = if (n > 0) "사용불가 ${n}건 · ${Geo.ago(u!!.last)}" else "공중화장실"
            addLabel("p_${p.id}", p.lat, p.lon, C.GRAY, label, true, n, p)
            shown++
        }
        for (t in groupToilets) {
            if (Geo.dist(clat, clon, t.lat, t.lon) > limit) continue
            addLabel("g_${t.groupId}_${t.id}", t.lat, t.lon, C.GREEN, "그룹", false, 0, t); shown++
        }
        for (t in mine) {
            if (Geo.dist(clat, clon, t.lat, t.lon) > limit) continue
            addLabel("m_${t.id}", t.lat, t.lon, C.BLUE, "나만 보기", false, 0, t); shown++
        }
        countText.text = if (prefs.circleOnly) "원 안 화장실 ${shown}곳" else "근처 화장실 ${shown}곳"
        unavailChip.text = "근처 사용불가 ${unavailIn}곳"
        unavailChip.visibility = if (unavailIn > 0) View.VISIBLE else View.GONE
    }

    private fun onLabelClick(label: Label) {
        if (placing) return
        when (val t = labelTargets[label.labelId]) {
            is Toilet -> Sheets.toiletDetail(this, t)
            is PublicToilet -> Sheets.publicDetail(this, t)
        }
    }

    // ---------------- 내 위치 ----------------

    private fun hasLoc() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val locCallback = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            val l = r.lastLocation ?: return
            val first = myLat == null
            myLat = l.latitude; myLon = l.longitude
            try { updateMe() } catch (e: Exception) {
                showMapError("내 위치 표시 중 오류\n${e.javaClass.simpleName}: ${e.message}")
            }
            if (first) move(l.latitude, l.longitude, 16)
            else if (following) move(l.latitude, l.longitude)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!hasLoc()) return
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000).setMinUpdateDistanceMeters(3f).build()
        fused.requestLocationUpdates(req, locCallback, Looper.getMainLooper())
    }

    fun refreshCharacter() {
        meStyles = null
        updateMe()
    }

    private fun updateMe() {
        val m = map ?: return
        val lat = myLat ?: return
        val lon = myLon ?: return
        val layer = m.labelManager?.layer ?: return
        val sc = iconScale()
        if (meStyles == null || sc != meScale) {
            meScale = sc
            meStyles = arrayOf(
                LabelStyles.from(LabelStyle.from(Icons.character(this, prefs.character, 0, sc)).setAnchorPoint(0.5f, 0.9f)),
                LabelStyles.from(LabelStyle.from(Icons.character(this, prefs.character, 1, sc)).setAnchorPoint(0.5f, 0.9f))
            )
            meLabel?.let { try { layer.remove(it) } catch (_: Exception) {} }
            meLabel = null
        }
        val existing = meLabel
        if (existing == null) {
            meLabel = layer.addLabel(LabelOptions.from("me", LatLng.from(lat, lon)).setStyles(meStyles!![0]))
        } else {
            existing.moveTo(LatLng.from(lat, lon))
        }
    }

    private val wiggle = object : Runnable {
        override fun run() {
            try {
                val st = meStyles
                val l = meLabel
                if (st != null && l != null) {
                    meFrame = 1 - meFrame
                    l.changeStyles(st[meFrame])
                }
            } catch (_: Exception) {}
            handler.postDelayed(this, if (following) 300 else 550)
        }
    }

    private fun goToMe() {
        val lat = myLat
        val lon = myLon
        if (lat == null || lon == null) {
            if (!hasLoc()) permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            else toast("내 위치를 찾는 중이에요. 폰의 위치(GPS)를 켜 주세요")
            return
        }
        move(lat, lon)
    }

    private fun setFollow(on: Boolean) {
        following = on
        followBtn.background = if (on) round(C.BLUE, 14f) else glass(14f)
        followBtn.setTextColor(if (on) Color.WHITE else C.TEXT)
        if (on) { goToMe(); hint.text = "내 위치를 따라가는 중 · 지도를 밀면 꺼져요" }
        else if (!placing) hint.text = "두 손가락으로 확대·축소"
    }

    // ---------------- 화장실 추가 · 위치 옮기기 ----------------

    private fun addAtMyLocation() {
        val lat = myLat
        val lon = myLon
        if (lat == null || lon == null) { goToMe(); return }
        startPlacing(null, lat, lon)
    }

    fun startPlacing(target: Toilet?, lat: Double, lon: Double) {
        placing = true
        movingTarget = target
        setFollow(false)
        move(lat, lon, 18)
        centerPin.visibility = View.VISIBLE
        placeBar.visibility = View.VISIBLE
        addBtn.visibility = View.GONE
        rightCol.visibility = View.GONE
        hint.text = "지도를 움직여 빨간 표시를 화장실 자리에 맞추세요"
    }

    fun exitPlacing() {
        placing = false
        movingTarget = null
        centerPin.visibility = View.GONE
        placeBar.visibility = View.GONE
        addBtn.visibility = View.VISIBLE
        rightCol.visibility = View.VISIBLE
        hint.text = "두 손가락으로 확대·축소"
    }

    private fun confirmPlace() {
        val (lat, lon) = center()
        val t = movingTarget
        if (t != null) {
            t.lat = lat; t.lon = lon; t.updated = System.currentTimeMillis()
            exitPlacing()
            lifecycleScope.launch {
                saveToilet(t, t.scope, t.groupId)
                toast("위치를 옮겼어요")
            }
        } else {
            exitPlacing()
            Sheets.edit(this, null, lat, lon)
        }
    }

    // ---------------- 데이터 ----------------

    suspend fun reloadMine() {
        busy(true)
        try {
            if (Cloud.loggedIn) {
                mine = Cloud.myToilets()
                groups = Cloud.myGroups()
                val all = ArrayList<Toilet>()
                for (g in groups) all.addAll(Cloud.groupToilets(g))
                groupToilets = all
            } else {
                mine = local.all()
                groups = emptyList()
                groupToilets = emptyList()
            }
        } catch (e: Exception) {
            toast("불러오지 못했어요: ${e.message}")
            if (!Cloud.loggedIn) mine = local.all()
        } finally {
            busy(false)
        }
        drawToilets()
    }

    /** 저장 (범위가 바뀌면 예전 자리에서 지움) */
    suspend fun saveToilet(t: Toilet, oldScope: String?, oldGroup: String?) {
        busy(true)
        try {
            t.updated = System.currentTimeMillis()
            if (oldScope != null && (oldScope != t.scope || oldGroup != t.groupId)) {
                if (oldScope == "group" && !oldGroup.isNullOrEmpty()) Cloud.deleteGroupToilet(oldGroup, t.id)
                else if (Cloud.loggedIn) Cloud.deleteMine(t.id) else local.delete(t.id)
            }
            if (t.scope == "group") Cloud.saveGroupToilet(t)
            else if (Cloud.loggedIn) Cloud.saveMine(t) else local.upsert(t)
        } catch (e: Exception) {
            toast("저장하지 못했어요: ${e.message}")
        } finally {
            busy(false)
        }
        reloadMine()
    }

    suspend fun deleteToilet(t: Toilet) {
        busy(true)
        try {
            if (t.scope == "group") Cloud.deleteGroupToilet(t.groupId, t.id)
            else if (Cloud.loggedIn) Cloud.deleteMine(t.id) else local.delete(t.id)
        } catch (e: Exception) {
            toast("삭제하지 못했어요: ${e.message}")
        } finally {
            busy(false)
        }
        reloadMine()
    }

    suspend fun refreshUnavailable() {
        unavail = try { Cloud.unavailable() } catch (e: Exception) { emptyMap() }
        drawToilets()
    }

    private val unavailTick = object : Runnable {
        override fun run() {
            lifecycleScope.launch { refreshUnavailable() }
            handler.postDelayed(this, 120_000)
        }
    }

    /** 로그인 후 처리 */
    suspend fun afterLogin(quiet: Boolean = false) {
        try {
            Cloud.touchUser()
            if (!prefs.termsAgreed) Sheets.terms(this)
            if (!prefs.uploadedLocal) {
                val l = local.all()
                for (t in l) Cloud.saveMine(t)
                prefs.uploadedLocal = true
                if (l.isNotEmpty() && !quiet) toast("폰에 있던 화장실 ${l.size}곳을 계정에 옮겼어요")
            }
            isAdmin = Cloud.isAdmin()
        } catch (e: Exception) {
            if (!quiet) toast("서버 연결 문제: ${e.message}")
        }
        reloadMine()
    }

    fun copy(s: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("비밀번호", s))
        toast("복사했어요")
    }

    fun open(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
    }

    fun checkUpdate(silent: Boolean) {
        lifecycleScope.launch {
            val info = Updater.latest()
            if (info == null) { if (!silent) toast("업데이트 정보를 못 받았어요"); return@launch }
            if (info.versionCode > BuildConfig.VERSION_CODE) {
                confirm("새 버전이 있어요", "${info.name}\n지금 업데이트할까요?", "업데이트") { Updater.install(this@MainActivity, info) }
            } else if (!silent) toast("최신 버전이에요 (v${BuildConfig.VERSION_NAME})")
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.resume()
    }

    override fun onPause() {
        super.onPause()
        mapView.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        fused.removeLocationUpdates(locCallback)
    }

    companion object {
        fun lpMatch() = ViewGroup.LayoutParams(-1, -2)
    }
}
