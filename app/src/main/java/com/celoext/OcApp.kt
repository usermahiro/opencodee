package com.celoext

import android.app.Application
import com.celoext.api.EventHub
import com.celoext.api.OpenCodeApi
import com.celoext.data.Catalog
import com.celoext.data.LiveNotify
import com.celoext.data.PhoneBridge
import com.celoext.data.Devices
import com.celoext.data.ChatHub
import com.celoext.data.Memory
import com.celoext.data.Prefs
import com.celoext.data.Scheduler
import com.celoext.data.UsageTracker
import com.celoext.runtime.NineRouterController
import com.celoext.runtime.RuntimeController
import com.celoext.runtime.RuntimePaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.celoext.data.Vault

class OcApp : Application() {
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    override fun onCreate() {
        // our own toolbar over selected text (Copy / Select all / Reply / Read aloud), via LocalTextToolbar
        androidx.compose.foundation.ComposeFoundationFlags.isNewContextMenuEnabled = false
        super.onCreate()
        Graph.init(this)
    }
}

/** Single place where dependencies are wired up: the app is small, no DI framework needed. */
object Graph {
    lateinit var app: Application
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var prefs: Prefs
    lateinit var paths: RuntimePaths
    lateinit var runtime: RuntimeController
    lateinit var ninerouter: NineRouterController
    lateinit var devices: Devices
    lateinit var api: OpenCodeApi
    lateinit var events: EventHub
    lateinit var chats: ChatHub
    lateinit var usage: UsageTracker
    lateinit var catalog: Catalog

    fun init(a: Application) {
        app = a
        prefs = Prefs(a)
        paths = RuntimePaths(a)
        devices = Devices(prefs)
        api = OpenCodeApi(prefs)
        runtime = RuntimeController(a, paths, prefs, api)
        ninerouter = NineRouterController(a, paths, prefs)
        // people who used the app before the welcome screen existed don't see it again
        if (!prefs.onboarded && paths.rootfsReady && prefs.loginSeen) prefs.onboarded = true
        events = EventHub(api, runtime)
        chats = ChatHub(api, events, prefs)
        usage = UsageTracker(a, api, prefs, events)
        catalog = Catalog(api, prefs, events)
        events.start(scope)
        chats.start()
        catalog.start()
        usage.start()
        Scheduler.load(a)
        PhoneBridge.start()
        LiveNotify.start()
        // the server is up: connect the "phone" MCP to it (the config entry changes only when needed)
        scope.launch { runtime.state.collect { if (it is com.celoext.runtime.RuntimeState.Running) PhoneBridge.register() } }
        Memory.sync()
        Vault.restore()
        Vault.reconcileAccount()
        scope.launch(Dispatchers.IO) { while (true) { Vault.saveLight(); Vault.reconcileAccount(); delay(30_000) } }
    }
}
