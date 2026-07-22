package com.example.mirrordrive

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {
    private lateinit var videoRenderer: VideoRenderer
    private lateinit var overlayController: OverlayController
    private lateinit var root: FrameLayout
    private lateinit var aspectView: AspectRatioFrameLayout
    private lateinit var surfaceView: SurfaceView

    // Real decoded video size (portrait for a portrait iPhone), reported by the renderer.
    private var videoW = 0
    private var videoH = 0
    // True while the mirror is showing in the floating overlay window rather than fullscreen.
    private var overlayActive = false

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
        // "창 모드" (window mode) toggle -> floating overlay window (Feature 2).
        root.addView(
            Button(this).apply {
                text = "창 모드"
                setOnClickListener { requestOverlay() }
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ),
        )
        setContentView(root)

        overlayController = OverlayController(this)
        videoRenderer = VideoRenderer(
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

    override fun onDestroy() {
        if (isFinishing) overlayController.hide()
        super.onDestroy()
    }
}
