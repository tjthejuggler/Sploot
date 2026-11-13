package com.example.sploot.ble.snoop

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream

/**
 * Stream-extracts the btsnoop entries from a bugreport zip without ever landing
 * the whole zip on disk: `bugreportz` output is piped straight into
 * [ZipInputStream] and only the matching entries' deflated bytes are copied out.
 * Pure JVM — unit-tested with synthetic zips.
 *
 * Rotation-aware (on-device failure 2026-11-02): vendor stacks keep the previous
 * generation as `btsnoop_hci.log.last` inside the same zip. When both entries
 * exist the output is one chronological file: the `.last` generation verbatim
 * (its 16-byte header becomes *the* file header), followed by the current
 * generation's *records* (its embedded header stripped — a stray header would
 * otherwise be misread as a corrupt record by [BtsnoopParser]). When only one
 * entry exists it is written as-is. Returns the primary entry name.
 */
object BugreportZipEntryExtractor {

    /** The canonical entry path inside an Android bugreport zip. */
    const val CANONICAL_ENTRY = "FS/data/misc/bluetooth/logs/btsnoop_hci.log"

    /** Suffix of the previous generation kept by rotating stacks. */
    const val LAST_ENTRY_SUFFIX = "btsnoop_hci.log.last"

    /** Accepts the canonical entry, any vendor variant ending in btsnoop_hci.log, or its `.last`. */
    fun isBtsnoopEntry(name: String): Boolean =
        name == CANONICAL_ENTRY ||
            name.endsWith("/btsnoop_hci.log") ||
            name.endsWith("btsnoop_hci.log") ||
            name.endsWith("/$LAST_ENTRY_SUFFIX") ||
            name.endsWith(LAST_ENTRY_SUFFIX)

    private fun isCurrent(name: String): Boolean =
        name.endsWith("btsnoop_hci.log") && !name.endsWith(".last")

    /**
     * Scans [zipStream] for the btsnoop entries and writes them (older
     * generation first) to [dest]. Returns the primary entry name, or null when
     * no matching entry exists.
     */
    fun extract(zipStream: InputStream, dest: File): String? {
        dest.parentFile?.mkdirs()
        dest.outputStream().use { out ->
            val name = extract(zipStream, out)
            if (name == null) dest.delete()
            return name
        }
    }

    /** Variant that writes to a caller-provided stream (used by tests). */
    fun extract(zipStream: InputStream, out: OutputStream): String? {
        var lastName: String? = null
        var currentName: String? = null
        val lastBytes = ByteArrayOutputStream()
        val currentBytes = ByteArrayOutputStream()

        ZipInputStream(zipStream.buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                if (!entry.isDirectory && isBtsnoopEntry(entry.name)) {
                    if (isCurrent(entry.name)) {
                        if (currentName == null) {
                            currentName = entry.name
                            zin.copyTo(currentBytes)
                        }
                    } else if (lastName == null) {
                        lastName = entry.name
                        zin.copyTo(lastBytes)
                    }
                }
                zin.closeEntry()
            }
        }

        var primary: String? = null
        if (lastName != null) {
            out.write(lastBytes.toByteArray())
            primary = lastName
        }
        if (currentName != null) {
            if (lastName != null) {
                // Chronological concat: append current generation minus its 16-byte header.
                val body = currentBytes.toByteArray()
                if (body.size > 16) out.write(body, 16, body.size - 16)
            } else {
                out.write(currentBytes.toByteArray())
            }
            primary = primary ?: currentName
        }
        return primary
    }
}
