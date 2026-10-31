package com.nohead.mesh.data.transport.nostr

import com.nohead.mesh.data.local.NostrRelayDao
import com.nohead.mesh.data.local.NostrRelayEntity
import com.nohead.mesh.domain.crypto.CryptoEngine
import com.nohead.mesh.domain.model.MeshEnvelope
import com.nohead.mesh.domain.model.ProtocolConstants
import com.nohead.mesh.domain.model.TransportCapabilities
import com.nohead.mesh.domain.model.TransportType
import com.nohead.mesh.domain.service.IdentityManager
import com.nohead.mesh.domain.transport.InboundTransportPacket
import com.nohead.mesh.domain.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional Internet Transport over Nostr WebSocket Relays (TLS).
 *
 * Features:
 * - Multiple configurable relay endpoints (`wss://...`)
 * - Optional SOCKS5 Tor proxy (`127.0.0.1:9050` Orbot compatibility)
 * - Wraps signed/encrypted [MeshEnvelope] inside Nostr Ephemeral/Application-specific Events (Kind 24242 / 42)
 *   with `#d` (channel), `#g` (geohash), and `#p` (recipient peerId) tags
 * - Event deduplication and origin Ed25519 signature verification
 * - Never publishes unencrypted private messages to public relays
 */
@Singleton
class NostrTransport @Inject constructor(
    private val nostrRelayDao: NostrRelayDao,
    private val identityManager: IdentityManager,
    private val cryptoEngine: CryptoEngine
) : Transport {

    companion object {
        const val NOSTR_KIND_NOHEAD_ENVELOPE = 24242
        val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.primal.net"
        )
    }

    @Serializable
    data class NostrEventWire(
        val id: String,
        val pubkey: String,
        val created_at: Long,
        val kind: Int,
        val tags: List<List<String>>,
        val content: String,
        val sig: String
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override val transportType: TransportType = TransportType.NOSTR_RELAY

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _torProxyEnabled = MutableStateFlow(false)
    val torProxyEnabled: StateFlow<Boolean> = _torProxyEnabled.asStateFlow()

    private val _torSocksPort = MutableStateFlow(9050)
    val torSocksPort: StateFlow<Int> = _torSocksPort.asStateFlow()

    private val _discoveredPeersCount = MutableStateFlow(0)
    override val discoveredPeersCount: StateFlow<Int> = _discoveredPeersCount.asStateFlow()

    private val _connectedPeersCount = MutableStateFlow(0)
    override val connectedPeersCount: StateFlow<Int> = _connectedPeersCount.asStateFlow()

    private val _connectedRelayUrls = MutableStateFlow<Set<String>>(emptySet())
    val connectedRelayUrls: StateFlow<Set<String>> = _connectedRelayUrls.asStateFlow()

    private val connectingSockets = ConcurrentHashMap<String, WebSocket>()
    private val activeSockets = ConcurrentHashMap<String, WebSocket>()
    private val seenNostrEventIds = ConcurrentHashMap<String, Long>()

    private val inboundFlow = MutableSharedFlow<InboundTransportPacket>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private fun buildHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)

        if (_torProxyEnabled.value) {
            builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", _torSocksPort.value)))
        }
        return builder.build()
    }

    suspend fun initializeDefaultRelaysIfEmpty() {
        val existing = nostrRelayDao.getRelaysSnapshot()
        if (existing.isEmpty()) {
            DEFAULT_RELAYS.forEach { url ->
                nostrRelayDao.upsertRelay(
                    NostrRelayEntity(
                        url = url,
                        isEnabled = false, // Disabled by default so local-only privacy is preserved until opted in
                        isReadEnabled = true,
                        isWriteEnabled = true,
                        lastConnectedEpochMs = null
                    )
                )
            }
        }
    }

    fun setTorProxy(enabled: Boolean, socksPort: Int = 9050) {
        _torProxyEnabled.value = enabled
        _torSocksPort.value = socksPort
        if (_isRunning.value) {
            scope.launch {
                stop()
                start()
            }
        }
    }

    override suspend fun start() {
        initializeDefaultRelaysIfEmpty()
        val enabledRelays = nostrRelayDao.getRelaysSnapshot().filter { it.isEnabled }
        if (enabledRelays.isEmpty()) {
            _isRunning.value = false
            return
        }
        _isRunning.value = true
        val client = buildHttpClient()
        for (relay in enabledRelays) {
            connectRelay(client, relay.url)
        }
    }

    override suspend fun stop() {
        _isRunning.value = false
        (connectingSockets.values + activeSockets.values).forEach { ws ->
            try {
                ws.close(1000, "No head stopping Nostr transport")
            } catch (_: Exception) {
            }
        }
        connectingSockets.clear()
        activeSockets.clear()
        _connectedRelayUrls.value = emptySet()
        _connectedPeersCount.value = 0
    }

    override suspend fun discoverPeers() {
        if (_isRunning.value) {
            sendSubscriptionFiltersToAll()
        }
    }

    private fun connectRelay(client: OkHttpClient, url: String) {
        if (activeSockets.containsKey(url) || connectingSockets.containsKey(url)) return
        val request = try {
            Request.Builder().url(url).build()
        } catch (_: Exception) {
            return
        }

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connectingSockets.remove(url)
                activeSockets[url] = webSocket
                _connectedRelayUrls.value = activeSockets.keys.toSet()
                _connectedPeersCount.value = activeSockets.size
                sendSubscriptionFilter(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingNostrFrame(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connectingSockets.remove(url)
                activeSockets.remove(url)
                _connectedRelayUrls.value = activeSockets.keys.toSet()
                _connectedPeersCount.value = activeSockets.size
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connectingSockets.remove(url)
                activeSockets.remove(url)
                _connectedRelayUrls.value = activeSockets.keys.toSet()
                _connectedPeersCount.value = activeSockets.size
            }
        })
        connectingSockets[url] = ws
    }

    private fun sendSubscriptionFiltersToAll() {
        activeSockets.values.forEach { sendSubscriptionFilter(it) }
    }

    private fun sendSubscriptionFilter(ws: WebSocket) {
        val reqFrame = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("nohead_sub_v1"))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(NOSTR_KIND_NOHEAD_ENVELOPE)) })
                put("limit", 100)
            })
        }.toString()
        try {
            ws.send(reqFrame)
        } catch (_: Exception) {
        }
    }

    private fun handleIncomingNostrFrame(rawFrame: String) {
        runCatching {
            val array = json.parseToJsonElement(rawFrame).jsonArray
            if (array.size >= 3 && array[0].jsonPrimitive.content == "EVENT") {
                val eventObj = array[2].jsonObject
                val eventId = eventObj["id"]?.jsonPrimitive?.content ?: return
                val content = eventObj["content"]?.jsonPrimitive?.content ?: return

                if (seenNostrEventIds.putIfAbsent(eventId, System.currentTimeMillis()) != null) {
                    return // Deduplicate across multiple relays
                }

                val envelope = json.decodeFromString<MeshEnvelope>(content)
                if (!identityManager.verifyEnvelopeSignature(envelope)) {
                    return // Reject forged envelope
                }

                inboundFlow.tryEmit(
                    InboundTransportPacket(
                        envelope = envelope.copy(viaTransports = envelope.viaTransports + TransportType.NOSTR_RELAY),
                        sourceTransport = TransportType.NOSTR_RELAY
                    )
                )
            }
        }
    }

    override suspend fun sendPacket(envelope: MeshEnvelope, targetPeerId: String?): Result<Unit> = runCatching {
        if (!_isRunning.value || activeSockets.isEmpty()) {
            throw IllegalStateException("No active Nostr relays")
        }
        val local = identityManager.identityState.value
        val createdAtSec = envelope.createdAtEpochMs / 1000L
        val envelopeJson = json.encodeToString(envelope)
        val tags = mutableListOf<List<String>>()
        envelope.channelId?.let { tags.add(listOf("d", it)) }
        envelope.geohash?.let { tags.add(listOf("g", it)) }
        envelope.recipientPeerId?.let { tags.add(listOf("p", it)) }

        val canonicalCommitment = buildJsonArray {
            add(JsonPrimitive(0))
            add(JsonPrimitive(local.signingPublicKeyHex))
            add(JsonPrimitive(createdAtSec))
            add(JsonPrimitive(NOSTR_KIND_NOHEAD_ENVELOPE))
            add(JsonArray(tags.map { tagList -> JsonArray(tagList.map { JsonPrimitive(it) }) }))
            add(JsonPrimitive(envelopeJson))
        }.toString()

        val eventIdHex = cryptoEngine.sha256Hex(canonicalCommitment.encodeToByteArray())
        val sigHex = CryptoEngine.run {
            identityManager.signBytes(eventIdHex.encodeToByteArray()).toHex()
        }

        val eventWire = NostrEventWire(
            id = eventIdHex,
            pubkey = local.signingPublicKeyHex,
            created_at = createdAtSec,
            kind = NOSTR_KIND_NOHEAD_ENVELOPE,
            tags = tags,
            content = envelopeJson,
            sig = sigHex
        )

        val eventFrame = buildJsonArray {
            add(JsonPrimitive("EVENT"))
            add(json.encodeToJsonElement(NostrEventWire.serializer(), eventWire))
        }.toString()

        for (ws in activeSockets.values) {
            try {
                ws.send(eventFrame)
            } catch (_: Exception) {
            }
        }
    }

    override fun receivePackets(): Flow<InboundTransportPacket> = inboundFlow.asSharedFlow()

    override fun getCapabilities(): TransportCapabilities {
        return TransportCapabilities(
            transportType = TransportType.NOSTR_RELAY,
            isSupportedOnDevice = true,
            isEnabled = _isRunning.value && activeSockets.isNotEmpty(),
            maxPayloadBytes = ProtocolConstants.MAX_TEXT_MESSAGE_BYTES * 4,
            supportsMultiHop = false,
            supportsStreamingAudio = false,
            requiresInternet = true
        )
    }
}

