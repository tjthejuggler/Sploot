package com.example.sploot.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sploot.comparison.ComparisonCalculator
import com.example.sploot.comparison.HypnogramCsvParser
import com.example.sploot.data.db.ComparisonEntity
import com.example.sploot.data.db.SleepStages
import com.example.sploot.di.AppContainer
import com.example.sploot.ui.components.StatCard
import com.example.sploot.ui.theme.stageColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ABCompareViewModel(private val container: AppContainer) : ViewModel() {

    data class Ui(
        val sessionId: String? = null,
        val result: ComparisonCalculator.Result? = null,
        val sourceLabel: String = "import",
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui = _ui.asStateFlow()

    fun load(sessionId: String) {
        viewModelScope.launch {
            val ours = container.sessionRepo.hypnogram(sessionId)
                .associate { it.epochIndex to it.stage }
            val comparisons = container.sessionRepo.comparisons(sessionId)
            val ref = comparisons.associate { it.epochIndex to it.stage }
            _ui.value = if (ref.isEmpty()) {
                Ui(sessionId = sessionId)
            } else {
                Ui(
                    sessionId = sessionId,
                    sourceLabel = comparisons.first().sourceLabel,
                    result = ComparisonCalculator.compare(ours, ref),
                )
            }
        }
    }

    fun import(sessionId: String, text: String, label: String) {
        viewModelScope.launch {
            try {
                val parsed = withContext(Dispatchers.Default) { HypnogramCsvParser.parse(text, label) }
                container.sessionRepo.insertComparisons(
                    parsed.map { (idx, stage) -> ComparisonEntity(sessionId, label, idx, stage) },
                )
                _ui.value = _ui.value.copy(error = null)
                load(sessionId)
            } catch (e: IllegalArgumentException) {
                _ui.value = _ui.value.copy(error = e.message)
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ABCompareViewModel(container) as T
        }
    }
}

@Composable
fun ABCompareScreen(sessionId: String, viewModel: ABCompareViewModel) {
    val ui by viewModel.ui.collectAsState()
    var pasted by remember { mutableStateOf("") }
    val context = LocalContext.current

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().decodeToString()
            }
            if (text != null) viewModel.import(sessionId, text, "reference") else {
                viewModel.import(sessionId, "", "reference")
            }
        }
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("A/B compare", style = MaterialTheme.typography.headlineMedium)
            Text(
                "session $sessionId vs imported reference",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Import reference hypnogram", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "CSV / paste: `epoch,stage` or `time,stage`; stages W/N1/N2/N3/R or 0-3",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = pasted,
                        onValueChange = { pasted = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("0,W\n30,W\n60,N1\n…") },
                        minLines = 3,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.import(sessionId, pasted, "sleep2") },
                            enabled = pasted.isNotBlank(),
                        ) { Text("Parse pasted") }
                        Button(onClick = { filePicker.launch(arrayOf("text/*", "text/csv", "text/comma-separated-values", "*/*")) }) {
                            Text("Pick CSV file")
                        }
                    }
                    ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        ui.result?.let { r ->
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard("Concordance", "%.1f%%".format(r.concordancePct), Modifier.weight(1f))
                    StatCard("Cohen's κ", "%.3f".format(r.kappa), Modifier.weight(1f), accent = MaterialTheme.colorScheme.secondary)
                    StatCard("Epochs", r.n.toString(), Modifier.weight(1f))
                    StatCard("Coverage", "%.0f%%".format(r.coveragePct), Modifier.weight(1f))
                }
                if (r.coveragePct < 99.5) {
                    Text(
                        "Only %.0f%% of the two hypnograms overlap — epoch counts or start times differ; "
                            .format(r.coveragePct) +
                            "agreement is computed on the ${r.n} overlapping epochs only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Confusion matrix (rows: ours / cols: ${ui.sourceLabel})", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Spacer(Modifier.weight(0.25f))
                            SleepStages.NAMES.forEach {
                                Text(it, Modifier.weight(0.25f), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        (0 until 4).forEach { a ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .background(stageColor(a).copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 4.dp),
                            ) {
                                Text(SleepStages.NAMES[a], Modifier.weight(0.25f), style = MaterialTheme.typography.labelSmall)
                                (0 until 4).forEach { b ->
                                    val cell = r.confusion[a][b]
                                    Text(
                                        if (cell > 0) cell.toString() else "·",
                                        Modifier
                                            .weight(0.25f)
                                            .let { m -> if (a == b && cell > 0) m.border(1.dp, stageColor(a), RoundedCornerShape(4.dp)) else m },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (a == b) stageColor(a) else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Stage durations", style = MaterialTheme.typography.titleSmall)
                        (0 until 4).forEach { s ->
                            Text(
                                "${SleepStages.NAMES[s]}: Ours ${ComparisonCalculator.formatDuration(r.oursStageMin[s])}" +
                                    " | ${ui.sourceLabel}: ${ComparisonCalculator.formatDuration(r.refStageMin[s])}" +
                                    "  (Δ %+.0f min)".format(r.deltaMin[s]),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.9f),
                            )
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(48.dp)) }
    }
}
