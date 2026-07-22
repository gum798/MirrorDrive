package com.example.mirrordrive

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.provider.Settings
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * A signal radiating from a mirror: concentric mirror-cyan rings expanding and fading outward,
 * over a soft cyan core. Drawn with [Canvas] + a single [ValueAnimator] (no bundled SVG/Lottie).
 *
 * Reduced motion: when the system animator duration scale is 0 (developer setting / battery
 * saver), the rings are drawn once at fixed radii and never animate — [start]/[stop] no-op.
 */
class RippleSignalView(context: Context) : View(context) {
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(1.6f)
        color = Palette.MIRROR
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Palette.MIRROR
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val ringCount = 3
    private var progress = 0f
    private var cx = 0f
    private var cy = 0f
    private var maxRadius = 0f
    private val coreRadius = context.dpf(6f)

    private val reducedMotion: Boolean = run {
        val scale = Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        )
        scale == 0f
    }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2800
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { progress = it.animatedValue as Float; invalidate() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        // Leave a stroke-width margin so the outermost ring never clips at the edge.
        maxRadius = (minOf(w, h) / 2f) - context.dpf(2f)
        glowPaint.shader = RadialGradient(
            cx, cy, (coreRadius * 3.2f).coerceAtLeast(1f),
            Palette.MIRROR_GLOW, 0x0055D6D0, Shader.TileMode.CLAMP,
        )
    }

    /** Begin (or resume) the radiating animation; no-op under reduced motion. */
    fun start() {
        if (reducedMotion) { invalidate(); return }
        if (!animator.isStarted) animator.start()
    }

    /** Pause the animation (called when the waiting screen is hidden) to save power. */
    fun stop() {
        if (animator.isStarted) animator.cancel()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (maxRadius <= 0f) return
        // Soft core glow, then the mirror core.
        canvas.drawCircle(cx, cy, coreRadius * 3.2f, glowPaint)
        canvas.drawCircle(cx, cy, coreRadius, corePaint)

        for (i in 0 until ringCount) {
            val phase = if (reducedMotion) {
                (i + 1f) / ringCount               // static: evenly spaced rings
            } else {
                (progress + i.toFloat() / ringCount) % 1f
            }
            val radius = coreRadius + phase * (maxRadius - coreRadius)
            val alpha = ((1f - phase) * 200f).toInt().coerceIn(0, 255)
            ringPaint.color = Palette.MIRROR
            ringPaint.alpha = alpha
            canvas.drawCircle(cx, cy, radius, ringPaint)
        }
    }
}
