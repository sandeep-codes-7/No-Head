package com.nohead.mesh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Tag
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.nohead.mesh.domain.model.ChannelType
import com.nohead.mesh.domain.model.ChatChannel
import com.nohead.mesh.domain.model.GeohashRoom
import com.nohead.mesh.domain.service.GeohashCodec

@Composable
fun ChannelsAndGeohashScreen(
    channels: List<ChatChannel>,
    presetGeohashRooms: List<GeohashRoom>,
    bookmarkedGeohashRooms: List<GeohashRoom>,
    onOpenChannel: (String) -> Unit,
    onCreatePublicChannel: (String, String) -> Unit,
    onCreatePasswordChannel: (String, String, String) -> Unit,
    onRotateChannelKey: (String) -> Unit,
    onJoinGeohashRoom: (String) -> Unit,
    onToggleBookmarkGeohash: (GeohashRoom) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var showCreateChannelDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Mesh & Encrypted Channels") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Geohash Location Rooms") }
            )
        }

        if (selectedTab == 0) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "[ CHANNELS & PASSWORD GROUPS ]",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "BLE mesh channels are proximity-based. Password-protected channels derive group keys via Argon2id + HKDF epoch rotation.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = { showCreateChannelDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("New")
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(channels, key = { it.channelId }) { ch ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenChannel(ch.channelId) }
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(
                                            imageVector = when (ch.channelType) {
                                                ChannelType.PASSWORD_PROTECTED -> Icons.Default.Lock
                                                ChannelType.GLOBAL_NOSTR, ChannelType.GEOHASH_ROOM -> Icons.Default.Public
                                                ChannelType.PUBLIC_MESH -> Icons.Default.Tag
                                            },
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = ch.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Text(
                                        text = "${ch.channelType.name} • Epoch #${ch.keyEpoch}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                                Text(
                                    text = ch.topic,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (ch.channelType == ChannelType.PASSWORD_PROTECTED) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Argon2id + ChaCha20-Poly1305 (Does not protect against compromised insider members)",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.tertiary,
                                            modifier = Modifier.weight(1f)
                                        )
                                        AssistChip(
                                            onClick = { onRotateChannelKey(ch.channelId) },
                                            label = { Text("Rotate Key", style = MaterialTheme.typography.labelSmall) },
                                            leadingIcon = { Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            GeohashDirectoryTab(
                presetRooms = presetGeohashRooms,
                bookmarkedRooms = bookmarkedGeohashRooms,
                onJoinGeohashRoom = onJoinGeohashRoom,
                onToggleBookmark = onToggleBookmarkGeohash
            )
        }
    }

    if (showCreateChannelDialog) {
        var name by remember { mutableStateOf("#") }
        var topic by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var isPasswordProtected by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showCreateChannelDialog = false },
            title = { Text("Join or Create Mesh Channel") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Channel Name (e.g. #mesh-ops)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = topic,
                        onValueChange = { topic = it },
                        label = { Text("Topic / Description") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    FilterChip(
                        selected = isPasswordProtected,
                        onClick = { isPasswordProtected = !isPasswordProtected },
                        label = { Text("Enable Argon2id Password Encryption") },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(14.dp)) }
                    )
                    if (isPasswordProtected) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Shared Channel Password") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "Note: Password protection encrypts packets against non-members on the mesh, but cannot prevent a malicious member who knows the password from reading messages.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (isPasswordProtected) {
                            onCreatePasswordChannel(name, password, topic)
                        } else {
                            onCreatePublicChannel(name, topic)
                        }
                        showCreateChannelDialog = false
                    }
                ) {
                    Text("Join / Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateChannelDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun GeohashDirectoryTab(
    presetRooms: List<GeohashRoom>,
    bookmarkedRooms: List<GeohashRoom>,
    onJoinGeohashRoom: (String) -> Unit,
    onToggleBookmark: (GeohashRoom) -> Unit
) {
    var manualGeohash by remember { mutableStateOf("u33d") }
    var selectedPrecision by remember { mutableIntStateOf(4) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Manual Coarse Geohash Selector (Zero GPS Permission Required)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = GeohashCodec.privacyWarningForPrecision(selectedPrecision),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Slider(
                        value = selectedPrecision.toFloat(),
                        onValueChange = {
                            selectedPrecision = it.toInt().coerceIn(1, 6)
                            if (manualGeohash.length > selectedPrecision) {
                                manualGeohash = manualGeohash.take(selectedPrecision)
                            }
                        },
                        valueRange = 1f..6f,
                        steps = 4
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = manualGeohash,
                            onValueChange = { manualGeohash = it.take(8) },
                            label = { Text("Geohash Code (Base32)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = { onJoinGeohashRoom(manualGeohash.take(selectedPrecision)) }) {
                            Text("Join #geo-${manualGeohash.take(selectedPrecision)}")
                        }
                    }
                }
            }
        }

        if (bookmarkedRooms.isNotEmpty()) {
            item {
                Text(
                    text = "Bookmarked Geohash Regions",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            items(bookmarkedRooms, key = { "bm_${it.geohash}" }) { room ->
                GeohashRoomItemCard(
                    room = room,
                    isBookmarked = true,
                    onJoin = { onJoinGeohashRoom(room.geohash.take(selectedPrecision)) },
                    onToggleBookmark = { onToggleBookmark(room) }
                )
            }
        }

        item {
            Text(
                text = "Global & Regional Geohash Directory",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        items(presetRooms, key = { it.geohash }) { room ->
            val isBm = bookmarkedRooms.any { it.geohash == room.geohash }
            GeohashRoomItemCard(
                room = room,
                isBookmarked = isBm,
                onJoin = { onJoinGeohashRoom(room.geohash.take(selectedPrecision)) },
                onToggleBookmark = { onToggleBookmark(room) }
            )
        }
    }
}

@Composable
private fun GeohashRoomItemCard(
    room: GeohashRoom,
    isBookmarked: Boolean,
    onJoin: () -> Unit,
    onToggleBookmark: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "#geo-${room.geohash} — ${room.approximateRegionLabel}",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Approx Center: ${"%.2f".format(room.centerLatitude)}, ${"%.2f".format(room.centerLongitude)} • Radius: ±${room.approxRadiusKm} km",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleBookmark) {
                    Icon(
                        imageVector = if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = "Bookmark Geohash",
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                }
                OutlinedButton(onClick = onJoin) {
                    Text("Enter")
                }
            }
        }
    }
}

