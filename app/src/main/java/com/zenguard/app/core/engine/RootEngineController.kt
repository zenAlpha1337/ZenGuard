package com.zenguard.app.core.engine

import android.content.Context
import android.os.Process
import android.util.Log
import com.zenguard.app.core.model.ProxyMode
import com.zenguard.app.core.model.VpnProfile
import com.zenguard.app.core.model.VpnState
import com.zenguard.app.core.nowbar.NowBarNotificationManager
import com.zenguard.app.core.nowbar.NowBarStateBridge
import com.zenguard.app.core.root.FirewallBackend
import com.zenguard.app.core.root.RootShellManager
import com.zenguard.app.core.root.TProxyConfig
import com.zenguard.app.core.root.TProxyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

/**
 * Split tunneling configuration specifying package UIDs to include or exclude.
 */
data class SplitTunnelConfig(
    val excludedUids: List<Int> = emptyList(),
    val includedUids: List<Int> = emptyList()
)

/**
 * Orchestrates the root-based proxy engine lifecycle for both `ROOT_TPROXY` and `ROOT_TUN` modes.
 *
 * Responsibilities:
 * 1. Writes sing-box JSON configuration to the app's filesDir.
 * 2. Locates and launches the sing-box native binary with root privileges and PID tracking.
 * 3. Applies transparent proxy rules (TProxyManager) for `ROOT_TPROXY`.
 * 4. Strictly guarantees idempotent cleanup of firewall rules, routes, and background processes
 *    on shutdown or any startup failure.
 */
object RootEngineController {

    private const val TAG = "RootEngineController"
    private const val CONFIG_FILE_NAME = "sing_box_config.json"
    private const val LOG_FILE_NAME = "sing_box.log"

    private val mutex = Mutex()

    private val _vpnState = MutableStateFlow(VpnState.DISCONNECTED)
    val vpnState: StateFlow<VpnState> = _vpnState.asStateFlow()

    private val _activeMode = MutableStateFlow<ProxyMode?>(null)
    val activeMode: StateFlow<ProxyMode?> = _activeMode.asStateFlow()

    private val _activeProfile = MutableStateFlow<VpnProfile?>(null)
    val activeProfile: StateFlow<VpnProfile?> = _activeProfile.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * Optional custom override path for the sing-box binary.
     */
    var customBinaryPath: String? = null

    /**
     * True if the root engine is actively running in connected state.
     */
    val isRunning: Boolean
        get() = _vpnState.value == VpnState.CONNECTED

    /**
     * Starts the root proxy engine in either ROOT_TPROXY or ROOT_TUN mode.
     *
     * @param context Application context used for filesDir access.
     * @param mode Selected root proxy mode (`ROOT_TPROXY` or `ROOT_TUN`).
     * @param profile Optional server connection profile.
     * @param customConfigJson Optional raw sing-box JSON config string to override default generation.
     * @param splitTunnelConfig Optional UIDs for app-based split tunneling.
     * @param tproxyPort Port for TProxy inbound redirection (default 12345).
     */
    suspend fun start(
        context: Context,
        mode: ProxyMode,
        profile: VpnProfile? = null,
        customConfigJson: String? = null,
        splitTunnelConfig: SplitTunnelConfig = SplitTunnelConfig(),
        tproxyPort: Int = 12345
    ): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                if (mode != ProxyMode.ROOT_TPROXY && mode != ProxyMode.ROOT_TUN) {
                    val err = "RootEngineController only supports ROOT_TPROXY or ROOT_TUN, got: $mode"
                    Log.e(TAG, err)
                    return@withContext Result.failure(IllegalArgumentException(err))
                }

                Log.i(TAG, "Starting Root Engine in mode: ${mode.name}")
                _vpnState.value = VpnState.CONNECTING
                _activeMode.value = mode
                _activeProfile.value = profile
                _errorMessage.value = null

                // 1. Verify root permission
                if (!RootShellManager.isRootAvailable()) {
                    val err = "Root access is required but not granted or unavailable"
                    _errorMessage.value = err
                    _vpnState.value = VpnState.ERROR
                    return@withContext Result.failure(IllegalStateException(err))
                }

                // 2. Pre-cleanup to clear any residual states or orphaned processes
                cleanupOnFailure()

                // 3. Resolve sing-box binary
                val binaryFile = resolveSingBoxBinary(context)
                    ?: run {
                        val err = "sing-box binary could not be found in nativeLibraryDir, filesDir, or system PATH"
                        _errorMessage.value = err
                        _vpnState.value = VpnState.ERROR
                        return@withContext Result.failure(FileNotFoundException(err))
                    }

                // 4. Generate and write configuration file to app filesDir
                val configFile = File(context.filesDir, CONFIG_FILE_NAME)
                val configContent = customConfigJson ?: generateDefaultConfig(mode, profile, tproxyPort)
                configFile.writeText(configContent)
                Log.d(TAG, "Config written to ${configFile.absolutePath}")

                // 5. Start background sing-box process
                val logFile = File(context.filesDir, LOG_FILE_NAME)
                val startRes = RootShellManager.startSingBox(
                    binaryPath = binaryFile.absolutePath,
                    configPath = configFile.absolutePath,
                    logPath = logFile.absolutePath
                )

                if (startRes.isFailure) {
                    val error = startRes.exceptionOrNull() ?: Exception("Unknown error starting sing-box")
                    Log.e(TAG, "Failed to start sing-box binary", error)
                    cleanupOnFailure()
                    _errorMessage.value = error.message
                    _vpnState.value = VpnState.ERROR
                    return@withContext Result.failure(error)
                }

                // 6. Configure TProxy firewall & routing rules if mode is ROOT_TPROXY
                if (mode == ProxyMode.ROOT_TPROXY) {
                    val myUid = Process.myUid()
                    val tproxyConfig = TProxyConfig(
                        tproxyPort = tproxyPort,
                        tproxyMark = 1,
                        routingMark = 255,
                        tableId = 100,
                        singBoxUid = myUid,
                        excludedUids = splitTunnelConfig.excludedUids,
                        includedUids = splitTunnelConfig.includedUids,
                        enableIpv6 = true
                    )

                    val tproxyRes = TProxyManager.setup(tproxyConfig)
                    if (tproxyRes.isFailure) {
                        val error = tproxyRes.exceptionOrNull() ?: Exception("Failed to configure TProxy firewall")
                        Log.e(TAG, "Failed to setup TProxy rules", error)
                        cleanupOnFailure()
                        _errorMessage.value = error.message
                        _vpnState.value = VpnState.ERROR
                        return@withContext Result.failure(error)
                    }
                }

                // 7. Update state to CONNECTED
                _vpnState.value = VpnState.CONNECTED
                Log.i(TAG, "Root Engine successfully CONNECTED in mode: ${mode.name}")

                // 8. Update notification and live capsule
                updateNotification(context, VpnState.CONNECTED, mode, profile?.name ?: mode.label)

                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error starting root engine: ${e.message}", e)
                cleanupOnFailure()
                _errorMessage.value = e.message
                _vpnState.value = VpnState.ERROR
                Result.failure(e)
            }
        }
    }

    /**
     * Stops the root engine and cleanly removes all routing rules, firewall tables,
     * and running sing-box background processes.
     */
    suspend fun stop(context: Context? = null): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Stopping Root Engine...")
                _vpnState.value = VpnState.DISCONNECTING

                // Guaranteed idempotent cleanup
                cleanupOnFailure()

                _vpnState.value = VpnState.DISCONNECTED
                val lastMode = _activeMode.value ?: ProxyMode.ROOT_TPROXY
                _activeMode.value = null
                _activeProfile.value = null

                if (context != null) {
                    updateNotification(context, VpnState.DISCONNECTED, lastMode, "Disconnected")
                }

                Log.i(TAG, "Root Engine successfully stopped.")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping Root Engine: ${e.message}", e)
                _vpnState.value = VpnState.ERROR
                _errorMessage.value = e.message
                Result.failure(e)
            }
        }
    }

    /**
     * Resolves the location of the sing-box binary on the device.
     */
    fun resolveSingBoxBinary(context: Context): File? {
        // Check user override
        customBinaryPath?.let { customPath ->
            val file = File(customPath)
            if (file.exists()) {
                RootShellManager.exec("chmod 755 \"${file.absolutePath}\"")
                return file
            }
        }

        return RootShellManager.resolveSingBoxBinary(context)
    }

    /**
     * Generates a minimal, valid sing-box JSON configuration for ROOT_TPROXY or ROOT_TUN mode.
     */
    private fun generateDefaultConfig(
        mode: ProxyMode,
        profile: VpnProfile?,
        tproxyPort: Int
    ): String {
        val serverOutbound = if (profile != null) {
            """
            {
              "type": "${profile.protocol.lowercase()}",
              "tag": "proxy-out",
              "server": "${profile.server}",
              "routing_mark": 255
            }
            """.trimIndent()
        } else {
            """
            {
              "type": "direct",
              "tag": "direct-out",
              "routing_mark": 255
            }
            """.trimIndent()
        }

        return if (mode == ProxyMode.ROOT_TPROXY) {
            """
            {
              "log": {
                "level": "warn",
                "timestamp": true
              },
              "inbounds": [
                {
                  "type": "tproxy",
                  "tag": "tproxy-in",
                  "listen": "::",
                  "listen_port": $tproxyPort,
                  "sniff": true,
                  "sniff_override_destination": false
                }
              ],
              "outbounds": [
                $serverOutbound,
                {
                  "type": "direct",
                  "tag": "fallback-direct",
                  "routing_mark": 255
                }
              ],
              "route": {
                "auto_detect_interface": true
              }
            }
            """.trimIndent()
        } else {
            // ROOT_TUN mode: sing-box configures tun0 interface directly via root
            """
            {
              "log": {
                "level": "warn",
                "timestamp": true
              },
              "inbounds": [
                {
                  "type": "tun",
                  "tag": "tun-in",
                  "interface_name": "zenguard-tun",
                  "inet4_address": "172.19.0.1/30",
                  "inet6_address": "fdfe:dcba:9876::1/126",
                  "auto_route": true,
                  "strict_route": true,
                  "stack": "system",
                  "sniff": true
                }
              ],
              "outbounds": [
                $serverOutbound,
                {
                  "type": "direct",
                  "tag": "fallback-direct"
                }
              ],
              "route": {
                "auto_detect_interface": true
              }
            }
            """.trimIndent()
        }
    }

    /**
     * Ensures all TProxy rules and sing-box background processes are terminated.
     * Guaranteed never to throw.
     */
    private fun cleanupOnFailure() {
        try {
            TProxyManager.cleanup()
        } catch (e: Exception) {
            Log.e(TAG, "Error during TProxyManager cleanup: ${e.message}", e)
        }

        try {
            RootShellManager.stopSingBox()
        } catch (e: Exception) {
            Log.e(TAG, "Error during RootShellManager stopSingBox: ${e.message}", e)
        }
    }

    /**
     * Updates One UI Now Bar / Live Updates ongoing notification.
     */
    private fun updateNotification(
        context: Context,
        state: VpnState,
        mode: ProxyMode,
        label: String
    ) {
        try {
            NowBarNotificationManager.updateStatus(
                context = context,
                state = state,
                mode = mode,
                profileName = label,
                speedSummary = if (state == VpnState.CONNECTED) "Active" else "Idle"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification: ${e.message}")
        }
    }

    /**
     * Connects NowBarStateBridge toggle events directly to this RootEngineController.
     */
    fun attachToNowBarBridge(context: Context, scope: CoroutineScope) {
        NowBarStateBridge.onToggleRequested = {
            scope.launch {
                if (isRunning) {
                    stop(context)
                } else {
                    val mode = _activeMode.value ?: ProxyMode.ROOT_TPROXY
                    start(context, mode, _activeProfile.value)
                }
            }
        }
    }
}
