package com.example.sploot.data.repo

import com.example.sploot.data.db.SleepStages
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One scored 30 s epoch. */
data class StagePoint(val epochStartMs: Long, val stage: Int)

/** One inter-beat interval. */
data class IbiPoint(val timestampMs: Long, val intervalMs: Int)

/**
 * Computed nightly sleep summary. All minutes are rounded to 1 decimal,
 * percentages of TIB (sleep efficiency) or TST (stage breakdown) to 1 decimal.
 */
@Serializable
data class SleepSummary(
    /** Time in bed: total epochs * 30 s. */
    val tibMin: Double,
    /** Total sleep time: non-Wake epochs * 30 s. */
    val tstMin: Double,
    /** Sleep efficiency: TST / TIB * 100. */
    val sleepEfficiencyPct: Double,
    /** Sleep onset latency: epochs from first epoch to first non-Wake epoch. */
    val solMin: Double,
    /** Wake after sleep onset: Wake epochs after first sleep epoch. */
    val wasoMin: Double,
    /** Number of contiguous WASO runs (>= 1 wake epoch) after sleep onset. */
    val wasoEpisodes: Int,
    val wakeMin: Double,
    val lightMin: Double,
    val deepMin: Double,
    val remMin: Double,
    /** Stage minutes as % of TST. */
    val wakePct: Double,
    val lightPct: Double,
    val deepPct: Double,
    val remPct: Double,
    /** Highest 5-minute rolling mean IBI expressed as bpm (resting heart rate). */
    val lowestRhrBpm: Double?,
    /** rMSSD over the night's IBI series (successive-difference RMS, in ms). */
    val meanRmssdMs: Double?,
)

/**
 * Pure-Kotlin nightly summary computation — no Android dependencies, fully
 * unit-testable.
 *
 * Definitions used:
 *  - Epochs are 30 s AASM epochs, ordered by [StagePoint.epochStartMs].
 *  - SOL = index of first non-Wake epoch * 30 s (0 if already asleep in epoch 0).
 *  - WASO counts every Wake epoch after the first non-Wake epoch, including
 *    trailing wake (kept simple by design; episodes = contiguous Wake runs).
 *  - "Lowest 5-min RHR": the highest 5-minute rolling mean of IBI (longer
 *    intervals = lower heart rate) converted to bpm via 60000 / meanMs.
 *    Windows slide sample-by-sample over timestamp-ordered IBIs and require
 *    at least [MIN_IBIS_IN_WINDOW] samples.
 *  - rMSSD = sqrt(mean((IBI[i+1] - IBI[i])^2)) over the timestamp-ordered
 *    night series (standard formula, whole-night for now).
 */
object SummaryCalculator {

    const val EPOCH_MS = 30_000L
    const val RHR_WINDOW_MS = 300_000L
    const val MIN_IBIS_IN_WINDOW = 3
    const val MIN_IBIS_FOR_RMSSD = 3

    fun calculate(stages: List<StagePoint>, ibis: List<IbiPoint>): SleepSummary {
        val ordered = stages.sortedBy { it.epochStartMs }
        val tibMin = ordered.size * EPOCH_MS / 60_000.0

        val firstSleepIdx = ordered.indexOfFirst { it.stage != SleepStages.WAKE }
        val solMin = if (ordered.isEmpty() || firstSleepIdx < 0) tibMin
        else firstSleepIdx * EPOCH_MS / 60_000.0

        val stageCount = IntArray(SleepStages.NAMES.size)
        ordered.forEach { if (it.stage in 0..3) stageCount[it.stage]++ }

        val tstMin = (ordered.size - stageCount[SleepStages.WAKE]) * EPOCH_MS / 60_000.0
        val wasoWake = if (firstSleepIdx < 0) 0
        else ordered.drop(firstSleepIdx + 1).count { it.stage == SleepStages.WAKE }
        val wasoEpisodes = if (firstSleepIdx < 0) 0 else countWakeRuns(ordered.drop(firstSleepIdx + 1))

        val cleanIbis = ibis
            .sortedBy { it.timestampMs }
            .filter { it.intervalMs > 0 }

        return SleepSummary(
            tibMin = round1(tibMin),
            tstMin = round1(tstMin),
            sleepEfficiencyPct = if (tibMin > 0) round1(tstMin / tibMin * 100) else 0.0,
            solMin = round1(solMin),
            wasoMin = round1(wasoWake * EPOCH_MS / 60_000.0),
            wasoEpisodes = wasoEpisodes,
            wakeMin = round1(stageCount[SleepStages.WAKE] * EPOCH_MS / 60_000.0),
            lightMin = round1(stageCount[SleepStages.LIGHT] * EPOCH_MS / 60_000.0),
            deepMin = round1(stageCount[SleepStages.DEEP] * EPOCH_MS / 60_000.0),
            remMin = round1(stageCount[SleepStages.REM] * EPOCH_MS / 60_000.0),
            wakePct = pctOfTst(stageCount[SleepStages.WAKE], tstMin),
            lightPct = pctOfTst(stageCount[SleepStages.LIGHT], tstMin),
            deepPct = pctOfTst(stageCount[SleepStages.DEEP], tstMin),
            remPct = pctOfTst(stageCount[SleepStages.REM], tstMin),
            lowestRhrBpm = lowestRhr(cleanIbis)?.let { round1(it) },
            meanRmssdMs = rmssd(cleanIbis)?.let { round1(it) },
        )
    }

    /** rMSSD in ms over timestamp-ordered IBIs, or null if fewer than 3 samples. */
    fun rmssd(ibis: List<IbiPoint>): Double? {
        if (ibis.size < MIN_IBIS_FOR_RMSSD) return null
        var sumSq = 0.0
        var n = 0
        for (i in 1 until ibis.size) {
            val d = (ibis[i].intervalMs - ibis[i - 1].intervalMs).toDouble()
            sumSq += d * d
            n++
        }
        return sqrt(sumSq / n)
    }

    /**
     * Lowest resting heart rate in bpm: highest 5-min rolling mean IBI
     * (window = [RHR_WINDOW_MS] by timestamp), converted via 60000/mean.
     */
    fun lowestRhr(ibis: List<IbiPoint>): Double? {
        if (ibis.size < MIN_IBIS_IN_WINDOW) return null
        var best = 0.0
        var lo = 0
        var sum = 0L
        for (hi in ibis.indices) {
            sum += ibis[hi].intervalMs
            while (ibis[hi].timestampMs - ibis[lo].timestampMs >= RHR_WINDOW_MS) {
                sum -= ibis[lo].intervalMs
                lo++
            }
            val count = hi - lo + 1
            if (count >= MIN_IBIS_IN_WINDOW) {
                best = max(best, sum.toDouble() / count)
            }
        }
        return if (best <= 0) null else 60_000.0 / best
    }

    private fun countWakeRuns(epochs: List<StagePoint>): Int {
        var runs = 0
        var inRun = false
        for (e in epochs) {
            val wake = e.stage == SleepStages.WAKE
            if (wake && !inRun) runs++
            inRun = wake
        }
        return runs
    }

    private fun pctOfTst(count: Int, tstMin: Double): Double =
        if (tstMin > 0) round1(count * EPOCH_MS / 60_000.0 / tstMin * 100) else 0.0

    private fun round1(v: Double): Double = (v * 10).roundToInt() / 10.0
}
