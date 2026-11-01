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
import com.example.sploot.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sploot.data.db.DailyBaselineEntity
import com.example.sploot.data.repo.AutonomicBaselines
import com.example.sploot.di.AppContainer
import com.example.sploot.ui.components.NightCard
import com.example.sploot.ui.components.SectionTitle
import com.example.sploot.ui.components.SimpleLineChart
import com.example.sploot.ui.components.StatCard
import com.example.sploot.ui.theme.HrCurve
import com.example.sploot.ui.theme.RmssdCurve
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Trends: weekly / monthly / 6-month / yearly views of nightly autonomic
 * aggregates. One chart block per metric, one switcher controlling range.
 */
class TrendsViewModel(private val container: AppContainer) : ViewModel() {

    data class Ui(
        val days: Int = 7,
        val rows: List<DailyBaselineEntity> = emptyList(),
        val baselines: AutonomicBaselines? = null,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui = _ui.asStateFlow()

    init {
        load(7)
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

        // Average nightly heart rate — one chart, switchable ranges
        item {
            NightCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("Average nightly HR")
                    SimpleLineChart(
                        points = ui.rows.mapIndexed { i, r ->
                            i.toFloat() / ui.rows.size.coerceAtLeast(1) to r.nocturnalRhrMin.toFloat()
                        },
                        lineColor = HrCurve,
                        band = ui.baselines?.let {
                            (it.rhr.median - it.rhr.iqr / 2)..(it.rhr.median + it.rhr.iqr / 2)
                        },
                        minMaxLabel = { "%.0f".format(it) },
                    )
                    Text(
                        ui.baselines?.let {
                            "baseline median %.0f bpm · IQR %.0f (n=${it.rhr.nDays})"
                                .format(it.rhr.median, it.rhr.iqr)
                        } ?: "${ui.rows.size} nights",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            NightCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("Nocturnal rMSSD")
                    SimpleLineChart(
                        points = ui.rows.mapIndexed { i, r ->
                            i.toFloat() / ui.rows.size.coerceAtLeast(1) to r.nocturnalRmssd.toFloat()
                        },
                        lineColor = RmssdCurve,
                        band = ui.baselines?.let {
                            (it.rmssd.median - it.rmssd.iqr / 2)..(it.rmssd.median + it.rmssd.iqr / 2)
                        },
                        minMaxLabel = { "%.0f".format(it) },
                    )
                    Text(
                        ui.baselines?.let {
                            "baseline median %.0f ms · IQR %.0f (n=${it.rmssd.nDays})"
                                .format(it.rmssd.median, it.rmssd.iqr)
                        } ?: "${ui.rows.size} nights",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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
