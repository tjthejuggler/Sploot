package com.example.sploot.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sploot.data.db.SessionEntity
import com.example.sploot.data.db.SleepStages
import com.example.sploot.data.db.StageEntity
import com.example.sploot.data.repo.BaselineMath
import com.example.sploot.data.repo.IbiPoint
import com.example.sploot.data.repo.SleepSummary
import com.example.sploot.data.repo.SummaryCalculator
import com.example.sploot.di.AppContainer
import com.example.sploot.ui.components.HypnogramChart
import com.example.sploot.ui.components.QuietMetric
import com.example.sploot.ui.components.SimpleLineChart
import com.example.sploot.ui.components.StageProportionBar
import com.example.sploot.ui.components.formatEpochClock
import com.example.sploot.ui.theme.HrCurve
import com.example.sploot.ui.theme.RmssdCurve
import com.example.sploot.ui.theme.TextOnDark
import java.time.LocalDate
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DashboardViewModel(private val container: AppContainer) : ViewModel() {

    data class Ui(
        val sessions: List<SessionEntity> = emptyList(),
        val selectedId: String? = null,
        val stages: List<StageEntity> = emptyList(),
        val summary: SleepSummary? = null,
        val hrPoints: List<Pair<Float, Float>> = emptyList(),
        val rmssdPoints: List<Pair<Float, Float>> = emptyList(),
        val rhrMarkerIndex: Int = -1,
        val recoveryScore: Int? = null,
        val baselineNote: String? = null,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui = _ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val sessions = container.sessionRepo.sessions()
            val id = _ui.value.selectedId ?: sessions.firstOrNull()?.id
            _ui.value = Ui(sessions = sessions, selectedId = id)
            id?.let { load(it) }
        }
    }

    fun select(sessionId: String) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(selectedId = sessionId)
            load(sessionId)
        }
    }

    private suspend fun load(sessionId: String) {
        val repo = container.sessionRepo
        val stages = repo.hypnogram(sessionId)
        val intervals = repo.intervals(sessionId)
        val session = repo.session(sessionId) ?: return
        val summary = repo.summary(sessionId)

        // Nocturnal HR curve: per-epoch mean HR
        val epochMs = 30_000L
        val start = session.startEpochMs
        val hrByEpoch = DoubleArray(stages.size)
        val cnt = IntArray(stages.size)
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

        // Rolling 5-min rMSSD beneath the hypnogram
        val rmssdPoints = rollingRmssd(ibis, start, stages.size * epochMs)

        // Lowest-RHR marker = index of minimum of the HR curve
        val rhrIdx = hrPoints.indices.minByOrNull { hrPoints[it].second } ?: -1

        // Recovery score vs baseline
        val date = java.time.Instant.ofEpochMilli(start)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val row = container.database.dailyBaselineDao().byDate(date.toString())
        val baselines = container.recoveryRepo.baseline60(date.minusDays(1))
        val score = if (row != null && baselines != null) {
            BaselineMath.recoveryScore(row.nocturnalRmssd, row.nocturnalRhrMin, baselines)
        } else null

        _ui.value = _ui.value.copy(
            stages = stages,
            summary = summary,
            hrPoints = hrPoints,
            rmssdPoints = rmssdPoints,
            rhrMarkerIndex = rhrIdx,
            recoveryScore = score,
            baselineNote = if (baselines == null) {
                "Baseline needs ≥ ${com.example.sploot.data.repo.BaselineStats.MIN_DAYS} nights of history"
            } else null,
        )
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
            override fun <T : ViewModel> create(modelClass: Class<T>): T = DashboardViewModel(container) as T
        }
    }
}

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    initialSessionId: String?,
    onCompare: (String) -> Unit,
) {
    LaunchedEffect(initialSessionId) {
        initialSessionId?.let { viewModel.select(it) }
    }
    val ui by viewModel.ui.collectAsState()
    var scrub by remember { mutableStateOf<String?>(null) }

    // Dreamlike editorial layout: no boxed card grid. The hypnogram is the
    // hero; metrics float as quiet typographic notes at varied sizes and
    // alignments; stage durations collapse into one soft proportion bar.
    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            Text("last night", style = MaterialTheme.typography.displaySmall, color = TextOnDark)
            Text(
                ui.selectedId ?: "no sessions yet — record a night first",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Recovery — a quiet floating number, offset left, no box
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column {
                    Text(
                        "recovery",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        ui.recoveryScore?.let { "$it" } ?: "—",
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    ui.baselineNote?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // a lone quiet counterpoint on the right
                ui.summary?.let { s ->
                    Column(
                        Modifier.padding(top = 10.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        Text(
                            "efficiency",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "${s.sleepEfficiencyPct}%",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
        }

        // Hypnogram — hero, borderless, floating minimal labels
        if (ui.stages.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "the night",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    scrub?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = TextOnDark)
                    }
                    val session = ui.sessions.firstOrNull { it.id == ui.selectedId }
                    val startMs = session?.startEpochMs ?: 0L
                    HypnogramChart(
                        stages = ui.stages.map { it.stage },
                        epochStartMs = ui.stages.map { startMs + it.epochIndex * 30_000L },
                        onScrub = { idx ->
                            scrub = "${formatEpochClock(startMs + idx * 30_000L)} · ${SleepStages.name(ui.stages[idx].stage).lowercase()}"
                        },
                    )
                }
            }

            // Stage durations as one soft horizontal proportion bar
            ui.summary?.let { s ->
                item { StageProportionBar(lightMin = s.lightMin, deepMin = s.deepMin, remMin = s.remMin, wakeMin = (s.wasoMin + s.solMin)) }
            }
        }

        // Autonomic curves — borderless, whispering titles
        if (ui.hrPoints.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "heart rate (bpm)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SimpleLineChart(
                        ui.hrPoints,
                        lineColor = HrCurve,
                        markerIndices = setOf(ui.rhrMarkerIndex),
                        minMaxLabel = { "%.0f".format(it) },
                    )
                    Text(
                        "rmssd, 5 min (ms)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SimpleLineChart(ui.rmssdPoints, lineColor = RmssdCurve, minMaxLabel = { "%.0f".format(it) })
                    ui.summary?.lowestRhrBpm?.let {
                        Text(
                            "lowest rhr $it bpm",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        }

        // Core metrics — organic offset flow, borderless typographic notes
        ui.summary?.let { s ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                        QuietMetric("asleep", fmt(s.tstMin), Modifier.weight(1f), large = true)
                        QuietMetric("in bed", fmt(s.tibMin), Modifier.weight(1f))
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(40.dp),
                    ) {
                        QuietMetric("fell asleep in", fmt(s.solMin), Modifier.weight(1f))
                        QuietMetric(
                            "awake during",
                            "${fmt(s.wasoMin)} · ${s.wasoEpisodes}×",
                            Modifier.weight(1f),
                        )
                        QuietMetric(
                            "rmssd",
                            s.meanRmssdMs?.let { "$it ms" } ?: "—",
                            Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // Session history
        item {
            Text(
                "nights",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(ui.sessions, key = { it.id }) { s ->
            // borderless row: tonal wash + whitespace instead of a boxed card
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.45f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(s.id, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${s.source} · ${java.text.SimpleDateFormat("MMM d, HH:mm").format(java.util.Date(s.startEpochMs))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { viewModel.select(s.id) }) { Text("view") }
                    TextButton(onClick = { onCompare(s.id) }) { Text("a/b") }
                }
            }
        }
        item { Spacer(Modifier.height(48.dp)) }
    }
}

private fun fmt(min: Double): String =
    if (min >= 60) "${(min / 60).toInt()}h${"%02d".format((min % 60).toInt())}" else "${min.toInt()}m"
