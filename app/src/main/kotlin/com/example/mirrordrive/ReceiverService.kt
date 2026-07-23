package com.example.mirrordrive

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class ReceiverService : Service() {
    private var discovery: DiscoveryService? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        // Native init can return false or throw (unloadable/incompatible native lib). Don't let it
        // crash the whole process — log it and stop the service gracefully. The activity's native
        // gate already keeps the UI alive; this is the belt-and-braces path for the service.
        val ok = try {
            NativeBridge.nativeInit(filesDir.absolutePath)
        } catch (t: Throwable) {
            Log.e(TAG, "nativeInit threw", t)
            false
        }
        if (!ok) {
            Log.e(TAG, "native init failed; stopping ReceiverService (mirroring unavailable)")
            stopSelf()
            return
        }
        discovery = DiscoveryService(this).also {
            it.start(
                name = "MirrorDrive",
                port = NativeBridge.nativeGetPort(),
                pkHex = NativeBridge.nativeGetPublicKeyHex(),
                deviceId = NativeBridge.nativeGetDeviceId(),
            )
        }
    }

    override fun onDestroy() { discovery?.stop(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, id: Int) = START_STICKY

    private fun startAsForeground() {
        val chId = "mirrordrive"
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(chId, "MirrorDrive", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
        val n = NotificationCompat.Builder(this, chId)
            .setContentTitle("MirrorDrive").setContentText("Ready to mirror")
            .setSmallIcon(android.R.drawable.stat_sys_upload).build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, n)
        }
    }

    companion object {
        private const val TAG = "ReceiverService"
    }
}
