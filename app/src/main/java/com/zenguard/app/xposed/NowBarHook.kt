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
            "com.samsung.android.systemui.nowbar.NowBarAllowlistManager"
        )

        for (className in candidateClasses) {
            val clazz = XposedHelpers.findClassIfExists(className, classLoader) ?: continue
            XposedBridge.hookAllMethods(clazz, "isAllowed", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val pkg = param.args.firstOrNull() as? String
                    if (pkg == ZENGUARD_PKG) {
                        param.result = true
                    }
                }
            })
            XposedBridge.log("ZenGuard: Hooked $className.isAllowed for Now Bar")
        }
    }

    companion object {
        private const val ZENGUARD_PKG = "com.zenguard.app"
        private const val SYSTEM_UI_PKG = "com.android.systemui"
        private const val AOD_SERVICE_PKG = "com.samsung.android.app.aodservice"
    }
}
