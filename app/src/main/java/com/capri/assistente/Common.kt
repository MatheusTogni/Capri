package com.capri.assistente

import android.content.Context
import android.content.res.AssetManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

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

    /** Chave do Groq (opcional): responde quando o Gemini recusa. */
    var groqKey: String
        get() = sp.getString("groqKey", "") ?: ""
        set(v) = sp.edit().putString("groqKey", v.trim()).apply()

    var wakeWords: String
        get() = sp.getString("wakeWords", DEFAULT_WAKE) ?: DEFAULT_WAKE
        set(v) = sp.edit().putString("wakeWords", v).apply()

    /**
     * Nome da voz do celular (ex.: "pt-br-x-afs-local"). Vazio = voz padrão do celular.
     * Chave nova: a antiga "voice" guardava vozes do Gemini, que não existem mais no app.
     */
    var voice: String
        get() = sp.getString("phoneVoice", "") ?: ""
        set(v) = sp.edit().putString("phoneVoice", v.trim()).apply()

    /** Depois de responder, ouve mais uma vez sem precisar da palavra de ativação. */
    var conversationMode: Boolean
        get() = sp.getBoolean("conversationMode", true)
        set(v) = sp.edit().putBoolean("conversationMode", v).apply()

    /** Dizer a palavra de ativação enquanto ela fala faz ela parar e ouvir. */
    var bargeIn: Boolean
        get() = sp.getBoolean("bargeIn", true)
        set(v) = sp.edit().putBoolean("bargeIn", v).apply()

    /** Ao ser chamada, responde com uma frase da lista ("Fala aí!") em vez do bipe. */
    var greetOn: Boolean
        get() = sp.getBoolean("greetOn", true)
        set(v) = sp.edit().putBoolean("greetOn", v).apply()

    /** Frases de resposta ao ser chamada, uma por linha. */
    var greetings: String
        get() = sp.getString("greetings", DEFAULT_GREETINGS) ?: DEFAULT_GREETINGS
        set(v) = sp.edit().putString("greetings", v).apply()

    fun greetList(): List<String> = greetings.lines().map { it.trim() }.filter { it.isNotBlank() }

    /** Volume da Capri em % do máximo, independente do volume de mídia do celular. */
    var volume: Int
        get() = sp.getInt("capriVolume", 70)
        set(v) = sp.edit().putInt("capriVolume", v).apply()

    /** Tom, velocidade e efeitos da voz robótica (padrão: [RoboFx.CAPRI]). */
    var robo: RoboFx
        get() {
            val d = RoboFx.CAPRI
            return RoboFx(
                pitch = sp.getInt("roboPitch", d.pitch),
                rate = sp.getInt("roboRate", d.rate),
                metal = sp.getInt("roboMetal", d.metal),
                digital = sp.getInt("roboDigital", d.digital),
                echo = sp.getInt("roboEcho", d.echo),
            )
        }
        set(v) = sp.edit()
            .putInt("roboPitch", v.pitch).putInt("roboRate", v.rate)
            .putInt("roboMetal", v.metal).putInt("roboDigital", v.digital).putInt("roboEcho", v.echo)
            .apply()

    /** Mostra no histórico os detalhes técnicos (tempos, requisições, estado do serviço). */
    var debugLogs: Boolean
        get() = sp.getBoolean("debugLogs", false)
        set(v) { sp.edit().putBoolean("debugLogs", v).apply(); CapriBus.debugLogs = v }

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
        // curtas: ela só começa a ouvir a pergunta depois de terminar a frase
        val DEFAULT_GREETINGS = listOf(
            "Fala aí!", "E aí?", "Opa, diz aí.", "Pode falar.", "Tô aqui.", "Tô ouvindo.",
            "Manda.", "Diz aí.", "Sim?", "Que foi?", "Oi! Em que posso ajudar?", "Pois não?",
        ).joinToString("\n")
    }
}

/** "Barramento" simples entre o serviço e a tela (mesmo processo). */
object CapriBus {
    private val main = Handler(Looper.getMainLooper())
    private val lines = ArrayDeque<String>()

    // histórico salvo em arquivo: uma thread só, então leitura e gravações acontecem na ordem
    private val io = Executors.newSingleThreadExecutor()
    private var file: File? = null
    private val stamp = SimpleDateFormat("dd/MM HH:mm:ss", Locale("pt", "BR"))

    var status = "Desligada"; private set
    var heard = ""; private set
    @Volatile var running = false
    /** Tela principal aberta: aí o Android deixa o serviço abrir outro app sem permissão especial. */
    @Volatile var uiVisible = false

    var onChange: (() -> Unit)? = null

    /** Barrinha "ouvindo" visível (a BarActivity fecha quando vira false). */
    @Volatile var barVisible = false
    var onBar: (() -> Unit)? = null

    fun status(s: String) = main.post { status = s; onChange?.invoke(); onBar?.invoke() }
    fun bar(visible: Boolean) { barVisible = visible; main.post { onBar?.invoke() } }
    fun heard(s: String) = main.post { heard = s; onChange?.invoke() }
    fun log(s: String) = main.post {
        // uma linha por registro, para o arquivo continuar legível
        val line = stamp.format(Date()) + " " + s.replace(Regex("\\s*\\n\\s*"), " ")
        lines.addFirst(line)
        while (lines.size > SHOWN) lines.removeLast()
        onChange?.invoke()
        file?.let { f -> io.execute { append(f, line) } }
    }
    /** Opção "Mostrar logs de debug" das configurações. */
    @Volatile var debugLogs = false
    /** Detalhe técnico: só vai para o histórico com os logs de debug ligados. */
    fun debug(s: String) { if (debugLogs) log(s) }

    fun logText(): String = lines.joinToString("\n\n")

    /** Liga o histórico ao arquivo e carrega o que ficou de antes (só na primeira chamada). */
    fun init(ctx: Context) {
        debugLogs = Prefs(ctx).debugLogs // já vale para o que for registrado logo em seguida
        main.post { attachFile(ctx) }
    }

    private fun attachFile(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "historico.txt")
        file = f
        io.execute {
            val old = try { if (f.exists()) f.readLines(Charsets.UTF_8) else emptyList() } catch (_: Exception) { emptyList() }
            main.post {
                // tudo do arquivo é mais antigo do que o que foi registrado enquanto ele carregava
                old.asReversed().take(SHOWN).forEach { if (lines.size < SHOWN) lines.addLast(it) }
                onChange?.invoke()
            }
        }
    }

    /** Histórico inteiro (até [KEPT] linhas), do mais antigo para o mais novo. */
    fun fullHistory(callback: (String) -> Unit) {
        val f = file
        io.execute {
            val text = try { if (f != null && f.exists()) f.readText(Charsets.UTF_8) else "" } catch (_: Exception) { "" }
            main.post { callback(text) }
        }
    }

    fun clearHistory() = main.post {
        lines.clear()
        onChange?.invoke()
        file?.let { f -> io.execute { f.delete() } }
    }

    private fun append(f: File, line: String) {
        try {
            f.appendText(line + "\n", Charsets.UTF_8)
            if (f.length() > MAX_BYTES) f.writeText(f.readLines(Charsets.UTF_8).takeLast(KEPT).joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    private const val SHOWN = 40 // na tela
    private const val KEPT = 500 // no arquivo, depois de cortar
    private const val MAX_BYTES = 80_000L
}

/**
 * Quanto tempo cada etapa de uma pergunta levou, para achar onde a Capri demora.
 * Marcas que não aconteceram (ex.: pergunta ouvida pelo Vosk) só ficam fora do resumo.
 */
object Timing {
    private val marks = HashMap<String, Long>()

    @Synchronized fun reset() = marks.clear()

    /** Guarda só a primeira vez de cada marca. */
    @Synchronized fun mark(name: String) { marks.putIfAbsent(name, SystemClock.elapsedRealtime()) }

    /** "mic 650ms · silêncio 1.4s · ..." e zera as marcas; null se não houver o que mostrar. */
    @Synchronized fun summary(): String? {
        val parts = STEPS.mapNotNull { (label, from, to) ->
            val a = marks[from]; val b = marks[to]
            if (a != null && b != null) "$label ${fmt(b - a)}" else null
        }
        marks.clear()
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    fun fmt(ms: Long): String = if (ms < 1000) "${ms}ms" else String.format(Locale.US, "%.1fs", ms / 1000.0)

    // (rótulo, de, até)
    private val STEPS = listOf(
        Triple("mic", "inicio", "micPronto"),       // bipe + abrir o reconhecedor
        Triple("fala", "micPronto", "fimFala"),     // a pessoa falando
        Triple("silêncio", "fimFala", "pergunta"),  // reconhecedor esperando confirmar o fim
        Triple("resposta", "pergunta", "resposta"), // Gemini (ou comando local)
        Triple("voz", "resposta", "voz"),           // até o som começar a sair
        Triple("total", "inicio", "voz"),
    )
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
