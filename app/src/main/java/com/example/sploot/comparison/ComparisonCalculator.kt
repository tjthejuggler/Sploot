package com.example.sploot.comparison

import com.example.sploot.data.db.SleepStages
import kotlin.math.abs

/**
 * Epoch-by-epoch agreement between our staged hypnogram and an imported
 * reference (e.g. sleep²): concordance %, unweighted Cohen's κ, 4×4 confusion
 * matrix (rows = ours, cols = reference) and per-stage duration deltas.
 *
 * Only epochs present in BOTH series are compared (epoch-index intersection).
 * Pure Kotlin, no Android dependencies.
 */
object ComparisonCalculator {

    const val EPOCH_MS = 30_000L

    data class Result(
        /** Number of epochs compared. */
        val n: Int,
        /** agreeing epochs / n * 100 (0 when n == 0). */
        val concordancePct: Double,
        /** Unweighted Cohen's κ (0 when n == 0 or degenerate marginals). */
        val kappa: Double,
        /** confusion[ours][reference], 4×4. */
        val confusion: Array<IntArray>,
        /** Per-stage minutes in our hypnogram over the compared epochs. */
        val oursStageMin: DoubleArray,
        /** Per-stage minutes in the reference over the compared epochs. */
        val refStageMin: DoubleArray,
        /** oursStageMin[s] - refStageMin[s] per stage. */
        val deltaMin: DoubleArray,
        /**
         * Overlap of the two series: compared epochs / min(len(ours), len(ref)) * 100.
         * < 100 signals mismatched epoch counts or misaligned start times.
         */
        val coveragePct: Double = 100.0,
    )

    /**
     * @param ours map epochIndex → our stage
     * @param reference map epochIndex → reference stage
     */
    fun compare(ours: Map<Int, Int>, reference: Map<Int, Int>): Result {
        val indices = ours.keys.intersect(reference.keys).sorted()
        val n = indices.size
        val confusion = Array(4) { IntArray(4) }
        var agree = 0
        for (i in indices) {
            val a = ours.getValue(i)
            val b = reference.getValue(i)
            if (a in 0..3 && b in 0..3) {
                confusion[a][b]++
                if (a == b) agree++
            }
        }
        val rowTotals = IntArray(4) { s -> confusion[s].sum() }
        val colTotals = IntArray(4) { s -> confusion.sumOf { it[s] } }

        val concordance = if (n > 0) 100.0 * agree / n else 0.0
        val po = if (n > 0) agree.toDouble() / n else 0.0
        val pe = (0 until 4).sumOf { s -> rowTotals[s].toDouble() / n * colTotals[s] / n }
        val kappa = if (n > 0 && abs(1.0 - pe) > 1e-12) (po - pe) / (1.0 - pe) else 0.0

        val oursMin = DoubleArray(4) { s -> rowTotals[s] * EPOCH_MS / 60_000.0 }
        val refMin = DoubleArray(4) { s -> colTotals[s] * EPOCH_MS / 60_000.0 }
        val delta = DoubleArray(4) { s -> oursMin[s] - refMin[s] }
        val denom = minOf(ours.size, reference.size).coerceAtLeast(1)
        val coverage = 100.0 * n / denom
        return Result(n, concordance, kappa, confusion, oursMin, refMin, delta, coverage)
    }

    /**
     * Aligns two hypnograms by absolute timestamp before comparing: reference
     * epoch indices are shifted by round((refStartMs - oursStartMs) / 30 s).
     * Use when both start times are known; for paste-imports (no reference
     * clock) call [compare] directly — [Result.coveragePct] then reports how
     * much of the shorter series was matched.
     */
    fun compareAligned(
        oursStartMs: Long,
        ours: Map<Int, Int>,
        refStartMs: Long,
        reference: Map<Int, Int>,
    ): Result {
        val shift = Math.round((refStartMs - oursStartMs).toDouble() / EPOCH_MS).toInt()
        if (shift == 0) return compare(ours, reference)
        val shifted = reference.entries.associate { (k, v) -> (k + shift) to v }
        return compare(ours, shifted)
    }

    /** Convenience: "Ours: 1h42 Deep | sleep²: 1h38 Deep" per stage. */
    fun formatDuration(minutes: Double): String {
        val totalMin = minutes.roundToIntSafe()
        return "${totalMin / 60}h${"%02d".format(totalMin % 60)}"
    }

    private fun Double.roundToIntSafe(): Int = kotlin.math.round(this).toInt()
}

/**
 * Flexible reference-hypnogram CSV/TSV/paste parser.
 *
 * Accepted line shapes (header line optional, `#`/`;` comments skipped):
 *  - `epochIndex,stage`
 *  - `time,stage` — seconds (float) or `HH:MM:SS` from session start;
 *    converted to epoch index via floor(t / 30).
 *  - Three+ columns: the LAST column is the stage, the FIRST is the index/time
 *    (e.g. sleep² exports like `epoch,start,stop,stage`).
 *
 * Stage mapping (case-insensitive): W/Wake/0 → 0, N1/N2/L/1 → 1, N3/D/2 → 2,
 * R/REM/3 → 3. Unmappable lines throw [IllegalArgumentException] with the
 * offending line number.
 */
object HypnogramCsvParser {

    fun parse(text: String, label: String = "import"): Map<Int, Int> {
        val out = LinkedHashMap<Int, Int>()
        text.lineSequence().forEachIndexed { lineNo, rawLine ->
            val line = rawLine.trim().trimEnd(',')
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) return@forEachIndexed
            val cols = line.split(',', ';', '\t').map { it.trim() }
            val stageStr = cols.lastOrNull() ?: return@forEachIndexed
            val stage = mapStage(stageStr)
                ?: throw IllegalArgumentException(
                    "line ${lineNo + 1}: unmappable stage '$stageStr' (expected W/N1/N2/N3/R or 0-3)",
                )
            val first = cols.first()
            // Plain integers are epoch indices; decimal values are seconds
            // from session start; HH:MM(:SS) clocks are wall offsets.
            val idx = when {
                first.contains(':') -> epochOfClock(first)
                else -> first.toDoubleOrNull()?.let {
                    if (first.contains('.')) (it / 30.0).toInt() else it.toInt()
                }
            } ?: throw IllegalArgumentException(
                "line ${lineNo + 1}: cannot parse epoch index / time '$first'",
            )
            if (idx < 0) throw IllegalArgumentException("line ${lineNo + 1}: negative epoch index")
            out[idx] = stage
        }
        if (out.isEmpty()) throw IllegalArgumentException("no hypnogram rows found in $label input")
        return out
    }

    fun mapStage(s: String): Int? = when (s.uppercase()) {
        "W", "WAKE", "AWAKE", "0" -> SleepStages.WAKE
        "N1", "N2", "L", "LIGHT", "NREM", "1" -> SleepStages.LIGHT
        "N3", "D", "DEEP", "SW", "SWS", "2" -> SleepStages.DEEP
        "R", "REM", "3" -> SleepStages.REM
        else -> null
    }

    private fun epochOfClock(clock: String): Int {
        val parts = clock.split(':').map { it.toIntOrNull() ?: return -1 }
        val seconds = when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            else -> return -1
        }
        return seconds / 30
    }
}
