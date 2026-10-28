package com.capri.assistente

import android.content.Context
import java.io.File

/** O que a Capri fala depois de mexer na lista; pode deixar uma pergunta pendente. */
class TodoReply(
    val text: String,
    val choices: List<String>? = null, // "qual delas?" aguardando resposta
    val confirmClear: Boolean = false, // "tem certeza?" aguardando sim/não
)

/** Lista de tarefas falada: um arquivo de texto no celular, uma tarefa por linha. */
class TodoStore(ctx: Context) {
    private val file = File(ctx.filesDir, "tarefas.txt")

    fun all(): List<String> = try {
        if (file.exists()) file.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotBlank() } else emptyList()
    } catch (_: Exception) { emptyList() }

    private fun save(items: List<String>) = file.writeText(items.joinToString("\n"), Charsets.UTF_8)

    /** Índices das tarefas que batem com [query]: igual, ou com todas as palavras dela (ex.: "pão"). */
    fun find(query: String): List<Int> {
        val items = all()
        val q = normalize(query)
        items.indices.filter { normalize(items[it]) == q }.let { if (it.isNotEmpty()) return it }
        val words = q.split(" ").filter { it.isNotBlank() && it !in STOP }
        if (words.isEmpty()) return emptyList()
        return items.indices.filter { i ->
            val w = normalize(items[i]).split(" ")
            words.all { qw -> w.any { it.startsWith(qw) } }
        }
    }

    fun run(c: Command.Todo): TodoReply {
        val items = all()
        return when (c) {
            is Command.Todo.Add -> {
                if (items.any { normalize(it) == normalize(c.text) }) return TodoReply("${c.text} já está na lista.")
                save(items + c.text)
                TodoReply("Anotado: ${c.text}. Agora você tem ${count(items.size + 1)}.")
            }
            Command.Todo.Read -> when (items.size) {
                0 -> TodoReply("Sua lista está vazia.")
                1 -> TodoReply("Você tem 1 tarefa: ${items[0]}.")
                else -> TodoReply("Você tem ${count(items.size)}: " +
                    joinPt(items.mapIndexed { i, t -> "${i + 1}, $t" }, sep = "; ") + ".")
            }
            Command.Todo.Count ->
                TodoReply(if (items.isEmpty()) "Sua lista está vazia." else "Você tem ${count(items.size)}.")
            is Command.Todo.Remove -> when {
                items.isEmpty() -> TodoReply("Sua lista está vazia.")
                c.number > 0 ->
                    if (c.number <= items.size) removeAt(items, c.number - 1)
                    else TodoReply("Não tem tarefa número ${c.number}. Sua lista tem ${count(items.size)}.")
                else -> {
                    val found = find(c.query)
                    when (found.size) {
                        0 -> TodoReply("Não achei ${c.query} na lista.")
                        1 -> removeAt(items, found[0])
                        else -> {
                            val options = found.take(4).map { items[it] }
                            TodoReply("Tem ${found.size} parecidas: ${joinPt(options)}. Qual delas?", choices = options)
                        }
                    }
                }
            }
            Command.Todo.Clear -> when (items.size) {
                0 -> TodoReply("Sua lista já está vazia.")
                1 -> TodoReply("Tem certeza? Vou apagar a única tarefa: ${items[0]}.", confirmClear = true)
                else -> TodoReply("Tem certeza? Vou apagar as ${items.size} tarefas.", confirmClear = true)
            }
        }
    }

    /** Resposta ao "qual delas?": pela posição ("a segunda") ou pelo nome. */
    fun removeChoice(answer: String, options: List<String>): TodoReply {
        val words = normalize(answer).split(" ")
        val byPosition = words.firstNotNullOfOrNull { TodoParser.ORDINAL[it] ?: it.toIntOrNull() }
            ?.takeIf { it in 1..options.size }?.let { options[it - 1] }
        val pick = byPosition ?: options
            .map { o -> o to normalize(o).split(" ").count { it !in STOP && it in words } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .let { if (it.size == 1 || (it.size > 1 && it[0].second > it[1].second)) it[0].first else null }
            ?: return TodoReply("Não entendi qual delas. Pode pedir de novo.")
        val items = all()
        val i = items.indexOf(pick)
        return if (i < 0) TodoReply("Essa tarefa já não está mais na lista.") else removeAt(items, i)
    }

    fun confirmClear(answer: String): TodoReply {
        val w = normalize(answer).split(" ")
        if ("nao" in w || w.none { it in YES }) return TodoReply("Tá bom, não apaguei nada.")
        save(emptyList())
        return TodoReply("Pronto, apaguei tudo. Sua lista está vazia.")
    }

    private fun removeAt(items: List<String>, i: Int): TodoReply {
        val left = items.size - 1
        save(items.filterIndexed { j, _ -> j != i })
        return TodoReply(when (left) {
            0 -> "Pronto, tirei ${items[i]}. Sua lista ficou vazia."
            1 -> "Pronto, tirei ${items[i]} da lista. Falta 1."
            else -> "Pronto, tirei ${items[i]} da lista. Faltam $left."
        })
    }

    private fun count(n: Int) = if (n == 1) "1 tarefa" else "$n tarefas"

    /** "a, b e c" */
    private fun joinPt(parts: List<String>, sep: String = ", "): String =
        if (parts.size <= 1) parts.joinToString() else parts.dropLast(1).joinToString(sep) + " e " + parts.last()

    companion object {
        private val STOP = setOf("a", "o", "as", "os", "de", "da", "do", "das", "dos", "e", "pra", "para", "com", "na", "no", "um", "uma")
        private val YES = setOf("sim", "pode", "confirmo", "confirma", "isso", "claro", "apaga", "apague", "quero", "certeza", "manda", "bora", "tenho")
    }
}

/**
 * Pedidos diretos da lista, entendidos sem o Gemini. Pega a tarefa do texto original
 * (com acentos) usando as palavras normalizadas para achar onde ela começa e termina.
 */
object TodoParser {

    private val ADD = setOf("adiciona", "adicione", "adicionar", "coloca", "coloque", "colocar", "bota", "bote", "botar",
        "poe", "ponha", "inclui", "inclua", "incluir", "acrescenta", "acrescente", "anota", "anote", "anotar",
        "salva", "salve", "guarda", "guarde", "cadastra", "cadastre")
    private val REMOVE = setOf("tira", "tire", "tirar", "remove", "remova", "remover", "apaga", "apague", "apagar",
        "risca", "risque", "riscar", "exclui", "exclua", "excluir", "deleta", "delete", "conclui", "concluir",
        "terminei", "fiz")
    private val CLEAR = setOf("limpa", "limpe", "limpar", "zera", "zere", "zerar", "esvazia", "esvazie", "esvaziar",
        "apaga", "apague", "apagar")
    private val LIST = setOf("lista", "tarefa", "tarefas", "afazeres")
    // palavras em volta de "lista" ("na minha lista de tarefas") que não fazem parte da tarefa
    private val GLUE = setOf("na", "no", "a", "o", "as", "os", "em", "minha", "minhas", "meu", "meus", "da", "do",
        "das", "dos", "de", "pra", "para", "nova", "uma", "um", "lista", "tarefa", "tarefas", "afazeres",
        "compras", "mercado", "item", "itens")
    private val ALL = setOf("tudo", "todas", "todos", "toda", "inteira", "por", "favor")
    private val LEAD = setOf("que", "a", "o", "as", "os", "de", "da", "do", "pra", "para", "e", "ja")
    private val TRAIL = setOf("por", "favor", "ai", "pfv")
    private val READ = setOf("le", "leia", "ler", "fala", "fale", "diz", "diga", "mostra", "mostre", "quais", "qual",
        "que", "tem", "ve", "ver")
    // ler/contar só se a frase não tiver mais nada ("quais filmes estão na lista do oscar" vai pro Gemini)
    private val QUESTION_OK = GLUE + READ + setOf("sao", "e", "esta", "estao", "ai", "ainda", "pendentes", "me", "eu",
        "tenho", "ha", "quantas", "quantos", "coisas", "por", "favor", "minha", "agora", "hoje")

    val ORDINAL = mapOf("primeira" to 1, "primeiro" to 1, "segunda" to 2, "segundo" to 2, "terceira" to 3,
        "terceiro" to 3, "quarta" to 4, "quarto" to 4, "quinta" to 5, "quinto" to 5, "sexta" to 6, "sexto" to 6,
        "setima" to 7, "setimo" to 7, "oitava" to 8, "oitavo" to 8, "nona" to 9, "nono" to 9, "decima" to 10, "decimo" to 10)
    private val NUMBER = ORDINAL + mapOf("um" to 1, "uma" to 1, "dois" to 2, "duas" to 2, "tres" to 3, "quatro" to 4,
        "cinco" to 5, "seis" to 6, "sete" to 7, "oito" to 8, "nove" to 9, "dez" to 10)

    fun parse(spoken: String, store: TodoStore): Command.Todo? {
        var pairs = spoken.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(" ")
            .map { it to normalize(it) }
            .filter { it.second.isNotBlank() && ' ' !in it.second }
        if (pairs.firstOrNull()?.second == "capri") pairs = pairs.drop(1)
        val o = pairs.map { it.first }
        val n = pairs.map { it.second }
        if (n.isEmpty()) return null
        val hasList = n.any { it in LIST }

        if (hasList && n.any { it == "quantas" || it == "quantos" } && n.all { it in QUESTION_OK }) {
            return Command.Todo.Count
        }

        // "limpa a lista", "apaga todas as tarefas": verbo + lista, sem nenhuma tarefa no meio
        val clearAt = n.indexOfFirst { it in CLEAR }
        if (hasList && clearAt >= 0 && n.drop(clearAt + 1).all { it in GLUE || it in ALL }) return Command.Todo.Clear

        val addAt = n.indexOfFirst { it in ADD }
        if (hasList && addAt >= 0) {
            val item = itemText(o, n, addAt + 1)
            return if (item.isBlank()) null else Command.Todo.Add(item)
        }

        val removeAt = n.indexOfFirst { it in REMOVE }
        if (removeAt >= 0) {
            val rest = n.drop(removeAt + 1)
            // pelo número: "tira a tarefa 2", "remove o item número três", "risca a segunda tarefa"
            val k = rest.indexOfLast { it == "tarefa" || it == "item" || it == "numero" }
            if (k >= 0) {
                val num = rest.getOrNull(k + 1)?.let { it.toIntOrNull() ?: NUMBER[it] }
                    ?: rest.getOrNull(k - 1)?.let { ORDINAL[it] }
                if (num != null && num > 0) return Command.Todo.Remove("", num)
            }
            val item = itemText(o, n, removeAt + 1)
            if (item.isBlank()) return null
            // sem falar "lista" ("risca pão", "já fiz X"), só se a tarefa existir; senão é outro pedido
            return if (hasList || store.find(item).isNotEmpty()) Command.Todo.Remove(item) else null
        }

        if (hasList && n.any { it in READ } && n.all { it in QUESTION_OK }) return Command.Todo.Read
        return null
    }

    /** A tarefa: antes de "na lista" ("adiciona pão na lista") ou depois ("adiciona na lista pão"). */
    private fun itemText(o: List<String>, n: List<String>, start: Int): String {
        val k = (start until n.size).firstOrNull { n[it] == "lista" }
            ?: (start until n.size).firstOrNull { n[it] in LIST }
            ?: return clean(o, n, start, n.size)
        var ls = k
        while (ls > start && n[ls - 1] in GLUE) ls--
        // "anota fazer a tarefa de matemática": "tarefa" no meio da frase faz parte da tarefa
        if (n[k] != "lista" && ls != start) return clean(o, n, start, n.size)
        var le = k + 1
        while (le < n.size && n[le] in GLUE) le++
        return clean(o, n, start, ls).ifBlank { clean(o, n, le, n.size) }
    }

    private fun clean(o: List<String>, n: List<String>, from: Int, to: Int): String {
        var a = from
        var b = to
        while (a < b && n[a] in LEAD) a++
        while (b > a && n[b - 1] in TRAIL) b--
        return o.subList(a, b).joinToString(" ")
    }
}
