package com.zenguard.app.core.data

import android.content.Context
import android.content.SharedPreferences
import com.zenguard.app.core.config.OutboundNode
import com.zenguard.app.core.config.OutboundProtocol
import com.zenguard.app.core.config.RoutingConfig
import com.zenguard.app.core.config.SubscriptionInfo
import com.zenguard.app.core.model.ProxyMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight JSON-backed repository for proxy profiles, active node selection,
 * proxy mode, routing rules, and subscriptions.
 */
class ProfileRepository(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val _nodes = MutableStateFlow<List<OutboundNode>>(emptyList())
    val nodes: StateFlow<List<OutboundNode>> = _nodes.asStateFlow()

    private val _activeNodeId = MutableStateFlow<String?>(null)
    val activeNodeId: StateFlow<String?> = _activeNodeId.asStateFlow()

    private val _activeNode = MutableStateFlow<OutboundNode?>(null)
    val activeNode: StateFlow<OutboundNode?> = _activeNode.asStateFlow()

    private val _proxyMode = MutableStateFlow(ProxyMode.VPN_TUN)
    val proxyMode: StateFlow<ProxyMode> = _proxyMode.asStateFlow()

    private val _routingConfig = MutableStateFlow(RoutingConfig())
    val routingConfig: StateFlow<RoutingConfig> = _routingConfig.asStateFlow()

    private val _subscriptions = MutableStateFlow<List<SubscriptionInfo>>(emptyList())
    val subscriptions: StateFlow<List<SubscriptionInfo>> = _subscriptions.asStateFlow()

    init {
        loadData()
    }

    // =========================================================================
    // Node Management
    // =========================================================================

    @Synchronized
    fun addNode(node: OutboundNode) {
        val updated = _nodes.value + node
        _nodes.value = updated
        if (_activeNodeId.value == null) {
            setActiveNodeId(node.id)
        } else {
            syncActiveNode()
        }
        saveNodes()
    }

    @Synchronized
    fun addNodes(newNodes: List<OutboundNode>) {
        if (newNodes.isEmpty()) return
        val updated = _nodes.value + newNodes
        _nodes.value = updated
        if (_activeNodeId.value == null && updated.isNotEmpty()) {
            setActiveNodeId(updated.first().id)
        } else {
            syncActiveNode()
        }
        saveNodes()
    }

    @Synchronized
    fun updateNode(updatedNode: OutboundNode) {
        val updatedList = _nodes.value.map { if (it.id == updatedNode.id) updatedNode else it }
        _nodes.value = updatedList
        syncActiveNode()
        saveNodes()
    }

    @Synchronized
    fun deleteNode(nodeId: String) {
        val updatedList = _nodes.value.filter { it.id != nodeId }
        _nodes.value = updatedList
        if (_activeNodeId.value == nodeId) {
            val newActive = updatedList.firstOrNull()?.id
            _activeNodeId.value = newActive
            saveActiveNodeId()
        }
        syncActiveNode()
        saveNodes()
    }

    @Synchronized
    fun setActiveNodeId(id: String?) {
        _activeNodeId.value = id
        syncActiveNode()
        saveActiveNodeId()
    }

    @Synchronized
    fun updateNodesLatency(latencyResults: List<OutboundNode>) {
        val latencyMap = latencyResults.associate { it.id to it.latencyMs }
        val updatedList = _nodes.value.map { node ->
            if (latencyMap.containsKey(node.id)) {
                node.copy(latencyMs = latencyMap[node.id])
            } else {
                node
            }
        }
        _nodes.value = updatedList
        syncActiveNode()
        saveNodes()
    }

    @Synchronized
    fun replaceSubscriptionNodes(subscriptionId: String, freshNodes: List<OutboundNode>) {
        val nonSubNodes = _nodes.value.filter { it.subscriptionId != subscriptionId }
        val taggedFresh = freshNodes.map { it.copy(subscriptionId = subscriptionId) }
        val combined = nonSubNodes + taggedFresh
        _nodes.value = combined
        if (_activeNodeId.value == null && combined.isNotEmpty()) {
            _activeNodeId.value = combined.first().id
            saveActiveNodeId()
        }
        syncActiveNode()
        saveNodes()
    }

    @Synchronized
    fun clearAllNodes() {
        _nodes.value = emptyList()
        _activeNodeId.value = null
        syncActiveNode()
        saveNodes()
        saveActiveNodeId()
    }

    // =========================================================================
    // ProxyMode Management
    // =========================================================================

    @Synchronized
    fun setProxyMode(mode: ProxyMode) {
        _proxyMode.value = mode
        prefs.edit().putString(KEY_PROXY_MODE, mode.name).apply()
    }

    // =========================================================================
    // Routing Configuration Management
    // =========================================================================

    @Synchronized
    fun updateRoutingConfig(config: RoutingConfig) {
        _routingConfig.value = config
        prefs.edit().putString(KEY_ROUTING_CONFIG, config.toJson().toString()).apply()
    }

    // =========================================================================
    // Subscription Management
    // =========================================================================

    @Synchronized
    fun addSubscription(subscription: SubscriptionInfo) {
        val updated = _subscriptions.value + subscription
        _subscriptions.value = updated
        saveSubscriptions()
    }

    @Synchronized
    fun updateSubscription(subscription: SubscriptionInfo) {
        val updated = _subscriptions.value.map {
            if (it.id == subscription.id) subscription else it
        }
        _subscriptions.value = updated
        saveSubscriptions()
    }

    @Synchronized
    fun deleteSubscription(subscriptionId: String) {
        val updated = _subscriptions.value.filter { it.id != subscriptionId }
        _subscriptions.value = updated
        saveSubscriptions()

        // Also delete nodes associated with this subscription
        val updatedNodes = _nodes.value.filter { it.subscriptionId != subscriptionId }
        _nodes.value = updatedNodes
        if (_activeNodeId.value != null && updatedNodes.none { it.id == _activeNodeId.value }) {
            _activeNodeId.value = updatedNodes.firstOrNull()?.id
            saveActiveNodeId()
        }
        syncActiveNode()
        saveNodes()
    }

    // =========================================================================
    // Private Load & Save
    // =========================================================================

    private fun loadData() {
        // 1. Load Nodes
        val rawNodesJson = prefs.getString(KEY_NODES, null)
        val loadedNodes = mutableListOf<OutboundNode>()
        if (!rawNodesJson.isNullOrBlank()) {
            try {
                val array = JSONArray(rawNodesJson)
                for (i in 0 until array.length()) {
                    val nodeObj = array.getJSONObject(i)
                    loadedNodes.add(OutboundNode.fromJson(nodeObj))
                }
            } catch (_: Exception) {
                // If corrupted, fallback to demo profile
            }
        }

        // Pre-populate with default demo VLESS Reality profile if empty
        if (loadedNodes.isEmpty()) {
            val demoProfile = createDemoProfile()
            loadedNodes.add(demoProfile)
            _nodes.value = loadedNodes
            _activeNodeId.value = demoProfile.id
            saveNodes()
            saveActiveNodeId()
        } else {
            _nodes.value = loadedNodes
            val savedActiveId = prefs.getString(KEY_ACTIVE_NODE_ID, null)
            _activeNodeId.value = if (savedActiveId != null && loadedNodes.any { it.id == savedActiveId }) {
                savedActiveId
            } else {
                loadedNodes.firstOrNull()?.id
            }
        }

        syncActiveNode()

        // 2. Load ProxyMode
        val savedModeStr = prefs.getString(KEY_PROXY_MODE, null)
        if (savedModeStr != null) {
            try {
                _proxyMode.value = ProxyMode.valueOf(savedModeStr)
            } catch (_: Exception) {
                _proxyMode.value = ProxyMode.VPN_TUN
            }
        }

        // 3. Load RoutingConfig
        val rawRoutingJson = prefs.getString(KEY_ROUTING_CONFIG, null)
        if (!rawRoutingJson.isNullOrBlank()) {
            try {
                _routingConfig.value = RoutingConfig.fromJson(JSONObject(rawRoutingJson))
            } catch (_: Exception) {
                _routingConfig.value = RoutingConfig()
            }
        }

        // 4. Load Subscriptions
        val rawSubsJson = prefs.getString(KEY_SUBSCRIPTIONS, null)
        val loadedSubs = mutableListOf<SubscriptionInfo>()
        if (!rawSubsJson.isNullOrBlank()) {
            try {
                val array = JSONArray(rawSubsJson)
                for (i in 0 until array.length()) {
                    val subObj = array.getJSONObject(i)
                    loadedSubs.add(SubscriptionInfo.fromJson(subObj))
                }
            } catch (_: Exception) {}
        }
        _subscriptions.value = loadedSubs
    }

    private fun syncActiveNode() {
        val currentId = _activeNodeId.value
        _activeNode.value = _nodes.value.firstOrNull { it.id == currentId }
    }

    private fun saveNodes() {
        val array = JSONArray()
        _nodes.value.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_NODES, array.toString()).apply()
    }

    private fun saveActiveNodeId() {
        prefs.edit().putString(KEY_ACTIVE_NODE_ID, _activeNodeId.value).apply()
    }

    private fun saveSubscriptions() {
        val array = JSONArray()
        _subscriptions.value.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_SUBSCRIPTIONS, array.toString()).apply()
    }

    private fun createDemoProfile(): OutboundNode {
        return OutboundNode(
            id = "demo-vless-reality-01",
            name = "Default VLESS Reality",
            protocol = OutboundProtocol.VLESS,
            server = "104.16.132.229",
            port = 443,
            uuid = "d342294f-d434-4537-8854-4a2b95b8ee9e",
            security = "reality",
            sni = "www.microsoft.com",
            fingerprint = "chrome",
            alpn = listOf("h2", "http/1.1"),
            publicKey = "mQdYzX7k5H4n4Q4N-r9V3sY8_t9P2p5X7k5H4n4Q4N8",
            shortId = "0123456789abcdef",
            spiderX = "/",
            flow = "xtls-rprx-vision",
            network = "tcp",
            latencyMs = null
        )
    }

    companion object {
        private const val PREFS_NAME = "zenguard_profiles"
        private const val KEY_NODES = "key_nodes"
        private const val KEY_ACTIVE_NODE_ID = "key_active_node_id"
        private const val KEY_PROXY_MODE = "key_proxy_mode"
        private const val KEY_ROUTING_CONFIG = "key_routing_config"
        private const val KEY_SUBSCRIPTIONS = "key_subscriptions"

        @Volatile
        private var instance: ProfileRepository? = null

        fun getInstance(context: Context): ProfileRepository {
            return instance ?: synchronized(this) {
                instance ?: ProfileRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
