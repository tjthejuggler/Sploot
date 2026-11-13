package com.example.sploot

import com.example.sploot.ble.snoop.BugreportZipEntryExtractor
import com.example.sploot.ble.snoop.ShellProcess
import com.example.sploot.ble.snoop.ShellRunner
import com.example.sploot.ble.snoop.SnoopAutoFetcher
import com.example.sploot.ble.snoop.SnoopFingerprint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 12 JVM tests: fingerprint change detection, bugreport-zip entry
 * extraction, and the acquisition fallback state machine driven by a scripted
 * fake [ShellRunner] (Shizuku itself cannot run on the JVM — that is exactly
 * why the runner is an interface).
 */
class SnoopAutoFetchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- helpers ----

    /** Minimal but valid btsnoop file header ("btsnoop\0" + version + datalink). */
    private fun btsnoopBytes(payload: Int = 0): ByteArray {
        val h = ByteArray(16)
        val magic = byteArrayOf(0x62, 0x74, 0x73, 0x6e, 0x6f, 0x6f, 0x70, 0x00)
        System.arraycopy(magic, 0, h, 0, 8)
        h[11] = 1 // version u32 BE = 1
        h[15] = 0xEA.toByte() // datalink 1002
        return h
    }

    private fun zipWithBtsnoop(log: ByteArray, extraEntries: List<String> = emptyList()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            extraEntries.forEach {
                z.putNextEntry(ZipEntry(it))
                z.write("noise".toByteArray())
                z.closeEntry()
            }
            z.putNextEntry(ZipEntry("FS/data/misc/bluetooth/logs/btsnoop_hci.log"))
            z.write(log)
            z.closeEntry()
        }
        return out.toByteArray()
    }

    private class Scripted(val exit: Int, val bytes: ByteArray)

    /** Deterministic shell: exact command string → scripted stdout + exit code. */
    private class FakeShellRunner(
        override val isAvailable: Boolean = true,
        override val unavailableReason: String? = if (isAvailable) null else "Shizuku is not running",
        private val script: Map<String, Scripted> = emptyMap(),
    ) : ShellRunner {
        override fun exec(command: String): ShellProcess {
            val s = script[command]
                ?: error("unexpected command in script: $command")
            return ShellProcess(ByteArrayInputStream(s.bytes)) { s.exit }
        }
    }

    private fun fetcher(runner: ShellRunner): Pair<SnoopAutoFetcher, File> {
        val cache = File(Files.createTempDirectory("snoop").toFile(), "btsnoop_auto.log")
        return SnoopAutoFetcher(runner, cache) to cache
    }

    // ---- 1. fingerprint change detection ----

    @Test
    fun `fingerprint detects first import, no change, change and rotation`() {
        val fp1 = SnoopFingerprint.Fingerprint(1000, "a".repeat(64))
        val fp1b = SnoopFingerprint.decode(fp1.encoded)
        assertEquals(fp1, fp1b)

        // nothing imported yet → always "changed"
        assertTrue(SnoopFingerprint.hasChanged(fp1, null))

        // identical fingerprint → unchanged
        assertFalse(SnoopFingerprint.hasChanged(fp1, fp1))

        // grown log → changed; shrunken log → changed AND looks rotated
        val fp2 = SnoopFingerprint.Fingerprint(2000, "b".repeat(64))
        assertTrue(SnoopFingerprint.hasChanged(fp2, fp1))
        assertFalse(SnoopFingerprint.looksRotated(fp2.sizeBytes, fp1.sizeBytes))
        val fp3 = SnoopFingerprint.Fingerprint(500, "c".repeat(64))
        assertTrue(SnoopFingerprint.hasChanged(fp3, fp1))
        assertTrue(SnoopFingerprint.looksRotated(fp3.sizeBytes, fp1.sizeBytes))
        // same size but different content still counts as changed (hash guard)
        assertTrue(SnoopFingerprint.hasChanged(SnoopFingerprint.Fingerprint(1000, "d".repeat(64)), fp1))

        // malformed persisted values decode to null
        assertEquals(null, SnoopFingerprint.Fingerprint.Companion.decode("junk"))
        assertEquals(null, SnoopFingerprint.Fingerprint.Companion.decode(null))
    }

    @Test
    fun `fingerprint compute streams size and sha256`() {
        val bytes = btsnoopBytes()
        val fp = SnoopFingerprint.compute(ByteArrayInputStream(bytes))
        assertEquals(bytes.size.toLong(), fp.sizeBytes)
        assertEquals(64, fp.sha256.length)
    }

    // ---- 2. bugreport zip entry extraction ----

    @Test
    fun `extracts only the btsnoop entry from a synthetic bugreport zip`() {
        val log = btsnoopBytes()
        val zip = zipWithBtsnoop(log, extraEntries = listOf("bugreport-Sploot-2026.txt", "FS/other.txt"))
        val dest = tmp.newFile("extracted.log")

        val entry = BugreportZipEntryExtractor.extract(ByteArrayInputStream(zip), dest)

        assertNotNull(entry)
        assertEquals(BugreportZipEntryExtractor.CANONICAL_ENTRY, entry)
        assertTrue("extracted entry keeps btsnoop magic", dest.readBytes().contentEquals(log))
    }

    @Test
    fun `returns null when zip has no btsnoop entry`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("bugreport.txt"))
            z.write("no log here".toByteArray())
        }
        val dest = tmp.newFile("nothing.log")
        assertEquals(null, BugreportZipEntryExtractor.extract(ByteArrayInputStream(out.toByteArray()), dest))
    }

    // ---- 3. fallback state machine (scripted shell) ----

    private val P0 = SnoopAutoFetcher.CANDIDATE_PATHS[0]
    private val P1 = SnoopAutoFetcher.CANDIDATE_PATHS[1]
    private val P2 = SnoopAutoFetcher.CANDIDATE_PATHS[2]
    private val P3 = SnoopAutoFetcher.CANDIDATE_PATHS[3]
    private val ZIP = "/bugreport/bugreport-Sploot-2026.zip"

    @Test
    fun `direct shell cat succeeds on the standard path`() {
        val log = btsnoopBytes()
        val runner = FakeShellRunner(
            script = mapOf(
                "ls -nl $P0" to Scripted(0, "-rw-rw---- 1 root bluetooth ${log.size} 2026-11-01 btsnoop_hci.log\n".toByteArray()),
                "cat $P0" to Scripted(0, log),
                "ls -nl $P0.last" to Scripted(1, "No such file".toByteArray()),
            ),
        )
        val (fetcher, cache) = fetcher(runner)

        val r = fetcher.fetch()

        assertTrue("expected direct success, got $r", r is SnoopAutoFetcher.Result.Success)
        r as SnoopAutoFetcher.Result.Success
        assertEquals(SnoopAutoFetcher.Method.DIRECT_SHELL, r.method)
        assertEquals(log.size.toLong(), r.logSizeBytes)
        assertEquals(null, r.bugreportZipBytes)
        assertTrue("magic must survive acquisition", cache.readBytes().contentEquals(log))
        assertTrue("no rotation warning on first import", r.warnings.isEmpty())
    }

    @Test
    fun `falls back to bugreportz when direct read is permission-blocked`() {
        val log = btsnoopBytes()
        val zip = zipWithBtsnoop(log)
        val runner = FakeShellRunner(
            script = mapOf(
                "ls -nl $P0" to Scripted(1, "ls: $P0: Permission denied".toByteArray()),
                "ls -nl $P1" to Scripted(1, "ls: $P1: Permission denied".toByteArray()),
                "ls -nl $P2" to Scripted(1, "ls: $P2: Permission denied".toByteArray()),
                "ls -nl $P3" to Scripted(1, "ls: $P3: Permission denied".toByteArray()),
                "bugreportz -p" to Scripted(0, "PROGRESS:1/1\nOK: $ZIP\n".toByteArray()),
                "cat '$ZIP'" to Scripted(0, zip),
                "rm -f '$ZIP'" to Scripted(0, ByteArray(0)),
            ),
        )
        val (fetcher, cache) = fetcher(runner)

        val r = fetcher.fetch()

        assertTrue("expected bugreportz success, got $r", r is SnoopAutoFetcher.Result.Success)
        r as SnoopAutoFetcher.Result.Success
        assertEquals(SnoopAutoFetcher.Method.BUGREPORTZ, r.method)
        assertEquals(zip.size.toLong(), r.bugreportZipBytes)
        assertTrue(cache.readBytes().contentEquals(log))
    }

    /** Regression (on-device 2026-11-01): the extractor stops reading after the
     *  btsnoop entry, SIGPIPE-ing the upstream `cat` (exit 141). The capture is
     *  complete — the entry was read to EOF — so this must be a Success. */
    @Test
    fun `bugreportz success despite cat dying with SIGPIPE after entry extraction`() {
        val log = btsnoopBytes()
        val zip = zipWithBtsnoop(log, extraEntries = listOf("main_entry.txt"))
        val runner = FakeShellRunner(
            script = mapOf(
                "ls -nl $P0" to Scripted(1, "ls: $P0: Permission denied".toByteArray()),
                "ls -nl $P1" to Scripted(1, ByteArray(0)),
                "ls -nl $P2" to Scripted(1, ByteArray(0)),
                "ls -nl $P3" to Scripted(1, ByteArray(0)),
                "bugreportz -p" to Scripted(0, "OK: $ZIP\n".toByteArray()),
                "cat '$ZIP'" to Scripted(141, zip),
                "rm -f '$ZIP'" to Scripted(0, ByteArray(0)),
            ),
        )
        val (fetcher, cache) = fetcher(runner)

        val r = fetcher.fetch()

        assertTrue("expected bugreportz success despite SIGPIPE, got $r", r is SnoopAutoFetcher.Result.Success)
        r as SnoopAutoFetcher.Result.Success
        assertEquals(SnoopAutoFetcher.Method.BUGREPORTZ, r.method)
        assertTrue("capture must contain the extracted entry", cache.readBytes().contentEquals(log))
    }

    /** A non-SIGPIPE early exit (e.g. cat killed by the OOM killer) is still a failure. */
    @Test
    fun `bugreportz fails on other non-zero exits`() {
        val log = btsnoopBytes()
        val zip = zipWithBtsnoop(log)
        val runner = FakeShellRunner(
            script = mapOf(
                "ls -nl $P0" to Scripted(1, ByteArray(0)),
                "ls -nl $P1" to Scripted(1, ByteArray(0)),
                "ls -nl $P2" to Scripted(1, ByteArray(0)),
                "ls -nl $P3" to Scripted(1, ByteArray(0)),
                "bugreportz -p" to Scripted(0, "OK: $ZIP\n".toByteArray()),
                "cat '$ZIP'" to Scripted(9, zip),
                "rm -f '$ZIP'" to Scripted(0, ByteArray(0)),
            ),
        )
        val (fetcher, _) = fetcher(runner)

        val r = fetcher.fetch()

        assertTrue(r is SnoopAutoFetcher.Result.Failure)
        assertTrue((r as SnoopAutoFetcher.Result.Failure).reason.contains("ended early"))
    }

    @Test
    fun `reports rotation warning when the log shrank since last import`() {
        val log = btsnoopBytes()
        val runner = FakeShellRunner(
            script = mapOf(
                "ls -nl $P0" to Scripted(0, "x".toByteArray()),
                "cat $P0" to Scripted(0, log),
                "ls -nl $P0.last" to Scripted(1, "No such file".toByteArray()),
            ),
        )
        val (fetcher, _) = fetcher(runner)

        val r = fetcher.fetch(previousSizeBytes = log.size.toLong() + 1000)

        assertTrue(r is SnoopAutoFetcher.Result.Success)
        r as SnoopAutoFetcher.Result.Success
        assertTrue(
            "rotation warning expected",
            r.warnings.any { it.contains("rotated") },
        )
    }

    /** Regression (on-device 2026-11-02): when `.last` exists the two
     *  generations are concatenated older-first behind one header. */
    @Test
    fun `fetches and concatenates the previous generation when present`() {
        val older = btsnoopBytes()
        val current = btsnoopBytes()
        val runner = FakeShellRunner(
            script = mapOf(
                "ls -nl $P0" to Scripted(0, "-rw-rw---- 1 root bluetooth ${current.size} btsnoop_hci.log\n".toByteArray()),
                "cat $P0" to Scripted(0, current),
                "ls -nl $P0.last" to Scripted(0, "-rw-rw---- 1 root bluetooth ${older.size} btsnoop_hci.log.last\n".toByteArray()),
                "cat $P0.last" to Scripted(0, older),
            ),
        )
        val (fetcher, cache) = fetcher(runner)

        val r = fetcher.fetch()

        assertTrue("expected success, got $r", r is SnoopAutoFetcher.Result.Success)
        // cache = current header + current body + older records
        val got = cache.readBytes()
        assertTrue("header preserved", got.copyOfRange(0, 16).contentEquals(current.copyOfRange(0, 16)))
        val expected = current.copyOfRange(16, current.size) + older.copyOfRange(16, older.size)
        assertEquals(16 + expected.size, got.size)
        assertTrue(got.copyOfRange(16, got.size).contentEquals(expected))
    }

    /** `.last` absent (fresh boot / no rotation) behaves exactly like before. */
    @Test
    fun `fetches current generation only when no last file exists`() {
        val log = btsnoopBytes()
        val runner = FakeShellRunner(
            script = mapOf(
                "ls -nl $P0" to Scripted(0, "x".toByteArray()),
                "cat $P0" to Scripted(0, log),
                "ls -nl $P0.last" to Scripted(1, "No such file".toByteArray()),
            ),
        )
        val (fetcher, cache) = fetcher(runner)

        val r = fetcher.fetch()

        assertTrue(r is SnoopAutoFetcher.Result.Success)
        assertTrue(cache.readBytes().contentEquals(log))
    }

    @Test
    fun `vendor path P1 with last generation is found when standard path missing`() {
        val older = btsnoopBytes()
        val current = btsnoopBytes()
        val script = mutableMapOf(
            "ls -nl $P0" to Scripted(1, "Permission denied".toByteArray()),
            "ls -nl $P1" to Scripted(0, "x".toByteArray()),
            "cat $P1" to Scripted(0, current),
            "ls -nl $P1.last" to Scripted(0, "x".toByteArray()),
            "cat $P1.last" to Scripted(0, older),
        )
        val (fetcher, cache) = fetcher(FakeShellRunner(script = script))

        val r = fetcher.fetch()

        assertTrue("expected vendor-path success, got $r", r is SnoopAutoFetcher.Result.Success)
        r as SnoopAutoFetcher.Result.Success
        assertEquals(SnoopAutoFetcher.Method.DIRECT_SHELL, r.method)
        val got = cache.readBytes()
        val expected = current.copyOfRange(16, current.size) + older.copyOfRange(16, older.size)
        assertTrue(got.copyOfRange(16, got.size).contentEquals(expected))
    }

    @Test
    fun `garbage from cat is rejected by the magic check`() {
        val runner = FakeShellRunner(
            script = mapOf(
                "ls -nl $P0" to Scripted(0, "x".toByteArray()),
                "cat $P0" to Scripted(0, "<html>Permission denied</html>".toByteArray()),
                "ls -nl $P0.last" to Scripted(1, ByteArray(0)),
                "ls -nl $P1" to Scripted(1, ByteArray(0)),
                "ls -nl $P2" to Scripted(1, ByteArray(0)),
                "ls -nl $P3" to Scripted(1, ByteArray(0)),
                "bugreportz -p" to Scripted(1, "bugreportz: not found".toByteArray()),
            ),
        )
        val (fetcher, _) = fetcher(runner)

        val r = fetcher.fetch()

        assertTrue(r is SnoopAutoFetcher.Result.Failure)
        r as SnoopAutoFetcher.Result.Failure
        assertTrue(r.reason.contains("not a btsnoop file"))
        assertTrue(r.reason.contains("bugreportz"))
    }

    /** Regression: Shizuku binder dying mid-run (the crash seen on-device) must
     *  surface as Result.Failure, never propagate out of fetch(). */
    @Test
    fun `mid-run binder loss returns failure instead of throwing`() {
        val runner = object : ShellRunner {
            override val isAvailable = true
            override val unavailableReason: String? = null
            override fun exec(command: String): ShellProcess =
                throw IllegalStateException("Shizuku newProcess failed: dead object")
        }
        val (fetcher, _) = fetcher(runner)

        val r = fetcher.fetch()

        assertTrue("expected graceful failure, got $r", r is SnoopAutoFetcher.Result.Failure)
        r as SnoopAutoFetcher.Result.Failure
        assertTrue(r.reason.contains("fetch aborted"))
        assertTrue(r.reason.contains("newProcess failed"))
    }

    @Test
    fun `unavailable transport short-circuits to a clear status`() {
        val runner = FakeShellRunner(isAvailable = false, unavailableReason = "Shizuku is not running")
        val (fetcher, _) = fetcher(runner)

        val r = fetcher.fetch()

        assertTrue(r is SnoopAutoFetcher.Result.Unavailable)
        assertEquals("Shizuku is not running", (r as SnoopAutoFetcher.Result.Unavailable).reason)
    }
}
