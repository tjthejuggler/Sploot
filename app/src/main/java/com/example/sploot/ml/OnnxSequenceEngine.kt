package com.example.sploot.ml

import ai.onnxruntime.OnnxTensor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Primary engine: whole-night sequence model (1D CNN + Bi-LSTM/GRU + attention)
 * exported to ONNX and executed with ONNX Runtime.
 *
 * Model I/O contract (configurable via metadata JSON):
 *  - input  "features": float32 [1, T, 127] where T = number of 30 s epochs
 *  - output "probs":    float32 [T, 4] — logits or softmax probabilities
 *    (per metadata `logits` flag; softmax applied here when true).
 *
 * Runs entirely offline on [Dispatchers.Default].
 */
class OnnxSequenceEngine(
    model: LoadedModel,
    sessionOpener: SessionOpener = SessionOpener.DEFAULT,
) : OnnxEngineBase(model, sessionOpener) {

    override suspend fun stage(features: List<FloatArray>): SleepStagingResult {
        require(features.isNotEmpty()) { "empty night" }
        val width = features[0].size
        return onDispatcher {
            val shape = longArrayOf(1, features.size.toLong(), width.toLong())
            val buf = features.flatten()
            OnnxTensor.createTensor(env, buf, shape).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    val out = result.get(0).value
                    val matrix = when (out) {
                        is Array<*> -> out.map { row -> toProbabilities(row as FloatArray) }
                        is FloatArray -> { // flat [T*4]
                            require(out.size % 4 == 0) { "output not divisible by 4 classes" }
                            (0 until out.size / 4).map { r ->
                                toProbabilities(out.copyOfRange(r * 4, r * 4 + 4))
                            }
                        }
                        else -> error("unexpected ONNX output type: ${out?.javaClass}")
                    }
                    require(matrix.size == features.size) {
                        "sequence model returned ${matrix.size} rows for ${features.size} epochs"
                    }
                    SleepStagingResult(
                        stages = matrix.map(::argmax).toIntArray(),
                        probabilities = matrix.toTypedArray(),
                        engineId = engineId,
                    )
                }
            }
        }
    }
}
