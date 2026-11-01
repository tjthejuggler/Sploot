package com.example.sploot.ble.snoop

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
) {

    sealed class Status {
        data object Idle : Status()
        data class Running(val step: String) : Status()
        data class Done(val message: String) : Status()
        data class Failed(val message: String) : Status()
    }

    sealed class CaptureResult {
        data object Unchanged : CaptureResult()
        data class Captured(val sessionId: String?, val message: String) : CaptureResult()
        data class Failed(val message: String) : CaptureResult()
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status = _status.asStateFlow()

    private val mutex = Mutex()

    /**
     * App-open hook: runs the whole capture silently when auto-capture is
     * enabled and Shizuku is up. Never throws — a quiet no-op is preferred
     * over nagging the user at 7am.
     */
    suspend fun maybeAutoCapture() {
        if (!store.isAutoEnabled()) return
        val unavailable = fetcher.availability
        if (unavailable != null) {
            _status.value = Status.Failed(
                "${unavailable.reason}. sploot only needs shizuku at this moment — " +
                    "start it, then tap “capture last night”.",
            )
            return
        }
        runCapture()
    }

    /** One-tap / Settings test-fetch entry point. */
    suspend fun captureNow(): CaptureResult = runCapture()

    private suspend fun runCapture(): CaptureResult = mutex.withLock {
        val previous = store.currentFingerprint()
        val prevSize = store.currentSize()

        _status.value = Status.Running("fetching btsnoop log")
        val result = withContext(Dispatchers.IO) { fetcher.fetch(prevSize) }

        val success = when (result) {
            is SnoopAutoFetcher.Result.Unavailable ->
                return CaptureResult.Failed(
                    result.reason + " — sploot only needs shizuku at this moment. " +
                        "start it, then tap “capture last night”.",
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

        _status.value = Status.Running("processing last night")
        val sessionId = "auto-" + LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-snoop"
        val state = processNight.processSnoopImport(
            sessionId = sessionId,
            decode = { onSample ->
                SnoopIngestSession { success.file.inputStream() }.run(onSample)
            },
        )
        when (state) {
            is ProcessingState.Done -> {
                store.recordImport(fingerprint)
                val recovery = recoveryRepo.computeMorningScore(LocalDate.now())?.recoveryScore
                postMorningNotification(state.summary, recovery, success.warnings)
                val s = state.summary
                val msg = buildString {
                    append("Captured ${fingerprint.sizeBytes} bytes via ${success.method}")
                    if (s != null) {
                        append(
                            " — sleep ${fmtMin(s.tstMin)}, deep ${fmtMin(s.deepMin)}, " +
                                "REM ${fmtMin(s.remMin)}",
                        )
                    }
                    if (recovery != null) append(", recovery $recovery/100")
                    success.warnings.forEach { append(". ⚠ $it") }
                }
                _status.value = Status.Done(msg)
                CaptureResult.Captured(state.sessionId, msg)
            }
            is ProcessingState.Error -> {
                // Fetch worked but processing failed (e.g. sensor not worn) — still
                // record the fingerprint so we do not retry the same log forever.
                store.recordImport(fingerprint)
                _status.value = Status.Failed("Captured log but processing failed: ${state.message}")
                CaptureResult.Failed(state.message)
            }
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
        private const val CHANNEL_ID = "morning_summary"
        private const val NOTIFICATION_ID = 2001
    }
}
