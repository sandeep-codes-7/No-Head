package com.nohead.mesh.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class LocalIdentity(
    val peerId: String,
    val nickname: String,
    val signingPublicKeyHex: String,
    val encryptionPublicKeyHex: String,
    val fingerprintFormatted: String,
    val rotatingPresenceId: String,
    val createdAtEpochMs: Long,
    val keyGeneration: Int = 1
)

@Serializable
data class IdentityBackupBundle(
    val version: Int = 1,
    val nickname: String,
    val peerId: String,
    val signingPublicKeyHex: String,
    val encryptionPublicKeyHex: String,
    val argon2SaltBase64: String,
    val nonceBase64: String,
    val encryptedPrivateKeysBase64: String,
    val exportedAtEpochMs: Long
)

@Serializable
data class PeerNode(
    val peerId: String,
    val nickname: String,
    val signingPublicKeyHex: String,
    val encryptionPublicKeyHex: String,
    val fingerprint: String,
    val presenceState: PeerPresenceState,
    val activeTransports: Set<TransportType>,
    val rssiDbm: Int? = null,
    val hopDistance: Int = 1,
    val isVerified: Boolean = false,
    val isFavorite: Boolean = false,
    val isBlocked: Boolean = false,
    val isTrustedCourier: Boolean = false,
    val lastSeenEpochMs: Long,
    val rotatingPresenceToken: String = "",
    val protocolVersion: Int = ProtocolConstants.PROTOCOL_VERSION
)

@Serializable
enum class ChannelType {
    PUBLIC_MESH,
    PASSWORD_PROTECTED,
    GEOHASH_ROOM,
    GLOBAL_NOSTR
}

@Serializable
data class ChatChannel(
    val channelId: String,
    val name: String,
    val topic: String,
    val channelType: ChannelType,
    val geohash: String? = null,
    val isJoined: Boolean = true,
    val isMuted: Boolean = false,
    val ownerPeerId: String? = null,
    val keyEpoch: Int = 1,
    val passwordSaltBase64: String? = null,
    val memberPeerIds: Set<String> = emptySet(),
    val unreadCount: Int = 0,
    val createdAtEpochMs: Long
)

@Serializable
enum class MessageContentType {
    TEXT,
    ACTION_IRC,
    SYSTEM_NOTICE,
    IMAGE_ATTACHMENT,
    FILE_ATTACHMENT,
    VOICE_NOTE,
    LIVE_PTT_CLIP,
    CASHU_TOKEN
}

@Serializable
data class AttachmentMetadata(
    val attachmentId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256Hex: String,
    val totalChunks: Int,
    val receivedChunks: Int = 0,
    val localFilePath: String? = null,
    val isAcceptedByUser: Boolean = false,
    val durationSeconds: Int? = null,
    val expiresAtEpochMs: Long? = null
)

@Serializable
data class ChatMessage(
    val messageId: String,
    val conversationId: String, // Channel ID or Peer ID for DMs
    val isDirectMessage: Boolean,
    val senderPeerId: String,
    val senderNickname: String,
    val senderVerified: Boolean,
    val recipientPeerId: String? = null,
    val content: String,
    val contentType: MessageContentType = MessageContentType.TEXT,
    val timestampEpochMs: Long,
    val expiresAtEpochMs: Long,
    val deliveryStatus: DeliveryStatus,
    val transportOrigin: TransportType,
    val hopCount: Int = 0,
    val isOutgoing: Boolean,
    val isEncrypted: Boolean,
    val signatureVerified: Boolean,
    val attachment: AttachmentMetadata? = null,
    val replyToMessageId: String? = null
)

@Serializable
data class DirectConversationSummary(
    val peerId: String,
    val nickname: String,
    val lastMessagePreview: String,
    val lastMessageTimestampMs: Long,
    val unreadCount: Int,
    val isVerified: Boolean,
    val isFavorite: Boolean,
    val isBlocked: Boolean,
    val sessionEstablished: Boolean,
    val presenceState: PeerPresenceState
)

@Serializable
data class GeohashRoom(
    val geohash: String,
    val precision: Int,
    val approximateRegionLabel: String,
    val centerLatitude: Double,
    val centerLongitude: Double,
    val approxRadiusKm: Double,
    val isBookmarked: Boolean = false,
    val activeParticipantCount: Int = 0
)

@Serializable
data class TransportCapabilities(
    val transportType: TransportType,
    val isSupportedOnDevice: Boolean,
    val isEnabled: Boolean,
    val maxPayloadBytes: Int,
    val supportsMultiHop: Boolean,
    val supportsStreamingAudio: Boolean,
    val requiresInternet: Boolean
)

@Serializable
data class NetworkDiagnosticsSnapshot(
    val activeTransports: Set<TransportType>,
    val bleAdapterEnabled: Boolean,
    val bleAdvertisingActive: Boolean,
    val bleScanningActive: Boolean,
    val wifiAwareSupported: Boolean,
    val wifiAwareAvailable: Boolean,
    val nostrConnectedRelays: Int,
    val nostrConfiguredRelays: Int,
    val discoveredPeersCount: Int,
    val connectedPeersCount: Int,
    val packetsSentTotal: Long,
    val packetsReceivedTotal: Long,
    val packetsRelayedTotal: Long,
    val packetsDeduplicatedDropped: Long,
    val packetsRateLimitedDropped: Long,
    val packetsInvalidSignatureDropped: Long,
    val storeForwardQueuedCount: Int,
    val courierMailboxesStoredCount: Int,
    val batterySaverModeEnabled: Boolean,
    val currentScanIntervalMs: Long,
    val protocolVersion: Int = ProtocolConstants.PROTOCOL_VERSION
)

