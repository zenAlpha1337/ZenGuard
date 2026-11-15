package com.zenguard.app.core.config

import android.net.Uri
import android.util.Base64
import com.zenguard.app.core.engine.CustomRule
import com.zenguard.app.core.engine.RoutingConfig
import com.zenguard.app.core.model.ProxyMode
import com.zenguard.app.core.model.VpnProfile
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Detailed outbound profile configuration model supporting:
 * VLESS (Reality, uTLS), VMess, Trojan, Shadowsocks, Hysteria2, TUIC.
 */
data class OutboundProfile(
    val tag: String = "proxy",
    val type: String = "vless", // "vless", "vmess", "trojan", "shadowsocks", "hysteria2", "tuic"
    val server: String = "127.0.0.1",
    val serverPort: Int = 443,
    val uuid: String? = null,
    val password: String? = null,
    val security: String = "auto", // vmess
    val alterId: Int = 0, // vmess
    val method: String = "2022-blake3-aes-128-gcm", // shadowsocks
    val flow: String? = null, // xtls-rprx-vision
    val tlsEnabled: Boolean = true,
    val serverName: String? = null,
    val insecure: Boolean = false,
    val alpn: List<String>? = null,
    val utlsFingerprint: String? = "chrome",
    val realityEnabled: Boolean = false,
    val realityPublicKey: String? = null,
    val realityShortId: String? = null,
    val network: String = "tcp", // "tcp", "ws", "grpc", "http"
    val wsPath: String? = null,
    val wsHeaders: Map<String, String>? = null,
    val grpcServiceName: String? = null,
    val upMbps: Int = 100, // hysteria2
    val downMbps: Int = 100, // hysteria2
    val congestionControl: String = "bbr", // tuic
    val udpRelayMode: String = "native", // tuic
    val zeroRttHandshake: Boolean = false // tuic
)

/**
 * Production-grade sing-box JSON configuration generator.
 * Generates valid v1.8+ / v1.9+ sing-box configuration files using org.json.
 */
object SingBoxConfigGenerator {

    /**
     * Generates a sing-box [JSONObject] from a high-level [VpnProfile], [RoutingConfig], and [ProxyMode].
     */
    fun generate(
        profile: VpnProfile,
        routingConfig: RoutingConfig = RoutingConfig(),
        mode: ProxyMode = ProxyMode.VPN_TUN
    ): JSONObject {
        val outbound = parseOutbound(profile)
        return generate(outbound, routingConfig, mode)
    }

    /**
     * Generates a sing-box [JSONObject] with a fully specified [OutboundProfile].
     */
    fun generate(
        outbound: OutboundProfile,
        routingConfig: RoutingConfig = RoutingConfig(),
        mode: ProxyMode = ProxyMode.VPN_TUN
    ): JSONObject {
        val root = JSONObject()

        // 1. Log block
        root.put("log", JSONObject().apply {
            put("disabled", false)
            put("level", "info")
            put("timestamp", true)
        })

        // 2. DNS block
        root.put("dns", buildDnsBlock(routingConfig))

        // 3. Inbounds block
        root.put("inbounds", buildInboundsBlock(routingConfig, mode))

        // 4. Outbounds block
        root.put("outbounds", buildOutboundsBlock(outbound))

        // 5. Route block
        root.put("route", buildRouteBlock(routingConfig))

        return root
    }

    private fun buildDnsBlock(routingConfig: RoutingConfig): JSONObject {
        val dns = JSONObject()
        val servers = JSONArray()

        // Remote DNS (DoH via proxy detour)
        servers.put(JSONObject().apply {
            put("tag", "remote-dns")
            put("address", routingConfig.remoteDns)
            put("address_resolver", "local-dns")
            put("strategy", "prefer_ipv4")
            put("detour", "proxy")
        })

        // Local DNS (direct detour)
        servers.put(JSONObject().apply {
            put("tag", "local-dns")
            put("address", routingConfig.directDns)
            put("detour", "direct")
        })

        // Block DNS
        servers.put(JSONObject().apply {
            put("tag", "block-dns")
            put("address", "rcode://refused")
        })

        // FakeIP DNS server
        if (routingConfig.fakeDns) {
            servers.put(JSONObject().apply {
                put("tag", "fakeip-dns")
                put("address", "fakeip")
            })

            dns.put("fakeip", JSONObject().apply {
                put("enabled", true)
                put("inet4_range", "198.18.0.0/15")
                put("inet6_range", "fc00::/18")
            })
        }

        dns.put("servers", servers)

        // DNS Rules
        val rules = JSONArray()

        // Local / direct outbounds resolve via local-dns
        rules.put(JSONObject().apply {
            put("outbound", JSONArray().apply { put("any") })
            put("server", "local-dns")
        })

        if (routingConfig.fakeDns) {
            rules.put(JSONObject().apply {
                put("inbound", JSONArray().apply { put("tun-in") })
                put("server", "fakeip-dns")
            })
        }

        dns.put("rules", rules)
        dns.put("final", if (routingConfig.fakeDns) "fakeip-dns" else "remote-dns")
        dns.put("strategy", "prefer_ipv4")
        dns.put("independent_cache", true)

        return dns
    }

    private fun buildInboundsBlock(routingConfig: RoutingConfig, mode: ProxyMode): JSONArray {
        val inbounds = JSONArray()

        // 1. TUN Inbound
        // Note: auto_route is false for Android VpnService (managed via VpnService.Builder routes)
        // and true for ROOT_TUN mode.
        val tun = JSONObject().apply {
            put("type", "tun")
            put("tag", "tun-in")
            put("interface_name", "tun0")
            put("inet4_address", "172.19.0.1/30")
            put("inet6_address", "fdfe:dcba:9876::1/126")
            put("mtu", 9000)
            put("auto_route", mode == ProxyMode.ROOT_TUN)
            put("strict_route", false)
            put("stack", routingConfig.tunStack) // "gvisor", "system", "mixed"
            put("sniff", true)
            put("sniff_override_destination", false)
        }
        inbounds.put(tun)

        // 2. TProxy Inbound (Root TProxy mode & transparent redirect)
        val tproxy = JSONObject().apply {
            put("type", "tproxy")
            put("tag", "tproxy-in")
            put("listen", "127.0.0.1")
            put("listen_port", routingConfig.tproxyPort)
            put("sniff", true)
            put("sniff_override_destination", false)
        }
        inbounds.put(tproxy)

        // 3. Mixed Inbound (SOCKS5 + HTTP proxy for local apps / PROXY_ONLY mode)
        val mixed = JSONObject().apply {
            put("type", "mixed")
            put("tag", "mixed-in")
            put("listen", "127.0.0.1")
            put("listen_port", routingConfig.mixedPort)
            put("sniff", true)
        }
        inbounds.put(mixed)

        return inbounds
    }

    private fun buildOutboundsBlock(outbound: OutboundProfile): JSONArray {
        val outbounds = JSONArray()

        // 1. Primary Proxy outbound
        outbounds.put(buildProxyOutbound(outbound))

        // 2. Direct outbound
        outbounds.put(JSONObject().apply {
            put("type", "direct")
            put("tag", "direct")
        })

        // 3. Block outbound
        outbounds.put(JSONObject().apply {
            put("type", "block")
            put("tag", "block")
        })

        // 4. DNS-out outbound
        outbounds.put(JSONObject().apply {
            put("type", "dns")
            put("tag", "dns-out")
        })

        return outbounds
    }

    private fun buildProxyOutbound(profile: OutboundProfile): JSONObject {
        val out = JSONObject()
        out.put("tag", profile.tag)

        when (profile.type.lowercase()) {
            "vless" -> {
                out.put("type", "vless")
                out.put("server", profile.server)
                out.put("server_port", profile.serverPort)
                out.put("uuid", profile.uuid ?: "b831381d-6324-4d53-ad4f-8cda48b30811")
                if (!profile.flow.isNullOrBlank()) {
                    out.put("flow", profile.flow) // xtls-rprx-vision
                }
                out.put("packet_encoding", "xudp")
                attachTlsAndReality(out, profile)
                attachTransport(out, profile)
            }
            "vmess" -> {
                out.put("type", "vmess")
                out.put("server", profile.server)
                out.put("server_port", profile.serverPort)
                out.put("uuid", profile.uuid ?: "")
                out.put("security", profile.security)
                out.put("alter_id", profile.alterId)
                if (profile.tlsEnabled) {
                    attachStandardTls(out, profile)
                }
                attachTransport(out, profile)
            }
            "trojan" -> {
                out.put("type", "trojan")
                out.put("server", profile.server)
                out.put("server_port", profile.serverPort)
                out.put("password", profile.password ?: "")
                attachStandardTls(out, profile)
                attachTransport(out, profile)
            }
            "shadowsocks", "ss" -> {
                out.put("type", "shadowsocks")
                out.put("server", profile.server)
                out.put("server_port", profile.serverPort)
                out.put("method", profile.method)
                out.put("password", profile.password ?: "")
            }
            "hysteria2", "hy2" -> {
                out.put("type", "hysteria2")
                out.put("server", profile.server)
                out.put("server_port", profile.serverPort)
                if (!profile.password.isNullOrBlank()) {
                    out.put("password", profile.password)
                }
                if (profile.upMbps > 0) out.put("up_mbps", profile.upMbps)
                if (profile.downMbps > 0) out.put("down_mbps", profile.downMbps)
                attachStandardTls(out, profile)
            }
            "tuic" -> {
                out.put("type", "tuic")
                out.put("server", profile.server)
                out.put("server_port", profile.serverPort)
                if (!profile.uuid.isNullOrBlank()) out.put("uuid", profile.uuid)
                if (!profile.password.isNullOrBlank()) out.put("password", profile.password)
                out.put("congestion_control", profile.congestionControl)
                out.put("udp_relay_mode", profile.udpRelayMode)
                out.put("zero_rtt_handshake", profile.zeroRttHandshake)
                attachStandardTls(out, profile)
            }
            else -> {
                // Fallback to direct
                out.put("type", "direct")
            }
        }
        return out
    }

    private fun attachStandardTls(out: JSONObject, profile: OutboundProfile) {
        val tls = JSONObject().apply {
            put("enabled", true)
            put("server_name", profile.serverName ?: profile.server)
            put("insecure", profile.insecure)
            if (!profile.alpn.isNullOrEmpty()) {
                val alpnArr = JSONArray()
                profile.alpn.forEach { alpnArr.put(it) }
                put("alpn", alpnArr)
            }
            if (!profile.utlsFingerprint.isNullOrBlank()) {
                put("utls", JSONObject().apply {
                    put("enabled", true)
                    put("fingerprint", profile.utlsFingerprint)
                })
            }
        }
        out.put("tls", tls)
    }

    private fun attachTlsAndReality(out: JSONObject, profile: OutboundProfile) {
        if (!profile.tlsEnabled) return

        val tls = JSONObject().apply {
            put("enabled", true)
            put("server_name", profile.serverName ?: profile.server)
            put("insecure", profile.insecure)

            if (!profile.utlsFingerprint.isNullOrBlank()) {
                put("utls", JSONObject().apply {
                    put("enabled", true)
                    put("fingerprint", profile.utlsFingerprint)
                })
            }

            if (profile.realityEnabled && !profile.realityPublicKey.isNullOrBlank()) {
                val reality = JSONObject().apply {
                    put("enabled", true)
                    put("public_key", profile.realityPublicKey)
                    if (!profile.realityShortId.isNullOrBlank()) {
                        put("short_id", profile.realityShortId)
                    }
                }
                put("reality", reality)
            }
        }
        out.put("tls", tls)
    }

    private fun attachTransport(out: JSONObject, profile: OutboundProfile) {
        if (profile.network.equals("ws", ignoreCase = true)) {
            val transport = JSONObject().apply {
                put("type", "ws")
                if (!profile.wsPath.isNullOrBlank()) put("path", profile.wsPath)
                if (!profile.wsHeaders.isNullOrEmpty()) {
                    val headersObj = JSONObject()
                    profile.wsHeaders.forEach { (k, v) -> headersObj.put(k, v) }
                    put("headers", headersObj)
                }
            }
            out.put("transport", transport)
        } else if (profile.network.equals("grpc", ignoreCase = true)) {
            val transport = JSONObject().apply {
                put("type", "grpc")
                if (!profile.grpcServiceName.isNullOrBlank()) put("service_name", profile.grpcServiceName)
            }
            out.put("transport", transport)
        }
    }

    private fun buildRouteBlock(routingConfig: RoutingConfig): JSONObject {
        val route = JSONObject()
        route.put("auto_detect_interface", routingConfig.autoDetectInterface)
        route.put("final", "proxy")

        val rules = JSONArray()

        // 1. DNS protocol redirection to dns-out
        rules.put(JSONObject().apply {
            put("protocol", "dns")
            put("outbound", "dns-out")
        })

        // 2. Bypass LAN
        if (routingConfig.bypassLan) {
            rules.put(JSONObject().apply {
                put("ip_is_private", true)
                put("outbound", "direct")
            })
        }

        // 3. Bypass Russia (GeoIP/GeoSite & domain suffixes)
        if (routingConfig.bypassRu) {
            rules.put(JSONObject().apply {
                put("geoip", JSONArray().apply { put("ru") })
                put("geosite", JSONArray().apply { put("ru") })
                put("outbound", "direct")
            })
            rules.put(JSONObject().apply {
                put("domain_suffix", JSONArray().apply {
                    put(".ru")
                    put(".su")
                    put(".xn--p1ai") // .рф
                    put(".by")
                    put(".kz")
                })
                put("outbound", "direct")
            })
        }

        // 4. Custom domain & IP rules
        for (custom in routingConfig.customRules) {
            val customRuleObj = JSONObject()
            if (custom.domains.isNotEmpty()) {
                val dArray = JSONArray()
                custom.domains.forEach { dArray.put(it) }
                customRuleObj.put("domain", dArray)
            }
            if (custom.ipRanges.isNotEmpty()) {
                val ipArray = JSONArray()
                custom.ipRanges.forEach { ipArray.put(it) }
                customRuleObj.put("ip_cidr", ipArray)
            }
            customRuleObj.put("outbound", custom.outboundTag)
            rules.put(customRuleObj)
        }

        route.put("rules", rules)
        return route
    }

    /**
     * Parses a [VpnProfile] or URL into an [OutboundProfile].
     */
    fun parseOutbound(profile: VpnProfile): OutboundProfile {
        val raw = profile.server.trim()
        if (raw.startsWith("vless://") ||
            raw.startsWith("vmess://") ||
            raw.startsWith("trojan://") ||
            raw.startsWith("ss://") ||
            raw.startsWith("hysteria2://") ||
            raw.startsWith("hy2://") ||
            raw.startsWith("tuic://")
        ) {
            return parseFromUri(raw)
        }

        // Standard profile without raw URL
        return when (profile.protocol.lowercase()) {
            "vless" -> OutboundProfile(
                type = "vless",
                server = profile.server,
                serverPort = 443,
                uuid = "b831381d-6324-4d53-ad4f-8cda48b30811",
                flow = "xtls-rprx-vision",
                tlsEnabled = true,
                realityEnabled = true,
                realityPublicKey = "qp_ghu_placeholder_key_singbox_reality=",
                realityShortId = "0123456789abcdef",
                serverName = "dl.google.com",
                utlsFingerprint = "chrome"
            )
            "vmess" -> OutboundProfile(
                type = "vmess",
                server = profile.server,
                serverPort = 443,
                uuid = "b831381d-6324-4d53-ad4f-8cda48b30811"
            )
            "trojan" -> OutboundProfile(
                type = "trojan",
                server = profile.server,
                serverPort = 443,
                password = "zen-trojan-password"
            )
            "shadowsocks", "ss" -> OutboundProfile(
                type = "shadowsocks",
                server = profile.server,
                serverPort = 8388,
                method = "2022-blake3-aes-128-gcm",
                password = "zen-ss-password"
            )
            "hysteria2", "hy2" -> OutboundProfile(
                type = "hysteria2",
                server = profile.server,
                serverPort = 443,
                password = "zen-hy2-password"
            )
            "tuic" -> OutboundProfile(
                type = "tuic",
                server = profile.server,
                serverPort = 443,
                uuid = "b831381d-6324-4d53-ad4f-8cda48b30811",
                password = "zen-tuic-password"
            )
            else -> OutboundProfile(
                type = "vless",
                server = profile.server
            )
        }
    }

    /**
     * Parses standard proxy share URIs.
     */
    fun parseFromUri(uriString: String): OutboundProfile {
        try {
            val uri = Uri.parse(uriString)
            val scheme = uri.scheme?.lowercase() ?: "vless"

            if (scheme == "vmess") {
                return parseVmessLink(uriString)
            }

            val host = uri.host ?: "127.0.0.1"
            val port = if (uri.port > 0) uri.port else 443
            val userInfo = uri.userInfo ?: ""
            val queryParams = mutableMapOf<String, String>()

            uri.query?.split("&")?.forEach { param ->
                val parts = param.split("=")
                if (parts.size == 2) {
                    queryParams[URLDecoder.decode(parts[0], "UTF-8")] = URLDecoder.decode(parts[1], "UTF-8")
                }
            }

            val security = queryParams["security"] ?: "none"
            val isTls = security.equals("tls", true) || security.equals("reality", true)
            val isReality = security.equals("reality", true)

            return OutboundProfile(
                tag = uri.fragment ?: "proxy",
                type = scheme,
                server = host,
                serverPort = port,
                uuid = if (scheme == "vless" || scheme == "tuic") userInfo else null,
                password = if (scheme != "vless") userInfo else null,
                flow = queryParams["flow"],
                tlsEnabled = isTls,
                serverName = queryParams["sni"] ?: host,
                realityEnabled = isReality,
                realityPublicKey = queryParams["pbk"],
                realityShortId = queryParams["sid"],
                utlsFingerprint = queryParams["fp"] ?: "chrome",
                network = queryParams["type"] ?: "tcp",
                wsPath = queryParams["path"],
                grpcServiceName = queryParams["serviceName"]
            )
        } catch (e: Exception) {
            return OutboundProfile(type = "vless", server = "127.0.0.1")
        }
    }

    private fun parseVmessLink(link: String): OutboundProfile {
        try {
            val base64 = link.removePrefix("vmess://")
            val decoded = String(Base64.decode(base64, Base64.DEFAULT), StandardCharsets.UTF_8)
            val json = JSONObject(decoded)

            return OutboundProfile(
                tag = json.optString("ps", "proxy"),
                type = "vmess",
                server = json.optString("add", "127.0.0.1"),
                serverPort = json.optInt("port", 443),
                uuid = json.optString("id", ""),
                alterId = json.optInt("aid", 0),
                security = json.optString("scy", "auto"),
                network = json.optString("net", "tcp"),
                tlsEnabled = json.optString("tls").equals("tls", true),
                serverName = json.optString("sni", json.optString("add")),
                wsPath = json.optString("path", "")
            )
        } catch (e: Exception) {
            return OutboundProfile(type = "vmess", server = "127.0.0.1")
        }
    }
}
