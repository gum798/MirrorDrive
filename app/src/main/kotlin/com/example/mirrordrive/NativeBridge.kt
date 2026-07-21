package com.example.mirrordrive

object NativeBridge {
    init { System.loadLibrary("mirrordrive") }
    external fun nativeVersion(): String
    external fun nativeInit(filesDir: String): Boolean
    external fun nativeGetPublicKeyHex(): String
    external fun nativeGetDeviceId(): String
    external fun nativeGetPort(): Int
}
