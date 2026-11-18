package com.celoext.data

import com.celoext.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI-compatible gateways that the user connects with an API key (shown in Settings → Providers).
 * The provider block (base URL + model list) goes into the global opencode.jsonc through the server, and the key goes
 * into OpenCode's own auth store, so it ends up in the same place as the key of any other provider
 * (and Vault already backs that up).
 */
object CustomProviders {
    class Preset(
        val id: String,
        val name: String,
        val baseUrl: String,
        val exampleModel: String,
        /** Tried first when picking the default model (the one known to answer on /chat/completions). */
        val preferredModel: String = exampleModel,
        /** Registered when the gateway can't list its models (an outage, or /models returning 500). */
        val fallbackModels: List<Pair<String, Int>> = emptyList(),
    )

    // The gateway prefixes every id with the upstream it routes to ("atmoro/…"), and rejects the bare name
    // with a 500. The list is only a fallback for when /models itself fails.
    val KAMIHUB = Preset(
        id = "kamihub",
        name = "Kamihub",
        baseUrl = "https://kamihub.us.ci/v1",
        exampleModel = "atmoro/alicn/deepseek-v4.1-flash",
        preferredModel = "discovery/Atria-Dawn",
        fallbackModels = listOf(
            "atmoro/alicn/deepseek-v4.1-flash" to 128_000,
            "atmoro/atmo/deepseek-v4.1-flash" to 128_000,
            "atmoro/alicn/deepseek-v4-flash-0731" to 128_000,
            "atmoro/alicn/deepseek-v4-pro-0813" to 128_000,
            "atmoro/alicn/qwen3.8-flash" to 128_000,
            "atmoro/alicn/qwen3.8-omni-flash" to 128_000,
            "atmoro/alicn/qwen3.8-max" to 128_000,
            "atmoro/alicn/qwen3.8-max-0902" to 128_000,
            "atmoro/alicn/kimi-k2.6" to 200_000,
            "atmoro/alicn/glm-5.2" to 128_000,
            "atmoro/ag/claude-sonnet-4-6" to 1_000_000,
            "atmoro/cx/gpt-6-luna" to 128_000,
            "atmoro/mai/muse-spark-1.3-contributor" to 128_000,
            "atmoro/mm/MiniMax-M2.1" to 200_000,
            "atmoro/mm/MiniMax-M3" to 200_000,
            "atmoro/mm/MiniMax-M3.1-Flash" to 1_000_000,
            "atmoro/zai/glm-4.6v" to 128_000,
            "atmoro/zai/glm-5.3" to 128_000,
            "atmoro/zai/glm-5.3-flash" to 1_000_000,
            "discovery/Agents-A1" to 128_000,
            "discovery/Atria-Dawn" to 128_000,
            "discovery/Atria-Dawn-Preview" to 128_000,
            "discovery/intern-s2" to 128_000,
            "discovery/Sage-A1.5" to 128_000,
        ),
    )

    /**
     * The local 9Router gateway this app hosts in its own environment (Settings → 9Router).
     * The base URL points at the loopback port the gateway listens on, so the key the user copies
     * from the 9Router dashboard works like any other OpenAI-compatible key.
     */
    fun NINEROUTER(port: Int) = Preset(
        id = "9router",
        name = "9Router",
        baseUrl = "http://127.0.0.1:$port/v1",
        exampleModel = "kr/claude-sonnet-4.5",
        preferredModel = "kr/claude-sonnet-4.5",
        fallbackModels = listOf(
            "kr/claude-sonnet-4.5" to 200_000,
            "kr/glm-5" to 128_000,
            "kr/claude-haiku-4.5" to 200_000,
            "kr/MiniMax-M2.5" to 200_000,
        ),
    )

    class Connected(val count: Int, val model: String, /** Non-null when no model answered a test message. */ val warning: String?)

    /** Registers the provider and picks a model that really answers. Throws a readable message when the key is refused or the gateway is unreachable. */
    suspend fun connect(p: Preset, rawKey: String): Connected = withContext(Dispatchers.IO) {
        val key = rawKey.trim()
        require(key.isNotEmpty() && key.none { it.isWhitespace() || it.isISOControl() }) { "The API key has spaces or line breaks in it" }
        val models = fetchModels(p, key)
        // Same request as: curl {baseUrl}/chat/completions -H "Authorization: Bearer KEY" -d '{"model":...,"messages":[...]}'
        // The list can contain ids that fail (the gateway answers 500 for some), so test a few and keep one that answers.
        val ids = models.keys().asSequence().toList()
        val candidates = (listOf(p.preferredModel, p.exampleModel) + ids).distinct().take(5)
        var chosen: String? = null
        var lastError: String? = null
        for (id in candidates) {
            val r = probeChat(p, key, id)
            if (r == null) { chosen = id; break }
            if (r.startsWith("refused")) error("${p.name} ${r}")
            lastError = "$id: $r"
        }
        val model = chosen ?: p.preferredModel
        // The chosen model must exist in the registered list even when /models didn't return it.
        if (!models.has(model)) models.put(model, modelEntry(model, 128_000, 8192))
        val block = JSONObject()
            .put("name", p.name)
            // OpenCode 2.x uses the native provider package and `settings`, not the V1 npm/options shape.
            .put("package", "@opencode/ai/providers/openai-compatible")
            // The compatible provider reads these package-specific settings when issuing requests.
            .put("settings", JSONObject().put("baseURL", p.baseUrl).put("apiKey", key))
            .put("models", models)
        FreeModels.apply(p.id, block)
        // Also keep it in OpenCode's auth store, like any other provider. Some server versions don't know a
        // custom integration id here and answer 500 (which used to abort the whole connect), so it is best effort.
        runCatching { Graph.api.setApiKey(p.id, key) }
        runCatching { Graph.api.disposeAll() }
        runCatching { Graph.catalog.refresh() }
        FreeModels.finish(p.id, model)
        Connected(models.length(), model, if (chosen == null) "No model answered a test message (${lastError.orEmpty().take(140)})" else null)
    }

    /** One tiny chat request. Returns null when the model answered, otherwise a short reason. */
    private fun probeChat(p: Preset, key: String, model: String): String? {
        val c = URL(p.baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection
        return try {
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("Authorization", "Bearer $key")
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "opencode-mobile")
            val body = JSONObject().put("model", model).put("max_tokens", 8)
                .put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", "halo"))).toString()
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            if (code in 200..299) return null
            val text = runCatching { c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty() }.getOrDefault("").trim().replace(Regex("\\s+"), " ").take(120)
            if (code == 401 || code == 403) "refused the API key (HTTP $code)" else "HTTP $code" + if (text.isBlank()) "" else " $text"
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        } finally {
            c.disconnect()
        }
    }

    private fun modelEntry(id: String, context: Int, output: Int) = JSONObject()
        .put("name", id)
        .put("capabilities", JSONObject()
            .put("tools", true)
            .put("input", org.json.JSONArray(listOf("text")))
            .put("output", org.json.JSONArray(listOf("text")))
            .put("reasoning", JSONObject().put("supported", false)))
        .put("limit", JSONObject().put("context", context).put("output", output))

    private fun fallback(p: Preset): JSONObject {
        val ids = p.fallbackModels.ifEmpty { listOf(p.exampleModel to 128_000) }
        return JSONObject().apply {
            ids.forEach { (id, ctx) -> put(id, modelEntry(id, ctx, minOf(8192, ctx))) }
        }
    }

    /**
     * GET {baseUrl}/models. A refused key stops here (an OpenAI-compatible gateway reports that as 401/403, but some
     * report it as 200 with an `error` body, which used to be taken for a model list). A gateway whose model list is
     * down (5xx) or missing (404/405) still gets its default models, because chat usually works.
     */
    private fun fetchModels(p: Preset, key: String): JSONObject {
        val c = URL(p.baseUrl.trimEnd('/') + "/models").openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 15_000; c.readTimeout = 20_000
            c.setRequestProperty("Authorization", "Bearer $key")
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "opencode-mobile")
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = runCatching { stream?.bufferedReader()?.use { it.readText() }.orEmpty() }.getOrDefault("")
            when {
                code == 401 || code == 403 -> error("${p.name} refused the API key (HTTP $code)")
                code == 404 || code == 405 -> fallback(p)
                code in 500..599 -> fallback(p) // listing is broken on their side; the chat endpoint may still answer
                code !in 200..299 -> error("${p.name} answered HTTP $code" + body.trim().take(160).let { if (it.isBlank()) "" else ": $it" })
                else -> parseModels(p, body) ?: fallback(p)
            }
        } finally {
            c.disconnect()
        }
    }

    private fun parseModels(p: Preset, body: String): JSONObject? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val data = root.optJSONArray("data")
        if (data == null) {
            // 200 + {"error": "API key required…"}: the gateway has no models for this key, so say so instead of
            // registering a model that will fail on the first message
            val err = root.optString("error").takeIf { it.isNotBlank() && it != "null" }
                ?: root.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            if (err != null) error("${p.name}: $err")
            return null
        }
        val out = JSONObject()
        for (i in 0 until data.length()) {
            val m = data.optJSONObject(i) ?: continue
            val id = m.optString("id").takeIf { it.isNotBlank() } ?: continue
            val ctx = m.optInt("context_length", m.optInt("context_window", 128_000)).takeIf { it > 0 } ?: 128_000
            out.put(id, modelEntry(id, ctx, minOf(8192, ctx)))
        }
        return out.takeIf { it.length() > 0 }
    }
}
