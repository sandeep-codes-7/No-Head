package com.nohead.mesh.domain.transport

import com.nohead.mesh.domain.crypto.CryptoEngine
import com.nohead.mesh.domain.model.MeshEnvelope
import com.nohead.mesh.domain.model.PacketFragment
import com.nohead.mesh.domain.model.ProtocolConstants
import com.nohead.mesh.domain.model.TransportCapabilities
import com.nohead.mesh.domain.model.TransportType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

/**
 * Transport-independent networking abstraction designed so Android and future iOS clients
 * share the exact same envelope semantics and security policies.
 */
interface Transport {
    val transportType: TransportType
    val isRunning: StateFlow<Boolean>
    val discoveredPeersCount: StateFlow<Int>
    val connectedPeersCount: StateFlow<Int>

    suspend fun start()
    suspend fun stop()
    suspend fun discoverPeers()
    suspend fun sendPacket(envelope: MeshEnvelope, targetPeerId: String? = null): Result<Unit>
    fun receivePackets(): Flow<InboundTransportPacket>
    fun getCapabilities(): TransportCapabilities
}

data class InboundTransportPacket(
    val envelope: MeshEnvelope,
    val sourceTransport: TransportType,
    val rssiDbm: Int? = null,
    val immediateSenderAddress: String? = null
)

/**
 * Handles deterministic packet fragmentation and reassembly with CRC32 integrity verification
 * and bounded buffer expiration for low-MTU links (BLE GATT / L2CAP).
 */
class PacketFragmenter(
    private val maxChunkPayloadBytes: Int = ProtocolConstants.BLE_DEFAULT_CHUNK_PAYLOAD_BYTES
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private data class ReassemblySession(
        val totalFragments: Int,
        val createdAtMs: Long = System.currentTimeMillis(),
        val chunks: ConcurrentHashMap<Int, ByteArray> = ConcurrentHashMap()
    )

    private val activeSessions = ConcurrentHashMap<String, ReassemblySession>()

    fun fragmentEnvelope(envelope: MeshEnvelope): List<PacketFragment> {
        val serializedBytes = json.encodeToString(envelope).encodeToByteArray()
        val totalFragments = ((serializedBytes.size + maxChunkPayloadBytes - 1) / maxChunkPayloadBytes).coerceAtLeast(1)
        val fragments = ArrayList<PacketFragment>(totalFragments)

        for (index in 0 until totalFragments) {
            val start = index * maxChunkPayloadBytes
            val end = minOf(start + maxChunkPayloadBytes, serializedBytes.size)
            val slice = serializedBytes.copyOfRange(start, end)
            val crc = CRC32().apply { update(slice) }.value
            fragments.add(
                PacketFragment(
                    protocolVersion = envelope.protocolVersion,
                    envelopeId = envelope.packetId,
                    fragmentIndex = index,
                    totalFragments = totalFragments,
                    chunkBase64 = CryptoEngine.encodeBase64(slice),
                    crc32 = crc
                )
            )
        }
        return fragments
    }

    /**
     * Feeds an incoming [PacketFragment] and returns the reassembled [MeshEnvelope] once all fragments
     * arrive and pass CRC32 validation.
     */
    fun onFragmentReceived(fragment: PacketFragment, nowEpochMs: Long = System.currentTimeMillis()): MeshEnvelope? {
        if (fragment.totalFragments <= 0 || fragment.totalFragments > 4096) return null
        if (fragment.fragmentIndex < 0 || fragment.fragmentIndex >= fragment.totalFragments) return null

        val chunkBytes = try {
            CryptoEngine.decodeBase64(fragment.chunkBase64)
        } catch (_: Exception) {
            return null
        }

        val computedCrc = CRC32().apply { update(chunkBytes) }.value
        if (computedCrc != fragment.crc32) {
            return null // Corrupted BLE fragment dropped
        }

        purgeStaleSessions(nowEpochMs)

        if (fragment.totalFragments == 1) {
            return runCatching {
                json.decodeFromString<MeshEnvelope>(chunkBytes.decodeToString())
            }.getOrNull()
        }

        // Enforce bounded memory limit on concurrent reassembly sessions
        if (activeSessions.size >= 128 && !activeSessions.containsKey(fragment.envelopeId)) {
            val oldestKey = activeSessions.entries.minByOrNull { it.value.createdAtMs }?.key
            if (oldestKey != null) activeSessions.remove(oldestKey)
        }

        val session = activeSessions.computeIfAbsent(fragment.envelopeId) {
            ReassemblySession(totalFragments = fragment.totalFragments, createdAtMs = nowEpochMs)
        }

        if (session.totalFragments != fragment.totalFragments) {
            activeSessions.remove(fragment.envelopeId)
            return null
        }

        session.chunks[fragment.fragmentIndex] = chunkBytes

        if (session.chunks.size == session.totalFragments) {
            activeSessions.remove(fragment.envelopeId)
            val totalSize = session.chunks.values.sumOf { it.size }
            val combined = ByteArray(totalSize)
            var offset = 0
            for (i in 0 until session.totalFragments) {
                val part = session.chunks[i] ?: return null
                System.arraycopy(part, 0, combined, offset, part.size)
                offset += part.size
            }
            return runCatching {
                json.decodeFromString<MeshEnvelope>(combined.decodeToString())
            }.getOrNull()
        }

        return null
    }

    private fun purgeStaleSessions(nowEpochMs: Long) {
        val expiryThreshold = nowEpochMs - 60_000L // 60 seconds reassembly window
        val iterator = activeSessions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.createdAtMs < expiryThreshold) {
                iterator.remove()
            }
        }
    }
}

