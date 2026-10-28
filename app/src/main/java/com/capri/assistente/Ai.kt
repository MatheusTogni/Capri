package com.capri.assistente

import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Recusa passageira do provedor (cota, servidor cheio ou demora): vale tentar outro modelo ou provedor. */
class BusyException(val kind: Kind, msg: String) : IOException(msg) {
    enum class Kind { QUOTA, OVERLOADED, TIMEOUT }
}

/** Erro que não melhora tentando de novo agora (chave, modelo, pedido). */
class ApiException(val code: Int, msg: String) : IOException(msg)

/** Um provedor de IA (Gemini, Groq): recebe a conversa e devolve texto ou um pedido de função. */
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

    /** Mensagem do corpo de erro ({"error":{"message":...}}) e, no Gemini, qual cota estourou. */
    fun detail(resp: String): String {
        val err = try { JSONObject(resp).optJSONObject("error") } catch (_: Exception) { null }
            ?: return resp.take(200)
        val sb = StringBuilder(err.optString("message").take(200))
        val details = err.optJSONArray("details")
        if (details != null) for (i in 0 until details.length()) {
            val d = details.optJSONObject(i) ?: continue
            d.optJSONArray("violations")?.optJSONObject(0)?.optString("quotaId")?.takeIf { it.isNotBlank() }
                ?.let { sb.append(" · cota: $it") }
            d.optString("retryDelay").takeIf { it.isNotBlank() }?.let { sb.append(" · tentar em $it") }
        }
        return sb.toString()
    }

    /** Lança a exceção certa para um código fora de 2xx. */
    fun fail(provider: String, model: String, code: Int, resp: String): Nothing {
        val msg = "$provider: ${what(code)} (HTTP $code em $model). ${detail(resp)}".trim()
        val kind = busyKind(code)
        if (kind != null) throw BusyException(kind, msg)
        throw ApiException(code, msg)
    }

    /** O que a Capri fala quando nenhum provedor conseguiu responder. */
    fun spoken(e: Exception): String = when {
        e is BusyException && e.kind == BusyException.Kind.QUOTA ->
            "Acabou a cota grátis da inteligência artificial por agora. Tenta de novo daqui a pouco."
        e is BusyException && e.kind == BusyException.Kind.TIMEOUT ->
            "A inteligência artificial demorou demais para responder. Tenta de novo."
        e is BusyException -> "O servidor da inteligência artificial está sobrecarregado agora. Tenta de novo daqui a pouco."
        e is ApiException && (e.code == 401 || e.code == 403) ->
            "A chave da inteligência artificial não funcionou. Confere nas configurações."
        e is ApiException && e.code == 404 -> "O modelo configurado não existe mais. Troca nas configurações."
        else -> "Deu um erro ao falar com a inteligência artificial. Dá uma olhada no aplicativo."
    }
}

/** Uma função que a IA pode pedir. Tipos em maiúsculas (formato do Gemini); o Groq converte. */
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

    const val ACTIONS = """Você consegue agir no celular usando as funções disponíveis:
- ligar ou fazer chamada de vídeo: ligar_whatsapp (você só liga pelo WhatsApp; não existe ligação telefônica comum);
- clima, notícias, resultados de jogos ou qualquer coisa atual: pesquisar_na_web;
- lanterna, volume, timer, alarme e lembretes: as funções com esses nomes;
- lista de tarefas, afazeres ou anotar algo para não esquecer: lista_tarefas.
Se o pedido não precisar de nenhuma função, só responda."""

    const val SEARCH = "Pesquise na web para responder com informação atual."

    fun clock(): String =
        "Agora é " + SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy, HH:mm", Locale("pt", "BR")).format(Date()) +
            " (relógio do celular)."

    private fun p(name: String, type: String, description: String) = ToolDef.Prop(name, type, description)

    /** Ações que a IA pode pedir em vez de responder com texto. */
    val TOOLS = listOf(
        ToolDef(CALL_TOOL, "Faz uma ligação de voz ou chamada de vídeo pelo WhatsApp para um contato da agenda do celular.",
            listOf("contato"), listOf(
                p("contato", "STRING", "Nome do contato do jeito que o usuário falou, ex.: \"João\", \"mãe\", \"Ana da academia\"."),
                p("video", "BOOLEAN", "true se o usuário pediu chamada de vídeo; false para ligação de voz."))),
        ToolDef(SEARCH_TOOL, "Pesquisa na web informação atual: clima, notícias, resultados de jogos, preços, eventos recentes.",
            listOf("consulta"), listOf(
                p("consulta", "STRING", "O que pesquisar, em poucas palavras."))),
        ToolDef("lanterna", "Liga ou desliga a lanterna do celular.", listOf("ligar"), listOf(
            p("ligar", "BOOLEAN", "true para ligar, false para desligar."))),
        ToolDef("volume", "Muda o volume de mídia do celular.", listOf("acao"), listOf(
            p("acao", "STRING", "Uma destas: aumentar, diminuir, maximo, mudo."))),
        ToolDef("timer", "Inicia um timer (contagem regressiva) no app de relógio.", listOf("segundos"), listOf(
            p("segundos", "INTEGER", "Duração total em segundos."))),
        ToolDef("alarme", "Cria um alarme no app de relógio. Use também para lembretes (\"me lembra de X às 20h\"); só aceita a próxima vez que esse horário chegar.",
            listOf("hora", "minuto"), listOf(
                p("hora", "INTEGER", "Hora de 0 a 23."),
                p("minuto", "INTEGER", "Minuto de 0 a 59."),
                p("descricao", "STRING", "Texto do lembrete, ex.: \"tomar remédio\". Vazio para despertador."))),
        ToolDef("lista_tarefas", "Mexe na lista de tarefas (afazeres) do usuário, salva no celular: adicionar, ler, contar, remover (feita/concluída) ou limpar tudo.",
            listOf("acao"), listOf(
                p("acao", "STRING", "Uma destas: adicionar, ler, contar, remover, limpar."),
                p("texto", "STRING", "Para adicionar: a tarefa, curta, ex.: \"comprar pão\". Para remover: o nome ou parte do nome da tarefa."),
                p("numero", "INTEGER", "Para remover pela posição na lista (1 = primeira). 0 se for pelo nome."))),
    )
}
