package com.example.sploot.ble

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.polar.sdk.api.model.PolarDeviceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.min

private val Context.devicePrefs: DataStore<Preferences> by preferencesDataStore(name = "polar_device")

/** Exponential-backoff schedule for reconnection attempts (pure, unit-testable). */
object ReconnectionBackoff {
    const val INITIAL_MS = 1_000L
    const val MAX_MS = 60_000L

    /** Delay before attempt [attempt] (0-based). Doubles per attempt, capped at [MAX_MS]. */
    fun delayForAttempt(attempt: Int): Long {
        require(attempt >= 0) { "attempt must be >= 0" }
        var d = INITIAL_MS
        repeat(attempt) { d = min(d * 2, MAX_MS) }
        return d
    }
}

/** Discovery result row. */
data class DiscoveredDevice(
    val deviceId: String,
    val name: String,
    val rssi: Int,
    val connectable: Boolean,
)

/**
 * Device discovery, last-known-device persistence (DataStore), battery
 * queries and automatic reconnection with exponential backoff
 * (1s -> 2s -> 4s -> ... -> 60s cap, reset on successful connect).
 *
 * The reconnection loop is started with [startAutoReconnect] (typically from
 * the recording service) and stopped with [stopAutoReconnect].
 */
class PolarDeviceRepository(
    private val context: Context,
    private val client: PolarBleClient,
    private val scope: CoroutineScope,
) {

    companion object {
        private const val TAG = "PolarDeviceRepository"
        private val KEY_DEVICE_ID = stringPreferencesKey("last_device_id")
        private val KEY_DEVICE_NAME = stringPreferencesKey("last_device_name")
        private const val SCAN_TIMEOUT_MS = 15_000L
    }

    private val _lastKnownDeviceId = MutableStateFlow<String?>(null)
    val lastKnownDeviceId: StateFlow<String?> = _lastKnownDeviceId.asStateFlow()

    /** Human-readable name of the last-known device (e.g. "Polar Verity Sense"), for UI display. */
    private val _lastKnownDeviceName = MutableStateFlow<String?>(null)
    val lastKnownDeviceName: StateFlow<String?> = _lastKnownDeviceName.asStateFlow()

    private val _discovered = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discovered: StateFlow<List<DiscoveredDevice>> = _discovered.asStateFlow()

    private var reconnectJob: Job? = null

    /** Loads the persisted device id/name into the last-known flows. */
    suspend fun loadLastKnownDevice() {
        val prefs = context.devicePrefs.data.first()
        _lastKnownDeviceId.value = prefs[KEY_DEVICE_ID]
        _lastKnownDeviceName.value = prefs[KEY_DEVICE_NAME]
    }

    suspend fun setLastKnownDevice(deviceId: String, name: String? = null) {
        context.devicePrefs.edit {
            it[KEY_DEVICE_ID] = deviceId
            if (name != null) it[KEY_DEVICE_NAME] = name
        }
        _lastKnownDeviceId.value = deviceId
        if (name != null) _lastKnownDeviceName.value = name
    }

    /** Updates just the stored display name (e.g. refreshed during a scan). */
    suspend fun setLastKnownDeviceName(name: String) {
        context.devicePrefs.edit { it[KEY_DEVICE_NAME] = name }
        _lastKnownDeviceName.value = name
    }

    /**
     * Scans for connectable Polar devices until [timeoutMs] or cancellation.
     * Caller must ensure runtime permissions (BLUETOOTH_SCAN / fine location
     * on API < 31) are granted; returns an empty flow otherwise.
     */
    fun scan(timeoutMs: Long = SCAN_TIMEOUT_MS): Flow<DiscoveredDevice> =
        kotlinx.coroutines.flow.flow {
            if (!client.hasRequiredPermissions()) {
                Log.w(TAG, "scan requested without runtime permissions")
                return@flow
            }
            val seen = mutableMapOf<String, DiscoveredDevice>()
            try {
                kotlinx.coroutines.withTimeout(timeoutMs) {
                    client.api.searchForDevice().rxToFlow().collect { info: PolarDeviceInfo ->
                        if (info.isConnectable) {
                            val row = DiscoveredDevice(info.deviceId, info.name, info.rssi, true)
                            seen[info.deviceId] = row
                            _discovered.value = seen.values.sortedByDescending { it.rssi }
                            // Keep the stored display name fresh for the paired sensor.
                            if (info.deviceId == _lastKnownDeviceId.value &&
                                info.name.isNotBlank() && info.name != _lastKnownDeviceName.value
                            ) {
                                runCatching { setLastKnownDeviceName(info.name) }
                            }
                            emit(row)
                        }
                    }
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                // scan window elapsed
            }
        }

    /** Connects to [deviceId], persists it (plus an optional display name), and (re)starts the reconnection loop. */
    suspend fun connectAndRemember(deviceId: String, name: String? = null) {
        setLastKnownDevice(deviceId, name)
        client.connect(deviceId)
        startAutoReconnect(deviceId)
    }

    /**
     * Automatic reconnection loop: whenever the client reports DISCONNECTED,
     * retry [PolarBleClient.connect] after [ReconnectionBackoff] delays until
     * the connection sticks. Backoff resets to 1 s on a successful connect.
     */
    fun startAutoReconnect(deviceId: String) {
        stopAutoReconnect()
        reconnectJob = scope.launch {
            var attempt = 0
            client.connectionState.collect { state ->
                when (state) {
                    SensorStatus.ConnectionState.CONNECTED -> attempt = 0
                    SensorStatus.ConnectionState.DISCONNECTED -> {
                        val delayMs = ReconnectionBackoff.delayForAttempt(attempt)
                        Log.i(TAG, "reconnect attempt #$attempt in ${delayMs}ms")
                        delay(delayMs)
                        attempt++
                        // Ignore result; state machine drives the next iteration.
                        try {
                            client.connect(deviceId)
                        } catch (e: Exception) {
                            Log.w(TAG, "reconnect failed", e)
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    fun stopAutoReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    /** Current battery percentage, if the device has reported it. */
    fun battery(): Flow<Int?> = client.batteryPercent

    /** Convenience: last-known device id as a one-shot flow. */
    fun lastKnownDeviceFlow(): Flow<String?> = context.devicePrefs.data.map { it[KEY_DEVICE_ID] }
}
