package com.capri.assistente

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

class Gemini(val apiKey: String, val model: String) : AiProvider {

    override val label = "Gemini"

    override fun chat(system: String, history: List<Pair<String, String>>): AiTurn {
        val contents = JSONArray()
        for ((role, text) in history) {
            contents.put(
                JSONObject()
                    .put("role", role)
                    .put("parts", JSONArray().put(JSONObject().put("text", text)))
            )
        }
        val body = JSONObject()
            .put("system_instruction", systemObj(system))
            .put("contents", contents)
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", tools())))

        val parts = partsOf(postWithRetry(body))
        for (i in 0 until parts.length()) {
            val fc = parts.getJSONObject(i).optJSONObject("functionCall") ?: continue
            return AiTurn.Call(fc.optString("name"), fc.optJSONObject("args") ?: JSONObject())
        }
        return AiTurn.Text(textOf(parts))
    }

    /**
     * Pesquisa no Google. Tenta os modelos de SEARCH_MODELS em ordem: se um sair do ar
     * (o Google aposenta modelos antigos com HTTP 404) ou recusar, vai para o próximo.
     */
    override fun search(system: String, prompt: String): String {
        val body = JSONObject()
            .put("system_instruction", systemObj(system))
            .put("contents", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        var last: Exception? = null
        for (m in SEARCH_MODELS) {
            try {
                return textOf(partsOf(post(m, body, readTimeout = 25_000)))
            } catch (e: Exception) {
                CapriBus.log("⚠️ Pesquisa falhou em $m: ${e.message}")
                last = e
            }
        }
        throw last!!
    }

    private fun systemObj(text: String) =
        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun partsOf(resp: String): JSONArray =
        JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")

    private fun textOf(parts: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (!p.optBoolean("thought", false)) sb.append(p.optString("text"))
        }
        return sb.toString().trim()
    }

    private fun tools() = JSONArray().apply {
        for (t in AiPrompts.TOOLS) put(JSONObject()
            .put("name", t.name)
            .put("description", t.description)
            .put("parameters", JSONObject()
                .put("type", "OBJECT")
                .put("properties", JSONObject().apply {
                    t.props.forEach { put(it.name, JSONObject().put("type", it.type).put("description", it.description)) }
                })
                .put("required", JSONArray(t.required))))
    }

    /**
     * Tenta o modelo escolhido e, se ele recusar (cota, sobrecarga, demora), cai para os
     * modelos reserva. Outros erros sobem na hora.
     */
    private fun postWithRetry(body: JSONObject): String {
        val attempts = listOf(model) + FALLBACK_MODELS.filter { it != model }
        var last: BusyException? = null
        for ((i, m) in attempts.withIndex()) {
            if (i > 0) Thread.sleep(300L)
            try {
                val resp = post(m, body)
                if (m != model) CapriBus.debug("↪️ $model indisponível (${last?.message}), respondi com $m")
                return resp
            } catch (e: BusyException) {
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
            if (code == 400 && resp.contains("thinking", ignoreCase = true)) throw BadThinkingConfig()
            if (code !in 200..299) HttpErrors.fail(label, m, code, resp)
            return resp
        } catch (e: SocketTimeoutException) {
            CapriBus.debug("   ↳ $m sem resposta em ${Timing.fmt(SystemClock.elapsedRealtime() - t0)}")
            throw BusyException(BusyException.Kind.TIMEOUT, "Gemini demorou demais para responder ($m).")
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
        private val SEARCH_MODELS = listOf("gemini-3.8-flash", "gemini-flash-lite-latest")
        private val FALLBACK_MODELS = listOf("gemini-flash-lite-latest", "gemini-3.8-flash")
    }
}
