package com.example.sploot.data.export

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Native EDF+ (continuity category C) writer.
 *
 * Layout per the EDF+ specification:
 *  - 256-byte main header:
 *      0..7    version "0"
 *      8..87   patient identification (80)
 *      88..167 recording identification (80)
 *      168..175 start date "dd.mm.yy"
 *      176..183 start time "hh.mm.ss"
 *      184..191 header size in bytes ("768" for 2 signals)
 *      192..235 reserved — "EDF+C" for continuous
 *      236..243 number of data records
 *      244..251 data-record duration in seconds ("1")
 *      252..255 number of signals ("2")
 *  - 2 x 256-byte signal headers (label, transducer, physdim, physmin/max,
 *    digmin/max, prefilter, samples-per-record — all 16*8+80 = 256 bytes).
 *  - data records of 1 s: IBI channel (1 int16 LE sample, step-held
 *    tachogram digitized from [PHYS_MIN_MS, PHYS_MAX_MS] onto
 *    [-32768, 32767]) followed by the "EDF Annotations" channel
 *    (ANNOT_BYTES bytes per record).
 *
 * Annotations are emitted as EDF+ time-stamped annotation lists (TAL):
 * for each annotation falling in data record r, the TAL bytes are
 * `onset(ASCII) 0x20 0x14 text 0x00` with onset = seconds since file start.
 * Stage annotations are written by callers at 30 s epoch boundaries
 * (e.g. "stage=Wake"); each record's annotation block is null-padded.
 *
 * Pure java.io — byte layout is directly unit-testable on the JVM.
 */
object EdfPlusWriter {

    const val PHYS_MIN_MS = 300.0
    const val PHYS_MAX_MS = 2200.0
    const val DIG_MIN = -32768.0
    const val DIG_MAX = 32767.0
    const val ANNOT_BYTES = 40
    const val ANNOT_SAMPLES = ANNOT_BYTES / 2 // 20 int16 samples

    private val DATE = DateTimeFormatter.ofPattern("dd.MM.yy")
    private val TIME = DateTimeFormatter.ofPattern("HH.mm.ss")

    /** One IBI sample: onset (s from file start) and interval in ms. */
    data class Ibi(val tSec: Double, val intervalMs: Int)

    /** One annotation: onset (s from file start) and free text. */
    data class Annot(val onsetSec: Double, val text: String)

    fun write(
        out: OutputStream,
        patientId: String,
        recordingId: String,
        start: LocalDateTime,
        ibis: List<Ibi>,
        annotations: List<Annot>,
        durationSec: Long,
    ) {
        val nRecords = durationSec.coerceAtLeast(1L)
        writeHeader(out, patientId, recordingId, start, nRecords)

        val sortedIbis = ibis.sortedBy { it.tSec }
        val byRecord = annotations.groupBy { floor(it.onsetSec).toLong().coerceIn(0, nRecords - 1) }

        var heldMs = PHYS_MIN_MS
        var ibiIdx = 0
        for (r in 0 until nRecords) {
            while (ibiIdx < sortedIbis.size && sortedIbis[ibiIdx].tSec < r + 1) {
                heldMs = sortedIbis[ibiIdx].intervalMs.toDouble()
                ibiIdx++
            }
            writeInt16(out, digitize(heldMs))
            writeAnnotations(out, byRecord[r].orEmpty())
        }
        out.flush()
    }

    fun digitize(physicalMs: Double): Int {
        val clipped = physicalMs.coerceIn(PHYS_MIN_MS, PHYS_MAX_MS)
        val v = (clipped - PHYS_MIN_MS) / (PHYS_MAX_MS - PHYS_MIN_MS) *
            (DIG_MAX - DIG_MIN) + DIG_MIN
        return v.roundToInt().coerceIn(DIG_MIN.toInt(), DIG_MAX.toInt())
    }

    fun undigitize(digital: Int): Double =
        (digital - DIG_MIN) / (DIG_MAX - DIG_MIN) *
            (PHYS_MAX_MS - PHYS_MIN_MS) + PHYS_MIN_MS

    private fun writeHeader(
        out: OutputStream,
        patientId: String,
        recordingId: String,
        start: LocalDateTime,
        nRecords: Long,
    ) {
        val headerSize = 256 * 3
        text(out, "0", 8)
        text(out, patientId, 80)
        text(out, recordingId, 80)
        text(out, start.format(DATE), 8)
        text(out, start.format(TIME), 8)
        num(out, headerSize, 8)
        text(out, "EDF+C", 44)
        num(out, nRecords, 8)
        num(out, 1, 8) // data record duration: 1 s
        num(out, 2, 4) // signals: IBI + annotations

        // Signal 1: IBI tachogram
        text(out, "IBI_ms", 16)
        text(out, "Inter-beat interval", 80)
        text(out, "ms", 8)
        num(out, PHYS_MIN_MS.toInt(), 8)
        num(out, PHYS_MAX_MS.toInt(), 8)
        num(out, DIG_MIN.toInt(), 8)
        num(out, DIG_MAX.toInt(), 8)
        text(out, "", 80)
        num(out, 1, 8) // 1 sample / record (1 Hz tachogram)
        text(out, "", 32) // reserved

        // Signal 2: EDF+ annotations channel
        text(out, "EDF Annotations", 16)
        text(out, "", 80)
        text(out, "", 8)
        num(out, -1, 8)
        num(out, -1, 8)
        num(out, -1, 8)
        num(out, -1, 8)
        text(out, "", 80)
        num(out, ANNOT_SAMPLES, 8)
        text(out, "", 32) // reserved
    }

    private fun writeAnnotations(out: OutputStream, anns: List<Annot>) {
        val buf = ByteArray(ANNOT_BYTES)
        var pos = 0
        for (a in anns) {
            val tal = ByteArrayOutputStream().apply {
                write(formatOnset(a.onsetSec).toByteArray(Charsets.US_ASCII))
                write(0x20); write(0x14)
                write(a.text.toByteArray(Charsets.US_ASCII))
                write(0x00)
            }.toByteArray()
            val room = buf.size - pos
            if (tal.size > room) break
            System.arraycopy(tal, 0, buf, pos, tal.size)
            pos += tal.size
        }
        out.write(buf)
    }

    private fun formatOnset(onsetSec: Double): String {
        val rounded = (onsetSec * 10).roundToInt() / 10.0
        return if (rounded == rounded.toLong().toDouble()) {
            rounded.toLong().toString()
        } else {
            rounded.toString()
        }
    }

    private fun writeInt16(out: OutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    private fun text(out: OutputStream, s: String, len: Int) {
        val b = s.toByteArray(Charsets.US_ASCII)
        require(b.size <= len) { "EDF field overflow: \"$s\" > $len bytes" }
        out.write(b)
        repeat(len - b.size) { out.write(' '.code) }
    }

    /** Numeric EDF header fields: ASCII, right-justified, space padded. */
    private fun num(out: OutputStream, v: Number, len: Int) {
        val s = v.toLong().toString()
        require(s.length <= len) { "EDF numeric field overflow: $s > $len" }
        repeat(len - s.length) { out.write(' '.code) }
        out.write(s.toByteArray(Charsets.US_ASCII))
    }

}
