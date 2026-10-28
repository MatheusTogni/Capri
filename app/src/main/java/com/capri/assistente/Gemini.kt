package com.capri.assistente

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** O Google está sem capacidade agora (429/5xx); não é problema da chave nem do app. */
class GeminiBusyException(msg: String) : IOException(msg)

class Gemini(val apiKey: String, val model: String) {

    /** O que o Gemini decidiu: responder em texto ou pedir uma ação no celular. */
    sealed class Reply {
        data class Text(val text: String) : Reply()
        data class WhatsAppCall(val contact: String, val video: Boolean) : Reply()
        data class Action(val command: Command) : Reply()
    }

    private val history = mutableListOf<Pair<String, String>>() // (role, texto)

    fun ask(question: String): Reply {
        history.add("user" to question)

        val contents = JSONArray()
        for ((role, text) in history) {
            contents.put(
                JSONObject()
                    .put("role", role)
                    .put("parts", JSONArray().put(JSONObject().put("text", text)))
            )
        }
        val body = JSONObject()
            .put("system_instruction", systemObj("$STYLE\n$ACTIONS\n${clock()}"))
            .put("contents", contents)
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", tools())))

        try {
            val resp = postWithRetry(body)

            val parts = JSONObject(resp)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")

            for (i in 0 until parts.length()) {
                val fc = parts.getJSONObject(i).optJSONObject("functionCall") ?: continue
                val args = fc.optJSONObject("args") ?: JSONObject()
                // no histórico fica só um texto, para a conversa continuar fazendo sentido
                when (val name = fc.optString("name")) {
                    CALL_TOOL -> {
                        val contact = args.optString("contato").trim()
                        if (contact.isBlank()) continue
                        remember("Ok, vou ligar para $contact pelo WhatsApp.")
                        return Reply.WhatsAppCall(contact, args.optBoolean("video", false))
                    }
                    SEARCH_TOOL -> {
                        val answer = search(question, args.optString("consulta").ifBlank { question })
                        remember(answer)
                        return Reply.Text(answer)
                    }
                    else -> {
                        val cmd = toCommand(name, args) ?: continue
                        remember("Ok, feito.")
                        return Reply.Action(cmd)
                    }
                }
            }

            val answer = textOf(parts).ifBlank { "Não tenho uma resposta para isso." }
            remember(answer)
            return Reply.Text(answer)
        } catch (e: Exception) {
            if (history.lastOrNull()?.first == "user") history.removeAt(history.lastIndex)
            throw e
        }
    }

    private fun remember(answer: String) {
        history.add("model" to answer)
        // guarda só as últimas 10 trocas (pergunta + resposta)
        while (history.size > 20) { history.removeAt(0); history.removeAt(0) }
    }

    private fun clock(): String =
        "Agora é " + SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy, HH:mm", Locale("pt", "BR")).format(Date()) +
            " (relógio do celular)."

    private fun systemObj(text: String) =
        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun textOf(parts: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (!p.optBoolean("thought", false)) sb.append(p.optString("text"))
        }
        return sb.toString().trim()
    }

    /**
     * Pesquisa no Google. Tenta os modelos de SEARCH_MODELS em ordem: se um sair do ar
     * (o Google aposenta modelos antigos com HTTP 404) ou estiver sobrecarregado, vai para o próximo.
     */
    private fun search(question: String, query: String): String {
        val body = JSONObject()
            .put("system_instruction", systemObj("$STYLE\nUse a busca do Google para responder com informação atual.\n${clock()}"))
            .put("contents", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", "$question\n(pesquisar: $query)")))))
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        CapriBus.log("🔎 Pesquisando: $query")
        for (m in SEARCH_MODELS) {
            try {
                val resp = post(m, body, readTimeout = 25_000)
                val parts = JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                return textOf(parts).ifBlank { "Não achei nada sobre isso." }
            } catch (e: Exception) {
                CapriBus.log("⚠️ Pesquisa falhou em $m: ${e.message}")
            }
        }
        return "Não consegui pesquisar agora. Tenta de novo daqui a pouco."
    }

    private fun toCommand(name: String, a: JSONObject): Command? = when (name) {
        "lanterna" -> Command.Torch(a.optBoolean("ligar", true))
        "volume" -> a.optString("acao").takeIf { it in setOf("aumentar", "diminuir", "maximo", "mudo") }
            ?.let { Command.Volume(it) }
        "timer" -> a.optInt("segundos", 0).takeIf { it > 0 }?.let { Command.Timer(it) }
        "alarme" -> {
            val h = a.optInt("hora", -1); val m = a.optInt("minuto", 0)
            if (h in 0..23 && m in 0..59) Command.Alarm(h, m, a.optString("descricao").trim()) else null
        }
        else -> null
    }

    /** Ações que o Gemini pode pedir em vez de responder com texto. */
    private fun tools() = JSONArray()
        .put(tool(CALL_TOOL, "Faz uma ligação de voz ou chamada de vídeo pelo WhatsApp para um contato da agenda do celular.",
            listOf("contato"),
            "contato" to prop("STRING", "Nome do contato do jeito que o usuário falou, ex.: \"João\", \"mãe\", \"Ana da academia\"."),
            "video" to prop("BOOLEAN", "true se o usuário pediu chamada de vídeo; false para ligação de voz.")))
        .put(tool(SEARCH_TOOL, "Pesquisa no Google informação atual: clima, notícias, resultados de jogos, preços, eventos recentes.",
            listOf("consulta"),
            "consulta" to prop("STRING", "O que pesquisar, em poucas palavras.")))
        .put(tool("lanterna", "Liga ou desliga a lanterna do celular.", listOf("ligar"),
            "ligar" to prop("BOOLEAN", "true para ligar, false para desligar.")))
        .put(tool("volume", "Muda o volume de mídia do celular.", listOf("acao"),
            "acao" to prop("STRING", "Uma destas: aumentar, diminuir, maximo, mudo.")))
        .put(tool("timer", "Inicia um timer (contagem regressiva) no app de relógio.", listOf("segundos"),
            "segundos" to prop("INTEGER", "Duração total em segundos.")))
        .put(tool("alarme", "Cria um alarme no app de relógio. Use também para lembretes (\"me lembra de X às 20h\"); só aceita a próxima vez que esse horário chegar.",
            listOf("hora", "minuto"),
            "hora" to prop("INTEGER", "Hora de 0 a 23."),
            "minuto" to prop("INTEGER", "Minuto de 0 a 59."),
            "descricao" to prop("STRING", "Texto do lembrete, ex.: \"tomar remédio\". Vazio para despertador.")))

    private fun prop(type: String, description: String) =
        JSONObject().put("type", type).put("description", description)

    private fun tool(name: String, description: String, required: List<String>, vararg props: Pair<String, JSONObject>) =
        JSONObject()
            .put("name", name)
            .put("description", description)
            .put("parameters", JSONObject()
                .put("type", "OBJECT")
                .put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
                .put("required", JSONArray(required)))

    /**
     * Tenta o modelo escolhido e, se ele estiver sobrecarregado ou demorar demais,
     * cai para os modelos reserva. Outros erros sobem na hora.
     */
    private fun postWithRetry(body: JSONObject): String {
        val attempts = listOf(model) + FALLBACK_MODELS.filter { it != model }
        var last: GeminiBusyException? = null
        for ((i, m) in attempts.withIndex()) {
            if (i > 0) Thread.sleep(300L)
            try {
                val resp = post(m, body)
                if (m != model) CapriBus.debug("↪️ $model indisponível (${last?.message}), respondi com $m")
                return resp
            } catch (e: GeminiBusyException) {
                last = e
            }
        }
        throw last!!
    }

    /** Pede pouco "raciocínio" (resposta bem mais rápida); se o modelo não aceitar, manda sem. */
    private fun post(m: String, body: JSONObject, readTimeout: Int = 15_000): String {
        if (m !in noThinkingConfig) {
            val fast = JSONObject(body.toString())
                .put("generationConfig", JSONObject().put("thinkingConfig", JSONObject().put("thinkingLevel", "low")))
            try {
                return send(m, fast.toString(), readTimeout)
            } catch (e: BadThinkingConfig) {
                noThinkingConfig.add(m)
            }
        }
        return send(m, body.toString(), readTimeout)
    }

    private class BadThinkingConfig : IOException()

    private fun send(m: String, body: String, readTimeout: Int): String {
        val t0 = SystemClock.elapsedRealtime()
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            // resposta de voz é curta: se passar disso, melhor tentar o modelo reserva
            conn.readTimeout = readTimeout
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val firstByte = SystemClock.elapsedRealtime() - t0
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            logRequest(m, code, firstByte, SystemClock.elapsedRealtime() - t0, resp)
            if (code in RETRY_CODES) {
                throw GeminiBusyException("Gemini sobrecarregado (HTTP $code em $m). Tente de novo daqui a pouco.")
            }
            if (code == 400 && resp.contains("thinking", ignoreCase = true)) throw BadThinkingConfig()
            if (code !in 200..299) {
                throw IOException("Gemini respondeu HTTP $code: ${resp.take(300)}")
            }
            return resp
        } catch (e: SocketTimeoutException) {
            CapriBus.debug("   ↳ $m sem resposta em ${Timing.fmt(SystemClock.elapsedRealtime() - t0)}")
            throw GeminiBusyException("Gemini demorou demais para responder ($m).")
        } finally {
            conn.disconnect()
        }
    }

    /** Uma linha por requisição: modelo, HTTP, tempo até o 1º byte, total e tokens de "raciocínio". */
    private fun logRequest(m: String, code: Int, firstByte: Long, total: Long, resp: String) {
        val thoughts = if (code in 200..299) {
            try { JSONObject(resp).optJSONObject("usageMetadata")?.optInt("thoughtsTokenCount", 0) ?: 0 } catch (_: Exception) { 0 }
        } else 0
        CapriBus.debug("   ↳ $m HTTP $code · 1º byte ${Timing.fmt(firstByte)} · total ${Timing.fmt(total)}" +
            if (thoughts > 0) " · pensou $thoughts tokens" else "")
    }

    companion object {
        /** Modelos que recusaram o thinkingConfig (não tenta de novo enquanto o app estiver aberto). */
        private val noThinkingConfig = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        private val RETRY_CODES = setOf(429, 500, 502, 503, 504)
        private const val CALL_TOOL = "ligar_whatsapp"
        private const val SEARCH_TOOL = "pesquisar_na_web"
        private val SEARCH_MODELS = listOf("gemini-3.8-flash", "gemini-flash-lite-latest")
        private val FALLBACK_MODELS = listOf("gemini-flash-lite-latest", "gemini-3.8-flash")

        /** Jeito de falar (vale também para a pesquisa). */
        const val STYLE = """Você é a Capri, uma assistente de voz que fala português do Brasil.
Suas respostas serão lidas em voz alta por um sintetizador de voz, então:
- responda de forma curta e natural, normalmente em 1 a 3 frases;
- nunca use markdown, listas, asteriscos, emojis, tabelas ou links;
- escreva números e siglas de um jeito fácil de falar;
- para data e hora, use o relógio do celular informado no fim destas instruções.
Seja simpática e direta."""

        const val ACTIONS = """Você consegue agir no celular usando as funções disponíveis:
- ligar ou fazer chamada de vídeo: ligar_whatsapp (você só liga pelo WhatsApp; não existe ligação telefônica comum);
- clima, notícias, resultados de jogos ou qualquer coisa atual: pesquisar_na_web;
- lanterna, volume, timer, alarme e lembretes: as funções com esses nomes.
Se o pedido não precisar de nenhuma função, só responda."""
    }
}
