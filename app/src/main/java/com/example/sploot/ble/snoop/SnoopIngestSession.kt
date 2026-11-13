package com.example.sploot.ble.snoop

import com.example.sploot.ble.RawSample
import java.io.InputStream

/**
 * Drives a full btsnoop ingestion run:
 *
 * `BtsnoopParser` (file/record framing) → `HciAclDemux` (ACL → L2CAP) →
 * `AttGattDecoder` (ATT notifications + handle→UUID mapping) → `PmdFrameDecoder`
 * (Polar PMD / standard GATT payloads) → [RawSample] stream — identical to Mode D,
 * so the downstream signal pipeline is ingestion-mode-agnostic.
 *
 * Simple synchronous API: create with a stream provider, call [run] with a sample
 * consumer, receive a [Summary]. Unknown frame types and unmapped handles are counted
 * and skipped, never fatal.
 */
class SnoopIngestSession(private val openStream: () -> InputStream) {

    /** Resulting accounting for one ingestion run. */
    data class Summary(
        val btsnoopRecords: Int = 0,
        val btsnoopSkipped: Int = 0,
        val l2capFrames: Int = 0,
        val attNotifications: Int = 0,
        val hrSamples: Int = 0,
        val ibiSamples: Int = 0,
        val accSamples: Int = 0,
        val ecgSamples: Int = 0,
        val unknownFrames: Int = 0,
        val unknownHandles: Map<Int, UUIDCount>,
        val batteryPercent: Int? = null,
        /** IBIs recovered from a Polar offline-recording file transfer (.REC). */
        val offlineRecordingIbi: Int = 0,
        /** True when a `.REC` transfer was seen in the capture. */
        val offlineRecordingSeen: Boolean = false,
    )

    /** handle + occurrence count for notifications whose UUID never got resolved. */
    data class UUIDCount(val handle: Int, val count: Int)

    /**
     * Runs the pipeline; every decoded [RawSample] is passed to [onSample] in
     * capture order. Throws [IllegalArgumentException] when the stream is not a
     * btsnoop file.
     */
    fun run(onSample: (RawSample) -> Unit): Summary {
        val pmd = PmdFrameDecoder()
        val offline = PolarOfflineRecordingDecoder(onSample)
        val batteryTracker = BatteryTracker()
        val unknownHandles = HashMap<Int, Int>()

        var attRef: AttGattDecoder? = null
        val att = AttGattDecoder { ntf ->
            val uuid = attRef?.handleToUuid?.get(ntf.attrHandle)
            if (uuid == null) {
                // Unresolved handle: may still be a Polar offline-recording
                // transfer captured mid-flight (the reading app skipped service
                // discovery), so let the content-based decoder try.
                offline.onNotification(ntf.attrHandle, ntf.timestampMs, ntf.value)
                unknownHandles.merge(ntf.attrHandle, 1, Int::plus)
            } else if (uuid == PmdFrameDecoder.UUID_BATTERY && ntf.value.isNotEmpty()) {
                batteryTracker.percent = ntf.value[0].toInt() and 0xFF
            } else {
                pmd.decode(uuid, ntf.timestampMs, ntf.value, onSample)
            }
        }
        attRef = att

        val acl = HciAclDemux { pdu ->
            // Arm the offline-recording decoder from ATT writes naming a .REC path.
            if (pdu.cid == AttGattDecoder.ATT_CID && pdu.payload.size > 3) {
                val op = pdu.payload[0].toInt() and 0xFF
                if (op == 0x12 || op == 0x52) { // WRITE_REQ / WRITE_CMD
                    offline.onWrite(pdu.payload.copyOfRange(3, pdu.payload.size))
                }
            }
            att.feed(pdu)
        }

        val counters = BtsnoopParser().parse(openStream()) { record ->
            acl.feed(record.h4Packet, record.timestampMs)
        }

        return Summary(
            btsnoopRecords = counters.recordsParsed,
            btsnoopSkipped = counters.recordsSkipped,
            l2capFrames = acl.l2capFrames,
            attNotifications = att.notifications + att.indications,
            hrSamples = pmd.hrSamples,
            ibiSamples = pmd.ibiSamples,
            accSamples = pmd.accSamples,
            ecgSamples = pmd.ecgSamples,
            unknownFrames = pmd.unknownFrames,
            unknownHandles = unknownHandles.map { UUIDCount(it.key, it.value) }.associateBy { it.handle },
            batteryPercent = batteryTracker.percent,
            offlineRecordingIbi = offline.ibiEmitted,
            offlineRecordingSeen = offline.armed,
        )
    }

    /** Convenience: collects the whole run into a list. */
    fun collect(): Pair<List<RawSample>, Summary> {
        val samples = ArrayList<RawSample>()
        val summary = run { samples.add(it) }
        return samples to summary
    }

    private class BatteryTracker {
        var percent: Int? = null
    }
}
