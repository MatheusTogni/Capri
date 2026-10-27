package com.capri.assistente

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract

/**
 * Ligações pelo WhatsApp. Não existe API oficial: o WhatsApp cria, em cada contato da agenda,
 * linhas "Ligação de voz"/"Chamada de vídeo" (as mesmas que aparecem no app de Contatos).
 * Abrir uma dessas linhas com o WhatsApp inicia a chamada.
 */
object WhatsApp {

    data class Contact(val contactId: Long, val name: String, val dataId: Long, val mime: String, val pkg: String, val video: Boolean)

    // pacote -> (voz, vídeo); com.whatsapp.w4b é o WhatsApp Business
    private val MIMES = linkedMapOf(
        "com.whatsapp" to ("vnd.android.cursor.item/vnd.com.whatsapp.voip.call" to "vnd.android.cursor.item/vnd.com.whatsapp.video.call"),
        "com.whatsapp.w4b" to ("vnd.android.cursor.item/vnd.com.whatsapp.w4b.voip.call" to "vnd.android.cursor.item/vnd.com.whatsapp.w4b.video.call"),
    )

    /**
     * Contatos com WhatsApp que batem com o nome falado, só os de melhor pontuação.
     * Lança SecurityException se não houver permissão de contatos.
     */
    fun find(ctx: Context, spoken: String, video: Boolean): List<Contact> {
        val wanted = MIMES.mapValues { (_, m) -> if (video) m.second else m.first }
        val pkgByMime = wanted.entries.associate { (pkg, mime) -> mime to pkg }
        val all = linkedMapOf<Long, Contact>() // um por contato, preferindo o WhatsApp comum
        ctx.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID, ContactsContract.Data.CONTACT_ID,
                ContactsContract.Data.DISPLAY_NAME, ContactsContract.Data.MIMETYPE),
            "${ContactsContract.Data.MIMETYPE} IN (${wanted.values.joinToString(",") { "?" }})",
            wanted.values.toTypedArray(),
            null
        )?.use { c ->
            while (c.moveToNext()) {
                val mime = c.getString(3) ?: continue
                val contactId = c.getLong(1)
                val pkg = pkgByMime[mime] ?: continue
                if (all[contactId]?.pkg == "com.whatsapp") continue
                all[contactId] = Contact(contactId, c.getString(2) ?: "", c.getLong(0), mime, pkg, video)
            }
        }

        val q = normalize(spoken)
        if (q.isBlank()) return emptyList()
        val scored = all.values.map { it to score(q, normalize(it.name)) }.filter { it.second > 0 }
        val best = scored.maxOfOrNull { it.second } ?: return emptyList()
        return scored.filter { it.second == best }.map { it.first }.sortedBy { it.name }
    }

    // 3 = nome igual; 2 = todas as palavras faladas estão no nome; 1 = alguma palavra (3+ letras) está
    private fun score(q: String, name: String): Int {
        if (name.isBlank()) return 0
        if (q == name) return 3
        val nameWords = name.split(" ").toSet()
        val qWords = q.split(" ")
        if (qWords.all { it in nameWords }) return 2
        if (qWords.any { it.length >= 3 && it in nameWords }) return 1
        return 0
    }

    /** Escolhe entre vários contatos a partir da resposta ("o Silva", "o segundo"...). */
    fun pick(answer: String, options: List<Contact>): Contact? {
        val a = normalize(answer)
        val ordinals = listOf("primeiro", "segundo", "terceiro", "quarto")
        ordinals.forEachIndexed { i, w -> if (" $a ".contains(" $w ") && i < options.size) return options[i] }
        // conta só as palavras que diferenciam os contatos (ignora o "joao" que todos têm)
        val common = options.map { normalize(it.name).split(" ").toSet() }.reduce { x, y -> x intersect y }
        val aWords = a.split(" ").toSet()
        val hits = options.map { o -> o to normalize(o.name).split(" ").count { it !in common && it in aWords } }
        val top = hits.maxOf { it.second }
        if (top == 0) return null
        return hits.filter { it.second == top }.singleOrNull()?.first
    }

    fun call(ctx: Context, c: Contact) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.withAppendedPath(ContactsContract.Data.CONTENT_URI, c.dataId.toString()), c.mime)
            .setPackage(c.pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }
}
