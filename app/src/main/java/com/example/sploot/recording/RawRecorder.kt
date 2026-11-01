package com.example.sploot.recording

import com.example.sploot.ble.AccSample
import com.example.sploot.ble.EcgSample
import com.example.sploot.ble.HrSample
import com.example.sploot.ble.IbiSample
import com.example.sploot.ble.RawSample
import com.example.sploot.ble.SensorStatus
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Crash-safe chunked raw logger.
 *
 * File format (`filesDir/sessions/<sessionId>/raw.bin`):
 * ```
 * Magic header (12 bytes):
 *   bytes 0..3   0x53505231 ("SPR1")
 *   bytes 4..11  session start epoch ms (Java big endian)
 * Then a sequence of length-prefixed frames:
 *   type:u8 | timestampMs:i64 | payloadLen:u16 | payload bytes
 * ```
 * The last frame may be truncated after a process kill — readers skip an
 * incomplete tail frame via the length prefix.
 *
 * A companion `index.json` is rewritten on every flush with counters and
 * timing so post-processing can validate the session without scanning.
 *
 * Frames are appended to an in-memory buffer and flushed (fsync) at least
 * every [flushIntervalMs] (default 60 s) and on [close] — worst-case data
 * loss on process kill is bounded by one flush window.
 */
class RawRecorder(
    private val sessionDir: File,
    private val sessionId: String,
    private val flushIntervalMs: Long = DEFAULT_FLUSH_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    companion object {
        const val MAGIC = 0x53505231 // "SPR1"
        const val HEADER_SIZE = 12
        const val RAW_FILE_NAME = "raw.bin"
        const val INDEX_FILE_NAME = "index.json"
        const val DEFAULT_FLUSH_INTERVAL_MS = 60_000L

        // Frame type codes
        const val TYPE_HR: Int = 0x01
        const val TYPE_IBI: Int = 0x02
        const val TYPE_ACC: Int = 0x03
        const val TYPE_ECG: Int = 0x04
        const val TYPE_STATUS: Int = 0x05
    }

    @Serializable
    data class SessionIndex(
        val sessionId: String,
        val startedAtMs: Long,
        val lastFlushMs: Long?,
        val endedAtMs: Long?,
        val framesWritten: Long,
        val bytesWritten: Long,
        val counters: Counters,
    )

    @Serializable
    data class Counters(
        val hr: Long = 0,
        val ibi: Long = 0,
        val acc: Long = 0,
        val ecg: Long = 0,
        val status: Long = 0,
    )

    data class Stats(
        val framesWritten: Long,
        val bytesWritten: Long,
        val counters: Counters,
        val pendingBytes: Int,
        val lastFlushMs: Long?,
    )

    /** Decoded frame tuple used by readers/tests. */
    data class DecodedFrame(
        val type: Int,
        val timestampMs: Long,
        val payload: ByteArray,
    )

    private val json = Json { prettyPrint = true }
    private val rawFile = File(sessionDir, RAW_FILE_NAME)
    private val indexFile = File(sessionDir, INDEX_FILE_NAME)

    private var startedAtMs: Long = 0
    private var lastFlushMs: Long? = null
    private var framesWritten = 0L
    private var bytesWritten = 0L
    private var counters = Counters()
    private val buffer = ByteArrayOutputStream(256 * 1024)
    private var output: FileOutputStream? = null
    private var closed = false
    private var flushJob: Job? = null

    /** Creates the session dir + file header; starts the periodic flusher. */
    @Synchronized
    fun start(scope: CoroutineScope? = null) {
        check(!closed) { "recorder already closed" }
        if (output != null) return
        sessionDir.mkdirs()
        startedAtMs = clock()
        val out = FileOutputStream(rawFile)
        val header = ByteArrayOutputStream(HEADER_SIZE).let { bo ->
            val d = DataOutputStream(bo)
            d.writeInt(MAGIC)
            d.writeLong(startedAtMs)
            bo.toByteArray()
        }
        out.write(header)
        out.fd.sync()
        output = out
        bytesWritten = header.size.toLong()
        writeIndex()
        if (scope != null) {
            flushJob = scope.launch {
                while (!closed) {
                    delay(flushIntervalMs)
                    flush()
                }
            }
        }
    }

    /** Encodes and buffers one sample. Synchronous, cheap, no I/O. */
    @Synchronized
    fun append(sample: RawSample) {
        if (closed) return
        buffer.write(encodeFrame(sample, clock()))
        framesWritten++
        counters = when (sample) {
            is HrSample -> counters.copy(hr = counters.hr + 1)
            is IbiSample -> counters.copy(ibi = counters.ibi + 1)
            is AccSample -> counters.copy(acc = counters.acc + 1)
            is EcgSample -> counters.copy(ecg = counters.ecg + 1)
        }
    }

    /** Status frames record battery/connection transitions. */
    @Synchronized
    fun appendStatus(status: SensorStatus) {
        if (closed) return
        val bo = ByteArrayOutputStream(2)
        val d = DataOutputStream(bo)
        d.writeByte(status.batteryPercent ?: -1)
        d.writeByte(status.connectionState.ordinal)
        buffer.write(frame(TYPE_STATUS, clock(), bo.toByteArray()))
        framesWritten++
        counters = counters.copy(status = counters.status + 1)
    }

    /** Writes buffered frames + fsync + rewrites index. Safe to call anytime. */
    @Synchronized
    fun flush() {
        val out = output ?: return
        if (buffer.size() > 0) {
            out.write(buffer.toByteArray())
            out.fd.sync()
            buffer.reset()
        }
        lastFlushMs = clock()
        bytesWritten = rawFile.length()
        writeIndex()
    }

    /** Final flush + close. Tolerates double-close. */
    @Synchronized
    fun close() {
        if (closed) return
        flushJob?.cancel()
        try {
            flush()
        } finally {
            runCatching { output?.close() }
            output = null
            closed = true
            writeIndex(includeEnd = true)
        }
    }

    @Synchronized
    fun stats(): Stats = Stats(
        framesWritten = framesWritten,
        bytesWritten = bytesWritten,
        counters = counters,
        pendingBytes = buffer.size(),
        lastFlushMs = lastFlushMs,
    )

    private fun writeIndex(includeEnd: Boolean = false) {
        val idx = SessionIndex(
            sessionId = sessionId,
            startedAtMs = startedAtMs,
            lastFlushMs = lastFlushMs,
            endedAtMs = if (includeEnd) clock() else null,
            framesWritten = framesWritten,
            bytesWritten = bytesWritten,
            counters = counters,
        )
        runCatching { indexFile.writeText(json.encodeToString(idx)) }
    }

    // ---- Frame codec ----

    internal fun frame(type: Int, timestampMs: Long, payload: ByteArray): ByteArray =
        ByteArrayOutputStream(payload.size + 11).let { bo ->
            val d = DataOutputStream(bo)
            d.writeByte(type)
            d.writeLong(timestampMs)
            d.writeShort(payload.size)
            d.write(payload)
            bo.toByteArray()
        }

    internal fun encodeFrame(sample: RawSample, nowMs: Long): ByteArray {
        val payload: ByteArray = ByteArrayOutputStream().let { bo ->
            val d = DataOutputStream(bo)
            when (sample) {
                is HrSample -> d.writeShort(sample.hr)
                is IbiSample -> {
                    d.writeShort(sample.intervalMs)
                    d.writeByte(sample.source.ordinal)
                    d.writeByte(sample.blockerBit.asTriState())
                    d.writeByte(sample.skinContact.asTriState())
                    d.writeShort(sample.error ?: -1)
                }
                is AccSample -> {
                    d.writeShort(sample.sampleRate)
                    d.writeShort(sample.x)
                    d.writeShort(sample.y)
                    d.writeShort(sample.z)
                }
                is EcgSample -> d.writeShort(sample.microVolts)
            }
            bo.toByteArray()
        }
        val type = when (sample) {
            is HrSample -> TYPE_HR
            is IbiSample -> TYPE_IBI
            is AccSample -> TYPE_ACC
            is EcgSample -> TYPE_ECG
        }
        return frame(type, nowMs, payload)
    }

    private fun Boolean?.asTriState(): Int = when (this) {
        null -> 0
        false -> 1
        true -> 2
    }

    /** Parses the full file (minus header), skipping a truncated tail frame. */
    fun readFrames(): List<DecodedFrame> {
        if (!rawFile.exists()) return emptyList()
        val bytes = rawFile.readBytes()
        if (bytes.size < HEADER_SIZE) return emptyList()
        val frames = mutableListOf<DecodedFrame>()
        var off = HEADER_SIZE
        while (off + 11 <= bytes.size) {
            val type = bytes[off].toInt() and 0xFF
            var p = off + 1
            var ts = 0L
            for (i in 0 until 8) ts = (ts shl 8) or (bytes[p + i].toLong() and 0xFF)
            p += 8
            val len = ((bytes[p].toInt() and 0xFF) shl 8) or (bytes[p + 1].toInt() and 0xFF)
            p += 2
            if (p + len > bytes.size) break // truncated tail after crash
            frames.add(DecodedFrame(type, ts, bytes.copyOfRange(p, p + len)))
            off = p + len
        }
        return frames
    }

    /** Reads the session start timestamp from the file header. */
    fun readHeaderTimestamp(): Long? {
        if (!rawFile.exists()) return null
        val bytes = rawFile.readBytes()
        if (bytes.size < HEADER_SIZE) return null
        var ts = 0L
        for (i in 4 until 12) ts = (ts shl 8) or (bytes[i].toLong() and 0xFF)
        return ts
    }
}
