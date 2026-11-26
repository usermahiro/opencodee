package com.celoext.data

import com.celoext.api.PermissionReq
import com.celoext.api.QuestionReq
import com.celoext.api.Session

/**
 * One running session as the notification tracker sees it: the last step, how many steps and files
 * are done and when it started. [LiveNotify] publishes these; the in-app task pill reads them.
 */
data class LiveTask(
    val sessionID: String,
    val step: String,
    val steps: Int,
    val files: Int,
    val started: Long,
)

/** A row in the task sheet. [detail] is the live step, a retry reason or the waiting prompt. */
data class TaskRow(
    val sessionID: String,
    val title: String,
    val detail: String,
    val needsInput: Boolean,
    val started: Long,
    val steps: Int,
    val files: Int,
)

/** Turns the event state into the running tasks the pill and the sheet show. */
object BackgroundTasks {
    fun rows(
        statuses: Map<String, String>,
        retries: Map<String, RetryInfo>,
        permissions: List<PermissionReq>,
        questions: List<QuestionReq>,
        sessions: Map<String, Session>,
        live: List<LiveTask>,
        connected: Boolean,
        excludeID: String? = null,
    ): List<TaskRow> {
        // Without the event stream the last statuses may be stale. A ghost "working" row is worse
        // than a gap; the list returns on reconnect, when the statuses refresh.
        if (!connected) return emptyList()

        val waiting = buildSet {
            permissions.forEach { add(it.sessionID) }
            questions.forEach { add(it.sessionID) }
        }
        val liveByID = live.associateBy { it.sessionID }

        return (statuses.keys + waiting)
            .asSequence()
            .filter { it != excludeID }
            .mapNotNull { id ->
                val session = sessions[id]
                // Subagent work belongs to the parent row, not to a row of its own.
                if (session?.parentID != null) return@mapNotNull null
                val l = liveByID[id]
                val retry = retries[id]
                TaskRow(
                    sessionID = id,
                    title = session?.displayTitle ?: "Task",
                    detail = when {
                        id in waiting -> "Waiting for your answer"
                        retry != null && retry.message.isNotBlank() -> retry.message
                        l != null -> l.step
                        else -> "Working"
                    },
                    needsInput = id in waiting,
                    started = l?.started ?: session?.time?.created?.takeIf { it > 0 } ?: Long.MAX_VALUE,
                    steps = l?.steps ?: 0,
                    files = l?.files ?: 0,
                )
            }
            .sortedWith(
                compareByDescending<TaskRow> { it.needsInput }
                    .thenBy { it.started }
                    .thenBy { it.sessionID },
            )
            .toList()
    }

    fun pill(rows: List<TaskRow>): String? = when {
        rows.isEmpty() -> null
        rows.size == 1 -> if (rows[0].needsInput) "Needs your OK" else rows[0].detail
        rows.any { it.needsInput } -> "${rows.size} tasks · Needs your OK"
        else -> "${rows.size} tasks · ${rows[0].detail}"
    }
}
