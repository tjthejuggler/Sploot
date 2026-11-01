package com.example.sploot.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.sploot.R
import com.example.sploot.ui.theme.TextOnDark
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sploot.ble.DiscoveredDevice
import com.example.sploot.ble.PolarBleClient
import com.example.sploot.ble.PolarDeviceRepository
import com.example.sploot.ble.SensorStatus
import com.example.sploot.di.AppContainer
import com.example.sploot.recording.OrphanSession
import com.example.sploot.recording.SessionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RecordViewModel(private val container: AppContainer) : ViewModel() {

    val connectionState = container.polarClient.connectionState
    val battery = container.polarClient.batteryPercent
    val lastKnownDevice = container.deviceRepo.lastKnownDeviceId
    val discovered = container.deviceRepo.discovered
    val recordingStatus = container.sessionManager.status

    private val _hr = MutableStateFlow<Int?>(null)
    val hr = _hr.asStateFlow()

    private val _packetCount = MutableStateFlow(0L)
    val packetCount = _packetCount.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning = _scanning.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage = _statusMessage.asStateFlow()

    private var scanJob: Job? = null
    private var collectJob: Job? = null

    /** Un-finalized sessions found on disk (service/battery death mid-night). */
    private val _orphans = MutableStateFlow<List<OrphanSession>>(emptyList())
    val orphans = _orphans.asStateFlow()

    /** True while the database holds no finished sessions (drives the empty state). */
    private val _hasSessions = MutableStateFlow(false)
    val hasSessions = _hasSessions.asStateFlow()

    init {
        collectJob = viewModelScope.launch {
            container.polarClient.hrFlow.collect { _hr.value = it.hr }
        }
        refreshOrphans()
        viewModelScope.launch {
            _hasSessions.value = container.sessionRepo.sessions().isNotEmpty()
        }
    }

    /** Scans filesDir/sessions for interrupted recordings not yet in the DB. */
    fun refreshOrphans() {
        viewModelScope.launch {
            _orphans.value = container.sessionManager.findOrphans { sid ->
                container.sessionRepo.session(sid) != null
            }
        }
    }

    /** Marks the orphan complete and routes it into nightly processing. */
    fun recoverOrphan(orphan: OrphanSession) {
        container.sessionManager.markOrphanComplete(orphan)
        _orphans.value = _orphans.value - orphan
        lastStoppedSession.value = orphan.sessionId
    }

    fun dismissOrphan(orphan: OrphanSession) {
        _orphans.value = _orphans.value - orphan
    }

    /** Live status of the automatic morning capture (fetch → process → notify). */
    val captureStatus = container.morningCapture.status

    /** One-tap "Capture last night" — runs the Shizuku fetch + full pipeline. */
    fun captureLastNight() {
        container.appScope.launch {
            runCatching { container.morningCapture.captureNow() }
        }
    }

    /** Session id just stopped, for the caller to route into processing. */
    val lastStoppedSession = MutableStateFlow<String?>(null)

    fun btPermissionsGranted(): Boolean = container.polarClient.hasRequiredPermissions()

    fun scan() {
        if (!container.polarClient.hasRequiredPermissions()) {
            _statusMessage.value = "Bluetooth permissions required"
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

    fun connect(deviceId: String) {
        viewModelScope.launch {
            runCatching { container.deviceRepo.connectAndRemember(deviceId) }
                .onFailure { _statusMessage.value = "Connect failed: ${it.message}" }
        }
    }

    fun startRecording() {
        val device = lastKnownDevice.value ?: run {
            _statusMessage.value = "Pick a device first"
            return
        }
        container.sessionManager.startRecording(device)
    }

    fun stopRecording() {
        val id = recordingStatus.value.sessionId
        container.sessionManager.stopRecording()
        if (id != null) lastStoppedSession.value = id
    }

    fun newImportSessionId(): String = container.sessionManager.newSessionId() + "-snoop"

    fun consumeStatusMessage() {
        _statusMessage.value = null
    }

    override fun onCleared() {
        scanJob?.cancel()
        collectJob?.cancel()
        super.onCleared()
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = RecordViewModel(container) as T
        }
    }
}

@Composable
fun HomeScreen(
    viewModel: RecordViewModel,
    onProcessRecorded: (String) -> Unit,
    onProcessSnoop: (String, android.net.Uri) -> Unit,
) {
    val context = LocalContext.current
    val conn by viewModel.connectionState.collectAsState()
    val battery by viewModel.battery.collectAsState()
    val lastDevice by viewModel.lastKnownDevice.collectAsState()
    val discovered by viewModel.discovered.collectAsState()
    val recording by viewModel.recordingStatus.collectAsState()
    val hr by viewModel.hr.collectAsState()
    val packets by viewModel.packetCount.collectAsState()
    val scanning by viewModel.scanning.collectAsState()
    val statusMsg by viewModel.statusMessage.collectAsState()
    val stoppedSession by viewModel.lastStoppedSession.collectAsState()
    val orphans by viewModel.orphans.collectAsState()
    val hasSessions by viewModel.hasSessions.collectAsState()
    val captureStatus by viewModel.captureStatus.collectAsState()

    // Route into processing right after a stop (or orphan recovery)
    androidx.compose.runtime.LaunchedEffect(stoppedSession) {
        stoppedSession?.let { sid ->
            viewModel.lastStoppedSession.value = null
            onProcessRecorded(sid)
        }
    }

    // Runtime permissions: Bluetooth + notifications
    val btPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) viewModel.scan()
        else viewModel.consumeStatusMessage()
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val snoopPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val sid = viewModel.newImportSessionId()
            onProcessSnoop(sid, uri)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Quiet header: misty eucalypt/moonlight wash fading into the page,
        // a faint distant koala silhouette drifting top-right (the app's one
        // koala presence), and airy greeting typography.
        Box(
            Modifier
                .fillMaxWidth()
                .height(230.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.img_mist),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // fade the wash into the surface color
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.background.copy(alpha = 0.30f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.86f),
                                MaterialTheme.colorScheme.background,
                            ),
                        ),
                    ),
            )
            Image(
                painter = painterResource(R.drawable.img_koala_ghost),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 24.dp, end = 8.dp)
                    .size(150.dp),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(start = 28.dp, end = 28.dp, bottom = 20.dp),
                contentAlignment = Alignment.BottomStart,
            ) {
                Column {
                    Text(
                        "Sploot",
                        style = MaterialTheme.typography.displaySmall,
                        color = TextOnDark,
                    )
                }
            }
        }

        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        if (!hasSessions) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.img_eucalypt_sprig),
                        contentDescription = null,
                        modifier = Modifier.size(88.dp),
                    )
                    Text("no nights yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "record a first night — mornings will gather here",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Sensor", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Device: ${lastDevice ?: "none paired"}",
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
                    Text("HR: ${hr?.let { "$it bpm" } ?: "--"} · packets: $packets")
                    if (statusMsg != null) {
                        Text(statusMsg!!, color = MaterialTheme.colorScheme.error)
                    }
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
                        if (lastDevice != null && !recording.active) {
                            OutlinedButton(onClick = { viewModel.connect(lastDevice!!) }) { Text("Reconnect") }
                        }
                    }
                }
            }
        }

        if (discovered.isNotEmpty()) {
            item { Text("Discovered", style = MaterialTheme.typography.titleSmall) }
            items(discovered, key = { it.deviceId }) { d: DiscoveredDevice ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(d.name.ifBlank { d.deviceId })
                            Text("RSSI ${d.rssi}", style = MaterialTheme.typography.labelSmall)
                        }
                        Button(onClick = { viewModel.connect(d.deviceId) }) { Text("Pair") }
                    }
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Recording", style = MaterialTheme.typography.titleMedium)
                    if (recording.active) {
                        Text("Session ${recording.sessionId} running", color = MaterialTheme.colorScheme.secondary)
                        CircularProgressIndicator(Modifier.height(20.dp))
                    } else {
                        Text("Idle")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!recording.active) {
                            Button(
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= 33) {
                                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                    viewModel.startRecording()
                                },
                                enabled = lastDevice != null,
                            ) { Text("Start overnight recording") }
                        } else {
                            Button(onClick = { viewModel.stopRecording() }) { Text("Stop recording") }
                        }
                        OutlinedButton(onClick = { snoopPicker.launch(arrayOf("*/*")) }) {
                            Text("Import btsnoop log")
                        }
                    }
                    if (!recording.active) {
                        Button(onClick = { viewModel.captureLastNight() }) {
                            Text("Capture last night")
                        }
                    }
                }
            }
        }

        // Subtle morning-capture status (auto-run on open or one-tap capture)
        val cs = captureStatus
        if (cs !is com.example.sploot.ble.snoop.MorningCaptureCoordinator.Status.Idle) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = when (cs) {
                            is com.example.sploot.ble.snoop.MorningCaptureCoordinator.Status.Done ->
                                MaterialTheme.colorScheme.secondaryContainer
                            is com.example.sploot.ble.snoop.MorningCaptureCoordinator.Status.Failed ->
                                MaterialTheme.colorScheme.errorContainer
                            else -> MaterialTheme.colorScheme.surfaceContainer
                        },
                    ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            when (cs) {
                                is com.example.sploot.ble.snoop.MorningCaptureCoordinator.Status.Running ->
                                    "Auto capture: ${cs.step}…"
                                is com.example.sploot.ble.snoop.MorningCaptureCoordinator.Status.Done ->
                                    "Auto capture ✓"
                                is com.example.sploot.ble.snoop.MorningCaptureCoordinator.Status.Failed ->
                                    "Auto capture failed"
                                else -> ""
                            },
                            style = MaterialTheme.typography.titleSmall,
                        )
                        val detail = when (cs) {
                            is com.example.sploot.ble.snoop.MorningCaptureCoordinator.Status.Running -> cs.step
                            is com.example.sploot.ble.snoop.MorningCaptureCoordinator.Status.Done -> cs.message
                            is com.example.sploot.ble.snoop.MorningCaptureCoordinator.Status.Failed -> cs.message
                            else -> ""
                        }
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (orphans.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Interrupted recording found",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        orphans.forEach { o ->
                            Column(Modifier.padding(vertical = 4.dp)) {
                                Text(
                                    "Session ${o.sessionId} · ${o.durationMs / 3_600_000} h " +
                                        "${(o.durationMs % 3_600_000) / 60_000} min · ${o.ibiCount} IBIs",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { viewModel.recoverOrphan(o) }) {
                                        Text("Recover & process")
                                    }
                                    OutlinedButton(onClick = { viewModel.dismissOrphan(o) }) {
                                        Text("Ignore")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(48.dp)) }
        }
    }
}
