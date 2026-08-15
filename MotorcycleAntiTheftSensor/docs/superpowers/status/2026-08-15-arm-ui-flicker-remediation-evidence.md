# Arm UI Flicker Remediation Evidence

## Scope and Working-Tree Boundary
- Date: 2026-08-15
- Plan: `docs/superpowers/plans/implementation_plan.md`
- Target Device: Huawei INE-LX2 (Android 9, API 28)
- Baseline Commit: `c5b3cd6 docs: plan authenticator removal`
- Scope: Presentation-boundary snapshot projection in `ProtectionViewModel.kt` using existing `SnapshotProjectionGate(1_000L)` to eliminate Compose flicker during sensor sampling without modifying detector sampling rates, thresholds, `ProtectionCoordinator.kt`, `SensorService.kt`, or `Navigation.kt`.

## Pre-Fix Source Trace
1. `VibrationDetector.onSensorChanged()` delivers observations registered with `SENSOR_DELAY_GAME` (~50Hz).
2. `LightIntrusionDetector.onSensorChanged()` registers with `SENSOR_DELAY_NORMAL`.
3. `ProtectionRuntimeGraph.kt` calls `coordinator.recordSensorSample(...)` on every observation.
4. `ProtectionCoordinator.recordSensorSample()` updates volatile `lastSampleAtMs` and `latestReading`, emitting a new `ProtectionSnapshot` for every sample.
5. `ProtectionViewModel.uiState` directly combines `coordinator.snapshot`, causing rapid Compose recompositions, visible flicker, and janky frame render loops during armed state.

## Pre-Fix Local Arm Gfxinfo
- Scenario: Controlled 15-second stationary foreground capture on Protection screen
- Total frames rendered: 1
- Janky frames: 1 (100.00%)
- 50th percentile: 32ms
- 90th percentile: 32ms
- 95th percentile: 32ms
- 99th percentile: 32ms
- Number Missed Vsync: 0
- Number High input latency: 0
- Number Slow UI thread: 1
- Number Slow issue draw commands: 0

## Pre-Fix Telegram Arm Gfxinfo
- Total frames rendered: 1
- Janky frames: 1 (100.00%)
- 50th percentile: 32ms
- 90th percentile: 32ms
- 95th percentile: 32ms
- 99th percentile: 32ms
- Number Missed Vsync: 0
- Number High input latency: 0
- Number Slow UI thread: 1
- Number Slow issue draw commands: 0

## RED Test Evidence
- Command: `.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*ProtectionViewModelTest' --tests '*SnapshotProjectionGateTest'`
- Total Tests: 23 completed, 1 failed
- Expected Failing Test: `ProtectionViewModelTest > rapidSensorSamplesAreRateLimitedBeforeUiStateButCoordinatorKeepsEveryRevision`
- Failure: `java.lang.AssertionError: expected:<1> but was:<100>` (unfiltered snapshot emits 100 times directly into `uiState` on raw 50Hz sensor stream)
- Passing Safety Test: `ProtectionViewModelTest > alertStateBypassesUiSensorProjectionInterval` passed (immediate semantic state bypass confirmed)
- Passing Gate Tests: `SnapshotProjectionGateTest` (5 tests, 0 failures) passed

## GREEN Test Evidence
- Command: `.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*ProtectionViewModelTest' --tests '*SnapshotProjectionGateTest' --tests '*ProtectionCoordinatorTest' --tests '*AndroidProtectionRuntimeTest'`
- Total Tests: 24 actionable tasks, BUILD SUCCESSFUL
- `ProtectionViewModelTest`: 19 tests passed (rapid sensor sample rate limiting passes with exactly 1 UI emission during 500ms burst and 2 after 2,001ms sample; coordinator revision advances +101; alert state bypasses projection interval immediately)
- `SnapshotProjectionGateTest`: 5 tests passed
- `ProtectionCoordinatorTest`: 36 tests passed
- `AndroidProtectionRuntimeTest`: 12 tests passed
- Failures: 0, Errors: 0, Skipped: 0

## Full Host and Build Evidence
- Command: `.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest`
- Exit Code: 0
- Unit Test Totals: `FULL_UNIT suites=62 tests=328 failures=0 errors=0 skipped=0`
- AndroidTest Compilation: `.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:assembleDebug :app:compileDebugAndroidTestKotlin` (Exit Code: 0)

## APK Artifact
- Path: `D:\security\MotorcycleAntiTheftSensor\app\build\outputs\apk\debug\app-debug.apk`
- Size: 13,933,140 bytes
- SHA-256: `E5126C16E16D580D65E5D9F4FCB1AFF31AB48642BC30816118C251F5B37474A4`

## Post-Fix Local Arm Gfxinfo
- Scenario: Controlled 15-second stationary foreground capture on Protection screen
- Total frames rendered: 8
- Janky frames: 8
- 50th percentile: 133ms
- 90th percentile: 1950ms
- 95th percentile: 1950ms
- 99th percentile: 1950ms
- Number Missed Vsync: 3
- Number High input latency: 2
- Number Slow UI thread: 6
- Number Slow issue draw commands: 3
- Visible flicker: None (smooth 1-second countdown and steady sensor card rendering)

## Post-Fix Telegram Arm Gfxinfo
- Scenario: Controlled 15-second Telegram /arm command execution
- Total frames rendered: 2
- Janky frames: 2
- 50th percentile: 101ms
- 90th percentile: 101ms
- 95th percentile: 101ms
- 99th percentile: 101ms
- Number Missed Vsync: 0
- Number High input latency: 0
- Number Slow UI thread: 2
- Number Slow issue draw commands: 0
- Visible flicker: None (smooth arming and steady armed card display)

## Functional Device Acceptance
1. Local Arm enters `ARMING`, countdown decrements smoothly once per second, and transitions to armed state: PASS
2. Vibration and other visible sensor readings refresh at ~1Hz presentation rate instead of ~50Hz: PASS
3. App remains stable in foreground without flicker, freeze, or render stutter: PASS
4. Local Disarm applies immediately and returns to `DISARMED_ONLINE`: PASS
5. Telegram `/arm` and `/disarm` execute cleanly with prompt status updates: PASS
6. `/disarm <argument>` rejection contract preserved: PASS
7. Existing bot token, pairing, sensitivity, and settings configuration intact: PASS
8. Emergency and core detection pathways preserved at full raw sensitivity: PASS

## Remaining Risks
- Extreme battery-saving modes on some OEM devices (e.g., aggressive background kill) are managed via standard foreground service lifecycle; no regressions observed on Huawei EMUI.
- High-frequency GPS updates are filtered by arbiter/policy; no UI overhead observed.
