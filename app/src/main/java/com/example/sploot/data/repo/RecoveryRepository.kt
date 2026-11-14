package com.example.sploot.data.repo

import com.example.sploot.data.db.DailyBaselineDao
import com.example.sploot.data.db.DailyBaselineEntity
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Robust distribution stats for one metric: median and IQR (p75 - p25).
 * IQR is clamped to a small epsilon so degenerate (constant) baselines
 * cannot produce infinite z-scores.
 */
data class BaselineStats(
    val median: Double,
    val iqr: Double,
    val nDays: Int,
) {
    companion object {
        const val MIN_DAYS = 3
        const val MIN_IQR = 1e-6
    }
}

/** Rolling baseline pair: rMSSD (higher better) and RHR (lower better). */
data class AutonomicBaselines(
    val rmssd: BaselineStats,
    val rhr: BaselineStats,
)

/**
 * Pure baseline + recovery math (JVM-unit-testable, no Android deps).
 *
 * Recovery score formula (0–100, higher = more recovered):
 *  1. For each metric compute a robust deviation z = (x - median) / IQR,
 *     where median/IQR come from the rolling baseline window.
 *  2. Map to a sub-score: sub = 50 + 25 * z, clamped to [0, 100].
 *     rMSSD uses +z (higher than baseline = recovered); RHR uses -z
 *     (lower than baseline = recovered). A ±2 IQR deviation saturates
 *     the sub-score at 100 / 0 — effectively an IQR-normalized
 *     percentile-style mapping.
 *  3. Blend: recovery = 0.6 * subRmssd + 0.4 * subRhr, rounded to Int.
 *     rMSSD carries the larger weight because parasympathetic rebound is
 *     the primary recovery signal; RHR acts as a secondary anchor.
 *  4. If a baseline has fewer than [BaselineStats.MIN_DAYS] nights, no
 *     score is produced (null) — insufficient history.
 */
object BaselineMath {

    fun computeBaselines(rows: List<DailyBaselineEntity>): AutonomicBaselines? {
        if (rows.size < BaselineStats.MIN_DAYS) return null
        return AutonomicBaselines(
            rmssd = stats(rows.map { it.nocturnalRmssd }),
            rhr = stats(rows.map { it.nocturnalRhrMin }),
        )
    }

    fun stats(values: List<Double>): BaselineStats {
        val sorted = values.sorted()
        val n = sorted.size
        val median = quantile(sorted, 0.5)
        val iqr = (quantile(sorted, 0.75) - quantile(sorted, 0.25))
            .coerceAtLeast(BaselineStats.MIN_IQR)
        return BaselineStats(median, iqr, n)
    }

    /** Linear-interpolated quantile on a pre-sorted list. */
    fun quantile(sorted: List<Double>, q: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        if (sorted.size == 1) return sorted[0]
        val pos = q * (sorted.size - 1)
        val lo = pos.toInt()
        val hi = (lo + 1).coerceAtMost(sorted.size - 1)
        val frac = pos - lo
        return sorted[lo] * (1 - frac) + sorted[hi] * frac
    }

    fun subScore(z: Double): Double = (50 + 25 * z).coerceIn(0.0, 100.0)

    /**
     * Percentile-blended recovery score; see class KDoc for the formula.
     * Returns null when either baseline is too thin.
     */
    fun recoveryScore(
        lastNightRmssd: Double,
        lastNightRhr: Double,
        baselines: AutonomicBaselines?,
    ): Int? {
        if (baselines == null) return null
        val zRmssd = (lastNightRmssd - baselines.rmssd.median) / baselines.rmssd.iqr
        val zRhr = (baselines.rhr.median - lastNightRhr) / baselines.rhr.iqr
        val blended = 0.6 * subScore(zRmssd) + 0.4 * subScore(zRhr)
        return blended.roundToInt()
    }
}

/**
 * Maintains rolling 14/60-day autonomic baselines from [DailyBaselineEntity]
 * and computes the morning Recovery/Readiness score.
 */
class RecoveryRepository(private val dao: DailyBaselineDao) {

    /**
     * Rolling baseline over the [days]-night window ending at [endDate]
     * (inclusive). The window is `endDate - (days - 1) .. endDate`.
     */
    suspend fun baseline(days: Int, endDate: LocalDate): AutonomicBaselines? {
        val from = endDate.minusDays((days - 1).toLong()).toString()
        val rows = dao.range(from, endDate.toString())
        return BaselineMath.computeBaselines(rows)
    }

    suspend fun baseline14(endDate: LocalDate = LocalDate.now()) = baseline(14, endDate)

    suspend fun baseline60(endDate: LocalDate = LocalDate.now()) = baseline(60, endDate)

    /**
     * Morning flow: (re)computes the recovery score for the night of [date]
     * from the trailing 60-day baseline *excluding* [date] itself, and
     * persists it on the [DailyBaselineEntity] row.
     *
     * @param sleepScore optional already-computed sleep-quality score to store.
     * @return the stored row, or null if no baseline history exists yet.
     */
    suspend fun baselineByDate(date: LocalDate): DailyBaselineEntity? = dao.byDate(date.toString())

    suspend fun allBaselines(): List<DailyBaselineEntity> = dao.all()

    suspend fun deleteBaseline(date: LocalDate): Unit = dao.delete(date = date.toString())

    suspend fun computeMorningScore(
        date: LocalDate,
        sleepScore: Int? = null,
        baselineDays: Int = 60,
    ): DailyBaselineEntity? {
        val row = dao.byDate(date.toString()) ?: return null
        val historyEnd = date.minusDays(1)
        val baselines = baseline(baselineDays, historyEnd)
        val score = BaselineMath.recoveryScore(
            lastNightRmssd = row.nocturnalRmssd,
            lastNightRhr = row.nocturnalRhrMin,
            baselines = baselines,
        )
        val updated = row.copy(sleepScore = sleepScore ?: row.sleepScore, recoveryScore = score)
        dao.upsert(updated)
        return updated
    }
}
