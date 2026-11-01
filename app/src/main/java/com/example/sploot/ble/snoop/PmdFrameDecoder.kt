package com.example.sploot.ble.snoop

import com.example.sploot.ble.AccSample
import com.example.sploot.ble.EcgSample
import com.example.sploot.ble.HrSample
import com.example.sploot.ble.IbiSample
import com.example.sploot.ble.IbiSource
import com.example.sploot.ble.RawSample
import java.util.UUID

/**
 * Decodes Polar PMD notification payloads and standard GATT characteristics into
 * [RawSample]s, so the snoop pipeline emits exactly the same types as Mode D
 * (live Polar SDK ingestion).
 *
 * ## PMD data frame layout (verified against polar-ble-sdk 6.7.0 bytecode:
 * `com.polar.androidcommunications...pmd.PmdDataFrame`, `BlePMDClient.parseDeltaFramesAll`,
 * `AccData`, `EcgData`, `PpiData`, `PmdTimeStampUtils`)
 *
 * ```
 * byte 0      measurement type id (0x00 ECG, 0x01 PPG, 0x02 ACC, 0x03 PPI)
 * bytes 1..8  u64 LE frame timestamp in NANOSECONDS (epoch ns); the timestamp marks
 *             the LAST sample of the frame; earlier samples are extrapolated backwards
 * byte 9      frame type byte: bit7 set = compressed (delta) frame, low 7 bits = type index
 * bytes 10..  data content
 * ```
 *
 * Note: the plan text ("frame type 0x008913 / 0x008881 / 0x008886") described the raw
 * ATT value seen on the wire in some captures; the authoritative layout above (replicated
 * from the SDK parser) is what production sensors emit and what this decoder accepts.
 *
 * ### PPI (measurement 3, raw type 0): 6-byte samples
 * `[hr u8][ppInterval u16 LE, ms][ppErrorEstimate u16 LE, 1/8 ms][flags u8]`
 * flags: bit0 = blockerBit, bit1 = skinContactStatus, bit2 = skinContactSupported.
 * (The plan's guess of a u16 pp + respiration byte was superseded by the SDK layout.)
 *
 * ### ECG (measurement 0, raw type 0): i24 LE samples at 130 Hz, factor-scaled to µV.
 *
 * ### ACC (measurement 2)
 *  - raw type 0: 3 × i24 LE per sample; raw type 1: 3 × i16 LE per sample.
 *  - compressed type 0 (delta): 3 signed 16-bit LE reference samples followed by blocks.
 *    Each block: `[u8 a][u8 b]` — total delta bits = a × b × channels — then
 *    ceil(bits/8) bytes of 8-bit signed per-channel deltas (LSB-first bit order),
 *    accumulated channel-wise onto the previous sample set (exact SDK algorithm).
 *    milli-g = round(raw × factor × 1000) where factor = rangeG / 32768.
 *
 * ACC/ECG `factor` is negotiated over the PMD control point and is not present in the
 * notification itself; the snoop pipeline uses [accRangeG] (default ±2 g) and an ECG
 * factor of 1.0 (H10 streams µV directly). Assumption documented here; override the
 * constructor parameters if a capture used a different range.
 */
class PmdFrameDecoder(
    private val accRangeG: Int = 2,
    private val ecgFactor: Int = 1,
) {

    /** Per-stream state (previous frame ns timestamp) for sample-time extrapolation. */
    private val prevFrameNs = HashMap<Int, Long>()

    var hrSamples = 0; private set
    var ibiSamples = 0; private set
    var accSamples = 0; private set
    var ecgSamples = 0; private set
    var unknownFrames = 0; private set

    /**
     * Demultiplexes one notification. [uuid] selects the characteristic;
     * [recordTimestampMs] (btsnoop capture time) is used as fallback when the payload
     * carries no usable embedded timestamp.
     */
    fun decode(uuid: UUID, recordTimestampMs: Long, value: ByteArray, emit: (RawSample) -> Unit) {
        when (uuid) {
            UUID_PMD_DATA -> decodePmd(value, recordTimestampMs, emit)
            UUID_HR_MEASUREMENT -> decodeHrMeasurement(recordTimestampMs, value, emit)
            else -> unknownFrames++
        }
    }

    // ------------------------------------------------------------------ standard HR service

    /** 0x2A37: flags u8; bit0 = HR is u16, bit4 = RR intervals present (u16 each, 1/1024 s). */
    private fun decodeHrMeasurement(ts: Long, value: ByteArray, emit: (RawSample) -> Unit) {
        if (value.isEmpty()) return
        val flags = value[0].toInt()
        val hr16 = flags and 0x01 != 0
        var off = 1
        if (hr16 && value.size >= 3) {
            emit(HrSample(ts, AttGattDecoder.u16le(value, 1))); off = 3
        } else if (value.size >= 2) {
            emit(HrSample(ts, value[1].toInt() and 0xFF)); off = 2
        } else return
        hrSamples++
        if (flags and 0x10 != 0) {
            var t = ts
            while (off + 2 <= value.size) {
                val raw = AttGattDecoder.u16le(value, off)
                val rrMs = (raw * 1000.0 / 1024.0).toInt()
                t += rrMs
                emit(IbiSample(t, rrMs, IbiSource.HR_SERVICE))
                ibiSamples++
                off += 2
            }
        }
    }

    // ------------------------------------------------------------------ Polar PMD

    private fun decodePmd(value: ByteArray, recordTsMs: Long, emit: (RawSample) -> Unit) {
        if (value.size < 10) { unknownFrames++; return }
        val measurement = value[0].toInt() and 0x3F
        val frameNs = readU64le(value, 1)
        val frameType = value[9].toInt() and 0xFF
        val compressed = frameType and 0x80 != 0
        val typeIdx = frameType and 0x7F
        val content = value.copyOfRange(10, value.size)
        // Sanity: PMD timestamps are epoch ns (~1.5e18). Otherwise fall back to capture time.
        val tsMs = if (frameNs > 1_000_000_000_000_000_000L) frameNs / 1_000_000 else recordTsMs
        when (measurement) {
            MEAS_PPI -> if (!compressed && typeIdx == 0) decodePpi(tsMs, content, emit) else unknownFrames++
            MEAS_ECG -> if (!compressed && typeIdx == 0) decodeEcg(measurement, frameNs, tsMs, content, emit) else unknownFrames++
            MEAS_ACC -> decodeAcc(measurement, frameNs, tsMs, content, compressed, typeIdx, emit)
            else -> unknownFrames++
        }
    }

    private fun decodePpi(tsMs: Long, content: ByteArray, emit: (RawSample) -> Unit) {
        if (content.size < 6) { unknownFrames++; return }
        val samples = ArrayList<Triple<Int, Boolean, Boolean>>(content.size / 6) // (ppMs, blocker, skin)
        var off = 0
        while (off + 6 <= content.size) {
            val pp = AttGattDecoder.u16le(content, off + 1)
            val flags = content[off + 5].toInt() and 0xFF
            samples.add(Triple(pp, flags and 1 != 0, flags and 2 != 0))
            off += 6
        }
        if (samples.isEmpty()) { unknownFrames++; return }
        // SDK: frame timestamp marks the last sample; walk backwards by pp intervals.
        var t = tsMs
        for (i in samples.indices.reversed()) {
            val (pp, blocker, skin) = samples[i]
            emit(
                IbiSample(
                    timestampMs = t,
                    intervalMs = pp,
                    source = IbiSource.PPI,
                    blockerBit = blocker,
                    skinContact = skin,
                    error = null,
                )
            )
            ibiSamples++
            t -= pp
        }
    }

    private fun decodeEcg(meas: Int, frameNs: Long, tsMs: Long, content: ByteArray, emit: (RawSample) -> Unit) {
        val n = content.size / 3
        if (n == 0) { unknownFrames++; return }
        val firstTs = extrapolateFirstMs(meas, frameNs, n)
        val step = stepMs(meas, n, frameNs, tsMs).coerceAtLeast(1L)
        for (i in 0 until n) {
            val v = i24le(content, i * 3) * ecgFactor
            emit(EcgSample(firstTs + i * step, v))
            ecgSamples++
        }
        prevFrameNs[meas] = frameNs
    }

    private fun decodeAcc(meas: Int, frameNs: Long, tsMs: Long, content: ByteArray, compressed: Boolean, typeIdx: Int, emit: (RawSample) -> Unit) {
        val frames: List<IntArray> = when {
            !compressed && typeIdx == 0 -> triples24(content)
            !compressed && typeIdx == 1 -> triples16(content)
            compressed && typeIdx == 0 -> deltaDecode3ch16(content)
            else -> { unknownFrames++; return }
        }
        if (frames.isEmpty()) { unknownFrames++; return }
        val n = frames.size
        val scale = accRangeG * 1000.0 / 32768.0 // milli-g per LSB (16-bit reference scale)
        val firstTs = extrapolateFirstMs(meas, frameNs, n)
        val step = stepMs(meas, n, frameNs, tsMs)
        for (i in 0 until n) {
            val f = frames[i]
            emit(
                AccSample(
                    timestampMs = firstTs + i * step,
                    x = Math.round(f[0] * scale).toInt(),
                    y = Math.round(f[1] * scale).toInt(),
                    z = Math.round(f[2] * scale).toInt(),
                    sampleRate = 52,
                )
            )
            accSamples++
        }
        prevFrameNs[meas] = frameNs
    }

    // ------------------------------------------------------------------ timestamps

    /**
     * SDK semantics: the frame timestamp belongs to the LAST sample. When a previous
     * frame exists, the inter-sample step is (ts - prevTs)/n, else 1/sampleRate.
     * Returns the first sample's time in ms.
     */
    private fun extrapolateFirstMs(meas: Int, frameNs: Long, n: Int): Long =
        frameNs / 1_000_000 - stepMs(meas, n, frameNs, 0) * (n - 1)

    private fun stepMs(meas: Int, n: Int, frameNs: Long, fallbackMs: Long): Long {
        val prev = prevFrameNs[meas] ?: return 1000L / if (meas == MEAS_ACC) 52 else 130
        val d = frameNs - prev
        return if (d > 0 && n > 0) d / 1_000_000 / n else 1000L / if (meas == MEAS_ACC) 52 else 130
    }

    private fun ecgStepMs(): Long = 1000L / 130

    // ------------------------------------------------------------------ raw sample readers

    private fun triples24(c: ByteArray): List<IntArray> {
        val out = ArrayList<IntArray>(c.size / 9)
        var off = 0
        while (off + 9 <= c.size) {
            out.add(intArrayOf(i24le(c, off), i24le(c, off + 3), i24le(c, off + 6)))
            off += 9
        }
        return out
    }

    private fun triples16(c: ByteArray): List<IntArray> {
        val out = ArrayList<IntArray>(c.size / 6)
        var off = 0
        while (off + 6 <= c.size) {
            out.add(intArrayOf(i16le(c, off), i16le(c, off + 2), i16le(c, off + 4)))
            off += 6
        }
        return out
    }

    /**
     * Polar delta decompression, byte-for-byte equivalent to the SDK
     * `BlePMDClient.parseDeltaFramesAll(value, channels=3, resolution=16, SIGNED_INT)`:
     *
     *  1. `channels` (=3) signed 16-bit LE absolute reference samples (2 bytes each).
     *  2. Repeated blocks: `[u8 a][u8 b]` header, delta bit count = a × b × channels;
     *     followed by ceil(bits/8) bytes holding 8-bit two's-complement per-channel
     *     deltas, packed LSB-first, one group of 3 deltas per sample; each group is
     *     accumulated channel-wise onto the previous sample.
     */
    fun deltaDecode3ch16(content: ByteArray): List<IntArray> {
        if (content.size < 6) return emptyList()
        val channels = 3
        var off = 0
        val frames = ArrayList<IntArray>()
        val prev = IntArray(channels)
        for (ch in 0 until channels) {
            prev[ch] = i16le(content, ch * 2)
        }
        frames.add(prev.copyOf())
        off = channels * 2
        while (off + 2 <= content.size) {
            val a = content[off].toInt() and 0xFF
            val b = content[off + 1].toInt() and 0xFF
            off += 2
            val bits = a * b * channels
            if (bits <= 0) break
            val bytes = (bits + 7) / 8
            if (off + bytes > content.size) break
            var bit = 0
            var cur = prev.copyOf()
            while (bit + channels * 8 <= bits) {
                for (ch in 0 until channels) {
                    val d = content[off + (bit / 8)].toInt() // 8-bit aligned: one byte per delta
                    cur[ch] += d
                    bit += 8
                }
                frames.add(cur.copyOf())
            }
            off += bytes
            for (ch in 0 until channels) prev[ch] = cur[ch]
        }
        return frames
    }

    // ------------------------------------------------------------------ little-endian readers

    private fun readU64le(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }

    private fun i16le(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF or (b[o + 1].toInt() shl 8)

    private fun i24le(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o + 2].toInt() shl 16)

    companion object {
        const val MEAS_ECG = 0x00
        const val MEAS_PPG = 0x01
        const val MEAS_ACC = 0x02
        const val MEAS_PPI = 0x03

        /** Polar PMD Data characteristic (notifications). */
        val UUID_PMD_DATA: UUID = UUID.fromString("FB005C82-02E7-F387-1CAD-8ACD2D8DF0C8")

        /** Polar PMD Control point (not decoded here; kept for reference/docs). */
        val UUID_PMD_CONTROL: UUID = UUID.fromString("FB005C81-02E7-F387-1CAD-8ACD2D8DF0C8")

        /** Standard Heart Rate Measurement 0x2A37. */
        val UUID_HR_MEASUREMENT: UUID = UUID.fromString("00002A37-0000-1000-8000-00805F9B34FB")

        /** Standard Battery Level 0x2A19. */
        val UUID_BATTERY: UUID = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")
    }
}
