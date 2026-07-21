package com.example.mirrordrive

import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class VideoRendererTest {
    // Feeds a bundled raw H.264 elementary-stream asset through VideoRenderer to an
    // off-screen Surface and asserts at least one frame renders (output buffer produced).
    @Test fun decodesBundledH264ToSurface() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val rendered = CountDownLatch(1)
        // An ImageReader with GPU/overlay usage provides an off-screen Surface the hardware
        // video decoder can render into headlessly (more robust than a bare SurfaceTexture).
        val reader = ImageReader.newInstance(
            1280, 720, ImageFormat.PRIVATE, 4,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_COMPOSER_OVERLAY
        )
        val surface = reader.surface
        val renderer = VideoRenderer(onFrameRendered = { rendered.countDown() })
        renderer.onSurface(surface)

        // sample.h264 = a few seconds of Annex-B H.264 in androidTest assets.
        // (assets.open handles aapt-compressed assets; openFd would require noCompress.)
        val bytes = ctx.assets.open("sample.h264").use { it.readBytes() }
        // Split into Annex-B access units on 00 00 00 01 boundaries (test helper).
        for ((i, au) in splitAnnexB(bytes).withIndex()) {
            renderer.onAccessUnit(au, ptsUs = i * 33_000L, isConfig = i == 0)
        }
        assertTrue("no frame rendered in 5s", rendered.await(5, TimeUnit.SECONDS))
        renderer.release(); reader.close()
    }
}
