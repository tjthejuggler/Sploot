package com.example.sploot.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.sploot.ble.PolarBleClient
import com.example.sploot.ble.PolarDeviceRepository
import com.example.sploot.ble.snoop.MorningCaptureCoordinator
import com.example.sploot.ble.snoop.PolarOfflineRecordingPuller
import com.example.sploot.ble.snoop.ShizukuShellRunner
import com.example.sploot.ble.snoop.SnoopAutoFetcher
import com.example.sploot.ble.snoop.SnoopCaptureStore
import com.example.sploot.data.db.SplootDatabase
import com.example.sploot.data.export.ExportManager
import com.example.sploot.data.repo.RecoveryRepository
import com.example.sploot.data.repo.SessionRepository
import com.example.sploot.ml.EngineRegistry
import com.example.sploot.ml.ModelLoader
import com.example.sploot.ml.SleepStagingEngine
import com.example.sploot.processing.ProcessNightUseCase
import com.example.sploot.recording.SessionManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsPrefs: DataStore<Preferences> by preferencesDataStore(name = "sploot_settings")

/** Manual-DI container owned by the [com.example.sploot.SplootApplication]. */
class AppContainer(val context: Context) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ---- persistence ----
    val database: SplootDatabase = SplootDatabase.get(context)
    val sessionRepo = SessionRepository(database)
    val recoveryRepo = RecoveryRepository(database.dailyBaselineDao())

    // ---- BLE + recording ----
    val polarClient = PolarBleClient(context)
    val deviceRepo = PolarDeviceRepository(context, polarClient, appScope)
    val sessionManager = SessionManager.get(context)

    // ---- ML ----
    private val modelLoader = ModelLoader(
        assets = { name -> runCatching { context.assets.open(name) }.getOrNull() },
        modelDir = File(context.filesDir, "models"),
    )

    val settingsStore = SettingsStore(context)

    /**
     * Builds the currently-selected engine (fresh model load each night).
     * Falls back to the heuristic engine when the selected id has no loadable
     * model (the bundled ONNX files are placeholders; the heuristic engine is
     * the default and needs no model file).
     */
    fun engine(): SleepStagingEngine {
        val id = settingsStore.cachedEngineId
        if (id == EngineRegistry.HEURISTIC) {
            return EngineRegistry.create(EngineRegistry.HEURISTIC, null)
        }
        return try {
            EngineRegistry.create(id, modelLoader.load(id))
        } catch (e: Exception) {
            EngineRegistry.create(EngineRegistry.HEURISTIC, null)
        }
    }

    fun modelInstaller() = modelLoader

    // ---- processing + export ----
    val processNight = ProcessNightUseCase(
        repo = sessionRepo,
        recoveryRepo = recoveryRepo,
        engineProvider = { engine() },
        assets = { name -> runCatching { context.assets.open(name) }.getOrNull() },
    )

    // ---- automated btsnoop capture (Phase 12) ----
    val snoopCaptureStore = SnoopCaptureStore(context)
    val snoopAutoFetcher = SnoopAutoFetcher(
        runner = ShizukuShellRunner(),
        cacheFile = File(context.cacheDir, "btsnoop_auto.log"),
    )
    val morningCapture = MorningCaptureCoordinator(
        context = context,
        fetcher = snoopAutoFetcher,
        store = snoopCaptureStore,
        processNight = processNight,
        recoveryRepo = recoveryRepo,
        repo = sessionRepo,
        deviceRepo = deviceRepo,
        puller = PolarOfflineRecordingPuller(polarClient),
    )

    val exportManager = ExportManager(context, sessionRepo)

    fun sessionsRoot(): File = File(context.filesDir, "sessions")
}

/** Engine selection persisted in DataStore ("sploot-seq" default). */
class SettingsStore(private val context: Context) {

    private val key = stringPreferencesKey("staging_engine")

    val engineId: Flow<String> = context.settingsPrefs.data.map {
        // Default to the heuristic engine until trained ONNX models ship
        // (the bundled sploot-seq/gbdt files are placeholders).
        it[key] ?: EngineRegistry.HEURISTIC
    }

    @Volatile var cachedEngineId: String = EngineRegistry.HEURISTIC
        private set

    suspend fun warmCache() {
        cachedEngineId = engineId.first()
    }

    suspend fun setEngineId(id: String) {
        require(id in EngineRegistry.ALL) { "unknown engine id $id" }
        context.settingsPrefs.edit { it[key] = id }
        cachedEngineId = id
    }
}
