package com.celoext.data

import com.celoext.Graph
import com.celoext.api.AgentInfo
import com.celoext.api.EventHub
import com.celoext.api.ModelInfo
import com.celoext.api.OpenCodeApi
import com.celoext.api.ProviderInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Connected providers, their models and agents: what model selection needs. */
class Catalog(private val api: OpenCodeApi, private val prefs: Prefs, private val events: EventHub) {
    val providers = MutableStateFlow<List<ProviderInfo>>(emptyList())
    val agents = MutableStateFlow<List<AgentInfo>>(emptyList())
    /** Commands and skills for the "/" menu in the input. */
    val commands = MutableStateFlow<List<com.celoext.api.NamedItem>>(emptyList())
    val loaded = MutableStateFlow(false)
    private var defaults: Map<String, String> = emptyMap()

    fun start() {
        Graph.scope.launch { events.generation.collect { if (it > 0) refresh() } }
    }

    suspend fun refresh() {
        runCatching {
            val r = api.connectedProviders()
            // Server warming up returns [] for a few seconds. Never wipe a good list with an empty one.
            if (r.providers.isNotEmpty()) {
                val kept = providers.value.associateBy { it.id }
                val merged = (r.providers + providers.value.filter { it.id !in r.providers.map { p -> p.id } })
                providers.value = merged.sortedWith(compareBy({ rank(it.id) }, { it.name }))
                defaults = r.default.ifEmpty { defaults }
                loaded.value = true
            } else if (providers.value.isEmpty()) {
                loaded.value = false
            }
        }
        runCatching { agents.value = api.agents().filter { !it.hidden && it.mode != "subagent" } }
        runCatching {
            val cmds = api.commands()
            val sks = api.skills()
            commands.value = (cmds + sks).sortedWith(compareBy({ if (it.source == "skill") 0 else 1 }, { it.name }))
        }
        ensureSelection()
    }

    private fun rank(id: String) = when (id) { "opencode-go" -> 0; "opencode" -> 1; "anthropic" -> 2; "openai" -> 3; else -> 5 }

    fun model(providerId: String, modelId: String): ModelInfo? =
        providers.value.firstOrNull { it.id == providerId }?.models?.get(modelId)

    fun provider(id: String): ProviderInfo? = providers.value.firstOrNull { it.id == id }

    /** If the selected model is gone (first launch or the provider was disconnected), take the best available one. */
    private fun ensureSelection() {
        val list = providers.value
        if (list.isEmpty()) return
        // Keep a saved selection that still exists somewhere. Only replace a truly missing one.
        if (prefs.hasModel) {
            val p = list.firstOrNull { it.id == prefs.providerId }
            if (p != null && p.models.containsKey(prefs.modelId)) return
            if (p != null && list.any { q -> q.models.containsKey(prefs.modelId) }) {
                // Model moved provider: keep the model id, just fix the provider.
                prefs.providerId = list.first { q -> q.models.containsKey(prefs.modelId) }.id
                return
            }
        }
        for (p in list) {
            val m = defaults[p.id]?.let { p.models[it] } ?: p.models.values.firstOrNull() ?: continue
            prefs.providerId = p.id; prefs.modelId = m.id; prefs.variant = ""
            return
        }
    }

    /** After a provider is connected, select its model: prefer fast ("flash") ones and the server's recommended ones. */
    fun selectBestFor(providerId: String) {
        val p = provider(providerId) ?: return
        val m = defaults[providerId]?.let { p.models[it] }
            ?: p.models.values.filter { it.id.contains("flash", true) && !it.id.contains("lite", true) }.maxByOrNull { it.id }
            ?: p.models.values.firstOrNull() ?: return
        prefs.providerId = p.id; prefs.modelId = m.id; prefs.variant = ""
    }

    fun selectedLabel(): String {
        val m = model(prefs.providerId, prefs.modelId)
        if (m != null && m.name.isNotBlank()) return m.name
        if (prefs.modelId.isNotBlank()) return prefs.modelId
        if (providers.value.isEmpty()) return "Connecting…"
        return "Select model"
    }
}
