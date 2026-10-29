# aurora brand

> Historical brand reference. The product reinvention uses [BRAND_SYSTEM.md](BRAND_SYSTEM.md), which
> supersedes the palette, typography and mark below.

This document guides every aurora surface: app, launcher, desktop and web. Code tokens live in
`app/src/main/java/app/aurora/ui/theme/`. If this document and the code disagree, fix one of them.

## Positioning

**aurora helps people take back control of their attention.** Technology should serve human
intentions, not hijack them.

aurora is not a blocker with a dashboard. It is a calm, trustworthy companion. It makes intentional
use the easy path. The feeling we design for is **"I am in control."**

### Personality

| We are | We are not |
| --- | --- |
| Premium | Pretentious |
| Calm | Boring |
| Powerful | Aggressive |
| Minimal | Empty |
| Sophisticated | Complicated |
| Honest | Alarmist |

### Voice

- Speak softly and plainly. Use short sentences and the second person ("your attention").
- Never shame. No guilt about screen time, no streak anxiety, no "you wasted 4 hours".
  An early-ended session is "completely fine".
- Never overclaim. If a feature isn't live, say so in the interface itself, for example:
  "Your choices are saved, but nothing is blocked yet."
- The brand name is always lowercase: **aurora**, including at the start of a sentence.
- Section labels are lowercase with open tracking. aurora never shouts in ALL CAPS.

## The idea: first light

An aurora is light appearing out of darkness. Our visual language is **the horizon at first
light**. It is a quiet, dark (or misty) field where light rises from below. Light is the
reward, not decoration: the more you are in control, the more light there is.

### Signature elements

These four elements make aurora recognisable without the logo. Use them deliberately and
sparingly.

1. **The mark.** A horizon arc that shifts from dawn to aurora, with one rising point of light
   above it. It reads as first light over the horizon, and as a person, because the point is you.
   It is the app icon, the Today tab icon, and the sign-off in Settings.
2. **Horizon glow** (`HorizonGlow`). A wide, low, two-tone light that rises from the bottom edge
   of a hero area. Its intensity carries meaning:
   - idle: dim and neutral;
   - in focus: bright and cool, breathing slowly;
   - complete: warm.

   During onboarding the glow brightens with each step.
3. **The focus arc** (`HorizonArc`). Time moves from the left horizon, over the top, to the right,
   like the sun crossing the sky. The point of light travels along the arc. It is never a generic
   progress ring.
4. **The rising point.** A small dot of aurora light marks "you are here", for example the selected
   tab and live status pills.

### Avoid

Purple-to-blue gradients. Glassmorphism. Glowing orbs placed at random. Card-on-card layouts.
Emoji navigation. Mascots and stock illustrations. Dense charts. Red alarm states for normal
behaviour.

## Colour

Neutrals carry 90% of every screen. Colour has meaning.

### Palette (`Palette`)

| Token | Hex | Use |
| --- | --- | --- |
| Polar 950 | `#0A0F0E` | Dark background (night with a green undertone) |
| Polar 900 | `#111816` | Dark surface |
| Frost | `#ECEFEC` | Dark primary text |
| Polar 400 / 450 | `#8D9894` / `#717E7A` | Dark secondary / tertiary text |
| Mist | `#F3F4EF` | Light background (warm paper) |
| Paper | `#FBFBF8` | Light surface |
| Ink | `#0F1715` | Light primary text |
| Ink 70 / 55 | `#45504C` / `#646F6B` | Light secondary / tertiary text |
| **Aurora 400** | `#7BE3B6` | Signature accent on dark |
| Aurora 500 | `#3FC495` | Glow light |
| **Aurora 700** | `#137A57` | Signature accent on light |
| **Dawn 400** | `#F0B48A` | Warm secondary: completion, preview notes, human moments |
| Dawn 700 | `#A64B22` | Dawn text on light |
| Starlight | `#F6F1E7` | The rising point on dark |

### Semantic roles (`AuroraColors`)

`background`, `surface`, `hairline`, `textPrimary/Secondary/Tertiary`, `accent`, `onAccent`,
`accentSoft`, `dawn`, `dawnText`, `glowCool`, `glowWarm`, `light`, `danger`.

Screens use semantic roles only, never raw palette values. Each role is also mapped onto Material 3
(`toMaterial()`), so stock components inherit the brand.

### Rules

- **Accent is for action and "on" states.** That means the primary button, selected controls and live
  status. Don't use it for decoration.
- **Dawn is for warmth and honesty.** Use it for completion, "preview" notes and "soon" tags. It is
  never an error colour.
- **Contrast.** Primary and secondary text meet WCAG AA (4.5:1) on background and surface in both
  themes. Tertiary text is for supporting copy (≥ 4.2:1). Accent buttons pass AA: white on
  Aurora 700 and Polar 950 on Aurora 400.
- Hairlines are 8% ink or white. Prefer whitespace to borders, and borders to shadows. aurora uses
  no drop shadows.

## Typography

**Manrope** (SIL OFL), bundled as one variable font. It is geometric enough to feel precise and
humanist enough to feel warm.

| Style | Size / line | Weight | Tracking | Use |
| --- | --- | --- | --- | --- |
| Hero | 64 / 68 | 300 | −3.5% | The one number a screen is about (timer, total). Tabular figures. |
| Display | 34 / 40 | 600 | −2.5% | Screen titles, emotional headlines |
| Headline | 26 / 32 | 600 | −2% | Dialog titles, completion |
| Title | 18 / 24 | 600 | −1% | Empty-state titles, emphasis numbers |
| Body | 16 / 24 | 400 | 0 | Default copy |
| Body strong | 16 / 24 | 600 | −0.5% | Row titles |
| Secondary | 14 / 20 | 400 | 0 | Supporting copy |
| Label | 15 / 20 | 600 | 0 | Buttons, pills |
| Caption | 12 / 16 | 500 | +1% | Metadata, tab labels |
| Eyebrow | 12 / 16 | 600 | +9% | Lowercase section labels |
| Wordmark | 22 / 26 | 600 | −3% | "aurora" |

Large numbers are light and headlines are firm. That contrast is part of the voice. All sizes
are in `sp`, so they respect the system font scale.

## Space and layout

- 4dp grid: `4 · 8 · 12 · 16 · 24 · 32 · 48`.
- Screen gutter is **24dp**. Grouped surfaces sit at 16dp, so their content aligns with the gutter.
- Large titles scroll away into a compact top bar that shows a hairline only once content passes
  under it.
- Use one primary action per screen, full width, placed where the thumb is.
- Minimum touch target is 48dp. Rows are at least 64dp.
- Layouts are edge to edge. Content runs under the system bars and is padded by insets, never by
  fixed values.

## Shape

| Token | Radius | Use |
| --- | --- | --- |
| Pill | 50% | Buttons, choice pills, text fields, status |
| S | 10dp | Small tiles, skeletons |
| M | 16dp | Reserved |
| L | 24dp | Grouped row surfaces, notices |
| Dialog | 28dp | Dialogs |

Interactive elements are fully round. Containers are soft but never bubbly.

## Iconography

aurora draws its own line icons (`AuroraIcons`): a 24dp grid, a 1.75 stroke, round caps and joins,
and no fills. Icons are literal and quiet. Today uses the mark, Protect a shield, Insights three
bars and Settings two sliders (not a cog). Add new icons in the same file and style. Don't
mix in icon libraries.

## Motion

Motion explains change. It is never a performance.

- **One curve**: `CubicBezier(0.2, 0, 0, 1)`. It responds quickly and settles slowly.
- **Durations**: Quick 120ms (press, small exits), Standard 240ms (state changes), Gentle 420ms
  (screen and phase changes), Breath 4.2s (ambient glow).
- **Screens** slide in a short distance (1/12 of the width) as they fade. Tabs only cross-fade.
  The focus screen rises with a slight scale-in, the one moment of ceremony.
- **Press feedback**: buttons scale to 0.97.
- **Ambient**: only the in-focus glow breathes and only live status dots pulse. Nothing else loops.
- **Reduced motion**: this applies when the in-app setting is on or Android animations are off.
  Nothing moves, scales or loops. State changes snap, and screens only cross-fade.

## Components

Everything lives in `ui/components/`. Build new screens from these components before you create
new ones.

| Component | Purpose |
| --- | --- |
| `AuroraButton` (Primary / Secondary / Quiet) | Actions. One Primary per screen. |
| `AuroraIconButton` | 48dp icon-only action, always labelled for screen readers |
| `AuroraScreen` | Large-title scaffold with collapsing top bar and inset handling |
| `AuroraNavBar` | Three-tab bar with the rising-point indicator |
| `RowGroup`, `AuroraRow`, `ToggleRow`, `RowDivider`, `SectionLabel` | Settings-style lists |
| `ChoicePills`, `SelectionMark`, `AuroraSwitch`, `AuroraTextField` | Selection and input |
| `StatusPill`, `Notice`, `EmptyState`, `SkeletonRows`, `AuroraDialog` | Status and feedback |
| `ComingSoonRow` | An honest, non-interactive preview of a planned feature |
| `AuroraMark`, `Wordmark`, `HorizonGlow`, `HorizonArc` | Brand signatures |

## App icon

The adaptive icon (`res/drawable/ic_launcher_*`) has three layers:

- **Background:** Polar 950 with a faint aurora glow rising from below.
- **Foreground:** the mark, with a dawn → aurora arc and a Starlight point.
- **Monochrome:** the same mark, for Android 13+ themed icons.

## Accessibility

- Every icon-only control has a label. Decorative graphics are hidden from screen readers.
- Rows are single focus targets with correct roles: checkbox, switch, radio and tab.
- Section labels and screen titles are headings.
- Timers announce "12 minutes remaining", not each second.
- Respect font scale, reduced motion and both themes.
