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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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

/** Borderless typographic metric note — dreamlike editorial layout unit:
 * a whispered lowercase label and a value; whitespace is the container. */
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

/** One soft horizontal proportion bar for stage durations (replaces four
 * cards): a single rounded strip segmented by stage share, quiet labels. */
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
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp)),
        ) {
            listOf(
                lightMin to stageColor(SleepStages.LIGHT),
                deepMin to stageColor(SleepStages.DEEP),
                remMin to stageColor(SleepStages.REM),
                wakeMin to stageColor(SleepStages.WAKE),
            ).forEach { (min, c) ->
                Box(
                    Modifier
                        .weight(((min / total).toFloat()).coerceAtLeast(0f))
                        .fillMaxHeight()
                        .background(c.copy(alpha = 0.75f)),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "light ${minFmt(lightMin)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "deep ${minFmt(deepMin)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "rem ${minFmt(remMin)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun minFmt(min: Double): String =
    if (min >= 60) "${(min / 60).toInt()}h ${min.toInt() % 60}m" else "${min.toInt()}m"

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
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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

/**
 * Interactive 30 s hypnogram: colored bands for Wake/REM/Light/Deep, tap or
 * drag to scrub — the selected epoch's clock time + stage is reported through
 * [onScrub].
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
                .height(160.dp)
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

            // dreamlike: no gridlines — only a faint bottom baseline
            val baseY = 4.dp.toPx() + 4 * bandH
            drawLine(GridLine.copy(alpha = 0.45f), Offset(0f, baseY), Offset(w, baseY), strokeWidth = 1f)

            // stage bands (y axis: Wake top … REM bottom), soft and breathing
            stages.forEachIndexed { i, stage ->
                val y = when (stage) {
                    SleepStages.WAKE -> 0
                    SleepStages.LIGHT -> 1
                    SleepStages.DEEP -> 2
                    else -> 3
                }
                drawRect(
                    color = stageColor(stage).copy(alpha = 0.85f),
                    topLeft = Offset(i * slot, 4.dp.toPx() + y * bandH + 1.5f),
                    size = androidx.compose.ui.geometry.Size(slot + 0.5f, bandH - 3f),
                )
            }

            // scrub line
            if (selected in stages.indices) {
                val x = (selected + 0.5f) * slot
                drawLine(ScrubLine, Offset(x, 0f), Offset(x, h), strokeWidth = 2f, cap = StrokeCap.Round)
            }
        }
        // legend + scrub readout
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SleepStages.NAMES.forEachIndexed { i, name ->
                Row {
                    Canvas(Modifier.height(10.dp).fillMaxWidth(0.02f)) {
                        drawRect(stageColor(i))
                    }
                    Text(
                        " $name",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Simple line chart for trends / autonomic overlays. Values are
 * (x-position 0..1 fraction, value) pairs; [yLabel] formats min/max labels.
 */
@Composable
fun SimpleLineChart(
    points: List<Pair<Float, Float>>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    markerIndices: Set<Int> = emptySet(),
    minMaxLabel: (Float) -> String = { "%.1f".format(it) },
    band: ClosedFloatingPointRange<Double>? = null,
) {
    Column(modifier) {
        val ys = points.map { it.second }
        val lo = if (ys.isEmpty()) 0f else minOf(ys.min(), (band?.start ?: ys.min().toDouble()).toFloat())
        val hi = if (ys.isEmpty()) 1f else maxOf(ys.max(), (band?.endInclusive ?: ys.max().toDouble()).toFloat())
        val span = (hi - lo).takeIf { it > 1e-9f } ?: 1f
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(120.dp),
        ) {
            val w = size.width
            val h = size.height
            if (points.isEmpty()) return@Canvas

            fun yOf(v: Float): Float = h - ((v - lo) / span) * (h - 8.dp.toPx()) - 4.dp.toPx()

            // baseline band (e.g. median ± IQR)
            if (band != null) {
                val yTop = yOf(band.endInclusive.toFloat())
                val yBot = yOf(band.start.toFloat())
                drawRect(
                    color = lineColor.copy(alpha = 0.12f),
                    topLeft = Offset(0f, yTop),
                    size = androidx.compose.ui.geometry.Size(w, (yBot - yTop).coerceAtLeast(0f)),
                )
            }

            drawLine(GridLine.copy(alpha = 0.6f), Offset(0f, h - 4.dp.toPx()), Offset(w, h - 4.dp.toPx()), strokeWidth = 1f)

            val path = Path()
            points.forEachIndexed { i, (fx, v) ->
                val x = fx * w
                if (i == 0) path.moveTo(x, yOf(v)) else path.lineTo(x, yOf(v))
            }
            drawPath(path, lineColor, style = Stroke(width = 2.5f, cap = StrokeCap.Round))

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
