package com.capri.assistente

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** Groq (API no formato da OpenAI): reserva quando o Gemini recusa. Bem rápido, com busca na web própria. */
class Groq(val apiKey: String) : AiProvider {

    override val label = "Groq"

    override fun chat(system: String, history: List<Pair<String, String>>): AiTurn {
        val messages = JSONArray().put(msg("system", system))
        for ((role, text) in history) messages.put(msg(if (role == "model") "assistant" else "user", text))
        val body = base(messages)
            .put("tools", tools())
            .put("tool_choice", "auto")

        val message = messageOf(post(body, readTimeout = 15_000))
        val call = message.optJSONArray("tool_calls")?.optJSONObject(0)?.optJSONObject("function")
        if (call != null) {
            val args = try { JSONObject(call.optString("arguments", "{}")) } catch (_: Exception) { JSONObject() }
            return AiTurn.Call(call.optString("name"), args)
        }
        return AiTurn.Text(clean(message.optString("content")))
    }

    /** Busca na web embutida do modelo (browser_search), feita no servidor do Groq. */
    override fun search(system: String, prompt: String): String {
        val messages = JSONArray().put(msg("system", system)).put(msg("user", prompt))
        val body = base(messages)
            .put("tools", JSONArray().put(JSONObject().put("type", "browser_search")))
            .put("tool_choice", "required")
        return clean(messageOf(post(body, readTimeout = 25_000)).optString("content"))
    }

    private fun msg(role: String, content: String) = JSONObject().put("role", role).put("content", content)

    // raciocínio "low": mais rápido e gasta menos cota; sem o raciocínio na resposta
    private fun base(messages: JSONArray) = JSONObject()
        .put("model", MODEL)
        .put("messages", messages)
        .put("reasoning_effort", "low")
        .put("include_reasoning", false)

    private fun messageOf(resp: String): JSONObject =
        JSONObject(resp).getJSONArray("choices").getJSONObject(0).getJSONObject("message")

    /** Tira as marcas de citação do gpt-oss (ex.: 【3†L10-L15】), que o sintetizador leria. */
    private fun clean(text: String) = text.replace(Regex("【[^】]*】"), "").trim()

    private fun tools() = JSONArray().apply {
        for (t in AiPrompts.TOOLS) put(JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", t.name)
                .put("description", t.description)
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject().apply {
                        t.props.forEach { put(it.name, JSONObject().put("type", it.type.lowercase()).put("description", it.description)) }
                    })
                    .put("required", JSONArray(t.required)))))
    }

    private fun post(body: JSONObject, readTimeout: Int): String {
        val t0 = SystemClock.elapsedRealtime()
        val conn = URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = readTimeout
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val firstByte = SystemClock.elapsedRealtime() - t0
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            CapriBus.debug("   ↳ groq $MODEL HTTP $code · 1º byte ${Timing.fmt(firstByte)} · " +
                "total ${Timing.fmt(SystemClock.elapsedRealtime() - t0)}")
            if (code !in 200..299) HttpErrors.fail(label, MODEL, code, resp)
            return resp
        } catch (e: SocketTimeoutException) {
            CapriBus.debug("   ↳ groq $MODEL sem resposta em ${Timing.fmt(SystemClock.elapsedRealtime() - t0)}")
            throw BusyException(BusyException.Kind.TIMEOUT, "Groq demorou demais para responder ($MODEL).")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        // único modelo de produção do Groq com funções e busca na web juntos (out/2026)
        const val MODEL = "openai/gpt-oss-120b"
    }
}
