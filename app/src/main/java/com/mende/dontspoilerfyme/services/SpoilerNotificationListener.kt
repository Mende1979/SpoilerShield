package com.mende.dontspoilerfyme.services

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.mende.dontspoilerfyme.data.SettingsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import androidx.core.app.NotificationCompat

class SpoilerNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var shieldOnCached: Boolean = false
    @Volatile private var shieldEndAtCached: Long = 0L
    @Volatile private var delaySecondsCached: Int = 30
    @Volatile private var whitelistCached: Set<String> = emptySet()
    @Volatile private var delayedCached: Set<String> = emptySet()
    @Volatile private var isPremiumCached: Boolean = false

    private val safePackages = setOf(
        "com.google.android.dialer",
        "com.android.dialer",
        "com.google.android.apps.messaging",
        "com.android.mms",
        "com.android.phone",
        "com.google.android.apps.clock",
        "com.android.deskclock",
        "com.android.calendar",
        "com.google.android.calendar",
        // ✅ Permission controller (AOSP + Google)
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        val store = SettingsStore(applicationContext)

        scope.launch {
            store.shieldEnabled.collectLatest {
                shieldOnCached = it
                // Keep foreground service aligned with source-of-truth state.
                // This makes Shield survive process restarts and prevents "notification-as-state" bugs.
                if (it) {
                    SpoilerShieldService.start(applicationContext)
                } else {
                    SpoilerShieldService.stop(applicationContext)
                }
            }
        }
        scope.launch { store.shieldEndAtEpochMs.collectLatest { shieldEndAtCached = it } }
        scope.launch { store.delaySeconds.collectLatest { delaySecondsCached = it } }
        scope.launch { store.whitelistPackages.collectLatest { whitelistCached = it } }
        scope.launch { store.delayedPackages.collectLatest { delayedCached = it } }
        scope.launch { store.isPremium.collectLatest { isPremiumCached = it } }
    }

    private fun shouldBypassDelay(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification

        // 1) Foreground service (player, navigazione, call, ecc.)
        if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) {
            return true
        }

        // 2) Media playback category
        if (n.category == Notification.CATEGORY_TRANSPORT) {
            return true
        }

        // 3) MediaStyle / DecoratedMediaStyle
        val template = n.extras.getString(Notification.EXTRA_TEMPLATE)
        if (template != null && template.contains("Media", ignoreCase = true)) {
            return true
        }

        // 4) MediaSession (alcune app lo mettono solo qui)
        if (n.extras.containsKey("android.mediaSession")) {
            return true
        }

        // 5) Critiche “funzionali”
        if (n.category == Notification.CATEGORY_CALL) return true
        if (n.category == Notification.CATEGORY_ALARM) return true
        if (n.category == Notification.CATEGORY_NAVIGATION) return true

        return false
    }

    private fun conversationKeyOrNull(sbn: StatusBarNotification): String? {
        val n = sbn.notification
        val e = n.extras

        // 1️⃣ ShortcutId (molto stabile per conversazioni moderne)
        val shortcutId = n.shortcutId
        if (!shortcutId.isNullOrBlank()) {
            return "${sbn.packageName}|sc:$shortcutId"
        }

        // 2️⃣ GroupKey (molto stabile per WhatsApp)
        val groupKey = sbn.groupKey
        if (!groupKey.isNullOrBlank()) {
            return "${sbn.packageName}|gk:$groupKey"
        }

        // 3️⃣ Conversation title (fallback)
        val convTitle =
            e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()
        if (!convTitle.isNullOrBlank()) {
            return "${sbn.packageName}|ct:$convTitle"
        }

        // 4️⃣ Title fallback
        val title =
            e.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        if (!title.isNullOrBlank()) {
            return "${sbn.packageName}|t:$title"
        }

        return null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras

        // anti-loop: ignora le nostre notifiche ricostruite
        if (extras.getBoolean(EXTRA_REPOSTED, false)) return

        // ongoing
        if ((sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

        // sistema
        if (sbn.packageName == "android" || sbn.packageName == "com.android.systemui") return

        // non processare notifiche della nostra app
        if (sbn.packageName == packageName) return

        // Ignore safety/system packages and ourselves (also keeps "apps that notified" clean)
        val ignored = safePackages + setOf(packageName)
        if (sbn.packageName in ignored) return

        // Premium behavior: delay ONLY selected apps.
        // Free behavior: delay ALL apps (except ignored) with fixed 30s.
        if (isPremiumCached) {
            val effectiveDelayed = delayedCached
            if (sbn.packageName !in effectiveDelayed) return
        } else {
            // keep old premium whitelist for backward compatibility only (no-op here)
        }

        // shield attivo + non scaduto?
        val now = System.currentTimeMillis()
        val isExpired = shieldEndAtCached != 0L && now >= shieldEndAtCached

// se è scaduto, spegniamo “davvero” (evita che resti ON in UI/stato)
        if (shieldOnCached && isExpired) {
            scope.launch {
                val store = SettingsStore(applicationContext)
                store.setShieldEnabled(false)
                store.setShieldEndAtEpochMs(0L)
                // IMPORTANTISSIMO: allinea anche il foreground service
                SpoilerShieldService.stop(applicationContext)
            }
            return
        }

// ✅ Shield OFF: non fare nulla
        if (!shieldOnCached) return

// group summary: cancella ma non ripubblicare
        val isGroupSummary = (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0

// testo
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()

        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString("\n") { it.toString() }
            ?.trim()
            .orEmpty()

        val effectiveText = when {
            bigText.isNotEmpty() -> bigText
            text.isNotEmpty() -> text
            lines.isNotEmpty() -> lines
            else -> ""
        }

// 🔥 FIX CRITICO: non bloccare notifiche necessarie al playback
        if (shouldBypassDelay(sbn)) {
            return
        }

        if (title.isEmpty() && effectiveText.isEmpty()) return

// cancella sempre
        try {
            cancelNotification(sbn.key)
            @Suppress("DEPRECATION")
            cancelNotification(sbn.packageName, sbn.tag, sbn.id)
        } catch (_: Throwable) {
            return
        }

        if (isGroupSummary) return

// ✅ DEDUP: 1 work + 1 notifId per EVENTO (stabile) -> mantiene TUTTI i messaggi senza duplicare
        val eventKey = buildString {
            append(sbn.key)
            append('|')
            append(sbn.postTime)   // cambia per ogni update “reale”
            append('|')
            append(title)
            append('|')
            append(effectiveText)
        }

// id stabile (no nanoTime)
        val conversationKey = conversationKeyOrNull(sbn)

        val stableId = if (conversationKey != null) {
            abs(conversationKey.hashCode())   // ✅ 1 ID per chat
        } else {
            abs(eventKey.hashCode())          // ✅ come prima (1 ID per evento)
        }

        TapIntentCache.put(stableId, sbn.notification.contentIntent)

        val workName = if (conversationKey != null) {
            "repost_thread_$stableId"         // ✅ stabile per chat → REPLACE aggiorna
        } else {
            "repost_evt_$stableId"
        }

        // ... stableId, TapIntentCache, workName ...

        if (conversationKey != null) {
            val convTitle =
                extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: title.takeIf { it.isNotBlank() }

            val extracted = NotificationCompat.MessagingStyle
                .extractMessagingStyleFromNotification(sbn.notification)

            if (extracted != null && extracted.messages.isNotEmpty()) {

                val convTitle = extracted.conversationTitle?.toString()
                    ?: title.takeIf { it.isNotBlank() }

                val lastMessage = extracted.messages.last()

                val senderName = lastMessage.person?.name?.toString()
                val textOnly = lastMessage.text?.toString()?.trim().orEmpty()
                val mTime = lastMessage.timestamp

                if (textOnly.isNotBlank()) {
                    ConversationMessageCache.addMsg(
                        notifId = stableId,
                        conversationTitle = convTitle,
                        sender = senderName,
                        text = textOnly,
                        timeMs = mTime
                    )
                }
            }

            else {
                // fallback: quello che avevi prima (title: effectiveText)
                val fallback = buildString {
                    if (title.isNotBlank()) {
                        append(title)
                        if (effectiveText.isNotBlank()) append(": ")
                    }
                    if (effectiveText.isNotBlank()) append(effectiveText)
                }

                if (fallback.isNotBlank()) {
                    ConversationMessageCache.addMsg(
                        notifId = stableId,
                        conversationTitle = convTitle,
                        sender = null,
                        text = fallback,
                        timeMs = System.currentTimeMillis()
                    )
                }
            }
        }

// FREE enforcement: delay fisso 30s
        val delaySeconds = if (isPremiumCached) delaySecondsCached else 30
        val delayMs = delaySeconds * 1000L

        val finalTitle = title.ifEmpty { null }
        val finalText = effectiveText.ifEmpty { null }

        SpoilerRepostWorker.schedule(
            context = applicationContext,
            workName = workName,
            pkg = sbn.packageName,
            title = finalTitle,
            text = finalText,
            delayMs = delayMs,
            notifId = stableId
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        try {
            // Considera solo notifiche della tua app (quelle repostate)
            if (sbn.packageName != packageName) return

            val extras = sbn.notification.extras
            if (!extras.getBoolean(EXTRA_REPOSTED, false)) return

            val id = sbn.id
            if (id != 0) {
                // ✅ pulisci SOLO la chat relativa a questa notifica
                ConversationMessageCache.clear(id)

                // opzionale: se vuoi, pulisci anche il tap intent associato
                // (ma tu hai TTL, quindi non è indispensabile)
                // TapIntentCache.remove(id) // solo se implementi remove()
            }
        } catch (_: Throwable) {
            // no-op
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        const val EXTRA_REPOSTED = "dontspoilerfyme_reposted"
    }
}
