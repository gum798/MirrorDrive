package com.example.mirrordrive

/** A width/height pair produced by [fitInside]. */
data class FitSize(val width: Int, val height: Int)

/**
 * Fit a [videoW]x[videoH] video inside a [containerW]x[containerH] box preserving the video
 * aspect ratio (letterbox / pillarbox). Returns the largest size that fits fully inside the
 * container; the caller centres it, leaving equal black bars on the two opposite edges
 * (top/bottom for a wide video, left/right for a portrait video on a landscape screen).
 *
 * The returned size never exceeds the container in either dimension. Non-positive inputs
 * yield a zero size (nothing to render yet).
 */
fun fitInside(containerW: Int, containerH: Int, videoW: Int, videoH: Int): FitSize {
    if (containerW <= 0 || containerH <= 0 || videoW <= 0 || videoH <= 0) return FitSize(0, 0)
    // Compare aspect ratios by cross-multiplication (Long) to avoid float rounding drift:
    // the container is proportionally wider than the video when
    //   containerW/containerH > videoW/videoH  <=>  containerW*videoH > videoW*containerH
    return if (containerW.toLong() * videoH > videoW.toLong() * containerH) {
        // Container is wider than the video → height is the limit (pillarbox: bars left/right).
        val h = containerH
        val w = (videoW.toLong() * h / videoH).toInt()
        FitSize(w, h)
    } else {
        // Container is taller than (or as wide as) the video → width is the limit
        // (letterbox: bars top/bottom, or an exact fit when aspects match).
        val w = containerW
        val h = (videoH.toLong() * w / videoW).toInt()
        FitSize(w, h)
    }
}
