# Arm UI Flicker Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the visible Protection-screen flicker and excessive Compose work during `ARMING` and armed sensor sampling without reducing raw sensor frequency, intrusion detection sensitivity, protection-state responsiveness, or Telegram control behavior.

**Architecture:** Keep `ProtectionCoordinator.snapshot` authoritative and keep every raw observation flowing through the detection engine. Reuse the existing semantic-aware `SnapshotProjectionGate` only at the `ProtectionViewModel` presentation boundary so volatile sensor timestamps/readings reach Compose at most once per second while state, health, battery, incident, permission, delivery, and connectivity changes remain immediate. Do not modify the coordinator, detector rates, ticker, Navigation actions, service, or GPS path unless controlled before/after evidence disproves this hypothesis and a separately approved plan replaces this one.

**Tech Stack:** Kotlin, Android, Jetpack Compose, StateFlow/Flow, Kotlin coroutines, JUnit 4, kotlinx-coroutines-test, Gradle wrapper, ADB `dumpsys gfxinfo`.

## Global Constraints

- This is a UI-presentation performance remediation, not a protection-engine redesign.
- `VibrationDetector` uses `SensorManager.SENSOR_DELAY_GAME`; `LightIntrusionDetector` uses `SensorManager.SENSOR_DELAY_NORMAL`. Never describe both as 50 Hz.
- Do not change `VibrationDetector`, `LightIntrusionDetector`, `AudioPeakDetector`, `PowerThermalMonitor`, `LocationObservationProvider`, `SensorObservationProcessor`, thresholds, debounce windows, sampling modes, or sensitivity mapping.
- Do not throttle, sample, debounce, or drop calls to `ProtectionCoordinator.recordSensorSample()` in this plan.
- Do not modify `ProtectionCoordinator.kt`, `Navigation.kt`, `ProtectionRuntimeGraph.kt`, `SensorService.kt`, GPS Live Pursuit, Telegram commands, notification behavior, persistence behavior, SMS, calls, incidents, or protection-state transitions.
- Exact Telegram behavior remains unchanged: paired owner `/arm` and `/disarm` work; argument-bearing `/disarm 123456` remains rejected.
- The arming countdown must continue updating once per second while state is `ARMING`.
- Do not change the existing `ticker` loop in `ProtectionViewModel`. Outside `ARMING`, `ProtectionUiState` equality already prevents the unchanged `armingSecondsRemaining = null` projection from becoming a new visible UI state; the 1 Hz ticker is not the confirmed flicker cause.
- Do not wrap `ProtectionAppActions` in `remember` in this plan. Action-object allocation is secondary and cannot prevent recomposition driven by changing `uiState`.
- Reuse `SnapshotProjectionGate`; do not create a second throttle abstraction or add a dependency.
- `SnapshotProjectionGate` must continue publishing semantic changes immediately. Do not add `latestReading` or `lastSampleAtMs` to `ProjectionKey`, because they are the volatile fields intentionally being coalesced.
- Do not expose or log bot tokens, Chat IDs, pairing codes, Telegram arguments, encrypted preferences, TOTP remnants, GPS coordinates, or other secrets in diagnostics/evidence.
- The checkout is a mixed dirty worktree. Never run `git reset`, `git clean`, `git checkout --`, `git stash`, or `git add .`; never revert unrelated Authenticator, GPS, Settings, Telegram, or user changes.
- `ProtectionViewModel.kt` and `ProtectionViewModelTest.kt` already contain substantial unrelated working-tree edits. Read their complete current contents and preserve every pre-existing hunk.
- Do not commit whole modified files from the shared dirty checkout. A commit is allowed only in a separate clean worktree where every staged hunk belongs to this plan.
- Do not run performance captures while screen recording; recording changes rendering load. A separate visual recording may be collected after quantitative captures if the user requests it.
- Use the Android Studio JBR and low-concurrency Gradle baseline:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' <tasks>
```

---

## Decision Record

### Selected: Presentation-boundary projection

Filter `coordinator.snapshot` inside `ProtectionViewModel` through the existing `SnapshotProjectionGate(1_000L)` before it enters the five-flow `combine`. This leaves raw detection and the authoritative snapshot untouched while bounding Compose-visible sensor churn to approximately 1 Hz. The gate's semantic key already bypasses the interval for protection state, service/polling/reachability, permission blockers, sensor health state/detail, degradation reasons, battery/thermal values, incidents, and delivery changes.

### Rejected as the first fix: Core throttling

Do not add a timestamp map or early return to `ProtectionCoordinator.recordSensorSample()`. The authoritative snapshot is consumed by UI, service notification/persistence, freshness evaluation, Telegram formatting, and Live Pursuit coordination. Core throttling would change shared semantics, introduce concurrency/reset questions, and make a UI bug capable of affecting protection behavior.

### Rejected as root-cause fixes: Ticker gating and remembered actions

The ticker changes `currentTimeMs` at 1 Hz and is required for the arming countdown. `ProtectionAppActions` recreation may allocate objects, but it cannot explain sensor-rate state changes or stop state-driven recomposition. Neither change is authorized by this plan. If the selected projection fix fails the quantitative gate, stop and write a new evidence-based plan instead of stacking these guesses.

---

## Verified Source Trace Behind the Hypothesis

1. `VibrationDetector.onSensorChanged()` creates a `SensorObservation` for every valid accelerometer callback registered with `SENSOR_DELAY_GAME`.
2. `AndroidProtectionRuntime.handleObservation()` calls `sensorSampleRecorder(...)` for every usable observation before incident-decision processing.
3. `ProtectionRuntimeGraph` wires that recorder directly to `coordinator.recordSensorSample(...)`.
4. `ProtectionCoordinator.recordSensorSample()` copies `sensorHealth`, updates volatile `lastSampleAtMs/latestReading`, and `updateSnapshot()` increments `revision` every time.
5. `ProtectionViewModel.uiState` currently combines raw `coordinator.snapshot` directly, so each materially different sensor timestamp/reading produces a different `ProtectionUiState` and reaches Compose.
6. `ProtectionScreen` renders `latestReading` and `lastSampleAtMs`, so those rapid changes can invalidate visible sensor cards.
7. Existing `SnapshotProjectionGate` already coalesces volatile sample timestamps/readings while allowing semantic changes immediately; `SensorService` uses it successfully for notification and persistence projection.

Current read-only device evidence before this plan was written showed 28,773 cumulative frames with 21,846 janky frames (75.93%) on Huawei INE-LX2. This proves substantial historical jank but is not an arm-specific controlled baseline. Task 1 must collect a reset, controlled baseline before production edits.

---

## File Responsibility Map

- Modify `app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt` — add the RED regression proving rapid authoritative samples are coalesced only before UI state, plus immediate semantic-state coverage.
- Modify `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt` — add the presentation-only `SnapshotProjectionGate` and feed its filtered Flow into `combine`.
- Verify without modifying `app/src/main/java/com/example/motorcycleantitheftsensor/protection/SnapshotProjectionGate.kt` — reuse its existing synchronized semantic projection policy.
- Verify without modifying `app/src/test/java/com/example/motorcycleantitheftsensor/protection/SnapshotProjectionGateTest.kt` — retain the existing timestamp-coalescing/state-bypass coverage.
- Create `docs/superpowers/status/2026-08-15-arm-ui-flicker-remediation-evidence.md` — record before/after host, APK, device, frame, and visual acceptance without secrets.

No other production or test file is in scope.

---

### Task 1: Capture a Controlled Pre-Fix Baseline

**Files:**
- Read only: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/VibrationDetector.kt`
- Read only: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/LightIntrusionDetector.kt`
- Read only: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt`
- Read only: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt`
- Read only: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/SnapshotProjectionGate.kt`
- Read only: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt`
- Create after measurements: `docs/superpowers/status/2026-08-15-arm-ui-flicker-remediation-evidence.md`

**Interfaces:**
- Consumes: current installed debug application and `adb dumpsys gfxinfo`.
- Produces: two controlled pre-fix measurements, one local-arm and one Telegram-arm, that later runs can compare against.

- [ ] **Step 1: Record repository and overlapping-file state**

Run:

```powershell
git log -1 --oneline
git status --short
git diff -- app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt
rg -n "SENSOR_DELAY_GAME|SENSOR_DELAY_NORMAL|recordSensorSample|SnapshotProjectionGate|coordinator.snapshot" app/src/main/java/com/example/motorcycleantitheftsensor
```

Required:

- Save the current commit ID and state that this is a mixed working tree.
- Confirm `VibrationDetector` is GAME and `LightIntrusionDetector` is NORMAL.
- Confirm the ViewModel directly combines `coordinator.snapshot` before editing.
- Preserve all existing ViewModel/ViewModelTest diffs.

- [ ] **Step 2: Verify exactly one authorized Huawei target**

Run:

```powershell
$adb='C:\Users\ASUS\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb devices -l
& $adb shell pidof com.example.motorcycleantitheftsensor
```

Required: exactly one authorized Huawei INE-LX2. If more than one device exists, use `-s <exact-serial>` for every remaining ADB command. Redact the serial in evidence.

- [ ] **Step 3: Capture the local-arm baseline under controlled conditions**

Prepare the phone:

1. Keep the motorcycle/phone stationary.
2. Put the app in the foreground on the Protection destination.
3. Scroll to the top so the protection state, countdown, and action button are visible.
4. Ensure the app is disarmed and no popup/snackbar is currently animating.
5. Do not run screen recording.

Reset graphics counters:

```powershell
& $adb shell dumpsys gfxinfo com.example.motorcycleantitheftsensor reset | Out-Null
```

Immediately tap the local Arm button once. Leave the UI untouched for 15 seconds: 10 seconds `ARMING`, followed by 5 seconds armed. Then run:

```powershell
$localBefore = & $adb shell dumpsys gfxinfo com.example.motorcycleantitheftsensor
$localBefore | Select-String -Pattern 'Total frames rendered|Janky frames|50th percentile|90th percentile|95th percentile|99th percentile|Number Missed Vsync|Number High input latency|Number Slow UI thread|Number Slow issue draw commands'
```

Record every selected line plus whether visible flicker occurred during `ARMING`, after reaching armed state, or both. Disarm after capture.

- [ ] **Step 4: Capture the Telegram-arm baseline under the same conditions**

Return to the same foreground screen/scroll position and ensure disarmed. Reset counters:

```powershell
& $adb shell dumpsys gfxinfo com.example.motorcycleantitheftsensor reset | Out-Null
```

From the already-paired owner account, send exact `/arm` once. Leave the UI untouched for 15 seconds, then run:

```powershell
$telegramBefore = & $adb shell dumpsys gfxinfo com.example.motorcycleantitheftsensor
$telegramBefore | Select-String -Pattern 'Total frames rendered|Janky frames|50th percentile|90th percentile|95th percentile|99th percentile|Number Missed Vsync|Number High input latency|Number Slow UI thread|Number Slow issue draw commands'
```

Record the same fields and visible result. Do not record the Chat ID or any other Telegram data. Disarm after capture.

- [ ] **Step 5: Apply the diagnosis stop gate**

Proceed to Task 2 only if at least one controlled run reproduces visible flicker or shows sustained jank while sensor readings/timestamps update rapidly. If neither run reproduces the symptom:

- do not edit production code;
- record both clean runs;
- capture a fresh report when the user can reproduce the symptom;
- end the task as `NOT REPRODUCED`, not `FIXED`.

- [ ] **Step 6: Create the evidence document without secrets**

Create `docs/superpowers/status/2026-08-15-arm-ui-flicker-remediation-evidence.md` with these exact headings:

```markdown
# Arm UI Flicker Remediation Evidence

## Scope and Working-Tree Boundary
## Pre-Fix Source Trace
## Pre-Fix Local Arm Gfxinfo
## Pre-Fix Telegram Arm Gfxinfo
## RED Test Evidence
## GREEN Test Evidence
## Full Host and Build Evidence
## APK Artifact
## Post-Fix Local Arm Gfxinfo
## Post-Fix Telegram Arm Gfxinfo
## Functional Device Acceptance
## Remaining Risks
```

Populate the first four sections now. Use bounded claims: historical cumulative figures are context, while the reset 15-second runs are the actual baseline.

---

### Task 2: Add the UI-Emission Regression Tests

**Files:**
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt`
- Verify only: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/SnapshotProjectionGateTest.kt`

**Interfaces:**
- Consumes: `ProtectionViewModel`, `ProtectionCoordinator.recordSensorSample(...)`, existing `fakeCoordinator(...)`, `FakeIncidentRepository`, `FakeProtectionSettingsGateway`, and `SnapshotProjectionGate` behavior.
- Produces: a failing regression showing raw coordinator revisions remain complete while Compose-visible UI emissions are bounded, plus a safety assertion that `ALERT_ACTIVE` bypasses the interval.

- [ ] **Step 1: Add the RED rapid-sample test exactly**

Add this test inside `ProtectionViewModelTest`:

```kotlin
@Test
fun rapidSensorSamplesAreRateLimitedBeforeUiStateButCoordinatorKeepsEveryRevision() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    var wallClockMs = 1_000L
    val coordinator = fakeCoordinator(ProtectionState.ARMED_HEALTHY)
    val viewModel = ProtectionViewModel(
        coordinator = coordinator,
        incidents = FakeIncidentRepository(emptyList()),
        settings = FakeProtectionSettingsGateway(),
        nowMs = { wallClockMs },
        ticker = emptyFlow(),
        dispatcher = dispatcher,
        callbackDispatcher = dispatcher,
    )
    advanceUntilIdle()

    val emissions = mutableListOf<ProtectionUiState>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        viewModel.uiState.collect(emissions::add)
    }
    runCurrent()
    emissions.clear()
    val initialRevision = coordinator.snapshot.value.revision

    repeat(100) { index ->
        wallClockMs = 1_000L + index * 5L
        coordinator.recordSensorSample(
            kind = SensorKind.VIBRATION,
            atMs = wallClockMs,
            detail = "accelerometer_magnitude",
            normalizedValue = 9.8 + index,
        )
        runCurrent()
    }

    assertEquals(initialRevision + 100L, coordinator.snapshot.value.revision)
    assertEquals(1, emissions.size)

    wallClockMs = 2_001L
    coordinator.recordSensorSample(
        kind = SensorKind.VIBRATION,
        atMs = wallClockMs,
        detail = "accelerometer_magnitude",
        normalizedValue = 777.0,
    )
    runCurrent()

    assertEquals(initialRevision + 101L, coordinator.snapshot.value.revision)
    assertEquals(2, emissions.size)
    assertEquals(
        777.0,
        requireNotNull(
            viewModel.uiState.value.protection.sensorHealth[SensorKind.VIBRATION]
                ?.latestReading
                ?.value,
        ),
        0.0,
    )
}
```

This test deliberately asserts both boundaries:

- all 101 raw samples still advance the authoritative snapshot revision;
- only the first semantic sensor-health addition and the first sample after the 1,000 ms interval reach UI state.

- [ ] **Step 2: Add the immediate semantic-state safety test exactly**

Add this second test:

```kotlin
@Test
fun alertStateBypassesUiSensorProjectionInterval() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    var wallClockMs = 1_000L
    val coordinator = fakeCoordinator(ProtectionState.ARMED_HEALTHY)
    val viewModel = ProtectionViewModel(
        coordinator = coordinator,
        incidents = FakeIncidentRepository(emptyList()),
        settings = FakeProtectionSettingsGateway(),
        nowMs = { wallClockMs },
        ticker = emptyFlow(),
        dispatcher = dispatcher,
        callbackDispatcher = dispatcher,
    )
    advanceUntilIdle()

    val emissions = mutableListOf<ProtectionUiState>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        viewModel.uiState.collect(emissions::add)
    }
    runCurrent()

    coordinator.recordSensorSample(
        kind = SensorKind.VIBRATION,
        atMs = wallClockMs,
        detail = "accelerometer_magnitude",
        normalizedValue = 9.8,
    )
    runCurrent()
    emissions.clear()

    wallClockMs = 1_100L
    coordinator.recordIncident(incident(id = "projection-alert", updatedAtMs = wallClockMs))
    runCurrent()

    assertEquals(1, emissions.size)
    assertEquals(ProtectionState.ALERT_ACTIVE, emissions.single().protection.state)
    assertEquals("projection-alert", emissions.single().protection.lastIncident?.id)
}
```

Do not weaken `assertEquals(1, emissions.size)` into a range and do not insert delays to make the test pass.

- [ ] **Step 3: Run the tests and record the expected RED**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*ProtectionViewModelTest' --tests '*SnapshotProjectionGateTest'
```

Expected before production edit:

- `rapidSensorSamplesAreRateLimitedBeforeUiStateButCoordinatorKeepsEveryRevision` fails because the current raw `coordinator.snapshot` path produces approximately 100 UI emissions instead of 1.
- `alertStateBypassesUiSensorProjectionInterval` passes or fails only for a real safety regression; investigate any unrelated failure before continuing.
- `SnapshotProjectionGateTest` remains green.

Record the exact failing assertion and XML suite/test/failure/error/skipped totals in `## RED Test Evidence`. A RED caused by compilation, wrong test paths, missing fixtures, or unrelated tests does not authorize production edits; correct the test first.

---

### Task 3: Add Presentation-Only Snapshot Projection

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt`
- Verify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/SnapshotProjectionGateTest.kt`

**Interfaces:**
- Consumes: `ProtectionCoordinator.snapshot: StateFlow<ProtectionSnapshot>`, `SnapshotProjectionGate.shouldProject(snapshot, nowMs): Boolean`, and injected `nowMs: () -> Long`.
- Produces: `projectedSnapshots: Flow<ProtectionSnapshot>` feeding `ProtectionUiState`; default minimum interval is exactly `1_000L`.

- [ ] **Step 1: Add only the required imports**

Add these imports to `ProtectionViewModel.kt`:

```kotlin
import com.example.motorcycleantitheftsensor.protection.SnapshotProjectionGate
import kotlinx.coroutines.flow.filter
```

Do not add Compose imports, clocks, mutexes, atomics, channels, debounce, sample, conflate, or a new dependency.

- [ ] **Step 2: Inject a fresh gate per ViewModel**

Add this constructor parameter immediately after `ticker` and before `dispatcher`:

```kotlin
private val snapshotProjectionGate: SnapshotProjectionGate =
    SnapshotProjectionGate(UI_SNAPSHOT_PROJECTION_INTERVAL_MS),
```

The gate must be instance-owned. Do not place it in a companion object, singleton, runtime graph, or coordinator.

- [ ] **Step 3: Create the projected snapshot Flow**

Immediately after `currentTimeMs`, add:

```kotlin
private val projectedSnapshots = coordinator.snapshot.filter { snapshot ->
    snapshotProjectionGate.shouldProject(snapshot, nowMs())
}
```

This is a cold presentation Flow collected by the existing eagerly started `uiState`. Do not call `stateIn` on it separately and do not introduce a second coroutine scope.

- [ ] **Step 4: Change exactly one combine input**

Replace only the first input of the existing `combine`:

```kotlin
val uiState: StateFlow<ProtectionUiState> = combine(
    projectedSnapshots,
    destination,
    settingsSummary,
    presentation,
    currentTimeMs,
) { snapshot, selectedDestination, currentSettings, inputs, currentTime ->
```

Leave the `ProtectionUiState.from(...)` mapping, `stateIn`, initial value, settings flow, presentation flow, operation flags, messages, and ticker unchanged.

- [ ] **Step 5: Add the exact interval constant inside the class**

Before the closing brace of `ProtectionViewModel`, add:

```kotlin
private companion object {
    const val UI_SNAPSHOT_PROJECTION_INTERVAL_MS = 1_000L
}
```

Do not reuse the service's 5,000 ms constant. The UI must refresh live readings once per second while raw sensor processing remains unchanged.

- [ ] **Step 6: Run the focused GREEN gate**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*ProtectionViewModelTest' --tests '*SnapshotProjectionGateTest' --tests '*ProtectionCoordinatorTest' --tests '*AndroidProtectionRuntimeTest'
```

Required:

- The rapid-sample regression passes with exactly 1 UI emission inside the first 500 ms sample burst and exactly 2 after the 2,001 ms sample.
- The coordinator revision assertion proves every raw sample is retained.
- `ALERT_ACTIVE` and latest incident bypass the projection interval.
- Existing countdown, command, settings, coordinator freshness, sensor reading, and runtime observation tests remain green.
- XML failures `0`, errors `0`, skipped `0`.

- [ ] **Step 7: Run scoped static and diff gates**

Run:

```powershell
rg -n "projectedSnapshots|SnapshotProjectionGate|UI_SNAPSHOT_PROJECTION_INTERVAL_MS|coordinator.snapshot" app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt
rg -n "SENSOR_DELAY_GAME|SENSOR_DELAY_NORMAL" app/src/main/java/com/example/motorcycleantitheftsensor/sensor/VibrationDetector.kt app/src/main/java/com/example/motorcycleantitheftsensor/sensor/LightIntrusionDetector.kt
git diff --check -- app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt
git diff -- app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt
```

Required:

- `combine` uses `projectedSnapshots`; direct `coordinator.snapshot` remains only where the initial value or current authoritative state is intentionally read.
- Detector delays are unchanged.
- Scoped `git diff --check` exits `0` for newly edited lines. Existing unrelated end-of-file whitespace must not be expanded or reformatted as part of this task.
- The diff contains no edit to ticker, commands, settings, messages, Navigation, coordinator, runtime graph, service, or GPS.

- [ ] **Step 8: Checkpoint without mixing the dirty worktree**

In the shared checkout, do not stage these files because they contain pre-existing changes. Record the exact scoped diff and test evidence instead. Only in an isolated clean worktree may the agent run:

```powershell
git add app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt
git commit -m "fix(ui): coalesce volatile protection sensor updates"
```

---

### Task 4: Full Host and Build Verification

**Files:**
- Verify: complete application and host-test source
- Modify evidence only: `docs/superpowers/status/2026-08-15-arm-ui-flicker-remediation-evidence.md`

**Interfaces:**
- Consumes: Task 3 GREEN implementation.
- Produces: fresh full XML totals and an installable APK with recorded SHA-256.

- [ ] **Step 1: Run the full host suite with fresh execution**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest
```

Parse every fresh XML file:

```powershell
$totals = [ordered]@{ Suites = 0; Tests = 0; Failures = 0; Errors = 0; Skipped = 0 }
Get-ChildItem -Path 'app\build\test-results\testDebugUnitTest' -Filter 'TEST-*.xml' | ForEach-Object {
    $suite = [xml](Get-Content -LiteralPath $_.FullName -Raw)
    $totals.Suites += 1
    $totals.Tests += [int]$suite.testsuite.tests
    $totals.Failures += [int]$suite.testsuite.failures
    $totals.Errors += [int]$suite.testsuite.errors
    $totals.Skipped += [int]$suite.testsuite.skipped
}
Write-Output ('FULL_UNIT suites={0} tests={1} failures={2} errors={3} skipped={4}' -f $totals.Suites,$totals.Tests,$totals.Failures,$totals.Errors,$totals.Skipped)
```

Required: Gradle exit `0`, failures `0`, errors `0`, skipped `0`. Record exact totals, not the earlier 323-test baseline.

- [ ] **Step 2: Assemble and compile Android tests**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:assembleDebug :app:compileDebugAndroidTestKotlin
```

Required: both tasks exit `0`. Do not claim Android device tests ran; this command compiles them only.

- [ ] **Step 3: Record the exact APK artifact**

Run:

```powershell
$apk = Get-Item -LiteralPath 'app\build\outputs\apk\debug\app-debug.apk'
$hash = Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256
Write-Output ('APK path={0} bytes={1} sha256={2}' -f $apk.FullName,$apk.Length,$hash.Hash)
```

Record path, byte count, SHA-256, build timestamp, and Gradle exit codes under `## APK Artifact`.

---

### Task 5: Install and Capture Controlled Post-Fix Evidence

**Files:**
- Install: `app/build/outputs/apk/debug/app-debug.apk`
- Modify evidence: `docs/superpowers/status/2026-08-15-arm-ui-flicker-remediation-evidence.md`

**Interfaces:**
- Consumes: Task 4 APK and Task 1 controlled before metrics.
- Produces: same-condition post-fix local/Telegram metrics and functional acceptance.

- [ ] **Step 1: Upgrade-install without clearing user configuration**

Run:

```powershell
$adb='C:\Users\ASUS\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb devices -l
& $adb install -r 'app\build\outputs\apk\debug\app-debug.apk'
```

Required: exactly one intended Huawei target and install result `Success`. Do not uninstall, run `pm clear`, remove encrypted preferences, or re-pair Telegram.

- [ ] **Step 2: Capture post-fix local-arm metrics**

Use exactly the same device position, foreground Protection destination, scroll position, and 15-second duration as Task 1. Ensure disarmed and no popup is animating, then:

```powershell
& $adb shell dumpsys gfxinfo com.example.motorcycleantitheftsensor reset | Out-Null
```

Tap Arm once, wait untouched for 15 seconds, then:

```powershell
$localAfter = & $adb shell dumpsys gfxinfo com.example.motorcycleantitheftsensor
$localAfter | Select-String -Pattern 'Total frames rendered|Janky frames|50th percentile|90th percentile|95th percentile|99th percentile|Number Missed Vsync|Number High input latency|Number Slow UI thread|Number Slow issue draw commands'
```

Record every line and visible result. Disarm after capture.

- [ ] **Step 3: Capture post-fix Telegram-arm metrics**

Return to the same disarmed screen and reset counters:

```powershell
& $adb shell dumpsys gfxinfo com.example.motorcycleantitheftsensor reset | Out-Null
```

Send exact `/arm` from the paired owner, wait untouched for 15 seconds, then:

```powershell
$telegramAfter = & $adb shell dumpsys gfxinfo com.example.motorcycleantitheftsensor
$telegramAfter | Select-String -Pattern 'Total frames rendered|Janky frames|50th percentile|90th percentile|95th percentile|99th percentile|Number Missed Vsync|Number High input latency|Number Slow UI thread|Number Slow issue draw commands'
```

Record every line and visible result. Disarm after capture.

- [ ] **Step 4: Apply the quantitative performance gate**

For both local and Telegram scenarios, calculate:

```text
jankPercent = (Janky frames / Total frames rendered) * 100
requiredMaximum = max(15.0, preFixJankPercent * 0.50)
```

Required for each scenario:

- no visible full-screen/card flicker during the 10-second countdown or first 5 armed seconds;
- post-fix `jankPercent <= requiredMaximum`;
- post-fix 90th/95th/99th percentile frame times are not worse than the corresponding controlled pre-fix run;
- no increase in missed-vsync, slow-UI-thread, or slow-draw-command counts relative to pre-fix under the same 15-second window.

If visible flicker is gone but a numeric threshold fails by one isolated frame, report the exact figures and rerun the same scenario once. Do not average unlike conditions. If the repeat also fails, the plan is not complete.

- [ ] **Step 5: Verify functional behavior on the installed build**

Perform these checks without screen recording:

1. Local Arm enters `ARMING`, countdown changes once per second, and reaches `ARMED_HEALTHY` or the legitimate degraded state after 10 seconds.
2. Vibration and other visible sensor readings refresh approximately once per second, not at accelerometer callback frequency.
3. Leave the app armed and foreground for 60 seconds; required: no flicker, no frozen UI, and live values continue changing.
4. Local Disarm applies immediately and returns to `DISARMED_ONLINE`.
5. Telegram `/arm` and `/disarm` from the paired owner still work and state changes appear promptly.
6. `/disarm 123456` remains rejected and cannot change protection state.
7. Existing bot token, owner pairing, sensitivity, GPS, and Settings configuration remain intact after `install -r`.
8. Do not deliberately trigger an emergency SMS/call path for this UI fix. Existing host tests cover incident behavior; any real-world emergency-channel test requires separate user authorization.

Record each item as PASS or FAIL. A visually smooth countdown with broken Arm/Disarm, stale readings, or delayed state changes is a failure.

- [ ] **Step 6: Update evidence with bounded conclusions**

Complete all remaining evidence headings. Include:

- exact changed files;
- RED failure and GREEN result;
- focused and full XML totals;
- build/compile exit codes;
- APK bytes and SHA-256;
- redacted Huawei identity;
- pre/post local and Telegram metrics;
- the calculated jank percentages and required maxima;
- eight functional acceptance results;
- explicit unexecuted gates or deviations.

Do not say “flicker fixed” unless both controlled scenarios pass visual, quantitative, and functional gates.

---

### Task 6: Final Scope Audit and Stop Rule

**Files:**
- Review only: all working-tree changes
- Modify only if evidence formatting is incomplete: `docs/superpowers/status/2026-08-15-arm-ui-flicker-remediation-evidence.md`

**Interfaces:**
- Consumes: Tasks 1–5 evidence.
- Produces: an auditable completion or a bounded failure report; it never authorizes speculative extra fixes.

- [ ] **Step 1: Confirm only authorized implementation files changed**

Run:

```powershell
git status --short
git diff --name-only
git diff -- app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt docs/superpowers/status/2026-08-15-arm-ui-flicker-remediation-evidence.md
git diff --check -- app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt docs/superpowers/status/2026-08-15-arm-ui-flicker-remediation-evidence.md
```

Required: the new remediation changes are limited to ViewModel projection, its tests, and evidence. Pre-existing dirty files remain present but untouched by this plan.

- [ ] **Step 2: Search for prohibited speculative changes**

Run:

```powershell
git diff -- app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt app/src/main/java/com/example/motorcycleantitheftsensor/Navigation.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt app/src/main/java/com/example/motorcycleantitheftsensor/sensor
```

Required: no hunk from this remediation changes these files. Existing unrelated hunks must be identified as pre-existing, not included in the remediation handoff.

- [ ] **Step 3: Apply the final decision rule**

Mark the remediation `COMPLETE` only when:

- controlled pre-fix evidence reproduced the issue;
- the RED emission test failed for the intended reason;
- presentation projection made it GREEN without reducing coordinator revisions;
- semantic state/incident changes remained immediate;
- focused/full/build/Android-test compile gates are green;
- both controlled post-fix device scenarios meet the quantitative threshold and show no visible flicker;
- all eight functional checks pass;
- no prohibited file was changed by this remediation.

If any condition fails, mark `INCOMPLETE` with the exact failing evidence. Do not add coordinator throttling, ticker changes, remembered actions, `debounce`, `sample`, `conflate`, arbitrary delays, or detector-rate changes. Return to root-cause investigation and write a separate reviewed plan.

## Expected Outcome

The accelerometer and all detection logic continue receiving their original raw observation stream, `ProtectionCoordinator.snapshot.revision` continues reflecting every authoritative update, and semantic state/health/incident changes reach UI immediately. Only volatile sensor timestamp/reading projection into `ProtectionUiState` is limited to one update per second, removing the rapid Compose invalidation that caused visible flicker while preserving the one-second arming countdown and all protection behavior.
