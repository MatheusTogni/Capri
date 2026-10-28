package com.capri.assistente

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.provider.AlarmClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ações que a Capri faz no próprio celular. */
sealed class Command {
    data class Torch(val on: Boolean) : Command()
    data class Volume(val action: String) : Command() // "aumentar", "diminuir", "maximo", "mudo"
    object Time : Command()
    object Today : Command()
    data class Timer(val seconds: Int) : Command()
    data class Alarm(val hour: Int, val minute: Int, val label: String) : Command()

    /** Lista de tarefas ([TodoStore]); quem executa é o serviço, que pode perguntar "qual delas?". */
    sealed class Todo : Command() {
        data class Add(val text: String) : Todo()
        object Read : Todo()
        object Count : Todo()
        /** Pelo [number] na lista (1 = primeira) ou, se 0, pelo nome ou parte dele ([query]). */
        data class Remove(val query: String, val number: Int = 0) : Todo()
        object Clear : Todo()
    }

    /** Precisa abrir o app de relógio (bloqueado em segundo plano sem "aparecer sobre outros apps"). */
    val opensApp get() = this is Timer || this is Alarm
}

/**
 * Reconhece pedidos simples sem passar pelo Gemini (resposta na hora, sem gastar cota).
 * Só pega frases bem diretas; o resto vai para o Gemini, que também sabe pedir essas ações.
 */
object LocalCommands {

    private val ON = setOf("liga", "ligar", "ligue", "acende", "acender", "acenda", "ativa", "ativar", "ative")
    private val OFF = setOf("desliga", "desligar", "desligue", "apaga", "apagar", "apague", "desativa", "desativar")
    private val UP = setOf("aumenta", "aumentar", "aumente", "sobe", "subir", "suba")
    private val DOWN = setOf("abaixa", "abaixar", "abaixe", "diminui", "diminuir", "diminua", "baixa", "baixar")

    private val TIME = Regex("""^((me )?(diz|fala|sabe|sabe me dizer) )?(que|quais) horas? (sao|e)( agora)?$""")
    private val TODAY = Regex("""^((me )?(diz|fala) )?(que|qual) (dia|data) (e|e hoje|de hoje|hoje)$|^que dia e hoje$""")
    private val DURATION = Regex("""(\d+|um|uma|meia) (segundos?|minutos?|horas?)""")

    fun parse(spoken: String): Command? {
        val t = normalize(spoken).removePrefix("capri ").trim()
        val words = t.split(" ").toSet()

        if ("lanterna" in words) {
            // OFF antes de ON: "desliga" contém "liga"
            if (words.any { it in OFF }) return Command.Torch(false)
            if (words.any { it in ON }) return Command.Torch(true)
        }

        if ("volume" in words) {
            return when {
                "maximo" in words || "tudo" in words -> Command.Volume("maximo")
                "mudo" in words || words.any { it.startsWith("silencia") } -> Command.Volume("mudo")
                words.any { it in UP } -> Command.Volume("aumentar")
                words.any { it in DOWN } -> Command.Volume("diminuir")
                else -> null
            }
        }

        if (TIME.matches(t)) return Command.Time
        if (TODAY.matches(t)) return Command.Today

        if (words.any { it == "timer" || it == "temporizador" || it == "cronometro" }) {
            var total = 0
            for (m in DURATION.findAll(t)) {
                val n = when (m.groupValues[1]) { "um", "uma" -> 1; "meia" -> 0; else -> m.groupValues[1].toInt() }
                val unit = m.groupValues[2]
                total += when {
                    m.groupValues[1] == "meia" && unit.startsWith("hora") -> 1800
                    unit.startsWith("segundo") -> n
                    unit.startsWith("minuto") -> n * 60
                    else -> n * 3600
                }
            }
            // "1 minuto e meio"
            if (t.contains("minuto e meio") || t.contains("minutos e meio")) total += 30
            if (total > 0) return Command.Timer(total)
        }
        return null
    }
}

/** Executa um [Command] e devolve a frase que a Capri fala. */
class CommandRunner(private val ctx: Context) {

    private val ptBR = Locale("pt", "BR")

    fun run(c: Command): String = try {
        when (c) {
            is Command.Torch -> torch(c.on)
            is Command.Volume -> volume(c.action)
            Command.Time -> spokenTime()
            Command.Today -> "Hoje é " + SimpleDateFormat("EEEE, d 'de' MMMM", ptBR).format(Date()) + "."
            is Command.Timer -> timer(c.seconds)
            is Command.Alarm -> alarm(c)
            is Command.Todo -> "" // o serviço trata antes de chegar aqui
        }
    } catch (e: Exception) {
        CapriBus.log("❌ Comando falhou: ${e.message}")
        "Não consegui fazer isso agora."
    }

    private fun spokenTime(): String {
        val cal = java.util.Calendar.getInstance()
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val m = cal.get(java.util.Calendar.MINUTE)
        val hours = if (h == 1) "Agora é 1 hora" else "Agora são $h horas"
        return when (m) {
            0 -> "$hours em ponto."
            1 -> "$hours e 1 minuto."
            else -> "$hours e $m minutos."
        }
    }

    private fun torch(on: Boolean): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "Esse celular não tem lanterna."
        return try {
            cm.setTorchMode(id, on)
            if (on) "Lanterna ligada." else "Lanterna desligada."
        } catch (e: Exception) {
            // acontece quando outro app está usando a câmera
            "Não consegui mexer na lanterna. A câmera pode estar em uso."
        }
    }

    private fun volume(action: String): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val s = AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(s)
        val step = maxOf(1, max / 5)
        when (action) {
            "maximo" -> am.setStreamVolume(s, max, 0)
            "mudo" -> { am.setStreamVolume(s, 0, 0); return "" } // falar algo no mudo não faz sentido
            "aumentar" -> am.setStreamVolume(s, minOf(max, am.getStreamVolume(s) + step), 0)
            "diminuir" -> am.setStreamVolume(s, maxOf(1, am.getStreamVolume(s) - step), 0)
            else -> return "Não entendi o que fazer com o volume."
        }
        return "Volume em ${am.getStreamVolume(s) * 100 / max} por cento."
    }

    private fun timer(seconds: Int): String {
        val secs = seconds.coerceIn(1, 86_400)
        ctx.startActivity(Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, secs)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Capri")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Timer de ${duration(secs)} começando."
    }

    private fun alarm(a: Command.Alarm): String {
        ctx.startActivity(Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, a.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, a.minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, a.label.ifBlank { "Capri" })
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val hora = if (a.minute == 0) "${a.hour} horas" else "${a.hour} e ${"%02d".format(a.minute)}"
        return if (a.label.isBlank()) "Alarme marcado para as $hora." else "Combinado, te lembro às $hora: ${a.label}."
    }

    private fun duration(secs: Int): String {
        val h = secs / 3600; val m = secs % 3600 / 60; val s = secs % 60
        val parts = mutableListOf<String>()
        if (h > 0) parts += if (h == 1) "1 hora" else "$h horas"
        if (m > 0) parts += if (m == 1) "1 minuto" else "$m minutos"
        if (s > 0) parts += if (s == 1) "1 segundo" else "$s segundos"
        return parts.joinToString(" e ")
    }
}
