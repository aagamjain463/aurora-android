# aurora product reinvention

The current Home/Shield/Intent/Track model, including Habits, is described in
[CONFIGURATIONS.md](CONFIGURATIONS.md). It supersedes this earlier
`feat/aurora-product-reinvention` navigation and activation flow.
The previous product and validation records remain in Git history. Brand details are in
[BRAND_SYSTEM.md](BRAND_SYSTEM.md), enforcement in [SHIELD.md](SHIELD.md), and copy review in
[AURORA_COPY_REVIEW.md](AURORA_COPY_REVIEW.md).

## Protection model

Adding an app saves its preferences. It does not start protection. Protection comes from a timed
session, a schedule, Focus, a spent daily limit, or Intentional App Opening. Start protection asks
for apps, mode, settings where needed, duration and confirmation. Custom durations are supported.

| Mode | While active | Access / early end |
| --- | --- | --- |
| Flexible | Fully blocked | Turn off in aurora; no continue button on Shield |
| Friction | A task before access | Math, written intention with read-back, or reflection; then chosen usage time |
| Cooldown | A wait before access | A genuine reopening restarts the persisted wait; then chosen usage time |
| Always Block | Fully blocked | No in-app early end; timer ends automatically |

Always Block has no escape through edits, removing an app, deleting data or downgrading. A running
session captures its app keys and settings. It survives process recreation and reboot through stored
state, though enforcement requires a bound accessibility service. Android still lets the person
revoke permission, force-stop or uninstall aurora. Always Block does not claim device-owner control.

Friction math has three difficulties and rejects an incorrect answer. Written intention requires
at least two words and a confirmation. Reflection asks two questions and includes a pause. The wait
in Shield Cooldown is separate from the post-use cooldown in Intentional App Opening.

## Rule precedence

Invalid events are ignored. Unknown/unreadable configuration is reported as unavailable and shields
nothing. Recovery surfaces (Settings, launcher, keyboard, installers, phone/emergency and aurora)
are exempt. Otherwise `decide()` selects the strongest current restriction:

1. Always Block
2. Flexible
3. Focus
4. Spent daily limit
5. Cooldown
6. Friction
7. Intentional App Opening cooldown
8. Intentional App Opening

A grant lifts its own level and weaker levels only. A Friction grant cannot lift Cooldown, Focus,
Flexible, Always Block or a spent limit. The plan determines active items before the decision;
`decide()` does not read the plan. A timer rechecks the app already open at the next relevant change.

## Intentional App Opening

This is a separate feature, not a fifth mode. For each enabled app the person can set a reflection
pause, reason, alternative, default usage duration, expiry action and whether each opening starts
fresh. Stronger protection takes precedence. The flow is pause, decide, choose time, use, follow up.
Return home is always available from the prompt.

The chosen time counts foreground use, not elapsed time while away. Window events and screen-off
tracking pause the timer. Usage access lets Android's usage intervals correct the count; without it,
the UI identifies the count as an estimate. The query seeds the foreground app from Android's retained events before the session start and clips intervals to the chosen window; an accessibility overlay does not pause the underlying activity. Expiry while the app stays open shows either a Reminder
with another decision or a post-use cooldown. Once cooldown ends, a fresh decision is required.
Free supports three enabled apps; Plus has no Intent app limit.

## Schedules, Focus and limits

The schedule wizard asks for name, days, start/end, apps/groups, mode, optional mode settings and a
plain-language summary. A window whose end is at or before its start runs overnight. Windows use
local time and handle DST. The strongest overlapping mode wins. Flexible windows can be skipped.
A running Always Block window is committed to a session before edits, disabling or deletion.

Free has 120 Always Block minutes per start day, shared by sessions and committed schedule windows.
Overlapping reserved time is not charged twice. On Free, a scheduled Always Block window commits
only the available allowance; the remaining window behaves as Flexible. This fallback is disclosed
in the schedule wizard and was approved by the founder on 29 October 2026. Plus commits the full
window. Existing commitments survive downgrade.

Focus fully blocks its apps/group for the selected duration. Ending early records that outcome.
Daily limits use measured foreground usage, require Usage access and reset at local midnight.

## Today and progress

The approved headline is “Make room for what matters.” The main number is “Time away from protected
apps”. It is the union of today's protection spans, minus intervals when an app covered by each
span was in front. Overlaps count once. Access granted during protection still counts as use.
Screen-off time counts as away. The UI explains this and does not call it time saved or productive
time. Missing Usage access is shown honestly rather than inventing measured use.

Protection status and progress are separate pillars. Aurora Light reflects XP/history, not the
away metric. Insights excludes aurora and launcher time from screen time. Android's retained usage
history limits the period available; missing data is shown as unavailable.

## Data and migration

DataStore holds protected items, rules, runtime, history, preferences and cached entitlement. Data
stays in app-private storage; cloud backup is disabled. Unknown JSON fields are ignored, but malformed
configuration is not silently replaced. The foreground event path reads an in-memory snapshot.

Legacy `Block` and `RulesOnly` decode as Flexible preferences. Legacy Lockdown runtime migrates to
an Always Block session. Existing Intent notes retain their reason, alternative and pause. Migration
fixtures cover the founder's prior preferences. Clear history resets progress; deleting all data
keeps a running Always Block commitment until its end. Google Play owns purchases independently.

## Platform and commercial limits

Shield uses a service-owned accessibility overlay. The optional Web & content Shield reads address
bars in Chrome/Chrome Beta, Samsung Internet, Firefox, Edge, Brave, Opera and DuckDuckGo. Other
browsers and inaccessible address bars are not covered. Shorts/Reels exit remains experimental;
current real-app versions and signed-in flows have not been validated.

Free: five protected apps/sites, three Intent apps, three daily limits, one schedule, one Focus
profile, 120 Always Block minutes per day and Today/7-day Insights. Plus removes these limits and
adds 30-day Insights. Paused items remain saved after downgrade; restoring them is explicit.

Play Billing uses the configured subscription product and Play-formatted prices, acknowledges
purchases and restores by querying Play. Pending purchases do not grant Plus. Cached Plus lasts up
to seven days offline. Play Console products, license testers, purchase lifecycle checks, signing,
server verification and accessibility policy approval remain launch requirements. Release APKs here
are unsigned. The manifest removes INTERNET/ACCESS_NETWORK_STATE from Billing diagnostics; purchase
flows with those removals need license-tester validation.

## Validation and launch gates

See [REINVENTION_VALIDATION.md](REINVENTION_VALIDATION.md) for exact evidence and outstanding work.
Emulator success is not physical-device or OEM reliability proof. In particular, Xiaomi may kill the
accessibility process and leave an enabled service unbound. The interface reports that state and
provides Autostart/battery guidance. Real-phone blocking, reboot recovery, latency, TalkBack,
notifications/deep links, split-screen and PiP remain separate checks.
