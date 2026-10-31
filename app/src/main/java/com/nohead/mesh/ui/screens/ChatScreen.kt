package com.nohead.mesh.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nohead.mesh.domain.model.ChannelType
import com.nohead.mesh.domain.model.ChatChannel
import com.nohead.mesh.domain.model.ChatMessage
import com.nohead.mesh.domain.model.DeliveryStatus
import com.nohead.mesh.domain.model.LocalIdentity
import com.nohead.mesh.domain.model.MessageContentType
import com.nohead.mesh.domain.model.NetworkDiagnosticsSnapshot
import com.nohead.mesh.domain.model.PeerNode
import com.nohead.mesh.domain.model.TransportType
import com.nohead.mesh.domain.service.IrcCommandParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(
    identity: LocalIdentity,
    diagnostics: NetworkDiagnosticsSnapshot,
    hopLimit: Int,
    channels: List<ChatChannel>,
    peers: List<PeerNode>,
    activeConversationId: String,
    isDirectMessage: Boolean,
    messages: List<ChatMessage>,
    isRecordingVoice: Boolean,
    isPlayingVoiceId: String?,
    isLivePttStreaming: Boolean,
    onSelectChannel: (String) -> Unit,
    onSendText: (String) -> Unit,
    onSendAttachment: (String, String, ByteArray) -> Unit,
    onToggleVoiceRecord: () -> Unit,
    onCancelVoiceRecord: () -> Unit,
    onPlayVoiceNote: (String, String?) -> Unit,
    onStartLivePtt: () -> Unit,
    onStopLivePtt: () -> Unit,
    onRotateChannelKeys: (String) -> Unit,
    onOpenDiagnostics: () -> Unit
) {
    var composerText by remember { mutableStateOf("") }
    var showAttachmentMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState()

    val autocompleteCommands = remember(composerText) {
        IrcCommandParser.autocompleteSuggestions(composerText)
    }

    val activeChannel = remember(channels, activeConversationId, isDirectMessage) {
        if (isDirectMessage) null else channels.find { it.channelId == activeConversationId }
    }
    val activePeer = remember(peers, activeConversationId, isDirectMessage) {
        if (isDirectMessage) peers.find { it.peerId == activeConversationId } else null
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onToggleVoiceRecord()
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val contentResolver = context.contentResolver
                val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                var fileName = "attachment_${System.currentTimeMillis() % 10000}"
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && cursor.moveToFirst()) {
                        fileName = cursor.getString(nameIndex) ?: fileName
                    }
                }
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) {
                    onSendAttachment(fileName, mimeType, bytes)
                }
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top Mesh Status & Branding Header
        MeshHeaderBar(
            identity = identity,
            diagnostics = diagnostics,
            activeConversationTitle = if (isDirectMessage) {
                "@${activePeer?.nickname ?: activeConversationId}"
            } else {
                activeChannel?.name ?: activeConversationId
            },
            subtitle = if (isDirectMessage) {
                "E2EE Direct Session • ${activePeer?.presenceState?.name ?: "OFFLINE"} • Verified=${activePeer?.isVerified == true}"
            } else {
                activeChannel?.topic ?: "Proximity BLE Mesh Channel"
            },
            isInternetAccessible = activeChannel?.channelType == ChannelType.GLOBAL_NOSTR ||
                activeChannel?.channelType == ChannelType.GEOHASH_ROOM,
            isPasswordProtected = activeChannel?.channelType == ChannelType.PASSWORD_PROTECTED,
            onRotateKey = {
                if (activeChannel?.channelType == ChannelType.PASSWORD_PROTECTED) {
                    onRotateChannelKeys(activeChannel.channelId)
                }
            },
            onOpenDiagnostics = onOpenDiagnostics
        )

        // Quick Channel Switcher Bar
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(channels.filter { it.isJoined }, key = { it.channelId }) { channel ->
                FilterChip(
                    selected = !isDirectMessage && activeConversationId == channel.channelId,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelectChannel(channel.channelId)
                    },
                    label = {
                        Text(
                            text = channel.name,
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    leadingIcon = {
                        when (channel.channelType) {
                            ChannelType.PASSWORD_PROTECTED -> Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            ChannelType.GLOBAL_NOSTR, ChannelType.GEOHASH_ROOM -> Icon(
                                Icons.Default.Public,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            ChannelType.PUBLIC_MESH -> Unit
                        }
                    }
                )
            }
        }

        // Warning banner when a public room can leave the local mesh via Nostr relays
        if (!isDirectMessage && (activeChannel?.channelType == ChannelType.GLOBAL_NOSTR || activeChannel?.channelType == ChannelType.GEOHASH_ROOM)) {
            Surface(
                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "⚠ PUBLIC RELAY WARNING: Messages in ${activeChannel.name} are signed and published to external Nostr relays when internet relays are enabled.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        // Message Timeline
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "NO HEAD MESH TIMELINE READY",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = if (isDirectMessage) {
                                    "Messages in this thread are End-to-End Encrypted using X25519 + ChaCha20-Poly1305 and signed with Ed25519. Offline messages queue in SQLCipher Store-and-Forward."
                                } else {
                                    "Proximity BLE mesh channel. Messages relay up to $hopLimit hops across nearby devices without internet. Type /help for IRC commands."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            items(messages, key = { it.messageId }) { msg ->
                MessageBubbleItem(
                    message = msg,
                    isPlayingVoice = isPlayingVoiceId == msg.attachment?.attachmentId,
                    onPlayVoiceNote = {
                        onPlayVoiceNote(msg.attachment?.attachmentId.orEmpty(), msg.attachment?.localFilePath)
                    }
                )
            }
        }

        // IRC Command Autocomplete Drawer
        if (autocompleteCommands.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(
                        text = "IRC Command Autocomplete:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    autocompleteCommands.take(4).forEach { spec ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    composerText = spec.syntax.substringBefore(" ") + " "
                                }
                                .padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(spec.syntax, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(
                                spec.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Active Voice Recording / Live Push-to-Talk Banner
        if (isRecordingVoice || isLivePttStreaming) {
            Surface(
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isRecordingVoice) {
                            "● RECORDING VOICE NOTE... Tap Stop to send or Cancel"
                        } else {
                            "● LIVE PTT STREAMING OVER MESH... Tap PTT to stop"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    if (isRecordingVoice) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = onCancelVoiceRecord) {
                                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = onToggleVoiceRecord) {
                                Text("Send", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    } else {
                        TextButton(onClick = onStopLivePtt) {
                            Text("Stop PTT", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        // Bottom Composer Bar with Attachment, Voice Note, and Live PTT controls
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Chunked Attachment button with menu (Device File Picker or Diagnostic Beacon)
                    Box {
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showAttachmentMenu = true
                            }
                        ) {
                            Icon(
                                Icons.Default.AttachFile,
                                contentDescription = "Send Chunked Mesh Attachment",
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }
                        DropdownMenu(
                            expanded = showAttachmentMenu,
                            onDismissRequest = { showAttachmentMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Pick File / Image from Device") },
                                onClick = {
                                    showAttachmentMenu = false
                                    filePickerLauncher.launch("*/*")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Send Mesh Diagnostic Beacon (.txt)") },
                                onClick = {
                                    showAttachmentMenu = false
                                    val sampleDiagnosticPayload = buildString {
                                        appendLine("No head Mesh Diagnostic Beacon")
                                        appendLine("Sender: ${identity.nickname} (${identity.peerId})")
                                        appendLine("Timestamp: ${System.currentTimeMillis()}")
                                        appendLine("Fingerprint: ${identity.fingerprintFormatted}")
                                    }.encodeToByteArray()
                                    onSendAttachment(
                                        "mesh_beacon_${System.currentTimeMillis() % 10000}.txt",
                                        "text/plain",
                                        sampleDiagnosticPayload
                                    )
                                }
                            )
                        }
                    }

                    // Voice Note Record/Stop button
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val hasAudioPermission = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (isRecordingVoice || hasAudioPermission) {
                                onToggleVoiceRecord()
                            } else {
                                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (isRecordingVoice) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = "Record Voice Note",
                            tint = if (isRecordingVoice) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }

                    // Live Push-to-Talk toggle chip
                    AssistChip(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (isLivePttStreaming) onStopLivePtt() else onStartLivePtt()
                        },
                        label = {
                            Text(
                                text = if (isLivePttStreaming) "PTT LIVE" else "PTT",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.GraphicEq,
                                contentDescription = "Live Push-to-Talk",
                                tint = if (isLivePttStreaming) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )

                    OutlinedTextField(
                        value = composerText,
                        onValueChange = { composerText = it },
                        placeholder = {
                            Text(
                                text = if (isDirectMessage) "Encrypted message or /cmd..." else "Mesh broadcast or /help...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        maxLines = 3,
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(
                        onClick = {
                            if (composerText.isNotBlank()) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSendText(composerText)
                                composerText = ""
                            }
                        }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send Message",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MeshHeaderBar(
    identity: LocalIdentity,
    diagnostics: NetworkDiagnosticsSnapshot,
    activeConversationTitle: String,
    subtitle: String,
    isInternetAccessible: Boolean,
    isPasswordProtected: Boolean,
    onRotateKey: () -> Unit,
    onOpenDiagnostics: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
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
                            .background(
                                if (diagnostics.connectedPeersCount > 0) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.tertiary
                            )
                    )
                    Text(
                        text = "NO HEAD // ${identity.nickname}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Transport & Peer Counter Pill (clickable to open Diagnostics)
                val transportBadges = diagnostics.activeTransports.joinToString("+") {
                    when (it) {
                        TransportType.BLE_MESH -> "BLE"
                        TransportType.WIFI_AWARE -> "NAN"
                        TransportType.NOSTR_RELAY -> "NOSTR"
                        TransportType.LOOPBACK_SIMULATED -> "SIM"
                        TransportType.OFFLINE -> "OFFLINE"
                    }
                }
                Text(
                    text = "[$transportBadges | D:${diagnostics.discoveredPeersCount} C:${diagnostics.connectedPeersCount}]",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onOpenDiagnostics() }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = activeConversationTitle,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (isPasswordProtected) {
                    AssistChip(
                        onClick = onRotateKey,
                        label = { Text("Rotate Key", style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = {
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(14.dp))
                        }
                    )
                } else if (isInternetAccessible) {
                    Text(
                        text = "INTERNET ROOM",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubbleItem(
    message: ChatMessage,
    isPlayingVoice: Boolean,
    onPlayVoiceNote: () -> Unit
) {
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val formattedTime = remember(message.timestampEpochMs) {
        timeFormatter.format(Date(message.timestampEpochMs))
    }

    if (message.contentType == MessageContentType.SYSTEM_NOTICE) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(10.dp)
        ) {
            Text(
                text = "[SYS $formattedTime] ${message.content}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary
            )
        }
        return
    }

    val bubbleAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start
    val bubbleColor = if (message.isOutgoing) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = bubbleAlignment
    ) {
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(10.dp),
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(0.88f)
        ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Header: Sender Nickname + PeerID badge + Hop Count + Transport
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "${message.senderNickname} (${message.senderPeerId.takeLast(6)})",
                            style = MaterialTheme.typography.labelMedium,
                            color = peerAccentColor(message.senderPeerId),
                            fontWeight = FontWeight.Bold
                        )
                        if (message.senderVerified) {
                            Icon(
                                Icons.Default.VerifiedUser,
                                contentDescription = "Verified Identity",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                        if (message.isEncrypted) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = "End-to-End Encrypted",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }

                    Text(
                        text = "${message.transportOrigin.name.take(3)} • hop:${message.hopCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Content Body
                when (message.contentType) {
                    MessageContentType.ACTION_IRC -> {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyLarge,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    MessageContentType.VOICE_NOTE -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { onPlayVoiceNote() }
                                .padding(8.dp)
                        ) {
                            Icon(
                                imageVector = if (isPlayingVoice) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = "Play Voice Note",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text(
                                    text = message.content,
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Text(
                                    text = "SHA-256: ${message.attachment?.sha256Hex?.take(12).orEmpty()}…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    MessageContentType.FILE_ATTACHMENT, MessageContentType.IMAGE_ATTACHMENT -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(8.dp)
                        ) {
                            Text(
                                text = "📎 ${message.attachment?.fileName ?: message.content}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Size: ${message.attachment?.sizeBytes ?: 0} B • Chunks: ${message.attachment?.receivedChunks ?: 1}/${message.attachment?.totalChunks ?: 1} • Verified SHA-256",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Never auto-executed. Saved inside sandboxed app storage.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                    MessageContentType.CASHU_TOKEN -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.tertiary, RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            Text(
                                text = "⚡ Cashu Ecash Token Detected (Optional / Never required for messaging)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                            Text(
                                text = message.content.take(48) + "...",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    else -> {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Footer: Timestamp + Honest Delivery Status Indicator
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (message.isOutgoing) {
                        Spacer(modifier = Modifier.width(6.dp))
                        DeliveryStatusBadge(status = message.deliveryStatus)
                    }
                }
            }
        }
    }
}

@Composable
private fun DeliveryStatusBadge(status: DeliveryStatus) {
    val (icon, label, tint) = when (status) {
        DeliveryStatus.QUEUED -> Triple(Icons.Default.Schedule, "QUEUED", MaterialTheme.colorScheme.tertiary)
        DeliveryStatus.SENT -> Triple(Icons.Default.Check, "SENT", MaterialTheme.colorScheme.onSurfaceVariant)
        DeliveryStatus.RELAYED -> Triple(Icons.Default.Check, "RELAYED", MaterialTheme.colorScheme.secondary)
        DeliveryStatus.DELIVERED -> Triple(Icons.Default.DoneAll, "DELIVERED", MaterialTheme.colorScheme.primary)
        DeliveryStatus.READ -> Triple(Icons.Default.DoneAll, "READ", MaterialTheme.colorScheme.primary)
        DeliveryStatus.FAILED -> Triple(Icons.Default.ErrorOutline, "FAILED", MaterialTheme.colorScheme.error)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(12.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

private fun peerAccentColor(peerId: String): Color {
    val palette = listOf(
        Color(0xFF00E676),
        Color(0xFF00B0FF),
        Color(0xFFFFB300),
        Color(0xFFFF80AB),
        Color(0xFFB388FF),
        Color(0xFF64FFDA)
    )
    val idx = (peerId.hashCode() and 0x7FFFFFFF) % palette.size
    return palette[idx]
}

