# Aurora business model

This document defines what Aurora Free and Aurora Plus include, how limits are counted, and how
the entitlement system enforces them. [`ROADMAP.md`](ROADMAP.md) says when each piece is built.
[`BRAND.md`](BRAND.md) governs how every limit and upgrade moment looks and sounds.

- **Status:** initial business model, agreed 2026-10-28. Nothing in this document is implemented.
- **Billing:** real Google Play Billing and subscriptions arrive in Roadmap Phase 11. Until then,
  every person is on Aurora Free, and limits are enforced as each feature ships.
- **Naming:** the brand is always title case in product copy and store listings: "Aurora Free" and
  "Aurora Plus" (Brand Bible: never lowercase).

---

## 1. Principles

1. **Same protection strength on both plans.** A shield in Free is exactly as strong as a shield
   in Plus. Lockdown in Free is the same Lockdown as in Plus. Aurora Plus sells capacity and depth
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
6. **Honest selling.** Aurora never advertises a feature that doesn't exist yet. The real price and
   renewal terms are always shown, with no urgency tricks. There are no upsells during Focus, a
   shield moment or Lockdown.

## 2. Plans

### 2.1 Aurora Free

| Allowance | Free limit |
| --- | --- |
| Active Shield targets (apps and websites combined, unique) | 5 |
| Active Intent apps (unique) | 3 |
| Cooldown and Always Block Shields | 120 minutes per local calendar day, shared by the account's devices and by the accounts on one phone (ANTI_ABUSE.md) |
| Active Goals | 1 |
| Flexible and Friction Shields | Unlimited |
| Saved Shield and Intent presets | Unlimited, within the active-target limits |
| Recurring schedules | Unlimited |
| Focus sessions and profiles | Unlimited |
| Daily Limit rules | Unlimited |
| Completed, paused and archived Goals | Unlimited |
| Insights | Basic |

### 2.2 Aurora Plus

| Allowance | plus |
| --- | --- |
| Active Shield targets | No commercial limit |
| Active Intent apps | No commercial limit |
| Cooldown and Always Block Shields | No daily quota |
| Active Goals | Unlimited |
| Everything unlimited on Free | Unlimited |
| Insights | Advanced, with 30-day history |
| Cloud sync | Planned for the future. Not sold until it exists (section 6). |

Unlimited means no subscription quota, not unlimited device resources or guaranteed OS-level enforcement. Monthly
and annual unlock the same things. There is no lifetime plan.

Everything in Free is also in Plus.

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

A **protected item** is an app (from 1.0) or a website (from 1.1) that Aurora watches. Each item
has an everyday **protection mode**. Free allows 5 items in total, so apps and websites share one
pool. In 1.0 all 5 items are apps.

### 3.2 Protection modes

| Mode | What happens when the person opens the item | Plan |
| --- | --- | --- |
| **Flexible** | The shield appears with "Continue" available at once. It is a moment of awareness. | Free and Plus |
| **Friction** | The shield appears and "Continue" becomes available after a short wait: one breath, or a wait the person chooses. | Free and Plus |
| **Cooldown** | Each opening allows a set time. Then the app closes and stays shielded for a cooldown period. Opening it during the cooldown needs a Friction wait (Q9). | Free and Plus |
| **Intent** (intentional opening) | A breath, a question about why, then an opening for a chosen number of minutes. | Free: 3 apps. plus: unlimited. |
| **Only during focus** | No everyday shield. The item is shielded only during Focus sessions, schedules and Lockdown. | Free and Plus |

Intent applies to apps only. Websites use Flexible, Friction, Cooldown or "Only during focus".
Intent apps are protected items, so they count toward the 5-item limit as well.

### 3.3 Focus sessions and Focus profiles

- A **basic Focus session** is a timed session. During it, every item in the session's profile is
  shielded, and "Continue" sits behind a Friction wait. Free allows unlimited basic sessions.
- A **Focus profile** is a saved session setup: a name, a default length, which protected items it
  covers, and whether it uses Lockdown. Free has 1 profile (the default one, covering all
  protected items). plus allows unlimited profiles.

### 3.4 Committed protection

**Cooldown** and **Always Block** (the strongest state, once called Lockdown) share 120 minutes per local
calendar day on Free (`plan/CommittedQuota.kt`). They are not separate allowances.

- **What counts** is the union of committed time, on an interval ledger. Cooldown 10:00 to 11:00 and Always
  Block 10:30 to 11:30 cost 90 minutes. A session wholly inside time already covered costs nothing.
- **Before anything starts:** work out the interval and what it adds, refuse it if that passes the allowance,
  otherwise reserve the time and store the commitment in one transaction, and then enforce. A person with 25
  minutes left can't start a separate 40-minute session; Aurora offers the 25.
- **Across midnight:** each date is charged its own part, and each part must fit its own date.
- **Schedules** are unlimited but aren't a promise of committed time. Each run checks the allowance when it
  comes due. If it doesn't fit, it doesn't start, Aurora says so once, and nothing softer replaces it. One window
  that could never fit a day is refused when the schedule is made.
- **A start can't be undone by the plan.** Expiry, a billing failure or a refresh never weakens a commitment
  already running. Plus time is recorded too, so a downgrade counts the day honestly.
- **Intent's cooldown after use** is separate. It neither uses this allowance nor ends when it runs out.
- **Time zones and clocks:** see section 7.
- **What it can't stop**, on both plans: uninstalling Aurora, turning off its accessibility service in Android
  Settings, safe mode and `adb`. Aurora says this plainly where it is offered (section 7).

### 3.5 Daily Limit rules

A **Daily Limit rule** is a daily minute budget for one app. When it runs out the app is shielded until local
midnight. Rules are unlimited on Free. Each rule's app counts once toward the 5 active Shield targets. A rule
has no strict mode of its own: it never locks Settings and never reserves committed minutes.

### 3.6 Recurring schedules

Recurring schedules are unlimited on Free. Their targets count while the schedule is enabled, and each committed
run answers to the shared allowance when it comes due.

## 4. How limits are counted

| Allowance | What counts | What frees a slot |
| --- | --- | --- |
| Shield targets | The union of what is running and everything enabled: activations, Daily Limits, a running Focus. A target is a package name, or a website's normalised host (lowercase, ASCII, no trailing dot, no `www.`; other subdomains are their own target). Saved drafts don't count. | Pausing or deleting what enables it, or its run ending |
| Intent apps | The union of Intent apps in running and enabled configurations | Same |
| Active Goals | Goals in the Active state | Pausing, completing or archiving one |
| Committed minutes | The union of Cooldown and Always Block intervals, per date | The next local day. Time reserved isn't refunded except when a run is turned off |

Every refusal explains itself and offers what can be done: free a slot, choose a shorter time, or see Plus.
Refused means nothing changed, so closing the message can't continue the action.

## 5. Entitlement system

### 5.1 Shape

`plan/EntitlementPolicy.kt` is the one authority. Pure Kotlin, every rule unit tested. It returns a
`LimitDecision`: allowed or not, reason code, usage, limit, what remains, the remedy, whether upgrading would help.

Domain operations in `Actions.kt` and `ConfigActions.kt` ask it under one lock (`AuroraApplication.entitlements`),
because a check and its write span two stores. `changeRules` is the one way rules that can switch on a target
change. Screens only show the answers.

What is enforced is also computed at read time (`Rules.retainedShield`, `retainedIntent`, `entitledActivations`,
`liveRuns`), so data that arrived some other way, such as a phone-to-phone restore, still can't enforce past the plan.

### 5.2 Where the plan comes from

From a snapshot the verification server signed after asking Google, and nothing else: `docs/BILLING.md`.

### 5.3 Rules for features

- `decide()` and the accessibility service never read the plan, so both plans get the same protection strength.
- All limit numbers live in `FreeLimits` and nowhere else.
- No paywall for anything unlimited. No countdowns, fake discounts or urgency.

### 5.4 Downgrade

Computed, not processed: what is active is a function of the plan and the saved configuration, so there is no
migration to run, nothing to recover after process death, and nothing to undo.

1. Everything stays saved, with its history.
2. Past the limits, the person chooses the 5 Shield targets, 3 Intent apps and 1 Goal that stay active. Until they
   do, the oldest stay active, and Home says so.
3. Everything else is paused and can't be switched on again past the limit.
4. A commitment already running finishes as promised, and its targets hold their slots until then.
5. Renewing restores everything at once.

## 6. Cloud sync (future)

Cloud sync is listed under Aurora Plus as a future feature. It conflicts with Aurora's current
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

**Time zones and clocks, for the committed allowance.** A regular app can't make device time tamper-proof.
Within one boot, the allowance reads the monotonic clock anchored on its newest ledger entry, so changing the date
or time does nothing. After a restart only the wall clock is left: setting it back up to two days can't reopen an
earlier day, further back is treated as correcting a wrong clock, and setting it forward across a restart isn't
seen. A zone change takes effect when the local day of the newest entry ends. Clearing Aurora's data or
reinstalling it starts a fresh ledger, which is the limit of local accounting. Settings › Delete all data keeps it.

These limits apply equally to both plans. Aurora states them in the product wherever they matter.

| Limitation | Effect | How Aurora handles it |
| --- | --- | --- |
| Shield depends on an accessibility service. | The person can turn it off in Android Settings at any time. Some OEMs and Android's Advanced Protection mode may disable it. | `ProtectionStatus` shows `NeedsPermission`. Nothing claims protection while it's off. |
| Lockdown can't defeat the system. | Uninstalling Aurora, turning off the service, safe mode and `adb` all end protection. | Said plainly wherever Lockdown is offered. Aurora never implies otherwise. |
| Daily budgets use the device clock. | Changing the date or time can reset Lockdown minutes and app limits. | Accepted for 1.0. There's no server to check time against. |
| The entitlement is checked on the phone. | A modified app can unlock plus. | Accepted, because there is no backend. Play Billing's state is trusted. |
| Website filtering covers supported browsers only (1.1). | In-app browsers, custom tabs and WebViews aren't covered. | A "Works in" list on the Websites screen. |
| Daily limits need Usage access. | Without it, limits can't measure time. | The limits screen explains this and offers the permission. |
| Android keeps limited usage history. | Basic insights cover 7 days at most from Android's own records. | Advanced insights store daily totals on the phone (D14). |
| Notifications can be delayed. | Doze may delay the end-of-session alert. | The completion moment is always correct when Aurora opens (gate G6). |

## 8. Open questions

Each question has a proposed answer that the roadmap uses until it is confirmed.

| ID | Question | Proposed answer |
| --- | --- | --- |
| Q1 | Do the mode definitions in 3.2 (Flexible, Friction, Cooldown) match the intended product? | As written |
| Q2 | Do apps in a daily limit rule count toward the 5 protected items? | No |
| Q3 | When a daily limit runs out, is the app shielded with Friction, or blocked until midnight? | Friction. Lockdown stays the only state with no way through, and it is metered. |
| Q4 | Is Lockdown reserved at start (as in 3.4), or charged minute by minute? | Reserved at start, refunded on early ends outside Aurora's control |
| Q5 | Is there a free trial for Aurora Plus, and how long? | 7 days on the yearly plan |
| Q6 | Prices outside India | Decide before Phase 11 |
| Q7 | Do Focus profiles cover only protected items, or any app? | Protected items only |
| Q8 | Are gentle streaks (3.7) acceptable as the "streaks" in Free? | Yes |
| Q9 | Can a Cooldown be skipped? | Yes, behind a Friction wait, except during Lockdown |
