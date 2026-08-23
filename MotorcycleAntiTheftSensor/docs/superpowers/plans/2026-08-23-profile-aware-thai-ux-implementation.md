# Profile-aware Thai UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every reachable production surface understandable in Thai, profile-aware, truthful about what the phone can detect, correct about units, accessible at large text sizes, and consistent across UI, notifications, Telegram, and SMS.

**Architecture:** Keep `ProtectionCoordinator` and the existing profile/runtime models authoritative. Put static screen and accessibility copy in Android string resources, put profile/domain/value formatting in a pure Kotlin presentation catalog, and make each channel consume those shared semantics without parsing enum names or inventing protection state.

**Tech Stack:** Kotlin 2.3.20, Android SDK 36/minSdk 24, Jetpack Compose Material 3, coroutines/StateFlow, Android string resources, JUnit 4, AndroidX Compose instrumented tests.

**Spec:** `docs/superpowers/specs/2026-08-23-profile-aware-thai-ux-terminology-design.md`

**Planning boundary:** This handoff authorizes no production edits by itself. Start implementation only in an execution session after the owner assigns this plan. This document does not claim that source, tests, APK, installation, layout, TalkBack, sensors, Telegram, or SMS have been verified.

## Global Constraints

- Read `D:\security\AGENTS.md`, `AI_WORKFLOW.md`, and the approved spec completely before editing code.
- Execute from `D:\security\.worktrees\continuity-recovery-tdd\MotorcycleAntiTheftSensor` on branch `codex/continuity-recovery-tdd` unless the owner explicitly selects another clean baseline.
- Preserve all unrelated dirty and untracked files. Before every task run `git status --short`; never reset, clean, checkout, broadly stage, move, overwrite, or commit files outside that task. Stage exact paths only.
- Do not run production-editing agents concurrently in this checkout. If the parent agent delegates review or inventory work, those agents must remain read-only. Final integration and all verification stay with the parent agent.
- Run only one Gradle invocation at a time. Set Android Studio JBR, use `--no-daemon --max-workers=1`, and do not overlap Gradle with ADB installation or instrumentation.
- Do not add dependencies, rename internal enums/public APIs for display purposes, change sensor algorithms or thresholds, change authorization/encryption/GPS/SMS policy, or revive `DashboardScreen`/`MainScreen`.
- `ProtectionCoordinator` remains the only protection-state authority. Presentation code formats existing facts; it never arms, disarms, infers readiness, or claims delivery.
- Keep Telegram token, chat ID, pairing code, command arguments, and encryption keys out of UI text, accessibility semantics, screenshots, notifications, Events, Telegram echoes, SMS, and logs. Preserve explicit pairing-code reveal and `FLAG_SECURE`.
- Keep pre-unlock Direct Boot storage and notification content local-only. Do not add Telegram, SMS, TOTP, pairing, secret, or encrypted-incident reads to the Direct Boot path.
- Shared/global wording must be installation-neutral. The terms `รถ`, `ใต้เบาะ`, `รอบตัวรถ`, and `สตาร์ทเครื่องยนต์` are permitted only in an explicitly selected `VEHICLE` presentation.
- Power copy may describe only the configured charging outlet/power strip. It must never claim a building-wide outage. Entry supporting sensors must never be presented as independent proof that a door opened.
- A 1–10 value is a relative detection setting, not a physical unit. Show it only when it changes a real bounded detector parameter and explain both endpoints.
- Keep one existing live-region/Snackbar announcement path. Do not add a second event bus or duplicate the same status announcement in hero, banner, and Snackbar.
- Source/build/install evidence and fresh real-device acceptance are separate gates. Never infer device behavior, visual quality, sensor support, notification delivery, Telegram delivery, SMS delivery, or TalkBack quality from host tests or APK assembly.

## Baseline and Execution Setup

- [ ] Confirm the approved baseline and protect unrelated work:

```powershell
Set-Location 'D:\security\.worktrees\continuity-recovery-tdd\MotorcycleAntiTheftSensor'
git branch --show-current
git log -1 --oneline
git status --short
```

Expected baseline includes commit `660f0c8 docs: specify profile-aware Thai UX terminology`. Untracked logs, screenshots, and checkpoint files already present are unrelated and must remain untouched.

- [ ] Set the task environment once per PowerShell session:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\ASUS\AppData\Local\Android\Sdk'
```

- [ ] Run the pre-edit compilation gate:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest compileDebugAndroidTestKotlin assembleDebug
```

Expected: PASS before Task 1. If it fails, record the exact pre-existing failure and stop; do not disguise it with unrelated fixes.

---

## Task 1: Establish the typed Thai presentation vocabulary and unit formatter

**Files:**

- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/PresentationTextCatalog.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionPresentationModels.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/PresentationTextCatalogTest.kt`
- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionValueFormatterTest.kt`

**Interfaces:**

- Consumes existing `ProtectionProfile`, `ProfileSetupState`, `SensorCapability`, `SensorSource`, `SensorRole`, `SensorUnit`, `IncidentType`, `IncidentSeverity`, `IncidentLifecycle`, `DeliveryState`, and `IncidentEvidence`.
- Produces immutable `ProfilePresentation`, `CapabilityPresentation`, `IncidentPresentation`, and `FormattedMeasurement` values.
- Keep existing catalog functions temporarily as delegating compatibility methods so Tasks 2–7 can migrate callers without a flag day.

- [ ] Write failing tests for all three profile cards and context-specific capability wording. Include exact assertions for:

```kotlin
assertEquals("ยานพาหนะ", PresentationTextCatalog.profile(ProtectionProfile.VEHICLE).name)
assertEquals("ประตูและทางเข้า", PresentationTextCatalog.profile(ProtectionProfile.ENTRY).name)
assertEquals("ไฟเลี้ยงจุดติดตั้ง", PresentationTextCatalog.profile(ProtectionProfile.POWER).name)
assertEquals("การขยับที่ต้องการให้แจ้งเตือน", PresentationTextCatalog.capability(ProtectionProfile.VEHICLE, SensorCapability.MOVEMENT).title)
assertFalse(PresentationTextCatalog.capability(ProtectionProfile.ENTRY, SensorCapability.MAGNETIC).isPrimaryControl)
assertFalse(PresentationTextCatalog.capability(ProtectionProfile.POWER, SensorCapability.LIGHT).isGenericSensitivityControl)
```

- [ ] Write failing unit-format tests covering every accepted quantity:

```kotlin
assertEquals("15°", ProtectionValueFormatter.doorAngle(15))
assertEquals("35 °C", ProtectionValueFormatter.temperatureCelsius(35.0))
assertEquals("1.5 วินาที", ProtectionValueFormatter.duration(1_500))
assertEquals("750 มิลลิวินาที", ProtectionValueFormatter.duration(750))
assertEquals("120 lux", ProtectionValueFormatter.lux(120.0).value)
assertEquals("2.4 m/s²", ProtectionValueFormatter.measurement(2.4, SensorUnit.METERS_PER_SECOND_SQUARED).value)
assertEquals("0.8 rad/s", ProtectionValueFormatter.measurement(0.8, SensorUnit.RADIANS_PER_SECOND).value)
assertEquals("42 µT", ProtectionValueFormatter.measurement(42.0, SensorUnit.MICROTESLA).value)
assertEquals("-36 dBFS", ProtectionValueFormatter.audioDbfs(-36.0).value)
```

The current enum exposes `LUX_RATIO` rather than raw lux and has no dBFS member, so `lux(...)` and `audioDbfs(...)` are explicit advanced-formatting functions rather than invented enum values. Use the existing constants for `METERS_PER_SECOND_SQUARED`, `RADIANS_PER_SECOND`, `DEGREES`, `MICROTESLA`, `LUX_RATIO`, `NORMALIZED_STATE`, and `TRIGGER`. Preserve Thai-locale digit grouping and suppress meaningless trailing `.0`.

- [ ] Run the RED gate:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests "*PresentationTextCatalogTest" --tests "*ProtectionValueFormatterTest"
```

Expected: FAIL because the typed presentation models and formatter functions do not exist.

- [ ] Add the smallest pure Kotlin model surface:

```kotlin
data class ProfilePresentation(
    val name: String,
    val promise: String,
)

data class CapabilityPresentation(
    val title: String,
    val explanation: String,
    val roleLabel: String,
    val isPrimaryControl: Boolean,
    val isGenericSensitivityControl: Boolean,
)

data class FormattedMeasurement(
    val value: String,
    val interpretation: String,
)
```

Add exhaustive `when` mappings. Roles must render `ใช้ยืนยันหลัก`, `ใช้ประกอบการยืนยัน`, and `ไม่ใช้`. Normal descriptions are semantic; technical source names and physical units are returned only by advanced formatting functions.

- [ ] Implement `ProtectionValueFormatter` with bounded, locale-aware decimal formatting. Durations at or above 1,000 ms use seconds unless millisecond precision is necessary. Proximity uses near/far semantics and never fabricates centimetres.

- [ ] Run the GREEN gate:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests "*PresentationTextCatalogTest" --tests "*ProtectionValueFormatterTest"
```

Expected: PASS.

- [ ] Commit only Task 1 files:

```powershell
git add app/src/main/java/com/example/motorcycleantitheftsensor/protection/PresentationTextCatalog.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionPresentationModels.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/PresentationTextCatalogTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionValueFormatterTest.kt
git commit -m "feat(ui): add profile-aware Thai presentation catalog"
```

---

## Task 2: Translate the production shell, profile picker, and permission language

**Files:**

- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreen.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiText.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/ProtectionScreen.kt`
- Modify: `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt`
- Modify: `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionProfilesUiTest.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiTextTest.kt`

**Interfaces:**

- `PrimaryDestination` continues to map the same three enum destinations and icon resources; only labels and semantics change.
- Profile selection continues through `ProtectionAppActions.selectProfile`, `confirmProfileSwitch`, and `cancelProfileSwitch`; no new state owner is introduced.
- Permission actions continue through `requestPermissions`; copy changes must not imply that permission was granted.

- [ ] Update instrumented tests first to require exactly `ปกป้อง`, `เหตุการณ์`, and `ตั้งค่า`, with Thai destination content descriptions and the existing stable three-tab count.

- [ ] Replace English profile assertions with the approved card names and promises. Add tests for the Thai switch actions:

```kotlin
composeRule.onNodeWithText("เปลี่ยนการใช้งาน").assertExists()
composeRule.onNodeWithText("ใช้รูปแบบเดิมต่อ").assertExists()
composeRule.onNodeWithText("หยุดการปกป้องแล้วเปลี่ยน").assertExists()
```

- [ ] Extend `ProtectionUiTextTest` to assert Thai names and plain-language explanations for notifications, microphone, location, phone, and SMS permissions. Unknown permissions must use a neutral Thai fallback and must not expose the raw package string.

- [ ] Run the RED gates sequentially:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests "*ProtectionUiTextTest"
.\gradlew.bat --no-daemon --max-workers=1 compileDebugAndroidTestKotlin
```

Expected: host assertions fail on incomplete Thai copy; Android-test compilation may fail until expected strings and screen API usage align.

- [ ] Move static shell/dialog/accessibility copy into `strings.xml`. Use `stringResource` in Compose and `PresentationTextCatalog.profile(...)` for profile-domain labels. Replace English semantic suffixes such as `"${item.label} destination"` with complete Thai phrases.

- [ ] Remove mixed-language visible labels such as `(Change use)`, `Keep current protection`, `Stop protection and change use`, and `Review permissions`. Do not translate identifiers, log-only values, or test tags.

- [ ] Make long profile promises wrap naturally. Do not cap Thai titles to one line; retain a minimum 48 dp target and ensure selection is conveyed by role/state as well as color.

- [ ] Run the focused GREEN gates:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests "*ProtectionUiTextTest"
.\gradlew.bat --no-daemon --max-workers=1 compileDebugAndroidTestKotlin
```

Expected: PASS.

- [ ] Commit only Task 2 files with `git commit -m "feat(ui): translate shell and profile selection to Thai"`.

---

## Task 3: Rebuild Settings around the selected protection profile

**Files:**

- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt` only if a presentation-ready derived field is required; do not duplicate repository state.
- Modify: `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt`
- Modify: `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionProfilesUiTest.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelSettingsTest.kt` only if projection coverage is needed.

**Interfaces:**

- Consumes `ProtectionUiState.profile`, `ProtectionUiState.settings`, and the existing `SensorGroupUiModel`/`SensorSourceUiModel` diagnostics.
- Normal profile controls call only existing actions: `changeSensitivity`, Entry angle/commissioning actions, Power commissioning actions, and `restoreRecommendedProfile`.
- The existing advanced `updateSensorConfiguration`/`applySensorPreset` path remains the only sensor-configuration mutation path.

- [ ] Write failing Compose tests for the required section order:

```text
การใช้งานปัจจุบัน
การตรวจจับของรูปแบบนี้
การแจ้งเตือน
ความต่อเนื่องของระบบ
การวินิจฉัยขั้นสูง
```

Assert these headings appear in this visual order for each profile.

- [ ] Add profile-specific failing tests:

  - Vehicle shows `การขยับที่ต้องการให้แจ้งเตือน`, the two endpoint explanations, and a meaningful current 1–10 value.
  - Entry shows `แจ้งเมื่อประตูเปิดเกิน 15° จากตำแหน่งปิด`, quick choices `5°`, `15°`, `30°`, and no normal `µT`, magnetic-strength, or rotation-sensitivity control.
  - Power shows independent `การชาร์จโทรศัพท์` and `ไฟยืนยันจุดติดตั้ง` rows, plus the bounded shared-outlet explanation, and no generic sensitivity slider.
  - Significant-motion trigger, audio diagnostics, and binary proximity never expose fake 1–10 sliders.

- [ ] Add a failing disclosure test proving technical cards and source-role controls are absent until `การวินิจฉัยขั้นสูง` is expanded. Remove emojis and the mixed label `Advanced Role Mapping` from expectations.

- [ ] Run the RED gate:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 compileDebugAndroidTestKotlin
```

Expected: compilation succeeds only after test API adjustments, but instrumented assertions remain RED until the UI is implemented.

- [ ] Refactor `SettingsScreen` into small private composables aligned to the five approved sections. Keep the public `SettingsScreen(state, actions, contentPadding)` signature stable. Suggested local functions:

```kotlin
@Composable private fun CurrentUseSection(...)
@Composable private fun ProfileDetectionSection(...)
@Composable private fun AlertChannelsSection(...)
@Composable private fun ContinuitySection(...)
@Composable private fun AdvancedDiagnosticsSection(...)
```

- [ ] Render profile-specific normal controls with exhaustive `when (selectedProfile)`. For `null`, show the picker/setup prompt instead of falling back to Vehicle. Never infer installation type from sensors.

- [ ] Keep Telegram pairing, token replacement, SMS fallback, permissions, Auto-Start, and health diagnostics behavior intact. Translate labels and explanations, preserve secret masking/reveal, and do not expose secrets through semantics.

- [ ] Ensure every interactive row is at least 48 dp, long Thai labels wrap/stack, and the three Entry quick choices wrap or stack instead of forcing equal-width truncated buttons.

- [ ] Build the test APKs and run the focused tests on the assigned device only after the owner confirms ADB use:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 assembleDebug assembleDebugAndroidTest
& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
& "$env:ANDROID_HOME\platform-tools\adb.exe" shell am instrument -w -e class com.example.motorcycleantitheftsensor.ui.ProtectionProfilesUiTest com.example.motorcycleantitheftsensor.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: PASS on all three profile contexts. Record the device serial/model and do not call this cross-device acceptance.

- [ ] Commit only Task 3 files with `git commit -m "feat(settings): present Thai controls by protection profile"`.

---

## Task 4: Simplify Protection into outcome-first Thai status

**Files:**

- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/ProtectionScreen.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/EntryGuardSection.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/PowerGuardSection.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionTimeFormatterTest.kt`
- Modify: `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt`
- Modify: `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionProfilesUiTest.kt`

**Interfaces:**

- Consumes the coordinator-backed `ProtectionStatusUiState`, `ProtectionProfileUiState`, sensor health, latest incident, and `GuidanceContent` already projected by `ProtectionUiState.from(...)`.
- Uses the Task 1 catalog for state, health, delivery, and incident terms. It must not call `.name`, `.lowercase()`, or `.replace('_', ' ')` for display.
- All arm/disarm/profile/commissioning actions remain unchanged.

- [ ] Add failing tests for outcome-first hero copy in DISARMED, ARMING, ARMED, ALARMING, ERROR, and DEGRADED states. Verify one state-correct primary action remains and mixed English status fragments are absent.

- [ ] Add profile-specific outcome tests:

  - Vehicle can mention a vehicle only when Vehicle is selected.
  - Entry reports angle from the calibrated closed position and does not turn magnetic change into a door-open claim.
  - Power reports charging and witness-light status independently and never says the whole building lost power.

- [ ] Add failing tests that diagnostics such as audio state, raw health detail, last-sample time, and delivery internals are behind one advanced disclosure and use Thai labels when opened.

- [ ] Run RED: `compileDebugAndroidTestKotlin` followed by the focused instrumentation class after APK assembly.

- [ ] Replace display-time enum transformations (`state.audio.state.name...`, `deliveryState.displayName()`, `health.state.displayName()`) with catalog calls. Keep raw diagnostic strings out of the normal layer; if a diagnostic is safe and useful, label it explicitly inside Advanced only.

- [ ] Use `formatProtectionTimestamp` only through a Thai-locale-aware formatter established by tests. Avoid `N/A`, `Ready`, `Running`, and raw millisecond concatenation.

- [ ] Verify exactly one live-region/Snackbar announcement for each state transition. The visible hero may update without creating a duplicate assertive announcement.

- [ ] Run the focused host and device GREEN gates, then commit Task 4 with `git commit -m "feat(protection): show profile-aware Thai outcomes"`.

---

## Task 5: Translate Events and replace raw enum rendering

**Files:**

- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/events/EventsScreen.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/PresentationTextCatalog.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/PresentationTextCatalogTest.kt`
- Modify: `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt`

**Interfaces:**

- `SecurityIncident.toEventRow()` remains a projection of persisted facts. Do not fabricate a profile field because `SecurityIncident` currently has no profile snapshot.
- `ProtectionEventRow` retains typed `IncidentType`, `IncidentSeverity`, `IncidentLifecycle`, and `DeliveryState`; Compose formats them through the shared catalog.
- Event source remains truthful (`เหตุการณ์จริง` or another verified model value); do not hard-code a misleading source category.

- [ ] Add failing catalog tests for every incident type, severity, lifecycle, delivery state, and empty/loading/error/retry/clear-history term. Shared incident titles must remain installation-neutral where the persisted incident has no profile.

- [ ] Translate existing Events UI tests to require:

```text
กำลังโหลดเหตุการณ์
ยังไม่มีเหตุการณ์
เกิดข้อผิดพลาดในการโหลดเหตุการณ์
ลองใหม่
ล้างประวัติ
ยืนยันการล้างประวัติ
ยกเลิก
```

Also assert `OPEN`, `CLOSED`, `INTERRUPTED`, `PENDING`, `SENT`, `FAILED`, `NOT_ELIGIBLE`, `Source:`, `Severity:`, `Lifecycle:`, `Evidence:`, `Time:`, `Delivery:`, and `REAL` are absent from visible output.

- [ ] Run the RED host/catalog and Android-test compilation gates.

- [ ] Delete `private fun Enum<*>.displayName()` from `EventsScreen.kt`. Render title, severity, lifecycle, delivery, evidence, and timestamp through typed catalog/formatter functions and Android resources.

- [ ] Keep event evidence bounded to facts present in the incident. Never infer theft, forced entry, or site-wide outage. Do not display raw `diagnostic`, tokens, chat IDs, command arguments, or encryption keys.

- [ ] Run GREEN host tests and the Events methods in `ProtectionAppScreenTest`, then commit with `git commit -m "feat(events): localize incident history in Thai"`.

---

## Task 6: Localize foreground and Direct Boot notifications without broadening authority

**Files:**

- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/service/DirectBootBootstrapService.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/PresentationTextCatalog.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/service/ForegroundNotificationPolicyTest.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/service/DirectBootBootstrapPolicyTest.kt`

**Interfaces:**

- `SensorService.notificationText(snapshot)` continues to consume authoritative `ProtectionSnapshot` only.
- Direct Boot content continues to consume only device-protected, non-secret recovery facts already approved by the continuity design.
- Notification title becomes installation-neutral; profile-specific body wording is allowed only if the authoritative snapshot contains that profile fact.

- [ ] Write failing policy assertions that notification title/body contain no `Motorcycle Guard`, raw state enum, unexplained English, or banned shared vehicle wording.

- [ ] Add Direct Boot assertions that Thai recovery copy does not read or reveal Telegram/SMS/TOTP/pairing/encrypted-incident data and does not claim protection resumed before the coordinator confirms it.

- [ ] Run RED:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests "*ForegroundNotificationPolicyTest" --tests "*DirectBootBootstrapPolicyTest"
```

- [ ] Move static notification title/channel copy to resources and use shared state labels for body text. Keep Android notification requirements, service lifecycle, channel IDs, pending intents, and recovery behavior unchanged.

- [ ] Run GREEN and commit with `git commit -m "feat(service): localize protection notifications"`.

---

## Task 7: Align guidance, Telegram, and SMS with the same Thai semantics

**Files:**

- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/UserGuidance.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentMessagePresentationFactory.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentMessageFormatter.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionMessagePresentationFactory.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionStateTelegramNotifier.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusFormatter.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/UserGuidanceCatalogTest.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/IncidentMessageFormatterTest.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/CrossChannelMessageConsistencyTest.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/PowerIncidentFormatterTest.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionStateTelegramNotifierTest.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusFormatterTest.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramCommandHandlerTest.kt` only where command help/error copy is user-facing.

**Interfaces:**

- Preserve Telegram pairing, mandatory authorization, TLS pinning, command routing, delivery acceptance, and `/sensitivity N` compatibility behavior.
- Preserve AES-GCM SMS eligibility/encryption and the rule that SMS never contains a map link or location.
- `IncidentMessagePresentationFactory` remains the single incident message projection; channel formatters shorten it without changing its meaning.

- [ ] Add failing tests that the same typed state/severity/lifecycle/delivery meaning appears across guidance, Telegram status, Telegram incident, and SMS, allowing channel-appropriate length.

- [ ] Add explicit regression assertions:

  - no token, chat ID, pairing code, command argument, or AES key is echoed;
  - SMS contains no latitude, longitude, map URL, or GPS wording;
  - delivery is never called successful until the recorded state is `SENT`;
  - shared messages contain no banned Vehicle-only wording;
  - Power wording never claims a building outage;
  - single-sensor evidence never claims theft or forced entry.

- [ ] Replace the old global `ระดับความไว` command wording with `ระดับการตรวจจับ` and explain that `/sensitivity N` is a compatibility command affecting supported Vehicle detectors only. Do not silently extend it to Entry angle or Power witness logic.

- [ ] Run RED:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests "*UserGuidanceCatalogTest" --tests "*IncidentMessageFormatterTest" --tests "*CrossChannelMessageConsistencyTest" --tests "*PowerIncidentFormatterTest" --tests "*ProtectionStateTelegramNotifierTest" --tests "*ProtectionStatusFormatterTest" --tests "*TelegramCommandHandlerTest"
```

- [ ] Migrate formatter literals to shared catalog semantics while keeping each channel's existing security and transport code untouched. Remove decorative emoji from normal app UI; Telegram warning markers may remain only if tests establish they are meaningful, accessible text symbols rather than the sole state indicator.

- [ ] Run GREEN and commit with `git commit -m "feat(messaging): align Thai protection terminology"`.

---

## Task 8: Add whole-app language, accessibility, and responsive-layout contracts

**Files:**

- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/ui/ThaiPresentationSourceContractTest.kt`
- Modify: `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt`
- Modify: `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionProfilesUiTest.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/theme/Type.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/theme/Theme.kt` only if tests prove current tokens fail Thai legibility/contrast; do not add dark theme.
- Modify only the reachable production Compose files identified by failing contracts; do not edit disconnected `DashboardScreen.kt` or `ui/main/MainScreen.kt`.

**Interfaces:**

- Source contracts scan only the production entry path rooted at `Navigation.kt -> ProtectionAppScreen` plus service/channel formatters. Test tags, enum identifiers, protocol commands, resource names, and developer-only logs are not user-facing and must be excluded explicitly.
- Compose tests use semantics, not pixel coordinates, for minimum targets, roles, selected state, range information, and readable labels.

- [ ] Create a failing source-contract test that rejects these patterns in reachable user-facing code:

```kotlin
val forbiddenDisplayTransforms = listOf(".name.lowercase()", ".replace('_', ' ')", "fun Enum<*>.displayName")
val forbiddenSharedVehicleTerms = listOf("ใต้เบาะ", "รอบตัวรถ", "สตาร์ทเครื่องยนต์")
val forbiddenOldLabels = listOf("ระดับความไว", "Advanced Role Mapping", "Clear history", "Review permissions")
```

The test must be precise: allow `ระดับความไว` only in an explicitly documented compatibility/migration test if still necessary, and allow `รถ` only in profile-specific Vehicle branches/resources. Do not scan dead legacy screens and then modify them to make the test pass.

- [ ] Add a resource/catalog completeness test that iterates every relevant enum and asserts a nonblank Thai display value without exposing `enum.name`.

- [ ] Add Compose coverage at 200% font scale and a small-width viewport for all three profiles. Assertions must prove:

  - required labels remain discoverable without ellipsis-only truncation;
  - buttons reflow/stack and remain at least 48 dp;
  - selectable controls expose Thai name, role, selected/checked state, and range where applicable;
  - status is not communicated by color alone;
  - opening Advanced does not duplicate live-region announcements.

- [ ] Add portrait and landscape test configurations. Keep assertions semantic and structural so tests do not become screenshot pixel locks.

- [ ] Run the focused RED/GREEN loop until the contract and Android-test compilation pass:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests "*ThaiPresentationSourceContractTest"
.\gradlew.bat --no-daemon --max-workers=1 compileDebugAndroidTestKotlin
```

- [ ] Make only evidence-driven typography/layout changes. Prefer wrapping, `FlowRow`/vertical stacking already available from Compose, and content-sized cards. Preserve the approved plain paper-light theme; use color plus text/icon/state semantics.

- [ ] Run the full host/build gate:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest compileDebugAndroidTestKotlin assembleDebug assembleDebugAndroidTest
```

Expected: PASS. Capture the complete command and final exit code.

- [ ] Commit only Task 8 files with `git commit -m "test(ui): enforce Thai responsive presentation contracts"`.

---

## Task 9: Fresh device acceptance and evidence handoff

**Files:**

- Create: `docs/testing/2026-08-23-profile-aware-thai-ux-device-acceptance.md`
- No production source changes in this task. Any discovered defect returns to the relevant prior task with a new RED test before implementation.

- [ ] Confirm the connected device and record exact identity:

```powershell
& "$env:ANDROID_HOME\platform-tools\adb.exe" devices -l
& "$env:ANDROID_HOME\platform-tools\adb.exe" shell getprop ro.product.manufacturer
& "$env:ANDROID_HOME\platform-tools\adb.exe" shell getprop ro.product.model
& "$env:ANDROID_HOME\platform-tools\adb.exe" shell wm size
& "$env:ANDROID_HOME\platform-tools\adb.exe" shell wm density
```

- [ ] Install the freshly built app/test APKs with `adb install -r`. Do not clear app data unless a test explicitly requires first-run state and the owner approves losing the device's current configuration.

- [ ] Run `ProtectionAppScreenTest` and `ProtectionProfilesUiTest` by direct instrumentation and save exact pass/fail output in the acceptance document.

- [ ] Manually verify the connected Huawei in portrait and landscape at normal and 200% font scale:

  - bottom navigation and profile picker;
  - Vehicle detection control and endpoint explanation;
  - Entry calibration, 5°/15°/30° choices, and angle-from-closed explanation;
  - Power charging/witness rows and bounded outlet wording;
  - Events loading/empty/error/detail/clear dialog;
  - Settings alert/continuity/advanced disclosures;
  - foreground notification and, only on a PIN/pattern-capable test device, the pre-unlock Direct Boot window.

- [ ] Perform TalkBack acceptance for every primary action, profile card, slider/range, disclosure, dialog, live status, and error/retry path. Record duplicate announcements, missing roles/states, clipped Thai text, and focus-order problems as failures.

- [ ] Validate real channel wording only with authorized test destinations:

  - Telegram `/status` and one authorized incident reflect recorded facts and do not expose secrets;
  - SMS fallback is tested only if eligibility/configuration already exists, remains encrypted, and contains no location;
  - distinguish message formatting from accepted transport delivery.

- [ ] Cover at least one small-width configuration in addition to the connected Huawei. Emulator configuration is acceptable for layout only; it does not prove sensor, Direct Boot, Telegram, or SMS behavior on another physical model.

- [ ] Restore any temporary font scale, orientation, or display-size changes. Do not leave mock incidents, changed alert destinations, or revealed secret UI behind.

- [ ] Record a matrix with columns: device/configuration, profile, flow, expected Thai wording, observed result, screenshot/log path, and PASS/FAIL. State limitations explicitly, including unsupported sensors and any unavailable pre-unlock test window.

- [ ] Commit the evidence document only after all claims in it are backed by fresh output:

```powershell
git add docs/testing/2026-08-23-profile-aware-thai-ux-device-acceptance.md
git commit -m "docs(test): record Thai UX device acceptance"
```

---

## Final Review Gates

- [ ] Re-read the approved spec sections 2–12 and map each acceptance criterion to a passing automated test or a recorded device row.
- [ ] Run `git status --short` and `git diff --check`. Verify all pre-existing unrelated files remain present and untouched.
- [ ] Run the final one-invocation gate:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest compileDebugAndroidTestKotlin assembleDebug assembleDebugAndroidTest
```

- [ ] Review changed production files for secret disclosure, raw enum rendering, vehicle-only shared wording, fake units, misleading delivery success, and duplicate live-region announcements.
- [ ] Confirm no Authenticator/QR/TOTP or Demo Mode UI returned and no legacy dashboard became reachable.
- [ ] Report separately:

  - source files changed;
  - tests added/updated;
  - exact host/build commands and results;
  - exact APK/install/instrumentation evidence;
  - device/configuration matrix actually completed;
  - remaining hardware, notification, Telegram, SMS, Direct Boot, and cross-device risks.

## Agent Handoff Prompt

Use this exact prompt when assigning implementation:

> Implement `docs/superpowers/plans/2026-08-23-profile-aware-thai-ux-implementation.md` from the approved baseline in `D:\security\.worktrees\continuity-recovery-tdd\MotorcycleAntiTheftSensor`. First read `D:\security\AGENTS.md`, `AI_WORKFLOW.md`, the approved spec, and the entire plan. Use `superpowers:subagent-driven-development` task-by-task, preserve all unrelated dirty/untracked files, use scoped TDD, run one Gradle invocation at a time with Android Studio JBR and `--no-daemon --max-workers=1`, stage exact paths only, and stop at every task commit/review checkpoint. Do not claim device, TalkBack, sensor, notification, Telegram, SMS, or Direct Boot acceptance without fresh evidence.
