package com.example.sploot.ble.snoop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.sploot.MainActivity
import com.example.sploot.R
import com.example.sploot.SplootApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service (`connectedDevice` type) that keeps the previous-night
 * download from the Polar sensor alive when the user leaves the app.
 *
 * Shows a persistent progress notification ("Downloading last night from
 * <device> — <step>") that tracks [MorningCaptureCoordinator.Status], then a
 * completion notification once the download/processing finishes. When a night
 * was actually processed, the coordinator's own morning-summary notification
 * serves as the completion notice and the progress notification is removed.
 *
 * Started from the Home screen (auto on sensor connect, or "Try again").
 */
class MorningDownloadService : Service() {

    companion object {
        private const val TAG = "MorningDownload"
        const val ACTION_START = "com.example.sploot.snoop.DOWNLOAD_START"

        private const val CHANNEL_ID = "morning_download"
        private const val NOTIFICATION_ID = 2002

        /** Wakelock safety cap — a large multi-night pull can take minutes. */
        private const val WAKELOCK_TIMEOUT_MS = 20 * 60_000L

        fun start(context: Context) {
            val intent = Intent(context, MorningDownloadService::class.java)
                .setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground("preparing to download")

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sploot:morningDownload").apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }

        val container = (application as SplootApplication).container
        scope.launch {
            // Mirror the coordinator's live status into the notification.
            val statusJob = launch {
                container.morningCapture.status.collect { status ->
                    when (status) {
                        is MorningCaptureCoordinator.Status.Running ->
                            updateNotification(status.step, ongoing = true)
                        else -> Unit // terminal states handled below
                    }
                }
            }
            val result = runCatching { container.morningCapture.captureNow() }
            statusJob.cancel()
            finish(result.getOrNull())
        }
        return START_NOT_STICKY
    }

    private fun finish(result: MorningCaptureCoordinator.CaptureResult?) {
        wakeLock?.let { if (it.isHeld) it.release() }
        val statusMsg = containerStatusMessage()
        when (result) {
            // A night was processed: the coordinator already posted the
            // "Good morning — last night processed" summary notification;
            // just clear the progress notification.
            is MorningCaptureCoordinator.CaptureResult.Captured ->
                stopForeground(STOP_FOREGROUND_REMOVE)
            is MorningCaptureCoordinator.CaptureResult.Unchanged ->
                postFinal("✓ ${statusMsg ?: "no new sleep data on the sensor"}")
            is MorningCaptureCoordinator.CaptureResult.Failed ->
                postFinal("Download problem: ${result.message}")
            null -> {
                Log.w(TAG, "captureNow threw")
                postFinal("Download problem: ${statusMsg ?: "the download was interrupted"}")
            }
        }
        stopSelf()
    }

    private fun containerStatusMessage(): String? {
        val container = (application as SplootApplication).container
        return when (val s = container.morningCapture.status.value) {
            is MorningCaptureCoordinator.Status.Done -> s.message
            is MorningCaptureCoordinator.Status.Failed -> s.message
            else -> null
        }
    }

    // ---- notifications ----

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.morning_download_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.morning_download_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, ongoing: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(
                getString(
                    if (ongoing) R.string.morning_download_progress_title
                    else R.string.morning_download_done_title
                )
            )
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_sploot)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, ongoing) // indeterminate while running
            .setContentIntent(contentIntent)
            .setAutoCancel(!ongoing)
            .build()
    }

    private fun startAsForeground(initialStep: String) {
        val notification = buildNotification(initialStep, ongoing = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(step: String, ongoing: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(step, ongoing))
    }

    private fun postFinal(text: String) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return // user declined notifications; the Home card still shows state
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text, ongoing = false))
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }
}
