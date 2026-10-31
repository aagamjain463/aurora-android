# Opal design study and Aurora roadmap

> Superseded in part: the founder chose Northern Lights as the identity and a signature opening. See `11-northern-lights-pass.md`.

Studied 2026-10-30 on a Redmi 22041219I (1080×2408, 440dpi), Opal 4.17.0 (`com.withopal.opal`).
Method: observing the running app with screenshots, screen recordings and the accessibility tree.
The APK was not pulled, decoded or decompiled, and no runtime instrumentation was used.

Labels: **Observed** was seen on the device. **Inferred** is a reasonable reading of what was seen.
**Unknown** was not established.

## 1. What makes Opal feel like Opal

The brand is a single idea: **a precious object on a dark stage**. Nearly every other choice stays quiet so
that the object can perform.

### Branding

| Element | Observed |
|---|---|
| Hero object | Photoreal iridescent opal on a plinth, between dark grey rock, under a starfield. Its silhouette differs between captures, so it moves or is rotated (rendering method unknown) |
| Object as system | The same language recurs: rendered flame for streaks, rendered hourglass for focus hours, rendered LCD timer set into rock |
| Logo | Wordmark whose "O" is an organic pebble ring. The same pebble is the Home tab icon and frames the avatar |
| Parent crest | "The Focus Company" laurel seal with Latin motto. Full screen on launch, watermarked behind the profile and the guest pass. It reads as an institution, not an app |
| Palette | True black `#000000`; cards `#0D0D0D` to `#121212`; selected nav pill about `#21332C`; accent is a vertical gradient from `#C5FDB7` to `#ADFBE4`; white primary button |
| Atmosphere | A faint green glow at the top of each screen, and a soft green glow at the bottom of some cards |
| Warmth and alarm | Amber flame is the only warm colour. Pink-red appears only in "worse than average" bars |
| Type | One neutral grotesque throughout, plus a heavy wide cut for reveal numbers such as "21 years". Exact fonts unknown |
| Voice | First person and intimate: "I can help with that, [name]." "Yep, you read this right." Personal and confrontational |
| Sharing | "30-day guest pass" drawn as a physical pass in the accent gradient with the crest watermarked |

### Product design

| Pattern | Observed |
|---|---|
| Layout | 24dp gutters, large-radius cards, 48dp round back button, about 50dp full-width pill buttons |
| Home hierarchy | Object, then one number (Score), then three sub-scores joined to it by a drawn bracket, then screen time |
| Persistent action | "Start to focus" pill floats above the dock, over scrolling content, which fades out beneath it |
| Dock | Three tabs. The selected tab has a tinted pill, tinted icon and tinted label |
| Score detail | Large arc gauge, bracket, sub-score pills, plain explanation, then comparison bars with a marked AVG tick |
| Sub-score pills | Filled when low or empty, accent outline when strong (Sleep 0, Focus 90, Rest 90) |
| App lists | Name with a thin accent usage bar whose length encodes time |
| Picture cards | "For you" presets are tall art cards with name, duration and Start. Each is its own 3D image |
| Empty states | Ghost circles where app icons would be. A dashed sample curve for the screen-time forecast, with "Check back in 10 days" |
| Onboarding | Conversational and unhurried: one thought per screen, personal projection, then permissions in the same voice, on cards rather than system prompts |

### Motion

Timings are approximate. They come from 2fps review and scene detection, not frame-accurate measurement.

| Moment | Observed |
|---|---|
| Word-by-word reveal | Text arrives one word at a time, each fading in from a blur, about 200ms a word |
| Number wheel | The big number rolls like a vertical picker, with neighbouring values blurred and dim above and below (4, 6, then 7 years) over about 1 to 1.5s |
| Staged action | The Continue button and footnote appear about a second after the sentence ends. Continue arrives with a light sweep across it |
| Layout shift | Existing text slides upward to make room for new content |
| Score load | Values start as dashes, then fill |
| Tab change | Effectively instant, about 100ms or less |
| Launch | Crest holds on black for about 3 seconds, then Home |

**The takeaway:** Opal keeps the interface still and spends its motion on a few narrative moments.

## 2. Where Aurora already stands

Aurora is already close in mood: a dark background, a mint accent, a floating dock and round controls.
This has two consequences.

1. Copying Opal further would make Aurora look like a derivative of the category leader. That costs recognition,
   and the bible's section 12 makes recognition the goal.
2. Aurora has the stronger concept. First Light gives light a meaning (protected, running, earned), whereas
   Opal's object is decorative. Aurora should borrow discipline and craft, not appearance.

## 3. Scope: what to take

### Do not copy

These are Opal's identifiable brand and creative work. Reproducing them creates trademark, trade dress and
copyright exposure, and it erases Aurora's identity. Have counsel review before any launch that moves closer to Opal.

- The gem or any jewel-as-hero, and the pebble "O"
- The crest, the motto and the "Focus Company" institutional device
- Any Opal artwork, screenshots, icons, fonts or copy, including its sentences
- The mint-to-aqua gradient as a signature, and true black with `#121212` cards as a wholesale swap
- The name "Score" with Sleep, Focus and Rest as an identical bracketed trio

### Do not copy, on principle

- **Fear projections** ("21 years of your life"). Aurora's bible rules out guilt, and its tone is kind and true
- **A headline number with thin data.** On this device Opal showed a Score of 80 with Sleep 0 and a young account.
  Aurora does not show numbers it cannot back up
- **Sample curves in empty states.** The dashed forecast is close to Aurora's banned sample statistics

### Adopt, in First Light's language

| Opal technique | Aurora's version |
|---|---|
| One object carries the brand | The ring and point already do. Make them the single performer on every hero screen |
| Word-by-word blur reveal | `WordReveal` with Aurora's own copy, for the few moments that earn it |
| Number wheel with blurred neighbours | Extend `RollingClock` for milestone reveals, in Newsreader |
| Staged action after the sentence | Delay the primary action until the thought lands |
| Floating action above the dock | Home's Primary action, once the person scrolls past it |
| Earned objects | Streak and focus-hours marks drawn as First Light glows, not a second illustration style |
| Ghost empty states | Faint ring-and-point placeholders with an honest date, and no invented curve |
| Value bars in app lists | Thin usage bars drawn as light from real data |
| Conversational permissions | One thought per screen, using the existing tone rules |
| Shareable pass | A guest pass with the First Light art and the promise |

## 4. Roadmap

Each phase ships alone. Effort: S is days, M is about a week, L is two weeks or more.
Every UI step ends with `./gradlew recordRoborazziDebug` and a review of the changed images in
`app/src/test/screenshots/`. Every motion item must snap under `Aurora.reducedMotion`, and animated text must be
read as one string by TalkBack.

### Phase 0: Decisions (S)

- Founder rules on section 6.
- Record the outcome in `DESIGN_DECISIONS.md`.

**Done when:** the bible reflects each amendment.

### Phase 1: Narrative motion primitives (S)

Add to `ui/components/Motion.kt`:

- `WordReveal`: words fade in from a soft blur at about 200ms each. Blur needs API 31 (`Modifier.blur` has no effect
  below it), so older devices use fade only.
- `RollingNumber`: extends `RollingClock` with dimmed neighbour values.
- `StagedAction`: holds a block until a preceding reveal completes.

**Done when:** the three have tests or screenshots in both themes, at 200% font scale and with reduced motion.

### Phase 2: Onboarding as a conversation (M)

`ui/onboarding/OnboardingScreen.kt`, `res/values/strings.xml`

- One thought per screen, using `WordReveal` and `StagedAction`.
- Replace any projection with a truthful, kind statement built from the person's own answer. No life-years framing.
- Show permissions as `auroraSurface` cards with `AuroraSwitch`-style state and one line of honest purpose each.
- End on the existing `Sky.Flare`.

**Done when:** the founder approves the new copy (it is checked by `CopyTest`) and the flow passes with TalkBack.

### Phase 3: Home hierarchy (M)

`ui/home/HomeScreen.kt`, `ui/home/HomeSections.kt`, `ui/components/Scaffold.kt`

- Stage the hero: art, then a single true statement or number, then cards.
- Primary action floats above `AuroraNavBar` after it scrolls off screen, with content fading out beneath it. It is
  the same action, so the screen still has one Primary.
- Thin light usage bars in lists, using `rememberFill`.

**Done when:** one filled action is visible in every scroll position, and all numbers come from real data.

### Phase 4: Earned-object system (M)

`ui/components/Light.kt`, `ui/insights/InsightsScreen.kt`

- One visual rule: progress is a glow in the ring-and-point language, sized by real history.
- Profile or Track summary with two or three earned marks.
- Ghost empty states replace blank areas; a "Check back on [date]" line only where the date is true.

**Done when:** a brand-new user sees no invented value anywhere.

### Phase 5: Picture presets (L)

`ui/protect/`, `brand/tools/first_light.py`

- Presets become tall cards with distinct times of day from the First Light painting, for example first light, noon haze
  and dusk. This needs a bible amendment to "Art on hero screens only".
- Generate them from the existing procedural tool, so there is still one illustration style.

**Done when:** each preset is recognisable at a glance and text stays legible at 200%.

### Phase 6: Shareable pass (S, needs a product decision)

- Guest pass card with the First Light art, the mark and the promise.
- Only build it if referral is actually wanted.

**Done when:** a pass can be shared and redeemed end to end.

## 5. Gaps in this study

Not observed:

- **Block screen.** Triggering it means starting a session on the founder's phone. This matters most, since it is the
  moment Aurora's Shield competes with directly.
- Paywall, the first onboarding screens, the rule builder, the "Start to focus" sheet, session running and completion
  states, and settings.

Not established without inspecting the app package: exact fonts, exact animation curves and durations, whether assets are
video or real-time 3D, and the true type scale.

To close the runtime gaps, the founder can start a short session and open a blocked app while a recording runs.

## 6. Decisions for the founder

Recommendation first. Phases 2 to 5 need the first decision.

1. **Stay First Light and adopt technique (recommended), or redesign toward Opal's look.** This roadmap assumes the
   former. The latter would break the bible's core rules and is not recommended, for legal and recognition reasons.
2. **Allow one heavier numeric moment?** The bible rules out heavy bold headings. Recommendation: no. Keep Newsreader 300;
   it is more distinctive than Opal's heavy cut.
3. **Allow art on preset cards?** Recommendation: yes, only from the First Light painting (Phase 5).
4. **Keep the streak mark?** The bible allows streaks but not shaming. Recommendation: yes, with no loss messaging.
