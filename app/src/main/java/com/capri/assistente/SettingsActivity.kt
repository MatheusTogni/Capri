package com.capri.assistente

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Tela de configurações, aberta pela engrenagem da tela principal. */
class SettingsActivity : NeonActivity() {

    private lateinit var prefs: Prefs
    private var testPlayer: MediaPlayer? = null
    private lateinit var overlayButton: Button

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

        root.addView(label("Palavras de ativação (separadas por vírgula)"))
        val wakeInput = input().apply { setText(prefs.wakeWords) }
        root.addView(wakeInput, blockParams(dp(6)))

        root.addView(label("Voz da Capri"))
        var selectedVoice = prefs.voice
        val voicePicker = TextView(this).apply {
            textSize = 15f
            setTextColor(Neon.TEXT)
            background = CutDrawable(Neon.PANEL, Neon.PURPLE, dp(1).toFloat(), dp(8).toFloat())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            text = voiceLabel(selectedVoice)
        }
        voicePicker.setOnClickListener {
            pickVoice(selectedVoice) { v -> selectedVoice = v; voicePicker.text = voiceLabel(v) }
        }
        val testButton = neonButton("▶ Testar", Neon.CYAN).apply { textSize = 12f }
        testButton.setOnClickListener {
            testVoice(keyInput.text.toString().trim(), selectedVoice, testButton)
        }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(voicePicker, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(testButton, LinearLayout.LayoutParams(WRAP_CONTENT, dp(46)).apply { marginStart = dp(8) })
        }, blockParams(dp(6)))

        root.addView(neonButton("Salvar configurações", Neon.GREEN).apply {
            setOnClickListener {
                prefs.apiKey = keyInput.text.toString()
                prefs.model = modelInput.text.toString()
                prefs.wakeWords = wakeInput.text.toString()
                prefs.voice = selectedVoice
                Toast.makeText(this@SettingsActivity, "Salvo!", Toast.LENGTH_SHORT).show()
                finish()
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

        // sem isso não aparece a barrinha "ouvindo" e o Android bloqueia abrir o WhatsApp em segundo plano
        overlayButton = neonButton("", Neon.PURPLE).apply {
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
        }
        root.addView(overlayButton, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply { topMargin = dp(10) })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Neon.BG)
            isFillViewport = true
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        // atualiza ao voltar da tela de permissão do Android
        overlayButton.text = if (Settings.canDrawOverlays(this)) "✓ Aparecer sobre outros apps liberado"
                             else "Liberar aparecer sobre outros apps"
    }

    override fun onDestroy() {
        releaseTestPlayer()
        super.onDestroy()
    }

    // "" = voz do próprio celular (sem Gemini)
    private fun voiceLabel(v: String): String = when {
        v.isBlank() -> "Voz do celular   ▾"
        else -> GeminiVoice.VOICES[v]?.let { "$v · $it   ▾" } ?: "$v   ▾"
    }

    private fun pickVoice(current: String, onPick: (String) -> Unit) {
        val names = GeminiVoice.VOICES.keys.toMutableList()
        // mantém na lista uma voz salva que não está entre as conhecidas
        if (current.isNotBlank() && current !in names) names.add(0, current)
        val values = names + ""
        val labels = values.map { voiceLabel(it).removeSuffix("▾").trim() }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Voz da Capri")
            .setSingleChoiceItems(labels, values.indexOf(current)) { d, i ->
                onPick(values[i])
                d.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Toca uma frase curta com a voz escolhida (usa a chave do campo, mesmo sem salvar). */
    private fun testVoice(key: String, voice: String, button: Button) {
        if (key.isBlank()) { Toast.makeText(this, "Coloque a chave do Gemini primeiro.", Toast.LENGTH_SHORT).show(); return }
        if (voice.isBlank()) { Toast.makeText(this, "A voz do celular não usa o Gemini; escolha uma voz da lista.", Toast.LENGTH_SHORT).show(); return }
        button.isEnabled = false
        button.text = "..."
        Thread {
            val result = try {
                Result.success(GeminiVoice(key, voice).synth(TEST_PHRASE))
            } catch (e: Exception) {
                Result.failure(e)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                button.isEnabled = true
                button.text = "▶ Testar"
                result.onSuccess { playTest(it) }.onFailure {
                    Toast.makeText(this, "Não deu para testar: ${it.message?.take(150)}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun playTest(wav: ByteArray) {
        try {
            releaseTestPlayer()
            val f = File(cacheDir, "teste-voz.wav")
            f.writeBytes(wav)
            testPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(f.absolutePath)
                setOnCompletionListener { releaseTestPlayer() }
                prepare()
                start()
            }
        } catch (e: Exception) {
            releaseTestPlayer()
            Toast.makeText(this, "Não consegui tocar o áudio: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun releaseTestPlayer() {
        testPlayer?.let { try { it.release() } catch (_: Exception) {} }
        testPlayer = null
    }

    companion object {
        // tem a palavra de ativação: se a Capri estiver ligada, o teste pode ativá-la
        private const val TEST_PHRASE = "Oi! Eu sou a Capri."
    }
}
