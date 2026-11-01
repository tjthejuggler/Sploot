package com.example.sploot

import com.example.sploot.features.ActigraphyFeatures
import com.example.sploot.features.FeatureVectorBuilder
import com.example.sploot.features.HrvFrequencyDomain
import com.example.sploot.features.HrvNonlinear
import com.example.sploot.features.HrvTimeDomain
import com.example.sploot.signal.CleanBeat
import com.example.sploot.signal.EpochBuilder
import com.example.sploot.signal.FilterResult
import com.example.sploot.signal.Interpolator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import java.util.Random

class FeatureExtractorsTest {

    // ---------- time domain (hand cases) ----------

    @Test
    fun timeDomain_handCases() {
        val ibi = doubleArrayOf(1000.0, 1100.0)
        assertEquals(1050.0, HrvTimeDomain.meanIbi(ibi), 1e-9)
        assertEquals(50.0, HrvTimeDomain.sdnn(ibi), 1e-9)   // population SD
        assertEquals(100.0, HrvTimeDomain.rmssd(ibi), 1e-9)
    }

    @Test
    fun pnn50_handCase() {
        // successive diffs: +20, -40, +120 → 1 of 3 > 50 ms → 33.33 %
        val ibi = doubleArrayOf(800.0, 820.0, 780.0, 900.0)
        assertEquals(100.0 / 3, HrvTimeDomain.pnn50(ibi), 1e-9)
        // RMSSD = sqrt((20² + 40² + 120²)/3) = sqrt(16400/3)
        assertEquals(kotlin.math.sqrt(16400.0 / 3.0), HrvTimeDomain.rmssd(ibi), 1e-9)
        // SDSD: diffs = [20, -40, 120], mean = 33.33
        val d = listOf(20.0, -40.0, 120.0)
        val m = d.average()
        val expectSdsd = kotlin.math.sqrt(d.sumOf { (it - m) * (it - m) } / d.size)
        assertEquals(expectSdsd, HrvTimeDomain.sdsd(ibi), 1e-9)
    }

    @Test
    fun iqr_handCase() {
        val ibi = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 100.0, 6.0, 7.0, 8.0)
        // quantile type-7: Q1 = 2.75, Q3 = 7.25 → IQR = 4.5
        assertEquals(4.5, HrvTimeDomain.iqr(ibi), 1e-9)
    }

    @Test
    fun constantSeries_hasZeroVariability() {
        val ibi = DoubleArray(50) { 900.0 }
        assertEquals(0.0, HrvTimeDomain.sdnn(ibi), 1e-9)
        assertEquals(0.0, HrvTimeDomain.rmssd(ibi), 1e-9)
        assertEquals(0.0, HrvTimeDomain.pnn50(ibi), 1e-9)
    }

    // ---------- frequency domain ----------

    @Test
    fun lsp_detectsKnownHfPeakWithin10Percent() {
        val target = 0.25
        val (t, y) = sineRr(modHz = target)
        val (freqs, power) = HrvFrequencyDomain.lombScargle(t, y)
        // dominant peak inside the HF band
        var best = -1; var bestP = Double.NEGATIVE_INFINITY
        for (i in freqs.indices) {
            if (freqs[i] in 0.15..0.4 && power[i] > bestP) { bestP = power[i]; best = i }
        }
        assertTrue(best >= 0)
        assertEquals(target, freqs[best], 0.1 * target)
    }

    @Test
    fun lsp_andWelch_agreeOnBandDomination() {
        // Strong LF (0.10 Hz) modulation → LF > HF in BOTH estimators.
        val (t, y) = sineRr(modHz = 0.10, minutes = 2.0)
        val beats = t.mapIndexed { i, ti -> CleanBeat((ti * 1000).toLong(), y[i]) }
        val start = 0L
        val end = (t.last() * 1000).toLong()
        val tach = Interpolator().tachogram(beats, start, end)
        val res = HrvFrequencyDomain.compute(t, y, tach.values)
        assertTrue(res.lsp.lf > res.lsp.hf)
        assertTrue(res.welch.lf > res.welch.hf)
        assertTrue(res.lsp.lfNu > 50.0)
        assertTrue(res.lsp.lfHfRatio.isFinite())
    }

    @Test
    fun bandPowers_unitsAndNu() {
        val bp = com.example.sploot.features.BandPowers(100.0, 300.0, 100.0, 75.0, 25.0, 3.0)
        assertEquals(75.0, bp.lfNu, 1e-9)
        assertEquals(25.0, bp.hfNu, 1e-9)
    }

    // ---------- nonlinear ----------

    @Test
    fun poincare_whiteNoiseSd1ApproxSd2() {
        val rnd = Random(42)
        val ibi = DoubleArray(400) { 900 + rnd.nextGaussian() * 40 }
        val p = HrvNonlinear.poincare(ibi)
        assertTrue("sd1=${p.sd1}", p.sd1 > 25 && p.sd1 < 65)
        assertTrue("ratio=${p.sd2Sd1Ratio}", p.sd2Sd1Ratio in 0.85..1.15) // iid → SD1 ≈ SD2
    }

    @Test
    fun poincare_alternatingSeriesSmallSd1() {
        // perfectly alternating → successive diffs large, scatter small → SD1 > SD2
        val ibi = DoubleArray(60) { if (it % 2 == 0) 800.0 else 1000.0 }
        val p = HrvNonlinear.poincare(ibi)
        assertTrue(p.sd1 > p.sd2)
    }

    @Test
    fun sampEn_periodicIsLowRandomIsHigh() {
        val periodic = DoubleArray(240) { if (it % 2 == 0) 800.0 else 1000.0 }
        assertTrue(HrvNonlinear.sampleEntropy(periodic) < 0.5)
        val rnd = Random(7)
        val random = DoubleArray(400) { 900 + rnd.nextGaussian() * 40 }
        val se = HrvNonlinear.sampleEntropy(random)
        assertTrue("sampen=$se", se in 1.0..3.5)
    }

    @Test
    fun dfa_whiteNoiseAlpha1NearHalf() {
        val rnd = Random(123)
        val ibi = DoubleArray(600) { 900 + rnd.nextGaussian() * 30 }
        val a1 = HrvNonlinear.dfaAlpha1(ibi)
        assertTrue("alpha1=$a1", a1 in 0.3..0.8) // uncorrelated → ≈0.5
        val a2 = HrvNonlinear.dfaAlpha2(ibi)
        assertTrue("alpha2=$a2", a2.isNaN() || a2 in 0.1..1.2)
    }

    @Test
    fun dfa_correlatedSeriesHasHigherAlpha() {
        // Random walk (integrated noise) → α ≈ 1.5 for Brownian motion.
        val rnd = Random(31)
        var acc = 0.0
        val ibi = DoubleArray(600) {
            acc += rnd.nextGaussian() * 10
            900 + acc
        }
        val a1 = HrvNonlinear.dfaAlpha1(ibi)
        assertTrue("alpha1=$a1", a1 > 1.0)
    }

    @Test
    fun nonlinear_tooShortReturnsNaN() {
        assertTrue(HrvNonlinear.poincare(doubleArrayOf(900.0, 910.0)).sd1.isNaN())
        assertTrue(HrvNonlinear.sampleEntropy(DoubleArray(4) { 900.0 }).isNaN())
        assertTrue(HrvNonlinear.dfaAlpha1(doubleArrayOf(900.0, 910.0, 920.0)).isNaN())
    }

    // ---------- FeatureVectorBuilder ----------

    private fun syntheticNight(epochCount: Int): List<com.example.sploot.signal.EpochData> {
        val rnd = Random(2024)
        val clean = mutableListOf<CleanBeat>()
        var t = 0L
        var clock = 0.0
        while (clean.size < epochCount * 34) {
            val ibi = 900 + 50 * sin(2 * PI * 0.22 * clock) + rnd.nextGaussian() * 8
            clock += ibi / 1000.0
            t += ibi.toLong()
            clean += CleanBeat(t, ibi)
        }
        val acc = mutableListOf<com.example.sploot.ble.AccSample>()
        val fs = 52
        for (i in 0 until epochCount * 30 * fs) {
            val tt = i.toDouble() / fs
            // ±100 mg → epoch ENMO = 100/π ≈ 31.8 mg (above the 22 mg threshold)
            val mod = 100 * sin(2 * PI * 0.25 * tt)
            acc += com.example.sploot.ble.AccSample((tt * 1000).toLong(), (1000 + mod).toInt(), 0, 0, fs)
        }
        return EpochBuilder().build(0L, FilterResult(clean, emptyList()), acc)
    }

    @Test
    fun featureVector_dimensionAndOrderContract() {
        assertEquals(127, FeatureVectorBuilder.FEATURE_NAMES.size)
        // unique names
        assertEquals(FeatureVectorBuilder.FEATURE_NAMES.size, FeatureVectorBuilder.FEATURE_NAMES.toSet().size)
        // window group boundaries
        assertEquals("hrv_mean_ibi", FeatureVectorBuilder.FEATURE_NAMES[0])
        assertEquals("acc_time_since_movement", FeatureVectorBuilder.FEATURE_NAMES[30])
        assertEquals("hrv_mean_ibi_w90", FeatureVectorBuilder.FEATURE_NAMES[31])
        assertEquals("hrv_mean_ibi_w300", FeatureVectorBuilder.FEATURE_NAMES[93])
        assertEquals("clock_minutes_since_start", FeatureVectorBuilder.FEATURE_NAMES[124])
        assertEquals("clock_tod_cos", FeatureVectorBuilder.FEATURE_NAMES[126])
    }

    @Test
    fun featureVector_manifestMatchesNames() {
        val m = FeatureVectorBuilder.MANIFEST_JSON
        assertTrue(m.contains("\"count\": 127"))
        FeatureVectorBuilder.FEATURE_NAMES.forEach { assertTrue(m.contains("\"$it\"")) }
        // feature entries appear exactly once each — count lines inside the
        // "features": [ ... ] block.
        var inBlock = false
        var count = 0
        for (line in m.lines()) {
            val tr = line.trim()
            if (tr.startsWith("\"features\"")) { inBlock = true; continue }
            if (inBlock && tr == "]") break
            if (inBlock && tr.isNotEmpty()) count++
        }
        assertEquals(FeatureVectorBuilder.FEATURE_NAMES.size, count)
    }

    @Test
    fun featureVector_stableAcrossRunsAndDeterministicOrder() {
        val epochs = syntheticNight(20)
        val b = FeatureVectorBuilder()
        val v1 = b.build(epochs, 10, 0L)
        val v2 = b.build(epochs, 10, 0L)
        assertEquals(FeatureVectorBuilder.FEATURE_NAMES.size, v1.values.size)
        assertTrue(v1.values.contentEquals(v2.values))
        // target-epoch values finite for a clean night
        val idx = FeatureVectorBuilder.FEATURE_NAMES.indexOf("hrv_sdnn")
        assertTrue(v1.values[idx].isFinite())
        val respIdx = FeatureVectorBuilder.FEATURE_NAMES.indexOf("resp_rate")
        assertTrue(v1.values[respIdx].isFinite())
        // clock features
        val clockIdx = FeatureVectorBuilder.FEATURE_NAMES.indexOf("clock_minutes_since_start")
        assertEquals(5.0, v1.values[clockIdx], 1e-9) // epoch 10 → 10 × 0.5 min
        val sinIdx = FeatureVectorBuilder.FEATURE_NAMES.indexOf("clock_tod_sin")
        val cosIdx = FeatureVectorBuilder.FEATURE_NAMES.indexOf("clock_tod_cos")
        assertEquals(1.0, v1.values[sinIdx] * v1.values[sinIdx] + v1.values[cosIdx] * v1.values[cosIdx], 1e-9)
    }

    @Test
    fun featureVector_contextWindowsSmoothBaseFeatures() {
        val epochs = syntheticNight(20)
        val v = FeatureVectorBuilder().build(epochs, 10, 0L)
        val names = FeatureVectorBuilder.FEATURE_NAMES
        listOf("hrv_mean_ibi", "hrv_sdnn", "acc_enmo").forEach { base ->
            val w30 = v.values[names.indexOf(base)]
            val w300 = v.values[names.indexOf(base + "_w300")]
            assertTrue("$base w30=$w30", w30.isFinite())
            assertTrue("$base w300=$w300", w300.isFinite())
        }
        // 30 s alpha2 needs ≥ 128 beats (~33) → invalid; 300 s window valid.
        val a2Short = v.values[names.indexOf("hrv_dfa_alpha2")]
        assertFalse("expected NaN for 30s dfa alpha2, got $a2Short", a2Short.isFinite())
        val a2Long = v.values[names.indexOf("hrv_dfa_alpha2_w300")]
        assertTrue("expected finite 300s dfa alpha2, got $a2Long", a2Long.isFinite())
    }

    @Test
    fun featureVector_badEpochsYieldNanNotException() {
        // Night where 1 epoch has too few beats (<10) and one has a failing
        // acceptance gate (>40% rejected): windows covering those epochs must
        // emit NaN HRV features instead of throwing (ml_pipeline/features.py parity).
        val good = syntheticNight(8)
        val sparse = good[2].copy(ibis = good[2].ibis.take(3))
        val epochs = good.mapIndexed { i, e -> if (i == 2) sparse else e }
        val b = FeatureVectorBuilder()
        val names = FeatureVectorBuilder.FEATURE_NAMES
        // must not throw for any epoch
        val v = b.build(epochs, 2, 0L)
        // 30 s target window of the sparse epoch: time/freq/nonlinear HRV all NaN
        listOf("hrv_mean_ibi", "hrv_sdnn", "hrv_rmssd", "hrv_vlf_lsp", "hrv_sd1", "hrv_sampen", "resp_rate")
            .forEach { n ->
                val value = v.values[names.indexOf(n)]
                assertTrue("expected NaN for $n on sparse epoch, got $value", value.isNaN())
                assertFalse("valid[$n] must be false", v.valid[names.indexOf(n)])
            }
        // clock features remain finite
        assertTrue(v.values[names.indexOf("clock_minutes_since_start")].isFinite())
    }

    @Test
    fun featureVector_contextWindowRecoversFromSparseTargetEpoch() {
        val good = syntheticNight(12)
        val sparse = good[6].copy(ibis = good[6].ibis.take(2))
        val epochs = good.mapIndexed { i, e -> if (i == 6) sparse else e }
        val v = FeatureVectorBuilder().build(epochs, 6, 0L)
        val names = FeatureVectorBuilder.FEATURE_NAMES
        // 300 s context window pools enough beats → finite again
        val w300 = v.values[names.indexOf("hrv_rmssd_w300")]
        assertTrue("expected finite w300 rmssd, got $w300", w300.isFinite())
    }

    @Test
    fun featureVector_noBeatsAtAllYieldsAllNanHrv() {
        // ACC-only night: every HRV/resp feature NaN, actigraphy finite.
        val acc = mutableListOf<com.example.sploot.ble.AccSample>()
        val fs = 52
        for (i in 0 until 5 * 30 * fs) {
            val tt = i.toDouble() / fs
            acc += com.example.sploot.ble.AccSample((tt * 1000).toLong(), 1000, 0, 0, fs)
        }
        val epochs = EpochBuilder().build(0L, FilterResult(emptyList(), emptyList()), acc)
        val v = FeatureVectorBuilder().build(epochs, 2, 0L)
        val names = FeatureVectorBuilder.FEATURE_NAMES
        assertTrue(v.values[names.indexOf("hrv_mean_ibi")].isNaN())
        assertTrue(v.values[names.indexOf("hrv_sdnn_w90")].isNaN())
        assertTrue(v.values[names.indexOf("acc_enmo")].isFinite())
        assertTrue(v.valid[names.indexOf("acc_enmo")])
    }

    @Test
    fun actigraphyMovementFlagsPresentInVector() {
        val epochs = syntheticNight(20)
        val v = FeatureVectorBuilder().build(epochs, 0, 0L)
        val names = FeatureVectorBuilder.FEATURE_NAMES
        val enmo = v.values[names.indexOf("acc_enmo")]
        assertTrue(enmo.isFinite())
        // clamped-|sin| mean ≈ 100/π ≈ 31.8 mg
        assertTrue("enmo=$enmo", enmo in 25.0..40.0)
        val tslm = v.values[names.indexOf("acc_time_since_movement")]
        // epoch 0 of synthetic night is above threshold → 0
        assertEquals(0.0, tslm, 1e-9)
    }

    // ---------- helpers ----------

    private fun sineRr(modHz: Double, minutes: Double = 2.0): Pair<DoubleArray, DoubleArray> {
        val t = mutableListOf<Double>()
        val y = mutableListOf<Double>()
        var clock = 0.0
        while (clock < minutes * 60) {
            val ibi = 900 + 60 * sin(2 * PI * modHz * clock)
            clock += ibi / 1000.0
            t += clock
            y += ibi
        }
        return t.toDoubleArray() to y.toDoubleArray()
    }
}
