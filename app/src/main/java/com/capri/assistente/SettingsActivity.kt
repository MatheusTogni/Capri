package com.capri.assistente

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** Tela de configurações, aberta pela engrenagem da tela principal. */
class SettingsActivity : NeonActivity() {

    private lateinit var prefs: Prefs
    private var tts: TextToSpeech? = null // só para listar e testar as vozes
    private var ttsReady = false
    private var voices: List<Voice> = emptyList()
    private var selectedVoice = ""
    private lateinit var voicePicker: TextView
    private var speaker: RoboSpeaker? = null
    private var fx = RoboFx.CAPRI // ajustes da voz robótica na tela (salvos no "Salvar configurações")
    private var volume = 70 // volume da Capri na tela (idem)
    private val fxRefresh = mutableListOf<() -> Unit>() // redesenha as barras depois do "Restaurar"
    private lateinit var permsHeader: Button
    private lateinit var permsBox: LinearLayout
    private var permsOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        applyWindowColors()

        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad * 2)
        }

        // ---- topo: título + voltar ----
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(section("Configurações").apply { setPadding(0, 0, 0, 0) },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(neonButton("✕ Fechar", Neon.MUTED).apply {
                textSize = 12f
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(42)))
        })

        root.addView(label("Chave da API do Gemini"))
        val keyInput = input().apply {
            setText(prefs.apiKey)
            hint = "Cole aqui a chave do AI Studio"
            isSingleLine = true
            // inputType/transformação depois do isSingleLine, senão a chave aparece em texto puro
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            typeface = Typeface.MONOSPACE
        }
        root.addView(keyInput, blockParams(dp(6)))

        root.addView(label("Modelo do Gemini"))
        val modelInput = input().apply { setText(prefs.model); isSingleLine = true }
        root.addView(modelInput, blockParams(dp(6)))

        root.addView(label("Chave do Groq (reserva, opcional)"))
        val groqInput = input().apply {
            setText(prefs.groqKey)
            hint = "Responde quando o Gemini recusar (console.groq.com)"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            typeface = Typeface.MONOSPACE
        }
        root.addView(groqInput, blockParams(dp(6)))

        root.addView(label("Palavras de ativação (separadas por vírgula)"))
        val wakeInput = input().apply { setText(prefs.wakeWords) }
        root.addView(wakeInput, blockParams(dp(6)))

        root.addView(label("Voz da Capri"))
        selectedVoice = prefs.voice
        voicePicker = TextView(this).apply {
            textSize = 15f
            setTextColor(Neon.TEXT)
            background = CutDrawable(Neon.PANEL, Neon.PURPLE, dp(1).toFloat(), dp(8).toFloat())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { pickVoice() }
        }
        val testButton = neonButton("▶ Testar", Neon.CYAN).apply {
            textSize = 12f
            setOnClickListener { testVoice() }
        }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(voicePicker, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(testButton, LinearLayout.LayoutParams(WRAP_CONTENT, dp(46)).apply { marginStart = dp(8) })
        }, blockParams(dp(6)))
        root.addView(neonButton("⬇ Baixar mais vozes", Neon.PURPLE).apply {
            textSize = 12f
            setOnClickListener { PhoneVoices.openDownload(this@SettingsActivity, tts?.defaultEngine) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)).apply { topMargin = dp(8) })
        refreshVoice()
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.let { speaker = RoboSpeaker(this, it) }
            }
            refreshVoice()
        }

        // ---- volume próprio + voz robótica: ajusta ouvindo no "Testar" ----
        volume = prefs.volume
        root.addView(slider("Volume da Capri (independente do celular)", 0..100, { volume }) { volume = it },
            blockParams(dp(12)))
        fx = prefs.robo
        root.addView(fxSlider("Tom", 50..200, { it.pitch }) { f, v -> f.copy(pitch = v) },
            blockParams(dp(12)))
        root.addView(fxSlider("Velocidade", 50..200, { it.rate }) { f, v -> f.copy(rate = v) },
            blockParams(dp(4)))
        root.addView(fxSlider("Metálico", 0..100, { it.metal }) { f, v -> f.copy(metal = v) },
            blockParams(dp(4)))
        root.addView(fxSlider("Digital", 0..100, { it.digital }) { f, v -> f.copy(digital = v) },
            blockParams(dp(4)))
        root.addView(fxSlider("Eco sintético", 0..100, { it.echo }) { f, v -> f.copy(echo = v) },
            blockParams(dp(4)))
        root.addView(neonButton("↺ Restaurar padrão Capri", Neon.MUTED).apply {
            textSize = 12f
            setOnClickListener { fx = RoboFx.CAPRI; fxRefresh.forEach { it() } }
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)).apply { topMargin = dp(8) })

        root.addView(label("Conversa"))
        var conversation = prefs.conversationMode
        root.addView(toggle("Modo conversa (ouvir de novo após responder)", conversation) { conversation = it },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply { topMargin = dp(6) })
        var bargeIn = prefs.bargeIn
        root.addView(toggle("Interromper dizendo a palavra de ativação", bargeIn) { bargeIn = it },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })
        var greetOn = prefs.greetOn
        root.addView(toggle("Responder ao ser chamada (no lugar do bipe)", greetOn) { greetOn = it },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })
        root.addView(label("Frases de resposta (uma por linha; sorteia uma a cada chamada)"))
        val greetInput = input().apply {
            setText(prefs.greetings)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = false
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
        }
        root.addView(greetInput, blockParams(dp(6)))
        root.addView(neonButton("↺ Frases padrão", Neon.MUTED).apply {
            textSize = 12f
            setOnClickListener { greetInput.setText(Prefs.DEFAULT_GREETINGS) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)).apply { topMargin = dp(8) })

        root.addView(label("Histórico"))
        var debugLogs = prefs.debugLogs
        root.addView(toggle("Mostrar logs de debug", debugLogs) { debugLogs = it },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply { topMargin = dp(6) })

        root.addView(neonButton("Salvar configurações", Neon.GREEN).apply {
            setOnClickListener {
                prefs.apiKey = keyInput.text.toString()
                prefs.model = modelInput.text.toString()
                prefs.groqKey = groqInput.text.toString()
                prefs.wakeWords = wakeInput.text.toString()
                prefs.voice = selectedVoice
                prefs.robo = fx
                prefs.volume = volume
                prefs.conversationMode = conversation
                prefs.bargeIn = bargeIn
                prefs.greetOn = greetOn
                prefs.greetings = greetInput.text.toString()
                prefs.debugLogs = debugLogs
                Toast.makeText(this@SettingsActivity, "Salvo!", Toast.LENGTH_SHORT).show()
                finish()
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply { topMargin = dp(18) })

        // ---- permissões: lista que abre e fecha, com o que está OK e o que falta ----
        root.addView(label("Permissões"))
        permsHeader = neonButton("", Neon.GREEN).apply {
            isAllCaps = false
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { permsOpen = !permsOpen; refreshPerms() }
        }
        root.addView(permsHeader, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply { topMargin = dp(6) })
        permsBox = panel(Neon.PURPLE)
        root.addView(permsBox, blockParams(dp(6)))
        // começa aberta se faltar alguma, para ninguém deixar passar
        permsOpen = perms().any { !granted(it) }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Neon.BG)
            isFillViewport = true
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        // atualiza ao voltar da tela de permissão do Android (ou de baixar vozes)
        refreshPerms()
        refreshVoice()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERM) return
        val p = permissions.firstOrNull() ?: return
        // negada sem poder perguntar de novo ("não perguntar novamente"): só dá para liberar na tela do app
        if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED && !shouldShowRequestPermissionRationale(p)) {
            Toast.makeText(this, "Libere em Permissões, na tela do aplicativo.", Toast.LENGTH_LONG).show()
            openScreen(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        refreshPerms()
    }

    /**
     * Uma permissão da lista. [runtime] é pedida pela janelinha do Android;
     * as especiais ([check] + [screen]) só se liberam numa tela de configurações do sistema.
     */
    private class Perm(
        val title: String,
        val why: String,
        val runtime: String? = null,
        val check: (() -> Boolean)? = null,
        val screen: (() -> Intent)? = null,
        val fallback: (() -> Intent)? = null, // se o celular não tiver a [screen]
    )

    private fun perms(): List<Perm> = buildList {
        add(Perm("Microfone", "Ouvir a palavra de ativação e o que você fala.",
            runtime = Manifest.permission.RECORD_AUDIO))
        add(Perm("Contatos", "Achar o contato para ligar pelo WhatsApp.",
            runtime = Manifest.permission.READ_CONTACTS))
        add(Perm("Ligações", "Abrir a chamada no WhatsApp.",
            runtime = Manifest.permission.CALL_PHONE))
        if (Build.VERSION.SDK_INT >= 33) {
            add(Perm("Notificações", "Mostrar o aviso de que a Capri está ligada.",
                runtime = Manifest.permission.POST_NOTIFICATIONS))
        }
        // sem isso não aparece a barrinha "ouvindo" e o Android bloqueia abrir o WhatsApp em segundo plano
        add(Perm("Aparecer sobre outros apps", "Barrinha \"ouvindo\" e abrir WhatsApp ou relógio com o app fechado.",
            check = { Settings.canDrawOverlays(this@SettingsActivity) },
            screen = { Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")) }))
        add(Perm("Fora da economia de bateria", "O Android não desligar a Capri em segundo plano.",
            check = { getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) },
            // só "Sem restrições" conta; "Otimizado" (chave ligada na tela do app) ainda economiza bateria
            screen = { Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")) },
            fallback = { Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }))
        // marcação separada da de cima (pode estar ligada mesmo com "Sem restrições"): com ela o Android
        // tira o serviço do primeiro plano ao sair do app e silencia o microfone
        if (Build.VERSION.SDK_INT >= 28) {
            add(Perm("Liberada em segundo plano", "Continuar ouvindo com o app fechado.",
                check = { !CapriService.backgroundRestricted(this@SettingsActivity) },
                screen = { Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")) }))
        }
    }

    private fun granted(p: Perm): Boolean =
        p.runtime?.let { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } ?: p.check!!()

    private fun request(p: Perm) {
        if (p.runtime != null) requestPermissions(arrayOf(p.runtime), REQ_PERM)
        else openScreen(p.screen!!(), p.fallback?.invoke())
    }

    private fun openScreen(intent: Intent, fallback: Intent? = null) {
        for (i in listOfNotNull(intent, fallback, Intent(Settings.ACTION_SETTINGS))) {
            try {
                startActivity(i)
                return
            } catch (_: Exception) {}
        }
    }

    /** Redesenha o cabeçalho (X/Y OK) e, se estiver aberta, as linhas de cada permissão. */
    private fun refreshPerms() {
        val list = perms()
        val ok = list.count { granted(it) }
        val allOk = ok == list.size
        permsHeader.text = "${if (permsOpen) "▾" else "▸"}  ${if (allOk) "✓" else "⚠"}  $ok de ${list.size} liberadas"
        styleButton(permsHeader, if (allOk) Neon.GREEN else Neon.YELLOW, filled = false)

        permsBox.removeAllViews()
        permsBox.visibility = if (permsOpen) View.VISIBLE else View.GONE
        if (!permsOpen) return
        list.forEachIndexed { i, p ->
            val on = granted(p)
            permsBox.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(mono(14f, if (on) Neon.GREEN else Neon.RED).apply {
                        text = (if (on) "✓  " else "✕  ") + p.title
                    })
                    addView(mono(11f, Neon.MUTED).apply {
                        text = p.why
                        setPadding(0, dp(2), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                if (!on) {
                    addView(neonButton("Liberar", Neon.YELLOW).apply {
                        textSize = 11f
                        setOnClickListener { request(p) }
                    }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(40)).apply { marginStart = dp(8) })
                }
            }, blockParams(if (i == 0) 0 else dp(12)))
        }
    }

    override fun onDestroy() {
        speaker?.stop()
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }

    /** Botão liga/desliga: verde com ◉ quando ligado, apagado com ○ quando desligado. */
    private fun toggle(title: String, initial: Boolean, onChange: (Boolean) -> Unit): Button {
        var on = initial
        return neonButton("", Neon.GREEN).apply {
            isAllCaps = false
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(14), 0, dp(14), 0)
            fun refresh() {
                text = (if (on) "◉  " else "○  ") + title
                styleButton(this, if (on) Neon.GREEN else Neon.MUTED, filled = false)
            }
            refresh()
            setOnClickListener { on = !on; refresh(); onChange(on) }
        }
    }

    /** Relê as vozes instaladas (podem ter sido baixadas agora) e atualiza o texto do seletor. */
    private fun refreshVoice() {
        val t = tts
        voices = if (ttsReady && t != null) PhoneVoices.list(t) else emptyList()
        val name = if (!ttsReady && selectedVoice.isNotBlank()) "Carregando vozes..."
                   else PhoneVoices.label(selectedVoice, voices)
        voicePicker.text = "$name   ▾"
    }

    private fun pickVoice() {
        if (!ttsReady) { Toast.makeText(this, "Ainda carregando as vozes do celular...", Toast.LENGTH_SHORT).show(); return }
        val values = listOf("") + voices.map { it.name }
        val labels = values.map { PhoneVoices.label(it, voices) }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Voz da Capri")
            .setSingleChoiceItems(labels, values.indexOf(selectedVoice).coerceAtLeast(0)) { d, i ->
                selectedVoice = values[i]
                refreshVoice()
                d.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Fala uma frase curta com a voz escolhida (mesmo sem salvar). */
    private fun testVoice() {
        val t = tts
        if (!ttsReady || t == null) { Toast.makeText(this, "Ainda carregando as vozes do celular...", Toast.LENGTH_SHORT).show(); return }
        PhoneVoices.apply(t, selectedVoice)
        speaker?.speak(TEST_PHRASE, fx, volume)
    }

    /** Barra de um ajuste da voz robótica: mexer nela muda [fx] (o "Testar" já usa). */
    private fun fxSlider(title: String, range: IntRange, get: (RoboFx) -> Int, set: (RoboFx, Int) -> RoboFx): View =
        slider(title, range, { get(fx) }) { fx = set(fx, it) }

    /** "Metálico · 35%" com a barra embaixo. [value] é relido no "Restaurar". */
    private fun slider(title: String, range: IntRange, value: () -> Int, onChange: (Int) -> Unit): View {
        val text = mono(12f, Neon.TEXT)
        val bar = SeekBar(this).apply {
            max = range.last - range.first
            progressTintList = ColorStateList.valueOf(Neon.CYAN)
            thumbTintList = ColorStateList.valueOf(Neon.CYAN)
            progressBackgroundTintList = ColorStateList.valueOf(Neon.MUTED)
        }
        fun refresh() {
            text.text = "$title · ${value()}%"
            bar.progress = value() - range.first
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                onChange(p + range.first)
                text.text = "$title · ${value()}%"
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        refresh()
        fxRefresh.add { refresh() }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text)
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    companion object {
        // tem a palavra de ativação: se a Capri estiver ligada, o teste pode ativá-la
        private const val TEST_PHRASE = "Oi! Eu sou a Capri."
        private const val REQ_PERM = 7
    }
}
