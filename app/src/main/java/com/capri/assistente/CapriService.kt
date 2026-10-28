package com.capri.assistente

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.media.AudioManager
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
    private var speaker: RoboSpeaker? = null // fala com o tom e os efeitos da voz robótica
    private var appliedVoice: String? = null // voz já aplicada no tts (null = nenhuma ainda)
    private var pendingCall: WhatsApp.Contact? = null // liga quando terminar de falar
    private var pendingChoice: List<WhatsApp.Contact>? = null // "qual deles?" aguardando resposta
    private lateinit var todos: TodoStore
    private var pendingTodo: List<String>? = null // "qual delas?" da lista de tarefas
    private var pendingClear = false // "tem certeza?" antes de limpar a lista
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
        todos = TodoStore(this)
        CapriBus.init(this)
        createChannel()
        promote(buildNotification("Carregando..."))
        CapriBus.running = true
        CapriBus.debug("ℹ️ Serviço iniciado. ${processInfo()}")

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "capri:escuta")
            .apply { setReferenceCounted(false); acquire() }

        tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 80) } catch (e: Exception) { null }

        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.let { t ->
                    t.setLanguage(Locale("pt", "BR"))
                    speaker = RoboSpeaker(this, t)
                }
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
                    if (backgroundRestricted(this)) {
                        CapriBus.log("⚠️ O Android está restringindo a Capri em segundo plano: com o app fechado ela para de ouvir. Veja em Configurações → Permissões.")
                    }
                    val battery = (getSystemService(POWER_SERVICE) as PowerManager)
                        .isIgnoringBatteryOptimizations(packageName)
                    CapriBus.debug("ℹ️ Bateria: ${if (battery) "sem restrições" else "otimizada"}")
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
        Timing.reset()
        Timing.mark("inicio")
        stopVosk()
        if (interrupted) stopSpeaking()
        CapriBus.log(if (interrupted) "✋ Interrompida!" else "✨ Ativada!")
        setStatus("Pode falar...")
        showBar()
        val phrase = pickGreeting()
        val s = speaker
        val t = tts
        if (phrase == null || s == null || t == null || !ttsReady) {
            beep(ToneGenerator.TONE_PROP_BEEP)
            main.postDelayed({ if (mode == Mode.ASKING) startAndroidRecognizer() }, 300)
            return
        }
        // "Fala aí!" no lugar do bipe; só ouve depois, para não ouvir a própria voz
        CapriBus.debug("👋 $phrase")
        useChosenVoice(t)
        s.speak(phrase, prefs.robo, prefs.volume, onDone = {
            main.postDelayed({ if (mode == Mode.ASKING) startAndroidRecognizer() }, 150)
        })
    }

    private var lastGreeting = ""

    /** Frase aleatória da lista (sem repetir a anterior); null = usar o bipe. */
    private fun pickGreeting(): String? {
        if (!prefs.greetOn) return null
        val all = prefs.greetList()
        if (all.isEmpty()) return null
        return all.filter { it != lastGreeting }.ifEmpty { all }.random().also { lastGreeting = it }
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
            override fun onReadyForSpeech(params: Bundle?) { Timing.mark("micPronto") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { Timing.mark("fimFala") }
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
                        CapriBus.debug("ℹ️ Reconhecedor do Google falhou (erro $error). Usando o Vosk para a pergunta.")
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
        Timing.reset()
        Timing.mark("inicio")
        setStatus("Pode falar...")
        main.postDelayed({ if (mode == Mode.ASKING) startAndroidRecognizer() }, 250)
    }

    private fun noQuestion() {
        pendingChoice = null
        pendingTodo = null
        pendingClear = false
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
        Timing.mark("pergunta")
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
        // lista de tarefas: "qual delas?" e "tem certeza?" aguardando resposta
        pendingTodo?.let { options ->
            pendingTodo = null
            todoReply(todos.removeChoice(q, options))
            return
        }
        if (pendingClear) {
            pendingClear = false
            todoReply(todos.confirmClear(q))
            return
        }

        // pedidos simples (lista, lanterna, volume, hora, timer) nem passam pelo Gemini
        TodoParser.parse(q, todos)?.let { runCommand(it); return }
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
        if (c is Command.Todo) {
            CapriBus.debug("📝 Lista: ${c.javaClass.simpleName}")
            todoReply(todos.run(c))
            return
        }
        // timer/alarme abrem o app de relógio: com o app fechado precisa de "aparecer sobre outros apps"
        if (c.opensApp && !Settings.canDrawOverlays(this) && !CapriBus.uiVisible) {
            speak("Para isso com o aplicativo fechado, preciso da permissão de aparecer sobre outros apps. Ativa nas configurações da Capri.")
            return
        }
        CapriBus.debug("⚡ Comando: ${c.javaClass.simpleName}")
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
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            speak("Preciso da permissão de fazer ligações para abrir a chamada no WhatsApp. Libera ela nas permissões do aplicativo.")
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
        } catch (e: SecurityException) {
            CapriBus.log("❌ Não consegui abrir o WhatsApp: ${e.message}")
            speak("Preciso da permissão de fazer ligações para abrir a chamada no WhatsApp. Libera ela nas permissões do aplicativo.")
        } catch (e: Exception) {
            CapriBus.log("❌ Não consegui abrir o WhatsApp: ${e.message}")
            listenForWake()
        }
    }

    private fun listenForChoice() {
        stopVosk()
        mode = Mode.ASKING
        Timing.reset()
        Timing.mark("inicio")
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
            checkForeground()
            checkMic()
            main.postDelayed(this, 2000)
        }
    }

    private fun promote(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    /** O Android pode tirar o serviço do primeiro plano; sem isso ele silencia o microfone. */
    private var fgLost = false // já registrou (e tentou corrigir) esta queda

    private fun checkForeground() {
        if (Build.VERSION.SDK_INT < 29) return
        if (foregroundServiceType != 0) { fgLost = false; return }
        if (fgLost) return
        fgLost = true
        CapriBus.debug("⚠️ O Android tirou a Capri do primeiro plano. ${processInfo()}")
        try {
            promote(buildNotification(CapriBus.status))
            CapriBus.debug("✅ Voltou ao primeiro plano. ${processInfo()}")
        } catch (e: Exception) {
            CapriBus.debug("❌ Não deu para voltar ao primeiro plano: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private var micState = "ok" // último estado do microfone registrado no histórico

    /**
     * Diagnóstico: com o app fechado, o Android pode silenciar o microfone ou parar a gravação
     * sem avisar. Registra no histórico quando isso acontece e quando volta.
     */
    private fun checkMic() {
        if (mode != Mode.WAKE || speechService == null) return
        val mine = (getSystemService(AUDIO_SERVICE) as AudioManager).activeRecordingConfigurations
        val state = when {
            mine.isEmpty() -> "parou"
            Build.VERSION.SDK_INT >= 29 && mine.all { it.isClientSilenced } -> "silenciado"
            else -> "ok"
        }
        if (state == micState) return
        micState = state
        val where = if (CapriBus.uiVisible) "app aberto" else "app fechado"
        when (state) {
            "silenciado" -> CapriBus.log("🔇 O Android silenciou o microfone ($where)." +
                if (CapriBus.debugLogs) " ${processInfo()}" else "")
            "parou" -> {
                CapriBus.log("🔇 A gravação do microfone parou sozinha ($where). Reabrindo...")
                listenForWake()
            }
            else -> CapriBus.log("🎙️ Microfone voltou ($where).")
        }
    }

    // ---------- 4. Falar ----------

    private fun speak(text: String) {
        Timing.mark("resposta")
        mode = Mode.SPEAKING
        CapriBus.log("🤖 Capri: $text")
        val clean = text.replace(Regex("[*#_`>|]"), " ").take(3900)
        speakingNorm = normalize(clean)
        // interromper: enquanto fala, o Vosk continua ouvindo só a palavra de ativação
        if (prefs.bargeIn) { stopVosk(); startVosk(question = false) }
        setStatus("Falando...")
        val t = tts
        val s = speaker
        if (!ttsReady || t == null || s == null) { afterSpeaking(); return }
        useChosenVoice(t)
        s.speak(clean, prefs.robo, prefs.volume,
            onStart = {
                Timing.mark("voz")
                Timing.summary()?.let { CapriBus.debug("⏱️ $it") }
            },
            onDone = { afterSpeaking() })
    }

    /** Aplica a voz das configurações (só quando muda, já que dá para trocar com a Capri ligada). */
    private fun useChosenVoice(t: TextToSpeech) {
        val name = prefs.voice
        if (name == appliedVoice) return
        appliedVoice = name
        if (!PhoneVoices.apply(t, name)) {
            CapriBus.log("⚠️ A voz escolhida não está mais no celular. Usando a voz padrão.")
        }
    }

    private fun afterSpeaking() {
        if (mode != Mode.SPEAKING || destroyed) return
        pendingCall?.let { c -> pendingCall = null; launchCall(c); return }
        if (pendingChoice != null || pendingTodo != null || pendingClear) { listenForChoice(); return }
        if (prefs.conversationMode) listenForFollowUp() else listenForWake()
    }

    /** Fala a resposta da lista e guarda a pergunta pendente ("qual delas?", "tem certeza?"). */
    private fun todoReply(r: TodoReply) {
        pendingTodo = r.choices
        pendingClear = r.confirmClear
        speak(r.text)
    }

    /** Corta a fala no meio (quando dizem "Capri" enquanto ela fala). */
    private fun stopSpeaking() {
        pendingCall = null // "Ligando para..." interrompido = ligação cancelada
        pendingChoice = null
        pendingTodo = null
        pendingClear = false // interrompeu o "tem certeza?": não apaga nada
        speaker?.stop()
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

    /** O bipe também usa o volume da Capri, senão some com o celular no mudo. */
    private fun beep(type: Int) {
        CapriVolume.hold(this, prefs.volume)
        try { tone?.startTone(type, 150) } catch (_: Exception) {}
        main.postDelayed({ CapriVolume.release(this) }, 300)
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
        // MIN: sem ícone na barra de status, fica recolhida no fim da lista (o Android exige a notificação para o microfone)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.deleteNotificationChannel("capri") // canal antigo (LOW); importância de canal existente não muda
        val ch = NotificationChannel(CHANNEL, "Capri em segundo plano", NotificationManager.IMPORTANCE_MIN)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
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
            .setShowWhen(false)
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(null as Icon?, "Desligar", stopPi).build())
            .build()
    }

    /** Como o Android está tratando o processo agora (125 = serviço em primeiro plano; 128 = tipo microfone). */
    private fun processInfo(): String {
        val p = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(p)
        val type = if (Build.VERSION.SDK_INT >= 29) foregroundServiceType.toString() else "?"
        val notif = (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .activeNotifications.any { it.id == NOTIF_ID }
        val restricted = backgroundRestricted(this)
        // 10 ativo, 20 uso frequente, 30 frequente, 40 raro, 45 restrito
        val bucket = if (Build.VERSION.SDK_INT >= 28)
            (getSystemService(USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager).appStandbyBucket.toString() else "?"
        return "[imp=${p.importance} tipo=$type notif=${if (notif) "sim" else "não"} " +
            "restrito=${if (restricted) "sim" else "não"} balde=$bucket android=${Build.VERSION.SDK_INT}]"
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        CapriBus.debug("ℹ️ App removido dos recentes; a Capri continua ligada.")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        destroyed = true
        CapriBus.running = false
        main.removeCallbacksAndMessages(null)
        stopVosk()
        destroyAndroidRecognizer()
        hideBar()
        speaker?.stop()
        CapriVolume.releaseAll(this) // bipe pendente (o removeCallbacks cancelou a devolução)
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        try { model?.close() } catch (_: Exception) {}
        try { tone?.release() } catch (_: Exception) {}
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        CapriBus.status("Desligada")
        super.onDestroy()
    }

    companion object {
        /** Restrita em segundo plano: o Android rebaixa o serviço ao sair do app e ignora o startForeground. */
        fun backgroundRestricted(ctx: android.content.Context): Boolean =
            Build.VERSION.SDK_INT >= 28 &&
                ctx.getSystemService(android.app.ActivityManager::class.java).isBackgroundRestricted

        const val ACTION_STOP = "com.capri.assistente.STOP"
        private const val CHANNEL = "capri_min"
        private const val NOTIF_ID = 1
    }
}
