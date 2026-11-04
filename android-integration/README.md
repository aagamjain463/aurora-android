# Conflict-aware integration handoff

These artifacts were built and tested in temporary copies. The original Android/iOS working trees were never edited by this task. The other agent continued editing Android quota behavior during the rehearsal; the final backend patch uses a complete current snapshot, including phone quota imports/helpers and the latest reservation ordering. Do not substitute a stale worker or apply the original backend commit wholesale over anti-abuse work.

## Artifacts and checks

- `backend-on-anti-abuse.patch`: Apple backend additions reconciled with the current `feat/device-quota-anti-abuse` working tree. Keeps installation/device/phone seats and integrity, existing route/body handling, race-safe quota changes and current tests. Adds App Store configuration and migration `0004_app_store.sql` after `0003_released_keys.sql`.
- `backend-base-sha256.json`: exact pre-patch contents of every changed existing backend file; null means a new file.
- `android-store-management.patch`: adds subscription `store` metadata at the end of the Kotlin constructor to preserve positional callers; App Store subscriptions show Apple-device management instructions. Native Play purchasing remains unchanged. Generic renewal/payment/account/deletion wording no longer assumes Google Play. Removes automated purchase transfers and adds a signed-sandbox regression test proving Android remains Free.
- `android-ui-base-sha256.json`: source hashes for the Android UI patch.
- `../ios-integration/aurora-ios-billing.patch` and `base-sha256.json`: matching current iOS sources, including uncommitted changes. Retires the insecure verifier and its old tests. Adds explicit iOS-only sandbox review access and removes Apple transfers.

`rehearsal-provenance.json` records the source branch HEAD. HEAD alone does not describe uncommitted work: verify the content hashes and `git apply --check` too. Patches are snapshots, not permission to overwrite later work.

## Exact next local steps

1. Let the other agent finish and commit its Android anti-abuse changes, including `0003_released_keys.sql`. Separately preserve/commit its iOS changes. Do not reset either dirty tree. These commits are the owners' integration step, not an action already taken here.
2. Create a separate integration worktree from that preserved anti-abuse branch. Compare its files with both Android base manifests. If contents differ, rebase the patches manually using the recorded base, preserving the newer source. Never use an overwrite copy, `git reset --hard`, or blanket conflict resolution.
3. In that integration worktree, run `git apply --check /absolute/path/to/backend-on-anti-abuse.patch` and the same check for `android-store-management.patch`. Apply each only after its check passes. Do not also cherry-pick the backend preservation commit: that would duplicate additions against the wrong baseline.
4. Inspect `server/src/worker.js`: keep the current anti-abuse imports and helpers; keep `hasPlus(env, account, installId, now)` seat checking while excluding Sandbox rows; keep the newer reservation revision/idempotency ordering; use the cached `rawBody` for Apple notifications; exempt both notification routes from Firebase/device authentication without exempting purchase routes. Inspect `fixture.js` for both Apple HTTP stubs and current integrity/phone test state.
5. Run `node --test test/` from the integrated `server` directory and `./gradlew --offline testDebugUnitTest` from its Android root. Review the resulting diff. Commit only after the local checks pass. No push, deployment or remote migration is authorized.
6. Create a separate iOS integration worktree/copy from the preserved iOS source. Compare `ios-integration/base-sha256.json`, run `git apply --check` on the iOS patch, then apply it. Review every hunk against newer work. Remove the old verifier's imports/routes wherever it was manually installed; deleting its source file alone cannot prove an already deployed service was replaced.
7. Run the iOS unit tests with free simulator ad hoc signing (`CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY=- DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER=`). The initialized-simulator suite passes. A brand-new simulator still times out on its first StoreKit operation; keep that failure visible and investigate before claiming a clean purchase flow. Do not skip the failing test or treat a runner restart as success.
8. Before submission, resolve the [4.10 audit](../docs/APP_STORE_REVIEW_AUDIT.md) with Apple and get user approval for any product/pricing changes. Later, under separate authorization, configure App Store Connect with Family Sharing OFF and perform real sandbox/API/notification checks. No Apple Developer membership is needed for the completed local checks.

## Deployment is outside this handoff

Migration order is `0001_accounts`, `0002_devices`, `0003_released_keys`, `0004_app_store`. None ran remotely. Later deployment needs a separately reviewed rollout/rollback, secrets and real sandbox validation. Keep the additive Apple schema if reverting the Worker; do not erase purchase bindings during rollback.

Apple and Google Play transfers are disabled. Original-account sign-in/restore is the safe recovery path. A future transfer needs proof of both account owners, not replayable receipt/JWS/token possession. Both native purchase flows remain supported.
