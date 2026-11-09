package com.celoext.api

import com.celoext.Graph
import com.celoext.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(val code: Int, message: String) : IOException(message)

/** REST API client for OpenCode 2.2.5 with v1 compatibility translation layer. */
class OpenCodeApi(private val prefs: Prefs) {
    companion object {
        /** The server rejects any `limit` above 200 ("Expected a value less than or equal to 200"), so never send more. */
        const val MAX_LIMIT = 200
        fun pageLimit(n: Int) = n.coerceIn(1, MAX_LIMIT)
    }

    val baseUrl get() = Graph.devices.active()?.url ?: "http://127.0.0.1:${prefs.serverPort}"
    private val password get() = Graph.devices.active()?.password ?: prefs.serverPassword
    private val jsonType = "application/json".toMediaType()

    val http: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 32 })
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** SSE client: the server sends heartbeats, 30 s silence triggers reconnect. */
    val streamHttp: OkHttpClient = http.newBuilder().readTimeout(30, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()

    private val permissionSessions = ConcurrentHashMap<String, String>()
    private val formCache = ConcurrentHashMap<String, JsonObject>()
    private val oauthAttempts = ConcurrentHashMap<String, String>()
    private val configMutex = Mutex()

    fun request(path: String, query: Map<String, String?> = emptyMap()): Request.Builder {
        val url = (baseUrl + path).toHttpUrl().newBuilder().apply {
            query.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) }
        }.build()
        return Request.Builder().url(url).header("Authorization", Credentials.basic("opencode", password))
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { c ->
        c.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (!c.isCancelled) c.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { c.resume(response) }
        })
    }

    private suspend fun call(
        method: String,
        path: String,
        query: Map<String, String?> = emptyMap(),
        body: JsonElement? = null,
    ): JsonElement? {
        val rb = body?.let { OcJson.encodeToString(JsonElement.serializer(), it).toRequestBody(jsonType) }
            ?: if (method == "GET" || method == "DELETE" && body == null) null else "{}".toRequestBody(jsonType)
        val req = request(path, query).method(method, rb).build()
        return http.newCall(req).await().use { r ->
            val text = withContext(Dispatchers.IO) { r.body.string() }
            if (!r.isSuccessful) throw ApiException(r.code, errorText(text, r.code))
            if (text.isBlank()) null else runCatching { OcJson.parseToJsonElement(text) }.getOrNull()
        }
    }

    private suspend fun callRaw(
        path: String,
        query: Map<String, String?> = emptyMap(),
        rawBody: ByteArray,
        contentType: String = "application/octet-stream",
    ) {
        val rb = rawBody.toRequestBody(contentType.toMediaType())
        val req = request(path, query).post(rb).build()
        http.newCall(req).await().use { r ->
            val text = withContext(Dispatchers.IO) { r.body.string() }
            if (!r.isSuccessful) throw ApiException(r.code, errorText(text, r.code))
        }
    }

    private fun errorText(body: String, code: Int): String {
        val o = runCatching { OcJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val msg = o?.str("message") ?: (o?.obj("data"))?.str("message") ?: (o?.arr("error")?.firstOrNull() as? JsonObject)?.str("message")
        return msg ?: "HTTP $code" + body.take(200).let { if (it.isBlank()) "" else ": $it" }
    }

    private fun dir(d: String?) = if (d.isNullOrBlank()) emptyMap() else mapOf("directory" to d)
    private fun loc(d: String?) = if (d.isNullOrBlank()) emptyMap() else mapOf("location[directory]" to d)

    // ---------- server ----------

    private val healthHttp by lazy { http.newBuilder().callTimeout(2, TimeUnit.SECONDS).build() }

    suspend fun healthVersionOrThrow(): String? {
        var last: Exception? = null
        for (path in listOf("/api/info", "/api/health", "/global/health")) {
            try {
                return healthHttp.newCall(request(path).build()).await().use { r ->
                    val text = withContext(Dispatchers.IO) { r.body.string() }
                    if (!r.isSuccessful) throw ApiException(r.code, "health $path ${r.code}")
                    (runCatching { OcJson.parseToJsonElement(text) as? JsonObject }.getOrNull())?.str("version") ?: "2.x"
                }
            } catch (e: Exception) { last = e }
        }
        throw last ?: ApiException(-1, "health failed")
    }

    suspend fun reachable(url: String, password: String): String? = try {
        val req = Request.Builder().url(url.trimEnd('/') + "/api/info").header("Authorization", Credentials.basic("opencode", password)).build()
        healthHttp.newBuilder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).build().newCall(req).await().use { r ->
            when {
                r.code == 401 -> "Wrong password"
                !r.isSuccessful -> "Server answered ${r.code}"
                else -> null
            }
        }
    } catch (_: Exception) { "Not reachable — is it on, on the same network, and is `opencode serve` running?" }

    suspend fun probe(url: String, password: String): String {
        val req = Request.Builder().url(url.trimEnd('/') + "/api/info").header("Authorization", Credentials.basic("opencode", password)).build()
        return http.newCall(req).await().use { r ->
            val text = withContext(Dispatchers.IO) { r.body.string() }
            when {
                r.code == 401 -> throw ApiException(401, "Wrong password")
                !r.isSuccessful -> throw ApiException(r.code, "Server answered ${r.code}")
                else -> (runCatching { OcJson.parseToJsonElement(text) as? JsonObject }.getOrNull())?.str("version") ?: "unknown"
            }
        }
    }

    // ---------- sessions ----------

    suspend fun listSessions(directory: String? = null, limit: Int = MAX_LIMIT, search: String? = null): List<Session> {
        val q = dir(directory) + mapOf("limit" to pageLimit(limit).toString(), "search" to search)
        val res = call("GET", "/api/session", q)
        val arr = (res as? JsonObject)?.arr("data") ?: res as? JsonArray ?: JsonArray(emptyList())
        return arr.mapNotNull { (it as? JsonObject)?.let(::parseSession) }
    }

    suspend fun createSession(directory: String, title: String? = null, permission: JsonArray? = null): Session {
        val body = buildJsonObject {
            putJsonObject("location") { put("directory", directory) }
            title?.let { put("title", it) }
            permission?.let { put("permissions", it) }
        }
        val res = call("POST", "/api/session", body = body)!!
        val root = res as JsonObject
        // the session may sit under "data", "session" or at the top level, depending on the server version
        val data = listOf(root.obj("data"), root.obj("session"), root).filterNotNull().firstOrNull { it.str("id")?.startsWith("ses") == true }
            ?: throw ApiException(-1, "The server did not return a session id: ${root.toString().take(160)}")
        return parseSession(data)
    }

    suspend fun renameSession(id: String, directory: String?, title: String) {
        call("PATCH", "/api/session/$id", loc(directory), buildJsonObject { put("title", title) })
    }

    suspend fun archiveSession(id: String, directory: String?) {
        call("PATCH", "/api/session/$id", loc(directory), buildJsonObject { put("archived", true) })
    }

    suspend fun setSessionPermission(id: String, directory: String?, rules: JsonArray) {
        call("PATCH", "/api/session/$id", loc(directory), buildJsonObject { put("permissions", rules) })
    }

    suspend fun deleteSession(id: String, directory: String?) {
        call("DELETE", "/api/session/$id", loc(directory))
    }

    suspend fun forkSession(id: String, directory: String?, messageID: String?): Session {
        val body = buildJsonObject { messageID?.let { put("before", it) } }
        val res = call("POST", "/api/session/$id/fork", loc(directory), body)!!
        val data = (res as? JsonObject)?.obj("data") ?: res as JsonObject
        return parseSession(data)
    }

    suspend fun summarize(id: String, directory: String?, providerID: String, modelID: String) {
        call("POST", "/api/session/$id/compact", loc(directory))
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun deleteMessage(id: String, directory: String?, messageID: String) {
        // v2 does not expose a message deletion endpoint
    }

    suspend fun abort(id: String, directory: String?) {
        call("POST", "/api/session/$id/interrupt", loc(directory))
    }

    suspend fun revert(id: String, directory: String?, messageID: String) {
        call("POST", "/api/session/$id/revert/stage", loc(directory), buildJsonObject {
            put("messageID", messageID)
            put("files", true)
        })
        call("POST", "/api/session/$id/revert/commit", loc(directory))
    }

    suspend fun statuses(directory: String?): Map<String, JsonObject> {
        val res = call("GET", "/api/session/active", loc(directory)) as? JsonObject ?: return emptyMap()
        val data = res.obj("data") ?: res
        return data.mapNotNull { (k, v) ->
            (v as? JsonObject)?.let { k to buildJsonObject { put("type", "busy") } }
        }.toMap()
    }

    suspend fun messages(id: String, directory: String?, limit: Int = MAX_LIMIT): List<MessageWithParts> {
        val res = call("GET", "/api/session/$id/message", loc(directory) + mapOf("limit" to pageLimit(limit).toString()))
            ?: JsonArray(emptyList())
        return parseMessages(res, defaultSessionID = id)
    }

    suspend fun todos(id: String, directory: String?): JsonArray {
        val msgs = runCatching { messages(id, directory, limit = 100) }.getOrDefault(emptyList())
        for (m in msgs.asReversed()) {
            for (p in m.parts.asReversed()) {
                if (p.raw.str("tool") == "todowrite") {
                    val arr = p.raw.obj("state")?.obj("metadata")?.arr("todos")
                    if (arr != null) return arr
                }
            }
        }
        return JsonArray(emptyList())
    }

    suspend fun diff(id: String, directory: String?): JsonArray {
        val res = call("GET", "/api/session/$id/diff", loc(directory))
        return (res as? JsonObject)?.arr("data") ?: res as? JsonArray ?: JsonArray(emptyList())
    }

    suspend fun promptAsync(
        id: String,
        directory: String?,
        parts: JsonArray,
        providerID: String?,
        modelID: String?,
        agent: String?,
        variant: String?,
        system: String? = null,
    ) {
        if (!system.isNullOrBlank()) {
            runCatching {
                call("POST", "/api/session/$id/synthetic", loc(directory), buildJsonObject {
                    put("text", system)
                    put("resume", false)
                })
            }
        }
        if (!providerID.isNullOrBlank() && !modelID.isNullOrBlank()) {
            runCatching {
                call("POST", "/api/session/$id/model", loc(directory), buildJsonObject {
                    putJsonObject("model") {
                        put("id", modelID)
                        put("providerID", providerID)
                        if (!variant.isNullOrBlank()) put("variant", variant)
                    }
                })
            }
        }
        if (!agent.isNullOrBlank()) {
            runCatching {
                call("POST", "/api/session/$id/agent", loc(directory), buildJsonObject {
                    put("agent", agent)
                })
            }
        }

        val textParts = ArrayList<String>()
        val fileParts = ArrayList<JsonObject>()

        for (pEl in parts) {
            val p = pEl as? JsonObject ?: continue
            when (p.str("type")) {
                "text" -> p.str("text")?.let { textParts.add(it) }
                "file" -> {
                    val uri = p.str("url") ?: continue
                    val name = p.str("filename") ?: p.str("name")
                    fileParts.add(buildJsonObject {
                        put("uri", uri)
                        name?.let { put("name", it) }
                    })
                }
            }
        }

        val body = buildJsonObject {
            put("text", textParts.joinToString("\n"))
            if (fileParts.isNotEmpty()) {
                putJsonArray("files") { fileParts.forEach { add(it) } }
            }
        }
        call("POST", "/api/session/$id/prompt", loc(directory), body)
    }

    // ---------- permissions and questions ----------

    suspend fun permissions(directory: String?): List<PermissionReq> {
        val res = call("GET", "/api/permission/request", loc(directory))
        val arr = (res as? JsonObject)?.arr("data") ?: res as? JsonArray ?: JsonArray(emptyList())
        val list = arr.mapNotNull { (it as? JsonObject)?.let(::parsePermission) }
        list.forEach { permissionSessions[it.id] = it.sessionID }
        return list
    }

    suspend fun replyPermission(requestId: String, directory: String?, reply: String, sessionId: String? = null) {
        val sid = sessionId ?: permissionSessions[requestId]
        if (sid != null) {
            call("POST", "/api/session/$sid/permission/$requestId/reply", loc(directory), buildJsonObject {
                put("decision", reply)
            })
        }
    }

    suspend fun questions(directory: String?): List<QuestionReq> {
        val res = call("GET", "/api/form", loc(directory))
        val arr = (res as? JsonObject)?.arr("data") ?: res as? JsonArray ?: JsonArray(emptyList())
        val list = ArrayList<QuestionReq>()
        for (item in arr) {
            val o = item as? JsonObject ?: continue
            val id = o.str("id") ?: continue
            formCache[id] = o
            list.add(parseQuestion(o))
        }
        return list
    }

    suspend fun replyQuestion(requestId: String, directory: String?, answers: List<List<String>>, sessionId: String? = null) {
        val form = formCache[requestId]
        val sid = sessionId ?: form?.str("sessionID") ?: return
        val fields = form?.arr("fields").orEmpty()
        val answerMap = buildJsonObject {
            fields.forEachIndexed { idx, fEl ->
                val f = fEl as? JsonObject ?: return@forEachIndexed
                val key = f.str("key") ?: return@forEachIndexed
                val type = f.str("type") ?: "string"
                val given = answers.getOrNull(idx).orEmpty()
                when (type) {
                    "multiselect" -> putJsonArray(key) { given.forEach { add(JsonPrimitive(it)) } }
                    "boolean" -> put(key, given.firstOrNull() == "true")
                    "number", "integer" -> put(key, given.firstOrNull()?.toDoubleOrNull() ?: 0)
                    else -> put(key, given.firstOrNull() ?: "")
                }
            }
        }
        call("POST", "/api/session/$sid/form/$requestId/reply", loc(directory), buildJsonObject {
            put("answer", answerMap)
        })
    }

    suspend fun rejectQuestion(requestId: String, directory: String?, sessionId: String? = null) {
        val sid = sessionId ?: formCache[requestId]?.str("sessionID") ?: return
        call("DELETE", "/api/session/$sid/form/$requestId", loc(directory))
    }

    // ---------- providers and models ----------

    suspend fun connectedProviders(): ProvidersResponse {
        val pRes = call("GET", "/api/provider")
        val pArr = (pRes as? JsonObject)?.arr("data") ?: pRes as? JsonArray ?: JsonArray(emptyList())

        val mRes = call("GET", "/api/model")
        val mArr = (mRes as? JsonObject)?.arr("data") ?: mRes as? JsonArray ?: JsonArray(emptyList())

        val modelsByProvider = HashMap<String, MutableMap<String, ModelInfo>>()
        for (mEl in mArr) {
            val mObj = mEl as? JsonObject ?: continue
            val info = parseModelInfo(mObj)
            modelsByProvider.getOrPut(info.providerID) { HashMap() }[info.id] = info
        }

        val providers = pArr.mapNotNull { pEl ->
            val pObj = pEl as? JsonObject ?: return@mapNotNull null
            val id = pObj.str("id") ?: return@mapNotNull null
            val name = pObj.str("name") ?: id
            val models = modelsByProvider[id].orEmpty()
            ProviderInfo(id = id, name = name, models = models)
        }

        return ProvidersResponse(providers = providers)
    }

    suspend fun allProviders(): AllProvidersResponse {
        val iRes = call("GET", "/api/integration")
        val iArr = (iRes as? JsonObject)?.arr("data") ?: iRes as? JsonArray ?: JsonArray(emptyList())
        val all = iArr.mapNotNull { iEl ->
            val iObj = iEl as? JsonObject ?: return@mapNotNull null
            val id = iObj.str("id") ?: return@mapNotNull null
            AllProvider(id = id, name = iObj.str("name") ?: id)
        }
        val pRes = call("GET", "/api/provider")
        val pArr = (pRes as? JsonObject)?.arr("data") ?: pRes as? JsonArray ?: JsonArray(emptyList())
        val connected = pArr.mapNotNull { (it as? JsonObject)?.str("id") }
        return AllProvidersResponse(all = all, connected = connected)
    }

    suspend fun providerAuthMethods(): Map<String, List<AuthMethod>> {
        val res = call("GET", "/api/integration")
        val arr = (res as? JsonObject)?.arr("data") ?: res as? JsonArray ?: JsonArray(emptyList())
        val out = HashMap<String, List<AuthMethod>>()
        for (iEl in arr) {
            val iObj = iEl as? JsonObject ?: continue
            val id = iObj.str("id") ?: continue
            val methods = iObj.arr("methods")?.mapNotNull { mEl ->
                val mObj = mEl as? JsonObject ?: return@mapNotNull null
                AuthMethod(
                    type = mObj.str("type") ?: "key",
                    label = mObj.str("label") ?: mObj.str("type") ?: "API key",
                )
            }.orEmpty()
            out[id] = methods
        }
        return out
    }

    suspend fun setApiKey(providerId: String, key: String) {
        call("POST", "/api/integration/$providerId/connect/key", body = buildJsonObject {
            put("key", key)
        })
    }

    suspend fun removeAuth(providerId: String) {
        val res = call("GET", "/api/credential")
        val arr = (res as? JsonObject)?.arr("data") ?: res as? JsonArray ?: JsonArray(emptyList())
        for (cEl in arr) {
            val cObj = cEl as? JsonObject ?: continue
            if (cObj.str("integrationID") == providerId) {
                cObj.str("id")?.let { cid -> call("DELETE", "/api/credential/$cid") }
            }
        }
    }

    suspend fun oauthAuthorize(providerId: String, method: Int, inputs: Map<String, String> = emptyMap()): JsonObject {
        val iRes = call("GET", "/api/integration")
        val iArr = (iRes as? JsonObject)?.arr("data") ?: iRes as? JsonArray ?: JsonArray(emptyList())
        val intObj = iArr.firstOrNull { (it as? JsonObject)?.str("id") == providerId } as? JsonObject
        val methods = intObj?.arr("methods").orEmpty()
        val methodObj = methods.getOrNull(method) as? JsonObject
        val methodId = methodObj?.str("id") ?: "oauth"

        val body = buildJsonObject {
            put("methodID", methodId)
            if (inputs.isNotEmpty()) {
                putJsonObject("answer") { inputs.forEach { (k, v) -> put(k, v) } }
            }
        }
        val res = call("POST", "/api/integration/$providerId/connect/oauth", body = body)
        val data = (res as? JsonObject)?.obj("data") ?: res as? JsonObject ?: JsonObject(emptyMap())
        data.str("attemptID")?.let { oauthAttempts["$providerId:$method"] = it }
        return data
    }

    suspend fun oauthCallback(providerId: String, method: Int, code: String?) {
        val attemptId = oauthAttempts["$providerId:$method"] ?: return
        if (code != null) {
            call("POST", "/api/integration/$providerId/connect/oauth/$attemptId/complete", body = buildJsonObject {
                put("code", code)
            })
        } else {
            val maxWait = System.currentTimeMillis() + 600_000L
            while (System.currentTimeMillis() < maxWait) {
                delay(1500)
                val res = call("GET", "/api/integration/$providerId/connect/oauth/$attemptId")
                val status = (res as? JsonObject)?.obj("data")?.str("status")
                if (status == "complete") return
                if (status == "failed" || status == "expired") error("OAuth failed: $status")
            }
            error("OAuth timed out")
        }
    }

    suspend fun disposeAll() {
        call("POST", "/api/location/reload")
    }

    // ---------- config, agents, MCP ----------

    private suspend fun configDocument(): Pair<String, JsonObject> {
        val res = call("GET", "/api/config") as? JsonArray ?: JsonArray(emptyList())
        val entries = res.mapNotNull { it as? JsonObject }
        val doc = entries.firstOrNull { it.str("type") == "document" }
        val info = doc?.obj("info") ?: JsonObject(emptyMap())
        return configFilePath(entries) to info
    }

    suspend fun config(): JsonObject {
        val (_, info) = configDocument()
        return info
    }

    private fun deepMerge(target: JsonObject, patch: JsonObject): JsonObject {
        val map = target.toMutableMap()
        for ((k, v) in patch) {
            val existing = map[k]
            if (existing is JsonObject && v is JsonObject) {
                map[k] = deepMerge(existing, v)
            } else {
                map[k] = v
            }
        }
        return JsonObject(map)
    }

    private fun normalizePatch(patch: JsonObject): JsonObject {
        val out = patch.toMutableMap()
        patch.str("small_model")?.let { sm ->
            val agentsObj = (out["agents"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            val titleObj = (agentsObj["title"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            titleObj["model"] = JsonPrimitive(sm)
            agentsObj["title"] = JsonObject(titleObj)
            out["agents"] = JsonObject(agentsObj)
        }
        patch.obj("mcp")?.let { mcpObj ->
            if (!mcpObj.containsKey("servers")) {
                val servers = mutableMapOf<String, JsonElement>()
                for ((sName, sCfg) in mcpObj) {
                    val sObj = sCfg as? JsonObject ?: continue
                    val sanitized = sObj.toMutableMap()
                    if (sanitized["enabled"]?.let { (it as? JsonPrimitive)?.booleanOrNull } == false) {
                        sanitized["disabled"] = JsonPrimitive(true)
                    }
                    sanitized.remove("enabled")
                    servers[sName] = JsonObject(sanitized)
                }
                out["mcp"] = buildJsonObject { put("servers", JsonObject(servers)) }
            }
        }
        return JsonObject(out)
    }

    suspend fun patchConfig(patch: JsonObject): JsonObject = configMutex.withLock {
        val (docPath, currentInfo) = configDocument()
        val normalized = normalizePatch(patch)
        val merged = deepMerge(currentInfo, normalized)
        val bytes = merged.toString().toByteArray(Charsets.UTF_8)
        callRaw("/api/experimental/fs/write", mapOf("path" to docPath), bytes)
        runCatching { call("POST", "/api/location/reload") }
        config()
    }

    suspend fun patchGlobalConfig(patch: JsonObject): JsonObject = patchConfig(patch)

    suspend fun agents(): List<AgentInfo> {
        val res = call("GET", "/api/agent")
        val arr = (res as? JsonObject)?.arr("data") ?: res as? JsonArray ?: JsonArray(emptyList())
        return arr.mapNotNull { aEl ->
            val a = aEl as? JsonObject ?: return@mapNotNull null
            AgentInfo(
                name = a.str("name") ?: a.str("id") ?: "",
                description = a.str("description"),
                mode = a.str("mode") ?: "primary",
                hidden = a.bool("hidden") ?: false,
            )
        }
    }

    suspend fun commands(): List<NamedItem> {
        val res = call("GET", "/api/command")
        val arr = (res as? JsonObject)?.arr("data") ?: res as? JsonArray ?: JsonArray(emptyList())
        return arr.mapNotNull { cEl ->
            val c = cEl as? JsonObject ?: return@mapNotNull null
            NamedItem(
                name = c.str("name") ?: "",
                description = c.str("description"),
                source = "command",
            )
        }
    }

    suspend fun skills(): List<NamedItem> {
        val res = call("GET", "/api/skill")
        val arr = (res as? JsonObject)?.arr("data") ?: res as? JsonArray ?: JsonArray(emptyList())
        return arr.mapNotNull { sEl ->
            val s = sEl as? JsonObject ?: return@mapNotNull null
            NamedItem(
                name = s.str("name") ?: s.str("id") ?: "",
                description = s.str("description"),
                source = "skill",
                location = s.str("path"),
                content = s.str("content"),
            )
        }
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun tools(providerId: String, modelId: String, directory: String?): JsonArray = JsonArray(emptyList())

    suspend fun mcpStatus(): JsonObject {
        val res = call("GET", "/api/mcp")
        val arr = (res as? JsonObject)?.arr("data") ?: res as? JsonArray ?: JsonArray(emptyList())
        return buildJsonObject {
            arr.forEach { sEl ->
                val s = sEl as? JsonObject ?: return@forEach
                val name = s.str("name") ?: return@forEach
                put(name, s.obj("status") ?: buildJsonObject { put("status", "unknown") })
            }
        }
    }

    suspend fun mcpConnect(name: String) {
        call("POST", "/api/experimental/mcp/$name/connect")
    }

    suspend fun mcpDisconnect(name: String) {
        call("POST", "/api/experimental/mcp/$name/disconnect")
    }

    suspend fun mcpAdd(name: String, config: JsonObject) {
        val sanitized = config.toMutableMap()
        if (sanitized["enabled"]?.let { (it as? JsonPrimitive)?.booleanOrNull } == false) {
            sanitized["disabled"] = JsonPrimitive(true)
        }
        sanitized.remove("enabled")
        call("PUT", "/api/experimental/mcp/$name", body = buildJsonObject {
            put("config", JsonObject(sanitized))
        })
    }

    suspend fun serverDirectory(): String? {
        val res = call("GET", "/api/location") as? JsonObject
        return res?.str("directory")
    }

    suspend fun projects(): JsonArray {
        val res = call("GET", "/api/project")
        return res as? JsonArray ?: (res as? JsonObject)?.arr("data") ?: JsonArray(emptyList())
    }

    private fun JsonArray?.orEmpty() = this ?: JsonArray(emptyList())
}

/**
 * The file a settings change has to write. `/api/config` lists the config sources; a fresh install
 * has only the directory and no document yet. Writing to the directory itself fails with HTTP 500,
 * so the document has to be created inside it.
 */
internal fun configFilePath(entries: List<JsonObject>): String {
    entries.firstOrNull { it.str("type") == "document" }?.str("path")?.takeIf { it.isNotBlank() }?.let { return it }
    val dir = entries.firstOrNull { it.str("type") == "directory" }?.str("path")?.takeIf { it.isNotBlank() }
        ?: "/root/.config/opencode"
    return java.io.File(dir, "opencode.json").path
}
