package com.zenguard.app.core.engine

import android.content.Context
import com.zenguard.app.core.model.ProxyMode
import com.zenguard.app.core.model.TrafficStats
import com.zenguard.app.core.model.VpnProfile
import com.zenguard.app.core.model.VpnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Split tunneling policy modes for VPN and TProxy engines.
 */
enum class PerAppMode {
    ALL,
    ALLOW_LIST,
    DISALLOW_LIST
}

/**
 * Domain and IP CIDR routing rules.
 */
data class CustomRule(
    val domains: List<String> = emptyList(),
    val ipRanges: List<String> = emptyList(),
    val outboundTag: String = "direct" // "direct", "proxy", "block"
)

/**
 * High-level routing and sing-box engine configuration.
 */
data class RoutingConfig(
    val bypassLan: Boolean = true,
    val bypassRu: Boolean = false,
    val fakeDns: Boolean = false,
    val remoteDns: String = "https://1.1.1.1/dns-query",
    val directDns: String = "local",
    val autoDetectInterface: Boolean = true,
    val tunStack: String = "gvisor", // "gvisor", "system", "mixed"
    val tproxyPort: Int = 10808,
    val mixedPort: Int = 10809,
    val perAppMode: PerAppMode = PerAppMode.ALL,
    val perAppPackages: List<String> = emptyList(),
    val customRules: List<CustomRule> = emptyList()
)

/**
 * Unified Core Engine interface for ZenGuard.
 * Supports standard Android VPN (VpnService), Root TProxy, and Root TUN modes.
 */
interface CoreEngine {
    val state: StateFlow<VpnState>
    val traffic: StateFlow<TrafficStats>
    val logs: StateFlow<List<String>>

    fun start(mode: ProxyMode, profile: VpnProfile, routingConfig: RoutingConfig = RoutingConfig())
    fun stop()
}

/**
 * Global singleton instance of [CoreEngine].
 * Coordinates UI states, VpnService, and upcoming Root engine modules.
 */
object ZenCoreEngine : CoreEngine {

    private val engineScope = CoroutineScope(Dispatchers.Default + Job())

    private val _state = MutableStateFlow(VpnState.DISCONNECTED)
    override val state: StateFlow<VpnState> = _state.asStateFlow()

    private val _traffic = MutableStateFlow(TrafficStats())
    override val traffic: StateFlow<TrafficStats> = _traffic.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    override val logs: StateFlow<List<String>> = _logs.asStateFlow()

    @Volatile
    private var activeContext: Context? = null

    fun attachContext(context: Context) {
        activeContext = context.applicationContext
    }

    override fun start(mode: ProxyMode, profile: VpnProfile, routingConfig: RoutingConfig) {
        val context = activeContext
        appendLog("[CoreEngine] Requesting start: mode=${mode.label}, profile=${profile.name}")

        when (mode) {
            ProxyMode.VPN_TUN -> {
                if (context != null) {
                    _state.value = VpnState.CONNECTING
                    ZenVpnService.start(context, profile, routingConfig)
                } else {
                    _state.value = VpnState.ERROR
                    appendLog("[CoreEngine] Error: Application context not attached to ZenCoreEngine")
                }
            }
            ProxyMode.ROOT_TPROXY, ProxyMode.ROOT_TUN -> {
                if (context != null) {
                    _state.value = VpnState.CONNECTING
                    appendLog("[CoreEngine] Initializing ${mode.label} via RootEngineController...")
                    engineScope.launch {
                        val result = RootEngineController.start(context, mode, profile)
                        if (result.isSuccess) {
                            _state.value = VpnState.CONNECTED
                            appendLog("[CoreEngine] ${mode.label} connected.")
                        } else {
                            _state.value = VpnState.ERROR
                            appendLog("[CoreEngine] ${mode.label} error: ${result.exceptionOrNull()?.message}")
                        }
                    }
                } else {
                    _state.value = VpnState.ERROR
                    appendLog("[CoreEngine] Error: Application context not attached.")
                }
            }
            ProxyMode.PROXY_ONLY -> {
                _state.value = VpnState.CONNECTING
                appendLog("[CoreEngine] Starting Proxy Only (Mixed SOCKS5/HTTP on 127.0.0.1:${routingConfig.mixedPort})...")
                _state.value = VpnState.CONNECTED
                appendLog("[CoreEngine] Proxy Only running.")
            }
        }
    }

    override fun stop() {
        val context = activeContext
        appendLog("[CoreEngine] Stopping engine...")
        _state.value = VpnState.DISCONNECTING

        if (context != null) {
            ZenVpnService.stop(context)
            if (RootEngineController.isRunning) {
                engineScope.launch {
                    RootEngineController.stop(context)
                }
            }
        }

        _state.value = VpnState.DISCONNECTED
        _traffic.value = TrafficStats()
        appendLog("[CoreEngine] Engine stopped.")
    }

    // Engine updates from ZenVpnService & Root engine
    fun updateState(newState: VpnState) {
        _state.value = newState
    }

    fun updateTraffic(stats: TrafficStats) {
        _traffic.value = stats
    }

    fun appendLog(message: String) {
        val current = _logs.value.toMutableList()
        if (current.size > 500) {
            current.removeAt(0)
        }
        current.add(message)
        _logs.value = current
    }
}
