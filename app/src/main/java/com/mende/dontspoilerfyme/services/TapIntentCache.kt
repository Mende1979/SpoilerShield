package com.mende.dontspoilerfyme.services

import android.app.PendingIntent
import java.util.concurrent.ConcurrentHashMap

object TapIntentCache {

    private data class Entry(
        val contentIntent: PendingIntent?,
        val createdAtMs: Long
    )

    private val map = ConcurrentHashMap<Int, Entry>()

    // TTL > del tuo massimo delay (mettiamo 75 min)
    private const val TTL_MS = 75 * 60 * 1000L


    fun put(notifId: Int, contentIntent: PendingIntent?) {
        if (contentIntent == null) return
        map[notifId] = Entry(contentIntent, System.currentTimeMillis())
        prune()
    }

    /** take = ritorna e rimuove (evita che cresca all’infinito) */
    fun get(notifId: Int): PendingIntent? {
        prune()
        return map[notifId]?.contentIntent
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.createdAtMs > TTL_MS) it.remove()
        }
    }
}