package com.example.sploot.features

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/** Poincaré plot descriptors. */
data class PoincareMeasures(val sd1: Double, val sd2: Double, val sd2Sd1Ratio: Double)

/**
 * Nonlinear HRV measures: Poincaré SD1/SD2, Sample Entropy (m=2, r=0.2·SDNN)
 * and DFA α1 / α2 with linear detrending.
 *
 * All functions are pure; NaN is returned when the series is too short for a
 * reliable estimate.
 */
object HrvNonlinear {

    /**
     * Poincaré plot: SD1 = SD of the transverse axis (short-term variability),
     * SD2 = SD of the longitudinal axis. For white-noise-like series
     * SD1 ≈ SD2.
     */
    fun poincare(ibi: DoubleArray): PoincareMeasures {
        if (ibi.size < 3) return PoincareMeasures(Double.NaN, Double.NaN, Double.NaN)
        val n = ibi.size
        val mean = ibi.average()
        val varX = ibi.sumOf { (it - mean) * (it - mean) } / (n - 1)
        var accD = 0.0
        for (i in 1 until n) {
            val d = ibi[i] - ibi[i - 1]
            accD += d * d
        }
        val varD = accD / (n - 1) // variance of successive differences
        val sd1 = sqrt(varD / 2.0)
        val sd2 = sqrt(2.0 * varX - sd1 * sd1)
        return PoincareMeasures(sd1, sd2, if (sd1 > 0) sd2 / sd1 else Double.NaN)
    }

    /**
     * Sample Entropy, m = 2, r = 0.2 × SDNN (Richman & Moorman 2000).
     * SampEn = −ln(B/A) where B counts matched (m+1)-tuples and A counts
     * matched m-tuples (self-matches excluded). Direct O(n²) counting — the
     * per-epoch series length (~40–400 beats) makes this trivially fast.
     */
    fun sampleEntropy(ibi: DoubleArray): Double {
        if (ibi.size < 5) return Double.NaN
        val sd = HrvTimeDomain.sdnn(ibi)
        if (sd <= 0.0) return Double.NaN
        val r = 0.2 * sd
        val a = countMatches(ibi, 2, r)
        val b = countMatches(ibi, 3, r)
        if (a == 0L || b == 0L) return Double.NaN
        return -ln(b.toDouble() / a.toDouble())
    }

    private fun countMatches(x: DoubleArray, m: Int, r: Double): Long {
        val n = x.size
        var count = 0L
        for (i in 0 until n - m) {
            for (j in i + 1 until n - m) {
                var match = true
                for (k in 0 until m) {
                    if (abs(x[i + k] - x[j + k]) > r) { match = false; break }
                }
                if (match) count++
            }
        }
        return count
    }

    /**
     * Detrended Fluctuation Analysis.
     *
     * @param boxes box sizes to evaluate (α1: 4–64, α2: 64–300 per spec).
     * @return slope of the log F(n) vs log n regression, or NaN when fewer
     *   than 3 box sizes are computable or any F(n) is non-positive.
     */
    fun dfa(ibi: DoubleArray, boxes: IntArray): Double {
        val usable = boxes.filter { it >= 4 && ibi.size >= 2 * it }
        if (usable.size < 3) return Double.NaN
        val n = ibi.size
        val mean = ibi.average()
        // Integrated (cumulative-sum) profile.
        val profile = DoubleArray(n)
        var acc = 0.0
        for (i in 0 until n) {
            acc += ibi[i] - mean
            profile[i] = acc
        }
        val logN = mutableListOf<Double>()
        val logF = mutableListOf<Double>()
        for (box in usable) {
            val nBoxes = n / box
            var rss = 0.0
            for (b in 0 until nBoxes) {
                val s = b * box
                rss += detrendRss(profile, s, box)
            }
            val f = sqrt(rss / (nBoxes * box))
            if (f > 0) {
                logN += ln(box.toDouble())
                logF += ln(f)
            }
        }
        if (logN.size < 3) return Double.NaN
        return linearSlope(logN.toDoubleArray(), logF.toDoubleArray())
    }

    /** Residual sum of squares after linear detrend of profile[s, s+len). */
    private fun detrendRss(y: DoubleArray, s: Int, len: Int): Double {
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (k in 0 until len) {
            val xv = k.toDouble()
            val yv = y[s + k]
            sx += xv; sy += yv; sxx += xv * xv; sxy += xv * yv
        }
        val denom = len * sxx - sx * sx
        val intercept: Double
        val slope: Double
        if (denom == 0.0) { intercept = sy / len; slope = 0.0 } else {
            slope = (len * sxy - sx * sy) / denom
            intercept = (sy - slope * sx) / len
        }
        var rss = 0.0
        for (k in 0 until len) {
            val resid = y[s + k] - (intercept + slope * k)
            rss += resid * resid
        }
        return rss
    }

    private fun linearSlope(x: DoubleArray, y: DoubleArray): Double {
        val n = x.size
        val mx = x.average(); val my = y.average()
        var num = 0.0; var den = 0.0
        for (i in 0 until n) {
            num += (x[i] - mx) * (y[i] - my)
            den += (x[i] - mx) * (x[i] - mx)
        }
        return if (den == 0.0) Double.NaN else num / den
    }

    /** α1: boxes 4–64. */
    fun dfaAlpha1(ibi: DoubleArray): Double =
        dfa(ibi, intArrayOf(4, 5, 6, 8, 10, 13, 16, 21, 32, 42, 64).filter { it in 4..64 }.toIntArray())

    /** α2: boxes 64–300. */
    fun dfaAlpha2(ibi: DoubleArray): Double =
        dfa(ibi, intArrayOf(64, 80, 100, 128, 160, 200, 250, 300).filter { it in 64..300 }.toIntArray())
}
