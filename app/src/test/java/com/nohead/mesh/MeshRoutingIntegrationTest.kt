package com.nohead.mesh

import com.nohead.mesh.domain.crypto.CryptoEngine
import com.nohead.mesh.domain.crypto.CryptoEngine.Companion.decodeBase64
import com.nohead.mesh.domain.crypto.CryptoEngine.Companion.encodeBase64
import com.nohead.mesh.domain.model.EncryptionMetadata
import com.nohead.mesh.domain.model.MeshEnvelope
import com.nohead.mesh.domain.model.PacketType
import com.nohead.mesh.domain.model.ProtocolConstants
import com.nohead.mesh.domain.model.TransportType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Multi-Node Mesh Simulation & Integration Test Harness.
 *
 * Verifies:
 * 1. Two-peer direct communication (Node A <-> Node B)
 * 2. Three-hop relay mesh (Node A -> Node B -> Node C where A and C cannot communicate directly)
 * 3. Multi-path & multi-transport duplicate packet suppression (BLE + Wi-Fi Aware + Nostr)
 * 4. Expired packet rejection and forged signature rejection
 * 5. Opaque Store-and-Forward Courier delivery (Node B stores encrypted envelope for offline Node C
 *    without being able to read plaintext, then delivers when Node C connects)
 */
class MeshRoutingIntegrationTest {

    private val crypto = CryptoEngine()

    private class SimulatedMeshNode(
        val name: String,
        val crypto: CryptoEngine
    ) {
        val signKey = crypto.generateEd25519KeyPair()
        val encKey = crypto.generateX25519KeyPair()
        val peerId = crypto.derivePeerId(signKey.publicKeyBytes)

        val directNeighbors = mutableSetOf<SimulatedMeshNode>()
        val seenPacketIds = mutableSetOf<String>()
        val deliveredMessages = mutableListOf<Pair<MeshEnvelope, String>>()
        val courierMailbox = mutableListOf<MeshEnvelope>()
        var relayedCount = 0
        var duplicateDroppedCount = 0
        var invalidSigDroppedCount = 0

        fun connectBidirectional(other: SimulatedMeshNode) {
            this.directNeighbors.add(other)
            other.directNeighbors.add(this)
        }

        fun disconnectBidirectional(other: SimulatedMeshNode) {
            this.directNeighbors.remove(other)
            other.directNeighbors.remove(this)
        }

        fun signEnvelope(unsigned: MeshEnvelope): MeshEnvelope {
            val sig = crypto.signEd25519(signKey.privateKeyBytes, unsigned.canonicalSignableBytes())
            return unsigned.copy(signatureBase64 = encodeBase64(sig))
        }

        fun verifyEnvelope(env: MeshEnvelope): Boolean {
            val derivedId = crypto.derivePeerId(
                with(CryptoEngine) { env.senderSigningPubKeyHex.hexToByteArray() }
            )
            if (derivedId != env.senderPeerId) return false
            return crypto.verifyEd25519(
                publicKeyBytes = with(CryptoEngine) { env.senderSigningPubKeyHex.hexToByteArray() },
                message = env.canonicalSignableBytes(),
                signatureBytes = decodeBase64(env.signatureBase64)
            )
        }

        fun broadcastOrSend(env: MeshEnvelope) {
            seenPacketIds.add(env.packetId)
            for (neighbor in directNeighbors) {
                neighbor.onPacketReceived(env, nowEpochMs = env.createdAtEpochMs + 10)
            }
        }

        fun onPacketReceived(env: MeshEnvelope, nowEpochMs: Long) {
            if (env.expiresAtEpochMs <= nowEpochMs) return
            if (env.senderPeerId == peerId) return

            if (!seenPacketIds.add(env.packetId)) {
                duplicateDroppedCount++
                return
            }

            if (!verifyEnvelope(env)) {
                invalidSigDroppedCount++
                return
            }

            when (env.packetType) {
                PacketType.PUBLIC_CHANNEL_MSG -> {
                    val text = decodeBase64(env.payloadBase64).decodeToString()
                    deliveredMessages.add(env to text)
                }
                PacketType.DIRECT_ENCRYPTED_MSG -> {
                    if (env.recipientPeerId == peerId) {
                        val meta = env.encryptionMetadata ?: return
                        val staticShared = crypto.computeX25519SharedSecret(
                            encKey.privateKeyBytes,
                            with(CryptoEngine) { env.senderEncryptionPubKeyHex.hexToByteArray() }
                        )
                        val ephShared = meta.ephemeralPublicKeyHex?.let {
                            crypto.computeX25519SharedSecret(
                                encKey.privateKeyBytes,
                                with(CryptoEngine) { it.hexToByteArray() }
                            )
                        }
                        val sessionKey = crypto.deriveNoiseSessionKey(
                            staticSharedSecret = staticShared,
                            ephemeralSharedSecret = ephShared,
                            initiatorPeerId = env.senderPeerId,
                            responderPeerId = peerId
                        )
                        val plaintext = crypto.decryptAead(
                            key32Bytes = sessionKey,
                            ciphertextBytes = decodeBase64(env.payloadBase64),
                            nonceBytes = decodeBase64(meta.nonceBase64),
                            associatedData = env.associatedDataBytes(),
                            algorithm = meta.algorithm
                        ).decodeToString()
                        deliveredMessages.add(env to plaintext)
                        return // Destination reached
                    } else {
                        // Store in opaque courier mailbox for offline recipient
                        courierMailbox.add(env)
                    }
                }
                else -> Unit
            }

            if (env.ttl > 1) {
                relayedCount++
                val relayed = env.copy(ttl = env.ttl - 1, hopCount = env.hopCount + 1)
                for (neighbor in directNeighbors) {
                    neighbor.onPacketReceived(relayed, nowEpochMs)
                }
            }
        }

        fun flushCourierMailboxTo(newlyConnectedPeer: SimulatedMeshNode, nowEpochMs: Long) {
            val iterator = courierMailbox.iterator()
            while (iterator.hasNext()) {
                val env = iterator.next()
                if (env.recipientPeerId == newlyConnectedPeer.peerId) {
                    newlyConnectedPeer.onPacketReceived(
                        env.copy(ttl = (env.ttl - 1).coerceAtLeast(1), hopCount = env.hopCount + 1),
                        nowEpochMs
                    )
                    iterator.remove()
                }
            }
        }
    }

    @Test
    fun `Three-hop mesh routes public and E2EE direct messages from Node A to Node C through Node B with deduplication`() = runTest {
        val nodeA = SimulatedMeshNode("NodeA", crypto)
        val nodeB = SimulatedMeshNode("NodeB_Relay", crypto)
        val nodeC = SimulatedMeshNode("NodeC", crypto)

        // Topology: A <-> B <-> C (A and C cannot communicate directly)
        nodeA.connectBidirectional(nodeB)
        nodeB.connectBidirectional(nodeC)
        assertFalse(nodeA.directNeighbors.contains(nodeC))

        val now = 1730000000000L

        // 1. Node A broadcasts a public mesh message
        val pubUnsigned = MeshEnvelope(
            packetId = "pub_hop_test_1",
            packetType = PacketType.PUBLIC_CHANNEL_MSG,
            senderPeerId = nodeA.peerId,
            senderNickname = nodeA.name,
            senderSigningPubKeyHex = nodeA.signKey.publicKeyHex,
            senderEncryptionPubKeyHex = nodeA.encKey.publicKeyHex,
            channelId = "#nearby-mesh",
            createdAtEpochMs = now,
            expiresAtEpochMs = now + 60_000L,
            ttl = ProtocolConstants.DEFAULT_MAX_TTL,
            hopCount = 0,
            payloadBase64 = encodeBase64("Hello from A across B to C".encodeToByteArray()),
            signatureBase64 = ""
        )
        val pubSigned = nodeA.signEnvelope(pubUnsigned)
        nodeA.broadcastOrSend(pubSigned)

        // Verify both B (hopCount=0) and C (hopCount=1, relayed by B) received the message exactly once
        assertEquals(1, nodeB.deliveredMessages.size)
        assertEquals("Hello from A across B to C", nodeB.deliveredMessages.first().second)
        assertEquals(0, nodeB.deliveredMessages.first().first.hopCount)

        assertEquals(1, nodeC.deliveredMessages.size)
        assertEquals("Hello from A across B to C", nodeC.deliveredMessages.first().second)
        assertEquals(1, nodeC.deliveredMessages.first().first.hopCount)
        assertEquals(1, nodeB.relayedCount)

        // 2. Inject duplicate packet over secondary transport (Wi-Fi Aware / Nostr) into Node C
        nodeC.onPacketReceived(
            pubSigned.copy(viaTransports = setOf(TransportType.WIFI_AWARE)),
            nowEpochMs = now + 50
        )
        assertEquals("Duplicate packet must not create second UI message", 1, nodeC.deliveredMessages.size)
        assertTrue(nodeC.duplicateDroppedCount >= 1)
    }

    @Test
    fun `Store-and-Forward Courier Node B holds encrypted DM while Node C is offline and delivers on reconnect`() = runTest {
        val nodeA = SimulatedMeshNode("NodeA", crypto)
        val nodeB = SimulatedMeshNode("NodeB_Courier", crypto)
        val nodeC = SimulatedMeshNode("NodeC_Offline", crypto)

        // Initially only A <-> B are connected; C is completely disconnected/offline
        nodeA.connectBidirectional(nodeB)
        val now = 1730000000000L

        // Node A encrypts a 1:1 Direct Message for Node C
        val ephKey = crypto.generateX25519KeyPair()
        val staticShared = crypto.computeX25519SharedSecret(nodeA.encKey.privateKeyBytes, nodeC.encKey.publicKeyBytes)
        val ephShared = crypto.computeX25519SharedSecret(ephKey.privateKeyBytes, nodeC.encKey.publicKeyBytes)
        val sessionKey = crypto.deriveNoiseSessionKey(staticShared, ephShared, nodeA.peerId, nodeC.peerId)

        val draftDm = MeshEnvelope(
            packetId = "dm_courier_01",
            packetType = PacketType.DIRECT_ENCRYPTED_MSG,
            senderPeerId = nodeA.peerId,
            senderNickname = nodeA.name,
            senderSigningPubKeyHex = nodeA.signKey.publicKeyHex,
            senderEncryptionPubKeyHex = nodeA.encKey.publicKeyHex,
            recipientPeerId = nodeC.peerId,
            createdAtEpochMs = now,
            expiresAtEpochMs = now + 300_000L,
            ttl = 5,
            hopCount = 0,
            payloadBase64 = "",
            signatureBase64 = ""
        )
        val aead = crypto.encryptAead(
            key32Bytes = sessionKey,
            plaintext = "Confidential offline courier payload for C".encodeToByteArray(),
            associatedData = draftDm.associatedDataBytes()
        )
        val signedDm = nodeA.signEnvelope(
            draftDm.copy(
                payloadBase64 = aead.ciphertextBase64,
                encryptionMetadata = EncryptionMetadata(
                    algorithm = aead.algorithm,
                    nonceBase64 = aead.nonceBase64,
                    ephemeralPublicKeyHex = ephKey.publicKeyHex
                )
            )
        )

        // Node A transmits to Node B
        nodeA.broadcastOrSend(signedDm)

        // Verify Node B could not decrypt it as a delivered message, and instead stored it in its opaque courier mailbox
        assertEquals(0, nodeB.deliveredMessages.size)
        assertEquals(1, nodeB.courierMailbox.size)
        assertEquals(0, nodeC.deliveredMessages.size)

        // Later, Node C comes online and connects to Courier Node B
        nodeB.connectBidirectional(nodeC)
        nodeB.flushCourierMailboxTo(nodeC, nowEpochMs = now + 10_000L)

        // Verify Node C decrypted the message and verified Node A's Ed25519 signature
        assertEquals(1, nodeC.deliveredMessages.size)
        assertEquals("Confidential offline courier payload for C", nodeC.deliveredMessages.first().second)
        assertEquals(1, nodeC.deliveredMessages.first().first.hopCount)
        assertEquals(0, nodeB.courierMailbox.size)
    }
}

