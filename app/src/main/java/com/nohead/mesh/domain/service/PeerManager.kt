package com.nohead.mesh.domain.service

import com.nohead.mesh.data.local.PeerDao
import com.nohead.mesh.data.local.PeerEntity
import com.nohead.mesh.domain.crypto.CryptoEngine
import com.nohead.mesh.domain.model.PeerAnnouncementPayload
import com.nohead.mesh.domain.model.PeerNode
import com.nohead.mesh.domain.model.PeerPresenceState
import com.nohead.mesh.domain.model.TransportType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages discovered and known peers, presence transitions (online nearby, online internet,
 * stale, offline), cryptographic fingerprint verification, favorites, trusted courier status,
 * and blocklist enforcement.
 */
@Singleton
class PeerManager @Inject constructor(
    private val peerDao: PeerDao,
    private val cryptoEngine: CryptoEngine
) {
    companion object {
        private const val ONLINE_THRESHOLD_MS = 90_000L // 90 seconds
        private const val STALE_THRESHOLD_MS = 300_000L // 5 minutes
    }

    val allPeers: Flow<List<PeerNode>> = peerDao.observeAllPeers().map { entities ->
        val now = System.currentTimeMillis()
        entities.map { it.toDomain(now) }
    }

    val favoritePeers: Flow<List<PeerNode>> = peerDao.observeFavoritePeers().map { entities ->
        val now = System.currentTimeMillis()
        entities.map { it.toDomain(now) }
    }

    suspend fun getPeerById(peerId: String): PeerNode? {
        return peerDao.getPeerById(peerId)?.toDomain(System.currentTimeMillis())
    }

    suspend fun isPeerBlocked(peerId: String): Boolean {
        return peerDao.getPeerById(peerId)?.isBlocked == true
    }

    suspend fun onPeerDiscoveredOrUpdated(
        announcement: PeerAnnouncementPayload,
        sourceTransport: TransportType,
        rssiDbm: Int?,
        hopDistance: Int
    ): PeerNode {
        val existing = peerDao.getPeerById(announcement.peerId)
        val fingerprint = cryptoEngine.formatIdentityFingerprint(
            announcement.signingPublicKeyHex,
            announcement.encryptionPublicKeyHex
        )
        val mergedTransports = buildSet {
            existing?.activeTransportsCsv?.split(",")
                ?.filter { it.isNotBlank() }
                ?.mapNotNull { runCatching { TransportType.valueOf(it) }.getOrNull() }
                ?.let { addAll(it) }
            addAll(announcement.supportedTransports)
            add(sourceTransport)
        }
        val presence = if (sourceTransport == TransportType.NOSTR_RELAY) {
            PeerPresenceState.ONLINE_INTERNET
        } else {
            PeerPresenceState.ONLINE_NEARBY
        }
        val now = System.currentTimeMillis()

        val updatedEntity = PeerEntity(
            peerId = announcement.peerId,
            nickname = announcement.nickname,
            signingPublicKeyHex = announcement.signingPublicKeyHex,
            encryptionPublicKeyHex = announcement.encryptionPublicKeyHex,
            fingerprint = fingerprint,
            presenceState = presence,
            activeTransportsCsv = mergedTransports.joinToString(",") { it.name },
            rssiDbm = rssiDbm ?: existing?.rssiDbm,
            hopDistance = hopDistance.coerceAtLeast(1),
            isVerified = existing?.isVerified ?: false,
            isFavorite = existing?.isFavorite ?: false,
            isBlocked = existing?.isBlocked ?: false,
            isTrustedCourier = existing?.isTrustedCourier ?: false,
            lastSeenEpochMs = now,
            rotatingPresenceToken = announcement.rotatingPresenceToken,
            protocolVersion = announcement.protocolVersion,
            sessionSharedKeyBase64 = existing?.sessionSharedKeyBase64
        )
        peerDao.upsertPeer(updatedEntity)
        return updatedEntity.toDomain(now)
    }

    suspend fun registerPeerFromEnvelope(
        peerId: String,
        nickname: String,
        signingPubKeyHex: String,
        encryptionPubKeyHex: String,
        sourceTransport: TransportType,
        rssiDbm: Int?,
        hopDistance: Int
    ) {
        val existing = peerDao.getPeerById(peerId)
        val fingerprint = cryptoEngine.formatIdentityFingerprint(signingPubKeyHex, encryptionPubKeyHex)
        val now = System.currentTimeMillis()
        val transports = buildSet {
            existing?.activeTransportsCsv?.split(",")
                ?.filter { it.isNotBlank() }
                ?.mapNotNull { runCatching { TransportType.valueOf(it) }.getOrNull() }
                ?.let { addAll(it) }
            add(sourceTransport)
        }
        val presence = if (sourceTransport == TransportType.NOSTR_RELAY) {
            PeerPresenceState.ONLINE_INTERNET
        } else {
            PeerPresenceState.ONLINE_NEARBY
        }
        peerDao.upsertPeer(
            PeerEntity(
                peerId = peerId,
                nickname = nickname,
                signingPublicKeyHex = signingPubKeyHex,
                encryptionPublicKeyHex = encryptionPubKeyHex,
                fingerprint = fingerprint,
                presenceState = presence,
                activeTransportsCsv = transports.joinToString(",") { it.name },
                rssiDbm = rssiDbm ?: existing?.rssiDbm,
                hopDistance = hopDistance.coerceAtLeast(1),
                isVerified = existing?.isVerified ?: false,
                isFavorite = existing?.isFavorite ?: false,
                isBlocked = existing?.isBlocked ?: false,
                isTrustedCourier = existing?.isTrustedCourier ?: false,
                lastSeenEpochMs = now,
                rotatingPresenceToken = existing?.rotatingPresenceToken.orEmpty(),
                protocolVersion = existing?.protocolVersion ?: 1,
                sessionSharedKeyBase64 = existing?.sessionSharedKeyBase64
            )
        )
    }

    suspend fun toggleFavorite(peerId: String): Boolean {
        val existing = peerDao.getPeerById(peerId) ?: return false
        val next = !existing.isFavorite
        peerDao.setFavorite(peerId, next)
        return next
    }

    suspend fun setPeerBlocked(peerId: String, blocked: Boolean) {
        peerDao.setBlocked(peerId, blocked)
    }

    suspend fun verifyPeerFingerprint(peerId: String, expectedFingerprint: String? = null): Boolean {
        val existing = peerDao.getPeerById(peerId) ?: return false
        if (expectedFingerprint != null &&
            existing.fingerprint.replace(" ", "").lowercase() != expectedFingerprint.replace(" ", "").lowercase()
        ) {
            return false
        }
        peerDao.setVerified(peerId, true)
        return true
    }

    suspend fun setTrustedCourier(peerId: String, trusted: Boolean) {
        peerDao.setTrustedCourier(peerId, trusted)
    }

    private fun PeerEntity.toDomain(nowMs: Long): PeerNode {
        val age = nowMs - lastSeenEpochMs
        val transports = activeTransportsCsv.split(",")
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { TransportType.valueOf(it) }.getOrNull() }
            .toSet()

        val computedPresence = when {
            age <= ONLINE_THRESHOLD_MS -> presenceState
            age <= STALE_THRESHOLD_MS -> PeerPresenceState.STALE
            else -> PeerPresenceState.OFFLINE
        }

        return PeerNode(
            peerId = peerId,
            nickname = nickname,
            signingPublicKeyHex = signingPublicKeyHex,
            encryptionPublicKeyHex = encryptionPublicKeyHex,
            fingerprint = fingerprint,
            presenceState = computedPresence,
            activeTransports = transports,
            rssiDbm = rssiDbm,
            hopDistance = hopDistance,
            isVerified = isVerified,
            isFavorite = isFavorite,
            isBlocked = isBlocked,
            isTrustedCourier = isTrustedCourier,
            lastSeenEpochMs = lastSeenEpochMs,
            rotatingPresenceToken = rotatingPresenceToken,
            protocolVersion = protocolVersion
        )
    }
}

