package com.example.sploot

import com.example.sploot.ble.AccSample
import com.example.sploot.ble.EcgSample
import com.example.sploot.ble.HrSample
import com.example.sploot.ble.IbiSample
import com.example.sploot.ble.IbiSource
import com.example.sploot.ble.RawSample
import com.example.sploot.ble.snoop.AttGattDecoder
import com.example.sploot.ble.snoop.BtsnoopParser
import com.example.sploot.ble.snoop.PmdFrameDecoder
import com.example.sploot.ble.snoop.SnoopIngestSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.UUID

/**
 * Phase 5 unit tests: every fixture is constructed byte-by-byte here (no captured dumps).
 */
class SnoopIngestTest {

    // ------------------------------------------------------------------ fixture builders

    private fun u16le(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun u24le(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte())

    private fun u32be(v: Int): ByteArray {
        val b = ByteArray(4)
        for (i in 0 until 4) b[i] = ((v shr (8 * (3 - i))) and 0xFF).toByte()
        return b
    }

    private fun u64be(v: Long): ByteArray {
        val b = ByteArray(8)
        for (i in 0 until 8) b[i] = ((v shr (8 * (7 - i))) and 0xFF).toByte()
        return b
    }

    private fun u64le(v: Long): ByteArray {
        val b = ByteArray(8)
        for (i in 0 until 8) b[i] = ((v shr (8 * i)) and 0xFF).toByte()
        return b
    }

    private fun be(vararg arrays: ByteArray): ByteArray = arrays.reduce { a, c -> a + c }

    /** btsnoop file: 16-byte header + records (received direction, epochMs timestamps). */
    private fun btsnoop(vararg records: Pair<Long, ByteArray>): ByteArray {
        val header = byteArrayOf(0x62, 0x74, 0x73, 0x6E, 0x6F, 0x6F, 0x70, 0) +
            u32be(1) + u32be(1002)
        var out = header
        for ((epochMs, packet) in records) {
            out = be(out, u32be(packet.size), u32be(packet.size), u32be(1), u32be(0),
                u64be(BtsnoopParser.BTSNOOP_EPOCH_OFFSET_US + epochMs * 1000), packet)
        }
        return out
    }

    /** One HCI ACL packet (H4 0x02) carrying the start (PB=0x2) or continuation (PB=0x1) fragment. */
    private fun acl(handle: Int, pb: Int, fragment: ByteArray): ByteArray =
        be(byteArrayOf(0x02), u16le(handle or (pb shl 12)), u16le(fragment.size), fragment)

    /** L2CAP/ATT PDU (CID 4). */
    private fun attPdu(att: ByteArray): ByteArray = be(u16le(att.size), u16le(4), att)

    private fun attNotification(handle: Int, value: ByteArray) =
        be(byteArrayOf(0x1B.toByte()), u16le(handle), value)

    /** ATT_READ_BY_TYPE_RESP with one 16-bit-UUID characteristic entry. */
    private fun readByType16(declHandle: Int, props: Int, valueHandle: Int, uuid16: Int) =
        be(byteArrayOf(0x09.toByte(), 7), u16le(declHandle), byteArrayOf(props.toByte()), u16le(valueHandle), u16le(uuid16))

    /** ATT_READ_BY_TYPE_RESP with one 128-bit-UUID characteristic entry. */
    private fun readByType128(declHandle: Int, props: Int, valueHandle: Int, uuid: UUID) =
        be(byteArrayOf(0x09.toByte(), 21), u16le(declHandle), byteArrayOf(props.toByte()), u16le(valueHandle), uuidLe(uuid))

    /** UUID in ATT transmission order (whole 16 bytes little-endian vs the string form). */
    private fun uuidLe(uuid: UUID): ByteArray {
        val msb = ByteArray(8); val lsb = ByteArray(8)
        var m = uuid.mostSignificantBits; var l = uuid.leastSignificantBits
        for (i in 7 downTo 0) { msb[i] = (m and 0xFF).toByte(); m = m shr 8; lsb[i] = (l and 0xFF).toByte(); l = l shr 8 }
        return be(lsb.reversedArray(), msb.reversedArray())
    }

    // ------------------------------------------------------------------ 1. file + record framing

    @Test
    fun `btsnoop header and timestamp conversion`() {
        val ms = 1700000000000L
        val file = btsnoop(ms to byteArrayOf(0x01, 0x0A)) // H4 event packet, not ACL
        var seen = 0
        val counters = BtsnoopParser().parse(ByteArrayInputStream(file)) { r ->
            seen++
            assertEquals(ms, r.timestampMs)
            assertEquals(BtsnoopParser.Direction.RECEIVED, r.direction)
        }
        assertEquals(1, seen)
        assertEquals(1, counters.recordsParsed)
        assertEquals(1, counters.nonAclPackets)
        assertEquals(0, counters.recordsSkipped)
    }

    @Test
    fun `malformed truncated tail record is skipped`() {
        val good = attPdu(attNotification(0x0012, byteArrayOf(0x2A)))
        val file = btsnoop(1000L to acl(0x0001, 0x2, good)) +
            // record header claims 1000 bytes but only 10 follow
            u32be(1000) + u32be(1000) + u32be(1) + u32be(0) + u64be(BtsnoopParser.BTSNOOP_EPOCH_OFFSET_US) + ByteArray(10)
        val counters = BtsnoopParser().parse(ByteArrayInputStream(file)) { }
        assertEquals(1, counters.recordsParsed)
        assertEquals(1, counters.recordsSkipped)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `bad magic rejected`() {
        BtsnoopParser().parse(ByteArrayInputStream(ByteArray(32))) { }
    }

    // ------------------------------------------------------------------ 2. ACL reassembly + handle mapping

    @Test
    fun `acl fragmentation reassembles one pdu across two fragments`() {
        val pdu = attPdu(attNotification(0x0012, byteArrayOf(0x09, 0x09)))
        val split = pdu.size / 2
        val file = btsnoop(
            5_000L to acl(0x0008, 0x2, pdu.copyOfRange(0, split)),
            5_100L to acl(0x0008, 0x1, pdu.copyOfRange(split, pdu.size)),
        )
        val (samples, summary) = SnoopIngestSession { ByteArrayInputStream(file) }.collect()
        assertTrue(samples.isEmpty()) // handle unknown -> not decoded, but ATT layer ran
        assertEquals(1, summary.l2capFrames)
        assertEquals(1, summary.attNotifications)
        assertEquals(1, summary.unknownHandles.size)
    }

    @Test
    fun `read_by_type maps 16-bit and 128-bit uuids`() {
        val pmdData = PmdFrameDecoder.UUID_PMD_DATA
        val file = btsnoop(
            1_000L to acl(0x0001, 0x2, attPdu(readByType128(0x0010, 0x10, 0x0011, pmdData))),
            2_000L to acl(0x0001, 0x2, attPdu(readByType16(0x0020, 0x10, 0x0021, 0x2A37))),
        )
        val att = AttGattDecoder { }
        BtsnoopParser().parse(ByteArrayInputStream(file)) { r ->
            val aclDemux = com.example.sploot.ble.snoop.HciAclDemux { p -> att.feed(p) }
            aclDemux.feed(r.h4Packet, r.timestampMs)
        }
        assertEquals(pmdData, att.handleToUuid[0x0011])
        assertEquals(AttGattDecoder.uuid16(0x2A37), att.handleToUuid[0x0021])
    }

    // ------------------------------------------------------------------ 3. HR service 0x2A37

    @Test
    fun `hr measurement with rr intervals converts 1024ths to ms`() {
        // flags 0x10: HR u8 + two RR u16 in 1/1024 s (1024 -> 1000 ms, 512 -> 500 ms)
        val value = byteArrayOf(0x10, 62) + u16le(1024) + u16le(512)
        val file = btsnoop(
            1_000L to acl(0x0001, 0x2, attPdu(readByType16(0x0020, 0x10, 0x0021, 0x2A37))),
            2_000L to acl(0x0001, 0x2, attPdu(attNotification(0x0021, value))),
        )
        val (samples, summary) = SnoopIngestSession { ByteArrayInputStream(file) }.collect()
        val hr = samples.filterIsInstance<HrSample>()
        assertEquals(listOf(62), hr.map { it.hr })
        assertEquals(listOf(2_000L), hr.map { it.timestampMs })
        val ibi = samples.filterIsInstance<IbiSample>()
        assertEquals(listOf(1000, 500), ibi.map { it.intervalMs })
        assertTrue(ibi.all { it.source == IbiSource.HR_SERVICE })
        assertEquals(2, summary.ibiSamples)
        assertEquals(1, summary.hrSamples)
    }

    // ------------------------------------------------------------------ 4. PMD PPI

    @Test
    fun `ppi notification yields ibi with blocker and skin bits`() {
        val frameNs = 1_700_000_000_000_000_000L
        // [type=3 PPI][u64 ns][frameType 0 raw][hr u8][pp u16][err u16][flags: skin contact bit1]
        val value = be(byteArrayOf(3), u64le(frameNs), byteArrayOf(0), byteArrayOf(60), u16le(950), u16le(8), byteArrayOf(0x02))
        val file = btsnoop(
            1_000L to acl(0x0001, 0x2, attPdu(readByType128(0x0010, 0x10, 0x0011, PmdFrameDecoder.UUID_PMD_DATA))),
            2_000L to acl(0x0001, 0x2, attPdu(attNotification(0x0011, value))),
        )
        val (samples, _) = SnoopIngestSession { ByteArrayInputStream(file) }.collect()
        val ibi = samples.filterIsInstance<IbiSample>()
        assertEquals(1, ibi.size)
        assertEquals(950, ibi[0].intervalMs)
        assertEquals(IbiSource.PPI, ibi[0].source)
        assertEquals(false, ibi[0].blockerBit)
        assertEquals(true, ibi[0].skinContact)
        assertEquals(frameNs / 1_000_000, ibi[0].timestampMs) // frame ts belongs to the sample
    }

    // ------------------------------------------------------------------ 5. PMD ACC delta frame

    @Test
    fun `delta compressed acc frame decodes known slope within milli-g`() {
        val decoder = PmdFrameDecoder(accRangeG = 2)
        // refs (x,y,z) = (0, -16384, 16384); 4 delta samples: x +100, y +50, z 0 each step.
        // Block header: a=4, b=8 -> bits = 4*8*3 = 96 -> 12 bytes, 4 samples x 3 i8 deltas.
        val deltas = listOf(
            Triple(100, 50, 0), Triple(100, 50, 0),
            Triple(100, 50, 0), Triple(100, 50, 0),
        )
        var content = be(
            u16le(0), u16le(-16384), u16le(16384),
            byteArrayOf(4, 8),
        )
        for ((dx, dy, dz) in deltas) content += byteArrayOf(dx.toByte(), dy.toByte(), dz.toByte())

        val out = ArrayList<RawSample>()
        decoder.decode(PmdFrameDecoder.UUID_PMD_DATA, 1_000L, pmdFrame(2, content, 0x80), out::add)
        val acc = out.filterIsInstance<AccSample>()
        assertEquals(5, acc.size)
        assertEquals(52, acc[0].sampleRate)
        val scale = 2000.0 / 32768.0
        // x slope: 0,100,200,300,400 raw -> milli-g
        for (i in 0 until 5) {
            assertEquals(Math.round(100 * i * scale).toInt(), acc[i].x)
            assertEquals(-Math.round((16384 - 50 * i) * scale).toInt(), acc[i].y)
            assertEquals(Math.round(16384 * scale).toInt(), acc[i].z) // ~1000 mg (1 g)
        }
        assertEquals(1000, acc[4].z) // 1 g in milli-g
        // timestamps: 1000/52 = 19 ms step, frame ts marks the LAST sample
        assertEquals(19L, acc[1].timestampMs - acc[0].timestampMs)
        assertEquals(1_700_000_000_000L, acc[4].timestampMs)
    }

    /** [frameType] 0x00 = raw type 0, 0x80 = compressed type 0. */
    private fun pmdFrame(measurement: Int, content: ByteArray, frameType: Int): ByteArray =
        be(byteArrayOf(measurement.toByte()), u64le(1_700_000_000_000_000_000L), byteArrayOf(frameType.toByte()), content)

    // ------------------------------------------------------------------ 6. PMD ECG

    @Test
    fun `ecg frame decodes i24 microvolt samples`() {
        val decoder = PmdFrameDecoder()
        val content = be(u24le(100), u24le(-200), u24le(300))
        val out = ArrayList<RawSample>()
        decoder.decode(PmdFrameDecoder.UUID_PMD_DATA, 9_000L, pmdFrame(0, content, 0x00), out::add)
        val ecg = out.filterIsInstance<EcgSample>()
        assertEquals(listOf(100, -200, 300), ecg.map { it.microVolts })
        // 130 Hz -> ~7 ms spacing; frame timestamp (1700...000 ms) belongs to last sample
        assertEquals(1_700_000_000_000L, ecg.last().timestampMs)
        assertEquals(ecg.last().timestampMs - 14L, ecg.first().timestampMs)
    }

    // ------------------------------------------------------------------ 7. resilience

    @Test
    fun `unknown pmd frame type is counted and skipped`() {
        val decoder = PmdFrameDecoder()
        val out = ArrayList<RawSample>()
        decoder.decode(PmdFrameDecoder.UUID_PMD_DATA, 1_000L, pmdFrame(0x05, byteArrayOf(1, 2, 3), 0x00), out::add)
        assertTrue(out.isEmpty())
        assertEquals(1, decoder.unknownFrames)
    }

    @Test
    fun `battery level surfaces in summary`() {
        val file = btsnoop(
            1_000L to acl(0x0001, 0x2, attPdu(readByType16(0x0030, 0x10, 0x0031, 0x2A19))),
            2_000L to acl(0x0001, 0x2, attPdu(attNotification(0x0031, byteArrayOf(87)))),
        )
        val (_, summary) = SnoopIngestSession { ByteArrayInputStream(file) }.collect()
        assertEquals(87, summary.batteryPercent)
    }
}
