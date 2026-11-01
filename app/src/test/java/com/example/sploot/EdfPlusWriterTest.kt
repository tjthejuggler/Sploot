package com.example.sploot

import com.example.sploot.data.export.EdfPlusWriter
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EdfPlusWriterTest {

    private fun field(bytes: ByteArray, offset: Int, len: Int): String =
        bytes.copyOfRange(offset, offset + len).toString(Charsets.US_ASCII).trim()

    private fun littleInt16(bytes: ByteArray, offset: Int): Int {
        val v = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        return if (v > 32767) v - 65536 else v
    }

    private fun build(): ByteArray = ByteArrayOutputStream().also { out ->
        EdfPlusWriter.write(
            out = out,
            patientId = "sploot-user",
            recordingId = "sploot-20261031",
            start = LocalDateTime.of(2026, 10, 31, 23, 30, 0),
            ibis = listOf(EdfPlusWriter.Ibi(1.5, 1000)),
            annotations = listOf(
                EdfPlusWriter.Annot(1.5, "stage=Light"),
                EdfPlusWriter.Annot(2.0, "stage=Deep"),
            ),
            durationSec = 3,
        )
    }.toByteArray()

    @Test
    fun `header layout follows EDF+ spec`() {
        val b = build()

        // total: 256*(1 + 2 signals) + 3 records * (2 IBI bytes + 40 annot bytes)
        assertEquals(768 + 3 * 42, b.size)

        assertEquals("0", field(b, 0, 8))
        assertEquals("sploot-user", field(b, 8, 80))
        assertEquals("sploot-20261031", field(b, 88, 80))
        assertEquals("31.10.26", field(b, 168, 8))
        assertEquals("23.30.00", field(b, 176, 8))
        assertEquals("768", field(b, 184, 8))
        assertEquals("EDF+C", field(b, 192, 44).take(5))
        assertEquals("3", field(b, 236, 8)) // data records
        assertEquals("1", field(b, 244, 8)) // record duration s
        assertEquals("2", field(b, 252, 4)) // nr signals

        // signal 1 header at 256
        assertEquals("IBI_ms", field(b, 256, 16))
        assertEquals("ms", field(b, 256 + 16 + 80, 8))
        assertEquals("300", field(b, 256 + 104, 8))
        assertEquals("2200", field(b, 256 + 112, 8))
        assertEquals("-32768", field(b, 256 + 120, 8))
        assertEquals("32767", field(b, 256 + 128, 8))
        assertEquals("1", field(b, 256 + 216, 8)) // samples per record

        // signal 2 (annotations) header at 512
        assertEquals("EDF Annotations", field(b, 512, 16))
        assertEquals("-1", field(b, 512 + 104, 8)) // physmin must be negative
        assertEquals("-1", field(b, 512 + 120, 8)) // digmin must be negative
        assertEquals("20", field(b, 512 + 216, 8)) // 40 bytes / 2
    }

    @Test
    fun `ibi channel step-holds and digitizes correctly`() {
        val b = build()
        fun ibiAt(record: Int) = littleInt16(b, 768 + record * 42)

        // record 0: no sample seen yet -> held = PHYS_MIN -> digmin
        assertEquals(-32768, ibiAt(0))
        // record 1: sample at t=1.5 consumed -> 1000 ms
        assertEquals(EdfPlusWriter.digitize(1000.0), ibiAt(1))
        // record 2: step-held at 1000 ms
        assertEquals(EdfPlusWriter.digitize(1000.0), ibiAt(2))

        // round-trip within quantization step (~0.03 ms)
        val phys = EdfPlusWriter.undigitize(ibiAt(1))
        assertEquals(1000.0, phys, 0.03)
    }

    @Test
    fun `annotation TALs land in the right data records`() {
        val b = build()
        // record 1 starts at 768 + 42; annotation channel at +2 bytes
        val tal1 = b.copyOfRange(768 + 42 + 2, 768 + 42 + 2 + 20)
        val text1 = tal1.toString(Charsets.US_ASCII).trimEnd('\u0000')
        assertEquals("1.5 \u0014stage=Light", text1)

        val tal2 = b.copyOfRange(768 + 84 + 2, 768 + 84 + 2 + 20)
        val text2 = tal2.toString(Charsets.US_ASCII).trimEnd('\u0000')
        assertEquals("2 \u0014stage=Deep", text2)
    }

    @Test
    fun `digitization clips out-of-range values`() {
        assertEquals(-32768, EdfPlusWriter.digitize(-50.0))
        assertEquals(32767, EdfPlusWriter.digitize(99999.0))
        assertTrue(EdfPlusWriter.undigitize(-32768) >= EdfPlusWriter.PHYS_MIN_MS - 1e-9)
    }
}
