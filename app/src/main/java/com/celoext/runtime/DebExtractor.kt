package com.celoext.runtime

import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.InputStream

/**
 * Extracts Termux .deb packages without external tools: .deb is an ar archive whose
 * `data.tar.xz` member holds the package files (the whole Termux prefix layout).
 */
object DebExtractor {

    fun extract(
        deb: File,
        dest: File,
        /** Called with each regular file once it is written (the Termux relocation patches it here). */
        onFile: ((File) -> Unit)? = null,
        // Last on purpose: callers pass the progress callback as a trailing lambda.
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): TarExtractor.Result {
        deb.inputStream().buffered(1 shl 16).use { input ->
            val magic = ByteArray(8)
            if (!readFully(input, magic) || String(magic) != "!<arch>\n") error("Not a .deb archive: ${deb.name}")
            var dataTar: ByteArray? = null
            val members = ArrayList<String>()
            while (true) {
                val header = ByteArray(60)
                if (!readFully(input, header)) break
                // GNU `ar` ends member names with '/', dpkg-deb pads with spaces: accept both.
                val name = String(header, 0, 16).trim('\u0000', ' ').trimEnd('/')
                members += name
                val size = String(header, 48, 10).trim().toLongOrNull() ?: error("Corrupt .deb member header in ${deb.name}")
                // data.tar.xz today; match any compression suffix and pick the decoder by magic bytes below.
                if (dataTar == null && name.startsWith("data.tar")) {
                    dataTar = ByteArray(size.toInt())
                    if (!readFully(input, dataTar)) error("Truncated $name in ${deb.name}")
                } else {
                    skip(input, size)
                }
                if (size % 2 == 1L) skip(input, 1)
            }
            val data = dataTar ?: error("${deb.name} has no data.tar member (members: ${members.joinToString().ifEmpty { "none" }})")
            // Say clearly when a package uses a compression we can't unpack, instead of failing in the tar reader.
            if (data.size > 4 && data[0] == 0x28.toByte() && data[1] == 0xB5.toByte() && data[2] == 0x2F.toByte() && data[3] == 0xFD.toByte()) {
                error("${deb.name}: data member is zstd-compressed (members: ${members.joinToString()}); only xz, gzip and plain tar are supported")
            }
            if (data.size > 3 && data[0] == 'B'.code.toByte() && data[1] == 'Z'.code.toByte() && data[2] == 'h'.code.toByte()) {
                error("${deb.name}: data member is bzip2-compressed (members: ${members.joinToString()}); only xz, gzip and plain tar are supported")
            }
            // Termux debs are double compressed today; accept a plain .gz or .tar too, cheap insurance.
            val raw = TarExtractor.CountingStream(data.inputStream())
            val tar: InputStream = when {
                data.size > 6 && data[0] == 0xFD.toByte() && data[1] == '7'.code.toByte() && data[2] == 'z'.code.toByte() -> XZInputStream(raw)
                data.size > 2 && data[0] == 0x1F.toByte() && data[1] == 0x8B.toByte() -> java.util.zip.GZIPInputStream(raw)
                else -> raw
            }
            // Progress is measured on the compressed member, the unit of the total passed here.
            return TarExtractor.extract(tar, data.size.toLong(), dest, onProgress, consumed = { raw.count }, onFile = onFile)
        }
    }

    private fun readFully(i: InputStream, b: ByteArray): Boolean {
        var off = 0
        while (off < b.size) {
            val r = i.read(b, off, b.size - off)
            if (r < 0) return off != 0 && error("truncated archive")
            off += r
        }
        return true
    }

    private fun skip(i: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(1 shl 14)
        while (left > 0) { val r = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (r < 0) return; left -= r }
    }
}
