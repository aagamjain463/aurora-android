# G1 checkpoint (2026-10-29)

Stands in for the task handoff `aurora-g1-emulator-validation-20261028-230215.md`. The ai-handoff guard
denied access to that handoff in this session, so it was left unchanged. Branch: `spike/g1-harness`
(local only, never pushed or merged). Commit with the results: `355d3bd`.

**Status:** emulator validation completed. **G1 not passed**, because no physical device has been tested.

## Done

- Harness ran end to end on emulator-5554 (Android 16, API 36, `g1` build). The results and limits
  are in `ENFORCEMENT_FEASIBILITY.md` section 10.6.
- Latency over 122 opens: 100 ok, 14 timeout, 8 missed, 0 within 300 ms. Detection median 30 ms.
  Presentation median 1246 ms, which is most of the exposure.
- Recovery: `am crash` restart took 1.5 s. Turning the service off unbinds it, the process survives,
  and no Shield shows. Re-enabling took 0.59 s from the setting write to `onServiceConnected`.
- `g1.sh` fixes: `G1_SETTLE` is 3.5 s, `G1_RAPID` sets the rapid interval, and exposure
  percentiles count timeouts and misses as >2000 ms.
- Checks: `bash -n` passed. 9 unit tests passed. Lint has warnings only. Debug, release and `g1`
  builds pass. The release APK has no spike code.

## Open

- Is presentation slow on real phones too? If so, the activity-based Shield can't meet 300 ms.
  Look at section 3.4 again.
- Nothing in the app tells the user when the service is turned off. Phase 3 must add that.
- Not run yet: the `manual` scenarios, `reboot`, `forcestop`, TalkBack, and behaviour specific to
  a manufacturer.

## Next steps (physical devices)

1. Use a Pixel, a Samsung, and a Xiaomi or OnePlus phone. Run `./gradlew assembleG1`, then
   `adb install -r app/build/outputs/apk/g1/app-g1.apk`. Also install once from a file manager (G4).
2. On each phone, run `device`, `launches <pkg> 50 warm`, `launches <pkg> 20 cold`, and
   `rapid <pkg> com.android.settings 20` (default interval and with `G1_RAPID=2.0`). Then run `kill`.
3. Run the `manual` scenarios as listed in section 10.3, then `revoke`. Record the setting-write
   time and connect time.
4. Only with the user's approval: run `G1_ALLOW_REBOOT=1 reboot`, then `forcestop` last.
5. To pass: at least 50 opens, `within-300ms` of 90% or more of all opens, and
   `shield-not-in-front=0`. Fill table 10.5 and finish section 9 items 2 and 5–8.
