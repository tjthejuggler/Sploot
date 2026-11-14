package com.example.sploot.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.example.sploot.ble.snoop.SnoopAutoFetcher
import com.example.sploot.ble.snoop.SnoopIngestSession
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sploot.ble.SensorStatus
import com.example.sploot.di.AppContainer
import com.example.sploot.ml.EngineRegistry
import com.example.sploot.ui.components.NightCard
import com.example.sploot.ui.components.SectionTitle
import android.Manifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    data class Ui(
        val engineId: String = EngineRegistry.SEQ,
        val message: String? = null,
        val autoEnabled: Boolean = true,
        val testBusy: Boolean = false,
        val testResult: String? = null,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui = _ui.asStateFlow()

    // ---- sensor (moved from Home) ----
    val connectionState = container.polarClient.connectionState
    val battery = container.polarClient.batteryPercent
    val lastKnownDevice = container.deviceRepo.lastKnownDeviceId
    val discovered = container.deviceRepo.discovered

    private val _scanning = MutableStateFlow(false)
    val scanning = _scanning.asStateFlow()

    private var scanJob: kotlinx.coroutines.Job? = null

    fun btPermissionsGranted(): Boolean = container.polarClient.hasRequiredPermissions()

    fun scan() {
        if (!container.polarClient.hasRequiredPermissions()) {
            _ui.value = _ui.value.copy(message = "Bluetooth permissions required")
            return
        }
        _scanning.value = true
        scanJob = viewModelScope.launch {
            try {
                container.deviceRepo.scan().collect { /* discovered StateFlow updates */ }
            } finally {
                _scanning.value = false
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        _scanning.value = false
    }

    fun connect(deviceId: String, name: String? = null) {
        viewModelScope.launch {
            runCatching { container.deviceRepo.connectAndRemember(deviceId, name) }
                .onFailure { _ui.value = _ui.value.copy(message = "Connect failed: ${it.message}") }
        }
    }

    init {
        viewModelScope.launch {
            container.settingsStore.engineId.collect { id ->
                _ui.value = _ui.value.copy(engineId = id)
            }
        }
        viewModelScope.launch {
            container.snoopCaptureStore.autoEnabled.collect { on ->
                _ui.value = _ui.value.copy(autoEnabled = on)
            }
        }
    }

    fun setAutoEnabled(enabled: Boolean) {
        viewModelScope.launch { container.snoopCaptureStore.setAutoEnabled(enabled) }
    }

    /**
     * "Test fetch now": runs the real fallback chain (direct cat → bugreportz),
     * parses the fetched log for packet/Polar-sample counts and reports
     * bytes / packets / Polar share, or the precise failure reason.
     */
    fun testFetch() {
        if (_ui.value.testBusy) return
        _ui.value = _ui.value.copy(testBusy = true, testResult = null)
        viewModelScope.launch {
            // Safety net: any unexpected throw must surface as a status line,
            // never as an unhandled coroutine exception (process death).
            val result = try {
                withContext(Dispatchers.IO) {
                val prevSize = container.snoopCaptureStore.currentSize()
                when (val r = container.snoopAutoFetcher.fetch(prevSize)) {
                    is SnoopAutoFetcher.Result.Unavailable -> "Unavailable: ${r.reason}"
                    is SnoopAutoFetcher.Result.Failure -> "Failed: ${r.reason}"
                    is SnoopAutoFetcher.Result.Success -> {
                        val summary = runCatching {
                            SnoopIngestSession { r.file.inputStream() }.run { }
                        }.getOrNull()
                        val polar = summary?.let {
                            it.hrSamples + it.ibiSamples + it.accSamples + it.ecgSamples
                        }
                        buildString {
                            append(
                                "Fetched ${r.logSizeBytes} bytes via ${r.method}" +
                                    (r.bugreportZipBytes?.let { " (bugreport zip $it B)" } ?: ""),
                            )
                            if (summary != null) {
                                append(
                                    " — ${summary.btsnoopRecords} snoop records, " +
                                        "${summary.attNotifications} ATT notifications",
                                )
                                if (polar != null) {
                                    append(
                                        ", $polar Polar samples (" +
                                            (if (summary.btsnoopRecords > 0)
                                                polar * 100L / summary.btsnoopRecords else 0) + "% of records)",
                                    )
                                }
                            } else {
                                append(" — parse check failed (bad content?)")
                            }
                            r.warnings.forEach { append("\n⚠ $it") }
                        }
                    }
                }
            }
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Exception) {
                "Failed: unexpected error — ${e.message ?: e.javaClass.simpleName}"
            }
            _ui.value = _ui.value.copy(testBusy = false, testResult = result)
        }
    }

    fun setEngine(id: String) {
        viewModelScope.launch {
            runCatching { container.settingsStore.setEngineId(id) }
                .onSuccess { _ui.value = _ui.value.copy(message = "Engine set to $id") }
                .onFailure { _ui.value = _ui.value.copy(message = it.message) }
        }
    }

    private val _restaging = MutableStateFlow(false)
    val restaging = _restaging.asStateFlow()

    /** Re-stages every stored night with the current engine's raw-IBI model. */
    fun restageAllNights() {
        if (_restaging.value) return
        _restaging.value = true
        viewModelScope.launch {
            val outcome = try {
                val o = container.restageAll.restageAll()
                "Re-staged ${o.restaged} night(s)" + if (o.skipped > 0) ", skipped ${o.skipped}" else ""
            } catch (e: Exception) {
                "Re-staging failed: ${e.message ?: e.javaClass.simpleName}"
            }
            _restaging.value = false
            _ui.value = _ui.value.copy(message = outcome)
        }
    }

    /** Installs `<id>.onnx` + `<id>.json` picked from local storage. */
    fun installModel(engineId: String, modelUri: Uri, metaUri: Uri) {
        viewModelScope.launch {
            try {
                val resolver = container.context.contentResolver
                withContext(Dispatchers.IO) {
                    resolver.openInputStream(modelUri)!!.use { m ->
                        resolver.openInputStream(metaUri)!!.use { j ->
                            container.modelInstaller().installReplacement(engineId, m, j)
                        }
                    }
                }
                _ui.value = _ui.value.copy(message = "Installed replacement model for $engineId")
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(message = "Install failed: ${e.message}")
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(container) as T
        }
    }
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onOpenExport: () -> Unit = {}) {
    val ui by viewModel.ui.collectAsState()
    val context = LocalContext.current
    var pendingEngine by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var modelUri by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Uri?>(null) }

    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            if (modelUri == null) modelUri = uri else {
                viewModel.installModel(pendingEngine ?: ui.engineId, modelUri!!, uri)
                modelUri = null
            }
        }
    }

    val btPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) viewModel.scan()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        SensorCard(viewModel, btLauncher, btPermissions)

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Staging engine", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(EngineRegistry.IBI, EngineRegistry.SEQ, EngineRegistry.GBDT).forEach { id ->
                        FilterChip(
                            selected = ui.engineId == id,
                            onClick = { viewModel.setEngine(id) },
                            label = { Text(id.removePrefix("sploot-")) },
                        )
                    }
                }
                val restaging by viewModel.restaging.collectAsState()
                Button(
                    enabled = !restaging,
                    onClick = { viewModel.restageAllNights() },
                ) { Text(if (restaging) "Re-staging…" else "Re-stage all nights with current engine") }
                Button(onClick = {
                    pendingEngine = ui.engineId
                    modelUri = null
                    modelPicker.launch(arrayOf("*/*"))
                }) { Text("Install replacement model (.onnx)") }
                if (modelUri != null) {
                    Text("Now pick the metadata .json…", style = MaterialTheme.typography.labelSmall)
                    Button(onClick = { modelPicker.launch(arrayOf("*/*")) }) { Text("Pick metadata JSON") }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Battery optimization", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Exempt Sploot from battery optimizations so overnight recording is not killed.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }
                }) { Text("Request exemption") }
            }
        }

        AutomaticCaptureCard(viewModel)

        // Export (moved from the old bottom-bar tab)
        NightCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Export")
                Text(
                    "Export sessions as CSV or EDF+.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onOpenExport) { Text("Open export screen") }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("About", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Sploot — autonomic sleep staging & recovery.\n" +
                        "Default engine: sploot-ibi-cnn (raw-IBI network, validated vs sleep² at κ 0.74); " +
                        "sploot-seq/gbdt are placeholders. Retrain via ml_pipeline/ " +
                        "and install replacements above. Licensed under the project license; Polar BLE SDK " +
                        "and ONNX Runtime are subject to their own licenses.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        ui.message?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
        Spacer(Modifier.height(48.dp))
    }
}

/**
 * "Automatic capture" section: the auto-on-open toggle and the
 * "Test fetch now" button with a bytes/packets/Polar-share report.
 */
@Composable
private fun AutomaticCaptureCard(viewModel: SettingsViewModel) {
    val ui by viewModel.ui.collectAsState()

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Automatic capture", style = MaterialTheme.typography.titleMedium)

            Text(
                "Nights are pulled straight off the Polar Verity Sense whenever it " +
                    "connects and today's night is missing. A manual log import stays " +
                    "available as a fallback from the home screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Switch(
                    checked = ui.autoEnabled,
                    onCheckedChange = { viewModel.setAutoEnabled(it) },
                )
                Text(
                    "  Capture automatically when the sensor connects",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Button(onClick = { viewModel.testFetch() }, enabled = !ui.testBusy) {
                    Text(if (ui.testBusy) "Fetching…" else "Test fetch now")
                }
                if (ui.testBusy) CircularProgressIndicator(Modifier.height(18.dp))
            }

            ui.testResult?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.startsWith("Fetched")) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** Sensor management (moved from Home): pairing, scan, connect, live status. */
@Composable
private fun SensorCard(
    viewModel: SettingsViewModel,
    btLauncher: androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>,
    btPermissions: Array<String>,
) {
    val conn by viewModel.connectionState.collectAsState()
    val battery by viewModel.battery.collectAsState()
    val lastDevice by viewModel.lastKnownDevice.collectAsState()
    val discovered by viewModel.discovered.collectAsState()
    val scanning by viewModel.scanning.collectAsState()

    NightCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Sensor")
            Text(
                "Device: ${lastDevice?.let { POLAR_DEVICE_NAME } ?: "none paired"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Connection: ${conn.name.lowercase().replaceFirstChar { it.uppercase() }}",
                color = when (conn) {
                    SensorStatus.ConnectionState.CONNECTED -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text("Battery: ${battery?.let { "$it%" } ?: "--"}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (viewModel.scanning.value) {
                            viewModel.stopScan()
                        } else if (viewModel.btPermissionsGranted()) {
                            viewModel.scan()
                        } else {
                            btLauncher.launch(btPermissions)
                        }
                    },
                ) {
                    Text(if (scanning) "Stop scan" else "Scan for devices")
                }
                if (lastDevice != null) {
                    OutlinedButton(onClick = { viewModel.connect(lastDevice!!) }) { Text("Reconnect") }
                }
            }
            if (discovered.isNotEmpty()) {
                Text("Discovered", style = MaterialTheme.typography.titleSmall)
                discovered.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(d.name.ifBlank { d.deviceId })
                            Text("RSSI ${d.rssi}", style = MaterialTheme.typography.labelSmall)
                        }
                        Button(onClick = { viewModel.connect(d.deviceId, d.name) }) { Text("Pair") }
                    }
                }
            }
        }
    }
}
