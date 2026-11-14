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
     * Optional raw-IBI staging path for engines that consume the inter-beat
     * interval series directly (sleep²-style networks) instead of per-epoch
     * feature vectors. Returns null when the engine does not support this
     * input mode, or when the night has too little usable IBI — callers fall
     * back to the feature-based [stage].
     */
    suspend fun stageRawIbi(
        startMs: Long,
        beats: List<Pair<Long, Int>>,
        nEpochs: Int,
    ): SleepStagingResult? = null

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
    /**
     * Default: sleep²-style raw-IBI network (CNN + BiGRU) via ONNX Runtime.
     * Trained on PhysioNet HMC + Challenge-2018 (1,106 nights); validated
     * against the user's sleep² hypnograms at κ 0.736 over 7 nights
     * (2026-11-14). Wake-calibrated (bias 1.25) from a 209-night sleep² dump.
     */
    const val IBI = "sploot-ibi-cnn"

    /** Whole-night sequence model (CNN + BiLSTM) via ONNX Runtime. */
    const val SEQ = "sploot-seq"

    /** Per-epoch gradient-boosted tree reference model via ONNX Runtime. */
    const val GBDT = "sploot-gbdt"

    /**
     * Physiologically-grounded heuristic stager. Fallback when the IBI model
     * cannot load or a night has too little usable IBI (the bundled
     * sploot-seq/gbdt files are placeholders emitting a fixed class prior
     * regardless of input — verified on-device 2026-11-02).
     */
    const val HEURISTIC = "sploot-heuristic"

    val ALL = setOf(IBI, HEURISTIC, SEQ, GBDT)

    /** Engine ids that require a loadable ONNX model file. */
    val MODEL_BASED = setOf(IBI, SEQ, GBDT)

    /**
     * @throws IllegalArgumentException unknown engine id, or model metadata whose
     *   engineId does not match [engineId].
     */
    fun create(engineId: String, model: LoadedModel?): SleepStagingEngine =
        when (engineId) {
            HEURISTIC -> HeuristicStagingEngine()
            IBI -> {
                val m = requireNotNull(model) { "engine '$engineId' requires a loaded model" }
                require(m.metadata.engineId == engineId) {
                    "model metadata engineId '${m.metadata.engineId}' != requested '$engineId'"
                }
                OnnxIbiEngine(m)
            }
            SEQ -> {
                val m = requireNotNull(model) { "engine '$engineId' requires a loaded model" }
                require(m.metadata.engineId == engineId) {
                    "model metadata engineId '${m.metadata.engineId}' != requested '$engineId'"
                }
                OnnxSequenceEngine(m)
            }
            GBDT -> {
                val m = requireNotNull(model) { "engine '$engineId' requires a loaded model" }
                require(m.metadata.engineId == engineId) {
                    "model metadata engineId '${m.metadata.engineId}' != requested '$engineId'"
                }
                GbdtReferenceEngine(m)
            }
            else -> throw IllegalArgumentException("unknown engine id: $engineId")
        }
}
