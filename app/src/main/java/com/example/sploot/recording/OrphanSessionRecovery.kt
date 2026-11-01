package com.example.sploot.recording

import java.io.File
import kotlinx.serialization.json.Json

/**
 * A recording session directory found on disk that was never finalized:
 * `index.json` exists but `endedAtMs == null` (service killed by the OS /
 * battery death before [RawRecorder.close] ran).
 */
data class OrphanSession(
    val sessionId: String,
    val dir: File,
    val index: RawRecorder.SessionIndex,
    /** Recorded span start → last flush, ms (0 when nothing was flushed). */
    val durationMs: Long,
    val ibiCount: Long,
    val accCount: Long,
)

/**
 * Scans `filesDir/sessions/` for orphaned (un-finalized) sessions on next
 * app open so the user can be offered recovery + processing.
 *
 * An orphan qualifies when:
 *  - the directory contains a parseable `index.json`,
 *  - `endedAtMs` is null (recorder never closed cleanly),
 *  - `raw.bin` still exists, and
 *  - the session has not already been imported into the database.
 *
 * Recovery = [markComplete] (stamps `endedAtMs` from the last flush) after
 * which the normal processing flow ([com.example.sploot.processing.ProcessNightUseCase])
 * can run on the recovered directory. Pure Kotlin (java.io only) for full
 * JVM unit-testability.
 */
class OrphanSessionRecovery(private val sessionsRoot: File) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param isImported returns true when the session id already exists in the
     *   Room database.
     */
    suspend fun findOrphans(isImported: suspend (String) -> Boolean = { false }): List<OrphanSession> {
        val dirs = sessionsRoot.listFiles { f -> f.isDirectory } ?: return emptyList()
        val result = mutableListOf<OrphanSession>()
        for (dir in dirs) {
            val idxFile = File(dir, RawRecorder.INDEX_FILE_NAME)
            if (!idxFile.isFile) continue
            val idx = runCatching {
                json.decodeFromString<RawRecorder.SessionIndex>(idxFile.readText())
            }.getOrNull() ?: continue // corrupted index — not recoverable
            if (idx.endedAtMs != null) continue // finalized normally
            if (!File(dir, RawRecorder.RAW_FILE_NAME).isFile) continue
            if (isImported(idx.sessionId)) continue // already in DB
            val end = idx.lastFlushMs ?: idx.startedAtMs
            result.add(
                OrphanSession(
                    sessionId = idx.sessionId,
                    dir = dir,
                    index = idx,
                    durationMs = (end - idx.startedAtMs).coerceAtLeast(0),
                    ibiCount = idx.counters.ibi,
                    accCount = idx.counters.acc,
                ),
            )
        }
        return result.sortedBy { it.index.startedAtMs } // oldest first
    }

    /**
     * Finalizes the orphan's `index.json`: stamps `endedAtMs` from the last
     * successful flush (or [nowMs] when nothing was ever flushed). Idempotent.
     */
    fun markComplete(orphan: OrphanSession, nowMs: Long = System.currentTimeMillis()) {
        val idxFile = File(orphan.dir, RawRecorder.INDEX_FILE_NAME)
        if (!idxFile.isFile) return
        runCatching {
            val current = json.decodeFromString<RawRecorder.SessionIndex>(idxFile.readText())
            val ended = current.endedAtMs ?: current.lastFlushMs ?: nowMs
            idxFile.writeText(json.encodeToString(RawRecorder.SessionIndex.serializer(), current.copy(endedAtMs = ended)))
        }
    }
}
