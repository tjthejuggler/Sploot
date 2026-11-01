package com.example.sploot.data.repo

import com.example.sploot.data.db.ComparisonEntity
import com.example.sploot.data.db.DailyBaselineEntity
import com.example.sploot.data.db.EpochFeatureEntity
import com.example.sploot.data.db.RawIntervalEntity
import com.example.sploot.data.db.SessionDao
import com.example.sploot.data.db.SessionEntity
import com.example.sploot.data.db.SleepStages
import com.example.sploot.data.db.SplootDatabase
import com.example.sploot.data.db.StageEntity
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json

/**
 * Repository over the Room schema: session lifecycle, chunked bulk inserts
 * for derived rows, and the queries the UI/export layers need.
 */
class SessionRepository(private val db: SplootDatabase) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val sessionDao: SessionDao = db.sessionDao()

    companion object {
        /** Chunk sizes keep SQLite bind-variable counts comfortably low. */
        const val FEATURE_CHUNK = 500
        const val STAGE_CHUNK = 500
        const val IBI_CHUNK = 1000
    }

    // ---- lifecycle ----

    suspend fun createSession(session: SessionEntity) = sessionDao.upsert(session)

    suspend fun completeSession(id: String, endEpochMs: Long, notes: String? = null) =
        sessionDao.complete(id, endEpochMs, notes)

    suspend fun deleteSession(id: String) = sessionDao.delete(id)

    // ---- bulk inserts ----

    suspend fun insertEpochFeatures(rows: List<EpochFeatureEntity>) =
        rows.chunked(FEATURE_CHUNK).forEach { db.epochFeatureDao().insertAll(it) }

    suspend fun insertStages(rows: List<StageEntity>) =
        rows.chunked(STAGE_CHUNK).forEach { db.stageDao().insertAll(it) }

    suspend fun insertIntervals(rows: List<RawIntervalEntity>) =
        rows.chunked(IBI_CHUNK).forEach { db.rawIntervalDao().insertAll(it) }

    suspend fun insertComparisons(rows: List<ComparisonEntity>) =
        rows.chunked(STAGE_CHUNK).forEach { db.comparisonDao().insertAll(it) }

    // ---- queries ----

    suspend fun session(id: String): SessionEntity? = sessionDao.byId(id)

    suspend fun sessions(): List<SessionEntity> = sessionDao.all()

    suspend fun hypnogram(sessionId: String): List<StageEntity> =
        db.stageDao().forSession(sessionId)

    suspend fun epochFeatures(sessionId: String): List<EpochFeatureEntity> =
        db.epochFeatureDao().forSession(sessionId)

    suspend fun intervals(sessionId: String): List<RawIntervalEntity> =
        db.rawIntervalDao().forSession(sessionId)

    suspend fun comparisons(sessionId: String): List<ComparisonEntity> =
        db.comparisonDao().forSession(sessionId)

    suspend fun summary(sessionId: String): SleepSummary? =
        sessionDao.byId(sessionId)?.sleepSummaryJson?.let {
            runCatching { json.decodeFromString<SleepSummary>(it) }.getOrNull()
        }

    // ---- summary pipeline ----

    /**
     * Loads stages + IBIs for [sessionId], computes the [SleepSummary] via
     * [SummaryCalculator], persists it on the session row and mirrors the
     * two baseline inputs (nocturnal rMSSD, lowest RHR) into
     * [DailyBaselineEntity] for the night's local date.
     *
     * @return the computed summary, or null if the session does not exist.
     */
    suspend fun computeAndStoreSummary(sessionId: String): SleepSummary? {
        val session = sessionDao.byId(sessionId) ?: return null
        val stages = hypnogram(sessionId)
        val ibis = intervals(sessionId)
        if (stages.isEmpty() && ibis.isEmpty()) return null

        val summary = SummaryCalculator.calculate(
            // epochStartMs is not stored on StageEntity; epochIndex preserves order
            stages = stages.map { StagePoint(it.epochIndex.toLong(), it.stage) },
            ibis = ibis.map { IbiPoint(it.timestampMs, it.intervalMs) },
        )
        sessionDao.storeSummary(sessionId, json.encodeToString(SleepSummary.serializer(), summary))
        upsertBaselineFromSummary(session, summary)
        return summary
    }

    private suspend fun upsertBaselineFromSummary(session: SessionEntity, summary: SleepSummary) {
        val rmssd = summary.meanRmssdMs ?: return
        val rhr = summary.lowestRhrBpm ?: return
        val date = Instant.ofEpochMilli(session.startEpochMs)
            .atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val existing = db.dailyBaselineDao().byDate(date)
        db.dailyBaselineDao().upsert(
            (existing ?: DailyBaselineEntity(date, rmssd, rhr, null, null)).copy(
                nocturnalRmssd = rmssd,
                nocturnalRhrMin = rhr,
            )
        )
    }

    // ---- mapping helpers used by ingestion + export ----

    fun stageName(stage: Int): String = SleepStages.name(stage)
}
