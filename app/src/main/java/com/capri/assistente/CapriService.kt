package com.capri.assistente

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import java.util.Locale

/**
 * Fluxo:
 *  1. Vosk fica ouvindo (offline) até reconhecer a palavra de ativação.
 *  2. Bipe -> o reconhecedor do Android (Google) ouve a pergunta.
 *     Se ele falhar em segundo plano, o próprio Vosk ouve a pergunta.
 *  3. A pergunta vai para o Gemini.
 *  4. A resposta é lida em voz alta e volta para o passo 1.
 */
class CapriService : Service() {

    private enum class Mode { LOADING, WAKE, ASKING, THINKING, SPEAKING }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs

    private var initialized = false
    private var destroyed = false
    private var mode = Mode.LOADING

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var voskRecognizer: Recognizer? = null
    private var session = 0 // invalida eventos atrasados do Vosk

    private var androidRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var gemini: Gemini? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var tone: ToneGenerator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!initialized) {
            initialized = true
            init()
        }
        return START_NOT_STICKY
    }

    private fun init() {
        prefs = Prefs(this)
        createChannel()
        val n = buildNotification("Carregando...")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        CapriBus.running = true

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "capri:escuta")
            .apply { setReferenceCounted(false); acquire() }

        tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 80) } catch (e: Exception) { null }

        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale("pt", "BR"))
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { main.post { afterSpeaking() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { main.post { afterSpeaking() } }
                })
                ttsReady = true
            } else {
                CapriBus.log("⚠️ Não consegui iniciar a voz (TTS) do celular.")
            }
        }

        setStatus("Preparando o modelo de voz (a 1ª vez demora)...")
        Thread {
            try {
                val dir = ModelInstaller.ensure(this)
                val m = Model(dir.absolutePath)
                main.post {
                    if (destroyed) { m.close(); return@post }
                    model = m
                    CapriBus.log("✅ Capri pronta.")
                    listenForWake()
                }
            } catch (e: Throwable) {
                CapriBus.log("❌ Erro ao carregar o modelo: ${e.message}")
                main.post { stopSelf() }
            }
        }.start()
    }

    // ---------- 1. Palavra de ativação (Vosk) ----------

    private fun listenForWake() {
        if (destroyed) return
        stopVosk()
        mode = Mode.WAKE
        val first = prefs.wakeList().firstOrNull() ?: "capri"
        if (!startVosk(question = false)) return
        setStatus("Ouvindo... diga \"$first\"")
    }

    /** Retorna false se não conseguiu abrir o microfone (e agenda nova tentativa). */
    private fun startVosk(question: Boolean): Boolean {
        val m = model ?: return false
        session++
        val id = session
        return try {
            val rec = Recognizer(m, 16000f)
            voskRecognizer = rec
            val svc = SpeechService(rec, 16000f)
            speechService = svc
            val listener = VoskListener(id, question)
            if (question) svc.startListening(listener, 10_000) else svc.startListening(listener)
            true
        } catch (e: Exception) {
            CapriBus.log("⚠️ Microfone indisponível: ${e.message}. Tentando de novo...")
            stopVosk()
            if (question) {
                main.postDelayed({ noQuestion() }, 500)
            } else {
                main.postDelayed({ if (mode == Mode.WAKE || mode == Mode.LOADING) listenForWake() }, 3000)
            }
            false
        }
    }

    private fun stopVosk() {
        session++
        speechService?.let {
            try { it.cancel() } catch (_: Exception) {}
            try { it.shutdown() } catch (_: Exception) {}
        }
        speechService = null
        voskRecognizer?.let { try { it.close() } catch (_: Exception) {} }
        voskRecognizer = null
    }

    private fun containsWake(text: String): Boolean {
        val t = " " + normalize(text) + " "
        if (t.isBlank()) return false
        return prefs.wakeList().any { t.contains(" $it ") }
    }

    private inner class VoskListener(val id: Int, val question: Boolean) :
        org.vosk.android.RecognitionListener {

        private var handled = false
        private fun valid() = id == session && !handled && !destroyed

        override fun onPartialResult(hypothesis: String?) {
            if (!valid()) return
            val text = parse(hypothesis, "partial")
            if (text.isBlank()) return
            CapriBus.heard(text)
            if (!question && containsWake(text)) { handled = true; onWake() }
        }

        override fun onResult(hypothesis: String?) {
            if (!valid()) return
            val text = parse(hypothesis, "text")
            if (text.isBlank()) return
            CapriBus.heard(text)
            if (question) {
                handled = true
                stopVosk()
                handleQuestion(text)
            } else if (containsWake(text)) {
                handled = true
                onWake()
            }
        }

        override fun onFinalResult(hypothesis: String?) {
            if (!valid() || !question) return
            handled = true
            val text = parse(hypothesis, "text")
            stopVosk()
            if (text.isBlank()) noQuestion() else handleQuestion(text)
        }

        override fun onTimeout() {
            if (!valid() || !question) return
            handled = true
            val text = try { parse(voskRecognizer?.finalResult, "text") } catch (_: Exception) { "" }
            stopVosk()
            if (text.isBlank()) noQuestion() else handleQuestion(text)
        }

        override fun onError(exception: Exception?) {
            if (!valid()) return
            handled = true
            CapriBus.log("⚠️ Erro no Vosk: ${exception?.message}")
            stopVosk()
            main.postDelayed({ listenForWake() }, 2000)
        }
    }

    private fun parse(json: String?, field: String): String =
        try { JSONObject(json ?: "{}").optString(field, "").trim() } catch (_: Exception) { "" }

    // ---------- 2. Ouvir a pergunta ----------

    private fun onWake() {
        mode = Mode.ASKING
        stopVosk()
        beep(ToneGenerator.TONE_PROP_BEEP)
        CapriBus.log("✨ Ativada!")
        setStatus("Pode falar...")
        main.postDelayed({ if (mode == Mode.ASKING) startAndroidRecognizer() }, 300)
    }

    private fun startAndroidRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            startVoskQuestion()
            return
        }
        val sr = SpeechRecognizer.createSpeechRecognizer(this)
        androidRecognizer = sr
        var done = false
        sr.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!p.isNullOrBlank()) CapriBus.heard(p)
            }

            override fun onResults(results: Bundle?) {
                if (done) return
                done = true
                val q = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                destroyAndroidRecognizer()
                if (q.isBlank()) noQuestion() else handleQuestion(q)
            }

            override fun onError(error: Int) {
                if (done) return
                done = true
                destroyAndroidRecognizer()
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> noQuestion()
                    else -> {
                        CapriBus.log("ℹ️ Reconhecedor do Google falhou (erro $error). Usando o Vosk para a pergunta.")
                        startVoskQuestion()
                    }
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            sr.startListening(intent)
        } catch (e: Exception) {
            done = true
            destroyAndroidRecognizer()
            startVoskQuestion()
        }
    }

    private fun destroyAndroidRecognizer() {
        androidRecognizer?.let { try { it.destroy() } catch (_: Exception) {} }
        androidRecognizer = null
    }

    private fun startVoskQuestion() {
        if (destroyed) return
        stopVosk()
        mode = Mode.ASKING
        setStatus("Pode falar... (Vosk)")
        startVosk(question = true)
    }

    private fun noQuestion() {
        CapriBus.log("🤷 Não ouvi nenhuma pergunta.")
        beep(ToneGenerator.TONE_PROP_NACK)
        main.postDelayed({ listenForWake() }, 400)
    }

    // ---------- 3. Gemini ----------

    private fun handleQuestion(q: String) {
        mode = Mode.THINKING
        CapriBus.log("🗣️ Você: $q")
        setStatus("Pensando...")

        val key = prefs.apiKey
        if (key.isBlank()) {
            speak("Você ainda não colocou a chave do Gemini no aplicativo.")
            return
        }
        val modelName = prefs.model
        val g = gemini?.takeIf { it.apiKey == key && it.model == modelName } ?: Gemini(key, modelName)
        gemini = g

        Thread {
            val answer = try {
                g.ask(q)
            } catch (e: Exception) {
                CapriBus.log("❌ ${e.message}")
                "Deu um erro ao falar com o Gemini. Dá uma olhada no aplicativo."
            }
            main.post { if (!destroyed) speak(answer) }
        }.start()
    }

    // ---------- 4. Falar ----------

    private fun speak(text: String) {
        mode = Mode.SPEAKING
        CapriBus.log("🤖 Capri: $text")
        setStatus("Falando...")
        val t = tts
        if (!ttsReady || t == null) { listenForWake(); return }
        val clean = text.replace(Regex("[*#_`>|]"), " ").take(3900)
        t.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "capri-${System.currentTimeMillis()}")
    }

    private fun afterSpeaking() {
        if (mode == Mode.SPEAKING && !destroyed) listenForWake()
    }

    // ---------- utilidades ----------

    private fun beep(type: Int) {
        try { tone?.startTone(type, 150) } catch (_: Exception) {}
    }

    private fun setStatus(s: String) {
        CapriBus.status(s)
        if (!destroyed) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotification(s))
        }
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "Capri ouvindo", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, CapriService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Capri")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setColor(Neon.RED)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(null as Icon?, "Desligar", stopPi).build())
            .build()
    }

    override fun onDestroy() {
        destroyed = true
        CapriBus.running = false
        main.removeCallbacksAndMessages(null)
        stopVosk()
        destroyAndroidRecognizer()
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        try { model?.close() } catch (_: Exception) {}
        try { tone?.release() } catch (_: Exception) {}
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        CapriBus.status("Desligada")
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.capri.assistente.STOP"
        private const val CHANNEL = "capri"
        private const val NOTIF_ID = 1
    }
}
