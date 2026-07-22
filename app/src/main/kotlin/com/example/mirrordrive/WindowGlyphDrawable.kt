package com.example.mirrordrive

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * A small "window mode" glyph: two overlapping rounded rectangles (a front pane over a back
 * pane), stroked. Hand-drawn on a [Canvas] rather than a bundled vector so it scales crisply
 * and tints with its host control's state.
 *
 * Stateful: hosted as a TextView compound drawable it receives the view's drawable state, so
 * it can follow the label from silver (rest) to ground (pressed, on the cyan fill).
 */
class WindowGlyphDrawable(
    private val sizePx: Int,
    private val strokePx: Float,
) : Drawable() {
    private var colors: ColorStateList = ColorStateList.valueOf(Color.WHITE)
    private var current = Color.WHITE
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    /** Drive the glyph colour from the same [ColorStateList] as the label. */
    fun setColors(csl: ColorStateList) {
        colors = csl
        current = csl.getColorForState(state, csl.defaultColor)
        invalidateSelf()
    }

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val next = colors.getColorForState(state, colors.defaultColor)
        return if (next != current) { current = next; invalidateSelf(); true } else false
    }

    override fun setTintList(tint: ColorStateList?) {
        if (tint != null) setColors(tint)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val inset = strokePx
        val r = (minOf(w, h)) * 0.14f
        paint.color = current

        // Back pane — upper-right.
        val back = RectF(
            b.left + w * 0.34f, b.top + inset,
            b.right - inset, b.top + h * 0.60f,
        )
        canvas.drawRoundRect(back, r, r, paint)

        // Front pane — lower-left, overlapping the back pane.
        val front = RectF(
            b.left + inset, b.top + h * 0.38f,
            b.left + w * 0.66f, b.bottom - inset,
        )
        canvas.drawRoundRect(front, r, r, paint)
    }

    override fun getIntrinsicWidth(): Int = sizePx
    override fun getIntrinsicHeight(): Int = sizePx
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("deprecated in Drawable")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
