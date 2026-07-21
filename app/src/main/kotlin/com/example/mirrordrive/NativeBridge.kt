package com.example.mirrordrive

object NativeBridge {
    init { System.loadLibrary("mirrordrive") }
    external fun nativeVersion(): String
}
