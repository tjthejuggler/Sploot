package com.example.sploot

import com.example.sploot.features.FeatureVectorBuilder
import com.example.sploot.ml.EngineRegistry
import com.example.sploot.ml.ModelLoader
import com.example.sploot.ml.StagingPipeline
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

/**
 * End-to-end staging through the bundled placeholder ONNX models, executed with
 * the desktop ONNX Runtime test artifact (same ai.onnxruntime API as the AAR).
 */
class StagingEndToEndTest {

    private fun assetsModelsDir(): File =
        listOf(File("src/main/assets/models"), File("app/src/main/assets/models"))
            .firstOrNull { it.isDirectory }
            ?: error("assets/models not found (CWD=${File(".").absolutePath})")

    private fun syntheticNight(epochs: Int, seed: Int = 7): List<FloatArray> {
        val rnd = Random(seed)
        return List(epochs) {
            FloatArray(FeatureVectorBuilder.FEATURE_NAMES.size) { j ->
                // every 5th feature NaN in some epochs to exercise the imputation path
                if (j % 5 == 0 && rnd.nextFloat() < 0.1f) Float.NaN
                else rnd.nextFloat() * 10f
            }
        }
    }

    private fun assertValidResult(epochs: Int, result: com.example.sploot.ml.SleepStagingResult) {
        assertEquals(epochs, result.stages.size)
        assertEquals(epochs, result.probabilities.size)
        result.probabilities.forEach { row ->
            assertEquals(4, row.size)
            assertTrue("probabilities must be non-negative", row.all { it >= 0f })
            assertTrue("row must sum to 1: ${row.toList()}", abs(row.sum() - 1f) < 1e-3f)
        }
        result.stages.forEach { assertTrue(it in 0..3) }
    }

    @Test
    fun `sequence engine stages a synthetic night via placeholder model`() = runTest {
        val loader = ModelLoader.forDirectory(assetsModelsDir())
        val engine = EngineRegistry.create(EngineRegistry.SEQ, loader.load(EngineRegistry.SEQ))
        val result = StagingPipeline(engine).stageNight(syntheticNight(epochs = 40))
        assertValidResult(40, result)
        assertEquals(EngineRegistry.SEQ, result.engineId)
        // Placeholder argmax prior: light (0.45) → whole night Light
        assertTrue(result.stages.all { it == 1 })
    }

    @Test
    fun `gbdt engine stages a synthetic night via placeholder model`() = runTest {
        val loader = ModelLoader.forDirectory(assetsModelsDir())
        val engine = EngineRegistry.create(EngineRegistry.GBDT, loader.load(EngineRegistry.GBDT))
        val result = StagingPipeline(engine).stageNight(syntheticNight(epochs = 12, seed = 3))
        assertValidResult(12, result)
        assertEquals(EngineRegistry.GBDT, result.engineId)
    }

    @Test
    fun `sequence engine handles varying night lengths`() = runTest {
        val loader = ModelLoader.forDirectory(assetsModelsDir())
        val engine = EngineRegistry.create(EngineRegistry.SEQ, loader.load(EngineRegistry.SEQ))
        for (t in listOf(1, 2, 97)) {
            val result = StagingPipeline(engine).stageNight(syntheticNight(epochs = t))
            assertEquals(t, result.stages.size)
        }
    }

    @Test
    fun `pipeline result maps cleanly onto StageEntity rows`() = runTest {
        val loader = ModelLoader.forDirectory(assetsModelsDir())
        val engine = EngineRegistry.create(EngineRegistry.SEQ, loader.load(EngineRegistry.SEQ))
        val features = syntheticNight(epochs = 8)
        val result = StagingPipeline(engine).stageNight(features)
        // StageEntity(sessionId, epochIndex, stage, probabilities, engineId) shape
        val rows = result.stages.indices.map { i ->
            listOf("night-1", i, result.stages[i], result.probabilities[i].toList(), result.engineId)
        }
        assertEquals(8, rows.size)
        assertTrue(rows.all { it[2] in 0..3 && (it[3] as List<*>).size == 4 })
    }
}
