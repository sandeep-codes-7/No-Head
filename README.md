# No head — Decentralized Peer-to-Peer Mesh Messaging Application

**No head** is a privacy-first, anonymous-by-default decentralized peer-to-peer messaging application for Android (API 26+) that enables communication without internet, user accounts, phone numbers, or central servers.

---

## 1. Key Capabilities

1. **Bluetooth Low Energy (BLE) Multi-Hop Mesh**:
   - Native Android BLE Peripheral (`BluetoothLeAdvertiser` + `BluetoothGattServer`) and Central (`BluetoothLeScanner` + `BluetoothGatt` client pool).
   - Handshake & capability negotiation over dedicated GATT characteristics (`00004E48-...`, `00004E49-...`, `00004E4A-...`).
   - Deterministic packet fragmentation & out-of-order reassembly with CRC32 frame verification (`PacketFragmenter`).
   - Multi-hop message relay (`TTL` default 5, configurable 1–10) with in-memory LRU + persistent SQLCipher `seen_packets` deduplication.
2. **Optional Wi-Fi Aware (NAN) High-Speed Transport**:
   - Runtime hardware capability detection (`FEATURE_WIFI_AWARE`) and automatic fallback to BLE Mesh.
   - Shares the exact same versioned `MeshEnvelope` wire format and cryptographic verification policy.
3. **Optional Nostr Internet Relay Transport & Tor SOCKS5 Proxy**:
   - Connects to user-enabled `wss://` TLS WebSocket relays (disabled by default to preserve strict local-only operation until opted in).
   - Supports routing internet WebSocket connections through a local Tor SOCKS5 proxy (`127.0.0.1:9050`).
4. **End-to-End Encrypted Direct Messages & Password-Protected Channels**:
   - **Identity**: Local `Ed25519` signing keypair + `X25519` key agreement keypair + rotating BLE presence token.
   - **1:1 Direct Messaging**: Noise XX-inspired Static-Static + Ephemeral-Static `X25519` Diffie-Hellman key agreement with `HKDF-SHA256` and `ChaCha20-Poly1305` / `AES-256-GCM` AEAD encryption.
   - **Password-Protected Channels**: `Argon2id` password key derivation with `HKDF-SHA256` group key epoch rotation.
5. **Encrypted Store-and-Forward & Courier Mailboxes**:
   - Persistent `SQLCipher`-encrypted Room database (`NoHeadDatabase`) with Android Keystore AES-256-GCM key wrapping.
   - Exponential backoff outbox queue and bounded Spray-and-Wait opaque courier mailboxes (`4 MB` quota) where courier nodes cannot read plaintext.
6. **Public Channels, IRC Commands & Coarse Geohash Rooms**:
   - Interactive IRC slash command parser & autocomplete (`/help`, `/join`, `/leave`, `/msg`, `/who`, `/nick`, `/block`, `/unblock`, `/clear`, `/hug`, `/slap`).
   - Manual Base32 Geohash room selector (`GeohashCodec`) that never requires GPS permission for BLE messaging and explains precision radius privacy tradeoffs.
7. **Media Attachments, Voice Notes & Live Push-to-Talk (PTT)**:
   - Chunked attachment transfer with SHA-256 verification, strict MIME/extension filtering, and sandboxed storage quotas (`32 MB`).
   - AAC-LC voice notes (`.m4a`) and low-latency 8kHz mono Live Push-to-Talk streaming.
8. **Emergency Privacy Wipe (Panic Wipe)**:
   - Immediately stops all radio transports and foreground services, destroys Android Keystore wrap keys, wipes all database tables and attachments, and generates an unlinked identity.

---

## 2. Project Structure

```text
app/src/main/java/com/nohead/mesh/
├── MainActivity.kt                         # Main Compose host & 7-section bottom navigation
├── NoHeadApplication.kt                    # Hilt application entry point
├── data/
│   ├── local/
│   │   ├── Entities.kt                     # Room entities (Messages, Peers, Channels, Queue, Courier, Relays)
│   │   └── NoHeadDatabase.kt               # SQLCipher-encrypted Room Database & DAOs
│   └── transport/
│       ├── ble/
│       │   ├── BleMeshForegroundService.kt # ConnectedDevice Foreground Service for background relay
│       │   └── BleMeshTransport.kt         # Native Android BLE Advertiser, Scanner, GATT Server & Client
│       ├── nostr/
│       │   └── NostrTransport.kt           # TLS WebSocket Nostr relay client + SOCKS5 Tor proxy support
│       ├── simulated/
│       │   └── SimulatedLoopbackTransport.kt # Multi-hop virtual mesh nodes for single-device dev/testing
│       └── wifiaware/
│           └── WifiAwareTransport.kt       # Runtime-detected Wi-Fi Aware (NAN) transport
├── di/
│   └── AppModule.kt                        # Hilt DI bindings & SQLCipher SupportFactory setup
├── domain/
│   ├── crypto/
│   │   └── CryptoEngine.kt                 # Audited Ed25519, X25519, HKDF-SHA256, Argon2id, ChaCha20-Poly1305
│   ├── model/
│   │   ├── DomainModels.kt                 # Domain entities & diagnostics models
│   │   └── ProtocolModels.kt               # Versioned MeshEnvelope, PacketFragment, Handshake schemas
│   ├── service/
│   │   ├── AttachmentAndVoiceManager.kt    # Chunked media, AAC voice notes, Live PTT audio streamer
│   │   ├── ChannelManager.kt               # Public & Argon2id channels + IRC command parser
│   │   ├── GeohashService.kt               # Base32 Geohash encoder/decoder & regional directory
│   │   ├── IdentityManager.kt              # Keystore-backed identity, QR fingerprints, encrypted backup
│   │   ├── MessageRouter.kt                # Multi-hop router, deduplication, rate limiting, receipts
│   │   ├── PeerManager.kt                  # Peer presence, trust, verification, favorites, blocklist
│   │   ├── PrivacyManager.kt               # Emergency Panic Wipe & sanitized diagnostics export
│   │   ├── SessionManager.kt               # E2EE 1:1 session ratcheting & AEAD encryption
│   │   └── StoreForwardManager.kt          # Persistent outbox & opaque courier mailboxes
│   └── transport/
│       └── Transport.kt                    # Cross-platform transport abstraction & PacketFragmenter
└── ui/
    ├── components/
    │   └── QrCodeComposables.kt            # ZXing QR code generator for identity verification
    ├── screens/
    │   ├── ChannelsAndGeohashScreen.kt     # Channel manager, Argon2id group creator, Geohash browser
    │   ├── ChatScreen.kt                   # Main terminal-inspired timeline, IRC autocomplete, PTT/Voice
    │   ├── OnboardingScreen.kt             # Identity roll, permission explanations, anonymity notice
    │   ├── PeersAndDirectScreens.kt        # Peer directory, QR verification, Favorites, E2EE DM list
    │   └── SettingsAndDiagnosticsScreens.kt # Identity backup, Nostr/Tor, Panic Wipe, Telemetry
    ├── theme/
    │   └── NoHeadTheme.kt                  # Terminal-inspired Dark & Light Material 3 theme
    └── viewmodel/
        └── NoHeadMainViewModel.kt          # Reactive state holder & user action dispatcher
```

---

## 3. Build, Test & Run Instructions

### Prerequisites
- **JDK**: Java 17 or Java 21 LTS
- **Android SDK**: API 35 (`compileSdk = 35`, `minSdk = 26`)

### Build Debug APK
```powershell
.\gradlew.bat assembleDebug --no-daemon
```
The built Debug APK is output to:
`app/build/outputs/apk/debug/app-debug.apk`

### Run Automated Unit & Multi-Hop Integration Tests
```powershell
.\gradlew.bat testDebugUnitTest --no-daemon
```

### Build Release APK
To build a minified, shrinked Release APK (`isMinifyEnabled = true`, `isShrinkResources = true`):
```powershell
.\gradlew.bat assembleRelease --no-daemon
```
Sign the resulting `app/build/outputs/apk/release/app-release-unsigned.apk` with `apksigner` using your release keystore.

---

## 4. Documentation Index

- [Architecture & Protocol Specification](docs/PROTOCOL_SPECIFICATION.md)
- [Security Threat Model, Privacy Policy & Data Inventory](docs/SECURITY_THREAT_MODEL_AND_PRIVACY.md)
- [Real-Device Testing Checklist, Compatibility Matrix & Feature Matrix](docs/TESTING_AND_COMPATIBILITY.md)

---

## 5. License

This project is licensed under the Apache License, Version 2.0. See the [LICENSE](LICENSE) file for details.


