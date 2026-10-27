package com.capri.assistente

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.WindowManager
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

    private enum class Mode { LOADING, WAKE, ASKING, THINKING, SPEAKING, PAUSED }

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
    private var player: MediaPlayer? = null
    private var speakId = 0 // invalida áudios do Gemini que chegarem atrasados
    private var pendingCall: WhatsApp.Contact? = null // liga quando terminar de falar
    private var pendingChoice: List<WhatsApp.Contact>? = null // "qual deles?" aguardando resposta
    private var followUp = false // ouvindo sem palavra de ativação logo depois de responder (modo conversa)
    private var speakingNorm = "" // o que ela está falando, para não se interromper com a própria voz
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
                    main.postDelayed(callWatch, 2000)
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
        hideBar()
        if (inCall()) {
            mode = Mode.PAUSED // o callWatch retoma quando a chamada acabar
            setStatus("Pausada durante a chamada")
            return
        }
        mode = Mode.WAKE
        val first = prefs.wakeList().firstOrNull() ?: "capri"
        if (!startVosk(question = false)) return
        setStatus("Ouvindo... diga \"$first\"")
    }

    /** Retorna false se não conseguiu abrir o microfone (e agenda nova tentativa). */
    private fun startVosk(question: Boolean, timeoutMs: Int = 10_000): Boolean {
        val m = model ?: return false
        session++
        val id = session
        return try {
            val rec = if (question) Recognizer(m, 16000f) else Recognizer(m, 16000f, prefs.wakeGrammar())
            voskRecognizer = rec
            val svc = SpeechService(rec, 16000f)
            speechService = svc
            val listener = VoskListener(id, question)
            if (question) svc.startListening(listener, timeoutMs) else svc.startListening(listener)
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
        // enquanto ela fala, ignora palavras de ativação que estão na própria fala (ex.: "olá")
        val own = if (mode == Mode.SPEAKING) " $speakingNorm " else ""
        return prefs.wakeList().any { t.contains(" $it ") && !own.contains(" $it ") }
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

    // "[unk]" é o que a gramática da ativação devolve para qualquer coisa que não seja a palavra
    private fun parse(json: String?, field: String): String =
        try { JSONObject(json ?: "{}").optString(field, "").replace("[unk]", "").trim() } catch (_: Exception) { "" }

    // ---------- 2. Ouvir a pergunta ----------

    private fun onWake() {
        val interrupted = mode == Mode.SPEAKING
        mode = Mode.ASKING
        followUp = false
        stopVosk()
        if (interrupted) stopSpeaking()
        beep(ToneGenerator.TONE_PROP_BEEP)
        CapriBus.log(if (interrupted) "✋ Interrompida!" else "✨ Ativada!")
        setStatus("Pode falar...")
        showBar()
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
        startVosk(question = true, timeoutMs = if (followUp) 6_000 else 10_000)
    }

    /** Modo conversa: depois de responder, ouve mais uma vez sem precisar da palavra de ativação. */
    private fun listenForFollowUp() {
        stopVosk()
        mode = Mode.ASKING
        followUp = true
        setStatus("Pode falar...")
        main.postDelayed({ if (mode == Mode.ASKING) startAndroidRecognizer() }, 250)
    }

    private fun noQuestion() {
        pendingChoice = null
        if (followUp) {
            // silêncio depois da resposta: a conversa acabou, volta a esperar o "Capri" sem alarde
            followUp = false
            listenForWake()
            return
        }
        CapriBus.log("🤷 Não ouvi nenhuma pergunta.")
        beep(ToneGenerator.TONE_PROP_NACK)
        main.postDelayed({ listenForWake() }, 400)
    }

    // ---------- 3. Gemini ----------

    private fun handleQuestion(q: String) {
        mode = Mode.THINKING
        followUp = false
        CapriBus.log("🗣️ Você: $q")
        setStatus("Pensando...")

        // resposta para "qual deles?" de uma ligação
        pendingChoice?.let { options ->
            pendingChoice = null
            val c = WhatsApp.pick(q, options)
            if (c == null) speak("Não entendi qual deles. Pode pedir de novo.") else startCall(c)
            return
        }

        // pedidos simples (lanterna, volume, hora, timer) nem passam pelo Gemini
        LocalCommands.parse(q)?.let { runCommand(it); return }

        val key = prefs.apiKey
        if (key.isBlank()) {
            speak("Você ainda não colocou a chave do Gemini no aplicativo.")
            return
        }
        val modelName = prefs.model
        val g = gemini?.takeIf { it.apiKey == key && it.model == modelName } ?: Gemini(key, modelName)
        gemini = g

        Thread {
            val reply = try {
                g.ask(q)
            } catch (e: GeminiBusyException) {
                CapriBus.log("⏳ ${e.message}")
                Gemini.Reply.Text("O Gemini está sobrecarregado agora. Tenta de novo daqui a pouco.")
            } catch (e: Exception) {
                CapriBus.log("❌ ${e.message}")
                Gemini.Reply.Text("Deu um erro ao falar com o Gemini. Dá uma olhada no aplicativo.")
            }
            when (reply) {
                is Gemini.Reply.Text -> main.post { if (!destroyed) speak(reply.text) }
                is Gemini.Reply.Action -> main.post { if (!destroyed) runCommand(reply.command) }
                is Gemini.Reply.WhatsAppCall -> {
                    // null = sem permissão de contatos
                    val found = try { WhatsApp.find(this, reply.contact, reply.video) } catch (_: SecurityException) { null }
                    main.post { if (!destroyed) onCallRequest(reply, found) }
                }
            }
        }.start()
    }

    // ---------- 3a. Comandos no celular ----------

    private fun runCommand(c: Command) {
        // timer/alarme abrem o app de relógio: com o app fechado precisa de "aparecer sobre outros apps"
        if (c.opensApp && !Settings.canDrawOverlays(this) && !CapriBus.uiVisible) {
            speak("Para isso com o aplicativo fechado, preciso da permissão de aparecer sobre outros apps. Ativa nas configurações da Capri.")
            return
        }
        CapriBus.log("⚡ Comando: ${c.javaClass.simpleName}")
        val answer = CommandRunner(this).run(c)
        if (answer.isBlank()) {
            // ex.: volume no mudo — não tem o que falar
            mode = Mode.SPEAKING
            afterSpeaking()
        } else {
            speak(answer)
        }
    }

    // ---------- 3b. Ligação pelo WhatsApp ----------

    private fun onCallRequest(req: Gemini.Reply.WhatsAppCall, found: List<WhatsApp.Contact>?) {
        when {
            found == null -> speak("Preciso da permissão de contatos para ligar. Libera ela nas permissões do aplicativo.")
            found.isEmpty() -> speak("Não encontrei ${req.contact} com WhatsApp nos seus contatos.")
            found.size == 1 -> startCall(found[0])
            else -> {
                val options = found.take(4)
                pendingChoice = options
                val names = options.map { it.name }
                speak("Encontrei ${names.dropLast(1).joinToString(", ")} e ${names.last()}. Qual deles?")
            }
        }
    }

    private fun startCall(c: WhatsApp.Contact) {
        // com o app fechado, o Android só deixa abrir o WhatsApp com "aparecer sobre outros apps"
        if (!Settings.canDrawOverlays(this) && !CapriBus.uiVisible) {
            speak("Para eu ligar com o aplicativo fechado, preciso da permissão de aparecer sobre outros apps. Ativa nas configurações da Capri.")
            return
        }
        pendingCall = c
        speak(if (c.video) "Chamada de vídeo com ${c.name}." else "Ligando para ${c.name} no WhatsApp.")
    }

    private fun launchCall(c: WhatsApp.Contact) {
        stopVosk()
        hideBar()
        try {
            WhatsApp.call(this, c)
            CapriBus.log("📞 ${if (c.video) "Vídeo" else "Ligação"} pelo WhatsApp: ${c.name}")
            // o callWatch volta a ouvir quando a chamada acabar (ou se ela não começar)
            mode = Mode.PAUSED
            setStatus("Abrindo o WhatsApp...")
        } catch (e: Exception) {
            CapriBus.log("❌ Não consegui abrir o WhatsApp: ${e.message}")
            listenForWake()
        }
    }

    private fun listenForChoice() {
        stopVosk()
        mode = Mode.ASKING
        beep(ToneGenerator.TONE_PROP_BEEP)
        setStatus("Pode falar...")
        main.postDelayed({ if (mode == Mode.ASKING) startAndroidRecognizer() }, 300)
    }

    private fun inCall(): Boolean {
        val m = (getSystemService(AUDIO_SERVICE) as AudioManager).mode
        return m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION
    }

    /** Pausa a escuta durante qualquer chamada, para não disputar o microfone nem ouvir a conversa. */
    private val callWatch = object : Runnable {
        override fun run() {
            if (destroyed) return
            val busy = inCall()
            if (busy && mode == Mode.WAKE) {
                stopVosk()
                mode = Mode.PAUSED
                setStatus("Pausada durante a chamada")
            } else if (!busy && mode == Mode.PAUSED) {
                listenForWake()
            }
            main.postDelayed(this, 2000)
        }
    }

    // ---------- 4. Falar ----------

    private fun speak(text: String) {
        mode = Mode.SPEAKING
        CapriBus.log("🤖 Capri: $text")
        val clean = text.replace(Regex("[*#_`>|]"), " ").take(3900)
        val id = ++speakId
        speakingNorm = normalize(clean)
        // interromper: enquanto fala, o Vosk continua ouvindo só a palavra de ativação
        if (prefs.bargeIn) { stopVosk(); startVosk(question = false) }
        val key = prefs.apiKey
        val voiceName = prefs.voice
        val quotaOver = System.currentTimeMillis() < prefs.voiceBlockedUntil
        if (key.isBlank() || voiceName.isBlank() || quotaOver) { speakLocal(clean); return }

        setStatus("Pensando na voz...")
        Thread {
            val wav = try {
                GeminiVoice(key, voiceName).synth(clean)
            } catch (e: GeminiVoice.QuotaException) {
                val until = System.currentTimeMillis() + e.retryAfterMs
                prefs.voiceBlockedUntil = until
                val hora = java.text.SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(java.util.Date(until))
                CapriBus.log("⏳ Cota diária da voz do Gemini acabou. Usando a voz do celular até $hora.")
                null
            } catch (e: Exception) {
                CapriBus.log("⚠️ ${e.message} (usando a voz do celular)")
                null
            }
            main.post {
                if (destroyed || id != speakId || mode != Mode.SPEAKING) return@post
                if (wav == null || !playWav(wav, id)) speakLocal(clean)
            }
        }.start()
    }

    /** Toca o áudio do Gemini; retorna false se não conseguiu (aí cai na voz do celular). */
    private fun playWav(wav: ByteArray, id: Int): Boolean = try {
        releasePlayer()
        val f = java.io.File(cacheDir, "fala.wav")
        f.writeBytes(wav)
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setDataSource(f.absolutePath)
            setOnCompletionListener { if (id == speakId) { releasePlayer(); afterSpeaking() } }
            setOnErrorListener { _, what, _ ->
                if (id == speakId) { CapriBus.log("⚠️ Erro ao tocar a voz do Gemini ($what)"); releasePlayer(); afterSpeaking() }
                true
            }
            prepare()
            start()
        }
        setStatus("Falando...")
        true
    } catch (e: Exception) {
        CapriBus.log("⚠️ Não consegui tocar a voz do Gemini: ${e.message}")
        releasePlayer()
        false
    }

    private fun releasePlayer() {
        player?.let { try { it.release() } catch (_: Exception) {} }
        player = null
    }

    private fun speakLocal(text: String) {
        setStatus("Falando...")
        val t = tts
        if (!ttsReady || t == null) { afterSpeaking(); return }
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "capri-${System.currentTimeMillis()}")
    }

    private fun afterSpeaking() {
        if (mode != Mode.SPEAKING || destroyed) return
        pendingCall?.let { c -> pendingCall = null; launchCall(c); return }
        if (pendingChoice != null) { listenForChoice(); return }
        if (prefs.conversationMode) listenForFollowUp() else listenForWake()
    }

    /** Corta a fala no meio (quando dizem "Capri" enquanto ela fala). */
    private fun stopSpeaking() {
        speakId++ // descarta áudio do Gemini que ainda esteja chegando
        pendingCall = null // "Ligando para..." interrompido = ligação cancelada
        pendingChoice = null
        try { tts?.stop() } catch (_: Exception) {}
        releasePlayer()
    }

    // ---------- barrinha "ouvindo" ----------

    private var overlayBar: CapriBarView? = null

    /** Desbloqueado: janela flutuante. Bloqueado/tela apagada: BarActivity (único jeito de passar do bloqueio). */
    private fun showBar() {
        if (!Settings.canDrawOverlays(this)) return
        CapriBus.bar(true)
        val locked = (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        val screenOn = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        if (locked || !screenOn) {
            try {
                startActivity(Intent(this, BarActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            } catch (_: Exception) {}
            return
        }
        if (overlayBar != null) return
        val d = resources.displayMetrics.density
        val v = CapriBarView(this).apply { setStatus(CapriBus.status) }
        val lp = WindowManager.LayoutParams(
            resources.displayMetrics.widthPixels - (32 * d).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // não rouba foco nem toques do app que estiver aberto
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (48 * d).toInt()
        }
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(v, lp)
            overlayBar = v
        } catch (e: Exception) {
            CapriBus.log("⚠️ Não consegui mostrar a barra: ${e.message}")
        }
    }

    private fun hideBar() {
        if (CapriBus.barVisible) CapriBus.bar(false)
        overlayBar?.let { try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {} }
        overlayBar = null
    }

    // ---------- utilidades ----------

    private fun beep(type: Int) {
        try { tone?.startTone(type, 150) } catch (_: Exception) {}
    }

    private fun setStatus(s: String) {
        CapriBus.status(s)
        overlayBar?.setStatus(s)
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
        hideBar()
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        releasePlayer()
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
