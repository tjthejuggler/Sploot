package com.example.sploot.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sploot.data.db.SleepStages
import com.example.sploot.data.repo.IbiPoint
import com.example.sploot.data.repo.SleepSummary
import com.example.sploot.di.AppContainer
import com.example.sploot.ui.components.HypnogramChart
import com.example.sploot.ui.components.NightCard
import com.example.sploot.ui.components.SectionTitle
import com.example.sploot.ui.components.SimpleLineChart
import com.example.sploot.ui.components.StageLegend
import com.example.sploot.ui.components.StageProportionBar
import com.example.sploot.ui.components.formatEpochClock
import com.example.sploot.ui.theme.HrCurve
import com.example.sploot.ui.theme.RmssdCurve
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Night detail (Sleep2 day-detail style): header metrics, "Sleep phases"
 * hypnogram card with stage stats, and a "Heart rate" card with the nocturnal
 * HR curve (mean line) plus the 5-min rMSSD curve.
 */
class NightDetailViewModel(private val container: AppContainer) : ViewModel() {

    data class Ui(
        val sessionId: String? = null,
        val startMs: Long = 0,
        val endMs: Long? = null,
        val stages: List<Int> = emptyList(),
        val summary: SleepSummary? = null,
        val hrPoints: List<Pair<Float, Float>> = emptyList(),
        val rmssdPoints: List<Pair<Float, Float>> = emptyList(),
        val avgHr: Double? = null,
        val lowestHr: Double? = null,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui = _ui.asStateFlow()

    fun load(sessionId: String) {
        viewModelScope.launch {
            val repo = container.sessionRepo
            val session = repo.session(sessionId) ?: return@launch
            val stageRows = repo.hypnogram(sessionId)
            val intervals = repo.intervals(sessionId)
            val summary = repo.summary(sessionId)

            // per-epoch mean HR curve (Sleep2-like nightly HR line)
            val epochMs = 30_000L
            val start = session.startEpochMs
            val hrByEpoch = DoubleArray(stageRows.size.coerceAtLeast(1))
            val cnt = IntArray(stageRows.size.coerceAtLeast(1))
            val ibis = intervals.map { IbiPoint(it.timestampMs, it.intervalMs) }.sortedBy { it.timestampMs }
            ibis.forEach {
                val idx = ((it.timestampMs - start) / epochMs).toInt()
                if (idx in hrByEpoch.indices) {
                    hrByEpoch[idx] += 60_000.0 / it.intervalMs
                    cnt[idx]++
                }
            }
            val hrPoints = hrByEpoch.indices.mapNotNull { i ->
                if (cnt[i] > 0) i.toFloat() / hrByEpoch.size.coerceAtLeast(1) to (hrByEpoch[i] / cnt[i]).toFloat() else null
            }

            // rolling 5-min rMSSD
            val rmssdPoints = rollingRmssd(ibis, start, stageRows.size * epochMs)

            _ui.value = Ui(
                sessionId = sessionId,
                startMs = start,
                endMs = session.endEpochMs,
                stages = stageRows.map { it.stage },
                summary = summary,
                hrPoints = hrPoints,
                rmssdPoints = rmssdPoints,
                avgHr = hrPoints.map { it.second }.average().takeIf { hrPoints.isNotEmpty() },
                lowestHr = hrPoints.minByOrNull { it.second }?.second?.toDouble(),
            )
        }
    }

    private fun rollingRmssd(ibis: List<IbiPoint>, startMs: Long, totalMs: Long): List<Pair<Float, Float>> {
        if (ibis.size < 3 || totalMs <= 0) return emptyList()
        val windowMs = 300_000L
        val out = ArrayList<Pair<Float, Float>>()
        var lo = 0
        var hi = 0
        while (hi < ibis.size) {
            while (ibis[hi].timestampMs - ibis[lo].timestampMs > windowMs) lo++
            val slice = ibis.subList(lo, hi + 1)
            if (slice.size >= 3) {
                var sumSq = 0.0
                for (i in 1 until slice.size) {
                    val d = (slice[i].intervalMs - slice[i - 1].intervalMs).toDouble()
                    sumSq += d * d
                }
                val frac = ((slice.first().timestampMs - startMs).toFloat() / totalMs).coerceIn(0f, 1f)
                out.add(frac to sqrt(sumSq / (slice.size - 1)).toFloat())
            }
            hi++
        }
        return out
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = NightDetailViewModel(container) as T
        }
    }
}

private val dayFmt = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())

@Composable
fun NightDetailScreen(viewModel: NightDetailViewModel, sessionId: String) {
    val ui by viewModel.ui.collectAsState()
    LaunchedEffect(sessionId) { viewModel.load(sessionId) }
    var scrub by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        // Header: date + time range
        item {
            Column {
                Text(
                    dayFmt.format(Date(ui.startMs)),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${fmtHm(ui.startMs)} - ${fmtHm(ui.endMs ?: ui.startMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Top metrics row (asleep / efficiency / lowest rhr)
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                ui.summary?.let { s ->
                    Metric("asleep", fmtDur(s.tstMin))
                    Metric("efficiency", "${s.sleepEfficiencyPct.toInt()}%")
                    s.lowestRhrBpm?.let { Metric("lowest rhr", "${it.toInt()} bpm") }
                }
            }
        }

        // ---- Sleep phases card ----
        if (ui.stages.isNotEmpty()) {
            item {
                NightCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionTitle("Sleep phases")
                        StageLegend()
                        val epochStartMs = remember(ui.stages.size) {
                            List(ui.stages.size) { i -> ui.startMs + i * 30_000L }
                        }
                        scrub?.let {
                            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                        }
                        HypnogramChart(
                            stages = ui.stages,
                            epochStartMs = epochStartMs,
                            onScrub = { idx ->
                                scrub = "${formatEpochClock(ui.startMs + idx * 30_000L)} · ${SleepStages.name(ui.stages[idx]).lowercase()}"
                            },
                        )
                        ui.summary?.let {
                            StageProportionBar(
                                lightMin = it.lightMin,
                                deepMin = it.deepMin,
                                remMin = it.remMin,
                                wakeMin = it.wakeMin + it.solMin,
                            )
                        }
                    }
                }
            }

            // Stage duration tiles (Deep / REM / Light / Awake)
            item {
                ui.summary?.let { s ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        StageTile("Deep", s.deepMin, s.deepPct, Modifier.weight(1f))
                        StageTile("REM", s.remMin, s.remPct, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        StageTile("Light", s.lightMin, s.lightPct, Modifier.weight(1f))
                        StageTile("Awake", s.wakeMin, s.wakePct, Modifier.weight(1f))
                    }
                }
            }
        }

        // ---- Heart rate card ----
        if (ui.hrPoints.isNotEmpty()) {
            item {
                NightCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionTitle("Heart rate")
                        Text(
                            "Average heart rate:  ${ui.avgHr?.toInt() ?: "--"} bpm",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        SimpleLineChart(
                            ui.hrPoints,
                            lineColor = HrCurve,
                            minMaxLabel = { "%.0f".format(it) },
                        )
                    }
                }
            }
        }

        // ---- HRV card ----
        if (ui.rmssdPoints.isNotEmpty()) {
            item {
                NightCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionTitle("HRV (rMSSD, 5 min)")
                        Text(
                            "Night rMSSD:  ${ui.summary?.meanRmssdMs?.toInt() ?: "--"} ms",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        SimpleLineChart(
                            ui.rmssdPoints,
                            lineColor = RmssdCurve,
                            minMaxLabel = { "%.0f".format(it) },
                        )
                    }
                }
            }
        }

        // ---- Sleep metrics card ----
        ui.summary?.let { s ->
            item {
                NightCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionTitle("Sleep metrics")
                        MetricRow("Time in bed", fmtDur(s.tibMin))
                        MetricRow("Asleep", fmtDur(s.tstMin))
                        MetricRow("Fell asleep in", fmtDur(s.solMin))
                        MetricRow("Awake during", "${fmtDur(s.wasoMin)} · ${s.wasoEpisodes}×")
                        s.meanRmssdMs?.let { MetricRow("rMSSD", "$it ms") }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(56.dp)) }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StageTile(label: String, minutes: Double, pct: Double, modifier: Modifier = Modifier) {
    NightCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(fmtDur(minutes), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${pct.toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun fmtHm(ms: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

private fun fmtDur(min: Double): String =
    if (min >= 60) "${(min / 60).toInt()}h ${"%02d".format(min.toInt() % 60)}m" else "${min.toInt()}m"
