# No head — Real-Device Testing Checklist, Compatibility Matrix & Feature Completion Matrix

## 1. Real-Device Multi-Phone Testing Checklist (3+ Physical Android Devices)

> [!IMPORTANT]
> Emulator environments do not emulate physical Bluetooth Low Energy radio advertising/scanning hardware. To validate physical RF behavior, execute the following 12-scenario test protocol across at least three physical Android devices (**Phone A**, **Phone B**, and **Phone C**).

### Pre-Test Setup
1. Install `app/build/outputs/apk/debug/app-debug.apk` onto **Phone A**, **Phone B**, and **Phone C** via `adb install -r app-debug.apk`.
2. Enable Airplane Mode first, then turn **Bluetooth ON** (keeping Wi-Fi and Mobile Data **OFF**) on all three phones to prove zero-internet operation.
3. Complete onboarding on each phone and note each phone's generated `Peer ID` (`nh_...`).
4. In **Settings**, optionally toggle **Local Simulated Multi-Hop Test Nodes** to `OFF` so only physical BLE peers appear in the directory.

### 12 Required Verification Scenarios
- [ ] **Test 1 — Direct BLE Discovery & Exchange (A -> B)**:
  Place Phone A and Phone B within 2 meters. Open **People** and verify both discover each other (`ONLINE_NEARBY`, `hop=1`). Send a message in `#nearby-mesh` from A and verify B receives it with `signatureVerified = true`.
- [ ] **Test 2 — Multi-Hop Relay (A -> B -> C)**:
  Keep A, B, and C active. Send a public message from A with `TTL = 5` and verify `Diagnostics -> Multi-Hop Packets Relayed` increments on B and C displays the message.
- [ ] **Test 3 — Out-of-Range Hidden Node Relay (A <-> B <-> C)**:
  Place Phone A and Phone C at opposite ends of a building (out of direct BLE range of each other) with Phone B positioned midway between them. Confirm A cannot see C at `hop=1`, then send a message from A to C and verify B relays it (`hopCount = 1` on C) and returns the delivery acknowledgment back to A (`RELAYED` -> `DELIVERED`).
- [ ] **Test 4 — Multi-Path Deduplication**:
  Place all three phones close together so C receives A's broadcast both directly from A (`hop=0`) and relayed via B (`hop=1`). Verify the message appears **only once** in C's chat timeline and `Diagnostics -> Duplicate Packets Suppressed` increments.
- [ ] **Test 5 — BLE Disconnection & Automatic Recovery**:
  Walk Phone B out of range until its presence transitions to `STALE` / `OFFLINE`, then bring it back within range and verify GATT reconnection and presence restoration without restarting the app.
- [ ] **Test 6 — Offline Store-and-Forward & Courier Delivery**:
  Turn Bluetooth **OFF** on Phone C. From Phone A, send an E2EE Direct Message to C. Verify status on A shows `QUEUED` (and B stores an opaque envelope in `Courier Mailboxes Stored`). Turn Bluetooth **ON** on Phone C and verify the queued message is automatically delivered and decrypted, transitioning A's status to `DELIVERED` / `READ`.
- [ ] **Test 7 — Background Operation & Foreground Service**:
  Enable **Settings -> Foreground BLE Mesh Relay Service** on Phone B, lock Phone B's screen, and verify Phone A can still relay packets through Phone B to Phone C.
- [ ] **Test 8 — Permission Denial Graceful Degradation**:
  Revoke `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` in Android OS Settings. Launch **No head** and verify it does not crash, accurately reports `OFFLINE` transport status, and allows re-granting permissions.
- [ ] **Test 9 — Bluetooth Adapter Toggled Off**:
  Toggle the system Bluetooth switch OFF while chatting. Verify `BleMeshTransport` catches `BluetoothAdapter.STATE_OFF`, updates `BLE Adapter Enabled = false` in Diagnostics, and queues outgoing messages cleanly.
- [ ] **Test 10 — Chunked Media & Voice Note Transfer**:
  Record a Voice Note or tap the Attachment button in `#nearby-mesh`. Verify the payload is split into `PacketFragment` chunks with CRC32 checks, reassembled on the receiving phone, verified against its `SHA-256` digest, and played back.
- [ ] **Test 11 — E2EE Direct Message & QR Fingerprint Verification**:
  Open **People -> Verify QR** on Phone A and Phone B, compare the 128-bit safety fingerprints, mark verified, and exchange 1:1 Direct Messages. Verify the lock icon and `Verified` shield appear.
- [ ] **Test 12 — Optional Nostr Relay Internet Fallback**:
  Enable Wi-Fi on Phone A and Phone C (with Bluetooth OFF), enable a `wss://` relay in **Settings -> Optional Nostr Relays**, join `#global-nostr` or `#geo-u33d`, and verify signed messages synchronize across the internet relay with the public relay warning banner visible.

---

## 2. Android Device Compatibility Matrix

| Android OS / API Level | BLE Mesh (Central + Peripheral) | Runtime Permissions Required | Wi-Fi Aware (NAN) Support | Background BLE Behavior & Known OEM Notes |
| :--- | :--- | :--- | :--- | :--- |
| **Android 8.0–9.0 (API 26–28)** | Supported | `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` | Supported on Pixel 2/3, Galaxy S9+ | Requires `ScanFilter` (`SERVICE_UUID`) for screen-off scanning. |
| **Android 10–11 (API 29–30)** | Supported | `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` | Supported on Pixel 4/5, Galaxy S20/S21 | Location toggle must be ON at OS level for BLE scan results on API <= 30. |
| **Android 12 (API 31–32)** | Supported | `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` | Supported on compatible hardware | **Zero location permission required** for BLE mesh due to `neverForLocation` flag. |
| **Android 13 (API 33)** | Supported | `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `NEARBY_WIFI_DEVICES`, `POST_NOTIFICATIONS` | Supported with `NEARBY_WIFI_DEVICES` | Granular notification and nearby Wi-Fi permissions without location access. |
| **Android 14–15 (API 34–35)** | Supported | Same as API 33 + `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Supported on Pixel 6/7/8/9, Galaxy S22–S24 | Enforces `connectedDevice` foreground service type. Xiaomi (MIUI/HyperOS), Samsung (OneUI), and OnePlus (OxygenOS) users should set Battery Usage to "Unrestricted" for uninterrupted multi-hop relay while screen is off. |

---

## 3. Known Platform Limitations & Honest Disclosures

1. **Android Background BLE Restrictions**: Aggressive OEM battery killers (e.g. Xiaomi, Huawei, Oppo) may suspend background BLE advertising or scanning even with a Foreground Service unless the user exempts the app from battery optimizations. **No head** never promises guaranteed background delivery.
2. **BLE Throughput & Media Size Caps**: BLE 4.2/5.0 GATT connections have limited effective throughput (~5–20 KB/s across multi-hop links). **No head** enforces a strict `256 KB` attachment size cap over pure BLE and automatically uses Wi-Fi Aware (`10 MB` cap) when available.
3. **Flash Storage Wear-Leveling during Emergency Wipe**: While Emergency Privacy Wipe immediately destroys the Android Keystore AES-256-GCM key wrapping the SQLCipher database passphrase (rendering database blocks cryptographically unrecoverable) and zero-overwrites attachment files, raw flash memory controllers perform wear-leveling below the OS filesystem layer.
4. **Password-Protected Channels Insider Threat**: Shared-password channels use `Argon2id` + `ChaCha20-Poly1305` to protect group traffic from external mesh nodes, but any participant who knows the password can decrypt messages until removed and the group key epoch is rotated.

---

## 4. Feature Completion Matrix

| Requirement / Subsystem | Implementation Status | Primary Source Files | Automated Test Coverage |
| :--- | :--- | :--- | :--- |
| **Phase 1: Clean Architecture, Terminal UI, Onboarding, Identity, SQLCipher DB** | **Complete** | `MainActivity.kt`, `NoHeadTheme.kt`, `OnboardingScreen.kt`, `IdentityManager.kt`, `NoHeadDatabase.kt` | `CryptoAndProtocolUnitTest` |
| **Phase 2: Native Android BLE Mesh (Advertise, Scan, GATT Server/Client, Fragmentation)** | **Complete** | `BleMeshTransport.kt`, `BleMeshForegroundService.kt`, `Transport.kt` | `CryptoAndProtocolUnitTest` (`PacketFragmenter`) |
| **Phase 3: Multi-Hop Routing, TTL, Deduplication, E2EE Direct Messages, Favorites, Blocking** | **Complete** | `MessageRouter.kt`, `SessionManager.kt`, `PeerManager.kt`, `PeersAndDirectScreens.kt` | `MeshRoutingIntegrationTest` (3-hop & deduplication) |
| **Phase 4: Store-and-Forward Queue, Courier Mailboxes, Public & Argon2id Channels, IRC Cmds** | **Complete** | `StoreForwardManager.kt`, `ChannelManager.kt`, `ChannelsAndGeohashScreen.kt` | `CryptoAndProtocolUnitTest` (`Argon2id`, `IrcCommandParser`), `MeshRoutingIntegrationTest` (Courier) |
| **Phase 5: Optional Nostr Relays (TLS WebSockets) & Coarse Geohash Location Rooms** | **Complete** | `NostrTransport.kt`, `GeohashService.kt`, `ChannelsAndGeohashScreen.kt` | `CryptoAndProtocolUnitTest` (`GeohashCodec`) |
| **Phase 6: Chunked Media Attachments, Voice Notes (AAC), Live Push-to-Talk, Wi-Fi Aware** | **Complete** | `AttachmentAndVoiceManager.kt`, `WifiAwareTransport.kt`, `ChatScreen.kt` | `CryptoAndProtocolUnitTest` |
| **Phase 7: Emergency Panic Wipe, Tor SOCKS5 Proxy, Sanitized Diagnostics, Cashu Token Recognition** | **Complete** | `PrivacyManager.kt`, `SettingsAndDiagnosticsScreens.kt`, `NostrTransport.kt` | Verified in build & unit test suite |

