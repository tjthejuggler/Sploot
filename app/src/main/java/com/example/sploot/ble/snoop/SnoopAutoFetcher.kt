package com.example.sploot.ble.snoop

import java.io.File
import java.io.InputStream

/**
 * Automated acquisition of the on-device btsnoop log via a privileged shell
 * (Shizuku uid-shell in production; fakes in JVM tests). Fallback chain:
 *
 *  (a) DIRECT_SHELL — `cat /data/misc/bluetooth/logs/btsnoop_hci.log`.
 *      Verified working on Pixel/AOSP-style builds where the bluetooth logs
 *      directory is group-readable by shell (uid 2000).
 *  (b) BUGREPORTZ — `bugreportz -p` (runs dumpstate, which reads the log with
 *      elevated privileges), then stream `cat` the produced zip and extract
 *      only the `FS/data/misc/bluetooth/logs/btsnoop_hci.log` entry via
 *      [BugreportZipEntryExtractor]. Works on builds where (a) is blocked.
 *  (c) MANUAL_SAF — outside this class: the Home-screen "Import btsnoop log"
 *      SAF picker (the pre-Phase-12 flow), still available when Shizuku is
 *      unavailable entirely.
 *
 * Every successful acquisition validates the `btsnoop\0` magic before the
 * bytes are handed anywhere, so a permission-error HTML page or a truncated
 * shell error can never reach the parser.
 */
class SnoopAutoFetcher(
    private val runner: ShellRunner,
    private val cacheFile: File,
) {

    companion object {
        /** Standard AOSP path first, then vendor variants observed in the wild. */
        val CANDIDATE_PATHS = listOf(
            "/data/misc/bluetooth/logs/btsnoop_hci.log",
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
        val bytes = tryCat(path)
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

    /** Streams `cat path` into [cacheFile]; returns bytes written or null on failure. */
    private fun tryCat(path: String): Long? {
        cacheFile.parentFile?.mkdirs()
        return runCatching {
            val p = runner.exec("cat $path")
            var written = 0L
            p.stdout.use { input ->
                cacheFile.outputStream().use { out ->
                    written = input.copyTo(out)
                }
            }
            if (p.awaitExit() != 0) null else written
        }.getOrNull()
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
