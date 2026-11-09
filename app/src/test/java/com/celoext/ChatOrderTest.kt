package com.celoext

import com.celoext.api.MessageInfo
import com.celoext.api.MessageWithParts
import com.celoext.api.TimeInfo
import com.celoext.api.inChatOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The message endpoint returns the latest page newest first. The chat list is drawn top to
 * bottom with the composer at the bottom, so a reload has to turn that around; otherwise a
 * conversation opens at its oldest message and the history reads backwards.
 */
class ChatOrderTest {
    private fun message(id: String, at: Long) =
        MessageWithParts(MessageInfo(id = id, sessionID = "ses_test", role = "assistant", time = TimeInfo(created = at)), emptyList())

    @Test fun newestFirstFromTheServerBecomesOldestFirstForTheChat() {
        val ordered = inChatOrder(listOf(message("msg_3", 300), message("msg_2", 200), message("msg_1", 100)))
        assertEquals(listOf("msg_1", "msg_2", "msg_3"), ordered.map { it.info.id })
    }

    @Test fun anAlreadyAscendingListIsLeftAlone() {
        val ordered = inChatOrder(listOf(message("msg_1", 100), message("msg_2", 200)))
        assertEquals(listOf("msg_1", "msg_2"), ordered.map { it.info.id })
    }

    @Test fun equalTimesFallBackToTheId() {
        val ordered = inChatOrder(listOf(message("msg_b", 100), message("msg_a", 100)))
        assertEquals(listOf("msg_a", "msg_b"), ordered.map { it.info.id })
    }
}
