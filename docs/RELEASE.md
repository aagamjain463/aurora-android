# Releasing Aurora to Google Play

This page covers everything from a clean checkout to a reviewed Play listing. What Aurora does with data is
in [`privacy-policy.html`](privacy-policy.html). The listing text is in [`STORE_LISTING.md`](STORE_LISTING.md).

## 1. Build the upload bundle

Release builds are signed with the upload key when `keystore.properties` exists in the repository root.
That file is git-ignored and must never be committed:

```properties
storeFile=/absolute/path/to/aurora-upload.jks
storePassword=…
keyAlias=aurora-upload
keyPassword=…
```

An upload key was generated on the founder's Mac on 30 October 2026 at `~/aurora-keys/aurora-upload.jks`
(RSA 4096, valid 10,000 days), with a copy of its properties in `~/aurora-keys/keystore.properties.backup`.
**Back up `~/aurora-keys/` to a password manager or an encrypted drive now.** With Play App Signing, Google
holds the app signing key, so a lost upload key can be reset through Play Console support. It can't be
recovered.

```sh
./gradlew testDebugUnitTest lintDebug    # must be green
./gradlew bundleRelease                  # app/build/outputs/bundle/release/app-release.aab
```

Before every upload, raise `versionCode` (and `versionName` when users should notice) in
`app/build.gradle.kts`.

## 2. Play Console setup

1. Create the app: name **Aurora**, default language English, App, Free (with in-app purchases).
2. **App signing:** accept Play App Signing, then upload `app-release.aab` to the **Internal testing** track first.
3. **Subscription:** Monetize › Subscriptions › create the product `aurora_plus` (the id the app expects;
   override with `-Paurora.plusProductId=…`). Add a monthly and a yearly base plan. India prices from
   `docs/BUSINESS_MODEL.md`: ₹149 a month and ₹1,199 a year.
4. **License testers:** Settings › License testing, add your Google accounts. Buy, cancel, restore and let a
   test subscription lapse on a real phone (Plus needs the verification server deployed: `docs/BILLING.md`).
5. **Privacy policy:** host `docs/privacy-policy.html` (for example on GitHub Pages) and paste its URL into
   App content › Privacy policy and the store listing.

## 3. App content declarations

### Accessibility API (Permissions declaration form)

Aurora has two accessibility services, both with `isAccessibilityTool="false"`. Both are shown only after
Aurora's own disclosure dialog, which the person must accept before Android's settings open.

Suggested answers:

- **Core functionality:** Aurora is a digital wellbeing app. The person chooses apps and websites to limit,
  and Aurora Shield pauses those apps when they come to the front.
- **Aurora Shield** listens only for window-state changes and reads only the package name of the app in
  front (`canRetrieveWindowContent="false"`).
- **Aurora Web & content Shield** is optional and off by default. It runs only in the listed browsers,
  YouTube and Instagram. It reads the browser's address field to pause websites the person protects, and
  view IDs to notice Shorts or Reels when the person turned that on.
- **Data:** nothing is stored beyond the person's own settings, and no usage data leaves the phone. `INTERNET` is used only to verify an
  Aurora Plus purchase (`docs/BILLING.md`).
- **Video:** record the disclosure dialog, Android's accessibility screen, and Shield pausing a chosen app.
  `adb shell screenrecord /sdcard/shield.mp4` on a phone.

### Usage access (`PACKAGE_USAGE_STATS`)

Used only to measure screen time and daily limits on the phone. The person grants it in Android settings.

### Device admin

`UninstallGuard` declares no policies (`<uses-policies />`). The person turns it on themselves, to stop
Aurora being uninstalled while a strict commitment they started is running.

### Data safety

Aurora collects user account info (email address, user ID via Firebase Authentication) and device/app identifiers (a random install id), as well as purchase tokens for Plus subscriptions and strict commitment windows (start timestamp, end timestamp, timezone for Free quota verification).
- **Personal info:** Email address and User IDs collected for account management and authentication.
- **Financial info:** Purchase history (purchase token via Google Play).
- **Device or other IDs:** Device/installation ID for session binding and seat limits (max 5 devices).
- **App activity / Usage data:** **None collected or transmitted.** All package names, websites, reflections, and usage times stay 100% on-device.
- **Data sharing:** No data is shared with third parties.
- **Security:** All data encrypted in transit (HTTPS/TLS).
- **Account deletion:** Supports in-app deletion and web-based deletion request portal (`docs/account-deletion.html`). See `docs/ACCOUNT_SYSTEM.md`.

### Other forms

- **Ads:** No ads.
- **Target audience:** 18 and over (or 13+ if you want teens; then Families policy applies).
- **Content rating:** complete the IARC questionnaire. Aurora has no user-generated content or chat.
- **Health apps:** not a health app. Choose "Productivity" as the category.
- **Foreground service / exact alarms:** none are used.

## 4. Review risks to know before submitting

1. **Strict commitments lock Settings and the package installer.** While Always Block, Cooldown or Friction
   runs, Aurora Shield covers Android Settings and every uninstall screen, whichever package the device
   maker uses (`protect/Exemptions.kt`, `lockedPackages`). Google Play's accessibility policy forbids using the API to "prevent users from
   disabling or uninstalling any app or service", with exceptions only for parental-control and enterprise
   apps. Other self-control apps ship similar strict modes, but this is the most likely reason for a
   rejection or a later removal. The person opts in to each session, confirms "I'm ready to commit", and the
   lock always ends with the timer. Explain that in the declaration. If Play rejects it, the smallest change
   is to stop locking Settings and the installer while keeping the rest of strict mode.
2. **Device admin used for uninstall protection** is reviewed under the same rule. It's optional and off
   by default.
3. **Billing diagnostics.** The manifest removes `ACCESS_NETWORK_STATE` and the datatransport backend the
   billing library brings, so `INTERNET` serves purchase verification only. Confirm with license testers.

## 5. Store listing assets

- Icon: `brand/store/out/play-icon-512.png` (512 × 512, full square; Play rounds the corners).
- Feature graphic: `brand/store/out/feature-graphic.png` (1024 × 500).
- Phone screenshots: `brand/store/out/01-home.png` … `05-track.png` (rendered by `brand/tools/store.sh`).
  Screenshots 02 and 03 use test fixtures; capture them again on a phone before the public launch.

## 6. Before moving from Internal testing to Production

- Test on at least one Pixel, one Samsung and one Xiaomi phone: blocking, reboot during a commitment,
  notification, widget and Quick Settings tile.
- One TalkBack pass through onboarding, Home and a Shield.
- Closed testing with at least 12 testers for 14 days if the developer account is a personal account
  created after November 2023 (Google's requirement for new personal accounts).
