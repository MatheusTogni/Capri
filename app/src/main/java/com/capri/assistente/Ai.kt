package com.capri.assistente

import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Recusa passageira do provedor (cota, servidor cheio ou demora): vale tentar outro modelo.
 * [retryMs] é quanto o servidor pediu para esperar (0 = não disse).
 */
class BusyException(val kind: Kind, msg: String, val retryMs: Long = 0) : IOException(msg) {
    enum class Kind { QUOTA, OVERLOADED, TIMEOUT }
}

/** Erro que não melhora tentando de novo agora (chave, modelo, pedido). [body] é a resposta crua do servidor. */
class ApiException(val code: Int, msg: String, val body: String = "") : IOException(msg)

/** Um provedor de IA (hoje só o Groq): recebe a conversa e devolve texto ou um pedido de função. */
interface AiProvider {
    val label: String

    /** [history]: pares (role, texto) com role "user" ou "model". */
    fun chat(system: String, history: List<Pair<String, String>>): AiTurn

    /** Responde [prompt] pesquisando na web. */
    fun search(system: String, prompt: String): String
}

sealed class AiTurn {
    data class Text(val text: String) : AiTurn()
    data class Call(val name: String, val args: JSONObject) : AiTurn()
}

/** Tradução dos códigos HTTP para o histórico (o 429 é cota da chave, não servidor lotado). */
object HttpErrors {
    fun busyKind(code: Int): BusyException.Kind? = when (code) {
        429 -> BusyException.Kind.QUOTA
        500, 502, 503, 504 -> BusyException.Kind.OVERLOADED
        else -> null
    }

    fun what(code: Int): String = when (code) {
        400 -> "pedido recusado"
        401 -> "chave inválida"
        403 -> "chave sem permissão"
        404 -> "modelo não encontrado (nome errado ou aposentado)"
        413 -> "pedido grande demais"
        429 -> "cota da chave esgotada"
        500 -> "erro interno do servidor"
        502, 504 -> "servidor fora do ar"
        503 -> "servidor sobrecarregado"
        else -> "erro"
    }

    /** Mensagem do corpo de erro ({"error":{"message":...}}). */
    fun detail(resp: String): String {
        val err = try { JSONObject(resp).optJSONObject("error") } catch (_: Exception) { null }
            ?: return resp.take(200)
        return err.optString("message").take(200)
    }

    /** O código do erro ({"error":{"code":...}}), ex.: "tool_use_failed"; vazio se não houver. */
    fun errorCode(resp: String): String =
        try { JSONObject(resp).optJSONObject("error")?.optString("code").orEmpty() } catch (_: Exception) { "" }

    /**
     * Quanto esperar até a cota voltar, em ms: o cabeçalho retry-after (segundos) ou o "try again in 1m5.2s"
     * da mensagem. 0 = o servidor não disse.
     */
    fun retryMs(retryAfter: String?, resp: String): Long {
        retryAfter?.trim()?.toDoubleOrNull()?.takeIf { it > 0 }?.let { return (it * 1000).toLong() }
        val text = try { JSONObject(resp).optJSONObject("error")?.optString("message") } catch (_: Exception) { null } ?: resp
        val wait = Regex("try again in ([0-9a-z.]+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1) ?: return 0
        var ms = 0.0
        for (m in Regex("(\\d+(?:\\.\\d+)?)(ms|h|m|s)").findAll(wait)) {
            val n = m.groupValues[1].toDouble()
            ms += when (m.groupValues[2]) {
                "h" -> n * 3_600_000
                "m" -> n * 60_000
                "s" -> n * 1_000
                else -> n
            }
        }
        return ms.toLong()
    }

    /** Lança a exceção certa para um código fora de 2xx. */
    fun fail(provider: String, model: String, code: Int, resp: String, retryAfter: String? = null): Nothing {
        val msg = "$provider: ${what(code)} (HTTP $code em $model). ${detail(resp)}".trim()
        val kind = busyKind(code)
        if (kind != null) throw BusyException(kind, msg, if (code == 429) retryMs(retryAfter, resp) else 0)
        throw ApiException(code, msg, resp)
    }

    /** O que a Capri fala quando nenhum modelo conseguiu responder. */
    fun spoken(e: Exception): String = when {
        e is BusyException && e.kind == BusyException.Kind.QUOTA -> quotaSpoken(e.retryMs)
        e is BusyException && e.kind == BusyException.Kind.TIMEOUT ->
            "A inteligência artificial demorou demais para responder. Tenta de novo."
        e is BusyException -> "O servidor da inteligência artificial está sobrecarregado agora. Tenta de novo daqui a pouco."
        e is ApiException && (e.code == 401 || e.code == 403) ->
            "A chave da inteligência artificial não funcionou. Confere nas configurações."
        e is ApiException && e.code == 404 -> "O modelo configurado não existe mais. Troca nas configurações."
        else -> "Deu um erro ao falar com a inteligência artificial. Dá uma olhada no aplicativo."
    }

    /** Cota esgotada: diz em quanto tempo volta, quando o servidor informou. */
    private fun quotaSpoken(ms: Long): String {
        fun unit(n: Long, one: String, many: String) = "$n ${if (n == 1L) one else many}"
        return when {
            ms <= 0 -> "Acabou a cota grátis da inteligência artificial por agora. Tenta de novo daqui a pouco."
            ms < 90_000 -> "Muitas perguntas em pouco tempo. Tenta de novo em ${unit((ms + 999) / 1_000, "segundo", "segundos")}."
            ms < 90 * 60_000L -> "Acabou a cota grátis da inteligência artificial por agora. " +
                "Volta em cerca de ${unit((ms + 59_999) / 60_000, "minuto", "minutos")}."
            else -> "Acabou a cota grátis da inteligência artificial por hoje. " +
                "Volta em cerca de ${unit((ms + 3_599_999) / 3_600_000, "hora", "horas")}."
        }
    }
}

/** Uma função que a IA pode pedir. Tipos no formato do JSON Schema: string, boolean, integer. */
class ToolDef(val name: String, val description: String, val required: List<String>, val props: List<Prop>) {
    class Prop(val name: String, val type: String, val description: String)
}

object AiPrompts {
    const val CALL_TOOL = "ligar_whatsapp"
    const val SEARCH_TOOL = "pesquisar_na_web"

    /** Jeito de falar (vale também para a pesquisa). */
    const val STYLE = """Você é a Capri, uma assistente de voz que fala português do Brasil.
Suas respostas serão lidas em voz alta por um sintetizador de voz, então:
- responda de forma curta e natural, normalmente em 1 a 3 frases;
- nunca use markdown, listas, asteriscos, emojis, tabelas ou links;
- escreva números e siglas de um jeito fácil de falar;
- para data e hora, use o relógio do celular informado no fim destas instruções.
Seja simpática e direta."""

    /** O que cada função faz já está na descrição dela; aqui só o que ajuda a escolher entre elas. */
    const val ACTIONS = """Você age no celular usando as funções disponíveis; se nenhuma servir, só responda.
- você só liga pelo WhatsApp (não existe ligação telefônica comum);
- para clima, notícias, resultados de jogos ou qualquer coisa atual, pesquise na web;
- lembretes ("me lembra de X às 20h") são alarmes; coisas para não esquecer vão na lista de tarefas."""

    const val SEARCH = "Pesquise na web para responder com informação atual."

    fun clock(): String =
        "Agora é " + SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy, HH:mm", Locale("pt", "BR")).format(Date()) +
            " (relógio do celular)."

    private fun p(name: String, type: String, description: String) = ToolDef.Prop(name, type, description)

    /** Ações que a IA pode pedir em vez de responder com texto. */
    val TOOLS = listOf(
        ToolDef(CALL_TOOL, "Faz uma ligação de voz ou chamada de vídeo pelo WhatsApp para um contato da agenda do celular.",
            listOf("contato"), listOf(
                p("contato", "string", "Nome do contato do jeito que o usuário falou, ex.: \"João\", \"mãe\", \"Ana da academia\"."),
                p("video", "boolean", "true se o usuário pediu chamada de vídeo; false para ligação de voz."))),
        ToolDef(SEARCH_TOOL, "Pesquisa na web informação atual: clima, notícias, resultados de jogos, preços, eventos recentes.",
            listOf("consulta"), listOf(
                p("consulta", "string", "O que pesquisar, em poucas palavras."))),
        ToolDef("lanterna", "Liga ou desliga a lanterna do celular.", listOf("ligar"), listOf(
            p("ligar", "boolean", "true para ligar, false para desligar."))),
        ToolDef("volume", "Muda o volume de mídia do celular.", listOf("acao"), listOf(
            p("acao", "string", "Uma destas: aumentar, diminuir, maximo, mudo."))),
        ToolDef("timer", "Inicia um timer (contagem regressiva) no app de relógio.", listOf("segundos"), listOf(
            p("segundos", "integer", "Duração total em segundos."))),
        ToolDef("alarme", "Cria um alarme no app de relógio. Use também para lembretes (\"me lembra de X às 20h\"); só aceita a próxima vez que esse horário chegar.",
            listOf("hora", "minuto"), listOf(
                p("hora", "integer", "Hora de 0 a 23."),
                p("minuto", "integer", "Minuto de 0 a 59."),
                p("descricao", "string", "Texto do lembrete, ex.: \"tomar remédio\". Vazio para despertador."))),
        ToolDef("lista_tarefas", "Mexe na lista de tarefas (afazeres) do usuário, salva no celular: adicionar, ler, contar, remover (feita/concluída) ou limpar tudo.",
            listOf("acao"), listOf(
                p("acao", "string", "Uma destas: adicionar, ler, contar, remover, limpar."),
                p("texto", "string", "Para adicionar: a tarefa, curta, ex.: \"comprar pão\". Para remover: o nome ou parte do nome da tarefa."),
                p("numero", "integer", "Para remover pela posição na lista (1 = primeira). 0 se for pelo nome."))),
    )
}
