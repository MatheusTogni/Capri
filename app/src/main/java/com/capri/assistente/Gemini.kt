package com.capri.assistente

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** O Google está sem capacidade agora (429/5xx); não é problema da chave nem do app. */
class GeminiBusyException(msg: String) : IOException(msg)

class Gemini(val apiKey: String, val model: String) {

    /** O que o Gemini decidiu: responder em texto ou pedir uma ação no celular. */
    sealed class Reply {
        data class Text(val text: String) : Reply()
        data class WhatsAppCall(val contact: String, val video: Boolean) : Reply()
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
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
            .put("contents", contents)
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().put(callTool()))))
            .toString()

        try {
            val resp = postWithRetry(body)

            val parts = JSONObject(resp)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")

            for (i in 0 until parts.length()) {
                val fc = parts.getJSONObject(i).optJSONObject("functionCall") ?: continue
                if (fc.optString("name") != CALL_TOOL) continue
                val args = fc.optJSONObject("args") ?: JSONObject()
                val contact = args.optString("contato").trim()
                if (contact.isBlank()) continue
                // no histórico fica só um texto, para a conversa continuar fazendo sentido
                remember("Ok, vou ligar para $contact pelo WhatsApp.")
                return Reply.WhatsAppCall(contact, args.optBoolean("video", false))
            }

            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (!p.optBoolean("thought", false)) sb.append(p.optString("text"))
            }
            val answer = sb.toString().trim().ifBlank { "Não tenho uma resposta para isso." }

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

    /** Ação que o Gemini pode pedir: ligação (ou vídeo) pelo WhatsApp. */
    private fun callTool() = JSONObject()
        .put("name", CALL_TOOL)
        .put("description", "Faz uma ligação de voz ou chamada de vídeo pelo WhatsApp para um contato da agenda do celular.")
        .put("parameters", JSONObject()
            .put("type", "OBJECT")
            .put("properties", JSONObject()
                .put("contato", JSONObject()
                    .put("type", "STRING")
                    .put("description", "Nome do contato do jeito que o usuário falou, ex.: \"João\", \"mãe\", \"Ana da academia\"."))
                .put("video", JSONObject()
                    .put("type", "BOOLEAN")
                    .put("description", "true se o usuário pediu chamada de vídeo; false para ligação de voz.")))
            .put("required", JSONArray().put("contato")))

    /**
     * Tenta o modelo escolhido (2 vezes) e, se ele estiver sobrecarregado,
     * cai para os modelos reserva. Erros que não são de sobrecarga sobem na hora.
     */
    private fun postWithRetry(body: String): String {
        val attempts = listOf(model, model) + FALLBACK_MODELS.filter { it != model }
        var last: GeminiBusyException? = null
        for ((i, m) in attempts.withIndex()) {
            if (i > 0) Thread.sleep(if (m == model) 1_500L else 500L)
            try {
                val resp = post(m, body)
                if (m != model) CapriBus.log("↪️ $model sobrecarregado, respondi com $m")
                return resp
            } catch (e: GeminiBusyException) {
                last = e
            }
        }
        throw last!!
    }

    private fun post(m: String, body: String): String {
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code in RETRY_CODES) {
                throw GeminiBusyException("Gemini sobrecarregado (HTTP $code em $m). Tente de novo daqui a pouco.")
            }
            if (code !in 200..299) {
                throw IOException("Gemini respondeu HTTP $code: ${resp.take(300)}")
            }
            return resp
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private val RETRY_CODES = setOf(429, 500, 502, 503, 504)
        private const val CALL_TOOL = "ligar_whatsapp"
        private val FALLBACK_MODELS = listOf("gemini-flash-lite-latest", "gemini-2.5-flash")

        const val SYSTEM = """Você é a Capri, uma assistente de voz que fala português do Brasil.
Suas respostas serão lidas em voz alta por um sintetizador de voz, então:
- responda de forma curta e natural, normalmente em 1 a 3 frases;
- nunca use markdown, listas, asteriscos, emojis, tabelas ou links;
- escreva números e siglas de um jeito fácil de falar;
- se a pergunta depender de informação em tempo real que você não tem (hora exata, clima, notícias de hoje), diga isso com sinceridade.
Quando pedirem para ligar ou fazer chamada de vídeo para alguém, use a função ligar_whatsapp.
Você só liga pelo WhatsApp; não existe ligação telefônica comum.
Seja simpática e direta."""
    }
}
