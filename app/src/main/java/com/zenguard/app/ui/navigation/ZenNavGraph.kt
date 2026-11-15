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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.zenguard.app.core.model.ProxyMode
import com.zenguard.app.core.model.TrafficStats
import com.zenguard.app.core.model.VpnState
import com.zenguard.app.core.nowbar.NowBarNotificationManager
import com.zenguard.app.core.nowbar.NowBarStateBridge
import com.zenguard.app.ui.screens.PlaceholderScreen
import com.zenguard.app.ui.screens.home.HomeScreen

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
    var vpnState by remember { mutableStateOf(VpnState.DISCONNECTED) }
    var selectedMode by remember { mutableStateOf(ProxyMode.VPN_TUN) }
    val activeProfileName = "Default VLESS Reality"

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
                    onSelectMode = { selectedMode = it }
                )
            }
            composable(ZenTab.PROFILES.route) {
                PlaceholderScreen("Profiles & Subscriptions", "VLESS / Hysteria2 / Trojan / Clash YAML (Stage 4)")
            }
            composable(ZenTab.ROUTING.route) {
                PlaceholderScreen("Routing & Split Tunneling", "Per-app / UID TProxy rules & .srs rule-sets (Stage 3–4)")
            }
            composable(ZenTab.SETTINGS.route) {
                PlaceholderScreen("Settings", "Root TProxy, One UI Now Bar Hook, DNS & Theme")
            }
        }
    }
}
