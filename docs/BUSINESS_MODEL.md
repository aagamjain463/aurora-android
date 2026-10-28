# aurora business model

This document defines what aurora Free and aurora plus include, how limits are counted, and how
the entitlement system enforces them. [`ROADMAP.md`](ROADMAP.md) says when each piece is built.
[`BRAND.md`](BRAND.md) governs how every limit and upgrade moment looks and sounds.

- **Status:** initial business model, agreed 2026-10-28. Nothing in this document is implemented.
- **Billing:** real Google Play Billing and subscriptions arrive in Roadmap Phase 11. Until then,
  every person is on aurora Free, and limits are enforced as each feature ships.
- **Naming:** the brand is always lowercase in product copy and store listings: "aurora Free" and
  "aurora plus".

---

## 1. Principles

1. **Same protection strength on both plans.** A shield in Free is exactly as strong as a shield
   in plus. Lockdown in Free is the same Lockdown as in plus. aurora plus sells capacity and depth
   (more items, more rules, more history), never a stronger or more reliable block.
2. **Limits apply when you set things up, not when protection runs.** The protection engine
   (`decide()` in the roadmap) never reads the plan. Limits are checked when the person adds an
   item, a rule, a schedule or a profile, and when a Lockdown starts. This keeps principle 1 true
   by construction.
3. **Protection never gets weaker by surprise.** A running Focus session or Lockdown always finishes
   as promised, whatever happens to the subscription.
4. **Turning protection off is always free.** Removing items, pausing protection and deleting data
   never need plus.
5. **Nothing is deleted on downgrade.** Items and rules beyond the Free limits pause. They come back
   on renewal.
6. **Honest selling.** aurora never advertises a feature that doesn't exist yet. The real price and
   renewal terms are always shown, with no urgency tricks. There are no upsells during Focus, a
   shield moment or Lockdown.

## 2. Plans

### 2.1 aurora Free

| Allowance | Free limit |
| --- | --- |
| Protected items (apps and websites combined) | 5 |
| Lockdown | 120 cumulative minutes per local calendar day |
| Basic Focus sessions | Unlimited |
| Protection modes | Flexible, Friction and Cooldown |
| Intentional opening (Intent) | 3 apps |
| Daily app-limit rules | 3 |
| Recurring schedules | 1 |
| Focus profiles | 1 |
| Insights | Basic insights, goals and streaks |

### 2.2 aurora plus

| Allowance | plus |
| --- | --- |
| Protected items (apps and websites) | Unlimited |
| Lockdown | Unlimited |
| Intentional opening (Intent) | Unlimited apps |
| Daily app-limit rules | Unlimited |
| Recurring schedules | Unlimited |
| Focus profiles | Unlimited |
| Insights | Advanced insights and challenges |
| Cloud sync | Planned for the future. Not sold until it exists (section 6). |

Everything in Free is also in plus.

### 2.3 Pricing

| Market | Monthly | Yearly | Yearly saving |
| --- | --- | --- | --- |
| India | ₹149 | ₹1,199 | About 33% against 12 × ₹149 (₹1,788) |
| Other markets | To be decided | To be decided | — |

To be decided before Phase 11:

- Whether there is a free trial, and its length.
- Prices for other markets (Play price templates or manual prices).
- Whether ₹149 and ₹1,199 are the prices the buyer sees, including tax. This plan assumes they
  are (gate G13 in the roadmap).

**Illustrative net revenue per subscriber in India.** This is unverified. It assumes the listed
price includes 18% GST and that Play's service fee on subscriptions is 15%. Confirm both under G13.

| Plan | Listed price | Without GST | After a 15% fee | Per month |
| --- | --- | --- | --- | --- |
| Monthly | ₹149 | ₹126.27 | ₹107.33 | ₹107.33 |
| Yearly | ₹1,199 | ₹1,016.10 | ₹863.69 | ₹71.97 |

## 3. Definitions

The plan names Lockdown, the protection modes, Focus profiles, goals, streaks and challenges.
These terms were not defined anywhere in the repository before. The definitions below are the
working definitions for the roadmap. Questions that are still open are in section 8.

### 3.1 Protected items

A **protected item** is an app (from 1.0) or a website (from 1.1) that aurora watches. Each item
has an everyday **protection mode**. Free allows 5 items in total, so apps and websites share one
pool. In 1.0 all 5 items are apps.

### 3.2 Protection modes

| Mode | What happens when the person opens the item | Plan |
| --- | --- | --- |
| **Flexible** | The shield appears with "Continue" available at once. It is a moment of awareness. | Free and plus |
| **Friction** | The shield appears and "Continue" becomes available after a short wait: one breath, or a wait the person chooses. | Free and plus |
| **Cooldown** | Each opening allows a set time. Then the app closes and stays shielded for a cooldown period. Opening it during the cooldown needs a Friction wait (Q9). | Free and plus |
| **Intent** (intentional opening) | A breath, a question about why, then an opening for a chosen number of minutes. | Free: 3 apps. plus: unlimited. |
| **Only during focus** | No everyday shield. The item is shielded only during Focus sessions, schedules and Lockdown. | Free and plus |

Intent applies to apps only. Websites use Flexible, Friction, Cooldown or "Only during focus".
Intent apps are protected items, so they count toward the 5-item limit as well.

### 3.3 Focus sessions and Focus profiles

- A **basic Focus session** is a timed session. During it, every item in the session's profile is
  shielded, and "Continue" sits behind a Friction wait. Free allows unlimited basic sessions.
- A **Focus profile** is a saved session setup: a name, a default length, which protected items it
  covers, and whether it uses Lockdown. Free has 1 profile (the default one, covering all
  protected items). plus allows unlimited profiles.

### 3.4 Lockdown

**Lockdown** is the strongest state aurora offers, and the only one with no in-app way through.
While it is active, the items in its scope have no "Continue", no "Open anyway", and no way to end
it early inside aurora. Every other state, on both plans, leaves a way through behind a breath or
a wait. It replaces the term "strict mode" used in earlier drafts of the roadmap.

- It can start with a Focus session (when the profile uses Lockdown), with a schedule, or by itself
  ("Lock now").
- **Metering on Free:** 120 cumulative minutes per local calendar day.
  - The planned length is reserved from the budget of the day the Lockdown starts. A Lockdown that
    crosses midnight finishes as promised, and its minutes all count toward the starting day.
  - The person can't start a Lockdown longer than the remaining budget. The length picker shows
    the minutes left: "75 minutes of Lockdown left today".
  - If a Lockdown ends early for a reason outside aurora's control (the accessibility service is
    turned off, for example), the unused minutes are returned to the day's budget.
  - A scheduled Lockdown that is longer than the remaining budget runs Lockdown for the minutes
    left and then continues as a basic Focus session. aurora says so when the schedule starts:
    "Lockdown for 40 minutes, then Friction until 17:00."
  - The budget resets at local midnight, in the device's current time zone.
- **What Lockdown can't stop**, on both plans: uninstalling aurora, turning off its accessibility
  service in Android Settings, safe mode and `adb`. aurora says this plainly wherever Lockdown is
  offered (section 7).

### 3.5 Daily app-limit rules

A **daily app-limit rule** is a daily minute budget for one app, or for a group of apps that share
one budget. When the budget runs out, the apps are shielded in Friction until local midnight.
Free allows 3 rules. Apps in a limit rule don't need to be protected items and don't use the
5-item pool (open question Q2).

### 3.6 Recurring schedules

A **recurring schedule** starts a Focus profile automatically on chosen days and times. Free allows
1 schedule. A schedule that uses Lockdown draws from the day's Lockdown budget.

### 3.7 Insights, goals, streaks and challenges

| Feature | Definition | Plan |
| --- | --- | --- |
| **Basic insights** | Today and the last 7 days: screen time, most-used apps, Focus minutes, how often the person chose not to open an item. | Free |
| **Goals** | A personal target, for example "Focus 3 times a week" or "Under 2 hours on social apps a day". | Free |
| **Streaks** | Consecutive days with a goal met, in the gentle form described below. | Free |
| **Advanced insights** | Longer history (30 and 90 days, then more), trends for each app, time-of-day patterns, and comparisons with the person's own past weeks. | plus |
| **Challenges** | Guided programs that run for several days, such as "A week of calm mornings", built from schedules, goals and Lockdown. | plus |

**Gentle streaks.** `BRAND.md` rules out streak anxiety, so streaks follow these rules:

- A missed day ends the current run but never erases it. The best run and the total number of days
  are kept and shown.
- One rest day each week doesn't break a run.
- Copy never says "lost", "broken" or "failed". The streak simply starts counting again.
- There are no notifications about streaks, and nothing nudges the person to protect a streak.

Advanced insights beyond 7 days need aurora to store daily totals per app on the phone, because
Android doesn't keep detailed usage history that long (gate G7). This changes the current promise
that "screen time is computed, never stored" (decision D14 in the roadmap). The data would still
never leave the phone.

## 4. How limits are counted

| Allowance | What counts | What frees a slot |
| --- | --- | --- |
| Protected items | Every app or website whose mode isn't Off | Removing an item, or uninstalling the app (aurora prunes uninstalled apps) |
| Intent apps | Protected apps in Intent mode | Switching the app to another mode |
| Daily app-limit rules | Each rule, whatever number of apps it covers | Deleting the rule |
| Recurring schedules | Each schedule, enabled or not | Deleting the schedule |
| Focus profiles | Each saved profile, including the default | Deleting a profile (the default profile can't be deleted) |
| Lockdown minutes | Planned minutes of each Lockdown, on the day it starts | Midnight. Early ends outside aurora's control are refunded. |

At a limit, aurora explains it calmly and offers a way forward that doesn't involve paying:

> You're protecting 5 items, the most aurora Free allows. Remove one to add another.

From Phase 11, the same message adds a Quiet link to aurora plus. Before Phase 11, aurora never
mentions plus, because it can't be bought yet.

## 5. Entitlement system

### 5.1 Shape

One small, pure module that every feature asks. It has no Android dependencies, so it is fully
unit tested.

```kotlin
// app/aurora/plan/Plan.kt (Roadmap Phase 2)
enum class Plan { Free, Plus }

enum class Allowance { ProtectedItems, IntentApps, DailyLimitRules, Schedules, FocusProfiles, LockdownMinutesPerDay }

enum class Feature { AdvancedInsights, Challenges }

/** null means unlimited. */
val FreeLimits: Map<Allowance, Int?> = mapOf(
    Allowance.ProtectedItems to 5,
    Allowance.IntentApps to 3,
    Allowance.DailyLimitRules to 3,
    Allowance.Schedules to 1,
    Allowance.FocusProfiles to 1,
    Allowance.LockdownMinutesPerDay to 120,
)

fun limit(plan: Plan, allowance: Allowance): Int? = if (plan == Plan.Plus) null else FreeLimits.getValue(allowance)
fun canAdd(plan: Plan, allowance: Allowance, current: Int): Boolean = limit(plan, allowance)?.let { current < it } ?: true
fun has(plan: Plan, feature: Feature): Boolean = plan == Plan.Plus
```

This sketch shows the intent. Phase 2 writes the real file, and it may differ in detail.

### 5.2 Where the plan comes from

| Period | Source of `Plan` |
| --- | --- |
| Phases 2–10, release builds | Always `Free`. |
| Phases 2–10, debug builds | `Free` by default. A debug-only Settings row switches to `Plus`, so plus behaviour can be tested before billing exists. |
| Phase 11 onward | The Play Billing entitlement, cached on the phone (Roadmap Phase 11). The debug switch stays in debug builds only. |

### 5.3 Rules for features

- Each feature phase checks its own allowance in the ViewModel action that adds or enables
  something. For example, `ProtectViewModel` asks `canAdd(plan, ProtectedItems, count)` before
  protecting a 6th item.
- Lockdown checks the day's remaining minutes when it starts. A small ledger in DataStore holds
  the current day and the minutes used.
- `decide()` and the accessibility service never read the plan. A test enforces this: a policy test
  runs the same cases under `Free` and `Plus` and expects identical decisions.
- All limit numbers live in `FreeLimits` and nowhere else.

### 5.4 Downgrade

When a plus subscription ends (from Phase 11):

1. Running Focus sessions and Lockdowns finish as promised.
2. Items, Intent apps, rules, schedules and profiles beyond the Free limits **pause**. The person
   chooses which ones stay active. Until they choose, the oldest stay active.
3. Lockdown goes back to 120 minutes a day from the next Lockdown started.
4. Advanced insights and challenges are hidden. Their data is kept, and a challenge in progress
   pauses.
5. Renewing restores everything as it was.

## 6. Cloud sync (future)

Cloud sync is listed under aurora plus as a future feature. It conflicts with aurora's current
foundations: no account, no backend and no `INTERNET` permission. Before it can be built, it needs
its own decision on:

- the account model and sign-in;
- the backend and its cost;
- end-to-end encryption of behavioural data;
- the `INTERNET` permission and every privacy claim in the app, the README and Data safety.

Until cloud sync exists, the paywall, the store listing and in-app copy don't mention it. Selling a
feature that doesn't exist breaks principle 6 and risks Play policy on misleading claims. Cloud
sync is not part of the 13-phase roadmap.

## 7. Android limitations

These limits apply equally to both plans. aurora states them in the product wherever they matter.

| Limitation | Effect | How aurora handles it |
| --- | --- | --- |
| Shield depends on an accessibility service. | The person can turn it off in Android Settings at any time. Some OEMs and Android's Advanced Protection mode may disable it. | `ProtectionStatus` shows `NeedsPermission`. Nothing claims protection while it's off. |
| Lockdown can't defeat the system. | Uninstalling aurora, turning off the service, safe mode and `adb` all end protection. | Said plainly wherever Lockdown is offered. aurora never implies otherwise. |
| Daily budgets use the device clock. | Changing the date or time can reset Lockdown minutes and app limits. | Accepted for 1.0. There's no server to check time against. |
| The entitlement is checked on the phone. | A modified app can unlock plus. | Accepted, because there is no backend. Play Billing's state is trusted. |
| Website filtering covers supported browsers only (1.1). | In-app browsers, custom tabs and WebViews aren't covered. | A "Works in" list on the Websites screen. |
| Daily limits need Usage access. | Without it, limits can't measure time. | The limits screen explains this and offers the permission. |
| Android keeps limited usage history. | Basic insights cover 7 days at most from Android's own records. | Advanced insights store daily totals on the phone (D14). |
| Notifications can be delayed. | Doze may delay the end-of-session alert. | The completion moment is always correct when aurora opens (gate G6). |

## 8. Open questions

Each question has a proposed answer that the roadmap uses until it is confirmed.

| ID | Question | Proposed answer |
| --- | --- | --- |
| Q1 | Do the mode definitions in 3.2 (Flexible, Friction, Cooldown) match the intended product? | As written |
| Q2 | Do apps in a daily limit rule count toward the 5 protected items? | No |
| Q3 | When a daily limit runs out, is the app shielded with Friction, or blocked until midnight? | Friction. Lockdown stays the only state with no way through, and it is metered. |
| Q4 | Is Lockdown reserved at start (as in 3.4), or charged minute by minute? | Reserved at start, refunded on early ends outside aurora's control |
| Q5 | Is there a free trial for aurora plus, and how long? | 7 days on the yearly plan |
| Q6 | Prices outside India | Decide before Phase 11 |
| Q7 | Do Focus profiles cover only protected items, or any app? | Protected items only |
| Q8 | Are gentle streaks (3.7) acceptable as the "streaks" in Free? | Yes |
| Q9 | Can a Cooldown be skipped? | Yes, behind a Friction wait, except during Lockdown |
