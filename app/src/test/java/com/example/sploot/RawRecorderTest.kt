package com.example.sploot

import com.example.sploot.ble.AccSample
import com.example.sploot.ble.EcgSample
import com.example.sploot.ble.HrSample
import com.example.sploot.ble.IbiSample
import com.example.sploot.ble.IbiSource
import com.example.sploot.ble.SensorStatus
import com.example.sploot.recording.RawRecorder
import java.io.DataInputStream
import java.io.ByteArrayInputStream
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawRecorderTest {

    private fun newRecorder(): Pair<RawRecorder, java.io.File> {
        val dir = createTempDirectory("sploot-session").toFile()
        return RawRecorder(dir, sessionId = "test-session") to dir
    }

    @Test
    fun `header contains magic and start timestamp`() {
        val (_, dir) = newRecorder()
        val t0 = 1_700_000_000_000L
        val rec = RawRecorder(dir, "s2", clock = { t0 })
        rec.start()
        assertEquals(t0, rec.readHeaderTimestamp())
        val head = java.io.File(dir, RawRecorder.RAW_FILE_NAME).readBytes().copyOfRange(0, 4)
        assertTrue(head.contentEquals("SPR1".toByteArray(Charsets.US_ASCII)))
        rec.close()
    }

    @Test
    fun `frame round-trip preserves all sample types`() {
        val (rec, _) = newRecorder()
        rec.start()
        val samples = listOf(
            HrSample(1_000, 62),
            IbiSample(1_100, 967, IbiSource.PPI, blockerBit = true, skinContact = true, error = 8),
            IbiSample(1_200, 1024, IbiSource.RR),
            AccSample(1_300, 12, -34, 56, 52),
            EcgSample(1_400, -32000),
        )
        samples.forEach { rec.append(it) }
        rec.appendStatus(SensorStatus(87, SensorStatus.ConnectionState.CONNECTED))
        rec.close()

        val frames = rec.readFrames()
        assertEquals(samples.size + 1, frames.size)
        assertEquals(RawRecorder.TYPE_HR, frames[0].type)
        assertEquals(RawRecorder.TYPE_IBI, frames[1].type)
        assertEquals(RawRecorder.TYPE_ACC, frames[3].type)
        assertEquals(RawRecorder.TYPE_ECG, frames[4].type)
        assertEquals(RawRecorder.TYPE_STATUS, frames[5].type)

        // decode IBI payload fields
        val d = DataInputStream(ByteArrayInputStream(frames[1].payload))
        assertEquals(967, d.readShort().toInt())
        assertEquals(IbiSource.PPI.ordinal, d.readByte().toInt())
        assertEquals(2, d.readByte().toInt()) // tri-state: true
        assertEquals(2, d.readByte().toInt()) // tri-state: true
        assertEquals(8, d.readShort().toInt())

        // HR payload
        val h = DataInputStream(ByteArrayInputStream(frames[0].payload))
        assertEquals(62, h.readShort().toInt())
    }

    @Test
    fun `counters and stats track appended packets`() {
        val (rec, _) = newRecorder()
        rec.start()
        repeat(5) { rec.append(AccSample(it.toLong(), 1, 2, 3, 52)) }
        repeat(3) { rec.append(HrSample(it.toLong(), 60 + it)) }
        val stats = rec.stats()
        assertEquals(8, stats.framesWritten)
        assertEquals(5, stats.counters.acc)
        assertEquals(3, stats.counters.hr)
        assertTrue(stats.pendingBytes > 0)
        rec.close()
        // close flushes: no pending bytes, counters persist in index json
        assertEquals(0, rec.stats().pendingBytes)
    }

    @Test
    fun `close flushes pending buffer to disk`() {
        val (rec, dir) = newRecorder()
        rec.start()
        repeat(100) { rec.append(AccSample(it.toLong(), 1, 2, 3, 52)) }
        assertTrue(rec.stats().pendingBytes > 0)
        rec.close()
        assertEquals(0, rec.stats().pendingBytes)
        val bytes = java.io.File(dir, RawRecorder.RAW_FILE_NAME).readBytes()
        assertTrue(bytes.size > RawRecorder.HEADER_SIZE)
        assertEquals(100, rec.readFrames().size)
    }

    @Test
    fun `truncated tail frame is skipped when reading`() {
        val (rec, dir) = newRecorder()
        rec.start()
        repeat(10) { rec.append(HrSample(it.toLong(), 60)) }
        rec.close()
        val file = java.io.File(dir, RawRecorder.RAW_FILE_NAME)
        val full = file.readBytes()
        // simulate process kill mid-frame: chop 3 bytes off
        val chopped = full.copyOf(full.size - 3)
        file.writeBytes(chopped)
        assertEquals(9, rec.readFrames().size)
    }

    @Test
    fun `index json written with session metadata`() {
        val (rec, dir) = newRecorder()
        rec.start()
        rec.append(HrSample(1L, 60))
        rec.close()
        val idxText = java.io.File(dir, RawRecorder.INDEX_FILE_NAME).readText()
        assertTrue(idxText.contains("\"sessionId\": \"test-session\""))
        assertNotNull(idxText)
    }
}
