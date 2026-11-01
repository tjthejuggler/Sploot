package com.example.sploot.ml

/**
 * Result of staging a full night.
 *
 * @param stages one stage per epoch, indices into [com.example.sploot.features.FeatureVectorBuilder]
 *   semantics: 0 = Wake, 1 = Light, 2 = Deep, 3 = REM.
 * @param probabilities per-epoch class probabilities (rows sum to 1, length 4, order W/L/D/R).
 *   These are the *raw* engine emissions (pre-Viterbi smoothing).
 * @param engineId identifier of the producing engine (e.g. "sploot-seq", "sploot-gbdt"),
 *   stored in StageEntity.engineId.
 */
data class SleepStagingResult(
    val stages: IntArray,
    val probabilities: Array<FloatArray>,
    val engineId: String,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * A staging engine maps per-epoch feature vectors (aligned to
 * [com.example.sploot.features.FeatureVectorBuilder.FEATURE_NAMES], NaN allowed for
 * insufficient data) to per-epoch stage probabilities.
 *
 * Implementations must be pure functions of their input + loaded model, run fully
 * offline, and perform inference on a background dispatcher.
 */
interface SleepStagingEngine {
    /** Engine identifier (must match the metadata JSON's engineId). */
    val engineId: String

    /**
     * @param features one [FloatArray] per epoch, each of length FEATURE_NAMES.size (127).
     * @return stages derived from argmax of the engine's probabilities plus the raw
     *   probability matrix; smoothing happens in [ViterbiSmoother], orchestrated by
     *   [StagingPipeline].
     */
    suspend fun stage(features: List<FloatArray>): SleepStagingResult
}

/** Registry / factory selecting an engine implementation by its id. */
object EngineRegistry {
    /** Whole-night sequence model (CNN + BiLSTM) via ONNX Runtime. */
    const val SEQ = "sploot-seq"

    /** Per-epoch gradient-boosted tree reference model via ONNX Runtime. */
    const val GBDT = "sploot-gbdt"

    val ALL = setOf(SEQ, GBDT)

    /**
     * @throws IllegalArgumentException unknown engine id, or model metadata whose
     *   engineId does not match [engineId].
     */
    fun create(engineId: String, model: LoadedModel): SleepStagingEngine {
        require(model.metadata.engineId == engineId) {
            "model metadata engineId '${model.metadata.engineId}' != requested '$engineId'"
        }
        return when (engineId) {
            SEQ -> OnnxSequenceEngine(model)
            GBDT -> GbdtReferenceEngine(model)
            else -> throw IllegalArgumentException("unknown engine id: $engineId")
        }
    }
}
