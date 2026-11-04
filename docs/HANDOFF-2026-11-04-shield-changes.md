# Handoff: founder's Shield / Intent / Friction changes (2026-11-04)

Branch `feat/device-quota-anti-abuse`. Work stopped midway. **Nothing compiled or tested yet.** The working tree
already held uncommitted UI work from before this session (Goals, HomeSections, ConfigureScreen and others, plus
screenshots). Don't revert those changes.

## Founder's requests (all seven)

1. **Website block, "Return home"**: close or clear the blocked tab so the browser works again when reopened. Opening the
   site again blocks it again.
2. **Parts of apps**: no full-screen Shield. Held-back screens (Reels tab, Search, other reels after a friend's reel,
   posts not opened from a chat, Shorts, videos from other channels) show a **small dark toast** ("Reels are not allowed",
   "Search is not allowed", "Other reels are not allowed"). Feeds (IG Home posts, profile grids, YT recommendations) are
   blanked with a plain message ("Posts are not allowed" / "Recommendations are not allowed"), and stories stay visible.
   The founder wants the blank area to look like the app ("white").
3. **Cooldowns never restart on screen off or lock**, only when the person leaves the app or switches apps and comes back.
   This applies to the Cooldown-mode session wait (EndSession in Aurora) and to the Intent post-usage cooldown.
4. **Intent cooldown shows in that app** with a running timer. The only ways past it: end the session in Aurora
   (Flexible at once, Friction challenge, Cooldown wait). Strict (AlwaysBlock) has no way past it.
5. **Intent: every reopen restarts everything** (the pause and the whole flow). The intentional session never resumes.
   Remove the "Decide again every time" option, because it is now always on.
6. **Session end stops Intent**: its timer, cooldown and pause all end when the session ends.
7. **Friction**: only two challenges, Math and Writing. For Writing, the person sets the exact phrase while creating the
   Shield, and ending the session requires typing that phrase.

## Done (uncompiled)

- `protect/Interceptor.kt`: adds a new constructor param `asleep: () -> Boolean`. While asleep, non-Shield windows are
  ignored, and a Shield is never counted as an attempt. Also adds a new `fun awake()`, which calls `onForeground(lastApp)`
  and `recheck()` on unlock. Covers #3 and keeps Intent time counting after unlock.
- `protect/ShieldService.kt`: passes `asleep = { screenAsleep(this) }`. On SCREEN_OFF it calls
  `foregroundChanged(null, screenOff = true)`. On SCREEN_ON and USER_PRESENT (newly registered) it calls
  `interceptor.awake()`. Adds a new top-level `fun screenAsleep(context)` (PowerManager not interactive, or keyguard
  locked).
- `Actions.kt`: `foregroundChanged(pkg, screenOff = false)`. Leaving now always ends the session
  (`leave = key != pkg && !screenOff`). `startIntent` always stores `freshEachOpen = true`. `reflectionStart` is deleted.
- `protect/Rules.kt`:
  - `FrictionSettings.phrase` added.
  - `val FrictionTasks = listOf(Math, Intention)` added. Reflection is retired and reads as Math.
  - `fun phraseMatches(phrase, typed)` added (ignores case and extra whitespace).
  - `Runtime.reflectionFor` deleted.
  - `policyState` now filters `intentUses` and `intentCooldowns` to keys in `intents` (#6).
- `protect/Cooldowns.kt`: adds `fun Runtime.intentsEnded(keys, still)`, which drops intentUses, cooldowns, lengths and
  reflections.
- `ConfigActions.kt`: `syncLocked` (ended runs) and `turnOffRun` call `.intentsEnded(...)`. Adds the
  `intentsEnded` import.
- `protect/ShieldContent.kt`:
  - `intentOver` polls `policySnapshot` every 1 s. When the app has left `intents`, the screen shows the new `Ended(...)`
    composable, extracted from `Blocked`.
  - The IntentCooldown branch now shows a `Countdown` and the body text. When a covering run can end
    (`cancellable || endsAfterTask`), it also shows "Open Aurora" and Leave.
  - `EndSession`:
    - Friction: Intention with a phrase uses the new `PhraseTask`. Intention without a phrase uses the old `IntentionTask`.
      Anything else uses `MathTask`.
    - Cooldown wait: `slept` flag, so a stop while `screenAsleep` doesn't reset `since`.
  - `ReflectionTask` is deleted.
  - The Intent pause is now `remember(pkg) { TrustedClock.wall() }`, so it is fresh every opening.
  - The `Chip` and `reflectionStart` imports are removed.

## Still to do

**Strings** (`res/values/strings.xml`):
- Add: `shield_intent_cooldown_end` ("To open it sooner, end this session in Aurora."), `friction_phrase_prompt`
  ("Type these words exactly:"), `friction_phrase_placeholder`, `friction_intention_confirm_phrase` ("End session").
- Add the phrase-setup title and hint for the Shield wizard.
- Add the part toasts: `part_no_reels`, `part_no_other_reels`, `part_no_search`, `part_no_posts`, `part_no_shorts`,
  `part_only_channels`.
- Change `ig_cover_posts` to "Posts are not allowed." and `yt_cover_feed` to "Recommendations are not allowed."
- Remove strings that are now unused: `intent_fresh_*`, `friction_reflect_*`, `friction_task_reflection*`,
  `friction_reflection_pause`, `part_cover_tap` (if unused). CopyTest doesn't cover any of these.

**`ui/library/ShieldScreens.kt`** (Type step):
- Use `items(FrictionTasks)` instead of `FrictionTask.entries`, and remove the Reflection pause block.
- When `task == Intention`, show an `AuroraTextField` for `phrase`, with a new `rememberSaveable` state seeded from
  `initial.friction.phrase`.
- `canNext` for Type also needs `mode != Friction || task != Intention || phrase.isNotBlank()`.
- Save with `FrictionSettings(task, difficulty, pause, phrase.trim())`, in both `save` and the Review preview.
- Map a stored Reflection to Math on load.

**Other UI:**
- `ui/protect/ItemScreen.kt:101-108`: use `FrictionTasks` and drop the Reflection chips.
- `ui/protect/ProtectParts.kt:113,122`: the Reflection branches can point at the Math strings, or be removed if the `when`
  allows it.
- `ui/library/IntentScreens.kt`: remove the `fresh` state and the "fresh" toggle item (lines ~129, 137, 197-209). Pass no
  `freshEachOpen` in the config.

**#2 parts** (`protect/Web.kt`, `protect/ContentService.kt`):
- `InstagramVerdict.Leave` and `YouTubeVerdict.Leave` / `NotFromChannel` get `@StringRes val says: Int`.
- Messages mode: change `search_tab` from `cover(0)` to `Leave(search)`. Set `says` per case (clips_tab = reels,
  reelViewer not sent = other reels, reelsPage = reels, posts not from chat = posts). Legacy search becomes Leave.
- `ContentService.present` for part keys: if `decision.reason != Intent`, show a toast overlay with `says` and
  `performGlobalAction(GLOBAL_ACTION_BACK)` instead of the presenter. Keep the full Shield only for Intent.
- The toast overlay: TYPE_ACCESSIBILITY_OVERLAY, NOT_FOCUSABLE | NOT_TOUCHABLE, WRAP_CONTENT, bottom-centred, a dark
  rounded TextView, hidden after about 2.5 s via the handler.
- Cover tap: present only if `canContinue`, otherwise show the toast.
- `CoverContent`: plain background matching system night mode (white or black) with grey text, and drop the "tap" line.
- Update `WebTest` expectations, including search becoming Leave.

**#1 websites** (`ContentService`):
- `leaveAction` for `web:` keys returns `GLOBAL_ACTION_BACK` and sets `clearing = browser`, plus a deadline about 4 s
  ahead.
- In `checkSite`, while clearing:
  - If the bar still shows a blocked host, perform BACK (throttled to about 600 ms, max about 6 times) instead of
    presenting.
  - Once the host is allowed or null, or the browser leaves the front, or the deadline passes, perform
    `GLOBAL_ACTION_HOME` and clear the state.
- Restructure `checkSite`'s early returns so the clearing logic sees a null host.

**Tests:**
- Delete or replace `RulesTest.reflectionContinuesInsteadOfRestarting` (lines 202-210), because `reflectionFor` is gone.
- Add these tests:
  - InterceptorTest: asleep produces no attempt or dismiss.
  - Rules/Cooldown test: `intentsEnded`, and `policyState` filtering cooldowns once the run is over.
  - FrictionTest: `phraseMatches`.
- Check CooldownTest line 73 and anything else using `freshEachOpen` or `foregroundChanged`.

**Verify:**
1. `./gradlew testDebugUnitTest`
2. `./gradlew recordRoborazziDebug`, then look at the changed screenshots, per CLAUDE.md.
3. `graphify update .`

## Open risks

- The website "close tab" behaviour relies on Back. In Chrome, a tab with no history where Back only minimises the app
  stays on the site; the fallback is just going home.
- The `asleep` check ignores non-Shield windows while the keyguard is locked. That is fine for normal use. Watch for
  OEM lock screens that report a non-systemui package.
- Every opening is now fresh, so leaving before the Intent time is up avoids the cooldown. That follows from the founder's
  request; confirm it with them.
