# Aurora Account & Cloud Entitlement System

Aurora is local-first in enforcement and cloud-authoritative in identity and entitlements.
This document details the production account architecture, backend services, cryptographic verification, Google Play billing integration, and privacy controls.

---

## 1. Architectural Overview

Aurora enforces protection locally on the device (via Accessibility and Usage Access services), ensuring zero-latency blocking and unwavering enforcement even in airplane mode. However, identity, Free tier strict quota, and Plus subscription entitlements are authoritative in the cloud to prevent circumvention, allow cross-device sync, and bind purchases to verified individuals.

```
┌────────────────────────────────────────────────────────┐
│                     Android Client                     │
│  ┌──────────────────┐  ┌─────────────┐  ┌───────────┐  │
│  │ Shield (Local)   │  │ ProfileVault│  │ Billing   │  │
│  │ • Zero-latency   │  │ • Multi-UID │  │ • Google  │  │
│  │ • Unbroken lock  │  │ • Carried   │  │   Play    │  │
│  └────────▲─────────┘  └──────▲──────┘  └─────▲─────┘  │
│           │                   │               │        │
│  ┌────────┴───────────────────┴───────────────┴─────┐  │
│  │ AccountRepository / AccountGate                  │  │
│  │ • Signed session state                           │  │
│  │ • Advance lookahead reservations (up to 6 hrs)   │  │
│  └────────────────────▲─────────────────────────────┘  │
└───────────────────────┼────────────────────────────────┘
                        │ HTTPS (Bearer ID Token)
                        ▼
┌────────────────────────────────────────────────────────┐
│          Cloudflare Worker (server/src/worker.js)      │
│  ┌─────────────────┐ ┌───────────────┐ ┌────────────┐  │
│  │ Firebase JWT    │ │ Quota Engine  │ │ Play API   │  │
│  │ Verification    │ │ • 120m shared │ │ Client     │  │
│  │ (JWKS / RS256)  │ │ • Ed25519/RSA │ │ • Service  │  │
│  │                 │ │   signatures  │ │   Account  │  │
│  └─────────────────┘ └───────▲───────┘ └──────▲─────┘  │
└──────────────────────────────┼────────────────┼────────┘
                               ▼                ▼
┌────────────────────────────────┐ ┌─────────────────────┐
│ Cloudflare D1 Database         │ │ Google Play API     │
│ • accounts, installations      │ │ & RTDN Pub/Sub Push │
│ • purchases, transfers         │ └─────────────────────┘
│ • quota_reservations, ledger   │
│ • deletion_requests            │
└────────────────────────────────┘
```

---

## 2. Authentication & Identity

### Supported Providers
Aurora supports two verified sign-in methods:
1. **Google Sign-In**: Powered by Android Credential Manager, returning an ID token verified against Google's public certificates.
2. **Email Link (Passwordless)**: A secure link sent via Firebase Authentication. The client requests a link, records the target email in secure cache (`noBackupFilesDir/pending_email`), and verifies the callback via deep link (`/__/auth/links`). If opened on another device, the app asks the user to enter their email address to finish sign-in.

Anonymous accounts and unverified email addresses are strictly rejected.

### Token Verification
All requests to the backend require an `Authorization: Bearer <ID_TOKEN>` header.
The Cloudflare Worker validates:
- **Signature & Algorithm**: RS256 verified against Firebase's public JWKS (`https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com`).
- **Issuer**: `https://securetoken.google.com/<FIREBASE_PROJECT_ID>`.
- **Audience**: Must match `<FIREBASE_PROJECT_ID>`.
- **Expiry & Timing**: Tokens older than 1 hour or issued in the future are rejected.
- **Email Verification**: `email_verified` must be `true`.

---

## 3. Server Architecture (Cloudflare Worker + D1)

The backend runs on Cloudflare Workers with a Cloudflare D1 relational database (`server/wrangler.toml` and `server/migrations/0001_accounts.sql`).

### API Routes
| Method | Route | Description |
|---|---|---|
| `POST` | `/v1/account/sync` | Syncs account profile, registered devices, quota status, and signed entitlement snapshot. |
| `POST` | `/v1/purchase/verify` | Verifies a Google Play purchase token via Google Play Developer API and binds it to the account. |
| `POST` | `/v1/purchase/transfer` | Explicitly transfers a bound purchase from an old account to a new account upon reauthentication. |
| `POST` | `/v1/quota/reserve` | Requests quota reservation for a strict commitment (`AlwaysBlock` or `Cooldown`). Returns a signed authorization token. |
| `POST` | `/v1/quota/release` | Releases an unstarted reservation back to the daily allowance (e.g., deleted before start). |
| `POST` | `/v1/quota/import` | One-time migration of unowned local ledger entries created before the account was linked. |
| `POST` | `/v1/installations/remove` | Removes a registered device from the account. |
| `POST` | `/v1/account/delete` | Requests account deletion with a 30-day grace period. |
| `POST` | `/v1/account/delete/cancel` | Cancels a pending account deletion request. |
| `POST` | `/v1/rtdn` | Webhook endpoint receiving Google Cloud Pub/Sub push notifications for real-time subscription lifecycle updates. |

### Quota Engine & Anti-Circumvention
- **Free Allowance**: 120 minutes/day for strict commitments (`Mode.AlwaysBlock` and `Mode.Cooldown`). Flexible and Friction modes do not consume quota.
- **Shared Across Devices**: All devices registered to the same account share the single 120-minute daily allowance. Starting a 60-minute session on phone A leaves 60 minutes on phone B.
- **Time Zone Hopping Defense**: Quota reset boundaries follow the user's IANA time zone. If a user changes time zones, the backend enforces that the new window only begins after the end of the newest reservation's local day, preventing infinite quota generation by timezone spoofing.
- **Advance Lookahead**: The Android client looks ahead up to 6 hours for scheduled commitments and requests advance authorizations. If authorized ahead, the commitment begins on schedule even if the device loses internet connectivity. If the server is unreachable when due, the occurrence waits unenforced without penalty.

---

## 4. Google Play Billing & Entitlement Binding

1. **One Account Binding**: A purchase belongs to an Aurora account UID, not just a device installation.
2. **Transfer Flow**: If an account attempts to claim a purchase already owned by another account, the backend rejects it with `conflict`. The user can explicitly initiate a purchase transfer from Settings, which requires recent reauthentication and revokes access from the prior account.
3. **Seat Limits**: A single Plus subscription can be active across up to 5 concurrent device installations. The 6th device is notified and must remove an existing device before activating.
4. **Real-Time Developer Notifications (RTDN)**: Google Cloud Pub/Sub sends notifications to `/v1/rtdn?token=<RTDN_TOKEN>`. The Worker processes subscription cancellations, expirations, renewals, and account holds, updating D1 immediately.

---

## 5. Multi-Account Device Storage (ProfileVault)

Aurora allows multiple verified accounts on the same Android device while preserving total data isolation:
- Data is stored in private files under `noBackupFilesDir/profiles/<sha256(uid)>.json`.
- When switching accounts, active state is serialized and parked atomically.
- **Carried Commitments**: If Account A has an active `AlwaysBlock` session running and logs out, or Account B logs in, the commitment continues running to prevent bypassing restrictions. However, it is anonymized as "Earlier commitment" and stripped of all personal tags, package names, motivations, and private ledger records.
- When an unowned device signs in for the first time, `ProfileVault` presents a decision: "Keep what is on this phone?". If accepted, local rules, habits, and today's committed minutes are claimed by the new account.

---

## 6. Account Deletion & Grace Period

- Users can request deletion from **Settings › Account › Delete account**.
- Reauthentication is mandatory before deletion can be scheduled.
- **30-Day Grace Period**: An account entered into deletion state enters a 30-day grace period during which deletion can be cancelled by signing in and tapping "Keep account".
- **Self-Service Web Deletion**: Google Play Data Safety requirements mandate a web-based deletion request mechanism. Users can submit deletion requests via `https://useaurora.app/account-deletion.html`.
- **Immediate Local Data Purge**: Requesting deletion immediately clears all local data, rules, motivations, and habits from the device, except active running commitments which complete their timer.

---

## 7. Data Safety Disclosures

Aurora is built on privacy. The following table provides the exhaustive breakdown of what leaves the phone:

| Data Type | Sent to Backend? | Purpose | Retention |
|---|---|---|---|
| User ID (Firebase UID) | Yes | Account authentication & profile lookup | Retained until account deleted |
| Email Address | Yes (via Auth) | Account verification & email link sign-in | Retained until account deleted |
| Installation ID (UUID) | Yes | Binding authorizations & device seats (max 5) | Retained until device removed |
| Commitment Window (Start, End, Zone) | Yes (Free tier) | Verifying the 120-minute daily strict quota | Retained 7 days in ledger |
| Purchase Token | Yes (Plus tier) | Verifying Google Play subscription status | Retained while active |
| App Package Names (e.g. `com.instagram.android`) | **NEVER** | None. Enforcement is 100% on-device. | Kept locally only |
| Protected Websites & URLs | **NEVER** | None. Evaluated locally in accessibility service. | Kept locally only |
| Personal Intent Reasons & Motivations | **NEVER** | None. Local habit tracking only. | Kept locally only |
| Usage Stats / Screen Time | **NEVER** | Read on-device from Android UsageStatsManager. | Kept locally only |
