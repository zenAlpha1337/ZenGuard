package com.zenguard.app.core.root

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.Shell
import java.io.File

/**
 * Root implementation types supported by Android devices.
 */
enum class RootManager(val displayName: String) {
    MAGISK("Magisk"),
    KERNEL_SU("KernelSU"),
    APATCH("APatch"),
    UNKNOWN("Unknown Root"),
    NONE("No Root")
}

/**
 * Firewall backend supported by the device kernel.
 */
enum class FirewallBackend(val displayName: String) {
    NFTABLES("nftables"),
    IPTABLES("iptables"),
    NONE("None")
}

/**
 * Manages root access, root manager detection, firewall capabilities,
 * and background sing-box native process execution via libsu.
 */
object RootShellManager {

    private const val TAG = "RootShellManager"

    @Volatile
    private var _singBoxPid: Int? = null

    /**
     * Currently tracked PID of the running sing-box process, if any.
     */
    val singBoxPid: Int?
        get() = _singBoxPid

    /**
     * Synchronously checks if root access is granted and available to the application.
     */
    fun isRootAvailable(): Boolean {
        return try {
            Shell.isAppGrantedRoot() == true || Shell.getShell().isRoot
        } catch (e: Exception) {
            Log.w(TAG, "isRootAvailable check failed: ${e.message}")
            false
        }
    }

    /**
     * Detects the root manager installed on the device (Magisk, KernelSU, APatch).
     */
    fun detectRootManager(): RootManager {
        if (!isRootAvailable()) return RootManager.NONE

        try {
            // Check su version string first (e.g. "26.4:MAGISK", "11742:KernelSU", "10763:APatch")
            val suRes = exec("su -v")
            if (suRes.isSuccess) {
                val suOutput = suRes.out.joinToString(" ").lowercase()
                when {
                    suOutput.contains("magisk") -> return RootManager.MAGISK
                    suOutput.contains("kernelsu") -> return RootManager.KERNEL_SU
                    suOutput.contains("apatch") -> return RootManager.APATCH
                }
            }

            // Check binary signatures and characteristic directories
            if (execQuick("which magisk") || execQuick("magisk -v") || execQuick("test -d /data/adb/magisk")) {
                return RootManager.MAGISK
            }

            if (execQuick("which ksud") || execQuick("ksud -V") || execQuick("test -d /data/adb/ksu")) {
                return RootManager.KERNEL_SU
            }

            if (execQuick("which apd") || execQuick("apd -V") || execQuick("test -d /data/adb/ap")) {
                return RootManager.APATCH
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error detecting root manager: ${e.message}")
        }

        return RootManager.UNKNOWN
    }

    /**
     * Retrieves root version string (e.g. from `su -v` or `magisk -v`).
     */
    fun getSuVersion(): String? {
        if (!isRootAvailable()) return null
        val res = exec("su -v")
        return if (res.isSuccess && res.out.isNotEmpty()) {
            res.out.joinToString(" ").trim()
        } else {
            null
        }
    }

    /**
     * Checks if nftables (`nft`) is available and functioning in the kernel.
     */
    fun hasNftables(): Boolean {
        if (!isRootAvailable()) return false
        return execQuick("which nft") && execQuick("nft list tables 2>/dev/null")
    }

    /**
     * Checks if iptables with mangle table support is available in the kernel.
     */
    fun hasIptables(): Boolean {
        if (!isRootAvailable()) return false
        return execQuick("which iptables") && execQuick("iptables -w 1 -t mangle -L -n 2>/dev/null")
    }

    /**
     * Detects preferred firewall backend: prefers nftables if supported, falls back to iptables.
     */
    fun checkFirewallBackend(): FirewallBackend {
        return when {
            hasNftables() -> FirewallBackend.NFTABLES
            hasIptables() -> FirewallBackend.IPTABLES
            else -> FirewallBackend.NONE
        }
    }

    /**
     * Resolves the location of the sing-box binary on the device.
     * Searches in nativeLibraryDir (e.g. libsing-box.so extracted from APK),
     * app internal files directories, common system paths, and system PATH.
     * Ensures executable permissions (`chmod 755`) before returning.
     *
     * @param context Application context used to query nativeLibraryDir and filesDir.
     * @return File pointing to executable sing-box binary, or null if not found.
     */
    fun resolveSingBoxBinary(context: Context): File? {
        // 1. App's nativeLibraryDir (e.g. libsing-box.so)
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val candidateNames = listOf("libsing-box.so", "libsingbox.so", "sing-box")
        for (name in candidateNames) {
            val file = File(nativeDir, name)
            if (file.exists()) {
                if (!file.canExecute()) {
                    file.setExecutable(true, false)
                }
                exec("chmod 755 \"${file.absolutePath}\"")
                return file
            }
        }

        // 2. App internal files directories
        val filesDir = context.filesDir
        val internalCandidates = listOf(
            File(filesDir, "sing-box"),
            File(filesDir, "libsing-box.so"),
            File(File(filesDir, "bin"), "sing-box")
        )
        for (file in internalCandidates) {
            if (file.exists()) {
                if (!file.canExecute()) {
                    file.setExecutable(true, false)
                }
                exec("chmod 755 \"${file.absolutePath}\"")
                return file
            }
        }

        // 3. Common rooted device binaries & paths
        val systemPaths = listOf(
            "/data/local/tmp/sing-box",
            "/system/bin/sing-box",
            "/system/xbin/sing-box"
        )
        for (path in systemPaths) {
            val file = File(path)
            if (file.exists()) {
                exec("chmod 755 \"${file.absolutePath}\"")
                return file
            }
        }

        // 4. Query PATH via root shell
        try {
            val whichRes = exec("which sing-box")
            if (whichRes.isSuccess && whichRes.out.isNotEmpty()) {
                val foundPath = whichRes.out.first().trim()
                if (foundPath.isNotEmpty()) {
                    val file = File(foundPath)
                    if (file.exists()) {
                        exec("chmod 755 \"${file.absolutePath}\"")
                        return file
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }

    /**
     * Starts the sing-box native binary in the background with pid tracking.
     *
     * @param binaryPath Absolute path to the sing-box executable.
     * @param configPath Absolute path to the sing-box JSON configuration file.
     * @param logPath Optional path to write sing-box stdout/stderr logs.
     * @return Result containing the integer process ID on success.
     */
    @Synchronized
    fun startSingBox(
        binaryPath: String,
        configPath: String,
        logPath: String? = null
    ): Result<Int> {
        if (!isRootAvailable()) {
            return Result.failure(IllegalStateException("Root access is not available"))
        }

        val binFile = File(binaryPath)
        if (!binFile.exists()) {
            return Result.failure(IllegalArgumentException("sing-box binary not found at $binaryPath"))
        }
        val cfgFile = File(configPath)
        if (!cfgFile.exists()) {
            return Result.failure(IllegalArgumentException("sing-box config not found at $configPath"))
        }

        // Stop any currently running instance first
        stopSingBox()

        // Ensure binary has executable permissions
        exec("chmod 755 \"$binaryPath\"")

        val logRedirect = if (!logPath.isNullOrBlank()) {
            val logFile = File(logPath)
            logFile.parentFile?.mkdirs()
            "> \"$logPath\" 2>&1"
        } else {
            "> /dev/null 2>&1"
        }

        // Launch in background detached and retrieve PID via $!
        val launchCmd = "\"$binaryPath\" run -c \"$configPath\" $logRedirect & echo \$!"
        val result = exec(launchCmd)

        if (!result.isSuccess || result.out.isEmpty()) {
            val errMsg = result.err.joinToString("\n").ifBlank { "Unknown execution error" }
            return Result.failure(IllegalStateException("Failed to launch sing-box: $errMsg"))
        }

        val pid = result.out.lastOrNull()?.trim()?.toIntOrNull()
            ?: return Result.failure(IllegalStateException("Failed to parse PID from output: ${result.out.joinToString("\n")}"))

        // Brief delay to allow sing-box to initialize or catch early crash
        try {
            Thread.sleep(250)
        } catch (_: InterruptedException) {}

        if (!isProcessRunning(pid)) {
            val logExcerpt = if (!logPath.isNullOrBlank() && File(logPath).exists()) {
                File(logPath).readLines().takeLast(10).joinToString("\n")
            } else {
                result.err.joinToString("\n")
            }
            return Result.failure(
                IllegalStateException("sing-box terminated immediately after start (PID $pid). Log:\n$logExcerpt")
            )
        }

        _singBoxPid = pid
        Log.i(TAG, "sing-box started successfully with PID $pid")
        return Result.success(pid)
    }

    /**
     * Checks if a process with the given PID is currently active.
     */
    fun isProcessRunning(pid: Int): Boolean {
        if (pid <= 0) return false
        val res = exec("kill -0 $pid 2>/dev/null")
        return res.isSuccess
    }

    /**
     * Stops the sing-box process gracefully (SIGTERM) and forces kill (SIGKILL) if needed.
     */
    @Synchronized
    fun stopSingBox(pid: Int? = _singBoxPid): Boolean {
        val targetPid = pid ?: _singBoxPid
        var terminated = false

        if (targetPid != null && targetPid > 0) {
            Log.i(TAG, "Stopping sing-box process (PID $targetPid)...")
            exec("kill -15 $targetPid 2>/dev/null")

            // Wait up to 1 second for graceful termination
            for (i in 0 until 10) {
                if (!isProcessRunning(targetPid)) {
                    terminated = true
                    break
                }
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {}
            }

            // Force kill if still lingering
            if (!terminated && isProcessRunning(targetPid)) {
                Log.w(TAG, "sing-box (PID $targetPid) did not exit gracefully, sending SIGKILL")
                exec("kill -9 $targetPid 2>/dev/null")
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {}
                terminated = !isProcessRunning(targetPid)
            }
        }

        // Clean up any remaining zombie or orphan sing-box processes
        exec("killall -15 sing-box 2>/dev/null", "pkill -f \"sing-box run\" 2>/dev/null")

        _singBoxPid = null
        return true
    }

    /**
     * Executes shell commands with root privileges.
     */
    fun exec(vararg commands: String): Shell.Result {
        return Shell.cmd(*commands).exec()
    }

    /**
     * Executes a list of shell commands with root privileges.
     */
    fun exec(commands: List<String>): Shell.Result {
        return Shell.cmd(*commands.toTypedArray()).exec()
    }

    /**
     * Executes a shell command and returns whether the exit code was 0.
     */
    fun execQuick(command: String): Boolean {
        return Shell.cmd(command).exec().isSuccess
    }

    /**
     * Executes a shell command and returns its stdout lines.
     */
    fun execOut(command: String): List<String> {
        return Shell.cmd(command).exec().out
    }
}
