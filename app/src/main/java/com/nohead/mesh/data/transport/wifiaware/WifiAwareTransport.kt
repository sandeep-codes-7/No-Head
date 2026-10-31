package com.nohead.mesh.data.transport.wifiaware

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import com.nohead.mesh.domain.crypto.CryptoEngine
import com.nohead.mesh.domain.model.MeshEnvelope
import com.nohead.mesh.domain.model.PacketType
import com.nohead.mesh.domain.model.PeerAnnouncementPayload
import com.nohead.mesh.domain.model.ProtocolConstants
import com.nohead.mesh.domain.model.TransportCapabilities
import com.nohead.mesh.domain.model.TransportType
import com.nohead.mesh.domain.service.IdentityManager
import com.nohead.mesh.domain.transport.InboundTransportPacket
import com.nohead.mesh.domain.transport.Transport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional Wi-Fi Aware (NAN) Transport for high-bandwidth nearby mesh communication.
 *
 * Detects hardware and OS support at runtime (`FEATURE_WIFI_AWARE`).
 * Shares the exact same [MeshEnvelope] schema and security policy as [BleMeshTransport].
 * Gracefully falls back to BLE when hardware is unsupported or disabled.
 */
@Singleton
@SuppressLint("MissingPermission")
class WifiAwareTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identityManager: IdentityManager
) : Transport {

    companion object {
        private const val SERVICE_NAME = "nohead_mesh_v1"
        private const val MESSAGE_ID_ENVELOPE = 101
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override val transportType: TransportType = TransportType.WIFI_AWARE

    private val _isSupported = MutableStateFlow(
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
    )
    val isSupported: StateFlow<Boolean> = _isSupported.asStateFlow()

    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _discoveredPeersCount = MutableStateFlow(0)
    override val discoveredPeersCount: StateFlow<Int> = _discoveredPeersCount.asStateFlow()

    private val _connectedPeersCount = MutableStateFlow(0)
    override val connectedPeersCount: StateFlow<Int> = _connectedPeersCount.asStateFlow()

    private val inboundFlow = MutableSharedFlow<InboundTransportPacket>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var wifiAwareManager: WifiAwareManager? = null
    private var awareSession: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private val discoveredPeerHandles = ConcurrentHashMap<Int, PeerHandle>()
    private var receiverRegistered = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED) {
                val available = wifiAwareManager?.isAvailable == true
                _isAvailable.value = available
                if (available && _isRunning.value) {
                    attachSession()
                } else if (!available) {
                    closeSessionInternal()
                }
            }
        }
    }

    override suspend fun start() {
        if (!_isSupported.value) return
        if (_isRunning.value) return

        wifiAwareManager = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
        _isAvailable.value = wifiAwareManager?.isAvailable == true
        _isRunning.value = true

        if (!receiverRegistered) {
            try {
                context.registerReceiver(
                    stateReceiver,
                    IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED)
                )
                receiverRegistered = true
            } catch (_: Exception) {
            }
        }

        if (_isAvailable.value) {
            attachSession()
        }
    }

    override suspend fun stop() {
        _isRunning.value = false
        closeSessionInternal()
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(stateReceiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }
    }

    override suspend fun discoverPeers() {
        if (!_isSupported.value) return
        if (!_isRunning.value) start()
        if (awareSession == null && _isAvailable.value) {
            attachSession()
        }
    }

    private fun attachSession() {
        val manager = wifiAwareManager ?: return
        if (!manager.isAvailable) return
        try {
            manager.attach(object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession) {
                    awareSession = session
                    startPublishAndSubscribe(session)
                }

                override fun onAttachFailed() {
                    awareSession = null
                }
            }, null)
        } catch (_: Exception) {
        }
    }

    private fun startPublishAndSubscribe(session: WifiAwareSession) {
        val local = identityManager.identityState.value
        val ssiBytes = "${local.peerId}|${local.rotatingPresenceId}".toByteArray(StandardCharsets.UTF_8)

        val publishConfig = PublishConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setServiceSpecificInfo(ssiBytes)
            .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
            .build()

        val subscribeConfig = SubscribeConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
            .build()

        try {
            session.publish(publishConfig, object : DiscoverySessionCallback() {
                override fun onPublishStarted(session: PublishDiscoverySession) {
                    publishSession = session
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    discoveredPeerHandles[peerHandle.hashCode()] = peerHandle
                    _discoveredPeersCount.value = discoveredPeerHandles.size
                    _connectedPeersCount.value = discoveredPeerHandles.size
                    handleIncomingMessage(message)
                }
            }, null)

            session.subscribe(subscribeConfig, object : DiscoverySessionCallback() {
                override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                    subscribeSession = session
                }

                override fun onServiceDiscovered(
                    peerHandle: PeerHandle,
                    serviceSpecificInfo: ByteArray?,
                    matchFilter: MutableList<ByteArray>?
                ) {
                    discoveredPeerHandles[peerHandle.hashCode()] = peerHandle
                    _discoveredPeersCount.value = discoveredPeerHandles.size
                    _connectedPeersCount.value = discoveredPeerHandles.size
                    sendAnnouncementToPeerHandle(peerHandle)
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    val isNewPeer = discoveredPeerHandles.put(peerHandle.hashCode(), peerHandle) == null
                    _discoveredPeersCount.value = discoveredPeerHandles.size
                    _connectedPeersCount.value = discoveredPeerHandles.size
                    handleIncomingMessage(message)
                    if (isNewPeer) {
                        sendAnnouncementToPeerHandle(peerHandle)
                    }
                }
            }, null)
        } catch (_: Exception) {
        }
    }

    private fun sendAnnouncementToPeerHandle(peerHandle: PeerHandle) {
        runCatching {
            val local = identityManager.identityState.value
            val now = System.currentTimeMillis()
            val announcePayload = PeerAnnouncementPayload(
                peerId = local.peerId,
                nickname = local.nickname,
                signingPublicKeyHex = local.signingPublicKeyHex,
                encryptionPublicKeyHex = local.encryptionPublicKeyHex,
                rotatingPresenceToken = local.rotatingPresenceId,
                protocolVersion = ProtocolConstants.PROTOCOL_VERSION,
                supportedTransports = setOf(TransportType.WIFI_AWARE, TransportType.BLE_MESH),
                timestampEpochMs = now
            )
            val payloadBytes = json.encodeToString(announcePayload).encodeToByteArray()
            val unsigned = MeshEnvelope(
                packetId = "nan_ann_${local.peerId}_$now",
                packetType = PacketType.PEER_ANNOUNCE,
                senderPeerId = local.peerId,
                senderNickname = local.nickname,
                senderSigningPubKeyHex = local.signingPublicKeyHex,
                senderEncryptionPubKeyHex = local.encryptionPublicKeyHex,
                createdAtEpochMs = now,
                expiresAtEpochMs = now + 180_000L,
                ttl = 2,
                hopCount = 0,
                payloadBase64 = CryptoEngine.encodeBase64(payloadBytes),
                signatureBase64 = "",
                viaTransports = setOf(TransportType.WIFI_AWARE)
            )
            val signed = identityManager.signEnvelope(unsigned)
            val bytes = json.encodeToString(signed).toByteArray(StandardCharsets.UTF_8)
            subscribeSession?.sendMessage(peerHandle, MESSAGE_ID_ENVELOPE, bytes)
                ?: publishSession?.sendMessage(peerHandle, MESSAGE_ID_ENVELOPE, bytes)
        }
    }

    private fun handleIncomingMessage(bytes: ByteArray) {
        val envelope = runCatching {
            json.decodeFromString<MeshEnvelope>(bytes.decodeToString())
        }.getOrNull() ?: return

        inboundFlow.tryEmit(
            InboundTransportPacket(
                envelope = envelope.copy(viaTransports = envelope.viaTransports + TransportType.WIFI_AWARE),
                sourceTransport = TransportType.WIFI_AWARE
            )
        )
    }

    override suspend fun sendPacket(envelope: MeshEnvelope, targetPeerId: String?): Result<Unit> = runCatching {
        if (!_isSupported.value || !_isAvailable.value) {
            throw IllegalStateException("Wi-Fi Aware unavailable")
        }
        val bytes = json.encodeToString(envelope).toByteArray(StandardCharsets.UTF_8)
        val pub = publishSession
        val sub = subscribeSession
        for (peerHandle in discoveredPeerHandles.values) {
            try {
                sub?.sendMessage(peerHandle, MESSAGE_ID_ENVELOPE, bytes)
                    ?: pub?.sendMessage(peerHandle, MESSAGE_ID_ENVELOPE, bytes)
            } catch (_: Exception) {
            }
        }
    }

    override fun receivePackets(): Flow<InboundTransportPacket> = inboundFlow.asSharedFlow()

    override fun getCapabilities(): TransportCapabilities {
        return TransportCapabilities(
            transportType = TransportType.WIFI_AWARE,
            isSupportedOnDevice = _isSupported.value,
            isEnabled = _isSupported.value && _isAvailable.value && _isRunning.value,
            maxPayloadBytes = ProtocolConstants.MAX_WIFI_AWARE_MEDIA_BYTES,
            supportsMultiHop = true,
            supportsStreamingAudio = true,
            requiresInternet = false
        )
    }

    private fun closeSessionInternal() {
        try {
            publishSession?.close()
            subscribeSession?.close()
            awareSession?.close()
        } catch (_: Exception) {
        }
        publishSession = null
        subscribeSession = null
        awareSession = null
        discoveredPeerHandles.clear()
        _discoveredPeersCount.value = 0
        _connectedPeersCount.value = 0
    }
}

