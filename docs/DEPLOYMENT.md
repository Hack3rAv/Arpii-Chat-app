# System Utility — Deployment & Provisioning Guide

Operator guide for configuring and deploying **System Utility** using the existing Cloudflare account resources (`af489b4dae7c64cf5ae95206e0ac2a45`), without creating duplicate databases or buckets.

> **Security Reminder**: Never commit Worker secrets (`AUTH_PEPPER`, `CF_TURN_KEY_ID`, `CF_TURN_API_TOKEN`), release keystores, `.dev.vars`, or `.provision-seed.sql` into version control.

---

## 1. Pre-Created Cloudflare Infrastructure

Configured in [`backend/wrangler.toml`](../backend/wrangler.toml) with separate `[env.staging]` (`system-utility-backend-staging`) and `[env.production]` (`system-utility-backend`) sections:

- **Cloudflare Account ID**: `af489b4dae7c64cf5ae95206e0ac2a45`
- **D1 Database**: `arpii` (ID: `f66221ed-7d73-475a-b65a-548436ecc380`) — stores accounts, sessions, owner-approved devices, `audit_events`, prekey bundles, encrypted message envelopes, call logs, and encrypted media metadata.
- **R2 Bucket**: `arpii` (APAC region) — stores encrypted media/avatar blobs only behind authenticated short-lived Worker upload (`15 min`) and HMAC-signed download (`120 sec`) grants.
- **Durable Object**: `ChatCoordinatorDO` (`CHAT_COORDINATOR`) — authenticated WebSocket presence, encrypted message relay, receipts, and WebRTC signaling.
- **Excluded / Deferred Services**: Firebase (FCM) and Google Drive backup/OAuth are **not** used in this release.

---

## 2. Backend Verification & D1 Migrations

```powershell
cd d:\LiveLoc\system-utility\backend
npm ci
npm run typecheck
npm test
npx wrangler deploy --dry-run --env staging
npx wrangler deploy --dry-run --env production
```

### Non-Destructive D1 Migrations

Migrations in [`backend/migrations/`](../backend/migrations/):
1. [`0001_initial_schema.sql`](../backend/migrations/0001_initial_schema.sql) — core tables (`users`, `devices`, `sessions`, `signal_*`, `encrypted_messages`, `encrypted_media_objects`, `call_logs`, etc.).
2. [`0002_indexes_and_triggers.sql`](../backend/migrations/0002_indexes_and_triggers.sql) — covering indexes + `enforce_two_users_only` insert trigger.
3. [`0003_device_approvals_and_audit.sql`](../backend/migrations/0003_device_approvals_and_audit.sql) — additive `ALTER TABLE` columns for device approval workflow (`approval_status`, `approved_at`, `approved_by_device_id`), `upload_expires_at`, and the `audit_events` table.

Apply to local Miniflare D1 and remote `arpii`:

```powershell
npm run db:migrate:local
npm run db:migrate:remote
```

---

## 3. Obtain & Set Required Worker Secrets

Create your **Cloudflare Realtime TURN Key** in the Cloudflare Dashboard (do **not** confuse the Realtime **App ID** with a TURN Key ID):
1. Cloudflare Dashboard -> **Realtime** (or **Calls**) -> **TURN Keys** -> **Create**.
2. Copy the **TURN Key ID** (`CF_TURN_KEY_ID`) and **TURN API Token** (`CF_TURN_API_TOKEN`).
3. Generate a 32+ byte random hex/base64 secret for `AUTH_PEPPER` (used for anti-enumeration fake salts, HMAC-peppered IP audit hashes, and short-lived R2 download grants).

Upload secrets per environment (**never paste secrets into source files or chat**):

```powershell
cd d:\LiveLoc\system-utility\backend

# Production
npx wrangler secret put AUTH_PEPPER --env production
npx wrangler secret put CF_TURN_KEY_ID --env production
npx wrangler secret put CF_TURN_API_TOKEN --env production

# Optional: Staging
npx wrangler secret put AUTH_PEPPER --env staging
npx wrangler secret put CF_TURN_KEY_ID --env staging
npx wrangler secret put CF_TURN_API_TOKEN --env staging
```

*(For local `wrangler dev`, create an untracked `backend/.dev.vars` file with those 3 keys.)*

---

## 4. Live Cloudflare Worker & Endpoint

The production Worker is deployed and bound to `arpii` D1 + `arpii` R2 + `ChatCoordinatorDO`:

- **Production Worker URL**: `https://system-utility-backend.avrodipff.workers.dev` (configured as the default Android endpoint in `android/gradle.properties`)
- Required auth secret `AUTH_PEPPER` is provisioned in production (`CF_TURN_KEY_ID` / `CF_TURN_API_TOKEN` can be added later before re-enabling calls).

To deploy updates:

```powershell
cd d:\LiveLoc\system-utility\backend

# Deploy Staging (optional verification first)
npx wrangler deploy --env staging

# Deploy Production
npx wrangler deploy --env production
```

Verify live health check:

```powershell
Invoke-RestMethod https://system-utility-backend.avrodipff.workers.dev/api/v1/health
```

Expected response confirms `checks.d1 == true`, `checks.r2 == true`, `checks.firebaseExcluded == true`, and `checks.driveBackupDeferred == true`.

---

## 5. Provision the Two Authorized Accounts (Offline CLI)

Public sign-up is disabled everywhere. Seed User 1 and User 2 directly into D1 `arpii` without hardcoding passwords:

```powershell
cd d:\LiveLoc\system-utility\backend
npm run provision
```

`provision-accounts.mjs` prompts for usernames, display names, and min-10-character passwords, hashes both passwords + generated single-use **Recovery Codes** via Argon2id + PBKDF2-SHA256, and writes `.provision-seed.sql`.

Apply the seed file to remote `arpii`, store the printed Recovery Codes securely offline, and immediately remove `.provision-seed.sql`:

```powershell
npx wrangler d1 execute arpii --remote --file=.provision-seed.sql
Remove-Item .provision-seed.sql
```

*(Note: After deploying your Worker, you should also seed both phones' first-device approval flow as expected: the very first device login per account bootstraps as `APPROVED`, while any secondary device requires approval from the already-signed-in device under Settings -> Active Devices & Sessions.)*

---

## 6. Build Android APK & Configure Git Releases Auto-Updates

Pass your deployed Worker URL and GitHub Releases repository (`owner/repo`) via `android/gradle.properties` or Gradle `-P` flags:

```powershell
cd d:\LiveLoc\system-utility\android
.\gradlew.bat testDebugUnitTest assembleRelease --no-daemon -PSYSTEM_UTILITY_DEFAULT_BASE_URL="https://system-utility-backend.avrodipff.workers.dev" -PSYSTEM_UTILITY_GITHUB_REPO="Hack3rAv/Arpii-Chat-app"
```

Users can also change or set their `owner/repo` at runtime under **Settings -> App Updates (Git Releases)** without reinstalling.

---

## 7. API Surface Summary

| Endpoint / Protocol | Purpose |
| :--- | :--- |
| `GET /api/v1/health` | D1, R2, TURN config & release posture check |
| `POST /api/v1/auth/salt`, `login`, `recover`, `change-password`, `logout` | Argon2id + PBKDF2 two-user auth, lockout & recovery |
| `GET /api/v1/auth/sessions`, `POST .../approve`, `POST .../reject`, `POST .../revoke` | Owner-approved device enrollment & session/device revocation |
| `GET /api/v1/security/audit` | Per-user security & device audit event log |
| `GET / PATCH /api/v1/profile` | Self/partner profile & encrypted avatar metadata |
| `POST /api/v1/keys/bundle`, `GET /api/v1/keys/partner` | Signal PQXDH Identity, Signed, Kyber-1024 & One-Time PreKeys |
| `POST /api/v1/messages/send`, `GET .../sync`, `POST .../ack`, `POST .../{id}/edit`, `POST .../{id}/delete` | Ciphertext mailbox, cursor sync & receipts |
| `POST /api/v1/media/init-upload`, `PUT .../blob/{id}`, `POST /api/v1/media/authorize-download`, `GET .../blob/{key}?grant=...` | Private R2 encrypted media with short-lived upload & HMAC download grants |
| `GET /api/v1/turn-credentials`, `POST /api/v1/calls/log` | Cloudflare Realtime TURN ICE server credentials & call audit log |
| `WSS /api/v1/ws` | `ChatCoordinatorDO` authenticated real-time relay & WebRTC signaling |
