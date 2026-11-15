package com.zenguard.app.core.nowbar

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.zenguard.app.MainActivity
import com.zenguard.app.core.model.ProxyMode
import com.zenguard.app.core.model.VpnState

/**
 * Manages Android 16 Live Updates / Samsung One UI Now Bar notifications.
 * Uses promoted ongoing notification flags without custom RemoteViews so One UI
 * can render the live capsule on the lock screen, AOD, and status bar chip.
 */
object NowBarNotificationManager {

    private const val CHANNEL_ID = "zenguard_live_status"
    private const val NOTIFICATION_ID = 1001
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ZenGuard Live Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Live VPN status & quick toggle for One UI Now Bar"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun updateStatus(
        context: Context,
        state: VpnState,
        mode: ProxyMode,
        profileName: String,
        speedSummary: String
    ) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)

        val openAppIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, NowBarActionReceiver::class.java).apply {
                action = NowBarActionReceiver.ACTION_TOGGLE_VPN
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusLabel = when (state) {
            VpnState.CONNECTED -> "Connected • ${mode.label}"
            VpnState.CONNECTING -> "Connecting…"
            VpnState.DISCONNECTING -> "Disconnecting…"
            VpnState.ERROR -> "Connection Error"
            VpnState.DISCONNECTED -> "Disconnected"
        }

        val actionLabel = if (state == VpnState.CONNECTED || state == VpnState.CONNECTING) {
            "Disconnect"
        } else {
            "Connect"
        }

        val chipText = when (state) {
            VpnState.CONNECTED -> "VPN ON"
            VpnState.CONNECTING -> "VPN…"
            else -> "VPN OFF"
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("ZenGuard • $profileName")
            .setContentText("$statusLabel | $speedSummary")
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(state == VpnState.CONNECTED || state == VpnState.CONNECTING)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)
            .addAction(0, actionLabel, toggleIntent)
            .setUsesChronometer(state == VpnState.CONNECTED)

        if (state == VpnState.CONNECTED) {
            builder.setWhen(System.currentTimeMillis())
        }

        // Request Live Update / One UI Now Bar promotion via standard extras
        builder.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
        builder.extras.putString("android.shortCriticalText", chipText)

        manager.notify(NOTIFICATION_ID, builder.build())
    }
}
