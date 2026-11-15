package com.zenguard.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.zenguard.app.core.config.UriParser
import com.zenguard.app.core.data.ProfileRepository
import com.zenguard.app.core.model.ProxyMode
import com.zenguard.app.core.model.TrafficStats
import com.zenguard.app.core.model.VpnState
import com.zenguard.app.core.network.SubscriptionManager
import com.zenguard.app.core.nowbar.NowBarNotificationManager
import com.zenguard.app.core.nowbar.NowBarStateBridge
import com.zenguard.app.ui.screens.home.HomeScreen
import com.zenguard.app.ui.screens.profiles.ProfilesScreen
import com.zenguard.app.ui.screens.routing.RoutingScreen
import com.zenguard.app.ui.screens.settings.SettingsScreen
import kotlinx.coroutines.launch

private enum class ZenTab(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Rounded.Home),
    PROFILES("profiles", "Profiles", Icons.Rounded.Dns),
    ROUTING("routing", "Routing", Icons.Rounded.AltRoute),
    SETTINGS("settings", "Settings", Icons.Rounded.Settings)
}

@Composable
fun ZenNavGraph() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: ZenTab.HOME.route

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(context) { ProfileRepository.getInstance(context) }

    val nodes by repository.nodes.collectAsState()
    val activeNodeId by repository.activeNodeId.collectAsState()
    val selectedMode by repository.proxyMode.collectAsState()
    val routingConfig by repository.routingConfig.collectAsState()

    var vpnState by remember { mutableStateOf(VpnState.DISCONNECTED) }
    val activeNode = nodes.firstOrNull { it.id == activeNodeId } ?: nodes.firstOrNull()
    val activeProfileName = activeNode?.name ?: "Default VLESS Reality"

    fun toggleVpn() {
        vpnState = if (vpnState == VpnState.CONNECTED) VpnState.DISCONNECTED else VpnState.CONNECTED
        NowBarNotificationManager.updateStatus(
            context = context,
            state = vpnState,
            mode = selectedMode,
            profileName = activeProfileName,
            speedSummary = if (vpnState == VpnState.CONNECTED) "↓ 1.2 MB/s ↑ 140 KB/s" else "Idle"
        )
    }

    DisposableEffect(Unit) {
        NowBarStateBridge.onToggleRequested = { toggleVpn() }
        onDispose { NowBarStateBridge.onToggleRequested = null }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                ZenTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(ZenTab.HOME.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = ZenTab.HOME.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(ZenTab.HOME.route) {
                HomeScreen(
                    vpnState = vpnState,
                    selectedMode = selectedMode,
                    trafficStats = if (vpnState == VpnState.CONNECTED) {
                        TrafficStats(uplinkBps = 140_000, downlinkBps = 1_200_000)
                    } else {
                        TrafficStats()
                    },
                    activeProfileName = activeProfileName,
                    onToggleConnection = { toggleVpn() },
                    onSelectMode = { repository.setProxyMode(it) }
                )
            }
            composable(ZenTab.PROFILES.route) {
                ProfilesScreen(
                    profiles = nodes.map { it.toVpnProfile() },
                    selectedProfileId = activeNodeId,
                    onSelectProfile = { repository.setActiveNodeId(it.id) },
                    onImportUri = { raw ->
                        val parsed = UriParser.parseBatch(raw)
                        if (parsed.isNotEmpty()) {
                            repository.addNodes(parsed)
                        }
                    },
                    onImportSubscription = { url ->
                        scope.launch {
                            runCatching { SubscriptionManager.fetchSubscription(url) }
                                .onSuccess { fetched ->
                                    if (fetched.isNotEmpty()) repository.addNodes(fetched)
                                }
                        }
                    },
                    onDeleteProfile = { repository.deleteNode(it) },
                    onPingProfile = { id ->
                        val target = nodes.firstOrNull { it.id == id } ?: return@ProfilesScreen
                        scope.launch {
                            val tested = SubscriptionManager.measureLatency(listOf(target))
                            repository.updateNodesLatency(tested)
                        }
                    },
                    onPingAll = {
                        scope.launch {
                            val tested = SubscriptionManager.measureLatency(nodes)
                            repository.updateNodesLatency(tested)
                        }
                    }
                )
            }
            composable(ZenTab.ROUTING.route) {
                RoutingScreen(
                    bypassLan = routingConfig.bypassLan,
                    onBypassLanChange = { repository.updateRoutingConfig(routingConfig.copy(bypassLan = it)) },
                    bypassRuLocal = routingConfig.bypassRu,
                    onBypassRuLocalChange = { repository.updateRoutingConfig(routingConfig.copy(bypassRu = it)) },
                    enableFakeIp = routingConfig.fakeIp,
                    onEnableFakeIpChange = { repository.updateRoutingConfig(routingConfig.copy(fakeIp = it)) }
                )
            }
            composable(ZenTab.SETTINGS.route) {
                SettingsScreen()
            }
        }
    }
}
