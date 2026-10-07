package com.lagradost.cloudstream3.services

import android.content.Context
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build.VERSION.SDK_INT
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.AppContextUtils.createNotificationChannel
import com.lagradost.cloudstream3.utils.BackupUtils
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream4.AppSettings
import java.util.concurrent.TimeUnit

const val BACKUP_CHANNEL_ID = "cloudstream3.backups"
const val BACKUP_WORK_NAME = "work_backup"
const val BACKUP_CHANNEL_NAME = "Backups"
const val BACKUP_CHANNEL_DESCRIPTION = "Notifications for background backups"
const val BACKUP_NOTIFICATION_ID = 938712898 // Random unique

// ============================================================
// ADIXTREAM SECURITY:
// Public backup UI is intentionally hidden.
// Periodic backup must remain disabled because exported backup
// data may expose internal repository configuration.
// Upstream scheduling/execution code is retained below.
// Re-enable only if explicitly requested.
// ============================================================
private const val ADIXTREAM_AUTOMATIC_BACKUP_DISABLED = true

class BackupWorkManager(val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {
    companion object {
        fun disableAutomaticBackup(context: Context) {
            val frequency = AppSettings(context).backup.frequency
            // Official OFF value; uses the existing automatic_backup_key storage.
            if (frequency.get() != 0) frequency.set(0)
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(BACKUP_WORK_NAME)
            // Also cancel any older/test requests carrying the existing backup tag.
            workManager.cancelAllWorkByTag(BACKUP_WORK_NAME)
        }

        fun enqueuePeriodicWork(context: Context?, intervalHours: Long) {
            if (context == null) return

            if (ADIXTREAM_AUTOMATIC_BACKUP_DISABLED) {
                disableAutomaticBackup(context)
                return
            }

            if (intervalHours == 0L) {
                WorkManager.getInstance(context).cancelUniqueWork(BACKUP_WORK_NAME)
                return
            }

            val constraints = Constraints.Builder()
                .setRequiresStorageNotLow(true)
                .build()

            val periodicSyncDataWork =
                PeriodicWorkRequest.Builder(
                    BackupWorkManager::class.java,
                    intervalHours,
                    TimeUnit.HOURS
                )
                    .addTag(BACKUP_WORK_NAME)
                    .setConstraints(constraints)
                    .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                BACKUP_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                periodicSyncDataWork
            )

            // Uncomment below for testing

//            val oneTimeBackupWork =
//                OneTimeWorkRequest.Builder(BackupWorkManager::class.java)
//                    .addTag(BACKUP_WORK_NAME)
//                    .setConstraints(constraints)
//                    .build()
//
//            WorkManager.getInstance(context).enqueue(oneTimeBackupWork)
        }
    }

    private val backupNotificationBuilder =
        NotificationCompat.Builder(context, BACKUP_CHANNEL_ID)
            .setColorized(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentTitle(context.getString(R.string.pref_category_backup))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setColor(context.colorFromAttribute(R.attr.colorPrimary))
            .setSmallIcon(R.drawable.ic_cloudstream_monochrome_big)

    override suspend fun doWork(): Result {
        // Cancellation is asynchronous. Never export from an old queued worker,
        // even if it starts before startup cancellation has completed.
        if (ADIXTREAM_AUTOMATIC_BACKUP_DISABLED) {
            disableAutomaticBackup(context)
            return Result.success()
        }

        context.createNotificationChannel(
            BACKUP_CHANNEL_ID,
            BACKUP_CHANNEL_NAME,
            BACKUP_CHANNEL_DESCRIPTION
        )

        val foregroundInfo = if (SDK_INT >= 29)
            ForegroundInfo(
                BACKUP_NOTIFICATION_ID, backupNotificationBuilder.build(), FOREGROUND_SERVICE_TYPE_DATA_SYNC
            ) else  ForegroundInfo(BACKUP_NOTIFICATION_ID, backupNotificationBuilder.build())
        setForeground(foregroundInfo)

        BackupUtils.backup(context)

        return Result.success()
    }
}
