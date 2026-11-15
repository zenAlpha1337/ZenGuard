package com.zenguard.app.ui.navigation

import android.app.Activity
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AltRoute
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.zenguard.app.core.config.UriParser
import com.zenguard.app.core.data.ProfileRepository
import com.zenguard.app.core.engine.ZenCoreEngine
import com.zenguard.app.core.model.ProxyMode
import com.zenguard.app.core.model.VpnState
import com.zenguard.app.core.network.SubscriptionManager
import com.zenguard.app.core.nowbar.NowBarStateBridge
import com.zenguard.app.ui.screens.home.HomeScreen
import com.zenguard.app.ui.screens.profiles.ProfilesScreen
import com.zenguard.app.ui.screens.routing.RoutingScreen
import com.zenguard.app.ui.screens.settings.SettingsScreen
import kotlinx.coroutines.launch

private enum class ZenTab(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Rounded.Home),
    PROFILES("profiles", "Profiles", Icons.Rounded.Dns),
    ROUTING("routing", "Routing", Icons.AutoMirrored.Rounded.AltRoute),
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
    val coreEngine = remember(context) {
        ZenCoreEngine.apply { attachContext(context) }
    }

    val nodes by repository.nodes.collectAsState()
    val activeNodeId by repository.activeNodeId.collectAsState()
    val selectedMode by repository.proxyMode.collectAsState()
    val routingConfig by repository.routingConfig.collectAsState()

    val vpnState by coreEngine.state.collectAsState()
    val trafficStats by coreEngine.traffic.collectAsState()

    val activeNode = nodes.firstOrNull { it.id == activeNodeId } ?: nodes.firstOrNull()
    val activeProfileName = activeNode?.name ?: "No Profile"

    fun startCoreEngine() {
        val node = activeNode ?: run {
            Toast.makeText(context, "Add or select a profile first", Toast.LENGTH_SHORT).show()
            return
        }
        coreEngine.start(
            mode = selectedMode,
            profile = node.toVpnProfile(),
            routingConfig = routingConfig.toEngineRoutingConfig()
        )
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startCoreEngine()
        } else {
            Toast.makeText(context, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    fun toggleVpn() {
        if (vpnState == VpnState.CONNECTED || vpnState == VpnState.CONNECTING) {
            coreEngine.stop()
            return
        }
        if (selectedMode == ProxyMode.VPN_TUN) {
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
                vpnPermissionLauncher.launch(prepareIntent)
                return
            }
        }
        startCoreEngine()
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
                    trafficStats = trafficStats,
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
                            Toast.makeText(context, "Imported ${parsed.size} node(s)", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "No valid nodes found", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onImportSubscription = { url ->
                        scope.launch {
                            runCatching { SubscriptionManager.fetchSubscription(url) }
                                .onSuccess { fetched ->
                                    if (fetched.isNotEmpty()) {
                                        repository.addNodes(fetched)
                                        Toast.makeText(context, "Loaded ${fetched.size} node(s)", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Subscription returned 0 nodes", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .onFailure { err ->
                                    Toast.makeText(context, "Sub error: ${err.message}", Toast.LENGTH_LONG).show()
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
