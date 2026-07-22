package com.example.mirrordrive

import android.content.Context
import kotlin.math.roundToInt

/**
 * The single deliberate visual world for MirrorDrive: a night dashboard built around
 * reflection / glass / stream imagery. Defined once here so every UI surface (waiting screen,
 * 창 모드 pill, floating-overlay controls) speaks the same language.
 *
 * One bold accent only — mirror cyan — kept distinct from the connected-signal green so
 * "live" never competes with "the brand".
 */
object Palette {
    const val GROUND = 0xFF0B0F14.toInt()   // night ink — the deepest background
    const val SURFACE = 0xFF141B22.toInt()  // raised panel / control fill
    const val MIRROR = 0xFF55D6D0.toInt()   // the one bold accent (cyan)
    const val SILVER = 0xFFAEB9C0.toInt()   // primary muted text
    const val LIVE = 0xFF56D98A.toInt()     // connected-signal green (separate from the accent)

    // Derived tints reused across controls.
    const val SILVER_DIM = 0xB0AEB9C0.toInt()          // secondary text (silver @ ~69%)
    const val SURFACE_FILL = 0xCC141B22.toInt()        // translucent control fill over video
    const val MIRROR_GLOW = 0x3355D6D0.toInt()         // faint cyan halo
    const val MIRROR_RIPPLE = 0x8055D6D0.toInt()       // touch ripple
}

fun Context.dp(v: Number): Int = (resources.displayMetrics.density * v.toFloat()).roundToInt()

fun Context.dpf(v: Number): Float = resources.displayMetrics.density * v.toFloat()
