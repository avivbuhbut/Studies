package com.aviv.phonemind

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

object BackupRecoveryScheduler {
    private const val WATCHDOG_NAME = "phonemind_drive_backup_watchdog"

    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<BackupRecoveryWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(
                WATCHDOG_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
    }

    fun kick(context: Context) {
        val app = context.applicationContext
        val store = BackupStateStore(app)
        if (!store.isActive()) return

        val categories = store.categories()
        if (categories.isEmpty()) return

        WorkManager.getInstance(app).enqueueUniqueWork(
            DriveBackupWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            DriveBackupWorker.request(categories, store.deleteAfter())
        )
    }
}

class BackupRecoveryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val store = BackupStateStore(applicationContext)
        if (store.isActive()) {
            store.setLastMessage("Wi-Fi זמין — ממשיך את הגיבוי")
            BackupRecoveryScheduler.kick(applicationContext)
        }
        return Result.success()
    }
}

class BackupBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        BackupRecoveryScheduler.ensureScheduled(context)
        BackupRecoveryScheduler.kick(context)
    }
}
