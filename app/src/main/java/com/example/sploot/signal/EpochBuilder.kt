package com.example.sploot.signal

import com.example.sploot.ble.AccSample

/**
 * Aggregates for one 30 s AASM epoch.
 *
 * @property hrvValid true when ≥ [MIN_ACCEPTABLE_IBI_RATIO] of the intervals
 *   whose beats fall inside this epoch were accepted — the gate for computing
 *   HRV features.
 * @property accValid true when a minimum number of ACC samples is present.
 */
data class EpochData(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val ibis: List<CleanBeat>,
    val rejectedCount: Int,
    val accSamples: List<AccSample>,
    val hrvValid: Boolean,
    val accValid: Boolean,
) {
    val ibiValues: DoubleArray get() = DoubleArray(ibis.size) { ibis[it].ibiMs }
    val totalIbiCount: Int get() = ibis.size + rejectedCount
    val acceptableIbiRatio: Double
        get() = if (totalIbiCount == 0) 0.0 else ibis.size.toDouble() / totalIbiCount
}

/**
 * Slices a night into aligned 30 s AASM epochs anchored at the session start
 * time and groups cleaned IBIs and ACC samples per epoch. Beat timestamps are
 * taken as the END of each interval (R-peak occurrence), so an IBI belongs to
 * the epoch containing its terminating beat.
 *
 * Pure Kotlin, no Android dependencies.
 */
class EpochBuilder(
    val epochLengthMs: Long = EPOCH_LENGTH_MS,
    val minAcceptableIbiRatio: Double = MIN_ACCEPTABLE_IBI_RATIO,
    val minAccSamples: Int = MIN_ACC_SAMPLES,
) {

    /**
     * @param sessionStartMs wall-clock anchor of epoch 0.
     * @param filtered output of [ArtifactFilter].
     * @param accSamples raw ACC stream (any timestamps outside beats are fine).
     */
    fun build(
        sessionStartMs: Long,
        filtered: FilterResult,
        accSamples: List<AccSample>,
    ): List<EpochData> {
        if (filtered.clean.isEmpty() && filtered.rejected.isEmpty() && accSamples.isEmpty()) {
            return emptyList()
        }
        val maxTs = maxOf(
            filtered.clean.lastOrNull()?.timestampMs ?: sessionStartMs,
            filtered.rejected.lastOrNull()?.timestampMs ?: sessionStartMs,
            accSamples.lastOrNull()?.timestampMs ?: sessionStartMs,
        )
        val nEpochs = (((maxTs - sessionStartMs) / epochLengthMs) + 1).toInt()

        val cleanByEpoch = Array(nEpochs) { mutableListOf<CleanBeat>() }
        for (b in filtered.clean) {
            val idx = epochIndex(b.timestampMs, sessionStartMs)
            if (idx in 0 until nEpochs) cleanByEpoch[idx] += b
        }
        val rejByEpoch = IntArray(nEpochs)
        for (r in filtered.rejected) {
            val idx = epochIndex(r.timestampMs, sessionStartMs)
            if (idx in 0 until nEpochs) rejByEpoch[idx]++
        }
        val accByEpoch = Array(nEpochs) { mutableListOf<AccSample>() }
        for (a in accSamples) {
            val idx = epochIndex(a.timestampMs, sessionStartMs)
            if (idx in 0 until nEpochs) accByEpoch[idx] += a
        }

        return (0 until nEpochs).map { i ->
            val start = sessionStartMs + i * epochLengthMs
            val ibis = cleanByEpoch[i]
            val total = ibis.size + rejByEpoch[i]
            val ratio = if (total == 0) 0.0 else ibis.size.toDouble() / total
            EpochData(
                index = i,
                startMs = start,
                endMs = start + epochLengthMs,
                ibis = ibis,
                rejectedCount = rejByEpoch[i],
                accSamples = accByEpoch[i],
                hrvValid = total > 0 && ratio >= minAcceptableIbiRatio,
                accValid = accByEpoch[i].size >= minAccSamples,
            )
        }
    }

    private fun epochIndex(ts: Long, sessionStartMs: Long): Int =
        ((ts - sessionStartMs) / epochLengthMs).toInt()

    companion object {
        const val EPOCH_LENGTH_MS = 30_000L
        const val MIN_ACCEPTABLE_IBI_RATIO = 0.60
        const val MIN_ACC_SAMPLES = 100
    }
}
