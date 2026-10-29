package com.vex.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * آیکون‌های وکتوری VEX — همه با Canvas رسم می‌شوند تا با تم طلایی سلطنتی هماهنگ بمانند.
 * icon: tg | globe | gear | chart | list | home | down | up | signal | crown | plus
 */
class IconView(context: Context, val icon: String) : View(context) {

    var iconColor: Int = Themes.goldMuted
        set(value) { field = value; invalidate() }

    var bold: Boolean = false
        set(value) { field = value; invalidate() }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val u = minOf(w, h) / 24f
        val sw = (if (bold) 2.7f else 2.1f) * u
        stroke.color = iconColor
        stroke.strokeWidth = sw
        fill.color = iconColor
        when (icon) {
            "tg" -> {
                // هواپیمای کاغذی تلگرام — طرح خوش‌نسبت با بال تاخورده‌ی دو رنگ
                val body = Path()
                body.moveTo(2.3f * u, 11.5f * u)   // دنباله
                body.lineTo(21.7f * u, 3.0f * u)   // نوک بالا
                body.lineTo(14.3f * u, 21.2f * u)  // پایین
                body.lineTo(10.8f * u, 14.4f * u)  // فرورفتگی بال
                body.close()
                canvas.drawPath(body, fill)
                // بال تاخورده (روشن‌تر = طلایی کم‌رنگ)
                val flap = Path()
                flap.moveTo(10.8f * u, 14.4f * u)
                flap.lineTo(21.7f * u, 3.0f * u)
                flap.lineTo(8.4f * u, 10.4f * u)
                flap.close()
                val fp = Paint(fill).apply { alpha = 150 }
                canvas.drawPath(flap, fp)
                // خط لبه‌ی پرواز برای برجستگی
                val edge = Paint(stroke).apply { strokeWidth = 1.2f * u }
                canvas.drawLine(2.3f * u, 11.5f * u, 21.7f * u, 3.0f * u, edge)
            }
            "timer" -> {
                // زمان‌سنج (کرنومتر): بدنه + دکمه‌ی بالا + عقربه
                val bc = cy + 1.6f * u
                canvas.drawCircle(cx, bc, 7.4f * u, stroke)
                // ساقه و کلاهک دکمه
                canvas.drawLine(cx, bc - 10.6f * u, cx, bc - 7.4f * u, stroke)
                val cap = Paint(stroke).apply { strokeWidth = sw * 1.2f }
                canvas.drawLine(cx - 2.6f * u, bc - 10.9f * u, cx + 2.6f * u, bc - 10.9f * u, cap)
                // تیک ساعت ۱۲ داخل بدنه
                canvas.drawLine(cx, bc - 7.4f * u + 1.2f * u, cx, bc - 4.6f * u, stroke)
                // عقربه به سمت یک‌ونیم
                val hand = Paint(stroke).apply { strokeWidth = sw * 1.15f }
                canvas.drawLine(cx, bc, cx + 4.0f * u, bc - 3.2f * u, hand)
                // محور
                canvas.drawCircle(cx, bc, 1.2f * u, fill)
            }
            "pencil" -> {
                // مداد مورب با نوک پر
                val p = Path()
                p.moveTo(3.2f * u, 17.0f * u)
                p.lineTo(16.8f * u, 3.5f * u)
                p.lineTo(19.7f * u, 6.5f * u)
                p.lineTo(6.1f * u, 20.0f * u)
                p.close()
                canvas.drawPath(p, stroke)
                // خط لبه‌ی چوب
                canvas.drawLine(9.9f * u, 10.3f * u, 18.3f * u, 5.0f * u, stroke)
                // نوک پر
                val tip = Path()
                tip.moveTo(3.2f * u, 17.0f * u)
                tip.lineTo(6.1f * u, 20.0f * u)
                tip.lineTo(4.2f * u, 20.8f * u)
                tip.close()
                canvas.drawPath(tip, fill)
            }
            "trash" -> {
                // سطل آشغال: درپوش + دستگیره + بدنه با شیارها
                canvas.drawLine(4.8f * u, 7.6f * u, 19.2f * u, 7.6f * u, stroke)
                val handle = Path()
                handle.moveTo(9.6f * u, 7.6f * u)
                handle.lineTo(9.6f * u, 5.6f * u)
                handle.lineTo(14.4f * u, 5.6f * u)
                handle.lineTo(14.4f * u, 7.6f * u)
                canvas.drawPath(handle, stroke)
                // بدنه‌ی باریک‌شونده
                val bodyP = Path()
                bodyP.moveTo(6.6f * u, 7.6f * u)
                bodyP.lineTo(7.6f * u, 19.2f * u)
                bodyP.lineTo(16.4f * u, 19.2f * u)
                bodyP.lineTo(17.4f * u, 7.6f * u)
                canvas.drawPath(bodyP, stroke)
                // شیارها
                val rib = Paint(stroke).apply { strokeWidth = sw * 0.85f }
                canvas.drawLine(10.2f * u, 10.4f * u, 10.6f * u, 16.6f * u, rib)
                canvas.drawLine(13.8f * u, 10.4f * u, 13.4f * u, 16.6f * u, rib)
            }
            "globe" -> {
                val r = 9f * u
                canvas.drawCircle(cx, cy, r, stroke)
                canvas.drawOval(RectF(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r), stroke)
                canvas.drawLine(cx - r, cy, cx + r, cy, stroke)
            }
            "gear" -> {
                val r = 7.6f * u
                val tp = Paint(stroke)
                tp.strokeWidth = sw * 2f
                for (i in 0 until 8) {
                    val a = i * (Math.PI / 4.0)
                    val x1 = cx + (r * 0.66 * cos(a)).toFloat()
                    val y1 = cy + (r * 0.66 * sin(a)).toFloat()
                    val x2 = cx + (r * 1.06 * cos(a)).toFloat()
                    val y2 = cy + (r * 1.06 * sin(a)).toFloat()
                    canvas.drawLine(x1, y1, x2, y2, tp)
                }
                canvas.drawCircle(cx, cy, r, stroke)
                canvas.drawCircle(cx, cy, r * 0.4f, stroke)
            }
            "chart" -> {
                val bp = Paint(stroke)
                bp.strokeWidth = sw * 1.7f
                canvas.drawLine(6f * u, 19f * u, 6f * u, 11.5f * u, bp)
                canvas.drawLine(12f * u, 19f * u, 12f * u, 5.5f * u, bp)
                canvas.drawLine(18f * u, 19f * u, 18f * u, 14f * u, bp)
            }
            "list" -> {
                val y1 = 6.2f * u
                val y2 = 12f * u
                val y3 = 17.8f * u
                canvas.drawLine(9.5f * u, y1, 20f * u, y1, stroke)
                canvas.drawLine(9.5f * u, y2, 20f * u, y2, stroke)
                canvas.drawLine(9.5f * u, y3, 20f * u, y3, stroke)
                val dotR = 1.4f * u
                canvas.drawCircle(4.6f * u, y1, dotR, fill)
                canvas.drawCircle(4.6f * u, y2, dotR, fill)
                canvas.drawCircle(4.6f * u, y3, dotR, fill)
            }
            "home" -> {
                val roof = Path()
                roof.moveTo(3f * u, 11.4f * u)
                roof.lineTo(12f * u, 4f * u)
                roof.lineTo(21f * u, 11.4f * u)
                canvas.drawPath(roof, stroke)
                canvas.drawLine(5.6f * u, 10.2f * u, 5.6f * u, 20f * u, stroke)
                canvas.drawLine(5.6f * u, 20f * u, 18.4f * u, 20f * u, stroke)
                canvas.drawLine(18.4f * u, 10.2f * u, 18.4f * u, 20f * u, stroke)
                canvas.drawLine(10.2f * u, 20f * u, 10.2f * u, 14.6f * u, stroke)
                canvas.drawLine(10.2f * u, 14.6f * u, 13.8f * u, 14.6f * u, stroke)
                canvas.drawLine(13.8f * u, 14.6f * u, 13.8f * u, 20f * u, stroke)
            }
            "down" -> {
                canvas.drawLine(cx, cy - 6.5f * u, cx, cy + 6.5f * u, stroke)
                val ch = Path()
                ch.moveTo(cx - 5f * u, cy + 0.8f * u)
                ch.lineTo(cx, cy + 6.5f * u)
                ch.lineTo(cx + 5f * u, cy + 0.8f * u)
                canvas.drawPath(ch, stroke)
            }
            "up" -> {
                canvas.drawLine(cx, cy - 6.5f * u, cx, cy + 6.5f * u, stroke)
                val ch = Path()
                ch.moveTo(cx - 5f * u, cy - 0.8f * u)
                ch.lineTo(cx, cy - 6.5f * u)
                ch.lineTo(cx + 5f * u, cy - 0.8f * u)
                canvas.drawPath(ch, stroke)
            }
            "signal" -> {
                val base = 19.5f * u
                val bw = 2.8f * u
                val hs = floatArrayOf(5.5f, 9.5f, 13.5f, 17f)
                val xs = floatArrayOf(3.6f, 8.8f, 14f, 19.2f)
                for (i in hs.indices) {
                    val x = xs[i] * u
                    canvas.drawRoundRect(
                        RectF(x, base - hs[i] * u, x + bw, base),
                        1.4f * u, 1.4f * u, fill
                    )
                }
            }
            "crown" -> {
                val peak = cy - 6.2f * u
                val baseY = cy + 4.2f * u
                val p = Path()
                p.moveTo(cx - 9f * u, baseY)
                p.lineTo(cx - 9f * u, cy - 2.4f * u)
                p.lineTo(cx - 4.5f * u, cy + 0.9f * u)
                p.lineTo(cx, peak)
                p.lineTo(cx + 4.5f * u, cy + 0.9f * u)
                p.lineTo(cx + 9f * u, cy - 2.4f * u)
                p.lineTo(cx + 9f * u, baseY)
                p.close()
                canvas.drawPath(p, fill)
                canvas.drawCircle(cx - 9f * u, cy - 2.9f * u, 1.5f * u, fill)
                canvas.drawCircle(cx, peak - 1.1f * u, 1.6f * u, fill)
                canvas.drawCircle(cx + 9f * u, cy - 2.9f * u, 1.5f * u, fill)
                canvas.drawRoundRect(
                    RectF(cx - 9f * u, baseY + 0.6f * u, cx + 9f * u, baseY + 3f * u),
                    1.2f * u, 1.2f * u, fill
                )
            }
            "plus" -> {
                val pp = Paint(stroke)
                pp.strokeWidth = sw * 1.35f
                canvas.drawLine(cx - 6.5f * u, cy, cx + 6.5f * u, cy, pp)
                canvas.drawLine(cx, cy - 6.5f * u, cx, cy + 6.5f * u, pp)
            }
        }
    }
}
