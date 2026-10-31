package com.nohead.mesh.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nohead.mesh.domain.model.DirectConversationSummary
import com.nohead.mesh.domain.model.LocalIdentity
import com.nohead.mesh.domain.model.PeerNode
import com.nohead.mesh.domain.model.PeerPresenceState
import com.nohead.mesh.ui.components.IdentityQrCodeView

private enum class PeerDirectoryFilter {
    ALL,
    FAVORITES,
    COURIERS,
    VERIFIED
}

@Composable
fun PeersAndFavoritesScreen(
    localIdentity: LocalIdentity,
    peers: List<PeerNode>,
    showFavoritesOnly: Boolean,
    onToggleShowFavoritesOnly: (Boolean) -> Unit = {},
    onStartDirectMessage: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onToggleBlock: (PeerNode) -> Unit,
    onToggleTrustedCourier: (PeerNode) -> Unit,
    onVerifyPeer: (String) -> Unit,
    onTriggerBleScan: () -> Unit
) {
    var selectedPeerForVerification by remember { mutableStateOf<PeerNode?>(null) }
    var showMyQrDialog by remember { mutableStateOf(false) }
    var activeFilter by remember(showFavoritesOnly) {
        mutableStateOf(if (showFavoritesOnly) PeerDirectoryFilter.FAVORITES else PeerDirectoryFilter.ALL)
    }

    val displayedPeers = remember(peers, activeFilter) {
        when (activeFilter) {
            PeerDirectoryFilter.ALL -> peers
            PeerDirectoryFilter.FAVORITES -> peers.filter { it.isFavorite }
            PeerDirectoryFilter.COURIERS -> peers.filter { it.isTrustedCourier }
            PeerDirectoryFilter.VERIFIED -> peers.filter { it.isVerified }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (activeFilter == PeerDirectoryFilter.FAVORITES) "[ FAVORITE & TRUSTED PEERS ]" else "[ MESH PEER DIRECTORY ]",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Distinguishes Nearby BLE/Wi-Fi Aware peers from Internet Nostr peers.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row {
                IconButton(onClick = { showMyQrDialog = true }) {
                    Icon(Icons.Default.QrCode2, contentDescription = "My Identity QR", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onTriggerBleScan) {
                    Icon(Icons.Default.Refresh, contentDescription = "Rescan BLE Mesh", tint = MaterialTheme.colorScheme.secondary)
                }
            }
        }

        // Filter Chips Bar (All Peers / Favorites / Couriers / Verified)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = activeFilter == PeerDirectoryFilter.ALL,
                onClick = {
                    activeFilter = PeerDirectoryFilter.ALL
                    onToggleShowFavoritesOnly(false)
                },
                label = { Text("All (${peers.size})", style = MaterialTheme.typography.labelSmall) }
            )
            FilterChip(
                selected = activeFilter == PeerDirectoryFilter.FAVORITES,
                onClick = {
                    activeFilter = PeerDirectoryFilter.FAVORITES
                    onToggleShowFavoritesOnly(true)
                },
                label = { Text("★ Favs (${peers.count { it.isFavorite }})", style = MaterialTheme.typography.labelSmall) }
            )
            FilterChip(
                selected = activeFilter == PeerDirectoryFilter.COURIERS,
                onClick = {
                    activeFilter = PeerDirectoryFilter.COURIERS
                    onToggleShowFavoritesOnly(false)
                },
                label = { Text("Couriers (${peers.count { it.isTrustedCourier }})", style = MaterialTheme.typography.labelSmall) }
            )
            FilterChip(
                selected = activeFilter == PeerDirectoryFilter.VERIFIED,
                onClick = {
                    activeFilter = PeerDirectoryFilter.VERIFIED
                    onToggleShowFavoritesOnly(false)
                },
                label = { Text("Verified (${peers.count { it.isVerified }})", style = MaterialTheme.typography.labelSmall) }
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            if (displayedPeers.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = if (showFavoritesOnly) "No favorite peers marked yet." else "Scanning for nearby BLE & Wi-Fi Aware mesh nodes...",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Star peers in the People directory to add them to Favorites or designate them as Store-and-Forward couriers.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            items(displayedPeers, key = { it.peerId }) { peer ->
                PeerDirectoryCard(
                    peer = peer,
                    onOpenDm = { onStartDirectMessage(peer.peerId) },
                    onToggleFavorite = { onToggleFavorite(peer.peerId) },
                    onToggleBlock = { onToggleBlock(peer) },
                    onToggleCourier = { onToggleTrustedCourier(peer) },
                    onInspectFingerprint = { selectedPeerForVerification = peer }
                )
            }
        }
    }

    if (showMyQrDialog) {
        val qrUri = "nohead://verify?peerId=${localIdentity.peerId}&nick=${localIdentity.nickname}&fp=${localIdentity.fingerprintFormatted.replace(" ", "")}"
        AlertDialog(
            onDismissRequest = { showMyQrDialog = false },
            title = { Text("My Cryptographic Identity QR") },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IdentityQrCodeView(qrPayload = qrUri)
                    Text("Nickname: ${localIdentity.nickname}", style = MaterialTheme.typography.labelMedium)
                    Text("Peer ID: ${localIdentity.peerId}", style = MaterialTheme.typography.labelSmall)
                    Text(
                        text = localIdentity.fingerprintFormatted,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showMyQrDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    selectedPeerForVerification?.let { peer ->
        val peerQr = "nohead://verify?peerId=${peer.peerId}&nick=${peer.nickname}&fp=${peer.fingerprint.replace(" ", "")}"
        AlertDialog(
            onDismissRequest = { selectedPeerForVerification = null },
            title = { Text("Verify Peer Identity: ${peer.nickname}") },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IdentityQrCodeView(qrPayload = peerQr, size = 150.dp)
                    Text(
                        text = "Compare this 128-bit safety fingerprint with ${peer.nickname} in person or via QR to prevent Man-in-the-Middle (MITM) key substitution:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = peer.fingerprint,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Ed25519 PubKey: ${peer.signingPublicKeyHex.take(24)}…\nX25519 PubKey: ${peer.encryptionPublicKeyHex.take(24)}…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onVerifyPeer(peer.peerId)
                        selectedPeerForVerification = null
                    }
                ) {
                    Text("Mark Fingerprint Verified")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedPeerForVerification = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun PeerDirectoryCard(
    peer: PeerNode,
    onOpenDm: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleBlock: () -> Unit,
    onToggleCourier: () -> Unit,
    onInspectFingerprint: () -> Unit
) {
    val presenceColor = when (peer.presenceState) {
        PeerPresenceState.ONLINE_NEARBY -> MaterialTheme.colorScheme.primary
        PeerPresenceState.ONLINE_INTERNET -> MaterialTheme.colorScheme.secondary
        PeerPresenceState.STALE -> MaterialTheme.colorScheme.tertiary
        PeerPresenceState.OFFLINE -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(presenceColor)
                    )
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = peer.nickname,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (peer.isVerified) {
                                Icon(
                                    Icons.Default.VerifiedUser,
                                    contentDescription = "Verified",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Text(
                            text = "ID: ${peer.peerId} • ${peer.presenceState.name} • Hop: ${peer.hopDistance}" +
                                (peer.rssiDbm?.let { " • RSSI: ${it}dBm" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (peer.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = "Favorite Peer",
                        tint = if (peer.isFavorite) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = "Fingerprint: ${peer.fingerprint}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = onOpenDm,
                    label = { Text("E2EE DM", style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = { Icon(Icons.Default.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(14.dp)) }
                )
                AssistChip(
                    onClick = onInspectFingerprint,
                    label = { Text(if (peer.isVerified) "Verified" else "Verify QR", style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = { Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(14.dp)) }
                )
                AssistChip(
                    onClick = onToggleCourier,
                    label = {
                        Text(
                            if (peer.isTrustedCourier) "Courier: ON" else "Courier: OFF",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
                AssistChip(
                    onClick = onToggleBlock,
                    label = {
                        Text(
                            if (peer.isBlocked) "Unblock" else "Block",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (peer.isBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(14.dp)) }
                )
            }
        }
    }
}

@Composable
fun DirectConversationsScreen(
    conversations: List<DirectConversationSummary>,
    onOpenConversation: (String) -> Unit,
    onRotateSessionKey: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "[ E2EE PRIVATE DIRECT CONVERSATIONS ]",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "1:1 threads encrypted with X25519 Ephemeral-Static DH + ChaCha20-Poly1305 AEAD. Courier nodes cannot read message plaintext.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(conversations, key = { it.peerId }) { conv ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenConversation(conv.peerId) }
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "@${conv.nickname}",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (conv.isVerified) {
                                    Icon(
                                        Icons.Default.VerifiedUser,
                                        contentDescription = "Verified",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                            Text(
                                text = conv.presenceState.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        Text(
                            text = conv.lastMessagePreview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Peer ID: ${conv.peerId}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(onClick = { onRotateSessionKey(conv.peerId) }) {
                                Text("Ratchet Key", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

