package com.celoext.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.celoext.api.MessageInfo
import com.celoext.api.OcJson
import com.celoext.api.OpenCodeApi
import com.celoext.api.TimeInfo
import com.celoext.api.Part
import com.celoext.api.arr
import com.celoext.api.obj
import com.celoext.api.str
import kotlinx.serialization.json.JsonObject

class ChatMessage(val id: String, info: MessageInfo) {
    var info by mutableStateOf(info)
    val parts = mutableStateListOf<Part>()

    fun upsertPart(p: Part) {
        val i = parts.indexOfFirst { it.id == p.id }
        if (i >= 0) {
            val existing = parts[i]
            if (p.type == "tool" && existing.type == "tool") {
                val existingState = existing.raw.obj("state")
                val newState = p.raw.obj("state")
                if (existingState != null && newState != null) {
                    val mergedState = kotlinx.serialization.json.buildJsonObject {
                        for ((k, v) in existingState) put(k, v)
                        for ((k, v) in newState) put(k, v)
                    }
                    val mergedRaw = kotlinx.serialization.json.buildJsonObject {
                        for ((k, v) in existing.raw) put(k, v)
                        for ((k, v) in p.raw) put(k, v)
                        put("state", mergedState)
                    }
                    parts[i] = Part(mergedRaw)
                    return
                }
            }
            parts[i] = p
        } else parts.add(p)
    }
}

data class Todo(val content: String, val status: String, val priority: String)

/** State of an open chat. All changes come from the main thread (SSE events and loading). */
class Chat(val id: String, private val api: OpenCodeApi) {
    var directory: String? = null
    val messages = mutableStateListOf<ChatMessage>()
    var loading by mutableStateOf(true)
    var loadError by mutableStateOf<String?>(null)
    var todos by mutableStateOf<List<Todo>>(emptyList())
    /** Messages that came with the initial load: they need no appear animation. */
    val initialIds = HashSet<String>()
    var live = false

    /** Shows a sent message right away, before the server confirms it. */
    fun addLocalUser(text: String): String {
        val id = "zzzz-local-" + System.nanoTime()
        val raw = kotlinx.serialization.json.buildJsonObject {
            put("id", kotlinx.serialization.json.JsonPrimitive("$id-p")); put("messageID", kotlinx.serialization.json.JsonPrimitive(id))
            put("type", kotlinx.serialization.json.JsonPrimitive("text")); put("text", kotlinx.serialization.json.JsonPrimitive(text))
        }
        val m = ChatMessage(id, MessageInfo(id = id, sessionID = this.id, role = "user", time = TimeInfo(created = System.currentTimeMillis())))
        m.parts.add(Part(raw))
        messages.add(m)
        return id
    }

    fun dropLocal(id: String) { messages.removeAll { it.id == id } }

    fun truncateFrom(messageId: String) { messages.removeAll { it.id >= messageId } }

    suspend fun load() {
        try {
            val list = api.messages(id, directory, com.celoext.api.OpenCodeApi.MAX_LIMIT)
            if (list.isEmpty() && messages.isNotEmpty()) {
                // Server warming / slow prompt enqueue often returns [] for a beat. Keep history on screen.
                loadError = null
                return
            }
            val fresh = list.map { m -> ChatMessage(m.info.id, m.info).also { it.parts.addAll(m.parts) } }
            val locals = messages.filter { it.id.startsWith("zzzz-local-") }
            messages.clear()
            messages.addAll(fresh)
            // a message we showed right away and the server hasn't returned yet must not blink and vanish
            locals.forEach { l -> if (fresh.none { it.info.isUser && it.parts.firstOrNull()?.text == l.parts.firstOrNull()?.text && it.id > "" && (it.info.time.created >= l.info.time.created - 5000) }) messages.add(l) }
            initialIds.clear(); initialIds.addAll(fresh.map { it.id })
            live = true
            loadError = null
            runCatching { api.todos(id, directory) }.getOrNull()?.let { todos = parseTodos(it) }
        } catch (e: Exception) {
            // Keep whatever is already on screen. A transient load failure must not blank the chat.
            if (messages.isEmpty()) loadError = e.message ?: "Could not load the chat"
            else loadError = null
        } finally {
            loading = false
        }
    }

    private val pending = LinkedHashMap<Pair<String, String>, StringBuilder>()
    private var flushQueued = false
    private val frameHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val flusher = Runnable { flushQueued = false; flushDeltas() }

    private fun flushDeltas() {
        if (pending.isEmpty()) return
        for ((key, sb) in pending) {
            val m = find(key.first) ?: continue
            val i = m.parts.indexOfFirst { it.id == key.second }
            if (i >= 0) m.parts[i] = m.parts[i].withText(m.parts[i].text + sb)
        }
        pending.clear()
    }

    fun onEvent(type: String, props: JsonObject) {
        // any other part event must see the text accumulated so far
        if (type != "message.part.delta") flushDeltas()
        when (type) {
            "message.updated" -> {
                val info = props.obj("info")?.let { runCatching { OcJson.decodeFromJsonElement(MessageInfo.serializer(), it) }.getOrNull() } ?: return
                val m = find(info.id)
                if (m != null) {
                    m.info = m.info.copy(
                        role = if (info.role.isNotBlank()) info.role else m.info.role,
                        modelID = info.modelID ?: m.info.modelID,
                        providerID = info.providerID ?: m.info.providerID,
                        model = info.model ?: m.info.model,
                        agent = info.agent ?: m.info.agent,
                        cost = info.cost ?: m.info.cost,
                        tokens = info.tokens ?: m.info.tokens,
                        finish = info.finish ?: m.info.finish,
                        error = info.error ?: m.info.error,
                        time = if (info.time.completed != null) m.info.time.copy(completed = info.time.completed) else m.info.time,
                    )
                } else {
                    // the real user message replaces the "local" one (the one we showed right away)
                    if (info.role == "user") messages.removeAll { it.id.startsWith("zzzz-local-") }
                    insert(ChatMessage(info.id, info))
                }
            }
            "message.removed" -> props.str("messageID")?.let { mid -> messages.removeAll { it.id == mid } }
            "message.part.updated" -> {
                val part = props.obj("part")?.let(::Part) ?: return
                val m = find(part.messageID) ?: insert(ChatMessage(part.messageID, MessageInfo(id = part.messageID, sessionID = id, role = "assistant")))
                m.upsertPart(part)
            }
            "message.part.delta" -> {
                if (props.str("field") != "text") return
                val mid = props.str("messageID") ?: return
                val pid = props.str("partID") ?: return
                // Tokens arrive dozens per second: buffer them and apply at most once per frame.
                pending.getOrPut(mid to pid) { StringBuilder() }.append(props.str("delta") ?: "")
                if (!flushQueued) { flushQueued = true; frameHandler.postDelayed(flusher, 24) }
            }
            "message.part.removed" -> {
                val m = find(props.str("messageID") ?: return) ?: return
                val pid = props.str("partID")
                m.parts.removeAll { it.id == pid }
            }
            "todo.updated" -> props.arr("todos")?.let { todos = parseTodos(it) }
        }
    }

    private fun find(mid: String) = messages.firstOrNull { it.id == mid }

    /** Message ids grow over time, so the order is lexicographic. */
    private fun insert(m: ChatMessage): ChatMessage {
        var i = messages.size
        // OpenCode 2.x sends no event for the user's own message, so the "local" bubble (id zzzz-local-…) stays until the
        // next reload. Anything arriving over SSE happened after it, so never walk back past it: otherwise the reply
        // lands above the question.
        while (i > 0 && !messages[i - 1].id.startsWith("zzzz-local-") && messages[i - 1].id > m.id) i--
        messages.add(i, m)
        return m
    }

    private fun parseTodos(a: kotlinx.serialization.json.JsonArray) = a.mapNotNull {
        val o = it as? JsonObject ?: return@mapNotNull null
        Todo(o.str("content") ?: "", o.str("status") ?: "pending", o.str("priority") ?: "medium")
    }
}
