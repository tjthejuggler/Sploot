package com.example.sploot.ble

/**
 * Unified raw telemetry contract shared by every ingestion mode.
 *
 * Mode D (direct Polar SDK, [PolarBleClient]) and Mode C (post-night btsnoop
 * parser, Phase 5) both emit instances of this hierarchy so the downstream
 * signal pipeline is mode-agnostic.
 *
 * Timestamps are wall-clock epoch milliseconds ([System.currentTimeMillis]).
 * Polar PMD timestamps are converted to epoch ms at ingestion time.
 */
sealed interface RawSample {
    val timestampMs: Long
}

/** Heart rate in beats per minute. */
data class HrSample(
    override val timestampMs: Long,
    val hr: Int,
) : RawSample

/** Source of an inter-beat interval. */
enum class IbiSource { PPI, RR, HR_SERVICE }

/**
 * One inter-beat (inter-pulse) interval in milliseconds.
 *
 * @param intervalMs IBI in ms (PPI or RR depending on [source]).
 * @param blockerBit Polar PPI artefact flag (true = sample contaminated).
 * @param skinContact Skin contact status when reported (PPI / HR service).
 * @param error Polar PPI error estimate in ms (1/8 ms units already resolved).
 */
data class IbiSample(
    override val timestampMs: Long,
    val intervalMs: Int,
    val source: IbiSource,
    val blockerBit: Boolean? = null,
    val skinContact: Boolean? = null,
    val error: Int? = null,
) : RawSample

/**
 * One 3-axis accelerometer sample. x/y/z in milli-g (Polar ACC raw units).
 *
 * @param sampleRate Effective sample rate in Hz of the owning stream.
 */
data class AccSample(
    override val timestampMs: Long,
    val x: Int,
    val y: Int,
    val z: Int,
    val sampleRate: Int,
) : RawSample

/** One raw ECG sample in microvolts (H10 PMD ECG stream, typically 130 Hz). */
data class EcgSample(
    override val timestampMs: Long,
    val microVolts: Int,
) : RawSample

/** Device-level status: battery + connection lifecycle. */
data class SensorStatus(
    val batteryPercent: Int?,
    val connectionState: ConnectionState,
) {
    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }
}
