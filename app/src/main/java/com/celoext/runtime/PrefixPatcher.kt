package com.celoext.runtime

import java.io.File
import java.io.RandomAccessFile

/**
 * Termux binaries have /data/data/com.termux/files/usr baked into them (RUNPATH, shebangs, git's
 * exec path, curl's CA bundle...). Only the com.termux app may own that directory, so instead every
 * occurrence is swapped, byte for byte, for a path of exactly the same length inside this app's own
 * data dir. Same length means the swap is safe in ELF files too: nothing moves.
 */
object PrefixPatcher {
    const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"

    /**
     * Stand-in for [TERMUX_PREFIX]: /data/data/<package>/files/<name>, with <name> as long as it
     * takes to match the original's length. Null when the package name is too long for that
     * (a debug build with an applicationId suffix, say).
     */
    fun targetPrefix(packageName: String): String? {
        val head = "/data/data/$packageName/files/"
        val room = TERMUX_PREFIX.length - head.length
        if (room < 1) return null
        return head + ("usr" + "_".repeat(room)).take(room)
    }

    /** Swaps every [from] in [f] for [to] in place and returns how many were replaced. */
    fun patchFile(f: File, from: ByteArray, to: ByteArray): Int {
        require(from.isNotEmpty() && from.size == to.size) { "replacement must have the same length" }
        if (!f.isFile || java.nio.file.Files.isSymbolicLink(f.toPath())) return 0
        var count = 0
        RandomAccessFile(f, "rw").use { raf ->
            val len = raf.length()
            if (len < from.size) return 0
            val buf = ByteArray(1 shl 20)
            val keep = from.size - 1
            var pos = 0L
            while (pos < len) {
                raf.seek(pos)
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), len - pos).toInt())
                if (n <= 0) break
                var i = 0
                while (i <= n - from.size) {
                    if (buf[i] == from[0] && matches(buf, i, from)) {
                        raf.seek(pos + i)
                        raf.write(to)
                        count++
                        i += from.size
                    } else i++
                }
                if (pos + n >= len) break
                // Overlap the chunks so a match that straddles the boundary is still found.
                pos += n - keep
            }
        }
        return count
    }

    private fun matches(buf: ByteArray, at: Int, p: ByteArray): Boolean {
        for (k in 1 until p.size) if (buf[at + k] != p[k]) return false
        return true
    }
}
