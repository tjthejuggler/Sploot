package com.example.sploot.recording

import android.content.Context
import android.content.Intent
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live recording status exposed to (future) UI. */
data class RecordingStatus(
    val sessionId: String?,
    val active: Boolean,
    val deviceId: String?,
) {
    companion object {
        val IDLE = RecordingStatus(null, false, null)
    }
}

/**
 * Owns recording session lifecycle: mints session ids, starts/stops
 * [RecordingForegroundService] and exposes a [StateFlow] of live status.
 */
class SessionManager(private val context: Context) {

    companion object {
        @Volatile private var instance: SessionManager? = null
        fun get(context: Context): SessionManager =
            instance ?: synchronized(this) {
                instance ?: SessionManager(context.applicationContext).also { instance = it }
            }

        private val ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }

    private val _status = MutableStateFlow(RecordingStatus.IDLE)
    val status: StateFlow<RecordingStatus> = _status.asStateFlow()

    /** Session directory convention: `filesDir/sessions/<id>/`. */
    fun sessionDir(sessionId: String): File =
        File(File(context.filesDir, "sessions"), sessionId)

    private val orphanRecovery: OrphanSessionRecovery
        get() = OrphanSessionRecovery(File(context.filesDir, "sessions"))

    /** Sessions on disk that were never finalized (service/battery death). */
    suspend fun findOrphans(isImported: suspend (String) -> Boolean): List<OrphanSession> =
        orphanRecovery.findOrphans(isImported)

    /** Stamps an orphan's index.json complete so processing can run on it. */
    fun markOrphanComplete(orphan: OrphanSession) =
        orphanRecovery.markComplete(orphan)

    fun newSessionId(now: LocalDateTime = LocalDateTime.now()): String =
        now.format(ID_FORMAT)

    /** Starts the foreground recording service for [deviceId]. */
    fun startRecording(deviceId: String): String {
        val sessionId = newSessionId()
        val intent = Intent(context, RecordingForegroundService::class.java).apply {
            action = RecordingForegroundService.ACTION_START
            putExtra(RecordingForegroundService.EXTRA_DEVICE_ID, deviceId)
            putExtra(RecordingForegroundService.EXTRA_SESSION_ID, sessionId)
        }
        RecordingForegroundService.start(context, intent)
        _status.value = RecordingStatus(sessionId, true, deviceId)
        return sessionId
    }

    fun stopRecording() {
        RecordingForegroundService.stop(context)
        _status.value = RecordingStatus.IDLE
    }

    /** Called by the service on its own teardown so status stays truthful. */
    internal fun onServiceStopped() {
        _status.value = RecordingStatus.IDLE
    }
}
