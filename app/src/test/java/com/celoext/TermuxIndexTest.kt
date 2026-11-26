package com.celoext

import com.celoext.runtime.TermuxIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TermuxIndexTest {
    private val index = """
Package: ripgrep
Version: 15.3.0
Architecture: aarch64
Filename: pool/main/r/ripgrep/ripgrep_15.3.0_aarch64.deb
SHA256: ABCDEF0123

Package: zsh
Version: 5.9
Filename: pool/main/z/zsh/zsh_5.9_aarch64.deb
SHA256: 1111

Package: pcre2
Filename: pool/main/p/pcre2/pcre2_10.50_aarch64.deb
SHA256: 2222
""".trimStart()

    @Test fun picksWantedPackagesOnly() {
        val m = TermuxIndex.parse(index, setOf("ripgrep", "pcre2"))
        assertEquals(setOf("ripgrep", "pcre2"), m.keys)
        assertFalse(m.containsKey("zsh"))
    }

    @Test fun readsPathAndLowercasesSha() {
        val e = TermuxIndex.parse(index, setOf("ripgrep")).getValue("ripgrep")
        assertEquals("pool/main/r/ripgrep/ripgrep_15.3.0_aarch64.deb", e.path)
        assertEquals("abcdef0123", e.sha)
    }

    @Test fun crlfIndexParses() {
        val m = TermuxIndex.parse(index.replace("\n", "\r\n"), setOf("ripgrep", "pcre2"))
        assertEquals(2, m.size)
    }
}
