# 01 — Research and design audit

Source basis: the five approved references in `docs/aurora-design-reference/` (opened and inspected
image by image), Aurora's rendered screens before this work (`git show 489d4a0:app/src/test/screenshots/*`),
and working knowledge of Apple, Opal and Linear product design. No live web browsing was done in this
session; observations about other products are general design patterns, not copied screens or assets.

## What makes premium consumer products feel premium

1. **One signature, used with restraint.** Apple's product pages lean on one hero image per idea, then
   go quiet. Opal's identity is a single luminous gem, not decoration everywhere. Linear's is one hue and
   a precise grid. Premium is a strong signature plus a calm system around it.
2. **Type does the work.** Large, light-to-medium display weights, generous leading, short lines. Heavy
   bold headlines read as utility; lighter large type reads as confidence.
3. **Surfaces have depth without noise.** Near-black tinted surfaces, 1px hairlines brighter at the top
   edge (light from above), soft corner radii. No glass blur needed.
4. **Exactly one primary action per screen, and it glows.** Everything else is quieter.
5. **Numbers are the hero of data screens** and are always true. Empty states are designed, not zero
   placeholders.
6. **Motion confirms, never decorates**: press scale, settle-in, one ambient breath at most.

## Generic-AI failures to avoid (and where Aurora had them)

| Failure | Aurora before |
|---|---|
| Heavy bold headline on flat dark | Today, Protect and Insights used 700-weight 36sp headings on plain background |
| Art used once, then abandoned | Only Today had the landscape; Protect and Insights were plain lists |
| Identical cards everywhere | Same flat `surface` box with hairline, no hierarchy |
| Primary action reads like any Material button | Flat accent pill; gradient only in dark, hard-stopped at 65% |
| Lowercase wordmark that reads as a placeholder | "aurora" in plain bold text, no symbol on tab screens |
| Information without hierarchy | Protect led with a status card, then a disabled button, then lists |

## What the approved references get right

- Cinematic horizon: emerald-black sky, a thin gold ring rising over mountains, lake reflection. The ring
  sits right of centre so type has a dark field at the left.
- Two-tone headlines: the lead phrase in warm cream, the end in white, set in a light-medium weight.
- Mark plus "Aurora" wordmark top left; one round control top right.
- Cards are dark glass with a lit top edge and rounded 24–28dp corners; each card has an icon badge in
  a ringed circle.
- Mode colours: Friction gold, Cooldown glacier blue, Always Block emerald, Flexible neutral.
- The CTA is a wide pill, emerald warming to pale gold, with a soft glow beneath.
- Focus: a big full-circle timer ring with the landscape visible inside it.
- Lifted bottom bar with rounded top corners; selected tab in a soft pill with a dot.

## Where the references are not production UI

- Illustrative data (streaks, 18 apps, percentages) is not real; Aurora must bind real data or show
  designed empty states.
- Intent merges reflection and duration choice into one screen; Aurora keeps the reflection pause as its
  own step (it is the product), then asks for the time.
- Protect's per-app toggles imply a global on/off per app that Aurora doesn't have (protection is a
  session or schedule). Aurora shows each app's mode as a chip instead.
- A profile button implies accounts; Aurora has none, so the top-right control is Settings.
- "Pause" in Focus is not a feature; it is not drawn.

## Direction

Keep the First Light composition as the signature. Give it three intensities (immersive, balanced,
none). Lighten display type, title-case the brand, build one lit card surface and one glowing CTA, and
recompose the five hero screens on that system so every other screen inherits it.
