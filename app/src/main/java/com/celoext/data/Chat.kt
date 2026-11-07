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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/** Bubbles shown before the server stores the message (see [Chat.addLocalUser]). */
const val LOCAL_PREFIX = "zzzz-local-"

/** How far a device clock may be behind the server's when matching a local bubble to a stored message. */
private const val CLOCK_SKEW_MS = 500L

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
    /** Creation time of the newest user message the server has confirmed; older ones cannot be a new send. */
    private var newestUserAt = 0L
    /** Only the latest load may write its snapshot: concurrent loads used to overwrite each other. */
    private var loadSeq = 0
    /** Grows on every SSE event: a load that raced with events must merge, not replace. */
    private var eventRevision = 0

    /** Shows a sent message right away, before the server confirms it. */
    fun addLocalUser(text: String): String {
        val id = LOCAL_PREFIX + System.nanoTime()
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
        val seq = ++loadSeq
        val rev = eventRevision
        try {
            // Parsing up to 200 messages with their tool output is hundreds of KB of JSON: keep it off the UI thread.
            val list = withContext(Dispatchers.IO) { api.messages(id, directory, com.celoext.api.OpenCodeApi.MAX_LIMIT) }
            // A newer load started while this one was in flight: its snapshot is fresher.
            if (seq != loadSeq) return
            val fresh = list.map { m -> ChatMessage(m.info.id, m.info).also { it.parts.addAll(m.parts) } }
            val locals = messages.filter { it.id.startsWith(LOCAL_PREFIX) }
            val keep = unconfirmedLocals(locals, fresh, newestUserAt)
            if (eventRevision != rev && messages.isNotEmpty()) {
                // Events arrived while the request was in flight and are newer than this snapshot:
                // add what is missing, but never wipe what the stream has already delivered.
                fresh.forEach { f -> if (messages.none { it.id == f.id }) insert(f) }
            } else {
                messages.clear()
                messages.addAll(fresh)
            }
            // The merge branch above cannot clear the list, so a local bubble that this snapshot has
            // confirmed would stay next to its stored copy. Drop every local that is no longer pending.
            dropConfirmedLocals(messages, keep)
            // a message we showed right away and the server hasn't returned yet must not blink and vanish
            keep.forEach { l -> if (messages.none { it.id == l.id }) insert(l) }
            newestUserAt = maxOf(newestUserAt, fresh.filter { it.info.isUser }.maxOfOrNull { it.info.time.created } ?: 0L)
            initialIds.clear(); initialIds.addAll(fresh.map { it.id })
            live = true
            loadError = null
            // The todo list is the metadata of the last todowrite part, which is already in this snapshot:
            // fetching it again meant a second request for 100 messages on every reload.
            latestTodos(fresh)?.let { todos = parseTodos(it) }
        } catch (e: Exception) {
            if (seq == loadSeq) loadError = e.message ?: "Could not load the chat"
        } finally {
            if (seq == loadSeq) loading = false
        }
    }

    /** The newest todowrite result in [list], if any. */
    private fun latestTodos(list: List<ChatMessage>): kotlinx.serialization.json.JsonArray? {
        for (m in list.asReversed()) for (p in m.parts.asReversed()) {
            if (p.raw.str("tool") == "todowrite") p.raw.obj("state")?.obj("metadata")?.arr("todos")?.let { return it }
        }
        return null
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
        eventRevision++
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
                    // The real user message replaces the local bubble we showed right away.
                    // One stored message confirms exactly one bubble, oldest first: removing all of
                    // them made a second message sent before the first was confirmed disappear
                    // until the next full reload.
                    if (info.role == "user") {
                        messages.firstOrNull { it.id.startsWith(LOCAL_PREFIX) }?.let { messages.remove(it) }
                        newestUserAt = maxOf(newestUserAt, info.time.created)
                    }
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
        while (i > 0 && !messages[i - 1].id.startsWith(LOCAL_PREFIX) && messages[i - 1].id > m.id) i--
        messages.add(i, m)
        return m
    }

    private fun parseTodos(a: kotlinx.serialization.json.JsonArray) = a.mapNotNull {
        val o = it as? JsonObject ?: return@mapNotNull null
        Todo(o.str("content") ?: "", o.str("status") ?: "pending", o.str("priority") ?: "medium")
    }
}

/**
 * Removes the local bubbles that [keep] does not list, meaning the server has stored them.
 * Needed when a load merges with live events instead of replacing the list.
 */
internal fun dropConfirmedLocals(messages: MutableList<ChatMessage>, keep: List<ChatMessage>) {
    val keepIds = keep.mapTo(HashSet()) { it.id }
    messages.removeAll { it.id.startsWith(LOCAL_PREFIX) && it.id !in keepIds }
}

/**
 * Local bubbles the server has not stored yet, matched one-to-one against the user messages that
 * are newer than the last one already seen.
 *
 * Text must not be used for this: slash commands are sent expanded, and the same word sent twice
 * ("continue") is still two different messages. Matching each stored message to exactly one bubble
 * keeps the second one on screen while its own request is still being persisted.
 */
internal fun unconfirmedLocals(locals: List<ChatMessage>, fresh: List<ChatMessage>, seenUntil: Long): List<ChatMessage> {
    val candidates = fresh.filter { it.info.isUser && it.info.time.created > seenUntil }.sortedBy { it.info.time.created }
    val claimed = HashSet<String>()
    val keep = ArrayList<ChatMessage>(locals.size)
    for (l in locals.sortedBy { it.info.time.created }) {
        val hit = candidates.firstOrNull { it.id !in claimed && it.info.time.created + CLOCK_SKEW_MS >= l.info.time.created }
        if (hit == null) keep += l else claimed += hit.id
    }
    return keep
}
