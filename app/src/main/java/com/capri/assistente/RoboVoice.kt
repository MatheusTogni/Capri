package com.capri.assistente

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Ajustes da voz robótica. [pitch] e [rate] em % (100 = normal) vão direto para o TTS;
 * [metal], [digital] e [echo] (0 a 100) são efeitos aplicados no áudio gerado.
 */
data class RoboFx(val pitch: Int, val rate: Int, val metal: Int, val digital: Int, val echo: Int) {
    val hasEffects get() = metal > 0 || digital > 0 || echo > 0

    companion object {
        /** Garota jovem, mas dá para perceber que é robótica. */
        val CAPRI = RoboFx(pitch = 115, rate = 100, metal = 35, digital = 15, echo = 30)
    }
}

/** Os efeitos em si, aplicados no PCM 16 bits mono. */
object RoboEffects {

    private const val RING_HZ = 160.0 // portadora do "metálico": baixa = robô grave, alta = sino
    private const val LFO_HZ = 0.3 // velocidade do vaivém do eco sintético

    fun process(input: ShortArray, sr: Int, fx: RoboFx): ShortArray {
        val metal = fx.metal / 100f
        val digital = fx.digital / 100f
        val echo = fx.echo / 100f
        val out = FloatArray(input.size)

        // eco sintético: atraso curtinho (2 a 6 ms) que vai e volta, com realimentação = timbre "oco"
        val bufLen = (sr * 0.008).toInt() + 2
        val buf = FloatArray(bufLen)
        var w = 0
        val feedback = 0.6f * echo
        val wet = 0.6f * echo

        // digital: menos bits (16 → 6) e amostras repetidas (soa "pixelado")
        val levels = 2f.pow(15 - 10 * digital)
        val hold = 1f + 4f * digital
        var holdPhase = hold
        var held = 0f

        for (i in input.indices) {
            var x = input[i] / 32768f
            if (metal > 0) {
                // modulação em anel misturada com o som original
                val m = 0.8f * metal
                x *= (1 - m) + m * sin(2 * PI * RING_HZ * i / sr).toFloat()
            }
            if (echo > 0) {
                val lfo = 0.5 + 0.5 * sin(2 * PI * LFO_HZ * i / sr)
                var pos = w - sr * (0.002 + 0.004 * lfo)
                while (pos < 0) pos += bufLen
                val i0 = floor(pos).toInt() % bufLen
                val frac = (pos - floor(pos)).toFloat()
                val delayed = buf[i0] * (1 - frac) + buf[(i0 + 1) % bufLen] * frac
                buf[w] = x + feedback * delayed
                w = (w + 1) % bufLen
                x += wet * delayed
            }
            if (digital > 0) {
                holdPhase += 1f
                if (holdPhase >= hold) {
                    holdPhase -= hold
                    held = round(x * levels) / levels
                }
                x = held
            }
            out[i] = x
        }

        // os efeitos mudam o volume: normaliza para o pico ficar em 90% (sem aumentar mais de 4x)
        val peak = out.maxOfOrNull { abs(it) } ?: 0f
        val gain = if (peak > 1e-4f) (0.9f / peak).coerceAtMost(4f) else 1f
        return ShortArray(out.size) { (out[it] * gain * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
    }
}

/**
 * Volume próprio da Capri: enquanto ela fala (ou bipa), a mídia vai para o nível dela e
 * a música de outros apps abaixa; no fim volta como estava. Contado, para fala e bipe
 * juntos não devolverem o volume antes da hora. Só na thread principal.
 */
object CapriVolume {
    private var holds = 0
    private var saved = -1 // volume da mídia antes da Capri
    private var mine = -1 // volume que a Capri colocou
    private var focus: AudioFocusRequest? = null

    fun hold(ctx: Context, percent: Int) {
        if (holds++ > 0) return
        val am = ctx.getSystemService(AudioManager::class.java)
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(SPEECH)
            .build()
            .also { try { am.requestAudioFocus(it) } catch (_: Exception) {} }
        if (am.isVolumeFixed) return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        saved = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        mine = (max * percent / 100.0).roundToInt().coerceIn(0, max)
        try { am.setStreamVolume(AudioManager.STREAM_MUSIC, mine, 0) } catch (_: Exception) { mine = -1 }
    }

    fun release(ctx: Context) {
        if (holds == 0 || --holds > 0) return
        restore(ctx)
    }

    /** Devolve o volume já, mesmo com algo ainda segurando (ex.: Capri desligando). */
    fun releaseAll(ctx: Context) {
        if (holds == 0) return
        holds = 0
        restore(ctx)
    }

    private fun restore(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java)
        focus?.let { try { am.abandonAudioFocusRequest(it) } catch (_: Exception) {} }
        focus = null
        // se mexeram no volume enquanto ela falava, fica o que a pessoa escolheu
        if (mine >= 0 && am.getStreamVolume(AudioManager.STREAM_MUSIC) == mine) {
            try { am.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0) } catch (_: Exception) {}
        }
        mine = -1
    }

    val SPEECH: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA) // mesmo canal do TTS (mídia)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
}

/** WAV gerado pelo TTS: só PCM 16 bits; estéreo vira mono. */
class Pcm(val samples: ShortArray, val rate: Int) {
    companion object {
        fun read(f: File): Pcm {
            val b = f.readBytes()
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            require(b.size >= 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF") { "WAV inválido" }
            var pos = 12
            var rate = 0
            var channels = 1
            var bits = 16
            var dataStart = -1
            var dataLen = 0
            while (pos + 8 <= b.size) {
                val id = String(b, pos, 4, Charsets.US_ASCII)
                val len = bb.getInt(pos + 4)
                val body = pos + 8
                if (id == "fmt ") {
                    channels = bb.getShort(body + 2).toInt()
                    rate = bb.getInt(body + 4)
                    bits = bb.getShort(body + 14).toInt()
                } else if (id == "data") {
                    dataStart = body
                    // alguns motores gravam o tamanho errado (0 ou maior que o arquivo)
                    dataLen = if (len <= 0 || body + len > b.size) b.size - body else len
                    break
                }
                if (len < 0) break
                pos = body + len + (len and 1)
            }
            require(dataStart >= 0 && bits == 16 && rate > 0 && channels > 0) { "WAV sem PCM 16 bits" }
            val frames = dataLen / 2 / channels
            val samples = ShortArray(frames) { i ->
                var sum = 0
                for (c in 0 until channels) sum += bb.getShort(dataStart + (i * channels + c) * 2)
                (sum / channels).toShort()
            }
            return Pcm(samples, rate)
        }
    }
}

/** Toca o PCM numa thread própria; [onEnd] só é chamado se tocar até o fim (não no [stop]). */
class RoboPlayer(private val pcm: Pcm, private val onEnd: () -> Unit) {
    @Volatile private var stopped = false
    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(CapriVolume.SPEECH)
        .setAudioFormat(AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(pcm.rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build())
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(maxOf(
            AudioTrack.getMinBufferSize(pcm.rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT),
            pcm.rate / 5 * 2))
        .build()

    fun start() {
        Thread {
            var finished = false
            try {
                track.play()
                val s = pcm.samples
                var off = 0
                // pedaços pequenos: depois do stop() (pause + flush) a escrita pendente termina logo
                while (!stopped && off < s.size) {
                    val n = track.write(s, off, minOf(1024, s.size - off))
                    if (n <= 0) break
                    off += n
                }
                // espera tocar o que ficou no buffer (com folga, caso a posição trave)
                val deadline = System.currentTimeMillis() + s.size * 1000L / pcm.rate + 2000
                while (!stopped && track.playbackHeadPosition < off && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20)
                }
                finished = !stopped
            } catch (_: Exception) {
                finished = !stopped
            } finally {
                try { track.stop() } catch (_: Exception) {}
                track.release()
            }
            if (finished) onEnd()
        }.start()
    }

    fun stop() {
        stopped = true
        try { track.pause(); track.flush() } catch (_: Exception) {}
    }
}

/**
 * Fala com o [tts] usando os ajustes de [RoboFx]. Sem efeitos, fala direto; com efeitos,
 * gera o áudio num arquivo, aplica os efeitos e toca. Toma conta do listener do [tts].
 */
class RoboSpeaker(context: Context, private val tts: TextToSpeech) {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val file = File(ctx.cacheDir, "fala-${System.identityHashCode(this)}.wav")
    private var holdingVolume = false
    private var seq = 0
    private var current: String? = null // fala em andamento; eventos de outras são ignorados
    private var currentFx: RoboFx? = null // null = sem efeitos (tts.speak direto)
    private var currentText = ""
    private var player: RoboPlayer? = null
    private var onStart: (() -> Unit)? = null
    private var onDone: (() -> Unit)? = null

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                main.post { if (id == current && currentFx == null) onStart?.invoke() }
            }
            override fun onDone(id: String?) {
                main.post { if (id == current) { if (currentFx == null) finish(id) else play(id!!) } }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { main.post { finish(id) } }
        })
    }

    /**
     * [volume] = volume da Capri em % (independente do volume do celular).
     * [onStart] quando o som começa; [onDone] quando termina ou dá erro (não depois de [stop]).
     */
    fun speak(text: String, fx: RoboFx, volume: Int, onStart: () -> Unit = {}, onDone: () -> Unit = {}) {
        stop()
        val id = "capri-${++seq}"
        current = id
        this.onStart = onStart
        this.onDone = onDone
        CapriVolume.hold(ctx, volume)
        holdingVolume = true
        tts.setPitch(fx.pitch / 100f)
        tts.setSpeechRate(fx.rate / 100f)
        if (!fx.hasEffects) {
            currentFx = null
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        } else {
            currentFx = fx
            currentText = text
            if (tts.synthesizeToFile(text, Bundle(), file, id) != TextToSpeech.SUCCESS) plain(id)
        }
    }

    /** Efeito falhou: fala sem efeito mesmo, para não ficar muda. */
    private fun plain(id: String) {
        currentFx = null
        tts.speak(currentText, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    /** Corta a fala no meio, sem chamar o onDone. */
    fun stop() {
        current = null
        player?.stop()
        player = null
        try { tts.stop() } catch (_: Exception) {}
        releaseVolume()
    }

    private fun releaseVolume() {
        if (!holdingVolume) return
        holdingVolume = false
        CapriVolume.release(ctx)
    }

    private fun play(id: String) {
        val fx = currentFx ?: return
        Thread {
            val out = try {
                val pcm = Pcm.read(file)
                Pcm(RoboEffects.process(pcm.samples, pcm.rate, fx), pcm.rate)
            } catch (e: Exception) {
                CapriBus.debug("⚠️ Efeito de voz falhou: ${e.message}")
                null
            }
            main.post {
                if (id != current) return@post
                if (out == null) { plain(id); return@post }
                onStart?.invoke()
                player = RoboPlayer(out) { main.post { finish(id) } }.also { it.start() }
            }
        }.start()
    }

    private fun finish(id: String?) {
        if (id == null || id != current) return
        current = null
        player = null
        releaseVolume()
        val cb = onDone
        onDone = null
        cb?.invoke()
    }
}
