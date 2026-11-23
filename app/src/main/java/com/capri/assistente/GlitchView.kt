package com.capri.assistente

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import java.util.Random
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Efeito cyberpunk de tela cheia que aparece por cima de tudo quando a Capri é chamada.
 * Só desenha (não toca em nada e não pega toques): brilho nas bordas na cor do status, linhas de varredura,
 * um feixe que desce a tela, cantoneiras de HUD, um texto que se "decodifica" e rajadas de glitch
 * (faixas coloridas com canais RGB separados) — uma forte ao ativar, depois algumas curtas e espaçadas.
 *
 * Os ajustes estão nas constantes do fim do arquivo.
 */
class GlitchView(ctx: Context) : View(ctx) {

    private val d = resources.displayMetrics.density
    private val rnd = Random()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint()
    private val scan = Paint()
    private val beam = Paint()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        letterSpacing = 0.12f
    }
    private val path = Path()
    private val scanMatrix = Matrix()
    private var scanShader: BitmapShader? = null
    private var tile = 3 // altura (px) do desenho repetido das linhas de varredura
    private var beamH = 0f
    private var vignette: RadialGradient? = null // refeito quando a cor muda ou a tela muda de tamanho

    private var color = Neon.YELLOW // cor do brilho: acompanha o status
    private var running = false
    private var t0 = 0L
    private var burstEnd = 0L // até quando dura a rajada de glitch atual
    private var burstPower = 0f // 0..1, força das rajadas depois da entrada
    private var nextBurst = 0L
    private var lastTick = 0L
    private var lastHud = 0L
    private var tearY = -1f
    private val slices = ArrayList<Slice>()
    private var hudLeft = ""
    private var hudRight = ""

    private class Slice(val x: Float, val y: Float, val w: Float, val h: Float, val shift: Float, val color: Int)

    init {
        // uma linha escura a cada poucos dp, repetida na tela inteira
        tile = (3 * d).toInt().coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(1, tile, Bitmap.Config.ARGB_8888)
        bmp.setPixel(0, 0, Color.argb(SCAN_ALPHA, 0, 0, 0))
        scanShader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        scan.shader = scanShader
    }

    /**
     * Liga o efeito, começando pela rajada forte de entrada. Se o usuário desligou as animações do Android,
     * não faz nada.
     */
    fun start() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val now = SystemClock.uptimeMillis()
        t0 = now
        burstEnd = now + INTRO_MS
        burstPower = 1f
        nextBurst = burstEnd + 900
        lastTick = 0L
        lastHud = 0L
        slices.clear()
        tearY = -1f
        running = true
        postInvalidateOnAnimation()
    }

    fun stop() {
        running = false
        slices.clear()
        invalidate()
    }

    /** O brilho das bordas acompanha o que a Capri está fazendo, e cada mudança dá um solavanco de glitch. */
    fun setStatus(status: String) {
        val c = Neon.statusColor(status)
        if (c == color) return
        color = c
        vignette = null
        val now = SystemClock.uptimeMillis()
        if (running && now >= burstEnd) {
            burstPower = 0.7f
            burstEnd = now + 170
            nextBurst = burstEnd + 900
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        vignette = null
        beamH = 140 * d
        beam.shader = LinearGradient(
            0f, 0f, 0f, beamH,
            intArrayOf(withAlpha(Neon.GREEN, 0), withAlpha(Neon.GREEN, BEAM_ALPHA), withAlpha(Neon.GREEN, 0)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (!running) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val now = SystemClock.uptimeMillis()
        val age = now - t0
        tick(now, age, w, h)

        drawGlow(canvas, w, h, age)

        // linhas de varredura rolando devagar
        scanMatrix.setTranslate(0f, (age * 0.03f * d) % tile)
        scanShader?.setLocalMatrix(scanMatrix)
        canvas.drawRect(0f, 0f, w, h, scan)

        // feixe verde que desce a tela a cada poucos segundos
        val beamY = ((age % BEAM_MS) / BEAM_MS.toFloat()) * (h + beamH) - beamH
        canvas.save()
        canvas.translate(0f, beamY)
        canvas.drawRect(0f, 0f, w, beamH, beam)
        canvas.restore()

        // clarão vermelho no instante em que ativa
        if (age < FLASH_MS) {
            val a = ((1f - age / FLASH_MS.toFloat()) * FLASH_ALPHA).toInt()
            canvas.drawColor(Color.argb(a, 255, 31, 75))
        }

        drawSlices(canvas, w)
        drawCorners(canvas, w, h, age, now)
        drawHud(canvas, w, now)

        postInvalidateOnAnimation()
    }

    // ---------- estado ----------

    /** Agenda e atualiza as rajadas de glitch e o texto do HUD (a cada [TICK_MS], para o efeito ser "picado"). */
    private fun tick(now: Long, age: Long, w: Float, h: Float) {
        if (now >= burstEnd && now >= nextBurst) {
            burstPower = 0.3f + rnd.nextFloat() * 0.45f
            burstEnd = now + 80 + rnd.nextInt(170)
            nextBurst = burstEnd + 800 + rnd.nextInt(1900)
        }
        if (now >= burstEnd) {
            if (slices.isNotEmpty()) { slices.clear(); tearY = -1f }
        } else if (now - lastTick >= TICK_MS) {
            lastTick = now
            makeSlices(power(age), w, h)
        }
        if (now - lastHud >= TICK_MS) {
            lastHud = now
            hudLeft = decode(HUD_LEFT, age)
            hudRight = decode(HUD_RIGHT, age)
        }
    }

    /** Força do glitch agora: forte na entrada e caindo, depois a da rajada atual. */
    private fun power(age: Long): Float = if (age < INTRO_MS) 1f - 0.6f * age / INTRO_MS else burstPower

    private fun makeSlices(p: Float, w: Float, h: Float) {
        slices.clear()
        val n = 3 + (p * 8).toInt()
        for (i in 0 until n) {
            val sh = 2 * d + rnd.nextFloat() * (6 + 18 * p) * d
            val sw = w * (0.15f + rnd.nextFloat() * 0.85f)
            val x = if (rnd.nextBoolean()) 0f else w - sw // colada numa das bordas
            val y = rnd.nextFloat() * (h - sh)
            val c = when (rnd.nextInt(5)) {
                0 -> Neon.CYAN
                1 -> Neon.GREEN
                2 -> Neon.PURPLE
                else -> Neon.RED
            }
            val shift = (4 + rnd.nextFloat() * 22 * p) * d * (if (rnd.nextBoolean()) 1 else -1)
            slices.add(Slice(x, y, sw, sh, shift, c))
        }
        tearY = if (rnd.nextFloat() < 0.6f) rnd.nextFloat() * h else -1f
    }

    /** O texto vai aparecendo letra a letra; as que faltam piscam como caracteres embaralhados. */
    private fun decode(s: String, age: Long): String {
        val shown = (age / DECODE_MS).toInt()
        if (shown >= s.length) return s
        val sb = StringBuilder(s.length)
        for (i in s.indices) sb.append(if (i < shown || s[i] == ' ') s[i] else GLYPHS[rnd.nextInt(GLYPHS.length)])
        return sb.toString()
    }

    // ---------- desenho ----------

    /** Brilho nas bordas da tela, na cor do status, pulsando devagar. */
    private fun drawGlow(canvas: Canvas, w: Float, h: Float, age: Long) {
        if (vignette == null) {
            val g = RadialGradient(
                w / 2, h / 2, hypot(w, h) / 2,
                intArrayOf(withAlpha(color, 0), withAlpha(color, 0), withAlpha(color, GLOW_ALPHA)),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
            )
            vignette = g
            glow.shader = g
        }
        val fade = (age / 300f).coerceAtMost(1f)
        val pulse = 0.6f + 0.4f * sin(age / 380.0).toFloat()
        glow.alpha = (255 * fade * pulse).toInt()
        canvas.drawRect(0f, 0f, w, h, glow)
    }

    private fun drawSlices(canvas: Canvas, w: Float) {
        for (s in slices) {
            fill.color = withAlpha(s.color, 0x66)
            canvas.drawRect(s.x + s.shift, s.y, s.x + s.w + s.shift, s.y + s.h, fill)
            // "separação de canais": uma cópia ciano deslocada para o outro lado
            fill.color = withAlpha(Neon.CYAN, 0x40)
            canvas.drawRect(s.x - s.shift, s.y + d, s.x + s.w - s.shift, s.y + s.h, fill)
        }
        if (tearY >= 0f) {
            fill.color = withAlpha(Color.WHITE, 0xB0)
            canvas.drawRect(0f, tearY, w, tearY + 1.5f * d, fill)
        }
    }

    /** Cantoneiras de HUD nos quatro cantos: crescem na entrada e tremem durante as rajadas. */
    private fun drawCorners(canvas: Canvas, w: Float, h: Float, age: Long, now: Long) {
        val len = 30 * d * (age / 350f).coerceAtMost(1f)
        if (len < 1f) return
        val jitter = if (now < burstEnd) (rnd.nextFloat() - 0.5f) * 8 * d * power(age) else 0f
        val left = 14 * d + jitter
        val right = w - 14 * d + jitter
        val top = TOP_DP * d
        val bottom = h - 18 * d
        stroke.strokeWidth = 2.5f * d
        for (pass in 0..1) {
            // primeiro a sombra vermelha deslocada, depois o traço verde por cima
            stroke.color = if (pass == 0) withAlpha(Neon.RED, 0xB0) else withAlpha(Neon.GREEN, 0xE6)
            val o = if (pass == 0) 2 * d else 0f
            bracket(canvas, left + o, top, len, 1f, 1f)
            bracket(canvas, right + o, top, len, -1f, 1f)
            bracket(canvas, left + o, bottom, len, 1f, -1f)
            bracket(canvas, right + o, bottom, len, -1f, -1f)
        }
    }

    private fun bracket(canvas: Canvas, x: Float, y: Float, len: Float, dx: Float, dy: Float) {
        path.reset()
        path.moveTo(x, y + dy * len)
        path.lineTo(x, y)
        path.lineTo(x + dx * len, y)
        canvas.drawPath(path, stroke)
    }

    private fun drawHud(canvas: Canvas, w: Float, now: Long) {
        val y = (TOP_DP + 22) * d
        val split = if (now < burstEnd) 1.5f * d else 0f
        for (pass in 0..1) {
            if (pass == 0 && split == 0f) continue
            text.color = if (pass == 0) withAlpha(Neon.RED, 0xCC) else withAlpha(Neon.GREEN, 0xF2)
            val o = if (pass == 0) split else 0f
            text.textAlign = Paint.Align.LEFT
            canvas.drawText(hudLeft, 22 * d + o, y, text)
            text.textAlign = Paint.Align.RIGHT
            canvas.drawText(hudRight, w - 22 * d + o, y, text)
        }
    }

    private fun withAlpha(c: Int, a: Int): Int = (a shl 24) or (c and 0x00FFFFFF)

    private companion object {
        // ---- ajustes ----
        const val INTRO_MS = 650L // rajada forte de entrada
        const val TICK_MS = 55L // de quanto em quanto tempo as faixas e o texto mudam
        const val FLASH_MS = 110L // clarão vermelho ao ativar
        const val FLASH_ALPHA = 0x50 // força do clarão (0..255)
        const val GLOW_ALPHA = 0x90 // força do brilho nas bordas (0..255)
        const val SCAN_ALPHA = 0x30 // força das linhas de varredura (0..255)
        const val BEAM_ALPHA = 0x22 // força do feixe que desce (0..255)
        const val BEAM_MS = 3200L // tempo do feixe para cruzar a tela
        const val DECODE_MS = 40L // tempo para cada letra do texto aparecer
        const val TOP_DP = 36 // distância do topo (abaixo da barra de status)
        const val HUD_LEFT = "// CAPRI.SYS"
        const val HUD_RIGHT = "ONLINE"
        const val GLYPHS = "01#/\\<>_=+*"
    }
}
