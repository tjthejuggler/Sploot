package com.example.sploot.ml

import java.io.InputStream

/**
 * Orchestrator: features → [PerNightNormalizer] → [SleepStagingEngine] →
 * [ViterbiSmoother] → [SleepStagingResult] ready to persist as StageEntity rows.
 *
 * The returned [SleepStagingResult.stages] are the *smoothed* Viterbi path;
 * `probabilities` are the engine's raw per-epoch emissions (pre-smoothing), which
 * is what StageEntity.probabilities should store for auditability.
 *
 * The HMM transition matrix is loaded from `assets/models/hmm_transition.json`
 * (learned artifact) when present and valid; otherwise the embedded
 * [ViterbiSmoother.DEFAULT_TRANSITIONS] priors are used.
 */
class StagingPipeline(
    private val engine: SleepStagingEngine,
    private val normalizer: PerNightNormalizer = PerNightNormalizer(),
    private val smoother: ViterbiSmoother = ViterbiSmoother(),
) {

    suspend fun stageNight(features: List<FloatArray>): SleepStagingResult {
        require(features.isNotEmpty()) { "cannot stage an empty night" }
        val scaled = normalizer.fitTransform(features)
        val raw = engine.stage(scaled)
        val smoothed = smoother.smooth(raw.probabilities)
        return SleepStagingResult(
            stages = smoothed.stages,
            probabilities = raw.probabilities,
            engineId = raw.engineId,
        )
    }

    companion object {
        /**
         * Builds a pipeline for [engine], preferring the learned HMM transition
         * model in the assets over the embedded defaults. A present-but-invalid
         * `hmm_transition.json` is logged and ignored (defaults keep nightly
         * processing alive rather than crashing it).
         */
        fun create(
            engine: SleepStagingEngine,
            assets: (String) -> InputStream?,
        ): StagingPipeline {
            val smoother = try {
                HmmTransitionLoader.loadOrNull(assets)
            } catch (e: IllegalArgumentException) {
                System.err.println("invalid hmm_transition.json, using defaults: ${e.message}")
                null
            }
            return if (smoother != null) StagingPipeline(engine, smoother = smoother)
            else StagingPipeline(engine)
        }
    }
}
