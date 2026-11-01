package com.example.sploot.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.sploot.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sploot.data.db.DailyBaselineEntity
import com.example.sploot.data.repo.AutonomicBaselines
import com.example.sploot.di.AppContainer
import com.example.sploot.ui.components.SimpleLineChart
import com.example.sploot.ui.components.StatCard
import com.example.sploot.ui.theme.HrCurve
import com.example.sploot.ui.theme.RmssdCurve
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TrendsViewModel(private val container: AppContainer) : ViewModel() {

    data class Ui(
        val days: Int = 14,
        val rows: List<DailyBaselineEntity> = emptyList(),
        val baselines: AutonomicBaselines? = null,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui = _ui.asStateFlow()

    init {
        load(14)
    }

    fun load(days: Int) {
        viewModelScope.launch {
            val today = LocalDate.now()
            val from = today.minusDays((days - 1).toLong()).toString()
            val rows = container.database.dailyBaselineDao().range(from, today.toString())
            val baselines = container.recoveryRepo.baseline(days, today)
            _ui.value = Ui(days = days, rows = rows, baselines = baselines)
        }
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = TrendsViewModel(container) as T
        }
    }
}

@Composable
fun TrendsScreen(viewModel: TrendsViewModel) {
    val ui by viewModel.ui.collectAsState()

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Autonomic trends", style = MaterialTheme.typography.headlineMedium)
        }
        item {
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(14, 60).forEach { d ->
                    FilterChip(
                        selected = ui.days == d,
                        onClick = { viewModel.load(d) },
                        label = { Text("$d days") },
                    )
                }
            }
        }

        if (ui.rows.isEmpty()) {
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

        item {
            ChartCard(
                title = "Nocturnal rMSSD (ms)",
                points = ui.rows.mapIndexed { i, r ->
                    (i.toFloat() / ui.rows.size.coerceAtLeast(1)) to r.nocturnalRmssd
                },
                band = ui.baselines?.let {
                    (it.rmssd.median - it.rmssd.iqr / 2)..(it.rmssd.median + it.rmssd.iqr / 2)
                },
                color = RmssdCurve,
                footer = ui.baselines?.let {
                    "baseline median %.0f ms · IQR %.0f (n=${it.rmssd.nDays})"
                        .format(it.rmssd.median, it.rmssd.iqr)
                },
            )
        }
        item {
            ChartCard(
                title = "Nocturnal RHR (bpm)",
                points = ui.rows.mapIndexed { i, r ->
                    (i.toFloat() / ui.rows.size.coerceAtLeast(1)) to r.nocturnalRhrMin
                },
                band = ui.baselines?.let {
                    (it.rhr.median - it.rhr.iqr / 2)..(it.rhr.median + it.rhr.iqr / 2)
                },
                color = HrCurve,
                footer = ui.baselines?.let {
                    "baseline median %.0f bpm · IQR %.0f (n=${it.rhr.nDays})"
                        .format(it.rhr.median, it.rhr.iqr)
                },
            )
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Nights", ui.rows.size.toString(), Modifier.weight(1f))
                StatCard(
                    "Latest rMSSD",
                    ui.rows.lastOrNull()?.nocturnalRmssd?.let { "$it ms" } ?: "--",
                    Modifier.weight(1f),
                )
                StatCard(
                    "Latest RHR",
                    ui.rows.lastOrNull()?.nocturnalRhrMin?.let { "$it bpm" } ?: "--",
                    Modifier.weight(1f),
                )
            }
        }
        item { Spacer(Modifier.height(48.dp)) }
    }
}

@Composable
private fun ChartCard(
    title: String,
    points: List<Pair<Float, Double>>,
    band: ClosedFloatingPointRange<Double>?,
    color: androidx.compose.ui.graphics.Color,
    footer: String?,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            SimpleLineChart(
                points.map { it.first to it.second.toFloat() },
                lineColor = color,
                band = band,
                minMaxLabel = { "%.0f".format(it) },
            )
            footer?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
