package com.example.mirrordrive

import android.app.Activity
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.widget.ScrollView
import android.widget.TextView

/**
 * A deliberately minimal error screen that must survive when everything else has failed —
 * a broken theme, an unloadable native library, a customized ROM. It extends the plain
 * framework [Activity] (NOT AppCompat, which needs a working AppCompat theme) and touches NO
 * native code and NO Android resources that a stripped ROM might be missing: colors, sizes and
 * text are all built in code.
 *
 * It shows a scrollable, selectable report — a header (device / sdk / ABIs) plus the stack
 * trace — that a field user can screenshot and send to support.
 */
class ErrorActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val message = intent?.getStringExtra(EXTRA_MESSAGE) ?: "No details available."

        // Night-dashboard spirit, but hardcoded so nothing can be missing at runtime.
        val ground = 0xFF0B0F14.toInt() // night ink
        val silver = 0xFFAEB9C0.toInt() // muted light text
        val mirror = 0xFF55D6D0.toInt() // cyan accent for the headline

        val text = TextView(this).apply {
            setBackgroundColor(ground)
            setTextColor(silver)
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
            val pad = (density() * 20).toInt()
            setPadding(pad, pad, pad, pad)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.START

            val headline = "MirrorDrive stopped. Screenshot this and send it to support.\n\n"
            val styled = android.text.SpannableString(headline + message)
            styled.setSpan(
                android.text.style.ForegroundColorSpan(mirror),
                0, headline.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            setText(styled)
        }

        val scroller = ScrollView(this).apply {
            setBackgroundColor(ground)
            addView(text)
        }
        setContentView(scroller)
    }

    private fun density(): Float = resources.displayMetrics.density

    companion object {
        const val EXTRA_MESSAGE = "com.example.mirrordrive.ERROR_MESSAGE"
    }
}
