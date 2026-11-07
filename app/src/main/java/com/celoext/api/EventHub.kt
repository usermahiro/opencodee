package com.celoext.api

import com.celoext.Graph
import com.celoext.runtime.RuntimeController
import com.celoext.runtime.RuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap

/** One global SSE connection to `/api/event`: translates v2 events into v1-shaped events for the app. */
class EventHub(private val api: OpenCodeApi, private val runtime: RuntimeController) {
    data class Event(val directory: String?, val type: String, val props: JsonObject)

    val events = MutableSharedFlow<Event>(extraBufferCapacity = 1024)
    val connected = MutableStateFlow(false)
    /** Grows on every (re)connect: subscribers must reload their state. */
    val generation = MutableStateFlow(0)

    private val toolNames = ConcurrentHashMap<String, String>()

    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(runtime.state, Graph.devices.activeId) { s, d ->
                if (d.isNotEmpty()) "remote:$d" else if (s is RuntimeState.Running) "local" else ""
            }
                .distinctUntilChanged()
                .collectLatest { target ->
                    if (target.isNotEmpty()) connectLoop() else connected.value = false
                }
        }
    }

    private suspend fun connectLoop() {
        var backoff = 500L
        while (currentCoroutineContext().isActive) {
            try {
                withContext(Dispatchers.IO) { stream { backoff = 500L } }
            } catch (_: Exception) { }
            connected.value = false
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(8000)
        }
    }

    private suspend fun stream(onOpen: () -> Unit) {
        val call = api.streamHttp.newCall(api.request("/api/event").header("Accept", "text/event-stream").build())
        val handle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { r ->
                if (!r.isSuccessful) return
                onOpen()
                connected.value = true
                generation.value++
                val src = r.body.source()
                val data = StringBuilder()
                while (true) {
                    val line = src.readUtf8Line() ?: break
                    if (line.isEmpty()) {
                        if (data.isNotEmpty()) { dispatch(data.toString()); data.setLength(0) }
                    } else if (line.startsWith("data:")) {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.removePrefix("data:").removePrefix(" "))
                    }
                }
            }
        } finally { handle.dispose() }
    }

    private fun dispatch(json: String) {
        val o = runCatching { OcJson.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return
        val rawType = o.str("type") ?: o.obj("payload")?.str("type") ?: return
        val dir = o.obj("location")?.str("directory") ?: o.str("directory")
        val data = o.obj("data") ?: o.obj("payload")?.obj("properties") ?: o

        // If it's already v1-shaped with payload.properties, pass through
        if (o.containsKey("payload")) {
            events.tryEmit(Event(dir, rawType, data))
            return
        }

        // Translate v2 event to v1 event(s)
        when (rawType) {
            "session.step.started" -> {
                val sid = data.str("sessionID") ?: return
                val mid = data.str("assistantMessageID") ?: return
                val started = data.long("started") ?: System.currentTimeMillis()
                events.tryEmit(Event(dir, "message.updated", buildJsonObject {
                    putJsonObject("info") {
                        put("id", mid)
                        put("sessionID", sid)
                        put("role", "assistant")
                        data.str("agent")?.let { put("agent", it) }
                        data.obj("model")?.let { put("model", it) }
                        putJsonObject("time") { put("created", started) }
                    }
                }))
            }
            "session.text.started" -> {
                val sid = data.str("sessionID") ?: return
                val mid = data.str("assistantMessageID") ?: return
                val ord = data.long("ordinal") ?: 0L
                val pid = "$mid-text-$ord"
                events.tryEmit(Event(dir, "message.part.updated", buildJsonObject {
                    putJsonObject("part") {
                        put("id", pid)
                        put("messageID", mid)
                        put("sessionID", sid)
                        put("type", "text")
                        put("text", "")
                    }
                }))
            }
            "session.text.delta" -> {
                val sid = data.str("sessionID") ?: return
                val mid = data.str("assistantMessageID") ?: return
                val ord = data.long("ordinal") ?: 0L
                val pid = "$mid-text-$ord"
                val delta = data.str("delta") ?: ""
                events.tryEmit(Event(dir, "message.part.delta", buildJsonObject {
                    put("sessionID", sid)
                    put("messageID", mid)
                    put("partID", pid)
                    put("field", "text")
                    put("delta", delta)
                }))
            }
            "session.text.ended" -> {
                val sid = data.str("sessionID") ?: return
                val mid = data.str("assistantMessageID") ?: return
                val ord = data.long("ordinal") ?: 0L
                val pid = "$mid-text-$ord"
                val text = data.str("text") ?: ""
                events.tryEmit(Event(dir, "message.part.updated", buildJsonObject {
                    putJsonObject("part") {
                        put("id", pid)
                        put("messageID", mid)
                        put("sessionID", sid)
                        put("type", "text")
                        put("text", text)
                    }
                }))
            }
            "session.reasoning.started" -> {
                val sid = data.str("sessionID") ?: return
                val mid = data.str("assistantMessageID") ?: return
                val ord = data.long("ordinal") ?: 0L
                val pid = "$mid-reasoning-$ord"
                events.tryEmit(Event(dir, "message.part.updated", buildJsonObject {
                    putJsonObject("part") {
                        put("id", pid)
                        put("messageID", mid)
                        put("sessionID", sid)
                        put("type", "reasoning")
                        put("text", "")
                    }
                }))
            }
            "session.reasoning.delta" -> {
                val sid = data.str("sessionID") ?: return
                val mid = data.str("assistantMessageID") ?: return
                val ord = data.long("ordinal") ?: 0L
                val pid = "$mid-reasoning-$ord"
                val delta = data.str("delta") ?: ""
                events.tryEmit(Event(dir, "message.part.delta", buildJsonObject {
                    put("sessionID", sid)
                    put("messageID", mid)
                    put("partID", pid)
                    put("field", "text")
                    put("delta", delta)
                }))
            }
            "session.reasoning.ended" -> {
                val sid = data.str("sessionID") ?: return
                val mid = data.str("assistantMessageID") ?: return
                val ord = data.long("ordinal") ?: 0L
                val pid = "$mid-reasoning-$ord"
                val text = data.str("text") ?: ""
                events.tryEmit(Event(dir, "message.part.updated", buildJsonObject {
                    putJsonObject("part") {
                        put("id", pid)
                        put("messageID", mid)
                        put("sessionID", sid)
                        put("type", "reasoning")
                        put("text", text)
                    }
                }))
            }
            "session.tool.input.started" -> {
                val sid = data.str("sessionID") ?: return
                val mid = data.str("assistantMessageID") ?: return
                val tid = data.str("id") ?: return
                val rawName = data.str("name") ?: "tool"
                val mappedName = when (rawName) {
                    "shell" -> "bash"
                    "subagent" -> "task"
                    else -> rawName
                }
                toolNames["$sid:$tid"] = mappedName
                events.tryEmit(Event(dir, "message.part.updated", buildJsonObject {
                    putJsonObject("part") {
                        put("id", tid)
                        put("messageID", mid)
                        put("sessionID", sid)
                        put("type", "tool")
                        put("tool", mappedName)
                        putJsonObject("state") {
                            put("status", "running")
                            putJsonObject("input") {}
                        }
                    }
                }))
            }
            "session.tool.called" -> {
                val sid = data.str("sessionID") ?: return
                val mid = data.str("assistantMessageID") ?: return
                val tid = data.str("id") ?: return
                val mappedName = toolNames["$sid:$tid"] ?: "tool"
                val input = data.obj("input") ?: buildJsonObject {}
                events.tryEmit(Event(dir, "message.part.updated", buildJsonObject {
                    putJsonObject("part") {
                        put("id", tid)
                        put("messageID", mid)
                        put("sessionID", sid)
                        put("type", "tool")
                        put("tool", mappedName)
                        putJsonObject("state") {
                            put("status", "running")
                            put("input", input)
                        }
                    }
                }))
            }
            "session.tool.progress" -> {
                val sid = data.str("sessionID") ?: return
                val mid = data.str("assistantMessageID") ?: return
                val tid = data.str("id") ?: return
                val mappedName = toolNames["$sid:$tid"] ?: "tool"
                val metadata = data.obj("metadata")
                events.tryEmit(Event(dir, "message.part.updated", buildJsonObject {
                    putJsonObject("part") {
                        put("id", tid)
                        put("messageID", mid)
                        put("sessionID", sid)
                        put("type", "tool")
                        put("tool", mappedName)
                        putJsonObject("state") {
                            put("status", "running")
                            metadata?.let { put("metadata", it) }
                        }
                    }
                }))
            }
            "session.tool.success" -> {
                val sid = data.str("sessionID") ?: return
                val mid = data.str("assistantMessageID") ?: return
                val tid = data.str("id") ?: return
                val mappedName = toolNames["$sid:$tid"] ?: "tool"
                val content = data.arr("content").orEmpty()
                val output = content.mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("\n")
                val metadata = data.obj("metadata")

                events.tryEmit(Event(dir, "message.part.updated", buildJsonObject {
                    putJsonObject("part") {
                        put("id", tid)
                        put("messageID", mid)
                        put("sessionID", sid)
                        put("type", "tool")
                        put("tool", mappedName)
                        putJsonObject("state") {
                            put("status", "completed")
                            if (output.isNotEmpty()) put("output", output)
                            metadata?.let { put("metadata", it) }
                        }
                    }
                }))

                metadata?.arr("todos")?.let { todos ->
                    events.tryEmit(Event(dir, "todo.updated", buildJsonObject {
                        put("sessionID", sid)
                        put("todos", todos)
                    }))
                }
            }
            "session.tool.failed" -> {
                val sid = data.str("sessionID") ?: return
                val mid = data.str("assistantMessageID") ?: return
                val tid = data.str("id") ?: return
                val mappedName = toolNames["$sid:$tid"] ?: "tool"
                val content = data.arr("content").orEmpty()
                val output = content.mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("\n")
                val metadata = data.obj("metadata")
                val err = data.obj("error")

                events.tryEmit(Event(dir, "message.part.updated", buildJsonObject {
                    putJsonObject("part") {
                        put("id", tid)
                        put("messageID", mid)
                        put("sessionID", sid)
                        put("type", "tool")
                        put("tool", mappedName)
                        putJsonObject("state") {
                            put("status", "error")
                            err?.let { put("error", it) }
                            if (output.isNotEmpty()) put("output", output)
                            metadata?.let { put("metadata", it) }
                        }
                    }
                }))
            }
            "session.step.ended" -> {
                val sid = data.str("sessionID") ?: return
                val mid = data.str("assistantMessageID") ?: return
                events.tryEmit(Event(dir, "message.updated", buildJsonObject {
                    putJsonObject("info") {
                        put("id", mid)
                        put("sessionID", sid)
                        data.str("finish")?.let { put("finish", it) }
                        data.dbl("cost")?.let { put("cost", it) }
                        data.obj("tokens")?.let { put("tokens", it) }
                        putJsonObject("time") { put("completed", System.currentTimeMillis()) }
                    }
                }))
            }
            "session.step.failed" -> {
                val sid = data.str("sessionID") ?: return
                val mid = data.str("assistantMessageID") ?: return
                events.tryEmit(Event(dir, "message.updated", buildJsonObject {
                    putJsonObject("info") {
                        put("id", mid)
                        put("sessionID", sid)
                        data.obj("error")?.let { put("error", it) }
                        data.dbl("cost")?.let { put("cost", it) }
                        data.obj("tokens")?.let { put("tokens", it) }
                        putJsonObject("time") { put("completed", System.currentTimeMillis()) }
                    }
                }))
            }
            "session.execution.started", "session.execution.succeeded",
            "session.execution.failed", "session.execution.interrupted" -> {
                val sid = data.str("sessionID") ?: return
                when (executionStatus(rawType)) {
                    "busy" -> events.tryEmit(Event(dir, "session.status", buildJsonObject {
                        put("sessionID", sid)
                        putJsonObject("status") { put("type", "busy") }
                    }))
                    // ChatHub's idle handling clears the status, sends the notification and reloads the
                    // open chat, which also confirms the local bubble and picks up the stored message ids.
                    "idle" -> events.tryEmit(Event(dir, "session.idle", buildJsonObject { put("sessionID", sid) }))
                }
            }
            "session.status" -> events.tryEmit(Event(dir, "session.status", data))
            "session.idle" -> events.tryEmit(Event(dir, "session.idle", data))
            "permission.asked" -> {
                val id = data.str("id") ?: return
                val sid = data.str("sessionID") ?: ""
                val rawAction = data.str("action") ?: ""
                val mappedAction = when (rawAction) {
                    "shell" -> "bash"
                    "subagent" -> "task"
                    "write", "patch", "apply_patch" -> "edit"
                    else -> rawAction
                }
                val resources = data.arr("resources") ?: JsonArray(emptyList())
                val callID = data.obj("source")?.str("id")
                events.tryEmit(Event(dir, "permission.asked", buildJsonObject {
                    put("id", id)
                    put("sessionID", sid)
                    put("permission", mappedAction)
                    put("patterns", resources)
                    data.obj("metadata")?.let { put("metadata", it) }
                    callID?.let { putJsonObject("tool") { put("callID", it) } }
                }))
            }
            "permission.replied" -> {
                val rid = data.str("requestID") ?: data.str("id") ?: return
                val sid = data.str("sessionID") ?: ""
                events.tryEmit(Event(dir, "permission.replied", buildJsonObject {
                    put("requestID", rid)
                    put("sessionID", sid)
                }))
            }
            "form.created" -> {
                val form = data.obj("form") ?: data
                events.tryEmit(Event(dir, "question.asked", form))
            }
            "form.replied" -> {
                val fid = data.str("formID") ?: data.str("id") ?: return
                events.tryEmit(Event(dir, "question.replied", buildJsonObject { put("requestID", fid) }))
            }
            "form.cancelled" -> {
                val fid = data.str("formID") ?: data.str("id") ?: return
                events.tryEmit(Event(dir, "question.rejected", buildJsonObject { put("requestID", fid) }))
            }
            "session.created" -> {
                events.tryEmit(Event(dir, "session.created", buildJsonObject {
                    put("info", data)
                }))
            }
            "session.renamed" -> {
                val sid = data.str("sessionID") ?: return
                val title = data.str("title") ?: ""
                events.tryEmit(Event(dir, "session.updated", buildJsonObject {
                    putJsonObject("info") {
                        put("id", sid)
                        put("title", title)
                    }
                }))
            }
            "session.archived" -> {
                val sid = data.str("sessionID") ?: return
                events.tryEmit(Event(dir, "session.updated", buildJsonObject {
                    putJsonObject("info") {
                        put("id", sid)
                        putJsonObject("time") { put("archived", System.currentTimeMillis()) }
                    }
                }))
            }
            "session.deleted" -> {
                val sid = data.str("sessionID") ?: data.str("id") ?: return
                events.tryEmit(Event(dir, "session.deleted", buildJsonObject {
                    put("id", sid)
                    putJsonObject("info") { put("id", sid) }
                }))
            }
            else -> {
                events.tryEmit(Event(dir, rawType, data))
            }
        }
    }

    private fun JsonArray?.orEmpty() = this ?: JsonArray(emptyList())
}

/**
 * OpenCode 2.2.5 reports a run through the execution lifecycle and no longer sends
 * `session.status` or `session.idle` for a normal prompt. Without this mapping the app never
 * learns that a session is busy, so it shows no stop button, sends no "reply finished"
 * notification and never reloads the chat to confirm the sent message.
 */
internal fun executionStatus(rawType: String): String? = when (rawType) {
    "session.execution.started" -> "busy"
    "session.execution.succeeded", "session.execution.failed", "session.execution.interrupted" -> "idle"
    else -> null
}
