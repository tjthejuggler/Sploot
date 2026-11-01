package com.example.sploot.ml

import ai.onnxruntime.OnnxTensor
import java.nio.FloatBuffer

/**
 * Reference/fallback engine: per-epoch gradient-boosted tree classifier
 * (LightGBM converted to ONNX) so both engines share the single ONNX Runtime
 * dependency. Context (temporal smoothing) is added by [ViterbiSmoother] instead
 * of the model itself.
 *
 * Model I/O contract (configurable via metadata JSON):
 *  - input  "features": float32 [1, 127] per epoch
 *  - output "probs":    float32 [1, 4] per epoch — class probabilities, or logits
 *    when metadata `logits` is true (softmax applied here).
 */
class GbdtReferenceEngine(
    model: LoadedModel,
    sessionOpener: SessionOpener = SessionOpener.DEFAULT,
) : OnnxEngineBase(model, sessionOpener) {

    override suspend fun stage(features: List<FloatArray>): SleepStagingResult {
        require(features.isNotEmpty()) { "empty night" }
        val width = features[0].size
        return onDispatcher {
            val probs = Array(features.size) { i ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(features[i]), longArrayOf(1, width.toLong())).use { tensor ->
                    session.run(mapOf(inputName to tensor)).use { result ->
                        val out = result.get(0).value
                        val row = when (out) {
                            is Array<*> -> (out[0] as FloatArray)
                            is FloatArray -> out
                            else -> error("unexpected ONNX output type: ${out?.javaClass}")
                        }
                        require(row.size == 4) { "expected 4-class output, got ${row.size}" }
                        toProbabilities(row)
                    }
                }
            }
            SleepStagingResult(
                stages = probs.map(::argmax).toIntArray(),
                probabilities = probs,
                engineId = engineId,
            )
        }
    }
}
