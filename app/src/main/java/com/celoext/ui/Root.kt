package com.celoext.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.celoext.Graph
import com.celoext.runtime.RuntimeState
import com.celoext.ui.components.LiveTaskPill
import com.celoext.ui.components.Spinner
import com.celoext.ui.components.Txt
import com.celoext.ui.screens.CapabilitiesScreen
import com.celoext.ui.screens.OcSettingsScreen
import com.celoext.ui.screens.AppearanceScreen
import com.celoext.ui.screens.AboutScreen
import com.celoext.ui.screens.AgentsScreen
import com.celoext.ui.screens.EditionScreen
import com.celoext.ui.screens.ChatScreen
import com.celoext.ui.screens.ConfigEditorScreen
import com.celoext.ui.screens.ArtifactsScreen
import com.celoext.ui.screens.ConnectScreen
import com.celoext.ui.screens.MemoryScreen
import com.celoext.ui.screens.ConsoleLoginScreen
import com.celoext.ui.screens.LoginScreen
import com.celoext.ui.screens.CodeScreen
import com.celoext.ui.screens.ScheduledScreen
import com.celoext.ui.screens.ConnectorsScreen
import com.celoext.ui.screens.DrawerContent
import com.celoext.ui.screens.HomeScreen
import com.celoext.ui.screens.ModelsScreen
import com.celoext.ui.screens.PermissionsScreen
import com.celoext.ui.screens.ProfileScreen
import com.celoext.ui.screens.ProjectsScreen
import com.celoext.ui.screens.ProvidersScreen
import com.celoext.ui.screens.RecentsScreen
import com.celoext.ui.screens.ServerScreen
import com.celoext.ui.screens.NineRouterScreen
import com.celoext.ui.screens.SettingsScreen
import com.celoext.ui.screens.SetupScreen
import com.celoext.ui.screens.SkillsScreen
import com.celoext.ui.screens.UsageScreen
import com.celoext.ui.theme.Oc
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun OcRoot() {
    val c = Oc.c
    val view = LocalView.current
    SideEffect {
        val w = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(w, view).apply {
            isAppearanceLightStatusBars = !c.isDark
            isAppearanceLightNavigationBars = !c.isDark
        }
    }
    val rt by Graph.runtime.state.collectAsState()
    Box(Modifier.fillMaxSize().background(c.bg)) {
        if (rt is RuntimeState.NotInstalled || rt is RuntimeState.Installing || rt is RuntimeState.InstallFailed) SetupScreen(rt)
        else { Shell(rt); com.celoext.ui.components.UpdateHost() }
        // the welcome tour sits on top of the app and takes all touches: nothing underneath can be pressed
        if (!Graph.prefs.onboarded) Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
            com.celoext.ui.screens.Onboarding()
        }
        com.celoext.ui.components.IntroHost()
        ToastHost()
    }
}

@Composable
private fun Shell(rt: RuntimeState) {
    val c = Oc.c
    val nav = remember { com.celoext.ui.NavMemory.nav }
    val openReq by Graph.chats.openRequest.collectAsState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    LaunchedEffect(openReq) {
        openReq?.let { r ->
            when (r) {
                "route:settings" -> { drawer.close(); nav.root(Route.Settings) }
                "route:home" -> { drawer.close(); nav.root(Route.Home) }
                "route:about" -> { drawer.close(); nav.root(Route.Settings); nav.push(Route.About) }
                "route:memory" -> { drawer.close(); nav.root(Route.Memory) }
                "route:drawer" -> drawer.open()
                "route:onboarding" -> Graph.prefs.onboarded = false
                "route:onboarded" -> Graph.prefs.onboarded = true
                "route:github" -> { drawer.close(); nav.root(Route.Settings); nav.push(Route.GitHub) }
                "route:backup" -> { drawer.close(); nav.root(Route.Settings); nav.push(Route.Backup) }
                "route:skills" -> { drawer.close(); nav.root(Route.Settings); nav.push(Route.Skills) }
                "route:phone" -> { drawer.close(); nav.root(Route.Settings); nav.push(Route.PhoneTools) }
                "route:intro" -> com.celoext.ui.components.Intro.replay.intValue++
                else -> if (r.startsWith("route:connect:")) { drawer.close(); nav.push(Route.Connect(r.removePrefix("route:connect:"))) }
                    else if (r.startsWith("route:console:")) { drawer.close(); nav.push(Route.ConsoleLogin(r.removePrefix("route:console:"))) }
                    else if (r.startsWith("route:preview:")) { drawer.close(); nav.push(Route.Preview(r.removePrefix("route:preview:"), (nav.current as? Route.Chat)?.id)) }
                    else if (!r.startsWith("route:")) nav.openChat(r)
            }
            Graph.chats.openRequest.value = null
        }
    }
    val open = { scope.launch { drawer.open() }; Unit }

    // The server starts by itself when the app opens.
    var autostarted by remember { mutableStateOf(false) }
    LaunchedEffect(rt) {
        if (!autostarted && rt is RuntimeState.Stopped && Graph.prefs.autoStartServer) { autostarted = true; Graph.runtime.startServer() }
    }
    // 9Router starts with the app when the user asked for it (it runs in the same environment).
    val nrSt by Graph.ninerouter.state.collectAsState()
    var nrAutostarted by remember { mutableStateOf(false) }
    LaunchedEffect(nrSt) {
        // NotInstalled is its own state, so Stopped here means it is installed but not running.
        if (!nrAutostarted && nrSt is com.celoext.runtime.NineRouterState.Stopped && Graph.prefs.ninerouterAuto) {
            nrAutostarted = true
            Graph.ninerouter.startServer()
        }
    }
    LaunchedEffect(rt is RuntimeState.Running) {
        if (rt is RuntimeState.Running) { Graph.catalog.refresh(); Graph.chats.refreshSessions() }
    }
    // First launch after the environment is installed: offer to sign in (can be skipped).
    LaunchedEffect(Unit) {
        if (Graph.prefs.onboarded && !Graph.prefs.loginSeen && Graph.prefs.signedIn.isBlank()) { kotlinx.coroutines.delay(500); nav.push(Route.Login) }
    }

    BackHandler(drawer.isOpen || nav.stack.size > 1 || nav.current != Route.Home) {
        if (drawer.isOpen) scope.launch { drawer.close() } else nav.pop()
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        // while a finger is on the mascot, the drawer doesn't open by swipe: it's being petted and pulled
        gesturesEnabled = drawer.isOpen || !com.celoext.ui.components.MascotGrab.held,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = c.bg, drawerShape = RectangleShape, windowInsets = WindowInsets(0), modifier = Modifier.fillMaxWidth(0.94f)) {
                DrawerContent(nav) { scope.launch { drawer.close() } }
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = nav.current,
                transitionSpec = {
                    when {
                        nav.direction == 2 -> fadeIn(tween(120)) togetherWith fadeOut(tween(120))
                        // fast, like in Claude: the screen slides in within ~200 ms with a sharp start and a soft stop
                        nav.direction > 0 -> (slideInHorizontally(tween(210, easing = androidx.compose.animation.core.LinearOutSlowInEasing)) { it / 5 } + fadeIn(tween(150))) togetherWith
                            (slideOutHorizontally(tween(210)) { -it / 12 } + fadeOut(tween(110)))
                        nav.direction < 0 -> (slideInHorizontally(tween(200, easing = androidx.compose.animation.core.LinearOutSlowInEasing)) { -it / 12 } + fadeIn(tween(150))) togetherWith
                            (slideOutHorizontally(tween(200)) { it / 5 } + fadeOut(tween(110)))
                        else -> fadeIn(tween(140)) togetherWith fadeOut(tween(90))
                    }
                },
                label = "nav",
            ) { r ->
                Box(Modifier.fillMaxSize().background(c.bg)) {
                    when (r) {
                        Route.Home -> HomeScreen(nav, open)
                        Route.NewCode -> HomeScreen(nav, open, code = true)
                        Route.Memory -> MemoryScreen(nav)
                        Route.Edition -> EditionScreen(nav)
                        is Route.Chat -> ChatScreen(r.id, nav, open)
                        Route.Login -> LoginScreen(nav)
                        is Route.ConsoleLogin -> ConsoleLoginScreen(r.method, nav)
                        is Route.Connect -> ConnectScreen(r.providerId, nav)
                        Route.Code -> CodeScreen(nav, open)
                        Route.Artifacts -> ArtifactsScreen(nav, open)
                        Route.Scheduled -> ScheduledScreen(nav, open)
                        Route.Recents -> RecentsScreen(nav, open)
                        Route.Projects -> ProjectsScreen(nav, open)
                        Route.Settings -> SettingsScreen(nav, open)
                        Route.Usage -> UsageScreen(nav)
                        Route.Profile -> ProfileScreen(nav)
                        Route.Providers -> ProvidersScreen(nav)
                        Route.Models -> ModelsScreen(nav)
                        Route.PermissionsCfg -> PermissionsScreen(nav)
                        Route.Connectors -> ConnectorsScreen(nav)
                        Route.Agents -> AgentsScreen(nav)
                        Route.Skills -> SkillsScreen(nav)
                        is Route.SkillEdit -> com.celoext.ui.screens.SkillEditScreen(r.name, nav)
                        Route.Server -> ServerScreen(nav)
                        Route.NineRouter -> NineRouterScreen(nav)
                        Route.ConfigEditor -> ConfigEditorScreen(nav)
                        Route.About -> AboutScreen(nav)
                        Route.Capabilities -> CapabilitiesScreen(nav)
                        Route.OcSettings -> OcSettingsScreen(nav)
                        Route.Appearance -> AppearanceScreen(nav)
                        Route.PhoneTools -> com.celoext.ui.screens.PhoneToolsScreen(nav)
                        Route.GitHub -> com.celoext.ui.screens.GitHubScreen(nav)
                        Route.Backup -> com.celoext.ui.screens.BackupScreen(nav)
                        Route.Team -> com.celoext.ui.screens.TeamScreen(nav)
                        Route.Mascot -> com.celoext.ui.screens.MascotRoom(nav)
                        Route.MascotSettings -> com.celoext.ui.screens.MascotSettings(nav)
                        is Route.Preview -> com.celoext.ui.screens.PreviewScreen(r.url, r.sessionId, nav)
                    }
                }
            }
            val cur = nav.current
            if (cur == Route.Home || cur == Route.NewCode || cur == Route.Code || cur is Route.Chat) ServerBanner(rt)
            // Home and Chat place the pill above their composer; everywhere else it floats by the bottom edge.
            // Code, Preview and Connectors keep their primary action at the bottom end, so the pill sits above it.
            if (cur != Route.Home && cur != Route.NewCode && cur !is Route.Chat) {
                val lift = if (cur == Route.Code || cur == Route.Connectors || cur is Route.Preview) 84.dp else 16.dp
                LiveTaskPill(
                    nav,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = lift),
                )
            }
        }
    }
    ConnectPrompt(nav)
}

/** A QR code or a celoagents://connect link: ask whether to connect, then connect. */
@Composable
private fun ConnectPrompt(nav: Nav) {
    val c = Oc.c
    val o = com.celoext.data.ConnectLink.pending ?: return
    val scope = rememberCoroutineScope()
    var busy by remember(o) { mutableStateOf(false) }
    var error by remember(o) { mutableStateOf<String?>(null) }
    com.celoext.ui.components.OcDialog(
        "Connect to ${o.name.ifBlank { "this computer" }}?",
        onDismiss = { if (!busy) com.celoext.data.ConnectLink.pending = null },
        confirmText = if (busy) "Connecting…" else "Connect",
        onConfirm = {
            if (busy) return@OcDialog
            busy = true; error = null
            scope.launch {
                try {
                    com.celoext.data.ConnectLink.accept(o)
                    com.celoext.data.ConnectLink.pending = null
                    nav.root(Route.Code)
                    Graph.chats.toasts.tryEmit("Connected to ${o.name.ifBlank { "your computer" }}")
                } catch (e: Exception) {
                    error = e.message?.takeIf { it.isNotBlank() } ?: "Can't reach the computer"
                } finally { busy = false }
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt("OpenCode on it will work from this phone: chats, code and files.", size = 15.sp, color = c.textDim, lineHeight = 21.sp)
            Txt(o.url, size = 13.sp, family = Oc.mono, color = c.textDim)
            error?.let { Txt(it, size = 14.sp, color = c.danger) }
        }
    }
}

@Composable
private fun ServerBanner(rt: RuntimeState) {
    val c = Oc.c
    val remoteId by Graph.devices.activeId.collectAsState()
    val connected by Graph.events.connected.collectAsState()
    // For a remote device the local server's state doesn't matter; what matters is whether we can reach it.
    val show = if (remoteId.isNotEmpty()) !connected else rt is RuntimeState.Starting || rt is RuntimeState.Crashed || rt is RuntimeState.Stopped
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 60.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(show) {
            Row(
                Modifier.padding(horizontal = 16.dp).clip(Oc.pill).background(c.surfaceHigh).border(1.dp, c.border, Oc.pill)
                    .clickable(enabled = remoteId.isEmpty() && rt !is RuntimeState.Starting) { Graph.runtime.startServer() }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (remoteId.isNotEmpty()) Txt("Can't reach ${Graph.devices.active()?.name ?: "device"}…", size = 14.sp, color = c.warn) else when (rt) {
                    is RuntimeState.Starting -> { Spinner(16.dp, stroke = 2.dp); Spacer(Modifier.width(10.dp)); Txt("Starting local server…", size = 14.sp) }
                    is RuntimeState.Crashed -> Txt("Server crashed — tap to restart", size = 14.sp, color = c.danger)
                    else -> Txt("Server stopped — tap to start", size = 14.sp, color = c.textDim)
                }
            }
        }
    }
}

@Composable
private fun ToastHost() {
    val c = Oc.c
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        Graph.chats.toasts.collect { msg = it; delay(3500); msg = null }
    }
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 120.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(msg != null) {
            Box(Modifier.padding(horizontal = 24.dp).clip(Oc.round(20.dp)).background(c.text).padding(horizontal = 18.dp, vertical = 12.dp)) {
                Txt(msg.orEmpty(), size = 15.sp, color = c.bg, maxLines = 4)
            }
        }
    }
}

