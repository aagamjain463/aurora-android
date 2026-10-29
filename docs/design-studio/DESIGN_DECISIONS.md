# Design decisions

Newest last. Each entry: decision, then why.

1. **Keep the First Light ring-and-point symbol from the references; don't invent a new logo.** It is
   already in the approved screens, survives 16dp and one colour, and is built from the art's own ring.
2. **Title-case "Aurora" everywhere**, in the wordmark and in all copy (71 strings, plus the Kotlin literals
   for Billing, limits and Shield). The references use it; lowercase read as a placeholder.
3. **Night art for dark mode is the ImageGen cinematic image, not the procedural render.** Compared side
   by side, the procedural night render is flatter than the references; the cinematic one matches them.
   It was re-encoded to a 109 KB WebP (from 2 MB).
4. **Light mode gets its own morning art (procedural `first_light_day`)**, instead of forcing a night
   hero onto a light theme. Same composition, same scrims, theme-correct status bar.
5. **Three art tiers; configuration screens get none.** This is the brief's "selective artwork" rule.
   Balanced crops higher, so the headline sits on the lake.
6. **A reading-side shade on the art** (dark at the left, clear at the right). Type stays legible over
   mountains and the ring stays bright, like the references.
7. **Display type at 500 weight (36sp), Metric at 56sp/500.** The references' headlines are light-medium;
   700 was the main "generic" tell.
8. **One container surface, `auroraSurface`**, replacing hand-rolled cards across Today, Protect, Intent,
   Start, Rules and RowGroup. A family resemblance across 30 screens comes from one modifier.
9. **The primary CTA uses the First Light gradient in both themes, with a glow in dark.** In light mode
   the gradient stays dark enough for white text at AA.
10. **Settings sits where the references have a profile button.** Aurora has no accounts; a profile icon
    would promise one.
11. **Protect shows each app's mode as a chip, not a per-app switch.** The references' toggles imply a
    per-app on/off that doesn't exist. Protection runs as a session or schedule.
12. **Protect's "Protection modes" card is an explainer, not a selector.** No check mark, no selection
    state: modes are chosen per app at Start. It shows the founder's exact descriptions.
13. **The Shield status card appears on Protect only when Shield needs attention.** A healthy Shield
    doesn't need a card.
14. **Intent keeps the reflection step separate from choosing a time.** The reference shows both at once,
    but the pause is the product. The new `PauseRing` and the reason and better-choice cards carry the
    reference's look.
15. **Focus has no Pause button.** The reference shows one, but it is not a feature. End session is a
    coral Danger button, so ending early never looks like the primary action.
16. **The Focus timer is a full ring with First Light inside (`LightRing`)**, replacing the half-arc. It
    matches the reference and reuses the art as the timer's face.
17. **Today's week shows recorded progress only** (dots for days with XP, focus sessions and minutes this
    week). Per-day time-away bars like the reference's need per-day protection history that isn't
    stored, so they are not drawn. No numbers are invented.
18. **Insights periods are "Today / 7 days / 30 days"** in a segmented track. "Today and this week"
    wrapped onto two lines and was ambiguous.
19. **No Figma file.** No connector was available. Browser-driving Figma needs sign-in plus an upload of
    private work (approval-gated), and it would duplicate the code system unverifiably. SVG masters, the
    token JSON and the Compose components are the editable sources.
20. **`DesignTokensTest`** keeps the JSON tokens and the Kotlin palette equal, so the brand file can't
    silently drift from the app.
