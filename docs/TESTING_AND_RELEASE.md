# System Utility — Verification, Release Signing & Two-Device Testing Guide

## 1. Automated Verification Commands

### 1.1 Cloudflare Backend (TypeScript + Vitest + Wrangler Dry-Run)

```powershell
cd d:\LiveLoc\system-utility\backend
npm run typecheck
npm test
npx wrangler deploy --dry-run --env staging
npx wrangler deploy --dry-run --env production
```

Covered Vitest suites (`src/__tests__/`):
- [`auth.test.ts`](../backend/src/__tests__/auth.test.ts) — Argon2id + PBKDF2-SHA256 login, rate limiting & 5-attempt lockout, recovery-code password rotation (revoking prior devices/sessions), first-device auto-bootstrap, owner approval/rejection of secondary devices, `audit_events` recording, and strict 2-user isolation.
- [`messaging.test.ts`](../backend/src/__tests__/messaging.test.ts) — Signal PQXDH PreKey directory upload, atomic one-time prekey popping, idempotent encrypted send, incremental cursor `/sync`, delivery/read receipts, edits, and deletes.
- [`media.test.ts`](../backend/src/__tests__/media.test.ts) — Private R2 encrypted media upload initialization, 15m upload grant expiry, SHA-256 ciphertext enforcement, short-lived 120s download grant authorization, and rejection of ungranted blob fetches.
- [`calls.test.ts`](../backend/src/__tests__/calls.test.ts) — Cloudflare Realtime TURN ICE server credential minting (`CF_TURN_KEY_ID` + `CF_TURN_API_TOKEN`), clean `503 TURN_NOT_CONFIGURED` / `502 TURN_UPSTREAM_ERROR` handling, and partner-restricted call logging.
- [`backup.test.ts`](../backend/src/__tests__/backup.test.ts) — Profile encrypted avatar key persistence and per-user isolated backup manifest routes.

### 1.2 Android Multi-Module Build & Unit Tests

```powershell
cd d:\LiveLoc\system-utility\android
.\gradlew.bat testDebugUnitTest assembleRelease --no-daemon
```

Covered unit test suites:
- `:core` — [`PreferencesAndConfigSerializationTest.kt`](../android/core/src/test/java/com/systemutility/core/model/PreferencesAndConfigSerializationTest.kt) (Appearance, Privacy, Notification prefs, and E2EE Envelope serialization).
- `:core-security` — [`StreamCipherEngineTest.kt`](../android/core-security/src/test/java/com/systemutility/core/security/StreamCipherEngineTest.kt) (Multi-chunk AES-256-GCM streaming encryption/decryption & tamper detection).
- `:core-database` — [`SignalRoundTripTest.kt`](../android/core-database/src/test/java/com/systemutility/core/database/signal/SignalRoundTripTest.kt) (Full Signal PQXDH handshake with Kyber-1024 + bidirectional Double Ratchet encryption) & [`SignalPersistenceAcrossRestartTest.kt`](../android/core-database/src/test/java/com/systemutility/core/database/signal/SignalPersistenceAcrossRestartTest.kt) (Persistent Signal identity/session survival across process/update restarts).
- `:core-network` — [`GitHubUpdateUtilsTest.kt`](../android/core-network/src/test/java/com/systemutility/core/network/update/GitHubUpdateUtilsTest.kt) (SemVer comparison, GitHub `owner/repo` normalization, ABI/universal HTTPS APK selection, and SHA-256 checksum parsing).
- `:feature-calls` — [`CallSignalingModelsTest.kt`](../android/feature-calls/src/test/java/com/systemutility/feature/calls/CallSignalingModelsTest.kt) (WebRTC SDP/ICE signaling DTOs, Cloudflare Realtime `TurnCredentialsResponse` STUN/TURN UDP/TCP/TLS parsing, and manual recording idle defaults).
- `:feature-backup` — [`BackupEncryptionAndRestoreTest.kt`](../android/feature-backup/src/test/java/com/systemutility/feature/backup/BackupEncryptionAndRestoreTest.kt) (Encrypted GZIP local backup archive round-trip, wrong-key rejection, and SHA-256 tamper rejection).

### 1.3 Generated Release APKs

With ABI splits and desktop/test binary stripping in [`app/build.gradle.kts`](../android/app/build.gradle.kts), Gradle outputs release APKs under `android/app/build/outputs/apk/release/`:
- `app-arm64-v8a-release.apk` (recommended for modern 64-bit Android phones)
- `app-armeabi-v7a-release.apk`
- `app-x86_64-release.apk` (recommended for x86_64 Android emulators)
- `app-universal-release.apk`

---

## 2. Production Release Keystore Signing & GitHub Actions Auto-Updates

[`android/app/build.gradle.kts`](../android/app/build.gradle.kts) supports both Gradle properties and environment variables (`SYSTEM_UTILITY_KEYSTORE_PATH`, `SYSTEM_UTILITY_KEYSTORE_PASSWORD`, `SYSTEM_UTILITY_KEY_ALIAS`, `SYSTEM_UTILITY_KEY_PASSWORD`), falling back to `debug` signing when unset so local builds work out of the box.

> **Important for In-Place Data Persistence**: Android requires every updated APK (`v1.0.1` over `v1.0.0`) to be signed by the **exact same signing certificate**. Always use a persistent release keystore locally and in GitHub Actions so users never have to uninstall or wipe their encrypted SQLCipher chat history.

1. Generate a private release keystore outside the repository (e.g. `C:\Keys\system-utility-release.jks`):
   ```powershell
   keytool -genkey -v -keystore C:\Keys\system-utility-release.jks -alias systemutility -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Add GitHub Actions Repository Secrets (for [`.github/workflows/android-release.yml`](../.github/workflows/android-release.yml)):
   - `ANDROID_KEYSTORE_BASE64` (base64 of `.jks` file)
   - `ANDROID_KEYSTORE_PASSWORD`
   - `ANDROID_KEY_ALIAS`
   - `ANDROID_KEY_PASSWORD`
3. Push a version tag (`git tag v1.0.1 && git push origin v1.0.1`) to build signed ABI APKs + `checksums.txt` and publish to GitHub Releases. *(Note: GitHub Actions only runs workflows located at the root `.github/workflows/` of your pushed repository.)*

*(No Google Play Services SHA-1 / Firebase Console fingerprint registration is needed because Firebase and Google Drive OAuth are excluded in this release.)*

---

## 3. Two-Device End-to-End Acceptance Checklist

Install `app-arm64-v8a-release.apk` on two physical Android devices (`Device A` = Account 1, `Device B` = Account 2) pointing to your deployed Worker URL:

1. **Authentication, Lockout & Owner Device Approval**:
   - Sign in on `Device A` (bootstraps automatically as Approved #1) and `Device B` (bootstraps automatically as Approved #1 for Account 2).
   - Attempt a login for Account 1 on a third test device -> confirm `Owner Approval Required` banner on login screen, approve or reject it from `Device A` under *Active Devices & Sessions*, and confirm `/api/v1/security/audit` logs the event.
   - Verify unknown usernames or >5 bad passwords trigger the server rate-limit lockout countdown without leaking whether a username exists.
2. **E2EE Messaging, WebSockets & WorkManager Sync**:
   - Send text, emoji reactions, replies, edits, pins, and Delete-for-Everyone between `Device A` and `Device B` while connected via WebSocket.
   - Airplane-mode `Device A`, send 3 messages (confirm queued banner), disable Airplane mode, and verify `MessageSyncWorker` / WebSocket flushes cleanly without duplicates.
   - Confirm Notification Settings clearly shows that live delivery uses WebSockets while connected + WorkManager sync recovery, without claiming instant terminated-app wakeups.
3. **E2EE Media & Voice Notes (`arpii` R2 Bucket)**:
   - Send a photo, video, PDF, and locked voice message (`1x` / `1.5x` / `2x` playback speed + live waveform).
   - Verify unauthenticated / ungranted `GET /api/v1/media/blob/...` fails cleanly and objects in R2 bucket `arpii` exist only as encrypted binary blobs.
4. **Cloudflare Realtime TURN Calling & Manual Recording**:
   - Place both a Voice Call and Video Call across different networks (Wi-Fi on A, cellular on B) to exercise Cloudflare Realtime TURN (`turn:` UDP/TCP + `turns:` TLS) + automatic ICE restart.
   - Tap the manual **Record** button during an active call; verify persistent `LOCAL RECORDING ACTIVE` notification and blinking `REC` HUD pill, tap Pause/Resume/Stop, then verify encrypted playback inside *Call History -> Recordings*.
5. **Personalization, Safety Number & App Lock**:
   - Update avatar & display name on `Device A` and confirm encrypted avatar download via short-lived R2 grant renders on `Device B`.
   - Compare the 60-digit **Safety Number** side-by-side and toggle verification.
   - Enable *App Lock PIN* + *Biometrics* and *Screenshot Shield (`FLAG_SECURE`)*.
6. **Deferred Backup Placeholder & Local Encrypted Snapshots**:
   - Open *Backup & Restore (Deferred)*, confirm no Google OAuth prompt is triggered and the warning explaining Cloudflare storage != complete backup is visible, create a *Local Encrypted Snapshot*, and test restoring it with the 64-char hex Backup Recovery Key.
