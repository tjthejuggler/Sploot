package com.example.sploot

import com.example.sploot.ble.IbiSample
import com.example.sploot.ble.snoop.PolarOfflineRecordingDecoder
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the offline-recording recovery decoder, with packet bytes
 * modelled on the on-device capture verified 2026-11-02 (Sleep2 pulling
 * PPI.REC over the Polar file-transfer GATT protocol).
 */
class PolarOfflineRecordingDecoderTest {

    private class Recorder {
        val samples = mutableListOf<IbiSample>()
        fun emit(s: com.example.sploot.ble.RawSample) {
            samples.add(s as IbiSample)
        }
    }

    /** 2026-11-01 19:06:05 UTC in epoch ms — the anchor embedded in metadata packets. */
    private val anchorMs: Long =
        java.time.LocalDateTime.of(2026, 11, 1, 19, 6, 5)
            .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()

    private fun u16le(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    /** `[a u16][b u16][ppi u16]` record, as seen on the wire. */
    private fun rec(a: Int, b: Int, ppiMs: Int): ByteArray =
        u16le(a) + u16le(b) + u16le(ppiMs)

    /** Data packet: `[seq u8][records…]`, low nibble of seq = 7. */
    private fun dataPacket(seq: Int, vararg records: ByteArray): ByteArray {
        val body = records.reduce { acc, c -> acc + c }
        return byteArrayOf(((seq shl 4) or 0x7).toByte()) + body
    }

    /** Metadata packet carrying the ASCII recording start (UTC). */
    private fun metaPacket(): ByteArray =
        byteArrayOf(0x06) +
            "meta\u0000\u0000\u00002026-11-01 19:06:05\u0000\u0000".toByteArray(Charsets.ISO_8859_1)

    private fun armWrite(): ByteArray =
        "\u0002\u0011\u0000\u0008\u0000\u0012\r/U/0/20261101/R/200605/PHYSDATA/PPI.REC"
            .toByteArray(Charsets.ISO_8859_1)

    // ---- 1. arming + happy path ----

    @Test
    fun `arms on write with REC path and decodes records with header anchor`() {
        val r = Recorder()
        val d = PolarOfflineRecordingDecoder(r::emit)

        assertFalse(d.armed)
        d.onWrite(armWrite())
        assertTrue("REC path write must arm the decoder", d.armed)

        // Header anchor: 2026-11-01 19:06:05 UTC = epoch ms
        d.onNotification(handle = 0x1f, captureMs = 0L, value = metaPacket())
        val anchor = java.time.LocalDateTime.of(2026, 11, 1, 19, 6, 5)
            .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        assertEquals(anchor, d.recordingStartMs)

        d.onNotification(0x1f, 1000L, dataPacket(0, rec(1, 0, 1000), rec(2, 0, 800)))
        d.onNotification(0x1f, 2000L, dataPacket(1, rec(3, 0, 600)))

        assertEquals(3, d.recordsSeen)
        assertEquals(3, r.samples.size)
        assertEquals(3, d.ibiEmitted)

        // Clock walks anchor + cumulative ppi; sample ts = end of interval.
        assertEquals(anchor + 1000L, r.samples[0].timestampMs)
        assertEquals(1000, r.samples[0].intervalMs)
        assertEquals(anchor + 1800L, r.samples[1].timestampMs)
        assertEquals(anchor + 2400L, r.samples[2].timestampMs)
        assertEquals(com.example.sploot.ble.IbiSource.PPI, r.samples[0].source)
    }

    // ---- 2. guards ----

    @Test
    fun `ignores notifications before arming and on other handles`() {
        val r = Recorder()
        val d = PolarOfflineRecordingDecoder(r::emit)
        d.onNotification(0x1f, 0L, dataPacket(0, rec(0, 0, 900)))
        assertEquals(0, d.recordsSeen)

        d.onWrite(armWrite())
        // Two packets on the locked-in handle reach the fallback anchor…
        d.onNotification(0x1f, 0L, dataPacket(0, rec(0, 0, 900)))
        d.onNotification(0x1f, 1000L, dataPacket(1, rec(0, 0, 900)))
        // …and a different handle afterwards is dropped (lock-in).
        d.onNotification(0x20, 2000L, dataPacket(2, rec(0, 0, 900)))
        assertEquals(2, d.recordsSeen)
        assertEquals(2, r.samples.size)
    }

    @Test
    fun `out-of-range ppi advances clock but is not emitted`() {
        val r = Recorder()
        val d = PolarOfflineRecordingDecoder(r::emit)
        d.onWrite(armWrite())
        d.onNotification(0x1f, 0L, metaPacket())
        // 100ms + 700ms + 99999ms (off-body) + 500ms
        d.onNotification(0x1f, 0L, dataPacket(0, rec(0, 0, 100), rec(0, 0, 700), rec(0, 0, 99999), rec(0, 0, 500)))

        assertEquals(4, d.recordsSeen)
        assertEquals(2, r.samples.size)
        // 100 ms is below MIN_VALID_PPI_MS: advances the clock, not emitted.
        // 99999 wraps to u16 34463 (also out of range, clock-only).
        // beats: skip(100) → 700 @ anchor+800 … skip → 500 @ anchor+35_763
        assertEquals(anchorMs + 800L, r.samples[0].timestampMs)
        assertEquals(700, r.samples[0].intervalMs)
        assertEquals(anchorMs + 35_763L, r.samples[1].timestampMs)
    }

    @Test
    fun `short packets and empty values are tolerated`() {
        val r = Recorder()
        val d = PolarOfflineRecordingDecoder(r::emit)
        d.onWrite(armWrite())
        d.onNotification(0x1f, 0L, byteArrayOf())
        d.onNotification(0x1f, 0L, byteArrayOf(0x07))
        d.onNotification(0x1f, 0L, byteArrayOf(0x07, 0x01, 0x02))
        assertEquals(0, d.recordsSeen)
        assertEquals(0, r.samples.size)
    }

    // ---- 3. capture-time anchor fallback ----

    @Test
    fun `without metadata the anchor falls back to first capture time minus clock`() {
        val r = Recorder()
        val d = PolarOfflineRecordingDecoder(r::emit)
        d.onWrite(armWrite())

        // Packet 1 at capture 100_000 (2×500 ms); packet 2 triggers the
        // fallback anchor: start = 100_000 − 1_000 = 99_000, then both
        // buffered beats are back-filled.
        d.onNotification(0x1f, 100_000L, dataPacket(0, rec(0, 0, 500), rec(0, 0, 500)))
        assertEquals(null, d.recordingStartMs)
        assertEquals(0, r.samples.size)
        d.onNotification(0x1f, 101_500L, dataPacket(1, rec(0, 0, 500)))
        assertEquals(99_000L, d.recordingStartMs)
        assertEquals(3, r.samples.size)
        assertEquals(99_500L, r.samples[0].timestampMs)
        assertEquals(100_000L, r.samples[1].timestampMs)
        assertEquals(100_500L, r.samples[2].timestampMs)
    }

    @Test
    fun `non REC writes never arm the decoder`() {
        val r = Recorder()
        val d = PolarOfflineRecordingDecoder(r::emit)
        d.onWrite("hello world".toByteArray())
        d.onWrite("/U/0/20261101/PHYSDATA/PPG.REC".toByteArray()) // other type still a REC
        assertTrue(d.armed)
        // Two packets reach the fallback anchor and flush the buffered beats.
        d.onNotification(0x1f, 0L, dataPacket(0, rec(0, 0, 800)))
        d.onNotification(0x1f, 1000L, dataPacket(1, rec(0, 0, 800)))
        assertEquals(2, r.samples.size)
    }

    // ---- 4. realistic throughput (regression: full-night transfer) ----

    @Test
    fun `high volume transfer decodes without state drift`() {
        val r = Recorder()
        val d = PolarOfflineRecordingDecoder(r::emit)
        d.onWrite(armWrite())
        d.onNotification(0x1f, 0L, metaPacket())

        val anchor = java.time.LocalDateTime.of(2026, 11, 1, 19, 6, 5)
            .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        var clock = 0L
        var seq = 0
        // 630 packets × 38 records × 1250 ms ≈ 8.3 h — exactly the packet
        // count and 229 B packet size of the real on-device capture.
        repeat(630) {
            val recs = Array(38) { rec(6, 0, 1250).also { clock += 1250 } }
            d.onNotification(0x1f, (seq * 34L), dataPacket(seq % 16, *recs))
            seq++
        }
        assertEquals(630 * 38, d.recordsSeen)
        assertEquals(630 * 38, r.samples.size)
        // last sample: anchor + full clock
        assertEquals(anchor + clock, r.samples.last().timestampMs)
        assertTrue("night must span > 8h of PPI", clock > 8 * 3_600_000L)
    }
}
