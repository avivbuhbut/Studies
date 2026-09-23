package com.aviv.phonemind

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.util.concurrent.TimeUnit

class DriveBackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val store = BackupStateStore(applicationContext)

        var categories = inputData.getStringArray(KEY_CATEGORIES)?.toSet().orEmpty()
        var deleteAfter = inputData.getBoolean(KEY_DELETE_AFTER, false)

        if (categories.isEmpty()) categories = store.categories()
        if (store.isActive()) deleteAfter = store.deleteAfter()

        if (categories.isEmpty()) {
            return Result.failure(workDataOf(KEY_ERROR to "לא נבחרו קטגוריות לגיבוי"))
        }

        if (!store.isActive()) store.activate(categories, deleteAfter)

        setForeground(foregroundInfo(0, "ממתין ל-Wi-Fi ומכין את הגיבוי…"))
        store.setLastMessage("הגיבוי פעיל")

        val db = IndexDatabase(applicationContext)
        try {
            val storageRepo = StorageRepository(applicationContext, db)
            val driveRepo = DriveBackupRepository(applicationContext)

            val cached = storageRepo.ensureFilesLoaded()
            val missing = cached.asSequence()
                .filter { !File(it.path).isFile }
                .map { it.path }
                .toList()

            if (missing.isNotEmpty()) {
                storageRepo.refreshAfterExternalDeletes(missing)
            }

            val result = driveRepo.backup(
                sourceItems = storageRepo.ensureFilesLoaded(),
                categories = categories,
                deleteAfterUpload = deleteAfter,
                progress = { pct, msg ->
                    store.setLastMessage(msg)
                    setProgressAsync(workDataOf(KEY_PROGRESS to pct, KEY_MESSAGE to msg))
                    setForegroundAsync(foregroundInfo(pct, msg))
                },
                shouldCancel = { isStopped || !store.isActive() }
            )

            if (result.deletedPaths.isNotEmpty()) {
                storageRepo.refreshAfterExternalDeletes(result.deletedPaths)
            }

            if (!store.isActive()) {
                return Result.success(workDataOf(KEY_CANCELLED to true))
            }

            if (result.cancelled) {
                store.setLastMessage("הגיבוי הושהה — ימשיך אוטומטית")
                return Result.retry()
            }

            if (result.failedFiles > 0) {
                store.setLastMessage("${result.failedFiles} קבצים ממתינים לניסיון נוסף")
                return Result.retry()
            }

            store.complete()
            return Result.success(
                workDataOf(
                    KEY_SELECTED_FILES to result.selectedFiles,
                    KEY_SELECTED_BYTES to result.selectedBytes,
                    KEY_UPLOADED_FILES to result.uploadedFiles,
                    KEY_UPLOADED_BYTES to result.uploadedBytes,
                    KEY_SKIPPED_FILES to result.skippedFiles,
                    KEY_FAILED_FILES to result.failedFiles,
                    KEY_DELETED_FILES to result.deletedFiles,
                    KEY_DELETED_BYTES to result.deletedBytes,
                    KEY_CANCELLED to false
                )
            )
        } catch (t: Throwable) {
            if (!store.isActive()) {
                return Result.success(workDataOf(KEY_CANCELLED to true))
            }

            val message = t.message.orEmpty().ifBlank {
                "הגיבוי הושהה וינסה שוב אוטומטית"
            }
            store.setLastMessage(message)
            return Result.retry()
        } finally {
            runCatching { db.close() }
        }
    }

    override fun onStopped() {
        super.onStopped()
        val store = BackupStateStore(applicationContext)
        if (store.isActive()) {
            store.setLastMessage(
                "Android עצר זמנית את התהליך — PhoneMind ימשיך אוטומטית"
            )
        }
    }

    private fun foregroundInfo(progress: Int, message: String): ForegroundInfo {
        createChannel()

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            31,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("PhoneMind · הגיבוי ממשיך ברקע")
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, progress.coerceIn(0, 100), progress <= 0)
            .build()

        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = applicationContext.getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as NotificationManager

            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "PhoneMind backups",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Google Drive backup continues in the background"
                        setShowBadge(false)
                    }
                )
            }
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "phonemind_drive_backup"
        const val KEY_CATEGORIES = "categories"
        const val KEY_DELETE_AFTER = "delete_after"
        const val KEY_PROGRESS = "progress"
        const val KEY_MESSAGE = "message"
        const val KEY_ERROR = "error"
        const val KEY_SELECTED_FILES = "selected_files"
        const val KEY_SELECTED_BYTES = "selected_bytes"
        const val KEY_UPLOADED_FILES = "uploaded_files"
        const val KEY_UPLOADED_BYTES = "uploaded_bytes"
        const val KEY_SKIPPED_FILES = "skipped_files"
        const val KEY_FAILED_FILES = "failed_files"
        const val KEY_DELETED_FILES = "deleted_files"
        const val KEY_DELETED_BYTES = "deleted_bytes"
        const val KEY_CANCELLED = "cancelled"

        private const val CHANNEL_ID = "phonemind_drive_backup"
        private const val NOTIFICATION_ID = 31031

        fun request(
            categories: Set<String>,
            deleteAfter: Boolean
        ): OneTimeWorkRequest =
            OneTimeWorkRequest.Builder(DriveBackupWorker::class.java)
                .setInputData(
                    workDataOf(
                        KEY_CATEGORIES to categories.toTypedArray(),
                        KEY_DELETE_AFTER to deleteAfter
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    20,
                    TimeUnit.SECONDS
                )
                .addTag(UNIQUE_WORK_NAME)
                .build()
    }
}
