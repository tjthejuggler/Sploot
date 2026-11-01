package com.example.sploot.signal

/**
 * Uniformly sampled tachogram for frequency-domain analysis.
 *
 * @property values interpolated IBI values at [sampleRateHz]; NaN marks gap
 *   regions that were NOT interpolated across.
 * @property valid mask parallel to [values] (false inside gaps).
 */
class Tachogram(val values: DoubleArray, val sampleRateHz: Double, val valid: BooleanArray) {
    val validRatio: Double
        get() = if (values.isEmpty()) 0.0 else valid.count { it } / values.size.toDouble()
}

/**
 * Produces a uniform tachogram from a cleaned beat series using monotone
 * piecewise-cubic Hermite interpolation (PCHIP, Fritsch–Carlson). PCHIP is
 * preferred over a natural cubic spline because it does not overshoot on the
 * steep flanks typical of ectopic-adjacent intervals.
 *
 * Gap handling: when more than [gapBeats] beats (~2) are missing between two
 * consecutive accepted beats (interval between beat times > [gapBeats] + 1
 * nominal beat periods), the region is left as NaN instead of being bridged,
 * and the epoch's validity flag degrades accordingly.
 *
 * Pure Kotlin, no Android dependencies.
 */
class Interpolator(
    val sampleRateHz: Double = 4.0,
    val gapBeats: Int = 2,
) {

    /**
     * Interpolate the beat series over [startMs, endMs].
     * Beat times are taken as the END of each interval (R-peak occurrence).
     */
    fun tachogram(beats: List<CleanBeat>, startMs: Long, endMs: Long): Tachogram {
        require(endMs > startMs) { "empty window" }
        val n = beats.size
        val total = ((endMs - startMs) / 1000.0 * sampleRateHz).toInt() + 1
        val out = DoubleArray(total) { Double.NaN }
        val validMask = BooleanArray(total)

        if (n < 2) return Tachogram(out, sampleRateHz, validMask)

        val t = DoubleArray(n) { (beats[it].timestampMs - startMs) / 1000.0 }
        val y = DoubleArray(n) { beats[it].ibiMs }
        val nominal = median(y)

        // Segment boundaries: a gap between beats i and i+1 longer than
        // (gapBeats + 1) nominal intervals means > gapBeats beats missing.
        val gapLimit = (gapBeats + 1) * nominal / 1000.0
        val segments = ArrayList<IntRange>()
        var segStart = 0
        for (i in 0 until n - 1) {
            if (t[i + 1] - t[i] > gapLimit) {
                if (i > segStart) segments += segStart..i
                segStart = i + 1
            }
        }
        if (n - 1 > segStart) segments += segStart..(n - 1)

        for (seg in segments) {
            val ts = t.sliceArray(seg)
            val ys = y.sliceArray(seg)
            val h = pchipSlopes(ts, ys)
            val lo = ((ts.first() * sampleRateHz).toInt()).coerceAtLeast(0)
            val hi = ((ts.last() * sampleRateHz).toInt()).coerceAtMost(total - 1)
            var k = 0 // segment pointer
            for (idx in lo..hi) {
                val x = idx / sampleRateHz
                while (k < ts.size - 2 && x > ts[k + 1]) k++
                val hSeg = ts[k + 1] - ts[k]
                if (hSeg <= 0) continue
                val s = (x - ts[k]) / hSeg
                out[idx] = hermite(ys[k], ys[k + 1], h[k], h[k + 1], hSeg, s)
                validMask[idx] = true
            }
        }
        return Tachogram(out, sampleRateHz, validMask)
    }

    /** Cubic Hermite basis evaluation. */
    private fun hermite(y0: Double, y1: Double, m0: Double, m1: Double, h: Double, s: Double): Double {
        val s2 = s * s
        val s3 = s2 * s
        val h00 = 2 * s3 - 3 * s2 + 1
        val h10 = s3 - 2 * s2 + s
        val h01 = -2 * s3 + 3 * s2
        val h11 = s3 - s2
        return h00 * y0 + h10 * h * m0 + h01 * y1 + h11 * h * m1
    }

    /** Fritsch–Carlson monotone slopes. */
    internal fun pchipSlopes(t: DoubleArray, y: DoubleArray): DoubleArray {
        val n = y.size
        if (n < 2) return DoubleArray(n)
        val d = DoubleArray(n - 1)
        for (i in 0 until n - 1) d[i] = (y[i + 1] - y[i]) / (t[i + 1] - t[i])

        val m = DoubleArray(n)
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) {
            if (d[i - 1] * d[i] <= 0) {
                m[i] = 0.0
            } else {
                val w1 = 2 * (t[i + 1] - t[i]) + (t[i] - t[i - 1])
                val w2 = (t[i + 1] - t[i]) + 2 * (t[i] - t[i - 1])
                m[i] = (w1 + w2) / (w1 / d[i - 1] + w2 / d[i])
            }
        }
        return m
    }

    private fun median(v: DoubleArray): Double {
        val s = v.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
    }
}
