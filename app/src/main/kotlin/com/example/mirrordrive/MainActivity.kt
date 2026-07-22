package com.example.mirrordrive

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {
    private lateinit var videoRenderer: VideoRenderer
    private lateinit var audioRenderer: AudioRenderer
    private lateinit var overlayController: OverlayController
    private lateinit var root: FrameLayout
    private lateinit var aspectView: AspectRatioFrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var waitingOverlay: WaitingOverlayView

    // Real decoded video size (portrait for a portrait iPhone), reported by the renderer.
    private var videoW = 0
    private var videoH = 0
    // True while the mirror is showing in the floating overlay window rather than fullscreen.
    private var overlayActive = false

    // uptimeMillis of the most recent rendered frame (written on the codec thread via the
    // renderer's onFrameRendered hook — a cheap volatile write that never touches decoding).
    // A UI-thread watchdog reads it to fade the waiting overlay in/out.
    @Volatile private var lastFrameMs = 0L
    private val ui = Handler(Looper.getMainLooper())
    private val waitingTick = object : Runnable {
        override fun run() { updateWaiting(); ui.postDelayed(this, 700) }
    }

    // Grant flow for SYSTEM_ALERT_WINDOW: after the settings screen returns, retry if granted.
    private val overlayPermLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) enterOverlay()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the screen on while mirroring (user request).
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // Night-ink system bars for the transient-swipe case (bars are otherwise hidden).
        window.statusBarColor = Palette.GROUND
        window.navigationBarColor = Palette.GROUND

        // Black container so the letterbox/pillarbox bars are black; the aspect view sizes
        // itself to the fitted video rect (in its onMeasure) and is centred within root, and
        // the SurfaceView fills the aspect view (Feature 1).
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        aspectView = AspectRatioFrameLayout(this)
        surfaceView = SurfaceView(this)
        aspectView.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            aspectView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
        // Waiting state: a night-ink overlay with a radiating signal, shown until the first
        // video frame renders. It sits ABOVE the video root but BELOW the 창 모드 pill so the
        // control stays tappable, and owns no surface/video state.
        waitingOverlay = WaitingOverlayView(this)
        root.addView(
            waitingOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        // "창 모드" (window mode) toggle -> floating overlay window (Feature 2).
        root.addView(
            buildModeButton(this) { requestOverlay() },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply {
                topMargin = dp(16)
                marginEnd = dp(16)
            },
        )
        setContentView(root)

        overlayController = OverlayController(this)
        videoRenderer = VideoRenderer(
            // Fired after each frame is released to the Surface; just stamp the time so the
            // waiting-overlay watchdog knows frames are flowing. Runs on the codec thread.
            onFrameRendered = { lastFrameMs = SystemClock.uptimeMillis() },
            onVideoSize = { w, h ->
                runOnUiThread {
                    videoW = w; videoH = h
                    aspectView.setAspect(w, h)
                    if (overlayActive) overlayController.updateAspect(w, h)
                }
            },
        )
        // Register the renderer as the native video sink before any stream arrives.
        NativeBridge.setVideoSink(videoRenderer)

        // Audio is independent of the surface/overlay and purely additive: create the
        // renderer once, then decode + play AAC-ELD frames as they arrive. The ASC for
        // 44100/stereo AirPlay-mirroring AAC-ELD is the fixed constant F8 E8 50 00.
        audioRenderer = AudioRenderer()
        audioRenderer.configure(byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00))
        NativeBridge.setAudioSink(audioRenderer)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                // On first launch the codec is not configured yet (surface just recorded); on a
                // return from overlay mode the running codec swaps back to this fullscreen
                // Surface in place. Either way, no reconfigure.
                videoRenderer.setSurface(holder.surface)
                if (overlayActive) {
                    overlayController.hide()
                    overlayActive = false
                }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // Backgrounding into overlay mode destroys this fullscreen Surface, but the
                // decoder must keep running (it now renders into the overlay Surface), so only
                // tear it down when we are genuinely leaving mirroring.
                if (!overlayActive) videoRenderer.release()
            }
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(android.content.Intent(this, ReceiverService::class.java))
    }

    private fun requestOverlay() {
        if (Settings.canDrawOverlays(this)) {
            enterOverlay()
        } else {
            overlayPermLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    /** Move the mirror into the floating overlay and send the app to the background. */
    private fun enterOverlay() {
        if (overlayActive) return
        overlayController.show(
            videoW = videoW,
            videoH = videoH,
            onSurface = { s -> videoRenderer.setSurface(s) },
            onReturnToFullscreen = {
                // Bring this (singleTask) activity back to the front; its surfaceCreated then
                // swaps the decoder back to fullscreen and dismisses the overlay.
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                )
            },
        )
        overlayActive = true
        moveTaskToBack(true)
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(waitingTick)
        ui.post(waitingTick)
    }

    override fun onPause() {
        ui.removeCallbacks(waitingTick)
        super.onPause()
    }

    /**
     * UI-thread watchdog (every ~700ms): the waiting overlay is visible whenever no frames are
     * currently arriving and the mirror is fullscreen. Frames flowing -> fade it out; frames
     * stalled (stream ended) -> fade it back in; floating-overlay mode -> keep it hidden. Reads
     * only [lastFrameMs]; it never influences how frames reach the decoder.
     */
    private fun updateWaiting() {
        val live = lastFrameMs != 0L && SystemClock.uptimeMillis() - lastFrameMs < 1500
        when {
            overlayActive -> waitingOverlay.hideImmediate()
            live -> waitingOverlay.fadeOut()
            else -> waitingOverlay.fadeIn()
        }
        waitingOverlay.refreshDeviceInfoIfNeeded()
    }

    override fun onDestroy() {
        ui.removeCallbacks(waitingTick)
        if (isFinishing) {
            overlayController.hide()
            // Audio has no surface; tear it down only when genuinely leaving mirroring
            // (never on the overlay/window-mode transition, which keeps the activity alive).
            if (::audioRenderer.isInitialized) audioRenderer.stop()
        }
        super.onDestroy()
    }
}
