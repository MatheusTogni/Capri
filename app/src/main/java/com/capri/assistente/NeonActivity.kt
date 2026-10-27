package com.capri.assistente

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Peças visuais neon usadas pelas telas da Capri. */
abstract class NeonActivity : Activity() {

    protected fun applyWindowColors() {
        window.statusBarColor = Neon.BG
        window.navigationBarColor = Neon.BG
    }

    protected fun panel(border: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = CutDrawable(Neon.PANEL, border, dp(1).toFloat(), dp(12).toFloat())
        setPadding(dp(14), dp(12), dp(14), dp(14))
    }

    protected fun input() = EditText(this).apply {
        textSize = 15f
        setTextColor(Neon.TEXT)
        setHintTextColor(Neon.MUTED)
        highlightColor = 0x668A3BFF
        background = CutDrawable(Neon.PANEL, Neon.PURPLE, dp(1).toFloat(), dp(8).toFloat())
        setPadding(dp(12), dp(12), dp(12), dp(12))
        if (Build.VERSION.SDK_INT >= 29) {
            textCursorDrawable = GradientDrawable().apply { setColor(Neon.GREEN); setSize(dp(2), 0) }
        }
    }

    protected fun neonButton(t: String, color: Int, filled: Boolean = false) = Button(this).apply {
        text = t
        isAllCaps = true
        letterSpacing = 0.08f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        stateListAnimator = null
        styleButton(this, color, filled)
    }

    protected fun styleButton(b: Button, color: Int, filled: Boolean) {
        val cut = dp(10).toFloat()
        val shape = if (filled) CutDrawable(color, cut = cut)
                    else CutDrawable(0x1A000000 or (color and 0x00FFFFFF), color, dp(2).toFloat(), cut)
        b.background = RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), shape, CutDrawable(color, cut = cut))
        b.setTextColor(if (filled) Neon.BG else color)
    }

    protected fun section(t: String) = mono(13f, Neon.RED).apply {
        text = "▌ ${t.uppercase()}"
        letterSpacing = 0.15f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setPadding(0, dp(28), 0, 0)
    }

    protected fun label(t: String) = mono(11f, Neon.MUTED).apply {
        text = "> ${t.uppercase()}"
        letterSpacing = 0.05f
        setPadding(0, dp(14), 0, 0)
    }

    protected fun mono(size: Float, color: Int) = TextView(this).apply {
        textSize = size
        setTextColor(color)
        typeface = Typeface.MONOSPACE
    }

    protected fun blockParams(top: Int) =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = top }

    protected fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
