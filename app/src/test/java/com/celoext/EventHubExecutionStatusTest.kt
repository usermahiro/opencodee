package com.celoext

import com.celoext.api.executionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * OpenCode 2.2.5 announces a run with session.execution.* and sends no session.status or
 * session.idle for a normal prompt. The app has to translate the execution lifecycle itself,
 * otherwise the chat never shows the stop button, never sends the "reply finished" notification
 * and never reloads after a run to confirm the sent message.
 */
class EventHubExecutionStatusTest {
    @Test fun startedMeansBusy() {
        assertEquals("busy", executionStatus("session.execution.started"))
    }

    @Test fun everyWayARunCanEndMeansIdle() {
        assertEquals("idle", executionStatus("session.execution.succeeded"))
        assertEquals("idle", executionStatus("session.execution.failed"))
        assertEquals("idle", executionStatus("session.execution.interrupted"))
    }

    @Test fun otherEventsAreNotStatuses() {
        assertNull(executionStatus("session.text.delta"))
        assertNull(executionStatus("session.status"))
    }
}
