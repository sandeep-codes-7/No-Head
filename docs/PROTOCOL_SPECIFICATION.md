# No head — Network Protocol Specification (v1)

## 1. Wire Protocol Overview

All transports (`BLE_MESH`, `WIFI_AWARE`, `NOSTR_RELAY`, `LOOPBACK_SIMULATED`) exchange versioned `MeshEnvelope` packets (`protocolVersion = 1`). Low-MTU links (`BLE_MESH`) split serialized `MeshEnvelope` payloads into `PacketFragment` frames with per-fragment `CRC32` checksums.

### 1.1 `MeshEnvelope` Schema
| Field | Type | Description |
| :--- | :--- | :--- |
| `protocolVersion` | `Int` | Protocol schema version (`1`). Packets with unknown versions are dropped. |
| `packetId` | `String` | Globally unique 128-bit identifier (`msg_<hex>`, `dm_<hex>`, `ann_<id>_<ts>`). |
| `packetType` | `PacketType` | `PEER_ANNOUNCE`, `HANDSHAKE_INIT`, `PUBLIC_CHANNEL_MSG`, `PASSWORD_CHANNEL_MSG`, `DIRECT_ENCRYPTED_MSG`, `DELIVERY_ACK`, `READ_RECEIPT`, `ATTACHMENT_CHUNK`, `VOICE_NOTE`, `LIVE_PTT_FRAME`. |
| `senderPeerId` | `String` | Deterministic ID `nh_<24-hex-chars>` derived from `SHA-256(senderSigningPubKeyBytes)[0..11]`. |
| `senderNickname` | `String` | Mutable display nickname (never trusted as cryptographic identity). |
| `senderSigningPubKeyHex` | `String` | 32-byte `Ed25519` public key (64 hex chars). |
| `senderEncryptionPubKeyHex` | `String` | 32-byte static `X25519` public key (64 hex chars). |
| `recipientPeerId` | `String?` | Target `peerId` for 1:1 Direct Messages, ACKs, and Courier Mailboxes. |
| `channelId` | `String?` | Target channel (`#nearby-mesh`, `#global-nostr`, `#geo-u33d`, etc.). |
| `geohash` | `String?` | Optional coarse Base32 geohash tag (`1..8` chars). |
| `createdAtEpochMs` | `Long` | Origin timestamp in UTC milliseconds. |
| `expiresAtEpochMs` | `Long` | Expiration UTC timestamp. Expired packets are immediately dropped. |
| `ttl` | `Int` | Remaining hop count (`1..10`, default `5`). Decremented on each relay hop. |
| `hopCount` | `Int` | Number of hops traversed from origin (`0` at origin, incremented on each relay). |
| `payloadBase64` | `String` | Base64-encoded body (plaintext JSON for public channels, AEAD ciphertext for DMs and password channels). |
| `encryptionMetadata` | `EncryptionMetadata?` | Algorithm (`ChaCha20-Poly1305` / `AES-256-GCM`), 96-bit `nonceBase64`, per-message `ephemeralPublicKeyHex`, `keyEpoch`, and optional `argon2SaltBase64`. |
| `signatureBase64` | `String` | 64-byte `Ed25519` signature over canonical immutable origin fields. |

---

## 2. Origin Authentication & Canonical Signing

To allow intermediate relay nodes to decrement `ttl` and increment `hopCount` without invalidating the sender's signature, `Ed25519` signatures are computed over `MeshEnvelope.canonicalSignableBytes()`:

```text
v=<protocolVersion>|id=<packetId>|type=<packetType>|sender=<senderPeerId>|signPub=<senderSigningPubKeyHex>|encPub=<senderEncryptionPubKeyHex>|rcpt=<recipientPeerId>|chan=<channelId>|geo=<geohash>|ts=<createdAtEpochMs>|exp=<expiresAtEpochMs>|payload=<payloadBase64>|enc=<nonceBase64>:<ephemeralPublicKeyHex>:<keyEpoch>
```

Before accepting any packet, `IdentityManager.verifyEnvelopeSignature()`:
1. Recomputes `derivePeerId(senderSigningPubKeyHex)` and verifies it matches `senderPeerId` (preventing Peer ID spoofing).
2. Verifies the 64-byte `Ed25519` signature over `canonicalSignableBytes()`.

---

## 3. End-to-End Encryption (1:1 Direct Messages)

1. **Key Agreement**:
   - Sender computes static-static secret: $DH_{ss} = \text{X25519}(sk_{\text{sender, static}}, pk_{\text{recipient, static}})$.
   - Sender generates a fresh ephemeral keypair $(sk_{\text{eph}}, pk_{\text{eph}})$ and computes ephemeral-static secret: $DH_{es} = \text{X25519}(sk_{\text{eph}}, pk_{\text{recipient, static}})$.
2. **Key Derivation (`HKDF-SHA256`)**:
   - Input Keying Material: $IKM = DH_{ss} \parallel DH_{es}$.
   - Salt: $\text{SHA-256}(\text{"NoHead-NoiseXX-Salt-v1"})$.
   - Info: $\text{"NoHead-NoiseXX-Session-v1:"} \parallel \min(id_A, id_B) \parallel \text{":"} \parallel \max(id_A, id_B)$.
   - Output: 256-bit symmetric AEAD key.
3. **AEAD Encryption**:
   - Cipher: `ChaCha20-Poly1305` (with `AES-256-GCM` fallback) using a 96-bit cryptographic random nonce.
   - Associated Data (AAD): `protocolVersion|packetId|packetType|senderPeerId|recipientPeerId|channelId|createdAtEpochMs`.

---

## 4. Password-Protected Group Channels

1. **Password Derivation**:
   - Uses `Argon2id` (`version = 0x13`, `iterations = 3`, `memory = 16384 KiB`, `parallelism = 2`, `output = 32 bytes`) with a 128-bit channel salt.
2. **Epoch Key Ratcheting**:
   - Group epoch keys are derived via `HKDF-SHA256(rootKey, salt, "NoHead-GroupEpoch:<channelId>:<epoch>")`.
   - When a member is removed or the owner triggers key rotation, `keyEpoch` increments and the group key is ratcheted.

---

## 5. Multi-Hop Routing & Deduplication Semantics

- **Delivery Model**: Honest at-least-once attempts with idempotent deduplication (`seenCache` LRU + `SeenPacketDao` SQLite table). Never claims impossible "guaranteed offline delivery" or "theoretical exactly-once delivery".
- **Loop Prevention**: Every node records `packetId` upon first sight or local creation. Any subsequent arrival of `packetId` over BLE, Wi-Fi Aware, or Nostr is dropped immediately (`packetsDeduplicatedDropped` counter incremented).
- **Courier Forwarding**: When a node receives a `DIRECT_ENCRYPTED_MSG` addressed to another peer, it stores the opaque signed ciphertext in `CourierMailboxDao` (bounded by a strict `4 MB` quota and packet expiration timestamp) and forwards it when the target peer is discovered.

---

## 6. Nostr Interoperability & Deployment Guide

- **Event Kind**: Uses Nostr Ephemeral/Application-specific event kind `24242` (`NOSTR_KIND_NOHEAD_ENVELOPE`) with tags `["d", "<channelId>"]`, `["g", "<geohash>"]`, and `["p", "<recipientPeerId>"]`.
- **Self-Hosting a Optional Nostr Relay**:
  Operators wishing to run a private or community Nostr relay for **No head** can deploy `strfry` or `nostr-rs-relay` behind a TLS reverse proxy (Nginx / Caddy) with `wss://` enabled and configure the URL in **Settings -> Optional Nostr Relays**.

