package com.mende.dontspoilerfyme.services

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object ConversationMessageCache {

    data class Msg(
        val sender: String?,
        val text: String,
        val timeMs: Long
    )

    data class ThreadData(
        val conversationTitle: String?,
        val messages: List<Msg>,
        val totalCount: Int
    )

    private data class MutableThreadData(
        var conversationTitle: String? = null,
        val messages: MutableList<Msg> = mutableListOf(),
        var totalCount: Int = 0,
        var lastUpdatedMs: Long = 0L
    )

    private val map = ConcurrentHashMap<Int, MutableThreadData>()
    private const val MAX_MESSAGES = 6
    private const val TTL_MS = 48L * 60L * 60L * 1000L
    private const val PRUNE_EVERY = 50
    private val ops = AtomicInteger(0)

    fun addMsg(notifId: Int, conversationTitle: String?, sender: String?, text: String, timeMs: Long) {
        if (text.isBlank()) return

        val thread = map.getOrPut(notifId) { MutableThreadData() }
        thread.conversationTitle = conversationTitle ?: thread.conversationTitle
        thread.totalCount += 1
        thread.lastUpdatedMs = System.currentTimeMillis()

        thread.messages.add(Msg(sender = sender?.takeIf { it.isNotBlank() }, text = text, timeMs = timeMs))
        while (thread.messages.size > MAX_MESSAGES) thread.messages.removeAt(0)

        if (ops.incrementAndGet() % PRUNE_EVERY == 0) prune()
    }

    fun getThread(notifId: Int): ThreadData? {
        val t = map[notifId] ?: return null
        return ThreadData(
            conversationTitle = t.conversationTitle,
            messages = t.messages.toList(),
            totalCount = t.totalCount
        )
    }

    fun clear(notifId: Int) {
        map.remove(notifId)
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.lastUpdatedMs > TTL_MS) it.remove()
        }
    }
}