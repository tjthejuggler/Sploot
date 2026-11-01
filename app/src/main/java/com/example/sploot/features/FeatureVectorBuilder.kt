package com.example.sploot.features

import com.example.sploot.signal.CleanBeat
import com.example.sploot.signal.EpochData
import com.example.sploot.signal.Interpolator
import com.example.sploot.signal.respiration.EdrExtractor
import kotlin.math.PI
import kotlin.math.sin

/** One assembled per-epoch feature vector plus metadata. */
data class EpochFeatures(
    val epochIndex: Int,
    val startMs: Long,
    val values: DoubleArray,
    /** Parallel to [values]: per-feature validity (false → value is NaN). */
    val valid: BooleanArray,
)

/**
 * Assembles the final per-epoch feature vector:
 *
 *  - All HRV / respiratory / actigraphy features computed over the 30 s target
 *    epoch (unsuffixed names) PLUS over centered sliding windows of 90 s, 150 s
 *    and 300 s (`_w90`, `_w150`, `_w300` suffixes).
 *  - Epoch-local clock features (single, not per-window): minutes since session
 *    start and sin/cos of time-of-day.
 *
 * **CONTRACT:** the exact order of [FEATURE_NAMES] is the stable interface for
 * the Python `ml_pipeline` and the ONNX models. Only append new features at
 * the end (and bump [FEATURE_VERSION]); never reorder. The same list is
 * serialized to `assets/features_manifest.json` ([MANIFEST_JSON]).
 *
 * Pure Kotlin, no Android dependencies.
 */
class FeatureVectorBuilder(
    private val interpolator: Interpolator = Interpolator(),
) {

    /**
     * Build the feature vector for epoch [epochIndex] given all epochs of the
     * night. Centered windows are clamped at the night boundaries (a 90 s
     * window at epoch 0 spans epochs 0–1, etc.).
     */
    fun build(epochs: List<EpochData>, epochIndex: Int, sessionStartMs: Long): EpochFeatures {
        require(epochIndex in epochs.indices) { "epoch index out of range" }
        val values = DoubleArray(FEATURE_NAMES.size)
        val valid = BooleanArray(FEATURE_NAMES.size)
        var v = 0

        // Precompute per-epoch ENMO for the rolling time-since-movement.
        val enmoPerEpoch = DoubleArray(epochs.size) { i ->
            val a = ActigraphyFeatures.compute(epochs[i].accSamples)
            if (a.enmo.isNaN()) 0.0 else a.enmo
        }

        for ((windowSec, suffix) in WINDOWS) {
            val feats = windowFeatures(epochs, epochIndex, windowSec, enmoPerEpoch)
            for ((name, value) in feats) {
                values[v] = value
                valid[v] = !value.isNaN()
                check(FEATURE_NAMES[v] == name + suffix) {
                    "feature order drift at $v: ${FEATURE_NAMES[v]} vs ${name + suffix}"
                }
                v++
            }
        }

        // Clock features (epoch-local).
        val epoch = epochs[epochIndex]
        val minutesSinceStart = (epoch.startMs - sessionStartMs) / 60_000.0
        val hoursOfDay = (epoch.startMs % DAY_MS) / 3_600_000.0
        values[v] = minutesSinceStart; valid[v] = true; v++
        values[v] = sin(2 * PI * hoursOfDay / 24.0); valid[v] = true; v++
        values[v] = kotlin.math.cos(2 * PI * hoursOfDay / 24.0); valid[v] = true; v++

        check(v == FEATURE_NAMES.size) { "feature count mismatch: $v vs ${FEATURE_NAMES.size}" }
        return EpochFeatures(epochIndex, epoch.startMs, values, valid)
    }

    /** Compute the 31 window features as ordered (name, value) pairs without suffix. */
    private fun windowFeatures(
        epochs: List<EpochData>,
        epochIndex: Int,
        windowSec: Int,
        enmoPerEpoch: DoubleArray,
    ): List<Pair<String, Double>> {
        val halfEpochs = windowSec / 60 // 30 s epochs → half-window in epochs
        val lo = (epochIndex - halfEpochs).coerceAtLeast(0)
        val hi = (epochIndex + halfEpochs).coerceAtMost(epochs.size - 1)
        val window = epochs.subList(lo, hi + 1)

        val beats: List<CleanBeat> = window.flatMap { it.ibis }
        val acc = window.flatMap { it.accSamples }
        val ibi = DoubleArray(beats.size) { beats[it].ibiMs }
        val accepted = window.sumOf { it.ibis.size }
        val total = window.sumOf { it.totalIbiCount }
        val hrvValid = total > 0 && accepted.toDouble() / total >= 0.60

        // HRV features: NaN when the window fails the validity gate — but the
        // 31-feature order contract still holds: invalid windows emit NaN
        // frequency-domain features rather than dropping them.
        val t = timeDomainFeatures(ibi, hrvValid)
        val f = if (hrvValid && ibi.size >= MIN_BEATS) frequencyFeatures(beats, window) else null
        val freqFeats = f?.features ?: NAN_FREQ_FEATURES
        val nl = nonlinearFeatures(ibi, hrvValid)

        // Respiration (needs HRV spectrum; ACC taken from the window).
        val resp: RespirationResult = if (f != null) {
            val est = EdrExtractor.estimate(f.freqs, f.power, acc)
            RespirationResult(est.rateBrpm, est.rrvBrpm, est.confidence, if (est.fromAcc) 1.0 else 0.0)
        } else {
            RespirationResult.DEFAULT
        }

        // Actigraphy: always computable when ACC present; rolling TSLM from
        // the target epoch.
        val act = if (acc.isNotEmpty()) {
            val m = ActigraphyFeatures.compute(acc)
            listOf(
                "acc_mean" to m.meanMag,
                "acc_var" to m.varMag,
                "acc_mad" to m.mad,
                "acc_enmo" to m.enmo,
                "acc_time_since_movement" to ActigraphyFeatures.timeSinceLastMovement(enmoPerEpoch, epochIndex),
            )
        } else {
            listOf(
                "acc_mean" to Double.NaN,
                "acc_var" to Double.NaN,
                "acc_mad" to Double.NaN,
                "acc_enmo" to Double.NaN,
                "acc_time_since_movement" to Double.NaN,
            )
        }

        return t + freqFeats + nl + listOf(
            "resp_rate" to resp.rate,
            "resp_rrv" to resp.rrv,
            "resp_confidence" to resp.confidence,
            "resp_from_acc" to resp.fromAcc,
        ) + act
    }

    private fun timeDomainFeatures(ibi: DoubleArray, hrvValid: Boolean): List<Pair<String, Double>> {
        fun d(f: () -> Double): Double = if (hrvValid && ibi.size >= MIN_BEATS) f() else Double.NaN
        return listOf(
            "hrv_mean_ibi" to d { HrvTimeDomain.meanIbi(ibi) },
            "hrv_sdnn" to d { HrvTimeDomain.sdnn(ibi) },
            "hrv_rmssd" to d { HrvTimeDomain.rmssd(ibi) },
            "hrv_pnn50" to d { HrvTimeDomain.pnn50(ibi) },
            "hrv_sdsd" to d { HrvTimeDomain.sdsd(ibi) },
            "hrv_iqr" to d { HrvTimeDomain.iqr(ibi) },
        )
    }

    private data class FreqResult(val features: List<Pair<String, Double>>, val freqs: DoubleArray, val power: DoubleArray)

    private fun frequencyFeatures(beats: List<CleanBeat>, window: List<EpochData>): FreqResult {
        val t0 = window.first().startMs
        val t = DoubleArray(beats.size) { (beats[it].timestampMs - t0) / 1000.0 }
        val y = DoubleArray(beats.size) { beats[it].ibiMs }
        val tach = interpolator.tachogram(beats, window.first().startMs, window.last().endMs)
        val res = HrvFrequencyDomain.compute(t, y, tach.values)
        val lsp = res.lsp
        val w = res.welch
        return FreqResult(
            listOf(
                "hrv_vlf_lsp" to lsp.vlf,
                "hrv_lf_lsp" to lsp.lf,
                "hrv_hf_lsp" to lsp.hf,
                "hrv_lfnu_lsp" to lsp.lfNu,
                "hrv_hfnu_lsp" to lsp.hfNu,
                "hrv_lfhf_lsp" to lsp.lfHfRatio,
                "hrv_vlf_welch" to w.vlf,
                "hrv_lf_welch" to w.lf,
                "hrv_hf_welch" to w.hf,
                "hrv_lfhf_welch" to w.lfHfRatio,
            ),
            res.lspFreqs,
            res.lspPower,
        )
    }

    private fun nonlinearFeatures(ibi: DoubleArray, hrvValid: Boolean): List<Pair<String, Double>> {
        fun d(f: () -> Double): Double = if (hrvValid && ibi.size >= MIN_BEATS) f() else Double.NaN
        return listOf(
            "hrv_sd1" to d { HrvNonlinear.poincare(ibi).sd1 },
            "hrv_sd2" to d { HrvNonlinear.poincare(ibi).sd2 },
            "hrv_sd2_sd1" to d { HrvNonlinear.poincare(ibi).sd2Sd1Ratio },
            "hrv_sampen" to d { HrvNonlinear.sampleEntropy(ibi) },
            "hrv_dfa_alpha1" to d { HrvNonlinear.dfaAlpha1(ibi) },
            "hrv_dfa_alpha2" to d { HrvNonlinear.dfaAlpha2(ibi) },
        )
    }

    private data class RespirationResult(val rate: Double, val rrv: Double, val confidence: Double, val fromAcc: Double) {
        companion object {
            val DEFAULT = RespirationResult(Double.NaN, Double.NaN, Double.NaN, Double.NaN)
        }
    }

    companion object {
        /** Frequency-domain feature names bound to NaN when the HRV gate fails. */
        private val NAN_FREQ_FEATURES: List<Pair<String, Double>> = listOf(
            "hrv_vlf_lsp", "hrv_lf_lsp", "hrv_hf_lsp", "hrv_lfnu_lsp", "hrv_hfnu_lsp", "hrv_lfhf_lsp",
            "hrv_vlf_welch", "hrv_lf_welch", "hrv_hf_welch", "hrv_lfhf_welch",
        ).map { it to Double.NaN }

        const val FEATURE_VERSION = 1
        const val MIN_BEATS = 10
        const val DAY_MS = 86_400_000L

        /** Window (seconds, total) to name suffix; base 30 s window is unsuffixed. */
        val WINDOWS: List<Pair<Int, String>> = listOf(
            30 to "",
            90 to "_w90",
            150 to "_w150",
            300 to "_w300",
        )

        /** The 31 per-window features, in order, WITHOUT the window suffix. */
        val WINDOW_FEATURE_BASE: List<String> = listOf(
            // Time domain (6)
            "hrv_mean_ibi", "hrv_sdnn", "hrv_rmssd", "hrv_pnn50", "hrv_sdsd", "hrv_iqr",
            // Frequency domain (10)
            "hrv_vlf_lsp", "hrv_lf_lsp", "hrv_hf_lsp", "hrv_lfnu_lsp", "hrv_hfnu_lsp", "hrv_lfhf_lsp",
            "hrv_vlf_welch", "hrv_lf_welch", "hrv_hf_welch", "hrv_lfhf_welch",
            // Nonlinear (6)
            "hrv_sd1", "hrv_sd2", "hrv_sd2_sd1", "hrv_sampen", "hrv_dfa_alpha1", "hrv_dfa_alpha2",
            // Respiration (4)
            "resp_rate", "resp_rrv", "resp_confidence", "resp_from_acc",
            // Actigraphy (5)
            "acc_mean", "acc_var", "acc_mad", "acc_enmo", "acc_time_since_movement",
        )

        /**
         * THE FEATURE ORDER CONTRACT. 31 features × 4 windows (30 s unsuffixed,
         * then _w90/_w150/_w300) + 3 clock features = 127 values.
         */
        val FEATURE_NAMES: List<String> = WINDOWS.flatMap { w ->
            WINDOW_FEATURE_BASE.map { it + w.second }
        } + listOf(
            "clock_minutes_since_start",
            "clock_tod_sin",
            "clock_tod_cos",
        )

        /** JSON manifest mirroring [FEATURE_NAMES] (assets/features_manifest.json). */
        val MANIFEST_JSON: String = buildString {
            append("{\n")
            append("  \"version\": $FEATURE_VERSION,\n")
            append("  \"count\": ${FEATURE_NAMES.size},\n")
            append("  \"windows_seconds\": ${WINDOWS.map { it.first }},\n")
            append("  \"features\": [\n")
            FEATURE_NAMES.forEachIndexed { i, name ->
                append("    \"")
                append(name)
                append('"')
                if (i < FEATURE_NAMES.size - 1) append(',')
                append('\n')
            }
            append("  ]\n")
            append("}\n")
        }
    }
}
