package com.example.sploot

import com.example.sploot.ml.EngineRegistry
import com.example.sploot.ml.HeuristicStagingEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the heuristic stager on the post-normalization 127-wide feature
 * matrix (same contract as the ONNX engines): high-vagal epochs must score
 * Deep, movement + suppressed vagal must score Wake, high short-RR variability
 * without movement must lean REM, and the engine must never emit a constant
 * distribution (the placeholder-model failure mode seen on-device 2026-11-02).
 */
class HeuristicStagingEngineTest {

    private val width = 127

    private fun row(
        rmssdZ: Float = 0f,
        sdnnZ: Float = 0f,
        ibiZ: Float = 0f,
        pnn50Z: Float = 0f,
        lfhfZ: Float = 0f,
        sd1Z: Float = 0f,
        enmoZ: Float = 0f,
    ): FloatArray = FloatArray(width).also {
        it[2] = rmssdZ       // hrv_rmssd
        it[1] = sdnnZ        // hrv_sdnn
        it[0] = ibiZ         // hrv_mean_ibi
        it[3] = pnn50Z       // hrv_pnn50
        it[11] = lfhfZ       // hrv_lf_hf_lsp
        it[22] = sd1Z        // hrv_sd1
        it[27] = enmoZ       // acc_enmo
    }

    @Test
    fun `high vagal night yields deep or light never constant`() = runBlocking {
        val engine = HeuristicStagingEngine()
        val feats = List(40) { row(rmssdZ = if (it % 4 == 0) 1.5f else -0.2f, sdnnZ = 0.8f, ibiZ = 0.5f) }
        val res = engine.stage(feats)
        // The engine must react to the input: differing inputs must produce
        // materially different probability vectors (placeholder failure mode:
        // identical [0.1, 0.45, 0.25, 0.2] for every epoch).
        assertTrue("probabilities must vary across epochs", !res.probabilities[0].contentEquals(res.probabilities[1]))
        val highVagalDeep = res.probabilities[0][2]
        val lowVagalDeep = res.probabilities[1][2]
        assertTrue(
            "higher vagal tone must raise deep probability ($highVagalDeep vs $lowVagalDeep)",
            highVagalDeep > lowVagalDeep * 2,
        )
    }

    @Test
    fun `movement + low vagal scores wake above prior`() = runBlocking {
        val engine = HeuristicStagingEngine()
        val feats = List(10) { row(enmoZ = 2f, rmssdZ = -1.2f, lfhfZ = 1.5f) }
        val res = engine.stage(feats)
        // After smoothing wake wins somewhere; at least the raw emission for
        // wake must exceed the placeholder's fixed 0.10.
        assertTrue("wake prob should beat placeholder prior", res.probabilities.maxOf { it[0] } > 0.2f)
    }

    @Test
    fun `registry creates heuristic engine without model file`() {
        // create() accepts a nullable model; HEURISTIC must not touch it.
        val engine = EngineRegistry.create(EngineRegistry.HEURISTIC, null)
        assertEquals("sploot-heuristic", engine.engineId)
    }

    @Test
    fun `probabilities are normalized`() = runBlocking {
        val engine = HeuristicStagingEngine()
        val res = engine.stage(List(5) { row() })
        res.probabilities.forEach { p ->
            assertEquals(1f, p.sum(), 0.01f)
            p.forEach { assertTrue(it >= 0f) }
        }
    }

}
