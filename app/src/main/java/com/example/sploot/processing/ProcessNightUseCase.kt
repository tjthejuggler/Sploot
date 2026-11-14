package com.example.sploot.processing

import com.example.sploot.ble.AccSample
import com.example.sploot.ble.IbiSample
import com.example.sploot.ble.IbiSource
import com.example.sploot.ble.RawSample
import com.example.sploot.data.db.EpochFeatureEntity
import com.example.sploot.data.db.RawIntervalEntity
import com.example.sploot.data.db.SessionEntity
import com.example.sploot.data.db.SessionSources
import com.example.sploot.data.db.StageEntity
import com.example.sploot.data.repo.RecoveryRepository
import com.example.sploot.data.repo.SessionRepository
import com.example.sploot.data.repo.SleepSummary
import com.example.sploot.features.FeatureVectorBuilder
import com.example.sploot.ml.SleepStagingEngine
import com.example.sploot.ml.StagingPipeline
import com.example.sploot.recording.RawRecorder
import com.example.sploot.signal.ArtifactFilter
import com.example.sploot.signal.EpochBuilder
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Progress states surfaced by the processing screen. */
sealed class ProcessingState {
    data object Idle : ProcessingState()
    data class Running(val step: String, val fraction: Float) : ProcessingState()
    data class Done(val sessionId: String, val summary: SleepSummary?) : ProcessingState()
    data class Error(val message: String) : ProcessingState()
}

/** One decoded ingestion bundle entering the shared pipeline. */
private data class IngestBundle(val startMs: Long, val ibis: List<IbiSample>, val accs: List<AccSample>)

/**
 * Runs the whole nightly pipeline for one session and persists everything:
 *
 * RawRecorder raw.bin frames (or a pre-decoded [RawSample] stream from a snoop
 * import) → IBI/ACC decode → ArtifactFilter → EpochBuilder →
 * FeatureVectorBuilder → StagingPipeline (learned HMM transitions when
 * present) → EpochFeature / Stage / RawInterval rows via [SessionRepository] →
 * SummaryCalculator → RecoveryRepository morning-score upsert.
 *
 * [onProgress] receives every [ProcessingState] including the terminal
 * [ProcessingState.Done] / [ProcessingState.Error].
 */
class ProcessNightUseCase(
    private val repo: SessionRepository,
    private val recoveryRepo: RecoveryRepository,
    private val engineProvider: () -> SleepStagingEngine,
    private val assets: (String) -> InputStream?,
) {

    companion object {
        /**
         * Minimum epoch count for a processable night (10 epochs = 5 min).
         * Shorter recordings are rejected with a user-comprehensible message
         * rather than producing a meaningless one-stage hypnogram.
         */
        const val MIN_EPOCHS = 10
    }

    /** Processes a live-recorded session from its `raw.bin` session directory. */
    suspend fun processRecordedSession(
        sessionId: String,
        sessionsRoot: File,
        onProgress: (ProcessingState) -> Unit = {},
    ): ProcessingState =
        process(
            sessionId = sessionId,
            deviceId = "polar",
            source = SessionSources.POLAR_SDK,
            onProgress = onProgress,
        ) {
            val dir = File(sessionsRoot, sessionId)
            if (!dir.isDirectory) {
                throw IllegalArgumentException(
                    "session folder '$sessionId' not found — the recording may have been deleted",
                )
            }
            val recorder = RawRecorder(dir, sessionId)
            val startMs = recorder.readHeaderTimestamp()
                ?: throw IllegalArgumentException(
                    "raw.bin is missing or corrupted for session $sessionId — no usable recording was captured",
                )
            val frames = recorder.readFrames()
            val ibis = ArrayList<IbiSample>(frames.size / 2)
            val accs = ArrayList<AccSample>(frames.size / 2)
            for (f in frames) {
                when (f.type) {
                    RawRecorder.TYPE_IBI -> decodeIbi(f.timestampMs, f.payload)?.let { ibis.add(it) }
                    RawRecorder.TYPE_ACC -> decodeAcc(f.timestampMs, f.payload)?.let { accs.add(it) }
                }
            }
            IngestBundle(startMs, ibis, accs)
        }

    /**
     * Processes a pre-decoded [RawSample] stream pulled from the sensor's
     * offline recordings (PPI.REC via the official SDK). Functionally identical
     * to [processSnoopImport] but takes an in-memory sample list instead of a
     * byte stream.
     */
    suspend fun processOfflineRecording(
        samples: List<RawSample>,
        sourcePath: String,
        onProgress: (ProcessingState) -> Unit = {},
    ): ProcessingState =
        process(
            sessionId = "rec-" + java.time.LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) +
                "-" + sourcePath.substringAfterLast('/').substringBefore('.'),
            deviceId = "polar-offline",
            source = SessionSources.SNOOP_IMPORT, // same diary treatment as snoop nights
            onProgress = onProgress,
        ) {
            val ibis = ArrayList<IbiSample>()
            val accs = ArrayList<AccSample>()
            var minTs = Long.MAX_VALUE
            for (sample in samples) {
                when (sample) {
                    is IbiSample -> {
                        ibis.add(sample)
                        if (sample.timestampMs < minTs) minTs = sample.timestampMs
                    }
                    is AccSample -> {
                        accs.add(sample)
                        if (sample.timestampMs < minTs) minTs = sample.timestampMs
                    }
                    else -> Unit
                }
            }
            if (ibis.isEmpty() && accs.isEmpty()) {
                throw IllegalArgumentException("recording $sourcePath contains no usable samples")
            }
            IngestBundle(minTs, ibis, accs)
        }

    /** Processes a btsnoop import: decodes the stream first, then the shared pipeline. */
    suspend fun processSnoopImport(
        sessionId: String,
        decode: ((RawSample) -> Unit) -> Unit,
        onProgress: (ProcessingState) -> Unit = {},
    ): ProcessingState =
        process(
            sessionId = sessionId,
            deviceId = "btsnoop",
            source = SessionSources.SNOOP_IMPORT,
            onProgress = onProgress,
        ) {
            val ibis = ArrayList<IbiSample>()
            val accs = ArrayList<AccSample>()
            var minTs = Long.MAX_VALUE
            decode { sample ->
                when (sample) {
                    is IbiSample -> {
                        ibis.add(sample)
                        if (sample.timestampMs < minTs) minTs = sample.timestampMs
                    }
                    is AccSample -> {
                        accs.add(sample)
                        if (sample.timestampMs < minTs) minTs = sample.timestampMs
                    }
                    else -> Unit
                }
            }
            if (ibis.isEmpty() && accs.isEmpty()) {
                throw IllegalArgumentException("no IBI/ACC samples decoded from btsnoop log")
            }
            IngestBundle(minTs, ibis, accs)
        }

    private suspend fun process(
        sessionId: String,
        deviceId: String,
        source: String,
        onProgress: (ProcessingState) -> Unit,
        ingest: () -> IngestBundle,
    ): ProcessingState = withContext(Dispatchers.Default) {
        try {
            onProgress(ProcessingState.Running("decoding samples", 0.05f))
            val bundle = ingest()
            val startMs = bundle.startMs
            val ibis = bundle.ibis
            val accs = bundle.accs

            onProgress(ProcessingState.Running("artifact filtering", 0.15f))
            val filtered = ArtifactFilter().filter(ibis)
            if (filtered.clean.isEmpty() && accs.isEmpty()) {
                throw IllegalArgumentException(
                    "no valid heart-rate intervals were recorded — was the sensor worn and in " +
                        "contact with skin all night?",
                )
            }

            onProgress(ProcessingState.Running("building epochs", 0.25f))
            val epochs = EpochBuilder().build(startMs, filtered, accs)
            if (epochs.isEmpty()) {
                throw IllegalArgumentException(
                    "no epochs could be built — the recording contains no usable data",
                )
            }
            if (epochs.size < MIN_EPOCHS) {
                throw IllegalArgumentException(
                    "recording too short: ${epochs.size} epoch(s) ≈ ${epochs.size / 2} min — " +
                        "at least $MIN_EPOCHS epochs (5 minutes) are needed for processing",
                )
            }

            onProgress(ProcessingState.Running("extracting features", 0.35f))
            val builder = FeatureVectorBuilder()
            val featureRows = (0 until epochs.size).map { builder.build(epochs, it, startMs) }
            // No usable signal anywhere (e.g. strap never made contact): the
            // pipeline still emits imputed-default stages, but we flag the
            // session so the user knows the hypnogram is not trustworthy.
            val allFeaturesInvalid = epochs.all { !it.hrvValid && !it.accValid }

            onProgress(ProcessingState.Running("staging (${epochs.size} epochs)", 0.55f))
            val engine = engineProvider()
            // Preferred path: raw-IBI network (sleep²-style) staging straight
            // from the interval series; falls back to the feature-based
            // pipeline (heuristic / placeholder models) when unsupported or
            // when too few clean beats survive preprocessing.
            val result = engine.stageRawIbi(
                startMs,
                ibis.map { it.timestampMs to it.intervalMs },
                epochs.size,
            ) ?: StagingPipeline.create(engine, assets).stageNight(
                featureRows.map { ef -> FloatArray(ef.values.size) { ef.values[it].toFloat() } },
            )

            onProgress(ProcessingState.Running("persisting", 0.75f))
            val summary = repo.persistNightAtomic(
                session = SessionEntity(
                    id = sessionId,
                    startEpochMs = startMs,
                    endEpochMs = epochs.last().endMs,
                    deviceId = deviceId,
                    deviceModel = null,
                    source = source,
                    notes = if (allFeaturesInvalid) {
                        "insufficient data: no epoch had usable signal; stages are defaults"
                    } else {
                        null
                    },
                    sleepSummaryJson = null,
                ),
                features = featureRows.map { ef ->
                    EpochFeatureEntity(
                        sessionId = sessionId,
                        epochIndex = ef.epochIndex,
                        epochStartMs = ef.startMs,
                        featureVector = ef.values.map { it.toFloat() },
                        validIbiCount = epochs[ef.epochIndex].ibis.size,
                    )
                },
                stages = result.stages.indices.map { i ->
                    StageEntity(
                        sessionId = sessionId,
                        epochIndex = i,
                        stage = result.stages[i],
                        probabilities = result.probabilities[i].toList(),
                        engineId = result.engineId,
                    )
                },
                intervals = ibis.map {
                    RawIntervalEntity(
                        sessionId = sessionId,
                        timestampMs = it.timestampMs,
                        intervalMs = it.intervalMs,
                        source = it.source.name,
                        blockerBit = it.blockerBit,
                        skinContact = it.skinContact,
                        error = it.error,
                    )
                },
            )

            onProgress(ProcessingState.Running("summarising", 0.9f))
            // sleep² convention: the night is dated by its wake-up morning
            val date = com.example.sploot.SleepDates.nightDate(startMs, epochs.last().endMs)
            recoveryRepo.computeMorningScore(date)
            val done = ProcessingState.Done(sessionId, summary)
            onProgress(done)
            done
        } catch (e: Exception) {
            val userMessage = when (e) {
                is IllegalArgumentException, is IllegalStateException -> e.message
                else -> "processing failed: ${e.message ?: e.javaClass.simpleName}"
            }
            val err = ProcessingState.Error(userMessage ?: "processing failed")
            onProgress(err)
            err
        }
    }

    // ---- raw.bin frame payload codecs (mirror RawRecorder.encodeFrame) ----

    internal fun decodeIbi(timestampMs: Long, payload: ByteArray): IbiSample? {
        if (payload.size < 7) return null
        val interval = readU16(payload, 0)
        if (interval <= 0) return null
        val source = IbiSource.entries.getOrElse(payload[2].toInt()) { IbiSource.PPI }
        val blocker = when (payload[3].toInt()) { 2 -> true; 1 -> false; else -> null }
        val contact = when (payload[4].toInt()) { 2 -> true; 1 -> false; else -> null }
        val err = readI16(payload, 5).let { if (it < 0) null else it }
        return IbiSample(timestampMs, interval, source, blocker, contact, err)
    }

    internal fun decodeAcc(timestampMs: Long, payload: ByteArray): AccSample? {
        if (payload.size < 8) return null
        return AccSample(
            timestampMs = timestampMs,
            sampleRate = readU16(payload, 0),
            x = readI16(payload, 2),
            y = readI16(payload, 4),
            z = readI16(payload, 6),
        )
    }

    private fun readU16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun readI16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() shl 8) or (b[off + 1].toInt() and 0xFF))
}
