package com.celoext

import com.celoext.api.PermissionReq
import com.celoext.api.QuestionReq
import com.celoext.api.Session
import com.celoext.api.TimeInfo
import com.celoext.data.BackgroundTasks
import com.celoext.data.LiveTask
import com.celoext.data.RetryInfo
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundTasksTest {
    private fun session(id: String, title: String = "Chat $id", parent: String? = null, created: Long = 10L) =
        Session(id = id, title = title, parentID = parent, time = TimeInfo(created = created))

    private fun live(id: String, step: String = "Editing Auth.kt", started: Long = 100L) =
        LiveTask(id, step, steps = 2, files = 1, started = started)

    private fun perm(id: String, sid: String) =
        PermissionReq(id, sid, "bash", emptyList(), JsonObject(emptyMap()), null)

    private fun question(id: String, sid: String) = QuestionReq(id, sid, emptyList())

    private fun rows(
        statuses: Map<String, String> = emptyMap(),
        retries: Map<String, RetryInfo> = emptyMap(),
        perms: List<PermissionReq> = emptyList(),
        questions: List<QuestionReq> = emptyList(),
        sessions: Map<String, Session> = emptyMap(),
        live: List<LiveTask> = emptyList(),
        connected: Boolean = true,
        exclude: String? = null,
    ) = BackgroundTasks.rows(statuses, retries, perms, questions, sessions, live, connected, exclude)

    @Test fun liveStepBecomesTheDetail() {
        val r = rows(
            statuses = mapOf("ses_a" to "busy"),
            sessions = mapOf("ses_a" to session("ses_a")),
            live = listOf(live("ses_a")),
        )
        assertEquals(1, r.size)
        assertEquals("Chat ses_a", r[0].title)
        assertEquals("Editing Auth.kt", r[0].detail)
        assertEquals(false, r[0].needsInput)
    }

    @Test fun retryReasonBeatsTheLiveStep() {
        val r = rows(
            statuses = mapOf("ses_a" to "retry"),
            retries = mapOf("ses_a" to RetryInfo("Provider overloaded", null, null, null)),
            live = listOf(live("ses_a")),
        )
        assertEquals("Provider overloaded", r[0].detail)
    }

    @Test fun pendingPermissionAppearsAndSortsFirst() {
        val r = rows(
            statuses = mapOf("ses_a" to "busy"),
            perms = listOf(perm("p1", "ses_b")),
            sessions = mapOf("ses_a" to session("ses_a", created = 10), "ses_b" to session("ses_b", created = 20)),
            live = listOf(live("ses_a", started = 20)),
        )
        assertEquals(listOf("ses_b", "ses_a"), r.map { it.sessionID })
        assertTrue(r[0].needsInput)
        assertEquals("Waiting for your answer", r[0].detail)
    }

    @Test fun pendingQuestionAlsoCountsAsWaiting() {
        val r = rows(questions = listOf(question("q1", "ses_c")))
        assertEquals(1, r.size)
        assertTrue(r[0].needsInput)
    }

    @Test fun openChatIsLeftOut() {
        val r = rows(statuses = mapOf("ses_a" to "busy"), live = listOf(live("ses_a")), exclude = "ses_a")
        assertTrue(r.isEmpty())
    }

    @Test fun otherTasksSurviveWhenTheOpenChatIsLeftOut() {
        val r = rows(statuses = mapOf("ses_a" to "busy", "ses_b" to "busy"), exclude = "ses_a")
        assertEquals(listOf("ses_b"), r.map { it.sessionID })
    }

    @Test fun childSessionsAreIgnored() {
        val r = rows(
            statuses = mapOf("ses_child" to "busy"),
            sessions = mapOf("ses_child" to session("ses_child", parent = "ses_parent")),
        )
        assertTrue(r.isEmpty())
    }

    @Test fun unknownSessionsStillGetARow() {
        val r = rows(statuses = mapOf("ses_x" to "busy"))
        assertEquals("Task", r[0].title)
        assertEquals("Working", r[0].detail)
    }

    @Test fun nothingRunningMeansNoPill() {
        val r = rows()
        assertTrue(r.isEmpty())
        assertNull(BackgroundTasks.pill(r))
    }

    @Test fun disconnectedStreamHidesStaleRows() {
        val r = rows(statuses = mapOf("ses_a" to "busy"), live = listOf(live("ses_a")), connected = false)
        assertTrue(r.isEmpty())
    }

    @Test fun pillForOneTaskIsItsStep() {
        val r = rows(statuses = mapOf("ses_a" to "busy"), live = listOf(live("ses_a")))
        assertEquals("Editing Auth.kt", BackgroundTasks.pill(r))
    }

    @Test fun pillForSeveralTasksCountsThem() {
        val r = rows(
            statuses = mapOf("ses_a" to "busy", "ses_b" to "busy"),
            live = listOf(live("ses_a", started = 100), live("ses_b", step = "Reading main.kt", started = 200)),
        )
        assertEquals("2 tasks · Editing Auth.kt", BackgroundTasks.pill(r))
    }

    @Test fun pillForWaitingSaysNeedsYourOkay() {
        val one = rows(perms = listOf(perm("p1", "ses_a")))
        assertEquals("Needs your OK", BackgroundTasks.pill(one))
        val two = rows(
            statuses = mapOf("ses_a" to "busy"),
            perms = listOf(perm("p1", "ses_b")),
            live = listOf(live("ses_a")),
        )
        assertEquals("2 tasks · Needs your OK", BackgroundTasks.pill(two))
    }

    @Test fun earliestTaskComesFirst() {
        val r = rows(
            statuses = mapOf("ses_a" to "busy", "ses_b" to "busy"),
            sessions = mapOf("ses_a" to session("ses_a", created = 50), "ses_b" to session("ses_b", created = 10)),
        )
        assertEquals(listOf("ses_b", "ses_a"), r.map { it.sessionID })
    }
}
