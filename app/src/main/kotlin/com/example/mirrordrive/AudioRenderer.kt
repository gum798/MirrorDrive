package com.example.mirrordrive

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * Decodes raw AAC-ELD access units delivered by the native AirPlay mirror pipeline (via
 * [onAudioFrame]) to PCM and plays them through an [AudioTrack].
 *
 * AirPlay screen mirroring sends 44100 Hz stereo AAC-ELD (ct=8). The native side hands us
 * the compressed, decrypted access units; this class decodes and plays them.
 *
 * Decode path: an asynchronous [MediaCodec] for `audio/mp4a-latm` configured for the ELD
 * profile ([MediaCodecInfo.CodecProfileLevel.AACObjectELD]) with the 4-byte AAC-ELD
 * AudioSpecificConfig as csd-0. Structured like [VideoRenderer]: a HandlerThread runs the
 * async callbacks; a drain loop feeds queued access units into free input buffers; decoded
 * PCM is written to a MODE_STREAM [AudioTrack].
 *
 * (A bundled fdk-aac fallback for devices that reject the ELD profile is intended but not
 * yet wired: the Task 1 prebuilt libfdk-aac.a is not compiled with -fPIC, so it cannot be
 * referenced from the native library — see native_bridge.cpp. Until it is rebuilt, this
 * MediaCodec path is the only decoder.)
 *
 * Pacing / lip-sync is intentionally NOT handled here (Task 5) — frames are played as they
 * arrive; the goal for this task is simply audible sound.
 */
class AudioRenderer(
    // Reports decoded PCM byte counts as they are written to the track (test/telemetry hook).
    private val onPcm: (Int) -> Unit = {},
) {
    private data class Au(val data: ByteArray, val ptsUs: Long)

    private val lock = Any()
    private val pending = ArrayDeque<Au>()      // AUs waiting for a free input buffer
    private val freeInputs = ArrayDeque<Int>()  // input buffer indices offered by the codec
    private var codec: MediaCodec? = null
    private var codecThread: HandlerThread? = null
    private var track: AudioTrack? = null

    @Volatile private var released = false

    /**
     * Build the output track and decoder for 44100/stereo AAC-ELD described by [ascBytes]
     * (the AudioSpecificConfig). Safe to call once, before any frames arrive.
     */
    fun configure(ascBytes: ByteArray) {
        synchronized(lock) {
            if (released) return
            if (!buildAudioTrack()) {
                Log.e(TAG, "AudioRenderer: no AudioTrack; audio disabled")
                return
            }
            if (tryConfigureMediaCodec(ascBytes)) {
                Log.i(TAG, "AudioRenderer: MediaCodec AAC-ELD path configured")
            } else {
                // Device rejected the ELD profile. The fdk-aac fallback is unavailable until
                // the prebuilt is rebuilt with -fPIC; audio stays silent on such a device.
                Log.e(TAG, "AudioRenderer: MediaCodec rejected AAC-ELD; audio disabled")
            }
        }
    }

    /** Native audio sink entry point (called from cb_audio_process via JNI). */
    fun onAudioFrame(data: ByteArray, ptsUs: Long) {
        synchronized(lock) {
            if (released || codec == null) return
            pending.add(Au(data, ptsUs))
            drain()
        }
    }

    fun stop() = release()

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
            // A codec teardown can race with this async callback; abandon rather than crash.
            Log.w(TAG, "audio drain aborted: ${e.message}")
        }
    }

    /** Must hold [lock]. Returns true if the async ELD decoder was created and started. */
    private fun tryConfigureMediaCodec(ascBytes: ByteArray): Boolean {
        var thread: HandlerThread? = null
        var c: MediaCodec? = null
        return try {
            val fmt = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS,
            ).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectELD,
                )
                setInteger(MediaFormat.KEY_IS_ADTS, 0)
                setByteBuffer("csd-0", ByteBuffer.wrap(ascBytes))
            }
            thread = HandlerThread("AudioRenderer-codec").apply { start() }
            val handler = Handler(thread.looper)
            c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(mc: MediaCodec, i: Int) {
                    synchronized(lock) { freeInputs.add(i); drain() }
                }
                override fun onOutputBufferAvailable(
                    mc: MediaCodec,
                    i: Int,
                    info: MediaCodec.BufferInfo,
                ) {
                    if (released) return
                    try {
                        val out = mc.getOutputBuffer(i)
                        if (out != null && info.size > 0) {
                            val pcm = ByteArray(info.size)
                            out.position(info.offset)
                            out.get(pcm)
                            track?.write(pcm, 0, pcm.size)
                            onPcm(pcm.size)
                        }
                        mc.releaseOutputBuffer(i, false)
                    } catch (e: IllegalStateException) {
                        // codec torn down under us; ignore.
                    }
                }
                override fun onOutputFormatChanged(mc: MediaCodec, f: MediaFormat) {
                    Log.i(TAG, "audio output format: $f")
                }
                override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e(TAG, "MediaCodec audio error: ${e.diagnosticInfo}", e)
                }
            }, handler)
            c.configure(fmt, null, null, 0) // may throw on ELD-rejecting devices
            c.start()
            codec = c
            codecThread = thread
            true
        } catch (e: Exception) {
            // IllegalArgumentException / MediaCodec.CodecException / IllegalStateException:
            // the platform decoder does not accept the ELD profile — clean up and report.
            Log.w(TAG, "MediaCodec ELD configure failed: ${e.message}")
            try { c?.release() } catch (_: Exception) {}
            thread?.quitSafely()
            codec = null
            codecThread = null
            false
        }
    }

    private fun buildAudioTrack(): Boolean {
        return try {
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufSize = if (minBuf > 0) 2 * minBuf else 2 * SAMPLE_RATE
            val builder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (Build.VERSION.SDK_INT >= 26) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            val t = builder.build()
            t.play()
            track = t
            true
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack build failed: ${e.message}", e)
            false
        }
    }

    fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            codec = null
            codecThread?.quitSafely()
            codecThread = null
            try { track?.stop() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
            track = null
            pending.clear()
            freeInputs.clear()
        }
    }

    private companion object {
        const val TAG = "MirrorDrive"
        const val SAMPLE_RATE = 44100
        const val CHANNELS = 2
    }
}
