package com.capri.assistente

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Paleta tirada da própria Capri: vermelho, verde-neon, roxo e preto. */
object Neon {
    const val BG = 0xFF0B0710.toInt()
    const val PANEL = 0xFF160C16.toInt()
    const val RED = 0xFFFF1F4B.toInt()
    const val GREEN = 0xFF39FF6A.toInt()
    const val PURPLE = 0xFF8A3BFF.toInt()
    const val CYAN = 0xFF2E7BFF.toInt()
    const val YELLOW = 0xFFFFE14D.toInt()
    const val TEXT = 0xFFF2E9F0.toInt()
    const val MUTED = 0xFF8C7A8A.toInt()

    /** Cor do status conforme o que a Capri está fazendo. */
    fun statusColor(status: String): Int = when {
        status.startsWith("Ouvindo") -> GREEN
        status.startsWith("Pode falar") -> CYAN
        status.startsWith("Pensando") -> PURPLE
        status.startsWith("Falando") -> RED
        status.startsWith("Desligada") -> MUTED
        else -> YELLOW
    }
}

/** Retângulo com os cantos superior-esquerdo e inferior-direito cortados. */
class CutDrawable(
    private val fill: Int,
    private val stroke: Int = 0,
    private val strokeWidth: Float = 0f,
    private val cut: Float,
) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill; style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = stroke
        style = Paint.Style.STROKE
        this.strokeWidth = this@CutDrawable.strokeWidth
    }
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val h = strokeWidth / 2
        val l = b.left + h; val t = b.top + h; val r = b.right - h; val btm = b.bottom - h
        path.reset()
        path.moveTo(l + cut, t)
        path.lineTo(r, t)
        path.lineTo(r, btm - cut)
        path.lineTo(r - cut, btm)
        path.lineTo(l, btm)
        path.lineTo(l, t + cut)
        path.close()
        if (fill != 0) canvas.drawPath(path, fillPaint)
        if (strokeWidth > 0f) canvas.drawPath(path, strokePaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
