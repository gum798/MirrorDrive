package com.example.mirrordrive

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * Decodes Annex-B H.264 access units delivered by the native AirPlay mirror pipeline
 * (via [onAccessUnit]) to a [Surface] using an asynchronous [MediaCodec].
 *
 * The live UxPlay stream prepends the SPS (NAL type 7) + PPS (type 8) to the first
 * keyframe, so config is self-detected from the byte stream rather than a separate
 * callback. SPS/PPS may also arrive in separate access units (as with a raw elementary
 * stream), so they are accumulated until both are known, then the codec is configured
 * lazily. Only H.264 is supported; H.265 AUs are dropped by the caller.
 */
class VideoRenderer(
    private val onFrameRendered: () -> Unit = {},
    // Reports the real decoded video size (honouring any crop rect) once the decoder has
    // produced an output format. Used to letterbox the fullscreen view and size the
    // floating overlay to the source aspect ratio. Delivered on the codec thread.
    private val onVideoSize: (w: Int, h: Int) -> Unit = { _, _ -> },
) {
    private data class Au(val data: ByteArray, val ptsUs: Long)

    private val lock = Any()
    private val pending = ArrayDeque<Au>()      // AUs waiting for a free input buffer
    private val freeInputs = ArrayDeque<Int>()  // input buffer indices offered by the codec
    private var codec: MediaCodec? = null
    private var codecThread: HandlerThread? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var configured = false
    @Volatile private var released = false

    // Best-effort diagnostic state for the on-screen HUD (test builds); never affects decoding.
    @Volatile private var lastSetSurface = "-"
    @Volatile private var lastError = "-"

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    fun onSurface(s: Surface) = setSurface(s)

    /**
     * Point the decoder at [s]. Before the codec is configured this just records the target
     * Surface for the pending [configure]. Once the codec is running it swaps the output
     * Surface in place via [MediaCodec.setOutputSurface] (API 23+) WITHOUT reconfiguring, so
     * a fullscreen <-> floating-overlay mode switch never tears the codec down and drops the
     * live AirPlay session — the same decoder simply renders into the new Surface.
     */
    fun setSurface(s: Surface) {
        synchronized(lock) {
            if (released) { lastSetSurface = "skip:released"; return }
            surface = s
            val c = codec
            if (configured && c != null) {
                try {
                    c.setOutputSurface(s)
                    lastSetSurface = "swap:valid=${s.isValid}"
                } catch (e: IllegalStateException) {
                    lastSetSurface = "swapFAIL:${e.message}"
                    Log.w("MirrorDrive", "setOutputSurface failed: ${e.message}")
                }
            } else {
                lastSetSurface = "record:cfg=$configured"
            }
        }
    }

    /** One-line decoder state for the on-screen diagnostic (test builds). Best-effort, lock-free. */
    fun debugLine(): String =
        "cfg=${if (configured) 1 else 0} rel=${if (released) 1 else 0} " +
            "surf=${if (surface?.isValid == true) 1 else 0} setS=$lastSetSurface err=$lastError"

    fun onAccessUnit(data: ByteArray, ptsUs: Long, isConfig: Boolean) {
        synchronized(lock) {
            if (released) return
            if (!configured) {
                // Accumulate SPS/PPS whether they arrive together (embedded in the first
                // keyframe, as the live mirror stream delivers them) or as separate access
                // units (a raw elementary stream), then configure once both are seen. Data
                // that arrives before we can configure is dropped: it cannot be decoded
                // without the parameter sets anyway.
                for (nal in splitAnnexB(data)) {
                    when (nalType(nal)) {
                        7 -> sps = nal
                        8 -> pps = nal
                    }
                }
                val s = sps
                val p = pps
                if (s == null || p == null || !configure(s, p)) return
            }
            // Only feed access units that carry a coded slice (VCL NAL types 1-5). Parameter
            // sets are already supplied via csd; queuing standalone SPS/PPS/SEI buffers makes
            // some hardware decoders (e.g. c2.qti.avc.decoder) fault.
            if (hasVcl(data)) {
                pending.add(Au(data, ptsUs))
                drain()
            }
        }
    }

    /** Must hold [lock]. Feeds queued AUs into any free codec input buffers. */
    private fun drain() {
        if (released) return
        val c = codec ?: return
        try {
            while (freeInputs.isNotEmpty() && pending.isNotEmpty()) {
                val i = freeInputs.removeFirst()
                val au = pending.removeFirst()
                val buf = c.getInputBuffer(i) ?: continue
                buf.clear()
                buf.put(au.data)
                c.queueInputBuffer(i, 0, au.data.size, au.ptsUs, 0)
            }
        } catch (e: IllegalStateException) {
            // A codec/surface teardown (or CodecException) can race with this async
            // callback; abandon draining rather than crash the codec thread.
            Log.w("MirrorDrive", "drain aborted: ${e.message}")
        }
    }

    /** Must hold [lock]. Creates and starts the decoder; returns false if no Surface yet. */
    private fun configure(sps: ByteArray, pps: ByteArray): Boolean {
        val s = surface ?: return false
        // Actual dimensions are derived from the SPS in csd-0; this size is only a hint.
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            // Screen mirroring is latency-sensitive: minimise decoder reorder buffering.
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // 0 = realtime
        }
        val thread = HandlerThread("VideoRenderer-codec").apply { start() }
        val handler = Handler(thread.looper)
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(mc: MediaCodec, i: Int) {
                synchronized(lock) { freeInputs.add(i); drain() }
            }
            override fun onOutputBufferAvailable(mc: MediaCodec, i: Int, info: MediaCodec.BufferInfo) {
                if (released) return
                try {
                    mc.releaseOutputBuffer(i, true)
                } catch (e: IllegalStateException) {
                    return
                }
                onFrameRendered()
            }
            override fun onOutputFormatChanged(mc: MediaCodec, f: MediaFormat) {
                val (w, h) = decodedVideoSize(f)
                Log.i("MirrorDrive", "video output format: ${w}x$h")
                onVideoSize(w, h)
            }
            override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                lastError = "code=${e.errorCode}"
                Log.e("MirrorDrive", "MediaCodec error code=${e.errorCode} diag=${e.diagnosticInfo}", e)
            }
        }, handler)
        c.configure(fmt, s, null, 0)
        c.start()
        codec = c
        codecThread = thread
        configured = true
        Log.i("MirrorDrive", "codec configured name=${c.name} sps=${sps.size} pps=${pps.size}")
        return true
    }

    fun flush() {
        synchronized(lock) {
            codec?.flush()
            freeInputs.clear()
            pending.clear()
        }
    }

    fun release() {
        synchronized(lock) {
            released = true
            try {
                codec?.stop()
            } catch (e: IllegalStateException) {
                // codec already stopped/errored
            }
            codec?.release()
            codec = null
            codecThread?.quitSafely()
            codecThread = null
            configured = false
            pending.clear()
            freeInputs.clear()
            sps = null
            pps = null
        }
    }
}

/**
 * The real displayed video size from a decoder output [MediaFormat]. KEY_WIDTH/KEY_HEIGHT can
 * include hardware alignment padding, so prefer the crop rectangle when the format advertises
 * one (real width = crop-right - crop-left + 1, height likewise). Falls back to KEY_WIDTH/HEIGHT.
 */
private fun decodedVideoSize(f: MediaFormat): Pair<Int, Int> {
    if (f.containsKey("crop-left") && f.containsKey("crop-right") &&
        f.containsKey("crop-top") && f.containsKey("crop-bottom")) {
        val w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1
        val h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1
        if (w > 0 && h > 0) return w to h
    }
    return f.getInteger(MediaFormat.KEY_WIDTH) to f.getInteger(MediaFormat.KEY_HEIGHT)
}

/** True if the Annex-B buffer contains at least one VCL slice NAL (types 1-5). */
private fun hasVcl(data: ByteArray): Boolean {
    for (nal in splitAnnexB(data)) {
        if (nalType(nal) in 1..5) return true
    }
    return false
}

/** NAL unit type (low 5 bits of the header byte) for an Annex-B NAL that still carries
 *  its 3- or 4-byte start code, or -1 if it is too short to classify. */
private fun nalType(nal: ByteArray): Int {
    val header = when {
        nal.size >= 5 && nal[2] == 0.toByte() && nal[3] == 1.toByte() -> nal[4] // 00 00 00 01
        nal.size >= 4 && nal[2] == 1.toByte() -> nal[3]                         // 00 00 01
        else -> return -1
    }
    return header.toInt() and 0x1f
}

/** Extract the first SPS (NAL type 7) and PPS (NAL type 8) from an Annex-B buffer that
 *  carries both (the live mirror stream prepends them to the first keyframe). */
fun extractSpsPps(annexB: ByteArray): Pair<ByteArray, ByteArray>? {
    var sps: ByteArray? = null
    var pps: ByteArray? = null
    for (nal in splitAnnexB(annexB)) {
        when (nalType(nal)) {
            7 -> sps = nal
            8 -> pps = nal
        }
    }
    val s = sps
    val p = pps
    return if (s != null && p != null) s to p else null
}

/** Split an Annex-B stream into NAL units, each retaining its start-code prefix.
 *  Handles both 00 00 01 (3-byte) and 00 00 00 01 (4-byte) start codes. Emulation
 *  prevention guarantees 00 00 01 never appears inside a NAL payload. */
fun splitAnnexB(data: ByteArray): List<ByteArray> {
    val out = ArrayList<ByteArray>()
    var i = 0
    var start = -1
    while (i + 2 < data.size) {
        if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
            // Absorb a leading zero so a 4-byte start code stays intact.
            val codeStart = if (i > 0 && data[i - 1] == 0.toByte()) i - 1 else i
            if (start in 0 until codeStart) out.add(data.copyOfRange(start, codeStart))
            start = codeStart
            i += 3
        } else {
            i++
        }
    }
    if (start in 0 until data.size) out.add(data.copyOfRange(start, data.size))
    return out
}
