package com.zenguard.app.core.nowbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NowBarActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE_VPN) {
            // Stage 2/3 will route this to CoreEngine (TUN / Root TProxy)
            NowBarStateBridge.onToggleRequested?.invoke()
        }
    }

    companion object {
        const val ACTION_TOGGLE_VPN = "com.zenguard.app.ACTION_TOGGLE_VPN"
    }
}

object NowBarStateBridge {
    var onToggleRequested: (() -> Unit)? = null
}
