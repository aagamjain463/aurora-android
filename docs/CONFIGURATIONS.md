# Configurations and Habits

The Home, Shield, Intent and Track architecture on `feat/aurora-configurations-habits`
supersedes the earlier three-tab navigation. Enforcement still uses the existing
accessibility service and policy engine. This document describes implemented behavior;
external launch gates remain in [REINVENTION_VALIDATION.md](REINVENTION_VALIDATION.md).

## Four tabs

- **Home:** choose Normal Use or a saved Custom Configuration, start or schedule it,
  see active runs and upcoming activations, and report finished Focus tasks.
- **Shield:** create, edit, duplicate and delete restriction presets. Each preset
  contains app/site keys, one mode, and that mode's settings.
- **Intent:** create, edit, duplicate and delete intentional-opening presets. Each
  contains app keys and reflection, usage-time and expiry settings.
- **Track:** Habits, configuration performance, screen time and existing progress.

The builders reuse First Light components and tokens. Settings, configuration,
preset, Habit, Focus and capability screens are pushed from these tabs.

## Model and persistence

`protect/Configurations.kt` defines presets, usage configurations, activations and
runs. `ConfigActions.kt` owns persistence and transitions.

Saving a preset, configuration or Habit starts nothing. A configuration combines
at most one Shield, one Intent and optional Focus. Custom Configurations reference
saved presets. Normal Use copies built-in defaults for that use; editing the copy
does not change those defaults. A preset referenced by a configuration cannot be
deleted until those references are removed.

An activation supplies timing:

- **Now:** immediately creates a timed run using the monotonic session clock.
- **Later:** one absolute start instant and a duration; travel does not move it.
- **Recurring:** local weekdays, start time and duration, with an optional inclusive
  last start date. Overnight windows and daylight-saving changes are supported.
  A newly created schedule starts with its next window, not an already-started one.
- **Always:** starts when enabled and continues until turned off. It is unavailable
  with Always Block, so it cannot create an indefinite commitment.

Policy derives eligible occurrences from stored activations at each evaluation.
`syncRuns()` materializes active occurrences, freezes preset copies and records
finished runs. Home, app startup and the service call it; no exact alarm is used.
Protection requires the accessibility service to be connected. Saving a schedule
does not ensure Android keeps that service alive.

Edits affect future occurrences. A run already active retains its frozen settings.
Deleting a configuration or schedule does not terminate its active commitment.
Turning off a recurring run skips that occurrence; turning off Always disables
its activation. Re-enabling Always creates a new occurrence from the new timestamp.

## Always Block and Free limits

Always Block refuses in-app cancellation while committed. A weaker overlapping
run or grant cannot lift it. Free has 120 committed minutes per local start day;
overlapping reservations count once. A scheduled window exceeding the remaining
allowance becomes Flexible after the committed portion. Plus commits the full
window. Existing commitments survive a downgrade.

Free activations are selected oldest first within five protected app/site keys,
three Intent app keys and one recurring schedule. Shared keys count once.
Excess activations stay saved and paused. Habits have no plan limit.

The capability screen explains consumer Android limits. Aurora cannot prevent
permission revocation, force-stop, uninstall or OS-level clock changes. Device
owner/admin controls are not implemented. A commitment is not a claim of tamperproof
device management.

## Cooldowns

Shield Cooldown waits and Intent post-use cooldowns use separate persisted state
in `protect/Cooldowns.kt`. A genuine new app-opening attempt restarts the full wait;
duplicate window events, recomposition and service rechecks do not. A completed
Shield wait has a five-minute grace period for choosing usage time.

An Intent session whose chosen time is spent settles into its stored post-use
cooldown before it can be replaced. Leaving the app, requesting a fresh opening
or holding a weaker access grant cannot bypass that cooldown.

## Success

`progress/Runs.kt` records evidence for each finished configuration run. Service
heartbeat spans establish verified protection time; overlapping spans count once.
Protection succeeds at 90% coverage, is partial at 50%, and is unverified below
50%. Turning a timed run off early does not count as success. Intent needs at least
50% service coverage; continuing past a reminder makes it partial. Focus depends
on the person's report, not the timer reaching zero. The combined result uses the
weakest part; absent evidence never creates a success.

## Habits

A Habit links one saved Custom Configuration to a target and a success criterion.
Creating, archiving or restoring it does not activate protection. Deleting its
configuration archives the linked Habit after explicit confirmation; run history
stays. Only archived Habits can be deleted.

Targets are specific weekdays, successful runs per week, or on demand. A Habit can
follow a configuration's schedule days; mismatched or unscheduled days are disclosed.
Today remains pending until successful. Rest days never break a streak. On-demand
Habits have no expected days or misses. Calendar and trend data come from actual
run records, with no sample statistics.

`progress/Habits.kt` derives progress from linked runs starting at or after Habit
creation and no later than the day being evaluated. Target edits are versioned from
today. Earlier daily and weekly results remain in totals; on-demand periods do not
create retroactive misses. Changing between days, weeks and on-demand starts a new
streak in the new unit. When target types are mixed, each recorded on-demand run
counts as an occurrence in the completion denominator; on-demand periods create
no missed days. The current on-demand target has no completion denominator.
For a weekly target, the last effective weekly target in
that calendar week supplies its required count.

## Migration

`protect/ConfigMigration.kt` migrates version 0 rules once. Saved app preferences
and groups become saved presets without starting protection. Schedules retain their
IDs, days, timing and enabled state as recurring activations. Enabled Intent apps
become Always activations starting at migration time; disabled apps remain saved.
Legacy app settings remain stored. Runtime sessions and commitments are retained.
An early-build Always run with a missing start is repaired from the current time
instead of appearing to have run since 1970.

## Verification

Domain tests cover timing, precedence, entitlement, migration, cooldown transitions,
evidence and Habit progress. Shared navigation tests run under Robolectric and on
an emulator, including configuration activation and Habit creation/archive/restore.
Roborazzi covers the builders, Home, libraries, Habit detail and Track in light,
dark, 200% text and reduced motion. Emulator tests modify test app state; never run
them on a personal phone. Real-device and Play launch gates remain separate.
