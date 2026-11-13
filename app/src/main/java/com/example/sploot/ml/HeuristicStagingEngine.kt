package com.example.sploot.ml

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Physiologically-grounded heuristic stager — the production engine until the
 * ONNX models are trained on real data (the shipped `sploot-seq`/`sploot-gbdt`
 * files are placeholders emitting a fixed prior; verified on-device 2026-11-02
 * when a fully-recovered night staged as constant [0.10, 0.45, 0.25, 0.20]).
 *
 * Operates on the same per-night-normalized feature matrix as the ONNX engines
 * (post [PerNightNormalizer]: z-scaled where reliable, 0.0-imputed NaN, >50 %-NaN
 * columns pass through unscaled). Consumes the 30 s-window columns (indices
 * follow [com.example.sploot.features.FeatureVectorBuilder.FEATURE_NAMES]):
 *
 *  - `hrv_rmssd` (idx 2): vagal tone — high in Deep/REM, lower in Light, lowest
 *    in Wake.
 *  - `hrv_sdnn` (idx 1): overall variability, similar gradient.
 *  - `hrv_mean_ibi` (idx 0): long beats = low HR = deeper sleep (arousals
 *    shorten IBI).
 *  - `hrv_lf_hf_lsp` (idx 11): sympathovagal balance — rises toward Wake.
 *  - `acc_enmo` (idx 27, when present): movement → Wake; all-zero on
 *    PPI-only nights.
 *  - `hrv_pnn50` (idx 3), `hrv_sd1` (idx 22): short-RR variability supporting
 *    REM (phasic swings).
 *
 * Scoring per epoch: each signal votes with a soft logistic of its
 * night-standardized value; the four stage scores are weighted sums of the
 * votes; a nightly `deep anchor` (how far this night's best-vagal epochs sit
 * above the night median) self-calibrates the Deep threshold so a uniformly
 * low-variability night doesn't hallucinate Deep. The Viterbi smoother
 * downstream enforces physiological stage continuity.
 *
 * Pure Kotlin, deterministic — unit-testable (HeuristicStagingEngineTest).
 */
class HeuristicStagingEngine : SleepStagingEngine {

    override val engineId: String = "sploot-heuristic"

    override suspend fun stage(features: List<FloatArray>): SleepStagingResult =
        withContext(Dispatchers.Default) {
            require(features.isNotEmpty()) { "empty night" }

            // Night-level context for self-calibration.
            val rmssd = features.map { it[RMSSD].toDouble() }.filter { it != 0.0 }
            val rmssdSorted = rmssd.sorted()
            val rmssdMedian = if (rmssdSorted.isEmpty()) 0.0 else rmssdSorted[rmssdSorted.size / 2]
            val rmssdP75 = if (rmssdSorted.isEmpty()) 0.0
            else rmssdSorted[(rmssdSorted.size * 3) / 4]
            // Deep anchor: how much the top-quartile vagal tone exceeds the median.
            val deepAnchor = (rmssdP75 - rmssdMedian).coerceAtLeast(0.0)

            // Was any accelerometer data present? (ENMO column all-zero ⇒ no.)
            val accPresent = features.any { it[ACC_ENMO] != 0f }

            // Sleep2-style dual-baseline HR elevation context (2026-11-03): a
            // flat night-wide lowHr term cannot separate quiet wake from sleep
            // because absolute morning HR (about 47 bpm) overlaps early-night
            // sleep HR (about 50 bpm). Instead:
            //  - ibiSmooth: mean IBI z over +-4 epochs (short-term HR level)
            //  - trailingBaseline: median of the PREVIOUS 90 epochs (45 min
            //    trailing level); trailing, not centered, so a wake period
            //    cannot dilute its own baseline. A sustained HR rise above it
            //    (morning awakening) yields a positive elevation.
            //  - restingSetpoint: 60th percentile of ibiSmooth (deepest-rest
            //    IBI level); elevation above it marks sleep-onset wake.
            // Verified against ground truth on the 2026-11-02 night
            // (22:07-04:05, asleep ~22:30, awake ~03:30): onset wake ends
            // ~22:38, morning wake fires at 03:32 and holds to the end.
            val n = features.size
            val ibiSmooth = DoubleArray(n) { i ->
                val from = maxOf(0, i - SMOOTH_HALF_EPOCHS)
                val to = minOf(n - 1, i + SMOOTH_HALF_EPOCHS)
                var sum = 0.0
                for (j in from..to) sum += features[j][MEAN_IBI]
                sum / (to - from + 1)
            }
            val trailingBaseline = DoubleArray(n) { i ->
                if (i == 0) ibiSmooth[0]
                else median(ibiSmooth, maxOf(0, i - TRAILING_EPOCHS), i - 1)
            }
            val restingSetpoint = quantile(ibiSmooth.sorted(), 0.60)

            val probs = features.mapIndexed { epoch, row ->
                val rmssdZ = row[RMSSD].toDouble()
                val sdnnZ = row[SDNN].toDouble()
                val ibiZ = row[MEAN_IBI].toDouble()
                val lfHfZ = row[LFHF_LSP].toDouble()
                val pnn50Z = row[PNN50].toDouble()
                val sd1Z = row[SD1].toDouble()
                val enmoZ = row[ACC_ENMO].toDouble()

                // Vote strengths in [-1, 1]: positive = more parasympathetic /
                // deeper, negative = more aroused / wakeer.
                val vagal = tanh(0.5 * rmssdZ) * 0.6 + tanh(0.4 * sdnnZ) * 0.25 +
                    tanh(0.4 * sd1Z) * 0.15
                val lowHr = tanh(0.35 * ibiZ)
                val sympathetic = tanh(0.35 * lfHfZ)
                val movement = if (accPresent) tanh(2.0 * enmoZ) else 0.0

                // HR elevation above the two baselines (positive = HR higher
                // than expected for the recent context, i.e. aroused/awake).
                val localElevation = trailingBaseline[epoch] - ibiSmooth[epoch]
                val globalElevation = restingSetpoint - ibiSmooth[epoch]
                val elevation = tanh(0.8 * globalElevation) + 0.5 * tanh(1.2 * localElevation)

                // Circadian position derived from the EPOCH INDEX × 30 s — NOT
                // from feature column 124, which is median/IQR-normalized by
                // PerNightNormalizer and therefore a z-score, not minutes. The
                // 2026-11-03 REM=0 bug: reading the normalized clock column made
                // remClock ≈ −0.78 for every epoch (a constant REM penalty), so
                // REM never won an epoch on real nights.
                val minutesSinceStart = epoch * 0.5
                val remClock = tanh((minutesSinceStart / 60.0 - 3.5) * 0.35)
                val deepClock = tanh((2.5 - minutesSinceStart / 60.0) * 0.2)

                // Wake: strong movement, HR elevation (quiet wake-in-bed has
                // distinctly shorter IBIs than sleep — the primary PPI-only
                // sleep-onset/morning-wake discriminator), suppressed vagal
                // tone, high LF/HF.
                val wakeScore = 1.4 * movement +
                    1.5 * tanh(1.2 * localElevation) + 0.25 * tanh(0.8 * globalElevation) -
                    0.8 * vagal + 0.6 * sympathetic + (if (accPresent) 0.0 else -0.15)
                // Deep: needs vagal tone clearly above the night's own norm,
                // front-loaded in the night.
                val deepScore = vagal * 0.9 + lowHr * 0.5 +
                    (if (deepAnchor > 0.05) tanh(3.0 * (rmssdZ - deepAnchor)) * 0.6 else -0.2) -
                    0.5 * movement + deepClock * 0.35
                // REM: phasic short-term variability clearly ABOVE the night's
                // own norm (hinge-style: below-median pNN50 actively votes
                // against REM), sympathetic tone (REM LF/HF is elevated vs
                // NREM, unlike Deep), late-night clock prior, and an explicit
                // anti-vagal hinge so top-vagal epochs stay Deep.
                val remScore = tanh(2.0 * (pnn50Z - 0.4)) * 0.65 +
                    tanh(1.0 * (sd1Z - 0.4)) * 0.35 +
                    tanh(0.8 * (lfHfZ - 0.3)) * 0.35 +
                    remClock * 0.60 -
                    tanh(1.5 * (rmssdZ - 0.5)) * 0.6 -
                    0.7 * movement -
                    // Suppress REM when HR sits above the resting/trailing
                    // baselines: quiet wake shares REM's low rmssd, and the
                    // late-night clock prior alone used to win REM during
                    // documented awake-in-bed periods.
                    0.6 * (elevation - 0.25).coerceAtLeast(0.0)
                // Light: independent "middling physiology" evidence — everything
                // near the night's median. (The old residual formula coupled
                // Light to |remScore|, which destabilized calibration.)
                val lightScore = 0.38 - 0.4 * abs(vagal) - 0.25 * abs(sympathetic) -
                    0.15 * abs(remClock) - 0.1 * movement

                val raw = doubleArrayOf(wakeScore * 1.2, lightScore, deepScore * 1.3, remScore * 1.25)
                softmax(raw, 0.32) // sharpen so genuine physiological differences separate stages
            }

            SleepStagingResult(
                stages = probs.map { p ->
                    p.indices.maxBy { p[it] }
                }.toIntArray(),
                probabilities = probs.map { arr ->
                    FloatArray(arr.size) { i -> arr[i].toFloat() }
                }.toTypedArray(),
                engineId = engineId,
            )
        }

    private fun tanh(x: Double): Double = kotlin.math.tanh(x)

    private fun abs(x: Double): Double = kotlin.math.abs(x)

    private fun median(v: DoubleArray, from: Int, to: Int): Double {
        val w = v.slice(from..to).sorted()
        return w[w.size / 2]
    }

    private fun quantile(sorted: List<Double>, q: Double): Double {
        if (sorted.isEmpty()) return 0.0
        if (sorted.size == 1) return sorted[0]
        val pos = q * (sorted.size - 1)
        val lo = pos.toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        return sorted[lo] * (1 - (pos - lo)) + sorted[hi] * (pos - lo)
    }

    private fun softmax(logits: DoubleArray, temperature: Double): DoubleArray {
        val max = logits.max()
        val exps = DoubleArray(logits.size) { kotlin.math.exp((logits[it] - max) / temperature) }
        val sum = exps.sum()
        return DoubleArray(exps.size) { exps[it] / sum }
    }

    companion object {
        // Indices into the post-normalization 127-wide vector:
        // window block layout = 31 features × 4 windows (30 s first), so the
        // unsuffixed window occupies 0..30. (Column 124 is the clock feature but
        // it is normalized like every other column — derive time from the epoch
        // index instead; see stage() for the REM=0 bug note.)
        private const val MEAN_IBI = 0
        private const val SDNN = 1
        private const val RMSSD = 2
        private const val PNN50 = 3
        private const val LFHF_LSP = 11
        private const val SD1 = 22
        private const val ACC_ENMO = 27

        // Dual-baseline HR-elevation wake detection (see stage()).
        private const val SMOOTH_HALF_EPOCHS = 4
        private const val TRAILING_EPOCHS = 90
    }
}
