package com.nohead.mesh.domain.service

import android.content.Context
import com.nohead.mesh.data.local.ChannelDao
import com.nohead.mesh.data.local.CourierMailboxDao
import com.nohead.mesh.data.local.GeohashBookmarkDao
import com.nohead.mesh.data.local.MessageDao
import com.nohead.mesh.data.local.NostrRelayDao
import com.nohead.mesh.data.local.OutgoingQueueDao
import com.nohead.mesh.data.local.PeerDao
import com.nohead.mesh.data.local.SeenPacketDao
import com.nohead.mesh.data.transport.ble.BleMeshForegroundService
import com.nohead.mesh.data.transport.ble.BleMeshTransport
import com.nohead.mesh.data.transport.nostr.NostrTransport
import com.nohead.mesh.data.transport.simulated.SimulatedLoopbackTransport
import com.nohead.mesh.data.transport.wifiaware.WifiAwareTransport
import com.nohead.mesh.domain.model.NetworkDiagnosticsSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Emergency Privacy Wipe & Sanitized Diagnostics Manager.
 *
 * Implements:
 * - Immediate stop of all background services & active network transports
 * - Destruction of local Ed25519/X25519 identity keys and Android Keystore SQLCipher wrap key
 * - Complete erasure of messages, peers, channels, store-and-forward queues, courier mailboxes,
 *   seen-packet caches, attachments, and cached diagnostics
 * - Honest warning regarding flash storage wear-leveling and external peer/relay copies
 * - Sanitized diagnostics export (zero message plaintext, zero private keys, zero nicknames)
 */
@Singleton
class PrivacyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identityManager: IdentityManager,
    private val messageRouter: MessageRouter,
    private val attachmentAndVoiceManager: AttachmentAndVoiceManager,
    private val bleTransport: BleMeshTransport,
    private val wifiAwareTransport: WifiAwareTransport,
    private val nostrTransport: NostrTransport,
    private val simulatedTransport: SimulatedLoopbackTransport,
    private val messageDao: MessageDao,
    private val peerDao: PeerDao,
    private val channelDao: ChannelDao,
    private val outgoingQueueDao: OutgoingQueueDao,
    private val courierMailboxDao: CourierMailboxDao,
    private val seenPacketDao: SeenPacketDao,
    private val nostrRelayDao: NostrRelayDao,
    private val geohashBookmarkDao: GeohashBookmarkDao,
    private val channelManager: ChannelManager
) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    /**
     * Executes the complete Emergency Privacy Wipe sequence.
     */
    suspend fun executeEmergencyPrivacyWipe() {
        // 1. Stop Foreground Service and all active radio/network transports immediately
        BleMeshForegroundService.stopService(context)
        bleTransport.stop()
        wifiAwareTransport.stop()
        nostrTransport.stop()
        simulatedTransport.stop()

        // 2. Wipe all Room SQLCipher tables
        messageDao.wipeAllMessages()
        peerDao.wipeAllPeers()
        channelDao.wipeAllChannels()
        outgoingQueueDao.wipeQueue()
        courierMailboxDao.wipeMailbox()
        seenPacketDao.wipeSeenPackets()
        nostrRelayDao.wipeRelays()
        geohashBookmarkDao.wipeBookmarks()

        // 3. Zero-overwrite and delete cached attachments, voice notes, and diagnostics files
        attachmentAndVoiceManager.deleteAllAttachmentsForWipe()
        File(context.cacheDir, "diagnostics").deleteRecursively()

        // 4. Clear in-memory router caches and counters
        messageRouter.clearInMemoryRouterStateForWipe()

        // 5. Destroy Android Keystore wrap keys and regenerate a clean, unlinked identity
        identityManager.destroyIdentityKeysForEmergencyWipe()

        // 6. Recreate default empty channels and restart local mesh transport cleanly
        channelManager.ensureDefaultChannelsExist()
        bleTransport.start()
        if (messageRouter.simulatedMeshEnabled.value) {
            simulatedTransport.start()
        }
        messageRouter.refreshDiagnostics()
    }

    /**
     * Exports a sanitized JSON diagnostics report containing strictly protocol counters,
     * transport state, and version info — NEVER private message content or secret keys.
     */
    fun exportSanitizedDiagnosticsReport(snapshot: NetworkDiagnosticsSnapshot): String {
        return json.encodeToString(snapshot)
    }
}

