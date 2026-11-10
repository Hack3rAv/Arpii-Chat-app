# System Utility — Technical Architecture & Security Model

## 1. Executive Summary & Architectural Principles

**System Utility** is a two-user-only, end-to-end encrypted Android private messenger designed with strict cryptographic separation between authentication, transport, persistence, media storage, and calling.

### Non-Negotiable Constraints
1. **Strict Two-User Boundary**: Enforced at database schema (`CHECK (slot_id IN (1, 2))` and `enforce_two_users_only`), Worker API authorization middleware, and Durable Object WebSocket routing. Third-party accounts, group rooms, and public discovery do not exist.
2. **Owner-Approved Device Enrollment & Audit Trail**: The first active device for an account bootstraps automatically as `APPROVED`; subsequent device logins are registered as `PENDING` (`403 DEVICE_APPROVAL_REQUIRED`) until an authorized owner device explicitly approves them under **Active Devices & Sessions**. Security events (login, approval, rejection, revocation, recovery, media grants, TURN minting) are recorded in D1 `audit_events` with HMAC-peppered IP hashes.
3. **True End-to-End Encryption (PQXDH + Double Ratchet)**: Client-side Signal Protocol (`org.signal:libsignal-android`) encrypts all message bodies and media key envelopes on-device. Cloudflare D1 (`arpii`), R2 (`arpii`), and Durable Objects see only authenticated ciphertext and minimum routing metadata.
4. **Zero Plaintext Secrets in Repo or APK**: Initial account credentials are provisioned directly to D1 via [`backend/scripts/provision-accounts.mjs`](../backend/scripts/provision-accounts.mjs) using Argon2id + PBKDF2-SHA256 verifiers. Worker secrets (`AUTH_PEPPER`, `CF_TURN_KEY_ID`, `CF_TURN_API_TOKEN`) live only in Cloudflare Worker secrets.
5. **Firebase-Free Realtime & Deferred Delivery**: Firebase/FCM is excluded. Live messages, receipts, and WebRTC signaling flow over authenticated WebSockets (`ChatCoordinatorDO`) while connected, backed by WorkManager deferred sync (`BackgroundDeliveryProvider` / `MessageSyncWorker`). Android cannot guarantee instant wakeup once terminated by the OS.
6. **Honest & Compliant Local Call Recording**: Call recording captures only explicit manual start/pause/stop local streams with a persistent OS foreground indicator and `REC` HUD badge, encrypted in app-private storage.
7. **Deferred Cloud Backup Strategy**: Google Drive OAuth backup is deferred in this release. `:feature-backup` retains the AES-256-GCM local snapshot archive codec (`BackupArchiveCodec`) and Recovery Key UI as a local-only placeholder. Cloudflare storage holds encrypted relay data and is **not** a complete backup strategy.

---

## 2. Project Structure

```text
d:\LiveLoc\system-utility\
├── docs\
│   ├── ARCHITECTURE.md
│   ├── DEPLOYMENT.md
│   └── TESTING_AND_RELEASE.md
├── infra\
│   └── coturn\                       # Deprecated self-hosted coturn artifacts (replaced by Cloudflare Realtime TURN)
├── backend\                            # Cloudflare Workers + Durable Objects + D1 (arpii) + R2 (arpii)
│   ├── wrangler.toml                   # Shared arpii D1/R2 bindings + [env.staging] / [env.production]
│   ├── migrations\
│   │   ├── 0001_initial_schema.sql
│   │   ├── 0002_indexes_and_triggers.sql
│   │   └── 0003_device_approvals_and_audit.sql
│   ├── scripts\
│   │   └── provision-accounts.mjs      # Offline CLI account seeder targeting D1 `arpii`
│   └── src\
│       ├── shared\                     # Env types, zod validation, WebCrypto utils, redacted logger
│       ├── database\                   # D1 repositories (Auth/Session/Audit, Messaging, Media, Calls)
│       ├── authentication\             # Rate-limited login, device approvals, recovery, audit route
│       ├── messaging\                  # PreKey directory + ciphertext mailbox
│       ├── storage\                    # Short-lived R2 upload (15m) & HMAC download (120s) grants
│       ├── signaling\                  # Cloudflare Realtime TURN API client & call logs
│       ├── notifications\              # Replaceable WakeDispatcher interface + WebSocketOnlyWakeDispatcher
│       ├── durable-objects\            # ChatCoordinatorDO (WebSocket presence, relay, WebRTC signaling)
│       ├── workers\                    # Worker entrypoint, CORS/header middleware, D1/R2 health check
│       └── __tests__\                  # Vitest + Miniflare integration tests
└── android\                            # Firebase-free 14-module Kotlin + Jetpack Compose app
    ├── app\                            # SystemUtilityApplication, MainActivity, NavGraph
    ├── core\                           # Models, Dispatchers, AppResult, SafeLogger, BackgroundDeliveryProvider
    ├── core-ui\                        # Material 3 Theme, LockOverlay, Shared Components
    ├── core-database\                  # SQLCipher Room DB, DAOs, Preferences DataStore, Signal Store
    ├── core-network\                   # TLS OkHttp Client, Retrofit REST APIs, Reconnecting WebSocket
    ├── core-security\                  # Keystore MasterKey, Argon2id KDF, AES-GCM StreamCipherEngine
    ├── feature-auth\                   # Splash, Login, Owner Device Approval UI, Sessions, Password Recovery
    ├── feature-chat\                   # Conversation Home, 1:1 Chat, MessageSyncWorker, WorkManagerDelivery
    ├── feature-media\                  # Stream-encrypted R2 upload + grant-authorized download, Gallery
    ├── feature-voice\                  # Voice Recorder + Speed Playback
    ├── feature-calls\                  # WebRTC Audio/Video, Cloudflare TURN refresh + ICE restart, Local Rec
    ├── feature-settings\               # Appearance, Stealth Notifs (WebSocket/WorkManager notice), App Lock
    ├── feature-backup\                 # Deferred Drive placeholder + local AES-256-GCM snapshot archive
    └── feature-profile\                # Self & Partner Profile Edit, Encrypted Avatar
```

---

## 3. Data-Flow Architecture Diagram

```text
+-----------------------------------------------------------------------------------+
|                                ANDROID CLIENT (Device A)                          |
|  [Compose UI] -> [core-security: Argon2id, Signal PQXDH, AES-256-GCM Stream]      |
|               -> [core-database: SQLCipher Room Vault & Outbox Queue]             |
+---------+-----------------------------------------------+-------------------------+
          | (1) HTTPS TLS 1.3 + WSS (Bearer Session Token + Approved Device ID)
          | (2) WebRTC DTLS-SRTP Media (Direct P2P or Cloudflare Realtime TURN)
          v
+-----------------------------------------------------------------------------------+
|                     CLOUDFLARE EDGE (Account af489b4dae7c64cf...)                 |
|  [Worker Router (`system-utility-backend`)]                                       |
|   |-- Auth & Device Approval ----> [D1 `arpii`: Users(<=2), Devices, Sessions,    |
|   |                                 AuditEvents, PreKeys, Ciphertext, CallLogs]   |
|   |-- /api/v1/media/* -----------> [R2 `arpii` (APAC): AES-256-GCM Blobs Only]   |
|   |-- /api/v1/turn-credentials --> [Cloudflare Realtime TURN Key API]             |
|   |                                 POST /v1/turn/keys/{CF_TURN_KEY_ID}/...       |
|   |-- WSS /api/v1/ws ------------> [Durable Object: ChatCoordinatorDO]            |
|                                     Presence, Typing, Relay, Receipts, WebRTC ICE |
+---------+-----------------------------------------------+-------------------------+
          | (3) WebSocket live delivery while connected (or WorkManager sync on reconnect)
          v
+-----------------------------------------------------------------------------------+
|                                ANDROID CLIENT (Device B)                          |
|  WorkManager BackgroundDeliveryProvider (15m periodic + immediate retry sync)     |
|  -> Signal PQXDH decrypt -> SQLCipher DB -> Authorize & stream R2 ciphertext      |
+-----------------------------------------------------------------------------------+
```

---

## 4. Security & Cryptography Model

| Security Layer | Mechanism | Key Storage & Scope | Trust Boundary |
| :--- | :--- | :--- | :--- |
| **Transport** | HTTPS TLS 1.3 & WSS | Android system CAs + Cloudflare edge TLS | Protects client <-> Worker API & signaling traffic. |
| **Authentication** | Client **Argon2id** (`64 MiB, t=3, p=1`) + Server **PBKDF2-HMAC-SHA256** (`100k` iters) + pepped fake salts | D1 `arpii` stores only PBKDF2 verifier | 5-attempt lockout per account/IP; zero plaintext passwords. |
| **Device Authorization** | First device `APPROVED`; secondary devices `PENDING` (`403 DEVICE_APPROVAL_REQUIRED`) until owner approves | `devices.approval_status` in D1 `arpii` | Prevents unauthorized reuse of credentials on an unapproved device; audited in `audit_events`. |
| **Messaging E2EE** | **Signal Protocol PQXDH + Double Ratchet** via `libsignal-android` | Private Identity/PreKeys/Ratchet state in SQLCipher Room + Android Keystore | Worker/DO route ciphertext only. **True E2EE**. |
| **Media & Avatar E2EE** | Per-object CSPRNG **AES-256-GCM** stream key; 15m upload reservation; 120s HMAC download grant | Keys embedded inside Signal E2EE envelope | R2 `arpii` holds only opaque encrypted blobs + SHA-256 checksums. |
| **Voice & Video Calls** | **WebRTC DTLS-SRTP** P2P or **Cloudflare Realtime TURN** UDP/TCP/TLS relay | Ephemeral TURN credentials minted server-side (`CF_TURN_KEY_ID` + `CF_TURN_API_TOKEN`) | Android refreshes credentials ~60s before expiry and handles `DISCONNECTED`/`FAILED` ICE restarts cleanly. |
| **Backup Placeholder** | Local GZIP + **AES-256-GCM + SHA-256** archive (`BackupArchiveCodec`) | Independent Backup Recovery Key in Keystore-backed prefs | Google Drive OAuth deferred; local snapshot only until a future remote provider is selected. |
