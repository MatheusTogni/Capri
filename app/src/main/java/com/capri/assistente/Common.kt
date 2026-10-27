package com.capri.assistente

import android.content.Context
import android.content.res.AssetManager
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.text.Normalizer

/** Remove acentos e deixa minúsculo, para comparar palavras. */
fun normalize(s: String): String =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("capri", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(v) = sp.edit().putString("apiKey", v.trim()).apply()

    var model: String
        get() = sp.getString("model", DEFAULT_MODEL)?.ifBlank { DEFAULT_MODEL } ?: DEFAULT_MODEL
        set(v) = sp.edit().putString("model", v.trim()).apply()

    var wakeWords: String
        get() = sp.getString("wakeWords", DEFAULT_WAKE) ?: DEFAULT_WAKE
        set(v) = sp.edit().putString("wakeWords", v).apply()

    /** Voz do Gemini (ex.: "Kore"). Vazio = usar a voz do próprio celular. */
    var voice: String
        get() = sp.getString("voice", DEFAULT_VOICE) ?: DEFAULT_VOICE
        set(v) = sp.edit().putString("voice", v.trim()).apply()

    fun wakeList(): List<String> =
        wakeWords.split(",").map { normalize(it) }.filter { it.isNotBlank() }

    /**
     * Gramática do Vosk para a escuta da palavra de ativação: ele só pode escolher entre
     * as palavras de ativação ou "[unk]" (qualquer outra coisa). Sem isso o modelo prefere
     * palavras comuns ("capa", "catarina") a nomes como "capri".
     * Mantém os acentos, porque o vocabulário do modelo tem as palavras acentuadas.
     */
    fun wakeGrammar(): String {
        val arr = org.json.JSONArray()
        wakeWords.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
            .forEach { arr.put(it) }
        arr.put("[unk]")
        return arr.toString()
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-flash-latest"
        const val DEFAULT_WAKE = "capri, cabri, capre, kapri, caprin"
        const val DEFAULT_VOICE = "Kore"
    }
}

/** "Barramento" simples entre o serviço e a tela (mesmo processo). */
object CapriBus {
    private val main = Handler(Looper.getMainLooper())
    private val lines = ArrayDeque<String>()

    var status = "Desligada"; private set
    var heard = ""; private set
    @Volatile var running = false

    var onChange: (() -> Unit)? = null

    fun status(s: String) = main.post { status = s; onChange?.invoke() }
    fun heard(s: String) = main.post { heard = s; onChange?.invoke() }
    fun log(s: String) = main.post {
        lines.addFirst(s)
        while (lines.size > 40) lines.removeLast()
        onChange?.invoke()
    }
    fun logText(): String = lines.joinToString("\n\n")
}

/** Copia o modelo do Vosk de dentro do APK para a memória do app (só na primeira vez). */
object ModelInstaller {
    private const val VERSION = "v1"

    fun ensure(ctx: Context): File {
        val target = File(ctx.filesDir, "model")
        val marker = File(target, ".copiado-$VERSION")
        if (marker.exists()) return target
        target.deleteRecursively()
        target.mkdirs()
        val top = ctx.assets.list("model")
        if (top.isNullOrEmpty()) throw IllegalStateException("O modelo de voz não veio dentro do APK")
        copy(ctx.assets, "model", target)
        marker.createNewFile()
        return target
    }

    private fun copy(am: AssetManager, path: String, dest: File) {
        val children = am.list(path) ?: emptyArray()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            am.open(path).use { input -> FileOutputStream(dest).use { input.copyTo(it) } }
            return
        }
        dest.mkdirs()
        for (c in children) copy(am, "$path/$c", File(dest, c))
    }
}
