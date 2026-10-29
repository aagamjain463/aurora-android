# Aurora Brand Bible

The definitive brand specification. **Aurora's approved First Light brand identity is the default visual
source of truth. Functional changes must reuse its design system unless the founder explicitly requests
a redesign.**

Detail lives in the numbered studio docs; this page is the rulebook.

## 1. Concept and promise

- **Concept: First Light.** Darkness is automatic behaviour; the first light over a horizon is awareness
  and choice. A ring of light rests on the horizon and a point of light rises above it: the person.
- **Promise:** *Make room for what matters.*
- **Feels like:** calm, confident, clear, free, intentional. Empowering, never punitive.

## 2. Logo

- Symbol: ring (emerald gradient) plus point (sun gold), 24-unit grid. Masters are in `brand/logo/`.
- Wordmark: "Aurora", Manrope 500, title case. Never lowercase, never another font.
- Lockup: symbol left, wordmark right, gap = 0.33 × symbol height. On tab screens use `AuroraHeader`.
- Do: use it on night or paper, at 16dp or more, in full colour or one colour.
- Don't: add a sun disc, rays or waves; recolour the ring gold or the point green; put it in a box; rotate
  or outline it; set "aurora" lowercase.

## 3. Colour

Founder colours: accent `#7BE3B6` (dark) / `#137A57` (light), dawn `#F0B48A`. Supporting: sun gold
`#F3C77D`, Night 950 background, Linen background. Tokens are in `brand/tokens/aurora.tokens.json`.

- Emerald means "on", "protected" or "go". Sun gold means warmth, progress and the person's own words.
  Coral means "end early". Nothing is red.
- Modes: Flexible mist · Friction sun · Cooldown glacier · Always Block aurora. Never invent a mode colour.

## 4. Typography

Manrope only. Display sizes in light weights (500); titles at 620; body at 450. Hero headlines are
two-tone: lead phrase in First Light cream (`headlineLead`), end on its own line in full white. Numbers
use tabular figures. Minimum body size is 15sp; minimum supporting text is 13sp, never below
`textTertiary` contrast.

## 5. Artwork

Only First Light, at three intensities (see `03-first-light-artwork.md`):

| Immersive | Balanced | None |
|---|---|---|
| Today, Focus, Shield and Intent, onboarding ends | Protect, Insights | Settings, configuration, pickers, dialogs |

Never put text into the art, never use a screenshot as a background, never add a second illustration
style. Data visuals (Aurora Light, LightRing, bars, week dots) are drawn as light from real data.

## 6. Layout

- 24dp text gutter; cards inset 16dp; 8dp between cards; 16–24dp inside.
- Hero screens: header (mark, wordmark, one round control), art, headline at the left over the lake,
  then cards.
- Configuration screens: large title, `SectionLabel`, `RowGroup`, no art.
- One primary action per screen, full width, near the content it acts on.

## 7. Components

Use the system in `04-design-system.md`. The essentials: `auroraSurface` for every container,
`AuroraButton` kinds (Primary, Secondary, Quiet, Danger), `LitBadge` for state icons, `ModeChip` for
modes, `AuroraNavBar` for tabs.

## 8. Motion

Quick to respond, slow to settle (`Motion.Ease`). Press 0.97. Phase fades 240–420ms. Timers glide
linearly. One ambient breath, only while a session runs. Haptic Confirm on start and finish. Reduced
motion snaps everything.

## 9. Tone

Second person, plain, specific, kind. Tell the truth about what Shield can and can't do. Founder-approved
strings are exact and centralised in `res/values/strings.xml` (checked by `CopyTest`):

- Make room for what matters. · Time away from protected apps · Never get distracted from what matters.
- Flexible: Blocked until your timer ends, or you turn it off.
- Friction: Complete a challenge before getting access.
- Cooldown: Wait before you can open this app.
- Always Block: No in-app early exit. Protection ends when your timer does.
- I'm ready to commit

## 10. Approved references

`docs/aurora-design-reference/01-today.png` … `05-insights.png` are the creative benchmark. Match their
atmosphere, hierarchy and character. Never their illustrative numbers.

## 11. Do and don't

| Do | Don't |
|---|---|
| Real data, or a designed empty state | Sample stats "for the screenshot" |
| Light display type, big | Heavy bold headings |
| One glowing primary action | Several filled buttons competing |
| `auroraSurface` cards with a lit edge | Flat grey Material cards, glass blur |
| Ring and point, emerald and gold | Generic sunrise, rainbow gradients, neon |
| Calm, honest copy | Guilt, streak shaming, "Lockdown" |
| Art on hero screens only | Landscape behind every form |
