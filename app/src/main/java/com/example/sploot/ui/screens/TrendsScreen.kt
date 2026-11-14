package com.example.sploot.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sploot.R
import com.example.sploot.SleepDates
import com.example.sploot.data.db.DailyBaselineEntity
import com.example.sploot.data.repo.AutonomicBaselines
import com.example.sploot.data.repo.SleepSummary
import com.example.sploot.di.AppContainer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.abs
import com.example.sploot.ui.components.NightCard
import com.example.sploot.ui.components.SectionTitle
import com.example.sploot.ui.dream.DreamFx
import com.example.sploot.ui.dream.rememberDreamTime
import com.example.sploot.ui.components.SimpleLineChart
import com.example.sploot.ui.components.StatCard
import com.example.sploot.ui.theme.HrCurve
import com.example.sploot.ui.theme.RmssdCurve
import com.example.sploot.ui.theme.stageColor
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Trends: long-horizon charts over every stored night (weekly / monthly /
 * 6-month / yearly). One switcher controls the range for all metric cards —
 * sleep architecture, duration/efficiency, HR and HRV aggregates.
 */
class TrendsViewModel(private val container: AppContainer) : ViewModel() {

    /** One charted night: its wake-up date and summary metrics. */
    data class NightPoint(
        val date: LocalDate,
        val startMs: Long,
        val summary: SleepSummary,
    )

    data class Ui(
        val days: Int = 7,
        val nights: List<NightPoint> = emptyList(),
        val baselines: List<DailyBaselineEntity> = emptyList(),
        val autonomic: AutonomicBaselines? = null,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui = _ui.asStateFlow()

    init {
        load(7)
    }

    fun load(days: Int) {
        viewModelScope.launch {
            val today = LocalDate.now()
            val from = today.minusDays((days - 1).toLong())
            val repo = container.sessionRepo

            val nights = repo.sessions()
                .filter { it.endEpochMs != null }
                .sortedByDescending { it.startEpochMs }
                .mapNotNull { s ->
                    val summary = repo.summary(s.id) ?: return@mapNotNull null
                    val wake = SleepDates.nightDate(s.startEpochMs, s.endEpochMs!!)
                    if (wake < from) null else NightPoint(wake, s.startEpochMs, summary)
                }
                .sortedBy { it.date }

            val baselineRows = container.database.dailyBaselineDao()
                .range(from.toString(), today.toString())

            _ui.value = Ui(
                days = days,
                nights = nights,
                baselines = baselineRows,
                autonomic = container.recoveryRepo.baseline(days, today),
            )
        }
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = TrendsViewModel(container) as T
        }
    }
}

private data class RangeOption(val label: String, val days: Int)

@Composable
fun TrendsScreen(viewModel: TrendsViewModel) {
    val ui by viewModel.ui.collectAsState()

    val ranges = listOf(
        RangeOption("week", 7),
        RangeOption("month", 30),
        RangeOption("6 months", 182),
        RangeOption("year", 365),
    )

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("Trends", style = MaterialTheme.typography.headlineMedium)
        }

        // single range switcher for all charts
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ranges.forEach { r ->
                    FilterChip(
                        selected = ui.days == r.days,
                        onClick = { viewModel.load(r.days) },
                        label = { Text(r.label) },
                    )
                }
            }
        }

        if (ui.nights.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.img_eucalypt_sprig),
                        contentDescription = null,
                        modifier = Modifier.size(88.dp),
                    )
                    Text("nothing gathered yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "processed nights will drift in here",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Summary stat tiles across the selected range
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Nights", ui.nights.size.toString(), Modifier.weight(1f))
                StatCard(
                    "Avg asleep",
                    ui.nights.takeIf { it.isNotEmpty() }
                        ?.let { ns -> fmtDur(ns.map { it.summary.tstMin }.average()) } ?: "--",
                    Modifier.weight(1f),
                )
                StatCard(
                    "Avg efficiency",
                    ui.nights.takeIf { it.isNotEmpty() }
                        ?.let { ns -> "%.0f%%".format(ns.map { it.summary.sleepEfficiencyPct }.average()) } ?: "--",
                    Modifier.weight(1f),
                )
            }
        }

        // ---- Sleep structure ----
        TrendCard("Time asleep", ui.nights, { it.summary.tstMin.toFloat() }, HrCurve, "min")
        TrendCard("Sleep efficiency", ui.nights, { it.summary.sleepEfficiencyPct.toFloat() }, HrCurve, "%")
        TrendCard("Deep sleep", ui.nights, { it.summary.deepMin.toFloat() }, stageColor(2), "min")
        TrendCard("REM sleep", ui.nights, { it.summary.remMin.toFloat() }, stageColor(3), "min")
        TrendCard("Light sleep", ui.nights, { it.summary.lightMin.toFloat() }, stageColor(1), "min")
        TrendCard("Wake after sleep", ui.nights, { it.summary.wasoMin.toFloat() }, stageColor(0), "min")
        TrendCard("Fell asleep in", ui.nights, { it.summary.solMin.toFloat() }, HrCurve, "min")

        // ---- Heart ----
        TrendCard("Average nightly HR", ui.nights, { it.summary.avgHrBpm?.toFloat() ?: Float.NaN }, HrCurve, "bpm")
        TrendCard("Resting HR (lowest)", ui.nights, { it.summary.lowestRhrBpm?.toFloat() ?: Float.NaN }, HrCurve, "bpm")
        TrendCard("Peak nightly HR", ui.nights, { it.summary.maxHrBpm?.toFloat() ?: Float.NaN }, HrCurve, "bpm")

        // ---- HRV ----
        TrendCard("Nocturnal rMSSD", ui.nights, { it.summary.meanRmssdMs?.toFloat() ?: Float.NaN }, RmssdCurve, "ms")
        TrendCard("Nocturnal SDNN", ui.nights, { it.summary.avgSdnnMs?.toFloat() ?: Float.NaN }, RmssdCurve, "ms")

        // Autonomic baseline card (medians from the recovery repo)
        ui.autonomic?.let { b ->
            item {
                NightCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionTitle("Autonomic baselines")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatCard("RHR median", "%.0f bpm".format(b.rhr.median), Modifier.weight(1f))
                            StatCard("rMSSD median", "%.0f ms".format(b.rmssd.median), Modifier.weight(1f))
                        }
                        Text(
                            "n=${b.rhr.nDays} nights · IQR rhr %.0f · rmssd %.0f"
                                .format(b.rhr.iqr, b.rmssd.iqr),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(48.dp)) }
    }
}

/** One metric, one card: dated line chart with axis labels + summary caption. */
private fun LazyListScope.TrendCard(
    title: String,
    nights: List<TrendsViewModel.NightPoint>,
    value: (TrendsViewModel.NightPoint) -> Float,
    lineColor: androidx.compose.ui.graphics.Color,
    unit: String,
) {
    val pts = nights.mapNotNull { n ->
        val v = value(n)
        if (v.isNaN()) null else n.date to v
    }
    if (pts.size < 2) return
    val vs = pts.map { it.second }
    item {
        NightCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(title)
                TrendChart(points = pts, lineColor = lineColor)
                Text(
                    "avg ${"%.0f".format(vs.average())} $unit · " +
                        "last ${"%.0f".format(vs.last())} · " +
                        "low ${"%.0f".format(vs.min())} · " +
                        "high ${"%.0f".format(vs.max())}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val dateLabelFmt = java.time.format.DateTimeFormatter.ofPattern("MMM d")

/**
 * Minimalist trend chart: y-axis value labels + hairline gridlines on the
 * left, the smoothed filled curve with per-night dots, and wake-date labels
 * (first / middle / last, de-duplicated, anchored to the plot edges) along
 * the bottom.
 */
@Composable
private fun TrendChart(
    points: List<Pair<LocalDate, Float>>,
    lineColor: androidx.compose.ui.graphics.Color,
) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = lineColor.copy(alpha = 0.10f)
    // dream: the trend line breathes hue and each night's node drifts a
    // couple of dp around its anchor, like the rest of the app.
    val dreamT by rememberDreamTime()
    val dreamLine = DreamFx.hueDrift(lineColor, dreamT, seed = 13)
    Column {
        Canvas(Modifier.fillMaxWidth().height(168.dp)) {
            val gutter = 40.dp.toPx()
            val w = size.width - gutter
            val h = size.height - 18.dp.toPx() // bottom strip for date labels
            val vs = points.map { it.second }
            val lo = vs.min()
            val hi = vs.max()
            val span = (hi - lo).takeIf { it > 1e-6f } ?: maxOf(hi, 1f) * 0.2f
            val pad = span * 0.15f
            val min = lo - pad
            val max = hi + pad
            fun x(i: Int) = gutter + i.toFloat() / (points.size - 1).coerceAtLeast(1) * w
            fun y(v: Float) = h - (v - min) / (max - min) * h
            val xLabelY = h + 12.dp.toPx()

            // hairline gridlines + y labels (min / mid / max)
            val labelPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(
                    (labelColor.alpha * 255).toInt(),
                    (labelColor.red * 255).toInt(),
                    (labelColor.green * 255).toInt(),
                    (labelColor.blue * 255).toInt(),
                )
                textSize = 9.dp.toPx()
                isAntiAlias = true
            }
            listOf(hi, (hi + lo) / 2f, lo).forEach { v ->
                val yy = y(v)
                drawLine(gridColor, Offset(gutter, yy), Offset(gutter + w, yy), 1f)
                drawContext.canvas.nativeCanvas.drawText(
                    if (abs(v) >= 100) "%.0f".format(v) else "%.1f".format(v),
                    0f,
                    yy + labelPaint.textSize / 3,
                    labelPaint,
                )
            }

            // smoothed filled curve — each node drifts on the dream clock
            val dreamAmp = 2.dp.toPx()
            val step = { i: Int ->
                val d = DreamFx.drift(dreamT, i, dreamAmp, seed = 31)
                Offset(x(i) + d.x, y(points[i].second) + d.y)
            }
            val line = androidx.compose.ui.graphics.Path().apply {
                moveTo(step(0).x, step(0).y)
                for (i in 1 until points.size) {
                    val prev = step(i - 1)
                    val cur = step(i)
                    quadraticBezierTo(
                        (prev.x + cur.x) / 2, prev.y,
                        (prev.x + cur.x) / 2, (prev.y + cur.y) / 2,
                    )
                }
                // close the gap: the smoothing loop ends at the midpoint
                // before the last node — curve the rest of the way into it.
                if (points.size >= 2) {
                    val prev = step(points.size - 2)
                    val last = step(points.size - 1)
                    quadraticBezierTo(
                        (prev.x + last.x) / 2, last.y,
                        last.x, last.y,
                    )
                }
            }
            val fill = androidx.compose.ui.graphics.Path().apply {
                addPath(line)
                lineTo(x(points.size - 1), h)
                lineTo(x(0), h)
                close()
            }
            drawPath(
                fill,
                Brush.verticalGradient(
                    0f to dreamLine.copy(alpha = 0.28f),
                    1f to dreamLine.copy(alpha = 0.02f),
                ),
            )
            drawPath(line, dreamLine, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))

            // per-night dots
            points.indices.forEach { i ->
                drawCircle(lineColor, radius = 2.5.dp.toPx(), center = step(i))
            }

            // x-axis date labels anchored to the plot area: first flush left,
            // last flush right, one middle label — all de-duplicated.
            data class XLabel(val text: String, val xPos: Float, val align: android.graphics.Paint.Align)
            val candidates = buildList {
                add(XLabel(points.first().first.format(dateLabelFmt), gutter, android.graphics.Paint.Align.LEFT))
                points.getOrNull(points.size / 2)?.let { mid ->
                    add(XLabel(mid.first.format(dateLabelFmt), x(points.size / 2), android.graphics.Paint.Align.CENTER))
                }
                add(XLabel(points.last().first.format(dateLabelFmt), gutter + w, android.graphics.Paint.Align.RIGHT))
            }
            var lastRight = Float.NEGATIVE_INFINITY
            candidates.sortedBy { it.xPos }.forEach { lbl ->
                val width = labelPaint.measureText(lbl.text)
                val left = when (lbl.align) {
                    android.graphics.Paint.Align.LEFT -> lbl.xPos
                    android.graphics.Paint.Align.CENTER -> lbl.xPos - width / 2
                    else -> lbl.xPos - width
                }
                // skip labels that would overlap the previously drawn one
                // (this also removes the "today twice" duplicate on short weeks)
                if (left < lastRight + 8.dp.toPx()) return@forEach
                drawContext.canvas.nativeCanvas.drawText(lbl.text, left, xLabelY, labelPaint)
                lastRight = left + width
            }
        }
    }
}

private fun fmtDur(min: Double): String =
    if (min >= 60) "${(min / 60).toInt()}h ${"%02d".format(min.toInt() % 60)}m" else "${min.toInt()}m"
