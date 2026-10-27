package com.capri.assistente

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.sin

/** Barrinha "a Capri está ouvindo" que aparece por cima de tudo depois da palavra de ativação. */
class CapriBarView(ctx: Context) : LinearLayout(ctx) {

    private val d = resources.displayMetrics.density
    private val text: TextView
    private val bars = List(4) { View(ctx) }
    private val anim: ValueAnimator

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = CutDrawable(0xF20B0710.toInt(), Neon.GREEN, 1.5f * d, 12f * d)
        setPadding(dp(10), dp(8), dp(16), dp(8))

        addView(ImageView(ctx).apply {
            setImageResource(R.drawable.capri_art)
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setOval(0, 0, v.width, v.height)
            }
            clipToOutline = true
        }, LayoutParams(dp(36), dp(36)))

        text = TextView(ctx).apply {
            textSize = 14f
            setTextColor(Neon.TEXT)
            typeface = Typeface.MONOSPACE
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12), 0, dp(12), 0)
        }
        addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        val eq = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        bars.forEach { b ->
            b.background = GradientDrawable().apply { setColor(Neon.GREEN); cornerRadius = 2f * d }
            eq.addView(b, LayoutParams(dp(3), dp(18)).apply { marginStart = dp(3) })
        }
        addView(eq, LayoutParams(LayoutParams.WRAP_CONTENT, dp(18)))

        // "equalizador": cada barrinha sobe e desce com um atraso diferente
        anim = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                bars.forEachIndexed { i, b -> b.scaleY = 0.3f + 0.7f * abs(sin(t + i * 0.8f)) }
            }
        }
    }

    fun setStatus(s: String) {
        text.text = s
        val c = Neon.statusColor(s)
        bars.forEach { (it.background as GradientDrawable).setColor(c) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim.start()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    private fun dp(v: Int) = (v * d).toInt()
}
