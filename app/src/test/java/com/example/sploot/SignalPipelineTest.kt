package com.example.sploot

import com.example.sploot.ble.AccSample
import com.example.sploot.ble.IbiSample
import com.example.sploot.ble.IbiSource
import com.example.sploot.features.ActigraphyFeatures
import com.example.sploot.signal.ArtifactFilter
import com.example.sploot.signal.CleanBeat
import com.example.sploot.signal.EpochBuilder
import com.example.sploot.signal.Interpolator
import com.example.sploot.signal.respiration.EdrExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class SignalPipelineTest {

    // ---------- ArtifactFilter ----------

    private fun ibi(ts: Long, ms: Int, blocker: Boolean? = null, skin: Boolean? = null, err: Int? = null) =
        IbiSample(ts, ms, IbiSource.PPI, blocker, skin, err)

    @Test
    fun artifactFilter_rejectsFlaggedSamples() {
        val f = ArtifactFilter().filter(
            listOf(
                ibi(0, 900),
                ibi(900, 910, blocker = true),
                ibi(1810, 920, skin = false),
                ibi(2730, 930, err = 200),
                ibi(3660, 940),
            ),
        )
        assertEquals(2, f.clean.size)
        assertEquals(3, f.rejectedCount)
        assertTrue(f.rejected.all { it.reason != com.example.sploot.signal.RejectionReason.OUT_OF_BOUNDS })
    }

    @Test
    fun artifactFilter_rejectsOutOfBounds() {
        val f = ArtifactFilter().filter(
            listOf(ibi(0, 299), ibi(300, 900), ibi(1200, 2201), ibi(3401, 950)),
        )
        assertEquals(2, f.clean.size)
        assertTrue(
            f.rejected.any { it.reason == com.example.sploot.signal.RejectionReason.OUT_OF_BOUNDS },
        )
    }

    @Test
    fun artifactFilter_rejectsEctopicDeviatingMoreThan20Percent() {
        val samples = (0 until 30).map { i ->
            ibi(i * 1000L, 1000 + (if (i % 2 == 0) 10 else -10)) // ±10 ms jitter
        }.toMutableList()
        samples.add(10, ibi(10_500, 1400)) // +40% deviation → ectopic
        val f = ArtifactFilter().filter(samples)
        assertTrue(f.rejected.any { it.reason == com.example.sploot.signal.RejectionReason.ECTOPIC })
        assertFalse(f.clean.any { it.ibiMs == 1400.0 })
    }

    @Test
    fun artifactFilter_keepsStableSeriesIntact() {
        val samples = (0 until 50).map { i -> ibi(i * 1000L, 1000) }
        val f = ArtifactFilter().filter(samples)
        assertEquals(50, f.clean.size)
        assertEquals(0, f.rejectedCount)
    }

    // ---------- Interpolator ----------

    @Test
    fun interpolator_uniformRateAndValues() {
        val beats = (1..30).map { CleanBeat(it * 1000L, 1000.0) }
        val tach = Interpolator().tachogram(beats, 0, 30_000)
        assertEquals(4.0, tach.sampleRateHz, 1e-9)
        assertEquals(121, tach.values.size)
        // beats span 1–30 s → grid 0–30 s; only the first second is uncovered
        assertTrue(tach.validRatio > 0.96)
        tach.values.filter { !it.isNaN() }.forEach { assertEquals(1000.0, it, 1e-9) }
    }

    @Test
    fun interpolator_leavesLargeGapsAsNaN() {
        // beats at ~1 s spacing, then 3 beats missing (> 2), then resumes.
        val beats = buildList {
            for (i in 1..10) add(CleanBeat(i * 1000L, 1000.0))
            for (i in 14..24) add(CleanBeat(i * 1000L, 1000.0)) // 3 s hole
        }
        val tach = Interpolator().tachogram(beats, 0, 25_000)
        assertTrue(tach.validRatio < 1.0)
        // inside the hole (11 s .. 13 s) must be NaN
        for (i in (11 * 4) until (13 * 4)) {
            assertTrue("expected NaN at $i", tach.values[i].isNaN())
            assertFalse(tach.valid[i])
        }
        // outside the hole values exist
        assertFalse(tach.values[5 * 4].isNaN())
        assertFalse(tach.values[20 * 4].isNaN())
    }

    @Test
    fun interpolator_pchipDoesNotOvershootMonotoneData() {
        // Monotone input → output must stay within [min, max].
        val beats = buildList {
            var t = 0L
            for (i in 1..40) {
                t += 800 + i * 8 // rising 808..1112 ms
                add(CleanBeat(t, (800 + i * 8).toDouble()))
            }
        }
        val tach = Interpolator().tachogram(beats, beats.first().timestampMs, beats.last().timestampMs)
        tach.values.filter { !it.isNaN() }.forEach {
            assertTrue(it in 800.0..1120.0)
        }
    }

    // ---------- EpochBuilder ----------

    @Test
    fun epochBuilder_groupsBeatsInto30sEpochs() {
        val clean = (0 until 90).map { i -> CleanBeat((i + 1) * 1000L, 1000.0) }
        val epochs = EpochBuilder().build(0L, com.example.sploot.signal.FilterResult(clean, emptyList()), emptyList())
        // beat at 90 s opens a 4th epoch with a single beat; beat at exactly
        // 30 s / 60 s belongs to the FOLLOWING epoch (half-open windows)
        assertEquals(4, epochs.size)
        assertEquals(29, epochs[0].ibis.size)
        assertEquals(30, epochs[1].ibis.size)
        assertEquals(30, epochs[2].ibis.size)
        assertEquals(1, epochs[3].ibis.size)
        epochs.forEach { assertTrue(it.hrvValid) }
    }

    @Test
    fun epochBuilder_marksEpochInvalidWhenBelow60PercentAcceptable() {
        val clean = (0 until 60).map { i -> CleanBeat((i + 1) * 1000L, 1000.0) }.toMutableList()
        val rejected = (0 until 45).map {
            com.example.sploot.signal.RejectedIbi(30_000 + it * 300L, 2500, com.example.sploot.signal.RejectionReason.OUT_OF_BOUNDS)
        }
        val epochs = EpochBuilder().build(
            0L,
            com.example.sploot.signal.FilterResult(clean, rejected),
            emptyList(),
        )
        assertEquals(3, epochs.size)
        assertFalse(epochs[1].hrvValid) // 30 clean vs 45 rejected = 40%
        assertTrue(epochs[0].hrvValid)
        assertEquals(45, epochs[1].rejectedCount)
        assertEquals(0.4, epochs[1].acceptableIbiRatio, 1e-9)
    }

    // ---------- EdrExtractor ----------

    @Test
    fun edr_rsaPeakDetectsSineModulatedRr() {
        // RR modulated at 0.25 Hz → 15 brpm expected from the LSP spectrum.
        val (t, y) = sineModulatedRr(minutes = 2.0, modHz = 0.25, amplitude = 60.0)
        val (freqs, power) = com.example.sploot.features.HrvFrequencyDomain.lombScargle(t, y)
        val est = EdrExtractor.estimate(freqs, power, emptyList())
        assertEquals(15.0, est.rateBrpm, 1.5) // ±10%
        assertFalse(est.fromAcc)
        assertTrue(est.confidence > 0.05)
    }

    @Test
    fun edr_accPeakDetectsRespiratoryMotionAndWinsFusion() {
        val acc = respiratoryAcc(seconds = 60, respHz = 0.22, fs = 52)
        val (t, y) = sineModulatedRr(minutes = 1.0, modHz = 0.30, amplitude = 25.0)
        val (freqs, power) = com.example.sploot.features.HrvFrequencyDomain.lombScargle(t, y)
        val est = EdrExtractor.estimate(freqs, power, acc)
        assertTrue(est.fromAcc)
        assertEquals(0.22 * 60, est.rateBrpm, 1.5) // ±10%
    }

    @Test
    fun edr_rrvIsSmallForStableBreathing() {
        val acc = respiratoryAcc(seconds = 60, respHz = 0.25, fs = 52)
        val (rt, ry) = sineModulatedRr(minutes = 1.0, modHz = 0.25, amplitude = 40.0)
        val (freqs, power) = com.example.sploot.features.HrvFrequencyDomain.lombScargle(rt, ry)
        val est = EdrExtractor.estimate(freqs, power, acc)
        assertTrue(est.rrvBrpm.isFinite())
        assertTrue("rrv=${est.rrvBrpm}", est.rrvBrpm < 4.0)
    }

    // ---------- Actigraphy ----------

    @Test
    fun enmo_handComputation() {
        // norms 1000 mg and 1100 mg → ENMO contributions 0 and 100 → mean 50 mg.
        val samples = listOf(
            AccSample(0, 1000, 0, 0, 52),
            AccSample(20, 1100, 0, 0, 52),
        )
        val m = ActigraphyFeatures.compute(samples)
        assertEquals(50.0, m.enmo, 1e-9)
        assertTrue(m.movement) // > 22 mg
    }

    @Test
    fun enmo_clampsBelowOneG() {
        val samples = listOf(
            AccSample(0, 980, 0, 0, 52),
            AccSample(20, 1010, 0, 0, 52),
        )
        val m = ActigraphyFeatures.compute(samples)
        assertEquals(5.0, m.enmo, 1e-9) // only 1010 contributes 10, halved
        assertFalse(m.movement)
    }

    @Test
    fun timeSinceLastMovement_countsEpochs() {
        val enmo = doubleArrayOf(30.0, 5.0, 5.0, 5.0, 40.0, 5.0)
        assertEquals(0.0, ActigraphyFeatures.timeSinceLastMovement(enmo, 0), 1e-9)
        assertEquals(1.0, ActigraphyFeatures.timeSinceLastMovement(enmo, 1), 1e-9)
        assertEquals(3.0, ActigraphyFeatures.timeSinceLastMovement(enmo, 3), 1e-9)
        assertEquals(0.0, ActigraphyFeatures.timeSinceLastMovement(enmo, 4), 1e-9)
        assertEquals(1.0, ActigraphyFeatures.timeSinceLastMovement(enmo, 5), 1e-9)
    }

    @Test
    fun timeSinceLastMovement_nanWhenNoMovementSeenYet() {
        val enmo = doubleArrayOf(5.0, 5.0)
        assertTrue(ActigraphyFeatures.timeSinceLastMovement(enmo, 1).isNaN())
    }

    // ---------- helpers ----------

    /** RR series with sinusoidal modulation at [modHz]; returns (tSec, ibiMs). */
    internal fun sineModulatedRr(minutes: Double, modHz: Double, amplitude: Double): Pair<DoubleArray, DoubleArray> {
        val t = mutableListOf<Double>()
        val y = mutableListOf<Double>()
        var clock = 0.0
        var idx = 0
        val end = minutes * 60.0
        while (clock < end) {
            val ibi = 900 + amplitude * sin(2 * PI * modHz * clock)
            clock += ibi / 1000.0
            t += clock
            y += ibi
            idx++
        }
        return t.toDoubleArray() to y.toDoubleArray()
    }

    /** ACC stream whose magnitude oscillates ±80 mg at [respHz] around 1 g. */
    internal fun respiratoryAcc(seconds: Int, respHz: Double, fs: Int): List<AccSample> {
        return (0 until seconds * fs).map { i ->
            val t = i.toDouble() / fs
            val mod = 80 * sin(2 * PI * respHz * t)
            AccSample((t * 1000).toLong(), (1000 + mod).toInt(), 0, 0, fs)
        }
    }

    private fun sqrtMag(x: Int, y: Int, z: Int): Double = sqrt((x * x + y * y + z * z).toDouble())
}
