package com.celoext.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

val OcJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = true
    encodeDefaults = false
}

@Serializable
data class CacheTokens(val read: Long = 0, val write: Long = 0)

@Serializable
data class Tokens(
    val input: Long = 0,
    val output: Long = 0,
    val reasoning: Long = 0,
    val total: Long? = null,
    val cache: CacheTokens = CacheTokens(),
) {
    val sum get() = total ?: (input + output + reasoning + cache.read + cache.write)
}

@Serializable
data class TimeInfo(
    val created: Long = 0,
    val updated: Long = 0,
    val completed: Long? = null,
    val archived: Long? = null,
)

@Serializable
data class ModelRef(
    val id: String? = null,
    val modelID: String? = null,
    val providerID: String = "",
    val variant: String? = null,
) {
    val model get() = modelID ?: id ?: ""
}

@Serializable
data class Session(
    val id: String,
    val title: String = "",
    val directory: String = "",
    val projectID: String? = null,
    val parentID: String? = null,
    val cost: Double? = null,
    val tokens: Tokens? = null,
    val time: TimeInfo = TimeInfo(),
    val agent: String? = null,
    val model: ModelRef? = null,
    val summary: JsonObject? = null,
) {
    val displayTitle get() =
        if (isDefaultTitle) (if (directory == com.celoext.data.CHAT_DIR) "New chat" else "New session") else title
    val isDefaultTitle get() = title.isBlank() || Regex("^(New session|Child session) - \\d{4}-").containsMatchIn(title)
}

@Serializable
data class MessageInfo(
    val id: String,
    val sessionID: String = "",
    val role: String = "",
    val time: TimeInfo = TimeInfo(),
    val parentID: String? = null,
    val modelID: String? = null,
    val providerID: String? = null,
    val model: ModelRef? = null,
    val agent: String? = null,
    val mode: String? = null,
    val cost: Double? = null,
    val tokens: Tokens? = null,
    val finish: String? = null,
    val error: JsonObject? = null,
) {
    val isUser get() = role == "user"
    val modelName get() = modelID ?: model?.model
    val providerName get() = providerID ?: model?.providerID
    val errorMessage: String?
        get() {
            val e = error ?: return null
            val data = e["data"] as? JsonObject
            return data?.str("message") ?: e.str("message") ?: e.str("name")
        }
    val errorName: String? get() = error?.str("name")
}

/** A message part is stored as is: OpenCode keeps adding part types, a strict model would break. */
data class Part(val raw: JsonObject) {
    val id get() = raw.str("id") ?: ""
    val messageID get() = raw.str("messageID") ?: ""
    val type get() = raw.str("type") ?: ""
    val text get() = raw.str("text") ?: ""
    fun withText(t: String) = Part(JsonObject(raw + ("text" to JsonPrimitive(t))))
}

data class MessageWithParts(val info: MessageInfo, val parts: List<Part>)

@Serializable
data class ProviderInfo(
    val id: String,
    val name: String = "",
    val env: List<String> = emptyList(),
    val models: Map<String, ModelInfo> = emptyMap(),
)

@Serializable
data class ModelInfo(
    val id: String,
    val name: String = "",
    val providerID: String = "",
    val family: String? = null,
    val status: String? = null,
    val capabilities: Caps? = null,
    val limit: Limit? = null,
    val cost: JsonObject? = null,
    val variants: Map<String, JsonObject>? = null,
    val release_date: String? = null,
) {
    val supportsImages get() = capabilities?.input?.image == true
    val reasoning get() = capabilities?.reasoning == true
    val variantNames get() = variants?.keys?.toList().orEmpty()
}

@Serializable
data class Caps(
    val reasoning: Boolean = false,
    val attachment: Boolean = false,
    val toolcall: Boolean = false,
    val input: Modalities? = null,
)

@Serializable
data class Modalities(val text: Boolean = false, val image: Boolean = false, val pdf: Boolean = false, val audio: Boolean = false, val video: Boolean = false)

@Serializable
data class Limit(val context: Long = 0, val output: Long = 0)

@Serializable
data class ProvidersResponse(val providers: List<ProviderInfo> = emptyList(), val default: Map<String, String> = emptyMap())

@Serializable
data class AllProvider(val id: String, val name: String = "", val env: List<String> = emptyList())

@Serializable
data class AllProvidersResponse(val all: List<AllProvider> = emptyList(), val connected: List<String> = emptyList())

@Serializable
data class AuthMethod(val type: String = "key", val label: String = "")

@Serializable
data class AgentInfo(
    val name: String,
    val description: String? = null,
    val mode: String = "primary",
    val native: Boolean = false,
    val hidden: Boolean = false,
)

@Serializable
data class NamedItem(
    val name: String,
    val description: String? = null,
    /** command | skill | mcp: where the command came from. */
    val source: String? = null,
    val template: String? = null,
    val agent: String? = null,
    /** For skills: the path to SKILL.md (or "<built-in>") and its text. */
    val location: String? = null,
    val content: String? = null,
)

data class PermissionReq(
    val id: String,
    val sessionID: String,
    val permission: String,
    val patterns: List<String>,
    val metadata: JsonObject,
    val callID: String?,
)

data class QuestionOption(val label: String, val description: String)
data class QuestionItem(val question: String, val header: String, val options: List<QuestionOption>, val multiple: Boolean, val custom: Boolean)
data class QuestionReq(val id: String, val sessionID: String, val questions: List<QuestionItem>)

// ---------- JsonObject helpers ----------

fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
fun JsonObject.arr(k: String): JsonArray? = this[k] as? JsonArray
fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull
fun JsonObject.dbl(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull

fun parseSession(o: JsonObject): Session {
    val loc = o.obj("location")
    val dir = loc?.str("directory") ?: o.str("directory") ?: ""
    val timeObj = o.obj("time")
    val time = if (timeObj != null) {
        TimeInfo(
            created = timeObj.long("created") ?: 0L,
            updated = timeObj.long("updated") ?: 0L,
            completed = timeObj.long("completed"),
            archived = timeObj.long("archived"),
        )
    } else TimeInfo()

    val modelObj = o.obj("model")
    val modelRef = if (modelObj != null) {
        ModelRef(
            id = modelObj.str("id"),
            modelID = modelObj.str("model") ?: modelObj.str("modelID") ?: modelObj.str("id"),
            providerID = modelObj.str("providerID") ?: "",
            variant = modelObj.str("variant"),
        )
    } else null

    val tokensObj = o.obj("tokens")
    val tokens = if (tokensObj != null) {
        val cacheObj = tokensObj.obj("cache")
        Tokens(
            input = tokensObj.long("input") ?: 0L,
            output = tokensObj.long("output") ?: 0L,
            reasoning = tokensObj.long("reasoning") ?: 0L,
            total = tokensObj.long("total"),
            cache = CacheTokens(
                read = cacheObj?.long("read") ?: 0L,
                write = cacheObj?.long("write") ?: 0L,
            ),
        )
    } else null

    return Session(
        id = o.str("id") ?: "",
        title = o.str("title") ?: "",
        directory = dir,
        projectID = o.str("projectID"),
        parentID = o.str("parentID"),
        cost = o.dbl("cost"),
        tokens = tokens,
        time = time,
        agent = o.str("agent"),
        model = modelRef,
        summary = o.obj("summary"),
    )
}

/**
 * Prices used to be a flat object. OpenCode 2.2.5 sends an array of tiers with the cache prices
 * nested under "cache". The cost hint under the composer expects the flat shape.
 */
private fun normalizeCost(el: JsonElement?): JsonObject? {
    val base = when (el) {
        is JsonArray -> el.firstOrNull() as? JsonObject
        is JsonObject -> el
        else -> null
    } ?: return null
    val out = base.toMutableMap()
    base.obj("cache")?.let { cache ->
        if ("cache_read" !in out) cache.dbl("read")?.let { out["cache_read"] = JsonPrimitive(it) }
        if ("cache_write" !in out) cache.dbl("write")?.let { out["cache_write"] = JsonPrimitive(it) }
    }
    return JsonObject(out)
}

fun parseModelInfo(o: JsonObject): ModelInfo {
    val id = o.str("id") ?: ""
    val name = o.str("name") ?: id
    val providerID = o.str("providerID") ?: ""
    val family = o.str("family")
    val status = o.str("status")

    val limitObj = o.obj("limit")
    val limit = if (limitObj != null) {
        Limit(
            context = limitObj.long("context") ?: 0L,
            output = limitObj.long("output") ?: 0L,
        )
    } else null

    val capsObj = o.obj("capabilities")
    val caps = if (capsObj != null) {
        val inputEl = capsObj["input"]
        val modalities = when (inputEl) {
            is JsonArray -> {
                val set = inputEl.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
                Modalities(
                    text = "text" in set,
                    image = "image" in set,
                    pdf = "pdf" in set,
                    audio = "audio" in set,
                    video = "video" in set,
                )
            }
            is JsonObject -> {
                Modalities(
                    text = inputEl.bool("text") ?: false,
                    image = inputEl.bool("image") ?: false,
                    pdf = inputEl.bool("pdf") ?: false,
                    audio = inputEl.bool("audio") ?: false,
                    video = inputEl.bool("video") ?: false,
                )
            }
            else -> Modalities()
        }
        val isReasoning = capsObj.bool("reasoning") ?: (capsObj.obj("reasoning") != null)
        Caps(
            reasoning = isReasoning,
            attachment = modalities.image || modalities.pdf,
            toolcall = capsObj.bool("tools") ?: capsObj.bool("toolcall") ?: false,
            input = modalities,
        )
    } else null

    val variantsObj = when (val vEl = o["variants"]) {
        is JsonArray -> vEl.mapNotNull { it as? JsonObject }.associateBy { it.str("id") ?: "" }
        is JsonObject -> vEl.entries.associate { (k, v) -> k to (v as? JsonObject ?: JsonObject(emptyMap())) }
        else -> null
    }

    val releaseDate = o.obj("time")?.long("released")?.toString() ?: o.str("release_date")

    return ModelInfo(
        id = id,
        name = name,
        providerID = providerID,
        family = family,
        status = status,
        capabilities = caps,
        limit = limit,
        cost = normalizeCost(o["cost"]),
        variants = variantsObj,
        release_date = releaseDate,
    )
}

fun parsePermission(o: JsonObject): PermissionReq {
    val rawAction = o.str("action") ?: o.str("permission") ?: ""
    val mappedAction = when (rawAction) {
        "shell" -> "bash"
        "subagent" -> "task"
        "write", "patch", "apply_patch" -> "edit"
        else -> rawAction
    }
    val patterns = (o.arr("resources") ?: o.arr("patterns"))?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    val callID = o.obj("source")?.str("id") ?: o.obj("tool")?.str("callID")
    return PermissionReq(
        id = o.str("id") ?: "",
        sessionID = o.str("sessionID") ?: "",
        permission = mappedAction,
        patterns = patterns,
        metadata = o.obj("metadata") ?: JsonObject(emptyMap()),
        callID = callID,
    )
}

fun parseQuestion(o: JsonObject): QuestionReq {
    val id = o.str("id") ?: ""
    val sessionID = o.str("sessionID") ?: ""
    val fieldsArr = o.arr("fields")
    if (fieldsArr != null) {
        val items = fieldsArr.mapNotNull { fEl ->
            val f = fEl as? JsonObject ?: return@mapNotNull null
            val key = f.str("key") ?: return@mapNotNull null
            val fTitle = f.str("title") ?: key
            val fDesc = f.str("description") ?: fTitle
            val fType = f.str("type") ?: "string"
            val options = f.arr("options")?.mapNotNull { optEl ->
                val oo = optEl as? JsonObject ?: return@mapNotNull null
                QuestionOption(oo.str("label") ?: oo.str("value") ?: "", oo.str("description") ?: "")
            }.orEmpty()
            QuestionItem(
                question = fDesc,
                header = fTitle,
                options = options,
                multiple = fType == "multiselect",
                custom = f.bool("custom") ?: (options.isEmpty() || fType == "string"),
            )
        }
        return QuestionReq(id = id, sessionID = sessionID, questions = items)
    }

    val questions = o.arr("questions")?.map { q ->
        val qo = q.jsonObject
        QuestionItem(
            question = qo.str("question") ?: "",
            header = qo.str("header") ?: "",
            options = qo.arr("options")?.map { opt ->
                val oo = opt.jsonObject
                QuestionOption(oo.str("label") ?: "", oo.str("description") ?: "")
            }.orEmpty(),
            multiple = qo.bool("multiple") ?: false,
            custom = qo.bool("custom") ?: true,
        )
    }.orEmpty()
    return QuestionReq(id = id, sessionID = sessionID, questions = questions)
}

/**
 * The message endpoint returns the latest page newest first. Every caller in the app expects
 * oldest first: the chat draws top to bottom, and interrupted-task detection reads the last
 * item as the newest one.
 */
internal fun inChatOrder(list: List<MessageWithParts>): List<MessageWithParts> =
    list.sortedWith(compareBy({ it.info.time.created }, { it.info.id }))

fun parseMessages(el: JsonElement, defaultSessionID: String = ""): List<MessageWithParts> {
    val arr = when (el) {
        is JsonArray -> el
        is JsonObject -> el.arr("data") ?: JsonArray(emptyList())
        else -> JsonArray(emptyList())
    }
    if (arr.isEmpty()) return emptyList()

    val first = arr.firstOrNull() as? JsonObject
    if (first != null && first.containsKey("info") && first.containsKey("parts")) {
        return arr.mapNotNull { m ->
            val o = m as? JsonObject ?: return@mapNotNull null
            val info = o.obj("info")?.let { runCatching { OcJson.decodeFromJsonElement(MessageInfo.serializer(), it) }.getOrNull() }
                ?: return@mapNotNull null
            MessageWithParts(info, o.arr("parts").orEmpty().mapNotNull { (it as? JsonObject)?.let(::Part) })
        }
    }

    val out = ArrayList<MessageWithParts>()
    var lastUserID: String? = null

    for (item in arr) {
        val o = item as? JsonObject ?: continue
        val type = o.str("type") ?: continue
        val id = o.str("id") ?: continue
        val sessionID = o.str("sessionID") ?: defaultSessionID
        val timeObj = o.obj("time")
        val created = timeObj?.long("created") ?: 0L
        val completed = timeObj?.long("completed") ?: timeObj?.long("streamed")

        when (type) {
            "user" -> {
                lastUserID = id
                val info = MessageInfo(
                    id = id,
                    sessionID = sessionID,
                    role = "user",
                    time = TimeInfo(created = created, completed = completed),
                )
                val parts = ArrayList<Part>()
                val text = o.str("text")
                if (!text.isNullOrEmpty()) {
                    parts.add(Part(buildJsonObject {
                        put("id", "$id-text")
                        put("messageID", id)
                        put("sessionID", sessionID)
                        put("type", "text")
                        put("text", text)
                    }))
                }
                o.arr("files")?.forEachIndexed { idx, fEl ->
                    val f = fEl as? JsonObject ?: return@forEachIndexed
                    val mime = f.str("mime") ?: "application/octet-stream"
                    val name = f.str("name") ?: "file-$idx"
                    val b64 = f.str("data")
                    val uri = f.obj("source")?.str("uri") ?: f.str("uri")
                    val url = if (b64 != null) "data:$mime;base64,$b64" else uri ?: ""
                    parts.add(Part(buildJsonObject {
                        put("id", "$id-file-$idx")
                        put("messageID", id)
                        put("sessionID", sessionID)
                        put("type", "file")
                        put("mime", mime)
                        put("filename", name)
                        put("url", url)
                    }))
                }
                out.add(MessageWithParts(info, parts))
            }
            "assistant" -> {
                val modelObj = o.obj("model")
                val modelRef = if (modelObj != null) {
                    ModelRef(
                        id = modelObj.str("id"),
                        modelID = modelObj.str("model") ?: modelObj.str("id"),
                        providerID = modelObj.str("providerID") ?: "",
                        variant = modelObj.str("variant"),
                    )
                } else null

                val tokensObj = o.obj("tokens")
                val tokens = if (tokensObj != null) {
                    val cacheObj = tokensObj.obj("cache")
                    Tokens(
                        input = tokensObj.long("input") ?: 0L,
                        output = tokensObj.long("output") ?: 0L,
                        reasoning = tokensObj.long("reasoning") ?: 0L,
                        total = tokensObj.long("total"),
                        cache = CacheTokens(
                            read = cacheObj?.long("read") ?: 0L,
                            write = cacheObj?.long("write") ?: 0L,
                        ),
                    )
                } else null

                val info = MessageInfo(
                    id = id,
                    sessionID = sessionID,
                    role = "assistant",
                    parentID = lastUserID,
                    modelID = modelRef?.model,
                    providerID = modelRef?.providerID,
                    model = modelRef,
                    agent = o.str("agent"),
                    cost = o.dbl("cost"),
                    tokens = tokens,
                    finish = o.str("finish"),
                    error = o.obj("error"),
                    time = TimeInfo(created = created, completed = completed),
                )
                val parts = ArrayList<Part>()
                o.arr("content")?.forEachIndexed { idx, cEl ->
                    val c = cEl as? JsonObject ?: return@forEachIndexed
                    val cType = c.str("type") ?: ""
                    when (cType) {
                        "text" -> {
                            parts.add(Part(buildJsonObject {
                                put("id", "$id-text-$idx")
                                put("messageID", id)
                                put("sessionID", sessionID)
                                put("type", "text")
                                put("text", c.str("text") ?: "")
                            }))
                        }
                        "reasoning" -> {
                            parts.add(Part(buildJsonObject {
                                put("id", "$id-reasoning-$idx")
                                put("messageID", id)
                                put("sessionID", sessionID)
                                put("type", "reasoning")
                                put("text", c.str("text") ?: "")
                            }))
                        }
                        "tool" -> {
                            val toolId = c.str("id") ?: "$id-tool-$idx"
                            val rawToolName = c.str("name") ?: "tool"
                            val mappedToolName = when (rawToolName) {
                                "shell" -> "bash"
                                "subagent" -> "task"
                                else -> rawToolName
                            }
                            val stateObj = c.obj("state")
                            val rawStatus = stateObj?.str("status") ?: "completed"
                            val mappedStatus = when (rawStatus) {
                                "streaming" -> "running"
                                else -> rawStatus
                            }
                            val input = stateObj?.get("input") ?: JsonObject(emptyMap())
                            val outputText = stateObj?.arr("content")?.joinToString("\n") {
                                (it as? JsonObject)?.str("text") ?: ""
                            } ?: stateObj?.str("text") ?: ""
                            val metadata = stateObj?.obj("metadata")
                            val stateTime = c.obj("time")

                            val mappedState = buildJsonObject {
                                put("status", mappedStatus)
                                put("input", input)
                                if (outputText.isNotEmpty()) put("output", outputText)
                                metadata?.let { put("metadata", it) }
                                stateObj?.obj("error")?.let { put("error", it) }
                                putJsonObject("time") {
                                    stateTime?.long("ran")?.let { put("start", it) }
                                    stateTime?.long("completed")?.let { put("end", it) }
                                }
                            }

                            parts.add(Part(buildJsonObject {
                                put("id", toolId)
                                put("messageID", id)
                                put("sessionID", sessionID)
                                put("type", "tool")
                                put("tool", mappedToolName)
                                put("state", mappedState)
                            }))
                        }
                    }
                }
                out.add(MessageWithParts(info, parts))
            }
            "shell" -> {
                val command = o.str("command") ?: ""
                val output = o.str("output") ?: ""
                val status = if (o.str("status") == "failed") "error" else "completed"
                val info = MessageInfo(
                    id = id,
                    sessionID = sessionID,
                    role = "assistant",
                    parentID = lastUserID,
                    time = TimeInfo(created = created, completed = completed),
                )
                val part = Part(buildJsonObject {
                    put("id", "$id-shell")
                    put("messageID", id)
                    put("sessionID", sessionID)
                    put("type", "tool")
                    put("tool", "bash")
                    putJsonObject("state") {
                        put("status", status)
                        putJsonObject("input") { put("command", command) }
                        put("output", output)
                        putJsonObject("time") {
                            put("start", created)
                            completed?.let { put("end", it) }
                        }
                    }
                })
                out.add(MessageWithParts(info, listOf(part)))
            }
        }
    }
    return out
}

private fun JsonArray?.orEmpty() = this ?: JsonArray(emptyList())
