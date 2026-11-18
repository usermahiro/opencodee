package com.celoext.ui.screens

import com.celoext.ui.theme.screenBackdrop
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celoext.Graph
import com.celoext.ui.Nav
import com.celoext.ui.components.ChoiceRow
import com.celoext.ui.components.EmptyState
import com.celoext.ui.components.IconBtn
import com.celoext.ui.components.OcDialog
import com.celoext.ui.components.OcSheet
import com.celoext.ui.components.OcTextField
import com.celoext.ui.components.Pill
import com.celoext.ui.components.SettingsGroup
import com.celoext.ui.components.SettingsRow
import com.celoext.ui.components.TopBar
import com.celoext.ui.components.Txt
import com.celoext.ui.theme.Ic
import com.celoext.ui.theme.Oc
import java.io.File

const val WORK_ROOT = "/root/work"

/** Project directories: everything in /root/work, plus directories that already have sessions. */
fun knownProjects(): List<Pair<String, Int>> {
    val counts = Graph.chats.sessions.value.groupingBy { it.directory }.eachCount()
    // A remote device has no phone directories: take only what's known from sessions.
    if (Graph.devices.isRemote) return (counts.keys + listOf(Graph.prefs.currentDir)).filter { it.isNotBlank() }.distinct().map { it to (counts[it] ?: 0) }
    val onDisk = Graph.paths.host(WORK_ROOT).listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.map { "$WORK_ROOT/${it.name}" }.orEmpty()
    return (listOf(WORK_ROOT) + onDisk + counts.keys).distinct().map { it to (counts[it] ?: 0) }
}

private fun validName(n: String) = n.isNotBlank() && n.all { it.isLetterOrDigit() || it in "-_. " } && !n.startsWith(".")

@Composable
fun ProjectsScreen(nav: Nav, onMenu: () -> Unit) {
    val c = Oc.c
    val sessions by Graph.chats.sessions.collectAsState()
    var newProject by remember { mutableStateOf(false) }
    val projects = remember(sessions, newProject) { knownProjects() }
    Box(Modifier.fillMaxSize().screenBackdrop("ProjectsScreen1")) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Projects", left = { IconBtn(Ic.Menu, onMenu) })
            if (projects.isEmpty()) EmptyState(Ic.Folder, "No projects yet", "Projects you create show up here.")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 120.dp)) {
                item {
                    SettingsGroup {
                        projects.forEach { (dir, n) ->
                            SettingsRow(
                                title = dir.removePrefix("/root/").ifBlank { "/" }, subtitle = "$n session${if (n == 1) "" else "s"}", icon = Ic.Folder,
                                trailing = { if (Graph.prefs.currentDir == dir) Txt("Active", size = 14.sp, color = c.link) },
                                onClick = { Graph.prefs.currentDir = dir; nav.root(com.celoext.ui.Route.Home) },
                            )
                        }
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp)) { Pill("New project", { newProject = true }, icon = Ic.Plus) }
    }
    if (newProject) NewProjectDialog { newProject = false }
}

@Composable
fun NewProjectDialog(onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    OcDialog("New project", onDone, "Create", {
        if (!validName(name)) { error = "Use letters, digits, space, - _ ."; return@OcDialog }
        val dir = "$WORK_ROOT/${name.trim()}"
        File(Graph.paths.rootfs, dir.trimStart('/')).mkdirs()
        Graph.prefs.currentDir = dir
        onDone()
    }) {
        Column {
            OcTextField(name, { name = it; error = null }, placeholder = "Folder name")
            error?.let { Txt(it, size = 14.sp, color = Oc.c.danger, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}

/** Pick the working directory right from the home screen. */
@Composable
fun ProjectPicker(onDismiss: () -> Unit) {
    var create by remember { mutableStateOf(false) }
    val projects = remember(create) { knownProjects() }
    OcSheet(onDismiss, "Project", skipHalf = false) {
        LazyColumn(Modifier.padding(bottom = 24.dp)) {
            items(projects) { (dir, n) ->
                ChoiceRow(dir.removePrefix("/root/").ifBlank { "/" }, Graph.prefs.currentDir == dir, { Graph.prefs.currentDir = dir; onDismiss() }, icon = Ic.Folder, subtitle = "$n sessions")
            }
            item { ChoiceRow("New project…", false, { create = true }, icon = Ic.Plus) }
        }
    }
    if (create) NewProjectDialog { create = false; onDismiss() }
}
