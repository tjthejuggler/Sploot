package com.example.sploot.ui.dream

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import kotlin.math.PI
import kotlin.math.sin

/**
 * DreamFx — the app's very quiet "is that moving?" layer.
 *
 * Everything here is intentionally near the threshold of perception:
 * slow Lissajous drifts around anchor points, colors that breathe over
 * minutes, and speeds that themselves drift so the motion sometimes
 * all but stops. Nothing is consequential; nothing should ever read as
 * a bug — only, on second glance, as something almost alive.
 *
 * All phase math is pure and deterministic in `t`, so any component can
 * sample the same dream time without coordination.
 */
object DreamFx {

    /** Deterministic hash → stable pseudo-random in [-1, 1) for a seed. */
    private fun noise(seed: Int): Float {
        var h = seed * 0x9E3779B9.toInt()
        h = (h xor (h ushr 16)) * 0x85EBCA6B.toInt()
        h = (h xor (h ushr 13)) * 0xC2B2AE35.toInt()
        h = h xor (h ushr 16)
        return ((h and 0xFFFFFF) / 16_777_216f) * 2f - 1f
    }

    /**
     * Slow multi-octave sine around zero for [channel] of [seed].
     * Frequencies use co-prime-ish periods (97 s / 53 s / 211 s) so the
     * combined pattern effectively never loops within a session.
     * `t` is in seconds.
     */
    private fun wave(t: Float, seed: Int, channel: Int): Float {
        val a = sin(t * (2f * PI.toFloat() / 97f) + noise(seed * 31 + channel) * PI.toFloat())
        val b = sin(t * (2f * PI.toFloat() / 53f) + noise(seed * 57 + channel) * PI.toFloat()) * 0.6f
        val c = sin(t * (2f * PI.toFloat() / 211f) + noise(seed * 83 + channel) * PI.toFloat()) * 0.9f
        return (a * 0.55f + b + c) / 2.05f
    }

    /**
     * A gentle drift offset for node/point [index] harnessed to its anchor.
     * [t] seconds, [ampPx] max displacement (keep small — 1..3 dp in px).
     */
    fun drift(t: Float, index: Int, ampPx: Float, seed: Int = 7): Offset =
        Offset(
            wave(t, seed + index, 0) * ampPx,
            wave(t, seed + index, 1) * ampPx * 0.8f,
        )

    /**
     * A -1..1 "breathing" value with a per-seed character; use it to
     * modulate alpha, hue, scale… by a few percent at most.
     */
    fun breathe(t: Float, seed: Int): Float = wave(t, seed, 2)

    /** Slowly alternating boolean — for dream signs that change their mind. */
    fun which(t: Float, seed: Int): Boolean = breathe(t, seed + 13) > 0f

    /**
     * Color that ever-so-slightly drifts toward a sibling hue and back.
     * [amount] ~0.04 keeps the shift below conscious notice.
     */
    fun colorDream(base: Color, other: Color, t: Float, seed: Int, amount: Float = 0.04f): Color {
        val f = ((breathe(t, seed) + 1f) * 0.5f) * amount
        return lerp(base, other, f)
    }

    /**
     * Hue that breathes ±[degrees] (default ~6° — felt, not seen) around
     * [base] on the dream clock. Alpha is preserved.
     */
    fun hueDrift(base: Color, t: Float, seed: Int, degrees: Float = 6f): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(base.toArgb(), hsv)
        hsv[0] = ((hsv[0] + breathe(t, seed) * degrees) % 360f + 360f) % 360f
        val argb = android.graphics.Color.HSVToColor(hsv)
        return Color(argb).copy(alpha = base.alpha)
    }
}

/**
 * Shared dream clock: a monotonically increasing time source whose *rate*
 * itself breathes — sometimes the world nearly holds still, sometimes it
 * drifts. Rate swings between ~0.2x and ~1.3x real time over ~4-minute
 * cycles, so drift speeds randomly morph the way dream time does.
 * Returns seconds of dream time.
 */
@Composable
fun rememberDreamTime(): State<Float> {
    var t by remember { mutableFloatStateOf(0f) }
    var lastFrame by remember { mutableFloatStateOf(-1f) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = withFrameNanos { it }
            if (lastFrame >= 0) {
                val dtReal = ((now - lastFrame.toLong()) / 1_000_000_000f).coerceIn(0f, 0.1f)
                // Rate breathes on a slow ~4-minute cycle, never quite zero.
                val rate = 0.75f + 0.55f * sin(t * (2f * PI.toFloat() / 239f))
                t += dtReal * rate
            }
            lastFrame = now.toFloat()
        }
    }
    return remember { object : State<Float> {
        override val value: Float get() = t
    } }
}
