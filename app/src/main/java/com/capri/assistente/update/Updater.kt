package com.capri.assistente.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.capri.assistente.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Atualização pelo GitHub: o Actions publica `version.json` e `capri.apk` na release `latest`.
 * Só baixa e instala quando o usuário pede ([download]); a instalação sempre passa pela confirmação do Android.
 */
object Updater {

    private const val BASE_URL = "https://github.com/MatheusTogni/Capri/releases/download/latest/"
    private const val RECHECK_MS = 60_000L // intervalo mínimo entre duas checagens

    data class Release(val versionCode: Int, val versionName: String, val notes: String)

    enum class Phase { IDLE, DOWNLOADING, INSTALLING, FAILED }

    var available: Release? = null
        private set
    var phase: Phase = Phase.IDLE
        private set
    var progress: Int = 0
        private set
    var error: String? = null
        private set

    /** Chamado na thread principal sempre que algo muda (versão encontrada, progresso, erro). */
    var listener: (() -> Unit)? = null

    /** Tela de confirmação do instalador; a Activity aberta a mostra (senão é aberta numa task nova). */
    var onConfirm: ((Intent) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var checking = false
    @Volatile private var lastCheck = 0L // elapsedRealtime da última checagem que deu certo

    private fun notifyChanged() = main.post { listener?.invoke() }

    /**
     * Procura uma versão nova. A Capri é o assistente do celular, então o processo dela fica vivo por dias:
     * não dá para checar só uma vez por processo. Quem chama (a tela principal, a cada vez que volta ao
     * primeiro plano) pode chamar à vontade; só vai ao GitHub se a última checagem já tem mais de [RECHECK_MS].
     */
    fun check() {
        if (checking || phase == Phase.DOWNLOADING || phase == Phase.INSTALLING) return
        val last = lastCheck
        if (last != 0L && SystemClock.elapsedRealtime() - last < RECHECK_MS) return
        checking = true
        Thread {
            try {
                val json = JSONObject(httpGet(BASE_URL + "version.json").bufferedReader().use { it.readText() })
                val release = Release(
                    versionCode = json.getInt("versionCode"),
                    versionName = json.optString("versionName", json.getInt("versionCode").toString()),
                    notes = json.optString("notes", "").lineSequence().firstOrNull().orEmpty().trim()
                )
                lastCheck = SystemClock.elapsedRealtime()
                val newer = release.versionCode > BuildConfig.VERSION_CODE
                // sai da frente se já estava instalada; troca se saiu outra ainda mais nova
                if (newer && available?.versionCode != release.versionCode) {
                    available = release
                    notifyChanged()
                } else if (!newer && available != null) {
                    available = null
                    notifyChanged()
                }
            } catch (_: Exception) {
                // sem internet ou GitHub fora do ar: tenta de novo na próxima vez que a tela abrir
            } finally {
                checking = false
            }
        }.start()
    }

    fun download(context: Context) {
        if (available == null || phase == Phase.DOWNLOADING) return
        val app = context.applicationContext
        phase = Phase.DOWNLOADING
        progress = 0
        error = null
        notifyChanged()
        Thread {
            try {
                val file = File(app.cacheDir, "update/capri.apk")
                file.parentFile?.mkdirs()
                val conn = open(BASE_URL + "capri.apk")
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        var done = 0L
                        while (input.read(buffer).also { read = it } >= 0) {
                            output.write(buffer, 0, read)
                            done += read
                            if (total > 0) {
                                val percent = (done * 100 / total).toInt()
                                if (percent != progress) {
                                    progress = percent
                                    notifyChanged()
                                }
                            }
                        }
                    }
                }
                phase = Phase.INSTALLING
                notifyChanged()
                install(app, file)
            } catch (_: Exception) {
                fail("Não foi possível baixar a atualização. Verifique a internet e tente de novo.")
            }
        }.start()
    }

    private fun install(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("capri.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val callback = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, InstallResultReceiver::class.java),
                flags
            )
            session.commit(callback.intentSender)
        }
    }

    internal fun confirm(context: Context, intent: Intent) {
        main.post {
            val show = onConfirm
            if (show != null) {
                show(intent)
            } else {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    internal fun fail(message: String) {
        phase = Phase.FAILED
        error = message
        notifyChanged()
    }

    private fun httpGet(url: String) = open(url).inputStream

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.useCaches = false
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Cache-Control", "no-cache")
        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            throw IllegalStateException("HTTP ${conn.responseCode}")
        }
        return conn
    }
}
