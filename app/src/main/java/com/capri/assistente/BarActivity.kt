package com.capri.assistente

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * A mesma barrinha, mas como tela transparente: é o único jeito de aparecer
 * por cima da tela de bloqueio (e acender a tela). Fecha sozinha quando a barra some.
 */
class BarActivity : Activity() {

    private lateinit var bar: CapriBarView
    private val listener: () -> Unit = { update() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        // só ocupa o rodapé; toques fora da barra vão para a tela de bloqueio
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.setGravity(Gravity.BOTTOM)
        window.setLayout(MATCH_PARENT, WRAP_CONTENT)

        val d = resources.displayMetrics.density
        bar = CapriBarView(this)
        setContentView(FrameLayout(this).apply {
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), (48 * d).toInt())
            addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
        CapriBus.onBar = listener
        update()
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        update()
    }

    private fun update() {
        if (!CapriBus.barVisible) {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        } else {
            bar.setStatus(CapriBus.status)
        }
    }

    override fun onDestroy() {
        if (CapriBus.onBar === listener) CapriBus.onBar = null
        super.onDestroy()
    }
}
