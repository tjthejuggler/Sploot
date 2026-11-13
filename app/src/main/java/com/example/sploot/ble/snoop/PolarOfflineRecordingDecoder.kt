package com.example.sploot.ble.snoop

import com.example.sploot.ble.IbiSample
import com.example.sploot.ble.IbiSource
import com.example.sploot.ble.RawSample
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Recovers Polar **offline recordings** (PPI.REC etc.) that transit the btsnoop
 * log as BLE file transfers — the case where the sensor recorded the night into
 * internal memory and a third-party app (Sleep2, Polar Flow) pulled the file
 * afterwards. Sploot's own capture window sees the transfer bytes even though
 * no live PMD streaming ever happened, and the transfer characteristic's GATT
 * handle is usually *not* UUID-resolvable in the capture (the reading app
 * skips service discovery on reconnect), so detection is content-based:
 *
 *  1. **Arm** when an ATT write payload contains a Polar recording path —
 *     e.g. `/U/0/20261101/R/200605/PHYSDATA/PPI.REC` (any `.REC` suffix).
 *  2. **Consume** notifications on the (learned) transfer handle. Packets are
 *     `[seq u8][records…]`; low nibble of byte 0 selects the kind
 *     (7 = data, 6 = metadata). Verified against a captured Sleep2 sync
 *     (2026-11-02): 630 data packets, 23,934 records, 97.8 % valid PPI,
 *     Σppi = 8.14 h — exactly the recorded night.
 *  3. **Records** are 6 bytes: `[u16 f0][u16 f1][u16 ppiMs]`, ppi in
 *     milliseconds at field +4. Physiological range 250–2500 ms is accepted,
 *     other values advance the clock (sensor-off / off-body gaps).
 *  4. **Anchor**: metadata packets carry the ASCII recording start
 *     (`2026-11-01 19:06:05`), which Polar stores in UTC; sample timestamps =
 *     anchor + Σppi. Records seen before an anchor resolves are buffered and
 *     back-filled; if no header exists at all the anchor falls back to
 *     (first data packet capture time − Σppi so far) after
 *     [FALLBACK_AFTER_PACKETS] packets.
 *
 * Pure JVM, unit-tested with synthetic transfer packets.
 */
class PolarOfflineRecordingDecoder(private val emit: (RawSample) -> Unit) {

    var armed = false
        private set
    var activeHandle: Int? = null
        private set
    var recordingStartMs: Long? = null
        private set
    var recordsSeen = 0
        private set
    var ibiEmitted = 0
        private set
    var clockMs = 0L
        private set

    private var firstDataPacketCaptureMs: Long? = null
    private var dataPacketsSeen = 0
    private var clockAtFirstPacketEndMs = 0L

    /** ppi values of records parsed before an anchor resolved, in order. */
    private var pending: ArrayList<Int>? = null

    /** ATT write observed; arms the decoder when it names a .REC transfer. */
    fun onWrite(value: ByteArray) {
        val text = runCatching { String(value, Charsets.ISO_8859_1) }.getOrNull() ?: return
        if (REC_PATH_REGEX.containsMatchIn(text)) {
            armed = true
            activeHandle = null
            recordingStartMs = null
            recordsSeen = 0
            ibiEmitted = 0
            clockMs = 0L
            firstDataPacketCaptureMs = null
            dataPacketsSeen = 0
            clockAtFirstPacketEndMs = 0L
            pending = null
        }
    }

    /** Notification observed on an (unresolved) handle while armed. */
    fun onNotification(handle: Int, captureMs: Long, value: ByteArray) {
        if (!armed) return
        val h = activeHandle
        if (h == null) activeHandle = handle else if (h != handle) return

        if (value.isEmpty()) return
        when (value[0].toInt() and 0x0F) {
            KIND_META -> extractStartTime(value)
            KIND_DATA -> if (value.size >= 7) parseData(value, captureMs)
        }
    }

    private fun extractStartTime(value: ByteArray) {
        if (recordingStartMs != null) return // anchor already committed
        val text = runCatching { String(value, Charsets.ISO_8859_1) }.getOrNull() ?: return
        val m = TIMESTAMP_REGEX.find(text) ?: return
        val parsed = runCatching {
            LocalDateTime.parse(m.value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull() ?: return
        recordingStartMs = parsed
        flushPending()
    }

    private fun parseData(value: ByteArray, captureMs: Long) {
        if (firstDataPacketCaptureMs == null) firstDataPacketCaptureMs = captureMs
        dataPacketsSeen++
        var off = 1
        while (off + 6 <= value.size) {
            val ppi = u16le(value, off + 4)
            recordsSeen++
            clockMs += ppi
            val base = recordingStartMs
            when {
                base != null -> emitValid(base + clockMs, ppi)
                else -> (pending ?: ArrayList<Int>().also { pending = it }).add(ppi)
            }
            off += 6
        }
        // The first packet's capture time corresponds to the END of its records
        // (a live transfer streams at recording pace); remember the clock there.
        if (dataPacketsSeen == 1) clockAtFirstPacketEndMs = clockMs
        // No header after a grace period: anchor from the capture clock and
        // back-fill everything buffered so far.
        if (recordingStartMs == null && dataPacketsSeen >= FALLBACK_AFTER_PACKETS) {
            val first = firstDataPacketCaptureMs ?: return
            recordingStartMs = first - clockAtFirstPacketEndMs
            flushPending()
        }
    }

    /** Back-fills buffered pre-anchor records once [recordingStartMs] is known. */
    private fun flushPending() {
        val base = recordingStartMs ?: return
        val buf = pending ?: return
        pending = null
        var bufferedTotal = 0L
        for (ppi in buf) bufferedTotal += ppi
        var t = base + clockMs - bufferedTotal
        for (ppi in buf) {
            t += ppi
            emitValid(t, ppi)
        }
    }

    private fun emitValid(timestampMs: Long, ppi: Int) {
        if (ppi in MIN_VALID_PPI_MS..MAX_VALID_PPI_MS) {
            emit(IbiSample(timestampMs, ppi, IbiSource.PPI))
            ibiEmitted++
        }
    }

    companion object {
        private const val KIND_DATA = 0x7
        private const val KIND_META = 0x6
        private const val MIN_VALID_PPI_MS = 250
        private const val MAX_VALID_PPI_MS = 2500

        /** Data packets to wait for a metadata header before capture-time fallback. */
        private const val FALLBACK_AFTER_PACKETS = 2

        /** `/U/0/20261101/R/200605/PHYSDATA/PPI.REC` and friends. */
        private val REC_PATH_REGEX = Regex("""/[A-Z0-9/]*\.REC\b""", RegexOption.IGNORE_CASE)
        private val TIMESTAMP_REGEX = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")

        private fun u16le(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    }
}
