package com.example.mirrordrive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AspectFitTest {
    @Test fun exactFit_whenAspectMatches() {
        assertEquals(FitSize(1920, 1080), fitInside(1920, 1080, 1920, 1080))
        // Same aspect expressed in reduced terms fits identically.
        assertEquals(FitSize(1920, 1080), fitInside(1920, 1080, 16, 9))
    }

    @Test fun portraitVideoInLandscapeContainer_pillarboxLimitedByHeight() {
        // 1080x1920 (portrait iPhone) on a 1920x1080 landscape screen -> height fills,
        // width is narrower -> black bars left/right.
        val fit = fitInside(1920, 1080, 1080, 1920)
        assertEquals(1080, fit.height)
        assertEquals(607, fit.width) // 1080 * 1080 / 1920 = 607 (truncated)
        assertTrue("must not exceed container width", fit.width < 1920)
    }

    @Test fun wideVideoInPortraitContainer_letterboxLimitedByWidth() {
        val fit = fitInside(1080, 1920, 1920, 1080)
        assertEquals(1080, fit.width)
        assertEquals(607, fit.height)
        assertTrue("must not exceed container height", fit.height < 1920)
    }

    @Test fun neverExceedsContainerInEitherDimension() {
        val cases = listOf(
            intArrayOf(800, 600, 1080, 1920),
            intArrayOf(1920, 1080, 4000, 1000),
            intArrayOf(500, 500, 1, 3),
            intArrayOf(1280, 720, 720, 1280),
        )
        for (c in cases) {
            val fit = fitInside(c[0], c[1], c[2], c[3])
            assertTrue("width ${fit.width} <= ${c[0]}", fit.width <= c[0])
            assertTrue("height ${fit.height} <= ${c[1]}", fit.height <= c[1])
            assertTrue("width positive", fit.width > 0)
            assertTrue("height positive", fit.height > 0)
        }
    }

    @Test fun preservesVideoAspectRatioWithinRounding() {
        val fit = fitInside(1000, 1000, 1080, 1920)
        val videoRatio = 1080.0 / 1920.0
        val fitRatio = fit.width.toDouble() / fit.height.toDouble()
        assertTrue("aspect drift too large: $fitRatio vs $videoRatio",
            abs(fitRatio - videoRatio) < 0.01)
    }

    @Test fun nonPositiveInputs_returnZeroSize() {
        assertEquals(FitSize(0, 0), fitInside(0, 100, 10, 10))
        assertEquals(FitSize(0, 0), fitInside(100, 0, 10, 10))
        assertEquals(FitSize(0, 0), fitInside(100, 100, 0, 10))
        assertEquals(FitSize(0, 0), fitInside(100, 100, 10, -5))
    }
}
