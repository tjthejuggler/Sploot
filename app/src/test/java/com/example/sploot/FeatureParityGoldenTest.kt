package com.example.sploot

import com.example.sploot.features.FeatureVectorBuilder
import com.example.sploot.signal.ArtifactFilter
import com.example.sploot.signal.EpochBuilder
import com.example.sploot.ble.AccSample
import com.example.sploot.ble.IbiSample
import com.example.sploot.ble.IbiSource
import kotlin.math.PI
import kotlin.math.sin

/**
 * Phase 7 parity harness: prints the 127-feature vectors for a fixed
 * deterministic synthetic session (integer LCG, identical generator in
 * ml_pipeline/parity_check.py) so the Python re-implementation can be compared
 * against the Kotlin reference within tolerance.
 *
 * The golden JSON is emitted to stdout between marker lines; parity_check.py
 * scrapes it from the JUnit XML system-out. The test itself also asserts the
 * pipeline behaves sanely (127 features, valid clock/HRV values).
 */
class FeatureParityGoldenTest {

    companion object {
        const val SESSION_START_MS = 1_735_712_345_000L
        const val DURATION_MS = 420_000L // 14 epochs of 30 s
        // Stop streams 5 s early so the last epoch is fully populated —
        // FeatureVectorBuilder.build() throws on windows that fail the HRV
        // validity gate (latent edge documented in ml_pipeline/README.md).
        val DATA_END_MS = SESSION_START_MS + DURATION_MS - 5_000L
    }

    /** Deterministic LCG identical to parity_check.py (mod 2^31). */
    private class Lcg(var x: Long = 1_234_567_89L) {
        fun next(): Long {
            x = (1_103_515_245L * x + 12_345L) ushr 0
            x = x and 0x7FFF_FFFFL
            return x
        }
        fun noise(amp: Long): Long = (next() % (2 * amp + 1)) - amp
    }

    private fun syntheticSession(): Pair<List<IbiSample>, List<AccSample>> {
        val rng = Lcg()
        val ibis = ArrayList<IbiSample>()
        var ts = SESSION_START_MS
        var i = 0
        while (ts < DATA_END_MS) {
            var interval = 900.0 +
                60.0 * sin(2 * PI * i / 55.0) +
                15.0 * sin(2 * PI * i / 11.0) +
                rng.noise(10)
            if (i % 17 == 5) interval *= 1.3        // ectopic-ish
            if (i % 101 == 7) interval = 2600.0     // out of bounds
            val iv = interval.toInt().coerceIn(300, 3000)
            ts += iv
            val blocker = i % 23 == 3
            val error = if (i % 37 == 11) 200 else 0
            ibis += IbiSample(
                timestampMs = ts,
                intervalMs = iv,
                source = IbiSource.PPI,
                blockerBit = blocker,
                skinContact = true,
                error = error,
            )
            i++
        }
        val acc = ArrayList<AccSample>()
        val accHz = 50
        var k = 0
        var accTs = SESSION_START_MS
        while (accTs < DATA_END_MS) {
            val movement = if (k % 700 in 500..560) 300 else 0
            val x = 20 + rng.noise(15) + movement / 3
            val y = -40 + rng.noise(15)
            val z = 990 + rng.noise(15) + movement + (8.0 * sin(2 * PI * 0.23 * k / accHz)).toInt()
            acc += AccSample(accTs, x.toInt(), y.toInt(), z.toInt(), accHz)
            accTs += 1000 / accHz
            k++
        }
        return ibis to acc
    }

    @org.junit.Test
    fun `synthetic session produces sane features and golden output`() {
        val (ibiSamples, accSamples) = syntheticSession()
        val filtered = ArtifactFilter().filter(ibiSamples)
        val epochs = EpochBuilder().build(SESSION_START_MS, filtered, accSamples)
        val builder = FeatureVectorBuilder()

        val rows = StringBuilder("[")
        for (idx in epochs.indices) {
            val f = builder.build(epochs, idx, SESSION_START_MS)
            assert(f.values.size == 127) { "expected 127 features, got ${f.values.size}" }
            if (idx == 0) {
                // Sanity: clock + base-window HRV features must be finite here.
                assert(!f.values[0].isNaN()) { "hrv_mean_ibi NaN on clean synthetic data" }
                assert(!f.values[124].isNaN()) { "clock_minutes_since_start NaN" }
            }
            if (idx > 0) rows.append(",")
            rows.append("[")
            rows.append(f.values.joinToString(",") { v -> if (v.isNaN()) "null" else v.toString() })
            rows.append("]")
        }
        rows.append("]")

        println("===SPLOOT_PARITY_GOLDEN_BEGIN===")
        println("{\"sessionStartMs\":$SESSION_START_MS,\"nEpochs\":${epochs.size},\"features\":$rows}")
        println("===SPLOOT_PARITY_GOLDEN_END===")

        assert(epochs.isNotEmpty())
    }
}
