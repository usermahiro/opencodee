package com.celoext.data

import com.celoext.Graph
import org.json.JSONObject

/**
 * Free fallback routes for when Zen's built-in free models hit the server limit
 * (it's counted on Zen's side, doesn't always reset and doesn't depend on the client):
 *  - GitHub Copilot: the sign-in is there, but OpenCode server 1.19.4 hides Copilot models unless the API marks them
 *    "model_picker_enabled", so the models that really answer on the free plan are added as our own provider;
 *  - Pollinations: an anonymous OpenAI-compatible gateway, no account needed (messages go to pollinations.ai).
 * Everything is written to the global opencode.jsonc inside the environment.
 */
object FreeModels {
    const val COPILOT_ID = "copilot-chat"
    const val POLLINATIONS_ID = "pollinations"
    const val COPILOT_DEFAULT = "gpt-4.1-2025-04-14"
    const val POLLINATIONS_DEFAULT = "openai-fast"

    private val configFile get() = listOf(
        Graph.paths.host("/root/.config/opencode/opencode.json"),
        Graph.paths.host("/root/.config/opencode/opencode.jsonc"),
    ).firstOrNull { it.exists() } ?: Graph.paths.host("/root/.config/opencode/opencode.json")
    private val authFile get() = Graph.paths.host("/root/.local/share/opencode/auth.json")

    private fun copilotToken(): String? = runCatching {
        JSONObject(authFile.readText()).optJSONObject("github-copilot")?.optString("refresh")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun model(name: String, context: Int, output: Int, tools: Boolean, reasoning: Boolean, images: Boolean) = JSONObject()
        .put("name", name)
        .put("capabilities", JSONObject()
            .put("tools", tools)
            .put("input", if (images) org.json.JSONArray(listOf("text", "image")) else org.json.JSONArray(listOf("text")))
            .put("output", org.json.JSONArray(listOf("text")))
            .put("reasoning", JSONObject().put("supported", reasoning)))
        .put("limit", JSONObject().put("context", context).put("output", output))

    private fun disabledList(): List<String> = runCatching {
        val arr = JSONObject(configFile.readText()).optJSONArray("disabled_providers") ?: return emptyList()
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())

    /** Applied through the server: it writes the global config and picks it up at once (editing the file by hand would need a restart). */
    internal suspend fun apply(providerId: String, block: JSONObject?) {
        val disabled = disabledList().filter { it != providerId }.toMutableList()
        if (block == null) disabled.add(providerId)
        val patch = JSONObject().put("disabled_providers", org.json.JSONArray(disabled))
        if (block != null) {
            // OpenCode 2.x global config uses the plural `providers` namespace.
            patch.put("providers", JSONObject().put(providerId, block))
        }
        val jsonPatch = com.celoext.api.OcJson.parseToJsonElement(patch.toString()) as kotlinx.serialization.json.JsonObject
        try {
            try {
                Graph.api.patchGlobalConfig(jsonPatch)
            } catch (first: Exception) {
                // "HTTP 500" from the server and EACCES on the file: the config file or its folder has no write bit
                // (seen after a restore/extract). Fix the modes from inside the environment and try the server once more.
                if (Graph.devices.isRemote || !fixConfigPerms()) throw first
                Graph.api.patchGlobalConfig(jsonPatch)
            }
        } catch (e: Exception) {
            // The server's config write answered an error (a bare "HTTP 500" with Kamihub). The config is a plain
            // JSON(C) file in the environment, so merge the block into it directly and let the server reload it.
            val why = if (Graph.devices.isRemote) "remote device" else (mergeIntoFile(patch)?.let { fixConfigPerms(); mergeIntoFile(patch) })
            if (why != null) throw IllegalStateException("Saving the provider config failed: ${e.message ?: e.javaClass.simpleName} (file fallback: $why)")
            runCatching { Graph.api.disposeAll() }
        }
    }

    /** Opens the mode bits on the config folders from inside the environment. True when it ran. */
    private suspend fun fixConfigPerms(): Boolean = runCatching {
        val rc = Graph.runtime.runGuest(
            listOf("/bin/bash", "-c", "mkdir -p /root/.config/opencode /root/.local/share/opencode; chmod -R a+rwX /root/.config/opencode /root/.local/share/opencode; true"),
            timeoutSec = 30,
        )
        rc == 0
    }.getOrDefault(false)

    /** Drops line and block comments (outside strings) and trailing commas so a .jsonc file can be parsed. */
    private fun stripJsonc(src: String): String {
        val o = StringBuilder(); var i = 0; var str = false
        while (i < src.length) {
            val ch = src[i]
            if (str) { o.append(ch); if (ch == '\\' && i + 1 < src.length) { o.append(src[i + 1]); i++ } else if (ch == '"') str = false }
            else if (ch == '"') { str = true; o.append(ch) }
            else if (ch == '/' && i + 1 < src.length && src[i + 1] == '/') { while (i < src.length && src[i] != '\n') i++; continue }
            else if (ch == '/' && i + 1 < src.length && src[i + 1] == '*') { i += 2; while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++; i += 2; continue }
            else o.append(ch)
            i++
        }
        return o.toString().replace(Regex(",(\\s*[}\\]])"), "$1")
    }

    /** Returns null on success, otherwise a short reason. */
    private fun mergeIntoFile(patch: JSONObject): String? = try {
        val f = configFile
        val text = if (f.exists()) f.readText() else ""
        val root = if (text.isBlank()) JSONObject() else JSONObject(stripJsonc(text))
        fun merge(into: JSONObject, from: JSONObject) {
            for (k in from.keys()) {
                val v = from.get(k)
                val cur = into.opt(k)
                if (v is JSONObject && cur is JSONObject) merge(cur, v) else into.put(k, v)
            }
        }
        merge(root, patch)
        f.parentFile?.mkdirs()
        f.writeText(root.toString(2))
        null
    } catch (e: Exception) {
        (e.message ?: e.javaClass.simpleName).take(100)
    }

    /** Copilot models that answer on the free plan via /chat/completions. */
    /** [select] = false: only add the models without changing the selected one (GitHub sign-in shouldn't force Copilot). */
    suspend fun enableCopilot(select: Boolean = true): Boolean {
        val token = copilotToken() ?: return false
        apply(
            COPILOT_ID,
            JSONObject()
                .put("name", "GitHub Copilot")
                .put("package", "@opencode/ai/providers/openai-compatible")
                .put(
                    "settings",
                    JSONObject().put("baseURL", "https://api.githubcopilot.com").put("apiKey", token),
                )
                .put(
                    "headers",
                    JSONObject().put("Copilot-Integration-Id", "vscode-chat").put("Editor-Version", "vscode/1.107.0").put("User-Agent", "opencode-mobile"),
                )
                .put(
                    "models",
                    JSONObject()
                        .put("gpt-4.1-2025-04-14", model("GPT-4.1", 128000, 16384, true, false, true))
                        .put("gpt-4o-2024-11-20", model("GPT-4o", 128000, 16384, true, false, true))
                        .put("gpt-4o-mini-2024-07-18", model("GPT-4o mini", 128000, 16384, true, false, true)),
                ),
        )
        if (select) finish(COPILOT_ID, COPILOT_DEFAULT) else { runCatching { Graph.api.disposeAll() }; runCatching { Graph.catalog.refresh() }; Vault.save() }
        return true
    }

    suspend fun enablePollinations() {
        apply(
            POLLINATIONS_ID,
            JSONObject()
                .put("name", "Pollinations (no account)")
                .put("package", "@opencode/ai/providers/openai-compatible")
                .put("settings", JSONObject().put("baseURL", "https://text.pollinations.ai/openai").put("apiKey", "anonymous"))
                .put("models", JSONObject().put(POLLINATIONS_DEFAULT, model("GPT-OSS 20B", 131072, 8192, true, true, false))),
        )
        finish(POLLINATIONS_ID, POLLINATIONS_DEFAULT)
    }

    suspend fun disable(providerId: String) {
        runCatching { apply(providerId, null) }
        runCatching { Graph.catalog.refresh() }
        Vault.save()
    }

    suspend fun finish(providerId: String, modelId: String) {
        runCatching { Graph.api.disposeAll() }
        runCatching { Graph.catalog.refresh() }
        Graph.prefs.providerId = providerId; Graph.prefs.modelId = modelId; Graph.prefs.variant = ""
        Vault.save()
    }
}
