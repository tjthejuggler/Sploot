package com.example.sploot.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.example.sploot.ui.dream.DreamFx
import com.example.sploot.ui.dream.rememberDreamTime
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
            modifier = modifier.border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(sheen.copy(alpha = 0.55f), MaterialTheme.colorScheme.surface.copy(alpha = 0.15f)),
                ),
                shape = shape,
            ),
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
        val dreamT by rememberDreamTime()
        stages.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .height(10.dp)
                        .fillMaxWidth(0.022f)
                        .background(
                            // dream: legend swatches breathe a few hue degrees
                            DreamFx.hueDrift(stageColor(s), dreamT, seed = 40 + s * 7),
                            RoundedCornerShape(2.dp),
                        ),
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
    val dreamT by rememberDreamTime()
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
            // dream: each sliver's hue breathes a few degrees around its
            // true color — never enough to misread the stage.
            val c = DreamFx.hueDrift(stageColor(stage), dreamT, seed = 40 + stage * 7 + i / 24)
            drawRect(
                color = c,
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

    val dreamT by rememberDreamTime()
    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(16.dp))
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

            // stage bands (y axis: Wake top … REM bottom), drawn as connected steps.
            // dream: bands float a hair up/down and their hues breathe —
            // harnessed to their lanes, just barely not still.
            val dreamAmp = 1.1.dp.toPx()
            stages.forEachIndexed { i, stage ->
                val y = when (stage) {
                    SleepStages.WAKE -> 0
                    SleepStages.LIGHT -> 1
                    SleepStages.DEEP -> 2
                    else -> 3
                }
                val d = DreamFx.drift(dreamT, i, dreamAmp, seed = 23)
                drawRect(
                    color = DreamFx.hueDrift(stageColor(stage), dreamT, seed = 40 + stage * 7 + i / 24),
                    topLeft = Offset(i * slot + d.x * 0.4f, 4.dp.toPx() + y * bandH + 2.5f + d.y),
                    size = Size(slot + 0.6f, bandH - 5f),
                )
            }

            // gloss overlay across the top half — subtle shimmer
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.05f), Color.Transparent),
                    startY = 0f,
                    endY = h * 0.55f,
                ),
                size = Size(w, h * 0.55f),
            )

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
    fillCurve: Boolean = false,
    smooth: Boolean = false,
) {
    val dreamT by rememberDreamTime()
    // dream: the line's own color slowly breathes a few degrees of hue.
    val dreamLineColor = DreamFx.hueDrift(lineColor, dreamT, seed = 11)
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
            val lineColor = dreamLineColor

            fun yOf(v: Float): Float = h - ((v - lo) / span) * (h - 10.dp.toPx()) - 5.dp.toPx()

            // dream: every point of the curve is harnessed to where it
            // should be, but drifts around its anchor on the dream clock —
            // the whole line reads as very slowly flowing.
            val dreamAmp = 2.2.dp.toPx()
            fun dx(i: Int): Float = DreamFx.drift(dreamT, i, dreamAmp, seed = 5).x
            fun dy(i: Int): Float = DreamFx.drift(dreamT, i, dreamAmp, seed = 5).y

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
                val x = fx * w + dx(i)
                if (i == 0) path.moveTo(x, yOf(v) + dy(i)) else path.lineTo(x, yOf(v) + dy(i))
            }
            if (smooth && points.size > 2) {
                // quadratic midpoint smoothing for soft sleep2-like curves
                val smoothPath = Path()
                val x0 = points.first().first * w
                smoothPath.moveTo(x0 + dx(0), yOf(points.first().second) + dy(0))
                for (i in 1 until points.size - 1) {
                    val px = points[i].first * w + dx(i)
                    val py = yOf(points[i].second) + dy(i)
                    val nx = points[i + 1].first * w + dx(i + 1)
                    val ny = yOf(points[i + 1].second) + dy(i + 1)
                    smoothPath.quadraticBezierTo(px, py, (px + nx) / 2f, (py + ny) / 2f)
                }
                smoothPath.lineTo(points.last().first * w + dx(points.size - 1), yOf(points.last().second) + dy(points.size - 1))
                if (fillCurve) {
                    val fill = Path().apply {
                        addPath(smoothPath)
                        lineTo(w, h)
                        lineTo(0f, h)
                        close()
                    }
                    drawPath(fill, Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.30f), lineColor.copy(alpha = 0.02f))))
                }
                drawPath(smoothPath, lineColor, style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            } else {
                if (fillCurve && points.isNotEmpty()) {
                    val fill = Path().apply {
                        addPath(path)
                        lineTo(w, h)
                        lineTo(0f, h)
                        close()
                    }
                    drawPath(fill, Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.30f), lineColor.copy(alpha = 0.02f))))
                }
                drawPath(path, lineColor, style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }

            markerIndices.forEach { i ->
                if (i in points.indices) {
                    // dream: marker nodes drift around their anchor like
                    // the rest of the line — tiny slow-motion wobble.
                    drawCircle(lineColor, radius = 5f, center = Offset(points[i].first * w + dx(i), yOf(points[i].second) + dy(i)))
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

/**
 * Circular recovery score ring (sleep2-style score visual): a rounded-cap
 * progress arc on a quiet track with the score number centered inside.
 */
@Composable
fun ScoreRing(
    score: Int?,
    modifier: Modifier = Modifier,
    ringColor: Color = MaterialTheme.colorScheme.tertiary,
    label: String = "recovery",
) {
    val dreamT by rememberDreamTime()
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
            val inset = stroke / 2 + 2.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            score?.let {
                // dream: the score arc breathes ±1.5° around twelve o'clock
                // and its hue drifts a few degrees — the ring almost holds
                // still, but not quite.
                drawArc(
                    color = DreamFx.hueDrift(ringColor, dreamT, seed = 17),
                    startAngle = -90f + DreamFx.breathe(dreamT, seed = 19) * 1.5f,
                    sweepAngle = 360f * (it / 100f).coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                score?.toString() ?: "—",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Sleep2-style stage duration row: four columns (Awake / REM / Light / Deep),
 * each with a colored dot, stage name, duration and share of the night.
 */
@Composable
fun StageDurationGrid(
    lightMin: Double,
    deepMin: Double,
    remMin: Double,
    wakeMin: Double,
    modifier: Modifier = Modifier,
) {
    val total = (lightMin + deepMin + remMin + wakeMin).takeIf { it > 0 } ?: return
    val cells = listOf(
        SleepStages.WAKE to wakeMin,
        SleepStages.REM to remMin,
        SleepStages.LIGHT to lightMin,
        SleepStages.DEEP to deepMin,
    )
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        cells.forEach { (stage, min) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(stageColor(stage), RoundedCornerShape(4.dp)),
                    )
                    Text(
                        "  ${SleepStages.name(stage).lowercase()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (min >= 60) "${(min / 60).toInt()}h${"%02d".format((min % 60).toInt())}" else "${min.toInt()}m",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "${(min / total * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
