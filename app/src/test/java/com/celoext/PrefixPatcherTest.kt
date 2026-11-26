package com.celoext

import com.celoext.runtime.PrefixPatcher
import com.celoext.runtime.TermuxIndex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class PrefixPatcherTest {
    private val from = PrefixPatcher.TERMUX_PREFIX.toByteArray(Charsets.ISO_8859_1)

    @Test fun targetKeepsTheLengthOfTheTermuxPrefix() {
        val t = PrefixPatcher.targetPrefix("com.celoext")!!
        assertEquals(PrefixPatcher.TERMUX_PREFIX.length, t.length)
        assertEquals("/data/data/com.celoext/files/us", t)
    }

    @Test fun packageNameTooLongGivesNoTarget() {
        assertNull(PrefixPatcher.targetPrefix("com.celoext.debug.something.long"))
    }

    @Test fun replacesEveryOccurrenceIncludingAcrossChunkBoundary() {
        val to = PrefixPatcher.targetPrefix("com.celoext")!!.toByteArray(Charsets.ISO_8859_1)
        val f = File.createTempFile("patch", ".bin")
        try {
            val pad = ByteArray((1 shl 20) - 10) { 'x'.code.toByte() }   // puts the 2nd match over the chunk edge
            val bytes = from + "/bin/sh\u0000".toByteArray() + pad + from + "/lib".toByteArray()
            f.writeBytes(bytes)
            assertEquals(2, PrefixPatcher.patchFile(f, from, to))
            val want = to + "/bin/sh\u0000".toByteArray() + pad + to + "/lib".toByteArray()
            assertArrayEquals(want, f.readBytes())
        } finally { f.delete() }
    }

    @Test fun fileWithoutThePrefixIsUntouched() {
        val f = File.createTempFile("patch", ".bin")
        try {
            f.writeBytes("nothing here".toByteArray())
            assertEquals(0, PrefixPatcher.patchFile(f, from, from.copyOf()))
            assertEquals("nothing here", f.readText())
        } finally { f.delete() }
    }

    @Test fun closureListsDependenciesFirstAndHonoursSkip() {
        val text = """
Package: git
Filename: pool/main/g/git/git_1_aarch64.deb
SHA256: aa
Depends: libcurl, termux-exec, zlib (>= 1.2)

Package: libcurl
Filename: pool/main/libc/libcurl/libcurl_1_aarch64.deb
SHA256: bb
Depends: openssl | libssl, zlib

Package: openssl
Filename: pool/main/o/openssl/openssl_1_aarch64.deb
SHA256: cc

Package: zlib
Filename: pool/main/z/zlib/zlib_1_aarch64.deb
SHA256: dd

Package: termux-exec
Filename: pool/main/t/termux-exec/termux-exec_1_aarch64.deb
SHA256: ee
""".trimStart()
        val index = TermuxIndex.parse(text)
        val names = TermuxIndex.closure(index, listOf("git"), setOf("termux-exec")).map { it.name }
        assertEquals(listOf("openssl", "zlib", "libcurl", "git"), names)
    }
}
