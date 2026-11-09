package kr.mytoilet.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/** 화면 가운데 고정된 큰 원 + 반경 표시 */
class CircleOverlay(ctx: Context) : View(ctx) {
    var label: String = "반경"
        set(v) { field = v; invalidate() }
    var show = true
        set(v) { field = v; invalidate() }

    private val d = ctx.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14378ADD }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C.BLUE; style = Paint.Style.STROKE; strokeWidth = 2.5f * d
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.BLUE }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C.BLUE_TEXT; textSize = 13 * d; textAlign = Paint.Align.CENTER; typeface = Fonts.bold
    }

    /** 원 반지름(화면 픽셀) */
    fun radiusPx(): Float = min(width, height) * 0.36f

    override fun onDraw(c: Canvas) {
        if (!show) return
        val cx = width / 2f
        val cy = height / 2f
        val r = radiusPx()
        c.drawCircle(cx, cy, r, fill)
        c.drawCircle(cx, cy, r, line)
        val w = tp.measureText(label) + 20 * d
        val box = RectF(cx - w / 2, cy - r - 13 * d, cx + w / 2, cy - r + 13 * d)
        c.drawRoundRect(box, 12 * d, 12 * d, white)
        c.drawRoundRect(box, 12 * d, 12 * d, line)
        val fm = tp.fontMetrics
        c.drawText(label, cx, box.centerY() - (fm.ascent + fm.descent) / 2, tp)
        c.drawCircle(cx, cy, 3 * d, dot)
    }
}
