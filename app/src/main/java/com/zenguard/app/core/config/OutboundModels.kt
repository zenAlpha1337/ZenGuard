package com.zenguard.app.core.config

import com.zenguard.app.core.model.VpnProfile
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Supported outbound proxy protocols.
 */
object OutboundProtocol {
    const val VLESS = "vless"
    const val VMESS = "vmess"
    const val TROJAN = "trojan"
    const val SHADOWSOCKS = "shadowsocks"
    const val HYSTERIA2 = "hysteria2"
    const val TUIC = "tuic"
}

/**
 * Data class representing a parsed and configured proxy outbound node.
 */
data class OutboundNode(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: String,
    val server: String,
    val port: Int,
    val uuid: String? = null,
    val password: String? = null,
    val security: String = "none", // none, tls, reality
    val sni: String? = null,
    val fingerprint: String? = "chrome",
    val alpn: List<String> = emptyList(),
    val publicKey: String? = null, // Reality pbk
    val shortId: String? = null,   // Reality sid
    val spiderX: String? = null,   // Reality spx
    val flow: String? = null,      // e.g. xtls-rprx-vision
    val network: String = "tcp",   // tcp, ws, grpc, httpupgrade, h2
    val wsPath: String? = null,
    val wsHeaders: Map<String, String> = emptyMap(),
    val grpcServiceName: String? = null,
    val alterId: Int = 0,          // VMess
    val cipher: String? = null,    // Shadowsocks cipher / VMess scy
    val congestionControl: String? = null, // Hysteria2 / TUIC
    val obfs: String? = null,      // Hysteria2 obfs
    val obfsPassword: String? = null,
    val udpRelayMode: String? = null, // TUIC
    val allowInsecure: Boolean = false,
    val subscriptionId: String? = null,
    val latencyMs: Long? = null
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("name", name)
        obj.put("protocol", protocol)
        obj.put("server", server)
        obj.put("port", port)
        uuid?.let { obj.put("uuid", it) }
        password?.let { obj.put("password", it) }
        obj.put("security", security)
        sni?.let { obj.put("sni", it) }
        fingerprint?.let { obj.put("fingerprint", it) }
        if (alpn.isNotEmpty()) {
            val alpnArray = JSONArray()
            alpn.forEach { alpnArray.put(it) }
            obj.put("alpn", alpnArray)
        }
        publicKey?.let { obj.put("publicKey", it) }
        shortId?.let { obj.put("shortId", it) }
        spiderX?.let { obj.put("spiderX", it) }
        flow?.let { obj.put("flow", it) }
        obj.put("network", network)
        wsPath?.let { obj.put("wsPath", it) }
        if (wsHeaders.isNotEmpty()) {
            val headersObj = JSONObject()
            wsHeaders.forEach { (k, v) -> headersObj.put(k, v) }
            obj.put("wsHeaders", headersObj)
        }
        grpcServiceName?.let { obj.put("grpcServiceName", it) }
        obj.put("alterId", alterId)
        cipher?.let { obj.put("cipher", it) }
        congestionControl?.let { obj.put("congestionControl", it) }
        obfs?.let { obj.put("obfs", it) }
        obfsPassword?.let { obj.put("obfsPassword", it) }
        udpRelayMode?.let { obj.put("udpRelayMode", it) }
        obj.put("allowInsecure", allowInsecure)
        subscriptionId?.let { obj.put("subscriptionId", it) }
        latencyMs?.let { obj.put("latencyMs", it) }
        return obj
    }

    /**
     * Converts to lightweight UI [VpnProfile].
     */
    fun toVpnProfile(): VpnProfile {
        return VpnProfile(
            id = id,
            name = name,
            protocol = protocol,
            server = server,
            latencyMs = latencyMs?.toInt()
        )
    }

    /**
     * Converts to [OutboundProfile] for sing-box config generation.
     */
    fun toOutboundProfile(): OutboundProfile {
        return OutboundProfile(
            tag = "proxy",
            type = protocol,
            server = server,
            serverPort = port,
            uuid = uuid,
            password = password,
            security = cipher ?: "auto",
            alterId = alterId,
            method = cipher ?: "2022-blake3-aes-128-gcm",
            flow = flow,
            tlsEnabled = security == "tls" || security == "reality",
            serverName = sni,
            insecure = allowInsecure,
            alpn = alpn.ifEmpty { null },
            utlsFingerprint = fingerprint ?: "chrome",
            realityEnabled = security == "reality",
            realityPublicKey = publicKey,
            realityShortId = shortId,
            network = network,
            wsPath = wsPath,
            wsHeaders = wsHeaders.ifEmpty { null },
            grpcServiceName = grpcServiceName,
            congestionControl = congestionControl ?: "bbr",
            udpRelayMode = udpRelayMode ?: "native"
        )
    }

    companion object {
        fun fromJson(obj: JSONObject): OutboundNode {
            val alpnList = mutableListOf<String>()
            val alpnArray = obj.optJSONArray("alpn")
            if (alpnArray != null) {
                for (i in 0 until alpnArray.length()) {
                    alpnList.add(alpnArray.getString(i))
                }
            }

            val headersMap = mutableMapOf<String, String>()
            val headersObj = obj.optJSONObject("wsHeaders")
            if (headersObj != null) {
                headersObj.keys().forEach { key ->
                    headersMap[key] = headersObj.optString(key)
                }
            }

            return OutboundNode(
                id = obj.optString("id", UUID.randomUUID().toString()),
                name = obj.optString("name", "Unnamed Node"),
                protocol = obj.optString("protocol", OutboundProtocol.VLESS),
                server = obj.optString("server", ""),
                port = obj.optInt("port", 443),
                uuid = if (obj.has("uuid")) obj.optString("uuid") else null,
                password = if (obj.has("password")) obj.optString("password") else null,
                security = obj.optString("security", "none"),
                sni = if (obj.has("sni")) obj.optString("sni") else null,
                fingerprint = if (obj.has("fingerprint")) obj.optString("fingerprint") else null,
                alpn = alpnList,
                publicKey = if (obj.has("publicKey")) obj.optString("publicKey") else null,
                shortId = if (obj.has("shortId")) obj.optString("shortId") else null,
                spiderX = if (obj.has("spiderX")) obj.optString("spiderX") else null,
                flow = if (obj.has("flow")) obj.optString("flow") else null,
                network = obj.optString("network", "tcp"),
                wsPath = if (obj.has("wsPath")) obj.optString("wsPath") else null,
                wsHeaders = headersMap,
                grpcServiceName = if (obj.has("grpcServiceName")) obj.optString("grpcServiceName") else null,
                alterId = obj.optInt("alterId", 0),
                cipher = if (obj.has("cipher")) obj.optString("cipher") else null,
                congestionControl = if (obj.has("congestionControl")) obj.optString("congestionControl") else null,
                obfs = if (obj.has("obfs")) obj.optString("obfs") else null,
                obfsPassword = if (obj.has("obfsPassword")) obj.optString("obfsPassword") else null,
                udpRelayMode = if (obj.has("udpRelayMode")) obj.optString("udpRelayMode") else null,
                allowInsecure = obj.optBoolean("allowInsecure", false),
                subscriptionId = if (obj.has("subscriptionId")) obj.optString("subscriptionId") else null,
                latencyMs = if (obj.has("latencyMs") && !obj.isNull("latencyMs")) obj.optLong("latencyMs") else null
            )
        }
    }
}

/**
 * Information about a remote subscription source.
 */
data class SubscriptionInfo(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val lastUpdated: Long = 0L,
    val autoUpdate: Boolean = true,
    val nodeCount: Int = 0
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("name", name)
        obj.put("url", url)
        obj.put("lastUpdated", lastUpdated)
        obj.put("autoUpdate", autoUpdate)
        obj.put("nodeCount", nodeCount)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): SubscriptionInfo {
            return SubscriptionInfo(
                id = obj.optString("id", UUID.randomUUID().toString()),
                name = obj.optString("name", "Subscription"),
                url = obj.optString("url", ""),
                lastUpdated = obj.optLong("lastUpdated", 0L),
                autoUpdate = obj.optBoolean("autoUpdate", true),
                nodeCount = obj.optInt("nodeCount", 0)
            )
        }
    }
}

/**
 * Mode of app split tunneling.
 */
enum class SplitTunnelMode {
    DISABLED,
    INCLUDE_ONLY,
    EXCLUDE
}

/**
 * Routing and DNS configuration for ZenGuard proxy engine.
 */
data class RoutingConfig(
    val bypassLan: Boolean = true,
    val bypassRu: Boolean = true,
    val fakeIp: Boolean = true,
    val dohUrl: String = "https://1.1.1.1/dns-query",
    val customDirectDomains: List<String> = emptyList(),
    val customProxyDomains: List<String> = emptyList(),
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.DISABLED,
    val selectedPackages: List<String> = emptyList()
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("bypassLan", bypassLan)
        obj.put("bypassRu", bypassRu)
        obj.put("fakeIp", fakeIp)
        obj.put("dohUrl", dohUrl)

        val directArray = JSONArray()
        customDirectDomains.forEach { directArray.put(it) }
        obj.put("customDirectDomains", directArray)

        val proxyArray = JSONArray()
        customProxyDomains.forEach { proxyArray.put(it) }
        obj.put("customProxyDomains", proxyArray)

        obj.put("splitTunnelMode", splitTunnelMode.name)

        val packagesArray = JSONArray()
        selectedPackages.forEach { packagesArray.put(it) }
        obj.put("selectedPackages", packagesArray)

        return obj
    }

    /**
     * Converts to [com.zenguard.app.core.engine.RoutingConfig] for sing-box execution.
     */
    fun toEngineRoutingConfig(): com.zenguard.app.core.engine.RoutingConfig {
        val perApp = when (splitTunnelMode) {
            SplitTunnelMode.DISABLED -> com.zenguard.app.core.engine.PerAppMode.ALL
            SplitTunnelMode.INCLUDE_ONLY -> com.zenguard.app.core.engine.PerAppMode.ALLOW_LIST
            SplitTunnelMode.EXCLUDE -> com.zenguard.app.core.engine.PerAppMode.DISALLOW_LIST
        }
        return com.zenguard.app.core.engine.RoutingConfig(
            bypassLan = bypassLan,
            bypassRu = bypassRu,
            fakeDns = fakeIp,
            remoteDns = dohUrl,
            perAppMode = perApp,
            perAppPackages = selectedPackages,
            customRules = buildList {
                if (customDirectDomains.isNotEmpty()) {
                    add(com.zenguard.app.core.engine.CustomRule(domains = customDirectDomains, outboundTag = "direct"))
                }
                if (customProxyDomains.isNotEmpty()) {
                    add(com.zenguard.app.core.engine.CustomRule(domains = customProxyDomains, outboundTag = "proxy"))
                }
            }
        )
    }

    companion object {
        fun fromJson(obj: JSONObject): RoutingConfig {
            val directList = mutableListOf<String>()
            val directArray = obj.optJSONArray("customDirectDomains")
            if (directArray != null) {
                for (i in 0 until directArray.length()) {
                    directList.add(directArray.getString(i))
                }
            }

            val proxyList = mutableListOf<String>()
            val proxyArray = obj.optJSONArray("customProxyDomains")
            if (proxyArray != null) {
                for (i in 0 until proxyArray.length()) {
                    proxyList.add(proxyArray.getString(i))
                }
            }

            val packagesList = mutableListOf<String>()
            val packagesArray = obj.optJSONArray("selectedPackages")
            if (packagesArray != null) {
                for (i in 0 until packagesArray.length()) {
                    packagesList.add(packagesArray.getString(i))
                }
            }

            val modeStr = obj.optString("splitTunnelMode", SplitTunnelMode.DISABLED.name)
            val mode = try {
                SplitTunnelMode.valueOf(modeStr)
            } catch (_: Exception) {
                SplitTunnelMode.DISABLED
            }

            return RoutingConfig(
                bypassLan = obj.optBoolean("bypassLan", true),
                bypassRu = obj.optBoolean("bypassRu", true),
                fakeIp = obj.optBoolean("fakeIp", true),
                dohUrl = obj.optString("dohUrl", "https://1.1.1.1/dns-query"),
                customDirectDomains = directList,
                customProxyDomains = proxyList,
                splitTunnelMode = mode,
                selectedPackages = packagesList
            )
        }
    }
}
