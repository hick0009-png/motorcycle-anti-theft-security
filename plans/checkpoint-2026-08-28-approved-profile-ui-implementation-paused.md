# Checkpoint — Approved Profile-aware Home UI Implementation (Paused)

**Date:** 2026-08-28  
**Workspace:** `D:\security\MotorcycleAntiTheftSensor`  
**Branch:** `feature/motorcycle-guard-protection`  
**HEAD:** `0765e0f` (`docs: checkpoint session 5 full sync (wiring + remediation + ui fix)`)  
**Status:** User approved the profile-aware home UI blueprint. Codex implementation was launched and changed the dirty worktree. User paused work before test/build/review completion. **Do not reset, checkout, stash, revert, delete, or commit existing work without explicit approval.**

## Product model — authoritative

This is a multi-purpose monitoring product, not vehicle-only. The supported uses are:

1. **Vehicle Guard** — vehicle monitoring/protection.
2. **Entry Guard** — shop/warehouse door or area monitoring.
3. **Power Guard** — power-outage monitoring using supply/charging and witness-light signals.

Shared UI wording and status/safety claims must remain installation-neutral.

## User-approved implementation direction

The approved blueprint is recorded in `plans/checkpoint-2026-08-28-profile-aware-home-ui-blueprint.md`.

- Calm, light, settings-style interface with large rounded cards.
- First screen order: system status → current use/profile → compact profile-specific status → latest event → expandable technical system details.
- Power Guard normal card remains compact; presents charger, witness light, and last-updated information.
- Do not treat either Power signal alone as a confirmed outage/fault; show a recovery instruction only for a confirmed fault.
- Fully Thai-localize presentation copy that is touched, including raw display values where feasible.

## What occurred in the last session

1. User explicitly approved implementation (`อนุมัติ`).
2. Codex CLI was invoked twice with incompatible CLI flag combinations; both commands exited code 2 without editing:
   - `codex exec ... --full-auto` — this Codex version rejects `--full-auto` on `exec`.
   - `codex exec --sandbox danger-full-access --approve-for-me` — CLI rejects these flags combined.
3. A third Codex command was launched successfully with:
   ```bash
   codex exec --sandbox danger-full-access '<implementation prompt>'
   ```
4. The process no longer exists when inspected; its final Codex output was not retained. Git status shows new source/test edits relative to the pre-Codex state, so inspect the diff carefully before assuming behavior is correct.
5. No fresh Gradle test/build result has been run or retained after the new changes. No commit was made.

## Current worktree facts

- `git diff --check` exits 0; only LF→CRLF warnings were printed.
- Current diff summary: **16 tracked files**, **1,029 insertions / 358 deletions**.
- New/modified Android instrumentation tests are now present:
  - `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt`
  - `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionProfilesUiTest.kt`
- Current untracked artifacts must be preserved:
  - `android-mcp.log`
  - `../MotorcycleAntiTheftSensor_User_Manual_v2.5.pdf`
  - `../docs/MotorcycleAntiTheftSensor_User_Manual.html`
  - `../docs/MotorcycleAntiTheftSensor_User_Manual.pdf`
  - `../docs/audits/`

## Dirty files, grouped by responsibility

### A. Power live-state / domain-to-UI slice

- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt`
  - Adds `startPowerStatusMonitoring()` / `stopPowerStatusMonitoring()` and avoids stopping the power/thermal monitor simply because detector monitoring stops.
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntime.kt`
  - Exposes matching default lifecycle APIs.
- `app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt`
  - Stops live power-status monitoring on service destruction.
- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt`
  - Replaces coarse charging states with `CHARGING`, `DISCHARGING`, `FULL`, `NOT_CHARGING`, `UNKNOWN`.
  - Adds witness `AVAILABLE`, `lastUpdatedAtMs`, and `confirmedFault` fields to `PowerSummaryRows`.
- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt`
  - Combines coordinator snapshot with selected Power witness model.
  - Projects actual power and light health to compact summary rows; identifies confirmed power fault only when there is an open Power incident while `ALERT_ACTIVE`.
  - Starts power-status monitoring when Power profile is selected.
- `app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt`
  - Expands Power mapping test cases and includes a disarmed live-change regression case.

### B. Approved profile-aware home UI changes

- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/ProtectionScreen.kt`
  - Adds system status card first; current-use card; vehicle status summary; latest-event card; system-details disclosure; Thai status mappings; rounded 24dp cards / 20dp padding.
  - Adds `PROTECTION_LIST_TAG` for Compose scrolling/tests.
- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/PowerGuardSection.kt`
  - Updated compact Power Guard presentation. **Review exact copy/conditions before acceptance.**
- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/EntryGuardSection.kt`
  - Card styling and hides angle controls behind a secondary action when ready.
- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreen.kt`
  - Thai primary navigation labels/accessibility descriptions.
- `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionProfilesUiTest.kt`
  - Many new Compose tests for normal Power/Entry/Vehicle hierarchy, setup states, confirmed-fault-only recovery text, and Thai values.
- `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt`
  - Adjusted degraded sensor copy expectation.

### C. Other dirty UI/copy WIP (must be separately reviewed)

- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/UserGuidance.kt`
  - Changes persistence/severity/action for many guidance types and copies. It still contains vehicle-specific Telegram strings in places (e.g. `มือถือรถ`) despite installation-neutral goal; assess scope before retaining.
- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/events/EventsScreen.kt`
  - Thai localization plus anti-spam caption for open event.
- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt`
  - Adds Telegram remote-control readiness card.
- `app/src/test/java/com/example/motorcycleantitheftsensor/protection/UserGuidanceCatalogTest.kt`
  - Adjusted guidance-copy assertions.

## Verification status

**Not verified.** Only static `git diff --check` has run successfully. No post-change Gradle build, unit test, instrumentation test, APK assembly/install, or physical charging-transition test has retained evidence.

Repository execution rules:

- Run exactly one Gradle invocation at a time.
- Always use `--no-daemon --max-workers=1`.
- Required environment:
  ```bash
  export JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
  export ANDROID_HOME='C:\Users\ASUS\AppData\Local\Android\Sdk'
  ```
- Required full host gate:
  ```bash
  ./gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin
  ```

## Exact resume plan

1. Read this checkpoint and the approved blueprint:
   ```bash
   cd D:\security\MotorcycleAntiTheftSensor
   git status --short
   git diff --check
   git diff --stat
   ```
2. Inspect Codex-added changes by file before editing, particularly `ProtectionScreen.kt`, `PowerGuardSection.kt`, `ProtectionViewModel.kt`, and `ProtectionProfilesUiTest.kt`.
3. Compile first to discover any code/API errors (one Gradle invocation):
   ```bash
   export JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
   export ANDROID_HOME='C:\Users\ASUS\AppData\Local\Android\Sdk'
   ./gradlew.bat --no-daemon --max-workers=1 :app:compileDebugKotlin
   ```
4. If compile passes, run focused host tests for `ProtectionViewModelTest` (one invocation). Fix compile/test regressions only; preserve scope.
5. Run instrumentation compile and the full host gate sequentially, never in parallel:
   ```bash
   ./gradlew.bat --no-daemon --max-workers=1 :app:compileDebugAndroidTestKotlin
   ./gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest
   ```
6. Independently review the complete diff for wrong Power claims, localized accessibility/copy regressions, missing imports, layout/test brittleness, and mixed unrelated behavior changes. Do not commit automatically.
7. Build/install the debug APK only after suite verification. On Huawei INE-LX2 manually verify:
   - profile selection and order for Vehicle / Entry / Power;
   - Power disarmed charger plug/unplug transition;
   - Power fault guidance only when the coordinator produces a confirmed open Power incident;
   - Thai line wrapping, 200% font scale, and no bottom-navigation overlap;
   - technical cards are absent until `ดูรายละเอียดระบบ` is expanded.
8. Report precise verification output and remaining device-only risk; request user approval before any commit.

## Important risks to assess after resume

- The UI diff is much larger than original (over 1k added lines) and came from a Codex session whose final report was not captured.
- Existing Worktree had prior WIP that must not be attributed entirely to the approved redesign.
- TDD “red” evidence from Codex was not retained, so do not claim strict TDD completion.
- There is a potential product-language inconsistency: `UserGuidance.kt` includes both installation-neutral and vehicle-specific wording. Shared UI remains required to be neutral.
- The second-level details UI uses `AnimatedVisibility` inside a `LazyColumn`; confirm layout/runtime rendering by compilation and actual Compose/device test.

## No commits / no cleanup

No commit occurred in this session. Existing dirty files and listed untracked artifacts remain intentionally preserved.
