# aurora Shield

Shield is a service-owned `TYPE_ACCESSIBILITY_OVERLAY`, implemented by `OverlayShieldPresenter` in
`protect/ShieldOverlay.kt`. There is no Shield activity. This supersedes the Phase 3 activity design;
older latency numbers in Git history do not measure this overlay.

## Event to overlay

```
TYPE_WINDOW_STATE_CHANGED
  ShieldService: package name + event timestamp; no window content
    Interceptor: snapshot, exemptions, strongest decision, next recheck
      OverlayShieldPresenter: one Compose overlay owned by the bound service
        ShieldContent: reason, task/wait/decision, timer, return home or access
```

The service reads no screen text, view tree, messages or typed input. The person enables it in
Android Accessibility after aurora's disclosure. The optional Web & content service is separate and
has its own disclosure because it reads supported browser address bars and content view IDs.

`decide()` and `nextChange()` are pure Kotlin. The precedence is Always Block, Flexible, Focus,
spent limit, Cooldown, Friction, Intent cooldown, Intent. Grants lift their own level and weaker
levels only. See [PRODUCT.md](PRODUCT.md) for mode semantics and migration.

The overlay covers the protected app, which stays underneath. A grant dismisses the overlay, so no
background activity launch is needed. Return home and Back perform the global Home action before
dismissal. The interceptor ignores late events briefly after that exit. Once a real launcher/app
window is observed, its event timestamp distinguishes a genuine rapid reopening from a stale event. Repeated events reassert one existing window. Timer checks
bring Shield back when access expires even if no new window event arrives.

The presenter requests transient audio focus while shown and abandons it on dismissal. This can
pause or duck cooperative media apps; it is not a guarantee that every app stops audio. The overlay
accepts keyboard input for math and intention tasks and handles predictive Back on supported Android.
It disappears when the service unbinds.

## Why the activity was replaced

On the founder's Redmi/HyperOS phone, the old activity path had three independent failures:

- MIUI's background activity permission (operation 10021) rejected starts silently.
- Android listed ShieldService as crashed rather than bound, so it received no foreground events.
- Instagram had saved mode preferences but was absent from the active protected-item list.

The first two findings came from device diagnostics; the saved configuration established the third.
A service-owned accessibility overlay avoids the OEM activity-start gate. It cannot prevent Android
or an OEM from killing the service. Reinstalling an earlier debug build rebound Shield, but that
alone did not verify actual Instagram blocking. The 29 October continuation verified Instagram blocking on the Redmi; detailed coverage and remaining OEM launch checks are in [REINVENTION_VALIDATION.md](REINVENTION_VALIDATION.md).

## Status and diagnostics

Enabled is not the same as bound. Status surfaces distinguish permission off, enabled but not running,
configuration error and running protection. Xiaomi guidance points to Autostart and unrestricted
battery use. Diagnostics expose the chain from enabled service through connection, observed app,
configuration, decision, presentation request and attached window. Attached is stronger evidence
than a successful `startActivity()` request ever was.

`ShieldTrace` keeps bounded timing/counter/lifecycle samples in memory. Debug logs contain numeric
timings rather than package names. Overlay draw timing measures event-to-draw callback, not the final
display refresh. It is diagnostic evidence only; G1 latency still requires physical devices.

## Recovery and limitations

- Settings, installers, launcher, keyboard, phone/emergency and aurora are exempt.
- Unknown or unreadable configuration shields nothing and reports failure; it is not guessed.
- Stored sessions survive process death. Monotonic time is used within a boot; wall time is the
  fallback after reboot. Enforcement resumes only when Android binds the service.
- Revoking Accessibility, force-stop, uninstall and OEM process termination can stop enforcement.
- The UI never promises uninstall prevention or an unbypassable device lock.
- Launcher/keyboard exemptions refresh on service connection.
- Website and Shorts/Reels enforcement depends on supported, version-sensitive view identifiers.

## Tests

Policy, Rules, Migration, Interceptor, TimeAway, Duration and Friction tests cover pure behaviour.
CopyTest protects the eight approved strings. Navigation/ProtectLimit tests exercise Compose flows.
Roborazzi records the main screens and Shield in light, dark, 200% text and reduced motion.

`uiautomator dump` temporarily suppresses normal accessibility services on this emulator. Never use
its resulting unbound state as proof of product failure. For enforcement checks use `screencap`,
`dumpsys window`, service diagnostics and ordinary launches; wait for binding after UI dumps.
Automated tests do not silently enable Accessibility. Avoid `connectedAndroidTest` on the founder's
phone: installation/uninstallation can destroy app data.

## Real-phone checklist

Use the current debug APK with `adb install -r -t`, preserving data. Never clear the founder's data.
Keep any backup in private local state, outside committed screenshots/docs.

1. Unlock the Redmi and authorize USB debugging. Confirm Shield is enabled **and bound**.
2. Install the current APK. Open Protect; verify Instagram appears by name and icon. Add it if needed.
3. Start a short Flexible session for Instagram. Open Instagram from launcher, Recents and a deep link.
   Confirm the overlay blocks interaction; Return home and Back must reach the launcher.
4. Turn Flexible off in aurora. Instagram should open. Record outcome and build version.
5. Test Always Block separately with a deliberately short duration. Confirm no in-app early exit;
   removing/editing the saved item must not end the commitment. Wait for automatic expiry.
6. Verify service recovery after app update and reboot/unlock. Check Autostart and battery settings.
7. Measure repeated opens on real hardware; target at least 90% shielded within 300 ms. Check
   notification launches, rapid switching, split-screen, PiP, media and TalkBack.
8. Report which checks passed, failed or remain pending. Emulator results cannot substitute.

Current run evidence and launch gates: [REINVENTION_VALIDATION.md](REINVENTION_VALIDATION.md).
