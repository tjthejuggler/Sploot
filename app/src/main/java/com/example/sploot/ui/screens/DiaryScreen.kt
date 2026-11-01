package com.example.sploot.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sploot.data.db.SessionEntity
import com.example.sploot.data.repo.SleepSummary
import com.example.sploot.di.AppContainer
import com.example.sploot.ui.components.NightCard
import com.example.sploot.ui.components.NightStageBar
import com.example.sploot.ui.theme.stageColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Diary list model: one row per recorded night, newest first. Summaries are
 * loaded lazily per row so the list stays instant even with many nights.
 */
class DiaryViewModel(private val container: AppContainer) : ViewModel() {

    data class NightRow(
        val session: SessionEntity,
        val summary: SleepSummary?,
        val stages: List<Int> = emptyList(),
    )

    data class Ui(
        val rows: List<NightRow> = emptyList(),
        val loaded: Boolean = false,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui = _ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val sessions = container.sessionRepo.sessions()
            val rows = sessions.map { s ->
                NightRow(
                    session = s,
                    summary = container.sessionRepo.summary(s.id),
                    stages = container.sessionRepo.hypnogram(s.id).map { it.stage },
                )
            }
            _ui.value = Ui(rows = rows, loaded = true)
        }
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = DiaryViewModel(container) as T
        }
    }
}

private val dayFmt = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())

@Composable
fun DiaryScreen(
    viewModel: DiaryViewModel,
    onOpenNight: (String) -> Unit,
) {
    val ui by viewModel.ui.collectAsState()

    LaunchedEffect(Unit) { viewModel.refresh() }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        if (ui.rows.isEmpty()) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("no nights yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "record or import a night and it will appear here",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        items(ui.rows, key = { it.session.id }) { row ->
            DiaryNightCard(row = row, onClick = { onOpenNight(row.session.id) })
        }

        item { Spacer(Modifier.height(56.dp)) }
    }
}

/** Sleep2-style diary entry: date header, duration + time range, stage bar, stats. */
@Composable
private fun DiaryNightCard(row: DiaryViewModel.NightRow, onClick: () -> Unit) {
    val s = row.session
    val sum = row.summary

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            dayFmt.format(Date(s.startEpochMs)),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        NightCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "${(sum?.tstMin ?: totalMin(s)).toInt() / 60}",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            " h ",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "%02d".format((sum?.tstMin ?: totalMin(s)).toInt() % 60),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            " min",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "${clock(s.startEpochMs)} - ${clock((s.endEpochMs ?: s.startEpochMs))}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // per-night stage bar graph (Sleep2 diary style)
                if (row.stages.isNotEmpty()) {
                    NightStageBar(stages = row.stages, height = 16)
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        sum?.let {
                            StageStat("Deep", it.deepMin, stageColor(2))
                            StageStat("REM", it.remMin, stageColor(3))
                            StageStat("Light", it.lightMin, stageColor(1))
                            StageStat("Awake", it.wakeMin, stageColor(0))
                        }
                    }
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(16.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    )
                }

                // footer stats: efficiency + lowest RHR
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    sum?.let {
                        FooterStat("efficiency", "${it.sleepEfficiencyPct.toInt()}%")
                        it.lowestRhrBpm?.let { rhr -> FooterStat("lowest rhr", "${rhr.toInt()} bpm") }
                        it.meanRmssdMs?.let { rm -> FooterStat("rmssd", "${rm.toInt()} ms") }
                    } ?: FooterStat("source", s.source.lowercase().replace('_', ' '))
                }
            }
        }
    }
}

@Composable
private fun StageStat(label: String, minutes: Double, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, RoundedCornerShape(2.dp)),
        )
        Text(
            " $label ${minutes.toInt()}m",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FooterStat(label: String, value: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "  $label",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun totalMin(s: SessionEntity): Double {
    val end = s.endEpochMs ?: return 0.0
    return ((end - s.startEpochMs).coerceAtLeast(0L) / 60_000.0)
}

private fun clock(ms: Long): String =
    java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
