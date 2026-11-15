package com.zenguard.app.ui.screens.profiles

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zenguard.app.core.model.VpnProfile
import com.zenguard.app.ui.theme.ZenMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SampleProfiles = listOf(
    VpnProfile("1", "Frankfurt Premium Reality", "VLESS", "de-fra.edge.zenguard.net:443", latencyMs = 38),
    VpnProfile("2", "Amsterdam Hysteria 2 Turbo", "HY2", "nl-ams.edge.zenguard.net:8443", latencyMs = 45),
    VpnProfile("3", "Helsinki TUIC v5 BBR", "TUIC", "fi-hel.edge.zenguard.net:443", latencyMs = 74),
    VpnProfile("4", "Stockholm Shadowsocks 2022", "SS", "se-sto.edge.zenguard.net:8388", latencyMs = 118),
    VpnProfile("5", "Vienna Reality Direct", "VLESS", "at-vie.edge.zenguard.net:443", latencyMs = 195),
    VpnProfile("6", "Ashburn East Fast", "Trojan", "us-iad.edge.zenguard.net:443", latencyMs = 280),
    VpnProfile("7", "Singapore Low Latency", "HY2", "sg-sin.edge.zenguard.net:8443", latencyMs = null)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    profiles: List<VpnProfile> = SampleProfiles,
    selectedProfileId: String? = "1",
    onSelectProfile: (VpnProfile) -> Unit = {},
    onImportUri: (String) -> Unit = {},
    onImportSubscription: (String) -> Unit = {},
    onDeleteProfile: (String) -> Unit = {},
    onPingProfile: (String) -> Unit = {},
    onPingAll: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    var isImportDialogOpen by remember { mutableStateOf(false) }
    var isTestingAllPing by remember { mutableStateOf(false) }

    // Ping rotation animation when "Test All Ping" is triggered
    val infiniteTransition = rememberInfiniteTransition(label = "pingRotation")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spin"
    )

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Profiles",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${profiles.size} servers • VLESS, HY2, TUIC, SS, Trojan",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // "Test All Ping" Action Pill
                FilledTonalButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        isTestingAllPing = true
                        onPingAll()
                        coroutineScope.launch {
                            delay(1800)
                            isTestingAllPing = false
                        }
                    },
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = if (isTestingAllPing) Icons.Rounded.Refresh else Icons.Rounded.Speed,
                        contentDescription = "Test Ping",
                        modifier = Modifier
                            .size(18.dp)
                            .then(if (isTestingAllPing) Modifier.rotate(rotationAngle) else Modifier)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isTestingAllPing) "Pinging…" else "Ping All",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Animated Server Cards List
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                items(items = profiles, key = { it.id }) { profile ->
                    val isSelected = profile.id == selectedProfileId
                    ServerProfileCard(
                        profile = profile,
                        isSelected = isSelected,
                        onSelect = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelectProfile(profile)
                        },
                        onPing = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onPingProfile(profile.id)
                        },
                        onDelete = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDeleteProfile(profile.id)
                        }
                    )
                }
            }
        }

        // Floating Action Button for Importing
        ExtendedFloatingActionButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                isImportDialogOpen = true
            },
            icon = { Icon(Icons.Rounded.Add, contentDescription = "Import") },
            text = { Text("Import Config") },
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
        )
    }

    // Import Dialog (URI / Clipboard / Subscription URL)
    if (isImportDialogOpen) {
        ImportProfileDialog(
            onDismiss = { isImportDialogOpen = false },
            onImportUri = { uri ->
                onImportUri(uri)
                isImportDialogOpen = false
            },
            onImportSubscription = { subUrl ->
                onImportSubscription(subUrl)
                isImportDialogOpen = false
            }
        )
    }
}

/**
 * Individual Server Card with protocol badge, server:port, and color-coded latency pill.
 */
@Composable
private fun ServerProfileCard(
    profile: VpnProfile,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onPing: () -> Unit,
    onDelete: () -> Unit
) {
    val cardBorder = if (isSelected) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }

    val cardColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = cardColor),
        border = cardBorder
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Radio/Check + Details
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = if (isSelected) "Selected" else "Not Selected",
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(24.dp)
                )

                Spacer(Modifier.width(12.dp))

                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = profile.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        ProtocolBadge(protocol = profile.protocol)
                    }

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = profile.server,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // Right: Latency Pill + Quick Actions
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                LatencyPill(latencyMs = profile.latencyMs, onClick = onPing)

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "Delete Profile",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * Protocol badge: VLESS, HY2, TUIC, SS, Trojan with distinctive MD3 tinting.
 */
@Composable
private fun ProtocolBadge(protocol: String) {
    val upper = protocol.uppercase()
    val badgeColor = when {
        upper.contains("VLESS") -> Color(0xFF00796B)
        upper.contains("HY") || upper.contains("HYSTERIA") -> Color(0xFF7B1FA2)
        upper.contains("TUIC") -> Color(0xFF0277BD)
        upper.contains("SS") || upper.contains("SHADOW") -> Color(0xFFD84315)
        upper.contains("TROJAN") -> Color(0xFF4527A0)
        else -> MaterialTheme.colorScheme.secondary
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = badgeColor.copy(alpha = 0.16f)
    ) {
        Text(
            text = upper,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = badgeColor
        )
    }
}

/**
 * Color-coded animated latency pill (<120ms green, <250ms amber, >=250ms red, Timeout muted).
 */
@Composable
private fun LatencyPill(
    latencyMs: Int?,
    onClick: () -> Unit
) {
    val targetColor = when {
        latencyMs == null -> MaterialTheme.colorScheme.outline
        latencyMs < 120 -> Color(0xFF2EBD85) // Crisp green
        latencyMs < 250 -> Color(0xFFF0B90B) // Warning amber
        else -> Color(0xFFE55353)            // High latency red
    }

    val pillColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = ZenMotion.colorTween(),
        label = "latencyColor"
    )

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = pillColor.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, pillColor.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(pillColor)
            )
            Text(
                text = if (latencyMs != null) "$latencyMs ms" else "Timeout",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = pillColor
            )
        }
    }
}

/**
 * MD3 AlertDialog for pasting URI/node link or entering Subscription URL.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportProfileDialog(
    onDismiss: () -> Unit,
    onImportUri: (String) -> Unit,
    onImportSubscription: (String) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var inputText by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Import Server Configuration",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                PrimaryTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Direct URI") },
                        icon = { Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Subscription") },
                        icon = { Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                }

                Text(
                    text = if (selectedTab == 0) {
                        "Paste a single vless://, hysteria2://, tuic://, ss://, or trojan:// share link."
                    } else {
                        "Enter a subscription URL (Clash/sing-box base64 or YAML endpoint)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    label = { Text(if (selectedTab == 0) "Config URI" else "Subscription URL") },
                    placeholder = {
                        Text(
                            if (selectedTab == 0) "vless://uuid@host:port?security=reality..."
                            else "https://example.com/api/v1/client/subscribe?token=..."
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4,
                    shape = RoundedCornerShape(16.dp)
                )

                // Quick Paste from Clipboard button
                OutlinedButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        clipboardManager.getText()?.text?.let { text ->
                            inputText = text.trim()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentPaste,
                        contentDescription = "Paste Clipboard",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Paste from Clipboard")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (inputText.isNotBlank()) {
                        if (selectedTab == 0) {
                            onImportUri(inputText.trim())
                        } else {
                            onImportSubscription(inputText.trim())
                        }
                    }
                },
                enabled = inputText.isNotBlank(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        shape = RoundedCornerShape(28.dp)
    )
}
