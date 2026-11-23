package com.capri.assistente.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import com.capri.assistente.CapriBarView
import com.capri.assistente.CapriBus
import com.capri.assistente.CapriService

/**
 * É isto que faz a Capri aparecer em "App de assistente digital". Escolhida lá, o botão de ligar
 * (segurar) chama a [CapriSession]: o microfone só abre nessa hora.
 * A classe em si não precisa fazer nada; o Android a mantém ligada e abre a sessão quando o botão é apertado.
 */
class CapriInteractionService : VoiceInteractionService()

/** O Android pede uma sessão nova a cada vez que o assistente é chamado. */
class CapriSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = CapriSession(this)
}

/**
 * Uma chamada pelo botão de ligar. A sessão só mostra a barrinha "ouvindo" (a janela dela mantém o app
 * em primeiro plano, que é o que libera o microfone) e acorda o [CapriService], que faz o resto:
 * ouvir a pergunta, responder e desligar sozinho.
 */
class CapriSession(ctx: Context) : VoiceInteractionSession(ctx) {

    private val main = Handler(Looper.getMainLooper())
    private var bar: CapriBarView? = null
    private var seenBar = false // a barra já apareceu; quando sumir, a conversa acabou
    private val barListener: () -> Unit = { update() }
    private val giveUp = Runnable { if (!CapriBus.barVisible) hide() } // o serviço não acordou

    override fun onCreateContentView(): View {
        val d = context.resources.displayMetrics.density
        val b = CapriBarView(context).also { bar = it; it.setStatus(WAKING) }
        return FrameLayout(context).apply {
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), (48 * d).toInt())
            addView(b, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
            setOnClickListener { dismiss() } // toque fora da barra fecha
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        CapriBus.init(context)
        CapriBus.assistUi = true
        CapriBus.onBar = barListener
        seenBar = false
        update()
        main.postDelayed(giveUp, GIVE_UP_MS)
        try {
            context.startForegroundService(
                Intent(context, CapriService::class.java).setAction(CapriService.ACTION_ASSIST)
            )
        } catch (e: Exception) {
            CapriBus.log("❌ Não consegui acordar a Capri pelo botão: ${e.javaClass.simpleName}: ${e.message}")
            hide()
        }
    }

    override fun onHide() {
        super.onHide()
        cleanup()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    override fun onBackPressed() = dismiss()

    /** O usuário fechou: o serviço para de ouvir e de falar. */
    private fun dismiss() {
        CapriBus.onAssistCancel?.invoke()
        hide()
    }

    private fun update() {
        when {
            CapriBus.barVisible -> {
                seenBar = true
                bar?.setStatus(CapriBus.status)
            }
            seenBar -> hide() // o serviço terminou a conversa
            else -> bar?.setStatus(WAKING)
        }
    }

    private fun cleanup() {
        main.removeCallbacks(giveUp)
        CapriBus.assistUi = false
        if (CapriBus.onBar === barListener) CapriBus.onBar = null
        seenBar = false
    }

    private companion object {
        const val GIVE_UP_MS = 10_000L
        const val WAKING = "Acordando a Capri..."
    }
}
