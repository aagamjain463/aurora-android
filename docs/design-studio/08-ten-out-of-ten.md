# 08 — The 10/10 pass

29 October 2026. Branch `feat/aurora-configurations-habits`. The founder asked for the whole plan from the
audit to be implemented. This page records what changed, how it was checked, and what can only be done by
people, phones or Google Play.

## What changed

### Brand consistency
- **One First Light painting.** Night and day now come from the same procedural painting
  (`brand/tools/first_light.py`), tuned towards reference `01-today.png`: a warm gold ring, calm hills
  and a golden column on the lake. The photoreal ImageGen night art is retired (see
  `03-first-light-artwork.md` for why and how to restore it).
- **Type never sits on the ring or the water line.** The horizon moved up (0.43 → 0.35) and the water
  line is softened, which fixes the light-theme rule through Home and Shield eyebrows and the Track eyebrow
  over the sun.
- **"Aurora Plus" everywhere.** Strings, README and the business model doc. The old "always lowercase"
  rule in `BUSINESS_MODEL.md` is replaced by the Brand Bible's title case.
- **New token:** `display-s` (Newsreader 32/36) for setup step titles, in `aurora.tokens.json` and
  `AuroraType.DisplayS`.

### Easier to use
- **Custom Configuration: 7 steps → 3.** What (name, Shield, Intent), When (timing and its length on one
  screen), Review (with optional Focus). Normal Use loses its separate Name, Timing and Focus steps too.
- **Shield editor 6 → 5 steps, Intent editor 6 → 5:** settings live under the choice they belong to.
- **Setup steps** start at the top, settle in with a short fade, and the progress bar carries the gold
  rising point.
- **"None"** is a quiet chip beside the heading, not a full card, and appears only once something is
  chosen.
- **Library cards:** tap to edit; Duplicate and Delete behind one overflow control. Delete is no longer a
  peer of Edit.
- **Track** leads with one number (verified protection) and a success rate, not six tiles that repeat
  "14". **"<1m"** is now "Under 1m".
- **Focus** shows the remaining time once (in the ring), not twice.
- **Home** shows its empty state once.
- **Onboarding welcome** fills the screen: the promise low over the lake, then Shield, Intent and Track
  arriving one by one.
- **Tablets:** content is a 640dp reading column; the art spans the screen.
- **Large text:** the symbol in the lockup now grows with font scale.

### Premium moments
- **Ongoing notification** while a timed configuration or Focus runs: name, mode, end time, a native
  countdown, timeout at the end; Android 16 progress style; promoted Live Update on 16 QPR2+. Asked for
  (Android 13+) the first time protection starts.
- **Home-screen widget** and **Quick Settings tile** showing what is protecting the person now.
- **Animated splash:** the ring draws along the horizon, then the point rises.
- **Finish moment:** a run that just succeeded gets a warm card with the completed arc and a Confirm haptic.
- **Shield stage entrance:** the art brightens and the app rises into its halo.
- **Aurora Plus** is a hero screen: First Light, a two-tone headline, the honest comparison, and an
  explicit message when Google Play has no plans to show (it used to be blank). The decorative
  "progress light" with invented values is gone.
- **Settings** opens with the plan as a warm card carrying the mark.
- **Focus inside a configuration** now has First Light.

### Removed
- Seven unreachable screens from the old Protect tab (`ProtectScreen`, `StartScreen`, the old Intent,
  Schedule and Groups screens) and 113 strings only they used; their stale baselines.

### Store and brand assets
- `brand/tools/store.sh` renders five 1080×1920 Play screenshots and the 1024×500 feature graphic into
  `brand/store/out/` from the verified dark baselines.

## Verification

| Check | Result |
| --- | --- |
| `./gradlew recordRoborazziDebug` (all unit, Robolectric and screenshot tests) | 314 tests, 0 failures |
| `./gradlew lintDebug` | No new issues |
| Screenshots | Every screen in Light, Dark, 200% and reduced motion, plus 360dp, tablet and the widget (`app/src/test/screenshots/formfactor/`) |
| Emulator, Android 16 (API 36) | Animated splash; Normal Use started through the real 5-step flow; ongoing notification with countdown and progress style; Quick Settings tile lit with the running count |

`verifyRoborazziDebug` is not stable run to run on a few screens (configure commit, Shield cooldown,
Focus run, Shield library): app icons load asynchronously and some screens show live clock values, so a
capture can land before the icon or a second later. CI doesn't run it (README). Waiting for icons in
`ScreenshotTest` would make it deterministic.

A real bug was caught on the way: `Notification.Builder.setRequestPromotedOngoing` exists only from
Android 16 QPR2 (36.1), so calling it on 36.0 crashed the notifier. It is now gated on `SDK_INT_FULL`.

## What still needs people, phones or Play

- **Founder review** of new and changed copy (onboarding pillars, Track, notification, widget, tile,
  finish card, overlap line). The eight founder-approved strings are unchanged.
- **Art sign-off:** the retired cinematic night art was a deliberate choice before this pass; confirm
  the single painting.
- **Physical phones:** Pixel, Samsung and Xiaomi; notification and widget behaviour after reboot and
  process death; Live Update promotion on a 36.1+ device.
- **TalkBack** pass on a real phone.
- **Store screenshots 02 and 03** use Robolectric fixtures, so app icons are placeholders and the data is
  test data. Capture those two from a phone before uploading, or approve the fixture data.
- **Trademark clearance** for the name and symbol; Play Console products and policy review (unchanged
  from `PRODUCT.md`).
- **Not done:** a shared-element transition from a configuration card to its running screen (the cost
  is high and the gain small); predictive back already works through Navigation 2.10 and
  `enableOnBackInvokedCallback`.
