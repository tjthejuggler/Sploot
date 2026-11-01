package com.example.sploot.features

import com.example.sploot.ble.AccSample
import kotlin.math.abs
import kotlin.math.sqrt

/** Per-epoch actigraphy aggregates (milli-g units unless stated). */
data class ActigraphyMeasures(
    /** Mean of the raw vector magnitude (milli-g). */
    val meanMag: Double,
    /** Variance of the vector magnitude (milli-g²). */
    val varMag: Double,
    /** Median absolute deviation of the vector magnitude (milli-g). */
    val mad: Double,
    /** Mean ENMO: euclidean norm minus one g, clamped ≥ 0 (milli-g). */
    val enmo: Double,
    /** True when a movement epoch was detected (ENMO > threshold). */
    val movement: Boolean,
)

/**
 * Per-30 s actigraphy features from tri-axial accelerometer samples.
 *
 * - **ENMO** (Euclidean Norm Minus One): per-sample |a| − 1 g, clamped ≥ 0,
 *   averaged over the epoch and expressed in milli-g. Negative part
 *   (dominant when the device is motionless and noise pulls the norm below
 *   1 g) is truncated per the van Hees convention.
 * - **Movement epoch**: epoch-mean ENMO above [MOVEMENT_ENMO_THRESHOLD_MG]
 *   = 22 milli-g, a value commonly used in the actigraphy sleep literature
 *   to separate movement from resting epochs.
 * - **Time since last movement** is a rolling cross-epoch measure computed by
 *   [timeSinceLastMovement] over the night's ENMO sequence.
 *
 * Pure Kotlin, no Android dependencies.
 */
object ActigraphyFeatures {
    const val MOVEMENT_ENMO_THRESHOLD_MG = 22.0

    /** Milliseconds in one 30 s epoch (for the time-since-movement conversion). */
    const val EPOCH_MS = 30_000.0

    fun compute(samples: List<AccSample>): ActigraphyMeasures {
        if (samples.isEmpty()) {
            return ActigraphyMeasures(Double.NaN, Double.NaN, Double.NaN, Double.NaN, false)
        }
        val mag = DoubleArray(samples.size) {
            val s = samples[it]
            sqrt((s.x * s.x + s.y * s.y + s.z * s.z).toDouble())
        }
        val mean = mag.average()
        val variance = mag.sumOf { (it - mean) * (it - mean) } / mag.size
        val med = median(mag)
        val mad = mag.map { abs(it - med) }.let { median(it.toDoubleArray()) }
        // ENMO: |a| − 1000 milli-g, clamped ≥ 0, epoch mean.
        val enmo = mag.sumOf { (it - 1000.0).coerceAtLeast(0.0) } / mag.size
        return ActigraphyMeasures(
            meanMag = mean,
            varMag = variance,
            mad = mad,
            enmo = enmo,
            movement = enmo > MOVEMENT_ENMO_THRESHOLD_MG,
        )
    }

    /**
     * Rolling time since the last movement epoch, in epochs (unit = one 30 s
     * epoch). 0 when the current epoch is a movement epoch; NaN when no
     * movement has been seen yet since recording start (models treat NaN via
     * the normalizer / imputer).
     */
    fun timeSinceLastMovement(enmoPerEpoch: DoubleArray, epochIndex: Int): Double {
        require(epochIndex in enmoPerEpoch.indices) { "epoch index out of range" }
        if (enmoPerEpoch[epochIndex] > MOVEMENT_ENMO_THRESHOLD_MG) return 0.0
        var i = epochIndex - 1
        while (i >= 0) {
            if (enmoPerEpoch[i] > MOVEMENT_ENMO_THRESHOLD_MG) return (epochIndex - i).toDouble()
            i--
        }
        return Double.NaN
    }

    private fun median(v: DoubleArray): Double {
        val s = v.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
    }
}
