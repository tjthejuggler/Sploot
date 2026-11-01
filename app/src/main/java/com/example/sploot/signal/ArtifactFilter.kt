package com.example.sploot.signal

import com.example.sploot.ble.IbiSample

/**
 * One accepted beat of the cleaned beat series.
 *
 * @param timestampMs wall-clock timestamp of the beat (R/R peak occurrence).
 * @param ibiMs accepted inter-beat interval in milliseconds.
 */
data class CleanBeat(val timestampMs: Long, val ibiMs: Double)

/** Reason an interval was rejected. */
enum class RejectionReason {
    BLOCKER_BIT,
    SKIN_CONTACT,
    ERROR_FLAG,
    OUT_OF_BOUNDS,
    ECTOPIC,
}

data class RejectedIbi(val timestampMs: Long, val intervalMs: Int, val reason: RejectionReason)

/**
 * Result of artifact filtering over a session.
 *
 * @property clean accepted beats, ordered by timestamp.
 * @property rejected rejected intervals, ordered by timestamp, with reasons.
 */
data class FilterResult(val clean: List<CleanBeat>, val rejected: List<RejectedIbi>) {
    val rejectedCount: Int get() = rejected.size
}

/**
 * Artifact rejection for beat-to-beat intervals.
 *
 * Pipeline (in order):
 *  1. Flag-based rejection: `blockerBit == true`, `skinContact == false`, or a
 *     PPI error estimate above [ERROR_LIMIT_MS] (Polar PPI reports error == 0
 *     for a clean interval; small non-zero values are normal quantization —
 *     only large error estimates indicate a corrupted interval).
 *  2. Physiological bounds: 300–2200 ms.
 *  3. Kubios-style threshold filter: an interval is flagged ectopic
 *     (missed / extra detection) when it deviates more than [DEVIATION_THRESHOLD]
 *     (20%) from the local 5-beat moving median (the beat itself plus its two
 *     surviving neighbours on each side, recomputed over flag/bounds-passed
 *     intervals).
 *
 * Missing-beat reconstruction is intentionally NOT performed here — it happens
 * downstream (Interpolator emits NaN gap regions instead).
 *
 * Pure Kotlin, no Android dependencies.
 */
class ArtifactFilter(
    private val minIbiMs: Int = MIN_IBI_MS,
    private val maxIbiMs: Int = MAX_IBI_MS,
    private val deviationThreshold: Double = DEVIATION_THRESHOLD,
    private val errorLimitMs: Int = ERROR_LIMIT_MS,
) {

    fun filter(samples: List<IbiSample>): FilterResult {
        val sorted = samples.sortedBy { it.timestampMs }
        val rejected = mutableListOf<RejectedIbi>()
        val survivors = ArrayList<IbiSample>(sorted.size)

        // Pass 1: flag + bounds rejection.
        for (s in sorted) {
            val reason = when {
                s.blockerBit == true -> RejectionReason.BLOCKER_BIT
                s.skinContact == false -> RejectionReason.SKIN_CONTACT
                s.error != null && s.error > errorLimitMs -> RejectionReason.ERROR_FLAG
                s.intervalMs < minIbiMs || s.intervalMs > maxIbiMs -> RejectionReason.OUT_OF_BOUNDS
                else -> null
            }
            if (reason != null) {
                rejected += RejectedIbi(s.timestampMs, s.intervalMs, reason)
            } else {
                survivors += s
            }
        }

        // Pass 2: Kubios-style 20% vs local 5-beat moving median.
        val n = survivors.size
        val keep = BooleanArray(n) { true }
        for (i in survivors.indices) {
            val window = IntArray(0)
            var count = 0
            val vals = DoubleArray(5)
            // Two surviving neighbours on each side (skipping already-removed
            // neighbours would bias toward cascade removal; we use positional
            // neighbours and evaluate against the median including them, which
            // is the classic moving-median formulation and keeps the filter
            // robust to bursts of ectopics).
            var j = i - 2
            while (j <= i + 2) {
                if (j in 0 until n) {
                    vals[count++] = survivors[j].intervalMs.toDouble()
                }
                j++
            }
            val windowVals = vals.copyOf(count)
            val median = median(windowVals)
            val x = survivors[i].intervalMs.toDouble()
            if (median > 0 && kotlin.math.abs(x - median) > deviationThreshold * median) {
                keep[i] = false
                rejected += RejectedIbi(
                    survivors[i].timestampMs,
                    survivors[i].intervalMs,
                    RejectionReason.ECTOPIC,
                )
            }
        }

        val clean = ArrayList<CleanBeat>(n)
        for (i in survivors.indices) {
            if (keep[i]) clean += CleanBeat(survivors[i].timestampMs, survivors[i].intervalMs.toDouble())
        }
        return FilterResult(clean, rejected)
    }

    private fun median(v: DoubleArray): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
    }

    companion object {
        const val MIN_IBI_MS = 300
        const val MAX_IBI_MS = 2200
        const val DEVIATION_THRESHOLD = 0.20
        const val ERROR_LIMIT_MS = 100
    }
}
