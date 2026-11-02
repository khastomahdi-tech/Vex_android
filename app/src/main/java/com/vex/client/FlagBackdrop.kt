package com.vex.client

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

/**
 * پس‌زمینه‌ی «پرچم شیر و خورشید» — VEX Client:
 *  • سه نوار افقی رنگ پرچم ایران (سبز / سفید / قرمز) با شفافیت خیلی کم —
 *    رنگ‌ها روی هر تمی (روشن/تیره/سلطنتی) آرام و سلطنتی دیده می‌شوند؛
 *  • نشان شیر و خورشید (bg_lion_sun) به‌صورت واترمارک طلایی وسط صفحه؛
 *  • هیچ‌جای کارت‌ها و متن‌ها را کدر نمی‌کند — چون همه‌ی اجزا با آلفای پایین رسم می‌شوند.
 *
 * نکته‌ی عملکرد: بیت‌مپ یک بار لود و کش می‌شود؛ رسم فقط چند مستطیل + یک drawBitmap است.
 */
class FlagBackdrop private constructor(private val bmp: Bitmap?) : Drawable() {

    private val flagP = Paint()
    private val bmpP = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var dark = true

    fun setDarkTheme(d: Boolean): FlagBackdrop {
        dark = d
        return this
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return

        // ---------- سه نوار پرچم ----------
        val bandA = if (dark) 0.055f else 0.09f
        val third = b.height() / 3f
        // سبز — بالای صفحه
        flagP.color = UI.alpha(if (dark) 0xFF1FA35C.toInt() else 0xFF149441.toInt(), bandA)
        canvas.drawRect(b.left, b.top, b.right, b.top + third, flagP)
        // نوار میانی — کمی روشن‌تر/کرمی (جای نشان)
        flagP.color = UI.alpha(if (dark) 0xFFF2E7C8.toInt() else 0xFFFFFFFF.toInt(),
            if (dark) 0.030f else 0.22f)
        canvas.drawRect(b.left, b.top + third, b.right, b.top + third * 2f, flagP)
        // قرمز — پایین صفحه
        flagP.color = UI.alpha(if (dark) 0xFFE04848.toInt() else 0xFFC93A3A.toInt(), bandA)
        canvas.drawRect(b.left, b.top + third * 2f, b.right, b.bottom, flagP)

        // ---------- نشان شیر و خورشید (واترمارک وسط) ----------
        val bmap = bmp ?: return
        val margin = (b.width() * 0.115f).toInt()
        val availW = b.width() - margin * 2
        val availH = b.height() - margin * 2
        var dw = availW
        var dh = availW * bmap.height / bmap.width
        if (dh > availH) { dh = availH; dw = availH * bmap.width / bmap.height }
        if (dw <= 0 || dh <= 0) return
        val l = b.left + (b.width() - dw) / 2
        val t = b.top + (b.height() - dh) / 2
        if (dark) {
            // تم تیره — نشان طلایی اصلی با واترمارک ملایم
            bmpP.colorFilter = null
            bmpP.alpha = 30
        } else {
            // تم روشن — طلایی تیره‌ترِ یکدست تا روی زمینه‌ی روشن دیده شود
            bmpP.colorFilter = android.graphics.PorterDuffColorFilter(
                0xFFB8860B.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
            bmpP.alpha = 74
        }
        canvas.drawBitmap(bmap, null, Rect(l, t, l + dw, t + dh), bmpP)
    }

    override fun setAlpha(alpha: Int) { /* واترمارک — آلفای ثابت */ }
    override fun setColorFilter(cf: ColorFilter?) { /* بدون فیلتر رنگ */ }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** بیت‌مپ یک بار لود و کش می‌شود */
        @Volatile private var cached: Bitmap? = null

        fun create(context: Context): FlagBackdrop {
            var b = cached
            if (b == null || b.isRecycled) {
                b = try {
                    BitmapFactory.decodeResource(context.resources, R.drawable.bg_lion_sun)
                } catch (_: Throwable) { null }
                cached = b
            }
            return FlagBackdrop(b)
        }
    }
}
