package com.mende.dontspoilerfyme.services

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mende.dontspoilerfyme.data.SettingsStore
import java.util.concurrent.TimeUnit

class ShieldAutoOffWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val store = SettingsStore(applicationContext)
        store.setShieldEnabled(false)
        store.setShieldEndAtEpochMs(0L)
        SpoilerShieldService.stop(applicationContext)
        // Se hai uno stop esplicito del service, qui puoi chiamarlo (opzionale).
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "shield_auto_off"

        fun schedule(context: Context, delayMs: Long) {
            val req = OneTimeWorkRequestBuilder<ShieldAutoOffWorker>()
                .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, req)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
