package com.example.sploot.ble.snoop

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.snoopCapturePrefs: DataStore<Preferences> by preferencesDataStore(name = "snoop_capture")

/**
 * Persisted state for the automatic morning capture: fingerprint of the last
 * imported btsnoop log (change detection + rotation warning) and the
 * auto-capture-on-open toggle.
 */
class SnoopCaptureStore(private val context: Context) {

    private val fingerprintKey = stringPreferencesKey("last_fingerprint")
    private val sizeKey = longPreferencesKey("last_size_bytes")
    private val enabledKey = booleanPreferencesKey("auto_capture_enabled")

    /** Last imported log fingerprint, or null when nothing was imported yet. */
    val lastFingerprint: Flow<SnoopFingerprint.Fingerprint?> =
        context.snoopCapturePrefs.data.map { SnoopFingerprint.Fingerprint.decode(it[fingerprintKey]) }

    val lastSizeBytes: Flow<Long?> = context.snoopCapturePrefs.data.map { it[sizeKey] }

    /** Auto-run the capture+process flow when the app is opened. Default on. */
    val autoEnabled: Flow<Boolean> =
        context.snoopCapturePrefs.data.map { it[enabledKey] ?: true }

    suspend fun setAutoEnabled(enabled: Boolean) {
        context.snoopCapturePrefs.edit { it[enabledKey] = enabled }
    }

    /** Records a completed import so the next run can detect change/rotation. */
    suspend fun recordImport(fp: SnoopFingerprint.Fingerprint) {
        context.snoopCapturePrefs.edit {
            it[fingerprintKey] = fp.encoded
            it[sizeKey] = fp.sizeBytes
        }
    }

    suspend fun currentFingerprint(): SnoopFingerprint.Fingerprint? = lastFingerprint.first()
    suspend fun currentSize(): Long? = lastSizeBytes.first()
    suspend fun isAutoEnabled(): Boolean = autoEnabled.first()
}
