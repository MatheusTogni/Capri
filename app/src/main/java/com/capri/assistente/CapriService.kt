package com.capri.assistente

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
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
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import com.capri.assistente.assist.Recognizers
import java.util.Locale

/**
 * A Capri só funciona chamada pelo botão de ligar (escolhida em "App de assistente digital"): não há escuta
 * contínua nem palavra de ativação, e o microfone só abre durante a conversa. Uma conversa:
 *  1. A sessão do assistente ([com.capri.assistente.assist.CapriSession]) acorda este serviço ([ACTION_ASSIST]).
 *  2. A saudação ("Fala aí!") ou um bipe, e o reconhecedor do Android (Google) ouve a pergunta.
 *  3. A pergunta vai para o Groq (se o modelo principal recusar, responde o reserva).
 *  4. A resposta é lida em voz alta. No modo conversa ela ouve de novo; senão, ou depois de um silêncio,
 *     o serviço desliga. Se o usuário se despede ("valeu Capri", "tchau"), ela só responde com uma frase da lista
 *     de despedidas ([Goodbye]) e desliga, sem passar pela IA; em silêncio, desliga sem falar nada.
 */
class CapriService : Service() {

    private enum class Mode { LOADING, ASKING, THINKING, SPEAKING }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs

    private var initialized = false
    private var destroyed = false
    private var mode = Mode.LOADING
    private var assistPending = false // esperando a voz (TTS) ficar pronta para começar a conversa
    private var turn = 0 // muda quando a conversa é fechada: a resposta da IA que ainda vem a caminho é descartada

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
    private var pendingShutdown = false // encerra a conversa quando terminar de falar
    private var followUp = false // ouvindo logo depois de responder (modo conversa)
    private var brain: Brain? = null
    private var brainKeys = "" // chaves/modelos com que o brain foi criado
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
            assistPending = true
            init()
        } else {
            assistNow()
        }
        return START_NOT_STICKY
    }

    /** Botão de ligar apertado de novo no meio da conversa: se ela está falando, interrompe e ouve. */
    private fun assistNow() {
        if (destroyed) return
        // ouvindo ou pensando: nada a fazer
        if (mode == Mode.SPEAKING) startConversation()
    }

    /** Começa a conversa, assim que a voz está pronta (ou depois de um tempo, sem ela). */
    private fun beginAssist() {
        if (!assistPending || destroyed) return
        assistPending = false
        startConversation()
    }

    /** Conversa terminou: fecha a barra e desliga, para o microfone não ficar aberto. */
    private fun endConversation() {
        hideBar()
        CapriBus.debug("ℹ️ Conversa terminada; microfone desligado.")
        stopSelf()
    }

    /** O usuário fechou a janela do assistente no meio da conversa: para de ouvir e de falar. */
    private fun cancelInteraction() {
        if (destroyed || !CapriBus.barVisible) return
        turn++ // a resposta da IA que ainda esteja a caminho não é mais falada
        stopSpeaking()
        destroyAndroidRecognizer()
        followUp = false
        CapriBus.log("✋ Fechada.")
        endConversation()
    }

    private fun init() {
        prefs = Prefs(this)
        todos = TodoStore(this)
        CapriBus.init(this)
        createChannel()
        promote(buildNotification("Carregando..."))
        CapriBus.running = true
        CapriBus.onAssistCancel = { cancelInteraction() }
        CapriBus.debug("ℹ️ Conversa iniciada pelo botão de ligar. ${processInfo()}")

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "capri:conversa")
            .apply { setReferenceCounted(false); acquire(WAKE_LOCK_MS) }

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
            beginAssist()
        }
        // se a voz demorar demais, começa mesmo assim (com o bipe no lugar da saudação)
        main.postDelayed({ beginAssist() }, 2500)
    }

    // ---------- 1. Ouvir a pergunta ----------

    private fun startConversation() {
        val interrupted = mode == Mode.SPEAKING
        mode = Mode.ASKING
        followUp = false
        Timing.reset()
        Timing.mark("inicio")
        if (interrupted) stopSpeaking()
        CapriBus.log(if (interrupted) "✋ Interrompida!" else "🔘 Chamada pelo botão de ligar")
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
        // Ao escolher a Capri como assistente, o Android pode trocar o reconhecedor padrão do celular pelo dela
        // (que só repassa para o do Google): aqui vai direto no do Google, sem a volta.
        val direct = Recognizers.forCapri(this)
        if (direct == null && !SpeechRecognizer.isRecognitionAvailable(this)) {
            cannotListen("Este celular não tem um reconhecedor de voz disponível.",
                "Este celular não tem um reconhecedor de voz disponível.")
            return
        }
        val sr = if (direct != null) SpeechRecognizer.createSpeechRecognizer(this, direct)
        else SpeechRecognizer.createSpeechRecognizer(this)
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
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> cannotListen(
                        "Sem permissão do microfone (erro $error).",
                        "Preciso da permissão do microfone. Libera ela nas configurações da Capri.")
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> cannotListen(
                        "Sem internet para o reconhecedor de voz (erro $error).",
                        "Não consegui entender sua voz porque estou sem internet.")
                    else -> cannotListen(
                        "O reconhecedor de voz falhou (erro $error).",
                        "Não consegui te ouvir agora. Tenta de novo.")
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
            cannotListen("Não consegui abrir o reconhecedor de voz: ${e.message}",
                "Não consegui te ouvir agora. Tenta de novo.")
        }
    }

    /** Não deu para ouvir: avisa e encerra (sem ouvir de novo, para não ficar num laço de erro). */
    private fun cannotListen(log: String, spoken: String) {
        CapriBus.log("⚠️ $log")
        pendingShutdown = true
        speak(spoken)
    }

    private fun destroyAndroidRecognizer() {
        androidRecognizer?.let { try { it.destroy() } catch (_: Exception) {} }
        androidRecognizer = null
    }

    /** Modo conversa: depois de responder, ouve mais uma vez sem precisar apertar o botão de novo. */
    private fun listenForFollowUp() {
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
            // silêncio depois da resposta: a conversa acabou, sem alarde
            followUp = false
            endConversation()
            return
        }
        CapriBus.log("🤷 Não ouvi nenhuma pergunta.")
        beep(ToneGenerator.TONE_PROP_NACK)
        main.postDelayed({ endConversation() }, 400)
    }

    // ---------- 2. IA (Groq) ----------

    private fun handleQuestion(q: String) {
        Timing.mark("pergunta")
        mode = Mode.THINKING
        followUp = false
        CapriBus.log("🗣️ Você: $q")

        // despedida ("valeu Capri", "tchau"): encerra sem passar pela IA, até no meio de um "qual deles?"
        if (Goodbye.matches(q)) { sayGoodbye(); return }
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

        // pedidos simples (lista, lanterna, volume, hora, timer) nem passam pela IA
        TodoParser.parse(q, todos)?.let { runCommand(it); return }
        LocalCommands.parse(q)?.let { runCommand(it); return }

        val groqKey = prefs.groqKey
        if (groqKey.isBlank()) {
            speak("Você ainda não colocou a chave do Groq no aplicativo.")
            return
        }
        val models = listOf(prefs.groqModel, prefs.groqBackup)
        val keys = "$groqKey|${models.joinToString(",")}"
        val b = brain?.takeIf { brainKeys == keys } ?: Brain(listOf<AiProvider>(Groq(groqKey, models)))
        brain = b
        brainKeys = keys

        val myTurn = turn
        Thread {
            // o motivo de cada falha já foi para o histórico (Brain)
            val reply = try {
                b.ask(q)
            } catch (e: Exception) {
                Brain.Reply.Text(HttpErrors.spoken(e))
            }
            // conversa fechada enquanto a IA pensava (turn mudou): descarta a resposta
            when (reply) {
                is Brain.Reply.Text -> main.post { if (!destroyed && myTurn == turn) speak(reply.text) }
                is Brain.Reply.Action -> main.post { if (!destroyed && myTurn == turn) runCommand(reply.command) }
                is Brain.Reply.WhatsAppCall -> {
                    // null = sem permissão de contatos
                    val found = try { WhatsApp.find(this, reply.contact, reply.video) } catch (_: SecurityException) { null }
                    main.post { if (!destroyed && myTurn == turn) onCallRequest(reply, found) }
                }
            }
        }.start()
    }

    /** O usuário se despediu: fala uma frase da lista e encerra (sem frase na lista, encerra calada). */
    private fun sayGoodbye() {
        // o que estava pendente ("qual deles?", "tem certeza?") é cancelado, nada é feito
        pendingChoice = null
        pendingTodo = null
        pendingClear = false
        val phrase = pickFarewell()
        if (phrase == null) {
            CapriBus.log("👋 Conversa encerrada pela despedida")
            endConversation()
            return
        }
        pendingShutdown = true
        speak(phrase)
    }

    private var lastFarewell = ""

    /** Frase aleatória da lista de despedidas (sem repetir a anterior); null = sem frases. */
    private fun pickFarewell(): String? {
        val all = prefs.farewellList()
        if (all.isEmpty()) return null
        return all.filter { it != lastFarewell }.ifEmpty { all }.random().also { lastFarewell = it }
    }

    // ---------- 2a. Comandos no celular ----------

    private fun runCommand(c: Command) {
        if (c is Command.Todo) {
            CapriBus.debug("📝 Lista: ${c.javaClass.simpleName}")
            todoReply(todos.run(c))
            return
        }
        if (c == Command.Shutdown) {
            CapriBus.log("⏻ Conversa encerrada por voz")
            pendingShutdown = true
            speak("Tá bom, vou parar de ouvir.")
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

    // ---------- 2b. Ligação pelo WhatsApp ----------

    private fun onCallRequest(req: Brain.Reply.WhatsAppCall, found: List<WhatsApp.Contact>?) {
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
        hideBar()
        try {
            WhatsApp.call(this, c)
            CapriBus.log("📞 ${if (c.video) "Vídeo" else "Ligação"} pelo WhatsApp: ${c.name}")
            endConversation() // a ligação segue no WhatsApp: não há o que retomar
        } catch (e: SecurityException) {
            CapriBus.log("❌ Não consegui abrir o WhatsApp: ${e.message}")
            pendingShutdown = true
            speak("Preciso da permissão de fazer ligações para abrir a chamada no WhatsApp. Libera ela nas permissões do aplicativo.")
        } catch (e: Exception) {
            CapriBus.log("❌ Não consegui abrir o WhatsApp: ${e.message}")
            endConversation()
        }
    }

    private fun listenForChoice() {
        mode = Mode.ASKING
        Timing.reset()
        Timing.mark("inicio")
        beep(ToneGenerator.TONE_PROP_BEEP)
        setStatus("Pode falar...")
        main.postDelayed({ if (mode == Mode.ASKING) startAndroidRecognizer() }, 300)
    }

    private fun promote(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ---------- 3. Falar ----------

    private fun speak(text: String) {
        Timing.mark("resposta")
        mode = Mode.SPEAKING
        CapriBus.log("🤖 Capri: $text")
        val clean = text.replace(Regex("[*#_`>|]"), " ").take(3900)
        // o microfone fica fechado enquanto ela fala; apertar o botão de novo interrompe
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
        if (pendingShutdown) { endConversation(); return }
        pendingCall?.let { c -> pendingCall = null; launchCall(c); return }
        if (pendingChoice != null || pendingTodo != null || pendingClear) { listenForChoice(); return }
        if (prefs.conversationMode) listenForFollowUp() else endConversation()
    }

    /** Fala a resposta da lista e guarda a pergunta pendente ("qual delas?", "tem certeza?"). */
    private fun todoReply(r: TodoReply) {
        pendingTodo = r.choices
        pendingClear = r.confirmClear
        speak(r.text)
    }

    /** Corta a fala no meio (quando apertam o botão de ligar de novo enquanto ela fala). */
    private fun stopSpeaking() {
        pendingCall = null // "Ligando para..." interrompido = ligação cancelada
        pendingChoice = null
        pendingTodo = null
        pendingClear = false // interrompeu o "tem certeza?": não apaga nada
        pendingShutdown = false // chamou de novo enquanto se despedia: a conversa continua
        speaker?.stop()
    }

    // ---------- barrinha "ouvindo" ----------

    /** A barrinha é desenhada pela janela do assistente ([com.capri.assistente.assist.CapriSession]); aqui só se avisa que ela deve aparecer. */
    private fun showBar() = CapriBus.bar(true)

    private fun hideBar() {
        if (CapriBus.barVisible) CapriBus.bar(false)
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
        if (!destroyed) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotification(s))
        }
    }

    private fun createChannel() {
        // MIN: sem ícone na barra de status, fica recolhida no fim da lista (o Android exige a notificação para o microfone)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.deleteNotificationChannel("capri") // canal antigo (LOW); importância de canal existente não muda
        val ch = NotificationChannel(CHANNEL, "Capri em conversa", NotificationManager.IMPORTANCE_MIN)
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
            .addAction(Notification.Action.Builder(null as Icon?, "Encerrar", stopPi).build())
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

    override fun onDestroy() {
        destroyed = true
        CapriBus.running = false
        CapriBus.onAssistCancel = null
        main.removeCallbacksAndMessages(null)
        destroyAndroidRecognizer()
        hideBar()
        speaker?.stop()
        CapriVolume.releaseAll(this) // bipe pendente (o removeCallbacks cancelou a devolução)
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        try { tone?.release() } catch (_: Exception) {}
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        CapriBus.status(CapriBus.IDLE)
        super.onDestroy()
    }

    companion object {
        /** Restrita em segundo plano: o Android rebaixa o serviço ao sair do app e ignora o startForeground. */
        fun backgroundRestricted(ctx: android.content.Context): Boolean =
            Build.VERSION.SDK_INT >= 28 &&
                ctx.getSystemService(android.app.ActivityManager::class.java).isBackgroundRestricted

        const val ACTION_STOP = "com.capri.assistente.STOP"
        /** Enviada pela sessão do assistente quando o botão de ligar é segurado. */
        const val ACTION_ASSIST = "com.capri.assistente.ASSIST"
        private const val CHANNEL = "capri_min"
        private const val NOTIF_ID = 1
        private const val WAKE_LOCK_MS = 15 * 60 * 1000L // segurança: uma conversa não passa disso
    }
}
