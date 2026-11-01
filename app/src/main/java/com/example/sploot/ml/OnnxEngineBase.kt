package com.example.sploot.ml

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer

/** Opens an OrtSession from raw model bytes; abstracted so JVM tests can inject one. */
fun interface SessionOpener {
    fun open(modelBytes: ByteArray): OrtSession

    companion object {
        /** Production opener: the process-wide ONNX Runtime environment (Android or desktop). */
        val DEFAULT = SessionOpener { bytes ->
            environment().createSession(bytes)
        }

        private val env = lazy { OrtEnvironment.getEnvironment() }

        /** Test hook: exposed environment (desktop artifact provides the same class). */
        fun environment(): OrtEnvironment = env.value
    }
}

/**
 * Shared machinery for the two ONNX engines: session lifecycle, configurable I/O
 * names (from model metadata), softmax-on-logits, and background execution.
 */
abstract class OnnxEngineBase(
    val model: LoadedModel,
    private val sessionOpener: SessionOpener = SessionOpener.DEFAULT,
) : SleepStagingEngine {

    protected val session: OrtSession by lazy { sessionOpener.open(model.bytes) }
    protected val env: OrtEnvironment get() = SessionOpener.environment()

    /** Metadata may override tensor names; otherwise take the first declared I/O. */
    protected open val inputName: String by lazy {
        model.metadata.inputName ?: session.inputNames.iterator().next()
    }
    protected open val outputName: String by lazy {
        model.metadata.outputName ?: session.outputNames.iterator().next()
    }

    override val engineId: String get() = model.metadata.engineId

    protected suspend fun <T> onDispatcher(block: () -> T): T = withContext(Dispatchers.Default) { block() }

    /** Apply row-wise softmax when the model emits logits (per metadata flag). */
    protected fun toProbabilities(row: FloatArray): FloatArray {
        if (!model.metadata.logits) return row
        var maxV = Float.NEGATIVE_INFINITY
        for (v in row) if (v > maxV) maxV = v
        var sum = 0.0
        val out = FloatArray(row.size)
        for (i in row.indices) {
            val e = kotlin.math.exp(row[i] - maxV)
            out[i] = e
            sum += e
        }
        for (i in out.indices) out[i] = (out[i] / sum).toFloat()
        return out
    }

    protected fun argmax(row: FloatArray): Int {
        var best = 0
        for (i in 1 until row.size) if (row[i] > row[best]) best = i
        return best
    }
}

/** Float matrix helper: flat row-major buffer for tensor construction. */
internal fun List<FloatArray>.flatten(): FloatBuffer {
    val w = first().size
    val buf = FloatBuffer.allocate(size * w)
    for (row in this) {
        require(row.size == w) { "ragged feature matrix" }
        buf.put(row)
    }
    buf.flip()
    return buf
}
