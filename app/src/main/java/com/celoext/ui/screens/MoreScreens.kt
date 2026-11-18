package com.celoext.ui.screens

import com.celoext.ui.theme.screenBackdrop
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celoext.BuildConfig
import com.celoext.Graph
import com.celoext.api.OcJson
import com.celoext.api.str
import com.celoext.runtime.RuntimeState
import com.celoext.ui.Nav
import com.celoext.ui.components.IconBtn
import com.celoext.ui.components.OcDialog
import com.celoext.ui.components.OcSheet
import com.celoext.ui.components.OcSwitch
import com.celoext.ui.components.OcTextField
import com.celoext.ui.components.Pill
import com.celoext.ui.components.Segmented
import com.celoext.ui.components.SettingsGroup
import com.celoext.ui.components.SettingsRow
import com.celoext.ui.components.SmallButton
import com.celoext.ui.components.Spinner
import com.celoext.ui.components.TopBar
import com.celoext.ui.components.Txt
import com.celoext.ui.theme.Ic
import com.celoext.ui.theme.Oc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

// ---------- Connectors (MCP) ----------

@Composable
fun ConnectorsScreen(nav: Nav) {
    val c = Oc.c
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<JsonObject?>(null) }
    var add by remember { mutableStateOf(false) }
    suspend fun load() { status = runCatching { Graph.api.mcpStatus() }.getOrDefault(JsonObject(emptyMap())) }
    LaunchedEffect(Unit) { load() }
    Box(Modifier.fillMaxSize().screenBackdrop("MoreScreens1")) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Connectors", left = { IconBtn(Ic.Back, { nav.pop() }) })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 120.dp)) {
                val s = status
                if (s == null) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Spinner(28.dp) }
                else if (s.isEmpty()) Txt("No MCP servers configured. Connectors give the agent extra tools — databases, browsers, docs.", Modifier.padding(24.dp), size = 16.sp, color = c.textDim, lineHeight = 23.sp)
                else SettingsGroup {
                    s.forEach { (name, v) ->
                        val st = (v as? JsonObject)?.str("status") ?: "unknown"
                        val on = st == "connected"
                        SettingsRow(name, subtitle = (v as? JsonObject)?.str("error") ?: st.replace('_', ' '), icon = Ic.Puzzle, trailing = {
                            OcSwitch(on) { want ->
                                scope.launch { runCatching { if (want) Graph.api.mcpConnect(name) else Graph.api.mcpDisconnect(name) }; load() }
                            }
                        })
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp)) { Pill("Add connector", { add = true }, icon = Ic.Plus) }
    }
    if (add) AddConnectorDialog({ add = false; scope.launch { load() } })
}

@Composable
private fun AddConnectorDialog(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("remote") }
    var target by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    OcDialog("Add connector", onDone, "Add", {
        val cfg = if (kind == "remote") buildJsonObject { put("type", "remote"); put("url", target.trim()); put("enabled", true) }
        else buildJsonObject {
            put("type", "local"); put("enabled", true)
            putJsonArray("command") { target.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { add(JsonPrimitive(it)) } }
        }
        scope.launch {
            runCatching { Graph.api.patchConfig(buildJsonObject { put("mcp", buildJsonObject { put(name.trim(), cfg) }) }); Graph.api.mcpAdd(name.trim(), cfg) }
                .onSuccess { onDone() }.onFailure { error = it.message }
        }
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OcTextField(name, { name = it }, placeholder = "Name (e.g. github)")
            Segmented(listOf("remote" to "Remote URL", "local" to "Local command"), kind, { kind = it })
            OcTextField(target, { target = it }, placeholder = if (kind == "remote") "https://…/mcp" else "npx -y @modelcontextprotocol/server-everything", mono = true)
            error?.let { Txt(it, size = 14.sp, color = Oc.c.danger) }
        }
    }
}

// ---------- Local server ----------

@Composable
fun ServerScreen(nav: Nav) {
    val c = Oc.c
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rt by Graph.runtime.state.collectAsState()
    val log by Graph.runtime.log.collectAsState()
    val prefs = Graph.prefs
    var showLog by remember { mutableStateOf(false) }
    var tools by remember { mutableStateOf<List<String>?>(null) }
    var reset by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().screenBackdrop("MoreScreens2")) {
        TopBar("Local server", left = { IconBtn(Ic.Back, { nav.pop() }) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 32.dp)) {
            Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(Oc.round(24.dp)).background(c.surface).padding(20.dp)) {
                val (label, color) = when (val s = rt) {
                    is RuntimeState.Running -> "Running · OpenCode ${s.version}" to c.ok
                    is RuntimeState.Starting -> "Starting…" to c.warn
                    is RuntimeState.Crashed -> s.message to c.danger
                    else -> "Stopped" to c.textDim
                }
                Txt(label, size = 19.sp, color = color)
                Txt("Local server · 127.0.0.1:${prefs.serverPort}", size = 15.sp, color = c.textDim, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (rt is RuntimeState.Running || rt is RuntimeState.Starting) SmallButton("Stop", { Graph.runtime.stopServer() }, danger = true)
                    else SmallButton("Start", { Graph.runtime.startServer() }, filled = true)
                    SmallButton("Restart", { Graph.runtime.restartServer() })
                    SmallButton("Log", { showLog = true })
                }
            }
            Spacer(Modifier.height(16.dp))
            SettingsGroup {
                SettingsRow("Start with the app", icon = Ic.Zap, trailing = { OcSwitch(prefs.autoStartServer) { prefs.autoStartServer = it } })
                SettingsRow("Keep CPU awake while running", icon = Ic.Server, trailing = { OcSwitch(prefs.keepAwake) { prefs.keepAwake = it } })
                SettingsRow("Compatibility mode", subtitle = "Try this if the server keeps crashing on your phone", icon = Ic.Shield, trailing = { OcSwitch(prefs.noSeccomp) { prefs.noSeccomp = it; Graph.runtime.restartServer() } })
                // Android 12+ kills "extra" child processes of apps (phantom process killer), and proot has many
                SettingsRow("Server keeps stopping?", subtitle = "Developer options → turn on “Disable child process restrictions”", icon = Ic.Info, onClick = {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { Graph.chats.toasts.tryEmit("Turn on Developer options first: tap Build number 7 times in About phone") }
                })
            }
            Spacer(Modifier.height(16.dp))
            SettingsGroup {
                SettingsRow("9Router server", subtitle = "AI gateway + dashboard inside this environment", icon = Ic.Router, chevron = true, onClick = { nav.push(com.celoext.ui.Route.NineRouter) })
                SettingsRow("Install dev tools", subtitle = "git, ripgrep, curl, python3, build-essential", icon = Ic.Terminal, onClick = {
                    val lines = mutableStateListOf<String>(); tools = lines
                    scope.launch {
                        val code = Graph.runtime.installDevTools { l -> scope.launch(Dispatchers.Main) { lines.add(l) } }
                        lines.add(if (code == 0) "✓ Done" else "✗ Failed (exit $code)")
                    }
                })
                SettingsRow("Don't kill the server in background", subtitle = "Exempt the app from battery optimization", icon = Ic.Zap, onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                })
                SettingsRow("Reset environment", subtitle = "Deletes the environment and everything inside it", icon = Ic.Trash, titleColor = c.danger, onClick = { reset = true })
            }
            Txt(
                "Android 12+ may kill background child processes. If the server dies when the screen is off, run once from a computer:\n" +
                    "adb shell device_config put activity_manager max_phantom_processes 2147483647\n" +
                    "adb shell settings put global settings_enable_monitor_phantom_procs false",
                size = 13.sp, color = c.textDim, modifier = Modifier.padding(20.dp), lineHeight = 19.sp, family = Oc.mono,
            )
        }
    }

    if (showLog) LogSheet(log) { showLog = false }
    tools?.let { lines -> LogSheet(lines, "Installing tools") { tools = null } }
    if (reset) OcDialog("Reset environment?", { reset = false }, "Reset", {
        reset = false; Graph.runtime.resetRuntime()
    }, danger = true) { Txt("The environment, OpenCode and all projects under /root will be deleted. Sessions history is lost too.", size = 16.sp, color = Oc.c.textDim) }
}

@Composable
private fun LogSheet(lines: List<String>, title: String = "Server log", onDismiss: () -> Unit) {
    val c = Oc.c
    val clip = LocalClipboardManager.current
    OcSheet(onDismiss, title, skipHalf = false) {
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallButton("Copy", { clip.setText(AnnotatedString(lines.joinToString("\n"))) })
        }
        Column(Modifier.fillMaxWidth().height(480.dp).verticalScroll(rememberScrollState(), reverseScrolling = true).padding(16.dp)) {
            Txt(lines.joinToString("\n").ifBlank { "(empty)" }, size = 11.sp, family = Oc.mono, color = c.textDim, lineHeight = 15.sp)
        }
    }
}

// ---------- Config editor ----------

@Composable
fun ConfigEditorScreen(nav: Nav) {
    val c = Oc.c
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val pretty = remember { Json { prettyPrint = true } }
    suspend fun load() {
        text = runCatching { pretty.encodeToString(JsonObject.serializer(), Graph.api.config()) }.getOrElse { "{}" }; loaded = true
    }
    LaunchedEffect(Unit) { load() }
    Column(Modifier.fillMaxSize().screenBackdrop("MoreScreens3")) {
        TopBar("Config", left = { IconBtn(Ic.Back, { nav.pop() }) }, right = { IconBtn(Ic.Refresh, { scope.launch { load() } }) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Txt("Everything OpenCode supports in opencode.json: models, agents, MCP, permissions, formatters, instructions, keybinds. Changes are merged and applied at once.", size = 14.sp, color = c.textDim, modifier = Modifier.padding(bottom = 12.dp), lineHeight = 20.sp)
            if (!loaded) Spinner(26.dp) else OcTextField(text, { text = it; msg = null }, singleLine = false, minLines = 16, mono = true)
            Spacer(Modifier.height(14.dp))
            Pill("Save", {
                val o = runCatching { OcJson.parseToJsonElement(text) as JsonObject }.getOrNull()
                if (o == null) msg = "Not valid JSON object"
                else scope.launch { msg = runCatching { Graph.api.patchConfig(o); "Saved" }.getOrElse { it.message } }
            }, modifier = Modifier.fillMaxWidth())
            msg?.let { Txt(it, size = 15.sp, color = if (it == "Saved") c.ok else c.danger, modifier = Modifier.padding(top = 12.dp)) }
        }
    }
}

// ---------- Profile ----------

@Composable
fun ProfileScreen(nav: Nav) {
    val c = Oc.c
    val ctx = LocalContext.current
    val prefs = Graph.prefs
    var name by remember { mutableStateOf(prefs.userName) }
    var rules by remember { mutableStateOf(com.celoext.data.Memory.userRules()) }
    var saved by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
            require(bounds.outWidth > 0)
            val bmp = ctx.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return@runCatching
            val side = minOf(bmp.width, bmp.height)
            val sq = android.graphics.Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
            val out = android.graphics.Bitmap.createScaledBitmap(sq, 512.coerceAtMost(side), 512.coerceAtMost(side), true)
            val f = File(ctx.filesDir, "avatar-${System.currentTimeMillis()}.jpg")
            f.outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
            prefs.avatarPath = f.path
        }
    }
    Column(Modifier.fillMaxSize().screenBackdrop("MoreScreens4")) {
        TopBar("Profile", left = { IconBtn(Ic.Back, { nav.pop() }) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(112.dp) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            Spacer(Modifier.height(14.dp))
            SmallButton("Edit photo", { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
            Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt("What should we call you?", size = 17.sp, modifier = Modifier.padding(start = 4.dp))
                OcTextField(name, { name = it })
                Pill("Update profile", { prefs.userName = name.trim() }, enabled = name.trim() != prefs.userName, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Txt("What personal preferences should OpenCode consider in responses?", size = 19.sp, modifier = Modifier.padding(start = 4.dp))
                Txt("Saved as global AGENTS.md — applies to every project.", size = 14.sp, color = c.textDim, modifier = Modifier.padding(start = 4.dp))
                OcTextField(rules, { rules = it; saved = false }, singleLine = false, minLines = 6, placeholder = "Reply in Russian. Prefer small diffs. Ask before big refactors.")
                Pill(if (saved) "Saved" else "Save preferences", {
                    runCatching { com.celoext.data.Memory.saveUserRules(rules) }.onSuccess { saved = true }
                }, enabled = !saved, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// ---------- About ----------

@Composable
fun AboutScreen(nav: Nav) {
    val c = Oc.c
    val ctx = LocalContext.current
    fun open(u: String) = ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    Column(Modifier.fillMaxSize().screenBackdrop("MoreScreens5")) {
        TopBar("About", left = { IconBtn(Ic.Back, { nav.pop() }) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 32.dp)) {
            SettingsGroup {
                SettingsRow("OpenCode mobile", subtitle = "v${BuildConfig.VERSION_NAME}", icon = Ic.Info)
                if (com.celoext.ui.theme.Oc.look.id == "cloud") SettingsRow("CeloAgents", subtitle = Graph.prefs.petCount.let { "Petted $it ${if (it == "1") "time" else "times"}" }, icon = Ic.Cloud, chevron = true, onClick = { nav.push(com.celoext.ui.Route.MascotSettings) })
                SettingsRow("Source on GitHub", subtitle = com.celoext.data.Updater.REPO, icon = Ic.GitHub, onClick = { open("https://github.com/" + com.celoext.data.Updater.REPO) })
                SettingsRow("OpenCode documentation", icon = Ic.External, onClick = { open("https://opencode.ai/docs") })
                SettingsRow("OpenCode Go console", icon = Ic.External, onClick = { open("https://opencode.ai/console") })
            }
            Spacer(Modifier.height(16.dp))
            // Updates from GitHub releases
            val upd by com.celoext.data.Updater.state.collectAsState()
            SettingsGroup {
                SettingsRow(
                    "Check for updates", icon = Ic.Download, chevron = true,
                    subtitle = when (val u = upd) {
                        is com.celoext.data.Updater.State.Checking -> "Checking GitHub…"
                        is com.celoext.data.Updater.State.UpToDate -> "You're on the newest version"
                        is com.celoext.data.Updater.State.Available -> "${u.r.name} is out — tap to install"
                        is com.celoext.data.Updater.State.Downloading -> "Downloading ${(u.progress * 100).toInt()}%"
                        is com.celoext.data.Updater.State.Installing -> "Installing…"
                        is com.celoext.data.Updater.State.Failed -> u.message.lines().first().take(60)
                        else -> "From releases of ${com.celoext.data.Updater.REPO}"
                    },
                    onClick = {
                        if (upd !is com.celoext.data.Updater.State.Available) com.celoext.data.Updater.check()
                        com.celoext.ui.components.UpdateUi.open.value = true
                    },
                )
                SettingsRow("Check automatically", subtitle = "Looks for a new version when the app starts", icon = Ic.Refresh,
                    trailing = { OcSwitch(Graph.prefs.updateAuto) { Graph.prefs.updateAuto = it } })
            }
            Txt(
                "Development By Mahiro | HiraTeam",
                Modifier.fillMaxWidth().padding(top = 24.dp), size = 14.sp, color = c.textDim, weight = FontWeight.Medium, align = TextAlign.Center,
            )
            Txt(
                "Unofficial client. Bundles open-source components from the Termux packages (GPL-2.0 / LGPL-3.0) and downloads a Linux base image and OpenCode (MIT) on first run.",
                size = 13.sp, color = c.textDim, modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp), lineHeight = 19.sp, align = TextAlign.Center,
            )
        }
    }
}
