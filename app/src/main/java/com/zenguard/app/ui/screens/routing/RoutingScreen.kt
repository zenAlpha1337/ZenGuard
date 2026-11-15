package com.zenguard.app.ui.screens.routing

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class SplitTunnelMode(val label: String, val description: String) {
    ALL_APPS("All Apps", "Route all device network traffic through the sing-box tunnel."),
    BYPASS_SELECTED("Bypass Selected", "Bypass chosen apps and direct domains; proxy everything else."),
    ONLY_SELECTED("Only Selected", "Only proxy chosen apps and domains; route everything else directly.")
}

val DefaultDohProviders = listOf(
    "Cloudflare" to "https://1.1.1.1/dns-query",
    "Google" to "https://dns.google/dns-query",
    "Quad9" to "https://dns.quad9.net/dns-query",
    "AdGuard" to "https://dns.adguard-dns.com/dns-query",
    "Custom" to ""
)

private val DefaultBypassRules = listOf(
    "*.local",
    "*.internal",
    "192.168.0.0/16",
    "10.0.0.0/8",
    "172.16.0.0/12",
    "ya.ru",
    "vk.com",
    "gosuslugi.ru"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoutingScreen(
    bypassLan: Boolean = true,
    onBypassLanChange: (Boolean) -> Unit = {},
    bypassRuLocal: Boolean = true,
    onBypassRuLocalChange: (Boolean) -> Unit = {},
    enableFakeIp: Boolean = true,
    onEnableFakeIpChange: (Boolean) -> Unit = {},
    selectedDohProvider: String = "Cloudflare",
    onDohProviderChange: (String) -> Unit = {},
    splitTunnelMode: SplitTunnelMode = SplitTunnelMode.ALL_APPS,
    onSplitTunnelModeChange: (SplitTunnelMode) -> Unit = {},
    customBypassRules: List<String> = DefaultBypassRules,
    onAddBypassRule: (String) -> Unit = {},
    onRemoveBypassRule: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    // Internal state mirrors for fully interactive local previews or standalone usage
    var localBypassLan by remember(bypassLan) { mutableStateOf(bypassLan) }
    var localBypassRu by remember(bypassRuLocal) { mutableStateOf(bypassRuLocal) }
    var localFakeIp by remember(enableFakeIp) { mutableStateOf(enableFakeIp) }
    var localDoh by remember(selectedDohProvider) { mutableStateOf(selectedDohProvider) }
    var customDohUrl by remember { mutableStateOf("https://doh.applied-privacy.net/query") }
    var localSplitMode by remember(splitTunnelMode) { mutableStateOf(splitTunnelMode) }

    val localRules = remember(customBypassRules) {
        mutableStateListOf<String>().apply { addAll(customBypassRules) }
    }
    var newRuleInput by remember { mutableStateOf("") }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 48.dp)
    ) {
        // Screen Header
        item {
            Column {
                Text(
                    text = "Routing & Rules",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "sing-box SRS compiled rule-sets, FakeIP & DNS tunning",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Section 1: Core sing-box Inbound/Outbound Toggles
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.elevatedCardColors()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Tunnel Routing",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(12.dp))

                    // Bypass LAN Toggle
                    RoutingToggleItem(
                        icon = Icons.Rounded.Router,
                        title = "Bypass LAN",
                        description = "Directly route private subnet traffic (192.168.0.0/16, 10.0.0.0/8, ::1/128)",
                        checked = localBypassLan,
                        onCheckedChange = { checked ->
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            localBypassLan = checked
                            onBypassLanChange(checked)
                        }
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )

                    // Bypass RU / Local (.srs) Toggle
                    RoutingToggleItem(
                        icon = Icons.Rounded.Rule,
                        title = "Bypass RU / Local (.srs)",
                        description = "Exclude regional services using sing-box compiled rule-sets (geosite-category-ru, geoip-ru)",
                        checked = localBypassRu,
                        onCheckedChange = { checked ->
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            localBypassRu = checked
                            onBypassRuLocalChange(checked)
                        },
                        tag = "SRS Rule-set"
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )

                    // Enable FakeIP Toggle
                    RoutingToggleItem(
                        icon = Icons.Rounded.Dns,
                        title = "Enable FakeIP",
                        description = "Allocate synthetic 198.18.0.0/15 IPs for zero-RTT local resolution and anti-DNS leak",
                        checked = localFakeIp,
                        onCheckedChange = { checked ->
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            localFakeIp = checked
                            onEnableFakeIpChange(checked)
                        },
                        tag = "TUN DNS"
                    )
                }
            }
        }

        // Section 2: Encrypted DoH DNS Upstream Selector
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.elevatedCardColors()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .animateContentSize()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Security,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Encrypted DNS (DoH)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "DNS-over-HTTPS resolver for remote domain queries",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DefaultDohProviders.forEach { (name, _) ->
                            FilterChip(
                                selected = localDoh == name,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    localDoh = name
                                    onDohProviderChange(name)
                                },
                                label = { Text(name) }
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = localDoh == "Custom",
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Column(modifier = Modifier.padding(top = 12.dp)) {
                            OutlinedTextField(
                                value = customDohUrl,
                                onValueChange = { customDohUrl = it },
                                label = { Text("Custom DoH URL") },
                                placeholder = { Text("https://example.com/dns-query") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                singleLine = true
                            )
                        }
                    }
                }
            }
        }

        // Section 3: Split Tunneling Mode
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.elevatedCardColors()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.AltRoute,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Split Tunneling Mode",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = localSplitMode.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SplitTunnelMode.entries.forEach { mode ->
                            FilterChip(
                                selected = localSplitMode == mode,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    localSplitMode = mode
                                    onSplitTunnelModeChange(mode)
                                },
                                label = { Text(mode.label) }
                            )
                        }
                    }
                }
            }
        }

        // Section 4: Editable Custom Domain / IP Direct Bypass Section
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.elevatedCardColors()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .animateContentSize()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Tune,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Custom Direct Bypass Rules",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${localRules.size} domains & CIDRs routed directly",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // Input row for adding new domain/CIDR rule
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = newRuleInput,
                            onValueChange = { newRuleInput = it },
                            placeholder = { Text("domain.com or 10.10.0.0/16") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            singleLine = true
                        )

                        IconButton(
                            onClick = {
                                val trimmed = newRuleInput.trim()
                                if (trimmed.isNotEmpty() && !localRules.contains(trimmed)) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    localRules.add(0, trimmed)
                                    onAddBypassRule(trimmed)
                                    newRuleInput = ""
                                }
                            },
                            enabled = newRuleInput.isNotBlank(),
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (newRuleInput.isNotBlank()) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = "Add Rule",
                                tint = if (newRuleInput.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.outline
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // Active rules chip list
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        localRules.forEach { rule ->
                            InputChip(
                                selected = false,
                                onClick = {},
                                label = { Text(rule, fontWeight = FontWeight.Medium) },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Remove",
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .clickable {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                localRules.remove(rule)
                                                onRemoveBypassRule(rule)
                                            },
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = InputChipDefaults.inputChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                ),
                                border = null,
                                modifier = Modifier.height(32.dp),
                                enabled = true
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Reusable switch item with icon, title, description and optional status badge.
 */
@Composable
private fun RoutingToggleItem(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(22.dp)
            )

            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (tag != null) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = tag,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
