package com.zenguard.app.core.root

import android.util.Log

/**
 * Configuration parameters for the TProxy redirection engine.
 */
data class TProxyConfig(
    val tproxyPort: Int = 12345,
    val tproxyMark: Int = 1,
    val routingMark: Int = 255,
    val tableId: Int = 100,
    val singBoxUid: Int? = null,
    val excludedUids: List<Int> = emptyList(),
    val includedUids: List<Int> = emptyList(),
    val enableIpv6: Boolean = true,
    val preferredBackend: FirewallBackend? = null
)

/**
 * Production-safe transparent proxy (TProxy) manager.
 * Supports dual backends: modern Linux nftables (`nft`) and legacy iptables (`iptables` / `ip6tables`).
 *
 * Configures kernel policy routing (`ip rule` + `ip route`) and netfilter redirection
 * while strictly bypassing loopback, local LAN CIDRs, and sing-box self-traffic to prevent routing loops.
 */
object TProxyManager {

    private const val TAG = "TProxyManager"

    const val CHAIN_PREROUTING = "ZENGUARD_PREROUTING"
    const val CHAIN_OUTPUT = "ZENGUARD_OUTPUT"
    const val NFT_TABLE = "zenguard"

    /**
     * Reserved / Private IPv4 CIDRs that must bypass the proxy to prevent routing loops
     * and allow local network communication.
     */
    val BYPASS_IPV4_CIDRS = listOf(
        "0.0.0.0/8",
        "10.0.0.0/8",
        "100.64.0.0/10",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "224.0.0.0/4",
        "240.0.0.0/4",
        "255.255.255.255/32"
    )

    /**
     * Reserved / Link-local IPv6 CIDRs that must bypass the proxy.
     */
    val BYPASS_IPV6_CIDRS = listOf(
        "::1/128",
        "fc00::/7",
        "fe80::/10",
        "ff00::/8"
    )

    @Volatile
    private var _activeBackend: FirewallBackend? = null

    /**
     * The firewall backend currently in use by active TProxy rules.
     */
    val activeBackend: FirewallBackend?
        get() = _activeBackend

    /**
     * Checks if TProxy rules are currently active.
     */
    val isConfigured: Boolean
        get() = _activeBackend != null

    /**
     * Sets up policy routing and transparent proxy firewall rules.
     * Idempotently removes any existing rules prior to installation.
     */
    @Synchronized
    fun setup(config: TProxyConfig = TProxyConfig()): Result<Unit> {
        if (!RootShellManager.isRootAvailable()) {
            return Result.failure(IllegalStateException("Root access is required to set up TProxy"))
        }

        Log.i(TAG, "Setting up TProxy: port=${config.tproxyPort}, mark=${config.tproxyMark}, table=${config.tableId}")

        // 1. Always perform clean wipe first to prevent stale rule stacking
        cleanup()

        // 2. Setup IP routing table & policy rules
        val routeResult = setupRouting(config)
        if (routeResult.isFailure) {
            cleanup()
            return routeResult
        }

        // 3. Select firewall backend
        val backend = config.preferredBackend ?: RootShellManager.checkFirewallBackend()
        if (backend == FirewallBackend.NONE) {
            cleanup()
            return Result.failure(IllegalStateException("Neither nftables nor iptables is available on this system"))
        }

        // 4. Apply firewall rules according to detected backend
        val fwResult = when (backend) {
            FirewallBackend.NFTABLES -> setupNftables(config)
            FirewallBackend.IPTABLES -> setupIptables(config)
            FirewallBackend.NONE -> Result.failure(IllegalStateException("No firewall backend available"))
        }

        return if (fwResult.isSuccess) {
            _activeBackend = backend
            Log.i(TAG, "TProxy successfully configured using backend: ${backend.displayName}")
            Result.success(Unit)
        } else {
            Log.e(TAG, "Failed to configure firewall rules, cleaning up...")
            cleanup()
            fwResult
        }
    }

    /**
     * Sets up policy routing table and fwmark lookup rules.
     */
    private fun setupRouting(config: TProxyConfig): Result<Unit> {
        val cmds = mutableListOf<String>()

        // Remove any duplicate matching rules first, then add clean rule and route
        cmds.add("while ip rule del fwmark ${config.tproxyMark} lookup ${config.tableId} 2>/dev/null; do :; done")
        cmds.add("ip rule add fwmark ${config.tproxyMark} lookup ${config.tableId} pref 100")
        cmds.add("ip route replace local 0.0.0.0/0 dev lo table ${config.tableId}")

        if (config.enableIpv6) {
            cmds.add("while ip -6 rule del fwmark ${config.tproxyMark} lookup ${config.tableId} 2>/dev/null; do :; done")
            cmds.add("ip -6 rule add fwmark ${config.tproxyMark} lookup ${config.tableId} pref 100")
            cmds.add("ip -6 route replace local ::/0 dev lo table ${config.tableId}")
        }

        val res = RootShellManager.exec(cmds)
        return if (res.isSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Failed to configure ip rule / route: ${res.err.joinToString("\n")}"))
        }
    }

    /**
     * Configures nftables transparent proxy ruleset.
     */
    private fun setupNftables(config: TProxyConfig): Result<Unit> {
        val v4BypassList = BYPASS_IPV4_CIDRS.joinToString(", ")
        val v6BypassList = BYPASS_IPV6_CIDRS.joinToString(", ")

        val nftScript = buildString {
            appendLine("table inet $NFT_TABLE {")

            // Sets for CIDR bypass
            appendLine("    set bypass_v4 {")
            appendLine("        type ipv4_addr")
            appendLine("        flags interval")
            appendLine("        elements = { $v4BypassList }")
            appendLine("    }")

            if (config.enableIpv6) {
                appendLine("    set bypass_v6 {")
                appendLine("        type ipv6_addr")
                appendLine("        flags interval")
                appendLine("        elements = { $v6BypassList }")
                appendLine("    }")
            }

            // PREROUTING chain: intercept incoming & rerouted local packets
            appendLine("    chain prerouting {")
            appendLine("        type filter hook prerouting priority mangle - 1; policy accept;")
            appendLine("        ip daddr @bypass_v4 return")
            if (config.enableIpv6) {
                appendLine("        ip6 daddr @bypass_v6 return")
            }
            appendLine("        meta nfproto ipv4 meta l4proto tcp tproxy ip to :${config.tproxyPort} meta mark set ${config.tproxyMark} accept")
            appendLine("        meta nfproto ipv4 meta l4proto udp tproxy ip to :${config.tproxyPort} meta mark set ${config.tproxyMark} accept")
            if (config.enableIpv6) {
                appendLine("        meta nfproto ipv6 meta l4proto tcp tproxy ip6 to :${config.tproxyPort} meta mark set ${config.tproxyMark} accept")
                appendLine("        meta nfproto ipv6 meta l4proto udp tproxy ip6 to :${config.tproxyPort} meta mark set ${config.tproxyMark} accept")
            }
            appendLine("    }")

            // OUTPUT chain: mark local application traffic to re-route via lo into PREROUTING
            appendLine("    chain output {")
            appendLine("        type route hook output priority mangle - 1; policy accept;")

            // 1. Loopback interface
            appendLine("        oifname \"lo\" return")

            // 2. Bypass sing-box self mark
            appendLine("        meta mark ${config.routingMark} return")

            // 3. Bypass sing-box UID if provided
            config.singBoxUid?.let { uid ->
                appendLine("        meta skuid $uid return")
            }

            // 4. Bypass private / LAN CIDRs
            appendLine("        ip daddr @bypass_v4 return")
            if (config.enableIpv6) {
                appendLine("        ip6 daddr @bypass_v6 return")
            }

            // 5. Per-app split tunneling
            if (config.excludedUids.isNotEmpty()) {
                val excludedList = config.excludedUids.joinToString(", ")
                appendLine("        meta skuid { $excludedList } return")
            }
            if (config.includedUids.isNotEmpty()) {
                val includedList = config.includedUids.joinToString(", ")
                appendLine("        meta skuid != { $includedList } return")
            }

            // Mark remaining TCP/UDP traffic to trigger lookup table 100
            appendLine("        meta l4proto { tcp, udp } meta mark set ${config.tproxyMark} accept")
            appendLine("    }")
            appendLine("}")
        }

        // Apply atomically via temp file in /data/local/tmp
        val tmpConf = "/data/local/tmp/zenguard_nft.conf"
        val writeCmd = "cat << 'EOF' > $tmpConf\n$nftScript\nEOF"
        val applyCmd = "nft -f $tmpConf"
        val cleanupCmd = "rm -f $tmpConf"

        val res = RootShellManager.exec(writeCmd, applyCmd, cleanupCmd)
        return if (res.isSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Failed to apply nftables ruleset: ${res.err.joinToString("\n")}"))
        }
    }

    /**
     * Configures iptables / ip6tables transparent proxy ruleset.
     */
    private fun setupIptables(config: TProxyConfig): Result<Unit> {
        val cmds = mutableListOf<String>()

        // Helper to setup rules for an iptables binary (iptables or ip6tables)
        fun buildIptablesCommands(tool: String, isIpv6: Boolean) {
            val cidrs = if (isIpv6) BYPASS_IPV6_CIDRS else BYPASS_IPV4_CIDRS

            // Clean previous references
            cmds.add("while $tool -w 2 -t mangle -D PREROUTING -j $CHAIN_PREROUTING 2>/dev/null; do :; done")
            cmds.add("while $tool -w 2 -t mangle -D OUTPUT -j $CHAIN_OUTPUT 2>/dev/null; do :; done")
            cmds.add("$tool -w 2 -t mangle -F $CHAIN_PREROUTING 2>/dev/null")
            cmds.add("$tool -w 2 -t mangle -X $CHAIN_PREROUTING 2>/dev/null")
            cmds.add("$tool -w 2 -t mangle -F $CHAIN_OUTPUT 2>/dev/null")
            cmds.add("$tool -w 2 -t mangle -X $CHAIN_OUTPUT 2>/dev/null")

            // Create chains
            cmds.add("$tool -w 2 -t mangle -N $CHAIN_PREROUTING")
            cmds.add("$tool -w 2 -t mangle -N $CHAIN_OUTPUT")

            // PREROUTING: bypass LAN, apply TPROXY
            for (cidr in cidrs) {
                cmds.add("$tool -w 2 -t mangle -A $CHAIN_PREROUTING -d $cidr -j RETURN")
            }
            cmds.add("$tool -w 2 -t mangle -A $CHAIN_PREROUTING -p tcp -j TPROXY --on-port ${config.tproxyPort} --tproxy-mark ${config.tproxyMark}")
            cmds.add("$tool -w 2 -t mangle -A $CHAIN_PREROUTING -p udp -j TPROXY --on-port ${config.tproxyPort} --tproxy-mark ${config.tproxyMark}")

            // OUTPUT: bypass loopback, self marks, UID, private CIDRs, split tunneling, then mark
            cmds.add("$tool -w 2 -t mangle -A $CHAIN_OUTPUT -m mark --mark ${config.routingMark} -j RETURN")
            config.singBoxUid?.let { uid ->
                cmds.add("$tool -w 2 -t mangle -A $CHAIN_OUTPUT -m owner --uid-owner $uid -j RETURN")
            }
            cmds.add("$tool -w 2 -t mangle -A $CHAIN_OUTPUT -o lo -j RETURN")
            for (cidr in cidrs) {
                cmds.add("$tool -w 2 -t mangle -A $CHAIN_OUTPUT -d $cidr -j RETURN")
            }

            // Split tunneling
            if (config.excludedUids.isNotEmpty()) {
                for (uid in config.excludedUids) {
                    cmds.add("$tool -w 2 -t mangle -A $CHAIN_OUTPUT -m owner --uid-owner $uid -j RETURN")
                }
            }

            if (config.includedUids.isNotEmpty()) {
                for (uid in config.includedUids) {
                    cmds.add("$tool -w 2 -t mangle -A $CHAIN_OUTPUT -m owner --uid-owner $uid -p tcp -j MARK --set-mark ${config.tproxyMark}")
                    cmds.add("$tool -w 2 -t mangle -A $CHAIN_OUTPUT -m owner --uid-owner $uid -p udp -j MARK --set-mark ${config.tproxyMark}")
                }
                cmds.add("$tool -w 2 -t mangle -A $CHAIN_OUTPUT -j RETURN")
            } else {
                cmds.add("$tool -w 2 -t mangle -A $CHAIN_OUTPUT -p tcp -j MARK --set-mark ${config.tproxyMark}")
                cmds.add("$tool -w 2 -t mangle -A $CHAIN_OUTPUT -p udp -j MARK --set-mark ${config.tproxyMark}")
            }

            // Hook into standard mangle PREROUTING and OUTPUT
            cmds.add("$tool -w 2 -t mangle -I PREROUTING 1 -j $CHAIN_PREROUTING")
            cmds.add("$tool -w 2 -t mangle -I OUTPUT 1 -j $CHAIN_OUTPUT")
        }

        buildIptablesCommands("iptables", isIpv6 = false)
        if (config.enableIpv6) {
            buildIptablesCommands("ip6tables", isIpv6 = true)
        }

        val res = RootShellManager.exec(cmds)
        return if (res.isSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Failed to configure iptables rules: ${res.err.joinToString("\n")}"))
        }
    }

    /**
     * Idempotent cleanup: cleanly removes all ip rules, ip routes, iptables chains,
     * and deletes the nftables table. Guaranteed safe to call repeatedly or on partially initialized states.
     */
    @Synchronized
    fun cleanup(): Result<Unit> {
        return try {
            val cmds = mutableListOf<String>()

            // 1. Remove nftables table
            cmds.add("nft delete table inet $NFT_TABLE 2>/dev/null")

            // 2. Remove IPv4 iptables rules and chains
            cmds.add("while iptables -w 2 -t mangle -D PREROUTING -j $CHAIN_PREROUTING 2>/dev/null; do :; done")
            cmds.add("while iptables -w 2 -t mangle -D OUTPUT -j $CHAIN_OUTPUT 2>/dev/null; do :; done")
            cmds.add("iptables -w 2 -t mangle -F $CHAIN_PREROUTING 2>/dev/null")
            cmds.add("iptables -w 2 -t mangle -X $CHAIN_PREROUTING 2>/dev/null")
            cmds.add("iptables -w 2 -t mangle -F $CHAIN_OUTPUT 2>/dev/null")
            cmds.add("iptables -w 2 -t mangle -X $CHAIN_OUTPUT 2>/dev/null")

            // 3. Remove IPv6 ip6tables rules and chains
            cmds.add("while ip6tables -w 2 -t mangle -D PREROUTING -j $CHAIN_PREROUTING 2>/dev/null; do :; done")
            cmds.add("while ip6tables -w 2 -t mangle -D OUTPUT -j $CHAIN_OUTPUT 2>/dev/null; do :; done")
            cmds.add("ip6tables -w 2 -t mangle -F $CHAIN_PREROUTING 2>/dev/null")
            cmds.add("ip6tables -w 2 -t mangle -X $CHAIN_PREROUTING 2>/dev/null")
            cmds.add("ip6tables -w 2 -t mangle -F $CHAIN_OUTPUT 2>/dev/null")
            cmds.add("ip6tables -w 2 -t mangle -X $CHAIN_OUTPUT 2>/dev/null")

            // 4. Remove IPv4 policy rules and route table 100
            cmds.add("while ip rule del fwmark 1 lookup 100 2>/dev/null; do :; done")
            cmds.add("while ip rule del fwmark 1 table 100 2>/dev/null; do :; done")
            cmds.add("ip route del local 0.0.0.0/0 dev lo table 100 2>/dev/null")
            cmds.add("ip route flush table 100 2>/dev/null")

            // 5. Remove IPv6 policy rules and route table 100
            cmds.add("while ip -6 rule del fwmark 1 lookup 100 2>/dev/null; do :; done")
            cmds.add("while ip -6 rule del fwmark 1 table 100 2>/dev/null; do :; done")
            cmds.add("ip -6 route del local ::/0 dev lo table 100 2>/dev/null")
            cmds.add("ip -6 route flush table 100 2>/dev/null")

            RootShellManager.exec(cmds)
            _activeBackend = null
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error during TProxy cleanup: ${e.message}", e)
            Result.failure(e)
        }
    }
}
