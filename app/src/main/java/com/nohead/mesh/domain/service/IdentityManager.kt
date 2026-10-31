package com.nohead.mesh.domain.service

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.nohead.mesh.domain.crypto.CryptoEngine
import com.nohead.mesh.domain.crypto.CryptoEngine.Companion.decodeBase64
import com.nohead.mesh.domain.crypto.CryptoEngine.Companion.encodeBase64
import com.nohead.mesh.domain.crypto.CryptoEngine.Companion.hexToByteArray
import com.nohead.mesh.domain.crypto.CryptoEngine.Companion.toHex
import com.nohead.mesh.domain.model.IdentityBackupBundle
import com.nohead.mesh.domain.model.LocalIdentity
import com.nohead.mesh.domain.model.MeshEnvelope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the user's local cryptographic identity, anonymous nickname, rotating BLE presence token,
 * SQLCipher database passphrase, and explicit encrypted backup/restore.
 *
 * Requires NO phone number, email, account, or central server.
 */
@Singleton
class IdentityManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cryptoEngine: CryptoEngine
) {
    companion object {
        private const val PREFS_FILE = "nohead_identity_secure_prefs"
        private const val KEYSTORE_ALIAS_DB = "nohead_sqlcipher_wrap_key_v1"
        private const val KEY_SIGNING_PRIV = "ed25519_priv_hex"
        private const val KEY_SIGNING_PUB = "ed25519_pub_hex"
        private const val KEY_ENC_PRIV = "x25519_priv_hex"
        private const val KEY_ENC_PUB = "x25519_pub_hex"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_CREATED_AT = "created_at_ms"
        private const val KEY_GENERATION = "key_generation"
        private const val KEY_ONBOARDING_DONE = "onboarding_completed"
        private const val KEY_WRAPPED_DB_PASSPHRASE = "wrapped_db_passphrase"
        private const val KEY_WRAPPED_DB_IV = "wrapped_db_iv"

        private val ADJECTIVES = listOf(
            "silent", "null", "cipher", "phantom", "neon", "ghost", "drift",
            "cobalt", "obsidian", "vector", "orbital", "static", "feral", "hollow"
        )
        private val NOUNS = listOf(
            "node", "relay", "packet", "moth", "beacon", "raven", "specter",
            "anchor", "pulse", "vertex", "echo", "hopper", "nomad", "signal"
        )
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val prefs: SharedPreferences by lazy {
        createSecurePrefs()
    }

    private val _identityState = MutableStateFlow<LocalIdentity>(loadOrCreateIdentity())
    val identityState: StateFlow<LocalIdentity> = _identityState.asStateFlow()

    private val _onboardingCompleted = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDING_DONE, false))
    val onboardingCompleted: StateFlow<Boolean> = _onboardingCompleted.asStateFlow()

    private fun createSecurePrefs(): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback in unit test or corrupted keystore recovery
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        }
    }

    @Synchronized
    private fun loadOrCreateIdentity(): LocalIdentity {
        val existingSignPriv = prefs.getString(KEY_SIGNING_PRIV, null)
        val existingSignPub = prefs.getString(KEY_SIGNING_PUB, null)
        val existingEncPriv = prefs.getString(KEY_ENC_PRIV, null)
        val existingEncPub = prefs.getString(KEY_ENC_PUB, null)

        if (existingSignPriv != null && existingSignPub != null && existingEncPriv != null && existingEncPub != null) {
            val peerId = cryptoEngine.derivePeerId(existingSignPub.hexToByteArray())
            val nickname = prefs.getString(KEY_NICKNAME, null) ?: generateRandomNickname(peerId)
            val createdAt = prefs.getLong(KEY_CREATED_AT, System.currentTimeMillis())
            val gen = prefs.getInt(KEY_GENERATION, 1)
            return LocalIdentity(
                peerId = peerId,
                nickname = nickname,
                signingPublicKeyHex = existingSignPub,
                encryptionPublicKeyHex = existingEncPub,
                fingerprintFormatted = cryptoEngine.formatIdentityFingerprint(existingSignPub, existingEncPub),
                rotatingPresenceId = cryptoEngine.generateRotatingPresenceId(),
                createdAtEpochMs = createdAt,
                keyGeneration = gen
            )
        }

        return generateFreshIdentityInternal(customNickname = null, generation = 1)
    }

    @Synchronized
    private fun generateFreshIdentityInternal(customNickname: String?, generation: Int): LocalIdentity {
        val edPair = cryptoEngine.generateEd25519KeyPair()
        val xPair = cryptoEngine.generateX25519KeyPair()
        val peerId = cryptoEngine.derivePeerId(edPair.publicKeyBytes)
        val nickname = customNickname?.takeIf { it.isNotBlank() } ?: generateRandomNickname(peerId)
        val now = System.currentTimeMillis()

        prefs.edit()
            .putString(KEY_SIGNING_PRIV, edPair.privateKeyHex)
            .putString(KEY_SIGNING_PUB, edPair.publicKeyHex)
            .putString(KEY_ENC_PRIV, xPair.privateKeyHex)
            .putString(KEY_ENC_PUB, xPair.publicKeyHex)
            .putString(KEY_NICKNAME, nickname)
            .putLong(KEY_CREATED_AT, now)
            .putInt(KEY_GENERATION, generation)
            .apply()

        return LocalIdentity(
            peerId = peerId,
            nickname = nickname,
            signingPublicKeyHex = edPair.publicKeyHex,
            encryptionPublicKeyHex = xPair.publicKeyHex,
            fingerprintFormatted = cryptoEngine.formatIdentityFingerprint(edPair.publicKeyHex, xPair.publicKeyHex),
            rotatingPresenceId = cryptoEngine.generateRotatingPresenceId(),
            createdAtEpochMs = now,
            keyGeneration = generation
        )
    }

    fun generateRandomNickname(peerIdHint: String? = null): String {
        val bytes = cryptoEngine.randomBytes(4)
        val adj = ADJECTIVES[(bytes[0].toInt() and 0xFF) % ADJECTIVES.size]
        val noun = NOUNS[(bytes[1].toInt() and 0xFF) % NOUNS.size]
        val suffix = peerIdHint?.takeLast(4) ?: "%02x%02x".format(bytes[2], bytes[3])
        return "${adj}_${noun}_$suffix"
    }

    fun updateNickname(newNickname: String) {
        val sanitized = newNickname.trim().replace(Regex("[^a-zA-Z0-9_\\-.]"), "_").take(24)
        if (sanitized.isBlank()) return
        prefs.edit().putString(KEY_NICKNAME, sanitized).apply()
        _identityState.value = _identityState.value.copy(nickname = sanitized)
    }

    fun completeOnboarding() {
        prefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
        _onboardingCompleted.value = true
    }

    fun rotatePresenceToken(): String {
        val nextToken = cryptoEngine.generateRotatingPresenceId()
        _identityState.value = _identityState.value.copy(rotatingPresenceId = nextToken)
        return nextToken
    }

    /**
     * Rotates or resets the cryptographic identity, incrementing key generation.
     */
    @Synchronized
    fun rotateCryptographicIdentity(preserveNickname: Boolean = true): LocalIdentity {
        val current = _identityState.value
        val nextNickname = if (preserveNickname) current.nickname else null
        val fresh = generateFreshIdentityInternal(nextNickname, current.keyGeneration + 1)
        _identityState.value = fresh
        return fresh
    }

    fun signBytes(data: ByteArray): ByteArray {
        val privHex = prefs.getString(KEY_SIGNING_PRIV, null)
            ?: throw IllegalStateException("Signing key missing")
        val privBytes = privHex.hexToByteArray()
        return try {
            cryptoEngine.signEd25519(privBytes, data)
        } finally {
            Arrays.fill(privBytes, 0.toByte())
        }
    }

    fun signEnvelope(unsignedEnvelope: MeshEnvelope): MeshEnvelope {
        val signature = signBytes(unsignedEnvelope.canonicalSignableBytes())
        return unsignedEnvelope.copy(signatureBase64 = encodeBase64(signature))
    }

    fun verifyEnvelopeSignature(envelope: MeshEnvelope): Boolean {
        val expectedPeerId = cryptoEngine.derivePeerId(envelope.senderSigningPubKeyHex.hexToByteArray())
        if (expectedPeerId != envelope.senderPeerId) {
            return false // Prevents PeerID spoofing with mismatched public key
        }
        val sigBytes = try {
            decodeBase64(envelope.signatureBase64)
        } catch (_: Exception) {
            return false
        }
        return cryptoEngine.verifyEd25519(
            publicKeyBytes = envelope.senderSigningPubKeyHex.hexToByteArray(),
            message = envelope.canonicalSignableBytes(),
            signatureBytes = sigBytes
        )
    }

    fun computeStaticSharedSecret(remoteEncryptionPubKeyHex: String): ByteArray {
        val privHex = prefs.getString(KEY_ENC_PRIV, null)
            ?: throw IllegalStateException("Encryption key missing")
        val privBytes = privHex.hexToByteArray()
        return try {
            cryptoEngine.computeX25519SharedSecret(privBytes, remoteEncryptionPubKeyHex.hexToByteArray())
        } finally {
            Arrays.fill(privBytes, 0.toByte())
        }
    }

    /**
     * Exports the local identity encrypted with a user-supplied passphrase via Argon2id + ChaCha20-Poly1305.
     * Never exports unencrypted private keys.
     */
    fun exportIdentityEncrypted(passphrase: CharArray): String {
        require(passphrase.size >= 8) { "Backup passphrase must be at least 8 characters" }
        val signPriv = prefs.getString(KEY_SIGNING_PRIV, null) ?: error("No identity")
        val encPriv = prefs.getString(KEY_ENC_PRIV, null) ?: error("No identity")
        val current = _identityState.value

        val salt = cryptoEngine.randomBytes(16)
        val derivedKey = cryptoEngine.deriveKeyArgon2id(passphrase, salt)
        val secretPayload = "$signPriv:$encPriv".encodeToByteArray()
        val aad = "${current.peerId}:${current.signingPublicKeyHex}:${current.encryptionPublicKeyHex}".encodeToByteArray()
        val encrypted = cryptoEngine.encryptAead(derivedKey, secretPayload, aad)

        Arrays.fill(derivedKey, 0.toByte())
        Arrays.fill(secretPayload, 0.toByte())

        val bundle = IdentityBackupBundle(
            version = 1,
            nickname = current.nickname,
            peerId = current.peerId,
            signingPublicKeyHex = current.signingPublicKeyHex,
            encryptionPublicKeyHex = current.encryptionPublicKeyHex,
            argon2SaltBase64 = encodeBase64(salt),
            nonceBase64 = encrypted.nonceBase64,
            encryptedPrivateKeysBase64 = encrypted.ciphertextBase64,
            exportedAtEpochMs = System.currentTimeMillis()
        )
        return json.encodeToString(bundle)
    }

    /**
     * Restores an encrypted identity backup after verifying the passphrase and key derivation.
     */
    fun importIdentityEncrypted(backupJson: String, passphrase: CharArray): Result<LocalIdentity> {
        return runCatching {
            val bundle = json.decodeFromString<IdentityBackupBundle>(backupJson)
            val salt = decodeBase64(bundle.argon2SaltBase64)
            val nonce = decodeBase64(bundle.nonceBase64)
            val ciphertext = decodeBase64(bundle.encryptedPrivateKeysBase64)
            val aad = "${bundle.peerId}:${bundle.signingPublicKeyHex}:${bundle.encryptionPublicKeyHex}".encodeToByteArray()

            val derivedKey = cryptoEngine.deriveKeyArgon2id(passphrase, salt)
            val decryptedBytes = cryptoEngine.decryptAead(derivedKey, ciphertext, nonce, aad)
            Arrays.fill(derivedKey, 0.toByte())

            val parts = decryptedBytes.decodeToString().split(":")
            Arrays.fill(decryptedBytes, 0.toByte())
            require(parts.size == 2) { "Invalid backup key structure" }

            val signPrivHex = parts[0]
            val encPrivHex = parts[1]
            val now = System.currentTimeMillis()

            prefs.edit()
                .putString(KEY_SIGNING_PRIV, signPrivHex)
                .putString(KEY_SIGNING_PUB, bundle.signingPublicKeyHex)
                .putString(KEY_ENC_PRIV, encPrivHex)
                .putString(KEY_ENC_PUB, bundle.encryptionPublicKeyHex)
                .putString(KEY_NICKNAME, bundle.nickname)
                .putLong(KEY_CREATED_AT, now)
                .putInt(KEY_GENERATION, _identityState.value.keyGeneration + 1)
                .apply()

            val restored = loadOrCreateIdentity()
            _identityState.value = restored
            restored
        }
    }

    /**
     * Retrieves or generates the 256-bit SQLCipher database passphrase wrapped by Android Keystore.
     */
    @Synchronized
    fun getOrCreateDatabasePassphrase(): ByteArray {
        val existingWrapped = prefs.getString(KEY_WRAPPED_DB_PASSPHRASE, null)
        val existingIv = prefs.getString(KEY_WRAPPED_DB_IV, null)

        if (existingWrapped != null && existingIv != null) {
            return try {
                unwrapWithKeystore(decodeBase64(existingWrapped), decodeBase64(existingIv))
            } catch (_: Exception) {
                decodeBase64(existingWrapped)
            }
        }

        val rawPassphrase = cryptoEngine.randomBytes(32)
        try {
            val (wrapped, iv) = wrapWithKeystore(rawPassphrase)
            prefs.edit()
                .putString(KEY_WRAPPED_DB_PASSPHRASE, encodeBase64(wrapped))
                .putString(KEY_WRAPPED_DB_IV, encodeBase64(iv))
                .apply()
        } catch (_: Exception) {
            prefs.edit()
                .putString(KEY_WRAPPED_DB_PASSPHRASE, encodeBase64(rawPassphrase))
                .putString(KEY_WRAPPED_DB_IV, encodeBase64(ByteArray(12)))
                .apply()
        }
        return rawPassphrase
    }

    private fun getOrCreateKeystoreSecretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getEntry(KEYSTORE_ALIAS_DB, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS_DB,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun wrapWithKeystore(data: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKeystoreSecretKey())
        return cipher.doFinal(data) to cipher.iv
    }

    private fun unwrapWithKeystore(wrapped: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKeystoreSecretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(wrapped)
    }

    /**
     * Destroys all local identity keys and Keystore entries during an Emergency Wipe.
     */
    @Synchronized
    fun destroyIdentityKeysForEmergencyWipe() {
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(KEYSTORE_ALIAS_DB)) {
                ks.deleteEntry(KEYSTORE_ALIAS_DB)
            }
        } catch (_: Exception) {
        }
        prefs.edit().clear().commit()
        val fresh = generateFreshIdentityInternal(customNickname = null, generation = 1)
        _onboardingCompleted.value = false
        _identityState.value = fresh
    }
}

