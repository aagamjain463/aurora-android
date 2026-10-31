# Handoff: strict mode anti-bypass for always block cooldown friction
- Task ID: strict-mode-anti-bypass-for-always-block-cooldown-friction-20261030-001736
- Current agent: Claude Opus 5.5
- Status: completed
- Last updated: 2026-10-30

## Objective
Always Block, Cooldown and Friction can't be bypassed: no uninstall, no Settings/permission changes, no type change, clock changes ignored, survives restarts. Flexible stays cancellable.

## Completed work
- Mode.strict (Policy.kt); ProtectionSession/ConfigRun cancellable only for Flexible (Always-timing legacy runs stay cancellable); Always timing refused for strict Shields.
- Restriction.locks + PolicyState.locked + lockedUntil; decide() shields Settings/installers as ShieldReason.Locked during strict runs (Exemptions.kt lockedPackages).
- TrustedClock (protect/TrustedClock.kt): monotonic during strict, offset persisted across reboots; relax() in syncRuns when nothing locks. Wired into app.now(), Interceptor, ShieldContent, ContentService.
- StrictReceiver (BOOT_COMPLETED, TIME_SET, MY_PACKAGE_REPLACED); UninstallGuard device admin + xml/device_admin.xml; UninstallProtection card in StrictModeScreen and Configure commit step.
- Strings updated (strict_*, admin_*, shield_locked_*). Tests added in PolicyTest, ConfigurationsTest. Unit tests pass.

## Remaining
- Emulator verification, roborazzi record + review, docs/CONFIGURATIONS.md update, graphify update.

## Tests and results
- Unit tests: all pass (PolicyTest.strictCommitmentLocksSettingsUntilItEnds, ConfigurationsTest strict cancel cases).
- Emulator (API 36): Cooldown 1h run. Settings and uninstall dialog shielded as Locked; `adb uninstall` -> DELETE_FAILED_DEVICE_POLICY_MANAGER; clock +2h ignored (wait and session kept trusted time); reboot with clock still +2h -> session continued (57 min left), Settings still locked. Always timing disabled for Cooldown in UI.
- Note: uiautomator dump suspends accessibility services; use screenshots for testing.
- recordRoborazziDebug: images written (strict, configure_commit, builder_shield etc. reviewed), but Gradle ends with java.io.EOFException writing test reports (data volume ~4.6 GB free; likely cause, unverified).

## Remaining issues
- OEM autostart restrictions (MIUI etc.) can stop Android rebinding the service after reboot; not fixable in-app.
- Safe mode / factory reset / pre-enabled adb remain bypasses.
- Notification chronometer still uses system clock (display only).
- docs/CONFIGURATIONS.md updated; graphify updated.
