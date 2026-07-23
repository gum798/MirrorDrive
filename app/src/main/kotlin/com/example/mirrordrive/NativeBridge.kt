package com.example.mirrordrive

import android.os.Build
import android.util.Log

object NativeBridge {
    /**
     * True iff libmirrordrive.so loaded successfully. Computed once, guarded so a failed load
     * (e.g. an x86 box with an arm-only .so, or a locked ROM missing libmediandk.so) never
     * throws out of class init and kills the process. UnsatisfiedLinkError is an Error, not an
     * Exception, so we catch [Throwable]. Callers must check this before invoking any external
     * fun below; doing so on an unavailable library would itself throw.
     */
    @JvmField
    val available: Boolean = try {
        System.loadLibrary("mirrordrive")
        true
    } catch (t: Throwable) {
        Log.e(
            "NativeBridge",
            "native load failed; supported ABIs=${Build.SUPPORTED_ABIS.joinToString(",")}",
            t,
        )
        false
    }

    external fun nativeVersion(): String
    external fun nativeInit(filesDir: String): Boolean
    external fun nativeGetPublicKeyHex(): String
    external fun nativeGetDeviceId(): String
    external fun nativeGetPort(): Int

    // Registers the video sink (a VideoRenderer) that native cb_video_process delivers
    // decoded Annex-B H.264 access units to via onAccessUnit(byte[], long, boolean).
    external fun setVideoSink(sink: Any)

    // Registers the audio sink (an AudioRenderer) that native cb_audio_process delivers
    // compressed AAC-ELD access units to via onAudioFrame(byte[], long).
    external fun setAudioSink(sink: Any)
}
