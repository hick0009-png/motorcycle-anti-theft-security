# Moto Guard Navy UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` task-by-task. Every task is a separate review checkpoint.

**Goal:** Refresh the three reachable Compose destinations and create real Settings subpages in the approved Navy–White–Green Moto Guard design without changing protection behavior.

**Architecture:** Keep `Navigation.kt`, `ProtectionCoordinator`, `ProtectionViewModel`, `ProtectionUiState`, settings gateway, and all existing actions authoritative. Compose receives only a presentation refresh; Settings owns a `rememberSaveable` internal page enum and uses `BackHandler` to return to its overview. The app remains a three-tab shell.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Android instrumentation tests, JUnit host tests.

**Spec:** `docs/superpowers/specs/2026-08-26-moto-guard-navy-ui-visual-design.md`

## Global constraints

- Work only in `D:\security\.worktrees\continuity-recovery-tdd\MotorcycleAntiTheftSensor` on `codex/continuity-recovery-tdd`.
- Do not alter `ProtectionCoordinator`, sensor policy, incident policy, authorization, encryption, Telegram/SMS/GPS/Direct Boot behavior, or the three `ProtectionDestination` values.
- Do not edit disconnected `DashboardScreen.kt` or `ui/main/MainScreen.kt`.
- Use vector drawables/resources or existing platform vector icons; no user-facing emoji or text pictograms.
- Preserve pairing-code masking plus `FLAG_SECURE`, password transformations and clearing of token/SMS key, reset-pairing confirmation, Power's separate charging/witness rows, the existing advanced disclosure, and 48 dp targets.
- Run one Gradle command at a time with `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`, `--no-daemon`, and `--max-workers=1`.
- Stage and commit exact task paths only. Stop if an unrelated dirty change appears.

---

### Task 1: Navy shell and bottom navigation

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreen.kt`
- Create: `app/src/main/res/drawable/ic_moto_guard_protection.xml`
- Create: `app/src/main/res/drawable/ic_moto_guard_events.xml`
- Create: `app/src/main/res/drawable/ic_moto_guard_settings.xml`
- Test: `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt`

**Consumes:** existing `ProtectionDestination`, `ProtectionAppActions.selectDestination`, labels and accessible descriptions from string resources.

**Produces:** navy shell tokens and a three-tab navigation bar with consistent vector icons, selected semantics, and stable test tags.

- [ ] Write a failing Compose test that selects each existing destination and asserts its visible Thai label plus a selected semantic state; assert the tab labels contain no emoji characters.
- [ ] Run `gradlew.bat --no-daemon --max-workers=1 connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.motorcycleantitheftsensor.ui.ProtectionAppScreenTest` and record the RED assertion caused by the old monochrome shell.
- [ ] Replace the local monochrome color scheme with semantic Navy–White–Green tokens, preserve the existing Scaffold and `PRIMARY_*` test tags, and replace the three Android system drawables with the new 24 dp outline vectors. Keep the selected state communicated by Material selection semantics as well as color.
- [ ] Re-run the focused instrumentation class; verify the new assertions pass. Run `findstr /s /i "🤖 📨 🔑 ⚡ 📊" app\src\main\java\com\example\motorcycleantitheftsensor\ui\ProtectionAppScreen.kt` and expect no matches.
- [ ] Commit exact files with `git commit -m "feat(ui): apply Moto Guard navy navigation shell"`.

### Task 2: Protection presentation hierarchy

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/ProtectionScreen.kt`
- Test: `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt`
- Test: `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionProfilesUiTest.kt`

**Consumes:** existing `ProtectionUiState`, profile presentation catalog, arm/disarm and commissioning actions.

**Produces:** compact navy header, outcome-first status card, one primary action per state, and restyled advanced disclosure.

- [ ] Write failing tests for a visible outcome-first status heading, exactly one primary arm/disarm action in each fixture, wrapping profile promise, and the current Entry/Power controls remaining reachable.
- [ ] Run only those test methods and verify failure against the previous layout.
- [ ] Recompose existing sections into header, status card, profile/setup card, supporting evidence card, and advanced disclosure without changing callbacks, state conditions, or test tags. Keep Power's two independent rows and all technical values behind advanced disclosure.
- [ ] Re-run the focused tests and then `testDebugUnitTest --tests "*ProtectionViewModelTest*"`; verify green output.
- [ ] Commit exact files with `git commit -m "feat(protection): apply Moto Guard status hierarchy"`.

### Task 3: Events timeline presentation

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/events/EventsScreen.kt`
- Test: `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt`

**Consumes:** existing event title, severity, lifecycle, delivery, timestamp, retry, and clear-history actions.

**Produces:** navy title header and readable event cards/timeline for populated, loading, error, and empty states.

- [ ] Write failing tests that retain all four state messages, retry action, and clear-history confirmation while asserting card/timeline semantics for a populated fixture.
- [ ] Run the scoped methods and verify the expected RED failure.
- [ ] Restyle only the events layout; do not add filter chips, fake profile data, or new event actions. Keep destructive clear confirmation and presentation-catalog formatting unchanged.
- [ ] Run focused Android tests and `testDebugUnitTest --tests "*ThaiPresentationSourceContractTest"`; verify green output.
- [ ] Commit exact files with `git commit -m "feat(events): apply Moto Guard event timeline"`.

### Task 4: Settings overview and internal navigation

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt`
- Test: `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt`

**Consumes:** existing `SettingsScreen(state, actions, contentPadding)` signature and all existing settings sections.

**Produces:** `SettingsPage` internal UI enum, four real overview cards, accessible page header/back action, and system-back return to overview.

- [ ] Write failing Compose tests that open each overview card, assert its actual page heading, press the header back action and device back, and return to the overview without changing the selected bottom destination.
- [ ] Run scoped tests and verify RED because Settings is still one long list.
- [ ] Add a file-private `SettingsPage` enum (`OVERVIEW`, `PROTECTION`, `DELIVERY_SECURITY`, `CONTINUITY`, `ADVANCED`) saved by `rememberSaveable`; use `BackHandler` for non-overview pages. Extract no domain logic: route each page to existing local composables/actions only.
- [ ] Re-run scoped tests and verify green. Confirm the public screen signature and all `ProtectionAppActions` calls are unchanged.
- [ ] Commit exact files with `git commit -m "feat(settings): add Moto Guard settings navigation"`.

### Task 5: Protection Settings page

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt`
- Test: `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionProfilesUiTest.kt`

**Consumes:** existing profile picker, Vehicle sensitivity, Entry angle/commissioning, Power commissioning and two signal rows.

**Produces:** a real `การปกป้อง` subpage that preserves profile-specific behavior and adaptive layout.

- [ ] Write failing tests that navigate to `การปกป้อง` and assert profile setup, Entry 5/15/30-degree choices, Power charging/witness rows, and 200%/narrow layout behavior.
- [ ] Run them to obtain RED.
- [ ] Move only existing profile/setup composables into the protection page; use cards, navy header, and vector icons without changing call order or values. Keep unavailable/setup-required states truthful.
- [ ] Run the focused suite and `testDebugUnitTest --tests "*ProtectionViewModelSettingsTest*"`; verify green.
- [ ] Commit exact files with `git commit -m "feat(settings): redesign protection setup page"`.

### Task 6: Delivery, security, and continuity Settings pages

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt`
- Test: `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt`

**Consumes:** existing Telegram/SMS, pairing reset, permission, and keep-alive controls and tests.

**Produces:** two real pages, `การแจ้งเตือนและความปลอดภัย` and `ความต่อเนื่องของระบบ`, with the same secret and permission behavior.

- [ ] Write failing tests for entering both pages, masked pairing/token/key behavior, `FLAG_SECURE` reveal lifecycle, reset confirmation, blocker-versus-reduced-coverage wording, and existing platform-settings actions.
- [ ] Run scoped tests and verify RED.
- [ ] Move existing composables to their matching page, replacing every user-facing emoji with the chosen vector-icon language. Preserve password transformations, clearing, confirmation, and current callbacks exactly.
- [ ] Run focused Android and host tests; search the reachable settings source for the prohibited emoji set and verify no matches.
- [ ] Commit exact files with `git commit -m "feat(settings): redesign delivery and continuity pages"`.

### Task 7: Advanced Settings page and integration gate

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt`
- Test: `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/ui/ThaiPresentationSourceContractTest.kt`
- Modify: `docs/testing/2026-08-23-profile-aware-thai-ux-device-acceptance.md`

**Consumes:** existing advanced disclosure, sensor configuration dialog, audio diagnostics, source-contract rules, and device acceptance template.

**Produces:** `การวินิจฉัยขั้นสูง` page with progressive disclosure intact, complete visual/accessibility evidence, and updated acceptance checklist.

- [ ] Write failing tests that advanced controls are absent from normal Settings overview, reachable only through the Advanced page/disclosure, and retain their current dialog/action behavior.
- [ ] Run scoped tests and verify RED.
- [ ] Restyle and relocate advanced sections without making technical terms normal-user content; remove remaining reachable emoji; preserve diagnostics, sensor configuration, audio semantics, and accessibility roles.
- [ ] Run focused tests, then the full host/compile/APK gate: `gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest compileDebugAndroidTestKotlin assembleDebug`.
- [ ] Perform fresh device acceptance only when a device is available; record observed facts, screenshots, and any failure in the acceptance document. Do not claim device acceptance from build success.
- [ ] Commit exact files with `git commit -m "feat(settings): complete Moto Guard navy UI refresh"`.

## Plan self-review

- Spec coverage: Tasks 1-3 cover the three production destinations; Tasks 4-7 cover the approved four Settings pages, no-emoji requirement, security boundaries, accessibility, and verification.
- No placeholders: every task identifies exact files, tests, commands, ownership, and commit scope.
- Type consistency: `SettingsPage` is file-private UI state; no new public navigation or domain interface is introduced.
