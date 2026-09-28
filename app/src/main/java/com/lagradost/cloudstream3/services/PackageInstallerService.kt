package com.lagradost.cloudstream3.services

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.net.Uri
import android.os.Build.VERSION.SDK_INT
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.FileProvider
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.MainActivity.Companion.deleteFileOnExit
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ui.settings.AdiXtreamUpdatePolicy
import com.lagradost.cloudstream3.ui.settings.ApkUpdater
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.utils.ApkInstaller
import com.lagradost.cloudstream3.utils.AppContextUtils.createNotificationChannel
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.math.roundToInt

class PackageInstallerService : Service() {
    private var installer: ApkInstaller? = null
    private var currentMode: Int = 0 

    private val baseNotification by lazy {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent =
            PendingIntentCompat.getActivity(this, 0, intent, 0, false)

        NotificationCompat.Builder(this, UPDATE_CHANNEL_ID)
            .setAutoCancel(false)
            .setColorized(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setColor(this.colorFromAttribute(R.attr.colorPrimary))
            .setContentTitle(getString(R.string.update_notification_downloading))
            .setContentIntent(pendingIntent)
    }

    override fun onCreate() {
        this.createNotificationChannel(
            UPDATE_CHANNEL_ID,
            UPDATE_CHANNEL_NAME,
            UPDATE_CHANNEL_DESCRIPTION
        )
        val notif = baseNotification.setSmallIcon(R.drawable.rdload).build()
        if (SDK_INT >= 29)
            startForeground(UPDATE_NOTIFICATION_ID, notif, FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(UPDATE_NOTIFICATION_ID, notif)
    }

    private val updateLock = Mutex()

    private suspend fun downloadUpdate(url: String, mode: Int): Boolean {
        try {
            require(AdiXtreamUpdatePolicy.allowsDownload(url)) { "Update must come from AdiXtream releases." }
            Log.d("PackageInstallerService", "Downloading AdiXtream update (Mode: $mode)")

            ioSafe {
                val appUpdateName = "AdiXtream"
                val appUpdateSuffix = "apk"
                this@PackageInstallerService.cacheDir.listFiles()?.filter {
                    it.name.startsWith(appUpdateName) && it.extension == appUpdateSuffix
                }?.forEach { deleteFileOnExit(it) }
            }

            updateLock.withLock {
                updateNotificationProgress(0f, ApkInstaller.InstallProgressStatus.Downloading)

                val downloadedFile = File.createTempFile("AdiXtream", ".apk", cacheDir)
                try {
                    val response = app.get(url)
                    var lastProgressUpdate = 0L
                    response.body.use { body ->
                        val totalSize = body.contentLength().takeIf { it > 0 }
                        body.byteStream().use { input ->
                            downloadedFile.outputStream().use { output ->
                                ApkUpdater.transfer(output, input, totalSize, { read, total ->
                                    val now = android.os.SystemClock.elapsedRealtime()
                                    if (total != null && total > 0 &&
                                        (now - lastProgressUpdate >= 500L || read >= total)
                                    ) {
                                        updateNotificationProgress(
                                            read.toFloat() / total.toFloat(), ApkInstaller.InstallProgressStatus.Downloading
                                        )
                                        lastProgressUpdate = now
                                    }
                                }, null)
                            }
                        }
                    }
                    ApkUpdater.validateUpgrade(this@PackageInstallerService, downloadedFile)
                    if (mode == 1) {
                        val installIntent = getInstallIntent(this@PackageInstallerService, downloadedFile)
                        val pendingIntent = PendingIntentCompat.getActivity(
                            this@PackageInstallerService, 1, installIntent, PendingIntent.FLAG_UPDATE_CURRENT, false
                        )
                        updateNotificationProgress(1f, ApkInstaller.InstallProgressStatus.Installing, pendingIntent)
                        try { startActivity(installIntent) } catch (e: Exception) { logError(e) }
                    } else {
                        installer = ApkInstaller(this@PackageInstallerService)
                        downloadedFile.inputStream().use { input ->
                            installer?.installApk(this@PackageInstallerService, input, downloadedFile.length(), {}) { status ->
                                updateNotificationProgress(0f, status)
                            }
                        }
                        downloadedFile.delete()
                    }
                } catch (error: Throwable) {
                    downloadedFile.delete()
                    throw error
                }
            }
            return true
        } catch (e: Exception) {
            logError(e)
            updateNotificationProgress(0f, ApkInstaller.InstallProgressStatus.Failed)
            return false
        }
    }

    private fun getInstallIntent(context: Context, file: File): Intent {
        ApkUpdater.validateUpgrade(context, file)
        val contentUri = FileProvider.getUriForFile(
            context, BuildConfig.APPLICATION_ID + ".provider", file
        )
        return Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) 
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            setDataAndType(contentUri, "application/vnd.android.package-archive")
        }
    }

    private fun updateNotificationProgress(
        percentage: Float,
        state: ApkInstaller.InstallProgressStatus,
        clickIntent: PendingIntent? = null
    ) {
        val text = when (state) {
            ApkInstaller.InstallProgressStatus.Installing -> R.string.update_notification_installing
            ApkInstaller.InstallProgressStatus.Preparing, ApkInstaller.InstallProgressStatus.Downloading -> R.string.update_notification_downloading
            ApkInstaller.InstallProgressStatus.Failed -> R.string.update_notification_failed
        }

        val iconRes = if (state == ApkInstaller.InstallProgressStatus.Failed) {
            R.drawable.rderror
        } else if (currentMode == 1) {
            android.R.drawable.stat_sys_download 
        } else {
            R.drawable.rdload 
        }

        val notificationBuilder = baseNotification
            .setContentTitle(getString(text))
            .setSmallIcon(iconRes)
            .apply {
                if (state == ApkInstaller.InstallProgressStatus.Failed) {
                    setAutoCancel(true)
                } else {
                    setProgress(
                        10000, (10000 * percentage).roundToInt(),
                        state != ApkInstaller.InstallProgressStatus.Downloading
                    )
                }
                
                if (clickIntent != null) {
                    setContentTitle("Unduhan Selesai") 
                    setContentText("Aplikasi berhasil didownload dan siap dipasang.") 
                    setContentIntent(clickIntent)
                    setAutoCancel(true) 
                    setOngoing(false)   
                    // --- PERBAIKAN: Menghapus bar progres ---
                    setProgress(0, 0, false)
                }
            }

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val id = if (state == ApkInstaller.InstallProgressStatus.Failed) UPDATE_NOTIFICATION_ID + 1 else UPDATE_NOTIFICATION_ID
        notificationManager.notify(id, notificationBuilder.build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
        currentMode = intent.getIntExtra(EXTRA_MODE, 0) 
        
        isDownloading = true 
        
        ioSafe {
            downloadUpdate(url, currentMode)
            
            isDownloading = false 

            if (SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(false)
            }
            
            this@PackageInstallerService.stopSelf() 
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        installer?.unregisterInstallActionReceiver()
        installer = null
        isDownloading = false
        this.stopSelf()
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onTimeout(reason: Int) {
        stopSelf()
    }

    companion object {
        private const val EXTRA_URL = "EXTRA_URL"
        private const val EXTRA_MODE = "EXTRA_MODE"

        var isDownloading: Boolean = false 

        const val UPDATE_CHANNEL_ID = "cloudstream3.updates"
        const val UPDATE_CHANNEL_NAME = "App Updates"
        const val UPDATE_CHANNEL_DESCRIPTION = "App updates notification channel"
        const val UPDATE_NOTIFICATION_ID = -68454136

        fun getIntent(context: Context, url: String, mode: Int): Intent {
            return Intent(context, PackageInstallerService::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_MODE, mode) 
        }
    }
}
