package com.example.sploot

import com.example.sploot.ble.HrSample
import com.example.sploot.ble.IbiSample
import com.example.sploot.ble.IbiSource
import com.example.sploot.data.db.ComparisonDao
import com.example.sploot.data.db.ComparisonEntity
import com.example.sploot.data.db.DailyBaselineDao
import com.example.sploot.data.db.DailyBaselineEntity
import com.example.sploot.data.db.EpochFeatureDao
import com.example.sploot.data.db.EpochFeatureEntity
import com.example.sploot.data.db.RawIntervalDao
import com.example.sploot.data.db.RawIntervalEntity
import com.example.sploot.data.db.SessionDao
import com.example.sploot.data.db.SessionEntity
import com.example.sploot.data.db.SplootDatabase
import com.example.sploot.data.db.StageDao
import com.example.sploot.data.db.StageEntity
import com.example.sploot.data.repo.RecoveryRepository
import com.example.sploot.data.repo.SessionRepository
import com.example.sploot.ml.SleepStagingEngine
import com.example.sploot.ml.SleepStagingResult
import com.example.sploot.processing.ProcessNightUseCase
import com.example.sploot.processing.ProcessingState
import com.example.sploot.recording.RawRecorder
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// ---- in-memory fake DB (no Room runtime needed) ----

private class FakeSessionDao : SessionDao {
    val rows = mutableMapOf<String, SessionEntity>()
    override suspend fun upsert(session: SessionEntity) { rows[session.id] = session }
    override suspend fun byId(id: String): SessionEntity? = rows[id]
    override suspend fun all(): List<SessionEntity> = rows.values.sortedByDescending { it.startEpochMs }
    override suspend fun complete(id: String, endEpochMs: Long, notes: String?) {
        rows[id] = rows[id]!!.copy(endEpochMs = endEpochMs, notes = notes)
    }
    override suspend fun storeSummary(id: String, summaryJson: String) {
        rows[id] = rows[id]!!.copy(sleepSummaryJson = summaryJson)
    }
    override suspend fun delete(id: String) { rows.remove(id) }
}

private class FakeEpochFeatureDao : EpochFeatureDao {
    val rows = mutableListOf<EpochFeatureEntity>()
    override suspend fun insertAll(rows: List<EpochFeatureEntity>) { this.rows += rows }
    override suspend fun forSession(sessionId: String): List<EpochFeatureEntity> =
        rows.filter { it.sessionId == sessionId }.sortedBy { it.epochIndex }
}

private class FakeStageDao : StageDao {
    val rows = mutableListOf<StageEntity>()
    override suspend fun insertAll(rows: List<StageEntity>) { this.rows += rows }
    override suspend fun forSession(sessionId: String): List<StageEntity> =
        rows.filter { it.sessionId == sessionId }.sortedBy { it.epochIndex }
}

private class FakeRawIntervalDao : RawIntervalDao {
    val rows = mutableListOf<RawIntervalEntity>()
    override suspend fun insertAll(rows: List<RawIntervalEntity>) { this.rows += rows }
    override suspend fun forSession(sessionId: String): List<RawIntervalEntity> =
        rows.filter { it.sessionId == sessionId }.sortedBy { it.timestampMs }
    override suspend fun countForSession(sessionId: String): Int =
        rows.count { it.sessionId == sessionId }
}

private class FakeComparisonDao : ComparisonDao {
    val rows = mutableListOf<ComparisonEntity>()
    override suspend fun insertAll(rows: List<ComparisonEntity>) { this.rows += rows }
    override suspend fun forSession(sessionId: String): List<ComparisonEntity> =
        rows.filter { it.sessionId == sessionId }.sortedBy { it.epochIndex }
    override suspend fun labelsForSession(sessionId: String): List<String> =
        rows.filter { it.sessionId == sessionId }.map { it.sourceLabel }.distinct()
}

private class FakeDb : SplootDatabase() {
    override fun createInvalidationTracker(): androidx.room.InvalidationTracker =
        throw UnsupportedOperationException("fake db")
    override fun clearAllTables() = Unit
    val sessions = FakeSessionDao()
    val features = FakeEpochFeatureDao()
    val stages = FakeStageDao()
    val intervals = FakeRawIntervalDao()
    val comparisons = FakeComparisonDao()
    val baselines = object : DailyBaselineDao {
        val rows = mutableMapOf<String, DailyBaselineEntity>()
        override suspend fun upsert(row: DailyBaselineEntity) { rows[row.date] = row }
        override suspend fun byDate(date: String): DailyBaselineEntity? = rows[date]
        override suspend fun range(fromDate: String, toDate: String): List<DailyBaselineEntity> =
            rows.keys.sorted().filter { it in fromDate..toDate }.map { rows.getValue(it) }
        override suspend fun all(): List<DailyBaselineEntity> = rows.values.sortedBy { it.date }
    }
    override fun sessionDao(): SessionDao = sessions
    override fun epochFeatureDao(): EpochFeatureDao = features
    override fun stageDao(): StageDao = stages
    override fun rawIntervalDao(): RawIntervalDao = intervals
    override fun comparisonDao(): ComparisonDao = comparisons
    override fun dailyBaselineDao(): DailyBaselineDao = baselines
}

/** Deterministic uniform-probability engine — enough for structural tests. */
private class FakeEngine : SleepStagingEngine {
    override val engineId = "fake"
    override suspend fun stage(features: List<FloatArray>): SleepStagingResult =
        SleepStagingResult(
            stages = IntArray(features.size),
            probabilities = Array(features.size) { FloatArray(4) { 0.25f } },
            engineId = engineId,
        )
}

/**
 * Edge-case hardening regression suite for ProcessNightUseCase: empty/short
 * sessions, zero valid IBIs, corrupted/truncated raw.bin — every path must
 * yield a user-comprehensible ProcessingState.Error (or a clean Done), never
 * an unhandled exception.
 */
class ProcessNightEdgeCaseTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun useCase(db: FakeDb = FakeDb()): ProcessNightUseCase = ProcessNightUseCase(
        repo = object : SessionRepository(db) {
            @Suppress("UNCHECKED_CAST")
            override suspend fun <T> inTransaction(block: suspend () -> T): T = block() as T
        },
        recoveryRepo = RecoveryRepository(db.baselines),
        engineProvider = { FakeEngine() },
        assets = { null },
    )

    /** Records `seconds` of realistic IBI data (0.9 s ± 60 ms jitter). */
    private fun recordIbis(dir: File, sessionId: String, seconds: Int, seed: Int = 1) {
        val rnd = Random(seed)
        val base = 1_700_000_000_000L
        var now = base
        val recorder = RawRecorder(dir, sessionId, clock = { now })
        recorder.start()
        var t = 0L
        while (t < seconds * 1000L) {
            val ibi = 900 + rnd.nextInt(-60, 61)
            now = base + t
            recorder.append(IbiSample(now, ibi, IbiSource.PPI, false, true, 8))
            t += ibi
        }
        recorder.close()
    }

    @Test
    fun `missing session folder yields comprehensible error`() = runTest {
        val state = useCase().processRecordedSession("nope", tmp.newFolder())
        assertTrue(state is ProcessingState.Error)
        assertTrue((state as ProcessingState.Error).message.contains("not found"))
    }

    @Test
    fun `missing raw bin yields comprehensible error`() = runTest {
        val root = tmp.newFolder()
        File(root, "empty-session").mkdirs()
        val state = useCase().processRecordedSession("empty-session", root)
        assertTrue(state is ProcessingState.Error)
        assertTrue((state as ProcessingState.Error).message.contains("missing or corrupted"))
    }

    @Test
    fun `session shorter than ten epochs is rejected with duration`() = runTest {
        val root = tmp.newFolder()
        recordIbis(File(root, "short"), "short", seconds = 90) // ≈ 3 epochs
        val state = useCase().processRecordedSession("short", root)
        assertTrue(state is ProcessingState.Error)
        val msg = (state as ProcessingState.Error).message
        assertTrue("message should explain the minimum: $msg", msg.contains("recording too short"))
    }

    @Test
    fun `zero valid IBIs is rejected with sensor-contact hint`() = runTest {
        val root = tmp.newFolder()
        // HR frames only — no IBI/ACC ever decoded
        val rec = RawRecorder(File(root, "hr-only"), "hr-only", clock = { 1_700_000_000_000L })
        rec.start()
        var t = 0L
        while (t < 600_000L) {
            rec.append(HrSample(t, 60))
            t += 1_000L
        }
        rec.close()
        val state = useCase().processRecordedSession("hr-only", root)
        assertTrue(state is ProcessingState.Error)
        val msg = (state as ProcessingState.Error).message
        assertTrue("message should mention sensor contact: $msg", msg.contains("heart-rate intervals"))
    }

    @Test
    fun `truncated raw bin tail still processes to Done`() = runTest {
        val root = tmp.newFolder()
        val dir = File(root, "crashed")
        recordIbis(dir, "crashed", seconds = 660) // ≈ 22 epochs
        // Simulate a process kill: APPEND a garbage partial frame (append mode!)
        java.io.FileOutputStream(File(dir, RawRecorder.RAW_FILE_NAME), true).use {
            it.write(byteArrayOf(0x02, 0x00))
        }
        val db = FakeDb()
        val state = useCase(db).processRecordedSession("crashed", root)
        assertTrue("expected Done, got $state", state is ProcessingState.Done)
        val done = state as ProcessingState.Done
        assertTrue(done.summary != null)
        assertTrue(db.stages.rows.isNotEmpty())
        assertEquals("crashed", db.sessions.rows.getValue("crashed").id)
    }

    @Test
    fun `corrupted header produces comprehensible error not crash`() = runTest {
        val root = tmp.newFolder()
        val dir = File(root, "bad-header")
        dir.mkdirs()
        File(dir, RawRecorder.RAW_FILE_NAME).writeBytes(ByteArray(6)) // too short for header
        val state = useCase().processRecordedSession("bad-header", root)
        assertTrue(state is ProcessingState.Error)
        assertTrue((state as ProcessingState.Error).message.contains("missing or corrupted"))
    }
}
