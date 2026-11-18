package com.celoext.ui.screens

import com.celoext.ui.theme.screenBackdrop
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celoext.Graph
import com.celoext.api.AllProvider
import com.celoext.api.AuthMethod
import com.celoext.api.str
import com.celoext.ui.Nav
import com.celoext.ui.components.Chip
import com.celoext.ui.components.IconBtn
import com.celoext.ui.components.OcDialog
import com.celoext.ui.components.OcSheet
import com.celoext.ui.components.OcTextField
import com.celoext.ui.components.Pill
import com.celoext.ui.components.SectionLabel
import com.celoext.ui.components.SettingsGroup
import com.celoext.ui.components.SettingsRow
import com.celoext.ui.components.SmallButton
import com.celoext.ui.components.Spinner
import com.celoext.ui.components.TopBar
import com.celoext.ui.components.Txt
import com.celoext.ui.theme.Ic
import com.celoext.ui.theme.Oc
import kotlinx.coroutines.launch

private val popular = listOf("opencode-go", "opencode", "anthropic", "openai", "github-copilot", "google", "openrouter", "xai", "deepseek", "groq", "mistral", "zai", "moonshotai")

@Composable
fun ProvidersScreen(nav: Nav) {
    val c = Oc.c
    val scope = rememberCoroutineScope()
    val connected by Graph.catalog.providers.collectAsState()
    var all by remember { mutableStateOf<List<AllProvider>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var connect by remember { mutableStateOf<AllProvider?>(null) }
    var disconnect by remember { mutableStateOf<String?>(null) }
    var kamihub by remember { mutableStateOf(false) }
    var ninerouter by remember { mutableStateOf(false) }

    suspend fun load() { all = runCatching { Graph.api.allProviders().all }.getOrDefault(emptyList()); loading = false }
    LaunchedEffect(Unit) { load() }

    val connectedIds = connected.map { it.id }.toSet()
    val kami = com.celoext.data.CustomProviders.KAMIHUB
    val nr = com.celoext.data.CustomProviders.NINEROUTER(Graph.ninerouter.port)
    val showKamihub = kami.id !in connectedIds && (query.isBlank() || kami.name.contains(query, true) || kami.id.contains(query, true))
    val showNineRouter = nr.id !in connectedIds && (query.isBlank() || nr.name.contains(query, true) || nr.id.contains(query, true))
    // Kamihub and 9Router have their own rows (they need a key and register their models), so they are not listed twice
    val available = all.filter { it.id !in connectedIds && it.id != kami.id && it.id != nr.id && (query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true)) }
        .sortedWith(compareBy({ popular.indexOf(it.id).let { i -> if (i < 0) 999 else i } }, { it.name.lowercase() }))

    Column(Modifier.fillMaxSize().screenBackdrop("ProvidersScreen1")) {
        TopBar("Providers", left = { IconBtn(Ic.Back, { nav.pop() }) })
        LazyColumn(Modifier.weight(1f).navigationBarsPadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)) {
            item {
                Box(Modifier.padding(top = 4.dp)) {
                    SettingsGroup {
                        SettingsRow("Free models", subtitle = "Copilot, or no account at all", icon = Ic.Sparkle, chevron = true, onClick = { nav.push(com.celoext.ui.Route.Login) })
                    }
                }
            }
            item { SectionLabel("Connected") }
            item {
                if (connected.isEmpty()) Txt("Nothing connected yet", Modifier.padding(horizontal = 20.dp), size = 16.sp, color = c.textDim)
                else SettingsGroup {
                    connected.forEach { p ->
                        SettingsRow(p.name.ifBlank { p.id }, subtitle = "${p.models.size} models", icon = Ic.Key, trailing = {
                            if (p.id == "opencode-go") Chip("Go", color = c.bg, bg = c.text)
                            SmallButton("Disconnect", { disconnect = p.id }, danger = true)
                        })
                    }
                }
            }
            item {
                SectionLabel("Add provider")
                Box(Modifier.padding(horizontal = 16.dp)) { OcTextField(query, { query = it }, placeholder = "Search providers") }
                Spacer(Modifier.height(12.dp))
            }
            if (loading) item { Box(Modifier.fillMaxWidth().padding(24.dp)) { Spinner(26.dp) } }
            item {
                SettingsGroup {
                    if (showKamihub) SettingsRow(kami.name, subtitle = "kamihub.us.ci · OpenAI-compatible", icon = Ic.Plus, chevron = true, onClick = { kamihub = true })
                    if (showNineRouter) SettingsRow(nr.name, subtitle = "localhost:${Graph.ninerouter.port} · this phone", icon = Ic.Router, chevron = true, onClick = { ninerouter = true })
                    available.take(60).forEach { p ->
                        SettingsRow(p.name.ifBlank { p.id }, subtitle = p.id, icon = Ic.Plus, chevron = true, onClick = { connect = p })
                    }
                }
            }
        }
    }

    connect?.let { p ->
        ConnectSheet(p, onDone = { connect = null; scope.launch { load() } })
    }
    if (kamihub) KamihubSheet(onDone = { kamihub = false; scope.launch { load() } })
    if (ninerouter) NineRouterSheet(nav, onDone = { ninerouter = false; scope.launch { load() } })
    disconnect?.let { id ->
        OcDialog("Disconnect $id?", { disconnect = null }, "Disconnect", {
            disconnect = null
            scope.launch {
                if (id == com.celoext.data.FreeModels.COPILOT_ID || id == com.celoext.data.FreeModels.POLLINATIONS_ID) com.celoext.data.FreeModels.disable(id)
                else runCatching { Graph.api.removeAuth(id); Graph.api.disposeAll() }
                Graph.catalog.refresh(); load()
            }
        }, danger = true) { Txt("Stored credentials for this provider are removed from the phone.", size = 16.sp, color = Oc.c.textDim) }
    }
}

@Composable
private fun ConnectSheet(p: AllProvider, onDone: () -> Unit) {
    val c = Oc.c
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var methods by remember { mutableStateOf<List<AuthMethod>?>(null) }
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var oauthIndex by remember { mutableStateOf<Int?>(null) }
    var oauthHint by remember { mutableStateOf<String?>(null) }
    var oauthMode by remember { mutableStateOf("code") }
    var code by remember { mutableStateOf("") }

    LaunchedEffect(p.id) {
        methods = runCatching { Graph.api.providerAuthMethods()[p.id] }.getOrNull().orEmpty().ifEmpty { listOf(AuthMethod("key", "API key")) }
    }

    fun finish() { scope.launch { runCatching { Graph.api.disposeAll() }; Graph.catalog.refresh(); Graph.chats.toasts.tryEmit("${p.name} connected"); onDone() } }

    OcSheet(onDone, p.name.ifBlank { p.id }, skipHalf = false) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (methods == null) Box(Modifier.fillMaxWidth().padding(24.dp)) { Spinner(26.dp) }
            methods?.forEachIndexed { i, m ->
                if (m.type == "key" || m.type == "api") {
                    Txt(m.label.ifBlank { "API key" }, size = 16.sp, color = c.textDim)
                    OcTextField(key, { key = it }, placeholder = "Paste key", password = true, mono = true)
                    if (p.id == "opencode-go" || p.id == "opencode") {
                        Txt("Get a key at opencode.ai/console (Go and Zen use the same console).", size = 14.sp, color = c.textDim)
                        SmallButton("Open console", { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://opencode.ai/console")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
                    }
                    Pill(if (busy) "Connecting…" else "Connect", {
                        busy = true; error = null
                        scope.launch {
                            runCatching { Graph.api.setApiKey(p.id, key.trim()) }.onSuccess { finish() }.onFailure { error = it.message }
                            busy = false
                        }
                    }, enabled = key.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth())
                } else {
                    Pill(m.label.ifBlank { "Sign in" }, {
                        busy = true; error = null
                        scope.launch {
                            runCatching { Graph.api.oauthAuthorize(p.id, i) }.onSuccess { r ->
                                oauthIndex = i
                                oauthMode = r.str("method") ?: "code"
                                oauthHint = r.str("instructions")
                                r.str("url")?.let { u -> ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                if (oauthMode == "auto") {
                                    runCatching { Graph.api.oauthCallback(p.id, i, null) }.onSuccess { finish() }.onFailure { error = it.message }
                                }
                            }.onFailure { error = it.message }
                            busy = false
                        }
                    }, filled = false, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                }
            }
            oauthHint?.let { Txt(it, size = 15.sp, color = c.textDim) }
            if (oauthIndex != null && oauthMode == "code") {
                OcTextField(code, { code = it }, placeholder = "Paste the code from the browser", mono = true)
                Pill("Complete sign-in", {
                    busy = true
                    scope.launch {
                        runCatching { Graph.api.oauthCallback(p.id, oauthIndex!!, code.trim()) }.onSuccess { finish() }.onFailure { error = it.message }
                        busy = false
                    }
                }, enabled = code.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth())
            }
            error?.let { Txt(it, size = 15.sp, color = c.danger) }
        }
    }
}

@Composable
private fun KamihubSheet(onDone: () -> Unit) {
    val c = Oc.c
    val scope = rememberCoroutineScope()
    val p = com.celoext.data.CustomProviders.KAMIHUB
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    OcSheet(onDone, p.name, skipHalf = false) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Txt("OpenAI-compatible gateway at ${p.baseUrl}. The key is checked with a test message, then the model list is fetched.", size = 14.sp, color = c.textDim)
            Txt("API key", size = 16.sp, color = c.textDim)
            OcTextField(key, { key = it }, placeholder = "Paste key", password = true, mono = true)
            Pill(if (busy) "Testing the key…" else "Connect", {
                busy = true; error = null
                scope.launch {
                    runCatching { com.celoext.data.CustomProviders.connect(p, key) }
                        .onSuccess { r ->
                            Graph.chats.toasts.tryEmit(
                                if (r.warning == null) "${p.name} connected · ${r.model.substringAfterLast('/')}"
                                else "${p.name} added, but ${r.warning}",
                            )
                            onDone()
                        }
                        .onFailure { error = it.message ?: "Could not connect" }
                    busy = false
                }
            }, enabled = key.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth())
            error?.let { Txt(it, size = 15.sp, color = c.danger) }
        }
    }
}

@Composable
private fun NineRouterSheet(nav: Nav, onDone: () -> Unit) {
    val c = Oc.c
    val scope = rememberCoroutineScope()
    val nr = Graph.ninerouter
    val st by nr.state.collectAsState()
    val p = com.celoext.data.CustomProviders.NINEROUTER(nr.port)
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    OcSheet(onDone, p.name, skipHalf = false) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Txt(
                "Local gateway at ${p.baseUrl}. Get an API key from its dashboard (Endpoint → API keys); the key is checked with a test message, then the model list is fetched.",
                size = 14.sp, color = c.textDim, lineHeight = 20.sp,
            )
            when {
                st is com.celoext.runtime.NineRouterState.Running -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmallButton("Open dashboard", { nav.push(com.celoext.ui.Route.Preview(nr.dashboardUrl, null)) }, filled = true)
                }
                st is com.celoext.runtime.NineRouterState.Installing -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Txt((st as com.celoext.runtime.NineRouterState.Installing).step + "…", size = 15.sp, color = c.warn)
                }
                st is com.celoext.runtime.NineRouterState.Starting -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Txt("Starting 9Router…", size = 15.sp, color = c.warn)
                }
                !nr.installed() -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Txt("The 9Router server isn't installed yet.", size = 15.sp, color = c.warn)
                    SmallButton("Install", {
                        scope.launch {
                            val ok = nr.install()
                            if (ok) nr.startServer()
                        }
                    }, filled = true)
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Txt(
                        (st as? com.celoext.runtime.NineRouterState.Crashed)?.message ?: "The 9Router server is not running.",
                        size = 15.sp, color = c.warn,
                    )
                    SmallButton("Start", { nr.startServer() }, filled = true)
                }
            }
            Txt("API key", size = 16.sp, color = c.textDim)
            OcTextField(key, { key = it }, placeholder = "Paste key", password = true, mono = true)
            Pill(if (busy) "Testing the key…" else "Connect", {
                busy = true; error = null
                scope.launch {
                    runCatching { com.celoext.data.CustomProviders.connect(p, key) }
                        .onSuccess { r ->
                            Graph.chats.toasts.tryEmit(
                                if (r.warning == null) "${p.name} connected · ${r.model.substringAfterLast('/')}"
                                else "${p.name} added, but ${r.warning}",
                            )
                            onDone()
                        }
                        .onFailure { error = it.message ?: "Could not connect" }
                    busy = false
                }
            }, enabled = key.isNotBlank() && !busy && st is com.celoext.runtime.NineRouterState.Running, modifier = Modifier.fillMaxWidth())
            error?.let { Txt(it, size = 15.sp, color = c.danger) }
        }
    }
}
