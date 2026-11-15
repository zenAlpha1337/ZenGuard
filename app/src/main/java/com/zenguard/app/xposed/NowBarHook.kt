package com.zenguard.app.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed / Xposed hook for Samsung One UI 7/8 Now Bar.
 * Targets com.android.systemui and com.samsung.android.app.aodservice to allowlist
 * ZenGuard's promoted ongoing notification in the Now Bar without manual Developer Options.
 */
class NowBarHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != SYSTEM_UI_PKG && lpparam.packageName != AOD_SERVICE_PKG) {
            return
        }

        XposedBridge.log("ZenGuard: Loaded NowBarHook in ${lpparam.packageName}")

        if (lpparam.packageName == SYSTEM_UI_PKG) {
            hookPromotedNotificationAllowlist(lpparam.classLoader)
        }
    }

    private fun hookPromotedNotificationAllowlist(classLoader: ClassLoader) {
        val candidateClasses = listOf(
            "com.android.systemui.statusbar.notification.collection.coordinator.PromotedNotificationsCoordinator",
            "com.android.systemui.statusbar.notification.SubscreenNotificationInfoManager",
            "com.samsung.android.systemui.nowbar.NowBarAllowlistManager",
            "com.android.systemui.statusbar.phone.ongoingactivity.OngoingActivityDataHelper",
            "com.android.systemui.statusbar.phone.ongoingactivity.OngoingActivityController"
        )

        for (className in candidateClasses) {
            val clazz = XposedHelpers.findClassIfExists(className, classLoader) ?: continue

            // Hook isAllowed -> return true for ZenGuard
            try {
                XposedBridge.hookAllMethods(clazz, "isAllowed", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (belongsToZenGuard(param)) {
                            param.result = true
                        }
                    }
                })
                XposedBridge.log("ZenGuard: Hooked $className.isAllowed for Now Bar")
            } catch (t: Throwable) {
                XposedBridge.log("ZenGuard: Failed to hook $className.isAllowed: ${t.message}")
            }

            // Hook isSupported -> return true for ZenGuard
            try {
                XposedBridge.hookAllMethods(clazz, "isSupported", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (belongsToZenGuard(param)) {
                            param.result = true
                        }
                    }
                })
                XposedBridge.log("ZenGuard: Hooked $className.isSupported for Now Bar")
            } catch (t: Throwable) {
                XposedBridge.log("ZenGuard: Failed to hook $className.isSupported: ${t.message}")
            }

            // Hook shouldHide -> return false for ZenGuard
            try {
                XposedBridge.hookAllMethods(clazz, "shouldHide", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (belongsToZenGuard(param)) {
                            param.result = false
                        }
                    }
                })
                XposedBridge.log("ZenGuard: Hooked $className.shouldHide for Now Bar")
            } catch (t: Throwable) {
                XposedBridge.log("ZenGuard: Failed to hook $className.shouldHide: ${t.message}")
            }
        }
    }

    private fun belongsToZenGuard(param: XC_MethodHook.MethodHookParam): Boolean {
        val args = param.args ?: return false
        for (arg in args) {
            if (arg == null) continue
            if (arg is String) {
                if (arg == ZENGUARD_PKG || arg.contains(ZENGUARD_PKG)) {
                    return true
                }
            } else {
                try {
                    val clazz = arg.javaClass
                    val pkgMethod = clazz.methods.firstOrNull { it.name == "getPackageName" && it.parameterTypes.isEmpty() }
                    val pkg = pkgMethod?.invoke(arg) as? String
                    if (pkg == ZENGUARD_PKG) return true

                    val sbnMethod = clazz.methods.firstOrNull { it.name == "getSbn" && it.parameterTypes.isEmpty() }
                    val sbn = sbnMethod?.invoke(arg)
                    if (sbn != null) {
                        val sbnPkgMethod = sbn.javaClass.methods.firstOrNull { it.name == "getPackageName" && it.parameterTypes.isEmpty() }
                        val sbnPkg = sbnPkgMethod?.invoke(sbn) as? String
                        if (sbnPkg == ZENGUARD_PKG) return true
                    }

                    if (arg.toString().contains(ZENGUARD_PKG)) return true
                } catch (_: Throwable) {
                    // Ignore reflection errors on non-matching objects
                }
            }
        }
        return false
    }

    companion object {
        private const val ZENGUARD_PKG = "com.zenguard.app"
        private const val SYSTEM_UI_PKG = "com.android.systemui"
        private const val AOD_SERVICE_PKG = "com.samsung.android.app.aodservice"
    }
}
