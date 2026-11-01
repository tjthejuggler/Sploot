package com.example.sploot.ble.snoop

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream

/**
 * Stream-extracts the btsnoop entry from a bugreport zip without ever landing
 * the whole zip on disk: `bugreportz` output is piped straight into
 * [ZipInputStream] and only the matching entry's deflated bytes are copied out.
 * Pure JVM — unit-tested with synthetic zips.
 */
object BugreportZipEntryExtractor {

    /** The canonical entry path inside an Android bugreport zip. */
    const val CANONICAL_ENTRY = "FS/data/misc/bluetooth/logs/btsnoop_hci.log"

    /** Accepts the canonical entry or any vendor variant ending in btsnoop_hci.log. */
    fun isBtsnoopEntry(name: String): Boolean =
        name == CANONICAL_ENTRY || name.endsWith("/btsnoop_hci.log") || name.endsWith("btsnoop_hci.log")

    /**
     * Scans [zipStream] for the btsnoop entry and copies it to [dest].
     * Returns the extracted entry name, or null when no matching entry exists.
     */
    fun extract(zipStream: InputStream, dest: File): String? {
        dest.parentFile?.mkdirs()
        ZipInputStream(zipStream.buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                if (!entry.isDirectory && isBtsnoopEntry(entry.name)) {
                    dest.outputStream().use { out -> zin.copyTo(out) }
                    return entry.name
                }
                zin.closeEntry()
            }
        }
        return null
    }

    /** Variant that writes to a caller-provided stream (used by tests). */
    fun extract(zipStream: InputStream, out: OutputStream): String? {
        ZipInputStream(zipStream.buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                if (!entry.isDirectory && isBtsnoopEntry(entry.name)) {
                    zin.copyTo(out)
                    return entry.name
                }
                zin.closeEntry()
            }
        }
        return null
    }
}
