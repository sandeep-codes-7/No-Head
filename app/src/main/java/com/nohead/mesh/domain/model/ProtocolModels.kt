package com.nohead.mesh.domain.model

import kotlinx.serialization.Serializable

/**
 * Explicit versioned protocol schema for No head transport-independent mesh packets.
 *
 * All transports (BLE Mesh, Wi-Fi Aware, Nostr Relay, Simulated Loopback) exchange
 * [MeshEnvelope] packets or fragments thereof.
 */
object ProtocolConstants {
    const val PROTOCOL_VERSION: Int = 1
    const val DEFAULT_MAX_TTL: Int = 5
    const val MAX_ALLOWED_TTL: Int = 10
    const val BLE_DEFAULT_CHUNK_PAYLOAD_BYTES: Int = 160
    const val MAX_TEXT_MESSAGE_BYTES: Int = 16 * 1024 // 16 KB
    const val MAX_BLE_MEDIA_BYTES: Int = 256 * 1024 // 256 KB strict BLE cap
    const val MAX_WIFI_AWARE_MEDIA_BYTES: Int = 10 * 1024 * 1024 // 10 MB Wi-Fi Aware cap
    const val DEFAULT_CHANNEL_ID: String = "#nearby-mesh"
    const val GLOBAL_NOSTR_CHANNEL_ID: String = "#global-nostr"
}

@Serializable
enum class TransportType {
    BLE_MESH,
    WIFI_AWARE,
    NOSTR_RELAY,
    LOOPBACK_SIMULATED,
    OFFLINE
}

@Serializable
enum class PacketType {
    PEER_ANNOUNCE,
    HANDSHAKE_INIT,
    HANDSHAKE_RESPONSE,
    PUBLIC_CHANNEL_MSG,
    PASSWORD_CHANNEL_MSG,
    DIRECT_ENCRYPTED_MSG,
    DELIVERY_ACK,
    READ_RECEIPT,
    STORE_FORWARD_COURIER,
    ATTACHMENT_MANIFEST,
    ATTACHMENT_CHUNK,
    VOICE_NOTE,
    LIVE_PTT_FRAME,
    CHANNEL_CONTROL
}

@Serializable
enum class AckType {
    RELAYED,
    DELIVERED,
    READ,
    REJECTED_RATE_LIMIT,
    REJECTED_AUTH
}

@Serializable
enum class DeliveryStatus {
    QUEUED,
    SENT,
    RELAYED,
    DELIVERED,
    READ,
    FAILED
}

@Serializable
enum class PeerPresenceState {
    ONLINE_NEARBY,
    ONLINE_INTERNET,
    STALE,
    OFFLINE
}

@Serializable
data class EncryptionMetadata(
    val algorithm: String = "ChaCha20-Poly1305",
    val nonceBase64: String,
    val ephemeralPublicKeyHex: String? = null,
    val keyEpoch: Int = 1,
    val argon2SaltBase64: String? = null
)

/**
 * Versioned Transport-Independent Message Envelope.
 *
 * The signature covers canonical bytes of:
 * protocolVersion | packetId | packetType | senderPeerId | senderSigningPubKeyHex |
 * recipientId | channelId | createdAtEpochMs | expiresAtEpochMs | payloadBase64 | encryptionMetadata
 *
 * Mutable routing fields ([ttl], [hopCount], [viaTransports]) are excluded from the origin signature
 * so intermediate relay nodes can decrement [ttl] and increment [hopCount] without invalidating
 * origin authentication.
 */
@Serializable
data class MeshEnvelope(
    val protocolVersion: Int = ProtocolConstants.PROTOCOL_VERSION,
    val packetId: String,
    val packetType: PacketType,
    val senderPeerId: String,
    val senderNickname: String,
    val senderSigningPubKeyHex: String,
    val senderEncryptionPubKeyHex: String,
    val recipientPeerId: String? = null,
    val channelId: String? = null,
    val geohash: String? = null,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val ttl: Int = ProtocolConstants.DEFAULT_MAX_TTL,
    val hopCount: Int = 0,
    val payloadBase64: String,
    val encryptionMetadata: EncryptionMetadata? = null,
    val signatureBase64: String,
    val replyToMessageId: String? = null,
    val viaTransports: Set<TransportType> = emptySet()
) {
    /**
     * Canonical byte representation of immutable origin fields for Ed25519 signing and verification.
     */
    fun canonicalSignableBytes(): ByteArray {
        val canonical = buildString {
            append("v=").append(protocolVersion).append('|')
            append("id=").append(packetId).append('|')
            append("type=").append(packetType.name).append('|')
            append("sender=").append(senderPeerId).append('|')
            append("signPub=").append(senderSigningPubKeyHex).append('|')
            append("encPub=").append(senderEncryptionPubKeyHex).append('|')
            append("rcpt=").append(recipientPeerId.orEmpty()).append('|')
            append("chan=").append(channelId.orEmpty()).append('|')
            append("geo=").append(geohash.orEmpty()).append('|')
            append("ts=").append(createdAtEpochMs).append('|')
            append("exp=").append(expiresAtEpochMs).append('|')
            append("payload=").append(payloadBase64).append('|')
            append("enc=").append(encryptionMetadata?.nonceBase64.orEmpty()).append(':')
            append(encryptionMetadata?.ephemeralPublicKeyHex.orEmpty()).append(':')
            append(encryptionMetadata?.keyEpoch ?: 0)
        }
        return canonical.encodeToByteArray()
    }

    /**
     * Associated Data (AAD) bound to AEAD encryption to prevent header tampering.
     */
    fun associatedDataBytes(): ByteArray {
        return "$protocolVersion|$packetId|${packetType.name}|$senderPeerId|${recipientPeerId.orEmpty()}|${channelId.orEmpty()}|$createdAtEpochMs".encodeToByteArray()
    }
}

/**
 * BLE / low-MTU transport packet fragment frame.
 */
@Serializable
data class PacketFragment(
    val protocolVersion: Int = ProtocolConstants.PROTOCOL_VERSION,
    val envelopeId: String,
    val fragmentIndex: Int,
    val totalFragments: Int,
    val chunkBase64: String,
    val crc32: Long
)

@Serializable
data class AckPayload(
    val targetMessageId: String,
    val ackType: AckType,
    val responderPeerId: String,
    val timestampEpochMs: Long,
    val hopCountObserved: Int = 0
)

@Serializable
data class PeerAnnouncementPayload(
    val peerId: String,
    val nickname: String,
    val signingPublicKeyHex: String,
    val encryptionPublicKeyHex: String,
    val rotatingPresenceToken: String,
    val protocolVersion: Int = ProtocolConstants.PROTOCOL_VERSION,
    val supportedTransports: Set<TransportType>,
    val supportsCourierForwarding: Boolean = true,
    val supportsVoiceNotes: Boolean = true,
    val supportsLivePtt: Boolean = true,
    val timestampEpochMs: Long
)

@Serializable
data class HandshakePayload(
    val peerId: String,
    val nickname: String,
    val signingPublicKeyHex: String,
    val staticEncryptionPublicKeyHex: String,
    val ephemeralEncryptionPublicKeyHex: String,
    val supportedProtocolVersion: Int = ProtocolConstants.PROTOCOL_VERSION,
    val challengeNonceHex: String,
    val signatureOverHandshakeBase64: String,
    val timestampEpochMs: Long
)

