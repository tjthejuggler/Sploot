package com.example.sploot.ble.snoop

import java.io.InputStream

/**
 * Mode C ingestion: parses Android `btsnoop_hci.log` files (16-byte record headers,
 * HCI ACL -> L2CAP -> ATT) and reconstructs the GATT notification stream captured
 * overnight by the OS Bluetooth stack.
 *
 * btsnoop file format (all record-header fields big-endian):
 *  - File header (16 bytes): magic "btsnoop\0", u32 version (1), u32 datalink type.
 *    Datalink 1002 (HCI UART H4, used by Android bug reports) and 1001/0 are treated
 *    as H4-encapsulated HCI packets. Assumption: records carry the H4 packet type
 *    byte as their first byte (true for DLT 1002; no pseudoheader variant has been
 *    observed in the wild for Android logs — documented assumption).
 *  - Record header (24 bytes): u32 originalLength, u32 includedLength,
 *    u32 packetFlags (bit 0: 0 = sent host->controller, 1 = received), u32 cumulativeDrops,
 *    u64 timestamp in microseconds since 0000-01-01 (btsnoop epoch).
 *    The constant 0x00E03AB44A676000 is the microsecond offset between 0000-01-01 and
 *    the Unix epoch, so epochMs = (ts - 0x00E03AB44A676000) / 1000.
 *
 * Malformed / truncated records are skipped and counted in [Counters]; parsing resumes
 * at the next record boundary whenever the remaining stream still contains a full header.
 */
class BtsnoopParser {

    enum class Direction { SENT, RECEIVED }

    /** One raw btsnoop record with its H4 HCI packet. */
    class SnoopRecord(
        val timestampMs: Long,
        val direction: Direction,
        val h4Packet: ByteArray,
    )

    /** Aggregate robustness / accounting counters for one parse run. */
    data class Counters(
        val recordsParsed: Int = 0,
        val recordsSkipped: Int = 0,
        val nonAclPackets: Int = 0,
    )

    /**
     * Parses the stream, invoking [onRecord] for every well-formed record.
     * Returns counters. Trailing truncated data terminates parsing gracefully.
     */
    fun parse(input: InputStream, onRecord: (SnoopRecord) -> Unit): Counters {
        val data = input.readBytes()
        require(data.size >= 16 && data.copyOfRange(0, 8).contentEquals(MAGIC)) {
            "not a btsnoop file (bad magic or truncated header)"
        }
        // bytes 8..12 version, 12..16 datalink — accepted but not otherwise interpreted (see KDoc).
        var records = 0
        var skipped = 0
        var nonAcl = 0
        var offset = 16
        while (offset + 24 <= data.size) {
            val origLen = data.readU32Be(offset)
            val incLen = data.readU32Be(offset + 4)
            val flags = data.readU32Be(offset + 8)
            @Suppress("UNUSED_VARIABLE") val drops = data.readU32Be(offset + 12)
            val ts = data.readU64Be(offset + 16)
            val bodyStart = offset + 24
            if (incLen > MAX_RECORD || bodyStart + incLen > data.size) {
                // Truncated or corrupt length: cannot trust further framing.
                skipped++
                break
            }
            val body = data.copyOfRange(bodyStart, bodyStart + incLen)
            if (origLen < incLen) {
                // Original length smaller than included length is corrupt.
                skipped++
            } else {
                records++
                onRecord(
                    SnoopRecord(
                        timestampMs = btsnoopTsToEpochMs(ts),
                        direction = if (flags and 1 == 1) Direction.RECEIVED else Direction.SENT,
                        h4Packet = body,
                    )
                )
                if (body.isEmpty() || (body[0].toInt() and 0xFF) != HCI_ACL) nonAcl++
            }
            offset = bodyStart + incLen
        }
        return Counters(records, skipped, nonAcl)
    }

    companion object {
        private val MAGIC = byteArrayOf(0x62, 0x74, 0x73, 0x6E, 0x6F, 0x6F, 0x70, 0) // "btsnoop\0"
        private const val MAX_RECORD = 1 shl 20
        /** H4 packet type byte for HCI ACL packets. */
        const val HCI_ACL = 0x02

        /** btsnoop epoch (us since 0000-01-01) -> Unix epoch milliseconds. */
        fun btsnoopTsToEpochMs(ts: Long): Long =
            (ts - BTSNOOP_EPOCH_OFFSET_US) / 1000

        /** Microseconds between 0000-01-01 and the Unix epoch. */
        const val BTSNOOP_EPOCH_OFFSET_US = 0x00E03AB44A676000L

        private fun ByteArray.readU32Be(o: Int): Int =
            ((this[o].toInt() and 0xFF) shl 24) or
                ((this[o + 1].toInt() and 0xFF) shl 16) or
                ((this[o + 2].toInt() and 0xFF) shl 8) or
                (this[o + 3].toInt() and 0xFF)

        private fun ByteArray.readU64Be(o: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (this[o + i].toLong() and 0xFF)
            return v
        }
    }
}

/** One reassembled L2CAP PDU with its connection handle. */
class L2capPdu(
    val connectionHandle: Int,
    val timestampMs: Long,
    val cid: Int,
    val payload: ByteArray,
)

/**
 * Reassembles HCI ACL fragments (H4 type 0x02 packets) into complete L2CAP PDUs.
 *
 * ACL header (little-endian): u16 handle|flags (handle = bits 0..11, PB = bits 12..13,
 * BC = bits 14..15), u16 data length. PB 0x2 (first, automatically flushable) and PB 0x0
 * (first, non-flushable — we treat identically) start a new PDU; PB 0x1 continues it.
 * A single ACL packet may contain multiple concatenated L2CAP PDUs; each completed PDU
 * (u16 LE length + u16 LE cid + payload) is forwarded to [sink].
 *
 * Reassembly state is kept per connection handle, mirroring the controller behaviour.
 */
class HciAclDemux(private val sink: (L2capPdu) -> Unit) {

    private class Buf(var bytes: ByteArray, var ts: Long)

    private val pending = HashMap<Int, Buf>()
    var l2capFrames = 0
        private set
    var aclFragments = 0
        private set
    var malformedFragments = 0
        private set

    /** Feed one H4 ACL packet (first byte must be 0x02). */
    fun feed(packet: ByteArray, timestampMs: Long) {
        if (packet.size < 5 || (packet[0].toInt() and 0xFF) != BtsnoopParser.HCI_ACL) {
            malformedFragments++
            return
        }
        val hf = ((packet[1].toInt() and 0xFF) or ((packet[2].toInt() and 0xFF) shl 8))
        val handle = hf and 0x0FFF
        val pb = (hf shr 12) and 0x3
        val len = ((packet[3].toInt() and 0xFF) or ((packet[4].toInt() and 0xFF) shl 8))
        val body = packet.copyOfRange(5, minOf(packet.size, 5 + len))
        if (body.size < len) {
            malformedFragments++
            return
        }
        aclFragments++
        when (pb) {
            0x2, 0x0 -> {
                if (pb == 0x0 && pending[handle] != null && body.size < 8) {
                    // Ambiguous legacy non-flushable first fragment; treat as continuation
                    // is unsafe, start fresh as the common Android stack never emits it.
                }
                pending[handle] = Buf(body, timestampMs)
            }
            0x1 -> {
                val buf = pending[handle]
                if (buf == null) {
                    malformedFragments++ // continuation without a first fragment
                    return
                }
                buf.bytes += body
            }
            else -> { // 0x3 reserved
                malformedFragments++
                return
            }
        }
        drain(handle)
    }

    /** Emits every complete L2CAP PDU buffered for [handle]; keeps a trailing partial. */
    private fun drain(handle: Int) {
        val buf = pending[handle] ?: return
        var off = 0
        while (off + 4 <= buf.bytes.size) {
            val pduLen = ((buf.bytes[off].toInt() and 0xFF) or ((buf.bytes[off + 1].toInt() and 0xFF) shl 8))
            val cid = ((buf.bytes[off + 2].toInt() and 0xFF) or ((buf.bytes[off + 3].toInt() and 0xFF) shl 8))
            if (off + 4 + pduLen > buf.bytes.size) break // incomplete PDU, wait for more fragments
            sink(
                L2capPdu(
                    connectionHandle = handle,
                    timestampMs = buf.ts,
                    cid = cid,
                    payload = buf.bytes.copyOfRange(off + 4, off + 4 + pduLen),
                )
            )
            l2capFrames++
            off += 4 + pduLen
        }
        if (off == buf.bytes.size) pending.remove(handle)
        else pending[handle] = Buf(buf.bytes.copyOfRange(off, buf.bytes.size), buf.ts)
    }
}
