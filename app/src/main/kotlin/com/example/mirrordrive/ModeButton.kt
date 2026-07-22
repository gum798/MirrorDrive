package com.example.mirrordrive

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView

/**
 * Builds the "창 모드" (window mode) control as a crafted pill rather than a default grey button:
 *
 *  - rounded surface-fill pill with a 1.5dp mirror-cyan stroke and a soft cyan elevation glow;
 *  - a hand-drawn window glyph + silver, slightly-tracked label;
 *  - states — rest (silver on translucent surface), pressed (ground text on a full cyan fill),
 *    focus-visible (thicker cyan outline for keyboard / D-pad accessibility) — plus a cyan ripple;
 *  - a ≥48dp touch target.
 *
 * The caller supplies the click action; this file never touches the mirror/overlay logic.
 */
fun buildModeButton(context: Context, onClick: () -> Unit): View {
    val radius = context.dpf(24f)
    val stroke = context.dp(1.5f).coerceAtLeast(1)
    val focusStroke = context.dp(2f).coerceAtLeast(2)

    fun pill(fill: Int, strokeColor: Int, strokeW: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(fill)
        setStroke(strokeW, strokeColor)
    }

    val bg = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), pill(Palette.MIRROR, Palette.MIRROR, stroke))
        addState(intArrayOf(android.R.attr.state_focused), pill(Palette.SURFACE_FILL, Palette.MIRROR, focusStroke))
        addState(intArrayOf(), pill(Palette.SURFACE_FILL, Palette.MIRROR, stroke))
    }
    val ripple = RippleDrawable(ColorStateList.valueOf(Palette.MIRROR_RIPPLE), bg, null)

    // Label + glyph follow the same state list: silver at rest, ground when pressed on cyan.
    val contentColors = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
        intArrayOf(Palette.GROUND, Palette.SILVER),
    )

    val glyph = WindowGlyphDrawable(context.dp(18), context.dpf(1.6f)).apply {
        setColors(contentColors)
    }

    return TextView(context).apply {
        text = "창 모드"
        setTextColor(contentColors)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        letterSpacing = 0.05f
        gravity = Gravity.CENTER
        includeFontPadding = false

        setCompoundDrawablesRelativeWithIntrinsicBounds(glyph, null, null, null)
        compoundDrawablePadding = context.dp(8)

        background = ripple
        isClickable = true
        isFocusable = true
        minHeight = context.dp(48)
        minWidth = context.dp(48)
        setPadding(context.dp(18), context.dp(12), context.dp(18), context.dp(12))

        // Soft cyan glow: a rounded elevation shadow tinted with the accent.
        elevation = context.dpf(6f)
        outlineSpotShadowColor = Palette.MIRROR
        outlineAmbientShadowColor = Palette.MIRROR

        setOnClickListener { onClick() }
    }
}
