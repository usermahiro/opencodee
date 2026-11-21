package com.celoext.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celoext.Graph
import com.celoext.data.BackgroundTasks
import com.celoext.data.LiveNotify
import com.celoext.ui.Nav
import com.celoext.ui.theme.Ic
import com.celoext.ui.theme.Oc

/**
 * In-app view of agent work that continues after the user leaves a chat. A pill by the composer
 * shows the current step or the waiting prompt; tapping it opens the list of running sessions.
 * [excludeID] is the chat already on screen: its steps and Stop are there, so it is left out.
 */
@Composable
fun LiveTaskPill(nav: Nav, excludeID: String? = null, modifier: Modifier = Modifier) {
    val c = Oc.c
    val statuses by Graph.chats.statuses.collectAsState()
    val retries by Graph.chats.retries.collectAsState()
    val permissions by Graph.chats.permissions.collectAsState()
    val questions by Graph.chats.questions.collectAsState()
    val sessions by Graph.chats.allSessions.collectAsState()
    val live by LiveNotify.active.collectAsState()
    val connected by Graph.events.connected.collectAsState()

    val rows = remember(statuses, retries, permissions, questions, sessions, live, connected, excludeID) {
        BackgroundTasks.rows(statuses, retries, permissions, questions, sessions, live, connected, excludeID)
    }
    var sheet by remember { mutableStateOf(false) }
    LaunchedEffect(rows.isEmpty()) { if (rows.isEmpty()) sheet = false }

    val text = BackgroundTasks.pill(rows) ?: return
    val waiting = rows.any { it.needsInput }

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            Modifier.clip(CircleShape)
                .background(c.surfaceHigh)
                .border(1.dp, if (waiting) c.warn.copy(alpha = 0.5f) else c.border, CircleShape)
                .clickable { sheet = true }
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (waiting) Ico(Ic.Warning, tint = c.warn, size = 16.dp) else Spinner(14.dp, stroke = 2.dp)
            Spacer(Modifier.width(8.dp))
            Txt(text, size = 14.sp, color = if (waiting) c.warn else c.text, maxLines = 1)
        }
    }

    if (sheet) OcSheet(onDismiss = { sheet = false }, title = "Tasks") {
        rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth()
                    .clickable { sheet = false; nav.openChat(row.sessionID) }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (row.needsInput) Ico(Ic.Warning, tint = c.warn, size = 20.dp) else Spinner(18.dp, stroke = 2.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Txt(row.title, size = 16.sp, maxLines = 1)
                    Txt(row.detail, size = 13.sp, color = if (row.needsInput) c.warn else c.textDim, maxLines = 1)
                }
                Txt(elapsed(row.started), size = 12.sp, color = c.textDim)
            }
        }
    }
}

/** Short age for the task row: "now", "7m", "2h 5m". */
private fun elapsed(started: Long): String {
    if (started <= 0 || started == Long.MAX_VALUE) return ""
    val s = ((System.currentTimeMillis() - started) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "now"
        s < 3600 -> "${s / 60}m"
        else -> "${s / 3600}h ${(s % 3600) / 60}m"
    }
}
