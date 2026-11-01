package com.example.sploot.features

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Band power results in ms². */
data class BandPowers(
    val vlf: Double,
    val lf: Double,
    val hf: Double,
    val lfNu: Double, // LF / (LF + HF) * 100
    val hfNu: Double, // HF / (LF + HF) * 100
    val lfHfRatio: Double,
)

/** Full result of frequency-domain analysis: LSP (primary) + Welch (cross-check). */
data class FrequencyDomainResult(
    val lsp: BandPowers,
    val welch: BandPowers,
    /** LSP frequency grid (Hz). */
    val lspFreqs: DoubleArray,
    /** LSP power at each grid frequency (ms²/Hz). */
    val lspPower: DoubleArray,
)

/**
 * Frequency-domain HRV.
 *
 * Primary estimator: native Lomb–Scargle periodogram over the irregular clean
 * IBI series (no FFT-on-irregular-sampling pitfalls, robust to rejected beats).
 * Cross-check: Welch PSD (Hann window, 50% overlap) over the interpolated 4 Hz
 * tachogram. Band powers in ms² (VLF 0.0033–0.04, LF 0.04–0.15, HF 0.15–0.4 Hz)
 * are trapezoid-integrated over the respective estimator's grid.
 *
 * Pure Kotlin, no external DSP library.
 */
object HrvFrequencyDomain {
    const val VLF_LO = 0.0033
    const val VLF_HI = 0.04
    const val LF_HI = 0.15
    const val HF_HI = 0.4

    /** Frequency grid step for the Lomb–Scargle periodogram (Hz). */
    const val LSP_DF = 0.002

    /** Welch segment length (samples at 4 Hz → 16 s). */
    const val WELCH_SEGMENT = 64

    /**
     * Compute both LSP and Welch estimates.
     *
     * @param beatTimesSec beat timestamps in seconds (irregular, cleaned).
     * @param ibiMs matching IBI values in ms.
     * @param tachogram 4 Hz interpolated tachogram (may contain NaN gaps; NaN
     *   samples are dropped from Welch segments).
     */
    fun compute(beatTimesSec: DoubleArray, ibiMs: DoubleArray, tachogram: DoubleArray): FrequencyDomainResult {
        val (freqs, power) = lombScargle(beatTimesSec, ibiMs)
        val (wf, wp) = welch(tachogram)
        return FrequencyDomainResult(
            lsp = bandPowers(freqs, power),
            welch = bandPowers(wf, wp),
            lspFreqs = freqs,
            lspPower = power,
        )
    }

    /** Convenience: LSP only. */
    fun computeLsp(beatTimesSec: DoubleArray, ibiMs: DoubleArray): Pair<DoubleArray, DoubleArray> =
        lombScargle(beatTimesSec, ibiMs)

    /**
     * Lomb–Scargle periodogram of the irregular series. Returns the frequency
     * grid (Hz) and power spectral density (ms²/Hz), variance-normalized so
     * band integration yields ms².
     */
    fun lombScargle(t: DoubleArray, y: DoubleArray): Pair<DoubleArray, DoubleArray> {
        require(t.size == y.size) { "t/y length mismatch" }
        val n = t.size
        val nBins = (HF_HI / LSP_DF).toInt() + 1
        val freqs = DoubleArray(nBins) { it * LSP_DF }
        val power = DoubleArray(nBins)
        if (n < 4) return freqs to power

        val mean = y.average()
        val yc = DoubleArray(n) { y[it] - mean }
        val variance = yc.sumOf { it * it } / n

        for (k in freqs.indices) {
            val f = freqs[k]
            if (f <= 0.0) continue
            val w = 2 * PI * f
            // Time offset tau making P(tau) extremal.
            val tau = atan2(sin2(w, t).sum(), cos2(w, t).sum()) / (2 * w)
            var cc = 0.0; var ss = 0.0; var sc = 0.0
            var num1 = 0.0; var num2 = 0.0
            for (i in 0 until n) {
                val a = w * (t[i] - tau)
                val c = cos(a); val s = sin(a)
                cc += c * c; ss += s * s; sc += s * c
                num1 += yc[i] * c
                num2 += yc[i] * s
            }
            val den = cc * ss - sc * sc
            if (den == 0.0) continue
            val p = (ss * num1 * num1 + cc * num2 * num2 - 2 * sc * num1 * num2) / den
            power[k] = p / (2.0 * variance) // dimensionless LSP
        }
        // Convert to PSD (ms²/Hz): variance-normalized LSP integrated over the
        // estimated band gives total variance; scale accordingly.
        for (k in freqs.indices) power[k] *= variance
        return freqs to power
    }

    /**
     * Welch PSD over the uniform tachogram: Hann window, 50% overlap segments,
     * direct DFT at the LSP frequency grid. NaN samples split the usable data
     * into runs; segments are drawn from valid runs only.
     */
    fun welch(x: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val nBins = (HF_HI / LSP_DF).toInt() + 1
        val freqs = DoubleArray(nBins) { it * LSP_DF }
        val acc = DoubleArray(nBins)
        var segCount = 0
        var i = 0
        val n = x.size
        while (i < n) {
            if (x[i].isNaN()) { i++; continue }
            var j = i
            while (j < n && !x[j].isNaN()) j++
            // segments of WELCH_SEGMENT with 50% overlap inside run [i, j)
            var s = i
            while (s + WELCH_SEGMENT <= j) {
                addSegment(x, s, freqs, acc)
                segCount++
                s += WELCH_SEGMENT / 2
            }
            i = j
        }
        if (segCount == 0) return freqs to DoubleArray(nBins)
        // addSegment already produces one-sided PSD (ms²/Hz) per segment with
        // per-segment mean removal; average across segments.
        val power = DoubleArray(nBins) { k -> acc[k] / segCount }
        return freqs to power
    }

    private fun addSegment(x: DoubleArray, start: Int, freqs: DoubleArray, acc: DoubleArray) {
        val seg = WELCH_SEGMENT
        val mean = (0 until seg).map { x[start + it] }.average()
        val w = DoubleArray(seg) { k -> 0.5 * (1 - cos(2 * PI * k / (seg - 1))) }
        var wss = 0.0
        for (k in 0 until seg) wss += w[k] * w[k]
        for (fi in freqs.indices) {
            val f = freqs[fi]
            if (f <= 0.0) continue
            var re = 0.0; var im = 0.0
            for (k in 0 until seg) {
                val v = (x[start + k] - mean) * w[k]
                val a = 2 * PI * f * k / 4.0 // 4 Hz sample rate
                re += v * cos(a)
                im += v * sin(a)
            }
            val p = (re * re + im * im) / (4.0 * wss) // one-sided PSD (ms²/Hz), fs=4
            acc[fi] += p
        }
    }

    /** Integrate PSD into band powers (trapezoid over grid points inside band). */
    fun bandPowers(freqs: DoubleArray, power: DoubleArray): BandPowers {
        fun band(lo: Double, hi: Double): Double = integrate(freqs, power, lo, hi)
        val vlf = band(VLF_LO, VLF_HI)
        val lf = band(VLF_HI, LF_HI)
        val hf = band(LF_HI, HF_HI)
        val total = lf + hf
        return BandPowers(
            vlf = vlf,
            lf = lf,
            hf = hf,
            lfNu = if (total > 0) 100.0 * lf / total else Double.NaN,
            hfNu = if (total > 0) 100.0 * hf / total else Double.NaN,
            lfHfRatio = if (hf > 0) lf / hf else Double.NaN,
        )
    }

    /** Trapezoid integration restricted to [lo, hi] with linear boundary interpolation. */
    private fun integrate(freqs: DoubleArray, power: DoubleArray, lo: Double, hi: Double): Double {
        if (freqs.size < 2) return 0.0
        var sum = 0.0
        for (k in 0 until freqs.size - 1) {
            val f0 = freqs[k]; val f1 = freqs[k + 1]
            if (f1 <= lo || f0 >= hi) continue
            val p0 = if (f0 >= lo) power[k] else lerp(freqs[k], power[k], freqs[k + 1], power[k + 1], lo)
            val p1 = if (f1 <= hi) power[k + 1] else lerp(freqs[k], power[k], freqs[k + 1], power[k + 1], hi)
            val a = maxOf(f0, lo); val b = minOf(f1, hi)
            if (b > a) sum += 0.5 * (p0 + p1) * (b - a)
        }
        return sum
    }

    private fun lerp(x0: Double, y0: Double, x1: Double, y1: Double, x: Double): Double =
        y0 + (y1 - y0) * (x - x0) / (x1 - x0)

    private fun sin2(w: Double, t: DoubleArray): DoubleArray = DoubleArray(t.size) { sin(2 * w * t[it]) }
    private fun cos2(w: Double, t: DoubleArray): DoubleArray = DoubleArray(t.size) { cos(2 * w * t[it]) }
}
