package com.example.sploot.ble.snoop

import java.io.InputStream

/**
 * One remote shell command execution. stdout is streamed (btsnoop logs can be
 * hundreds of MB); the exit code is only known after stdout has been consumed,
 * via [awaitExit].
 */
class ShellProcess(
    val stdout: InputStream,
    private val awaitExitFn: () -> Int,
) {
    /** Blocks until the command finishes; call after draining stdout. */
    fun awaitExit(): Int = awaitExitFn()
}

/** Result of a fully-consumed command (used for small outputs like `ls`/`bugreportz`). */
data class ShellOutput(val exitCode: Int, val bytes: ByteArray) {
    val text: String get() = String(bytes, Charsets.UTF_8)
}

/**
 * Abstraction over the privileged command runner (Shizuku uid-shell in
 * production; a scripted fake in JVM unit tests). Everything in
 * [SnoopAutoFetcher] is written against this interface so the fallback state
 * machine is unit-testable without a device.
 */
interface ShellRunner {
    /** True when the transport is usable at all (Shizuku running + permission granted). */
    val isAvailable: Boolean

    /** Human-readable reason when [isAvailable] is false, null otherwise. */
    val unavailableReason: String?

    /** Starts [command] (run via `sh -c`) and returns its streaming stdout. */
    fun exec(command: String): ShellProcess

    /** Convenience: runs [command], drains stdout into memory, returns code + bytes. */
    fun execFully(command: String): ShellOutput {
        val p = exec(command)
        val bytes = p.stdout.use { it.readBytes() }
        return ShellOutput(p.awaitExit(), bytes)
    }
}
