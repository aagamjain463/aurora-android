# Aurora visual review

29 October 2026. The left column contains the saved concept references. The right column contains native Compose content from the current verified dark baselines. Real emulator and Redmi captures, including navigation bars, are linked from [REINVENTION_VALIDATION.md](REINVENTION_VALIDATION.md). Baselines render content directly, so the navigation host's bottom tabs are absent from those images.

| Screen | Saved reference | Current native content |
| --- | --- | --- |
| Today | <img src="aurora-design-reference/01-today.png" width="280" alt="Today reference"> | <img src="../app/src/test/screenshots/today_Dark.png" width="280" alt="Today native dark content"> |
| Protect | <img src="aurora-design-reference/02-protect.png" width="280" alt="Protect reference"> | <img src="../app/src/test/screenshots/protect_Dark.png" width="280" alt="Protect native dark content"> |
| Intentional App Opening | <img src="aurora-design-reference/03-intent.png" width="280" alt="Intentional App Opening reference"> | <img src="../app/src/test/screenshots/shield_intent_Dark.png" width="280" alt="Intentional App Opening native dark content"> |
| Focus setup | <img src="aurora-design-reference/04-focus.png" width="280" alt="Focus setup reference"> | <img src="../app/src/test/screenshots/focus_Dark.png" width="280" alt="Focus setup native dark content"> |
| Insights | <img src="aurora-design-reference/05-insights.png" width="280" alt="Insights reference"> | <img src="../app/src/test/screenshots/insights_Dark.png" width="280" alt="Insights native dark content"> |

## What matches, and what differs

The shared dark emerald atmosphere, mountain/lake first-light art, golden horizon, native large headings, restrained borders, pill actions and separate protection/progress hierarchy now carry across the main surfaces. The art is an original reusable background with no baked-in UI. Light mode has a paper palette; Shield retains its night setting.

These are not pixel-identical reproductions. The reference viewport is shorter than the native 411 × 914 dp test viewport. Reference numbers, app choices and charts are illustrative; the current screens show actual saved state, measured data, empty or unavailable states. Focus setup is shown here rather than the reference's running timer. The intent test fixture uses Photos. The First Light mark follows the established vector geometry instead of replacing the icon system with a raster logo.

Today uses a growing hero, so its exact height follows font scale. It retains the source-of-measurement disclosure and places the decorative image apart from the data-driven progress Light. These are intentional adaptations to real content and accessibility, not a claim that sample content was implemented as measured behaviour.

## Accessibility and rendering checks

All ten content/Shield snapshots pass independent comparison in light, dark, 200% font and reduced motion. Reviewed large text for wrapping and scrolling, including the Shield action area and Settings controls. Native emulator onboarding was captured in light, dark and 200% font. These checks do not replace real TalkBack review; that remains a launch gate.
