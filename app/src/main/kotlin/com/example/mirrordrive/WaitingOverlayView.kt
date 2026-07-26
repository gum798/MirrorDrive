package com.example.mirrordrive

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
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
        // Car-context caveat: the iPhone cannot run wireless CarPlay and screen mirroring at the
        // same time (an iOS restriction, not ours). On a CarPlay box that's the #1 reason the phone
        // never finds MirrorDrive, so we say it here where the user is already looking + waiting.
        center.addView(
            TextView(context).apply {
                text = "무선 카플레이 사용 중엔 연결되지 않아요 · 카플레이를 끄고 연결하세요"
                setTextColor(Palette.SILVER_DIM)
                typeface = Typeface.SANS_SERIF
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                alpha = 0.7f
                letterSpacing = 0.02f
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(8) },
        )
        addView(
            center,
            LayoutParams(WRAP, WRAP, Gravity.CENTER),
        )

        // Bottom cluster (vertical, centred): scannable QR card, device identity, feedback link.
        val bottomStack = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // QR of the feedback form for a bystander to scan. Dark-on-light inside a small rounded
        // light card — scannability wins over the night-dashboard palette here. Cached + built in
        // a try/catch, so any encoding failure simply omits the QR instead of crashing.
        feedbackQr(context)?.let { qr ->
            val pad = context.dp(8)
            val card = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = context.dpf(10f)
                    setColor(QR_CARD)
                }
                setPadding(pad, pad, pad, pad)
                contentDescription = "의견 보내기 QR 코드"
                addView(
                    ImageView(context).apply {
                        setImageBitmap(qr)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    },
                    FrameLayout.LayoutParams(context.dp(112), context.dp(112)),
                )
            }
            bottomStack.addView(card, LinearLayout.LayoutParams(WRAP, WRAP))
            bottomStack.addView(
                TextView(context).apply {
                    text = "QR 스캔 · 의견 보내기"
                    setTextColor(Palette.SILVER_DIM)
                    typeface = Typeface.SANS_SERIF
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    letterSpacing = 0.04f
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(6) },
            )
        }

        // Live dot + device identity in monospace.
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
        bottomStack.addView(
            status,
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(14) },
        )

        // Quiet "피드백" link for this app's own user — a restrained mirror-cyan text link (not a
        // loud button) that opens the same feedback form in the browser. ≥44dp touch target.
        val feedback = TextView(context).apply {
            text = "피드백"
            contentDescription = "의견 보내기"
            setTextColor(Palette.MIRROR)
            typeface = Typeface.SANS_SERIF
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            letterSpacing = 0.06f
            gravity = Gravity.CENTER
            minHeight = context.dp(44)
            minWidth = context.dp(44)
            setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(4))
            isClickable = true
            isFocusable = true
            background = RippleDrawable(ColorStateList.valueOf(Palette.MIRROR_RIPPLE), null, null)
            setOnClickListener { openFeedbackForm(context) }
        }
        bottomStack.addView(feedback, LinearLayout.LayoutParams(WRAP, WRAP))

        addView(
            bottomStack,
            LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = context.dp(18)
            },
        )
    }

    /** Open the feedback form in the browser. NEW_TASK because the waiting view runs inside an
     *  activity/overlay; try/catch so a device with no browser fails quietly instead of crashing. */
    private fun openFeedbackForm(context: Context) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(FEEDBACK_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            // No browser / no handling activity — ignore rather than crash the waiting screen.
        }
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
        const val FEEDBACK_URL = "https://forms.gle/iwziaX1fZ5CToPyD7"
        const val QR_CARD = 0xFFEAEEF0.toInt()   // light card so the QR stays scannable

        // Encode the feedback-form QR once and cache it across view (re)creations.
        private var qrCache: Bitmap? = null
        fun feedbackQr(context: Context): Bitmap? {
            qrCache?.let { return it }
            return encodeQr(FEEDBACK_URL, context.dp(112), Palette.GROUND, QR_CARD)
                .also { qrCache = it }
        }
    }
}

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
