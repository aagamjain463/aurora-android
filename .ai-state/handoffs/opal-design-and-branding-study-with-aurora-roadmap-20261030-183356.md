# Handoff: Opal design and branding study with Aurora roadmap

- Task ID: opal-design-and-branding-study-with-aurora-roadmap-20261030-183356
- Task name: Opal design and branding study with Aurora roadmap
- Current agent: Claude Code
- Status: in progress
- Last updated: 2026-10-30 18:33:56

## Objective
Study Opal's visible branding, product design and animation on the connected Android device, then write a roadmap for Aurora

## Completed work
- (none yet)

## Current work
- (starting)

## Remaining work
- (to be determined)

## Technical decisions
- (none yet)

## Files modified
- (none yet)

## Tests and results
- (none yet)

## Errors or blockers
- none

## Exact next steps
1. (to be determined)

<!-- Observable progress between checkpoints: `ai-handoff recorded opal-design-and-branding-study-with-aurora-roadmap-20261030-183356.md` -->

## Checkpoint 2026-10-30
- Task ID: opal-design-and-branding-study-with-aurora-roadmap-20261030-183356
- Current agent: Claude Code
- Status: completed
- Objective: study Opal's visible branding, product design and animation on the device; write a roadmap for Aurora.
- Completed work: observed Opal 4.17.0 on the USB phone via screenshots, recordings and UI dumps; wrote docs/design-studio/10-opal-study-and-roadmap.md.
- Current work: none.
- Remaining work: founder decisions in section 6 of the roadmap; block screen, paywall and session states not observed.
- Technical decisions: no APK pull, decode, decompile or Frida, because no authorization to analyze Opal was stated (global REA rule 9). Roadmap adopts technique, not Opal's identity.
- Files modified: docs/design-studio/10-opal-study-and-roadmap.md only.
- Tests and results: docs only, none run.
- Errors or blockers: back taps were sent blind for one run while someone was using the phone. Opal accessibility service and overlay permission were enabled on the phone in that period, by hand per the recording, not by the agent's tap coordinates. Captures showing private messages were deleted.
- Exact next steps: founder picks section 6 options, then start Phase 1 in ui/components/Motion.kt.
- Last updated: 2026-10-30
