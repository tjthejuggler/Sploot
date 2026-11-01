package com.example.sploot.features

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Time-domain HRV measures. All functions are pure and operate on arrays of
 * IBI values in milliseconds; results are NaN when there are not enough beats.
 */
object HrvTimeDomain {

    /** Mean inter-beat interval (ms). */
    fun meanIbi(ibi: DoubleArray): Double =
        if (ibi.isEmpty()) Double.NaN else ibi.average()

    /** Standard deviation of NN intervals, population estimator (ms). */
    fun sdnn(ibi: DoubleArray): Double {
        if (ibi.size < 2) return Double.NaN
        val m = ibi.average()
        return sqrt(ibi.sumOf { (it - m) * (it - m) } / ibi.size)
    }

    /** Root mean square of successive differences (ms). */
    fun rmssd(ibi: DoubleArray): Double {
        if (ibi.size < 2) return Double.NaN
        var acc = 0.0
        for (i in 1 until ibi.size) {
            val d = ibi[i] - ibi[i - 1]
            acc += d * d
        }
        return sqrt(acc / (ibi.size - 1))
    }

    /** Standard deviation of successive differences (ms). Equals RMSSD's SD. */
    fun sdsd(ibi: DoubleArray): Double {
        if (ibi.size < 3) return Double.NaN
        val d = DoubleArray(ibi.size - 1) { i -> ibi[i + 1] - ibi[i] }
        val m = d.average()
        return sqrt(d.sumOf { (it - m) * (it - m) } / d.size)
    }

    /** Percentage of successive intervals differing by more than 50 ms (%). */
    fun pnn50(ibi: DoubleArray): Double {
        if (ibi.size < 2) return Double.NaN
        var count = 0
        for (i in 1 until ibi.size) {
            if (abs(ibi[i] - ibi[i - 1]) > 50.0) count++
        }
        return 100.0 * count / (ibi.size - 1)
    }

    /** Interquartile range of intervals (ms). */
    fun iqr(ibi: DoubleArray): Double {
        if (ibi.size < 4) return Double.NaN
        val q = quantile(ibi, 0.75) - quantile(ibi, 0.25)
        return q
    }

    /** Linear-interpolated quantile (type 7, R default). */
    internal fun quantile(x: DoubleArray, q: Double): Double {
        val s = x.sorted()
        val h = (s.size - 1) * q
        val lo = kotlin.math.floor(h).toInt()
        val hi = kotlin.math.ceil(h).toInt()
        return if (lo == hi) s[lo] else s[lo] + (h - lo) * (s[hi] - s[lo])
    }
}
