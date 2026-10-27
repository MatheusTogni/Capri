package com.capri.assistente

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var statusView: TextView
    private lateinit var heardView: TextView
    private lateinit var logView: TextView
    private lateinit var toggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Capri"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
        })

        statusView = TextView(this).apply { textSize = 18f; setPadding(0, dp(8), 0, 0) }
        heardView = TextView(this).apply { textSize = 14f; alpha = 0.7f; setPadding(0, dp(4), 0, dp(12)) }
        root.addView(statusView)
        root.addView(heardView)

        toggle = Button(this).apply { setOnClickListener { onToggle() } }
        root.addView(toggle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(label("Chave da API do Gemini"))
        val keyInput = EditText(this).apply {
            setText(prefs.apiKey)
            hint = "Cole aqui a chave do AI Studio"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        root.addView(keyInput)

        root.addView(label("Modelo do Gemini"))
        val modelInput = EditText(this).apply { setText(prefs.model); isSingleLine = true }
        root.addView(modelInput)

        root.addView(label("Palavras de ativação (separadas por vírgula)"))
        val wakeInput = EditText(this).apply { setText(prefs.wakeWords) }
        root.addView(wakeInput)

        root.addView(Button(this).apply {
            text = "Salvar configurações"
            setOnClickListener {
                prefs.apiKey = keyInput.text.toString()
                prefs.model = modelInput.text.toString()
                prefs.wakeWords = wakeInput.text.toString()
                Toast.makeText(this@MainActivity, "Salvo!", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(Button(this).apply {
            text = "Liberar da economia de bateria"
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(label("Histórico"))
        logView = TextView(this).apply { textSize = 14f; setTextIsSelectable(true) }
        root.addView(logView)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        CapriBus.onChange = { render() }
        render()
    }

    override fun onPause() {
        super.onPause()
        CapriBus.onChange = null
    }

    private fun render() {
        statusView.text = "Status: ${CapriBus.status}"
        heardView.text = if (CapriBus.heard.isBlank()) "" else "Ouvi: \"${CapriBus.heard}\""
        toggle.text = if (CapriBus.running) "Desligar Capri" else "Ligar Capri"
        logView.text = CapriBus.logText().ifBlank { "Nada ainda." }
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

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        alpha = 0.8f
        setPadding(0, dp(16), 0, 0)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_PERMS = 42
    }
}
