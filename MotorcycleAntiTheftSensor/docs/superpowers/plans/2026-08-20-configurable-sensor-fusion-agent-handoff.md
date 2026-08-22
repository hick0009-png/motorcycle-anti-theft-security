# Configurable Sensor Fusion Program Handoff

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this program task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver configurable real sensor listeners, per-group and per-source Settings, adaptive calibration/fusion, and human-readable consistent alerts without disturbing unrelated work already present in the dirty checkout.

**Architecture:** `ProtectionCoordinator` remains authoritative. Configuration flows from encrypted persistence and UI through `ProtectionRuntime` into a `SensorCapabilityController`; typed observations return through calibration, normalization, fusion, incidents, and one presentation layer shared by UI, notifications, Events, Telegram, and SMS.

**Tech Stack:** Kotlin 2.3.20, Android SDK 36/minSdk 24, Jetpack Compose Material 3, coroutines/StateFlow, EncryptedSharedPreferences, JUnit 4, AndroidX instrumented tests.

**Spec:** `docs/superpowers/specs/2026-08-20-configurable-sensor-fusion-design.md`

**Planning boundary:** Creating and reviewing this handoff changes documentation only. No production/test source is authorized until the owner separately starts an execution session from the approved plans.

## Global Constraints

- Read the approved spec completely before editing production code.
- Read `D:\security\AGENTS.md`; `AI_WORKFLOW.md` was required by repository instructions but was not present during planning, so re-check before execution and stop if a newly restored file changes this workflow.
- Preserve the dirty worktree. Never reset, checkout, clean, move, overwrite, broadly stage, or commit unrelated files.
- Before each task run `git status --short`, inspect the exact target files, and compare untracked additions directly because plain `git diff` omits them.
- Do not create an isolated worktree from `HEAD` until the owner decides how the current uncommitted sensor/protection changes become the execution baseline. The plan was mapped against the current dirty source, not just the last commit.
- The current checkout is not safe for task commits: many planned target files already contain user changes. Before Runtime Task 1, the owner must approve an exact baseline commit/checkpoint or a clean worktree created from that approved content. If a task target is still dirty before the task starts, stop; an exact-path `git add` would still capture pre-existing edits.
- Do not introduce a sensor, serialization, navigation, or testing dependency. Reuse Android `SensorManager`, existing Kotlin serialization plugin/`org.json` availability, coroutines, and existing Compose/test libraries.
- Android support remains `minSdk = 24`, `targetSdk = 36`, and `compileSdk = 36`.
- `ProtectionCoordinator` is the only protection-state owner. Settings and listeners never publish protection success independently.
- A source is `HEALTHY` only after successful listener/trigger registration plus valid fresh readiness/sample evidence.
- Arming requires at least one effective ready `PRIMARY`; unavailable `SUPPORTING` sources degrade; `OFF` sources do not degrade.
- Every arm performs a 10-second calibration/readiness phase; live changes recalibrate only affected groups.
- Significant Motion uses `requestTriggerSensor`/`cancelTriggerSensor` and is re-requested after every trigger.
- Do not use deprecated `Sensor.TYPE_ORIENTATION`; derive orientation from rotation-vector sources.
- Do not expose unrestricted 250 Hz. Use bounded Battery Saver/Balanced/Responsive requested sampling policies and measure delivered cadence on device.
- No sensor callback may perform disk, JSON, network, Telegram, SMS, or blocking main-thread work.
- Sensor listeners never send alerts directly. All evidence uses the existing incident/delivery path.
- The neutral incident guidance is exactly `ตรวจสอบสถานการณ์และดำเนินการตามความเหมาะสม`; do not add camera/person/authority/confrontation-specific advice without a new approved spec change.
- SMS never includes location. Preserve pairing, TLS pinning, secret redaction, delivery truth, Demo isolation, and existing SMS eligibility/encryption boundaries.
- Preserve the existing Telegram `/sensitivity N` command for one compatibility version by applying `N` atomically to both Movement and Light group sensitivity. It must use the same configuration policy/repository/apply path as Settings, produce a derived `CUSTOM` display when appropriate, and never write the legacy preference directly.
- Source/build/install evidence and real-device sensor acceptance are separate. Never claim a sensor or message flow works on a device from unit tests or assembly alone.
- On Windows error 1455, avoid overlapping Gradle/ADB and use Android Studio JBR, `--no-daemon`, `--max-workers=1`, in-process Kotlin compilation, and the existing low-memory Gradle settings.

---

## Program Decomposition

Implementation clarification: the approved conceptual `CUSTOM` preset is represented as a derived `SensorPresetDisplay.CUSTOM`, while persistence keeps the last named `basePreset`. This preserves the approved Custom UI and makes Reset deterministic; it does not create a fourth preset bundle or change user-visible behavior.

This spec contains three reviewable subsystems. Execute them through these detailed plans:

1. `docs/superpowers/plans/2026-08-20-configurable-sensor-runtime-fusion.md`
   - domain configuration and policy;
   - Android hardware catalog;
   - continuous/trigger adapters;
   - calibration generations;
   - adaptive sampling controller;
   - observation/fusion/deduplication;
   - runtime/coordinator arm eligibility and live apply.
2. `docs/superpowers/plans/2026-08-20-configurable-sensor-settings-persistence.md`
   - versioned encrypted persistence and legacy sensitivity migration;
   - Settings gateway/ViewModel state;
   - group overview and advanced per-source controls;
   - persistent operation/degradation feedback;
   - recovery and accessibility tests.
3. `docs/superpowers/plans/2026-08-20-human-readable-alerts-guidance.md`
   - localized presentation models and labels;
   - neutral incident guidance and actionable system remediation;
   - UI/notification/Events/Telegram/SMS formatters;
   - cross-channel semantic consistency, deduplication, and delivery truth.

## Dependency Order

```text
Shared Runtime 1 / Settings 1 (domain types/policy; execute once)
    ├── Settings Task 2 (codec/repository)
    ├── Runtime Tasks 2-7 (catalog/adapters/calibration/controller)
    └── Message Task 1 (localized labels may begin after source enums stabilize)

Settings Task 2 + Runtime Tasks 2-7
    └── Shared Runtime 8 / Message 2 (source-aware evidence and schema-v5 chronology; execute once)

Shared Runtime 8 / Message 2
    └── Shared Runtime 9 / Settings 3 (authoritative persisted apply and arm contract)

Shared Runtime 9 / Settings 3
    └── Runtime Task 10 (runtime graph)

Message Task 1 + Runtime Task 10
    ├── Settings Tasks 4-7 and 9 (ViewModel/UI/live apply/recovery; reuse shared Thai labels)
    └── Message Tasks 3-5 (presentation factories, formatters, and delivery truth)

Settings Tasks 4-7 and 9 + completed Message Tasks 1, 3, and 4
    └── Settings Task 8 (Telegram `/sensitivity N` migration)

Settings Tasks 4-9 + completed Message Tasks 1-5
    └── Message Tasks 6-8 (final shared UI/recovery/cross-channel integration)

All subsystem focused gates
    └── Program full host gate
        └── dedicated-device install and acceptance matrix
```

Do not run production-editing agents concurrently in the same checkout. Parallel work is allowed only in separate verified worktrees that share the exact approved baseline and edit disjoint files. Parent-agent integration and all full gates remain mandatory.

## Agent Task Board

| Delivery lane | Plan tasks | May start after | Review checkpoint |
| --- | --- | --- | --- |
| Domain/runtime agent | Shared Runtime 1 / Settings 1, then Runtime 2-7 | Baseline gate | Domain types, catalog, adapters, calibration, and controller focused tests GREEN |
| Fusion/coordinator agent | Shared Runtime 8 / Message 2, then shared Runtime 9 / Settings 3 and Runtime 10 | Runtime 1-7 and Settings 2 | Arm/live-apply integration GREEN; no scanner-only or fixed-sensitivity runtime caller remains |
| Persistence agent | Settings 2 | Shared domain task | Schema/migration/durable-write tests GREEN; no secret store erased by config recovery |
| Settings UI agent | Settings 4-9 | Settings 1-3 and Runtime 9 contract | Host ViewModel tests and dedicated-device Compose tests GREEN |
| Presentation agent | Message 1, 3-4 | Shared domain and incident tasks | Typed catalog and channel formatter tests GREEN; exact neutral guidance retained |
| Delivery/UI agent | Message 5-8 | Message 1-4 and Runtime 8 evidence types | Live SMS truth path, persistent UI, Events, recovery, and cross-channel tests GREEN |
| Parent integrator | All subsystem gates | All lanes | Full host, instrumented, install, and controlled device evidence recorded separately |

The named lanes are ownership boundaries, not permission to edit concurrently in this dirty checkout. Each executing agent must stop at its review checkpoint, return the exact diff/test evidence, and wait for parent integration before the next overlapping lane begins.

Overlap order is mandatory: Message Task 1 owns exhaustive domain labels before Settings Task 4; Settings Tasks 4-9 establish the sensor Settings UI before Message Tasks 6-8 consolidate shared persistent cards/Events/Snackbar; Telegram Settings Task 8 integrates after Message Tasks 1, 3, and 4 have established the shared presentation/status formatters. Do not run these overlapping UI/Telegram tasks in parallel even in separate worktrees unless the parent pre-assigns a deterministic merge order.

## Spec-to-Plan Coverage

| Approved behavior | Owning tasks |
| --- | --- |
| Per-source `OFF` / `SUPPORTING` / `PRIMARY` and Balanced defaults | Shared Runtime 1 / Settings 1; Settings 5-7 |
| Dynamic catalog and honest unavailable/effective state | Runtime 2, 7, 10; Settings 2, 5, 6 |
| Ten-second arm calibration and affected-group live recalibration | Runtime 4, 7, 9; Settings 4, 7 |
| Adaptive watch/confirm sampling and Significant Motion re-request | Runtime 3, 5, 7 |
| Baseline-relative normalization plus absolute safety limits | Runtime 1, 4-6 |
| Ready-primary arm requirement and supporting-only degradation | Runtime 7, 9; Settings 7; Message 1, 4 |
| Main group controls, Advanced per-source controls, sensitivity 1-10, bounded overrides, Reset | Settings 2, 4-6 |
| Durable versioned migration and desired/effective separation | Settings 2-5, 8-9 |
| One incident thread with role-aware evidence and source-family deduplication | Shared Runtime 8 / Message 2 |
| Human-readable Thai contract across all surfaces | Message 1-8 |
| Neutral incident guidance and no SMS location | Message 1, 3-5, 8 |
| Persistent critical/degraded/apply feedback and accessible history | Settings 4, 6, 9; Message 6-8 |
| Host, install, and real-device evidence kept distinct | Every subsystem acceptance; Program Full Gate |

## Baseline Gate

- [ ] **Step 1: Capture the exact execution baseline**

Run from `D:\security`:

```powershell
git branch --show-current
git rev-parse HEAD
git status --short
git log -5 --oneline --decorate
```

Expected: branch and commit are recorded; dirty changes remain present and untouched.

Blocking outcome: do not begin or commit a production task until every target file for that task is clean relative to an owner-approved execution baseline, or its pre-existing diff is explicitly included in that baseline. Documenting the current diff is not authorization to absorb it into a feature commit.

- [ ] **Step 2: Re-check workflow and plan assumptions**

```powershell
rg --files -g "AGENTS.md" -g "AI_WORKFLOW.md"
rg -n "SensorKind|SensorHealthState|applySensitivity|SensorScanner|IncidentMessageFormatter|ProtectionStatusFormatter" MotorcycleAntiTheftSensor/app/src/main MotorcycleAntiTheftSensor/app/src/test
```

Expected: any drift from the interfaces named in the three plans is resolved in the plan before production edits.

- [ ] **Step 3: Run a single non-overlapping host baseline**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
Set-Location D:\security\MotorcycleAntiTheftSensor
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon --max-workers=1 --console=plain
```

Expected: record exact pass/fail totals. If unrelated pre-existing failures exist, record them with file/test/error evidence; do not disable or rewrite them to make this program appear green.

- [ ] **Step 4: Commit only a baseline note if failures must be carried**

Create a project-local status note only when the baseline is not green. Stage that exact note, never `git add .`.

```powershell
git add -- docs/superpowers/status/2026-08-20-configurable-sensor-fusion-execution-baseline.md
git commit -m "docs: record sensor fusion execution baseline"
```

Expected: no unrelated file is staged or committed.

## Review Gates

Each task in the subsystem plans requires:

1. RED test is run and its intended failure is recorded in the task evidence; do not commit a deliberately failing task boundary.
2. Minimal GREEN production change.
3. Focused test pass.
4. `git diff --check` on the task files.
5. Reviewer checks spec compliance and unrelated-diff exclusion.
6. Exact-path commit.

After each subsystem:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
Set-Location D:\security\MotorcycleAntiTheftSensor
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks --no-daemon --max-workers=1 --console=plain
.\gradlew.bat :app:assembleDebug --rerun-tasks --no-daemon --max-workers=1 --console=plain
```

Expected: zero failures attributable to the subsystem; APK assembly succeeds. This is not device acceptance.

## Program Full Gate

- [ ] **Step 1: Run unit and assembly gates from fresh Gradle invocations**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
Set-Location D:\security\MotorcycleAntiTheftSensor
.\gradlew.bat --stop
.\gradlew.bat :app:testDebugUnitTest --no-daemon --max-workers=1 --console=plain
.\gradlew.bat :app:assembleDebug --no-daemon --max-workers=1 --console=plain
```

Expected: both commands exit 0; report test counts and APK path.

- [ ] **Step 2: Run instrumented tests on a dedicated test state**

Do not run against an owner-configured app state without backup/approval.

```powershell
$adb='C:\Users\ASUS\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb devices -l
$deviceSerial=Read-Host 'Enter the dedicated test-device serial exactly as shown'
$env:ANDROID_SERIAL=$deviceSerial
& $adb -s $deviceSerial get-state
.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon --max-workers=1 --console=plain
```

Expected: Compose and Android sensor contract tests pass; capture exact device serial/model and test totals.

- [ ] **Step 3: Scan user-visible outputs and secrets**

```powershell
rg -n "update status card only|accelerometer_magnitude|\.name\}|diagnostic\}|0\.0,0\.0|T[O]DO|T[B]D" app/src/main app/src/test
rg -n -i "bot[ _-]?token|totp|secret|sms.*key|api[_-]?key" app/src/main app/src/test
```

Expected: no raw internal copy or secret value reaches a formatter/UI/log; legitimate identifier names are manually classified rather than blindly deleted.

- [ ] **Step 4: Install the exact verified APK**

```powershell
$adb='C:\Users\ASUS\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb devices -l
$deviceSerial=Read-Host 'Enter the target serial exactly as shown by adb devices -l'
& $adb -s $deviceSerial get-state
& $adb -s $deviceSerial install -r app\build\outputs\apk\debug\app-debug.apk
& $adb -s $deviceSerial shell dumpsys package com.example.motorcycleantitheftsensor
```

Expected: install succeeds and `lastUpdateTime` corresponds to this run. Installation does not establish sensor/message acceptance.

## Real-Device Acceptance Matrix

Run controlled tests on the motorcycle phone and verify owner-phone Telegram separately. Never include bot tokens, pairing codes, SMS keys, or private location data in screenshots/logs.

- [ ] Catalog every supported source with Android type, vendor, reporting mode, wake-up flag, and availability.
- [ ] Verify each `OFF` source is unregistered and does not degrade protection.
- [ ] Verify each available `SUPPORTING` source cannot open an incident alone.
- [ ] Verify each available `PRIMARY` source can open exactly one bounded incident under controlled stimulus.
- [ ] Verify 10-second arm calibration and affected-group-only live recalibration.
- [ ] Verify Significant Motion re-request after trigger and adaptive watch/confirm/watch transitions.
- [ ] Verify requested versus delivered sampling cadence and record battery/temperature for each profile.
- [ ] Verify a multi-sensor action produces one incident thread, not duplicate alerts.
- [ ] Verify process restart and reboot re-enter calibration before healthy armed state.
- [ ] Verify Protection, notification, Events, Telegram, and eligible SMS preserve severity, state, time, and incident ID.
- [ ] Verify SMS contains no location.
- [ ] Verify neutral guidance is exactly `ตรวจสอบสถานการณ์และดำเนินการตามความเหมาะสม`.
- [ ] Verify Thai text with large font and TalkBack; record any truncation/reading-order defect.

Hardware absent from the target phone stays `UNAVAILABLE` and unaccepted until tested on a device that provides it.

## Expected Outcome

- Settings controls real listeners rather than a scanner-only inventory.
- Every displayed source has desired configuration, catalog availability, registration evidence, calibration/readiness, freshness, and incident-evidence traceability.
- The system never claims armed protection without one ready primary.
- Missing supporting sensors degrade honestly; disabled sensors do not.
- Sensor fusion prevents duplicate incident spam.
- Alerts are Thai, readable, neutral in incident advice, consistent across channels, and free of internal diagnostics/secrets.
- Verification evidence remains bounded: host green, install evidence, and device acceptance are reported separately.
