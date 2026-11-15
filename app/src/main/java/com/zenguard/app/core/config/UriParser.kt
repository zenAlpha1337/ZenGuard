package com.zenguard.app.core.config

import android.net.Uri
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Base64
import java.util.UUID

/**
 * Robust parser for various proxy outbound URI schemes:
 * - vless://
 * - vmess://
 * - trojan://
 * - ss:// (SIP002 and legacy)
 * - hysteria2:// / hy2://
 * - tuic://
 */
object UriParser {

    /**
     * Parses a single URI string into an [OutboundNode], or returns null if invalid.
     */
    fun parseUri(rawUri: String): OutboundNode? {
        val trimmed = rawUri.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
            return null
        }

        return try {
            when {
                trimmed.startsWith("vless://", ignoreCase = true) -> parseVless(trimmed)
                trimmed.startsWith("vmess://", ignoreCase = true) -> parseVmess(trimmed)
                trimmed.startsWith("trojan://", ignoreCase = true) -> parseTrojan(trimmed)
                trimmed.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(trimmed)
                trimmed.startsWith("hysteria2://", ignoreCase = true) ||
                        trimmed.startsWith("hy2://", ignoreCase = true) -> parseHysteria2(trimmed)
                trimmed.startsWith("tuic://", ignoreCase = true) -> parseTuic(trimmed)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses batch subscription text, supporting either multi-line plain URIs
     * or Base64-encoded subscription payloads.
     */
    fun parseBatch(text: String): List<OutboundNode> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        // 1. Try decoding as a Base64 subscription bundle first
        val resolvedText = if (!trimmed.startsWith("vless://") &&
            !trimmed.startsWith("vmess://") &&
            !trimmed.startsWith("trojan://") &&
            !trimmed.startsWith("ss://") &&
            !trimmed.startsWith("hysteria2://") &&
            !trimmed.startsWith("hy2://") &&
            !trimmed.startsWith("tuic://")
        ) {
            decodeBase64Safe(trimmed) ?: trimmed
        } else {
            trimmed
        }

        // 2. Parse line by line
        val lines = resolvedText.lines()
        val nodes = mutableListOf<OutboundNode>()

        for (line in lines) {
            val item = parseUri(line)
            if (item != null) {
                nodes.add(item)
            }
        }

        return nodes
    }

    // =========================================================================
    // VLESS parser
    // =========================================================================
    // Format: vless://uuid@host:port?query#name
    private fun parseVless(uriStr: String): OutboundNode? {
        val uri = Uri.parse(uriStr)
        val uuid = uri.userInfo ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443

        val queryParams = parseQueryParams(uri)
        val name = decodeFragment(uri.fragment) ?: "$host:$port"

        val security = queryParams["security"]?.lowercase() ?: "none"
        val pbk = queryParams["pbk"]
        val effectiveSecurity = if (!pbk.isNullOrBlank() || security == "reality") {
            "reality"
        } else if (security == "tls") {
            "tls"
        } else {
            security
        }

        val alpnList = queryParams["alpn"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val networkType = queryParams["type"] ?: queryParams["network"] ?: "tcp"
        val wsHost = queryParams["host"]
        val wsHeaders = if (!wsHost.isNullOrBlank()) mapOf("Host" to wsHost) else emptyMap()

        return OutboundNode(
            id = UUID.randomUUID().toString(),
            name = name,
            protocol = OutboundProtocol.VLESS,
            server = host,
            port = port,
            uuid = uuid,
            security = effectiveSecurity,
            sni = queryParams["sni"] ?: queryParams["peer"],
            fingerprint = queryParams["fp"] ?: queryParams["fingerprint"] ?: "chrome",
            alpn = alpnList,
            publicKey = pbk,
            shortId = queryParams["sid"],
            spiderX = queryParams["spx"],
            flow = queryParams["flow"],
            network = networkType,
            wsPath = queryParams["path"]?.let { safeUrlDecode(it) },
            wsHeaders = wsHeaders,
            grpcServiceName = queryParams["serviceName"],
            allowInsecure = queryParams["allowInsecure"] == "1" || queryParams["insecure"] == "1"
        )
    }

    // =========================================================================
    // VMess parser (v2rayN Base64 JSON format)
    // =========================================================================
    // Format: vmess://<base64(json)>
    private fun parseVmess(uriStr: String): OutboundNode? {
        val base64Content = uriStr.substringAfter("://", "")
        if (base64Content.isEmpty()) return null

        val jsonStr = decodeBase64Safe(base64Content) ?: return null
        val json = JSONObject(jsonStr)

        val server = json.optString("add")
        if (server.isEmpty()) return null

        val portVal = json.opt("port")
        val port = when (portVal) {
            is Number -> portVal.toInt()
            is String -> portVal.toIntOrNull() ?: 443
            else -> 443
        }

        val uuid = json.optString("id")
        val aidVal = json.opt("aid")
        val alterId = when (aidVal) {
            is Number -> aidVal.toInt()
            is String -> aidVal.toIntOrNull() ?: 0
            else -> 0
        }

        val name = json.optString("ps", "$server:$port")
        val net = json.optString("net", "tcp")
        val tlsVal = json.optString("tls", "")
        val security = if (tlsVal.equals("tls", ignoreCase = true) || tlsVal == "1") "tls" else "none"

        val host = json.optString("host", "")
        val sni = json.optString("sni", host)
        val path = json.optString("path", "")
        val alpnStr = json.optString("alpn", "")
        val alpnList = if (alpnStr.isNotBlank()) alpnStr.split(",").map { it.trim() } else emptyList()

        val headers = if (host.isNotBlank()) mapOf("Host" to host) else emptyMap()

        return OutboundNode(
            id = UUID.randomUUID().toString(),
            name = name,
            protocol = OutboundProtocol.VMESS,
            server = server,
            port = port,
            uuid = uuid,
            security = security,
            sni = sni.ifBlank { null },
            fingerprint = json.optString("fp", "chrome").ifBlank { null },
            alpn = alpnList,
            network = net,
            wsPath = path.ifBlank { null },
            wsHeaders = headers,
            alterId = alterId,
            cipher = json.optString("scy", "auto")
        )
    }

    // =========================================================================
    // Trojan parser
    // =========================================================================
    // Format: trojan://password@host:port?query#name
    private fun parseTrojan(uriStr: String): OutboundNode? {
        val uri = Uri.parse(uriStr)
        val password = uri.userInfo ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443

        val queryParams = parseQueryParams(uri)
        val name = decodeFragment(uri.fragment) ?: "$host:$port"
        val alpnList = queryParams["alpn"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val networkType = queryParams["type"] ?: queryParams["network"] ?: "tcp"
        val wsHost = queryParams["host"]
        val wsHeaders = if (!wsHost.isNullOrBlank()) mapOf("Host" to wsHost) else emptyMap()

        val security = queryParams["security"] ?: "tls"

        return OutboundNode(
            id = UUID.randomUUID().toString(),
            name = name,
            protocol = OutboundProtocol.TROJAN,
            server = host,
            port = port,
            password = password,
            security = security,
            sni = queryParams["sni"] ?: queryParams["peer"] ?: host,
            fingerprint = queryParams["fp"] ?: "chrome",
            alpn = alpnList,
            network = networkType,
            wsPath = queryParams["path"]?.let { safeUrlDecode(it) },
            wsHeaders = wsHeaders,
            grpcServiceName = queryParams["serviceName"],
            allowInsecure = queryParams["allowInsecure"] == "1" || queryParams["insecure"] == "1"
        )
    }

    // =========================================================================
    // Shadowsocks parser (SIP002 and legacy)
    // =========================================================================
    // SIP002: ss://base64(method:password)@server:port/?query#name
    // Legacy: ss://base64(method:password@server:port)#name
    private fun parseShadowsocks(uriStr: String): OutboundNode? {
        val withoutScheme = uriStr.substringAfter("://", "")
        if (withoutScheme.isEmpty()) return null

        val hashIdx = withoutScheme.indexOf('#')
        val fragment = if (hashIdx != -1) withoutScheme.substring(hashIdx + 1) else null
        val rawBody = if (hashIdx != -1) withoutScheme.substring(0, hashIdx) else withoutScheme
        val name = decodeFragment(fragment)

        return if (rawBody.contains("@")) {
            // SIP002 format
            val atIdx = rawBody.indexOf('@')
            val userPart = rawBody.substring(0, atIdx)
            val hostPart = rawBody.substring(atIdx + 1)

            val decodedUserInfo = decodeBase64Safe(userPart) ?: userPart
            val colonIdx = decodedUserInfo.indexOf(':')
            if (colonIdx == -1) return null
            val cipher = decodedUserInfo.substring(0, colonIdx)
            val password = decodedUserInfo.substring(colonIdx + 1)

            val (server, port) = parseHostAndPort(hostPart) ?: return null

            OutboundNode(
                id = UUID.randomUUID().toString(),
                name = name ?: "$server:$port",
                protocol = OutboundProtocol.SHADOWSOCKS,
                server = server,
                port = port,
                cipher = cipher,
                password = password
            )
        } else {
            // Legacy base64(method:password@server:port)
            val decoded = decodeBase64Safe(rawBody) ?: return null
            val atIdx = decoded.indexOf('@')
            if (atIdx == -1) return null

            val userPart = decoded.substring(0, atIdx)
            val hostPart = decoded.substring(atIdx + 1)

            val colonIdx = userPart.indexOf(':')
            if (colonIdx == -1) return null
            val cipher = userPart.substring(0, colonIdx)
            val password = userPart.substring(colonIdx + 1)

            val (server, port) = parseHostAndPort(hostPart) ?: return null

            OutboundNode(
                id = UUID.randomUUID().toString(),
                name = name ?: "$server:$port",
                protocol = OutboundProtocol.SHADOWSOCKS,
                server = server,
                port = port,
                cipher = cipher,
                password = password
            )
        }
    }

    // =========================================================================
    // Hysteria2 parser
    // =========================================================================
    // Format: hysteria2://auth@host:port?query#name or hy2://...
    private fun parseHysteria2(uriStr: String): OutboundNode? {
        val uri = Uri.parse(uriStr)
        val auth = uri.userInfo ?: ""
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443

        val queryParams = parseQueryParams(uri)
        val name = decodeFragment(uri.fragment) ?: "$host:$port"
        val alpnList = queryParams["alpn"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        return OutboundNode(
            id = UUID.randomUUID().toString(),
            name = name,
            protocol = OutboundProtocol.HYSTERIA2,
            server = host,
            port = port,
            password = auth,
            security = "tls",
            sni = queryParams["sni"] ?: host,
            alpn = alpnList,
            obfs = queryParams["obfs"],
            obfsPassword = queryParams["obfs-password"],
            allowInsecure = queryParams["insecure"] == "1" || queryParams["allowInsecure"] == "1"
        )
    }

    // =========================================================================
    // TUIC parser
    // =========================================================================
    // Format: tuic://uuid:password@host:port?query#name
    private fun parseTuic(uriStr: String): OutboundNode? {
        val uri = Uri.parse(uriStr)
        val userInfo = uri.userInfo ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443

        val uuid: String
        val password: String?
        if (userInfo.contains(":")) {
            val parts = userInfo.split(":", limit = 2)
            uuid = parts[0]
            password = parts[1]
        } else {
            uuid = userInfo
            password = null
        }

        val queryParams = parseQueryParams(uri)
        val name = decodeFragment(uri.fragment) ?: "$host:$port"
        val alpnList = queryParams["alpn"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        return OutboundNode(
            id = UUID.randomUUID().toString(),
            name = name,
            protocol = OutboundProtocol.TUIC,
            server = host,
            port = port,
            uuid = uuid,
            password = password,
            security = "tls",
            sni = queryParams["sni"] ?: host,
            alpn = alpnList,
            congestionControl = queryParams["congestion_control"] ?: queryParams["congestion"],
            udpRelayMode = queryParams["udp_relay_mode"] ?: queryParams["relay_mode"],
            allowInsecure = queryParams["allow_insecure"] == "1" || queryParams["insecure"] == "1"
        )
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun parseQueryParams(uri: Uri): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            for (name in uri.queryParameterNames) {
                uri.getQueryParameter(name)?.let { map[name] = it }
            }
        } catch (_: Exception) {
            val query = uri.query ?: return emptyMap()
            query.split("&").forEach { pair ->
                val idx = pair.indexOf('=')
                if (idx > 0) {
                    val k = pair.substring(0, idx)
                    val v = pair.substring(idx + 1)
                    map[k] = safeUrlDecode(v)
                }
            }
        }
        return map
    }

    private fun parseHostAndPort(rawHostPort: String): Pair<String, Int>? {
        val clean = rawHostPort.substringBefore('/').substringBefore('?')
        return if (clean.startsWith("[")) {
            val closeBracket = clean.indexOf(']')
            if (closeBracket == -1) return null
            val host = clean.substring(1, closeBracket)
            val portPart = clean.substring(closeBracket + 1).removePrefix(":")
            val port = portPart.toIntOrNull() ?: 8388
            Pair(host, port)
        } else {
            val parts = clean.split(":")
            if (parts.size == 2) {
                val host = parts[0]
                val port = parts[1].toIntOrNull() ?: 8388
                Pair(host, port)
            } else if (parts.size == 1 && parts[0].isNotEmpty()) {
                Pair(parts[0], 8388)
            } else {
                null
            }
        }
    }

    private fun decodeFragment(fragment: String?): String? {
        if (fragment.isNullOrBlank()) return null
        return safeUrlDecode(fragment).trim().ifBlank { null }
    }

    private fun safeUrlDecode(input: String): String {
        return try {
            URLDecoder.decode(input, "UTF-8")
        } catch (_: Exception) {
            input
        }
    }

    /**
     * Safely decodes Base64 string with padding repair and URL-safe / standard support.
     */
    fun decodeBase64Safe(input: String): String? {
        val clean = input.trim().replace("\r", "").replace("\n", "").replace(" ", "")
        if (clean.isEmpty()) return null

        val padded = when (clean.length % 4) {
            2 -> "$clean=="
            3 -> "$clean="
            else -> clean
        }

        val attempts = listOf(
            { Base64.getUrlDecoder().decode(padded) },
            { Base64.getDecoder().decode(padded) },
            { Base64.getUrlDecoder().decode(clean) },
            { Base64.getDecoder().decode(clean) }
        )

        for (fn in attempts) {
            try {
                val bytes = fn()
                return String(bytes, Charsets.UTF_8)
            } catch (_: Exception) {}
        }
        return null
    }
}
