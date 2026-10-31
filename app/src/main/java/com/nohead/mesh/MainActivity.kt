package com.nohead.mesh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nohead.mesh.ui.screens.ChannelsAndGeohashScreen
import com.nohead.mesh.ui.screens.ChatScreen
import com.nohead.mesh.ui.screens.DiagnosticsScreen
import com.nohead.mesh.ui.screens.DirectConversationsScreen
import com.nohead.mesh.ui.screens.OnboardingScreen
import com.nohead.mesh.ui.screens.PeersAndFavoritesScreen
import com.nohead.mesh.ui.screens.SettingsScreen
import com.nohead.mesh.ui.theme.NoHeadTheme
import com.nohead.mesh.ui.viewmodel.NoHeadMainViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: NoHeadMainViewModel = hiltViewModel()
            val themeMode by viewModel.themeMode.collectAsState()
            NoHeadTheme(themeMode = themeMode) {
                NoHeadAppRoot(viewModel = viewModel)
            }
        }
    }
}

private enum class NavSection(val title: String, val icon: ImageVector) {
    CHAT("Chat", Icons.AutoMirrored.Filled.Chat),
    PEERS("People", Icons.Default.Groups),
    CHANNELS("Channels", Icons.Default.Tag),
    DIRECT("Direct", Icons.Default.Lock),
    SYSTEM("System", Icons.Default.Settings)
}

@Composable
fun NoHeadAppRoot(viewModel: NoHeadMainViewModel) {
    val onboardingDone by viewModel.onboardingCompleted.collectAsState()
    val identity by viewModel.identityState.collectAsState()
    val diagnostics by viewModel.diagnosticsState.collectAsState()
    val channels by viewModel.allChannels.collectAsState()
    val peers by viewModel.allPeers.collectAsState()
    val activeConvId by viewModel.activeConversationId.collectAsState()
    val isDirect by viewModel.isDirectConversationActive.collectAsState()
    val messages by viewModel.activeMessages.collectAsState()
    val directConversations by viewModel.directConversations.collectAsState()
    val nostrRelays by viewModel.nostrRelays.collectAsState()
    val bookmarkedGeohash by viewModel.bookmarkedGeohashRooms.collectAsState()
    val isRecordingVoice by viewModel.isRecordingVoice.collectAsState()
    val isPlayingVoiceId by viewModel.isPlayingVoiceId.collectAsState()
    val isLivePtt by viewModel.isLivePttStreaming.collectAsState()
    val hopLimit by viewModel.configuredHopLimit.collectAsState()
    val readReceipts by viewModel.readReceiptsEnabled.collectAsState()
    val simulatedMesh by viewModel.simulatedMeshEnabled.collectAsState()
    val foregroundService by viewModel.foregroundServiceEnabled.collectAsState()
    val torProxy by viewModel.torProxyEnabled.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val bannerMessage by viewModel.statusBannerMessage.collectAsState()

    var currentNavOrdinal by rememberSaveable { mutableIntStateOf(NavSection.CHAT.ordinal) }
    var peopleFavoritesOnly by rememberSaveable { mutableStateOf(false) }
    var systemSubTab by rememberSaveable { mutableIntStateOf(0) } // 0 = Settings, 1 = Diagnostics
    val currentSection = NavSection.entries[currentNavOrdinal.coerceIn(0, NavSection.entries.lastIndex)]

    if (!onboardingDone) {
        OnboardingScreen(
            identity = identity,
            onRegenerateIdentity = { viewModel.rotateIdentityKeys() },
            onCompleteOnboarding = { nick -> viewModel.completeOnboarding(nick) }
        )
        return
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        bottomBar = {
            NavigationBar {
                NavSection.entries.forEach { section ->
                    NavigationBarItem(
                        selected = currentSection == section,
                        onClick = { currentNavOrdinal = section.ordinal },
                        icon = { Icon(section.icon, contentDescription = section.title) },
                        label = { Text(section.title, maxLines = 1) },
                        alwaysShowLabel = true
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentSection) {
                NavSection.CHAT -> {
                    ChatScreen(
                        identity = identity,
                        diagnostics = diagnostics,
                        hopLimit = hopLimit,
                        channels = channels,
                        peers = peers,
                        activeConversationId = activeConvId,
                        isDirectMessage = isDirect,
                        messages = messages,
                        isRecordingVoice = isRecordingVoice,
                        isPlayingVoiceId = isPlayingVoiceId,
                        isLivePttStreaming = isLivePtt,
                        onSelectChannel = { viewModel.selectChannelConversation(it) },
                        onSendText = { viewModel.sendComposerInput(it) },
                        onSendAttachment = { name, mime, bytes ->
                            viewModel.sendSampleAttachment(name, mime, bytes)
                        },
                        onToggleVoiceRecord = { viewModel.toggleVoiceNoteRecording() },
                        onCancelVoiceRecord = { viewModel.cancelVoiceNoteRecording() },
                        onPlayVoiceNote = { id, path -> viewModel.playOrStopVoiceNote(id, path) },
                        onStartLivePtt = { viewModel.startLivePushToTalk() },
                        onStopLivePtt = { viewModel.stopLivePushToTalk() },
                        onRotateChannelKeys = { viewModel.rotateChannelKeys(it) },
                        onOpenDiagnostics = {
                            systemSubTab = 1
                            currentNavOrdinal = NavSection.SYSTEM.ordinal
                        }
                    )
                }

                NavSection.PEERS -> {
                    PeersAndFavoritesScreen(
                        localIdentity = identity,
                        peers = peers,
                        showFavoritesOnly = peopleFavoritesOnly,
                        onToggleShowFavoritesOnly = { peopleFavoritesOnly = it },
                        onStartDirectMessage = { peerId ->
                            viewModel.selectDirectPeerConversation(peerId)
                            currentNavOrdinal = NavSection.CHAT.ordinal
                        },
                        onToggleFavorite = { viewModel.toggleFavoritePeer(it) },
                        onToggleBlock = { viewModel.toggleBlockPeer(it) },
                        onToggleTrustedCourier = { viewModel.toggleTrustedCourier(it) },
                        onVerifyPeer = { viewModel.markPeerVerified(it) },
                        onTriggerBleScan = { viewModel.triggerManualBleScan() }
                    )
                }

                NavSection.CHANNELS -> {
                    ChannelsAndGeohashScreen(
                        channels = channels,
                        presetGeohashRooms = viewModel.presetGeohashRooms,
                        bookmarkedGeohashRooms = bookmarkedGeohash,
                        onOpenChannel = { chId ->
                            viewModel.selectChannelConversation(chId)
                            currentNavOrdinal = NavSection.CHAT.ordinal
                        },
                        onCreatePublicChannel = { name, topic ->
                            viewModel.createPublicChannel(name, topic)
                            currentNavOrdinal = NavSection.CHAT.ordinal
                        },
                        onCreatePasswordChannel = { name, pwd, topic ->
                            viewModel.createPasswordChannel(name, pwd, topic)
                            currentNavOrdinal = NavSection.CHAT.ordinal
                        },
                        onRotateChannelKey = { viewModel.rotateChannelKeys(it) },
                        onJoinGeohashRoom = { geo ->
                            viewModel.joinGeohashRoom(geo)
                            currentNavOrdinal = NavSection.CHAT.ordinal
                        },
                        onToggleBookmarkGeohash = { viewModel.toggleBookmarkGeohash(it) }
                    )
                }

                NavSection.DIRECT -> {
                    DirectConversationsScreen(
                        conversations = directConversations,
                        onOpenConversation = { peerId ->
                            viewModel.selectDirectPeerConversation(peerId)
                            currentNavOrdinal = NavSection.CHAT.ordinal
                        },
                        onRotateSessionKey = { viewModel.rotatePeerSessionKey(it) }
                    )
                }

                NavSection.SYSTEM -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        TabRow(selectedTabIndex = systemSubTab) {
                            Tab(
                                selected = systemSubTab == 0,
                                onClick = { systemSubTab = 0 },
                                text = { Text("Settings & Privacy") },
                                icon = { Icon(Icons.Default.Settings, contentDescription = null) }
                            )
                            Tab(
                                selected = systemSubTab == 1,
                                onClick = { systemSubTab = 1 },
                                text = { Text("Mesh Diagnostics") },
                                icon = { Icon(Icons.Default.Analytics, contentDescription = null) }
                            )
                        }
                        if (systemSubTab == 0) {
                            SettingsScreen(
                                identity = identity,
                                themeMode = themeMode,
                                hopLimit = hopLimit,
                                readReceiptsEnabled = readReceipts,
                                simulatedMeshEnabled = simulatedMesh,
                                batterySaverEnabled = diagnostics.batterySaverModeEnabled,
                                foregroundServiceEnabled = foregroundService,
                                torProxyEnabled = torProxy,
                                nostrRelays = nostrRelays,
                                onSetThemeMode = { viewModel.setThemeMode(it) },
                                onUpdateNickname = { viewModel.updateNickname(it) },
                                onRotateIdentityKeys = { viewModel.rotateIdentityKeys() },
                                onExportIdentityBackup = { viewModel.exportIdentityBackup(it) },
                                onImportIdentityBackup = { json, pwd -> viewModel.importIdentityBackup(json, pwd) },
                                onSetHopLimit = { viewModel.setHopLimit(it) },
                                onSetReadReceipts = { viewModel.setReadReceipts(it) },
                                onSetSimulatedMesh = { viewModel.setSimulatedMesh(it) },
                                onToggleBatterySaver = { viewModel.toggleBatterySaver(it) },
                                onToggleForegroundService = { viewModel.toggleForegroundMeshService(it) },
                                onToggleTorProxy = { viewModel.toggleTorProxy(it) },
                                onToggleNostrRelay = { viewModel.toggleNostrRelay(it) },
                                onAddNostrRelay = { viewModel.addNostrRelay(it) },
                                onTriggerEmergencyWipe = { viewModel.triggerEmergencyPrivacyWipe() }
                            )
                        } else {
                            DiagnosticsScreen(
                                diagnostics = diagnostics,
                                onRefreshScan = { viewModel.triggerManualBleScan() },
                                onExportSanitizedDiagnostics = { viewModel.exportSanitizedDiagnostics() }
                            )
                        }
                    }
                }
            }

            bannerMessage?.let { msg ->
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp),
                    action = {
                        TextButton(onClick = { viewModel.dismissBanner() }) {
                            Text("OK")
                        }
                    }
                ) {
                    Text(msg)
                }
            }
        }
    }
}

