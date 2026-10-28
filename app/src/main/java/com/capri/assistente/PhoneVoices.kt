package com.capri.assistente

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/**
 * Vozes em português do motor de voz do celular (normalmente o do Google).
 * A escolha fica salva pelo nome da voz; "" = voz padrão do celular.
 */
object PhoneVoices {

    private val PT_BR = Locale("pt", "BR")

    /** Vozes pt-BR já baixadas no celular: as offline primeiro, depois as que precisam de internet. */
    fun list(tts: TextToSpeech): List<Voice> =
        (try { tts.voices } catch (_: Exception) { null }).orEmpty()
            .filter { v ->
                // alguns motores usam códigos de 3 letras (por/BRA)
                v.locale.language in setOf("pt", "por") && v.locale.country in setOf("BR", "BRA") &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features.orEmpty()
            }
            .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name }))

    /** "Voz 2 · offline" — o Android não informa se a voz é feminina ou masculina. */
    fun label(name: String, voices: List<Voice>): String {
        if (name.isBlank()) return "Padrão do celular"
        val i = voices.indexOfFirst { it.name == name }
        if (i < 0) return "Padrão do celular (a voz escolhida não está instalada)"
        return "Voz ${i + 1} · ${if (voices[i].isNetworkConnectionRequired) "internet" else "offline"}"
    }

    /**
     * Usa a voz [name] no [tts]. Se ela não existir mais (desinstalada), volta para a voz
     * padrão em português sem dar erro. Retorna false nesse caso.
     */
    fun apply(tts: TextToSpeech, name: String): Boolean {
        val v = name.takeIf { it.isNotBlank() }?.let { n -> list(tts).firstOrNull { it.name == n } }
        if (v != null && tts.setVoice(v) == TextToSpeech.SUCCESS) return true
        tts.language = PT_BR
        return name.isBlank()
    }

    /** Abre a tela do Android para baixar vozes; se o celular não tiver, as configurações de voz. */
    fun openDownload(ctx: Context, engine: String?) {
        val tries = listOf(
            Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply { if (engine != null) setPackage(engine) },
            Intent("com.android.settings.TTS_SETTINGS"),
            Intent(android.provider.Settings.ACTION_SETTINGS),
        )
        for (i in tries) {
            try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {}
        }
    }
}
