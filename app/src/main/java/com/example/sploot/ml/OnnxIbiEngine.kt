package com.example.sploot.ml

import ai.onnxruntime.OnnxTensor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer

/**
 * Default staging engine: sleep²-style raw-IBI network (multi-resolution CNN +
 * BiGRU, trained in `ml_pipeline/train_ibi_cnn.py` on PhysioNet HMC +
 * Challenge-2018, validated against the user's sleep² hypnograms at
 * κ 0.736 / acc 81.7% over 7 nights).
 *
 * Model I/O contract:
 *  - input  "ibi":   float32 [1, 2, N] where N = epochs*60 (ch0 z-scored IBI,
 *    ch1 validity mask; built by [IbiSequencePreprocessor])
 *  - output "probs": float32 [T, 4] softmax probabilities (W/L/D/R)
 *
 * Calibration: [ModelMetadata.wakeBias] (1.25 for the shipped model, fitted on
 * the user's 209-night sleep² history) scales the Wake probability by e^bias
 * before renormalisation — mathematically identical to adding a constant to the
 * Wake logit before the softmax that the network applies.
 *
 * This engine stages from RAW intervals via [stageRawIbi]; the feature-based
 * [stage] is intentionally unsupported (the 127-feature contract does not apply).
 */
class OnnxIbiEngine(
    model: LoadedModel,
    sessionOpener: SessionOpener = SessionOpener.DEFAULT,
) : OnnxEngineBase(model, sessionOpener) {

    override suspend fun stage(features: List<FloatArray>): SleepStagingResult =
        throw UnsupportedOperationException(
            "$engineId stages from raw IBI (stageRawIbi), not feature vectors",
        )

    /**
     * @param beats raw (timestampMs, intervalMs) pairs of the whole night.
     * @return null when fewer than 10 clean beats survive preprocessing —
     *   the caller should fall back to a feature-based engine.
     */
    override suspend fun stageRawIbi(
        startMs: Long,
        beats: List<Pair<Long, Int>>,
        nEpochs: Int,
    ): SleepStagingResult? {
        require(nEpochs > 0) { "no epochs" }
        val x = IbiSequencePreprocessor.toSequence(beats, startMs, nEpochs) ?: return null
        val n = x.size / 2
        return withContext(Dispatchers.Default) {
            val buf = FloatBuffer.wrap(x)
            val shape = longArrayOf(1, 2, n.toLong())
            OnnxTensor.createTensor(env, buf, shape).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    val out = result.get(0).value
                    val rows: List<FloatArray> = when (out) {
                        is Array<*> -> out.map { row ->
                            applyWakeBias(row as FloatArray)
                        }
                        is FloatArray -> {
                            require(out.size % 4 == 0) { "output not divisible by 4 classes" }
                            (0 until out.size / 4).map { r ->
                                applyWakeBias(out.copyOfRange(r * 4, r * 4 + 4))
                            }
                        }
                        else -> error("unexpected ONNX output type: ${out?.javaClass}")
                    }
                    require(rows.size == nEpochs) {
                        "IBI model returned ${rows.size} rows for $nEpochs epochs"
                    }
                    SleepStagingResult(
                        stages = rows.map(::argmax).toIntArray(),
                        probabilities = rows.toTypedArray(),
                        engineId = engineId,
                    )
                }
            }
        }
    }

    /** p_wake *= e^wakeBias, renormalise (== constant added to the wake logit pre-softmax). */
    private fun applyWakeBias(row: FloatArray): FloatArray {
        val bias = model.metadata.wakeBias
        if (bias == 0f || row.size != 4) return row
        val scaled = row.copyOf()
        scaled[0] *= kotlin.math.exp(bias)
        var sum = 0.0
        for (v in scaled) sum += v
        for (i in scaled.indices) scaled[i] = (scaled[i] / sum).toFloat()
        return scaled
    }
}
