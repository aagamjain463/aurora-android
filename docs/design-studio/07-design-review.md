# 07 — Design review

Method: every hero screen was rendered on the JVM (Roborazzi, 411×914dp, xxhdpi) in Light, Dark,
LargeText (200%) and ReducedMotion, and inspected image by image against the approved reference.
Today, Protect and Insights were also installed and captured on the Android emulator with real usage
data. Side by side: `evidence/compare.html` (before · reference · now · emulator).

Scale per criterion: ✓ matches the reference intent · ~ close, with a known gap · ✗ not addressed.

| Screen | Brand | Craft | Type | Hierarchy | Colour | Access. | Notes |
|---|---|---|---|---|---|---|---|
| Today | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | Headline on the lake, lit cards, real metric. No 7-day bars (data isn't stored per day) |
| Protect | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | App rows with mode chips; modes card in founder copy. No per-app switches (by design) |
| Intent (Shield) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | Centred stage, lit app name, reason and better-choice cards. Duration is a second step |
| Focus running | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | LightRing with art inside, Shield card, elapsed/remaining. No Pause (not a feature) |
| Insights | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | Segmented periods, screen-time card, gradient bars. No percentage-change deltas (not computed) |
| Onboarding welcome | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | First Light, two-tone founder headline |
| Shield blocked, Cooldown, Friction | ✓ | ~ | ✓ | ✓ | ✓ | ✓ | Same stage as Intent. Always Block inspected; Friction and Flexible rendered but not reviewed one by one |
| Settings, Start, Schedules, Item, Web, Plus | ~ | ~ | ✓ | ✓ | ✓ | ✓ | Restrained tier: new surfaces, buttons and type carry over, but no per-screen recomposition |

## Significant problems found, and the fixes

1. **Headlines crossed the treeline** (Today, first pass): the text sat on busy mountains. Fixed by a
   shorter immersive tier (520 → 440dp), so the horizon sits higher, plus a reading-side shade.
2. **Display type too heavy** compared with the references (560 weight). Now 500 at 36sp; Metric is 56/500.
3. **Insights period labels wrapped** ("Today and this week"). Now "7 days / 30 days" in a segmented
   track.
4. **The light-theme horizon line ran through the Protect and Insights titles.** The balanced crop is
   now bottom-biased for the morning art.
5. **Redundant "Shield is running" card** on Protect, found on the emulator with real data. Now shown
   only when Shield needs attention.
6. **The two-tone headline broke exact-copy tests** because of the designed line break. Semantics now
   carry the exact founder string (heading role kept). `NavigationTest` passes.
7. **Brand name inconsistency:** lowercase "aurora" in 71 strings and the Kotlin literals. Title-cased.

## Checked states

- Light, dark, 200% font scale and reduced motion for every captured screen (`*_LargeText`, …).
- Empty data: Today (no protection), Protect (nothing saved), Insights (quiet day). Each shows its designed
  empty state, with no invented numbers.
- Selected and disabled: tab pill and dot; segmented period; disabled Start protection (shown at 40%
  with a reason line).
- Long names: app labels (one line) and the Focus task (two lines) are set to ellipsize. This is code
  only; no long-name render was captured.

## Remaining material issues

- **Configuration screens are restrained, not recomposed.** They inherit the system (surfaces, buttons,
  type, chips) but their layouts are unchanged. They match the brand; they don't match the hero
  screens' craft.
- **Large text:** at 200% the 30dp mark looks small next to the 48sp wordmark (the mark is sized in dp).
  It is readable but unbalanced.
- **App icons in JVM renders are placeholders** (Robolectric has no real icons); the emulator renders
  are the truth for icon rows.
- **No physical-phone visual pass** in this session. A device was attached, but only the emulator was
  used, so as not to touch the founder's phone. Enforcement reliability was not re-tested here.
- **Small screens (360dp) and tablets** were not rendered. The art crops safely by construction, but the
  layouts are unverified there.
