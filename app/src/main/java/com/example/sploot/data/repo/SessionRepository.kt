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
import androidx.room.withTransaction
import kotlinx.serialization.json.Json

/**
 * Repository over the Room schema: session lifecycle, chunked bulk inserts
 * for derived rows, and the queries the UI/export layers need.
 */
open class SessionRepository(private val db: SplootDatabase) {

    // allowSpecialFloatingPointValues: SleepSummary metrics can be NaN on
    // partial-signal nights (e.g. PPI-only offline recordings with no
    // accelerometer); nullable fields cover most, NaN guards the rest.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        allowSpecialFloatingPointValues = true
    }

    private val sessionDao: SessionDao = db.sessionDao()

    /**
     * Transaction boundary. Real Room transactions in production; JVM fakes
     * (ProcessNightEdgeCaseTest) override this to run the block directly —
     * Room's `withTransaction` needs runtime machinery fakes cannot provide.
     */
    internal open suspend fun <T> inTransaction(block: suspend () -> T): T =
        db.withTransaction { block() }

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

    /**
     * Atomically persists one processed night: session row, epoch features,
     * stages and raw intervals all commit (or roll back) together, then the
     * summary is computed inside the same transaction. This closes the
     * kill-mid-persist window that produced a ghost session row with no
     * children on-device (2026-11-02): a process death now leaves either
     * nothing or a complete night.
     *
     * @return the computed [SleepSummary], or null when nothing usable.
     */
    suspend fun persistNightAtomic(
        session: SessionEntity,
        features: List<EpochFeatureEntity>,
        stages: List<StageEntity>,
        intervals: List<RawIntervalEntity>,
    ): SleepSummary? = inTransaction {
        sessionDao.upsert(session)
        features.chunked(FEATURE_CHUNK).forEach { db.epochFeatureDao().insertAll(it) }
        stages.chunked(STAGE_CHUNK).forEach { db.stageDao().insertAll(it) }
        intervals.chunked(IBI_CHUNK).forEach { db.rawIntervalDao().insertAll(it) }
        // Summary from the rows just written (same transaction, sees them all).
        val summary = SummaryCalculator.calculate(
            stages = stages.map { StagePoint(it.epochIndex.toLong(), it.stage) },
            ibis = intervals.map { IbiPoint(it.timestampMs, it.intervalMs) },
        )
        if (stages.isEmpty() && intervals.isEmpty()) {
            null
        } else {
            sessionDao.storeSummary(session.id, json.encodeToString(SleepSummary.serializer(), summary))
            upsertBaselineFromSummary(session, summary)
            summary
        }
    }

    // ---- bulk inserts ----

    suspend fun insertEpochFeatures(rows: List<EpochFeatureEntity>) =
        rows.chunked(FEATURE_CHUNK).forEach { db.epochFeatureDao().insertAll(it) }

    suspend fun insertStages(rows: List<StageEntity>) =
        rows.chunked(STAGE_CHUNK).forEach { db.stageDao().insertAll(it) }

    suspend fun insertIntervals(rows: List<RawIntervalEntity>) =
        rows.chunked(IBI_CHUNK).forEach { db.rawIntervalDao().insertAll(it) }

    suspend fun insertComparisons(rows: List<ComparisonEntity>) =
        rows.chunked(STAGE_CHUNK).forEach { db.comparisonDao().insertAll(it) }

    /**
     * Sessions from a snoop import with no derived rows — the process died
     * between the session upsert and the child inserts (non-atomic persist,
     * on-device 2026-11-02). They would show up as empty ghost nights in the
     * diary, so they are removed before a retry.
     */
    suspend fun deleteOrphanSnoopSessions(): Int {
        val orphans = sessionDao.all().filter {
            it.source == "SNOOP_IMPORT" &&
                db.stageDao().forSession(it.id).isEmpty() &&
                db.rawIntervalDao().forSession(it.id).isEmpty()
        }
        orphans.forEach { sessionDao.delete(it.id) }
        return orphans.size
    }

    /**
     * Keeps only the best `rec-*` session per calendar start-date, deleting
     * re-staged duplicates (the same sensor recording re-imported after an
     * engine fix — on-device 2026-11-02 left two Nov-1 nights side by side).
     *
     * "Best" = the session with the most derived stage rows; ties break on the
     * lexicographically greatest id (later import). A placeholder-staged row
     * (uniform stages from a fixed-prior model) and a real staged row have the
     * same stage count only when both ran the same engine, in which case the
     * later import wins.
     *
     * @return number of deleted duplicates.
     */
    suspend fun dedupeRecordingSessions(): Int {
        val recs = sessionDao.all().filter { it.id.startsWith("rec-") }
        if (recs.size < 2) return 0
        data class Score(val id: String, val date: String, val stageRows: Int, val idScore: String)
        val scored = recs.map { s ->
            Score(
                id = s.id,
                date = java.time.Instant.ofEpochMilli(s.startEpochMs)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString(),
                stageRows = db.stageDao().forSession(s.id).size,
                idScore = s.id,
            )
        }
        val keep = scored.groupBy { it.date }.map { (_, group) ->
            group.maxWithOrNull(
                compareBy({ it.stageRows }, { it.idScore }),
            )?.id
        }.filterNotNull().toSet()
        var deleted = 0
        for (s in recs) {
            if (s.id !in keep) {
                sessionDao.delete(s.id)
                deleted++
            }
        }
        return deleted
    }

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
