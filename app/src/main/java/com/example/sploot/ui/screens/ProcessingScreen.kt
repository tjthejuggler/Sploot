package com.example.sploot.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sploot.ble.snoop.SnoopIngestSession
import com.example.sploot.di.AppContainer
import com.example.sploot.processing.ProcessingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProcessingViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val state = _state.asStateFlow()

    fun run(sessionId: String, snoopUri: Uri?) {
        if (_state.value != ProcessingState.Idle) return
        viewModelScope.launch {
            if (snoopUri != null) {
                runSnoop(sessionId, snoopUri)
            } else {
                container.processNight.processRecordedSession(
                    sessionId = sessionId,
                    sessionsRoot = container.sessionsRoot(),
                    onProgress = { _state.value = it },
                )
            }
        }
    }

    private suspend fun runSnoop(sessionId: String, uri: Uri) = withContext(Dispatchers.IO) {
        val ingest = SnoopIngestSession {
            container.context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("cannot open $uri")
        }
        container.processNight.processSnoopImport(
            sessionId = sessionId,
            decode = { onSample -> ingest.run(onSample) },
            onProgress = { _state.value = it },
        )
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ProcessingViewModel(container) as T
        }
    }
}

@Composable
fun ProcessingScreen(
    sessionId: String,
    snoopUri: Uri?,
    viewModel: ProcessingViewModel,
    onDone: (String) -> Unit,
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(sessionId, snoopUri) { viewModel.run(sessionId, snoopUri) }

    val done = state as? ProcessingState.Done
    LaunchedEffect(done) { done?.let { onDone(it.sessionId) } }

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (val s = state) {
            is ProcessingState.Idle, is ProcessingState.Running -> {
                CircularProgressIndicator()
                Text("Processing night", style = MaterialTheme.typography.titleLarge)
                val step = (s as? ProcessingState.Running)
                Text(step?.step ?: "starting…")
                LinearProgressIndicator(
                    progress = { step?.fraction ?: 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is ProcessingState.Error -> {
                Text("Processing failed", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Text(s.message, Modifier.padding(16.dp))
                }
                Button(onClick = { onDone(sessionId) }) { Text("Back") }
            }
            is ProcessingState.Done -> {
                Text("Done", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
