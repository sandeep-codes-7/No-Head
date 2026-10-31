package com.nohead.mesh.domain.service

import com.nohead.mesh.data.local.MessageDao
import com.nohead.mesh.data.local.MessageEntity
import com.nohead.mesh.data.local.SeenPacketDao
import com.nohead.mesh.data.local.SeenPacketEntity
import com.nohead.mesh.data.transport.ble.BleMeshTransport
import com.nohead.mesh.data.transport.nostr.NostrTransport
import com.nohead.mesh.data.transport.simulated.SimulatedLoopbackTransport
import com.nohead.mesh.data.transport.wifiaware.WifiAwareTransport
import com.nohead.mesh.domain.crypto.CryptoEngine
import com.nohead.mesh.domain.model.AckPayload
import com.nohead.mesh.domain.model.AckType
import com.nohead.mesh.domain.model.ChannelType
import com.nohead.mesh.domain.model.ChatMessage
import com.nohead.mesh.domain.model.DeliveryStatus
import com.nohead.mesh.domain.model.MessageContentType
import com.nohead.mesh.domain.model.MeshEnvelope
import com.nohead.mesh.domain.model.NetworkDiagnosticsSnapshot
import com.nohead.mesh.domain.model.PacketType
import com.nohead.mesh.domain.model.PeerAnnouncementPayload
import com.nohead.mesh.domain.model.ProtocolConstants
import com.nohead.mesh.domain.model.TransportType
import com.nohead.mesh.domain.transport.InboundTransportPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Core Transport-Independent Multi-Hop Mesh Router & Message Coordinator.
 *
 * Responsibilities:
 * - Aggregates inbound packets across BLE Mesh, Wi-Fi Aware, Nostr Relays, and Simulated Loopback
 * - Enforces idempotent deduplication (in-memory LRU + persistent `SeenPacketDao`) so the same logical
 *   message never appears twice even if received over multiple hops and multiple transports
 * - Validates packet schema, TTL bounds, replay/expiration timestamps, and Ed25519 origin signatures
 * - Per-peer sliding window rate limiting to prevent flooding/spam attacks
 * - Multi-hop relay forwarding (`ttl - 1`, `hopCount + 1`) with randomized jitter (25-95ms) to prevent
 *   BLE radio collisions and rebroadcast storms
 * - Honest delivery state transitions (`QUEUED` -> `SENT` -> `RELAYED` -> `DELIVERED` -> `READ` / `FAILED`)
 */
@Singleton
class MessageRouter @Inject constructor(
    private val bleTransport: BleMeshTransport,
    private val wifiAwareTransport: WifiAwareTransport,
    private val nostrTransport: NostrTransport,
    private val simulatedTransport: SimulatedLoopbackTransport,
    private val identityManager: IdentityManager,
    private val peerManager: PeerManager,
    private val sessionManager: SessionManager,
    private val channelManager: ChannelManager,
    private val storeForwardManager: StoreForwardManager,
    private val attachmentAndVoiceManager: AttachmentAndVoiceManager,
    private val messageDao: MessageDao,
    private val seenPacketDao: SeenPacketDao
) {
    companion object {
        private const val IN_MEMORY_SEEN_CACHE_MAX = 4096
        private const val RATE_LIMIT_WINDOW_MS = 10_000L
        private const val MAX_PACKETS_PER_PEER_WINDOW = 45
        private const val MAX_CLOCK_SKEW_MS = 300_000L // 5 minutes
    }

    @Serializable
    data class WireChatBody(
        val text: String,
        val contentType: MessageContentType = MessageContentType.TEXT,
        val attachmentJson: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // Bounded in-memory LRU cache for ultra-fast deduplication before hitting SQLite
    private val seenCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(IN_MEMORY_SEEN_CACHE_MAX, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
                return size > IN_MEMORY_SEEN_CACHE_MAX
            }
        }
    )

    private data class PeerRateBucket(
        var windowStartMs: Long,
        var count: Int
    )

    private val peerRateBuckets = ConcurrentHashMap<String, PeerRateBucket>()

    private val packetsSentTotal = AtomicLong(0)
    private val packetsReceivedTotal = AtomicLong(0)
    private val packetsRelayedTotal = AtomicLong(0)
    private val packetsDeduplicatedDropped = AtomicLong(0)
    private val packetsRateLimitedDropped = AtomicLong(0)
    private val packetsInvalidSignatureDropped = AtomicLong(0)

    private val _configuredHopLimit = MutableStateFlow(ProtocolConstants.DEFAULT_MAX_TTL)
    val configuredHopLimit: StateFlow<Int> = _configuredHopLimit.asStateFlow()

    private val _readReceiptsEnabled = MutableStateFlow(true)
    val readReceiptsEnabled: StateFlow<Boolean> = _readReceiptsEnabled.asStateFlow()

    private val _simulatedMeshEnabled = MutableStateFlow(true)
    val simulatedMeshEnabled: StateFlow<Boolean> = _simulatedMeshEnabled.asStateFlow()

    private val _diagnosticsFlow = MutableStateFlow(buildDiagnosticsSnapshot(0, 0))
    val diagnosticsFlow: StateFlow<NetworkDiagnosticsSnapshot> = _diagnosticsFlow.asStateFlow()

    init {
        scope.launch {
            channelManager.ensureDefaultChannelsExist()
            nostrTransport.initializeDefaultRelaysIfEmpty()
            bleTransport.start()
            if (wifiAwareTransport.isSupported.value) {
                wifiAwareTransport.start()
            }
            if (_simulatedMeshEnabled.value) {
                simulatedTransport.start()
            }
            observeInboundPackets()
        }

        scope.launch {
            while (isActive) {
                delay(5_000L)
                processStoreAndForwardQueue()
                refreshDiagnostics()
            }
        }
    }

    fun setConfiguredHopLimit(ttl: Int) {
        _configuredHopLimit.value = ttl.coerceIn(1, ProtocolConstants.MAX_ALLOWED_TTL)
    }

    fun setReadReceiptsEnabled(enabled: Boolean) {
        _readReceiptsEnabled.value = enabled
    }

    fun setSimulatedMeshEnabled(enabled: Boolean) {
        _simulatedMeshEnabled.value = enabled
        scope.launch {
            if (enabled) simulatedTransport.start() else simulatedTransport.stop()
            refreshDiagnostics()
        }
    }

    fun observeMessagesForConversation(conversationId: String): Flow<List<ChatMessage>> {
        return messageDao.observeMessagesForConversation(conversationId).map { list ->
            list.map { it.toDomain() }
        }
    }

    private fun observeInboundPackets() {
        scope.launch {
            merge(
                bleTransport.receivePackets(),
                wifiAwareTransport.receivePackets(),
                nostrTransport.receivePackets(),
                simulatedTransport.receivePackets()
            ).collect { inbound ->
                handleInboundPacket(inbound)
            }
        }
    }

    /**
     * Entry point for all incoming packets across all transports.
     * Exposed as `suspend fun` so integration tests can verify multi-hop and deduplication behavior directly.
     */
    suspend fun handleInboundPacket(inbound: InboundTransportPacket) {
        packetsReceivedTotal.incrementAndGet()
        val env = inbound.envelope
        val now = System.currentTimeMillis()

        // 1. Validate protocol version & basic bounds
        if (env.protocolVersion != ProtocolConstants.PROTOCOL_VERSION) return
        if (env.ttl < 0 || env.ttl > ProtocolConstants.MAX_ALLOWED_TTL) return
        if (env.expiresAtEpochMs <= now) return
        if (env.createdAtEpochMs > now + MAX_CLOCK_SKEW_MS) return

        // 2. Ignore loopback of our own packets
        val localIdentity = identityManager.identityState.value
        if (env.senderPeerId == localIdentity.peerId) return

        // 3. Blocklist check
        if (peerManager.isPeerBlocked(env.senderPeerId)) return

        // 4. Rate limiting check per sender peer
        if (!allowPeerRateLimit(env.senderPeerId, now)) {
            packetsRateLimitedDropped.incrementAndGet()
            return
        }

        // 5. Idempotent Deduplication (in-memory LRU + persistent DB)
        synchronized(seenCache) {
            if (seenCache.containsKey(env.packetId)) {
                packetsDeduplicatedDropped.incrementAndGet()
                return
            }
            seenCache[env.packetId] = now
        }
        if (seenPacketDao.hasSeen(env.packetId, now)) {
            packetsDeduplicatedDropped.incrementAndGet()
            return
        }
        seenPacketDao.markSeen(
            SeenPacketEntity(
                packetId = env.packetId,
                senderPeerId = env.senderPeerId,
                seenAtEpochMs = now,
                expiresAtEpochMs = env.expiresAtEpochMs
            )
        )

        // 6. Cryptographic Origin Signature Verification
        val isVerifiedOrigin = if (env.packetType == PacketType.HANDSHAKE_INIT || env.packetType == PacketType.HANDSHAKE_RESPONSE) {
            val payloadBytes = runCatching { CryptoEngine.decodeBase64(env.payloadBase64) }.getOrNull()
            val announce = payloadBytes?.let {
                runCatching { json.decodeFromString<PeerAnnouncementPayload>(it.decodeToString()) }.getOrNull()
            }
            if (announce != null) {
                val pubKeyBytes = runCatching {
                    with(CryptoEngine) { env.senderSigningPubKeyHex.hexToByteArray() }
                }.getOrNull()
                if (pubKeyBytes != null && identityManager.let {
                        val signBytes = "${env.senderPeerId}|${env.senderSigningPubKeyHex}|${env.senderEncryptionPubKeyHex}|${announce.rotatingPresenceToken}".encodeToByteArray()
                        val sigBytes = runCatching { CryptoEngine.decodeBase64(env.signatureBase64) }.getOrNull()
                        sigBytes != null && CryptoEngine().verifyEd25519(pubKeyBytes, signBytes, sigBytes)
                    }
                ) {
                    true
                } else {
                    identityManager.verifyEnvelopeSignature(env)
                }
            } else {
                identityManager.verifyEnvelopeSignature(env)
            }
        } else {
            identityManager.verifyEnvelopeSignature(env)
        }

        if (!isVerifiedOrigin) {
            packetsInvalidSignatureDropped.incrementAndGet()
            return
        }

        // 7. Update sender peer presence & drain any store-and-forward envelopes for them
        peerManager.registerPeerFromEnvelope(
            peerId = env.senderPeerId,
            nickname = env.senderNickname,
            signingPubKeyHex = env.senderSigningPubKeyHex,
            encryptionPubKeyHex = env.senderEncryptionPubKeyHex,
            sourceTransport = inbound.sourceTransport,
            rssiDbm = inbound.rssiDbm,
            hopDistance = env.hopCount + 1
        )

        // 8. Process payload by PacketType
        when (env.packetType) {
            PacketType.PEER_ANNOUNCE, PacketType.HANDSHAKE_INIT, PacketType.HANDSHAKE_RESPONSE -> {
                val payloadBytes = runCatching { CryptoEngine.decodeBase64(env.payloadBase64) }.getOrNull()
                if (payloadBytes != null) {
                    val announce = runCatching {
                        json.decodeFromString<PeerAnnouncementPayload>(payloadBytes.decodeToString())
                    }.getOrNull()
                    if (announce != null) {
                        peerManager.onPeerDiscoveredOrUpdated(
                            announcement = announce,
                            sourceTransport = inbound.sourceTransport,
                            rssiDbm = inbound.rssiDbm,
                            hopDistance = env.hopCount + 1
                        )
                        flushQueuedEnvelopesForPeer(announce.peerId)
                    }
                }
            }

            PacketType.PUBLIC_CHANNEL_MSG -> {
                val channelId = env.channelId ?: ProtocolConstants.DEFAULT_CHANNEL_ID
                val bodyBytes = runCatching { CryptoEngine.decodeBase64(env.payloadBase64) }.getOrNull()
                if (bodyBytes != null) {
                    val wireBody = runCatching {
                        json.decodeFromString<WireChatBody>(bodyBytes.decodeToString())
                    }.getOrElse {
                        WireChatBody(text = bodyBytes.decodeToString())
                    }
                    persistIncomingMessage(
                        envelope = env,
                        conversationId = channelId,
                        isDirectMessage = false,
                        decryptedText = wireBody.text,
                        contentType = wireBody.contentType,
                        attachmentJson = wireBody.attachmentJson,
                        sourceTransport = inbound.sourceTransport,
                        isEncrypted = false
                    )
                }
            }

            PacketType.PASSWORD_CHANNEL_MSG -> {
                val channelId = env.channelId ?: return
                val meta = env.encryptionMetadata
                if (meta != null) {
                    val decryptedBytes = channelManager.decryptForPasswordChannel(
                        channelId = channelId,
                        ciphertextBase64 = env.payloadBase64,
                        metadata = meta,
                        associatedData = env.associatedDataBytes()
                    )
                    if (decryptedBytes != null) {
                        val wireBody = runCatching {
                            json.decodeFromString<WireChatBody>(decryptedBytes.decodeToString())
                        }.getOrElse {
                            WireChatBody(text = decryptedBytes.decodeToString())
                        }
                        persistIncomingMessage(
                            envelope = env,
                            conversationId = channelId,
                            isDirectMessage = false,
                            decryptedText = wireBody.text,
                            contentType = wireBody.contentType,
                            attachmentJson = wireBody.attachmentJson,
                            sourceTransport = inbound.sourceTransport,
                            isEncrypted = true
                        )
                    }
                }
            }

            PacketType.DIRECT_ENCRYPTED_MSG -> {
                if (env.recipientPeerId == localIdentity.peerId) {
                    val meta = env.encryptionMetadata ?: return
                    val decrypted = runCatching {
                        sessionManager.decryptDirectMessage(
                            senderPeerId = env.senderPeerId,
                            senderEncryptionPubKeyHex = env.senderEncryptionPubKeyHex,
                            ciphertextBase64 = env.payloadBase64,
                            encryptionMetadata = meta,
                            associatedData = env.associatedDataBytes()
                        )
                    }.getOrNull()

                    if (decrypted != null) {
                        val wireBody = runCatching {
                            json.decodeFromString<WireChatBody>(decrypted.decodeToString())
                        }.getOrElse {
                            WireChatBody(text = decrypted.decodeToString())
                        }
                        persistIncomingMessage(
                            envelope = env,
                            conversationId = env.senderPeerId,
                            isDirectMessage = true,
                            decryptedText = wireBody.text,
                            contentType = wireBody.contentType,
                            attachmentJson = wireBody.attachmentJson,
                            sourceTransport = inbound.sourceTransport,
                            isEncrypted = true
                        )
                        sendDeliveryAck(env, AckType.DELIVERED)
                        if (_readReceiptsEnabled.value) {
                            sendDeliveryAck(env, AckType.READ)
                        }
                    }
                    return // Addressed to us directly; no need to relay further
                } else {
                    // Addressed to another peer: store in opaque courier mailbox AND relay if TTL allows
                    storeForwardManager.storeCourierEnvelope(env)
                }
            }

            PacketType.DELIVERY_ACK, PacketType.READ_RECEIPT -> {
                val ackBytes = runCatching { CryptoEngine.decodeBase64(env.payloadBase64) }.getOrNull()
                if (ackBytes != null) {
                    val ack = runCatching { json.decodeFromString<AckPayload>(ackBytes.decodeToString()) }.getOrNull()
                    if (ack != null) {
                        handleIncomingAck(ack)
                    }
                }
                if (env.recipientPeerId == localIdentity.peerId) return
            }

            PacketType.ATTACHMENT_CHUNK, PacketType.VOICE_NOTE -> {
                val rawBytes = runCatching { CryptoEngine.decodeBase64(env.payloadBase64) }.getOrNull()
                if (rawBytes != null) {
                    val chunkWire = runCatching {
                        json.decodeFromString<AttachmentAndVoiceManager.AttachmentChunkWire>(rawBytes.decodeToString())
                    }.getOrNull()
                    if (chunkWire != null) {
                        val meta = attachmentAndVoiceManager.onAttachmentChunkReceived(chunkWire)
                        if (meta != null && meta.receivedChunks == meta.totalChunks) {
                            val convId = env.channelId ?: env.senderPeerId
                            val contentType = if (env.packetType == PacketType.VOICE_NOTE) {
                                MessageContentType.VOICE_NOTE
                            } else if (meta.mimeType.startsWith("image/")) {
                                MessageContentType.IMAGE_ATTACHMENT
                            } else {
                                MessageContentType.FILE_ATTACHMENT
                            }
                            persistIncomingMessage(
                                envelope = env,
                                conversationId = convId,
                                isDirectMessage = env.channelId == null,
                                decryptedText = if (contentType == MessageContentType.VOICE_NOTE) {
                                    "Voice Note (${meta.durationSeconds ?: 1}s)"
                                } else {
                                    "Attachment: ${meta.fileName} (${meta.sizeBytes} B)"
                                },
                                contentType = contentType,
                                attachmentJson = json.encodeToString(meta),
                                sourceTransport = inbound.sourceTransport,
                                isEncrypted = env.encryptionMetadata != null
                            )
                        }
                    }
                }
            }

            PacketType.LIVE_PTT_FRAME -> {
                val rawBytes = runCatching { CryptoEngine.decodeBase64(env.payloadBase64) }.getOrNull()
                if (rawBytes != null) {
                    val frame = runCatching {
                        json.decodeFromString<AttachmentAndVoiceManager.LivePttFrameWire>(rawBytes.decodeToString())
                    }.getOrNull()
                    if (frame != null) {
                        attachmentAndVoiceManager.playIncomingLivePttFrame(frame)
                    }
                }
            }

            else -> Unit
        }

        // 9. Multi-Hop Relay Decision
        // Relay packet if TTL > 1 and it came from a local mesh transport
        if (env.ttl > 1 && inbound.sourceTransport != TransportType.NOSTR_RELAY) {
            relayEnvelopeMultiHop(env)
        }
    }

    private suspend fun relayEnvelopeMultiHop(original: MeshEnvelope) {
        // Randomized backoff jitter (25-85ms) prevents BLE broadcast collision storms
        // when multiple neighboring nodes hear and rebroadcast the same packet simultaneously.
        delay((25L..85L).random())
        val relayedEnvelope = original.copy(
            ttl = original.ttl - 1,
            hopCount = original.hopCount + 1
        )
        packetsRelayedTotal.incrementAndGet()
        dispatchToActiveTransports(relayedEnvelope, includeInternetNostr = false)
    }

    private suspend fun persistIncomingMessage(
        envelope: MeshEnvelope,
        conversationId: String,
        isDirectMessage: Boolean,
        decryptedText: String,
        contentType: MessageContentType,
        attachmentJson: String?,
        sourceTransport: TransportType,
        isEncrypted: Boolean
    ) {
        val peer = peerManager.getPeerById(envelope.senderPeerId)
        val detectedType = if (contentType == MessageContentType.TEXT && decryptedText.trim().startsWith("cashuA")) {
            MessageContentType.CASHU_TOKEN
        } else {
            contentType
        }
        val entity = MessageEntity(
            messageId = envelope.packetId,
            conversationId = conversationId,
            isDirectMessage = isDirectMessage,
            senderPeerId = envelope.senderPeerId,
            senderNickname = envelope.senderNickname,
            senderVerified = peer?.isVerified == true,
            recipientPeerId = envelope.recipientPeerId,
            content = decryptedText,
            contentType = detectedType,
            timestampEpochMs = envelope.createdAtEpochMs,
            expiresAtEpochMs = envelope.expiresAtEpochMs,
            deliveryStatus = DeliveryStatus.DELIVERED,
            transportOrigin = sourceTransport,
            hopCount = envelope.hopCount,
            isOutgoing = false,
            isEncrypted = isEncrypted,
            signatureVerified = true,
            attachmentJson = attachmentJson,
            replyToMessageId = envelope.replyToMessageId
        )
        messageDao.insertMessageIgnoreDuplicate(entity)
    }

    private suspend fun handleIncomingAck(ack: AckPayload) {
        val existing = messageDao.getMessageById(ack.targetMessageId) ?: return
        val newStatus = when (ack.ackType) {
            AckType.RELAYED -> {
                if (existing.deliveryStatus == DeliveryStatus.QUEUED || existing.deliveryStatus == DeliveryStatus.SENT) {
                    DeliveryStatus.RELAYED
                } else existing.deliveryStatus
            }
            AckType.DELIVERED -> {
                storeForwardManager.acknowledgeDelivered(ack.targetMessageId)
                if (existing.deliveryStatus != DeliveryStatus.READ) DeliveryStatus.DELIVERED else existing.deliveryStatus
            }
            AckType.READ -> {
                storeForwardManager.acknowledgeDelivered(ack.targetMessageId)
                DeliveryStatus.READ
            }
            AckType.REJECTED_RATE_LIMIT, AckType.REJECTED_AUTH -> DeliveryStatus.FAILED
        }
        messageDao.updateDeliveryStatus(ack.targetMessageId, newStatus)
    }

    private suspend fun sendDeliveryAck(targetEnvelope: MeshEnvelope, ackType: AckType) {
        val local = identityManager.identityState.value
        val now = System.currentTimeMillis()
        val ackPayload = AckPayload(
            targetMessageId = targetEnvelope.packetId,
            ackType = ackType,
            responderPeerId = local.peerId,
            timestampEpochMs = now,
            hopCountObserved = targetEnvelope.hopCount + 1
        )
        val payloadBase64 = CryptoEngine.encodeBase64(json.encodeToString(ackPayload).encodeToByteArray())
        val unsigned = MeshEnvelope(
            packetId = "ack_${targetEnvelope.packetId}_${ackType.name}_${local.peerId}",
            packetType = if (ackType == AckType.READ) PacketType.READ_RECEIPT else PacketType.DELIVERY_ACK,
            senderPeerId = local.peerId,
            senderNickname = local.nickname,
            senderSigningPubKeyHex = local.signingPublicKeyHex,
            senderEncryptionPubKeyHex = local.encryptionPublicKeyHex,
            recipientPeerId = targetEnvelope.senderPeerId,
            createdAtEpochMs = now,
            expiresAtEpochMs = now + 180_000L,
            ttl = _configuredHopLimit.value,
            hopCount = 0,
            payloadBase64 = payloadBase64,
            signatureBase64 = ""
        )
        val signed = identityManager.signEnvelope(unsigned)
        dispatchToActiveTransports(signed, includeInternetNostr = false)
    }

    /**
     * Sends a message to a public channel, password-protected channel, or geohash room.
     */
    suspend fun sendChannelMessage(
        channel: com.nohead.mesh.domain.model.ChatChannel,
        text: String,
        contentType: MessageContentType = MessageContentType.TEXT,
        attachmentJson: String? = null
    ): ChatMessage {
        val local = identityManager.identityState.value
        val now = System.currentTimeMillis()
        val packetId = "msg_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val expiresAt = now + 86_400_000L // 24h TTL

        val wireBody = WireChatBody(
            text = text,
            contentType = contentType,
            attachmentJson = attachmentJson
        )
        val wireBytes = json.encodeToString(wireBody).encodeToByteArray()

        val draftEnvelope = MeshEnvelope(
            packetId = packetId,
            packetType = if (channel.channelType == ChannelType.PASSWORD_PROTECTED) {
                PacketType.PASSWORD_CHANNEL_MSG
            } else {
                PacketType.PUBLIC_CHANNEL_MSG
            },
            senderPeerId = local.peerId,
            senderNickname = local.nickname,
            senderSigningPubKeyHex = local.signingPublicKeyHex,
            senderEncryptionPubKeyHex = local.encryptionPublicKeyHex,
            channelId = channel.channelId,
            geohash = channel.geohash,
            createdAtEpochMs = now,
            expiresAtEpochMs = expiresAt,
            ttl = _configuredHopLimit.value,
            hopCount = 0,
            payloadBase64 = "",
            signatureBase64 = ""
        )

        val (payloadB64, encMeta, isEncrypted) = if (channel.channelType == ChannelType.PASSWORD_PROTECTED) {
            val encrypted = channelManager.encryptForPasswordChannel(
                channelId = channel.channelId,
                plaintext = wireBytes,
                associatedData = draftEnvelope.associatedDataBytes()
            ) ?: error("Password key missing for ${channel.channelId}")
            Triple(encrypted.first, encrypted.second, true)
        } else {
            Triple(CryptoEngine.encodeBase64(wireBytes), null, false)
        }

        val unsigned = draftEnvelope.copy(
            payloadBase64 = payloadB64,
            encryptionMetadata = encMeta
        )
        val signed = identityManager.signEnvelope(unsigned)

        synchronized(seenCache) {
            seenCache[packetId] = now
        }

        val initialStatus = if (hasAnyConnectedTransport()) DeliveryStatus.SENT else DeliveryStatus.QUEUED
        val activeOrigin = determinePrimaryActiveTransport()

        val entity = MessageEntity(
            messageId = packetId,
            conversationId = channel.channelId,
            isDirectMessage = false,
            senderPeerId = local.peerId,
            senderNickname = local.nickname,
            senderVerified = true,
            recipientPeerId = null,
            content = text,
            contentType = contentType,
            timestampEpochMs = now,
            expiresAtEpochMs = expiresAt,
            deliveryStatus = initialStatus,
            transportOrigin = activeOrigin,
            hopCount = 0,
            isOutgoing = true,
            isEncrypted = isEncrypted,
            signatureVerified = true,
            attachmentJson = attachmentJson
        )
        messageDao.upsertMessage(entity)

        val shouldSendToNostr = channel.channelType == ChannelType.GLOBAL_NOSTR ||
            channel.channelType == ChannelType.GEOHASH_ROOM
        val dispatched = dispatchToActiveTransports(signed, includeInternetNostr = shouldSendToNostr)
        if (!dispatched) {
            storeForwardManager.enqueueOutgoing(signed, priority = 1, allowCourierReplication = false)
            messageDao.updateDeliveryStatus(packetId, DeliveryStatus.QUEUED)
        }

        return entity.toDomain()
    }

    /**
     * Sends an end-to-end encrypted 1:1 Direct Message to [recipientPeerId].
     * Automatically queues in [StoreForwardManager] if the peer is offline or intermittent.
     */
    suspend fun sendDirectMessage(
        recipientPeerId: String,
        text: String,
        contentType: MessageContentType = MessageContentType.TEXT,
        attachmentJson: String? = null
    ): Result<ChatMessage> = runCatching {
        val peer = peerManager.getPeerById(recipientPeerId)
            ?: throw IllegalArgumentException("Peer $recipientPeerId unknown or public key not yet discovered")
        if (peer.isBlocked) {
            throw IllegalStateException("Cannot message blocked peer ${peer.nickname}")
        }

        val local = identityManager.identityState.value
        val now = System.currentTimeMillis()
        val packetId = "dm_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val expiresAt = now + 86_400_000L * 3 // 72h store-and-forward expiration

        val wireBody = WireChatBody(
            text = text,
            contentType = contentType,
            attachmentJson = attachmentJson
        )
        val wireBytes = json.encodeToString(wireBody).encodeToByteArray()

        val draftEnvelope = MeshEnvelope(
            packetId = packetId,
            packetType = PacketType.DIRECT_ENCRYPTED_MSG,
            senderPeerId = local.peerId,
            senderNickname = local.nickname,
            senderSigningPubKeyHex = local.signingPublicKeyHex,
            senderEncryptionPubKeyHex = local.encryptionPublicKeyHex,
            recipientPeerId = recipientPeerId,
            createdAtEpochMs = now,
            expiresAtEpochMs = expiresAt,
            ttl = _configuredHopLimit.value,
            hopCount = 0,
            payloadBase64 = "",
            signatureBase64 = ""
        )

        val encryptedResult = sessionManager.encryptDirectMessage(
            recipientPeerId = recipientPeerId,
            recipientEncryptionPubKeyHex = peer.encryptionPublicKeyHex,
            plaintextBytes = wireBytes,
            associatedData = draftEnvelope.associatedDataBytes()
        )

        val signed = identityManager.signEnvelope(
            draftEnvelope.copy(
                payloadBase64 = encryptedResult.ciphertextBase64,
                encryptionMetadata = encryptedResult.encryptionMetadata
            )
        )

        synchronized(seenCache) {
            seenCache[packetId] = now
        }

        val isPeerOnline = peer.presenceState != com.nohead.mesh.domain.model.PeerPresenceState.OFFLINE
        val initialStatus = if (isPeerOnline && hasAnyConnectedTransport()) {
            DeliveryStatus.SENT
        } else {
            DeliveryStatus.QUEUED
        }

        val entity = MessageEntity(
            messageId = packetId,
            conversationId = recipientPeerId,
            isDirectMessage = true,
            senderPeerId = local.peerId,
            senderNickname = local.nickname,
            senderVerified = true,
            recipientPeerId = recipientPeerId,
            content = text,
            contentType = contentType,
            timestampEpochMs = now,
            expiresAtEpochMs = expiresAt,
            deliveryStatus = initialStatus,
            transportOrigin = determinePrimaryActiveTransport(),
            hopCount = 0,
            isOutgoing = true,
            isEncrypted = true,
            signatureVerified = true,
            attachmentJson = attachmentJson
        )
        messageDao.upsertMessage(entity)

        // Always persist in encrypted store-and-forward queue until DELIVERED ACK arrives
        storeForwardManager.enqueueOutgoing(signed, priority = 2, allowCourierReplication = true)
        dispatchToActiveTransports(signed, includeInternetNostr = nostrTransport.isRunning.value, targetPeerId = recipientPeerId)

        entity.toDomain()
    }

    /**
     * Sends a chunked attachment or voice note over the active mesh transports.
     */
    suspend fun sendAttachmentOrVoiceNote(
        conversationId: String,
        isDirectMessage: Boolean,
        rawBytes: ByteArray,
        fileName: String,
        mimeType: String,
        isVoiceNote: Boolean,
        durationSeconds: Int? = null
    ): Result<ChatMessage> = runCatching {
        val isBleOnly = !wifiAwareTransport.getCapabilities().isEnabled
        attachmentAndVoiceManager.isSafeMimeAndSize(fileName, mimeType, rawBytes.size.toLong(), isBleOnly).getOrThrow()

        val (metadata, chunks) = attachmentAndVoiceManager.createAttachmentChunks(
            rawBytes = rawBytes,
            rawFileName = fileName,
            mimeType = mimeType,
            durationSeconds = durationSeconds
        )

        val local = identityManager.identityState.value
        val now = System.currentTimeMillis()
        val packetType = if (isVoiceNote) PacketType.VOICE_NOTE else PacketType.ATTACHMENT_CHUNK

        for (chunk in chunks) {
            val chunkBytes = json.encodeToString(chunk).encodeToByteArray()
            val unsigned = MeshEnvelope(
                packetId = "${metadata.attachmentId}_c${chunk.chunkIndex}",
                packetType = packetType,
                senderPeerId = local.peerId,
                senderNickname = local.nickname,
                senderSigningPubKeyHex = local.signingPublicKeyHex,
                senderEncryptionPubKeyHex = local.encryptionPublicKeyHex,
                recipientPeerId = if (isDirectMessage) conversationId else null,
                channelId = if (!isDirectMessage) conversationId else null,
                createdAtEpochMs = now,
                expiresAtEpochMs = now + 600_000L,
                ttl = _configuredHopLimit.value,
                hopCount = 0,
                payloadBase64 = CryptoEngine.encodeBase64(chunkBytes),
                signatureBase64 = ""
            )
            val signed = identityManager.signEnvelope(unsigned)
            dispatchToActiveTransports(signed, includeInternetNostr = false)
        }

        val label = if (isVoiceNote) {
            "Voice Note (${durationSeconds ?: 1}s)"
        } else {
            "Attachment: ${metadata.fileName} (${metadata.sizeBytes} B)"
        }
        val contentType = when {
            isVoiceNote -> MessageContentType.VOICE_NOTE
            mimeType.startsWith("image/") -> MessageContentType.IMAGE_ATTACHMENT
            else -> MessageContentType.FILE_ATTACHMENT
        }
        val metaJson = json.encodeToString(metadata)

        val entity = MessageEntity(
            messageId = metadata.attachmentId,
            conversationId = conversationId,
            isDirectMessage = isDirectMessage,
            senderPeerId = local.peerId,
            senderNickname = local.nickname,
            senderVerified = true,
            recipientPeerId = if (isDirectMessage) conversationId else null,
            content = label,
            contentType = contentType,
            timestampEpochMs = now,
            expiresAtEpochMs = now + 86_400_000L,
            deliveryStatus = DeliveryStatus.SENT,
            transportOrigin = determinePrimaryActiveTransport(),
            hopCount = 0,
            isOutgoing = true,
            isEncrypted = isDirectMessage,
            signatureVerified = true,
            attachmentJson = metaJson
        )
        messageDao.upsertMessage(entity)
        entity.toDomain()
    }

    suspend fun insertLocalSystemNotice(conversationId: String, noticeText: String) {
        val now = System.currentTimeMillis()
        val entity = MessageEntity(
            messageId = "sys_${UUID.randomUUID().toString().take(12)}",
            conversationId = conversationId,
            isDirectMessage = !conversationId.startsWith("#"),
            senderPeerId = "system",
            senderNickname = "SYSTEM",
            senderVerified = true,
            recipientPeerId = null,
            content = noticeText,
            contentType = MessageContentType.SYSTEM_NOTICE,
            timestampEpochMs = now,
            expiresAtEpochMs = now + 86_400_000L * 7,
            deliveryStatus = DeliveryStatus.DELIVERED,
            transportOrigin = determinePrimaryActiveTransport(),
            hopCount = 0,
            isOutgoing = false,
            isEncrypted = false,
            signatureVerified = true
        )
        messageDao.upsertMessage(entity)
    }

    private suspend fun dispatchToActiveTransports(
        envelope: MeshEnvelope,
        includeInternetNostr: Boolean,
        targetPeerId: String? = null
    ): Boolean {
        var anySucceeded = false
        if (wifiAwareTransport.getCapabilities().isEnabled) {
            if (wifiAwareTransport.sendPacket(envelope, targetPeerId).isSuccess) {
                anySucceeded = true
            }
        }
        if (bleTransport.isRunning.value) {
            if (bleTransport.sendPacket(envelope, targetPeerId).isSuccess) {
                anySucceeded = true
            }
        }
        if (_simulatedMeshEnabled.value && simulatedTransport.isRunning.value) {
            if (simulatedTransport.sendPacket(envelope, targetPeerId).isSuccess) {
                anySucceeded = true
            }
        }
        if (includeInternetNostr && nostrTransport.isRunning.value) {
            if (nostrTransport.sendPacket(envelope, targetPeerId).isSuccess) {
                anySucceeded = true
            }
        }
        if (anySucceeded) {
            packetsSentTotal.incrementAndGet()
        }
        return anySucceeded
    }

    private suspend fun flushQueuedEnvelopesForPeer(peerId: String) {
        val envelopes = storeForwardManager.drainEnvelopesForDiscoveredPeer(peerId)
        for (env in envelopes) {
            dispatchToActiveTransports(env, includeInternetNostr = false, targetPeerId = peerId)
        }
    }

    private suspend fun processStoreAndForwardQueue() {
        if (!hasAnyConnectedTransport()) return
        val due = storeForwardManager.getDueOutboxEnvelopes()
        for ((item, env) in due) {
            val sent = dispatchToActiveTransports(
                envelope = env,
                includeInternetNostr = nostrTransport.isRunning.value,
                targetPeerId = item.recipientPeerId
            )
            storeForwardManager.markRetryAttempt(item, consumedCourierReplica = sent)
            if (sent) {
                messageDao.updateDeliveryStatus(env.packetId, DeliveryStatus.SENT)
            }
        }
    }

    private fun allowPeerRateLimit(peerId: String, nowMs: Long): Boolean {
        val bucket = peerRateBuckets.computeIfAbsent(peerId) {
            PeerRateBucket(windowStartMs = nowMs, count = 0)
        }
        synchronized(bucket) {
            if (nowMs - bucket.windowStartMs > RATE_LIMIT_WINDOW_MS) {
                bucket.windowStartMs = nowMs
                bucket.count = 1
                return true
            }
            bucket.count++
            return bucket.count <= MAX_PACKETS_PER_PEER_WINDOW
        }
    }

    private fun hasAnyConnectedTransport(): Boolean {
        return bleTransport.connectedPeersCount.value > 0 ||
            wifiAwareTransport.connectedPeersCount.value > 0 ||
            nostrTransport.connectedPeersCount.value > 0 ||
            (_simulatedMeshEnabled.value && simulatedTransport.connectedPeersCount.value > 0)
    }

    private fun determinePrimaryActiveTransport(): TransportType {
        return when {
            wifiAwareTransport.getCapabilities().isEnabled && wifiAwareTransport.connectedPeersCount.value > 0 -> TransportType.WIFI_AWARE
            bleTransport.adapterEnabled.value && bleTransport.isRunning.value -> TransportType.BLE_MESH
            nostrTransport.isRunning.value && nostrTransport.connectedPeersCount.value > 0 -> TransportType.NOSTR_RELAY
            _simulatedMeshEnabled.value && simulatedTransport.isRunning.value -> TransportType.LOOPBACK_SIMULATED
            else -> TransportType.OFFLINE
        }
    }

    suspend fun refreshDiagnostics() {
        val (queuedCount, courierCount) = storeForwardManager.getCounts()
        _diagnosticsFlow.value = buildDiagnosticsSnapshot(queuedCount, courierCount)
    }

    private fun buildDiagnosticsSnapshot(queuedCount: Int, courierCount: Int): NetworkDiagnosticsSnapshot {
        val activeTransports = buildSet {
            if (bleTransport.isRunning.value && bleTransport.adapterEnabled.value) add(TransportType.BLE_MESH)
            if (wifiAwareTransport.getCapabilities().isEnabled) add(TransportType.WIFI_AWARE)
            if (nostrTransport.isRunning.value && nostrTransport.connectedPeersCount.value > 0) add(TransportType.NOSTR_RELAY)
            if (_simulatedMeshEnabled.value && simulatedTransport.isRunning.value) add(TransportType.LOOPBACK_SIMULATED)
            if (isEmpty()) add(TransportType.OFFLINE)
        }
        val discovered = bleTransport.discoveredPeersCount.value +
            wifiAwareTransport.discoveredPeersCount.value +
            (if (_simulatedMeshEnabled.value) simulatedTransport.discoveredPeersCount.value else 0)
        val connected = bleTransport.connectedPeersCount.value +
            wifiAwareTransport.connectedPeersCount.value +
            nostrTransport.connectedPeersCount.value +
            (if (_simulatedMeshEnabled.value) simulatedTransport.connectedPeersCount.value else 0)

        return NetworkDiagnosticsSnapshot(
            activeTransports = activeTransports,
            bleAdapterEnabled = bleTransport.adapterEnabled.value,
            bleAdvertisingActive = bleTransport.isAdvertising.value,
            bleScanningActive = bleTransport.isScanning.value,
            wifiAwareSupported = wifiAwareTransport.isSupported.value,
            wifiAwareAvailable = wifiAwareTransport.isAvailable.value,
            nostrConnectedRelays = nostrTransport.connectedRelayUrls.value.size,
            nostrConfiguredRelays = NostrTransport.DEFAULT_RELAYS.size,
            discoveredPeersCount = discovered,
            connectedPeersCount = connected,
            packetsSentTotal = packetsSentTotal.get(),
            packetsReceivedTotal = packetsReceivedTotal.get(),
            packetsRelayedTotal = packetsRelayedTotal.get(),
            packetsDeduplicatedDropped = packetsDeduplicatedDropped.get(),
            packetsRateLimitedDropped = packetsRateLimitedDropped.get(),
            packetsInvalidSignatureDropped = packetsInvalidSignatureDropped.get(),
            storeForwardQueuedCount = queuedCount,
            courierMailboxesStoredCount = courierCount,
            batterySaverModeEnabled = bleTransport.batterySaverMode.value,
            currentScanIntervalMs = bleTransport.currentScanIntervalMs.value,
            protocolVersion = ProtocolConstants.PROTOCOL_VERSION
        )
    }

    fun clearInMemoryRouterStateForWipe() {
        seenCache.clear()
        peerRateBuckets.clear()
        packetsSentTotal.set(0)
        packetsReceivedTotal.set(0)
        packetsRelayedTotal.set(0)
        packetsDeduplicatedDropped.set(0)
        packetsRateLimitedDropped.set(0)
        packetsInvalidSignatureDropped.set(0)
    }

    private fun MessageEntity.toDomain(): ChatMessage {
        val attachment = attachmentJson?.let {
            runCatching { json.decodeFromString<com.nohead.mesh.domain.model.AttachmentMetadata>(it) }.getOrNull()
        }
        return ChatMessage(
            messageId = messageId,
            conversationId = conversationId,
            isDirectMessage = isDirectMessage,
            senderPeerId = senderPeerId,
            senderNickname = senderNickname,
            senderVerified = senderVerified,
            recipientPeerId = recipientPeerId,
            content = content,
            contentType = contentType,
            timestampEpochMs = timestampEpochMs,
            expiresAtEpochMs = expiresAtEpochMs,
            deliveryStatus = deliveryStatus,
            transportOrigin = transportOrigin,
            hopCount = hopCount,
            isOutgoing = isOutgoing,
            isEncrypted = isEncrypted,
            signatureVerified = signatureVerified,
            attachment = attachment,
            replyToMessageId = replyToMessageId
        )
    }
}

