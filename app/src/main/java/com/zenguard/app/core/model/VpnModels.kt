package com.zenguard.app.core.model

enum class VpnState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR
}

enum class ProxyMode(val label: String, val requiresRoot: Boolean) {
    VPN_TUN("VPN (TUN)", requiresRoot = false),
    ROOT_TPROXY("Root TProxy", requiresRoot = true),
    ROOT_TUN("Root TUN", requiresRoot = true),
    PROXY_ONLY("Proxy Only", requiresRoot = false)
}

data class TrafficStats(
    val uplinkBps: Long = 0L,
    val downlinkBps: Long = 0L,
    val totalUpBytes: Long = 0L,
    val totalDownBytes: Long = 0L
)

data class VpnProfile(
    val id: String,
    val name: String,
    val protocol: String,
    val server: String,
    val latencyMs: Int? = null
)
