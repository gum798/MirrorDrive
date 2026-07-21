package com.example.mirrordrive

object NativeBridge {
    init { System.loadLibrary("mirrordrive") }
    external fun nativeVersion(): String
    external fun nativeInit(filesDir: String): Boolean
    external fun nativeGetPublicKeyHex(): String
    external fun nativeGetDeviceId(): String
    external fun nativeGetPort(): Int

    // Registers the video sink (a VideoRenderer) that native cb_video_process delivers
    // decoded Annex-B H.264 access units to via onAccessUnit(byte[], long, boolean).
    external fun setVideoSink(sink: Any)
}
