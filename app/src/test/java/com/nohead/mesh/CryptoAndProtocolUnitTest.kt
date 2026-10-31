package com.nohead.mesh

import com.nohead.mesh.domain.crypto.CryptoEngine
import com.nohead.mesh.domain.crypto.CryptoEngine.Companion.decodeBase64
import com.nohead.mesh.domain.crypto.CryptoEngine.Companion.encodeBase64
import com.nohead.mesh.domain.model.EncryptionMetadata
import com.nohead.mesh.domain.model.MeshEnvelope
import com.nohead.mesh.domain.model.PacketType
import com.nohead.mesh.domain.model.ProtocolConstants
import com.nohead.mesh.domain.model.TransportType
import com.nohead.mesh.domain.service.GeohashCodec
import com.nohead.mesh.domain.service.IrcCommandParser
import com.nohead.mesh.domain.transport.PacketFragmenter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoAndProtocolUnitTest {

    private val crypto = CryptoEngine()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `Ed25519 signature verification succeeds for valid envelope and rejects tampered payload`() {
        val signPair = crypto.generateEd25519KeyPair()
        val encPair = crypto.generateX25519KeyPair()
        val peerId = crypto.derivePeerId(signPair.publicKeyBytes)
        val now = 1730000000000L

        val unsigned = MeshEnvelope(
            protocolVersion = ProtocolConstants.PROTOCOL_VERSION,
            packetId = "pkt_test_001",
            packetType = PacketType.PUBLIC_CHANNEL_MSG,
            senderPeerId = peerId,
            senderNickname = "cipher_node",
            senderSigningPubKeyHex = signPair.publicKeyHex,
            senderEncryptionPubKeyHex = encPair.publicKeyHex,
            channelId = "#nearby-mesh",
            createdAtEpochMs = now,
            expiresAtEpochMs = now + 60_000L,
            ttl = 5,
            hopCount = 0,
            payloadBase64 = encodeBase64("Hello decentralized mesh".encodeToByteArray()),
            signatureBase64 = ""
        )

        val sigBytes = crypto.signEd25519(signPair.privateKeyBytes, unsigned.canonicalSignableBytes())
        val signed = unsigned.copy(signatureBase64 = encodeBase64(sigBytes))

        // Verify valid signature
        assertTrue(
            crypto.verifyEd25519(
                publicKeyBytes = signPair.publicKeyBytes,
                message = signed.canonicalSignableBytes(),
                signatureBytes = decodeBase64(signed.signatureBase64)
            )
        )

        // Verify multi-hop relay mutating TTL and hopCount preserves valid origin signature
        val relayed = signed.copy(ttl = signed.ttl - 1, hopCount = signed.hopCount + 1)
        assertTrue(
            crypto.verifyEd25519(
                publicKeyBytes = signPair.publicKeyBytes,
                message = relayed.canonicalSignableBytes(),
                signatureBytes = decodeBase64(relayed.signatureBase64)
            )
        )

        // Verify tampered payload is rejected
        val tampered = signed.copy(payloadBase64 = encodeBase64("Tampered payload".encodeToByteArray()))
        assertFalse(
            crypto.verifyEd25519(
                publicKeyBytes = signPair.publicKeyBytes,
                message = tampered.canonicalSignableBytes(),
                signatureBytes = decodeBase64(tampered.signatureBase64)
            )
        )
    }

    @Test
    fun `X25519 Noise session key derivation and ChaCha20-Poly1305 AEAD encrypt-decrypt roundtrip`() {
        val aliceSign = crypto.generateEd25519KeyPair()
        val aliceStaticEnc = crypto.generateX25519KeyPair()
        val aliceEphemeralEnc = crypto.generateX25519KeyPair()
        val alicePeerId = crypto.derivePeerId(aliceSign.publicKeyBytes)

        val bobSign = crypto.generateEd25519KeyPair()
        val bobStaticEnc = crypto.generateX25519KeyPair()
        val bobPeerId = crypto.derivePeerId(bobSign.publicKeyBytes)

        // Alice computes static-static and ephemeral-static shared secrets
        val aliceStaticShared = crypto.computeX25519SharedSecret(
            aliceStaticEnc.privateKeyBytes,
            bobStaticEnc.publicKeyBytes
        )
        val aliceEphShared = crypto.computeX25519SharedSecret(
            aliceEphemeralEnc.privateKeyBytes,
            bobStaticEnc.publicKeyBytes
        )
        val aliceSessionKey = crypto.deriveNoiseSessionKey(
            staticSharedSecret = aliceStaticShared,
            ephemeralSharedSecret = aliceEphShared,
            initiatorPeerId = alicePeerId,
            responderPeerId = bobPeerId
        )

        // Bob computes matching shared secrets using Alice's static and ephemeral public keys
        val bobStaticShared = crypto.computeX25519SharedSecret(
            bobStaticEnc.privateKeyBytes,
            aliceStaticEnc.publicKeyBytes
        )
        val bobEphShared = crypto.computeX25519SharedSecret(
            bobStaticEnc.privateKeyBytes,
            aliceEphemeralEnc.publicKeyBytes
        )
        val bobSessionKey = crypto.deriveNoiseSessionKey(
            staticSharedSecret = bobStaticShared,
            ephemeralSharedSecret = bobEphShared,
            initiatorPeerId = alicePeerId,
            responderPeerId = bobPeerId
        )

        assertArrayEquals(aliceSessionKey, bobSessionKey)

        val secretPlaintext = "Private E2EE mesh message with forward secrecy".encodeToByteArray()
        val aad = "1|dm_101|DIRECT_ENCRYPTED_MSG|$alicePeerId|$bobPeerId||1730000000000".encodeToByteArray()

        val encrypted = crypto.encryptAead(aliceSessionKey, secretPlaintext, aad)
        val decrypted = crypto.decryptAead(
            key32Bytes = bobSessionKey,
            ciphertextBytes = encrypted.ciphertextBytes,
            nonceBytes = encrypted.nonceBytes,
            associatedData = aad,
            algorithm = encrypted.algorithm
        )

        assertArrayEquals(secretPlaintext, decrypted)

        // Tampering with AAD must fail AEAD authentication
        val wrongAad = "1|dm_101|DIRECT_ENCRYPTED_MSG|$alicePeerId|attacker_id||1730000000000".encodeToByteArray()
        val tamperedResult = runCatching {
            crypto.decryptAead(
                key32Bytes = bobSessionKey,
                ciphertextBytes = encrypted.ciphertextBytes,
                nonceBytes = encrypted.nonceBytes,
                associatedData = wrongAad,
                algorithm = encrypted.algorithm
            )
        }
        assertTrue(tamperedResult.isFailure)
    }

    @Test
    fun `Argon2id derives deterministic group key for matching password and distinct key for wrong password`() {
        val salt = crypto.randomBytes(16)
        val key1 = crypto.deriveKeyArgon2id("correct-horse-battery-staple".toCharArray(), salt)
        val key2 = crypto.deriveKeyArgon2id("correct-horse-battery-staple".toCharArray(), salt)
        val wrongKey = crypto.deriveKeyArgon2id("wrong-password".toCharArray(), salt)

        assertArrayEquals(key1, key2)
        assertFalse(key1.contentEquals(wrongKey))
    }

    @Test
    fun `PacketFragmenter fragments large envelope and reassembles out-of-order fragments with CRC32 validation`() {
        val fragmenter = PacketFragmenter(maxChunkPayloadBytes = 80)
        val signPair = crypto.generateEd25519KeyPair()
        val encPair = crypto.generateX25519KeyPair()
        val peerId = crypto.derivePeerId(signPair.publicKeyBytes)
        val largeText = "MeshPayload-".repeat(60)

        val original = MeshEnvelope(
            packetId = "env_large_99",
            packetType = PacketType.PUBLIC_CHANNEL_MSG,
            senderPeerId = peerId,
            senderNickname = "frag_tester",
            senderSigningPubKeyHex = signPair.publicKeyHex,
            senderEncryptionPubKeyHex = encPair.publicKeyHex,
            channelId = "#nearby-mesh",
            createdAtEpochMs = 1730000000000L,
            expiresAtEpochMs = 1730000600000L,
            ttl = 5,
            hopCount = 1,
            payloadBase64 = encodeBase64(largeText.encodeToByteArray()),
            encryptionMetadata = EncryptionMetadata(nonceBase64 = encodeBase64(ByteArray(12))),
            signatureBase64 = encodeBase64(ByteArray(64)),
            viaTransports = setOf(TransportType.BLE_MESH)
        )

        val fragments = fragmenter.fragmentEnvelope(original)
        assertTrue("Expected multiple fragments", fragments.size > 5)

        // Feed corrupted fragment first — must be rejected
        val corrupted = fragments.first().copy(crc32 = fragments.first().crc32 + 1)
        assertNull(fragmenter.onFragmentReceived(corrupted))

        // Feed fragments in shuffled order
        val shuffled = fragments.shuffled()
        var reassembled: MeshEnvelope? = null
        for (i in 0 until shuffled.size - 1) {
            assertNull(fragmenter.onFragmentReceived(shuffled[i]))
        }
        reassembled = fragmenter.onFragmentReceived(shuffled.last())
        assertNotNull(reassembled)
        assertEquals(original, reassembled)
    }

    @Test
    fun `IRC command parser validates all required slash commands`() {
        assertEquals(IrcCommandParser.ParsedCommand.Help, IrcCommandParser.parse("/help"))
        assertEquals(
            IrcCommandParser.ParsedCommand.Join("#ops", "secret123"),
            IrcCommandParser.parse("/join #ops secret123")
        )
        assertEquals(
            IrcCommandParser.ParsedCommand.Leave("#ops"),
            IrcCommandParser.parse("/leave #ops")
        )
        assertEquals(
            IrcCommandParser.ParsedCommand.DirectMsg("relay_alpha", "meet at node 2"),
            IrcCommandParser.parse("/msg relay_alpha meet at node 2")
        )
        assertEquals(IrcCommandParser.ParsedCommand.Who, IrcCommandParser.parse("/who"))
        assertEquals(IrcCommandParser.ParsedCommand.Nick("zero_cool"), IrcCommandParser.parse("/nick zero_cool"))
        assertEquals(IrcCommandParser.ParsedCommand.Block("bad_peer"), IrcCommandParser.parse("/block bad_peer"))
        assertEquals(IrcCommandParser.ParsedCommand.Unblock("bad_peer"), IrcCommandParser.parse("/unblock bad_peer"))
        assertEquals(IrcCommandParser.ParsedCommand.Clear, IrcCommandParser.parse("/clear"))
        assertEquals(IrcCommandParser.ParsedCommand.Hug("alice"), IrcCommandParser.parse("/hug alice"))
        assertEquals(IrcCommandParser.ParsedCommand.Slap("bob"), IrcCommandParser.parse("/slap bob"))
    }

    @Test
    fun `GeohashCodec encodes and decodes coordinates within expected precision bounding box`() {
        val lat = 52.5200
        val lon = 13.4050
        val hash = GeohashCodec.encode(lat, lon, precision = 5)
        assertEquals(5, hash.length)
        assertTrue(GeohashCodec.isValidGeohash(hash))

        val bounds = GeohashCodec.decodeBounds(hash)
        assertTrue(lat in bounds.minLat..bounds.maxLat)
        assertTrue(lon in bounds.minLon..bounds.maxLon)
    }
}

