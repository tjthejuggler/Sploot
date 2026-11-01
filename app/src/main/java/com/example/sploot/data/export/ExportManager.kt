package com.example.sploot.data.export

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.example.sploot.data.db.SleepStages
import com.example.sploot.data.repo.SessionRepository
import com.example.sploot.data.repo.SleepSummary
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Factory for one export output stream (file name + MIME type). */
fun interface OutputSink {
    fun open(fileName: String, mime: String): OutputStream
}

/** Progress fraction 0..1 plus a human-readable step label. */
fun interface ExportProgress {
    fun onProgress(fraction: Float, step: String)
}

/**
 * Exports a recorded session to a user-chosen SAF tree.
 *
 * Formats:
 *  - CSV: `<id>_intervals.csv`, `<id>_epochs.csv`, `<id>_summary.json`
 *  - JSON: single `<id>_session.json` with everything nested
 *  - EDF+: `<id>.edf` — 1 Hz step-held IBI tachogram channel plus an
 *    "EDF Annotations" channel carrying one TAL per 30 s epoch boundary
 *    (`stage=Wake|Light|Deep|REM`), see [EdfPlusWriter]
 *  - ZIP: `<id>_bundle.zip` — the CSV trio + summary zipped.
 *
 * Parquet decision: SKIPPED. parquet-java/arrow drag in tens of MB of
 * transitive classes plus Hadoop shim conflicts on Android — a poor
 * trade for a mobile APK. The ZIP bundle of CSVs covers the same
 * lossless-portability use case at negligible cost. Revisit only if a
 * downstream toolchain demands columnar input.
 *
 * All I/O runs on [Dispatchers.IO]; [progress] is invoked from the same
 * dispatcher.
 */
class ExportManager(
    private val context: Context,
    private val repo: SessionRepository,
) {

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    // ---- public API (SAF tree Uri) ----

    suspend fun exportCsv(sessionId: String, treeUri: Uri, progress: ExportProgress? = null) =
        withContext(Dispatchers.IO) {
            val sink = treeUriSink(treeUri)
            writeCsvBundle(sessionId, sink, writeSummaryFile = true, progress)
        }

    suspend fun exportJson(sessionId: String, treeUri: Uri, progress: ExportProgress? = null) =
        withContext(Dispatchers.IO) {
            val session = repo.session(sessionId)
                ?: error("session $sessionId not found")
            progress?.onProgress(0.25f, "loaded")

            val intervals = repo.intervals(sessionId)
            progress?.onProgress(0.5f, "intervals")
            val epochs = repo.epochFeatures(sessionId)
            val stages = repo.hypnogram(sessionId)
            progress?.onProgress(0.75f, "epochs")
            val comparisons = repo.comparisons(sessionId)
            val summary = repo.summary(sessionId)

            val payload = json.encodeToString(
                SessionExport(
                    sessionId = session.id,
                    startEpochMs = session.startEpochMs,
                    endEpochMs = session.endEpochMs,
                    deviceId = session.deviceId,
                    deviceModel = session.deviceModel,
                    source = session.source,
                    notes = session.notes,
                    summary = summary,
                    intervals = intervals.map {
                        IntervalExport(
                            it.timestampMs, it.intervalMs, it.source,
                            it.blockerBit, it.skinContact, it.error,
                        )
                    },
                    epochs = epochs.map { f ->
                        val s = stages.firstOrNull { it.epochIndex == f.epochIndex }
                        EpochExport(
                            f.epochIndex, f.epochStartMs, f.validIbiCount,
                            f.featureVector, s?.stage, s?.probabilities, s?.engineId,
                        )
                    },
                    comparisons = comparisons.map {
                        ComparisonExport(it.sourceLabel, it.epochIndex, it.stage)
                    },
                )
            )
            sink(treeUriSink(treeUri), "${session.id}_session.json", "application/json").use { out ->
                out.write(payload.toByteArray())
            }
            progress?.onProgress(1f, "json done")
        }

    suspend fun exportEdfPlus(sessionId: String, treeUri: Uri, progress: ExportProgress? = null) =
        withContext(Dispatchers.IO) {
            val session = repo.session(sessionId)
                ?: error("session $sessionId not found")
            val intervals = repo.intervals(sessionId)
            val stages = repo.hypnogram(sessionId)
            if (intervals.isEmpty() && stages.isEmpty()) {
                error("session $sessionId has no data to export yet — process it first")
            }
            progress?.onProgress(0.2f, "loaded")

            val startMs = session.startEpochMs
            val durationSec = (((session.endEpochMs
                ?: intervals.lastOrNull()?.timestampMs
                ?: stages.lastOrNull()?.let { startMs + (it.epochIndex + 1) * 30_000L }
                ?: startMs) - startMs) / 1000L).coerceAtLeast(1L)

            sink(treeUriSink(treeUri), "${session.id}.edf", "application/octet-stream").use { out ->
                EdfPlusWriter.write(
                    out = out,
                    patientId = "sploot-user",
                    recordingId = "sploot-${session.id}",
                    start = LocalDateTime.ofInstant(Instant.ofEpochMilli(startMs), ZoneId.systemDefault()),
                    ibis = intervals.map {
                        EdfPlusWriter.Ibi((it.timestampMs - startMs) / 1000.0, it.intervalMs)
                    },
                    annotations = stages.map {
                        EdfPlusWriter.Annot(it.epochIndex * 30.0, "stage=${SleepStages.name(it.stage)}")
                    },
                    durationSec = durationSec,
                )
            }
            progress?.onProgress(1f, "edf done")
        }

    suspend fun exportZip(sessionId: String, treeUri: Uri, progress: ExportProgress? = null) =
        withContext(Dispatchers.IO) {
            val session = repo.session(sessionId)
                ?: error("session $sessionId not found")
            val intervals = repo.intervals(sessionId)
            val epochs = repo.epochFeatures(sessionId)
            val stages = repo.hypnogram(sessionId)
            val summary = repo.summary(sessionId)
            progress?.onProgress(0.3f, "loaded")

            val intervalBytes = intervalsCsv(intervals).toByteArray()
            val epochsBytes = epochsCsv(epochs, stages).toByteArray()
            val summaryBytes = summaryJson(summary).toByteArray()
            progress?.onProgress(0.6f, "csv built")

            sink(treeUriSink(treeUri), "${session.id}_bundle.zip", "application/zip").use { raw ->
                ZipOutputStream(raw).use { zip ->
                    zip.putNextEntry(ZipEntry("${session.id}_intervals.csv"))
                    zip.write(intervalBytes); zip.closeEntry()
                    zip.putNextEntry(ZipEntry("${session.id}_epochs.csv"))
                    zip.write(epochsBytes); zip.closeEntry()
                    zip.putNextEntry(ZipEntry("${session.id}_summary.json"))
                    zip.write(summaryBytes); zip.closeEntry()
                }
            }
            progress?.onProgress(1f, "zip done")
        }

    // ---- internal builders (JVM-testable) ----

    internal suspend fun writeCsvBundle(
        sessionId: String,
        sink: OutputSink,
        writeSummaryFile: Boolean,
        progress: ExportProgress?,
    ) {
        val session = repo.session(sessionId) ?: error("session $sessionId not found")
        sink.open("${session.id}_intervals.csv", "text/csv").use { out ->
            out.write(intervalsCsv(repo.intervals(sessionId)).toByteArray())
        }
        progress?.onProgress(0.4f, "intervals.csv")
        sink.open("${session.id}_epochs.csv", "text/csv").use { out ->
            out.write(epochsCsv(repo.epochFeatures(sessionId), repo.hypnogram(sessionId)).toByteArray())
        }
        progress?.onProgress(0.8f, "epochs.csv")
        if (writeSummaryFile) {
            sink.open("${session.id}_summary.json", "application/json").use { out ->
                out.write(summaryJson(repo.summary(sessionId)).toByteArray())
            }
        }
        progress?.onProgress(1f, "done")
    }

    internal fun intervalsCsv(rows: List<com.example.sploot.data.db.RawIntervalEntity>): String =
        buildString {
            append("timestampMs,intervalMs,source,blockerBit,skinContact,error\r\n")
            rows.forEach {
                append(
                    Csv.line(
                        listOf(
                            it.timestampMs.toString(),
                            it.intervalMs.toString(),
                            it.source,
                            it.blockerBit?.toString(),
                            it.skinContact?.toString(),
                            it.error?.toString(),
                        )
                    )
                )
                append("\r\n")
            }
        }

    internal fun epochsCsv(
        features: List<com.example.sploot.data.db.EpochFeatureEntity>,
        stages: List<com.example.sploot.data.db.StageEntity>,
    ): String {
        val stageByIndex = stages.associateBy { it.epochIndex }
        return buildString {
            append("epochIndex,epochStartMs,validIbiCount,stage,stageName,engineId,features,probabilities\r\n")
            features.sortedBy { it.epochIndex }.forEach { f ->
                val s = stageByIndex[f.epochIndex]
                append(
                    Csv.line(
                        listOf(
                            f.epochIndex.toString(),
                            f.epochStartMs.toString(),
                            f.validIbiCount.toString(),
                            s?.stage?.toString(),
                            s?.let { SleepStages.name(it.stage) },
                            s?.engineId,
                            f.featureVector?.joinToString(";") { v -> v.toString() },
                            s?.probabilities?.joinToString(";") { v -> v.toString() },
                        )
                    )
                )
                append("\r\n")
            }
        }
    }

    internal fun summaryJson(summary: SleepSummary?): String =
        summary?.let { json.encodeToString(it) } ?: "{}"

    // ---- SAF helpers ----

    private fun treeUriSink(treeUri: Uri): OutputSink = OutputSink { name, mime ->
        // SAF permission can be revoked by the user or the system mid-flow;
        // surface a message the UI can act on (re-select the folder) instead
        // of a raw SecurityException crash.
        val docUri = try {
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
        } catch (e: SecurityException) {
            throw IllegalStateException(
                "export folder permission was revoked — please re-select the destination folder", e,
            )
        }
        val created = try {
            DocumentsContract.createDocument(context.contentResolver, docUri, mime, name)
                ?: error("could not create document $name (folder missing or full?)")
        } catch (e: SecurityException) {
            throw IllegalStateException(
                "export folder permission was revoked — please re-select the destination folder", e,
            )
        }
        context.contentResolver.openOutputStream(created)
            ?: error("could not open output stream for $name")
    }

    private fun sink(factory: OutputSink, name: String, mime: String): OutputStream =
        factory.open(name, mime)

    // ---- nested JSON export DTOs ----

    @Serializable
    internal data class IntervalExport(
        val timestampMs: Long,
        val intervalMs: Int,
        val source: String,
        val blockerBit: Boolean? = null,
        val skinContact: Boolean? = null,
        val error: Int? = null,
    )

    @Serializable
    internal data class EpochExport(
        val epochIndex: Int,
        val epochStartMs: Long,
        val validIbiCount: Int,
        val features: List<Float>? = null,
        val stage: Int? = null,
        val probabilities: List<Float>? = null,
        val engineId: String? = null,
    )

    @Serializable
    internal data class ComparisonExport(
        val sourceLabel: String,
        val epochIndex: Int,
        val stage: Int,
    )

    @Serializable
    internal data class SessionExport(
        val sessionId: String,
        val startEpochMs: Long,
        val endEpochMs: Long?,
        val deviceId: String,
        val deviceModel: String?,
        val source: String,
        val notes: String?,
        val summary: SleepSummary?,
        val intervals: List<IntervalExport>,
        val epochs: List<EpochExport>,
        val comparisons: List<ComparisonExport>,
    )
}
