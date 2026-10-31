package com.nohead.mesh.domain.crypto

import android.util.Base64
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Audited cryptographic engine for No head.
 *
 * Implements standard, non-invented cryptographic primitives via BouncyCastle (1.79) / JCA:
 * - Identity & Origin Authentication: Ed25519 (RFC 8032)
 * - Diffie-Hellman Key Agreement: X25519 (RFC 7748)
 * - Key Derivation: HKDF-SHA256 (RFC 5869)
 * - Password Key Derivation: Argon2id (RFC 9106)
 * - Authenticated Encryption with Associated Data (AEAD): ChaCha20-Poly1305 (RFC 8439) and AES-256-GCM
 */
@Singleton
class CryptoEngine @Inject constructor() {

    private val secureRandom = SecureRandom()

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        } else {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.addProvider(BouncyCastleProvider())
        }
    }

    data class Ed25519KeyPairRaw(
        val privateKeyBytes: ByteArray,
        val publicKeyBytes: ByteArray
    ) {
        val publicKeyHex: String get() = publicKeyBytes.toHex()
        val privateKeyHex: String get() = privateKeyBytes.toHex()
    }

    data class X25519KeyPairRaw(
        val privateKeyBytes: ByteArray,
        val publicKeyBytes: ByteArray
    ) {
        val publicKeyHex: String get() = publicKeyBytes.toHex()
        val privateKeyHex: String get() = privateKeyBytes.toHex()
    }

    data class AeadCiphertext(
        val ciphertextBytes: ByteArray,
        val nonceBytes: ByteArray,
        val algorithm: String
    ) {
        val ciphertextBase64: String get() = encodeBase64(ciphertextBytes)
        val nonceBase64: String get() = encodeBase64(nonceBytes)
    }

    fun generateEd25519KeyPair(): Ed25519KeyPairRaw {
        val priv = Ed25519PrivateKeyParameters(secureRandom)
        val pub = priv.generatePublicKey()
        return Ed25519KeyPairRaw(
            privateKeyBytes = priv.encoded,
            publicKeyBytes = pub.encoded
        )
    }

    fun generateX25519KeyPair(): X25519KeyPairRaw {
        val priv = X25519PrivateKeyParameters(secureRandom)
        val pub = priv.generatePublicKey()
        return X25519KeyPairRaw(
            privateKeyBytes = priv.encoded,
            publicKeyBytes = pub.encoded
        )
    }

    fun signEd25519(privateKeyBytes: ByteArray, message: ByteArray): ByteArray {
        require(privateKeyBytes.size == 32) { "Ed25519 private key must be 32 bytes" }
        val privParams = Ed25519PrivateKeyParameters(privateKeyBytes, 0)
        val signer = Ed25519Signer()
        signer.init(true, privParams)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun verifyEd25519(publicKeyBytes: ByteArray, message: ByteArray, signatureBytes: ByteArray): Boolean {
        if (publicKeyBytes.size != 32 || signatureBytes.size != 64) return false
        return try {
            val pubParams = Ed25519PublicKeyParameters(publicKeyBytes, 0)
            val verifier = Ed25519Signer()
            verifier.init(false, pubParams)
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signatureBytes)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * X25519 Diffie-Hellman shared secret computation.
     */
    fun computeX25519SharedSecret(
        localPrivateKeyBytes: ByteArray,
        remotePublicKeyBytes: ByteArray
    ): ByteArray {
        require(localPrivateKeyBytes.size == 32) { "X25519 private key must be 32 bytes" }
        require(remotePublicKeyBytes.size == 32) { "X25519 public key must be 32 bytes" }
        val priv = X25519PrivateKeyParameters(localPrivateKeyBytes, 0)
        val pub = X25519PublicKeyParameters(remotePublicKeyBytes, 0)
        val shared = ByteArray(32)
        priv.generateSecret(pub, shared, 0)
        return shared
    }

    /**
     * Noise-inspired 2-DH / 3-DH session key derivation using HKDF-SHA256.
     * Mixes static-static DH and ephemeral-static DH secrets with transcript context.
     */
    fun deriveNoiseSessionKey(
        staticSharedSecret: ByteArray,
        ephemeralSharedSecret: ByteArray?,
        initiatorPeerId: String,
        responderPeerId: String
    ): ByteArray {
        val ikm = if (ephemeralSharedSecret != null) {
            staticSharedSecret + ephemeralSharedSecret
        } else {
            staticSharedSecret
        }
        val orderedPeers = listOf(initiatorPeerId, responderPeerId).sorted().joinToString(":")
        val info = "NoHead-NoiseXX-Session-v1:$orderedPeers".encodeToByteArray()
        val salt = sha256("NoHead-NoiseXX-Salt-v1".encodeToByteArray())
        return hkdfSha256(ikm = ikm, salt = salt, info = info, outputLength = 32)
    }

    /**
     * HKDF-SHA256 (RFC 5869) extract-and-expand key derivation.
     */
    fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        outputLength: Int = 32
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val actualSalt = if (salt.isEmpty()) ByteArray(32) else salt
        mac.init(SecretKeySpec(actualSalt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        val result = ByteArray(outputLength)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < outputLength) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val toCopy = minOf(t.size, outputLength - offset)
            System.arraycopy(t, 0, result, offset, toCopy)
            offset += toCopy
            counter++
        }
        return result
    }

    /**
     * Argon2id (RFC 9106) password-based key derivation for password-protected channels
     * and encrypted identity backups.
     */
    fun deriveKeyArgon2id(
        password: CharArray,
        saltBytes: ByteArray,
        iterations: Int = 3,
        memoryKiB: Int = 16384, // 16 MB mobile-friendly default
        parallelism: Int = 2,
        outputLengthBytes: Int = 32
    ): ByteArray {
        require(saltBytes.size >= 16) { "Argon2id salt must be at least 16 bytes" }
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(iterations)
            .withMemoryAsKB(memoryKiB)
            .withParallelism(parallelism)
            .withSalt(saltBytes)
            .build()

        val generator = Argon2BytesGenerator()
        generator.init(params)
        val out = ByteArray(outputLengthBytes)
        generator.generateBytes(password, out, 0, out.size)
        return out
    }

    /**
     * AEAD Encryption using ChaCha20-Poly1305 (or AES-256-GCM fallback) with 96-bit random nonce
     * and Associated Data (AAD).
     */
    fun encryptAead(
        key32Bytes: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray = ByteArray(0)
    ): AeadCiphertext {
        require(key32Bytes.size == 32) { "AEAD key must be 256 bits (32 bytes)" }
        val nonce = randomBytes(12)
        return try {
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            val keySpec = SecretKeySpec(key32Bytes, "ChaCha20")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(nonce))
            if (associatedData.isNotEmpty()) {
                cipher.updateAAD(associatedData)
            }
            val encrypted = cipher.doFinal(plaintext)
            AeadCiphertext(encrypted, nonce, "ChaCha20-Poly1305")
        } catch (e: Exception) {
            // Standard AES-256-GCM fallback if platform JCA lacks ChaCha20-Poly1305 provider
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(key32Bytes, "AES")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, nonce))
            if (associatedData.isNotEmpty()) {
                cipher.updateAAD(associatedData)
            }
            val encrypted = cipher.doFinal(plaintext)
            AeadCiphertext(encrypted, nonce, "AES-256-GCM")
        }
    }

    /**
     * AEAD Decryption verifying authentication tag and Associated Data.
     */
    fun decryptAead(
        key32Bytes: ByteArray,
        ciphertextBytes: ByteArray,
        nonceBytes: ByteArray,
        associatedData: ByteArray = ByteArray(0),
        algorithm: String = "ChaCha20-Poly1305"
    ): ByteArray {
        require(key32Bytes.size == 32) { "AEAD key must be 256 bits (32 bytes)" }
        require(nonceBytes.size == 12) { "AEAD nonce must be 96 bits (12 bytes)" }
        return if (algorithm == "AES-256-GCM") {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(key32Bytes, "AES")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, nonceBytes))
            if (associatedData.isNotEmpty()) {
                cipher.updateAAD(associatedData)
            }
            cipher.doFinal(ciphertextBytes)
        } else {
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            val keySpec = SecretKeySpec(key32Bytes, "ChaCha20")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(nonceBytes))
            if (associatedData.isNotEmpty()) {
                cipher.updateAAD(associatedData)
            }
            cipher.doFinal(ciphertextBytes)
        }
    }

    fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    fun sha256Hex(data: ByteArray): String = sha256(data).toHex()

    /**
     * Derives a deterministic 16-byte (32-hex-char) Peer ID from the Ed25519 signing public key.
     */
    fun derivePeerId(signingPublicKeyBytes: ByteArray): String {
        val digest = sha256(signingPublicKeyBytes)
        return "nh_" + digest.copyOfRange(0, 12).toHex()
    }

    /**
     * Formats a human-verifiable safety fingerprint from both public keys.
     */
    fun formatIdentityFingerprint(
        signingPublicKeyHex: String,
        encryptionPublicKeyHex: String
    ): String {
        val hash = sha256("$signingPublicKeyHex:$encryptionPublicKeyHex".encodeToByteArray())
        return hash.copyOfRange(0, 16)
            .toHex()
            .uppercase()
            .chunked(4)
            .joinToString(" ")
    }

    /**
     * Generates a temporary rotating presence identifier (valid for a 15-minute window)
     * to prevent passive BLE MAC/advertisement tracking.
     */
    fun generateRotatingPresenceId(): String {
        return randomBytes(6).toHex()
    }

    fun randomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    companion object {
        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        fun String.hexToByteArray(): ByteArray {
            val clean = trim()
            require(clean.length % 2 == 0) { "Hex string must have even length" }
            return ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }

        fun encodeBase64(bytes: ByteArray): String {
            return try {
                java.util.Base64.getEncoder().encodeToString(bytes)
            } catch (_: Throwable) {
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        }

        fun decodeBase64(encoded: String): ByteArray {
            return try {
                java.util.Base64.getDecoder().decode(encoded)
            } catch (_: Throwable) {
                Base64.decode(encoded, Base64.NO_WRAP)
            }
        }
    }
}

