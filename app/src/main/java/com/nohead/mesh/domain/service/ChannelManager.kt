package com.nohead.mesh.domain.service

import com.nohead.mesh.data.local.ChannelDao
import com.nohead.mesh.data.local.ChannelEntity
import com.nohead.mesh.domain.crypto.CryptoEngine
import com.nohead.mesh.domain.crypto.CryptoEngine.Companion.decodeBase64
import com.nohead.mesh.domain.crypto.CryptoEngine.Companion.encodeBase64
import com.nohead.mesh.domain.model.ChannelType
import com.nohead.mesh.domain.model.ChatChannel
import com.nohead.mesh.domain.model.EncryptionMetadata
import com.nohead.mesh.domain.model.ProtocolConstants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages public mesh channels, Argon2id password-protected group channels, geohash rooms,
 * and IRC-style slash command parsing & autocomplete.
 */
@Singleton
class ChannelManager @Inject constructor(
    private val channelDao: ChannelDao,
    private val identityManager: IdentityManager,
    private val cryptoEngine: CryptoEngine
) {
    private val _activeChannelId = MutableStateFlow(ProtocolConstants.DEFAULT_CHANNEL_ID)
    val activeChannelId: StateFlow<String> = _activeChannelId.asStateFlow()

    val channels: Flow<List<ChatChannel>> = channelDao.observeAllChannels().map { list ->
        list.map { it.toDomain() }
    }

    suspend fun ensureDefaultChannelsExist() {
        val nearby = channelDao.getChannelById(ProtocolConstants.DEFAULT_CHANNEL_ID)
        if (nearby == null) {
            channelDao.upsertChannel(
                ChannelEntity(
                    channelId = ProtocolConstants.DEFAULT_CHANNEL_ID,
                    name = "#nearby-mesh",
                    topic = "Proximity BLE & Wi-Fi Aware multi-hop mesh channel (no internet required)",
                    channelType = ChannelType.PUBLIC_MESH,
                    geohash = null,
                    isJoined = true,
                    isMuted = false,
                    ownerPeerId = null,
                    keyEpoch = 1,
                    passwordSaltBase64 = null,
                    derivedKeyBase64 = null,
                    memberPeerIdsCsv = identityManager.identityState.value.peerId,
                    unreadCount = 0,
                    createdAtEpochMs = System.currentTimeMillis()
                )
            )
        }

        val global = channelDao.getChannelById(ProtocolConstants.GLOBAL_NOSTR_CHANNEL_ID)
        if (global == null) {
            channelDao.upsertChannel(
                ChannelEntity(
                    channelId = ProtocolConstants.GLOBAL_NOSTR_CHANNEL_ID,
                    name = "#global-nostr",
                    topic = "Global public community via optional Nostr relays (Internet visible when relays enabled)",
                    channelType = ChannelType.GLOBAL_NOSTR,
                    geohash = null,
                    isJoined = true,
                    isMuted = false,
                    ownerPeerId = null,
                    keyEpoch = 1,
                    passwordSaltBase64 = null,
                    derivedKeyBase64 = null,
                    memberPeerIdsCsv = identityManager.identityState.value.peerId,
                    unreadCount = 0,
                    createdAtEpochMs = System.currentTimeMillis() + 1
                )
            )
        }
    }

    fun selectActiveChannel(channelId: String) {
        _activeChannelId.value = channelId
    }

    suspend fun joinOrCreatePublicChannel(rawName: String, topic: String = "Public mesh topic channel"): ChatChannel {
        val normalized = normalizeChannelName(rawName)
        val existing = channelDao.getChannelById(normalized)
        val localPeerId = identityManager.identityState.value.peerId
        if (existing != null) {
            val members = (existing.memberPeerIdsCsv.split(",").filter { it.isNotBlank() } + localPeerId).toSet()
            val updated = existing.copy(
                isJoined = true,
                memberPeerIdsCsv = members.joinToString(",")
            )
            channelDao.upsertChannel(updated)
            _activeChannelId.value = normalized
            return updated.toDomain()
        }

        val entity = ChannelEntity(
            channelId = normalized,
            name = normalized,
            topic = topic,
            channelType = ChannelType.PUBLIC_MESH,
            geohash = null,
            isJoined = true,
            isMuted = false,
            ownerPeerId = localPeerId,
            keyEpoch = 1,
            passwordSaltBase64 = null,
            derivedKeyBase64 = null,
            memberPeerIdsCsv = localPeerId,
            unreadCount = 0,
            createdAtEpochMs = System.currentTimeMillis()
        )
        channelDao.upsertChannel(entity)
        _activeChannelId.value = normalized
        return entity.toDomain()
    }

    /**
     * Creates or joins a password-protected channel using Argon2id key derivation.
     * Never uses a hardcoded shared encryption key.
     */
    suspend fun joinOrCreatePasswordChannel(
        rawName: String,
        password: CharArray,
        topic: String = "Password-protected encrypted mesh group",
        providedSaltBase64: String? = null
    ): ChatChannel {
        require(password.isNotEmpty()) { "Channel password cannot be empty" }
        val normalized = normalizeChannelName(rawName)
        val existing = channelDao.getChannelById(normalized)
        val saltBytes = when {
            providedSaltBase64 != null -> decodeBase64(providedSaltBase64)
            existing?.passwordSaltBase64 != null -> decodeBase64(existing.passwordSaltBase64)
            else -> {
                // Deterministic 16-byte channel-bound salt so peers joining with the same channel name
                // and password derive the same base root key before epoch ratcheting.
                cryptoEngine.sha256("NoHead-Argon2id-ChannelSalt-v1:$normalized".encodeToByteArray()).copyOfRange(0, 16)
            }
        }

        val derivedRootKey = cryptoEngine.deriveKeyArgon2id(
            password = password,
            saltBytes = saltBytes,
            iterations = 3,
            memoryKiB = 16384,
            parallelism = 2,
            outputLengthBytes = 32
        )
        val epoch = existing?.keyEpoch ?: 1
        val epochKey = cryptoEngine.hkdfSha256(
            ikm = derivedRootKey,
            salt = saltBytes,
            info = "NoHead-GroupEpoch:$normalized:$epoch".encodeToByteArray(),
            outputLength = 32
        )

        val localPeerId = identityManager.identityState.value.peerId
        val members = ((existing?.memberPeerIdsCsv?.split(",")?.filter { it.isNotBlank() } ?: emptyList()) + localPeerId).toSet()

        val entity = ChannelEntity(
            channelId = normalized,
            name = normalized,
            topic = topic,
            channelType = ChannelType.PASSWORD_PROTECTED,
            geohash = null,
            isJoined = true,
            isMuted = existing?.isMuted ?: false,
            ownerPeerId = existing?.ownerPeerId ?: localPeerId,
            keyEpoch = epoch,
            passwordSaltBase64 = encodeBase64(saltBytes),
            derivedKeyBase64 = encodeBase64(epochKey),
            memberPeerIdsCsv = members.joinToString(","),
            unreadCount = 0,
            createdAtEpochMs = existing?.createdAtEpochMs ?: System.currentTimeMillis()
        )
        channelDao.upsertChannel(entity)
        _activeChannelId.value = normalized
        return entity.toDomain()
    }

    /**
     * Rotates the group key epoch for a password-protected channel (e.g. after member removal).
     */
    suspend fun rotatePasswordChannelKeyEpoch(channelId: String): Boolean {
        val existing = channelDao.getChannelById(channelId) ?: return false
        val currentKeyB64 = existing.derivedKeyBase64 ?: return false
        val saltB64 = existing.passwordSaltBase64 ?: return false
        val newEpoch = existing.keyEpoch + 1
        val nextKey = cryptoEngine.hkdfSha256(
            ikm = decodeBase64(currentKeyB64),
            salt = decodeBase64(saltB64),
            info = "NoHead-GroupEpoch:$channelId:$newEpoch".encodeToByteArray(),
            outputLength = 32
        )
        channelDao.upsertChannel(
            existing.copy(
                keyEpoch = newEpoch,
                derivedKeyBase64 = encodeBase64(nextKey)
            )
        )
        return true
    }

    suspend fun removeMemberFromChannel(channelId: String, targetPeerId: String): Boolean {
        val existing = channelDao.getChannelById(channelId) ?: return false
        val updatedMembers = existing.memberPeerIdsCsv.split(",")
            .filter { it.isNotBlank() && it != targetPeerId }
            .toSet()
        channelDao.upsertChannel(existing.copy(memberPeerIdsCsv = updatedMembers.joinToString(",")))
        if (existing.channelType == ChannelType.PASSWORD_PROTECTED) {
            rotatePasswordChannelKeyEpoch(channelId)
        }
        return true
    }

    suspend fun joinGeohashRoom(geohash: String, regionLabel: String): ChatChannel {
        val cleanHash = geohash.trim().lowercase()
        val channelId = "#geo-$cleanHash"
        val localPeerId = identityManager.identityState.value.peerId
        val existing = channelDao.getChannelById(channelId)
        val entity = ChannelEntity(
            channelId = channelId,
            name = channelId,
            topic = "Coarse Geohash Room [$cleanHash] ($regionLabel) — Exact GPS never shared",
            channelType = ChannelType.GEOHASH_ROOM,
            geohash = cleanHash,
            isJoined = true,
            isMuted = existing?.isMuted ?: false,
            ownerPeerId = null,
            keyEpoch = 1,
            passwordSaltBase64 = null,
            derivedKeyBase64 = null,
            memberPeerIdsCsv = localPeerId,
            unreadCount = 0,
            createdAtEpochMs = existing?.createdAtEpochMs ?: System.currentTimeMillis()
        )
        channelDao.upsertChannel(entity)
        _activeChannelId.value = channelId
        return entity.toDomain()
    }

    suspend fun leaveChannel(channelId: String) {
        if (channelId == ProtocolConstants.DEFAULT_CHANNEL_ID) return
        channelDao.setJoined(channelId, false)
        if (_activeChannelId.value == channelId) {
            _activeChannelId.value = ProtocolConstants.DEFAULT_CHANNEL_ID
        }
    }

    suspend fun toggleMuteChannel(channelId: String): Boolean {
        val existing = channelDao.getChannelById(channelId) ?: return false
        val next = !existing.isMuted
        channelDao.setMuted(channelId, next)
        return next
    }

    suspend fun encryptForPasswordChannel(
        channelId: String,
        plaintext: ByteArray,
        associatedData: ByteArray
    ): Pair<String, EncryptionMetadata>? {
        val entity = channelDao.getChannelById(channelId) ?: return null
        val keyBase64 = entity.derivedKeyBase64 ?: return null
        val keyBytes = decodeBase64(keyBase64)
        val aead = cryptoEngine.encryptAead(keyBytes, plaintext, associatedData)
        return aead.ciphertextBase64 to EncryptionMetadata(
            algorithm = aead.algorithm,
            nonceBase64 = aead.nonceBase64,
            keyEpoch = entity.keyEpoch,
            argon2SaltBase64 = entity.passwordSaltBase64
        )
    }

    suspend fun decryptForPasswordChannel(
        channelId: String,
        ciphertextBase64: String,
        metadata: EncryptionMetadata,
        associatedData: ByteArray
    ): ByteArray? {
        val entity = channelDao.getChannelById(channelId) ?: return null
        val keyBase64 = entity.derivedKeyBase64 ?: return null
        return runCatching {
            cryptoEngine.decryptAead(
                key32Bytes = decodeBase64(keyBase64),
                ciphertextBytes = decodeBase64(ciphertextBase64),
                nonceBytes = decodeBase64(metadata.nonceBase64),
                associatedData = associatedData,
                algorithm = metadata.algorithm
            )
        }.getOrNull()
    }

    fun normalizeChannelName(raw: String): String {
        val clean = raw.trim().lowercase().replace(Regex("[^a-z0-9_\\-#]"), "-")
        return if (clean.startsWith("#")) clean else "#$clean"
    }

    private fun ChannelEntity.toDomain(): ChatChannel {
        return ChatChannel(
            channelId = channelId,
            name = name,
            topic = topic,
            channelType = channelType,
            geohash = geohash,
            isJoined = isJoined,
            isMuted = isMuted,
            ownerPeerId = ownerPeerId,
            keyEpoch = keyEpoch,
            passwordSaltBase64 = passwordSaltBase64,
            memberPeerIds = memberPeerIdsCsv.split(",").filter { it.isNotBlank() }.toSet(),
            unreadCount = unreadCount,
            createdAtEpochMs = createdAtEpochMs
        )
    }
}

/**
 * Full IRC-style command parser with input validation and autocomplete suggestions.
 *
 * Supported commands:
 * /help, /join, /leave, /msg, /who, /nick, /block, /unblock, /clear, /hug, /slap
 */
object IrcCommandParser {

    sealed class ParsedCommand {
        data object Help : ParsedCommand()
        data class Join(val channelName: String, val password: String? = null) : ParsedCommand()
        data class Leave(val channelName: String? = null) : ParsedCommand()
        data class DirectMsg(val targetPeerOrNick: String, val message: String) : ParsedCommand()
        data object Who : ParsedCommand()
        data class Nick(val newNickname: String) : ParsedCommand()
        data class Block(val targetPeerOrNick: String) : ParsedCommand()
        data class Unblock(val targetPeerOrNick: String) : ParsedCommand()
        data object Clear : ParsedCommand()
        data class Hug(val targetNick: String) : ParsedCommand()
        data class Slap(val targetNick: String) : ParsedCommand()
        data class InvalidCommand(val error: String) : ParsedCommand()
    }

    data class CommandSpec(
        val syntax: String,
        val description: String
    )

    val ALL_COMMANDS = listOf(
        CommandSpec("/help", "Show all supported IRC mesh commands"),
        CommandSpec("/join #channel [password]", "Join or create a public or password-protected channel"),
        CommandSpec("/leave [#channel]", "Leave the current or specified channel"),
        CommandSpec("/msg <peer> <text>", "Send an end-to-end encrypted direct message"),
        CommandSpec("/who", "List active peers discovered in the current channel / mesh"),
        CommandSpec("/nick <new_nickname>", "Change your anonymous display nickname"),
        CommandSpec("/block <peer>", "Block packets and messages from a peer"),
        CommandSpec("/unblock <peer>", "Unblock a previously blocked peer"),
        CommandSpec("/clear", "Clear local message history for the active conversation"),
        CommandSpec("/hug <nickname>", "Send an IRC action hug to a peer in the channel"),
        CommandSpec("/slap <nickname>", "Slap a peer around a bit with a large cryptographic trout")
    )

    fun autocompleteSuggestions(currentInput: String): List<CommandSpec> {
        val trimmed = currentInput.trimStart()
        if (!trimmed.startsWith("/")) return emptyList()
        val token = trimmed.substringBefore(" ").lowercase()
        return ALL_COMMANDS.filter { it.syntax.substringBefore(" ").startsWith(token) }
    }

    fun parse(rawInput: String): ParsedCommand? {
        val trimmed = rawInput.trim()
        if (!trimmed.startsWith("/")) return null

        val parts = trimmed.split(Regex("\\s+"), limit = 3)
        val cmd = parts[0].lowercase()

        return when (cmd) {
            "/help" -> ParsedCommand.Help
            "/join" -> {
                if (parts.size < 2 || parts[1].isBlank()) {
                    ParsedCommand.InvalidCommand("Usage: /join #channel [optional_password]")
                } else {
                    ParsedCommand.Join(parts[1], parts.getOrNull(2)?.takeIf { it.isNotBlank() })
                }
            }
            "/leave" -> ParsedCommand.Leave(parts.getOrNull(1))
            "/msg" -> {
                if (parts.size < 3 || parts[1].isBlank() || parts[2].isBlank()) {
                    ParsedCommand.InvalidCommand("Usage: /msg <peer_id_or_nick> <message>")
                } else {
                    ParsedCommand.DirectMsg(parts[1], parts[2])
                }
            }
            "/who" -> ParsedCommand.Who
            "/nick" -> {
                val nick = parts.getOrNull(1)?.trim()
                if (nick.isNullOrBlank() || nick.length < 2) {
                    ParsedCommand.InvalidCommand("Usage: /nick <new_nickname> (2-24 chars)")
                } else {
                    ParsedCommand.Nick(nick)
                }
            }
            "/block" -> {
                val target = parts.getOrNull(1)?.trim()
                if (target.isNullOrBlank()) {
                    ParsedCommand.InvalidCommand("Usage: /block <peer_id_or_nick>")
                } else {
                    ParsedCommand.Block(target)
                }
            }
            "/unblock" -> {
                val target = parts.getOrNull(1)?.trim()
                if (target.isNullOrBlank()) {
                    ParsedCommand.InvalidCommand("Usage: /unblock <peer_id_or_nick>")
                } else {
                    ParsedCommand.Unblock(target)
                }
            }
            "/clear" -> ParsedCommand.Clear
            "/hug" -> {
                val target = parts.drop(1).joinToString(" ").trim()
                if (target.isBlank()) ParsedCommand.InvalidCommand("Usage: /hug <nickname>")
                else ParsedCommand.Hug(target)
            }
            "/slap" -> {
                val target = parts.drop(1).joinToString(" ").trim()
                if (target.isBlank()) ParsedCommand.InvalidCommand("Usage: /slap <nickname>")
                else ParsedCommand.Slap(target)
            }
            else -> ParsedCommand.InvalidCommand("Unknown command '$cmd'. Type /help for available commands.")
        }
    }
}

