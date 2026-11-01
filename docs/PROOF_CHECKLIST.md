# Proof checklist

These checks need real phones, a Play Console account or a person using TalkBack. Automated tests cannot
stand in for them. Nothing here has been run yet. Tick an item only after seeing the result yourself.

Helpers: `scripts/device-proof.sh`. Run it without arguments to see its commands. Start with
`scripts/device-proof.sh install`, turn Shield on, and start a session that blocks one app.

## Blocking on real phones (step 14)

Do this on a Pixel, a Samsung, a Xiaomi and a OnePlus. Read the timings in Settings, Shield diagnostics.
The target is a block within 0.3 s on almost every open.

| Check | How | Pixel | Samsung | Xiaomi | OnePlus |
| --- | --- | --- | --- | --- | --- |
| 20 opens, in target time | `device-proof.sh opens <package>` | | | | |
| After a restart | `device-proof.sh reboot`, then open the app | | | | |
| After a force-stop | `device-proof.sh force-stop`, then open the app | | | | |
| In battery saver | `device-proof.sh battery-saver on`, then `opens` | | | | |
| Home warns if Shield stops | Turn Shield off in Android settings, open Aurora | | | | |

Turn battery saver off afterwards with `device-proof.sh battery-saver off`. After a force-stop, Android may
not restart the service. If so, the row fails until Aurora's warning brings the person back.

## Startup (step 15)

1. Run `device-proof.sh profile` on a physical phone, and commit the new `app/src/main/baseline-prof.txt`.
2. Run `device-proof.sh speed`. It fails above the 2.5 s limit in `scripts/check-startup.py`.

The benchmark measures time to first frame. First Light then plays for about 5.5 s on a cold open, unless
a session is running.

## Accessibility (step 16)

Do this on one phone with TalkBack on, then again after `device-proof.sh font 2.0`. Restore with `font 1.0`.

| Screen | TalkBack reads it in order | Every control named | 200% font fits | Contrast on glass cards |
| --- | --- | --- | --- | --- |
| Onboarding, all 4 steps | | | | |
| Home, idle | | | | |
| Home, session running | | | | |
| Home, Shield warning | | | | |
| Quick session and custom session flow | | | | |
| Shield tab and editor | | | | |
| Intent tab and editor | | | | |
| Track: Progress, Goals, Usage | | | | |
| Block screen, every mode | | | | |
| Aurora Plus and Settings | | | | |

## Store screenshots (step 19)

Use real apps and real numbers on a real phone. `device-proof.sh shot <name>` saves to `store-screenshots/`.
Capture: Home, a running session, the block screen, Track, and the Shield tab.

## Buying Plus (step 20)

Use Play Console license test accounts and an internal testing build.

- [ ] Monthly purchase unlocks Plus at once
- [ ] Yearly purchase unlocks Plus at once
- [ ] A declined card leaves the plan on Free, with a clear message
- [ ] A slow payment shows "Payment pending"
- [ ] Restore purchases works after a reinstall
- [ ] Cancelling returns to Free when the period ends, and nothing is deleted
