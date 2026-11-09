package com.celoext

import com.celoext.api.MessageInfo
import com.celoext.api.Part
import com.celoext.api.TimeInfo
import com.celoext.data.ChatMessage
import com.celoext.data.LOCAL_PREFIX
import com.celoext.data.dropConfirmedLocals
import com.celoext.data.unconfirmedLocals
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bubble shown right after sending must survive every reload until the server has stored
 * that exact message. These cases reproduce the "my message disappeared" report.
 */
class ChatLocalBubblesTest {
    private fun local(at: Long, text: String = "hello") = ChatMessage(
        LOCAL_PREFIX + at,
        MessageInfo(id = LOCAL_PREFIX + at, role = "user", time = TimeInfo(created = at)),
    ).also { m ->
        m.parts.add(Part(buildJsonObject {
            put("id", "p-$at"); put("messageID", LOCAL_PREFIX + at); put("type", "text"); put("text", text)
        }))
    }

    private fun stored(id: String, at: Long, role: String = "user") =
        ChatMessage(id, MessageInfo(id = id, role = role, time = TimeInfo(created = at)))

    @Test fun storedMessageDropsItsLocalBubble() {
        val keep = unconfirmedLocals(listOf(local(1_000)), listOf(stored("msg_1", 1_200)), seenUntil = 0)
        assertTrue(keep.isEmpty())
    }

    @Test fun secondSendSurvivesWhileOnlyTheFirstIsStored() {
        // First send already replaced a moment ago; second one is still in flight.
        val keep = unconfirmedLocals(
            locals = listOf(local(5_000, "continue")),
            fresh = listOf(stored("msg_1", 1_200)),
            seenUntil = 1_200,
        )
        assertEquals(1, keep.size)
        assertEquals(5_000L, keep[0].info.time.created)
    }

    @Test fun oneStoredMessageConfirmsOneBubble() {
        val keep = unconfirmedLocals(
            locals = listOf(local(1_000, "ok"), local(1_100, "ok")),
            fresh = listOf(stored("msg_1", 1_150)),
            seenUntil = 0,
        )
        assertEquals(1, keep.size)
        assertEquals(1_100L, keep[0].info.time.created)
    }

    @Test fun assistantMessagesNeverConfirmAUserBubble() {
        val keep = unconfirmedLocals(
            locals = listOf(local(1_000)),
            fresh = listOf(stored("msg_a", 1_200, role = "assistant")),
            seenUntil = 0,
        )
        assertEquals(1, keep.size)
    }

    @Test fun oldMessageDoesNotConfirmANewerBubble() {
        val keep = unconfirmedLocals(
            locals = listOf(local(5_000)),
            fresh = listOf(stored("msg_1", 1_200)),
            seenUntil = 0,
        )
        assertEquals(1, keep.size)
    }

    @Test fun mergedSnapshotDropsTheConfirmedBubble() {
        // A load that races with live events merges instead of replacing the list: the bubble the
        // snapshot has confirmed must still be removed, or the same message shows twice.
        val first = local(1_000, "first")
        val second = local(3_000, "second")
        val current = mutableListOf(first, stored("msg_1", 1_100), second)

        dropConfirmedLocals(current, keep = listOf(second))

        assertEquals(listOf("msg_1", second.id), current.map { it.id })
    }

    @Test fun mergedSnapshotKeepsPendingBubbles() {
        val first = local(1_000, "first")
        val second = local(3_000, "second")
        val current = mutableListOf(first, stored("msg_1", 1_100), second)

        dropConfirmedLocals(current, keep = listOf(first, second))

        assertEquals(listOf(first.id, "msg_1", second.id), current.map { it.id })
    }

    @Test fun alreadySeenMessageDoesNotConfirmAgain() {
        val keep = unconfirmedLocals(
            locals = listOf(local(3_000)),
            fresh = listOf(stored("msg_1", 3_100)),
            seenUntil = 3_100,
        )
        assertEquals(1, keep.size)
    }
}
