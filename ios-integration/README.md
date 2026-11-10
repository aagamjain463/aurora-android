# iOS integration snapshot

`aurora-ios-billing.patch` targets the latest iOS source snapshot, including its uncommitted work. `base-sha256.json` records the original contents of the changed files. The source working tree was not edited. Other-agent changes to Notifier and UI tests were incorporated into the isolated test baseline, not copied into this patch. The patch was regenerated on 2026-11-04 on top of the other agent's newer `BillingScenarios` warm-up loop, which it keeps.

Apply only in a separate preserved iOS worktree/copy, after comparing hashes and running `git apply --check`. The patch removes the insecure `server-patches/apple-verify.js`, obsolete tests and executable test entry point; its README points to the supported backend. Remove any separately installed old route/import as an explicit integration review item.

The patch adds account-token purchasing, correct bundle identity, sign-in rechecks, store-aware management copy, iOS-only signed sandbox review access and test isolation. It exposes no Apple transfer/claim action. It does not change products, prices or Free limits.

See [the integration sequence](../android-integration/README.md), [billing contract](../docs/APP_STORE_BILLING.md), [monetization audit](../docs/APP_STORE_REVIEW_AUDIT.md), and [local test results](../docs/LOCAL_BILLING_VALIDATION.md).
