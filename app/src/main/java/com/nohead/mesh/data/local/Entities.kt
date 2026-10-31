package com.nohead.mesh.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nohead.mesh.domain.model.ChannelType
import com.nohead.mesh.domain.model.DeliveryStatus
import com.nohead.mesh.domain.model.MessageContentType
import com.nohead.mesh.domain.model.PeerPresenceState
import com.nohead.mesh.domain.model.TransportType

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationId", "timestampEpochMs"]),
        Index(value = ["senderPeerId"])
    ]
)
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val conversationId: String,
    val isDirectMessage: Boolean,
    val senderPeerId: String,
    val senderNickname: String,
    val senderVerified: Boolean,
    val recipientPeerId: String?,
    val content: String,
    val contentType: MessageContentType,
    val timestampEpochMs: Long,
    val expiresAtEpochMs: Long,
    val deliveryStatus: DeliveryStatus,
    val transportOrigin: TransportType,
    val hopCount: Int,
    val isOutgoing: Boolean,
    val isEncrypted: Boolean,
    val signatureVerified: Boolean,
    val attachmentJson: String? = null,
    val replyToMessageId: String? = null
)

@Entity(
    tableName = "peers",
    indices = [Index(value = ["lastSeenEpochMs"])]
)
data class PeerEntity(
    @PrimaryKey val peerId: String,
    val nickname: String,
    val signingPublicKeyHex: String,
    val encryptionPublicKeyHex: String,
    val fingerprint: String,
    val presenceState: PeerPresenceState,
    val activeTransportsCsv: String,
    val rssiDbm: Int?,
    val hopDistance: Int,
    val isVerified: Boolean,
    val isFavorite: Boolean,
    val isBlocked: Boolean,
    val isTrustedCourier: Boolean,
    val lastSeenEpochMs: Long,
    val rotatingPresenceToken: String,
    val protocolVersion: Int,
    val sessionSharedKeyBase64: String? = null
)

@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey val channelId: String,
    val name: String,
    val topic: String,
    val channelType: ChannelType,
    val geohash: String?,
    val isJoined: Boolean,
    val isMuted: Boolean,
    val ownerPeerId: String?,
    val keyEpoch: Int,
    val passwordSaltBase64: String?,
    val derivedKeyBase64: String?,
    val memberPeerIdsCsv: String,
    val unreadCount: Int,
    val createdAtEpochMs: Long
)

@Entity(
    tableName = "outgoing_queue",
    indices = [Index(value = ["recipientPeerId", "nextRetryEpochMs"])]
)
data class OutgoingQueueEntity(
    @PrimaryKey val packetId: String,
    val recipientPeerId: String?,
    val channelId: String?,
    val envelopeJson: String,
    val priority: Int,
    val retryCount: Int,
    val nextRetryEpochMs: Long,
    val expiresAtEpochMs: Long,
    val allowCourierReplication: Boolean,
    val replicasRemaining: Int,
    val createdAtEpochMs: Long
)

@Entity(
    tableName = "courier_mailbox",
    indices = [Index(value = ["targetRecipientPeerId", "expiresAtEpochMs"])]
)
data class CourierMailboxEntity(
    @PrimaryKey val packetId: String,
    val targetRecipientPeerId: String,
    val senderPeerId: String,
    val opaqueEnvelopeJson: String,
    val sizeBytes: Int,
    val hopsRemaining: Int,
    val storedAtEpochMs: Long,
    val expiresAtEpochMs: Long
)

@Entity(
    tableName = "seen_packets",
    indices = [Index(value = ["expiresAtEpochMs"])]
)
data class SeenPacketEntity(
    @PrimaryKey val packetId: String,
    val senderPeerId: String,
    val seenAtEpochMs: Long,
    val expiresAtEpochMs: Long
)

@Entity(tableName = "nostr_relays")
data class NostrRelayEntity(
    @PrimaryKey val url: String,
    val isEnabled: Boolean,
    val isReadEnabled: Boolean,
    val isWriteEnabled: Boolean,
    val lastConnectedEpochMs: Long?
)

@Entity(tableName = "geohash_bookmarks")
data class GeohashBookmarkEntity(
    @PrimaryKey val geohash: String,
    val precision: Int,
    val label: String,
    val centerLat: Double,
    val centerLon: Double,
    val radiusKm: Double,
    val createdAtEpochMs: Long
)

