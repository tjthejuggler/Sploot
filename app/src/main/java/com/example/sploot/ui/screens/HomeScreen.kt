package com.example.sploot.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.sploot.R
import com.example.sploot.SleepDates
import com.example.sploot.data.db.SessionEntity
import com.example.sploot.data.repo.SleepSummary
import com.example.sploot.di.AppContainer
import com.example.sploot.recording.OrphanSession
import com.example.sploot.ui.components.NightCard
import com.example.sploot.ui.components.NightStageBar
import com.example.sploot.ui.theme.TextOnDark
import com.example.sploot.ui.theme.stageColor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sploot.ble.PolarDeviceRepository
import com.example.sploot.ble.SensorStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/** Friendly display name for the paired sensor (it is a Polar Verity Sense). */
val POLAR_DEVICE_NAME = "Polar Verity Sense"

fun deviceDisplayName(repo: PolarDeviceRepository): String? =
    repo.lastKnownDeviceId.value?.let { repo.lastKnownDeviceName.value ?: POLAR_DEVICE_NAME }

class RecordViewModel(private val container: AppContainer) : ViewModel() {

    val connectionState = container.polarClient.connectionState
    val battery = container.polarClient.batteryPercent
    val lastKnownDevice = container.deviceRepo.lastKnownDeviceId
    val deviceName = container.deviceRepo.lastKnownDeviceName
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

    /** Night rows for the home list (one per recorded night, newest first). */
    data class NightRow(
        val session: SessionEntity,
        val summary: SleepSummary?,
        val stages: List<Int> = emptyList(),
    )

    private val _nights = MutableStateFlow<List<NightRow>>(emptyList())
    val nights = _nights.asStateFlow()

    /** True when a night dated with *today's* wake-up date is already stored. */
    private val _hasTodayNight = MutableStateFlow(false)
    val hasTodayNight = _hasTodayNight.asStateFlow()

    init {
        collectJob = viewModelScope.launch {
            container.polarClient.hrFlow.collect { _hr.value = it.hr }
        }
        // Auto-download on connect: whenever the sensor connects (and no
        // recording is running), pull any new nights off it — but only when
        // today's night isn't already in the app. Once per connection.
        viewModelScope.launch {
            var pulledThisConnection = false
            container.polarClient.connectionState.collect { state ->
                when (state) {
                    SensorStatus.ConnectionState.CONNECTED -> {
                        if (!pulledThisConnection &&
                            container.morningCapture.status.value is com.example.sploot.ble.snoop.MorningCaptureCoordinator.Status.Idle &&
                            !recordingStatus.value.active
                        ) {
                            pulledThisConnection = true
                            if (!hasTodaysNight()) {
                                captureLastNight()
                            }
                        }
                    }
                    SensorStatus.ConnectionState.DISCONNECTED -> pulledThisConnection = false
                    else -> Unit
                }
            }
        }
        refreshOrphans()
        refreshNights()
        // After a capture finishes, re-check whether today's night arrived.
        viewModelScope.launch {
            container.morningCapture.status.collect {
                if (it is com.example.sploot.ble.snoop.MorningCaptureCoordinator.Status.Done) refreshNights()
            }
        }
    }

    /** True when the DB already holds a night whose wake-up morning is today. */
    private suspend fun hasTodaysNight(): Boolean {
        val today = LocalDate.now().toString()
        return container.sessionRepo.sessions().any {
            it.endEpochMs != null && SleepDates.nightDate(it.startEpochMs, it.endEpochMs).toString() == today
        }
    }

    fun refreshNights() {
        viewModelScope.launch {
            val sessions = container.sessionRepo.sessions()
            _nights.value = sessions.map { s ->
                NightRow(
                    session = s,
                    summary = container.sessionRepo.summary(s.id),
                    stages = container.sessionRepo.hypnogram(s.id).map { it.stage },
                )
            }
            _hasTodayNight.value = hasTodaysNight()
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

    /**
     * Downloads the previous night(s) from the sensor: connect-aware pull of
     * the armband's stored offline recordings. Runs automatically on connect
     * when today's night is missing; this is the retry entry point.
     */
    fun captureLastNight() {
        com.example.sploot.ble.snoop.MorningDownloadService.start(container.context)
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

    fun startRecording() {
        val device = lastKnownDevice.value ?: run {
            _statusMessage.value = "Pair the sensor first (Settings)"
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
    onOpenNight: (String) -> Unit,
) {
    val context = LocalContext.current
    val conn by viewModel.connectionState.collectAsState()
    val battery by viewModel.battery.collectAsState()
    val lastDevice by viewModel.lastKnownDevice.collectAsState()
    val deviceName by viewModel.deviceName.collectAsState()
    val recording by viewModel.recordingStatus.collectAsState()
    val hr by viewModel.hr.collectAsState()
    val scanning by viewModel.scanning.collectAsState()
    val statusMsg by viewModel.statusMessage.collectAsState()
    val stoppedSession by viewModel.lastStoppedSession.collectAsState()
    val orphans by viewModel.orphans.collectAsState()
    val nights by viewModel.nights.collectAsState()
    val hasTodayNight by viewModel.hasTodayNight.collectAsState()
    val captureStatus by viewModel.captureStatus.collectAsState()

    // Route into processing right after a stop (or orphan recovery)
    LaunchedEffect(stoppedSession) {
        stoppedSession?.let { sid ->
            viewModel.lastStoppedSession.value = null
            onProcessRecorded(sid)
        }
    }
    // Keep the night list fresh whenever the screen reappears
    LaunchedEffect(Unit) { viewModel.refreshNights() }

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
        // a faint distant koala silhouette drifting top-right.
        Box(
            Modifier
                .fillMaxWidth()
                .height(150.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.img_mist),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
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
                    .padding(top = 8.dp, end = 8.dp)
                    .size(110.dp),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(start = 28.dp, end = 28.dp, bottom = 6.dp),
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
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Previous-night download: only attempted while today's night is
            // missing; shows live progress by device name.
            val cs = captureStatus
            val deviceLabel = if (lastDevice != null) (deviceName ?: POLAR_DEVICE_NAME) else null
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
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Last night's sleep", style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                deviceLabel != null ->
                                    "$deviceLabel · ${conn.name.lowercase()}${battery?.let { " · $it%" } ?: ""}"
                                else -> "no Polar Verity Sense paired yet — pair one in Settings"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        when (cs) {
                            is com.example.sploot.ble.snoop.MorningCaptureCoordinator.Status.Idle ->
                                when {
                                    hasTodayNight -> Text(
                                        "today's night is already saved",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    conn == SensorStatus.ConnectionState.CONNECTED -> Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        CircularProgressIndicator(Modifier.height(18.dp))
                                        Spacer(Modifier.size(8.dp))
                                        Text("checking the sensor for new nights…")
                                    }
                                    else -> Text(
                                        "new nights download automatically when the sensor connects",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            is com.example.sploot.ble.snoop.MorningCaptureCoordinator.Status.Running ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.height(18.dp))
                                    Spacer(Modifier.size(8.dp))
                                    Text("${cs.step}…")
                                }
                            is com.example.sploot.ble.snoop.MorningCaptureCoordinator.Status.Done ->
                                Text(
                                    "✓ ${cs.message}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            is com.example.sploot.ble.snoop.MorningCaptureCoordinator.Status.Failed ->
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(cs.message, style = MaterialTheme.typography.bodySmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { viewModel.captureLastNight() }) {
                                            Text("Try again")
                                        }
                                        OutlinedButton(onClick = { snoopPicker.launch(arrayOf("*/*")) }) {
                                            Text("Import btsnoop log")
                                        }
                                    }
                                }
                        }
                    }
                }
            }

            // compact recording card
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Recording", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "$POLAR_DEVICE_NAME · ${conn.name.lowercase()}${battery?.let { " · $it%" } ?: ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (hr != null) {
                            Text(
                                "HR: ${hr} bpm",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (statusMsg != null) {
                            Text(statusMsg!!, color = MaterialTheme.colorScheme.error)
                        }
                        if (recording.active) {
                            Text("Session ${recording.sessionId} running", color = MaterialTheme.colorScheme.secondary)
                            CircularProgressIndicator(Modifier.height(20.dp))
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

            // ---- the days: one card per night, newest first -------------------
            if (nights.isNotEmpty()) {
                item {
                    Text(
                        "your nights",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                items(nights, key = { it.session.id }) { row ->
                    NightListCard(row = row, onClick = { onOpenNight(row.session.id) })
                }
            }

            item { Spacer(Modifier.height(48.dp)) }
        }
    }
}

private val listDayFmt = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())

/** Sleep2-style diary entry: date header, duration + time range, stage bar, stats. */
@Composable
private fun NightListCard(row: RecordViewModel.NightRow, onClick: () -> Unit) {
    val s = row.session
    val sum = row.summary

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            // sleep² convention: night dated by its wake-up morning
            listDayFmt.format(Date(s.endEpochMs ?: s.startEpochMs)),
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
                        "${clock(s.startEpochMs)} – ${clock((s.endEpochMs ?: s.startEpochMs))}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

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
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
