package com.example.sploot.ble.snoop

import java.util.UUID

/** An ATT Handle Value Notification (0x1B) or Indication (0x1D). */
class AttNotification(
    val timestampMs: Long,
    val attrHandle: Int,
    val value: ByteArray,
    val isIndication: Boolean,
)

/**
 * ATT/GATT decoding layer.
 *
 * Consumes L2CAP PDUs on the fixed ATT channel (CID 0x0004) and:
 *  - emits [AttNotification] for ATT_HANDLE_VALUE_NTF (0x1B) and ATT_HANDLE_VALUE_IND (0x1D):
 *    `[opcode][attrHandle u16 LE][value...]`;
 *  - tracks the attribute handle -> characteristic UUID mapping by observing
 *    ATT_READ_BY_TYPE_RESP (0x09) traffic (GATT character discovery). Entries with
 *    value length 3 (props u8 + valueHandle u16 + uuid u16) map 16-bit UUIDs, entries with
 *    length 17 carry 128-bit UUIDs. This is how the pipeline discovers which handle is
 *    PMD Data (FB005C82-02E7-F387-1CAD-8ACD2D8DF0C8), PMD Control, HR Measurement 0x2A37
 *    and Battery 0x2A19.
 *  - additionally consumes ATT_FIND_INFORMATION_RESP (0x05) which maps handles -> UUIDs
 *    for 16-bit (entry len 4) and 128-bit (entry len 20) descriptors/characteristics.
 *
 * Unknown ATT opcodes are counted, never fatal.
 */
class AttGattDecoder(private val sink: (AttNotification) -> Unit) {

    /** handle -> characteristic UUID (16-bit UUIDs expanded into the Bluetooth base UUID). */
    val handleToUuid = HashMap<Int, UUID>()

    var notifications = 0
        private set
    var indications = 0
        private set
    var unknownOpcodes = 0
        private set

    /** Feeds one L2CAP PDU; non-ATT CIDs are ignored. */
    fun feed(pdu: L2capPdu) {
        if (pdu.cid != ATT_CID || pdu.payload.isEmpty()) return
        when (pdu.payload[0].toInt() and 0xFF) {
            OP_HANDLE_VALUE_NTF, OP_HANDLE_VALUE_IND -> {
                if (pdu.payload.size < 3) return
                val handle = u16le(pdu.payload, 1)
                val value = pdu.payload.copyOfRange(3, pdu.payload.size)
                if (pdu.payload[0].toInt() and 0xFF == OP_HANDLE_VALUE_NTF) notifications++ else indications++
                sink(AttNotification(pdu.timestampMs, handle, value, isIndication = pdu.payload[0].toInt() and 0xFF == OP_HANDLE_VALUE_IND))
            }
            OP_READ_BY_TYPE_RESP -> parseReadByType(pdu.payload)
            OP_FIND_INFORMATION_RESP -> parseFindInformation(pdu.payload)
            else -> if (pdu.payload[0].toInt() and 0xFF > OP_MAX_KNOWN) unknownOpcodes++
        }
    }

    private fun parseReadByType(payload: ByteArray) {
        if (payload.size < 2) return
        val len = payload[1].toInt() and 0xFF
        if (len <= 2) return
        var off = 2
        while (off + len <= payload.size) {
            // Entry: [handle u16][props u8][valueHandle u16][uuid u16 | u128]
            when (len) {
                7 -> { // 16-bit UUID
                    val valueHandle = u16le(payload, off + 3)
                    handleToUuid[valueHandle] = u16UuidBytes(payload, off + 5)
                }
                21 -> { // 128-bit UUID
                    val valueHandle = u16le(payload, off + 3)
                    handleToUuid[valueHandle] = uuid128le(payload, off + 5)
                }
            }
            off += len
        }
    }

    private fun parseFindInformation(payload: ByteArray) {
        if (payload.size < 2) return
        val len = payload[1].toInt() and 0xFF
        if (len != 4 && len != 20) return
        var off = 2
        while (off + len <= payload.size) {
            val handle = u16le(payload, off)
            handleToUuid[handle] =
                if (len == 4) u16UuidBytes(payload, off + 2) else uuid128le(payload, off + 2)
            off += len
        }
    }

    companion object {
        const val ATT_CID = 0x0004
        const val OP_HANDLE_VALUE_NTF = 0x1B
        const val OP_HANDLE_VALUE_IND = 0x1D
        const val OP_READ_BY_TYPE_RESP = 0x09
        const val OP_FIND_INFORMATION_RESP = 0x05
        private const val OP_MAX_KNOWN = 0x20

        fun u16le(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

        /** 16-bit UUID in Bluetooth little-endian order -> UUID in the base UUID space. */
        fun u16UuidBytes(b: ByteArray, o: Int): UUID =
            uuid16(u16le(b, o))

        fun uuid16(v: Int): UUID =
            UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", v))

        fun uuid128le(b: ByteArray, o: Int): UUID {
            val msb = ByteArray(8); val lsb = ByteArray(8)
            // ATT transmits the UUID little-endian: reverse of the string representation order.
            for (i in 0 until 8) msb[i] = b[o + 15 - i]
            for (i in 0 until 8) lsb[i] = b[o + 7 - i]
            var m = 0L; var l = 0L
            for (i in 0 until 8) { m = (m shl 8) or (msb[i].toLong() and 0xFF); l = (l shl 8) or (lsb[i].toLong() and 0xFF) }
            return UUID(m, l)
        }
    }
}
