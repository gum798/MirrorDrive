package com.example.mirrordrive

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.widget.ImageView

/**
 * Builds the "창 모드" (window mode) control as a small, restrained glyph rather than a chunky
 * pill:
 *
 *  - icon-only — a hand-drawn window glyph (~22dp) that reads as its own purpose, labelled for
 *    accessibility via a content description;
 *  - a barely-there translucent chip (faint cyan fill + a cyan hairline) so the screen behind
 *    shows through — no heavy fill, no glow;
 *  - states — rest (dim mirror glyph), pressed (full mirror glyph + cyan ripple), focus-visible
 *    (thicker cyan outline for keyboard / D-pad accessibility);
 *  - the visible chip is inset inside a fixed ≥48dp touch target (transparent padding), so the
 *    mark is small but the hit area stays large.
 *
 * The caller supplies the click action; this file never touches the mirror/overlay logic.
 */
fun buildModeButton(context: Context, onClick: () -> Unit): View {
    val touch = context.dp(48)          // fixed touch target
    val glyphSize = context.dp(22)      // small visible glyph
    val chipInset = context.dp(8)       // shrinks the visible chip inside the touch target
    val stroke = context.dp(1f).coerceAtLeast(1)
    val focusStroke = context.dp(1.5f).coerceAtLeast(2)

    fun chip(fill: Int, strokeColor: Int, strokeW: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setStroke(strokeW, strokeColor)
    }

    val bg = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), chip(Palette.MIRROR_FAINT_FILL, Palette.MIRROR, stroke))
        addState(intArrayOf(android.R.attr.state_focused), chip(Palette.MIRROR_FAINT_FILL, Palette.MIRROR, focusStroke))
        addState(intArrayOf(), chip(Palette.MIRROR_FAINT_FILL, Palette.MIRROR_HAIRLINE, stroke))
    }
    val ripple = RippleDrawable(ColorStateList.valueOf(Palette.MIRROR_RIPPLE), bg, null)

    // Glyph: dim mirror-cyan at rest, full mirror when pressed / focused.
    val glyphColors = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_pressed),
            intArrayOf(android.R.attr.state_focused),
            intArrayOf(),
        ),
        intArrayOf(Palette.MIRROR, Palette.MIRROR, Palette.MIRROR_DIM),
    )
    val glyph = WindowGlyphDrawable(glyphSize, context.dpf(1.6f)).apply { setColors(glyphColors) }

    return ImageView(context).apply {
        setImageDrawable(glyph)
        scaleType = ImageView.ScaleType.CENTER
        // Chip fill/stroke are inset so the visible mark is small while the view stays ≥48dp.
        background = InsetDrawable(ripple, chipInset)
        isClickable = true
        isFocusable = true
        contentDescription = "창 모드"
        minimumWidth = touch
        minimumHeight = touch
        setOnClickListener { onClick() }
    }
}
