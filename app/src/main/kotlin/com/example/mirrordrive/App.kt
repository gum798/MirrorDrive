package com.example.mirrordrive

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Application entry point whose sole job is diagnostics: install a process-wide uncaught
 * exception handler so a fatal error becomes a written [crash.log] plus a readable on-screen
 * report ([ErrorActivity]) a field user can screenshot — instead of the app dying silently.
 *
 * Nothing here touches the video/audio/decode pipeline; it only observes failures.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, err ->
            val header = buildString {
                append("MirrorDrive crash\n")
                append("device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
                append("sdk: ${Build.VERSION.SDK_INT}\n")
                append("abis: ${Build.SUPPORTED_ABIS.joinToString(",")}\n")
                append("thread: ${thread.name}\n")
            }
            val trace = Log.getStackTraceString(err)

            // (a) Persist to disk so support can pull it even if the screen was missed.
            runCatching {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                File(filesDir, "crash.log").appendText("=== $stamp ===\n$header$trace\n\n")
            }

            // (b) Surface an on-screen, screenshot-able report.
            runCatching {
                startActivity(
                    Intent(this, ErrorActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(ErrorActivity.EXTRA_MESSAGE, "$header\n$trace"),
                )
            }

            // Preserve default behavior (process teardown / debugger reporting).
            previous?.uncaughtException(thread, err)
        }
    }
}
