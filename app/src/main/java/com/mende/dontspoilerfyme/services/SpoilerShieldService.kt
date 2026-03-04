package com.mende.dontspoilerfyme.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mende.dontspoilerfyme.MainActivity
import com.mende.dontspoilerfyme.R
import android.content.pm.ServiceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.cancel
import java.util.Locale

class SpoilerShieldService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tickerJob: Job? = null
    private val store by lazy { com.mende.dontspoilerfyme.data.SettingsStore(applicationContext) }

    private fun formatRemainingMinutes(ms: Long): String {
        val totalMinutes = (ms / 60_000).coerceAtLeast(0)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60

        return when {
            totalMinutes <= 0 -> "Less than 1m"
            hours > 0 -> String.format(Locale.US, "%dh %02dm", hours, minutes)
            else -> String.format(Locale.US, "%dm", minutes)
        }
    }

    private fun updateNotificationText(text: String) {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIF_ID, buildNotification(text))
    }

    private fun startNotificationTicker() {
        tickerJob?.cancel()

        tickerJob = serviceScope.launch {
            store.shieldEnabled.collectLatest { enabled ->

                if (!enabled) {
                    stopNotificationTicker()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collectLatest
                }

                // Cache endAt UNA SOLA VOLTA
                val endAt = store.shieldEndAtEpochMs.first()

                if (endAt == 0L) {
                    updateNotificationText("Protecting live events from notification spoilers")
                    return@collectLatest
                }

                while (isActive) {

                    val now = System.currentTimeMillis()
                    val remainingMs = (endAt - now).coerceAtLeast(0L)

                    if (remainingMs <= 0L) {
                        // Allinea stato e chiudi
                        serviceScope.launch(Dispatchers.IO) {
                            store.setShieldEnabled(false)
                            store.setShieldEndAtEpochMs(0L)
                        }

                        stopNotificationTicker()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        break
                    }

                    val text = "Time left: ${formatRemainingMinutes(remainingMs)}"
                    updateNotificationText(text)

                    // 🔥 Aggiornamento ogni minuto preciso
                    val delayMs = remainingMs % 60_000
                    delay(if (delayMs == 0L) 60_000 else delayMs)
                }
            }
        }
    }

    private fun stopNotificationTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        when (intent?.action) {

            ACTION_STOP -> {
                // Source of truth: aggiorna stato persistente
                serviceScope.launch(Dispatchers.IO) {
                    store.setShieldEnabled(false)
                    store.setShieldEndAtEpochMs(0L)
                }

                // Cancella eventuale auto-off schedulato
                ShieldAutoOffWorker.cancel(applicationContext)
                stopNotificationTicker()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_RESTORE_NOTIFICATION -> {
                createChannelIfNeeded()
                val notification = buildNotification("Protecting live events from notification spoilers")

                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    startNotificationTicker()
                } else {
                    startForeground(NOTIF_ID, notification)
                    startNotificationTicker()
                }

                serviceScope.launch(Dispatchers.IO) {
                    val enabled = store.shieldEnabled.first()
                    val endAt = store.shieldEndAtEpochMs.first()
                    val expired = endAt != 0L && System.currentTimeMillis() >= endAt

                    if (!enabled || expired) {
                        stopNotificationTicker()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        // opzionale: aggiorna notifica se vuoi
                        // (NotificationManager).notify(NOTIF_ID, buildNotification())
                    }
                }
                return START_STICKY
            }

            ACTION_START, null -> {
                // Start "normale" (o restart del servizio da sistema)
                createChannelIfNeeded()
                val notification = buildNotification("Protecting live events from notification spoilers")

                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(
                        NOTIF_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                    startNotificationTicker()
                } else {
                    startForeground(NOTIF_ID, notification)
                    startNotificationTicker()
                }

                return START_STICKY
            }

            else -> {
                // fallback: comportati come START
                createChannelIfNeeded()
                val notification = buildNotification("Protecting live events from notification spoilers")

                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(
                        NOTIF_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                    startNotificationTicker()
                } else {
                    startForeground(NOTIF_ID, notification)
                    startNotificationTicker()
                }

                return START_STICKY
            }
        }
    }


    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPending = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, SpoilerShieldService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val restoreIntent = Intent(this, SpoilerShieldService::class.java).apply { action = ACTION_RESTORE_NOTIFICATION }
        val restorePending = PendingIntent.getService(
            this, 2, restoreIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Spoiler Shield active")
            .setContentText(contentText)
            .setContentIntent(openAppPending)
            .setDeleteIntent(restorePending)
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Stop", stopPending)
            .build()

        n.flags = n.flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT
        return n
    }

    private fun createChannelIfNeeded() {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Spoiler Shield",
                NotificationManager.IMPORTANCE_LOW
            )
            mgr.createNotificationChannel(channel)
    }

    @Deprecated("Deprecated in Java")
    override fun onStart(intent: Intent?, startId: Int) {
        // kept for compatibility; logic is in onStartCommand
    }

    companion object {
        private const val CHANNEL_ID = "spoiler_shield"
        private const val NOTIF_ID = 1001

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_RESTORE_NOTIFICATION = "ACTION_RESTORE_NOTIFICATION"

        fun start(context: Context) {
            val i = Intent(context, SpoilerShieldService::class.java).apply { action = ACTION_START }

            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SpoilerShieldService::class.java))
        }
    }
    override fun onDestroy() {
        stopNotificationTicker()
        serviceScope.cancel()
        super.onDestroy()
    }
}
