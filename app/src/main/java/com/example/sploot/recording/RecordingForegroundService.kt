package com.example.sploot.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.sploot.MainActivity
import com.example.sploot.R
import com.example.sploot.ble.PolarBleClient
import com.example.sploot.ble.PolarDeviceRepository
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Overnight recording foreground service (`connectedDevice` type).
 *
 * Holds a partial wakelock, shows a persistent notification (connection
 * state, sensor battery, current HR, packet count — updated at ~1 Hz) and
 * collects [PolarBleClient] flows into a [RawRecorder].
 *
 * Lifecycle: started via [ACTION_START] (deviceId + sessionId extras) and
 * stopped via [ACTION_STOP] (usually from [SessionManager]).
 */
class RecordingForegroundService : Service() {

    companion object {
        private const val TAG = "RecordingFgService"
        const val ACTION_START = "com.example.sploot.recording.START"
        const val ACTION_STOP = "com.example.sploot.recording.STOP"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_SESSION_ID = "session_id"

        private const val CHANNEL_ID = "sploot_recording"
        private const val NOTIFICATION_ID = 42
        private const val NOTIFICATION_PERIOD_MS = 1_000L

        fun start(context: Context, intent: Intent) {
            ContextCompat_startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val stop = Intent(context, RecordingForegroundService::class.java)
                .setAction(ACTION_STOP)
            ContextCompat_startForegroundService(context, stop)
        }

        private fun ContextCompat_startForegroundService(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var client: PolarBleClient? = null
    private var repository: PolarDeviceRepository? = null
    private var recorder: RawRecorder? = null
    private var sessionId: String? = null
    private val packetCount = AtomicLong(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
                val sid = intent.getStringExtra(EXTRA_SESSION_ID)
                    ?: SessionManager.get(this).newSessionId()
                if (deviceId == null) {
                    Log.e(TAG, "ACTION_START without device id — stopping")
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (recorder == null || sessionId != sid) {
                    sessionId = sid
                    startRecording(deviceId, sid)
                }
            }
            else -> Unit
        }
        // If restarted by the system (STICKY) with no intent, keep service
        // alive so the wakelock/notification persist until ACTION_STOP.
        return START_STICKY
    }

    private fun startRecording(deviceId: String, sid: String) {
        startAsForeground()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sploot:recording").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L) // 12 h safety cap
        }

        val c = PolarBleClient(this)
        client = c
        val repo = PolarDeviceRepository(this, c, scope)
        repository = repo

        val rec = RawRecorder(
            sessionDir = SessionManager.get(this).sessionDir(sid),
            sessionId = sid,
        )
        recorder = rec
        rec.start(scope)
        packetCount.set(0)

        scope.launch {
            // Reconnect loop + remember device
            repo.connectAndRemember(deviceId)
        }

        // Start PMD streams once the streaming feature is ready
        scope.launch {
            c.streamingReady.collect { ready ->
                if (ready) {
                    c.startStreams(includeEcg = true, includePpg = false)
                }
            }
        }

        // Record status transitions
        scope.launch {
            combine(c.connectionState, c.batteryPercent) { conn, batt ->
                com.example.sploot.ble.SensorStatus(batt, conn)
            }.distinctUntilChanged().collect { rec.appendStatus(it) }
        }

        // Telemetry collection -> recorder
        listOf(
            c.hrFlow, c.ibiFlow, c.accFlow, c.ecgFlow,
        ).forEach { flow ->
            scope.launch {
                flow.collect { sample ->
                    rec.append(sample)
                    packetCount.incrementAndGet()
                }
            }
        }

        // Live notification ~1 Hz
        scope.launch {
            var lastHr: Int? = null
            launch { c.hrFlow.conflate().collect { lastHr = it.hr } }
            while (true) {
                kotlinx.coroutines.delay(NOTIFICATION_PERIOD_MS)
                updateNotification(c.sensorStatus(), lastHr, packetCount.get())
            }
        }
    }

    private fun startAsForeground() {
        val notification = buildNotification(
            com.example.sploot.ble.SensorStatus(
                null,
                com.example.sploot.ble.SensorStatus.ConnectionState.CONNECTING,
            ),
            null, 0L
        )
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

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.recording_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.recording_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        status: com.example.sploot.ble.SensorStatus,
        hr: Int?,
        packets: Long,
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stateLabel = when (status.connectionState) {
            com.example.sploot.ble.SensorStatus.ConnectionState.CONNECTED -> "Connected"
            com.example.sploot.ble.SensorStatus.ConnectionState.CONNECTING -> "Connecting…"
            com.example.sploot.ble.SensorStatus.ConnectionState.DISCONNECTING -> "Disconnecting…"
            com.example.sploot.ble.SensorStatus.ConnectionState.DISCONNECTED -> "Reconnecting…"
        }
        val battery = status.batteryPercent?.let { "$it%" } ?: "--"
        val hrLabel = hr?.let { "$it bpm" } ?: "-- bpm"
        val text = "$stateLabel · battery $battery · HR $hrLabel · $packets pkts"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_sploot) // white vector koala-moon
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun updateNotification(
        status: com.example.sploot.ble.SensorStatus,
        hr: Int?,
        packets: Long,
    ) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(status, hr, packets))
    }

    override fun onDestroy() {
        Log.i(TAG, "service destroyed — flushing recorder")
        wakeLock?.let { if (it.isHeld) it.release() }
        repository?.stopAutoReconnect()
        client?.disconnect()
        recorder?.close() // final flush + fsync
        client?.shutdown()
        SessionManager.get(this).onServiceStopped()
        scope.cancel()
        super.onDestroy()
    }
}
