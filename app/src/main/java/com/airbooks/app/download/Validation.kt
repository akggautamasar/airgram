package com.airbooks.app.download

import java.io.File
import java.security.MessageDigest

/**
 * Completed-file validation and digesting — pure JVM (java.io only) so integrity rules are
 * unit-testable. A download is never reported COMPLETED before [isValid] passes on the final
 * path, and SHA-256 runs only on finished files, never on the live download path.
 */
object FileValidation {
    /** Exists, non-empty, and exactly the size Telegram reported (0 = unknown, skip size check). */
    fun isValid(path: String?, expectedSize: Long): Boolean {
        if (path.isNullOrBlank()) return false
        val f = File(path)
        return runCatching { f.exists() && f.length() > 0 && (expectedSize <= 0 || f.length() == expectedSize) }
            .getOrDefault(false)
    }

    fun sha256(path: String): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        File(path).inputStream().use { inp ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = inp.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** Byte-identical copy check used when exporting next to an existing file. */
    fun sameContent(a: String, b: String): Boolean {
        val fa = File(a)
        val fb = File(b)
        if (!fa.exists() || !fb.exists() || fa.length() != fb.length()) return false
        return sha256(a) == sha256(b)
    }
}
