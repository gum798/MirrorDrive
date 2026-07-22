package com.example.mirrordrive

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The pre-mirror waiting state, shown on top of the black video root before any frames arrive
 * and again if the stream stops. Night-ink field with a radiating [RippleSignalView], the
 * wide-tracked MIRRORDRIVE wordmark, a hint on how to connect from the iPhone, and — pinned to
 * the bottom — a live-signal dot with the device id and IP:port in monospace.
 *
 * It owns no video/surface state: [MainActivity] fades it in/out purely from a first-frame
 * signal, so it can never affect how frames reach the decoder.
 */
class WaitingOverlayView(context: Context) : FrameLayout(context) {
    private val ripple = RippleSignalView(context)
    private val deviceLine: TextView
    private var deviceInfoComplete = false

    private var fadeAnim: ValueAnimator? = null
    private var shown = true

    init {
        setBackgroundColor(Palette.GROUND)
        isClickable = false           // never steal touches from the 창 모드 pill above it
        contentDescription = "미러링 대기 중"

        // Centre stack: ripple + wordmark + connect hint.
        val center = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        center.addView(
            ripple,
            LinearLayout.LayoutParams(context.dp(240), context.dp(240)),
        )
        center.addView(
            TextView(context).apply {
                text = "MIRRORDRIVE"
                setTextColor(Palette.SILVER)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                letterSpacing = 0.35f
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(20) },
        )
        center.addView(
            TextView(context).apply {
                text = "아이폰 제어센터 → 화면 미러링 → MirrorDrive"
                setTextColor(Palette.SILVER_DIM)
                typeface = Typeface.SANS_SERIF
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                letterSpacing = 0.02f
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(10) },
        )
        addView(
            center,
            LayoutParams(WRAP, WRAP, Gravity.CENTER),
        )

        // Bottom status: live dot + device identity in monospace.
        val status = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        status.addView(
            LiveDotView(context),
            LinearLayout.LayoutParams(context.dp(18), context.dp(18)),
        )
        deviceLine = TextView(context).apply {
            setTextColor(Palette.SILVER_DIM)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            letterSpacing = 0.02f
            text = "device —\n—"
        }
        status.addView(
            deviceLine,
            LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = context.dp(8) },
        )
        addView(
            status,
            LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = context.dp(28)
            },
        )
    }

    /** Read device id / port / Wi-Fi IPv4 (safe before native init: shows "—"), until complete. */
    fun refreshDeviceInfoIfNeeded() {
        if (deviceInfoComplete) return
        val id = runCatching { NativeBridge.nativeGetDeviceId() }.getOrNull()?.takeIf { it.isNotBlank() }
        val port = runCatching { NativeBridge.nativeGetPort() }.getOrDefault(0)
        val ip = localIpv4String()
        val idText = id ?: "—"
        val addrText = if (ip != null && port > 0) "$ip:$port" else "—"
        deviceLine.text = "device $idText\n$addrText"
        if (id != null && ip != null && port > 0) deviceInfoComplete = true
    }

    /** Fade the waiting screen out (first frame rendered). Idempotent. */
    fun fadeOut() {
        if (!shown) return
        shown = false
        ripple.stop()
        animateTo(0f) { visibility = GONE }
    }

    /** Fade the waiting screen back in (no stream / stream ended). Idempotent. */
    fun fadeIn() {
        if (shown && visibility == VISIBLE) return
        shown = true
        visibility = VISIBLE
        ripple.start()
        animateTo(1f, null)
    }

    /** Hide without animation (e.g. while the mirror is in the floating overlay). */
    fun hideImmediate() {
        shown = false
        fadeAnim?.cancel()
        ripple.stop()
        alpha = 0f
        visibility = GONE
    }

    private fun animateTo(target: Float, onEnd: (() -> Unit)?) {
        fadeAnim?.cancel()
        fadeAnim = ValueAnimator.ofFloat(alpha, target).apply {
            duration = 380
            interpolator = LinearInterpolator()
            addUpdateListener { alpha = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { onEnd?.invoke() }
            })
            start()
        }
    }

    private companion object {
        const val WRAP = LayoutParams.WRAP_CONTENT
    }
}

/** Wi-Fi IPv4 as a dotted string, or null if not on Wi-Fi. Mirrors DiscoveryService.wifiIpv4. */
/**
 * A small connected-signal dot: a solid green core inside a soft green halo that gently
 * breathes. Static under reduced motion.
 */
private class LiveDotView(context: Context) : View(context) {
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Palette.LIVE
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Palette.LIVE
    }
    private var pulse = 1f
    private val reducedMotion: Boolean = Settings.Global.getFloat(
        context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
    ) == 0f
    private val anim = ValueAnimator.ofFloat(0.35f, 1f).apply {
        duration = 1300
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!reducedMotion) anim.start()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val coreR = context.dpf(3.5f)
        val haloR = context.dpf(8f)
        halo.alpha = ((if (reducedMotion) 0.45f else pulse) * 90f).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, haloR, halo)
        core.alpha = 255
        canvas.drawCircle(cx, cy, coreR, core)
    }
}
