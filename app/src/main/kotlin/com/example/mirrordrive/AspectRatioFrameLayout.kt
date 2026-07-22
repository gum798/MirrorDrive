package com.example.mirrordrive

import android.content.Context
import android.widget.FrameLayout

/** Sizes itself to fit [aspectW]x[aspectH] inside the space the parent offers, preserving
 *  aspect ratio (letterbox/pillarbox). The child SurfaceView fills this view (MATCH_PARENT),
 *  so the video is aspect-correct and the parent's black background shows as the bars. */
class AspectRatioFrameLayout(context: Context) : FrameLayout(context) {
    private var aspectW = 0
    private var aspectH = 0

    fun setAspect(w: Int, h: Int) {
        if (w > 0 && h > 0 && (w != aspectW || h != aspectH)) {
            aspectW = w; aspectH = h; requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (aspectW <= 0 || aspectH <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec); return
        }
        val availW = MeasureSpec.getSize(widthMeasureSpec)
        val availH = MeasureSpec.getSize(heightMeasureSpec)
        val fit = fitInside(availW, availH, aspectW, aspectH)
        val w = if (fit.width > 0) fit.width else availW
        val h = if (fit.height > 0) fit.height else availH
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
        )
    }
}
