package com.example.mirrordrive

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class ReceiverService : Service() {
    private lateinit var discovery: DiscoveryService

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        check(NativeBridge.nativeInit(filesDir.absolutePath)) { "native init failed" }
        discovery = DiscoveryService(this)
        discovery.start(
            name = "MirrorDrive",
            port = NativeBridge.nativeGetPort(),
            pkHex = NativeBridge.nativeGetPublicKeyHex(),
            deviceId = NativeBridge.nativeGetDeviceId(),
        )
    }

    override fun onDestroy() { discovery.stop(); super.onDestroy() }
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
}
