package com.example.sploot

import com.example.sploot.data.db.DailyBaselineDao
import com.example.sploot.data.db.DailyBaselineEntity
import com.example.sploot.data.repo.AutonomicBaselines
import com.example.sploot.data.repo.BaselineMath
import com.example.sploot.data.repo.BaselineStats
import com.example.sploot.data.repo.RecoveryRepository
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecoveryRepositoryTest {

    private class FakeDao : DailyBaselineDao {
        val rows = mutableMapOf<String, DailyBaselineEntity>()
        override suspend fun upsert(row: DailyBaselineEntity) { rows[row.date] = row }
        override suspend fun byDate(date: String): DailyBaselineEntity? = rows[date]
        override suspend fun range(fromDate: String, toDate: String): List<DailyBaselineEntity> =
            rows.keys.sorted().filter { it in fromDate..toDate }.map { rows.getValue(it) }
        override suspend fun all(): List<DailyBaselineEntity> = rows.values.sortedBy { it.date }
    }

    @Test
    fun `stats median and iqr hand computed`() {
        // sorted: 40,42,44,46,48 -> median 44, q1 42, q3 46
        val s = BaselineMath.stats(listOf(42.0, 48.0, 40.0, 46.0, 44.0))
        assertEquals(44.0, s.median, 1e-9)
        assertEquals(4.0, s.iqr, 1e-9)
        assertEquals(5, s.nDays)
    }

    @Test
    fun `recovery score blends rmssd and rhr sub-scores`() {
        // rmssd z = +1 -> sub 75 ; rhr z = +0.5 -> sub 62.5 ; 0.6*75 + 0.4*62.5 = 70
        val b = AutonomicBaselines(
            rmssd = BaselineStats(44.0, 4.0, 30),
            rhr = BaselineStats(50.0, 4.0, 30),
        )
        assertEquals(70, BaselineMath.recoveryScore(48.0, 48.0, b)!!)
    }

    @Test
    fun `recovery score saturates at extremes`() {
        val b = AutonomicBaselines(
            rmssd = BaselineStats(44.0, 4.0, 30),
            rhr = BaselineStats(50.0, 4.0, 30),
        )
        assertEquals(100, BaselineMath.recoveryScore(999.0, 0.0, b)!!)
        assertEquals(0, BaselineMath.recoveryScore(0.0, 999.0, b)!!)
        assertNull(BaselineMath.recoveryScore(44.0, 50.0, null))
    }

    @Test
    fun `baseline needs minimum history`() {
        assertNull(BaselineMath.computeBaselines(emptyList()))
        assertNull(
            BaselineMath.computeBaselines(
                listOf(DailyBaselineEntity("2026-10-01", 40.0, 50.0, null, null))
            )
        )
    }

    @Test
    fun `repository window excludes today and upserts score`() = runTest {
        val dao = FakeDao()
        val repo = RecoveryRepository(dao)
        val today = LocalDate.of(2026, 10, 31)

        // 4 prior nights, deliberately including one far outside a 14d window
        listOf(
            "2026-10-27" to 44.0,
            "2026-10-28" to 42.0,
            "2026-10-29" to 46.0,
            "2026-10-30" to 40.0,
            "2026-01-01" to 80.0, // outside window
        ).forEach { (d, rmssd) ->
            dao.upsert(DailyBaselineEntity(d, rmssd, 50.0, null, null))
        }
        dao.upsert(DailyBaselineEntity(today.toString(), 999.0, 55.0, null, null))

        // 14-day window ending yesterday so today's outlier row is excluded
        val baseline = repo.baseline14(today.minusDays(1))
        // rmssd sorted: 40,42,44,46 -> median 43 (interp), q1 41.5, q3 44.5 -> iqr 3
        assertEquals(43.0, baseline!!.rmssd.median, 1e-9)
        assertEquals(3.0, baseline.rmssd.iqr, 1e-9)
        assertEquals(4, baseline.rmssd.nDays)

        val updated = repo.computeMorningScore(today, sleepScore = 88)
        assertEquals(88, updated!!.sleepScore)
        // rmssd far above baseline, rhr above (worse) baseline:
        // zRmssd saturated -> 100 ; zRhr = (50-55)/0 floored iqr.. clamp -> 0
        assertEquals(60, updated.recoveryScore)
        assertEquals(updated, dao.byDate(today.toString()))
    }
}
