package kr.mytoilet.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.materialswitch.MaterialSwitch

/** 색 */
object C {
    const val BLUE = 0xFF378ADD.toInt()
    const val ORANGE = 0xFFF27A1A.toInt()
    const val ORANGE_BG = 0xFFFDEBDC.toInt()
    const val ORANGE_TEXT = 0xFFB4540A.toInt()
    const val PURPLE = 0xFF7F56D9.toInt()   // 그룹 화장실 표시
    const val PURPLE_BG = 0xFFEFE9FB.toInt()
    const val PURPLE_TEXT = 0xFF5B3BB0.toInt()
    const val CAR = 0xFF2C2C2A.toInt()     // 차량 길찾기   // 나만 보기 화장실 표시
    const val BLUE_DARK = 0xFF185FA5.toInt()
    const val BLUE_TEXT = 0xFF0C447C.toInt()
    const val BLUE_BG = 0xFFE6F1FB.toInt()
    const val GREEN = 0xFF1D9E75.toInt()
    const val GREEN_TEXT = 0xFF085041.toInt()
    const val GREEN_BG = 0xFFE1F5EE.toInt()
    const val RED = 0xFFE24B4A.toInt()
    const val RED_TEXT = 0xFFA32D2D.toInt()
    const val RED_BG = 0xFFFCEBEB.toInt()
    const val AMBER = 0xFFEF9F27.toInt()
    const val AMBER_BG = 0xFFFAEEDA.toInt()
    const val AMBER_TEXT = 0xFF854F0B.toInt()
    const val GRAY = 0xFF888780.toInt()
    const val GRAY_BG = 0xFFF1EFE8.toInt()
    const val TEXT = 0xFF1F1F1D.toInt()
    const val TEXT2 = 0xFF5F5E5A.toInt()
    const val TEXT3 = 0xFF8A8983.toInt()
    const val GLASS = 0xE6FFFFFF.toInt()
    const val GLASS_LINE = 0xCCFFFFFF.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density
fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

/** 리퀴드 글라스 느낌 배경 (반투명 흰색 + 밝은 테두리) */
fun Context.glass(radius: Float = 18f, color: Int = C.GLASS, topOnly: Boolean = false): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        val r = dpf(radius)
        cornerRadii = if (topOnly) floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        else floatArrayOf(r, r, r, r, r, r, r, r)
        setStroke(dp(1), C.GLASS_LINE)
    }

fun Context.round(color: Int, radius: Float = 10f, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpf(radius)
        if (stroke > 0) setStroke(dp(stroke), strokeColor)
    }

fun Context.text(
    s: String, size: Float = 15f, color: Int = C.TEXT, weight: Int = 400,
    gravity: Int = Gravity.START
): TextView = TextView(this).apply {
    text = s
    textSize = size
    setTextColor(color)
    typeface = when {
        weight >= 700 -> Fonts.bold
        weight >= 600 -> Fonts.semi
        else -> Fonts.regular
    }
    this.gravity = gravity
    setLineSpacing(0f, 1.15f)
}

/** 일반 버튼 (누르면 바로 실행) */
fun Context.btn(
    s: String, bg: Int = C.GRAY_BG, fg: Int = C.TEXT, stroke: Int = 0,
    onClick: () -> Unit
): TextView = text(s, 15f, fg, 600, Gravity.CENTER).apply {
    background = round(bg, 12f, if (stroke != 0) 1 else 0, stroke)
    minHeight = dp(46)
    setPadding(dp(14), dp(10), dp(14), dp(10))
    isClickable = true
    isFocusable = true
    setOnClickListener { onClick() }
}

fun Context.primaryBtn(s: String, onClick: () -> Unit) = btn(s, C.BLUE, Color.WHITE, 0, onClick)

fun Context.chip(s: String, bg: Int, fg: Int): TextView = text(s, 12f, fg, 600).apply {
    background = round(bg, 7f)
    setPadding(dp(8), dp(3), dp(8), dp(3))
}

fun Context.vbox(gap: Int = 10): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
    dividerDrawable = GradientDrawable().apply { setSize(1, dp(gap)) }
}

fun Context.hbox(gap: Int = 8): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
    dividerDrawable = GradientDrawable().apply { setSize(dp(gap), 1) }
}

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
    LinearLayout.LayoutParams(w, h, weight)

fun LinearLayout.add(v: View, weight: Float = 0f): View {
    if (orientation == LinearLayout.HORIZONTAL && weight > 0f) addView(v, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight))
    else if (orientation == LinearLayout.HORIZONTAL) addView(v, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
    else addView(v, lp())
    return v
}

/** 입력칸 */
fun Context.field(label: String, hint: String, value: String = "", multi: Boolean = false): Pair<LinearLayout, EditText> {
    val box = vbox(4)
    box.add(text(label, 12f, C.TEXT2, 600))
    val et = EditText(this).apply {
        setText(value)
        this.hint = hint
        textSize = 15f
        typeface = Fonts.regular
        setTextColor(C.TEXT)
        background = round(0xFFFFFFFF.toInt(), 12f, 1, 0x22000000)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        inputType = if (multi) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        else InputType.TYPE_CLASS_TEXT
        if (!multi) maxLines = 1
    }
    box.add(et)
    return box to et
}

/** 켜기/끄기 토글 줄 */
fun Context.toggleRow(label: String, on: Boolean, sub: String? = null, onChange: (Boolean) -> Unit): LinearLayout {
    val row = hbox(10)
    val texts = vbox(2)
    texts.add(text(label, 15f, C.TEXT, 600))
    if (sub != null) texts.add(text(sub, 12f, C.TEXT3))
    row.add(texts, 1f)
    val sw = MaterialSwitch(this).apply {
        isChecked = on
        setOnCheckedChangeListener { _, c -> onChange(c) }
    }
    row.add(sw)
    return row
}

/** 여러 개 중 하나 고르기 토글 (붙어 있는 칸) */
fun Context.segmented(options: List<String>, selected: Int, enabled: List<Boolean>? = null, onPick: (Int) -> Unit): LinearLayout {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = round(0x14000000, 12f)
        setPadding(dp(3), dp(3), dp(3), dp(3))
    }
    val views = mutableListOf<TextView>()
    fun paint(i: Int) {
        views.forEachIndexed { j, v ->
            val en = enabled?.getOrNull(j) ?: true
            v.background = if (j == i) round(0xFFFFFFFF.toInt(), 10f) else null
            v.setTextColor(if (!en) C.TEXT3 else if (j == i) C.BLUE_TEXT else C.TEXT2)
            v.typeface = if (j == i) Fonts.bold else Fonts.regular
        }
    }
    options.forEachIndexed { i, o ->
        val v = text(o, 14f, C.TEXT2, 400, Gravity.CENTER).apply {
            setPadding(0, dp(9), 0, dp(9))
            setOnClickListener {
                if (enabled?.getOrNull(i) == false) return@setOnClickListener
                paint(i); onPick(i)
            }
        }
        views.add(v)
        box.addView(v, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
    paint(selected)
    return box
}

/** 아래에서 올라오는 유리창(모달) */
/** 아래에서 올라오는 창. half=true 면 화면 절반 높이로 고정하고 내용만 안에서 스크롤 */
class Sheet(val activity: Activity, title: String?, half: Boolean = false) {
    val dialog = BottomSheetDialog(activity)
    val body: LinearLayout = activity.vbox(12)

    init {
        val a = activity
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = a.glass(26f, 0xF2FFFFFF.toInt(), topOnly = true)
            setPadding(a.dp(18), a.dp(10), a.dp(18), a.dp(24))
        }
        val handle = View(a).apply { background = a.round(0x66888888, 3f) }
        root.addView(handle, LinearLayout.LayoutParams(a.dp(40), a.dp(5)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = a.dp(12)
        })
        if (title != null) {
            val head = a.hbox(8)
            head.add(a.text(title, 19f, C.TEXT, 700), 1f)
            head.add(a.text("✕", 20f, C.TEXT2).apply {
                setPadding(a.dp(8), a.dp(2), a.dp(4), a.dp(2))
                setOnClickListener { dialog.dismiss() }
            })
            root.addView(head, lp())
            root.addView(View(a), lp(h = a.dp(10)))
        }
        if (half) {
            // 제목은 고정, 목록만 안에서 스크롤
            val h = (a.resources.displayMetrics.heightPixels * 0.5f).toInt()
            root.setPadding(a.dp(18), a.dp(10), a.dp(18), 0)
            val inner = androidx.core.widget.NestedScrollView(a).apply {
                isVerticalScrollBarEnabled = true
                addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                setPadding(0, 0, 0, a.dp(24))
                clipToPadding = false
            }
            root.addView(inner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            dialog.setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
            dialog.setOnShowListener {
                val bs = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                bs?.setBackgroundColor(Color.TRANSPARENT)
                bs?.layoutParams?.height = h
                dialog.behavior.peekHeight = h
                dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
                dialog.behavior.skipCollapsed = true
            }
        } else {
            root.addView(body, lp())
            val scroll = ScrollView(a).apply {
                isFillViewport = true
                addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            dialog.setContentView(scroll)
            dialog.setOnShowListener {
                val bs = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                bs?.setBackgroundColor(Color.TRANSPARENT)
                dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
                dialog.behavior.skipCollapsed = true
            }
        }
    }

    fun add(v: View): View { body.add(v); return v }
    fun show(): Sheet {
        open.add(this)
        dialog.setOnDismissListener { open.remove(this) }
        dialog.show()
        return this
    }

    companion object {
        /** 지금 열려 있는 창들 (한 번에 닫기용) */
        val open = mutableListOf<Sheet>()
    }
    fun dismiss() = dialog.dismiss()
}

/** 열려 있는 아래 창 모두 닫기 */
fun Activity.closeSheets() {
    Sheet.open.toList().forEach { try { it.dismiss() } catch (_: Exception) {} }
    Sheet.open.clear()
}

/** 확인 창 (모달) */
fun Activity.confirm(title: String, msg: String, ok: String = "확인", danger: Boolean = false, onOk: () -> Unit) {
    val s = Sheet(this, title)
    s.add(text(msg, 15f, C.TEXT2))
    val row = hbox(8)
    row.add(btn("취소") { s.dismiss() }, 1f)
    row.add(btn(ok, if (danger) C.RED else C.BLUE, Color.WHITE) { s.dismiss(); onOk() }, 1f)
    s.add(row)
    s.show()
}

/** 글자 하나 입력 받는 창 */
fun Activity.ask(title: String, label: String, hint: String, value: String = "", onOk: (String) -> Unit) {
    val s = Sheet(this, title)
    val (box, et) = field(label, hint, value)
    s.add(box)
    val err = text("", 13f, C.RED_TEXT)
    s.add(err)
    val row = hbox(8)
    row.add(btn("취소") { s.dismiss() }, 1f)
    row.add(primaryBtn("확인") {
        val v = et.text.toString().trim()
        if (v.isEmpty()) { err.text = "내용을 입력해 주세요"; return@primaryBtn }
        s.dismiss(); onOk(v)
    }, 1f)
    s.add(row)
    s.show()
}

/** 화면 전체 글씨를 프리텐다드로 */
fun applyFont(v: View) {
    if (v is TextView && v.typeface == null) v.typeface = Fonts.regular
    if (v is ViewGroup) for (i in 0 until v.childCount) applyFont(v.getChildAt(i))
}
