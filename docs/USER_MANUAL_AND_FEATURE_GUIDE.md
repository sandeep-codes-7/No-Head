# No head — Complete Pin-to-Pin Feature Guide & Operator's Manual

> **Application Name**: No head  
> **Version**: `1.0.0-mesh` (Protocol Schema v1)  
> **Target OS**: Android 8.0+ (API 26 through API 35 / Android 15)  
> **Architecture**: Pure Peer-to-Peer (P2P), Local-First, Zero Central Server, Anonymous-by-Default  

---

## Table of Contents
1. [Core Architectural Philosophy](#1-core-architectural-philosophy)
2. [Pin-to-Pin Feature Breakdown](#2-pin-to-pin-feature-breakdown)
   - [2.1 Identity, Anonymity & Cryptography](#21-identity-anonymity--cryptography)
   - [2.2 Bluetooth Low Energy (BLE) Multi-Hop Mesh](#22-bluetooth-low-energy-ble-multi-hop-mesh)
   - [2.3 Optional Wi-Fi Aware (NAN) Transport](#23-optional-wi-fi-aware-nan-transport)
   - [2.4 Optional Nostr Internet Relay & Tor SOCKS5](#24-optional-nostr-internet-relay--tor-socks5)
   - [2.5 Message Routing, TTL & Anti-Loop Deduplication](#25-message-routing-ttl--anti-loop-deduplication)
   - [2.6 Store-and-Forward & Opaque Courier Mailboxes](#26-store-and-forward--opaque-courier-mailboxes)
   - [2.7 Public Channels & Full IRC Command System](#27-public-channels--full-irc-command-system)
   - [2.8 Password-Protected Group Channels](#28-password-protected-group-channels)
   - [2.9 1:1 End-to-End Encrypted Direct Messages (E2EE)](#29-11-end-to-end-encrypted-direct-messages-e2ee)
   - [2.10 Coarse Geohash Location Rooms](#210-coarse-geohash-location-rooms)
   - [2.11 Media Attachments, Voice Notes & Live Push-to-Talk (PTT)](#211-media-attachments-voice-notes--live-push-to-talk-ptt)
   - [2.12 Peer Directory, Trust Verification & Blocklist](#212-peer-directory-trust-verification--blocklist)
   - [2.13 Live Network Diagnostics & Telemetry](#213-live-network-diagnostics--telemetry)
   - [2.14 Emergency Privacy Wipe (Panic Wipe)](#214-emergency-privacy-wipe-panic-wipe)
3. [Screen-by-Screen Walkthrough & User Interface Manual](#3-screen-by-screen-walkthrough--user-interface-manual)
4. [Real-World Use Cases & Operational Playbooks](#4-real-world-use-cases--operational-playbooks)
   - [Playbook 1: Natural Disaster & Grid Outage](#playbook-1-natural-disaster--grid-outage-telecom-collapse)
   - [Playbook 2: Remote Wilderness, Hiking & Maritime Expeditions](#playbook-2-remote-wilderness-hiking--maritime-expeditions)
   - [Playbook 3: Crowded Stadiums, Festivals & Demonstrations](#playbook-3-crowded-stadiums-festivals--demonstrations)
   - [Playbook 4: High-Risk Privacy, Activism & Whistleblowing](#playbook-4-high-risk-privacy-activism--whistleblowing)
   - [Playbook 5: Mesh-to-Internet Gateway (Hybrid BLE + Nostr)](#playbook-5-mesh-to-internet-gateway-hybrid-ble--nostr)
5. [Troubleshooting & Platform Limitations FAQ](#5-troubleshooting--platform-limitations-faq)

---

## 1. Core Architectural Philosophy

No head was engineered from first principles around five immutable constraints:

1. **Zero Registration**: No phone numbers, email addresses, usernames, SMS codes, or server logins exist anywhere in the code.
2. **Local RF Mesh First**: Communication takes place directly over Bluetooth Low Energy (and Wi-Fi Aware) radios from chip to chip. The internet is strictly an optional, disabled-by-default auxiliary transport.
3. **Multi-Hop Relay**: A device does not need to be within radio range of the destination. Intermediate devices transparently store, route, and forward packets along the mesh path.
4. **Zero Fabricated Delivery Receipts**: Delivery indicators reflect real cryptographic acknowledgments. If a recipient is offline or out of reach, the state truthfully indicates `QUEUED` or `RELAYED`, never a fake checkmark.
5. **No Central Point of Seizure**: All local data resides in an encrypted database on the device (`SQLCipher` backed by Android Keystore hardware-backed keys). When destroyed via Emergency Wipe, local keys are obliterated immediately.

---

## 2. Pin-to-Pin Feature Breakdown

### 2.1 Identity, Anonymity & Cryptography

Every device generates a sovereign cryptographic identity locally during first launch:

- **Signing Keypair (`Ed25519` / RFC 8032)**:
  - Generates a 32-byte private key and 32-byte public key.
  - Used to sign all packet origin bytes, proving authentic authorship and preventing packet tampering across hops.
- **Key Agreement Keypair (`X25519` / RFC 7748)**:
  - Used for Static-Static and Ephemeral-Static Diffie-Hellman handshakes.
- **Cryptographic Peer ID (`nh_<24-hex-chars>`)**:
  - Deterministically calculated as `nh_` followed by the first 12 bytes of `SHA-256(Ed25519_PublicKey)`.
  - The application strictly enforces that the advertised public key matches the Peer ID; spoofing a Peer ID with someone else's public key causes immediate packet rejection.
- **Anonymous Nickname**:
  - A friendly, human-readable display moniker (e.g. `silent_specter_a1f4`).
  - Decoupled from the cryptographic Peer ID. The user can edit or roll their nickname anytime without losing their contacts or changing their public key.
- **Rotating Presence Token**:
  - A 15-minute rotating identifier broadcasted in BLE advertising packets.
  - Prevents passive RF surveillance from tracking a device's movements across the city using static BLE advertisements.
- **128-bit Safety Fingerprint**:
  - Calculated as `SHA-256(Ed25519_PubKey : X25519_PubKey)[0..15]`, displayed as 8 groups of 4 hexadecimal characters.
  - Formatted into an offline visual QR code (`IdentityQrCodeView`) for instant out-of-band contact verification.
- **Passphrase-Protected Backup (Argon2id + ChaCha20-Poly1305)**:
  - Private keys are **never** exported in plaintext.
  - When the user selects "Encrypted Backup", they provide an 8+ character password. No head runs `Argon2id` (16 MB memory, 3 iterations) to derive a 256-bit AES/ChaCha key, encrypts the private keys with a random 96-bit nonce, and outputs an armored JSON bundle.

---

### 2.2 Bluetooth Low Energy (BLE) Multi-Hop Mesh

The heart of No head's offline communications stack:

- **Dual-Role Operation (Peripheral & Central Simultaneously)**:
  - **Peripheral Role**: Runs `BluetoothLeAdvertiser` and `BluetoothGattServer`. Advertises service UUID `00004E48-0000-1000-8000-00805F9B34FB` with rotating presence tokens, accepting incoming GATT connections.
  - **Central Role**: Runs `BluetoothLeScanner` with active service filters, discovering surrounding nodes and maintaining up to 7 concurrent outgoing GATT client connections.
- **Custom GATT Service Architecture**:
  - `CHAR_PACKET_UUID` (`00004E49-...`): High-throughput characteristic configured for `PROPERTY_WRITE_NO_RESPONSE` and `PROPERTY_NOTIFY`.
  - `CHAR_HANDSHAKE_UUID` (`00004E4A-...`): Characteristic for mutual identity challenge/response and version negotiation.
- **MTU 512 Negotiation & Dynamic Fragmentation**:
  - On connection, No head requests an MTU of 512 bytes.
  - The `PacketFragmenter` splits serialized JSON envelopes into chunks (160 bytes default), attaches a 32-bit CRC checksum to each fragment, and transmits them.
  - The receiver checks each CRC and reassembles fragments in memory even if they arrive out-of-order over congested RF links.
- **Foreground Service Integration (`connectedDevice`)**:
  - Compliant with Android 14+ strict background policies.
  - Allows the mesh to keep listening and relaying packets while the phone is locked in your pocket or running other apps.
- **Adaptive Battery Saver**:
  - Switches BLE scan duty cycles between 15-second cycles (high responsiveness) and 45-second cycles (low power), cutting battery drain by over 60%.

---

### 2.3 Optional Wi-Fi Aware (NAN) Transport

Where supported by device silicon (e.g. Google Pixel, Samsung Galaxy):

- **Zero-Access-Point Networking**: Wi-Fi Aware forms direct high-speed P2P clusters between phones without connecting to a Wi-Fi router.
- **Runtime Capability Detection**: Uses `context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)` to dynamically detect hardware support.
- **Shared Envelope Schema**: Wi-Fi Aware uses the exact same `MeshEnvelope` security schema as BLE.
- **High-Bandwidth Media Highway**: Supports payloads up to 10 MB (vs. 256 KB on BLE), enabling high-resolution photo and file sharing.
- **Seamless BLE Fallback**: If Wi-Fi Aware is unavailable or turned off, transfers gracefully route over BLE.

---

### 2.4 Optional Nostr Internet Relay & Tor SOCKS5

For bridging local mesh islands across global distances:

- **Disabled by Default**: No internet connection is ever made unless the user explicitly enables a relay in Settings.
- **TLS WebSocket Transport**: Connects to configurable relays (`wss://relay.damus.io`, `wss://nos.lol`, or private community relays).
- **Kind 24242 Encrypted Envelopes**:
  - Wraps `MeshEnvelope` packets inside Nostr events using custom Ephemeral Kind `24242`.
  - Tags include `#d` (channel ID), `#g` (geohash), and `#p` (recipient peer ID).
- **Tor SOCKS5 Proxy Compatibility**:
  - Built-in toggle to route all Nostr WebSocket connections through `127.0.0.1:9050` (Orbot or local Tor daemon).
  - Conceals the user's real IP address from external relay operators.
- **Clear Leak Warnings**: The app displays an amber warning bar whenever a conversation is active in a room that publishes to internet relays.

---

### 2.5 Message Routing, TTL & Anti-Loop Deduplication

Ensures packets propagate efficiently across multi-hop topologies without crashing the network:

- **Configurable Hop Limit (TTL)**:
  - Default: 5 hops (configurable from 1 to 10 in Settings).
  - Each intermediate device decrements TTL by 1 and increments `hopCount` by 1. When TTL reaches 0, forwarding halts.
- **Immutable Origin Signatures**:
  - `MeshEnvelope` excludes mutable routing fields (`ttl`, `hopCount`, `viaTransports`) from the Ed25519 signature calculation. This allows relay nodes to adjust hop metadata without invalidating the sender's cryptographic signature.
- **Two-Tier Idempotent Deduplication**:
  - **Tier 1 (In-Memory LRU)**: An ultra-fast, synchronized 4096-entry cache checks `packetId` in sub-microsecond time.
  - **Tier 2 (Persistent Database)**: The `seen_packets` table stores packet IDs and expiration timestamps in SQLCipher, ensuring duplicate packets received minutes or hours later across different transports are discarded.
- **Sliding-Window Anti-Spam Rate Limiting**:
  - Tracks packet arrival rates per sending Peer ID.
  - Caps inbound traffic at 45 packets per 10-second window. Any flood exceeding this is dropped immediately, protecting device memory and battery.

---

### 2.6 Store-and-Forward & Opaque Courier Mailboxes

Solves the intermittent connectivity problem inherent in mobile ad-hoc networks (MANETs):

- **Persistent Encrypted Outbox**:
  - When a message is sent to an offline peer, it is stored in the `outgoing_queue` table inside the encrypted Room database.
  - Survives app restarts, device reboots, and battery depletion.
- **Exponential Backoff Retries**:
  - Outbox scheduler checks for due packets with backoff intervals ($5\text{s}, 10\text{s}, 20\text{s}, 40\text{s}\dots$ up to 5 minutes).
- **Spray-and-Wait Courier Replication**:
  - Outgoing packets are assigned up to 3 courier replicas. When a trusted neighbor is encountered, a replica is handed off.
- **Zero-Knowledge Opaque Mailboxes**:
  - Courier nodes store only encrypted `MeshEnvelope` ciphertext.
  - **Couriers cannot read message plaintext**, tamper with headers, or decrypt payloads.
  - Enforces a strict 4 MB storage quota per device to prevent storage exhaustion.
- **Automatic Drainage**: The moment an offline recipient comes into radio contact with any courier node holding their mail, the envelopes are drained and delivered.

---

### 2.7 Public Channels & Full IRC Command System

- **Default Proximity Channel (`#nearby-mesh`)**:
  - The default room for all nodes within local radio hops.
- **Dynamic Topic Channels**:
  - Join or create custom public channels (e.g. `#medical`, `#rescue`, `#general`).
- **Full IRC Slash Command Engine**:
  Equipped with real-time autocompletion and input validation:
  - `/help` — Displays all supported commands and their parameters.
  - `/join <channel> [password]` — Joins or creates a public or password-protected room.
  - `/leave [channel]` — Leaves the specified or active channel.
  - `/msg <peer_id_or_nick> <text>` — Sends an end-to-end encrypted direct message.
  - `/who` — Lists all discovered mesh peers, their hop distance, and verification status.
  - `/nick <new_nickname>` — Updates your anonymous display nickname.
  - `/block <peer_id_or_nick>` — Blocks all packets from a specific node.
  - `/unblock <peer_id_or_nick>` — Removes a node from the blocklist.
  - `/clear` — Erases local chat history for the active conversation.
  - `/hug <nick>` — Sends an expressive IRC action hug.
  - `/slap <nick>` — Sends a classic IRC slap action.

---

### 2.8 Password-Protected Group Channels

For private group communication across untrusted mesh nodes:

- **Argon2id Key Derivation**:
  - Derives a 256-bit group master key from the channel password and a deterministic channel-bound salt using `Argon2id` (RFC 9106).
- **Epoch Ratcheting via HKDF-SHA256**:
  - Derives per-epoch keys using `HKDF-SHA256(rootKey, salt, "NoHead-GroupEpoch:<channel>:<epoch>")`.
- **Member Removal & Forward Secrecy**:
  - When the channel owner removes a compromised member, they tap **Rotate Key**.
  - `keyEpoch` increments, deriving a fresh symmetric key so evicted members cannot decrypt subsequent messages.
- **Transparent Insider Threat Disclosure**:
  - The UI explicitly clarifies: Password protection secures group messages against eavesdroppers on the mesh, but cannot prevent an authorized insider who knows the password from viewing messages.

---

### 2.9 1:1 End-to-End Encrypted Direct Messages (E2EE)

- **Noise XX-Inspired Mutual Authentication**:
  - Combines Static-Static X25519 Diffie-Hellman (authenticating long-term identities) with per-packet Ephemeral-Static X25519 Diffie-Hellman.
  - Guarantees forward secrecy: compromising a static key does not decrypt past ephemeral sessions.
- **Authenticated Encryption (AEAD)**:
  - Uses `ChaCha20-Poly1305` (with `AES-256-GCM` fallback) with unique 96-bit random nonces.
  - Associated Data (AAD) cryptographically binds the protocol version, packet ID, sender, recipient, and timestamp to the ciphertext tag.
- **Read Receipts & Delivery ACKs**:
  - When Phone B decrypts a direct message, it automatically signs and returns a `DELIVERY_ACK` envelope back across the mesh.
  - Once Phone B views the conversation, an optional user-controlled `READ_RECEIPT` is dispatched.

---

### 2.10 Coarse Geohash Location Rooms

- **Zero Mandatory Location Permission**:
  - Location permissions are **never** required for BLE messaging.
  - Geohash rooms operate via manual Base32 geohash selection or coarse location presets.
- **Precision vs. Privacy Tradeoff Slider**:
  - Geohash levels 1–6 adjust coverage radius:
    - Precision 1: Continental ($\pm 2500\text{ km}$).
    - Precision 2: Regional ($\pm 630\text{ km}$).
    - Precision 3: Provincial ($\pm 78\text{ km}$).
    - Precision 4: Metro City ($\pm 20\text{ km}$).
    - Precision 5: Urban District ($\pm 2.4\text{ km}$).
    - Precision 6: Neighborhood ($\pm 0.6\text{ km}$).
- **Global Preset Directory & Bookmarks**:
  - Preloaded with major world metro zones (Berlin `u33d`, London `gcpv`, NYC `dr5r`, Tokyo `xn76`, Bengaluru `tdr1`, etc.).
  - Users can bookmark frequently visited geohash areas for one-tap entry.

---

### 2.11 Media Attachments, Voice Notes & Live Push-to-Talk (PTT)

- **Strict Sandbox & MIME Validation**:
  - Executable extensions (`.apk`, `.sh`, `.dex`, `.so`, `.exe`, `.bat`) are blocked and rejected by protocol filters.
  - Filenames are sanitized to prevent directory traversal (`../`) vulnerabilities.
  - Files are stored inside sandboxed internal storage and **never auto-executed**.
- **SHA-256 Digest Verification**:
  - Receiving nodes verify the complete file against the sender's SHA-256 hash before presenting it to the user.
- **Voice Notes (AAC-LC)**:
  - Records mono audio at 16 kHz compressed via AAC-LC at 24 kbps (~3 KB per second).
  - Designed specifically to fit within low-bandwidth BLE chunk transfers.
- **Live Push-to-Talk (PTT)**:
  - Low-latency 8 kHz mono voice frames streaming in 100 ms packets.
  - Hold the PTT button to transmit live audio directly to listening nodes in the room.

---

### 2.12 Peer Directory, Trust Verification & Blocklist

- **Presence State Tracking**:
  - `ONLINE_NEARBY`: Discovered directly within 1–2 BLE/Wi-Fi Aware hops.
  - `ONLINE_INTERNET`: Connected via active Nostr relays.
  - `STALE`: No broadcast heard in the last 90–300 seconds.
  - `OFFLINE`: Absent for over 5 minutes.
- **QR Identity Exchange & Fingerprint Verification**:
  - Scan another user's QR code to compare their 128-bit fingerprint.
  - Tapping **Mark Fingerprint Verified** attaches a cryptographic verification badge, defending against Man-in-the-Middle (MITM) attacks.
- **Trusted Courier Designation**:
  - Mark trusted peers as designated couriers for store-and-forward routing.
- **One-Tap Peer Blocklist**:
  - Instantly drops all packets, direct messages, and relays originating from blocked nodes.

---

### 2.13 Live Network Diagnostics & Telemetry

Open the **Diag** tab to view real-time protocol statistics:
- Active Radio Transports (`BLE`, `NAN`, `NOSTR`, `SIM`).
- Discovered and Connected Peer Counts.
- Packets Sent, Received, and Multi-Hop Relayed.
- Duplicate Packets Suppressed by Bloom/Cache Filters.
- Rate-Limited Drops and Invalid Signature Drops.
- Store-and-Forward Outbox Count and Courier Mailbox Count.
- **Sanitized Export**: One-tap export to clean JSON containing **zero message content and zero private keys**, suitable for debugging mesh connectivity.

---

### 2.14 Emergency Privacy Wipe (Panic Wipe)

When personal safety or device compromise is imminent:

1. **Immediate Service Termination**: Shuts down the background service, BLE advertiser, scanner, and WebSocket connections instantly.
2. **Key Material Destruction**: Obliterates the Android Keystore AES-256 master key that wraps the SQLCipher database passphrase, rendering all database blocks permanently unrecoverable.
3. **Storage Sanitization**:
   - Drops all SQLite tables (`messages`, `peers`, `channels`, `outgoing_queue`, `courier_mailbox`).
   - Overwrites cached attachment and voice files with zeros before deletion.
4. **Clean Identity Re-roll**: Generates an entirely fresh, random keypair and unlinked identity.
5. **Honest Technical Limitations**:
   - Transparently warns the user: Flash wear-leveling controllers operate below the OS layer, so forensic physical memory recovery is possible unless full-disk hardware encryption is active.
   - Wiping this device cannot erase copies already delivered to other peers' devices.

---

## 3. Screen-by-Screen Walkthrough & User Interface Manual

```text
┌────────────────────────────────────────────────────────┐
│  [NO HEAD // silent_specter]         [BLE+NAN | D:3 C:2]│  <- Header & Transport Pill
├────────────────────────────────────────────────────────┤
│ [#nearby-mesh]  [#medical]  [#geo-u33d]  [+ New]       │  <- Channel Switcher Bar
├────────────────────────────────────────────────────────┤
│                                                        │
│  [cipher_node (88a4c1)] • hop:1 • BLE                  │
│  Anyone near Sector 4 with extra bandages?             │
│                                              14:22 ✓✓  │
│                                                        │
│                           [You] • hop:0                │
│                           Relaying supply convoy now.  │
│                                              14:23 ✓✓  │
│                                                        │
├────────────────────────────────────────────────────────┤
│ [/] Autocomplete: /msg, /who, /join, /help             │  <- IRC Autocomplete Bar
├────────────────────────────────────────────────────────┤
│ [📎] [🎤] [PTT] | Type a message or /command...   [➤] │  <- Composer Bar
├────────────────────────────────────────────────────────┤
│  [Chat] [People] [Channels] [DMs] [Favs] [Diag] [Set]  │  <- 7-Section Navigation
└────────────────────────────────────────────────────────┘
```

### 1. Main Navigation Bar (Bottom)
- **Chat**: The active message timeline, channel switcher, and composer.
- **People**: The peer directory showing nearby and internet peers, presence states, RSSI signal levels, and QR verification tools.
- **Channels**: Discover and join public mesh channels, password-protected rooms, and coarse Geohash areas.
- **DMs**: Private 1:1 conversation list with E2EE status and session ratcheting options.
- **Favs**: Quick filter displaying starred peers and designated trusted couriers.
- **Diag**: Real-time telemetry, radio matrix, packet counters, and diagnostic export.
- **Settings**: Identity management, theme controls, hop limits, Nostr relays, Tor proxy, and Emergency Wipe.

### 2. The Header Bar
- **Branding & Nickname**: Displays current anonymous alias.
- **Status Indicator**: Green dot indicates active connected mesh peers; amber indicates scanning/standby.
- **Transport Badge Pill**: Shows active physical transports (e.g. `[BLE+NAN | D:4 C:3]` indicates Bluetooth LE and Wi-Fi Aware active, 4 discovered peers, 3 connected links).

---

## 4. Real-World Use Cases & Operational Playbooks

### Playbook 1: Natural Disaster & Grid Outage (Telecom Collapse)

**Context**: Severe hurricane, earthquake, or flooding knocks down cellular towers and cuts municipal power. Internet, cellular voice, and SMS are dead.

**Step-by-Step Operations**:
1. **Initial Deployment**:
   - Launch No head. The app immediately initializes its local BLE GATT server and begins advertising.
   - All survivors and emergency volunteers within 30–80 meters form an instant local mesh island.
2. **Forming Relay Chains Across Camp / Neighborhood**:
   - Position phones at intermediate checkpoints (tents, vehicles, building floors).
   - Phone A at the medical tent sends a broadcast in `#nearby-mesh`: *"Need clean water at Station 2."*
   - Phone B (checkpoint midway) automatically decrements TTL and relays the packet.
   - Phone C at the logistics depot (150 meters away, out of range of Phone A) receives the message with `hop:1` and verified signature.
3. **Triage Channel Coordination**:
   - Volunteers type `/join #medical` to spin up a dedicated emergency medical channel.
   - Tapping the **Mic** button sends quick 3-second voice notes with status updates.
4. **Offline Courier Routing to Remote Search Teams**:
   - A search team heading deep into unpowered ruins needs to receive instructions.
   - Dispatcher sends an encrypted DM to the search lead. The message status shows `QUEUED`.
   - A courier volunteer walking supplies toward that sector passes within BLE range of the dispatcher; their phone picks up the opaque courier replica.
   - When the courier reaches the search team 1 km away, their phones connect over BLE, delivering the encrypted message automatically.

---

### Playbook 2: Remote Wilderness, Hiking & Maritime Expeditions

**Context**: A group of mountaineers or boaters travel outside cellular coverage areas across mountain ridges or along coastlines.

**Step-by-Step Operations**:
1. **Pre-Trip Fingerprint Exchange**:
   - Before departing the trailhead, members open **People -> My QR Code**.
   - Everyone scans each other's QR codes and taps **Mark Fingerprint Verified**.
   - Verified shields appear next to all team members, locking their cryptographic keys against MITM substitution.
2. **Trail Mesh Tracking**:
   - Keep No head running in the background with **Foreground Service** enabled.
   - As members hike at different paces, the peer directory displays real-time `ONLINE_NEARBY` and `STALE` indicators along with hop distances.
3. **Live Push-to-Talk Walkie-Talkie**:
   - When encountering a trail fork or obstacle, hold the **PTT** button on the chat screen to transmit instant 8 kHz voice messages to teammates within radio reach without fiddling with separate hardware radios.

---

### Playbook 3: Crowded Stadiums, Festivals & Demonstrations

**Context**: 80,000 people inside a sports stadium or music festival cause extreme cellular carrier congestion. 5G/LTE bars show full signal, but data packets time out and SMS messages fail to send.

**Step-by-Step Operations**:
1. **Bypassing the Overloaded Cellular Core**:
   - Turn off Wi-Fi and Mobile Data to stop battery drain from carrier retries. Leave Bluetooth ON.
   - No head communicates device-to-device over 2.4 GHz BLE frequencies, unaffected by cellular backhaul congestion.
2. **Dense Multi-Hop Hopping**:
   - With thousands of people in close proximity, BLE packets hop from seat to seat across the stadium.
   - A broadcast sent with TTL = 5 easily traverses from one side of the arena to the other in under 300 ms.
3. **Group Coordination with Password Channels**:
   - Friends coordinate via a shared secret room: `/join #festival2026 mysecretpass`.
   - Non-group festival attendees relay the encrypted packets transparently without being able to read the contents.

---

### Playbook 4: High-Risk Privacy, Activism & Whistleblowing

**Context**: Investigative journalists, human rights defenders, or whistleblowers operating under surveillance or hostile electronic monitoring.

**Step-by-Step Operations**:
1. **Anti-Tracking Configuration**:
   - Open **Settings**. Ensure **Battery Saver Mode** is set to preferred intervals and verify that rotating presence IDs are active.
   - Keep nicknames generic and rotate them frequently via `/nick <random_name>`.
2. **Verifying Cryptographic Identities**:
   - Never trust nicknames alone. Verify out-of-band using the 128-bit safety fingerprint.
   - Send messages exclusively via 1:1 Direct Messages (`/msg <peer> <text>`), ensuring Noise XX Ephemeral-Static forward secrecy is enforced.
3. **Handling App Pre-Seizure (Emergency Panic Wipe)**:
   - If device seizure is imminent, navigate to **Settings -> Trigger Emergency Privacy Wipe**.
   - Within 500 ms, all keys in the Android Keystore are destroyed, SQLCipher databases are wiped, attachments are zero-overwritten, and a clean, innocent state is established.

---

### Playbook 5: Mesh-to-Internet Gateway (Hybrid BLE + Nostr)

**Context**: A rural village or isolated research outpost has zero cellular coverage, but one base camp tent has an intermittent satellite internet terminal (Starlink).

**Step-by-Step Operations**:
1. **Gateway Configuration**:
   - The phone at the base camp connects to the satellite Wi-Fi and opens No head.
   - In **Settings**, they toggle on a Nostr relay (e.g. `wss://relay.damus.io`) and optionally enable Tor SOCKS5.
2. **Bridging the Local Mesh**:
   - Village members communicate locally over BLE mesh into `#nearby-mesh`.
   - When important updates need to reach the global community, messages are posted to `#global-nostr` or `#geo-<hash>`.
   - The base camp gateway phone receives the packet over local BLE and automatically mirrors it across the WebSocket connection to the global Nostr network.
   - Responses from the outside world travel back down through the satellite link and hop through the local BLE mesh to villagers' phones.

---

## 5. Troubleshooting & Platform Limitations FAQ

### Q1: Why can't two phones find each other over BLE?
1. **Check Bluetooth Switch**: Ensure Bluetooth is toggled ON in Android system settings on both phones.
2. **Check App Permissions**:
   - On Android 12+ (API 31+): Ensure **Nearby Devices** (`BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`) permission is set to "Allow".
   - On Android 8.0–11 (API 26–30): Ensure **Location** is toggled ON at the OS level (Android OS architectural limitation for legacy BLE scanning).
3. **Tap Refresh**: In the **People** tab, tap the refresh icon in the upper right to trigger an immediate discovery probe.

### Q2: Does No head require phone pairing in Android Bluetooth settings?
**No!** Never pair phones in the Android OS Bluetooth menu. No head uses low-energy GATT Server/Client connections established directly inside the application. Pairing in system settings will interfere with mesh operation.

### Q3: How do I ensure background relaying works when my screen is off?
1. In No head **Settings**, enable **Foreground BLE Mesh Relay Service**. A persistent status notification will appear.
2. In Android system settings for No head, navigate to **Battery -> App Battery Usage** and select **Unrestricted** (prevents aggressive vendor battery killers like Xiaomi MIUI, Samsung OneUI, or OnePlus OxygenOS from killing the radio).

### Q4: What is the maximum file size I can send over BLE?
Over pure Bluetooth Low Energy, file attachments are capped at **256 KB** to prevent channel saturation. For larger files up to **10 MB**, ensure **Wi-Fi Aware** is supported and enabled on both devices.

### Q5: Can courier nodes read my private direct messages?
**No.** Courier mailboxes only hold opaque AEAD ciphertext (`ChaCha20-Poly1305`). Only the intended recipient holding the matching private key can decrypt the message.

