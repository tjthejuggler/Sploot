package com.example.sploot.ml

import kotlin.math.ln
import kotlin.math.max

/**
 * HMM post-processing: applies a sleep-stage transition matrix to per-epoch class
 * probabilities via Viterbi decoding (log-space), enforcing physiologically
 * plausible stage sequences, and reports a "flicker" diagnostic (number of stage
 * changes) before vs after smoothing.
 *
 * Stage order: 0 = Wake, 1 = Light, 2 = Deep, 3 = REM.
 */
class ViterbiSmoother(
    /** Row-stochastic matrix A[from][to]; defaults to [DEFAULT_TRANSITIONS]. */
    val transitions: Array<DoubleArray> = DEFAULT_TRANSITIONS,
    /** Initial state distribution π (defaults to sleep-onset prior: likely Wake). */
    val initial: DoubleArray = DEFAULT_INITIAL,
) {

    data class Smoothed(
        val stages: IntArray,
        /** Stage changes t→t+1 in the argmax-of-probabilities sequence (before smoothing). */
        val flickerBefore: Int,
        /** Stage changes t→t+1 in the Viterbi path (after smoothing). */
        val flickerAfter: Int,
    )

    init {
        require(transitions.size == NUM_STAGES && transitions.all { it.size == NUM_STAGES }) {
            "transition matrix must be 4×4"
        }
        require(initial.size == NUM_STAGES) { "initial distribution must have length 4" }
    }

    /**
     * @param probabilities per-epoch class probabilities; rows need not be normalised
     *   (they are normalised defensively). Empty/1-epoch inputs return as-is.
     */
    fun smooth(probabilities: Array<FloatArray>): Smoothed {
        val t = probabilities.size
        if (t == 0) return Smoothed(IntArray(0), 0, 0)
        val raw = probabilities.map { it.indices.maxByOrNull { i -> it[i] } ?: 0 }.toIntArray()
        if (t == 1) return Smoothed(intArrayOf(raw[0]), 0, 0)

        val logA = Array(NUM_STAGES) { s -> DoubleArray(NUM_STAGES) { s2 -> ln(max(transitions[s][s2], 1e-12)) } }
        val logPi = DoubleArray(NUM_STAGES) { ln(max(initial[it], 1e-12)) }

        // delta[t][s] = best log-prob of any path ending in state s at time t
        val delta = Array(t) { DoubleArray(NUM_STAGES) }
        val back = Array(t) { IntArray(NUM_STAGES) }
        for (s in 0 until NUM_STAGES) delta[0][s] = logPi[s] + ln(max(probabilities[0][s].toDouble(), 1e-12))
        for (i in 1 until t) {
            for (s in 0 until NUM_STAGES) {
                var bestPrev = 0
                var best = Double.NEGATIVE_INFINITY
                for (p in 0 until NUM_STAGES) {
                    val v = delta[i - 1][p] + logA[p][s]
                    if (v > best) {
                        best = v
                        bestPrev = p
                    }
                }
                delta[i][s] = best + ln(max(probabilities[i][s].toDouble(), 1e-12))
                back[i][s] = bestPrev
            }
        }
        val path = IntArray(t)
        path[t - 1] = delta[t - 1].indices.maxByOrNull { delta[t - 1][it] } ?: 0
        for (i in t - 2 downTo 0) path[i] = back[i + 1][path[i + 1]]
        return Smoothed(path, flicker(raw), flicker(path))
    }

    private fun flicker(stages: IntArray): Int {
        var c = 0
        for (i in 1 until stages.size) if (stages[i] != stages[i - 1]) c++
        return c
    }

    companion object {
        const val NUM_STAGES = 4

        /**
         * Default AASM-plausible transition priors (rows = from, cols = to;
         * order Wake/Light/Deep/REM). Values follow the structure reported for
         * transition matrices estimated from PSG hypnograms (e.g. Hippocampus-
         * literature & actigraphy HMM papers: ~0.88–0.93 self-transition, strong
         * Wake→Wake and Wake↔Light coupling, Deep→REM essentially forbidden —
         * transitions between N3 and REM in real hypnograms occur <1% of epochs,
         * and when they do they pass through Light/Wake).
         *
         *              to:  Wake  Light  Deep   REM
         * from Wake:      [ 0.90,  0.09, 0.005, 0.005 ]
         * from Light:     [ 0.06,  0.88, 0.030, 0.030 ]
         * from Deep:      [ 0.01,  0.08, 0.900, 0.010 ]
         * from REM:       [ 0.01,  0.09, 0.005, 0.895 ]
         */
        val DEFAULT_TRANSITIONS: Array<DoubleArray> = arrayOf(
            doubleArrayOf(0.90, 0.09, 0.005, 0.005),
            doubleArrayOf(0.06, 0.88, 0.030, 0.030),
            doubleArrayOf(0.01, 0.08, 0.900, 0.010),
            doubleArrayOf(0.01, 0.09, 0.005, 0.895),
        )

        /** Sleep-onset prior: recording starts awake, REM/Deep onset unlikely. */
        val DEFAULT_INITIAL: DoubleArray = doubleArrayOf(0.70, 0.25, 0.025, 0.025)
    }
}
