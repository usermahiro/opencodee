package com.celoext.api

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A settings change writes the global config file. On a fresh install the server lists only the
 * config directory, and writing to a directory fails with HTTP 500, so the path has to be built
 * from the directory instead.
 */
class ConfigDocumentTest {
    private fun entry(type: String, path: String) = buildJsonObject {
        put("type", type)
        put("path", path)
    }

    @Test fun usesTheDocumentWhenTheServerListsOne() {
        val path = configFilePath(listOf(entry("document", "/root/.config/opencode/opencode.jsonc"), entry("directory", "/root/.config/opencode")))
        assertEquals("/root/.config/opencode/opencode.jsonc", path)
    }

    @Test fun createsTheDocumentInsideTheDirectoryOnAFreshInstall() {
        val path = configFilePath(listOf(entry("directory", "/root/.config/opencode")))
        assertEquals("/root/.config/opencode/opencode.json", path)
    }

    @Test fun fallsBackToTheDefaultConfigPath() {
        assertEquals("/root/.config/opencode/opencode.json", configFilePath(emptyList()))
    }
}
