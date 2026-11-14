package com.example.sploot

import android.app.Application
import com.example.sploot.di.AppContainer
import kotlinx.coroutines.launch

/** Application entry: owns the manual-DI [AppContainer] singleton. */
class SplootApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.appScope.launch {
            runCatching { container.settingsStore.warmCache() }
            // One-shot migration: re-stage nights still staged by the legacy
            // heuristic engine with the raw-IBI network (2026-11-14). Skips
            // silently when everything is already on the target engine.
            runCatching {
                if (container.restageAll.hasLegacyStagedNights(container.settingsStore.cachedEngineId)) {
                    container.restageAll.restageAll()
                }
            }
            runCatching { container.deviceRepo.loadLastKnownDevice() }
            // Automatic morning flow — only when today's night is missing: if a
            // session already exists with today's wake-up date there is nothing
            // to fetch, so we don't even connect to the sensor.
            val today = java.time.LocalDate.now().toString()
            val hasTodayNight = runCatching {
                container.sessionRepo.sessions().any {
                    it.endEpochMs != null &&
                        SleepDates.nightDate(it.startEpochMs, it.endEpochMs).toString() == today
                }
            }.getOrDefault(false)
            if (!hasTodayNight) {
                runCatching { container.morningCapture.maybeAutoCapture() }
            }
        }
    }
}
