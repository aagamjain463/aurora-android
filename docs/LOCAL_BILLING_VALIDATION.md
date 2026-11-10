# Local billing validation — 2026-11-04

All changes and test runs used staging or temporary copies. No push, deployment, merge into an original app tree, paid membership, remote migration or authenticated Apple sandbox request occurred.

## Passing checks

| Check | Result |
| --- | --- |
| Staging backend `node --test test/` | 94 passed, 0 failed |
| Complete current Android anti-abuse backend plus integration patch | 129 passed, 0 failed |
| Android app plus store-management/security patch, `./gradlew --offline testDebugUnitTest` | 545 passed, 0 failed, 0 skipped |
| iOS patched source, initialized simulator, complete `AuroraTests` suite | 93 passed, 0 failed; three complete successful runs (latest after re-sync, below) |
| All three integration patches, `git apply --check` against their current source trees | Passed at handoff generation |
| Apple endpoint documentation | Verified current Apple API domain recommendation; real authenticated requests remain pending |

Backend coverage includes signature/chain and token vectors, wrong product/bundle/type/environment, active/grace/retry/expired/refunded states, original-account binding, replayed ownership evidence, both transfer endpoints disabled, family evidence refused during verify and refresh, sandbox-only review scope, production/Android quota isolation, environment-key collisions, sandbox off, notifications, races, scheduled refresh, additive migrations and rollback. Integration testing retains current device/phone/integrity/seat tests and latest anti-abuse reservation ordering.

Android coverage includes signed sandbox metadata leaving the production plan Free, store-field decoding and existing positional constructor compatibility. Both native purchase implementations remain in place.

## StoreKit failures investigated, not hidden

A brand-new simulator reproduces empty product loading with the original iOS test source. Unsigned simulator execution is unsuitable for establishing StoreKit behavior. Ad hoc signing is free and needed for reliable initialized-simulator checks, but it did not by itself fix cold first-use behavior.

With the patched source and ad hoc signing, two newly created simulators timed out on the first `BillingScenarios.testAConflictStoresNothing` operation. Xcode restarted the runner and the remaining 11 billing/subscription tests passed; the overall runs still failed. A captured spindump contained `No samples`, so it did not establish an internal Apple framework cause. Isolating test instances and skipping normal app startup in the test host did not eliminate that fresh-simulator failure. Do not claim signing alone fixes it.

The intermittent unit assertion `the signed answer finishes the transaction` also reproduced. The test previously compared against `Transaction.latest`, which can be stale while StoreKit propagates completion. The patched test identifies the transaction actually purchased from its test JWS and waits up to two seconds for that ID to disappear from `Transaction.unfinished`. Independent `Billing()` test instances and state cleanup remove shared verifier leakage. This test passes in both full unit runs; that is useful evidence, not proof that all StoreKit flakiness is resolved.

A three-iteration stress run failed at CoreSimulator app launch with `NSPOSIXErrorDomain Code=3`, rather than a purchase assertion. It cannot count as a passing stability check. Keep the first-run failure and simulator launch failure visible. Do not add a production retry, skip a failing test, or infer real sandbox acceptance from a warm local run.

## Re-sync with newer iOS test changes (2026-11-04, 09:30 IST)

After the first handoff, the other agent added a product-loading warm-up loop to `BillingScenarios.setUp` in the iOS tree (untracked `ScenarioTests.swift`). The iOS patch no longer applied. It was regenerated on top of that newer file without overwriting it. The test identity/entitlement reset now runs before the warm-up loop, because the loop returns early once products load. Teardown moved after the loop. `base-sha256.json` was refreshed, and `git apply --check` passes against the current iOS tree.

Results with the re-synced patch:

- Initialized simulator: 93 passed, 0 failed, exit 0. All 7 `BillingScenarios` tests passed.
- Brand-new simulator: product loading now succeeds (the warm-up loop fixes the empty-product symptom). The first purchase (`testAConflictStoresNothing`) still hung until the 2-minute test allowance. After Xcode restarted the runner, `testASignedAnswerIsStoredAndTheTransactionFinished` failed: the purchased ID was still in `Transaction.unfinished` after about 2 seconds. Exit 65.

So the fresh-simulator problem is now narrowed: product lookup is fixed; the first `purchase()` on a cold simulator hangs, and `finish()` propagation can be slower than 2 seconds right after. Xcode reports "Making a purchase without listening for transaction updates", because test hosts skip `Billing.start()`. Starting a `Transaction.updates` listener in the test host is the next experiment. Do not ship a production retry for this. Android patches still match their recorded hashes; the integrated anti-abuse backend still passes 129/129.

## Reproduction and later checks

Use an isolated copy and simulator. Run `xcodebuild test -project Aurora/Aurora.xcodeproj -scheme Aurora -destination 'platform=iOS Simulator,id=<id>' -only-testing:AuroraTests -parallel-testing-enabled NO CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY=- DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER=`. A fresh-run diagnostic should enable test timeouts and retain the `.xcresult` bundle; otherwise the first StoreKit operation can hang for minutes. Check the final process exit and failed tests, not only the last restarted runner's pass summary.

Next local investigation: compare a fully boot-ready fresh simulator with a previously initialized one, collect usable StoreKit/test-session diagnostics on the first operation, and distinguish product lookup from purchase completion before changing app behavior. App Store configuration, real sandbox JWT acceptance/notifications, TestFlight/App Review, real Screen Time entitlement provisioning and 4.10 acceptance are still outside the completed local proof.
