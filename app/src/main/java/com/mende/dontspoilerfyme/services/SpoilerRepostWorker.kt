package com.mende.dontspoilerfyme.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mende.dontspoilerfyme.R
import com.mende.dontspoilerfyme.data.SettingsStore
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class SpoilerRepostWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val pkg = inputData.getString(KEY_PKG) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE)?.takeIf { it.isNotBlank() }
        val text = inputData.getString(KEY_TEXT)?.takeIf { it.isNotBlank() }
        val notifId = inputData.getInt(KEY_NOTIF_ID, 0)

        val mgr = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(mgr)

        // ✅ Source of truth: Shield state in DataStore
        val store = SettingsStore(applicationContext)
        val shieldOn = store.shieldEnabled.first()
        val endAt = store.shieldEndAtEpochMs.first()
        val now = System.currentTimeMillis()
        val expired = endAt != 0L && now >= endAt

        if (!shieldOn || expired) {
            if (shieldOn) {
                // Keep state consistent if auto-off didn't run yet
                store.setShieldEnabled(false)
                store.setShieldEndAtEpochMs(0L)
                SpoilerShieldService.stop(applicationContext)
            }
            return Result.success()
        }

        val pm = applicationContext.packageManager

        // 1) Try to reuse original contentIntent (direct open conversation)
        val directTap: PendingIntent? =
            if (notifId != 0) TapIntentCache.get(notifId) else null

        // 2) Fallback: open app
        val launchIntent = pm.getLaunchIntentForPackage(pkg)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val fallbackTap: PendingIntent? = launchIntent?.let {
            PendingIntent.getActivity(
                applicationContext,
                if (notifId != 0) notifId else abs(pkg.hashCode()),
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val contentPending: PendingIntent? = directTap ?: fallbackTap

        val appName = try {
            val appInfo = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            pkg
        }

        val thread = if (notifId != 0) ConversationMessageCache.getThread(notifId) else null

        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setSubText(appName)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setExtras(
                Bundle().apply {
                    putBoolean(SpoilerNotificationListener.EXTRA_REPOSTED, true)
                }
            )

        if (contentPending != null) {
            builder.setContentIntent(contentPending)
        }

        if (thread != null && thread.messages.isNotEmpty()) {
            val convTitle = thread.conversationTitle ?: (title ?: appName)
            val msgs = thread.messages

            // Multi-line, deterministic output: always shows sender when available
            val formattedText = buildString {
                msgs.forEachIndexed { index, m ->
                    val s = m.sender?.trim()
                    val showSender = !s.isNullOrBlank() && !s.equals(convTitle.trim(), ignoreCase = true)
                    if (showSender) {
                        append(s)
                        append(": ")
                    }
                    append(m.text)
                    if (index != msgs.lastIndex) append("\n")
                }
            }

            builder
                .setContentTitle(convTitle)
                .setContentText(msgs.last().text)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(formattedText)
                        .setSummaryText("$appName • ${thread.totalCount} new messages")
                )
        } else {
            // Fallback for non-chat or when cache is empty
            builder
                .setContentTitle(title ?: "Delayed notification")
                .setContentText(text ?: "From $appName")
        }

        val notif = builder.build()

        // ✅ Always use the stable conversation notifId when provided
        val idToUse = if (notifId != 0) notifId else abs(pkg.hashCode())
        mgr.notify(idToUse, notif)

        return Result.success()
    }

    private fun ensureChannel(mgr: NotificationManager) {
        val existing = mgr.getNotificationChannel(CHANNEL_ID)
        if (existing == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Delayed notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            // Default is showBadge=true; leave it as default.
            mgr.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val KEY_PKG = "pkg"
        private const val KEY_TITLE = "title"
        private const val KEY_TEXT = "text"
        private const val KEY_NOTIF_ID = "notifId"
        private const val CHANNEL_ID = "spoiler_delayed"

        fun schedule(
            context: Context,
            workName: String,
            pkg: String,
            title: String?,
            text: String?,
            delayMs: Long,
            notifId: Int
        ) {
            val data = workDataOf(
                KEY_PKG to pkg,
                KEY_TITLE to (title ?: ""),
                KEY_TEXT to (text ?: ""),
                KEY_NOTIF_ID to notifId
            )

            val req = OneTimeWorkRequestBuilder<SpoilerRepostWorker>()
                .setInputData(data)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, req)
        }
    }
}