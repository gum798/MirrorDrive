package com.example.mirrordrive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * The floating window's resize affordance: a small "///" grip — three nested diagonal strokes
 * tucked into the bottom-right corner (the conventional resize-grip mark) — drawn in mirror-cyan
 * at a modest alpha with thin, round-capped strokes.
 *
 * Purely decorative. The caller keeps its `setOnTouchListener { onResize(e) }` and its layout
 * params; the view is sized to a ≥40dp touch target while the drawn mark stays small in the
 * corner, so the grip is easy to grab without a heavy fill obscuring the video behind it.
 */
class ResizeGripView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Palette.MIRROR_DIM
        strokeWidth = context.dpf(1.6f)
        strokeCap = Paint.Cap.ROUND
    }

    // Grip geometry (px): the mark hugs the bottom-right corner, small relative to the touch area.
    private val edge = context.dpf(6f)    // inset of the corner-most stroke from the view edge
    private val step = context.dpf(4.5f)  // spacing between the three nested strokes
    private val base = context.dpf(6f)    // length of the shortest (corner-most) stroke

    override fun onDraw(canvas: Canvas) {
        val cx = width - edge
        val cy = height - edge
        // Three nested "/" slashes (lower-left → upper-right), lengthening away from the corner.
        for (k in 0..2) {
            val d = base + k * step
            canvas.drawLine(cx - d, cy, cx, cy - d, paint)
        }
    }
}
