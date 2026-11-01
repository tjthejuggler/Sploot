package com.example.sploot.ble.snoop

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

/**
 * [ShellRunner] backed by Shizuku's canonical remote-process API.
 *
 * The Shizuku API artifact (13.1.5) does not expose a static
 * `Shizuku.newProcess(...)`, but the underlying AIDL does:
 * [IShizukuService.newProcess] runs the command with shell (uid 2000)
 * privileges inside the Shizuku server process and returns an
 * [moe.shizuku.server.IRemoteProcess] whose stdin/stdout/stderr are binder
 * pipes and whose exit code is queryable — no UserService, no second APK
 * process spawned inside Shizuku, and no bind-latch that can wedge either
 * process.
 */
class ShizukuShellRunner : ShellRunner {

    override val isAvailable: Boolean
        get() = runCatching {
            Shizuku.pingBinder() &&
                !Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    override val unavailableReason: String?
        get() = runCatching {
            when {
                !Shizuku.pingBinder() ->
                    "Shizuku is not running — start it (wireless debugging) and reopen Sploot"
                Shizuku.isPreV11() -> "Shizuku server too old — update Shizuku (v11+)"
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
                    "Sploot is not granted in Shizuku — open Shizuku and allow Sploot"
                else -> null
            }
        }.getOrNull() ?: "Shizuku unavailable"

    override fun exec(command: String): ShellProcess {
        val binder = Shizuku.getBinder()
            ?: throw IllegalStateException(unavailableReason ?: "Shizuku binder unavailable")
        val service = IShizukuService.Stub.asInterface(binder)
        val remote = runCatching {
            service.newProcess(arrayOf("sh", "-c", command), null, null)
        }.getOrElse { e ->
            throw IllegalStateException(
                "Shizuku newProcess failed: ${e.message ?: e.javaClass.simpleName}",
                e,
            )
        }
        val stdout = ParcelFileDescriptor.AutoCloseInputStream(remote.inputStream)
        val stderr = ParcelFileDescriptor.AutoCloseInputStream(remote.errorStream)
        // Drain stderr continuously on a helper thread — commands like
        // `bugreportz -p` stream progress to stderr, and if nobody reads it
        // the pipe fills and the command deadlocks. Keep only the tail for
        // diagnostics; never stop reading before EOF.
        val stderrTail = StringBuilder()
        Thread {
            runCatching {
                stderr.use { s ->
                    val buf = ByteArray(1 shl 14)
                    while (true) {
                        val n = s.read(buf)
                        if (n < 0) break
                        stderrTail.append(String(buf, 0, n, Charsets.UTF_8))
                        if (stderrTail.length > 16_384) stderrTail.delete(0, 8_192)
                    }
                }
            }
        }.apply { isDaemon = true }.start()
        return ShellProcess(stdout) {
            // Called after stdout is fully consumed, i.e. after process exit,
            // so exitValue() does not block a Shizuku binder thread. If the
            // process somehow has not exited yet, fall back to waitFor().
            runCatching { remote.exitValue() }
                .recoverCatching { remote.waitFor() }
                .getOrDefault(-1)
        }
    }
}
