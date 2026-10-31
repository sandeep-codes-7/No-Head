# No head — Security Threat Model, Privacy Policy Draft & Data Inventory

## 1. Security Threat Model

| Threat / Attack Vector | Risk Description | Implemented Mitigation in No head | Residual Risk / Honest Limitation |
| :--- | :--- | :--- | :--- |
| **1. Malicious Nearby Peers** | Passive eavesdropping or active injection of forged mesh packets over BLE / Wi-Fi Aware. | All packets require valid `Ed25519` signatures bound to `PeerId = SHA256(SignPubKey)[0..11]`. Private DMs use `X25519` + `ChaCha20-Poly1305` E2EE. | Public channel messages (`#nearby-mesh`) are readable by any nearby node in radio range by design. |
| **2. Rogue Relay Nodes** | Intermediate mesh hops or Nostr relays modifying, dropping, or inspecting packets. | Relay hops cannot modify immutable envelope fields without breaking the `Ed25519` origin signature or AEAD Associated Data tag. | Rogue relays can selectively drop packets (blackhole DoS) or log packet timing/size metadata. |
| **3. Spam & Flooding** | High-rate packet injection to exhaust battery, BLE airtime, or storage. | Per-peer sliding window rate limiter (`MAX_PACKETS_PER_PEER_WINDOW = 45` per 10s), strict TTL caps (`<= 10`), and instant `/block <peer>` enforcement. | A Sybil attacker generating thousands of fresh keypairs per minute can still consume radio airtime until rate-limited. |
| **4. Replay Attacks** | Re-broadcasting captured valid packets later to confuse state or duplicate messages. | Every `MeshEnvelope` carries `packetId`, `createdAtEpochMs`, and `expiresAtEpochMs`. Seen packet IDs are persisted in `SeenPacketDao` and rejected on replay. | Replays after `expiresAtEpochMs` are rejected by timestamp check; replays before expiration are rejected by `SeenPacketDao`. |
| **5. Malformed Packets** | Corrupted BLE fragments or crafted JSON designed to crash parsers or exhaust heap. | `PacketFragmenter` validates `CRC32` per fragment, caps fragment counts, bounds concurrent reassembly sessions (`128`), and uses safe `runCatching` deserialization. | None known. |
| **6. Identity Impersonation & MITM** | Attacker copies a victim's nickname or substitutes public keys during first contact. | UI clearly separates mutable `nickname` from cryptographic `peerId` (`nh_...`). Users verify 128-bit safety fingerprints via QR code (`IdentityQrCodeView`). | Before out-of-band fingerprint verification (TOFU), an active attacker could advertise a different `PeerId` with the same nickname. |
| **7. Compromised Channel Members** | An insider with the password to a password-protected channel leaks messages. | `ChannelManager` supports `rotatePasswordChannelKeyEpoch()` and member removal. | Password protection does **not** protect against a malicious member who currently knows the shared password. |
| **8. Malicious Couriers** | Store-and-forward courier nodes attempting to read, tamper with, or hoard offline messages. | Couriers only receive opaque `DIRECT_ENCRYPTED_MSG` envelopes (`ChaCha20-Poly1305` ciphertext) and cannot decrypt plaintext. Quota capped at `4 MB`. | Couriers can drop queued packets, delay delivery until expiration, or observe `senderPeerId` and `recipientPeerId`. |
| **9. Metadata Leakage & Traffic Analysis** | Tracking BLE MAC addresses or correlating packet headers (`senderPeerId`, `recipientPeerId`). | Android BLE uses rotating random resolvable MAC addresses; `IdentityManager` rotates the advertised presence token (`rotatingPresenceId`). Optional Tor SOCKS5 proxy hides IP from Nostr relays. | Local RF transmissions inherently reveal physical proximity to directional antennas. |
| **10. Malicious Attachments** | Sending `.apk`, `.sh`, `.dex`, or path-traversal filenames (`../../`) to execute code. | `AttachmentAndVoiceManager` blocks executable extensions, strips path traversal characters, validates SHA-256 hashes, enforces a `32 MB` quota, and never auto-opens files. | User must still exercise caution if manually exporting files to external apps. |
| **11. Physical Device Seizure / Key Theft** | Attacker extracts local SQLite database or SharedPreferences from device storage. | Room DB is encrypted with `SQLCipher` (`net.zetetic:android-database-sqlcipher`) using a 256-bit key wrapped by Android Keystore AES-256-GCM; identity keys use `EncryptedSharedPreferences`. Emergency Panic Wipe destroys keys immediately. | If device is seized while unlocked and before Emergency Wipe is triggered, active memory can be inspected. |

---

## 2. Data Inventory

| Data Category | Stored Locally? | Storage Mechanism & Encryption | Leaves Device over BLE / Wi-Fi Aware? | Sent to Optional Nostr Relays? |
| :--- | :--- | :--- | :--- | :--- |
| **Phone Number / Email / Contacts** | **NO** | Never collected or requested | Never | Never |
| **Ed25519 / X25519 Private Keys** | **YES** | `EncryptedSharedPreferences` (Android Keystore AES-256-GCM) | **NEVER** (Only exported locally if user explicitly creates an Argon2id encrypted backup) | **NEVER** |
| **Ed25519 / X25519 Public Keys & Nickname** | **YES** | `EncryptedSharedPreferences` & `peers` SQLCipher table | **YES** (In signed `PEER_ANNOUNCE` & `MeshEnvelope` headers) | **YES** (Only when user enables a Nostr relay) |
| **Public Channel Messages** | **YES** | `messages` table in SQLCipher Room DB | **YES** (Signed plaintext in `MeshEnvelope`) | **YES** (Only for `#global-nostr` or `#geo-*` rooms when Nostr relays are enabled) |
| **1:1 Direct Messages** | **YES** | `messages` table in SQLCipher Room DB | **YES** (Only as `ChaCha20-Poly1305` / `AES-256-GCM` E2EE ciphertext) | **YES** (Only as E2EE ciphertext fallback if Nostr relays are enabled) |
| **Store-and-Forward Courier Envelopes** | **YES** (Temporary) | `courier_mailbox` table in SQLCipher Room DB (4 MB quota, auto-expiring) | **YES** (Opaque E2EE ciphertext forwarded to target peer) | **NO** |
| **Geohash Bookmarks** | **YES** | `geohash_bookmarks` table in SQLCipher Room DB | Coarse geohash tag (`1..8` chars) included in `#geo-*` room messages; exact GPS coordinates are **never** stored or transmitted | Coarse geohash tag (`["g", "<geohash>"]`) included in `#geo-*` room events |
| **Diagnostics Telemetry** | **YES** (In-memory) | Sanitized packet counters only; zero message content | **NO** | **NO** |

---

## 3. Privacy Policy Draft

### No head Privacy Policy
**Effective Date**: October 31, 2026

1. **Zero Central Data Collection**:
   **No head** is an autonomous, peer-to-peer software application. The developers of **No head** operate **no central servers**, collect **no analytics or telemetry**, embed **no advertising SDKs**, and have **no access** to your identity keys, messages, peer list, or location.
2. **No Personal Identifiers Required**:
   You are never asked for your name, phone number, email address, or contact book. Your identity on the mesh is a locally generated cryptographic keypair (`Ed25519` and `X25519`).
3. **Local Encrypted Storage**:
   All chat histories, peer directories, and store-and-forward queues are stored exclusively on your device inside an encrypted `SQLCipher` database protected by the Android hardware-backed Keystore.
4. **Proximity Mesh Broadcasting**:
   When you enable Bluetooth Low Energy (BLE) or Wi-Fi Aware, your device broadcasts signed packets to nearby devices within radio range so messages can hop across the mesh. Anyone within radio range can receive public channel broadcasts (`#nearby-mesh`). Private 1:1 Direct Messages are end-to-end encrypted before leaving your device.
5. **Optional Third-Party Internet Relays (Nostr)**:
   Internet Nostr relays are **disabled by default**. If you manually enable one or more `wss://` Nostr relays in Settings, public messages in `#global-nostr` or `#geo-*` rooms and encrypted fallback packets will be transmitted to those relay operators. Unless you enable the Tor SOCKS5 proxy option (`127.0.0.1:9050`), relay operators can observe your IP address.
6. **Emergency Wipe & Data Deletion**:
   You can delete individual conversations or trigger the **Emergency Privacy Wipe** in Settings at any time to destroy all local cryptographic keys, databases, and cached files on your device. Please note that wiping your device cannot delete messages that have already been delivered to other peers' devices or external relays.

