package com.capri.assistente

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

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
        window.statusBarColor = Neon.BG
        window.navigationBarColor = Neon.BG

        val pad = dp(16)
        val root = LinearLayout(this).apply {
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

        // ---- configurações ----
        root.addView(section("Configurações"))

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

        root.addView(label("Palavras de ativação (separadas por vírgula)"))
        val wakeInput = input().apply { setText(prefs.wakeWords) }
        root.addView(wakeInput, blockParams(dp(6)))

        root.addView(neonButton("Salvar configurações", Neon.GREEN).apply {
            setOnClickListener {
                prefs.apiKey = keyInput.text.toString()
                prefs.model = modelInput.text.toString()
                prefs.wakeWords = wakeInput.text.toString()
                Toast.makeText(this@MainActivity, "Salvo!", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply { topMargin = dp(18) })

        root.addView(neonButton("Liberar da economia de bateria", Neon.PURPLE).apply {
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply { topMargin = dp(10) })

        // ---- histórico ----
        root.addView(section("Histórico"))
        logView = mono(13f, Neon.GREEN).apply {
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.15f)
        }
        root.addView(panel(Neon.GREEN).apply { addView(logView) }, blockParams(dp(8)))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Neon.BG)
            isFillViewport = true
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        CapriBus.onChange = { render() }
        render()
    }

    override fun onPause() {
        super.onPause()
        CapriBus.onChange = null
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
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
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
            Toast.makeText(this, "Dica: salve a chave do Gemini antes.", Toast.LENGTH_LONG).show()
        }
        startForegroundService(Intent(this, CapriService::class.java))
        CapriBus.running = true
        render()
    }

    // ---------- peças visuais ----------

    /** Imagem da Capri com moldura neon e o nome por cima. */
    private fun hero(): View {
        val frame = FrameLayout(this).apply {
            background = CutDrawable(Neon.BG, Neon.GREEN, dp(2).toFloat(), dp(18).toFloat())
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
        frame.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(240))
        return frame
    }

    private fun panel(border: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = CutDrawable(Neon.PANEL, border, dp(1).toFloat(), dp(12).toFloat())
        setPadding(dp(14), dp(12), dp(14), dp(14))
    }

    private fun input() = EditText(this).apply {
        textSize = 15f
        setTextColor(Neon.TEXT)
        setHintTextColor(Neon.MUTED)
        highlightColor = 0x668A3BFF
        background = CutDrawable(Neon.PANEL, Neon.PURPLE, dp(1).toFloat(), dp(8).toFloat())
        setPadding(dp(12), dp(12), dp(12), dp(12))
        if (Build.VERSION.SDK_INT >= 29) {
            textCursorDrawable = GradientDrawable().apply { setColor(Neon.GREEN); setSize(dp(2), 0) }
        }
    }

    private fun neonButton(t: String, color: Int, filled: Boolean = false) = Button(this).apply {
        text = t
        isAllCaps = true
        letterSpacing = 0.08f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        stateListAnimator = null
        styleButton(this, color, filled)
    }

    private fun styleButton(b: Button, color: Int, filled: Boolean) {
        val cut = dp(10).toFloat()
        val shape = if (filled) CutDrawable(color, cut = cut)
                    else CutDrawable(0x1A000000 or (color and 0x00FFFFFF), color, dp(2).toFloat(), cut)
        b.background = RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), shape, CutDrawable(color, cut = cut))
        b.setTextColor(if (filled) Neon.BG else color)
    }

    private fun section(t: String) = mono(13f, Neon.RED).apply {
        text = "▌ ${t.uppercase()}"
        letterSpacing = 0.15f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setPadding(0, dp(28), 0, 0)
    }

    private fun label(t: String) = mono(11f, Neon.MUTED).apply {
        text = "> ${t.uppercase()}"
        letterSpacing = 0.05f
        setPadding(0, dp(14), 0, 0)
    }

    private fun mono(size: Float, color: Int) = TextView(this).apply {
        textSize = size
        setTextColor(color)
        typeface = Typeface.MONOSPACE
    }

    private fun blockParams(top: Int) =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = top }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_PERMS = 42
    }
}
