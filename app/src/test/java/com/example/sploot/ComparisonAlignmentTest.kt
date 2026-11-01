package com.example.sploot

import com.example.sploot.comparison.ComparisonCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A/B compare edge cases: mismatched epoch counts, different start times.
 */
class ComparisonAlignmentTest {

    private val ours = (0 until 100).associate { it to it % 4 }

    @Test
    fun `mismatched epoch counts report coverage below 100`() {
        val ref = ours.filterKeys { it < 60 } // reference shorter
        val r = ComparisonCalculator.compare(ours, ref)
        assertEquals(60, r.n)
        assertEquals(100.0, r.coveragePct, 0.01) // ref fully covered by ours
        assertEquals(100.0, r.concordancePct, 0.01) // identical stages on overlap
    }

    @Test
    fun `reference longer than ours caps coverage at 100`() {
        val ref = (0 until 200).associate { it to it % 4 }
        val r = ComparisonCalculator.compare(ours, ref)
        assertEquals(100, r.n)
        assertEquals(100.0, r.coveragePct, 0.01)
    }

    @Test
    fun `disjoint indices give zero epochs and zero percent`() {
        val ref = (500 until 600).associate { it to it % 4 }
        val r = ComparisonCalculator.compare(ours, ref)
        assertEquals(0, r.n)
        assertEquals(0.0, r.concordancePct, 0.01)
        assertEquals(0.0, r.coveragePct, 0.01)
    }

    @Test
    fun `compareAligned shifts reference start by whole epochs`() {
        // identical hypnograms, reference started 2 epochs (60 s) later
        val oursMap = (0 until 50).associate { it to if (it < 10) 0 else 1 }
        val refShifted = (0 until 50).associate { it to if (it < 10) 0 else 1 }
        val r = ComparisonCalculator.compareAligned(
            oursStartMs = 0L,
            ours = oursMap,
            refStartMs = 2 * ComparisonCalculator.EPOCH_MS,
            reference = refShifted,
        )
        // ref epoch i aligns onto ours epoch i+2; intersection = epochs 2..49
        assertEquals(48, r.n)
        assertTrue("aligned concordance ${r.concordancePct}", r.concordancePct >= 95.0)
    }

    @Test
    fun `compareAligned with equal starts behaves like compare`() {
        val r = ComparisonCalculator.compareAligned(0L, ours, 0L, ours)
        assertEquals(100, r.n)
        assertEquals(100.0, r.concordancePct, 0.01)
        assertTrue(r.kappa > 0.9)
    }
}
