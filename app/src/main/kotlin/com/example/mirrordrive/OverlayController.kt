package com.example.mirrordrive

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
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

    val isShowing: Boolean get() = root != null

    @SuppressLint("ClickableViewAccessibility")
    fun show(
        videoW: Int,
        videoH: Int,
        onSurface: (Surface) -> Unit,
        onReturnToFullscreen: () -> Unit,
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

        // Return-to-fullscreen control (top-right).
        container.addView(
            Button(appContext).apply {
                text = "⤢"
                styleControl(this)
                setOnClickListener { onReturnToFullscreen() }
            },
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END),
        )
        // Close control (top-left) — dismiss the floating window and restore fullscreen.
        container.addView(
            Button(appContext).apply {
                text = "✕"
                styleControl(this)
                setOnClickListener { onReturnToFullscreen() }
            },
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START),
        )

        // Drag the body to move the whole window.
        container.setOnTouchListener { _, e -> onDrag(e) }

        // Resize handle (bottom-right) — resizes preserving the video aspect ratio.
        val handleSize = (metrics.density * 28).toInt()
        val handle = View(appContext).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = appContext.dpf(6f)
                setColor(0xAA55D6D0.toInt())   // translucent mirror-cyan grip
            }
        }
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
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        params = null
    }

    private fun onDrag(e: MotionEvent): Boolean {
        val lp = params ?: return false
        val r = root ?: return false
        return when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX; downRawY = e.rawY; startX = lp.x; startY = lp.y; true
            }
            MotionEvent.ACTION_MOVE -> {
                lp.x = startX + (e.rawX - downRawX).toInt()
                lp.y = startY + (e.rawY - downRawY).toInt()
                wm.updateViewLayout(r, lp); true
            }
            else -> false
        }
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

    /** Cyan-on-dark rounded styling for the floating window's ⤢ / ✕ controls, to match the
     *  night-dashboard look. Touch handlers and layout params are set by the caller and left
     *  untouched here. */
    private fun styleControl(b: Button) {
        b.setTextColor(Palette.MIRROR)
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        b.typeface = Typeface.DEFAULT_BOLD
        b.includeFontPadding = false
        b.stateListAnimator = null
        b.minWidth = appContext.dp(44)
        b.minHeight = appContext.dp(44)
        b.setPadding(appContext.dp(10), appContext.dp(6), appContext.dp(10), appContext.dp(6))
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = appContext.dpf(20f)
            setColor(Palette.SURFACE_FILL)
            setStroke(appContext.dp(1.5f), Palette.MIRROR)
        }
        b.background = RippleDrawable(ColorStateList.valueOf(Palette.MIRROR_RIPPLE), bg, null)
    }

    private companion object {
        const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
    }
}
