package com.example.sploot.ml

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Robust per-night feature scaling across the whole night's feature matrix.
 *
 * For each feature column j:
 *  - collect non-NaN values over all epochs, count NaNs;
 *  - if NaN in > 50% of epochs → column passes through **unscaled** (statistics are
 *    unreliable; the model treats 0 as "missing" post-imputation);
 *  - otherwise scale x' = (x − median) / IQR; if IQR == 0 fall back to
 *    MAD × 1.4826 (Gaussian-consistent MAD); if MAD == 0 fall back to standard
 *    deviation; if SD == 0 the column is constant → divide by 1.
 *  - after scaling, remaining NaNs are imputed with 0.0 (the post-imputation
 *    "missing" value the models are trained with).
 *
 * Pure Kotlin, no dependencies — unit-testable in isolation.
 */
class PerNightNormalizer {

    /** Fitted per-feature statistics for one night. */
    data class Stats(
        val median: DoubleArray,
        val scale: DoubleArray,
        val nanCounts: IntArray,
        val passThrough: BooleanArray,
        val epochs: Int,
    )

    fun fit(features: List<FloatArray>): Stats {
        require(features.isNotEmpty()) { "cannot fit normalizer on empty night" }
        val t = features.size
        val d = features[0].size
        for (f in features) require(f.size == d) { "ragged feature matrix" }

        val median = DoubleArray(d)
        val scale = DoubleArray(d) { 1.0 }
        val nanCounts = IntArray(d)
        val passThrough = BooleanArray(d)

        val col = DoubleArray(t)
        for (j in 0 until d) {
            var n = 0
            for (i in 0 until t) {
                val v = features[i][j]
                if (v.isNaN()) {
                    nanCounts[j]++
                } else {
                    col[n++] = v.toDouble()
                }
            }
            if (nanCounts[j] * 2 > t) {
                passThrough[j] = true // >50% NaN → unscaled
                continue
            }
            val values = col.copyOf(n).also { it.sort() }
            median[j] = medianOf(values)
            val iqr = quantile(values, 0.75) - quantile(values, 0.25)
            scale[j] = when {
                iqr > 0.0 -> iqr
                else -> {
                    // Fallback 1: MAD (×1.4826 for SD-equivalence)
                    val devs = DoubleArray(n) { abs(values[it] - median[j]) }.also { it.sort() }
                    val mad = medianOf(devs) * 1.4826
                    when {
                        mad > 0.0 -> mad
                        else -> {
                            // Fallback 2: standard deviation
                            var mean = 0.0
                            for (v in values) mean += v
                            mean /= n
                            var ss = 0.0
                            for (v in values) ss += (v - mean) * (v - mean)
                            val sd = sqrt(ss / n).takeIf { it > 0.0 } ?: 1.0
                            sd
                        }
                    }
                }
            }
        }
        return Stats(median, scale, nanCounts, passThrough, t)
    }

    /**
     * Apply fitted scaling: x' = (x − median)/scale for non-passthrough features,
     * passthrough features kept as-is; every NaN imputed with 0.0 afterwards.
     */
    fun transform(features: List<FloatArray>, stats: Stats): List<FloatArray> {
        return features.map { row ->
            require(row.size == stats.median.size) { "feature width mismatch vs fitted stats" }
            FloatArray(row.size) { j ->
                val v = row[j]
                when {
                    v.isNaN() -> 0f
                    stats.passThrough[j] -> v
                    else -> ((v - stats.median[j]) / stats.scale[j]).toFloat()
                }
            }
        }
    }

    /** Fit + transform in one call. */
    fun fitTransform(features: List<FloatArray>): List<FloatArray> = transform(features, fit(features))

    private fun medianOf(sorted: DoubleArray): Double = quantile(sorted, 0.5)

    /**
     * Linear-interpolated quantile of a **sorted** array (matches numpy's default
     * "linear" method, which the Python training pipeline uses).
     */
    private fun quantile(sorted: DoubleArray, q: Double): Double {
        val n = sorted.size
        if (n == 1) return sorted[0]
        val pos = q * (n - 1)
        val lo = pos.toInt()
        val hi = (lo + 1).coerceAtMost(n - 1)
        val frac = pos - lo
        return sorted[lo] * (1 - frac) + sorted[hi] * frac
    }
}
