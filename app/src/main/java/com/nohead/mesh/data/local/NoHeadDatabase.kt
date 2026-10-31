package com.nohead.mesh.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import com.nohead.mesh.domain.model.DeliveryStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestampEpochMs ASC")
    fun observeMessagesForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE isDirectMessage = 1 ORDER BY timestampEpochMs DESC")
    fun observeAllDirectMessages(): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessageIgnoreDuplicate(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: MessageEntity)

    @Query("UPDATE messages SET deliveryStatus = :status WHERE messageId = :messageId")
    suspend fun updateDeliveryStatus(messageId: String, status: DeliveryStatus)

    @Query("SELECT * FROM messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getMessageById(messageId: String): MessageEntity?

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun clearConversation(conversationId: String)

    @Query("DELETE FROM messages WHERE expiresAtEpochMs < :nowEpochMs")
    suspend fun deleteExpiredMessages(nowEpochMs: Long): Int

    @Query("DELETE FROM messages")
    suspend fun wipeAllMessages()
}

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY isFavorite DESC, lastSeenEpochMs DESC")
    fun observeAllPeers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE isFavorite = 1 ORDER BY nickname ASC")
    fun observeFavoritePeers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE peerId = :peerId LIMIT 1")
    suspend fun getPeerById(peerId: String): PeerEntity?

    @Query("SELECT * FROM peers")
    suspend fun getAllPeersSnapshot(): List<PeerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeer(peer: PeerEntity)

    @Query("UPDATE peers SET isFavorite = :isFavorite WHERE peerId = :peerId")
    suspend fun setFavorite(peerId: String, isFavorite: Boolean)

    @Query("UPDATE peers SET isBlocked = :isBlocked WHERE peerId = :peerId")
    suspend fun setBlocked(peerId: String, isBlocked: Boolean)

    @Query("UPDATE peers SET isVerified = :isVerified WHERE peerId = :peerId")
    suspend fun setVerified(peerId: String, isVerified: Boolean)

    @Query("UPDATE peers SET isTrustedCourier = :isTrusted WHERE peerId = :peerId")
    suspend fun setTrustedCourier(peerId: String, isTrusted: Boolean)

    @Query("DELETE FROM peers")
    suspend fun wipeAllPeers()
}

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels ORDER BY createdAtEpochMs ASC")
    fun observeAllChannels(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE channelId = :channelId LIMIT 1")
    suspend fun getChannelById(channelId: String): ChannelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChannel(channel: ChannelEntity)

    @Query("UPDATE channels SET isJoined = :isJoined WHERE channelId = :channelId")
    suspend fun setJoined(channelId: String, isJoined: Boolean)

    @Query("UPDATE channels SET isMuted = :isMuted WHERE channelId = :channelId")
    suspend fun setMuted(channelId: String, isMuted: Boolean)

    @Query("UPDATE channels SET unreadCount = :unreadCount WHERE channelId = :channelId")
    suspend fun setUnreadCount(channelId: String, unreadCount: Int)

    @Query("DELETE FROM channels WHERE channelId = :channelId")
    suspend fun deleteChannel(channelId: String)

    @Query("DELETE FROM channels")
    suspend fun wipeAllChannels()
}

@Dao
interface OutgoingQueueDao {
    @Query("SELECT * FROM outgoing_queue ORDER BY priority DESC, createdAtEpochMs ASC")
    fun observeQueue(): Flow<List<OutgoingQueueEntity>>

    @Query("SELECT * FROM outgoing_queue WHERE nextRetryEpochMs <= :nowEpochMs AND expiresAtEpochMs > :nowEpochMs ORDER BY priority DESC, createdAtEpochMs ASC LIMIT :limit")
    suspend fun getDuePackets(nowEpochMs: Long, limit: Int = 32): List<OutgoingQueueEntity>

    @Query("SELECT * FROM outgoing_queue WHERE recipientPeerId = :peerId AND expiresAtEpochMs > :nowEpochMs")
    suspend fun getPacketsForRecipient(peerId: String, nowEpochMs: Long): List<OutgoingQueueEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(item: OutgoingQueueEntity)

    @Query("DELETE FROM outgoing_queue WHERE packetId = :packetId")
    suspend fun removeById(packetId: String)

    @Query("DELETE FROM outgoing_queue WHERE expiresAtEpochMs <= :nowEpochMs")
    suspend fun purgeExpired(nowEpochMs: Long): Int

    @Query("SELECT COUNT(*) FROM outgoing_queue")
    suspend fun countQueued(): Int

    @Query("DELETE FROM outgoing_queue")
    suspend fun wipeQueue()
}

@Dao
interface CourierMailboxDao {
    @Query("SELECT * FROM courier_mailbox WHERE targetRecipientPeerId = :recipientPeerId AND expiresAtEpochMs > :nowEpochMs")
    suspend fun getMailForRecipient(recipientPeerId: String, nowEpochMs: Long): List<CourierMailboxEntity>

    @Query("SELECT * FROM courier_mailbox WHERE expiresAtEpochMs > :nowEpochMs LIMIT :limit")
    suspend fun getActiveCourierEnvelopes(nowEpochMs: Long, limit: Int = 50): List<CourierMailboxEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun storeEnvelope(envelope: CourierMailboxEntity): Long

    @Query("DELETE FROM courier_mailbox WHERE packetId = :packetId")
    suspend fun deleteEnvelope(packetId: String)

    @Query("DELETE FROM courier_mailbox WHERE expiresAtEpochMs <= :nowEpochMs")
    suspend fun purgeExpired(nowEpochMs: Long): Int

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM courier_mailbox")
    suspend fun getTotalCourierBytes(): Long

    @Query("SELECT COUNT(*) FROM courier_mailbox")
    suspend fun countStored(): Int

    @Query("DELETE FROM courier_mailbox")
    suspend fun wipeMailbox()
}

@Dao
interface SeenPacketDao {
    @Query("SELECT EXISTS(SELECT 1 FROM seen_packets WHERE packetId = :packetId AND expiresAtEpochMs > :nowEpochMs)")
    suspend fun hasSeen(packetId: String, nowEpochMs: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markSeen(entity: SeenPacketEntity)

    @Query("DELETE FROM seen_packets WHERE expiresAtEpochMs <= :nowEpochMs")
    suspend fun purgeExpired(nowEpochMs: Long): Int

    @Query("DELETE FROM seen_packets")
    suspend fun wipeSeenPackets()
}

@Dao
interface NostrRelayDao {
    @Query("SELECT * FROM nostr_relays ORDER BY url ASC")
    fun observeRelays(): Flow<List<NostrRelayEntity>>

    @Query("SELECT * FROM nostr_relays")
    suspend fun getRelaysSnapshot(): List<NostrRelayEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRelay(relay: NostrRelayEntity)

    @Query("DELETE FROM nostr_relays WHERE url = :url")
    suspend fun deleteRelay(url: String)

    @Query("DELETE FROM nostr_relays")
    suspend fun wipeRelays()
}

@Dao
interface GeohashBookmarkDao {
    @Query("SELECT * FROM geohash_bookmarks ORDER BY createdAtEpochMs DESC")
    fun observeBookmarks(): Flow<List<GeohashBookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBookmark(bookmark: GeohashBookmarkEntity)

    @Query("DELETE FROM geohash_bookmarks WHERE geohash = :geohash")
    suspend fun deleteBookmark(geohash: String)

    @Query("DELETE FROM geohash_bookmarks")
    suspend fun wipeBookmarks()
}

@Database(
    entities = [
        MessageEntity::class,
        PeerEntity::class,
        ChannelEntity::class,
        OutgoingQueueEntity::class,
        CourierMailboxEntity::class,
        SeenPacketEntity::class,
        NostrRelayEntity::class,
        GeohashBookmarkEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class NoHeadDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun peerDao(): PeerDao
    abstract fun channelDao(): ChannelDao
    abstract fun outgoingQueueDao(): OutgoingQueueDao
    abstract fun courierMailboxDao(): CourierMailboxDao
    abstract fun seenPacketDao(): SeenPacketDao
    abstract fun nostrRelayDao(): NostrRelayDao
    abstract fun geohashBookmarkDao(): GeohashBookmarkDao
}

