package com.example.sploot.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.sploot.data.db.SleepStages
import com.example.sploot.ui.theme.GridLine
import com.example.sploot.ui.theme.ScrubLine
import com.example.sploot.ui.theme.stageColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val clockFmt = DateTimeFormatter.ofPattern("HH:mm:ss")

fun formatEpochClock(epochStartMs: Long): String =
    Instant.ofEpochMilli(epochStartMs).atZone(ZoneId.systemDefault()).format(clockFmt)

private val hmFmt = DateTimeFormatter.ofPattern("HH:mm")

fun formatEpochHm(epochStartMs: Long): String =
    Instant.ofEpochMilli(epochStartMs).atZone(ZoneId.systemDefault()).format(hmFmt)

/**
 * Dimensional surface card: near-black raised container, soft corner radius,
 * subtle top-edge highlight for a slight 3D feel. The app's base container.
 */
@Composable
fun NightCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    val container = MaterialTheme.colorScheme.surface
    val sheen = MaterialTheme.colorScheme.surfaceContainerHigh
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = container),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Box(
                Modifier
                    .background(
                        Brush.verticalGradient(listOf(sheen.copy(alpha = 0.35f), Color.Transparent)),
                    )
                    .padding(18.dp),
            ) { content() }
        }
    } else {
        Card(
            modifier = modifier,
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = container),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Box(
                Modifier
                    .background(
                        Brush.verticalGradient(listOf(sheen.copy(alpha = 0.35f), Color.Transparent)),
                    )
                    .padding(18.dp),
            ) { content() }
        }
    }
}

/** Section header inside a card ("Sleep phases", "Heart rate") with optional chevron. */
@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        modifier = modifier,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** Quiet typographic metric: muted label over a large value. */
@Composable
fun QuietMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    accent: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = if (large) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineSmall,
            color = accent,
        )
    }
}

/**
 * Compact stage legend row — the single source of stage color mapping shown
 * next to every chart: colored square + name for Wake/REM/Light/Deep.
 */
@Composable
fun StageLegend(modifier: Modifier = Modifier, stages: List<Int> = listOf(0, 3, 1, 2)) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        stages.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .height(10.dp)
                        .fillMaxWidth(0.022f)
                        .background(stageColor(s), RoundedCornerShape(2.dp)),
                )
                Text(
                    "  ${SleepStages.name(s)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Sleep2-style segmented night bar: one rounded horizontal strip where each
 * epoch is a thin vertical sliver colored by stage. This is the per-night
 * graph used in the diary list.
 */
@Composable
fun NightStageBar(
    stages: List<Int>,
    modifier: Modifier = Modifier,
    height: Int = 14,
) {
    if (stages.isEmpty()) return
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape((height / 2).dp)),
    ) {
        val w = size.width
        val h = size.height
        val slot = w / stages.size
        stages.forEachIndexed { i, stage ->
            drawRect(
                color = stageColor(stage),
                topLeft = Offset(i * slot, 0f),
                size = Size(slot + 0.6f, h),
            )
        }
    }
}

/**
 * Interactive 30 s hypnogram (Sleep2 "Sleep phases" style): colored step bands
 * for Wake/REM/Light/Deep with clock axis labels, tap or drag to scrub — the
 * selected epoch's clock time + stage is reported through [onScrub].
 *
 * @param stages stage code per epoch (0..3)
 * @param epochStartMs wall-clock start of epoch i (same size as [stages])
 */
@Composable
fun HypnogramChart(
    stages: List<Int>,
    epochStartMs: List<Long>,
    modifier: Modifier = Modifier,
    onScrub: ((epochIndex: Int) -> Unit)? = null,
) {
    var selected by remember { mutableIntStateOf(-1) }

    fun epochAt(x: Float, width: Float): Int {
        if (stages.isEmpty() || width <= 0) return -1
        return ((x / width) * stages.size).toInt().coerceIn(0, stages.size - 1)
    }

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(170.dp)
                .pointerInput(stages.size) {
                    detectTapGestures { offset ->
                        selected = epochAt(offset.x, size.width.toFloat()).also { idx ->
                            if (idx >= 0) onScrub?.invoke(idx)
                        }
                    }
                }
                .pointerInput(stages.size) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        selected = epochAt(change.position.x, size.width.toFloat()).also { idx ->
                            if (idx >= 0) onScrub?.invoke(idx)
                        }
                    }
                },
        ) {
            if (stages.isEmpty()) return@Canvas
            val w = size.width
            val h = size.height
            val slot = w / stages.size
            val bandH = (h - 8.dp.toPx()) / 4f

            // faint bottom baseline
            val baseY = 4.dp.toPx() + 4 * bandH
            drawLine(GridLine, Offset(0f, baseY), Offset(w, baseY), strokeWidth = 1.5f)

            // stage bands (y axis: Wake top … REM bottom), drawn as connected steps
            stages.forEachIndexed { i, stage ->
                val y = when (stage) {
                    SleepStages.WAKE -> 0
                    SleepStages.LIGHT -> 1
                    SleepStages.DEEP -> 2
                    else -> 3
                }
                drawRect(
                    color = stageColor(stage),
                    topLeft = Offset(i * slot, 4.dp.toPx() + y * bandH + 1.5f),
                    size = Size(slot + 0.6f, bandH - 3f),
                )
            }

            // scrub line
            if (selected in stages.indices) {
                val x = (selected + 0.5f) * slot
                drawLine(ScrubLine, Offset(x, 0f), Offset(x, h), strokeWidth = 2f, cap = StrokeCap.Round)
            }
        }
        // clock axis
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val first = epochStartMs.firstOrNull()
            val last = epochStartMs.lastOrNull()
            if (first != null && last != null) {
                val mid = first + (last - first) / 2
                Text(formatEpochHm(first), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatEpochHm(mid), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatEpochHm(last), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Line chart for nightly autonomic curves with a dashed mean line (Sleep2
 * heart-rate style), optional highlighted markers and a baseline band.
 * Values are (x-position 0..1 fraction, value) pairs.
 */
@Composable
fun SimpleLineChart(
    points: List<Pair<Float, Float>>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    markerIndices: Set<Int> = emptySet(),
    minMaxLabel: (Float) -> String = { "%.1f".format(it) },
    band: ClosedFloatingPointRange<Double>? = null,
    showMean: Boolean = true,
    meanLabel: ((Float) -> String)? = null,
) {
    Column(modifier) {
        val ys = points.map { it.second }
        val lo = if (ys.isEmpty()) 0f else minOf(ys.min(), (band?.start ?: ys.min().toDouble()).toFloat())
        val hi = if (ys.isEmpty()) 1f else maxOf(ys.max(), (band?.endInclusive ?: ys.max().toDouble()).toFloat())
        val span = (hi - lo).takeIf { it > 1e-9f } ?: 1f
        val meanDashColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(140.dp),
        ) {
            val w = size.width
            val h = size.height
            if (points.isEmpty()) return@Canvas

            fun yOf(v: Float): Float = h - ((v - lo) / span) * (h - 10.dp.toPx()) - 5.dp.toPx()

            // horizontal gridlines (quiet)
            repeat(3) { g ->
                val y = (g + 1) * h / 4f
                drawLine(GridLine, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }
            drawLine(GridLine, Offset(0f, h - 4.dp.toPx()), Offset(w, h - 4.dp.toPx()), strokeWidth = 1.5f)

            // baseline band (e.g. median ± IQR)
            if (band != null) {
                val yTop = yOf(band.endInclusive.toFloat())
                val yBot = yOf(band.start.toFloat())
                drawRect(
                    color = lineColor.copy(alpha = 0.10f),
                    topLeft = Offset(0f, yTop),
                    size = Size(w, (yBot - yTop).coerceAtLeast(0f)),
                )
            }

            // dashed mean line
            if (showMean && points.isNotEmpty()) {
                val mean = ys.average().toFloat()
                val ym = yOf(mean)
                val dash = 8f
                var x = 0f
                while (x < w) {
                    drawLine(
                        meanDashColor,
                        Offset(x, ym),
                        Offset(minOf(x + dash, w), ym),
                        strokeWidth = 2f,
                    )
                    x += dash * 2
                }
            }

            val path = Path()
            points.forEachIndexed { i, (fx, v) ->
                val x = fx * w
                if (i == 0) path.moveTo(x, yOf(v)) else path.lineTo(x, yOf(v))
            }
            drawPath(path, lineColor, style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))

            markerIndices.forEach { i ->
                if (i in points.indices) {
                    drawCircle(lineColor, radius = 5f, center = Offset(points[i].first * w, yOf(points[i].second)))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(minMaxLabel(lo), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(minMaxLabel(hi), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Simple metric card used across dashboard / trends. */
@Composable
fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value, style = MaterialTheme.typography.titleLarge, color = accent, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** One soft horizontal proportion bar for stage durations (summary view). */
@Composable
fun StageProportionBar(
    lightMin: Double,
    deepMin: Double,
    remMin: Double,
    wakeMin: Double,
    modifier: Modifier = Modifier,
) {
    val total = (lightMin + deepMin + remMin + wakeMin).takeIf { it > 0 } ?: return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp)),
        ) {
            listOf(
                lightMin to stageColor(SleepStages.LIGHT),
                deepMin to stageColor(SleepStages.DEEP),
                remMin to stageColor(SleepStages.REM),
                wakeMin to stageColor(SleepStages.WAKE),
            ).forEach { (min, c) ->
                // Skip zero-duration stages: Compose weight must be > 0
                // (crash on PPI-only nights where deep/REM/wake are 0).
                val fraction = (min / total).toFloat()
                if (fraction > 0f) {
                    Box(
                        Modifier
                            .weight(fraction)
                            .fillMaxHeight()
                            .background(c),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(
                "light" to lightMin,
                "deep" to deepMin,
                "rem" to remMin,
            ).forEach { (label, min) ->
                Text(
                    "$label ${minFmt(min)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun minFmt(min: Double): String =
    if (min >= 60) "${(min / 60).toInt()}h ${min.toInt() % 60}m" else "${min.toInt()}m"
