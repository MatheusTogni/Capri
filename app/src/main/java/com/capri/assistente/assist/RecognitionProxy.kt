package com.capri.assistente.assist

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * O Android exige um reconhecedor de voz junto de todo assistente, e ao escolher a Capri como assistente
 * ele pode virar o reconhecedor padrão do celular (usado por outros apps). Este aqui não reconhece nada:
 * só repassa o pedido para o reconhecedor de outro app (o do Google), para ninguém ficar sem voz.
 */
class CapriRecognitionService : RecognitionService() {

    private var delegate: SpeechRecognizer? = null

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        val cb = listener ?: return
        release()
        val target = Recognizers.external(this)
        val sr = if (target == null) null else try {
            SpeechRecognizer.createSpeechRecognizer(this, target)
        } catch (_: Exception) {
            null
        }
        if (sr == null) {
            safely { cb.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }
        delegate = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = safely { cb.readyForSpeech(params) }
            override fun onBeginningOfSpeech() = safely { cb.beginningOfSpeech() }
            override fun onRmsChanged(rmsdB: Float) = safely { cb.rmsChanged(rmsdB) }
            override fun onBufferReceived(buffer: ByteArray?) = safely { cb.bufferReceived(buffer) }
            override fun onEndOfSpeech() = safely { cb.endOfSpeech() }
            override fun onError(error: Int) = safely { cb.error(error) }
            override fun onResults(results: Bundle?) = safely { cb.results(results) }
            override fun onPartialResults(partialResults: Bundle?) = safely { cb.partialResults(partialResults) }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        try {
            sr.startListening(recognizerIntent ?: Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH))
        } catch (_: Exception) {
            release()
            safely { cb.error(SpeechRecognizer.ERROR_CLIENT) }
        }
    }

    override fun onStopListening(listener: Callback?) {
        try { delegate?.stopListening() } catch (_: Exception) {}
    }

    override fun onCancel(listener: Callback?) = release()

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        delegate?.let {
            try { it.cancel() } catch (_: Exception) {}
            try { it.destroy() } catch (_: Exception) {}
        }
        delegate = null
    }

    // o cliente pode ter morrido no meio do caminho (RemoteException)
    private inline fun safely(block: () -> Unit) {
        try { block() } catch (_: Exception) {}
    }
}

object Recognizers {
    private const val GOOGLE = "com.google.android.googlequicksearchbox"

    /** Reconhecedor de voz de outro app (o do Google, de preferência); nunca o da própria Capri. */
    fun external(ctx: Context): ComponentName? {
        val found = ctx.packageManager
            .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .mapNotNull { it.serviceInfo }
            .filter { it.packageName != ctx.packageName }
        val pick = found.firstOrNull { it.packageName == GOOGLE } ?: found.firstOrNull() ?: return null
        return ComponentName(pick.packageName, pick.name)
    }

    /**
     * Reconhecedor que a própria Capri deve usar para ouvir a pergunta. Com a Capri como assistente, o Android
     * pode trocar o reconhecedor padrão do celular pelo dela (que só repassa), então, havendo o do Google,
     * vai direto nele; se não houver, só desvia quando o padrão for o da Capri. null = usar o padrão do sistema.
     */
    fun forCapri(ctx: Context): ComponentName? {
        val other = external(ctx) ?: return null
        return if (other.packageName == GOOGLE || defaultIsSelf(ctx)) other else null
    }

    /** O reconhecedor padrão do celular é o da própria Capri? (a leitura pode ser bloqueada: aí vale "não") */
    private fun defaultIsSelf(ctx: Context): Boolean = try {
        Settings.Secure.getString(ctx.contentResolver, "voice_recognition_service")
            ?.startsWith(ctx.packageName + "/") == true
    } catch (_: Exception) {
        false
    }
}
