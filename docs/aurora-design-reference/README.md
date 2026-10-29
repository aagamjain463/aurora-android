# Aurora — Locked Visual References (Five Screens)

These five images are the approved visual TARGETS for Aurora Android. Their composition, premium brand identity, illustration, visual hierarchy, color and typography take precedence over stylistic interpretations in old specs.

- `01-today.png` — home, hero scene, main metric, protection, separate progress, bottom tabs.
- `02-protect.png` — Protect app list, mode cards, primary action, premium visual styling.
- `03-intent.png` — Intent opening prompt, reflection, duration selector, cooldown behavior.
- `04-focus.png` — focus session hero timer, protected apps, elapsed/remaining, actions.
- `05-insights.png` — analytical charts, metrics, progress, bottom tabs.

These are **concept renders, not executable layouts or source assets**. Text and sample data in the images are illustrative except explicitly approved copy. Follow the founder's actual functionality specification if visual details conflict (for example per-app protection setup and explicit duration entry, no separate Lockdown, distinct Intent and Focus). Do not treat rendered buttons, toggles, or numbers as functional proof.

## Pixel-oriented design contract

- Aim for the same *perceived* look, not merely the color palette: dark emerald atmosphere, luminous gold horizon arc, mountain/lake visual metaphor, luminous emerald accents, quiet black-green cards, calibrated borders, warm-white typography, strong large headings, generous vertical breathing room, coherent logo/icon and polished bottom navigation.
- Treat the FIVE screens as the visual source of truth. Reproduce their visual geometry at a matching reference viewport, then adapt for other screen sizes, font scales, and accessibility.
- Do **not** use a full reference screenshot as a view background; the screenshots contain baked-in labels and controls. Instead create a clean art-only backdrop as a separate reusable asset, and implement text/charts/buttons/cards/timers natively in Jetpack Compose.
- The screenshots use illustrative example data. Always bind actual user data; show honest empty/unavailable states.
- Preserve exact founder-approved copy in the app for the following: Today headline `Make room for what matters.`; Today metric `Time away from protected apps`; Protect headline `Never get distracted from what matters.`; Flexible `Blocked until your timer ends, or you turn it off.`; Friction `Complete a challenge before getting access.`; Cooldown `Wait before you can open this app.`; Always Block `No in-app early exit. Protection ends when your timer does.`; confirmation `I'm ready to commit`.
- Keep Shield protection modes: Flexible full block cancelable in-app; Friction task to obtain timed access; Cooldown wait before timed access; Always Block no in-app early cancellation. Intent separate, with optional reminder or post-use cooldown. Focus task-based. Android system recovery not obstructed.
- Do not claim pixel-perfect identity without visual screenshot comparisons. Report measured differences and final preview images.

## QA procedure

1. Establish reference device viewport and UI size; note that images have a tall 9:16-ish aspect ratio and may not reflect exact Android density/insets.
2. Capture app screenshots at the same size, theme and data state as these images.
3. Compare side by side: hero artwork, title baselines, spacing, alignment, proportion, colors, font, card positions, graph layout, and icon treatments.
4. Iterate and capture again. Then test smaller phones, larger fonts, light mode, reduced motion and long strings.
5. Separate *visual matching* from *functional testing*. Report both honestly.
