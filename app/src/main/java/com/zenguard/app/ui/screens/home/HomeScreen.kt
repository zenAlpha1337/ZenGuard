package com.zenguard.app.ui.screens.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zenguard.app.core.model.ProxyMode
import com.zenguard.app.core.model.TrafficStats
import com.zenguard.app.core.model.VpnState
import com.zenguard.app.ui.theme.StatusConnected
import com.zenguard.app.ui.theme.StatusConnecting
import com.zenguard.app.ui.theme.StatusError
import com.zenguard.app.ui.theme.ZenMotion

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    vpnState: VpnState,
    selectedMode: ProxyMode,
    trafficStats: TrafficStats,
    activeProfileName: String,
    onToggleConnection: () -> Unit,
    onSelectMode: (ProxyMode) -> Unit,
    statusDetail: String? = null,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    val accentColor by animateColorAsState(
        targetValue = when (vpnState) {
            VpnState.CONNECTED -> StatusConnected
            VpnState.CONNECTING, VpnState.DISCONNECTING -> StatusConnecting
            VpnState.ERROR -> StatusError
            VpnState.DISCONNECTED -> MaterialTheme.colorScheme.primary
        },
        animationSpec = ZenMotion.colorTween(),
        label = "accentColor"
    )

    val cornerRadius by animateDpAsState(
        targetValue = when (vpnState) {
            VpnState.CONNECTED -> 80.dp
            VpnState.CONNECTING -> 48.dp
            else -> 36.dp
        },
        animationSpec = ZenMotion.dpSpring(),
        label = "buttonMorph"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (vpnState == VpnState.CONNECTING) 1.12f else 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Header Status
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = "ZenGuard",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                AnimatedContent(
                    targetState = vpnState,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "statusText"
                ) { state ->
                    Text(
                        text = when (state) {
                            VpnState.CONNECTED -> "Protected • ${selectedMode.label}"
                            VpnState.CONNECTING -> "Establishing sing-box…"
                            VpnState.DISCONNECTING -> "Closing tunnels…"
                            VpnState.ERROR -> "Connection failed"
                            VpnState.DISCONNECTED -> "Ready • ${selectedMode.label}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = accentColor
                    )
                }
                if (!statusDetail.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = statusDetail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Surface(
                shape = RoundedCornerShape(50),
                color = accentColor.copy(alpha = 0.14f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (vpnState == VpnState.CONNECTED) Icons.Rounded.Shield else Icons.Rounded.Security,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "sing-box",
                        style = MaterialTheme.typography.labelMedium,
                        color = accentColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Morphing Hero Connect Button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(vertical = 12.dp)
        ) {
            if (vpnState == VpnState.CONNECTED || vpnState == VpnState.CONNECTING) {
                Box(
                    modifier = Modifier
                        .size(188.dp)
                        .scale(pulseScale)
                        .clip(RoundedCornerShape(cornerRadius + 14.dp))
                        .background(accentColor.copy(alpha = 0.14f))
                )
            }

            Surface(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggleConnection()
                },
                shape = RoundedCornerShape(cornerRadius),
                color = accentColor,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.size(156.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PowerSettingsNew,
                        contentDescription = "Toggle VPN",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = when (vpnState) {
                            VpnState.CONNECTED -> "CONNECTED"
                            VpnState.CONNECTING -> "WAIT…"
                            else -> "CONNECT"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Mode & Telemetry Section
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                ProxyMode.entries.forEach { mode ->
                    FilterChip(
                        selected = selectedMode == mode,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelectMode(mode)
                        },
                        label = { Text(mode.label) }
                    )
                }
            }

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.elevatedCardColors()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Active Profile: $activeProfileName",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.ArrowDownward,
                                contentDescription = "Download",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = formatSpeed(trafficStats.downlinkBps),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.ArrowUpward,
                                contentDescription = "Upload",
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = formatSpeed(trafficStats.uplinkBps),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatSpeed(bps: Long): String {
    return when {
        bps >= 1_000_000 -> String.format("%.1f MB/s", bps / 1_000_000f)
        bps >= 1_000 -> String.format("%.1f KB/s", bps / 1_000f)
        else -> "$bps B/s"
    }
}
