package com.example.sploot.ble.snoop

import com.example.sploot.ble.IbiSample
import com.example.sploot.ble.IbiSource
import com.example.sploot.ble.RawSample
import com.polar.sdk.api.model.PolarOfflineRecordingData
import com.polar.sdk.api.model.PolarOfflineRecordingEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList

/**
 * Pulls the night's **offline recording** straight from the Polar sensor's
 * internal memory via the official SDK (`listOfflineRecordings` +
 * `getOfflineRecord`). This is the authoritative morning source: the sensor
 * keeps its `.REC` files regardless of what happens to the phone's btsnoop
 * log (rotation, overwrite, Shizuku down), and it is the same file third-party
 * apps (Sleep2, Polar Flow) read.
 *
 * The device must already be connected (Mode D stack handles that); the PPI
 * offline recording carries per-sample wall-clock offsets from the recording
 * start, which the SDK resolves into `timeStamp` fields.
 */
class PolarOfflineRecordingPuller(
    val client: com.example.sploot.ble.PolarBleClient,
) {

    /** Live sensor connection state (CONNECTED required for a pull). */
    val connectionState = client.connectionState

    /** One recovered recording: the entry plus its decoded samples. */
    data class Recovered(
        val entry: PolarOfflineRecordingEntry,
        val samples: List<RawSample>,
    )

    /**
     * Downloads every PPI offline recording on the sensor and converts them to
     * [IbiSample]s. Recordings already imported are skipped via [importedPaths].
     * Does NOT delete recordings from the sensor — Sleep2/Polar Flow may still
     * need them.
     *
     * @return recovered recordings in chronological order; empty when none exist.
     */
    suspend fun recoverPpiRecordings(
        deviceId: String,
        importedPaths: Set<String> = emptySet(),
    ): List<Recovered> {
        // The SDK Flowable emits the entries then never completes, so a plain
        // toList() would hang forever; entries all arrive within a few seconds
        // of the query, so a bounded collection is sufficient.
        val entries = kotlinx.coroutines.withTimeoutOrNull(LIST_TIMEOUT_MS) {
            client.listOfflineRecordings(deviceId).toList()
        } ?: emptyList()
        android.util.Log.i("PolarOfflinePull", "sensor reports ${entries.size} recordings: " +
            entries.joinToString { "${it.type} ${it.path} (${it.size}B)" })
        val wanted = entries
            .filter { it.type == com.polar.sdk.api.PolarBleApi.PolarDeviceDataType.PPI }
            .filter { it.path !in importedPaths }
            .sortedBy { it.date }
        val out = ArrayList<Recovered>(wanted.size)
        for (entry in wanted) {
            // The getOfflineRecord Flowable emits the assembled recording once
            // the PFtp transfer finishes (~143 KB takes 1-2 min over BLE) and
            // then never completes; firstOrNull returns on that emission. The
            // timeout only guards a dead transfer.
            android.util.Log.i("PolarOfflinePull", "downloading ${entry.path}…")
            val data = kotlinx.coroutines.withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
                client.getOfflineRecord(deviceId, entry).firstOrNull()
            } ?: run {
                android.util.Log.w("PolarOfflinePull", "download of ${entry.path} timed out")
                continue
            }
            val samples = when (data) {
                is PolarOfflineRecordingData.PpiOfflineRecording -> {
                    val startMs = data.startTime.toInstant().toEpochMilli()
                    var clock = 0L
                    data.data.samples.map { s ->
                        // Beat time = start + Σppi (exact; same walking clock
                        // the recording itself is defined by).
                        clock += s.ppi
                        IbiSample(
                            timestampMs = startMs + clock,
                            intervalMs = s.ppi,
                            source = IbiSource.PPI,
                            blockerBit = s.blockerBit,
                            skinContact = if (s.skinContactSupported) s.skinContactStatus else null,
                            error = s.errorEstimate,
                        )
                    }
                }
                else -> emptyList()
            }
            if (samples.isNotEmpty()) out.add(Recovered(entry, samples))
        }
        return out
    }

    companion object {
        /** Bounded wait for the (never-completing) recording-list Flowable. */
        private const val LIST_TIMEOUT_MS = 6_000L

        /** 144 KB over BLE GATT takes ~1-2 min; allow 5. */
        private const val DOWNLOAD_TIMEOUT_MS = 5 * 60_000L
    }
}
