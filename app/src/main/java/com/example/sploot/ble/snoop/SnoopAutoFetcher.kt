package com.example.sploot.ble.snoop

import java.io.File
import java.io.InputStream

/**
 * Automated acquisition of the on-device btsnoop log via a privileged shell
 * (Shizuku uid-shell in production; fakes in JVM tests). Fallback chain:
 *
 *  (a) DIRECT_SHELL — `cat <candidate path>` for every path in
 *      [CANDIDATE_PATHS]. Verified working on Pixel/AOSP-style builds where the
 *      bluetooth logs directory is group-readable by shell (uid 2000).
 *  (b) BUGREPORTZ — `bugreportz -p` (runs dumpstate, which reads the log with
 *      elevated privileges), then stream `cat` the produced zip and extract
 *      only the btsnoop entry via [BugreportZipEntryExtractor]. Works on builds
 *      where (a) is blocked.
 *  (c) MANUAL_SAF — outside this class: the Home-screen "Import btsnoop log"
 *      SAF picker (the pre-Phase-12 flow), still available when Shizuku is
 *      unavailable entirely.
 *
 * **Rotation handling (on-device failure 2026-11-02):** this device's stack
 * keeps the log under the vendor path `/data/log/bt/` and rotates at 65,536
 * records — the whole night ended up in `btsnoop_hci.log.last` while the
 * current generation only held the morning. For every generation captured,
 * the sibling `.last` file is also fetched and the two are *logically
 * concatenated* (older first) into [cacheFile] behind a single btsnoop header,
 * so a night that straddles a rotation is never split. When only `.last`
 * exists (fresh post-rotation capture), it is used on its own.
 *
 * Every successful acquisition validates the `btsnoop\0` magic before the
 * bytes are handed anywhere, so a permission-error HTML page or a truncated
 * shell error can never reach the parser.
 */
class SnoopAutoFetcher(
    private val runner: ShellRunner,
    /** Destination for the last successful capture (also read by the Shizuku-down recovery path). */
    val cacheFile: File,
) {

    companion object {
        /** Standard AOSP path first, then vendor variants observed in the wild. */
        val CANDIDATE_PATHS = listOf(
            "/data/misc/bluetooth/logs/btsnoop_hci.log",
            "/data/log/bt/btsnoop_hci.log",
            "/data/misc/bluetooth/btsnoop_hci.log",
            "/data/misc/bludroid/btsnoop_hci.log",
        )

        private val MAGIC = byteArrayOf(0x62, 0x74, 0x73, 0x6e, 0x6f, 0x6f, 0x70, 0x00) // "btsnoop\0"
    }

    enum class Method { DIRECT_SHELL, BUGREPORTZ }

    sealed class Result {
        /** The log was captured to [file] (magic-verified). */
        data class Success(
            val method: Method,
            val file: File,
            val logSizeBytes: Long,
            /** Zip bytes streamed in bugreportz mode (size context for the user), null in direct mode. */
            val bugreportZipBytes: Long?,
            /** Non-fatal advisories (log rotated, etc.). */
            val warnings: List<String>,
        ) : Result()

        /** Shizuku not running / Sploot not granted — manual SAF import is the remaining option. */
        data class Unavailable(val reason: String) : Result()

        data class Failure(val reason: String) : Result()
    }

    val availability: Result.Unavailable?
        get() = if (runner.isAvailable) null
        else Result.Unavailable(runner.unavailableReason ?: "Shizuku unavailable")

    /**
     * Runs the fallback chain and leaves the captured log in [cacheFile].
     * [previousSizeBytes] (last imported log size, if any) enables the
     * rotation warning when the fresh log shrank.
     */
    fun fetch(previousSizeBytes: Long? = null): Result {
        availability?.let { return it }

        // The Shizuku binder can die mid-run (server killed, permission revoked,
        // UserService failing to connect after the bind latch timeout), which
        // surfaces as an unchecked exception from [ShellRunner.exec]. Catch-all
        // so callers (Settings "Test fetch", MorningCaptureCoordinator) always
        // get a typed [Result] instead of a crashed coroutine.
        return runCatching { fetchInner(previousSizeBytes) }.getOrElse { e ->
            Result.Failure(
                "fetch aborted: ${e.message ?: e.javaClass.simpleName}" +
                    " (Shizuku may have stopped mid-run — retry)",
            )
        }
    }

    private fun fetchInner(previousSizeBytes: Long?): Result {
        val directReasons = mutableListOf<String>()
        for (path in CANDIDATE_PATHS) {
            when (val r = fetchDirect(path)) {
                is DirectOk -> {
                    return finish(r, Method.DIRECT_SHELL, null, previousSizeBytes)
                }
                is DirectFail -> directReasons.add("${path}: ${r.reason}")
            }
        }

        // (b) bugreportz — the logs dir may be unreadable for shell, but the
        // dumpstate binary collected by bugreportz can still see the file.
        when (val r = fetchViaBugreportz()) {
            is BugreportOk -> return finish(r, Method.BUGREPORTZ, r.zipBytes, previousSizeBytes)
            is BugreportFail -> return Result.Failure(
                "direct read failed (${directReasons.joinToString("; ")}); " +
                    "bugreportz failed: ${r.reason}",
            )
        }
    }

    // ---- (a) direct cat ----

    private sealed class DirectOutcome
    private class DirectOk(val file: File, val size: Long) : DirectOutcome()
    private class DirectFail(val reason: String) : DirectOutcome()

    private fun fetchDirect(path: String): DirectOutcome {
        // `ls -nl` first: distinguishes "no such file" from "permission denied"
        // and gives the expected size before we stream anything.
        val ls = runner.execFully("ls -nl $path")
        if (ls.exitCode != 0) {
            return DirectFail("ls: ${ls.text.trim().ifBlank { "exit ${ls.exitCode}" }}")
        }
        // Detect (don't trust) size for logging only.
        val sizeField = ls.text.trim().split(Regex("\\s+")).getOrNull(4)
        val bytes = tryCatGenerations(path)
        return when {
            bytes == null -> DirectFail("cat failed or permission denied")
            bytes < 16L -> {
                cacheFile.delete()
                DirectFail("log empty (${bytes} bytes) — HCI snoop logging off or no night captured")
            }
            !hasMagic(cacheFile) -> {
                cacheFile.delete()
                DirectFail("content is not a btsnoop file (${bytes} bytes, size field said $sizeField)")
            }
            else -> DirectOk(cacheFile, bytes)
        }
    }

    /**
     * Streams the current generation plus, when present, the previous
     * generation (`<path>.last`) into [cacheFile] as one chronologically
     * ordered file: `header + older-records + current-records`. Each raw
     * generation begins with its own 16-byte btsnoop header, so exactly one
     * header (the current generation's) is kept and both bodies are appended
     * header-less — a stray embedded header would otherwise be misread as a
     * corrupt record and abort [BtsnoopParser] at the seam. The current
     * generation is spooled to a sibling temp file to achieve the ordering
     * without holding bytes in memory. Returns total bytes written.
     */
    private fun tryCatGenerations(path: String): Long? {
        cacheFile.parentFile?.mkdirs()
        val tmpCur = File(cacheFile.parentFile, cacheFile.name + ".cur")
        return runCatching {
            val current = tryCatTo(path, tmpCur.outputStream()) ?: return null
            if (current < 16L) {
                tmpCur.delete()
                return current
            }
            var total = 0L
            cacheFile.outputStream().use { out ->
                // 1. header (from the current generation)
                tmpCur.inputStream().use { tin ->
                    val header = ByteArray(16)
                    var off = 0
                    while (off < 16) {
                        val n = tin.read(header, off, 16 - off)
                        if (n < 0) break
                        off += n
                    }
                    out.write(header, 0, off)
                    total += off
                }
                // 2. older records (skip the previous generation's own header)
                val lastPath = "$path.last"
                val ls = runCatching { runner.execFully("ls -nl $lastPath") }.getOrNull()
                if (ls != null && ls.exitCode == 0) {
                    tryCatTo(lastPath, SkippingAfter16OutputStream(out))
                }
                // 3. current records (skip the header already written)
                tmpCur.inputStream().use { tin ->
                    if (tin.skip(16L) == 16L) total += tin.copyTo(out)
                }
            }
            tmpCur.delete()
            total
        }.getOrElse {
            tmpCur.delete()
            null
        }
    }

    /** Streams `cat path` into [out]; returns bytes written or null on failure. */
    private fun tryCatTo(path: String, out: java.io.OutputStream): Long? =
        runCatching {
            val p = runner.exec("cat $path")
            var written = 0L
            p.stdout.use { input -> written = input.copyTo(out) }
            if (p.awaitExit() != 0) null else written
        }.getOrNull()

    /** OutputStream wrapper that silently discards the first 16 bytes (an embedded btsnoop header). */
    private class SkippingAfter16OutputStream(private val inner: java.io.OutputStream) : java.io.OutputStream() {
        private var dropped = 0
        override fun write(b: Int) {
            if (dropped < 16) dropped++ else inner.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (dropped >= 16) {
                inner.write(b, off, len)
                return
            }
            val skip = minOf(16 - dropped, len)
            dropped += skip
            if (len > skip) inner.write(b, off + skip, len - skip)
        }
    }


    // ---- (b) bugreportz ----

    private sealed class BugreportOutcome
    private class BugreportOk(val file: File, val size: Long, val zipBytes: Long) : BugreportOutcome()
    private class BugreportFail(val reason: String) : BugreportOutcome()

    private fun fetchViaBugreportz(): BugreportOutcome {
        val out = runner.execFully("bugreportz -p")
        if (out.exitCode != 0) {
            return BugreportFail("bugreportz exited ${out.exitCode}: ${out.text.trim().take(200)}")
        }
        // Output: "PROGRESS:x/y" lines, then "OK: /bugreport/bugreport-*.zip"
        val zipPath = out.text.lineSequence()
            .lastOrNull { it.startsWith("OK:") }
            ?.removePrefix("OK:")?.trim()
            ?.trim('"')
        if (zipPath.isNullOrBlank()) {
            return BugreportFail("bugreportz produced no zip path: ${out.text.trim().take(200)}")
        }

        cacheFile.parentFile?.mkdirs()
        var zipBytes = 0L
        return runCatching {
            val p = runner.exec("cat '$zipPath'")
            val raw = p.stdout
            val counted = object : InputStream() {
                override fun read(): Int = raw.read().also { if (it >= 0) zipBytes++ }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = raw.read(b, off, len)
                    if (n > 0) zipBytes += n
                    return n
                }
                override fun close() = raw.close()
            }
            val entry = BugreportZipEntryExtractor.extract(counted, cacheFile)
            val exit = p.awaitExit()
            // Best-effort cleanup; /bugreport is also purged by the system.
            runCatching { runner.exec("rm -f '$zipPath'") }
            // We stop reading the zip as soon as the btsnoop entry is fully
            // extracted, which SIGPIPEs the upstream `cat` (exit 141 / 13 on
            // some shells). That is the expected consequence of the early
            // close, not a truncation: the entry itself was read to EOF by
            // ZipInputStream before we returned. Validate content first; only
            // *other* non-zero exits mean the stream genuinely ended early.
            val streamClosedByUs = exit == 0 || exit == 141 || exit == 13
            when {
                entry == null -> {
                    cacheFile.delete()
                    BugreportFail("no btsnoop entry found inside bugreport zip (${zipBytes} bytes)")
                }
                !hasMagic(cacheFile) -> {
                    cacheFile.delete()
                    BugreportFail("extracted entry is not a btsnoop file")
                }
                !streamClosedByUs -> {
                    cacheFile.delete()
                    BugreportFail("bugreport zip stream ended early (exit $exit after $zipBytes bytes)")
                }
                else -> BugreportOk(cacheFile, cacheFile.length(), zipBytes)
            }
        }.getOrElse { BugreportFail("bugreportz path failed: ${it.message ?: it.javaClass.simpleName}") }
    }

    // ---- shared ----

    private fun finish(
        ok: DirectOk,
        method: Method,
        zipBytes: Long?,
        previousSizeBytes: Long?,
    ): Result.Success {
        val warnings = mutableListOf<String>()
        if (SnoopFingerprint.looksRotated(ok.size, previousSizeBytes)) {
            warnings.add(
                "log rotated — the new log (${ok.size} B) is smaller than the last imported one " +
                    "($previousSizeBytes B); some of the night may be missing. Consider disconnecting " +
                    "other Bluetooth devices (e.g. the Garmin watch) overnight to slow rotation.",
            )
        }
        return Result.Success(method, ok.file, ok.size, zipBytes, warnings)
    }

    private fun finish(
        ok: BugreportOk,
        method: Method,
        zipBytes: Long?,
        previousSizeBytes: Long?,
    ): Result.Success {
        val warnings = mutableListOf<String>()
        if (SnoopFingerprint.looksRotated(ok.size, previousSizeBytes)) {
            warnings.add(
                "log rotated — the new log (${ok.size} B) is smaller than the last imported one " +
                    "($previousSizeBytes B); some of the night may be missing. Consider disconnecting " +
                    "other Bluetooth devices (e.g. the Garmin watch) overnight to slow rotation.",
            )
        }
        return Result.Success(method, ok.file, ok.size, zipBytes, warnings)
    }

    private fun hasMagic(f: File): Boolean {
        val head = ByteArray(8)
        f.inputStream().use { s ->
            var off = 0
            while (off < 8) {
                val n = s.read(head, off, 8 - off)
                if (n < 0) return false
                off += n
            }
        }
        return head.contentEquals(MAGIC)
    }
}
