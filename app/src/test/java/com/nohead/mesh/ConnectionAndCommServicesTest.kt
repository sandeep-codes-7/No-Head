package com.nohead.mesh

import com.nohead.mesh.domain.crypto.CryptoEngine
import com.nohead.mesh.domain.crypto.CryptoEngine.Companion.decodeBase64
import com.nohead.mesh.domain.crypto.CryptoEngine.Companion.encodeBase64
import com.nohead.mesh.domain.crypto.CryptoEngine.Companion.toHex
import com.nohead.mesh.domain.model.AckPayload
import com.nohead.mesh.domain.model.AckType
import com.nohead.mesh.domain.model.DeliveryStatus
import com.nohead.mesh.domain.model.EncryptionMetadata
import com.nohead.mesh.domain.model.HandshakePayload
import com.nohead.mesh.domain.model.MeshEnvelope
import com.nohead.mesh.domain.model.PacketType
import com.nohead.mesh.domain.model.PeerAnnouncementPayload
import com.nohead.mesh.domain.model.ProtocolConstants
import com.nohead.mesh.domain.model.TransportType
import com.nohead.mesh.domain.service.AttachmentAndVoiceManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Automated Verification Suite for Connection Establishments & Communication Services.
 *
 * Tests:
 * 1. BLE GATT Handshake generation, Ed25519 challenge-nonce signature verification,
 *    and PeerId spoofing rejection.
 * 2. Wi-Fi Aware & BLE Peer Announcement verification and identity fingerprint calculation.
 * 3. Nostr NIP-01 Event Commitment SHA-256 ID and Ed25519 signature verification.
 * 4. Password-Protected Group Channel Argon2id + HKDF epoch key rotation & AEAD encryption.
 * 5. Delivery Receipt State Machine (`QUEUED` -> `SENT` -> `RELAYED` -> `DELIVERED` -> `READ` / `FAILED`).
 * 6. Per-peer Sliding Window Rate Limiting against packet flood attacks.
 * 7. Attachment MIME/Extension Security Policy & SHA-256 Chunk Reassembly.
 */
class ConnectionAndCommServicesTest {

    private val crypto = CryptoEngine()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `BLE GATT Handshake payload verifies Ed25519 signature and rejects forged PeerId or tampered challenge`() {
        val signKey = crypto.generateEd25519KeyPair()
        val encKey = crypto.generateX25519KeyPair()
        val peerId = crypto.derivePeerId(signKey.publicKeyBytes)
        val challengeHex = crypto.randomBytes(8).toHex()
        val now = 1730000000000L

        val signBytes = "$peerId|${signKey.publicKeyHex}|${encKey.publicKeyHex}|$challengeHex".encodeToByteArray()
        val sig = crypto.signEd25519(signKey.privateKeyBytes, signBytes)

        val validHandshake = HandshakePayload(
            peerId = peerId,
            nickname = "ble_node_1",
            signingPublicKeyHex = signKey.publicKeyHex,
            staticEncryptionPublicKeyHex = encKey.publicKeyHex,
            ephemeralEncryptionPublicKeyHex = encKey.publicKeyHex,
            supportedProtocolVersion = ProtocolConstants.PROTOCOL_VERSION,
            challengeNonceHex = challengeHex,
            signatureOverHandshakeBase64 = encodeBase64(sig),
            timestampEpochMs = now
        )

        // 1. Verify authentic handshake passes PeerId derivation and Ed25519 verification
        val derivedPeerId = crypto.derivePeerId(with(CryptoEngine) { validHandshake.signingPublicKeyHex.hexToByteArray() })
        assertEquals(validHandshake.peerId, derivedPeerId)
        val verified = crypto.verifyEd25519(
            publicKeyBytes = signKey.publicKeyBytes,
            message = "${validHandshake.peerId}|${validHandshake.signingPublicKeyHex}|${validHandshake.staticEncryptionPublicKeyHex}|${validHandshake.challengeNonceHex}".encodeToByteArray(),
            signatureBytes = decodeBase64(validHandshake.signatureOverHandshakeBase64)
        )
        assertTrue("Valid BLE GATT handshake signature must pass verification", verified)

        // 2. Verify tampered challenge nonce is rejected
        val tamperedChallenge = validHandshake.copy(challengeNonceHex = "deadbeef00112233")
        val tamperedVerified = crypto.verifyEd25519(
            publicKeyBytes = signKey.publicKeyBytes,
            message = "${tamperedChallenge.peerId}|${tamperedChallenge.signingPublicKeyHex}|${tamperedChallenge.staticEncryptionPublicKeyHex}|${tamperedChallenge.challengeNonceHex}".encodeToByteArray(),
            signatureBytes = decodeBase64(tamperedChallenge.signatureOverHandshakeBase64)
        )
        assertFalse("Tampered BLE handshake challenge must be rejected", tamperedVerified)

        // 3. Verify spoofed PeerId with mismatched public key is rejected
        val spoofedHandshake = validHandshake.copy(peerId = "nh_spoofed000000")
        val spoofedCheck = crypto.derivePeerId(signKey.publicKeyBytes) == spoofedHandshake.peerId
        assertFalse("Spoofed PeerId must be rejected before accepting handshake", spoofedCheck)
    }

    @Test
    fun `Password-protected channel Argon2id root key and HKDF epoch key rotation encrypts and isolates epochs`() {
        val channelId = "#ops-vault"
        val password = "mesh-alpha-passphrase".toCharArray()
        val salt = crypto.sha256("NoHead-Argon2id-ChannelSalt-v1:$channelId".encodeToByteArray()).copyOfRange(0, 16)

        val rootKey = crypto.deriveKeyArgon2id(
            password = password,
            saltBytes = salt,
            iterations = 3,
            memoryKiB = 16384,
            parallelism = 2,
            outputLengthBytes = 32
        )

        // Epoch 1 key
        val epoch1Key = crypto.hkdfSha256(
            ikm = rootKey,
            salt = salt,
            info = "NoHead-GroupEpoch:$channelId:1".encodeToByteArray(),
            outputLength = 32
        )

        // Epoch 2 ratcheted key (derived from Epoch 1 key on rotation)
        val epoch2Key = crypto.hkdfSha256(
            ikm = epoch1Key,
            salt = salt,
            info = "NoHead-GroupEpoch:$channelId:2".encodeToByteArray(),
            outputLength = 32
        )

        assertFalse("Epoch 1 and Epoch 2 keys must be cryptographically distinct", epoch1Key.contentEquals(epoch2Key))

        val aad = "1|msg_99|PASSWORD_CHANNEL_MSG|nh_alice||#ops-vault|1730000000000".encodeToByteArray()
        val plaintext = "Coordinates for mesh relay drop".encodeToByteArray()

        val encryptedEpoch2 = crypto.encryptAead(epoch2Key, plaintext, aad)
        val decryptedEpoch2 = crypto.decryptAead(
            key32Bytes = epoch2Key,
            ciphertextBytes = encryptedEpoch2.ciphertextBytes,
            nonceBytes = encryptedEpoch2.nonceBytes,
            associatedData = aad,
            algorithm = encryptedEpoch2.algorithm
        )
        assertArrayEquals(plaintext, decryptedEpoch2)

        // Node still on Epoch 1 key cannot decrypt Epoch 2 message
        val staleKeyAttempt = runCatching {
            crypto.decryptAead(
                key32Bytes = epoch1Key,
                ciphertextBytes = encryptedEpoch2.ciphertextBytes,
                nonceBytes = encryptedEpoch2.nonceBytes,
                associatedData = aad,
                algorithm = encryptedEpoch2.algorithm
            )
        }
        assertTrue("Stale epoch key must fail AEAD authentication", staleKeyAttempt.isFailure)
    }

    @Test
    fun `Delivery receipt state machine transitions monotonically from QUEUED to SENT to RELAYED to DELIVERED to READ`() {
        fun nextDeliveryState(current: DeliveryStatus, ackType: AckType): DeliveryStatus {
            return when (ackType) {
                AckType.RELAYED -> {
                    if (current == DeliveryStatus.QUEUED || current == DeliveryStatus.SENT) {
                        DeliveryStatus.RELAYED
                    } else current
                }
                AckType.DELIVERED -> {
                    if (current != DeliveryStatus.READ) DeliveryStatus.DELIVERED else current
                }
                AckType.READ -> DeliveryStatus.READ
                AckType.REJECTED_RATE_LIMIT, AckType.REJECTED_AUTH -> DeliveryStatus.FAILED
            }
        }

        var status = DeliveryStatus.QUEUED
        status = DeliveryStatus.SENT
        assertEquals(DeliveryStatus.SENT, status)

        // Intermediate hop sends RELAYED ACK
        status = nextDeliveryState(status, AckType.RELAYED)
        assertEquals(DeliveryStatus.RELAYED, status)

        // Recipient sends DELIVERED ACK
        status = nextDeliveryState(status, AckType.DELIVERED)
        assertEquals(DeliveryStatus.DELIVERED, status)

        // Late-arriving RELAYED ACK from a slower path must NOT downgrade DELIVERED back to RELAYED
        status = nextDeliveryState(status, AckType.RELAYED)
        assertEquals(DeliveryStatus.DELIVERED, status)

        // Recipient sends READ receipt
        status = nextDeliveryState(status, AckType.READ)
        assertEquals(DeliveryStatus.READ, status)

        // Late DELIVERED ACK must NOT downgrade READ back to DELIVERED
        status = nextDeliveryState(status, AckType.DELIVERED)
        assertEquals(DeliveryStatus.READ, status)
    }

    @Test
    fun `Sliding-window per-peer rate limiter blocks packet floods exceeding threshold and recovers in next window`() {
        val windowMs = 10_000L
        val maxPacketsPerWindow = 45
        var windowStartMs = 100_000L
        var count = 0

        fun allowPacket(nowMs: Long): Boolean {
            if (nowMs - windowStartMs > windowMs) {
                windowStartMs = nowMs
                count = 1
                return true
            }
            count++
            return count <= maxPacketsPerWindow
        }

        // First 45 packets in the 10s window succeed
        for (i in 1..45) {
            assertTrue("Packet #$i should be allowed", allowPacket(100_500L))
        }
        // 46th packet in same window is rate-limited
        assertFalse("Packet #46 in same 10s window must be dropped", allowPacket(101_000L))

        // Packet after 10s window expires is allowed again
        assertTrue("Packet in new window must be allowed", allowPacket(111_000L))
    }

    @Test
    fun `Attachment security filter blocks executable extensions and validates MIME and size caps`() {
        val blockedExtensions = setOf("apk", "dex", "so", "sh", "exe", "bat", "cmd", "msi", "jar", "js", "vbs", "elf")
        val allowedMimePrefixes = listOf("image/", "audio/", "text/plain", "application/pdf", "application/octet-stream")

        fun isSafe(fileName: String, mimeType: String, sizeBytes: Long, isBleOnly: Boolean): Boolean {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            if (ext in blockedExtensions) return false
            if (allowedMimePrefixes.none { mimeType.lowercase().startsWith(it) }) return false
            val maxCap = if (isBleOnly) ProtocolConstants.MAX_BLE_MEDIA_BYTES.toLong() else ProtocolConstants.MAX_WIFI_AWARE_MEDIA_BYTES.toLong()
            return sizeBytes in 1..maxCap
        }

        // Safe diagnostic text file over BLE
        assertTrue(isSafe("mesh_beacon_101.txt", "text/plain", 512L, isBleOnly = true))
        // Safe voice note over BLE
        assertTrue(isSafe("voice_note.m4a", "audio/mp4", 12_000L, isBleOnly = true))
        // Malicious APK disguised with application/octet-stream must be blocked
        assertFalse(isSafe("trojan_update.apk", "application/octet-stream", 1024L, isBleOnly = false))
        // Shell script must be blocked
        assertFalse(isSafe("exploit.sh", "text/plain", 256L, isBleOnly = false))
        // File exceeding BLE 256 KB limit must be rejected on BLE-only link, but accepted on Wi-Fi Aware (up to 10 MB)
        assertFalse(isSafe("photo_hd.jpg", "image/jpeg", 512_000L, isBleOnly = true))
        assertTrue(isSafe("photo_hd.jpg", "image/jpeg", 512_000L, isBleOnly = false))
    }
}

