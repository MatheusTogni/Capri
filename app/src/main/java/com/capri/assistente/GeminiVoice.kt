package com.capri.assistente

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Gera a fala da Capri com o modelo de voz do Gemini (Interactions API). Devolve um WAV. */
class GeminiVoice(val apiKey: String, val voice: String) {

    fun synth(text: String): ByteArray {
        val textItem = JSONObject()
            .put("type", "text")
            .put("text", text)
            .put("annotations", JSONArray().put(
                JSONObject().put("type", "speech_metadata").put("style", STYLE)
            ))
        val body = JSONObject()
            .put("model", MODEL)
            .put("input", JSONArray().put(
                JSONObject().put("type", "user_input").put("content", JSONArray().put(textItem))
            ))
            .put("response_format", JSONObject().put("type", "audio"))
            .put("generation_config", JSONObject().put("speech_config",
                JSONArray().put(JSONObject().put("voice", voice))
            ))
            .toString()

        val conn = URL("https://generativelanguage.googleapis.com/v1beta/interactions")
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
            if (code == 429) throw QuotaException(retryAfterMs(resp))
            if (code !in 200..299) {
                throw IOException("Voz do Gemini respondeu HTTP $code: ${resp.take(300)}")
            }

            val audio = findAudio(JSONObject(resp))
                ?: throw IOException("Voz do Gemini não devolveu áudio: ${resp.take(300)}")
            val bytes = Base64.decode(audio, Base64.DEFAULT)
            return if (isWav(bytes)) bytes else wrapWav(bytes)
        } finally {
            conn.disconnect()
        }
    }

    /** Cota da voz acabou (o plano gratuito tem poucas falas por dia). */
    class QuotaException(val retryAfterMs: Long) : IOException("Cota da voz do Gemini acabou")

    /** Lê o "Please retry in 10h58m10s" da mensagem de erro; sem isso, espera 1 hora. */
    private fun retryAfterMs(resp: String): Long {
        val m = Regex("""retry in\s*(?:(\d+)h)?\s*(?:(\d+)m(?!s))?\s*(?:([\d.]+)s)?""", RegexOption.IGNORE_CASE).find(resp)
            ?: return 3_600_000L
        val h = m.groupValues[1].toLongOrNull() ?: 0
        val min = m.groupValues[2].toLongOrNull() ?: 0
        val s = m.groupValues[3].toDoubleOrNull() ?: 0.0
        val total = ((h * 3600 + min * 60) * 1000 + (s * 1000).toLong())
        return if (total > 0) total else 3_600_000L
    }

    /** O áudio vem em base64 em steps[].content[] com type "audio"; pega o último. */
    private fun findAudio(json: JSONObject): String? {
        var found: String? = null
        val steps = json.optJSONArray("steps") ?: return null
        for (i in 0 until steps.length()) {
            val content = steps.optJSONObject(i)?.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val c = content.optJSONObject(j) ?: continue
                if (c.optString("type") == "audio" && c.optString("data").isNotBlank()) found = c.getString("data")
            }
        }
        return found
    }

    private fun isWav(b: ByteArray) =
        b.size > 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WAVE"

    /** Se vier PCM cru (24 kHz, mono, 16 bits), coloca o cabeçalho WAV para o MediaPlayer tocar. */
    private fun wrapWav(pcm: ByteArray): ByteArray {
        val rate = 24_000
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size)
        }.array()
        return ByteArrayOutputStream(44 + pcm.size).apply { write(header); write(pcm) }.toByteArray()
    }

    companion object {
        const val MODEL = "gemini-3.8-flash-tts"
        const val STYLE = "Fale em português do Brasil, com sotaque brasileiro, de forma natural, simpática e calorosa."
        /** Vozes prontas do Gemini (nome → jeito da voz, segundo a documentação do Google). */
        val VOICES = linkedMapOf(
            "Kore" to "firme",
            "Aoede" to "leve",
            "Leda" to "jovem",
            "Zephyr" to "animada",
            "Callirrhoe" to "tranquila",
            "Despina" to "suave",
            "Achernar" to "macia",
            "Sulafat" to "calorosa",
            "Vindemiatrix" to "gentil",
            "Puck" to "alegre (masculina)",
            "Charon" to "informativa (masculina)",
            "Fenrir" to "empolgada (masculina)",
        )
    }
}
