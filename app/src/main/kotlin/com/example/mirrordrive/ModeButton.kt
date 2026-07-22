package com.example.mirrordrive

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.TextView

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

    val glyph = WindowGlyphDrawable(glyphSize, context.dpf(1.6f)).apply {
        setColors(controlGlyphColors())
    }

    return ImageView(context).apply {
        setImageDrawable(glyph)
        scaleType = ImageView.ScaleType.CENTER
        // Chip fill/stroke are inset so the visible mark is small while the view stays ≥48dp.
        background = controlChipBackground(context, chipInset)
        isClickable = true
        isFocusable = true
        contentDescription = "창 모드"
        minimumWidth = touch
        minimumHeight = touch
        setOnClickListener { onClick() }
    }
}

/**
 * Builds the fullscreen quit (✕) control — the same restrained night-dashboard chip as the
 * 창 모드 button, but with a mirror-cyan "✕" glyph. Tapping it quits the app entirely (the
 * caller wires this to a full shutdown). Sits next to 창 모드 in the top area.
 */
fun buildQuitButton(context: Context, onClick: () -> Unit): View {
    val touch = context.dp(48)          // fixed touch target
    val chipInset = context.dp(8)       // shrinks the visible chip inside the touch target

    return TextView(context).apply {
        text = "✕"
        setTextColor(controlGlyphColors())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        typeface = Typeface.DEFAULT     // lighter weight — thin, refined glyph, matches overlay
        includeFontPadding = false
        gravity = Gravity.CENTER
        // Same translucent chip / ripple / inset as 창 모드 so both controls read as one system.
        background = controlChipBackground(context, chipInset)
        isClickable = true
        isFocusable = true
        contentDescription = "종료"
        minimumWidth = touch
        minimumHeight = touch
        minWidth = touch
        minHeight = touch
        setOnClickListener { onClick() }
    }
}

/** Shared translucent chip + cyan ripple for the fullscreen controls, inset inside the touch
 *  target so the visible mark stays small while the hit area is ≥48dp. */
private fun controlChipBackground(context: Context, chipInset: Int): Drawable {
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
    return InsetDrawable(ripple, chipInset)
}

/** Glyph/text colour for the fullscreen controls: dim mirror-cyan at rest, full mirror when
 *  pressed / focused. */
private fun controlGlyphColors() = ColorStateList(
    arrayOf(
        intArrayOf(android.R.attr.state_pressed),
        intArrayOf(android.R.attr.state_focused),
        intArrayOf(),
    ),
    intArrayOf(Palette.MIRROR, Palette.MIRROR, Palette.MIRROR_DIM),
)
