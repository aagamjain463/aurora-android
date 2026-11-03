> Current specification: [design-studio/DESIGN_SYSTEM.md](design-studio/DESIGN_SYSTEM.md) (warm monochrome, 2026-11-02).
> This earlier reinvention reference, and the [Aurora Brand Bible](design-studio/AURORA_BRAND_BIBLE.md), are
> historical where colour, typography, logo or components differ.

# aurora brand system

The current product uses the First Light symbol, a night-sky hero and a warm paper interface. The
source of truth is `ui/theme/` and `ui/components/`. This document supersedes the older palette and
mark in `BRAND.md`.

## Purpose and voice

aurora helps people make room for what matters. Protection describes what is happening now;
progress describes what the person has done. Keep those two ideas distinct. Use plain, calm copy,
specific actions and honest limits. Do not call time away “time saved” or imply productivity.
The product name is lowercase. Screen titles and section labels use sentence case.

Eight founder-approved strings are fixed verbatim in `strings.xml` and checked by `CopyTest`.
All other wording remains proposed until reviewed; see `AURORA_COPY_REVIEW.md`.

## First Light symbol

`AuroraMark` is drawn on a 24-unit grid. A continuous peak joins (4.2,19), (12,8.2) and (19.8,19),
with a 2.6-unit rounded stroke. A thin horizon runs from (2.5,21.6) to (21.5,21.6). A point of light
at (12,3.6), radius 1.9, sits above the summit. The peak reads as an A and as first light reaching
a summit. Keep the point and horizon with the peak; do not replace it with a generic mountain icon.

The adaptive launcher icon, monochrome icon and in-app mark share this geometry. Use the monochrome
version for themed icons. Keep clear space around the mark; decorative instances have no accessibility
label. The wordmark uses Manrope Bold with tight tracking.

## Palette

| Role | Light | Dark |
| --- | --- | --- |
| Background | Linen `#F5F1E8` | Night 950 `#08110E` |
| Surface | Paper `#FFFCF6` | Night 900 `#0E1915` |
| Raised surface | White `#FFFFFF` | Night 850 `#14221D` |
| Primary text | Ink `#0B1411` | Frost `#F1F6F3` |
| Secondary text | `#39453F` | `#B4C2BC` |
| Supporting text | `#5B6761` | `#8A9993` |
| Action / selected | Aurora 700 `#137A57` | Aurora 400 `#7BE3B6` |
| On action | White | Night 950 |
| Warm emphasis | Dawn 700 `#9C4A20` | Dawn 300 `#F7CFAF` |
| Error / destructive | `#B3261E` | `#F2877E` |

Read semantic `Aurora.colors` roles in screens. Accent identifies action and active state. Dawn
identifies commitment, completion and personal context. Neither should become a field of decoration.
The Today hero and Shield use night colours in both app themes. The Shield must remain readable
against an app underneath; its background is opaque.

## Typography

Manrope is bundled locally under SIL OFL; no font download is needed. All sizes use `sp`.

| Style | Size / line height | Weight |
| --- | --- | --- |
| Metric | 64 / 68 | 600 |
| Hero timer | 56 / 60 | 500 |
| Display | 36 / 42 | 700 |
| Headline | 28 / 34 | 700 |
| Title | 20 / 26 | 650 |
| Body | 16 / 24 | 450 |
| Body strong | 16 / 22 | 650 |
| Secondary | 15 / 21 | 450 |
| Label | 16 / 20 | 650 |
| Caption | 13 / 18 | 550 |
| Eyebrow | 13 / 16 | 700 |

Timers use tabular figures. Do not reduce text to fit a fixed-height card. At 200% font scale,
content must scroll and primary actions must remain reachable.

## Layout and components

Use the existing 4dp spacing scale, 24dp screen gutter and inset-aware `AuroraScreen`. Lists use
`RowGroup` and `AuroraRow`; selections use `Chip` or `ChoiceCard`; section introductions use
`ScreenIntro` and `PillarHeading`. Use `DurationPicker` for protection, access, waits, Focus and limits.
Custom duration accepts hours and minutes, validates the range and previews the end time where useful.

Prefer space and hairlines to shadows. One primary action per step. Icon-only actions need spoken
labels and at least 48dp targets. Use the existing line icons; do not mix icon libraries.

## Aurora Light and motion

`AuroraLight` draws a curved horizon, a lit rim and one rising point. Seven-day progress changes
the spread of light; today's progress changes the point's height. An empty history starts with a
thin rim and low point. Decorative light is never evidence of time saved.

Use the existing motion tokens and reduced-motion setting. Aurora Light enters once, then holds.
With reduced motion it appears immediately. Do not add looping animation to ordinary list screens.

## Review evidence

Real emulator captures live in `screenshots/aurora-reinvention/`. Roborazzi covers light, dark,
200% font and reduced motion for the main screens and Shield. A screenshot baseline verifies change
against a reference; it does not establish TalkBack usability, physical-device latency or OEM survival.

## Landscape asset and implementation

`app/src/main/res/drawable-nodpi/aurora_landscape.png` is an original 1536 × 1024 art-only asset generated with the built-in ImageGen tool on 29 October 2026. `AuroraLandscape` composites it with a native dark fade in Today, Shield and dark Focus. Text, app icons, timer arcs, cards and controls remain native Compose. The landscape is decorative; the separate Aurora Light in the progress card still represents recorded history. The Today hero grows with its text instead of imposing a fixed height. Dark primary actions use a restrained emerald-to-warm-white gradient. Light mode retains its darker accessible action colour.

The saved five-screen references guide atmosphere, hierarchy and composition. The implementation uses real local data and honest empty states; it does not reproduce the reference's illustrative numbers or claim pixel-perfect identity. The final visual evidence and remaining launch checks are in [REINVENTION_VALIDATION.md](REINVENTION_VALIDATION.md).

### Final ImageGen prompt

> Use case: stylized-concept. Asset type: text-free reusable Android app hero background, landscape 1536x1024. Create an exquisite, cinematic, naturalistic emerald mountain lake at first light for a premium calm focus app called Aurora. Layers of dark pine-covered mountain silhouettes descend from left and right toward a distant lake valley, soft emerald mist between the ridgelines, very dark black-green sky with fine subtle atmospheric grain. A large thin luminous golden semicircular dawn arc rises just above the central distant ridge, its warm golden sunlight blooms softly into emerald sky and reflects delicately across the still lake. The arc is the signature, elegant and restrained, not a planet or a white sun disk. Horizon and arc in upper-middle, natural lake reflection in lower half. Rich detailed mountain/forest silhouettes, sophisticated depth, quiet near-black emerald foreground fading smoothly to solid #07120f at bottom and corners, ample darkness along top for a native logo and beneath for native headline. Carefully composed premium nature illustration with realistic light, calm and beautiful. No text, letters, numbers, logos, icons, UI, cards, buttons, watermarks, people, animals or buildings. This will be an actual app image backdrop beneath native Compose UI, not an image of an app. Dark emerald and warm pale gold palette; no blue, purple, neon rings or oversaturated sunset orange.
