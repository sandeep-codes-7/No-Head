package com.nohead.mesh.data.transport.simulated

import com.nohead.mesh.domain.crypto.CryptoEngine
import com.nohead.mesh.domain.crypto.CryptoEngine.Companion.toHex
import com.nohead.mesh.domain.model.AckPayload
import com.nohead.mesh.domain.model.AckType
import com.nohead.mesh.domain.model.MeshEnvelope
import com.nohead.mesh.domain.model.PacketType
import com.nohead.mesh.domain.model.PeerAnnouncementPayload
import com.nohead.mesh.domain.model.ProtocolConstants
import com.nohead.mesh.domain.model.TransportCapabilities
import com.nohead.mesh.domain.model.TransportType
import com.nohead.mesh.domain.transport.InboundTransportPacket
import com.nohead.mesh.domain.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Development & Automated Integration Test Simulated Mesh Transport.
 *
 * Strictly labeled as [TransportType.LOOPBACK_SIMULATED] so it is never confused with
 * physical BLE delivery. Supports multi-hop virtual nodes (`relay_alpha`, `node_bravo`, `courier_charlie`)
 * with genuine Ed25519/X25519 cryptographic identities so multi-hop routing, deduplication,
 * and E2EE handshakes can be exercised on single devices or CI tests.
 */
@Singleton
class SimulatedLoopbackTransport @Inject constructor(
    private val cryptoEngine: CryptoEngine
) : Transport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override val transportType: TransportType = TransportType.LOOPBACK_SIMULATED

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _discoveredPeersCount = MutableStateFlow(0)
    override val discoveredPeersCount: StateFlow<Int> = _discoveredPeersCount.asStateFlow()

    private val _connectedPeersCount = MutableStateFlow(0)
    override val connectedPeersCount: StateFlow<Int> = _connectedPeersCount.asStateFlow()

    private val inboundFlow = MutableSharedFlow<InboundTransportPacket>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    data class VirtualMeshNode(
        val nickname: String,
        val signKey: CryptoEngine.Ed25519KeyPairRaw,
        val encKey: CryptoEngine.X25519KeyPairRaw,
        val peerId: String,
        val hopDistance: Int,
        val rssi: Int
    )

    val virtualNodes: List<VirtualMeshNode> by lazy {
        listOf(
            createVirtualNode("relay_alpha", hopDistance = 1, rssi = -54),
            createVirtualNode("node_bravo", hopDistance = 2, rssi = -71),
            createVirtualNode("courier_charlie", hopDistance = 3, rssi = -83)
        )
    }

    private fun createVirtualNode(
        nickname: String,
        hopDistance: Int,
        rssi: Int
    ): VirtualMeshNode {
        val signKey = cryptoEngine.generateEd25519KeyPair()
        val encKey = cryptoEngine.generateX25519KeyPair()
        val peerId = cryptoEngine.derivePeerId(signKey.publicKeyBytes)
        return VirtualMeshNode(
            nickname = "${nickname}_${peerId.takeLast(4)}",
            signKey = signKey,
            encKey = encKey,
            peerId = peerId,
            hopDistance = hopDistance,
            rssi = rssi
        )
    }

    override suspend fun start() {
        if (_isRunning.value) return
        _isRunning.value = true
        _discoveredPeersCount.value = virtualNodes.size
        _connectedPeersCount.value = virtualNodes.count { it.hopDistance == 1 }
        discoverPeers()
    }

    override suspend fun stop() {
        _isRunning.value = false
        _discoveredPeersCount.value = 0
        _connectedPeersCount.value = 0
    }

    override suspend fun discoverPeers() {
        if (!_isRunning.value) return
        val now = System.currentTimeMillis()
        for (node in virtualNodes) {
            val announce = PeerAnnouncementPayload(
                peerId = node.peerId,
                nickname = node.nickname,
                signingPublicKeyHex = node.signKey.publicKeyHex,
                encryptionPublicKeyHex = node.encKey.publicKeyHex,
                rotatingPresenceToken = cryptoEngine.generateRotatingPresenceId(),
                protocolVersion = ProtocolConstants.PROTOCOL_VERSION,
                supportedTransports = setOf(TransportType.BLE_MESH, TransportType.LOOPBACK_SIMULATED),
                timestampEpochMs = now
            )
            val payloadBase64 = CryptoEngine.encodeBase64(json.encodeToString(announce).encodeToByteArray())
            val unsigned = MeshEnvelope(
                packetId = "sim_ann_${node.peerId}_$now",
                packetType = PacketType.PEER_ANNOUNCE,
                senderPeerId = node.peerId,
                senderNickname = node.nickname,
                senderSigningPubKeyHex = node.signKey.publicKeyHex,
                senderEncryptionPubKeyHex = node.encKey.publicKeyHex,
                createdAtEpochMs = now,
                expiresAtEpochMs = now + 300_000L,
                ttl = ProtocolConstants.DEFAULT_MAX_TTL - (node.hopDistance - 1),
                hopCount = node.hopDistance - 1,
                payloadBase64 = payloadBase64,
                signatureBase64 = "",
                viaTransports = setOf(TransportType.LOOPBACK_SIMULATED)
            )
            val sig = cryptoEngine.signEd25519(node.signKey.privateKeyBytes, unsigned.canonicalSignableBytes())
            val signed = unsigned.copy(signatureBase64 = CryptoEngine.encodeBase64(sig))
            inboundFlow.tryEmit(
                InboundTransportPacket(
                    envelope = signed,
                    sourceTransport = TransportType.LOOPBACK_SIMULATED,
                    rssiDbm = node.rssi
                )
            )
        }
    }

    override suspend fun sendPacket(envelope: MeshEnvelope, targetPeerId: String?): Result<Unit> = runCatching {
        if (!_isRunning.value) throw IllegalStateException("Simulated transport stopped")

        // When a packet is sent into the simulated mesh, virtual nodes acknowledge relay/delivery
        // using real signed ACK envelopes so the routing and receipt state machine is genuinely verified.
        if (envelope.packetType == PacketType.PUBLIC_CHANNEL_MSG ||
            envelope.packetType == PacketType.PASSWORD_CHANNEL_MSG ||
            envelope.packetType == PacketType.DIRECT_ENCRYPTED_MSG
        ) {
            scope.launch {
                delay(80L)
                val responder = if (targetPeerId != null) {
                    virtualNodes.find { it.peerId == targetPeerId } ?: virtualNodes.first()
                } else {
                    virtualNodes.first()
                }
                val ackType = if (envelope.recipientPeerId == responder.peerId) {
                    AckType.DELIVERED
                } else {
                    AckType.RELAYED
                }
                val now = System.currentTimeMillis()
                val ackPayload = AckPayload(
                    targetMessageId = envelope.packetId,
                    ackType = ackType,
                    responderPeerId = responder.peerId,
                    timestampEpochMs = now,
                    hopCountObserved = responder.hopDistance
                )
                val payloadBase64 = CryptoEngine.encodeBase64(json.encodeToString(ackPayload).encodeToByteArray())
                val unsignedAck = MeshEnvelope(
                    packetId = "sim_ack_${envelope.packetId}_${responder.peerId}",
                    packetType = PacketType.DELIVERY_ACK,
                    senderPeerId = responder.peerId,
                    senderNickname = responder.nickname,
                    senderSigningPubKeyHex = responder.signKey.publicKeyHex,
                    senderEncryptionPubKeyHex = responder.encKey.publicKeyHex,
                    recipientPeerId = envelope.senderPeerId,
                    createdAtEpochMs = now,
                    expiresAtEpochMs = now + 120_000L,
                    ttl = ProtocolConstants.DEFAULT_MAX_TTL,
                    hopCount = responder.hopDistance - 1,
                    payloadBase64 = payloadBase64,
                    signatureBase64 = "",
                    viaTransports = setOf(TransportType.LOOPBACK_SIMULATED)
                )
                val sig = cryptoEngine.signEd25519(responder.signKey.privateKeyBytes, unsignedAck.canonicalSignableBytes())
                inboundFlow.emit(
                    InboundTransportPacket(
                        envelope = unsignedAck.copy(signatureBase64 = CryptoEngine.encodeBase64(sig)),
                        sourceTransport = TransportType.LOOPBACK_SIMULATED,
                        rssiDbm = responder.rssi
                    )
                )
            }
        }
    }

    /**
     * Injects an arbitrary packet into the inbound stream (used for multi-hop integration testing).
     */
    suspend fun injectInboundPacket(packet: InboundTransportPacket) {
        inboundFlow.emit(packet)
    }

    override fun receivePackets(): Flow<InboundTransportPacket> = inboundFlow.asSharedFlow()

    override fun getCapabilities(): TransportCapabilities {
        return TransportCapabilities(
            transportType = TransportType.LOOPBACK_SIMULATED,
            isSupportedOnDevice = true,
            isEnabled = _isRunning.value,
            maxPayloadBytes = ProtocolConstants.MAX_WIFI_AWARE_MEDIA_BYTES,
            supportsMultiHop = true,
            supportsStreamingAudio = true,
            requiresInternet = false
        )
    }
}

