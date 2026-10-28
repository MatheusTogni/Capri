package com.capri.assistente

/**
 * Guarda a conversa e pergunta aos provedores em ordem (Gemini, depois Groq):
 * se um falhar (cota, servidor cheio, demora, erro), o próximo responde com o mesmo histórico.
 */
class Brain(private val providers: List<AiProvider>) {

    /** O que a IA decidiu: responder em texto ou pedir uma ação no celular. */
    sealed class Reply {
        data class Text(val text: String) : Reply()
        data class WhatsAppCall(val contact: String, val video: Boolean) : Reply()
        data class Action(val command: Command) : Reply()
    }

    private val history = mutableListOf<Pair<String, String>>() // (role, texto)

    fun ask(question: String): Reply {
        history.add("user" to question)
        try {
            val system = "${AiPrompts.STYLE}\n${AiPrompts.ACTIONS}\n${AiPrompts.clock()}"
            val (provider, turn) = firstOk(providers) { it.chat(system, history) }
            // no histórico fica só um texto, para a conversa continuar fazendo sentido
            val reply = when (turn) {
                is AiTurn.Text -> Reply.Text(turn.text.ifBlank { "Não tenho uma resposta para isso." })
                is AiTurn.Call -> fromCall(provider, question, turn)
            }
            remember(when (reply) {
                is Reply.Text -> reply.text
                is Reply.WhatsAppCall -> "Ok, vou ligar para ${reply.contact} pelo WhatsApp."
                is Reply.Action -> "Ok, feito."
            })
            return reply
        } catch (e: Exception) {
            if (history.lastOrNull()?.first == "user") history.removeAt(history.lastIndex)
            throw e
        }
    }

    private fun fromCall(provider: AiProvider, question: String, call: AiTurn.Call): Reply {
        val a = call.args
        return when (call.name) {
            AiPrompts.CALL_TOOL -> {
                val contact = a.optString("contato").trim()
                if (contact.isBlank()) Reply.Text("Não entendi para quem ligar.")
                else Reply.WhatsAppCall(contact, a.optBoolean("video", false))
            }
            AiPrompts.SEARCH_TOOL -> Reply.Text(search(provider, question, a.optString("consulta").ifBlank { question }))
            else -> toCommand(call.name, a)?.let { Reply.Action(it) } ?: Reply.Text("Não entendi o que fazer.")
        }
    }

    /** Pesquisa primeiro no provedor que pediu; se falhar, nos outros. */
    private fun search(asker: AiProvider, question: String, query: String): String {
        CapriBus.log("🔎 Pesquisando: $query")
        val system = "${AiPrompts.STYLE}\n${AiPrompts.SEARCH}\n${AiPrompts.clock()}"
        val order = listOf(asker) + providers.filter { it !== asker }
        return try {
            firstOk(order) { it.search(system, "$question\n(pesquisar: $query)") }.second
                .ifBlank { "Não achei nada sobre isso." }
        } catch (e: BusyException) {
            if (e.kind == BusyException.Kind.QUOTA) "Acabou a cota grátis de pesquisa por agora. Tenta de novo daqui a pouco."
            else "Não consegui pesquisar agora. Tenta de novo daqui a pouco."
        } catch (_: Exception) {
            "Não consegui pesquisar agora. Tenta de novo daqui a pouco."
        }
    }

    /** Tenta cada provedor em ordem; se todos falharem, lança o erro do primeiro (o principal). */
    private fun <T> firstOk(order: List<AiProvider>, f: (AiProvider) -> T): Pair<AiProvider, T> {
        var first: Exception? = null
        for ((i, p) in order.withIndex()) {
            try {
                val r = f(p)
                if (i > 0) CapriBus.debug("↪️ Respondi com o ${p.label}")
                return p to r
            } catch (e: Exception) {
                CapriBus.log("⚠️ ${e.message}")
                if (first == null) first = e
            }
        }
        throw first ?: IllegalStateException("Nenhum provedor de IA configurado.")
    }

    private fun remember(answer: String) {
        history.add("model" to answer)
        // guarda só as últimas 10 trocas (pergunta + resposta)
        while (history.size > 20) { history.removeAt(0); history.removeAt(0) }
    }

    private fun toCommand(name: String, a: org.json.JSONObject): Command? = when (name) {
        "lanterna" -> Command.Torch(a.optBoolean("ligar", true))
        "volume" -> a.optString("acao").takeIf { it in setOf("aumentar", "diminuir", "maximo", "mudo") }
            ?.let { Command.Volume(it) }
        "timer" -> a.optInt("segundos", 0).takeIf { it > 0 }?.let { Command.Timer(it) }
        "alarme" -> {
            val h = a.optInt("hora", -1); val m = a.optInt("minuto", 0)
            if (h in 0..23 && m in 0..59) Command.Alarm(h, m, a.optString("descricao").trim()) else null
        }
        "lista_tarefas" -> {
            val text = a.optString("texto").trim()
            val number = a.optInt("numero", 0)
            when (a.optString("acao")) {
                "adicionar" -> text.takeIf { it.isNotBlank() }?.let { Command.Todo.Add(it) }
                "ler" -> Command.Todo.Read
                "contar" -> Command.Todo.Count
                "remover" -> if (text.isNotBlank() || number > 0) Command.Todo.Remove(text, number) else null
                "limpar" -> Command.Todo.Clear
                else -> null
            }
        }
        else -> null
    }
}
