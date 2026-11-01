package com.example.sploot.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sploot.data.db.SessionEntity
import com.example.sploot.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ExportFormat(val label: String) {
    CSV("CSV bundle"),
    JSON("JSON"),
    EDF("EDF+"),
    ZIP("ZIP"),
}

class ExportViewModel(private val container: AppContainer) : ViewModel() {

    data class Ui(
        val sessions: List<SessionEntity> = emptyList(),
        val selected: String? = null,
        val format: ExportFormat = ExportFormat.CSV,
        val running: Boolean = false,
        val progress: Float = 0f,
        val step: String? = null,
        val doneMessage: String? = null,
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val sessions = container.sessionRepo.sessions()
            _ui.value = _ui.value.copy(sessions = sessions, selected = sessions.firstOrNull()?.id)
        }
    }

    fun select(id: String) {
        _ui.value = _ui.value.copy(selected = id)
    }

    fun setFormat(f: ExportFormat) {
        _ui.value = _ui.value.copy(format = f)
    }

    fun export(treeUri: Uri) {
        val sid = _ui.value.selected ?: return
        _ui.value = _ui.value.copy(running = true, error = null, doneMessage = null, progress = 0f)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val progress: (Float, String) -> Unit = { f, s ->
                        _ui.value = _ui.value.copy(progress = f, step = s)
                    }
                    when (_ui.value.format) {
                        ExportFormat.CSV -> container.exportManager.exportCsv(sid, treeUri, progress)
                        ExportFormat.JSON -> container.exportManager.exportJson(sid, treeUri, progress)
                        ExportFormat.EDF -> container.exportManager.exportEdfPlus(sid, treeUri, progress)
                        ExportFormat.ZIP -> container.exportManager.exportZip(sid, treeUri, progress)
                    }
                }
                _ui.value = _ui.value.copy(running = false, doneMessage = "Exported ${_ui.value.format.label}")
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(running = false, error = e.message ?: "export failed")
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ExportViewModel(container) as T
        }
    }
}

@Composable
fun ExportScreen(viewModel: ExportViewModel) {
    val ui by viewModel.ui.collectAsState()

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.export(uri)
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Export", style = MaterialTheme.typography.headlineMedium) }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Session", style = MaterialTheme.typography.titleMedium)
                    if (ui.sessions.isEmpty()) {
                        Text("No sessions recorded yet.")
                    }
                    ui.sessions.take(10).forEach { s ->
                        FilterChip(
                            selected = ui.selected == s.id,
                            onClick = { viewModel.select(s.id) },
                            label = { Text(s.id) },
                        )
                    }
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Format", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExportFormat.entries.forEach { f ->
                            FilterChip(
                                selected = ui.format == f,
                                onClick = { viewModel.setFormat(f) },
                                label = { Text(f.label) },
                            )
                        }
                    }
                    Button(
                        onClick = { treePicker.launch(null) },
                        enabled = ui.selected != null && !ui.running,
                    ) { Text("Choose destination & export") }
                    if (ui.running) {
                        LinearProgressIndicator(progress = { ui.progress }, modifier = Modifier.fillMaxWidth())
                        ui.step?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    }
                    ui.doneMessage?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
                    ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        items(emptyList<String>()) {}
        item { Spacer(Modifier.height(48.dp)) }
    }
}
