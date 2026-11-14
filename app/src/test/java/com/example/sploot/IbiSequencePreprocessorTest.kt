package com.example.sploot

import com.example.sploot.ml.IbiSequencePreprocessor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Parity golden test: Kotlin [IbiSequencePreprocessor] vs the Python
 * `ml_pipeline/ibi_seq.py` reference (golden vector generated with numpy
 * seed 7). Guards the 1:1 preprocessing contract the on-device ONNX model
 * was trained against.
 */
class IbiSequencePreprocessorTest {

    private val obj by lazy {
        val text = javaClass.classLoader!!
            .getResourceAsStream("ibi_seq_golden.json")!!
            .readBytes().decodeToString()
        Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun `matches python ibi_seq golden vector`() {
        val beats = obj["beats"]!!.jsonArray.map { row ->
            val r = row.jsonArray
            r[0].jsonPrimitive.double.toLong() to r[1].jsonPrimitive.double.toInt()
        }
        val start = obj["startMs"]!!.jsonPrimitive.double.toLong()
        val nEpochs = obj["nEpochs"]!!.jsonPrimitive.double.toInt()
        val ch0 = obj["ch0"]!!.jsonArray.map { it.jsonPrimitive.double }
        val ch1 = obj["ch1"]!!.jsonArray.map { it.jsonPrimitive.double }

        val out = IbiSequencePreprocessor.toSequence(beats, start, nEpochs)
        assertNotNull(out)
        val n = nEpochs * IbiSequencePreprocessor.SAMPLES_PER_EPOCH
        assertEquals(2 * n, out!!.size)
        for (k in 0 until n) {
            assertEquals("ch0[$k]", ch0[k], out[k].toDouble(), 1e-3)
            assertEquals("ch1[$k]", ch1[k], out[n + k].toDouble(), 1e-9)
        }
        assertEquals(1.0, ch1.average(), 1e-9) // dense synthetic night: all samples valid
    }

    @Test
    fun `returns null for unprocessable input`() {
        assertNull(IbiSequencePreprocessor.toSequence(emptyList(), 0L, 10))
        val few = (0 until 5).map { it.toLong() * 1000L to 900 }
        assertNull(IbiSequencePreprocessor.toSequence(few, 0L, 10))
    }
}
