package com.example.sploot.ble.snoop

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
    private val ingestVersionKey = intPreferencesKey("last_ingest_version")
    private val importedRecPathsKey = stringSetPreferencesKey("imported_rec_paths")
    private val cacheFailFpKey = stringPreferencesKey("cache_fail_fingerprint")
    private val cacheFailCountKey = intPreferencesKey("cache_fail_count")

    /**
     * Bump when the decoder learns something new (e.g. offline-recording
     * transfers, 2026-11-02): a stored fingerprint written by an older ingest
     * version is stale — the same log must be reprocessed on the next open so
     * a decoding fix auto-recovers previously-failed nights.
     */
    val ingestVersion: Int = INGEST_VERSION

    /** Last imported log fingerprint, or null when nothing was imported yet. */
    val lastFingerprint: Flow<SnoopFingerprint.Fingerprint?> =
        context.snoopCapturePrefs.data.map { SnoopFingerprint.Fingerprint.decode(it[fingerprintKey]) }

    val lastSizeBytes: Flow<Long?> = context.snoopCapturePrefs.data.map { it[sizeKey] }

    /** Ingest version that produced [lastFingerprint], or null when unknown. */
    val lastIngestVersion: Flow<Int?> =
        context.snoopCapturePrefs.data.map { it[ingestVersionKey] }

    /** Auto-run the capture+process flow when the app is opened. Default on. */
    val autoEnabled: Flow<Boolean> =
        context.snoopCapturePrefs.data.map { it[enabledKey] ?: true }

    suspend fun setAutoEnabled(enabled: Boolean) {
        context.snoopCapturePrefs.edit { it[enabledKey] = enabled }
    }

    /** Records a completed import so the next run can detect change/rotation. */
    suspend fun recordImport(fp: SnoopFingerprint.Fingerprint, ingestVersion: Int = INGEST_VERSION) {
        context.snoopCapturePrefs.edit {
            it[fingerprintKey] = fp.encoded
            it[sizeKey] = fp.sizeBytes
            it[ingestVersionKey] = ingestVersion
        }
    }

    /**
     * Resolved fingerprint baseline: null when the stored fingerprint came from
     * an older ingest version (forcing a reprocess of the unchanged log).
     */
    suspend fun effectiveFingerprint(): SnoopFingerprint.Fingerprint? {
        val fp = lastFingerprint.first() ?: return null
        val version = lastIngestVersion.first()
        return if (version != null && version >= INGEST_VERSION) fp else null
    }

    suspend fun currentSize(): Long? = lastSizeBytes.first()
    suspend fun isAutoEnabled(): Boolean = autoEnabled.first()

    /** Recording paths already pulled from the sensor and imported. */
    val importedRecordingPaths: Flow<Set<String>> =
        context.snoopCapturePrefs.data.map { it[importedRecPathsKey] ?: emptySet() }

    suspend fun markRecordingImported(path: String) {
        context.snoopCapturePrefs.edit { it[importedRecPathsKey] = importedRecordingPaths.first() + path }
    }

    /**
     * Bookkeeping for the cached-capture retry: repeated failures of the same
     * cached file are counted; after [MAX_CACHE_FAILURES] the fingerprint is
     * recorded anyway so a broken cache cannot block the flow forever.
     *
     * @return true when the failure limit is reached (give up on this cache).
     */
    suspend fun noteCacheFailure(fingerprint: SnoopFingerprint.Fingerprint): Boolean {
        val prefs = context.snoopCapturePrefs.data.first()
        val same = prefs[cacheFailFpKey] == fingerprint.encoded
        val count = (if (same) prefs[cacheFailCountKey] else 0) ?: 0
        val giveUp = count + 1 >= MAX_CACHE_FAILURES
        context.snoopCapturePrefs.edit {
            it[cacheFailFpKey] = fingerprint.encoded
            it[cacheFailCountKey] = count + 1
        }
        if (giveUp) recordImport(fingerprint)
        return giveUp
    }

    suspend fun resetCacheFailures() {
        context.snoopCapturePrefs.edit {
            it.remove(cacheFailFpKey)
            it.remove(cacheFailCountKey)
        }
    }

    suspend fun currentFingerprint(): SnoopFingerprint.Fingerprint? = lastFingerprint.first()

    companion object {
        /** 1: original PMD+HR decoder. 2: + offline-recording (.REC) transfer recovery. */
        const val INGEST_VERSION = 2

        /** Give up reprocessing an unparseable cached capture after this many failures. */
        const val MAX_CACHE_FAILURES = 3
    }
}
