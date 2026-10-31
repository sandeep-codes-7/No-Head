package com.nohead.mesh.domain.service

import com.nohead.mesh.data.local.CourierMailboxDao
import com.nohead.mesh.data.local.CourierMailboxEntity
import com.nohead.mesh.data.local.OutgoingQueueDao
import com.nohead.mesh.data.local.OutgoingQueueEntity
import com.nohead.mesh.domain.model.MeshEnvelope
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Store-and-Forward & Courier Mailbox Manager.
 *
 * Handles:
 * - Persistent encrypted outgoing queue (survives restarts inside SQLCipher Room DB)
 * - Exponential backoff retry scheduling
 * - Bounded Spray-and-Wait replication (`replicasRemaining = 3`)
 * - Trusted-peer opaque courier mailboxes with strict storage quotas (`MAX_COURIER_STORAGE_BYTES = 4 MB`)
 * - Courier nodes only store opaque, signed, end-to-end encrypted [MeshEnvelope] packets and
 *   can never decrypt private message plaintext.
 */
@Singleton
class StoreForwardManager @Inject constructor(
    private val outgoingQueueDao: OutgoingQueueDao,
    private val courierMailboxDao: CourierMailboxDao
) {
    companion object {
        const val MAX_OUTGOING_QUEUE_ITEMS = 500
        const val MAX_COURIER_STORAGE_BYTES = 4L * 1024L * 1024L // 4 MB courier quota
        const val DEFAULT_SPRAY_WAIT_REPLICAS = 3
        private const val BASE_RETRY_BACKOFF_MS = 5_000L
        private const val MAX_RETRY_BACKOFF_MS = 300_000L // 5 min cap
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val queuedItems: Flow<List<OutgoingQueueEntity>> = outgoingQueueDao.observeQueue()

    suspend fun enqueueOutgoing(
        envelope: MeshEnvelope,
        priority: Int = 1,
        allowCourierReplication: Boolean = true
    ) {
        val now = System.currentTimeMillis()
        outgoingQueueDao.purgeExpired(now)
        val count = outgoingQueueDao.countQueued()
        if (count >= MAX_OUTGOING_QUEUE_ITEMS) return

        val entity = OutgoingQueueEntity(
            packetId = envelope.packetId,
            recipientPeerId = envelope.recipientPeerId,
            channelId = envelope.channelId,
            envelopeJson = json.encodeToString(envelope),
            priority = priority,
            retryCount = 0,
            nextRetryEpochMs = now,
            expiresAtEpochMs = envelope.expiresAtEpochMs,
            allowCourierReplication = allowCourierReplication,
            replicasRemaining = if (allowCourierReplication) DEFAULT_SPRAY_WAIT_REPLICAS else 0,
            createdAtEpochMs = now
        )
        outgoingQueueDao.enqueue(entity)
    }

    suspend fun acknowledgeDelivered(packetId: String) {
        outgoingQueueDao.removeById(packetId)
        courierMailboxDao.deleteEnvelope(packetId)
    }

    suspend fun getDueOutboxEnvelopes(nowEpochMs: Long = System.currentTimeMillis()): List<Pair<OutgoingQueueEntity, MeshEnvelope>> {
        outgoingQueueDao.purgeExpired(nowEpochMs)
        return outgoingQueueDao.getDuePackets(nowEpochMs).mapNotNull { item ->
            runCatching {
                item to json.decodeFromString<MeshEnvelope>(item.envelopeJson)
            }.getOrNull()
        }
    }

    suspend fun markRetryAttempt(item: OutgoingQueueEntity, consumedCourierReplica: Boolean = false) {
        val nextRetryCount = item.retryCount + 1
        val backoff = min(
            MAX_RETRY_BACKOFF_MS,
            BASE_RETRY_BACKOFF_MS * (1L shl nextRetryCount.coerceAtMost(6))
        )
        val nextReplicas = if (consumedCourierReplica && item.replicasRemaining > 0) {
            item.replicasRemaining - 1
        } else {
            item.replicasRemaining
        }
        outgoingQueueDao.enqueue(
            item.copy(
                retryCount = nextRetryCount,
                nextRetryEpochMs = System.currentTimeMillis() + backoff,
                replicasRemaining = nextReplicas
            )
        )
    }

    /**
     * Stores an opaque encrypted envelope on behalf of an offline recipient if within courier quota.
     */
    suspend fun storeCourierEnvelope(envelope: MeshEnvelope): Boolean {
        val targetPeerId = envelope.recipientPeerId ?: return false
        val now = System.currentTimeMillis()
        if (envelope.expiresAtEpochMs <= now) return false

        courierMailboxDao.purgeExpired(now)
        val serialized = json.encodeToString(envelope)
        val sizeBytes = serialized.encodeToByteArray().size
        val currentBytes = courierMailboxDao.getTotalCourierBytes()
        if (currentBytes + sizeBytes > MAX_COURIER_STORAGE_BYTES) {
            return false // Quota exceeded
        }

        val entity = CourierMailboxEntity(
            packetId = envelope.packetId,
            targetRecipientPeerId = targetPeerId,
            senderPeerId = envelope.senderPeerId,
            opaqueEnvelopeJson = serialized,
            sizeBytes = sizeBytes,
            hopsRemaining = (envelope.ttl - 1).coerceAtLeast(1),
            storedAtEpochMs = now,
            expiresAtEpochMs = envelope.expiresAtEpochMs
        )
        return courierMailboxDao.storeEnvelope(entity) != -1L
    }

    /**
     * Retrieves all queued and courier-stored envelopes matching a newly discovered [peerId].
     */
    suspend fun drainEnvelopesForDiscoveredPeer(peerId: String): List<MeshEnvelope> {
        val now = System.currentTimeMillis()
        val directOutbox = outgoingQueueDao.getPacketsForRecipient(peerId, now).mapNotNull {
            runCatching { json.decodeFromString<MeshEnvelope>(it.envelopeJson) }.getOrNull()
        }
        val courierHeld = courierMailboxDao.getMailForRecipient(peerId, now).mapNotNull { entity ->
            runCatching {
                val env = json.decodeFromString<MeshEnvelope>(entity.opaqueEnvelopeJson)
                courierMailboxDao.deleteEnvelope(entity.packetId)
                env
            }.getOrNull()
        }
        return directOutbox + courierHeld
    }

    suspend fun getCounts(): Pair<Int, Int> {
        return outgoingQueueDao.countQueued() to courierMailboxDao.countStored()
    }
}

