package com.celoext.ui.screens

import com.celoext.ui.theme.kimiLike
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.celoext.data.CHAT_DIR
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celoext.Graph
import com.celoext.ui.Nav
import com.celoext.ui.Route
import com.celoext.ui.components.ComposerHost
import com.celoext.ui.components.LiveTaskPill
import com.celoext.ui.components.Ico
import com.celoext.ui.components.IconBtn
import com.celoext.ui.components.TopBar
import com.celoext.ui.components.Txt
import com.celoext.ui.components.rememberComposerState
import com.celoext.ui.theme.Ic
import com.celoext.ui.theme.Oc
import com.celoext.ui.theme.lookBackdrop
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(nav: Nav, onMenu: () -> Unit, code: Boolean = false) {
    val c = Oc.c
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberComposerState()
    var sending by remember { mutableStateOf(false) }
    var voiceHome by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var ghost by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { shown = true }
    val activeDevice by Graph.devices.activeId.collectAsState()
    val connected by Graph.events.connected.collectAsState()
    var dirRev by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(activeDevice, connected) {
        if (activeDevice.isNotEmpty() && connected && Graph.prefs.currentDir.isBlank()) {
            runCatching { Graph.api.serverDirectory() }.getOrNull()?.let { Graph.prefs.currentDir = it; dirRev++ }
        }
    }
    val dirName = remember(dirRev, picker) { Graph.prefs.currentDir.trimEnd('/').substringAfterLast('/').ifBlank { "/" } }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val screenW = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()

    // send animation: the text from the input "flies" to the bubble's place in the chat, the rest fades
    var flightText by remember { mutableStateOf<String?>(null) }
    var composerRect by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val prog = remember { androidx.compose.animation.core.Animatable(0f) }

    // "Share → Ask OpenCode": text and files go straight into the input
    // only the screen that's visible now takes it: "Share" may open a second instance on top of the old one
    val life = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.LaunchedEffect(life) {
        life.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) { com.celoext.data.ShareInbox.pending.collect { sh ->
            if (sh == null) return@collect
            state.text = if (state.text.isBlank()) sh.text else state.text.trimEnd() + "\n" + sh.text
            sh.uris.forEach { u -> state.attachments.add(com.celoext.data.Attachments.describe(ctx, u)) }
            com.celoext.data.ShareInbox.pending.value = null
        } }
    }
    Box(Modifier.fillMaxSize().lookBackdrop(home = true)) {
    Column(Modifier.fillMaxSize().imePadding()) {
        Box(Modifier.graphicsLayer { alpha = 1f - prog.value }) {
            TopBar(
                left = { Row(verticalAlignment = Alignment.CenterVertically) { IconBtn(Ic.Menu, onMenu); com.celoext.ui.components.TopModelPill(state) } },
                right = {
                    com.celoext.ui.components.UsageRing(null, nav)
                    if (Oc.look.id == "cloud") com.celoext.ui.components.GhostCloudButton(ghost) { ghost = !ghost }
                    else IconBtn(Ic.Ghost, { ghost = !ghost }, tint = if (ghost) c.text else c.textDim)
                    if (code) Row(
                        Modifier.padding(end = 8.dp).clip(CircleShape).clickable { picker = true }.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Ico(Ic.Folder, size = 20.dp); Spacer(Modifier.width(8.dp)); Txt(dirName, size = 16.sp, maxLines = 1)
                    }
                },
            )
        }
        com.celoext.ui.components.InterruptedBanner { nav.openChat(it) }
        com.celoext.ui.components.AccessBanner()
        Column(Modifier.weight(1f).fillMaxWidth().graphicsLayer { alpha = 1f - prog.value * 1.6f; translationY = -prog.value * 40f }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            androidx.compose.animation.AnimatedContent(ghost, label = "home-mode", transitionSpec = { fadeIn() togetherWith fadeOut() }) { g ->
                androidx.compose.animation.AnimatedVisibility(
                    shown, enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(240)) +
                        androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(240)) { it / 8 },
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (g) {
                            if (Oc.look.id == "cloud") {
                                // anonymous chat: a ghost cloud, nothing is saved
                                com.celoext.ui.components.GhostCloud(112.dp, eyesLook = state.text.isNotEmpty())
                                Spacer(Modifier.height(26.dp))
                                Txt("Anonymous chat", size = 24.sp, weight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                Spacer(Modifier.height(10.dp))
                                Txt("Nothing is kept: it stays out of your history and memory, and it's deleted as soon as you leave.", Modifier.padding(horizontal = 40.dp), size = 16.sp, color = c.textDim, align = TextAlign.Center, lineHeight = 23.sp)
                            } else {
                            Ico(Ic.Ghost, tint = c.textDim, size = 56.dp)
                            Spacer(Modifier.height(22.dp))
                            Txt("Incognito chats stay out of your recents and are deleted when you leave them.", Modifier.padding(horizontal = 40.dp), size = 18.sp, color = c.textDim, align = TextAlign.Center)
                            }
                        } else {
                            if (Oc.look.id.kimiLike) {
                                com.celoext.ui.components.KimiHero(code, typing = state.text.isNotEmpty()) { nav.push(com.celoext.ui.Route.Providers) }
                            } else if (Oc.look.id in lookHomes) {
                                LookHome(Oc.look.id, code) { state.text = it }
                            } else if (Oc.desktop) {
                                // desktop: a dim label without captions, project chips under the input
                                com.celoext.ui.components.PixelWordmark(Modifier.padding(horizontal = 20.dp).graphicsLayer { alpha = 0.32f })
                            } else {
                                DefaultHome(nav, code) { state.text = it }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(80.dp))
        }
        Column(
            Modifier.navigationBarsPadding().onGloballyPositioned { composerRect = it.boundsInRoot() }
                .graphicsLayer { alpha = 1f - prog.value * 1.4f; translationY = prog.value * 90f },
        ) {
            if (Oc.look.id.kimiLike && !ghost && !state.text.startsWith("/")) com.celoext.ui.components.KimiQuickChips(nav, code) { state.text = it }
            LiveTaskPill(nav, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            ComposerHost(
                state = state, nav = nav, busy = sending, dashed = ghost,
                placeholder = if (Oc.look.id.kimiLike) "Ask me. Give me a task." else if (code) "Describe a task…" else "Chat with OpenCode…",
                onStop = {},
                onVoice = if (code || ghost) null else ({ voiceHome = true }),
                onSend = {
                    val text = state.text.trim()
                    val atts = state.attachments.toList()
                    state.clear(); sending = true; flightText = text
                    scope.launch {
                        try {
                            val dir = if (code || Graph.devices.isRemote) Graph.prefs.currentDir else CHAT_DIR
                            if (!Graph.devices.isRemote) Graph.paths.host(dir).mkdirs()
                            val flight = launch { prog.animateTo(1f, androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
                            val s = Graph.chats.createSession(dir)
                            if (ghost) Graph.chats.markIncognito(s.id)
                            Graph.chats.send(ctx, s.id, text, atts)
                            flight.join()
                            nav.openChatInstant(s.id)
                        } catch (e: Exception) {
                            Graph.chats.toasts.tryEmit(e.message ?: "Could not start the chat")
                            state.text = text; state.attachments.addAll(atts)
                            flightText = null; prog.snapTo(0f)
                        } finally { sending = false }
                    }
                },
            )
            if (Oc.desktop) DesktopChips(code, dirName, onProject = { picker = true }, onMemory = { nav.push(Route.Memory) })
        }
    }
    if (voiceHome) VoiceMode(null) { sid -> voiceHome = false; sid?.let { nav.openChatInstant(it) } }
    flightText?.let { text ->
        // bubble position and size: from the input to the top right corner, where it will be in the chat
        val bubbleMaxW = screenW * 0.88f
        val maxTextPx = with(density) { (bubbleMaxW - 36.dp).roundToPx() }
        val bodyFont = Oc.body
        val layout = remember(text, maxTextPx) {
            measurer.measure(text, androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontFamily = bodyFont), constraints = androidx.compose.ui.unit.Constraints(maxWidth = maxTextPx))
        }
        val toW = if (Oc.terminal) screenW - 32.dp else with(density) { layout.size.width.toDp() } + 36.dp
        val toH = with(density) { layout.size.height.toDp() } + 24.dp
        val e = prog.value
        val fromW = with(density) { composerRect.width.toDp() }
        val fromH = 64.dp
        val fromX = with(density) { composerRect.left.toDp() }
        val fromY = with(density) { composerRect.top.toDp() }
        val toX = if (Oc.terminal) 16.dp else screenW - 16.dp - toW
        val toY = 168.dp
        fun lerpDp(a: androidx.compose.ui.unit.Dp, b: androidx.compose.ui.unit.Dp) = a + (b - a) * e
        Box(
            Modifier.offset(lerpDp(fromX, toX), lerpDp(fromY, toY)).size(lerpDp(fromW, toW), lerpDp(fromH, toH))
                .clip(Oc.round(lerpDp(32.dp, 22.dp)))
                .background(androidx.compose.ui.graphics.lerp(c.surface, c.userBubble, e)),
        ) {
            Text(
                text, color = c.text, fontSize = 16.sp, lineHeight = 23.sp, fontFamily = Oc.body,
                modifier = Modifier.padding(horizontal = lerpDp(24.dp, 18.dp), vertical = lerpDp(18.dp, 12.dp)),
            )
        }
    }
    }
    if (picker) ProjectPicker({ picker = false })
}

/** The line under the input in the desktop look: project and Git (or memory for a regular chat). */
@Composable
private fun DesktopChips(code: Boolean, dirName: String, onProject: () -> Unit, onMemory: () -> Unit) {
    val c = Oc.c
    val git = remember(dirName) {
        !Graph.devices.isRemote && runCatching { java.io.File(Graph.paths.host(Graph.prefs.currentDir), ".git").exists() }.getOrDefault(false)
    }
    @Composable fun chip(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, filled: Boolean, onClick: (() -> Unit)?) {
        Row(
            Modifier.clip(Oc.pill).then(if (filled) Modifier.background(c.surfaceHigh) else Modifier)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Ico(icon, tint = c.textDim, size = 15.dp); Spacer(Modifier.width(6.dp)); Txt(text, size = 13.sp, color = c.textDim, maxLines = 1)
        }
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (code) {
            chip(dirName, Ic.Folder, false, onProject)
            Spacer(Modifier.width(8.dp))
            chip(if (git) "Git" else "No Git", Ic.Code, true, null)
        } else {
            chip("Chat", Ic.Chat, false, null)
            Spacer(Modifier.width(8.dp))
            chip(if (Graph.prefs.memoryEnabled) "Memory on" else "Memory off", Ic.Server, true, onMemory)
        }
    }
}
