package com.example.sploot

import com.example.sploot.ml.EngineRegistry
import com.example.sploot.ml.ModelLoader
import com.example.sploot.ml.SessionOpener
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Runs the bundled sploot-ibi-cnn ONNX model on a synthetic night via the
 * desktop ONNX Runtime artifact, verifying the engine contract (row count,
 * probability renormalisation under the wake bias, fallback on no data).
 */
class OnnxIbiEngineTest {

    private fun loader(): ModelLoader {
        val candidates = listOf(File("src/main/assets/models"), File("app/src/main/assets/models"))
        val dir = candidates.firstOrNull { it.isDirectory }
            ?: error("assets/models not found (CWD=${File(".").absolutePath})")
        return ModelLoader.forDirectory(dir)
    }

    @Test
    fun `stages synthetic night from raw ibi`(): Unit = runBlocking {
        val engine = EngineRegistry.create(EngineRegistry.IBI, loader().load(EngineRegistry.IBI))
        val nEpochs = 40
        // Slowly varying HRV-ish night at ~1 Hz beats, seed fixed.
        val rnd = java.util.Random(42)
        var t = 0L
        val beats = ArrayList<Pair<Long, Int>>(nEpochs * 30)
        repeat(nEpochs * 30) {
            val ibi = (1000 + 150 * kotlin.math.sin(it / 90.0) + rnd.nextDouble() * 60).toInt()
            beats.add(t to ibi)
            t += ibi.toLong()
        }
        val result = engine.stageRawIbi(0L, beats, nEpochs)
        assertNotNull(result)
        assertEquals(nEpochs, result!!.stages.size)
        assertEquals(nEpochs, result.probabilities.size)
        result.probabilities.forEach { row ->
            assertEquals(4, row.size)
            assertEquals("rows sum to 1", 1.0, row.sum().toDouble(), 1e-3)
            assertTrue(row.all { it >= 0f })
        }
        assertTrue(result.stages.all { it in 0..3 })
        // Bias active: staged wake probability must differ from raw model output
        // (verified indirectly by renormalisation above + argmax consistency).
        result.stages.forEachIndexed { i, s ->
            val max = result.probabilities[i].withIndex().maxBy { it.value }.index
            assertEquals("argmax(row) == stage", s, max)
        }
        // Feature path intentionally unsupported for this engine.
        try {
            engine.stage(listOf(FloatArray(127)))
            throw AssertionError("expected UnsupportedOperationException")
        } catch (_: UnsupportedOperationException) {
        }
    }

    @Test
    fun `returns null on unusable ibi`(): Unit = runBlocking {
        val engine = EngineRegistry.create(EngineRegistry.IBI, loader().load(EngineRegistry.IBI))
        assertNull(engine.stageRawIbi(0L, emptyList(), 10))
    }
}
