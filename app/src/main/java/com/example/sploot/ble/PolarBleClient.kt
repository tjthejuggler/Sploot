package com.example.sploot.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.polar.androidcommunications.api.ble.model.DisInfo
import com.polar.androidcommunications.api.ble.model.gatt.client.pmd.PmdMeasurementType
import com.polar.sdk.api.model.PolarHealthThermometerData
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.model.EcgSample as PolarEcgSample
import com.polar.sdk.api.model.PolarAccelerometerData
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHrData
import com.polar.sdk.api.model.PolarOfflineRecordingData
import com.polar.sdk.api.model.PolarOfflineRecordingEntry
import com.polar.sdk.api.model.PolarPpiData
import com.polar.sdk.api.model.PolarRecordingSecret
import com.polar.sdk.api.model.PolarSensorSetting
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.functions.Consumer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Mode D ingestion client: wraps the official Polar BLE SDK and exposes the
 * unified [RawSample] stream regardless of device model.
 *
 * - Verity Sense: HR (HR service), PPI via [PolarPpiData], ACC 52 Hz,
 *   optional raw PPG.
 * - H10: HR + RR intervals via [PolarHrData.PolarHrSample.rrsMs], ACC via PMD,
 *   ECG (typically 130 Hz) via PMD.
 *
 * Rx observables returned by the SDK are collected eagerly into hot
 * [SharedFlow]s (see [rxToFlow] for the cold bridging helper used elsewhere).
 */
class PolarBleClient(context: Context) {

    companion object {
        private const val TAG = "PolarBleClient"
    }

    private val appContext = context.applicationContext

    val api: PolarBleApi = PolarBleApiDefaultImpl.defaultImplementation(
        appContext,
        setOf(
            PolarBleApi.PolarBleSdkFeature.FEATURE_HR,
            PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO,
            PolarBleApi.PolarBleSdkFeature.FEATURE_BATTERY_INFO,
            PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING,
            PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_OFFLINE_RECORDING,
        )
    )

    private val _connectionState =
        MutableStateFlow(SensorStatus.ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<SensorStatus.ConnectionState> =
        _connectionState.asStateFlow()

    private val _batteryPercent = MutableStateFlow<Int?>(null)
    val batteryPercent: StateFlow<Int?> = _batteryPercent.asStateFlow()

    private val _hrFlow = MutableSharedFlow<HrSample>(extraBufferCapacity = 256)
    val hrFlow: SharedFlow<HrSample> = _hrFlow.asSharedFlow()

    private val _ibiFlow = MutableSharedFlow<IbiSample>(extraBufferCapacity = 256)
    val ibiFlow: SharedFlow<IbiSample> = _ibiFlow.asSharedFlow()

    private val _accFlow = MutableSharedFlow<AccSample>(extraBufferCapacity = 4096)
    val accFlow: SharedFlow<AccSample> = _accFlow.asSharedFlow()

    private val _ecgFlow = MutableSharedFlow<EcgSample>(extraBufferCapacity = 8192)
    val ecgFlow: SharedFlow<EcgSample> = _ecgFlow.asSharedFlow()

    /** True once FEATURE_POLAR_ONLINE_STREAMING is ready for the connected device. */
    private val _streamingReady = MutableStateFlow(false)
    val streamingReady: StateFlow<Boolean> = _streamingReady.asStateFlow()

    /** True once the offline-recording feature is ready for the connected device. */
    private val _offlineRecordingReady = MutableStateFlow(false)
    val offlineRecordingReady: StateFlow<Boolean> = _offlineRecordingReady.asStateFlow()

    private var connectedDeviceId: String? = null
    private val streamDisposables = mutableListOf<Disposable>()

    init {
        api.setApiLogger { msg -> Log.d(TAG, "sdk: $msg") }
        api.setAutomaticReconnection(false) // reconnection is owned by PolarDeviceRepository
        api.setApiCallback(object : PolarBleApiCallback() {
            override fun deviceConnecting(info: PolarDeviceInfo) {
                _connectionState.value = SensorStatus.ConnectionState.CONNECTING
            }

            override fun deviceConnected(info: PolarDeviceInfo) {
                connectedDeviceId = info.deviceId
                _connectionState.value = SensorStatus.ConnectionState.CONNECTED
            }

            override fun deviceDisconnected(info: PolarDeviceInfo) {
                stopAllStreams()
                connectedDeviceId = null
                _streamingReady.value = false
                _offlineRecordingReady.value = false
                _connectionState.value = SensorStatus.ConnectionState.DISCONNECTED
            }

            override fun batteryLevelReceived(id: String, level: Int) {
                _batteryPercent.value = level
            }

            override fun bleSdkFeatureReady(id: String, feature: PolarBleApi.PolarBleSdkFeature) {
                if (feature == PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING) {
                    _streamingReady.value = true
                }
                if (feature == PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_OFFLINE_RECORDING) {
                    _offlineRecordingReady.value = true
                }
            }

            override fun disInformationReceived(
                identifier: String,
                disInfo: DisInfo,
            ) = Unit

            override fun htsNotificationReceived(
                identifier: String,
                data: PolarHealthThermometerData,
            ) = Unit

            /** HR + RR (H10) arrive via the plain HR service notification. */
            override fun hrNotificationReceived(id: String, data: PolarHrData.PolarHrSample) {
                val ts = System.currentTimeMillis()
                _hrFlow.tryEmit(HrSample(ts, data.hr))
                if (data.rrAvailable) {
                    var cumulative = 0L
                    data.rrsMs.forEach { rr ->
                        cumulative += rr
                        _ibiFlow.tryEmit(
                            IbiSample(
                                timestampMs = ts + cumulative,
                                intervalMs = rr,
                                source = IbiSource.HR_SERVICE,
                                skinContact = if (data.contactStatusSupported) {
                                    data.contactStatus
                                } else null,
                            )
                        )
                    }
                }
            }
        })
    }

    /** BLUETOOTH_SCAN/CONNECT on Android 12+, ACCESS_FINE_LOCATION below. */
    fun hasRequiredPermissions(): Boolean = requiredRuntimePermissions().all {
        ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
    }

    fun requiredRuntimePermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun connect(deviceId: String) {
        _connectionState.value = SensorStatus.ConnectionState.CONNECTING
        try {
            api.connectToDevice(deviceId)
        } catch (e: com.polar.sdk.api.errors.PolarInvalidArgument) {
            _connectionState.value = SensorStatus.ConnectionState.DISCONNECTED
            throw IllegalArgumentException("Invalid device id $deviceId", e)
        }
    }

    fun disconnect() {
        val id = connectedDeviceId
        _connectionState.value = SensorStatus.ConnectionState.DISCONNECTING
        stopAllStreams()
        if (id != null) {
            try {
                api.disconnectFromDevice(id)
            } catch (_: com.polar.sdk.api.errors.PolarInvalidArgument) {
                // already gone
            }
        }
        _connectionState.value = SensorStatus.ConnectionState.DISCONNECTED
    }

    private fun startAccStream(deviceId: String): Disposable? {
        val disposable = api.requestStreamSettings(deviceId, PolarBleApi.PolarDeviceDataType.ACC)
            .map { settings ->
                val rates = settings.settings[PolarSensorSetting.SettingType.SAMPLE_RATE]
                val rate = rates?.firstOrNull { it >= 52 } ?: rates?.maxOrNull() ?: 52
                Pair(
                    PolarSensorSetting(mapOf(PolarSensorSetting.SettingType.SAMPLE_RATE to rate)),
                    rate,
                )
            }
            .subscribe(
                Consumer { (setting, rate) ->
                    api.startAccStreaming(deviceId, setting)
                        .subscribe(
                            Consumer { data: PolarAccelerometerData ->
                                val now = System.currentTimeMillis()
                                val n = data.samples.size
                                data.samples.forEachIndexed { i, s ->
                                    _accFlow.tryEmit(
                                        AccSample(
                                            timestampMs = now - ((n - 1 - i) * 1000L / rate),
                                            x = s.x,
                                            y = s.y,
                                            z = s.z,
                                            sampleRate = rate,
                                        )
                                    )
                                }
                            },
                            Consumer { e -> Log.w(TAG, "ACC stream error", e) },
                        ).also { inner -> streamDisposables.add(inner) }
                },
                Consumer { e -> Log.w(TAG, "ACC settings request failed", e) },
            )
        streamDisposables.add(disposable)
        return disposable
    }

    /**
     * Starts all online streams supported by the connected device:
     * PPI (Verity), ACC (52 Hz preferred, else max offered), ECG (H10),
     * optional raw PPG. HR/RR arrive via the HR callback on both devices.
     * Call after [streamingReady] becomes true.
     */
    fun startStreams(includeEcg: Boolean = true, includePpg: Boolean = false) {
        val deviceId = connectedDeviceId ?: return
        stopAllStreams()

        fun launch(block: () -> Disposable?) {
            try {
                block()?.let { streamDisposables.add(it) }
            } catch (e: Exception) {
                Log.w(TAG, "stream start failed", e)
            }
        }

        // PPI (Verity Sense primary IBI source)
        launch {
            api.startPpiStreaming(deviceId).subscribe(
                Consumer { data: PolarPpiData ->
                    val now = System.currentTimeMillis()
                    data.samples.forEach { s ->
                        _ibiFlow.tryEmit(
                            IbiSample(
                                timestampMs = now,
                                intervalMs = s.ppi,
                                source = IbiSource.PPI,
                                blockerBit = s.blockerBit,
                                skinContact = if (s.skinContactSupported) {
                                    s.skinContactStatus
                                } else null,
                                error = s.errorEstimate,
                            )
                        )
                        _hrFlow.tryEmit(HrSample(now, s.hr))
                    }
                },
                Consumer { e -> Log.w(TAG, "PPI stream error", e) },
            )
        }

        // ACC — prefer 52 Hz if offered, else max available
        launch { startAccStream(deviceId) }

        // ECG (H10, 130 Hz typical)
        if (includeEcg) {
            launch {
                api.requestStreamSettings(deviceId, PolarBleApi.PolarDeviceDataType.ECG)
                    .flatMapPublisher { api.startEcgStreaming(deviceId, it.maxSettings()) }
                    .subscribe(
                        Consumer { data ->
                            val now = System.currentTimeMillis()
                            val n = data.samples.size
                            data.samples.forEachIndexed { i, base ->
                                val raw = base as? PolarEcgSample
                                if (raw != null) {
                                    _ecgFlow.tryEmit(
                                        EcgSample(
                                            timestampMs = now - ((n - 1 - i) * 1000L / 130),
                                            microVolts = raw.voltage,
                                        )
                                    )
                                }
                            }
                        },
                        Consumer { e -> Log.w(TAG, "ECG stream error", e) },
                    )
            }
        }

        // Optional raw PPG (Verity) — reserved for future signal work
        if (includePpg) {
            launch {
                api.requestStreamSettings(deviceId, PolarBleApi.PolarDeviceDataType.PPG)
                    .flatMapPublisher { api.startPpgStreaming(deviceId, it.maxSettings()) }
                    .subscribe(
                        Consumer { /* PPG kept alive; not yet persisted */ },
                        Consumer { e -> Log.w(TAG, "PPG stream error", e) },
                    )
            }
        }
    }

    fun stopAllStreams() {
        streamDisposables.forEach { runCatching { it.dispose() } }
        streamDisposables.clear()
        val id = connectedDeviceId ?: return
        runCatching { api.stopHrStreaming(id) }
        runCatching { api.stopStreaming(id, PmdMeasurementType.PPI) }
        runCatching { api.stopStreaming(id, PmdMeasurementType.ACC) }
        runCatching { api.stopStreaming(id, PmdMeasurementType.ECG) }
        runCatching { api.stopStreaming(id, PmdMeasurementType.PPG) }
    }

    // ---- offline recordings (sensor-internal .REC files) ----

    /**
     * Lists the offline recordings stored in the sensor's internal memory.
     * The recording survives anything that happens to the phone's btsnoop log
     * — this is the authoritative morning source (Sleep2 reads the same files).
     */
    fun listOfflineRecordings(deviceId: String): Flow<PolarOfflineRecordingEntry> =
        api.listOfflineRecordings(deviceId).rxToFlow()

    /**
     * Downloads one offline recording. The sensor stores recordings unencrypted
     * unless a device secret was configured (Sploot never sets one). The SDK's
     * Kotlin signature is `PolarRecordingSecret?` — passing null selects the
     * no-decryption path; a non-null secret makes the SDK AES-decrypt the file,
     * which fails on plain recordings (verified on-device 2026-11-02:
     * IllegalBlockSizeException). Kotlin's compiler inserts a null-check on the
     * platform-type call site, so the null is passed via reflection.
     */
    fun getOfflineRecord(
        deviceId: String,
        entry: PolarOfflineRecordingEntry,
    ): Flow<PolarOfflineRecordingData> {
        val single = PolarBleApi::class.java
            .getMethod(
                "getOfflineRecord",
                String::class.java,
                PolarOfflineRecordingEntry::class.java,
                PolarRecordingSecret::class.java,
            )
            .invoke(api, deviceId, entry, null) as Single<PolarOfflineRecordingData>
        return single.rxToFlow()
    }

    /** Snapshot of device status for the notification/UI. */
    fun sensorStatus(): SensorStatus = SensorStatus(_batteryPercent.value, _connectionState.value)

    fun shutdown() {
        stopAllStreams()
        runCatching { api.cleanup() }
        runCatching { api.shutDown() }
    }
}

/**
 * Bridges an RxJava3 [Flowable] into a cold Kotlin [Flow]. Cancelling the
 * flow disposes the upstream subscription.
 */
fun <T : Any> Flowable<T>.rxToFlow(): Flow<T> = callbackFlow {
    val disposable = subscribe(
        { trySend(it) },
        { close(it) },
        { close() },
    )
    awaitClose { disposable.dispose() }
}

/** Bridges an RxJava3 [Single] into a cold Kotlin [Flow] (one value then close). */
fun <T : Any> Single<T>.rxToFlow(): Flow<T> = callbackFlow {
    val disposable = subscribe(
        { trySend(it); close() },
        { close(it) },
    )
    awaitClose { disposable.dispose() }
}
