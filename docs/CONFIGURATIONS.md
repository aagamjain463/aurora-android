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

## Setup flows

Each step is one decision; settings that belong to a choice appear under it on the same step.

| Flow | Steps |
| --- | --- |
| Custom Configuration | (Saved, when some exist) · What: name, Shield, Intent · When: timing and its length · Review: summary, optional Focus, Always Block commitment |
| Normal Session | One screen: how long, mode, apps, Start. Opens on the latest Shield, so a repeat is one tap. Not saved. "More options" opens the full flow: What: Shield, Intent · When · Review. |
| Configuration for a Habit | What · Focus, then save |
| Shield preset | Name · Apps (with "Add websites too") · Websites, only if asked for · Type with its settings · Review |
| Intent preset | Name · Apps · Reflection · Time: usage length and what happens after · Review |

"None" for a Shield or Intent is a small chip beside the heading, offered once something is chosen;
tapping a chosen card again also clears it. Library cards open the editor on tap; Duplicate and Delete
sit behind the overflow control.

Nothing starts without the Accessibility switch: Review, Normal Session and Home's quick starts send the
person to turn Shield on first, because without it a session is only a timer. Plan limits show in the app
list and stop at the limit, not at the end of the flow.

## Model and persistence

`protect/Configurations.kt` defines presets, usage configurations, activations and
runs. `ConfigActions.kt` owns persistence and transitions.

Saving a preset, configuration or Habit starts nothing. A configuration combines
at most one Shield, one Intent and optional Focus. Custom Configurations reference
saved presets. A Normal Session copies the presets it starts with and leaves no
configuration behind for reuse. A preset referenced by a configuration cannot be
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

## Strict protection

Always Block, Cooldown and Friction are strict (`Mode.strict`); only Flexible can
be turned off early. A strict run with an end:

- has no in-app early exit (`ConfigRun.cancellable`, `ProtectionSession.cancellable`);
  its Shield type and apps are frozen when it starts. Always timing is refused for
  strict Shields; Always runs made before this stay cancellable.
- locks Settings and the package installers (`lockedPackages`, `ShieldReason.Locked`)
  until it ends, so the Shield service, permissions, force stop, uninstall screens and
  the date and time can't be reached. Calls and emergency flows stay exempt.
- reads time from `TrustedClock`: monotonic while it runs, with the offset from the
  system clock saved across restarts, so moving the clock never ends a session or
  finishes a wait. With nothing strict running, the clock follows the system again.
- resumes after a restart (`StrictReceiver` on BOOT_COMPLETED; runs are stored).

Uninstall protection is an opt-in device administrator with no policies
(`UninstallGuard`). Android then refuses uninstalling from the launcher, Play Store
or adb until it is deactivated, which needs Settings (locked) or Aurora (only while
nothing strict runs). Safe mode, a factory reset, or adb with USB debugging already
on can still get around an accessibility-based lock.

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

## Goals

A Goal is created in Track and never starts or blocks anything. It either links one
saved custom session or limits apps or the whole screen time.

A session goal is tracked as time a day, time a week, or successful sessions a week.
Time counts only while the Shield service was bound, including a run going now. A
session goal succeeds once its target is reached. A period that ends short has failed,
except the one the goal was created in. Deleting a session removes its goals after
explicit confirmation; run history stays.

A limit goal sets a time per day or per week over foreground use. It fails the moment
the limit is passed and succeeds when the period ends within it. Finished days are
stored in `History.goalUsage` while Android still holds their records; a day it can't
read stays unknown and is never a success. Weeks reset every Monday.

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
