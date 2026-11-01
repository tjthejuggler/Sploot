package com.example.sploot.signal.respiration

import com.example.sploot.ble.AccSample
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Result of respiratory estimation for one epoch. */
data class RespirationEstimate(
    /** Respiratory rate in breaths/min. */
    val rateBrpm: Double,
    /** Respiratory rate variability (SD of sub-window estimates) in breaths/min. */
    val rrvBrpm: Double,
    /** True when the ACC-based estimate was preferred over the RSA-based one. */
    val fromAcc: Boolean,
    /** Spectral concentration (band peak / band total) of the chosen estimate, 0–1. */
    val confidence: Double,
)

/**
 * ECG/ACC-derived respiration (EDR) estimation per epoch.
 *
 * Two independent estimators:
 *  1. **RSA-based** — dominant spectral peak of the HRV Lomb–Scargle
 *     periodogram inside the respiratory band 0.13–0.5 Hz (RSA couples
 *     respiration to heart-rate oscillation). RRV proxy = spectral spread
 *     (power-weighted SD of frequency) within the band.
 *  2. **ACC-based** — spectral peak (Goertzel-style periodogram, 0.005 Hz
 *     grid) of the mean-subtracted ACC magnitude, restricted to 0.13–0.5 Hz
 *     (chest/arm breathing motion). RRV = SD of rate estimates from 10 s
 *     sub-windows when at least two are finite, else the same spectral
 *     spread proxy.
 *
 * **Fusion rule:** use the ACC estimate when it is available AND its spectral
 * concentration (peak power / total band power — how peaked the breathing
 * oscillation is vs. motion noise) is ≥ the RSA estimate's; otherwise use the
 * RSA estimate. Rationale: direct mechanical breathing motion is the more
 * specific signal whenever it is genuinely periodic, while RSA is the safer
 * fallback when limb-motion noise contaminates the accelerometer.
 *
 * Pure Kotlin, no Android dependencies.
 */
object EdrExtractor {
    const val RESP_BAND_LO = 0.13
    const val RESP_BAND_HI = 0.50
    const val DF = 0.005
    private const val SUB_WINDOW_SEC = 10.0
    private const val MIN_ACC_SAMPLES = 128

    /** Rate + spectral-concentration confidence for one source. */
    data class SourceEstimate(
        val rateBrpm: Double,
        val confidence: Double,
        val rrvBrpm: Double,
        val fromAcc: Boolean,
    )

    /**
     * Full estimation for one epoch.
     *
     * @param lspFreqs / lspPower HRV Lomb–Scargle spectrum of the epoch (Hz, ms²/Hz).
     * @param accSamples ACC samples of the epoch (milli-g); empty list if absent.
     */
    fun estimate(
        lspFreqs: DoubleArray,
        lspPower: DoubleArray,
        accSamples: List<AccSample>,
    ): RespirationEstimate {
        val rsa = rsaEstimate(lspFreqs, lspPower)
        val acc = if (accSamples.size >= MIN_ACC_SAMPLES) accEstimate(accSamples) else null
        val chosen = if (acc != null && acc.confidence >= rsa.confidence) acc else rsa
        return RespirationEstimate(
            rateBrpm = chosen.rateBrpm,
            rrvBrpm = chosen.rrvBrpm,
            fromAcc = chosen.fromAcc,
            confidence = chosen.confidence,
        )
    }

    private fun rsaEstimate(freqs: DoubleArray, power: DoubleArray): SourceEstimate {
        val peak = bandPeak(freqs, power)
            ?: return SourceEstimate(Double.NaN, 0.0, Double.NaN, fromAcc = false)
        return SourceEstimate(
            rateBrpm = peak.rateHz * 60.0,
            confidence = peak.concentration,
            rrvBrpm = bandSpread(freqs, power) * 60.0,
            fromAcc = false,
        )
    }

    private fun accEstimate(samples: List<AccSample>): SourceEstimate? {
        val full = accBandPeak(samples) ?: return null
        // RRV from 10 s sub-window rate estimates.
        val rates = mutableListOf<Double>()
        val t0 = samples.first().timestampMs
        val winMs = (SUB_WINDOW_SEC * 1000).toLong()
        var w = 0
        while (t0 + (w + 1) * winMs <= samples.last().timestampMs) {
            val sub = samples.filter {
                it.timestampMs >= t0 + w * winMs && it.timestampMs < t0 + (w + 1) * winMs
            }
            accBandPeak(sub)?.let { rates += it.rateHz * 60.0 }
            w++
        }
        val rrv = if (rates.size >= 2) {
            val m = rates.average()
            sqrt(rates.sumOf { (it - m) * (it - m) } / rates.size)
        } else full.spreadHz * 60.0
        return SourceEstimate(full.rateHz * 60.0, full.concentration, rrv, fromAcc = true)
    }

    private data class Peak(val rateHz: Double, val concentration: Double)

    private data class AccPeak(val rateHz: Double, val concentration: Double, val spreadHz: Double)

    private data class SpectrumPeak(val rateHz: Double, val concentration: Double, val spreadHz: Double)

    /** Dominant peak + concentration of an existing spectrum inside the respiratory band. */
    private fun bandPeak(freqs: DoubleArray, power: DoubleArray): SpectrumPeak? {
        var bestIdx = -1
        var best = Double.NEGATIVE_INFINITY
        var total = 0.0
        for (i in freqs.indices) {
            val f = freqs[i]
            if (f < RESP_BAND_LO || f > RESP_BAND_HI) continue
            total += power[i]
            if (power[i] > best) { best = power[i]; bestIdx = i }
        }
        if (bestIdx < 0 || total <= 0) return null
        return SpectrumPeak(freqs[bestIdx], power[bestIdx] / total, spread(freqs, power))
    }

    private fun bandSpread(freqs: DoubleArray, power: DoubleArray): Double = spread(freqs, power)

    /** Power-weighted SD of frequency within the respiratory band (Hz). */
    private fun spread(freqs: DoubleArray, power: DoubleArray): Double {
        var m0 = 0.0; var m1 = 0.0; var m2 = 0.0
        for (i in freqs.indices) {
            val f = freqs[i]
            if (f < RESP_BAND_LO || f > RESP_BAND_HI) continue
            val p = power[i]
            m0 += p; m1 += p * f; m2 += p * f * f
        }
        if (m0 <= 0) return Double.NaN
        val mean = m1 / m0
        return sqrt((m2 / m0 - mean * mean).coerceAtLeast(0.0))
    }

    /** Periodogram peak of mean-subtracted ACC magnitude inside the band. */
    private fun accBandPeak(samples: List<AccSample>): AccPeak? {
        if (samples.size < MIN_ACC_SAMPLES) return null
        val n = samples.size
        val mag = DoubleArray(n) {
            val s = samples[it]
            sqrt((s.x * s.x + s.y * s.y + s.z * s.z).toDouble())
        }
        val mean = mag.average()
        val fs = effectiveRate(samples)
        if (fs <= 0) return null
        val nBins = ((RESP_BAND_HI - RESP_BAND_LO) / DF).toInt() + 1
        val power = DoubleArray(nBins)
        val freqs = DoubleArray(nBins) { RESP_BAND_LO + it * DF }
        for (k in 0 until nBins) {
            val f = freqs[k]
            var re = 0.0; var im = 0.0
            for (i in 0 until n) {
                val v = mag[i] - mean
                val a = 2 * PI * f * i / fs
                re += v * cos(a)
                im += v * sin(a)
            }
            power[k] = (re * re + im * im) / n
        }
        val total = power.sum()
        if (total <= 0) return null
        var best = 0
        for (k in 1 until nBins) if (power[k] > power[best]) best = k
        var m1 = 0.0; var m2 = 0.0
        for (k in 0 until nBins) {
            m1 += power[k] * freqs[k]
            m2 += power[k] * freqs[k] * freqs[k]
        }
        val fMean = m1 / total
        val sd = sqrt((m2 / total - fMean * fMean).coerceAtLeast(0.0))
        return AccPeak(freqs[best], power[best] / total, sd)
    }

    private fun effectiveRate(samples: List<AccSample>): Double {
        val dt = (samples.last().timestampMs - samples.first().timestampMs) / 1000.0
        return if (dt <= 0) 0.0 else (samples.size - 1) / dt
    }
}
