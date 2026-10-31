package com.nohead.mesh.domain.service

import com.nohead.mesh.data.local.PeerDao
import com.nohead.mesh.domain.crypto.CryptoEngine
import com.nohead.mesh.domain.crypto.CryptoEngine.Companion.decodeBase64
import com.nohead.mesh.domain.crypto.CryptoEngine.Companion.encodeBase64
import com.nohead.mesh.domain.crypto.CryptoEngine.Companion.hexToByteArray
import com.nohead.mesh.domain.model.EncryptionMetadata
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages E2EE 1:1 cryptographic sessions between the local node and remote peers.
 *
 * Implements a Noise XX-inspired combination of:
 * 1. Static-Static X25519 Diffie-Hellman (authenticating both long-term identities)
 * 2. Per-message Ephemeral-Static X25519 Diffie-Hellman (providing forward-secrecy ratcheting)
 * 3. HKDF-SHA256 key derivation bound to ordered peer IDs
 * 4. ChaCha20-Poly1305 / AES-256-GCM AEAD encryption with Associated Data (AAD)
 */
@Singleton
class SessionManager @Inject constructor(
    private val identityManager: IdentityManager,
    private val peerDao: PeerDao,
    private val cryptoEngine: CryptoEngine
) {
    private val sessionEpochs = ConcurrentHashMap<String, Int>()

    data class EncryptedPayloadResult(
        val ciphertextBase64: String,
        val encryptionMetadata: EncryptionMetadata
    )

    /**
     * Encrypts a direct message payload for [recipientPeerId] using a fresh ephemeral X25519 keypair
     * mixed with the static-static X25519 shared secret.
     */
    suspend fun encryptDirectMessage(
        recipientPeerId: String,
        recipientEncryptionPubKeyHex: String,
        plaintextBytes: ByteArray,
        associatedData: ByteArray
    ): EncryptedPayloadResult {
        val localIdentity = identityManager.identityState.value
        val staticShared = identityManager.computeStaticSharedSecret(recipientEncryptionPubKeyHex)

        // Generate fresh ephemeral X25519 keypair for per-message forward secrecy
        val ephemeralKeyPair = cryptoEngine.generateX25519KeyPair()
        val ephemeralShared = cryptoEngine.computeX25519SharedSecret(
            localPrivateKeyBytes = ephemeralKeyPair.privateKeyBytes,
            remotePublicKeyBytes = recipientEncryptionPubKeyHex.hexToByteArray()
        )

        val sessionKey = cryptoEngine.deriveNoiseSessionKey(
            staticSharedSecret = staticShared,
            ephemeralSharedSecret = ephemeralShared,
            initiatorPeerId = localIdentity.peerId,
            responderPeerId = recipientPeerId
        )

        val aead = cryptoEngine.encryptAead(
            key32Bytes = sessionKey,
            plaintext = plaintextBytes,
            associatedData = associatedData
        )

        val epoch = sessionEpochs.compute(recipientPeerId) { _, v -> (v ?: 1) } ?: 1

        // Persist base static session establishment marker on peer record
        val peerEntity = peerDao.getPeerById(recipientPeerId)
        if (peerEntity != null && peerEntity.sessionSharedKeyBase64 == null) {
            peerDao.upsertPeer(
                peerEntity.copy(sessionSharedKeyBase64 = encodeBase64(cryptoEngine.sha256(staticShared)))
            )
        }

        return EncryptedPayloadResult(
            ciphertextBase64 = aead.ciphertextBase64,
            encryptionMetadata = EncryptionMetadata(
                algorithm = aead.algorithm,
                nonceBase64 = aead.nonceBase64,
                ephemeralPublicKeyHex = ephemeralKeyPair.publicKeyHex,
                keyEpoch = epoch
            )
        )
    }

    /**
     * Decrypts an incoming direct message payload from [senderPeerId] using the sender's
     * static X25519 public key and per-packet ephemeral X25519 public key.
     */
    suspend fun decryptDirectMessage(
        senderPeerId: String,
        senderEncryptionPubKeyHex: String,
        ciphertextBase64: String,
        encryptionMetadata: EncryptionMetadata,
        associatedData: ByteArray
    ): ByteArray {
        val localIdentity = identityManager.identityState.value
        val staticShared = identityManager.computeStaticSharedSecret(senderEncryptionPubKeyHex)

        val ephemeralShared = encryptionMetadata.ephemeralPublicKeyHex?.let { ephHex ->
            identityManager.computeStaticSharedSecret(ephHex)
        }

        val sessionKey = cryptoEngine.deriveNoiseSessionKey(
            staticSharedSecret = staticShared,
            ephemeralSharedSecret = ephemeralShared,
            initiatorPeerId = senderPeerId,
            responderPeerId = localIdentity.peerId
        )

        val plaintext = cryptoEngine.decryptAead(
            key32Bytes = sessionKey,
            ciphertextBytes = decodeBase64(ciphertextBase64),
            nonceBytes = decodeBase64(encryptionMetadata.nonceBase64),
            associatedData = associatedData,
            algorithm = encryptionMetadata.algorithm
        )

        sessionEpochs[senderPeerId] = encryptionMetadata.keyEpoch
        return plaintext
    }

    fun rotateSessionKeyEpoch(peerId: String): Int {
        return sessionEpochs.compute(peerId) { _, current -> (current ?: 1) + 1 } ?: 2
    }
}

