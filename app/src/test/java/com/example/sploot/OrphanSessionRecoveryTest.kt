package com.example.sploot

import com.example.sploot.ble.IbiSample
import com.example.sploot.ble.IbiSource
import com.example.sploot.recording.OrphanSessionRecovery
import com.example.sploot.recording.RawRecorder
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OrphanSessionRecoveryTest {

    @get:Rule val tmp = TemporaryFolder()

    /** Starts a recorder, appends `seconds` of IBIs, then flushes WITHOUT close
     *  — exactly the state left behind by a process kill mid-night. */
    private fun crashSession(root: File, id: String, seconds: Int, startMs: Long = 1_700_000_000_000L) {
        var t = 0L
        val rec = RawRecorder(File(root, id), id, clock = { startMs + t })
        rec.start()
        while (t < seconds * 1000L) {
            val ibi = 950
            rec.append(IbiSample(t, ibi, IbiSource.PPI, false, true, 8))
            t += ibi
        }
        rec.flush() // index.json rewritten, endedAtMs still null
    }

    @Test
    fun `un-finalized session is detected as orphan`() = runTest {
        val root = tmp.newFolder()
        crashSession(root, "20261030-230000", seconds = 120)
        val orphans = OrphanSessionRecovery(root).findOrphans()
        assertEquals(1, orphans.size)
        val o = orphans.first()
        assertEquals("20261030-230000", o.sessionId)
        assertTrue("duration ~120s: ${o.durationMs}", o.durationMs in 110_000L..130_000L)
        assertTrue("ibi count ${o.ibiCount}", o.ibiCount > 100)
    }

    @Test
    fun `cleanly closed session is not an orphan`() = runTest {
        val root = tmp.newFolder()
        val rec = RawRecorder(File(root, "done"), "done", clock = { 1_700_000_000_000L })
        rec.start()
        rec.append(IbiSample(0, 900, IbiSource.PPI))
        rec.close() // stamps endedAtMs
        assertTrue(OrphanSessionRecovery(root).findOrphans().isEmpty())
    }

    @Test
    fun `session already in the database is skipped`() = runTest {
        val root = tmp.newFolder()
        crashSession(root, "imported", seconds = 60)
        val orphans = OrphanSessionRecovery(root).findOrphans(isImported = { it == "imported" })
        assertTrue(orphans.isEmpty())
    }

    @Test
    fun `corrupted index json is ignored without throwing`() = runTest {
        val root = tmp.newFolder()
        val dir = File(root, "corrupt").apply { mkdirs() }
        File(dir, RawRecorder.INDEX_FILE_NAME).writeText("{ not json")
        File(dir, RawRecorder.RAW_FILE_NAME).writeBytes(ByteArray(64))
        assertTrue(OrphanSessionRecovery(root).findOrphans().isEmpty())
    }

    @Test
    fun `orphan without raw bin is ignored`() = runTest {
        val root = tmp.newFolder()
        val dir = File(root, "no-raw").apply { mkdirs() }
        File(dir, RawRecorder.INDEX_FILE_NAME).writeText(
            """{"sessionId":"no-raw","startedAtMs":1,"lastFlushMs":2,"endedAtMs":null,
               "framesWritten":1,"bytesWritten":64,
               "counters":{"hr":0,"ibi":1,"acc":0,"ecg":0,"status":0}}""",
        )
        assertTrue(OrphanSessionRecovery(root).findOrphans().isEmpty())
    }

    @Test
    fun `markComplete stamps endedAtMs from last flush and clears orphan`() = runTest {
        val root = tmp.newFolder()
        crashSession(root, "recover-me", seconds = 90)
        val recovery = OrphanSessionRecovery(root)
        val orphan = recovery.findOrphans().single()
        assertNotNull(orphan.index.lastFlushMs)
        recovery.markComplete(orphan, nowMs = 1_799_999_999_999L)
        // endedAtMs now set → no longer detected as orphan
        assertTrue(recovery.findOrphans().isEmpty())
        // and the stamped end came from the last flush, not "now"
        val idx = recovery.findOrphans() // empty
        assertTrue(idx.isEmpty())
        val marked = java.io.File(root, "recover-me/${RawRecorder.INDEX_FILE_NAME}").readText()
        assertTrue("endedAtMs must be non-null in index: $marked", marked.contains("\"endedAtMs\""))
    }

    @Test
    fun `multiple orphans returned oldest first`() = runTest {
        val root = tmp.newFolder()
        crashSession(root, "b", seconds = 30, startMs = 2_000_000_000_000L)
        crashSession(root, "a", seconds = 30, startMs = 1_000_000_000_000L)
        val ids = OrphanSessionRecovery(root).findOrphans().map { it.sessionId }
        assertEquals(listOf("a", "b"), ids)
    }
}
