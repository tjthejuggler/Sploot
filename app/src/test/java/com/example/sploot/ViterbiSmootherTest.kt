package com.example.sploot

import com.example.sploot.ml.ViterbiSmoother
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViterbiSmootherTest {

    private val smoother = ViterbiSmoother()

    /** Build one-hot-ish probability rows favoring [stage] with margin m. */
    private fun favor(stage: Int, m: Float = 0.6f): FloatArray =
        FloatArray(4) { if (it == stage) m else (1f - m) / 3f }

    @Test
    fun `single-epoch deep flicker between light epochs is smoothed away`() {
        // L L D L L: the lone Deep epoch is a flicker; Viterbi should drop it.
        val probs = arrayOf(favor(1), favor(1), favor(2), favor(1), favor(1))
        val s = smoother.smooth(probs)
        assertEquals(listOf(1, 1, 1, 1, 1), s.stages.toList())
        assertTrue(s.flickerAfter < s.flickerBefore)
    }

    @Test
    fun `direct deep to rem transition is suppressed`() {
        // W D D R R R strongly favored per-epoch: a Deep→REM adjacency is
        // physiologically implausible (A(Deep, REM) ≈ 0.01), so Viterbi must
        // route through Wake or Light somewhere between the Deep and REM blocks.
        val probs = arrayOf(
            favor(0), favor(2), favor(2), favor(3), favor(3), favor(3),
        )
        val s = smoother.smooth(probs)
        val path = s.stages.toList()
        var deepToRem = false
        for (i in 1 until path.size) {
            if (path[i - 1] == 2 && path[i] == 3) deepToRem = true
        }
        assertFalse("smoothed path contains direct Deep→REM transition: $path", deepToRem)
    }

    @Test
    fun `stable block sequence is preserved`() {
        // strong emissions so the already-plausible argmax path is the Viterbi optimum
        val probs = arrayOf(
            favor(0, 0.92f), favor(0, 0.92f), favor(1, 0.92f), favor(1, 0.92f),
            favor(2, 0.92f), favor(2, 0.92f), favor(1, 0.92f), favor(3, 0.92f),
            favor(3, 0.92f), favor(1, 0.92f),
        )
        val s = smoother.smooth(probs)
        assertEquals(favorStageSequence(probs), s.stages.toList()) // already smooth: unchanged
        assertEquals(5, s.flickerBefore)
        assertEquals(5, s.flickerAfter)
    }

    @Test
    fun `flicker diagnostic counts stage changes`() {
        val probs = arrayOf(favor(0), favor(1), favor(0), favor(0))
        val s = smoother.smooth(probs)
        assertEquals(2, s.flickerBefore)
    }

    @Test
    fun `empty and single epoch inputs handled`() {
        assertEquals(0, smoother.smooth(arrayOf()).stages.size)
        val one = smoother.smooth(arrayOf(favor(2)))
        assertEquals(1, one.stages.size)
        assertEquals(2, one.stages[0])
    }

    @Test
    fun `custom transition override allowed`() {
        // Uniform transitions → pure emission argmax path
        val uniform = Array(4) { DoubleArray(4) { 0.25 } }
        val s = ViterbiSmoother(uniform, doubleArrayOf(0.25, 0.25, 0.25, 0.25))
        val probs = arrayOf(favor(1), favor(2), favor(1))
        assertEquals(listOf(1, 2, 1), s.smooth(probs).stages.toList())
    }

    private fun favorStageSequence(probs: Array<FloatArray>): List<Int> =
        probs.map { row -> row.indices.maxByOrNull { row[it] }!! }
}
