# AURORA — Final Brand Design Handoff

## Brand identity
- Name: Aurora
- Central idea: **Control**
- Positioning: Premium digital self-control
- Audience: People who struggle with digital distractions and want to gain control over them.
- Personality: **Quiet Authority** — powerful, calm, refined, minimal, empowering.
- Values: Autonomy, Reliability, Simplicity, Respect, Craftsmanship.

## Five approved brand messages — exact wording
1. Primary: **Take back control.**
2. Mission: **Empower people to take control of their technology rather than be controlled by it.**
3. Vision / manifesto: **Technology should serve you. Not the other way around.**
4. Promise: **Reclaim your time and own your attention.**
5. Product explanation: **Shield yourself from distracting apps and websites and use them intentionally.**

Do not introduce additional slogans. Functional UI labels are permitted.

## Logo — source of truth
`Aurora_Final_Approved_Logo.png` is the original approved A12-inspired logo asset. Preserve it unchanged, including its subtle champagne-gold detail. Do not redraw or regenerate from the brand board.

**Gold is allowed ONLY inside this approved logo asset, and on Aurora Plus** (founder, 2026-11-08: the Plus card, badge, name and the actions that open Plus, in the `plus-gold-*` tokens). No gold anywhere else in product or brand graphics.

## Palette
- Obsidian `#0B0B0D`
- Graphite `#1B1C20`
- Slate `#34363B`
- Porcelain `#F5F5F2`
- Silver `#A7A9AE`
- Light surfaces `#FFFFFF`
- Light secondary text `#55565A`
- Light borders `#DDDDDA`

Dark default; fully developed light theme. Use semantic design tokens. Typeface: Inter.

## Visual language
Use precise geometric components, intentional negative space, crisp typography, clear hierarchy, subtle neutral surface elevation, and restrained purposeful motion. The brand should feel premium through craftsmanship, not decoration.

**Prohibited:** northern lights, mountains, celestial scenery, glowing horizons, luminous arcs, atmospheric light ribbons, nature photography, and any visuals that imply aurora borealis. No extra gold outside the approved logo and Aurora Plus.

## Product surfaces
Apply consistently across splash, onboarding, Home, Shields, Intentional Use, Focus Sessions, Progress, blocking screens, Settings, subscriptions, website, app-store assets, and marketing.

Prioritize real usability: clear protection status, reliable blocking, understandable modes, accessible contrast, 48dp touch targets, reduced motion, clear permission explanations, and honest limits. Preserve existing functionality and billing/entitlement logic.

## Reference image warning
`Aurora_Brand_Design_Reference.png` is a **conceptual composition only**, not a pixel-perfect implementation spec. Some illuminated curved graphics in it are **explicitly rejected**; remove them. The original approved logo and this written guide take precedence over the board. Never use fictional ratings or unsupported metrics from mockups.

## Implementation handoff
Audit existing Kotlin/Jetpack Compose screens and components; implement reusable theme/tokens first; redesign Home as benchmark; then Shields, Intentional Use, Focus, Progress, blocking, onboarding, settings and paywall; verify on actual emulator screenshots and automated tests before rollout.
