package com.example.sploot

import com.example.sploot.ml.HmmTransitionLoader
import com.example.sploot.ml.ViterbiSmoother
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

class HmmTransitionLoaderTest {

    private fun assetsOf(vararg files: Pair<String, String>): (String) -> java.io.InputStream? =
        { name -> files.firstOrNull { it.first == name }?.second?.let { ByteArrayInputStream(it.toByteArray()) } }

    @Test
    fun `missing asset returns null`() {
        assertNull(HmmTransitionLoader.loadOrNull({ null }))
    }

    @Test
    fun `valid learned matrix loads and is used by the smoother`() {
        val json = """
            {"A": [[0.95,0.04,0.005,0.005],[0.02,0.93,0.03,0.02],[0.01,0.02,0.96,0.01],[0.01,0.03,0.005,0.955]],
             "pi": [0.5,0.4,0.05,0.05]}
        """.trimIndent()
        val smoother = HmmTransitionLoader.parse(json)
        assertNotNull(smoother)
        assertArrayEquals(doubleArrayOf(0.95, 0.04, 0.005, 0.005), smoother.transitions[0], 1e-12)
        assertArrayEquals(doubleArrayOf(0.5, 0.4, 0.05, 0.05), smoother.initial, 1e-12)
        // smooth still works
        val res = smoother.smooth(
            arrayOf(
                floatArrayOf(0.8f, 0.1f, 0.05f, 0.05f),
                floatArrayOf(0.3f, 0.5f, 0.1f, 0.1f),
            ),
        )
        assertEquals(2, res.stages.size)
    }

    @Test
    fun `non-stochastic row is rejected`() {
        val json = """
            {"A": [[0.5,0.2,0.1,0.1],[0.02,0.93,0.03,0.02],[0.01,0.02,0.96,0.01],[0.01,0.03,0.005,0.955]],
             "pi": [0.5,0.4,0.05,0.05]}
        """.trimIndent()
        try {
            HmmTransitionLoader.parse(json)
            fail("expected IllegalArgumentException for non-stochastic row")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("row sums"))
        }
    }

    @Test
    fun `non-stochastic pi is rejected`() {
        val json = """
            {"A": [[0.95,0.04,0.005,0.005],[0.02,0.93,0.03,0.02],[0.01,0.02,0.96,0.01],[0.01,0.03,0.005,0.955]],
             "pi": [2.0,0.4,0.05,0.05]}
        """.trimIndent()
        try {
            HmmTransitionLoader.parse(json)
            fail("expected IllegalArgumentException for invalid pi")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `negative probability is rejected`() {
        val json = """
            {"A": [[-0.1,1.04,0.03,0.03],[0.02,0.93,0.03,0.02],[0.01,0.02,0.96,0.01],[0.01,0.03,0.005,0.955]],
             "pi": [0.5,0.4,0.05,0.05]}
        """.trimIndent()
        try {
            HmmTransitionLoader.parse(json)
            fail("expected IllegalArgumentException for negative probability")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `wrong shape is rejected`() {
        val json = """{"A": [[1.0,0.0],[0.0,1.0]], "pi": [0.5,0.5]}"""
        try {
            HmmTransitionLoader.parse(json)
            fail("expected IllegalArgumentException for non-4x4 matrix")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `loadOrNull surfaces present asset and throws on invalid`() {
        val good = HmmTransitionLoader.loadOrNull(
            assetsOf(
                HmmTransitionLoader.ASSET_PATH to
                    """{"A":[[0.95,0.04,0.005,0.005],[0.02,0.93,0.03,0.02],[0.01,0.02,0.96,0.01],[0.01,0.03,0.005,0.955]],"pi":[0.5,0.4,0.05,0.05]}""",
            ),
        )
        assertNotNull(good)

        // loadOrNull throws on present-but-invalid; StagingPipeline.create()
        // swallows that and falls back to the embedded defaults.
        try {
            HmmTransitionLoader.loadOrNull(
                assetsOf(HmmTransitionLoader.ASSET_PATH to """{"A": "nope", "pi": [1,2,3,4]}"""),
            )
            fail("expected IllegalArgumentException on malformed json")
        } catch (_: IllegalArgumentException) {
        }
    }
}
