package com.example.mirrordrive

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter

/**
 * Encode [content] as a crisp QR [Bitmap]: ZXing scales modules to integer pixel blocks, so the
 * result has hard edges (no anti-alias blur) and stays scannable. Dark [moduleColor] modules on
 * a light [bgColor] field, with a small quiet-zone margin. Returns null on any encoding failure
 * so the caller can simply omit the QR rather than crash.
 */
fun encodeQr(content: String, sizePx: Int, moduleColor: Int, bgColor: Int): Bitmap? = try {
    val hints = mapOf(EncodeHintType.MARGIN to 1)
    val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            pixels[row + x] = if (matrix[x, y]) moduleColor else bgColor
        }
    }
    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(pixels, 0, w, 0, 0, w, h) }
} catch (e: Exception) {
    null
}
