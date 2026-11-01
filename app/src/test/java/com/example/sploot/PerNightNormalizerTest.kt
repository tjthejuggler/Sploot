package com.example.sploot

import com.example.sploot.ml.PerNightNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PerNightNormalizerTest {

    private val n = PerNightNormalizer()

    private fun night(vararg cols: FloatArray): List<FloatArray> =
        (cols[0].indices).map { i -> FloatArray(cols.size) { j -> cols[j][i] } }

    @Test
    fun `median and IQR scaling with linear interpolation`() {
        // column of 5 values: median 2, q1 1, q3 3 → IQR 2
        val stats = n.fit(night(floatArrayOf(0f, 1f, 2f, 3f, 4f)))
        assertEquals(2.0, stats.median[0], 1e-9)
        assertEquals(2.0, stats.scale[0], 1e-9)
        val out = n.transform(night(floatArrayOf(0f, 1f, 2f, 3f, 4f)), stats)
        // x=4 → (4-2)/2 = 1
        assertEquals(1.0f, out[4][0], 1e-6f)
        assertEquals(0.0f, out[2][0], 1e-6f) // median maps to 0
    }

    @Test
    fun `constant column falls back to scale 1`() {
        // IQR 0, MAD 0, SD 0 → scale 1; median = constant → all outputs 0
        val stats = n.fit(night(floatArrayOf(5f, 5f, 5f, 5f)))
        assertEquals(1.0, stats.scale[0], 1e-9)
        val out = n.transform(night(floatArrayOf(5f, 5f, 5f, 5f)), stats)
        assertTrue(out.all { it[0] == 0f })
    }

    @Test
    fun `zero IQR with spread falls back to standard deviation`() {
        // [0,1,1,1,1,10]: median 1, IQR 0 (middle half all 1), MAD 0 → SD fallback
        val col = floatArrayOf(0f, 1f, 1f, 1f, 1f, 10f)
        val stats = n.fit(night(col))
        assertTrue(stats.scale[0] > 0.0)
        // mean = 14/6, population SD
        val mean = 14.0 / 6.0
        val sd = kotlin.math.sqrt(col.map { (it - mean) * (it - mean) }.sum() / col.size)
        assertEquals(sd, stats.scale[0], 1e-9)
    }

    @Test
    fun `feature with over 50 percent NaN passes through unscaled`() {
        // 3 of 4 epochs NaN → passthrough; the one real value (100) kept as-is
        val col = floatArrayOf(Float.NaN, 100f, Float.NaN, Float.NaN)
        val stats = n.fit(night(col))
        assertEquals(3, stats.nanCounts[0])
        assertTrue(stats.passThrough[0])
        val out = n.transform(night(col), stats)
        assertEquals(100f, out[1][0], 1e-6f)
        assertEquals(0f, out[0][0], 1e-6f) // NaN imputed with 0
    }

    @Test
    fun `NaNs at or below 50 percent are excluded from stats then imputed with 0`() {
        // 2 of 4 NaN (exactly 50%): real values [1,2] → median 1.5, q1/q3 both in [1,2]
        val col = floatArrayOf(Float.NaN, 1f, 2f, Float.NaN)
        val stats = n.fit(night(col))
        assertTrue(!stats.passThrough[0])
        assertEquals(1.5, stats.median[0], 1e-9)
        val out = n.transform(night(col), stats)
        assertEquals(0f, out[0][0], 1e-6f)
        assertEquals(0f, out[3][0], 1e-6f)
        // non-NaN scaled: (1-1.5)/scale and (2-1.5)/scale are symmetric around 0
        assertTrue(abs(out[1][0] + out[2][0]) < 1e-6f)
    }

    @Test
    fun `ragged matrix rejected`() {
        try {
            n.fit(listOf(FloatArray(3), FloatArray(4)))
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
