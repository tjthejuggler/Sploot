package com.example.sploot.ble.snoop

import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * Change-detection fingerprint for a captured btsnoop log: byte size plus a
 * SHA-256 over the full content. The log only ever grows between reboots
 * (unless rotated), so size-equality is a cheap short-circuit; the hash guards
 * against same-size rewrites. Pure JVM, unit-tested.
 */
object SnoopFingerprint {

    data class Fingerprint(val sizeBytes: Long, val sha256: String) {
        /** Serialized form persisted in DataStore. */
        val encoded: String get() = "$sizeBytes:$sha256"

        override fun toString(): String = encoded

        companion object {
            fun decode(s: String?): Fingerprint? {
                val parts = s?.split(":") ?: return null
                if (parts.size != 2) return null
                val size = parts[0].toLongOrNull() ?: return null
                if (parts[1].length != 64) return null
                return Fingerprint(size, parts[1])
            }
        }
    }

    /** Decodes a persisted fingerprint ("size:sha256"), or null when malformed. */
    fun decode(encoded: String?): Fingerprint? = Fingerprint.decode(encoded)

    /** True when the on-device log differs from the last imported one. */
    fun hasChanged(current: Fingerprint, lastImported: Fingerprint?): Boolean =
        lastImported == null || current != lastImported

    /** True when the fresh log shrank — btsnoop rotation/truncation overnight. */
    fun looksRotated(currentSize: Long, lastSize: Long?): Boolean =
        lastSize != null && currentSize < lastSize

    /** Computes size + SHA-256 while streaming the file through once. */
    fun compute(file: File): Fingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        file.inputStream().let { fis ->
            DigestInputStream(fis, digest).use { dis ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = dis.read(buf)
                    if (n < 0) break
                    size += n
                }
            }
        }
        return Fingerprint(size, digest.digest().joinToString("") { "%02x".format(it) })
    }

    /** Funnel for arbitrary streams (tests). */
    fun compute(stream: InputStream): Fingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        DigestInputStream(stream, digest).use { dis ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = dis.read(buf)
                if (n < 0) break
                size += n
            }
        }
        return Fingerprint(size, digest.digest().joinToString("") { "%02x".format(it) })
    }
}
