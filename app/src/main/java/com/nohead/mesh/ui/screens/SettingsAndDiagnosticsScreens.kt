package com.nohead.mesh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nohead.mesh.BuildConfig
import com.nohead.mesh.data.local.NostrRelayEntity
import com.nohead.mesh.domain.model.LocalIdentity
import com.nohead.mesh.domain.model.NetworkDiagnosticsSnapshot
import com.nohead.mesh.ui.theme.ThemeMode

@Composable
fun SettingsScreen(
    identity: LocalIdentity,
    themeMode: ThemeMode,
    hopLimit: Int,
    readReceiptsEnabled: Boolean,
    simulatedMeshEnabled: Boolean,
    batterySaverEnabled: Boolean,
    foregroundServiceEnabled: Boolean,
    torProxyEnabled: Boolean,
    nostrRelays: List<NostrRelayEntity>,
    onSetThemeMode: (ThemeMode) -> Unit,
    onUpdateNickname: (String) -> Unit,
    onRotateIdentityKeys: () -> Unit,
    onExportIdentityBackup: (String) -> Result<String>,
    onImportIdentityBackup: (String, String) -> Result<LocalIdentity>,
    onSetHopLimit: (Int) -> Unit,
    onSetReadReceipts: (Boolean) -> Unit,
    onSetSimulatedMesh: (Boolean) -> Unit,
    onToggleBatterySaver: (Boolean) -> Unit,
    onToggleForegroundService: (Boolean) -> Unit,
    onToggleTorProxy: (Boolean) -> Unit,
    onToggleNostrRelay: (NostrRelayEntity) -> Unit,
    onAddNostrRelay: (String) -> Unit,
    onTriggerEmergencyWipe: () -> Unit
) {
    var nicknameDraft by remember(identity.nickname) { mutableStateOf(identity.nickname) }
    var newRelayUrl by remember { mutableStateOf("wss://") }
    var showBackupDialog by remember { mutableStateOf(false) }
    var showWipeConfirmDialog by remember { mutableStateOf(false) }
    var wipeConfirmPhrase by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Text(
                text = "[ IDENTITY, PRIVACY & MESH SETTINGS ]",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // 1. Identity & Backup Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Cryptographic Identity (Key Gen #${identity.keyGeneration})",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Peer ID: ${identity.peerId}\nRotating Presence ID: ${identity.rotatingPresenceId}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Fingerprint:\n${identity.fingerprintFormatted}",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = nicknameDraft,
                            onValueChange = { nicknameDraft = it },
                            label = { Text("Anonymous Nickname") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = { onUpdateNickname(nicknameDraft) }) {
                            Text("Save")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = onRotateIdentityKeys, modifier = Modifier.weight(1f)) {
                            Text("Rotate Identity Keys")
                        }
                        OutlinedButton(onClick = { showBackupDialog = true }, modifier = Modifier.weight(1f)) {
                            Text("Encrypted Backup")
                        }
                    }
                }
            }
        }

        // 2. Mesh Routing, Power & Privacy Controls
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Mesh Routing, Power & Privacy Controls",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Text(
                        text = "Multi-Hop TTL Limit: $hopLimit hops (Default 5)",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Slider(
                        value = hopLimit.toFloat(),
                        onValueChange = { onSetHopLimit(it.toInt()) },
                        valueRange = 1f..10f,
                        steps = 8
                    )

                    SettingSwitchRow(
                        title = "Foreground BLE Mesh Relay Service",
                        subtitle = "Runs persistent notification to keep BLE scanning/relay active in background (subject to Android OEM battery limits).",
                        checked = foregroundServiceEnabled,
                        onCheckedChange = onToggleForegroundService
                    )

                    SettingSwitchRow(
                        title = "Battery Saver Adaptive BLE Scan",
                        subtitle = "Reduces BLE scan/advertise duty cycle (45s interval vs 15s) to conserve battery.",
                        checked = batterySaverEnabled,
                        onCheckedChange = onToggleBatterySaver
                    )

                    SettingSwitchRow(
                        title = "Send E2EE Read Receipts",
                        subtitle = "Notify verified peers when you read their direct messages.",
                        checked = readReceiptsEnabled,
                        onCheckedChange = onSetReadReceipts
                    )

                    SettingSwitchRow(
                        title = "Local Simulated Multi-Hop Test Nodes",
                        subtitle = "Enables virtual nodes (relay_alpha, node_bravo, courier_charlie) for single-device development & verification.",
                        checked = simulatedMeshEnabled,
                        onCheckedChange = onSetSimulatedMesh
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = themeMode == mode,
                                onClick = { onSetThemeMode(mode) },
                                label = { Text("Theme: ${mode.name}") }
                            )
                        }
                    }
                }
            }
        }

        // 3. Optional Nostr Internet Relays & Tor SOCKS5 Proxy
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Optional Nostr Relays & Tor Proxy",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = "Disabled by default for strict local-only operation. Enabling a relay connects via TLS WebSockets.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    SettingSwitchRow(
                        title = "Route Nostr Traffic via Tor SOCKS5 (127.0.0.1:9050)",
                        subtitle = "Requires Orbot or Tor daemon running locally. Note: Tor only proxies internet WebSocket traffic and does not make local RF BLE broadcasts anonymous.",
                        checked = torProxyEnabled,
                        onCheckedChange = onToggleTorProxy
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newRelayUrl,
                            onValueChange = { newRelayUrl = it },
                            label = { Text("Add wss:// Relay Endpoint") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = {
                            onAddNostrRelay(newRelayUrl)
                            newRelayUrl = "wss://"
                        }) {
                            Text("Add")
                        }
                    }
                }
            }
        }

        items(nostrRelays, key = { it.url }) { relay ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = relay.url,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace
                    )
                    Switch(
                        checked = relay.isEnabled,
                        onCheckedChange = { onToggleNostrRelay(relay) }
                    )
                }
            }
        }

        // 4. Emergency Privacy Wipe
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.14f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(10.dp))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Text(
                            text = "EMERGENCY PRIVACY WIPE (PANIC WIPE)",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Text(
                        text = "Immediately stops all transports, destroys Android Keystore SQLCipher wrap keys, wipes all messages, channels, favorites, and attachments, and generates a fresh unlinked identity.\n\n" +
                            "Honest Technical Warning: Flash storage wear-leveling prevents guaranteed physical sector forensic erasure without full-disk OS encryption, and wiping this device cannot recall packets already delivered to remote peers or external relays.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = { showWipeConfirmDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null)
                        Text("TRIGGER EMERGENCY PRIVACY WIPE")
                    }
                }
            }
        }
    }

    if (showBackupDialog) {
        var passphrase by remember { mutableStateOf("") }
        var exportedJson by remember { mutableStateOf("") }
        var feedback by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showBackupDialog = false },
            title = { Text("Argon2id Encrypted Identity Backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Private keys are NEVER exported in plaintext. Enter a passphrase (min 8 chars) to encrypt/decrypt your key bundle with Argon2id + ChaCha20-Poly1305.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("Backup Passphrase (8+ chars)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = exportedJson,
                        onValueChange = { exportedJson = it },
                        label = { Text("Encrypted Bundle JSON") },
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth()
                    )
                    feedback?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            onExportIdentityBackup(passphrase)
                                .onSuccess {
                                    exportedJson = it
                                    feedback = "Encrypted backup generated."
                                }
                                .onFailure { feedback = it.message }
                        }
                    ) {
                        Text("Export")
                    }
                    Button(
                        onClick = {
                            onImportIdentityBackup(exportedJson, passphrase)
                                .onSuccess {
                                    feedback = "Restored identity ${it.peerId}"
                                    showBackupDialog = false
                                }
                                .onFailure { feedback = "Restore failed: ${it.message}" }
                        }
                    ) {
                        Text("Restore")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackupDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (showWipeConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showWipeConfirmDialog = false },
            title = { Text("Confirm Emergency Privacy Wipe") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Type WIPE below or tap Execute Immediate Wipe to destroy all local keys, messages, and queues immediately.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = wipeConfirmPhrase,
                        onValueChange = { wipeConfirmPhrase = it },
                        label = { Text("Confirmation (optional safety check)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showWipeConfirmDialog = false
                        wipeConfirmPhrase = ""
                        onTriggerEmergencyWipe()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("EXECUTE IMMEDIATE WIPE")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWipeConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun DiagnosticsScreen(
    diagnostics: NetworkDiagnosticsSnapshot,
    onRefreshScan: () -> Unit,
    onExportSanitizedDiagnostics: () -> String
) {
    var exportedReport by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Text(
                text = "[ NETWORK & PROTOCOL DIAGNOSTICS ]",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "App Version: ${BuildConfig.VERSION_NAME} • Protocol Schema: v${diagnostics.protocolVersion}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Radio & Transport Matrix", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    DiagLine("Active Transports", diagnostics.activeTransports.joinToString(", ") { it.name })
                    DiagLine("BLE Adapter Enabled", diagnostics.bleAdapterEnabled.toString())
                    DiagLine("BLE Peripheral Advertising", diagnostics.bleAdvertisingActive.toString())
                    DiagLine("BLE Central Scanning", diagnostics.bleScanningActive.toString())
                    DiagLine("BLE Scan Interval", "${diagnostics.currentScanIntervalMs} ms (Battery Saver=${diagnostics.batterySaverModeEnabled})")
                    DiagLine("Wi-Fi Aware Hardware Support", diagnostics.wifiAwareSupported.toString())
                    DiagLine("Wi-Fi Aware Available Now", diagnostics.wifiAwareAvailable.toString())
                    DiagLine("Nostr Connected TLS Relays", "${diagnostics.nostrConnectedRelays} / ${diagnostics.nostrConfiguredRelays}")
                    DiagLine("Discovered Mesh Peers", diagnostics.discoveredPeersCount.toString())
                    DiagLine("Connected Active Links", diagnostics.connectedPeersCount.toString())
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Packet Routing & Security Telemetry", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
                    DiagLine("Packets Sent Total", diagnostics.packetsSentTotal.toString())
                    DiagLine("Packets Received Total", diagnostics.packetsReceivedTotal.toString())
                    DiagLine("Multi-Hop Packets Relayed", diagnostics.packetsRelayedTotal.toString())
                    DiagLine("Duplicate Packets Suppressed", diagnostics.packetsDeduplicatedDropped.toString())
                    DiagLine("Rate-Limited Flood Drops", diagnostics.packetsRateLimitedDropped.toString())
                    DiagLine("Invalid Signature Drops", diagnostics.packetsInvalidSignatureDropped.toString())
                    DiagLine("Store-and-Forward Outbox Queued", diagnostics.storeForwardQueuedCount.toString())
                    DiagLine("Opaque Courier Mailboxes Stored", diagnostics.courierMailboxesStoredCount.toString())
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(onClick = onRefreshScan, modifier = Modifier.weight(1f)) {
                    Text("Probe / Rescan Mesh")
                }
                OutlinedButton(
                    onClick = { exportedReport = onExportSanitizedDiagnostics() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Export Sanitized JSON")
                }
            }
        }

        exportedReport?.let { report ->
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Sanitized Diagnostics Export (Zero Plaintext / Zero Keys):",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = report,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
    }
}

