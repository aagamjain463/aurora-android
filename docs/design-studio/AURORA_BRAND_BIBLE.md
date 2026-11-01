# Aurora Brand Bible

The definitive brand specification. **Aurora's Northern Lights brand identity is the default visual
source of truth. Functional changes must reuse its design system unless the founder explicitly requests
a redesign.**

Detail lives in the numbered studio docs; this page is the rulebook.

## 1. Concept and promise

- **Concept: the Northern Lights.** Everything in Aurora is a polar night and the light that moves through it.
  Darkness is automatic behaviour; the aurora is awareness and choice.
- **Promise:** *Your phone. Your rules.*
- **Feels like:** calm, vast, alive, intentional. Empowering, never punitive.
- **One night.** The northern lights only exist in the dark, so Aurora has one theme. There is no light mode.

## 2. Logo

- **The master is `brand/final/aurora-final-logo.png`, chosen by the founder. It is never edited, regenerated or reinterpreted.** `LogoMasterTest` holds its checksum.
- Symbol: a capital A formed from flowing, translucent northern lights ribbons, emerald at the left into warm gold at the right, on midnight green.
- Wordmark: "Aurora" as drawn in the master. It is artwork, not a font: use `Wordmark`, never type it out in Cormorant or anything else.
- Tagline: "Your phone. Your rules." It stays separate from the logo artwork.
- In the app: `AuroraLogo` (symbol over wordmark), `AuroraMark` (symbol), `Wordmark`. On tab screens use `AuroraHeader`. All are cut from the master by `brand/tools/final_logo.py`; see `brand/final/README.md`.
- The app icon sets the symbol on the master's midnight green, without the wordmark.
- The cutouts are light lifted off a dark ground, so use them on night or midnight only. There is no version for pale backgrounds.
- The vectors in `brand/final/` are approximations for editing and for sizes under about 20dp. Never use them where the raster fits.
- Don't: recolour it, redraw it, rearrange symbol and wordmark, put it in a box, rotate it, or merge the tagline into it.

## 3. Colour

All from a real aurora over a polar sky. Tokens are in `brand/tokens/aurora.tokens.json`.

| Role | Value | Means |
|---|---|---|
| Night | `#080A0F`, surfaces to `#1E2F27` | The real sky: aubergine black, green-black where the light pools |
| Aurora green | `#4BEA55` | "On" or "protected", and the bright band of the ribbon. Oxygen green, measured from footage |
| Emerald | `#17A850`, fading to `#0B5A2A` | The body of the ribbon, in light only |
| Hot green | `#C9FFB4` | The knot of light on a primary action, never in the sky |
| Indigo | `#3D3A9E` | The top of a tall ray, in light only, never controls |
| Rose | `#F58BD0` | The pink fringe of a strong display: streaks, progress, the Friction mode |
| Ribbon sweep | `#8BF27A` → `#4BEA55` → `#1FC46A` | The one primary action |
| Pale aurora | `#DDF9D4` | The lead line of hero headlines |

- There is no gold, cream or sunrise anywhere. Soft red, the rare red of a high aurora, means "end early". Card edges catch the light, green fading into indigo.
- Modes: Flexible mist · Friction rose · Cooldown glacier · Always Block aurora. Never invent a mode colour.

### Glass

Every surface is glass (`Modifier.glass`, in `ui/components/Glass.kt`), and `auroraSurface` is the same thing. A thin dark base so text always reads, a frost that catches light from the top left, and a bright edge. `tint` colours it for a mode or a chosen state. Filled actions carry `gloss`, a reflection across their upper half.

What floats over moving content (the dock, the top bar) is `frosted`: real blur of the page behind it, Android 12 and later. Dialogs use the native window blur. Cards don't blur: they sit over soft light, and a blur per card would cost too much under the live sky. Never hand-roll a surface with `surface` or `elevated` and a hairline.

## 4. Typography

Cormorant Garamond 500 gives Display (50/52sp), DisplayXL (68/70sp), DisplayS (36/40sp) and the wordmark a fine, luxurious
voice: its thin strokes are light on a night sky. Every big number is Cormorant 400, lining and tabular: engraved, not a
stopwatch. Cormorant's letters are small for their size, so it is set large and never below 30sp. Geist is the
functional voice: titles and labels at 560, body at 420. Both are bundled. Hero headlines are two-tone: lead phrase in pale aurora (`headlineLead`), end
on its own line in full white. Small numbers use tabular figures. Minimum body size is 15sp; minimum supporting text 13sp.

## 5. Artwork

One painting: a polar night over a frozen lake, a near-black massif at the left, snow ridges at the right and a far
range in the valley (`brand/tools/aurora_art.py`). The land is generated, never traced from a photograph. Three intensities:

| Stage (560dp) | Immersive (480dp) | Balanced (380dp) | None |
|---|---|---|---|
| Focus, Shield and Intent prompts | Home, onboarding | Shield, Intent and Track tabs, Plus | Settings, builders, dialogs: only the faint `TopAurora` |

**The living sky** (`AuroraSky`) is the brand, and it is calm: light to rest under, never a light show. Broad soft bands of green spread across the sky with real darkness and stars between them, and never burn white. They wind very slowly, brighter patches wander along them, soft rays rise from them, and a dim reflection lies on the ice. Founder rule: spread, never concentrated, but never a full-sky fill either. It never crosses the land. Android 12 and below show `aurora_still`, the same scene painted.

| Sky | When | Looks |
|---|---|---|
| Quiet | Nothing protects you | A slow aurora; earned light makes its rays taller |
| Lit | Always protection, the Shield | Brighter |
| Alive | A timed session or Focus runs | The full ribbon |
| Flare | A Focus session completes; onboarding ends | One surge, then alive |

**The thread is the clock.** While something is timed, the opening's thread of light runs along the horizon as far as
the time has gone, and the sky is lit up to it. Never add a second timer shape.

Never put text into the art, never use a screenshot as a background, never add a second illustration style. Data
visuals are drawn as light from real data; never fake the sky with sample progress.

## 6. Layout

- Content is a reading column of at most 640dp (`ContentMaxWidth`); on tablets the art spans the screen,
  the cards and the dock don't.

- 24dp text gutter; cards inset 16dp; 8dp between cards; 16–24dp inside.
- Hero screens: header (mark, wordmark, one round control), art, headline at the left over the lake,
  then cards.
- Configuration screens: large title, `SectionLabel`, `RowGroup`, no art.
- One primary action per screen, full width, near the content it acts on. Home's is Start blocking,
  a filled row: one hour with the last-used apps. Saved sessions follow as neutral cards, each holding
  its own schedule; statistics come after them.
- Home shows what matters now. Shield attention, a finish, Focus reports and running sessions sit
  directly under the headline. A Shield problem is a large warning card with a one-tap fix, and it holds
  the filled action. While a session runs it fills Home: nothing to start, no statistics. Past runs live on Track.
- A store caption never repeats the headline already visible on the phone in the same image.
- Tabs sit in a floating 72dp dock, inset 16dp with 12dp above and below; reserve at least 96dp plus system
  navigation insets. The dock and content clearance grow with font scale. The selected tab carries a ribbon of aurora.
- `BrandEyebrow` opens sections with a short thread of light. Use sentence case or
  short editorial labels; supporting text stays at least 13sp.
- Borders are supporting structure: tinted edges at 24% and warm edges at 16%, fading toward the base.
  Reserve full accent outlines for selected controls.

## 7. Components

Use the system in `04-design-system.md`. The essentials: `auroraSurface` for every container,
`AuroraButton` kinds (Primary, Secondary, Quiet, Danger), `LitBadge` for state icons, `ModeChip` for
modes, `AuroraNavBar` for tabs.

## 8. Motion

**The opening is the signature.** On a cold start, or a return after 5 minutes away, a point of light catches at the left of the night and races along the ribbon's path, rays rise from it, and the ribbon climbs off the top of the screen, lifting the night from the app (5.2s, after the splash mark has drawn and held; a tap hurries it). On a first run it holds once the ribbon is lit, and the person swipes up to lift it. Screens wait for the night to part (`Opening.awaitParted`), then perform their own openings. It is always night, in both themes.

Every screen is under the same moving sky. There is one clock, so the light carries on across screens. Plain screens carry the ribbons at the top (`TopAurora`), and the light down every page wanders slowly (`PageGlow`). Shield lands with a sweep of aurora. Choice pills share one lit pill that slides between options. Key numbers count up once (`CountUpText`).

Motion must be visible to the naked eye (founder, 2026-10-31). Brightness stays where it was: the sky moves more, it does not burn more.

Quick to respond, slow to settle (`Motion.Ease`). Press sinks to 0.94 (buttons) or 0.96 (cards) and springs back with a small bounce. Blocks climb 44dp with a stagger, down the page, each time a tab is chosen, and rise in on their own when scrolled to or loaded late. Coming back from a pushed screen does not replay them. Pushed screens slide in from the right while the one below sinks to 0.92. Tabs fade in over each other, so the sky never dips. Knots of light cross the primary action, and the rim of a card a screen leads with. Timers glide linearly. The sky moves faster while a session runs. Starting gets a surge and a sweep of aurora; a finish gets one surge with Haptic Confirm. Sky changes settle over 1.6s. Reduced motion snaps everything: the sky still lights, but never moves.

## 8a. Outside the app

The brand continues where people meet it without opening Aurora, always quiet and always true:

- **Ongoing notification** (`SessionNotification`): only while a timed session or Focus runs; title
  is the session, text is the mode and the end time; a native countdown; Android 16 progress style.
  Always sessions never post one. Small icon is the one-colour silhouette of the symbol (`ic_stat_aurora`).
- **Widget** (`AuroraWidget`): night surface, the small-size symbol (`ic_mark`), gold eyebrow, Cormorant title. Idle it carries the
  promise; running it names the session and counts down.
- **Quick Settings tile** (`AuroraTile`): lit while protection runs, naming it.
- **Splash and opening (First Light)**: the system splash is empty midnight green. The approved logo forms once, along its own ribbons (`AuroraUnveil`), the wordmark follows, it holds, and the night fades off the page as it rises. About 4.5 seconds on a real arrival only; a tap hurries it; never on a return, or over a screen reader; a running session gets only the finished logo and a short fade. Assets: `brand/tools/first_light.py`.
- **The opening** (`AuroraUnveil`): see section 8.
- **Store art**: `brand/tools/store.sh` frames the verified dark baselines; the feature graphic is the
  lockup and the promise over First Light.
- **Plans**: "Aurora Free" and "Aurora Plus"; "Plus" alone where space is short. Never lowercase.

## 9. Tone

Second person, plain, specific, kind. Tell the truth about what Shield can and can't do. Founder-approved
strings are exact and centralised in `res/values/strings.xml` (checked by `CopyTest`):

- Your phone. Your rules. · Time Reclaimed · Never get distracted from what matters.
- Flexible: Blocked until your timer ends, or you turn it off.
- Friction: Complete a challenge before getting access.
- Cooldown: Wait before you can open this app.
- Always Block: No in-app early exit. Protection ends when your timer does.
- I'm ready to commit

**Say each fact once.** No eyebrow or supporting line that repeats a title, no label that repeats its card, no
explainer after the person has used the thing, and no card for something with nothing to show. What stays, however
long: what a commitment locks, what Shield can't do, and anything needed to decide or give consent.

## 10. Approved references

The recorded screens in `app/src/test/screenshots/` are the benchmark. `docs/aurora-design-reference/` holds the older
gold design, for history only.

## 11. Do and don't

| Do | Don't |
|---|---|
| Real data, or a designed empty state | Sample stats "for the screenshot" |
| Light display type, big | Heavy bold headings |
| One aurora-sweep primary action | Several filled buttons competing, other gradients |
| `auroraSurface` cards with a lit edge | Flat grey Material cards, glass blur |
| The Ribbon, oxygen green, indigo only in the light | Gold, sunrise, rainbow gradients |
| Calm, honest copy | Guilt, streak shaming, "Lockdown" |
| Art on hero screens only | Landscape behind every form |
| Aurora that means protection | Claiming "Protected now" while Shield is off |


## 12. Recognition and category ownership

**Category:** intentional phone use. **Descriptor:** Phone use, on your terms.
**Promise:** Your phone. Your rules.

Use the same promise across onboarding, store artwork and campaign headlines. The app makes it
concrete through Shield (protect attention), Intent (open with purpose), and Track (see progress).
Show the Ribbon consistently; do not invent a new logo for
each feature or campaign. Cormorant headlines against Geist controls make the product recognisable
without adding decorative elements to every screen.

The emotional benefit is a fuller day. The functional proof is control over distracting apps,
intentional openings and measured progress. Trust comes from local data, honest protection status,
clear permissions and calm recovery paths. Never promise an unbreakable block or guaranteed outcomes.

Premium means fewer competing signals: one filled action, generous reading space, legible secondary
text, restrained colour and complete empty states. Category leadership still requires reliable product
behaviour, customer research and repeated exposure; visual design alone does not establish it.
