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
            runCatching { container.deviceRepo.loadLastKnownDevice() }
            // Phase 12: fully automatic morning flow — on app open, capture the
            // overnight btsnoop log via Shizuku (if available) and, when it has
            // changed since the last import, process it and post the morning
            // summary notification. Silent no-op when disabled or unavailable.
            runCatching { container.morningCapture.maybeAutoCapture() }
        }
    }
}
