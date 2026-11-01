package com.example.sploot.ml

import java.io.InputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Loads a learned HMM transition model from `assets/models/hmm_transition.json`
 * (the artifact produced by `ml_pipeline/learn_hmm.py`):
 *
 * ```json
 * {
 *   "A":  [[0.91, 0.07, 0.01, 0.01], ...],   // row-stochastic A[from][to]
 *   "pi": [0.70, 0.25, 0.02, 0.03]            // initial distribution
 * }
 * ```
 *
 * Validation: 4×4 shape, every entry finite in [0, 1], each row (and π)
 * summing to 1 within [TOLERANCE]. Anything else throws
 * [IllegalArgumentException] — callers fall back to the embedded defaults.
 */
object HmmTransitionLoader {

    const val ASSET_PATH = "models/hmm_transition.json"
    const val TOLERANCE = 1e-3

    /**
     * Loads and validates the asset when present; returns `null` when the
     * asset is absent (embedded [ViterbiSmoother] defaults are then used) and
     * throws when it exists but is malformed/non-stochastic.
     */
    fun loadOrNull(assets: (String) -> InputStream?): ViterbiSmoother? {
        val text = assets(ASSET_PATH)?.use { it.readBytes().decodeToString() } ?: return null
        return parse(text)
    }

    /** Parses + validates a transition JSON document into a [ViterbiSmoother]. */
    fun parse(text: String): ViterbiSmoother {
        val obj = Json.parseToJsonElement(text).let { el ->
            el as? JsonObject
                ?: throw IllegalArgumentException("hmm_transition.json must be a JSON object")
        }
        val a = obj["A"]?.jsonArray
            ?: throw IllegalArgumentException("hmm_transition.json missing 'A' matrix")
        val pi = obj["pi"]?.jsonArray
            ?: throw IllegalArgumentException("hmm_transition.json missing 'pi' vector")

        if (a.size != ViterbiSmoother.NUM_STAGES) {
            throw IllegalArgumentException("A must be 4×4, got ${a.size} rows")
        }
        val matrix = a.map { rowEl ->
            val row = rowEl.jsonArray
            if (row.size != ViterbiSmoother.NUM_STAGES) {
                throw IllegalArgumentException("A rows must have length 4, got ${row.size}")
            }
            DoubleArray(ViterbiSmoother.NUM_STAGES) { j -> row[j].jsonPrimitive.double }
        }.toTypedArray()
        if (pi.size != ViterbiSmoother.NUM_STAGES) {
            throw IllegalArgumentException("pi must have length 4, got ${pi.size}")
        }
        val initial = DoubleArray(ViterbiSmoother.NUM_STAGES) { j -> pi[j].jsonPrimitive.double }

        validateStochastic(matrix, initial)
        return ViterbiSmoother(matrix, initial)
    }

    /** Throws unless every entry ∈ [0,1] and every row / π sums to 1 (± tol). */
    fun validateStochastic(a: Array<DoubleArray>, pi: DoubleArray) {
        for ((i, row) in a.withIndex()) {
            for (v in row) {
                require(v.isFinite() && v in 0.0..1.0) {
                    "A[$i] contains invalid probability $v"
                }
            }
            val sum = row.sum()
            require(kotlin.math.abs(sum - 1.0) <= TOLERANCE) {
                "A[$i] row sums to $sum (must be 1.0 ± $TOLERANCE)"
            }
        }
        for (v in pi) require(v.isFinite() && v in 0.0..1.0) { "pi contains invalid probability $v" }
        val sum = pi.sum()
        require(kotlin.math.abs(sum - 1.0) <= TOLERANCE) {
            "pi sums to $sum (must be 1.0 ± $TOLERANCE)"
        }
    }
}
