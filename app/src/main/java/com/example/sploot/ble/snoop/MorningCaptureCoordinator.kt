package com.example.sploot.ble.snoop

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.sploot.R
import com.example.sploot.data.repo.RecoveryRepository
import com.example.sploot.data.repo.SleepSummary
import com.example.sploot.processing.ProcessNightUseCase
import com.example.sploot.processing.ProcessingState
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Orchestrates the fully-automatic morning flow:
 *
 *  open Sploot → [maybeAutoCapture] → SnoopAutoFetcher (direct cat → bugreportz)
 *  → fingerprint change detection (skip when unchanged since last import)
 *  → SnoopIngestSession + ProcessNightUseCase in the background
 *  → morning-summary notification (sleep time, deep/REM minutes, recovery score)
 *  with the koala notification icon.
 *
 * The one-tap "Capture last night" button calls [captureNow]; the manual SAF
 * import on the Home screen remains as the final fallback when Shizuku is
 * unavailable.
 */
class MorningCaptureCoordinator(
    private val context: Context,
    private val fetcher: SnoopAutoFetcher,
    private val store: SnoopCaptureStore,
    private val processNight: ProcessNightUseCase,
    private val recoveryRepo: RecoveryRepository,
    private val repo: com.example.sploot.data.repo.SessionRepository,
    private val deviceRepo: com.example.sploot.ble.PolarDeviceRepository,
    private val puller: PolarOfflineRecordingPuller,
) {

    sealed class Status {
        data object Idle : Status()
        data class Running(val step: String) : Status()
        data class Done(val message: String) : Status()
        data class Failed(val message: String) : Status()
    }

    sealed class CaptureResult {
        data object Unchanged : CaptureResult()
        data class Captured(val sessionId: String?, val message: String? = null) : CaptureResult()
        data class Failed(val message: String) : CaptureResult()
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status = _status.asStateFlow()

    private val mutex = Mutex()

    /**
     * App-open hook: runs the whole capture silently when auto-capture is
     * enabled and Shizuku is up. Never throws — a quiet no-op is preferred
     * over nagging the user at 7am.
     *
     * Shizuku-down fallback (on-device recovery 2026-11-02): when the previous
     * fetch left a still-valid btsnoop capture in the app cache AND the stored
     * fingerprint predates the current ingest version (a decoding fix awaits),
     * the cached capture is reprocessed directly — no privileged shell needed.
     */
    suspend fun maybeAutoCapture() {
        // Serialize with any other capture in flight (Home connect trigger,
        // MorningDownloadService, manual retry).
        if (!mutex.tryLock()) return
        try {
            maybeAutoCaptureLocked()
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun maybeAutoCaptureLocked() {
        // 1. Primary: pull the night straight from the sensor's internal
        //    memory (offline recordings). Survives log rotation, cache
        //    overwrites and Shizuku being down — the sensor IS the recorder.
        //    Always attempted: an already-imported path list makes it a cheap
        //    no-op, and the sensor being connected is the only requirement.
        if (pullSensorRecordings()) return

        if (!store.isAutoEnabled()) return

        // 2. Fallback: the passive btsnoop pipeline (needs a privileged shell).
        val unavailable = fetcher.availability
        if (unavailable != null) {
            if (processCachedCapture("cached btsnoop capture")) return
            _status.value = Status.Failed(
                "couldn't read the stored nights — connect the sensor and tap “try again”",
            )
            return
        }
        runCaptureLocked()
    }

    /**
     * Downloads every not-yet-imported PPI offline recording from the
     * connected sensor and runs each through the shared pipeline.
     *
     * @return true when at least one recording was processed (Done or
     *   permanent Error) — the caller need not try the snoop fallback.
     */
    private suspend fun pullSensorRecordings(): Boolean {
        // Resolve the sensor: last-known id first, else a short scan (the
        // recording app may never have connected through Sploot).
        val deviceId = deviceRepo.lastKnownDeviceId.value ?: run {
            Log.i(TAG, "no last-known Polar device — scanning")
            val found = kotlinx.coroutines.withTimeoutOrNull(DISCOVER_TIMEOUT_MS) {
                deviceRepo.scan(timeoutMs = DISCOVER_TIMEOUT_MS).firstOrNull()
            } ?: run {
                Log.i(TAG, "no Polar device found nearby — skipping offline-recording pull")
                return false
            }
            Log.i(TAG, "discovered Polar ${found.deviceId} (${found.name})")
            deviceRepo.setLastKnownDevice(found.deviceId, found.name)
            found.deviceId
        }
        val deviceLabel = deviceRepo.lastKnownDeviceName.value ?: "Polar Verity Sense"
        _status.value = Status.Running("checking $deviceLabel for new nights")
        // Auto-connect: the morning flow must work without the user opening
        // the Record tab first. Bounded wait keeps app-open snappy when the
        // sensor is off / out of range.
        if (puller.connectionState.value != com.example.sploot.ble.SensorStatus.ConnectionState.CONNECTED) {
            Log.i(TAG, "connecting to $deviceId for offline-recording pull")
            _status.value = Status.Running("connecting to $deviceLabel")
            try {
                deviceRepo.connectAndRemember(deviceId)
            } catch (e: Exception) {
                Log.w(TAG, "connect failed: ${e.message}")
                _status.value = Status.Failed("Could not connect to $deviceLabel: ${e.message}")
                return false
            }
            val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
            while (puller.connectionState.value !=
                com.example.sploot.ble.SensorStatus.ConnectionState.CONNECTED &&
                System.currentTimeMillis() < deadline
            ) {
                kotlinx.coroutines.delay(500)
            }
            if (puller.connectionState.value != com.example.sploot.ble.SensorStatus.ConnectionState.CONNECTED) {
                Log.i(TAG, "sensor did not connect within ${CONNECT_TIMEOUT_MS / 1000}s — skipping pull")
                deviceRepo.stopAutoReconnect()
                _status.value = Status.Failed(
                    "$deviceLabel did not connect within ${CONNECT_TIMEOUT_MS / 1000}s — " +
                        "make sure it is charged and nearby, then try again.",
                )
                return false
            }
            // The SDK fires bleSdkFeatureReady(FEATURE_POLAR_OFFLINE_RECORDING)
            // after service discovery; calling listOfflineRecordings earlier
            // NPEs inside the file-transfer client (on-device 2026-11-02).
            val readyDeadline = System.currentTimeMillis() + FEATURE_TIMEOUT_MS
            while (!puller.client.offlineRecordingReady.value && System.currentTimeMillis() < readyDeadline) {
                kotlinx.coroutines.delay(250)
            }
            if (!puller.client.offlineRecordingReady.value) {
                Log.i(TAG, "offline-recording feature not ready within ${FEATURE_TIMEOUT_MS / 1000}s — skipping pull")
                deviceRepo.stopAutoReconnect()
                _status.value = Status.Failed("$deviceLabel connected but did not offer its stored recordings")
                return false
            }
            Log.i(TAG, "offline-recording feature ready")
        } else {
            // Already connected — still wait for the feature flag on a fresh
            // process (SDK service discovery may just have completed).
            val readyDeadline = System.currentTimeMillis() + FEATURE_TIMEOUT_MS
            while (!puller.client.offlineRecordingReady.value && System.currentTimeMillis() < readyDeadline) {
                kotlinx.coroutines.delay(250)
            }
        }
        val imported = store.importedRecordingPaths.first()
        return try {
            _status.value = Status.Running("looking for new nights on $deviceLabel")
            val recovered = puller.recoverPpiRecordings(deviceId, imported)
            if (recovered.isEmpty()) {
                Log.i(TAG, "no new PPI recordings on sensor")
                _status.value = Status.Done("No new nights on $deviceLabel — everything already downloaded.")
                return false
            }
            var processedAny = false
            for (rec in recovered) {
                Log.i(
                    TAG,
                    "pulling sensor recording ${rec.entry.path} (${rec.entry.size} B, ${rec.samples.size} PPI)",
                )
                _status.value = Status.Running(
                    "downloading ${rec.entry.date} night from $deviceLabel " +
                        "(${rec.entry.size / 1024} KB)",
                )
                // A previous failed run may have left a ghost session row
                // (kill mid non-atomic persist) showing time-but-no-stages,
                // or an older re-staged duplicate of the same night; clean
                // before writing the real night.
                runCatching { repo.deleteOrphanSnoopSessions() }
                runCatching { repo.dedupeRecordingSessions() }
                val state = processNight.processOfflineRecording(
                    rec.samples,
                    rec.entry.path,
                )
                when (state) {
                    is ProcessingState.Done -> {
                        store.markRecordingImported(rec.entry.path)
                        store.resetCacheFailures()
                        val recovery = recoveryRepo.computeMorningScore(LocalDate.now())?.recoveryScore
                        postMorningNotification(state.summary, recovery, emptyList())
                        val s = state.summary
                        _status.value = Status.Done(
                            buildString {
                                append("Captured from sensor: ${rec.entry.path}")
                                if (s != null) {
                                    append(" — sleep ${fmtMin(s.tstMin)}, deep ${fmtMin(s.deepMin)}, REM ${fmtMin(s.remMin)}")
                                }
                                if (recovery != null) append(", recovery $recovery/100")
                            },
                        )
                        processedAny = true
                    }
                    is ProcessingState.Error -> {
                        // A corrupted/partial recording is not retried forever.
                        store.markRecordingImported(rec.entry.path)
                        _status.value = Status.Failed(
                            "Sensor recording ${rec.entry.path} failed: ${state.message}",
                        )
                        processedAny = true
                    }
                    else -> Unit
                }
            }
            processedAny
        } catch (e: Exception) {
            Log.w(TAG, "sensor pull failed", e)
            false // fall through to the snoop path
        }
    }

    /**
     * One-tap / connect-triggered entry point. Sensor-first: pulls new
     * offline recordings directly from the Polar armband, and only falls
     * back to the Shizuku btsnoop pipeline when the sensor had nothing
     * new (or could not be reached).
     */
    suspend fun captureNow(): CaptureResult {
        // Serialize concurrent triggers (app-open auto capture + the Home
        // connect trigger + a manual retry can race); second caller no-ops.
        if (!mutex.tryLock()) {
            _status.value = Status.Failed("A download is already in progress")
            return CaptureResult.Failed("a download is already in progress")
        }
        try {
            if (pullSensorRecordings()) return CaptureResult.Captured(null, null)
            return runCaptureLocked()
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Reprocesses an existing valid cached capture when the stored fingerprint
     * is stale (older ingest version) — the "decoding fix landed, re-run the
     * same log" path. Returns true when the cached capture was consumed.
     */
    // Caller must already hold [mutex].
    private suspend fun processCachedCapture(sourceLabel: String): Boolean {
        val cache = fetcher.cacheFile
        if (!cache.isFile || cache.length() < 16L) return false
        val stored = store.lastFingerprint.first()
        val version = store.lastIngestVersion.first()
        if (stored != null && version != null && version >= SnoopCaptureStore.INGEST_VERSION) {
            // Baseline is current — but a previous attempt may have died after
            // the session-row upsert and before the child inserts (kill during
            // the non-atomic persist on-device 2026-11-02). Ghost rows would
            // block nothing but pollute the diary; purge them quietly.
            val purged = runCatching { repo.deleteOrphanSnoopSessions() }.getOrDefault(0)
            return purged == 0
        }
        // Deliberately no fingerprint match against [stored]: the cached
        // capture may be NEWER than the one that failed (a later fetch picked
        // up both rotation generations). Any valid cached capture processed
        // under the current ingest version supersedes the stale baseline, and
        // recordImport on success re-anchors the fingerprint.
        val actual = withContext(Dispatchers.IO) { runCatching { SnoopFingerprint.compute(cache) }.getOrNull() }
            ?: return false
        Log.i(TAG, "cached-capture recovery: stale ingest baseline (stored=$stored), reprocessing cache (${actual.sizeBytes} B)")
        val state = ingestAndStore(cache, actual, sourceLabel, warnings = emptyList())
        return state !is ProcessingState.Error
    }

    /** Shared ingest+persist+notify tail for both the fresh-fetch and cached-capture paths. */
    private suspend fun ingestAndStore(
        file: java.io.File,
        fingerprint: SnoopFingerprint.Fingerprint,
        sourceLabel: String,
        warnings: List<String>,
    ): ProcessingState {
        _status.value = Status.Running("processing last night")
        val sessionId = "auto-" + LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-snoop"
        Log.i(TAG, "processing $sessionId from ${file.name} (${fingerprint.sizeBytes} B)")
        val state = processNight.processSnoopImport(
            sessionId = sessionId,
            decode = { onSample ->
                SnoopIngestSession { file.inputStream() }.run(onSample)
            },
        )
        Log.i(TAG, "processing result: $state")
        when (state) {
            is ProcessingState.Done -> {
                store.recordImport(fingerprint)
                val recovery = recoveryRepo.computeMorningScore(LocalDate.now())?.recoveryScore
                postMorningNotification(state.summary, recovery, warnings)
                val s = state.summary
                val msg = buildString {
                    append("Captured ${fingerprint.sizeBytes} bytes via $sourceLabel")
                    if (s != null) {
                        append(
                            " — sleep ${fmtMin(s.tstMin)}, deep ${fmtMin(s.deepMin)}, " +
                                "REM ${fmtMin(s.remMin)}",
                        )
                    }
                    if (recovery != null) append(", recovery $recovery/100")
                    warnings.forEach { append(". ⚠ $it") }
                }
                _status.value = Status.Done(msg)
            }
            is ProcessingState.Error -> {
                // Fetch worked but processing failed. Do NOT record the
                // fingerprint: the next app open must retry the same log, so a
                // decoding gap fixed by an app update auto-recovers the night
                // (on-device 2026-11-02: recording the fingerprint on error
                // permanently locked out the only copy of that night's data).
                _status.value = Status.Failed("Captured log but processing failed: ${state.message}")
            }
            else -> _status.value = Status.Failed("processing interrupted")
        }
        return state
    }

    // Caller must already hold [mutex].
    private suspend fun runCaptureLocked(): CaptureResult {
        val previous = store.effectiveFingerprint()
        val prevSize = store.currentSize()

        _status.value = Status.Running("fetching btsnoop log")
        val result = withContext(Dispatchers.IO) { fetcher.fetch(prevSize) }

        val success = when (result) {
            is SnoopAutoFetcher.Result.Unavailable ->
                return CaptureResult.Failed(
                    "couldn't read the stored nights — connect the sensor and tap “try again”",
                )
            is SnoopAutoFetcher.Result.Failure ->
                return CaptureResult.Failed(result.reason)
            is SnoopAutoFetcher.Result.Success -> result
        }

        _status.value = Status.Running("checking for new data")
        val fingerprint = withContext(Dispatchers.IO) { SnoopFingerprint.compute(success.file) }
        if (!SnoopFingerprint.hasChanged(fingerprint, previous)) {
            val msg = "No new sleep data — the btsnoop log is unchanged since the last import " +
                "(${fingerprint.sizeBytes} bytes via ${success.method})."
            _status.value = Status.Done(msg)
            return CaptureResult.Unchanged
        }

        val state = ingestAndStore(success.file, fingerprint, "${success.method}", success.warnings)
        return when (state) {
            is ProcessingState.Done -> CaptureResult.Captured(state.sessionId, null)
            is ProcessingState.Error -> CaptureResult.Failed(state.message)
            else -> CaptureResult.Failed("processing interrupted")
        }
    }

    // ---- notification ----

    private fun postMorningNotification(
        summary: SleepSummary?,
        recoveryScore: Int?,
        warnings: List<String>,
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Morning sleep summary",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val title = "Good morning — last night processed"
        val text = when {
            summary != null -> buildString {
                append("Sleep ${fmtMin(summary.tstMin)} · deep ${fmtMin(summary.deepMin)} · ")
                append("REM ${fmtMin(summary.remMin)}")
                if (recoveryScore != null) append(" · recovery $recoveryScore/100")
                warnings.firstOrNull()?.let { append("\n⚠ ${it.take(120)}") }
            }
            else -> "The night was captured and processed. Open Sploot for details."
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sploot)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return // recording already asked for this; silently skip when declined
        }
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun fmtMin(min: Double): String =
        "${min.toInt()} min"

    companion object {
        private const val TAG = "MorningCapture"
        private const val CHANNEL_ID = "morning_summary"
        private const val NOTIFICATION_ID = 2001

        /** Bounded sensor-connect wait for the morning offline-recording pull. */
        private const val CONNECT_TIMEOUT_MS = 15_000L

        /** Bounded BLE scan when no sensor is remembered yet. */
        private const val DISCOVER_TIMEOUT_MS = 12_000L

        /** Wait for the SDK offline-recording feature after connect. */
        private const val FEATURE_TIMEOUT_MS = 10_000L
    }
}
