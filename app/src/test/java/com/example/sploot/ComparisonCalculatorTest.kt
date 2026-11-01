package com.example.sploot

import com.example.sploot.comparison.ComparisonCalculator
import com.example.sploot.comparison.HypnogramCsvParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ComparisonCalculatorTest {

    @Test
    fun `perfect agreement gives 100 percent and kappa 1`() {
        val ours = mapOf(0 to 0, 1 to 1, 2 to 2, 3 to 3, 4 to 1)
        val r = ComparisonCalculator.compare(ours, ours)
        assertEquals(5, r.n)
        assertEquals(100.0, r.concordancePct, 1e-9)
        assertEquals(1.0, r.kappa, 1e-9)
        assertEquals(5, r.confusion.indices.sumOf { r.confusion[it].sum() })
        for (s in 0 until 4) assertEquals(r.oursStageMin[s], r.refStageMin[s], 1e-9)
        assertTrue(r.deltaMin.all { it == 0.0 })
    }

    @Test
    fun `known confusion and kappa hand case`() {
        // ours vs ref over 8 epochs; disagreements at idx2 (2 vs 1) and idx3 (3 vs 2)
        val ours = listOf(0, 1, 2, 3, 2, 3, 0, 1)
        val ref = listOf(0, 1, 1, 2, 2, 3, 0, 1)
        val r = ComparisonCalculator.compare(
            ours.withIndex().associate { it.index to it.value },
            ref.withIndex().associate { it.index to it.value },
        )
        assertEquals(8, r.n)
        assertEquals(100.0 * 6.0 / 8, r.concordancePct, 1e-9)
        // rows (ours): W=2 L=2 D=2 R=2 ; cols (ref): W=2 L=3 D=2 R=1
        // po = 6/8 = .75, pe = (2*2+2*3+2*2+2*1)/64 = 16/64 = .25
        assertEquals((0.75 - 0.25) / (1 - 0.25), r.kappa, 1e-9)
        assertEquals(1, r.confusion[2][1]) // ours Deep, ref Light (idx 2)
        assertEquals(1, r.confusion[2][2]) // ours Deep, ref Deep (idx 4)
        assertEquals(1, r.confusion[3][2]) // ours REM, ref Deep (idx 3)
        // duration deltas: Deep ours 2 vs ref 2 → 0; Light ours 2 vs ref 3 → −0.5 min
        assertEquals(0.0, r.deltaMin[2], 1e-9)
        assertEquals(-0.5, r.deltaMin[1], 1e-9)
    }

    @Test
    fun `epoch intersection only compares shared indices`() {
        val ours = mapOf(0 to 0, 1 to 1, 2 to 1, 3 to 3)
        val ref = mapOf(1 to 1, 2 to 2, 3 to 3, 4 to 3) // epoch 4 not in ours
        val r = ComparisonCalculator.compare(ours, ref)
        assertEquals(3, r.n)
        assertEquals(100.0 * 2.0 / 3, r.concordancePct, 1e-9)
    }

    @Test
    fun `empty comparison is degenerate not crashing`() {
        val r = ComparisonCalculator.compare(emptyMap(), mapOf(0 to 1))
        assertEquals(0, r.n)
        assertEquals(0.0, r.concordancePct, 1e-9)
        assertEquals(0.0, r.kappa, 1e-9)
    }

    @Test
    fun `duration formatting`() {
        assertEquals("1h42", ComparisonCalculator.formatDuration(102.0))
        assertEquals("0h30", ComparisonCalculator.formatDuration(30.0))
        assertEquals("7h05", ComparisonCalculator.formatDuration(425.0))
    }

    // ---- HypnogramCsvParser ----

    @Test
    fun `parser accepts index-stage pairs with flexible stage labels`() {
        val m = HypnogramCsvParser.parse("0,W\n1,N1\n2,N3\n3,R\n4,2\n5,L\n")
        assertEquals(listOf(0, 1, 2, 3, 2, 1), (0..5).map { m.getValue(it) })
    }

    @Test
    fun `parser accepts time-based rows (seconds and clock)`() {
        // plain ints are epoch indices; decimals are seconds; HH:MM:SS clocks
        val m = HypnogramCsvParser.parse("0,W\n30.0,W\n60.5,N1\n00:02:00,N2\n")
        assertEquals(0, m.getValue(0)) // plain int 0
        assertEquals(0, m.getValue(1)) // 30.0 s → epoch 1, Wake
        assertEquals(1, m.getValue(2)) // 60.5 s → epoch 2, N1
        assertEquals(1, m.getValue(4)) // 120 s clock → epoch 4, N2→Light
    }

    @Test
    fun `parser takes last column as stage for wide exports`() {
        // sleep²-style: epoch,start,stop,stage
        val m = HypnogramCsvParser.parse("# comment\n0,0,30,Wake\n1,30,60,N2\n")
        assertEquals(0, m.getValue(0))
        assertEquals(1, m.getValue(1))
    }

    @Test
    fun `parser rejects unmappable stages with line number`() {
        try {
            HypnogramCsvParser.parse("0,W\n1,XX")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("line 2"))
        }
    }

    @Test
    fun `parser rejects empty input`() {
        try {
            HypnogramCsvParser.parse("\n# only comments\n")
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
