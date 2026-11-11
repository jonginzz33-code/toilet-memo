package kr.mytoilet.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.max

/** 지도 표시 그림 */
object Icons {

    /** 남녀 그림 화장실 표시 + 아래 이름표. unavailable>0 이면 빨간 사용불가 표시 */
    fun toilet(ctx: Context, color: Int, label: String, unavailable: Int = 0, outline: Boolean = false, scale: Float = 1f): Bitmap {
        val d = ctx.resources.displayMetrics.density * scale
        val red = unavailable > 0
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11 * d
            typeface = Fonts.bold
            textAlign = Paint.Align.CENTER
            this.color = if (red) Color.WHITE else if (outline) C.TEXT2 else color
        }
        val boxW = 44 * d
        val boxH = 34 * d
        val ring = if (red) 12 * d else 0f
        val labelW = labelPaint.measureText(label) + 14 * d
        val labelH = 19 * d
        val w = max(boxW + ring * 2, labelW) + 8 * d
        val h = ring + boxH + 3 * d + labelH + 8 * d + ring
        val bmp = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = w / 2
        val top = ring + 4 * d
        val box = RectF(cx - boxW / 2, top, cx + boxW / 2, top + boxH)

        if (red) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 3 * d; this.color = 0x66E24B4A
            }
            c.drawCircle(cx, box.centerY(), boxW / 2 + ring - 2 * d, p)
        }

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = if (red) C.RED else if (outline) Color.WHITE else color
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2 * d
            this.color = if (outline && !red) C.GRAY else Color.WHITE
        }
        c.drawRoundRect(box, 9 * d, 9 * d, fill)
        c.drawRoundRect(box, 9 * d, 9 * d, border)
        drawPeople(c, box, if (red || !outline) Color.WHITE else C.TEXT2, d)

        if (red) {
            val slash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = 3 * d; this.color = Color.WHITE; strokeCap = Paint.Cap.ROUND
            }
            c.drawLine(box.left + 6 * d, box.bottom - 6 * d, box.right - 6 * d, box.top + 6 * d, slash)
            val bx = box.right
            val by = box.top
            val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE }
            val badgeLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 2 * d; this.color = C.RED
            }
            c.drawCircle(bx, by, 10 * d, badge)
            c.drawCircle(bx, by, 10 * d, badgeLine)
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 12 * d; typeface = Fonts.bold; textAlign = Paint.Align.CENTER; this.color = C.RED_TEXT
            }
            val fm = tp.fontMetrics
            c.drawText(if (unavailable > 9) "9+" else unavailable.toString(), bx, by - (fm.ascent + fm.descent) / 2, tp)
        }

        val lb = RectF(cx - labelW / 2, box.bottom + 3 * d, cx + labelW / 2, box.bottom + 3 * d + labelH)
        val lbBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = if (red) C.RED else 0xF5FFFFFF.toInt() }
        c.drawRoundRect(lb, 6 * d, 6 * d, lbBg)
        if (!red) {
            val lbLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 1 * d; this.color = if (outline) C.GRAY else color
            }
            c.drawRoundRect(lb, 6 * d, 6 * d, lbLine)
        }
        val lm = labelPaint.fontMetrics
        c.drawText(label, cx, lb.centerY() - (lm.ascent + lm.descent) / 2, labelPaint)
        return bmp
    }

    /** 화장실 추가·옮기기 때 화면 가운데 큰 빨간 핀. 핀 끝이 정확한 자리. 반환: (그림, 핀 끝의 y 위치) */
    fun placePin(ctx: Context): Pair<Bitmap, Float> {
        val d = ctx.resources.displayMetrics.density
        val label = "여기에 화장실"
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 13 * d; typeface = Fonts.bold; textAlign = Paint.Align.CENTER; color = Color.WHITE
        }
        val labelW = tp.measureText(label) + 20 * d
        val labelH = 26 * d
        val r = 22 * d                       // 핀 머리 반지름
        val w = max(labelW, r * 2 + 8 * d) + 4 * d
        val headY = labelH + 10 * d + r
        val tipY = headY + r * 2.1f
        val h = tipY + 8 * d
        val bmp = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = w / 2

        // 말풍선
        val lb = RectF(cx - labelW / 2, 0f, cx + labelW / 2, labelH)
        c.drawRoundRect(lb, 13 * d, 13 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF222222.toInt() })
        val tri = Path().apply { moveTo(cx - 6 * d, labelH - 1); lineTo(cx + 6 * d, labelH - 1); lineTo(cx, labelH + 6 * d); close() }
        c.drawPath(tri, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF222222.toInt() })
        val fm = tp.fontMetrics
        c.drawText(label, cx, labelH / 2 - (fm.ascent + fm.descent) / 2, tp)

        // 바닥 그림자 (핀 끝 자리)
        c.drawOval(RectF(cx - 9 * d, tipY - 3 * d, cx + 9 * d, tipY + 3 * d), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000 })

        // 핀 (물방울 모양)
        val pin = Path().apply {
            moveTo(cx, tipY)
            cubicTo(cx - r * 0.35f, headY + r * 1.35f, cx - r, headY + r * 0.75f, cx - r, headY)
            arcTo(RectF(cx - r, headY - r, cx + r, headY + r), 180f, 180f)
            cubicTo(cx + r, headY + r * 0.75f, cx + r * 0.35f, headY + r * 1.35f, cx, tipY)
            close()
        }
        c.drawPath(pin, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.RED })
        c.drawPath(pin, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = Color.WHITE })
        // 머리 안 하얀 원 + 남녀 그림
        c.drawCircle(cx, headY, r * 0.72f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        c.save(); c.scale(0.62f, 0.62f, cx, headY)
        drawPeople(c, RectF(cx - 22 * d, headY - 17 * d, cx + 22 * d, headY + 17 * d), C.RED, d)
        c.restore()
        return bmp to tipY
    }

    /** 제휴 가게 핀: 초록 물방울 + 가운데 가게 사진(없으면 🤝) + 노란 ★ + 아래 이름표. 반환: (그림, 핀 끝 y 비율) */
    fun partnerPin(ctx: Context, photo: Bitmap?, label: String, scale: Float = 1f): Pair<Bitmap, Float> {
        val d = ctx.resources.displayMetrics.density * scale
        val r = 17 * d                          // 핀 머리 반지름 (화장실 표시와 비슷한 크기)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11 * d; typeface = Fonts.bold; textAlign = Paint.Align.CENTER; color = 0xFF0F6E56.toInt()
        }
        val labelW = tp.measureText(label) + 14 * d
        val labelH = 19 * d
        val w = max(labelW, r * 2 + 20 * d) + 4 * d
        val cx = w / 2
        val headY = 8 * d + r
        val tipY = headY + r * 1.8f
        val h = tipY + 4 * d + labelH + 2 * d
        val bmp = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val green = 0xFF1D9E75.toInt()

        val pin = Path().apply {
            moveTo(cx, tipY)
            cubicTo(cx - r * 0.35f, headY + r * 1.3f, cx - r, headY + r * 0.75f, cx - r, headY)
            arcTo(RectF(cx - r, headY - r, cx + r, headY + r), 180f, 180f)
            cubicTo(cx + r, headY + r * 0.75f, cx + r * 0.35f, headY + r * 1.3f, cx, tipY)
            close()
        }
        c.drawPath(pin, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green })
        c.drawPath(pin, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d; color = Color.WHITE })

        // 가운데 동그란 사진
        val ir = r * 0.78f
        c.drawCircle(cx, headY, ir + 1.5f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        if (photo != null) {
            c.save()
            c.clipPath(Path().apply { addCircle(cx, headY, ir, Path.Direction.CW) })
            c.drawBitmap(photo, null, RectF(cx - ir, headY - ir, cx + ir, headY + ir), Paint(Paint.FILTER_BITMAP_FLAG))
            c.restore()
        } else {
            val ep = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15 * d; textAlign = Paint.Align.CENTER }
            val fm = ep.fontMetrics
            c.drawText("🤝", cx, headY - (fm.ascent + fm.descent) / 2, ep)
        }

        // ★ 표시
        val sx = cx + r * 0.85f
        val sy = headY - r * 0.85f
        c.drawCircle(sx, sy, 7 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF5B400.toInt() })
        c.drawCircle(sx, sy, 7 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = Color.WHITE })
        val st = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9 * d; textAlign = Paint.Align.CENTER; color = Color.WHITE; typeface = Fonts.bold }
        val sfm = st.fontMetrics
        c.drawText("★", sx, sy - (sfm.ascent + sfm.descent) / 2, st)

        // 이름표
        val lb = RectF(cx - labelW / 2, tipY + 4 * d, cx + labelW / 2, tipY + 4 * d + labelH)
        c.drawRoundRect(lb, 7 * d, 7 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF5FFFFFF.toInt() })
        c.drawRoundRect(lb, 7 * d, 7 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.8f * d; color = green })
        val lm = tp.fontMetrics
        c.drawText(label, cx, lb.centerY() - (lm.ascent + lm.descent) / 2, tp)
        return bmp to (tipY / h)
    }

    /** 내가 보는 방향: 가운데에서 위로 퍼지는 파란 부채꼴 + 작은 화살표, angle 도만큼 시계 방향으로 돌림 */
    fun headingCone(ctx: Context, angle: Float): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val size = (150 * d).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val r = size / 2f
        c.rotate(angle, cx, cx)
        val grad = android.graphics.RadialGradient(cx, cx, r, intArrayOf(0x99378ADD.toInt(), 0x00378ADD), null, android.graphics.Shader.TileMode.CLAMP)
        c.drawArc(RectF(cx - r, cx - r, cx + r, cx + r), -90f - 28f, 56f, true, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = grad })
        // 캐릭터 머리 위 작은 화살표
        val tip = cx - 34 * d
        val arrow = Path().apply { moveTo(cx, tip - 9 * d); lineTo(cx + 8 * d, tip + 4 * d); lineTo(cx - 8 * d, tip + 4 * d); close() }
        c.drawPath(arrow, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        c.drawPath(arrow, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d; color = C.BLUE_DARK; strokeJoin = Paint.Join.ROUND })
        return bmp
    }

    /** 숫자 동그라미 (넓게 볼 때 묶음 표시): 종류 색 + 개수, 많을수록 조금 크게 */
    fun countBubble(ctx: Context, color: Int, n: Int): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val r = (14 + minOf(12.0, Math.log(n.toDouble() + 1) / Math.log(2.0) * 2.2)).toFloat() * d
        val size = (r * 2 + 6 * d).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        c.drawCircle(cx, cx + 1.5f * d, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0x33000000 })
        c.drawCircle(cx, cx, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        c.drawCircle(cx, cx, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; this.color = Color.WHITE })
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = (if (n >= 100) 12f else 14f) * d; typeface = Fonts.bold; textAlign = Paint.Align.CENTER; this.color = Color.WHITE
        }
        val fm = tp.fontMetrics
        c.drawText(if (n > 999) "999+" else n.toString(), cx, cx - (fm.ascent + fm.descent) / 2, tp)
        return bmp
    }

    /** 기준점(그림 가운데)의 세로 위치 비율 */
    fun toiletAnchorY(ctx: Context, unavailable: Int): Float {
        val d = ctx.resources.displayMetrics.density
        val ring = if (unavailable > 0) 12 * d else 0f
        val boxH = 34 * d
        val h = ring + boxH + 3 * d + 19 * d + 8 * d + ring
        return (ring + 4 * d + boxH / 2) / h
    }

    /** 남자·여자 그림 */
    private fun drawPeople(c: Canvas, box: RectF, color: Int, d: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val cy = box.centerY()
        val mx = box.centerX() - 9 * d
        val wx = box.centerX() + 9 * d
        // 남자
        c.drawCircle(mx, cy - 8 * d, 3.2f * d, p)
        c.drawRoundRect(RectF(mx - 4.5f * d, cy - 3.5f * d, mx + 4.5f * d, cy + 5 * d), 2 * d, 2 * d, p)
        c.drawRect(mx - 3.5f * d, cy + 4 * d, mx - 0.7f * d, cy + 11 * d, p)
        c.drawRect(mx + 0.7f * d, cy + 4 * d, mx + 3.5f * d, cy + 11 * d, p)
        // 가운데 줄
        val line = Paint(p).apply { strokeWidth = 1.4f * d }
        c.drawLine(box.centerX(), cy - 11 * d, box.centerX(), cy + 11 * d, line)
        // 여자
        c.drawCircle(wx, cy - 8 * d, 3.2f * d, p)
        val dress = Path().apply {
            moveTo(wx - 3 * d, cy - 3.5f * d); lineTo(wx + 3 * d, cy - 3.5f * d)
            lineTo(wx + 6 * d, cy + 5.5f * d); lineTo(wx - 6 * d, cy + 5.5f * d); close()
        }
        c.drawPath(dress, p)
        c.drawRect(wx - 2.6f * d, cy + 5 * d, wx - 0.6f * d, cy + 11 * d, p)
        c.drawRect(wx + 0.6f * d, cy + 5 * d, wx + 2.6f * d, cy + 11 * d, p)
    }

    /** 내 위치 캐릭터: 0 동글이, 1 휴지 친구, 2 물방울이. frame 0/1 로 움찔 */
    fun character(ctx: Context, style: Int, frame: Int, scale: Float = 1f): Bitmap {
        val d = ctx.resources.displayMetrics.density * scale
        val s = 64 * d
        val bmp = Bitmap.createBitmap(s.toInt(), s.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val k = s / 70f
        c.scale(k, k)
        // 그림자
        c.drawOval(RectF(20f, 64f, 50f, 69f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 })
        c.save()
        val ang = if (frame == 0) -6f else 6f
        if (style == 1) c.translate(0f, if (frame == 0) 0f else -3f) else c.rotate(ang, 35f, 64f)
        when (style) {
            1 -> drawTissue(c)
            2 -> drawDrop(c)
            else -> drawDongle(c)
        }
        c.restore()
        return bmp
    }

    private fun stroke(color: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = w
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun sweat(c: Canvas, x: Float, y: Float, sz: Float) {
        val p = Path().apply {
            moveTo(x, y); quadTo(x + sz * 0.8f, y + sz * 1.2f, x, y + sz * 1.6f)
            quadTo(x - sz * 0.8f, y + sz * 1.2f, x, y); close()
        }
        c.drawPath(p, fill(0xFF85B7EB.toInt()))
    }

    private fun drawDongle(c: Canvas) {
        c.drawLine(26f, 52f, 40f, 64f, stroke(C.BLUE_DARK, 5f))
        c.drawLine(44f, 52f, 30f, 64f, stroke(C.BLUE_DARK, 5f))
        c.drawCircle(35f, 32f, 23f, fill(C.BLUE))
        c.drawCircle(35f, 32f, 23f, stroke(Color.WHITE, 3f))
        val eye = stroke(Color.WHITE, 2.5f)
        c.drawPath(Path().apply { moveTo(23f, 28f); lineTo(30f, 31f); lineTo(23f, 34f) }, eye)
        c.drawPath(Path().apply { moveTo(47f, 28f); lineTo(40f, 31f); lineTo(47f, 34f) }, eye)
        c.drawPath(Path().apply { moveTo(28f, 42f); quadTo(31.5f, 39f, 35f, 42f); quadTo(38.5f, 45f, 42f, 42f) }, eye)
        c.drawCircle(21f, 38f, 3f, fill(0xFFF4C0D1.toInt()))
        c.drawCircle(49f, 38f, 3f, fill(0xFFF4C0D1.toInt()))
        sweat(c, 58f, 8f, 5f)
        sweat(c, 11f, 13f, 3.5f)
    }

    private fun drawTissue(c: Canvas) {
        val g = stroke(0xFFB4B2A9.toInt(), 2.5f)
        c.drawLine(6f, 34f, 13f, 34f, g); c.drawLine(4f, 42f, 11f, 42f, g); c.drawLine(8f, 50f, 13f, 50f, g)
        c.drawRoundRect(RectF(18f, 12f, 54f, 60f), 10f, 10f, fill(Color.WHITE))
        c.drawRoundRect(RectF(18f, 12f, 54f, 60f), 10f, 10f, stroke(C.GRAY, 2.5f))
        c.drawOval(RectF(18f, 8f, 54f, 20f), fill(C.GRAY_BG))
        c.drawOval(RectF(18f, 8f, 54f, 20f), stroke(C.GRAY, 2.5f))
        c.drawOval(RectF(31f, 12f, 41f, 16f), fill(0xFFB4B2A9.toInt()))
        c.drawCircle(29f, 32f, 3.5f, fill(0xFF444441.toInt())); c.drawCircle(43f, 32f, 3.5f, fill(0xFF444441.toInt()))
        c.drawCircle(30f, 31f, 1.2f, fill(Color.WHITE)); c.drawCircle(44f, 31f, 1.2f, fill(Color.WHITE))
        c.drawOval(RectF(32f, 38f, 40f, 48f), fill(C.RED))
        val br = stroke(0xFF444441.toInt(), 2f)
        c.drawLine(24f, 23f, 30f, 25f, br); c.drawLine(48f, 23f, 42f, 25f, br)
        sweat(c, 58f, 4f, 5f)
    }

    private fun drawDrop(c: Canvas) {
        val body = Path().apply {
            moveTo(35f, 6f); cubicTo(22f, 24f, 13f, 34f, 13f, 46f)
            cubicTo(13f, 58f, 23f, 66f, 35f, 66f); cubicTo(47f, 66f, 57f, 58f, 57f, 46f)
            cubicTo(57f, 34f, 48f, 24f, 35f, 6f); close()
        }
        c.drawPath(body, fill(0xFF5DCAA5.toInt()))
        c.drawPath(body, stroke(Color.WHITE, 3f))
        c.drawCircle(27f, 44f, 4.5f, fill(Color.WHITE)); c.drawCircle(43f, 44f, 4.5f, fill(Color.WHITE))
        c.drawCircle(27f, 45f, 2.3f, fill(0xFF04342C.toInt())); c.drawCircle(43f, 45f, 2.3f, fill(0xFF04342C.toInt()))
        val dk = stroke(0xFF04342C.toInt(), 2f)
        c.drawLine(22f, 37f, 29f, 35f, dk); c.drawLine(48f, 37f, 41f, 35f, dk)
        c.drawPath(Path().apply { moveTo(30f, 57f); quadTo(35f, 52f, 40f, 57f) }, stroke(0xFF04342C.toInt(), 2.5f))
        sweat(c, 56f, 18f, 5f)
    }
}
