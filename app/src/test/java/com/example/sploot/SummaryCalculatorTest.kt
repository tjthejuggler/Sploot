package com.example.sploot

import com.example.sploot.data.db.SleepStages
import com.example.sploot.data.repo.IbiPoint
import com.example.sploot.data.repo.StagePoint
import com.example.sploot.data.repo.SummaryCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SummaryCalculatorTest {

    private val t0 = 1_700_000_000_000L

    // 8 epochs of 30 s: W W L L D W W R
    private val stages = listOf(0, 0, 1, 1, 2, 0, 0, 3).mapIndexed { i, s ->
        StagePoint(t0 + i * 30_000L, s)
    }

    // IBIs at 1 s spacing, all inside a 5-minute window
    private val ibis = listOf(600, 620, 580, 640, 660, 600).mapIndexed { i, ms ->
        IbiPoint(t0 + i * 1000L, ms)
    }

    @Test
    fun `hypnogram metrics hand computed`() {
        val s = SummaryCalculator.calculate(stages, emptyList())

        // TIB = 8 * 30 s = 4 min; Wake = 4 epochs, Light 2, Deep 1, REM 1
        assertEquals(4.0, s.tibMin, 1e-9)
        assertEquals(2.0, s.tstMin, 1e-9)
        assertEquals(50.0, s.sleepEfficiencyPct, 1e-9)
        // first non-wake epoch is index 2 -> SOL = 1 min
        assertEquals(1.0, s.solMin, 1e-9)
        // wake epochs after onset: indices 5,6 -> 1 min, single episode
        assertEquals(1.0, s.wasoMin, 1e-9)
        assertEquals(1, s.wasoEpisodes)
        assertEquals(2.0, s.wakeMin, 1e-9)
        assertEquals(1.0, s.lightMin, 1e-9)
        assertEquals(0.5, s.deepMin, 1e-9)
        assertEquals(0.5, s.remMin, 1e-9)
        // % of TST = 2 min: wake 100%, light 50%, deep 25%, rem 25%
        assertEquals(100.0, s.wakePct, 1e-9)
        assertEquals(50.0, s.lightPct, 1e-9)
        assertEquals(25.0, s.deepPct, 1e-9)
        assertEquals(25.0, s.remPct, 1e-9)
        assertNull(s.lowestRhrBpm)
        assertNull(s.meanRmssdMs)
    }

    @Test
    fun `rmssd hand computed`() {
        // diffs: 20,-40,60,20,-60 -> mean square = 1920 -> sqrt = 43.817..
        val rmssd = SummaryCalculator.rmssd(ibis)!!
        assertEquals(43.8, rmssd, 0.05)
    }

    @Test
    fun `lowest rhr is highest rolling 5-min mean ibi`() {
        val s = SummaryCalculator.calculate(stages, ibis)
        // best 5-min rolling mean = first 5 samples (600+620+580+640+660)/5 = 620
        // -> 60000/620 = 96.77 bpm -> 96.8 after rounding
        assertEquals(96.8, s.lowestRhrBpm!!, 0.05)
        assertEquals(43.8, s.meanRmssdMs!!, 0.05)
    }

    @Test
    fun `rolling window respects 5 minute span`() {
        // two clusters 10 min apart: slow cluster mean 1000 -> 60 bpm
        val slow = List(10) { IbiPoint(t0 + it * 1000L, 1000) }
        val fast = List(10) { IbiPoint(t0 + 600_000L + it * 1000L, 500) }
        val s = SummaryCalculator.calculate(stages, slow + fast)
        assertEquals(60.0, s.lowestRhrBpm!!, 0.01)
    }

    @Test
    fun `all wake night`() {
        val s = SummaryCalculator.calculate(
            listOf(StagePoint(t0, SleepStages.WAKE)),
            emptyList(),
        )
        assertEquals(0.5, s.tibMin, 1e-9)
        assertEquals(0.0, s.tstMin, 1e-9)
        assertEquals(0.0, s.sleepEfficiencyPct, 1e-9)
        assertEquals(0.5, s.solMin, 1e-9) // no sleep onset -> whole TIB is latency
        assertEquals(0, s.wasoEpisodes)
    }

    @Test
    fun `empty inputs are safe`() {
        val s = SummaryCalculator.calculate(emptyList(), emptyList())
        assertEquals(0.0, s.tibMin, 1e-9)
        assertNull(s.lowestRhrBpm)
        assertNull(s.meanRmssdMs)
    }
}
