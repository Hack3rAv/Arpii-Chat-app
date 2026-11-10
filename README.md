# System Utility — Two-User Private E2EE Messenger

**System Utility** is a strictly **two-person-only** end-to-end encrypted Android private messaging and WebRTC calling platform built with Kotlin/Jetpack Compose on Android and Cloudflare Workers + Durable Objects (`ChatCoordinatorDO`) + D1 (`arpii`) + R2 (`arpii`, APAC) + Cloudflare Realtime TURN on the edge.

---

## Key Capabilities & Current Release Boundaries

- **Strict Two-User Architecture**: Enforced at SQL schema level (`CHECK (slot_id IN (1, 2))` + `enforce_two_users_only` insert trigger), Worker API authorization, owner-approved secondary device enrollment, and single-pair `ChatCoordinatorDO` WebSocket routing. No public sign-up, discovery, or group chats exist.
- **Zero Plaintext Credentials or Messages in Cloud**:
  - Authentication combines client-side **Argon2id** (`64 MiB`, `t=3`, `p=1`) + server **PBKDF2-HMAC-SHA256** (`100,000` iters) + anti-enumeration HMAC salts + 5-attempt lockout + single-use recovery codes + explicit owner approval for secondary devices + security audit logging in D1 `audit_events`.
  - Messaging uses **Signal Protocol PQXDH (X25519 + Kyber-1024) + Double Ratchet** via `org.signal:libsignal-android`.
  - Photos, videos, PDFs, voice messages, and avatars are encrypted client-side with unique 256-bit **AES-256-GCM** stream keys, SHA-256 verified, and uploaded to private Cloudflare **R2** (`arpii`) with short-lived upload/download grants.
- **Offline-First SQLCipher Vault**: Messages, Signal key material, attachment metadata, and call history live inside a **SQLCipher AES-256 Room database** protected by a hardware-backed **Android Keystore** master key.
- **Firebase-Free Delivery Architecture**: Firebase and FCM are **excluded** from this release. Real-time messages arrive over authenticated WebSockets (`WSS /api/v1/ws`) while connected, paired with **WorkManager** periodic/retry sync recovery (`BackgroundDeliveryProvider` / `MessageSyncWorker`). Instant push notifications after Android force-stops or terminates the app are not supported in this release.
- **Current Release Status (Chat Live Now)**:
  - **Active**: 1:1 Signal E2EE text messaging (replies, reactions, edits, pins, delete-for-everyone, disappearing timers), E2EE photos/videos/documents/voice notes, encrypted media gallery, profiles/avatars, theme & privacy settings, App Lock PIN/Biometrics, Safety Number verification, and Active Sessions & Device Approvals.
  - **Coming Soon**: Encrypted WebRTC Voice & Video Calls / Call Recording (`FeatureFlags.CALLS_ENABLED = false`) and Automated Cloud Backup & Restore (`FeatureFlags.BACKUP_ENABLED = false`).
- **GitHub Releases Auto-Update & In-Place Data Persistence**:
  - Built-in auto-updater queries `https://api.github.com/repos/<owner>/<repo>/releases/latest` (configurable in `gradle.properties` via `SYSTEM_UTILITY_GITHUB_REPO` or in-app under **Settings -> App Updates**), selects ABI-matched APKs, verifies SHA-256 checksums, and prompts Android's installer cleanly.
  - Paired with a persistent release signing keystore (`ANDROID_KEYSTORE_*` / `SYSTEM_UTILITY_KEYSTORE_*`) and exported Room schema migrations, updates upgrade in-place while preserving SQLCipher messages, attachments, preferences, and Signal keys.
- **Appearance, Privacy & App Lock**: Theme modes, customizable bubble colors/corner radius/font scale/wallpapers, discreet lock-screen masking (`Background sync notification`), 60-digit Safety Number verification, `FLAG_SECURE` screenshot shield, and Argon2id PIN + Android Biometric app lock.

---

## Repository Structure

```text
system-utility/
├── README.md
├── docs/
│   ├── ARCHITECTURE.md               # Security model, data flow, and system blueprint
│   ├── DEPLOYMENT.md                 # Cloudflare D1/R2/Workers, Realtime TURN, and account seeding
│   └── TESTING_AND_RELEASE.md        # Automated tests, R8 APKs, release signing, and QA checklist
├── infra/
│   └── coturn/                       # Deprecated self-hosted coturn configs (replaced by Cloudflare Realtime TURN)
├── backend/                          # TypeScript Cloudflare Workers + DO + D1 + R2 + Vitest suite
│   ├── migrations/                   # 0001_initial_schema, 0002_indexes_and_triggers, 0003_device_approvals_and_audit
│   ├── scripts/provision-accounts.mjs
│   └── src/
└── android/                          # 14-module Gradle Kotlin + Compose Android project (Firebase-free)
    ├── app/
    ├── core/, core-ui/, core-security/, core-database/, core-network/
    └── feature-{auth,chat,media,voice,calls,settings,backup,profile}/
```

---

## Quick Verification Commands

### Backend (TypeScript + Vitest + Wrangler Dry-Run)

```powershell
cd d:\LiveLoc\system-utility\backend
npm ci
npm run typecheck
npm test
npx wrangler deploy --dry-run --env production
```

### Android (Unit Tests + R8 Release APK)

```powershell
cd d:\LiveLoc\system-utility\android
.\gradlew.bat testDebugUnitTest assembleRelease --no-daemon
```

Generated APKs are placed in `d:\LiveLoc\system-utility\android\app\build\outputs\apk\release\` (use `app-arm64-v8a-release.apk` for modern Android devices or `app-x86_64-release.apk` for emulators).

---

## Next Guides

1. Read [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) for the cryptographic model and Firebase-free delivery design.
2. Follow [`docs/DEPLOYMENT.md`](./docs/DEPLOYMENT.md) to configure Worker secrets (`AUTH_PEPPER`, `CF_TURN_KEY_ID`, `CF_TURN_API_TOKEN`), deploy to Cloudflare Workers (`--env staging` / `--env production`), and provision your two user accounts into D1 `arpii`.
3. Follow [`docs/TESTING_AND_RELEASE.md`](./docs/TESTING_AND_RELEASE.md) to sign releases and run two-device acceptance testing.
