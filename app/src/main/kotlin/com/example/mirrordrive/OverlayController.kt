package com.example.mirrordrive

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout

/**
 * Owns a single floating mirror window (macOS iPhone-Mirroring style): a [SurfaceView] hosted
 * in a [WindowManager] TYPE_APPLICATION_OVERLAY window that floats over other apps. The window
 * is draggable, resizable while preserving the video aspect ratio, and carries controls to
 * return to fullscreen / close.
 *
 * It deliberately does NOT own the decoder. On surfaceCreated it hands its Surface to the
 * caller (which forwards it to the running [VideoRenderer] via setOutputSurface), so switching
 * modes never re-decodes and the AirPlay session in the foreground service keeps running.
 *
 * Single-gesture-at-a-time is assumed (drag OR resize), so the transient gesture anchors are
 * plain fields.
 */
class OverlayController(context: Context) {
    private val appContext = context.applicationContext
    private val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null

    // Video aspect (kept so resize can preserve it); defaults to portrait iPhone until known.
    private var aspectW = 9
    private var aspectH = 16

    // Fixed reference box (longer-edge px) the video aspect is fitted into. Stored so re-fitting
    // on rotation (updateAspect) never fits-inside-its-own-previous-output — which shrank the
    // window geometrically every rotation. Updated only on user resize.
    private var boxPx = 0

    // Gesture anchors captured on ACTION_DOWN.
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var startW = 0
    private var startH = 0

    private val minSizePx = (appContext.resources.displayMetrics.density * 120).toInt()

    // How close (px) a window edge must be to a screen edge, on release, to be pulled flush to it —
    // the "magnet" gauge. Drop the window further than this from every edge and it stays put.
    private val snapThresholdPx = appContext.dp(40)

    // Runs the short glide from where the window was released to its snapped resting position.
    private var snapAnim: ValueAnimator? = null

    val isShowing: Boolean get() = root != null

    @SuppressLint("ClickableViewAccessibility")
    fun show(
        videoW: Int,
        videoH: Int,
        onSurface: (Surface) -> Unit,
        onReturnToFullscreen: () -> Unit,
        onClose: () -> Unit,
    ) {
        if (isShowing) return
        aspectW = if (videoW > 0) videoW else 9
        aspectH = if (videoH > 0) videoH else 16

        // Initial size: fit the video aspect into ~60% of the screen's smaller edge.
        val metrics = appContext.resources.displayMetrics
        val box = (minOf(metrics.widthPixels, metrics.heightPixels) * 0.6).toInt()
        boxPx = box
        val fit = fitInside(box, box, aspectW, aspectH)

        val lp = WindowManager.LayoutParams(
            fit.width, fit.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (metrics.density * 16).toInt()
            y = (metrics.density * 48).toInt()
        }

        val container = FrameLayout(appContext).apply { setBackgroundColor(Color.BLACK) }

        val surfaceView = SurfaceView(appContext)
        container.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { onSurface(h.surface) }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {}
        })

        // Switch-to-fullscreen control (top-right) — glyph hugged into the top-end corner.
        container.addView(
            Button(appContext).apply {
                text = "⤢"
                styleControl(this)
                gravity = Gravity.TOP or Gravity.END
                setOnClickListener { onReturnToFullscreen() }
            },
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END),
        )
        // Quit control (top-left) — glyph hugged into the top-start corner; stops mirroring and
        // closes the app entirely (NOT a return-to-fullscreen).
        container.addView(
            Button(appContext).apply {
                text = "✕"
                styleControl(this)
                gravity = Gravity.TOP or Gravity.START
                setOnClickListener { onClose() }
            },
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START),
        )

        // Drag the body to move the whole window.
        container.setOnTouchListener { _, e -> onDrag(e) }

        // Resize handle (bottom-right) — a small "///" grip; resizes preserving the aspect ratio.
        // The touch target is ≥40dp while the drawn mark stays small in the corner.
        val handleSize = (metrics.density * 44).toInt()
        val handle = ResizeGripView(appContext)
        container.addView(
            handle,
            FrameLayout.LayoutParams(handleSize, handleSize, Gravity.BOTTOM or Gravity.END),
        )
        handle.setOnTouchListener { _, e -> onResize(e) }

        wm.addView(container, lp)
        root = container
        params = lp
    }

    /** Update the floating window to a new source aspect ratio (e.g. iPhone rotated). */
    fun updateAspect(videoW: Int, videoH: Int) {
        if (videoW <= 0 || videoH <= 0) return
        aspectW = videoW
        aspectH = videoH
        val lp = params ?: return
        val r = root ?: return
        // Fit into the FIXED reference box, not the window's own (already-fitted) size — fitting
        // inside a fit shrinks the window geometrically on every rotation.
        val fit = fitInside(boxPx, boxPx, aspectW, aspectH)
        lp.width = fit.width
        lp.height = fit.height
        wm.updateViewLayout(r, lp)
    }

    fun hide() {
        snapAnim?.cancel()
        snapAnim = null
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        params = null
    }

    private fun onDrag(e: MotionEvent): Boolean {
        val lp = params ?: return false
        val r = root ?: return false
        return when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                snapAnim?.cancel()   // grab the window mid-glide if a snap is still animating
                downRawX = e.rawX; downRawY = e.rawY; startX = lp.x; startY = lp.y; true
            }
            MotionEvent.ACTION_MOVE -> {
                lp.x = startX + (e.rawX - downRawX).toInt()
                lp.y = startY + (e.rawY - downRawY).toInt()
                wm.updateViewLayout(r, lp); true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                snapToEdges(); true
            }
            else -> false
        }
    }

    /**
     * The magnet. Called on release: clamp the window fully on-screen (so a fling can never lose
     * it), then, per axis independently, pull it flush to whichever screen edge is within
     * [snapThresholdPx] — nearer edge wins, ties go to the leading edge. Snapping X and Y
     * separately means corners fall out naturally. A window dropped away from every edge keeps its
     * spot. The move to the resting position is animated so it reads as a magnetic pull.
     */
    private fun snapToEdges() {
        val lp = params ?: return
        val r = root ?: return
        val screen = screenSize()
        val maxX = maxOf(0, screen.x - lp.width)
        val maxY = maxOf(0, screen.y - lp.height)

        var targetX = lp.x.coerceIn(0, maxX)
        val distLeft = targetX
        val distRight = maxX - targetX
        if (distLeft <= distRight) {
            if (distLeft <= snapThresholdPx) targetX = 0
        } else {
            if (distRight <= snapThresholdPx) targetX = maxX
        }

        var targetY = lp.y.coerceIn(0, maxY)
        val distTop = targetY
        val distBottom = maxY - targetY
        if (distTop <= distBottom) {
            if (distTop <= snapThresholdPx) targetY = 0
        } else {
            if (distBottom <= snapThresholdPx) targetY = maxY
        }

        animateSnap(lp, r, targetX, targetY)
    }

    /** Glide the window from its current [WindowManager.LayoutParams] position to (tx, ty). */
    private fun animateSnap(lp: WindowManager.LayoutParams, r: FrameLayout, tx: Int, ty: Int) {
        snapAnim?.cancel()
        val fromX = lp.x
        val fromY = lp.y
        if (fromX == tx && fromY == ty) return
        snapAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                lp.x = (fromX + (tx - fromX) * f).toInt()
                lp.y = (fromY + (ty - fromY) * f).toInt()
                runCatching { wm.updateViewLayout(r, lp) }
            }
            start()
        }
    }

    /** Real full-screen size in px — the coordinate space of [WindowManager.LayoutParams] x/y for a
     *  TOP|START, FLAG_LAYOUT_NO_LIMITS overlay. Re-read each release so it tracks rotation. */
    private fun screenSize(): Point {
        val p = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(p)
        return p
    }

    private fun onResize(e: MotionEvent): Boolean {
        val lp = params ?: return false
        val r = root ?: return false
        return when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX; downRawY = e.rawY; startW = lp.width; startH = lp.height; true
            }
            MotionEvent.ACTION_MOVE -> {
                val metrics = appContext.resources.displayMetrics
                val maxW = metrics.widthPixels
                val w = (startW + (e.rawX - downRawX)).toInt().coerceIn(minSizePx, maxW)
                lp.width = w
                lp.height = (w.toLong() * aspectH / aspectW).toInt()
                boxPx = maxOf(lp.width, lp.height)  // reference tracks the user's chosen size
                wm.updateViewLayout(r, lp); true
            }
            else -> false
        }
    }

    /** Bare, restrained styling for the floating window's ⤢ / ✕ controls: just a thin cyan glyph
     *  — no chip, fill, or border behind it, so the video shows through completely. The glyph is
     *  hugged into its corner (caller sets the corner gravity) with only a small padding, inside a
     *  fixed 48dp transparent touch target. Press / focus brighten the glyph from dim to full
     *  mirror-cyan (the only state feedback, since there is no background). Touch handlers and
     *  layout params are set by the caller and left untouched here. */
    private fun styleControl(b: Button) {
        b.setTextColor(controlTextColors())
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        b.typeface = Typeface.DEFAULT   // lighter weight than bold — thinner, more refined glyph
        b.includeFontPadding = false
        b.stateListAnimator = null
        b.minWidth = appContext.dp(48)  // fixed 48dp transparent touch target
        b.minHeight = appContext.dp(48)
        // Small padding so the corner-aligned glyph hugs the corner without touching the edge.
        b.setPadding(appContext.dp(4), appContext.dp(4), appContext.dp(4), appContext.dp(4))
        b.background = null             // no chip / fill / border — bare glyph only
    }

    /** Glyph colour: dim mirror-cyan at rest, full mirror when pressed / focused. */
    private fun controlTextColors() = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_pressed),
            intArrayOf(android.R.attr.state_focused),
            intArrayOf(),
        ),
        intArrayOf(Palette.MIRROR, Palette.MIRROR, Palette.MIRROR_DIM),
    )

    private companion object {
        const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
    }
}
