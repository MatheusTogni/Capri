package com.capri.assistente

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Groq (API no formato da OpenAI), só no plano grátis: quando a cota de um modelo acaba, ele é deixado de
 * lado até voltar e a Capri tenta o modelo reserva; se os dois estiverem sem cota, não faz pedido nenhum.
 *
 * [models]: o principal primeiro, depois a reserva. Os dois modelos de [CHOICES] têm função e busca na web.
 */
class Groq(val apiKey: String, models: List<String>) : AiProvider {

    override val label = "Groq"

    private val models = models.filter { it.isNotBlank() }.distinct().ifEmpty { listOf(FAST) }

    /** Até quando cada modelo está de molho (SystemClock.elapsedRealtime), depois de recusar um pedido. */
    private val blockedUntil = HashMap<String, Long>()

    /** false = o Groq recusou função + busca na web na mesma chamada; volta a decidir e pesquisar em duas. */
    @Volatile private var mixedTools = true

    override fun chat(system: String, history: List<Pair<String, String>>): AiTurn {
        val messages = JSONArray().put(msg("system", system))
        for ((role, text) in history) messages.put(msg(if (role == "model") "assistant" else "user", text))
        return onModels(models) { chatOn(it, messages) }
    }

    /**
     * Uma chamada só: as funções do celular e a busca na web vão juntas e o próprio modelo escolhe.
     * Se for busca, o Groq pesquisa no servidor e já devolve a resposta pronta.
     */
    private fun chatOn(model: String, messages: JSONArray): AiTurn {
        val oneHop = mixedTools && canSearch(model)
        val resp = try {
            post(model, withTools(model, messages, oneHop), readTimeout = if (oneHop) 25_000 else 15_000)
        } catch (e: ApiException) {
            if (!oneHop || e.code != 400) throw e
            // misturar função e busca não está na documentação: se o Groq recusou, usa duas chamadas.
            // "tool_use_failed" é só o modelo que montou a chamada errado; nesse caso tenta sem a busca só desta vez
            if (HttpErrors.errorCode(e.body) != "tool_use_failed") mixedTools = false
            CapriBus.debug("↩️ $model recusou função + busca juntas; usando duas chamadas")
            post(model, withTools(model, messages, false), readTimeout = 15_000)
        }

        val message = messageOf(resp)
        if ((message.optJSONArray("executed_tools")?.length() ?: 0) > 0) CapriBus.log("🔎 Pesquisei na web")
        val call = message.optJSONArray("tool_calls")?.optJSONObject(0)?.optJSONObject("function")
        if (call != null) {
            val name = call.optString("name")
            // a busca veio como função comum: o Groq não rodou sozinho, então cai no jeito de duas chamadas
            if (name == BROWSER_SEARCH) return AiTurn.Call(AiPrompts.SEARCH_TOOL, JSONObject())
            val args = try { JSONObject(call.optString("arguments", "{}")) } catch (_: Exception) { JSONObject() }
            return AiTurn.Call(name, args)
        }
        return AiTurn.Text(clean(message.optString("content")))
    }

    /** Busca na web embutida do modelo (browser_search), feita no servidor do Groq. Só com o modelo mais forte primeiro. */
    override fun search(system: String, prompt: String): String {
        val candidates = models.filter { canSearch(it) }.sortedByDescending { it == SMART }.ifEmpty { listOf(SMART) }
        val messages = JSONArray().put(msg("system", system)).put(msg("user", prompt))
        return onModels(candidates) { m ->
            val body = base(m, messages)
                .put("tools", JSONArray().put(JSONObject().put("type", BROWSER_SEARCH)))
                .put("tool_choice", "required")
            clean(messageOf(post(m, body, readTimeout = 25_000)).optString("content"))
        }
    }

    // ---------- reserva e cota ----------

    /**
     * Roda [f] no primeiro modelo de [candidates] que aceitar. Só passa para o próximo quando a recusa é
     * rápida (cota, servidor cheio, modelo aposentado): demora (timeout) não dobra a espera, só deixa o
     * modelo de molho para a próxima pergunta. Se todos estiverem sem cota, não faz nenhuma requisição.
     */
    private fun <T> onModels(candidates: List<String>, f: (String) -> T): T {
        var last: Exception? = null
        for (m in candidates) {
            if (blockedFor(m) > 0) continue
            try {
                val r = f(m)
                if (m != candidates.first()) CapriBus.debug("↪️ ${candidates.first()} indisponível, respondi com $m")
                return r
            } catch (e: BusyException) {
                last = e
                CapriBus.debug("   ↳ $m recusou: ${e.message}")
                when (e.kind) {
                    BusyException.Kind.QUOTA -> block(m, if (e.retryMs > 0) e.retryMs else 60_000L, 2_000L, 24 * HOUR)
                    BusyException.Kind.OVERLOADED -> block(m, 10_000L)
                    BusyException.Kind.TIMEOUT -> { block(m, 30_000L); throw e }
                }
            } catch (e: ApiException) {
                if (e.code != 404) throw e
                last = e // modelo aposentado: tenta o outro e não insiste neste por uma hora
                block(m, HOUR)
            }
        }
        // todos de molho (alguns já antes desta pergunta): quanto falta para o primeiro voltar
        val wait = candidates.minOf { blockedFor(it) }
        val e = last
        if (e is BusyException && e.kind != BusyException.Kind.QUOTA) throw e
        if (e is ApiException) throw e
        throw BusyException(BusyException.Kind.QUOTA,
            "Groq: cota grátis esgotada em ${candidates.joinToString()}. Volta em ${Timing.fmt(wait)}.", wait)
    }

    private fun block(model: String, ms: Long, min: Long = 0, max: Long = Long.MAX_VALUE) {
        synchronized(blockedUntil) { blockedUntil[model] = SystemClock.elapsedRealtime() + ms.coerceIn(min, max) }
    }

    /** Quanto falta (ms) para o [model] voltar a aceitar pedidos; 0 = pode usar. */
    private fun blockedFor(model: String): Long = synchronized(blockedUntil) {
        ((blockedUntil[model] ?: 0L) - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    }

    // ---------- corpo do pedido ----------

    private fun canSearch(model: String) = model.startsWith("openai/gpt-oss")

    private fun msg(role: String, content: String) = JSONObject().put("role", role).put("content", content)

    // raciocínio "low": mais rápido e gasta menos cota; sem o raciocínio na resposta
    private fun base(model: String, messages: JSONArray) = JSONObject()
        .put("model", model)
        .put("messages", messages)
        .put("reasoning_effort", "low")
        .put("include_reasoning", false)

    private fun withTools(model: String, messages: JSONArray, withSearch: Boolean) = base(model, messages)
        .put("tools", tools(withSearch))
        .put("tool_choice", "auto")

    private fun messageOf(resp: String): JSONObject =
        JSONObject(resp).getJSONArray("choices").getJSONObject(0).getJSONObject("message")

    /** Tira as marcas de citação do gpt-oss (ex.: 【3†L10-L15】), que o sintetizador leria. */
    private fun clean(text: String) = text.replace(Regex("【[^】]*】"), "").trim()

    /** As funções do celular; com [withSearch], a busca na web embutida no lugar da função de pesquisa. */
    private fun tools(withSearch: Boolean) = JSONArray().apply {
        for (t in AiPrompts.TOOLS) {
            if (withSearch && t.name == AiPrompts.SEARCH_TOOL) continue
            put(JSONObject()
                .put("type", "function")
                .put("function", JSONObject()
                    .put("name", t.name)
                    .put("description", t.description)
                    .put("parameters", JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject().apply {
                            t.props.forEach { put(it.name, JSONObject().put("type", it.type).put("description", it.description)) }
                        })
                        .put("required", JSONArray(t.required)))))
        }
        if (withSearch) put(JSONObject().put("type", BROWSER_SEARCH))
    }

    private fun post(model: String, body: JSONObject, readTimeout: Int): String {
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
            CapriBus.debug("   ↳ groq $model HTTP $code · 1º byte ${Timing.fmt(firstByte)} · " +
                "total ${Timing.fmt(SystemClock.elapsedRealtime() - t0)}")
            if (code !in 200..299) HttpErrors.fail(label, model, code, resp, conn.getHeaderField("retry-after"))
            return resp
        } catch (e: SocketTimeoutException) {
            CapriBus.debug("   ↳ groq $model sem resposta em ${Timing.fmt(SystemClock.elapsedRealtime() - t0)}")
            throw BusyException(BusyException.Kind.TIMEOUT, "Groq demorou demais para responder ($model).")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** Os dois únicos modelos do Groq com função e busca na web juntos (documentação, nov/2026). */
        const val FAST = "openai/gpt-oss-20b"
        const val SMART = "openai/gpt-oss-120b"

        /** (id, nome na lista das configurações). */
        val CHOICES = listOf(
            FAST to "gpt-oss-20b · mais rápido (recomendado)",
            SMART to "gpt-oss-120b · mais esperto, um pouco mais lento",
        )

        private const val BROWSER_SEARCH = "browser_search"
        private const val HOUR = 3_600_000L
    }
}
