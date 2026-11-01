package com.example.sploot

import com.example.sploot.ble.AccSample
import com.example.sploot.ble.IbiSample
import com.example.sploot.ble.IbiSource
import com.example.sploot.data.repo.IbiPoint
import com.example.sploot.data.repo.StagePoint
import com.example.sploot.data.repo.SummaryCalculator
import com.example.sploot.features.FeatureVectorBuilder
import com.example.sploot.ml.EngineRegistry
import com.example.sploot.ml.ModelLoader
import com.example.sploot.ml.PerNightNormalizer
import com.example.sploot.ml.StagingPipeline
import com.example.sploot.ml.ViterbiSmoother
import com.example.sploot.signal.ArtifactFilter
import com.example.sploot.signal.EpochBuilder
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Full-night, Robolectric-free integration regression test: a synthetic 8 h
 * night with stage-dependent HRV/actigraphy dynamics flows through the ENTIRE
 * pipeline — ArtifactFilter → EpochBuilder → FeatureVectorBuilder →
 * PerNightNormalizer → placeholder ONNX engines → Viterbi → SummaryCalculator —
 * and must produce a structurally valid hypnogram and summary without any
 * exception.
 *
 * Synthetic stage dynamics (mimicking autonomic signatures):
 *  - Wake:  fast, irregular HR (~92 bpm ±60 ms) + large ACC movement
 *  - Light: ~70 bpm ±40 ms, still
 *  - Deep:  low HR (~60 bpm ±15 ms, very regular), still
 *  - REM:   ~85 bpm ±80 ms (LF-dominant irregularity), still
 */
class FullNightPipelineTest {

    private val epochMs = 30_000L
    private val startMs = 1_770_000_000_000L // fixed wall clock for determinism

    private class StageDynamics(val meanMs: Double, val sdMs: Double, val accAmp: Int)

    private val dynamics = mapOf(
        0 to StageDynamics(650.0, 60.0, 200), // Wake
        1 to StageDynamics(850.0, 40.0, 8),   // Light
        2 to StageDynamics(1000.0, 15.0, 5),  // Deep
        3 to StageDynamics(700.0, 80.0, 6),   // REM
    )

    /** ~8 h hypnogram: initial wake then ultradian W→L→D→L→REM cycles. */
    private fun stageScript(nEpochs: Int): IntArray {
        val stages = IntArray(nEpochs)
        var i = 0
        fun fill(stage: Int, count: Int) {
            repeat(count) { if (i < nEpochs) stages[i++] = stage }
        }
        fill(0, 10) // sleep onset wake
        while (i < nEpochs) {
            fill(1, 8)
            fill(2, 14)
            fill(1, 8)
            fill(3, 7)
        }
        return stages
    }

    private fun assetsModelsDir(): File =
        listOf(File("src/main/assets/models"), File("app/src/main/assets/models"))
            .firstOrNull { it.isDirectory }
            ?: error("assets/models not found")

    @Test
    fun `synthetic eight hour night flows through the whole pipeline`() =
        kotlinx.coroutines.runBlocking {
        val nEpochs = 960 // exactly 8 h of 30 s epochs
        val script = stageScript(nEpochs)
        val rnd = java.util.Random(42)

        // ---- synthetic raw streams ----
        val ibis = ArrayList<IbiSample>(nEpochs * 40)
        val accs = ArrayList<AccSample>(nEpochs * 1_600)
        val accHz = 55
        var beatTs = startMs
        for (e in 0 until nEpochs) {
            val d = dynamics.getValue(script[e])
            val epochEnd = startMs + (e + 1) * epochMs
            while (beatTs < epochEnd) {
                val ibi = (d.meanMs + rnd.nextGaussian() * d.sdMs).roundToInt().coerceIn(300, 2000)
                beatTs += ibi
                if (beatTs < epochEnd) {
                    ibis.add(IbiSample(beatTs, ibi, IbiSource.PPI, false, true, 8))
                }
            }
            var accTs = startMs + e * epochMs
            while (accTs < epochEnd) {
                accs.add(
                    AccSample(
                        accTs,
                        (rnd.nextGaussian() * d.accAmp).toInt(),
                        (rnd.nextGaussian() * d.accAmp).toInt(),
                        (rnd.nextGaussian() * d.accAmp).toInt(),
                        accHz,
                    ),
                )
                accTs += 1_000L / accHz
            }
        }

        // ---- pipeline ----
        val filtered = ArtifactFilter().filter(ibis)
        assertTrue(
            "most synthetic IBIs must survive filtering (${filtered.clean.size}/${ibis.size})",
            filtered.clean.size > ibis.size * 3 / 4,
        )

        val epochs = EpochBuilder().build(startMs, filtered, accs)
        assertEquals("epoch count must match the scripted night", nEpochs, epochs.size)
        val validHrv = epochs.count { it.hrvValid }
        val validAcc = epochs.count { it.accValid }
        assertTrue("most epochs must have valid HRV ($validHrv/$nEpochs)", validHrv > nEpochs * 9 / 10)
        assertTrue("most epochs must have valid ACC ($validAcc/$nEpochs)", validAcc > nEpochs * 9 / 10)
        assertEquals(startMs, epochs.first().startMs)

        val builder = FeatureVectorBuilder()
        val featureRows = (0 until epochs.size).map { builder.build(epochs, it, startMs) }
        assertEquals(nEpochs, featureRows.size)
        featureRows.forEach { r ->
            assertEquals(FeatureVectorBuilder.FEATURE_NAMES.size, r.values.size)
            assertEquals(r.values.size, r.valid.size)
            // clock features are always present (last three)
            assertTrue(r.valid[r.valid.size - 1])
        }

        val loader = ModelLoader.forDirectory(assetsModelsDir())

        for (engineId in EngineRegistry.ALL) {
            val engine = EngineRegistry.create(engineId, loader.load(engineId))
            val pipeline = StagingPipeline.create(engine) { null } // no learned HMM: defaults
            val result = pipeline.stageNight(
                featureRows.map { ef -> FloatArray(ef.values.size) { ef.values[it].toFloat() } },
            )

            assertEquals("engine $engineId stage count", nEpochs, result.stages.size)
            assertEquals(nEpochs, result.probabilities.size)
            result.stages.forEach { assertTrue("stage in 0..3: $it", it in 0..3) }
            result.probabilities.forEach { row ->
                assertEquals(4, row.size)
                assertTrue(row.all { it >= 0f })
                assertTrue("row sums to 1", abs(row.sum() - 1f) < 1e-3f)
            }
        }

        // ---- summary over the (default-transition) Viterbi output ----
        val seqEngine = EngineRegistry.create(EngineRegistry.SEQ, loader.load(EngineRegistry.SEQ))
        val raw = seqEngine.stage(PerNightNormalizer().fitTransform(featureRows.map { ef -> FloatArray(ef.values.size) { ef.values[it].toFloat() } }))
        val smoothed = ViterbiSmoother().smooth(raw.probabilities)
        assertEquals(nEpochs, smoothed.stages.size)

        val summary = SummaryCalculator.calculate(
            stages = smoothed.stages.indices.map { StagePoint(it.toLong() * epochMs, smoothed.stages[it]) },
            ibis = ibis.map { IbiPoint(it.timestampMs, it.intervalMs) },
        )
        assertEquals("TIB = 8 h", 480.0, summary.tibMin, 0.01)
        assertTrue("TST <= TIB", summary.tstMin <= summary.tibMin)
        assertTrue(summary.sleepEfficiencyPct in 0.0..100.0)
        assertTrue(summary.wakeMin + summary.lightMin + summary.deepMin + summary.remMin <= summary.tibMin + 0.01)
        assertTrue("lowest RHR computed from IBIs", summary.lowestRhrBpm != null && summary.lowestRhrBpm in 40.0..110.0)
        assertTrue("rMSSD computed from IBIs", summary.meanRmssdMs != null && summary.meanRmssdMs!! > 0)
    }
}
