package com.capri.assistente

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.capri.assistente.update.Updater

class MainActivity : NeonActivity() {

    private lateinit var root: LinearLayout
    private var updateCardView: View? = null
    private var updateDismissed = false
    private var installAfterPermission = false

    private lateinit var prefs: Prefs
    private lateinit var statusView: TextView
    private lateinit var statusDot: View
    private lateinit var heardView: TextView
    private lateinit var logView: TextView
    private lateinit var toggle: Button
    private var pulse: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        CapriBus.init(this)
        applyWindowColors()

        val pad = dp(16)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad * 2)
        }

        root.addView(hero())

        // ---- status ----
        statusDot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Neon.MUTED) }
        }
        statusView = mono(15f, Neon.TEXT).apply { setPadding(dp(10), 0, 0, 0) }
        val statusRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)))
            addView(statusView)
        }
        heardView = mono(13f, Neon.CYAN).apply { setPadding(0, dp(6), 0, 0) }
        root.addView(panel(Neon.PURPLE).apply {
            addView(mono(11f, Neon.MUTED).apply { text = "// STATUS"; letterSpacing = 0.15f })
            addView(statusRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
            addView(heardView)
        }, blockParams(dp(16)))

        toggle = neonButton("", Neon.RED, filled = true).apply {
            textSize = 17f
            setOnClickListener { onToggle() }
        }
        root.addView(toggle, LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).apply { topMargin = dp(14) })

        // ---- histórico ----
        root.addView(section("Histórico"))
        logView = mono(13f, Neon.GREEN).apply {
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.15f)
        }
        root.addView(panel(Neon.GREEN).apply { addView(logView) }, blockParams(dp(8)))
        root.addView(LinearLayout(this).apply {
            addView(neonButton("Compartilhar", Neon.GREEN).apply { setOnClickListener { shareHistory() } },
                LinearLayout.LayoutParams(0, dp(46), 1f))
            addView(neonButton("Limpar", Neon.RED).apply { setOnClickListener { confirmClear() } },
                LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(10) })
        }, blockParams(dp(10)))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Neon.BG)
            isFillViewport = true
            addView(root)
        })
        Updater.check()
    }

    override fun onResume() {
        super.onResume()
        CapriBus.uiVisible = true
        CapriBus.onChange = { render() }
        Updater.listener = { refreshUpdateCard() }
        Updater.onConfirm = { startActivity(it) }
        if (installAfterPermission && packageManager.canRequestPackageInstalls()) {
            installAfterPermission = false
            Updater.download(this)
        }
        refreshUpdateCard()
        render()
    }

    override fun onPause() {
        super.onPause()
        CapriBus.uiVisible = false
        CapriBus.onChange = null
        Updater.listener = null
        Updater.onConfirm = null
        pulse?.cancel()
        pulse = null
    }

    private fun render() {
        val color = Neon.statusColor(CapriBus.status)
        statusView.text = CapriBus.status
        statusView.setTextColor(color)
        (statusDot.background as GradientDrawable).setColor(color)
        heardView.text = if (CapriBus.heard.isBlank()) "" else "> ouvi: \"${CapriBus.heard}\""
        heardView.visibility = if (CapriBus.heard.isBlank()) View.GONE else View.VISIBLE
        logView.text = CapriBus.logText().ifBlank { "> nada ainda_" }

        if (CapriBus.running) {
            toggle.text = "■  DESLIGAR CAPRI"
            styleButton(toggle, Neon.GREEN, filled = false)
            if (pulse == null) {
                pulse = ObjectAnimator.ofFloat(statusDot, View.ALPHA, 1f, 0.2f).apply {
                    duration = 700
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            }
        } else {
            toggle.text = "▶  LIGAR CAPRI"
            styleButton(toggle, Neon.RED, filled = true)
            pulse?.cancel()
            pulse = null
            statusDot.alpha = 1f
        }
    }

    private fun onToggle() {
        if (CapriBus.running) {
            startService(Intent(this, CapriService::class.java).setAction(CapriService.ACTION_STOP))
            return
        }
        // contatos e telefone são opcionais: sem eles a Capri só não consegue ligar pelo WhatsApp
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startCapri() else requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startCapri()
        } else {
            Toast.makeText(this, "Sem o microfone a Capri não consegue ouvir.", Toast.LENGTH_LONG).show()
        }
    }

    private fun startCapri() {
        if (prefs.apiKey.isBlank()) {
            Toast.makeText(this, "Dica: salve a chave do Gemini nas configurações (engrenagem).", Toast.LENGTH_LONG).show()
        }
        startForegroundService(Intent(this, CapriService::class.java))
        CapriBus.running = true
        render()
    }

    // ---------- atualização ----------

    /** Card no topo quando há versão nova no GitHub. Nada é baixado até tocar em "Atualizar". */
    private fun updateCard(): View? {
        val release = Updater.available ?: return null
        if (updateDismissed) return null

        val box = panel(Neon.GREEN)
        box.addView(mono(11f, Neon.MUTED).apply { text = "// ATUALIZAÇÃO"; letterSpacing = 0.15f })
        box.addView(mono(16f, Neon.GREEN).apply {
            text = "Nova versão disponível"
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setShadowLayer(8f, 0f, 0f, Neon.GREEN)
        }, blockParams(dp(6)))
        box.addView(mono(13f, Neon.TEXT).apply { text = "> atualizar para a v${release.versionName}?" }, blockParams(dp(4)))

        if (release.notes.isNotEmpty()) {
            box.addView(mono(12f, Neon.MUTED).apply {
                text = release.notes
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, blockParams(dp(6)))
        }

        val error = Updater.error
        if (Updater.phase == Updater.Phase.FAILED && error != null) {
            box.addView(mono(12f, Neon.RED).apply { text = "> $error" }, blockParams(dp(8)))
        }

        when (Updater.phase) {
            Updater.Phase.DOWNLOADING ->
                box.addView(mono(13f, Neon.CYAN).apply { text = "> baixando… ${Updater.progress}%_" }, blockParams(dp(12)))
            Updater.Phase.INSTALLING -> {
                box.addView(mono(13f, Neon.CYAN).apply { text = "> aguardando o instalador_" }, blockParams(dp(12)))
                box.addView(neonButton("Tentar de novo", Neon.PURPLE).apply { setOnClickListener { startUpdate() } },
                    LinearLayout.LayoutParams(MATCH_PARENT, dp(46)).apply { topMargin = dp(10) })
            }
            else -> {
                box.addView(LinearLayout(this).apply {
                    addView(neonButton("Agora não", Neon.MUTED).apply {
                        setOnClickListener {
                            updateDismissed = true
                            refreshUpdateCard()
                        }
                    }, LinearLayout.LayoutParams(0, dp(46), 1f))
                    val label = if (Updater.phase == Updater.Phase.FAILED) "Tentar de novo" else "Atualizar"
                    addView(neonButton(label, Neon.GREEN, filled = true).apply { setOnClickListener { startUpdate() } },
                        LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(10) })
                }, blockParams(dp(12)))
            }
        }
        box.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(16) }
        return box
    }

    /** Troca só o card de atualização (progresso do download etc.), sempre no topo da tela. */
    private fun refreshUpdateCard() {
        updateCardView?.let { root.removeView(it) }
        updateCardView = updateCard()?.also { root.addView(it, 0) }
    }

    private fun startUpdate() {
        if (!packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("Permitir atualizações")
                .setMessage("Para instalar a versão nova, o Android precisa que a Capri tenha permissão para instalar apps. " +
                    "Na próxima tela, ative \"Permitir desta fonte\" e volte para a Capri.")
                .setPositiveButton("Abrir ajustes") { _, _ ->
                    installAfterPermission = true
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        Updater.download(this)
    }

    // ---------- histórico ----------

    /** Manda o histórico inteiro (o do arquivo, não só o da tela) para outro app. */
    private fun shareHistory() {
        CapriBus.fullHistory { text ->
            if (text.isBlank()) {
                Toast.makeText(this, "O histórico está vazio.", Toast.LENGTH_SHORT).show()
                return@fullHistory
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Histórico da Capri")
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(send, "Compartilhar histórico"))
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage("Apagar todo o histórico da Capri?")
            .setPositiveButton("Apagar") { _, _ -> CapriBus.clearHistory() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- peças visuais ----------

    /** Imagem da Capri com moldura neon, o nome por cima e a engrenagem no canto. */
    private fun hero(): View {
        // recorta a imagem nos cantos cortados e desenha a borda verde por cima
        val frame = CutFrameLayout(this, dp(18).toFloat(), Neon.GREEN, dp(2).toFloat()).apply {
            background = CutDrawable(Neon.BG, cut = dp(18).toFloat())
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        frame.addView(ImageView(this).apply {
            setImageResource(R.drawable.capri_art)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // escurece a parte de baixo para o título aparecer bem
        frame.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00000000, 0xE60B0710.toInt())
            )
        }, FrameLayout.LayoutParams(MATCH_PARENT, dp(110), Gravity.BOTTOM))

        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(12))
            addView(TextView(this@MainActivity).apply {
                text = "CAPRI"
                textSize = 46f
                setTextColor(Neon.TEXT)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                letterSpacing = 0.12f
                // deslocamento vermelho/verde imitando glitch
                setShadowLayer(1f, dp(3).toFloat(), 0f, Neon.RED)
            })
            addView(mono(12f, Neon.GREEN).apply {
                text = "// ASSISTENTE NEURAL · NIGHT CITY"
                letterSpacing = 0.1f
                setShadowLayer(8f, 0f, 0f, Neon.GREEN)
            })
        }
        frame.addView(titles, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        // versão discreta no canto, só para consulta
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { null }
        if (version != null) {
            frame.addView(mono(10f, Neon.MUTED).apply {
                text = "v$version"
                alpha = 0.8f
                setShadowLayer(4f, 0f, 0f, 0xFF000000.toInt())
            }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, dp(16), dp(14))
            })
        }
        frame.addView(gearButton(), FrameLayout.LayoutParams(dp(42), dp(42), Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(10), dp(10), 0)
        })
        frame.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(240))
        return frame
    }

    private fun gearButton() = ImageButton(this).apply {
        setImageResource(R.drawable.ic_gear)
        imageTintList = ColorStateList.valueOf(Neon.GREEN)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = "Configurações"
        val cut = dp(8).toFloat()
        background = RippleDrawable(
            ColorStateList.valueOf(0x55FFFFFF),
            CutDrawable(0xB30B0710.toInt(), Neon.GREEN, dp(1).toFloat(), cut),
            CutDrawable(Neon.GREEN, cut = cut)
        )
        setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
    }

    companion object {
        private const val REQ_PERMS = 42
    }
}
