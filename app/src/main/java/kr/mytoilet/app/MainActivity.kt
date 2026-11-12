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
import android.os.Build
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
    private lateinit var routeBar: LinearLayout
    private lateinit var urgentBtn: TextView
    private lateinit var quickBar: LinearLayout
    /** 반경이 이보다 넓으면 숫자 동그라미로 묶어서 보여 줌 */
    private val CLUSTER_M = 300.0
    private lateinit var adminSwitch: TextView

    // 지도에 보일 종류 (관리자 퀵바로 바꿈. 일반 사용자는 공중화장실 스위치만)
    val showMine get() = !isAdmin || prefs.fMine
    val showGroup get() = !isAdmin || prefs.fGroup
    val showPartner get() = !isAdmin || prefs.fPartner
    /** 공중화장실: 관리자 화면이면 퀵바 '공공', 아니면 메뉴의 '공중화장실 보기' */
    val showPublic get() = if (isAdmin) prefs.fPublic else prefs.showPublic

    /** 관리자 퀵바 다시 그리기 */
    fun refreshQuickBar() {
        if (!::quickBar.isInitialized) return
        // 맨 위 관리자 스위치 (총관리자에게만 보임)
        adminSwitch.visibility = if (realAdmin) View.VISIBLE else View.GONE
        adminSwitch.text = if (prefs.adminView) "관리자 ON" else "관리자 OFF"
        adminSwitch.setTextColor(if (prefs.adminView) Color.WHITE else C.TEXT2)
        adminSwitch.background = if (prefs.adminView) round(C.CAR, 14f) else round(Color.WHITE, 14f, 1, 0xFFCCCCCC.toInt())
        quickBar.removeAllViews()
        quickBar.visibility = if (isAdmin) View.VISIBLE else View.GONE
        if (!isAdmin) return
        val all = prefs.fMine && prefs.fGroup && prefs.fPublic && prefs.fPartner
        fun chipOf(label: String, on: Boolean, color: Int, click: () -> Unit) {
            quickBar.add(text(label, 12f, if (on) Color.WHITE else C.TEXT3, 800, Gravity.CENTER).apply {
                background = if (on) round(color, 14f) else round(Color.WHITE, 14f, 1, 0xFFDDDDDD.toInt())
                setPadding(dp(9), dp(4), dp(9), dp(4))
                setOnClickListener { click(); refreshQuickBar(); drawToilets(); onCameraEnd() }
            })
        }
        chipOf("전체", all, C.CAR) {
            val v = !all
            prefs.fMine = v; prefs.fGroup = v; prefs.fPublic = v; prefs.fPartner = v
        }
        chipOf("내 것", prefs.fMine, C.ORANGE) { prefs.fMine = !prefs.fMine }
        chipOf("그룹", prefs.fGroup, C.PURPLE) { prefs.fGroup = !prefs.fGroup }
        chipOf("공공", prefs.fPublic, C.GRAY) { prefs.fPublic = !prefs.fPublic }
        chipOf("제휴", prefs.fPartner, C.GREEN) { prefs.fPartner = !prefs.fPartner }
    }
    /** 위젯 '길찾기'로 열렸을 때: 위치·지도가 준비되면 차량/도보 고르기 창 */
    private var pendingRoute: Triple<Double, Double, String>? = null
    private lateinit var routeInfo: TextView
    private var routeLine: com.kakao.vectormap.route.RouteLine? = null
    private var routeTarget: Triple<Double, Double, String>? = null
    private var routeCar = false
    private lateinit var routeKakaoBtn: TextView

    // ---------- 내비처럼 내가 보는 방향으로 지도 돌리기 (길찾기 중에만) ----------
    private lateinit var headingBtn: TextView
    private var headingOn = false
    private var heading = 0f              // 지금 방향 (도, 북쪽 0 · 시계 방향)
    private var lastRotateAt = 0L
    private var lastRotated = 999f
    private var gpsBearingAt = 0L          // GPS 이동 방향을 쓴 시각 (움직일 땐 나침반보다 GPS 우선)
    private var mapRotation = 0f           // 지금 지도가 돌아간 각도 (도)
    private var coneLabel: Label? = null
    private val coneStyles = HashMap<Int, LabelStyles>()   // 10도 단위로 그려 둔 부채꼴

    /** 내 위치에 '보는 방향' 파란 부채꼴 (길찾기·따라가기 중). 화면 기준 각도 = 방향 - 지도 회전 */
    private fun updateCone() {
        val m = map ?: return
        val lat = myLat ?: return
        val lon = myLon ?: return
        val layer = m.labelManager?.layer ?: return
        if (!coneWanted) { removeCone(); return }
        val step = Math.round((((heading - mapRotation) % 360 + 360) % 360) / 10f) % 36
        val st = coneStyles.getOrPut(step) {
            LabelStyles.from(LabelStyle.from(Icons.headingCone(this, step * 10f)).setAnchorPoint(0.5f, 0.5f))
        }
        val c = coneLabel
        if (c == null) coneLabel = layer.addLabel(LabelOptions.from("cone", LatLng.from(lat, lon)).setStyles(st).setRank(0L))
        else { c.moveTo(LatLng.from(lat, lon)); c.changeStyles(st) }
    }

    private val coneWanted get() = routeTarget != null || following

    /** 부채꼴이 필요할 때만 나침반을 켬 (배터리) */
    private fun syncCompass() {
        sensorManager.unregisterListener(compass)
        if (coneWanted) sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(compass, it, android.hardware.SensorManager.SENSOR_DELAY_UI)
        }
        updateCone()
    }

    private fun removeCone() {
        coneLabel?.let { l -> try { map?.labelManager?.layer?.remove(l) } catch (_: Exception) {} }
        coneLabel = null
    }
    private val sensorManager by lazy { getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager }
    private val rotMatrix = FloatArray(9)
    private val orient = FloatArray(3)
    private val remapped = FloatArray(9)
    private var declination = 0f           // 자석 북쪽 ↔ 진짜 북쪽 차이 (한국은 약 -8~-9도)
    private val compass = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            if (System.currentTimeMillis() - gpsBearingAt < 3000) return
            android.hardware.SensorManager.getRotationMatrixFromVector(rotMatrix, e.values)
            android.hardware.SensorManager.getOrientation(rotMatrix, orient)
            // 폰을 세워 들면(45도 넘게) 방향 값이 흔들리니, 화면 뒤쪽이 향한 방향으로 계산
            if (Math.abs(orient[1]) > Math.PI / 4) {
                android.hardware.SensorManager.remapCoordinateSystem(rotMatrix,
                    android.hardware.SensorManager.AXIS_X, android.hardware.SensorManager.AXIS_Z, remapped)
                android.hardware.SensorManager.getOrientation(remapped, orient)
            }
            var deg = declination + Math.toDegrees(orient[0].toDouble()).toFloat()
            // 화면이 가로면 보정
            deg += when (windowManager.defaultDisplay.rotation) { android.view.Surface.ROTATION_90 -> 90f; android.view.Surface.ROTATION_270 -> -90f; else -> 0f }
            applyHeading(deg)
        }
        override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
    }

    /** 부드럽게 따라가며, 너무 자주 돌리지 않음 (0.25초마다, 4도 넘게 바뀔 때만) */
    private fun applyHeading(deg: Float) {
        val target = ((deg % 360) + 360) % 360
        var diff = target - heading
        if (diff > 180) diff -= 360
        if (diff < -180) diff += 360
        heading = ((heading + diff * 0.25f) % 360 + 360) % 360
        val now = System.currentTimeMillis()
        var moved = heading - lastRotated
        if (moved > 180) moved -= 360
        if (moved < -180) moved += 360
        if (now - lastRotateAt < 250 || Math.abs(moved) < 4f) return
        lastRotateAt = now; lastRotated = heading
        val m = map ?: return
        if (headingOn) {
            lastProgMove = now
            mapRotation = heading
            try { m.moveCamera(CameraUpdateFactory.rotateTo(Math.toRadians(heading.toDouble()))) } catch (_: Exception) {}
        }
        updateCone()
    }

    private fun setHeading(on: Boolean) {
        headingOn = on
        if (::headingBtn.isInitialized) {
            headingBtn.background = if (on) round(C.BLUE, 12f) else glass(12f)
            headingBtn.setTextColor(if (on) Color.WHITE else C.TEXT)
        }
        val m = map
        if (on) {
            setFollow(true)
            lastProgMove = System.currentTimeMillis()
            try { m?.moveCamera(CameraUpdateFactory.tiltTo(Math.toRadians(40.0))) } catch (_: Exception) {}
            myLat?.let { la -> myLon?.let { lo -> move(la, lo, 17) } }
        } else {
            lastProgMove = System.currentTimeMillis()
            lastRotated = 999f
            mapRotation = 0f
            try { m?.moveCamera(CameraUpdateFactory.rotateTo(0.0)); m?.moveCamera(CameraUpdateFactory.tiltTo(0.0)) } catch (_: Exception) {}
        }
    }
    private lateinit var hint: TextView
    private lateinit var followBtn: ImageView
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
    /** 진짜 총관리자인지 (서버 확인) */
    var realAdmin = false
    /** 지금 관리자 화면으로 보는지: 총관리자 + 맨 위 '관리자' 스위치 켜짐. 끄면 일반 사용자 화면 */
    val isAdmin: Boolean get() = realAdmin && prefs.adminView
    /** 제휴 가게 (모든 사용자에게 보임) */
    var partners: List<Partner> = emptyList()
    /** 제휴 가게 자리 정하는 중이면 그 가게 (등록·수정 창으로 돌아감) */
    private var partnerDraft: Partner? = null
    private var photoCallback: ((android.net.Uri?) -> Unit)? = null
    private val photoPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        photoCallback?.invoke(uri); photoCallback = null
    }

    fun pickPhoto(cb: (android.net.Uri?) -> Unit) {
        photoCallback = cb
        photoPicker.launch("image/*")
    }

    suspend fun reloadPartners() {
        partners = try { Partners.all() } catch (e: Exception) { if (isAdmin) toast("제휴 업체를 못 불러왔어요: ${e.message}"); partners }
        updateWidget()
        drawToilets()
    }

    /** 관리자: 제휴 가게 자리를 지도에서 핀으로 정하기 */
    fun startPartnerPlacing(draft: Partner) {
        val (lat, lon) = if (draft.lat != 0.0) draft.lat to draft.lon else center()
        startPlacing(null, lat, lon)
        partnerDraft = draft
        hint.text = "지도를 움직여 빨간 핀을 제휴 가게 자리에 맞추세요"
    }

    private val labelTargets = HashMap<String, Any>()
    private val toiletLabels = mutableListOf<Label>()

    private var following = false
    private var followTouches = 0       // 따라가기 중 지도를 민 횟수 (2번이면 꺼짐)
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
        startMap()
        if (hasLoc()) startLocation() else permLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    placing -> exitPlacing()
                    routeTarget != null -> clearRoute()
                    else -> confirm("앱을 종료할까요?", "나만의 화장실을 닫아요.", "종료") { finish() }
                }
            }
        })
        lifecycleScope.launch {
            if (Cloud.loggedIn) afterLogin(quiet = true) else reloadMine()
            refreshUnavailable()
            reloadPartners()
            checkUpdate(silent = true)
        }
        handler.postDelayed(wiggle, 500)
        takeRouteIntent(intent)
    }

    // ---------------- 화면 ----------------

    private fun buildUi() {
        val root = FrameLayout(this)
        mapView = MapView(this)
        root.addView(mapView, FrameLayout.LayoutParams(-1, -1))

        circle = CircleOverlay(this).apply { show = prefs.circleOnly }
        root.addView(circle, FrameLayout.LayoutParams(-1, -1))

        // 핀 끝이 화면 정가운데(= 저장될 자리)에 오도록 위로 올림
        val (pinBmp, tipY) = Icons.placePin(this)
        centerPin = ImageView(this).apply {
            setImageBitmap(pinBmp)
            visibility = View.GONE
            translationY = pinBmp.height / 2f - tipY
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
        adminSwitch = text("관리자 ON", 11f, Color.WHITE, 800, Gravity.CENTER).apply {
            visibility = View.GONE
            setPadding(dp(9), dp(5), dp(9), dp(5))
            setOnClickListener {
                prefs.adminView = !prefs.adminView
                toast(if (prefs.adminView) "관리자 화면으로 바꿨어요" else "일반 사용자 화면으로 바꿨어요")
                refreshQuickBar(); drawToilets(); onCameraEnd()
            }
        }
        bar.add(adminSwitch)
        bar.add(text("☰", 22f, C.TEXT, 700, Gravity.CENTER).apply {
            setPadding(dp(10), dp(2), dp(10), dp(2))
            setOnClickListener { Sheets.menu(this@MainActivity) }
        })
        // 관리자 퀵바: 켜진 종류만 지도에 보이기 (관리자에게만 보임)
        quickBar = hbox(5).apply { visibility = View.GONE; setPadding(0, dp(6), 0, 0) }
        titleBox.add(quickBar)
        topBox.add(bar)
        addBtn = primaryBtn("＋ 화장실 추가") { addAtMapCenter() }.apply { elevation = dpf(3f) }
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
        // 파란 동그라미 + 흰 선 아이콘 (C안)
        fun rbtn(icon: Int, desc: String, onClick: () -> Unit) = ImageView(this).apply {
            setImageResource(icon)
            contentDescription = desc
            scaleType = ImageView.ScaleType.CENTER
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(C.BLUE) }
            elevation = dpf(4f)
            setOnClickListener { onClick() }
        }
        // 버튼 아래 흰 알약 글자 (무슨 버튼인지 바로 알게)
        fun add(btn: View, label: String) {
            rightCol.addView(btn, LinearLayout.LayoutParams(dp(42), if (btn is LinearLayout) -2 else dp(42)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            rightCol.addView(text(label, 11f, C.BLUE_DARK, 600, Gravity.CENTER).apply {
                background = round(Color.WHITE, 99f)
                elevation = dpf(2f)
                setPadding(dp(7), dp(1), dp(7), dp(1))
            }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(3); bottomMargin = dp(7) })
        }
        add(rbtn(R.drawable.ic_btn_search, "위치 검색") { searchPlace() }, "검색")
        // ＋/－는 한 덩어리 (자리를 덜 차지하게)
        val zoomPill = vbox(0).apply {
            background = round(C.BLUE, 21f)
            elevation = dpf(4f)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        fun zbtn(icon: Int, desc: String, inOut: Boolean) = ImageView(this).apply {
            setImageResource(icon); contentDescription = desc; scaleType = ImageView.ScaleType.CENTER
            setOnClickListener { zoom(inOut) }
        }
        zoomPill.addView(zbtn(R.drawable.ic_btn_plus, "확대", true), LinearLayout.LayoutParams(dp(42), dp(40)))
        zoomPill.addView(View(this).apply { setBackgroundColor(0x88FFFFFF.toInt()) }, LinearLayout.LayoutParams(dp(24), 1))
        zoomPill.addView(zbtn(R.drawable.ic_btn_minus, "축소", false), LinearLayout.LayoutParams(dp(42), dp(40)))
        add(zoomPill, "확대·축소")
        add(rbtn(R.drawable.ic_btn_locate, "내 위치") { goToMe() }, "내 위치")
        followBtn = rbtn(R.drawable.ic_btn_follow, "따라가기") { setFollow(!following) }
        add(followBtn, "따라가기")
        add(rbtn(R.drawable.ic_btn_list, "가까운 화장실 목록") { Sheets.list(this) }, "가까운\n화장실")
        // 버튼 줄은 '화장실 추가' 버튼 바로 아래부터 (위쪽과 겹치지 않게)
        val colLp = FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply { marginEnd = dp(12) }
        root.addView(rightCol, colLp)
        topBox.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val top = v.bottom + dp(10)
            if (colLp.topMargin != top) { colLp.topMargin = top; rightCol.layoutParams = colLp }
        }

        // 아래쪽
        val bottom = vbox(8).apply { setPadding(dp(12), 0, dp(12), dp(12)) }
        hint = text("두 손가락으로 확대·축소", 13f, C.BLUE_DARK, 600, Gravity.CENTER).apply {
            background = glass(14f)
            setPadding(dp(12), dp(7), dp(12), dp(7))
        }
        urgentBtn = text("🚨 급해요", 17f, Color.WHITE, 800, Gravity.CENTER).apply {
            background = round(C.RED, 28f)
            elevation = dpf(4f)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setOnClickListener { urgent() }
        }
        bottom.addView(urgentBtn, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.START })
        bottom.addView(hint, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        placeBar = hbox(8).apply { visibility = View.GONE }
        placeBar.add(btn("🔍 검색", C.GLASS) { searchPlace() }, 1f)
        placeBar.add(btn("취소", C.GLASS) { exitPlacing() }, 1f)
        placeBar.add(btn("이 위치로 정하기", C.RED, Color.WHITE) { confirmPlace() }, 2f)
        bottom.add(placeBar)
        // 길 보기 중일 때 아래 막대
        routeBar = vbox(8).apply { visibility = View.GONE }
        routeInfo = text("", 14f, C.TEXT, 700, Gravity.CENTER).apply {
            background = glass(14f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        routeBar.add(routeInfo)
        val rb = hbox(8)
        rb.add(btn("✕ 길 지우기", C.GLASS) { clearRoute() }, 1f)
        headingBtn = btn("🧭 방향", C.GLASS) { setHeading(!headingOn) }
        rb.add(headingBtn)
        routeKakaoBtn = btn("카카오맵으로 안내", C.BLUE, Color.WHITE) {
            routeTarget?.let { (la, lo, n) ->
                val app = Uri.parse("kakaomap://route?sp=${myLat},${myLon}&ep=$la,$lo&by=${if (routeCar) "CAR" else "FOOT"}")
                try { startActivity(Intent(Intent.ACTION_VIEW, app)) }
                catch (_: Exception) { open("https://map.kakao.com/link/to/${Uri.encode(n)},$la,$lo") }
            }
        }
        rb.add(routeKakaoBtn, 1f)
        routeBar.add(rb)
        bottom.add(routeBar)
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
                showMapError("지도를 열지 못했어요. 인터넷 연결을 확인해 주세요")
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
            showMapError("지도 준비 중 오류")
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
                if (following && System.currentTimeMillis() - lastProgMove > 900) {
                    // 한 번 밀면 그냥 두고(다시 내 위치로), 두 번째에 따라가기 끄기
                    followTouches++
                    if (followTouches >= 2) setFollow(false)
                    else hint.text = "한 번 더 밀면 따라가기가 꺼져요"
                }
            }
        }
        m.setOnCameraMoveEndListener { _, _, _ ->
            runOnUiThread {
                onCameraEnd()
                // 따라가기 중 한 번 밀었으면 내 위치로 되돌림
                if (following && followTouches == 1) myLat?.let { la -> myLon?.let { lo -> move(la, lo) } }
            }
        }
        // 위치가 지도보다 먼저 잡혔으면 여기서 내 위치로 이동
        myLat?.let { la -> myLon?.let { lo -> updateMe(); move(la, lo, 16) } }
        tryPendingRoute()
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
            showMapError("화장실 표시 중 오류")
            return
        }
        if (!showPublic) { publics = emptyList(); drawToilets(); return }
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
                showMapError("화장실 표시 중 오류")
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

        // 넓게 볼 때(반경 300m 넘음): 가까운 것끼리 종류별 숫자 동그라미로 묶기
        if (radiusM > CLUSTER_M) {
            drawClusters(layer, clat, clon, limit)
            return
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
            if (!showGroup) break
            if (Geo.dist(clat, clon, t.lat, t.lon) > limit) continue
            addLabel("g_${t.groupId}_${t.id}", t.lat, t.lon, C.PURPLE, "그룹 · ${Geo.agoShort(t.updated)}", false, 0, t); shown++
        }
        for (t in mine) {
            if (!showMine) break
            if (Geo.dist(clat, clon, t.lat, t.lon) > limit) continue
            addLabel("m_${t.id}", t.lat, t.lon, C.ORANGE, "나만 보기 · ${Geo.agoShort(t.updated)}", false, 0, t); shown++
        }
        // 제휴 가게: 화장실처럼 보고 있는 범위 안만, 크게
        for (p in partners) {
            if (!showPartner) break
            if (Geo.dist(clat, clon, p.lat, p.lon) > limit) continue
            val (bmp, ay) = Icons.partnerPin(this, p.bitmap(), "제휴 · ${p.name}", scale)
            val id = "pt_${p.id}"
            val opt = LabelOptions.from(id, LatLng.from(p.lat, p.lon))
                .setStyles(LabelStyles.from(LabelStyle.from(bmp).setAnchorPoint(0.5f, ay)))
                .setClickable(true)
                .setRank(100L)
            layer.addLabel(opt)?.let { toiletLabels.add(it); labelTargets[id] = p }
            Partners.countView(p.id)
            shown++
        }
        countText.text = if (prefs.circleOnly) "원 안 화장실 ${shown}곳" else "근처 화장실 ${shown}곳"
        unavailChip.text = "근처 사용불가 ${unavailIn}곳"
        unavailChip.visibility = if (unavailIn > 0) View.VISIBLE else View.GONE
    }

    /** 숫자 동그라미를 누르면 그 자리로 확대 */
    private class Cluster(val lat: Double, val lon: Double)

    private fun drawClusters(layer: com.kakao.vectormap.label.LabelLayer, clat: Double, clon: Double, limit: Double) {
        // 종류: 0 공공(회색) 1 내 것(주황) 2 그룹(보라) 3 제휴(초록)
        val items = ArrayList<Triple<Int, Double, Double>>()
        var unavailIn = 0
        for (p in publics) if (Geo.dist(clat, clon, p.lat, p.lon) <= limit) {
            items.add(Triple(0, p.lat, p.lon)); if ((unavail[p.id]?.count ?: 0) > 0) unavailIn++
        }
        if (showMine) for (t in mine) if (Geo.dist(clat, clon, t.lat, t.lon) <= limit) items.add(Triple(1, t.lat, t.lon))
        if (showGroup) for (t in groupToilets) if (Geo.dist(clat, clon, t.lat, t.lon) <= limit) items.add(Triple(2, t.lat, t.lon))
        if (showPartner) for (p in partners) if (Geo.dist(clat, clon, p.lat, p.lon) <= limit) { items.add(Triple(3, p.lat, p.lon)); Partners.countView(p.id) }

        // 원 지름을 약 5칸으로 나눈 격자
        val cellM = radiusM / 2.5
        val dLat = cellM / 111000.0
        val dLon = cellM / (111000.0 * Math.cos(Math.toRadians(clat)))
        val groups = items.groupBy { Triple(it.first, Math.floor(it.second / dLat).toInt(), Math.floor(it.third / dLon).toInt()) }
        val colors = intArrayOf(C.GRAY, C.ORANGE, C.PURPLE, C.GREEN)
        var i = 0
        for ((key, list) in groups) {
            val lat = list.sumOf { it.second } / list.size
            val lon = list.sumOf { it.third } / list.size
            val bmp = Icons.countBubble(this, colors[key.first], list.size)
            val id = "c_${i++}"
            val opt = LabelOptions.from(id, LatLng.from(lat, lon))
                .setStyles(LabelStyles.from(LabelStyle.from(bmp).setAnchorPoint(0.5f, 0.5f)))
                .setClickable(true)
                .setRank(if (key.first == 0) 1L else 50L)
            layer.addLabel(opt)?.let { toiletLabels.add(it); labelTargets[id] = Cluster(lat, lon) }
        }
        countText.text = if (prefs.circleOnly) "원 안 화장실 ${items.size}곳" else "근처 화장실 ${items.size}곳"
        unavailChip.text = "근처 사용불가 ${unavailIn}곳"
        unavailChip.visibility = if (unavailIn > 0) View.VISIBLE else View.GONE
    }

    private fun onLabelClick(label: Label) {
        if (placing) return
        when (val t = labelTargets[label.labelId]) {
            is Cluster -> {
                val z = (map?.cameraPosition?.zoomLevel ?: 13) + 2
                move(t.lat, t.lon, z)
            }
            is Toilet -> Sheets.toiletDetail(this, t)
            is PublicToilet -> Sheets.publicDetail(this, t)
            is Partner -> Sheets.partnerDetail(this, t)
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
            if (first) tryPendingRoute()
            declination = android.hardware.GeomagneticField(l.latitude.toFloat(), l.longitude.toFloat(), l.altitude.toFloat(), l.time).declination
            // 걷는 속도(약 1m/s)부터 GPS 진행 방향을 씀. 방향 오차가 크면(30도+) 나침반 유지
            if (coneWanted && l.hasBearing() && l.hasSpeed() && l.speed > 0.8f &&
                (Build.VERSION.SDK_INT < 26 || !l.hasBearingAccuracy() || l.bearingAccuracyDegrees < 30f)) {
                gpsBearingAt = System.currentTimeMillis()
                applyHeading(l.bearing)
            }
            try { updateMe() } catch (e: Exception) {
                showMapError("내 위치 표시 중 오류")
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
        // 마지막으로 알던 위치로 바로 시작 (새 위치를 기다리지 않게)
        // 단, 2분 안의 위치만 (오래된 위치면 엉뚱한 곳으로 가니 새 위치를 기다림)
        fused.lastLocation.addOnSuccessListener { l ->
            if (l != null && myLat == null && System.currentTimeMillis() - l.time < 120_000)
                locCallback.onLocationResult(LocationResult.create(listOf(l)))
        }
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
            meLabel = layer.addLabel(LabelOptions.from("me", LatLng.from(lat, lon)).setStyles(meStyles!![0]).setRank(10L))
        } else {
            existing.moveTo(LatLng.from(lat, lon))
        }
        if (coneWanted) updateCone()
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
        followTouches = 0
        // 따라가는 중: 진한 남색 + 흰 테두리
        followBtn.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(if (on) C.BLUE_TEXT else C.BLUE)
            if (on) setStroke(dp(3), Color.WHITE)
        }
        syncCompass()
        if (on) { goToMe(); hint.text = "내 위치를 따라가는 중 · 지도를 두 번 밀면 꺼져요" }
        else if (!placing) hint.text = "두 손가락으로 확대·축소"
    }

    // ---------------- 급해요 ----------------

    /** 가장 가까운 화장실(내 것 + 그룹 + 공중, 2km 안)로 바로 도보 길찾기 */
    private fun urgent() {
        val lat = myLat
        val lon = myLon
        if (lat == null || lon == null) { goToMe(); return }
        lifecycleScope.launch {
            busy(true)
            val pubs = ArrayList<PublicToilet>()
            if (showPublic) try {
                for (c in Geo.cellsAround(lat, lon, 2000.0)) pubs.addAll(Cloud.publicCell(c))
            } catch (_: Exception) {}
            busy(false)
            val cands = ((if (showMine) mine else emptyList()) + (if (showGroup) groupToilets else emptyList())).map { Triple(it.lat, it.lon, it.title()) } +
                (if (showPartner) partners else emptyList()).map { Triple(it.lat, it.lon, it.name) } +
                pubs.filter { (unavail[it.id]?.count ?: 0) == 0 }.map { Triple(it.lat, it.lon, it.name) }
            val best = cands.minByOrNull { Geo.dist(lat, lon, it.first, it.second) }
            if (best == null || Geo.dist(lat, lon, best.first, best.second) > 2000) {
                toast("2km 안에 화장실을 찾지 못했어요"); return@launch
            }
            val s = Sheet(this@MainActivity, "🚨 가장 가까운 화장실로 안내")
            s.add(text("${best.third} · ${Geo.fmt(Geo.dist(lat, lon, best.first, best.second))}", 14f, C.TEXT2))
            val row = hbox(8)
            row.add(btn("🚗 차량 이동", C.CAR, Color.WHITE) { s.dismiss(); showRoute(best.first, best.second, best.third, car = true) }, 1f)
            row.add(btn("🚶 도보 이동", C.BLUE, Color.WHITE) { s.dismiss(); showRoute(best.first, best.second, best.third, car = false) }, 1f)
            s.add(row)
            s.add(btn("취소") { s.dismiss() })
            s.show()
        }
    }

    // ---------------- 위젯 ----------------

    /** 위젯용 내 화장실 목록 저장 (거리는 위젯 '새로고침' 때만 계산) */
    fun updateWidget() {
        NearestWidget.saveList(this, (mine + groupToilets).distinctBy { it.id }, partners)
    }

    private fun takeRouteIntent(i: Intent?) {
        val lat = i?.getDoubleExtra(NearestWidget.EXTRA_LAT, 0.0) ?: 0.0
        val lon = i?.getDoubleExtra(NearestWidget.EXTRA_LON, 0.0) ?: 0.0
        val name = i?.getStringExtra(NearestWidget.EXTRA_NAME) ?: return
        if (lat == 0.0) return
        i.removeExtra(NearestWidget.EXTRA_NAME)
        pendingRoute = Triple(lat, lon, name)
        tryPendingRoute()
    }

    private fun tryPendingRoute() {
        val r = pendingRoute ?: return
        if (map == null || myLat == null) return
        pendingRoute = null
        val s = Sheet(this, "${r.third} 길찾기")
        val row = hbox(8)
        row.add(btn("🚗 차량 길찾기", C.CAR, Color.WHITE) { s.dismiss(); showRoute(r.first, r.second, r.third, car = true) }, 1f)
        row.add(btn("🚶 도보 길찾기", C.BLUE, Color.WHITE) { s.dismiss(); showRoute(r.first, r.second, r.third, car = false) }, 1f)
        s.add(row)
        s.show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeRouteIntent(intent)
    }

    // ---------------- 길 보기 ----------------

    /** 내 위치 → 화장실까지 길을 지도에 선으로 (도보: 파란 선, 차량: 검은 선, 길을 못 찾으면 회색 곧은 선) */
    fun showRoute(lat: Double, lon: Double, name: String, car: Boolean = false) {
        val m = map ?: return
        val sLat = myLat
        val sLon = myLon
        if (sLat == null || sLon == null) { goToMe(); return }
        clearRoute()
        routeTarget = Triple(lat, lon, name)
        routeCar = car
        setFollow(false)   // 길찾기 동안 나침반으로 부채꼴 방향을 그림 (syncCompass)
        lifecycleScope.launch {
            busy(true)
            val r = try { Walk.route(sLat, sLon, lat, lon, car) } catch (_: Exception) { null } finally { busy(false) }
            val pts = r?.points ?: listOf(sLat to sLon, lat to lon)
            val dist = r?.meters ?: Geo.dist(sLat, sLon, lat, lon)
            val min = ((r?.seconds ?: (dist / if (car) 8.0 else 1.2)) / 60).toInt().coerceAtLeast(1)
            try {
                val layer = m.routeLineManager?.layer ?: return@launch
                val styles = com.kakao.vectormap.route.RouteLineStylesSet.from(
                    "walk",
                    com.kakao.vectormap.route.RouteLineStyles.from(
                        com.kakao.vectormap.route.RouteLineStyle.from(dpf(6f), if (r == null) C.GRAY else if (car) C.CAR else C.BLUE)
                    )
                )
                val seg = com.kakao.vectormap.route.RouteLineSegment.from(pts.map { LatLng.from(it.first, it.second) })
                    .setStyles(styles.getStyles(0))
                routeLine = layer.addRouteLine(com.kakao.vectormap.route.RouteLineOptions.from(seg).setStylesSet(styles))
                lastProgMove = System.currentTimeMillis()
                m.moveCamera(CameraUpdateFactory.fitMapPoints(pts.map { LatLng.from(it.first, it.second) }.toTypedArray(), dp(80)))
                // 전체 길을 잠깐 보여 준 뒤, 내비처럼 내가 보는 방향으로
                handler.postDelayed({ if (routeTarget != null) setHeading(true) }, 1800)
            } catch (e: Exception) {
                showMapError("길을 그리지 못했어요")
            }
            routeInfo.text = (if (car) "🚗 $name 까지 ${Geo.fmt(dist)} · 차로 약 ${min}분" else "🚶 $name 까지 ${Geo.fmt(dist)} · 걸어서 약 ${min}분") + if (r == null) "\n(길 정보를 못 받아 곧은 선으로 보여요)" else ""
            routeKakaoBtn.background = round(if (car) C.CAR else C.BLUE, 14f)
            routeBar.visibility = View.VISIBLE
            addBtn.visibility = View.GONE
            urgentBtn.visibility = View.GONE
        }
    }

    fun clearRoute() {
        if (headingOn) setHeading(false)
        routeLine?.let { l -> try { map?.routeLineManager?.layer?.remove(l) } catch (_: Exception) {} }
        routeLine = null
        routeTarget = null
        syncCompass()
        if (::routeBar.isInitialized) routeBar.visibility = View.GONE
        if (!placing) { addBtn.visibility = View.VISIBLE; urgentBtn.visibility = View.VISIBLE }
    }

    /** 개인정보처리방침: 앱 안에 넣어 둔 페이지를 내용만 보여 줌 */
    fun showPrivacy() {
        val d = android.app.Dialog(this, android.R.style.Theme_Material_Light_NoActionBar)
        val box = vbox(0)
        val head = hbox(8).apply { setPadding(dp(16), dp(12), dp(8), dp(8)) }
        head.add(text("개인정보처리방침", 18f, C.TEXT, 800), 1f)
        head.add(text("✕", 20f, C.TEXT2).apply { setPadding(dp(10), dp(4), dp(10), dp(4)); setOnClickListener { d.dismiss() } })
        box.add(head)
        val web = android.webkit.WebView(this).apply {
            settings.javaScriptEnabled = false
            loadUrl("file:///android_asset/privacy.html")
        }
        box.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        d.setContentView(box)
        d.show()
    }

    // ---------------- 장소 검색 ----------------

    /** 주소·장소 이름으로 찾아서 지도를 그 자리로 옮김 (폰 내장 검색, 키 필요 없음) */
    fun searchPlace() {
        val input = android.widget.EditText(this).apply {
            hint = "예: 의왕시청, 호계동 123, 강남역"
            setSingleLine()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("위치 검색")
            .setView(box)
            .setPositiveButton("검색", null)
            .setNegativeButton("닫기", null)
            .create()
        fun go() {
            val q = input.text.toString().trim()
            if (q.isEmpty()) return
            dlg.dismiss()
            runSearch(q)
        }
        input.setOnEditorActionListener { _, _, _ -> go(); true }
        dlg.setOnShowListener {
            dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { go() }
            input.requestFocus()
            dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dlg.show()
    }

    @Suppress("DEPRECATION")
    private fun runSearch(q: String) {
        lifecycleScope.launch {
            busy(true)
            val found = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    // 한국 안에서만 찾기
                    android.location.Geocoder(this@MainActivity, java.util.Locale.KOREA)
                        .getFromLocationName(q, 8, 33.0, 124.5, 38.7, 131.9) ?: emptyList()
                }
            } catch (e: Exception) {
                toast("검색하지 못했어요: ${e.message ?: "인터넷 연결 확인"}")
                null
            } finally {
                busy(false)
            }
            if (found == null) return@launch
            if (found.isEmpty()) { toast("'$q' 를 찾지 못했어요. 주소나 다른 이름으로 해 보세요"); return@launch }
            val go = { a: android.location.Address ->
                setFollow(false)
                move(a.latitude, a.longitude, 17)
            }
            if (found.size == 1) { go(found[0]); return@launch }
            val names = found.map { a ->
                (a.featureName?.takeIf { it != a.getAddressLine(0) && !it.all(Char::isDigit) }?.let { "$it\n" } ?: "") +
                    (a.getAddressLine(0) ?: "${a.latitude}, ${a.longitude}")
            }.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("'$q' 검색 결과")
                .setItems(names) { _, i -> go(found[i]) }
                .setNegativeButton("닫기", null)
                .show()
        }
    }

    // ---------------- 화장실 추가 · 위치 옮기기 ----------------

    /** 지금 보고 있는 지도 자리에서 시작 (검색해서 옮긴 곳 그대로). 지도가 아직 없으면 내 위치 */
    private fun addAtMapCenter() {
        if (map != null) {
            val (lat, lon) = center()
            startPlacing(null, lat, lon)
            return
        }
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
        urgentBtn.visibility = View.GONE
        rightCol.visibility = View.GONE
        hint.text = "지도를 움직여 빨간 표시를 화장실 자리에 맞추세요"
    }

    fun exitPlacing() {
        placing = false
        movingTarget = null
        partnerDraft = null
        centerPin.visibility = View.GONE
        placeBar.visibility = View.GONE
        addBtn.visibility = View.VISIBLE
        urgentBtn.visibility = View.VISIBLE
        rightCol.visibility = View.VISIBLE
        hint.text = "두 손가락으로 확대·축소"
    }

    private fun confirmPlace() {
        val (lat, lon) = center()
        partnerDraft?.let { d ->
            d.lat = lat; d.lon = lon
            exitPlacing()
            Sheets.partnerEdit(this, d)
            return
        }
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
        updateWidget()
    }

    /** 저장 (범위가 바뀌면 예전 자리에서 지움) */
    /** touch=false 면 '최근 수정' 날짜를 바꾸지 않음 (공유 스위치만 바꿀 때) */
    suspend fun saveToilet(t: Toilet, oldScope: String?, oldGroup: String?, touch: Boolean = true) {
        busy(true)
        try {
            if (touch) t.updated = System.currentTimeMillis()
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

    // 사용불가 신고 목록은 앱을 켤 때 한 번만 읽음 (서버 무료 한도 아끼기).
    // 내가 신고하거나 '다시 열렸어요'를 누르면 그때도 새로 읽음.

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
            realAdmin = Cloud.isAdmin()
            refreshQuickBar()
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
        syncCompass()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(compass)
        mapView.pause()
    }

    override fun onStop() {
        super.onStop()
        // 제휴 가게 통계를 모아서 한 번에 올림
        lifecycleScope.launch { Partners.flush() }
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
