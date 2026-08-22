# Remove Manual Microphone Self-Test Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ถอนระบบ Test Microphone แบบ manual ออกจากทุกชั้นโดยสมบูรณ์ ขณะคงระบบฟังเสียงอัตโนมัติ, telemetry, classifier และ Sensor Fusion ตอน Arm ไว้เหมือนเดิม

**Architecture:** ลบ feature vertical slice จาก UI ลงถึง audio pipeline แทนการซ่อนปุ่มเพียงอย่างเดียว หลังลบ recorder จะเหลือเส้นทางเดียวคือ active Armed capture และ Android backend จะใช้ four-argument `AudioRecord.read(..., AudioRecord.READ_BLOCKING)` โดยตรง เพิ่ม source contract และ Compose test เพื่อป้องกัน self-test กลับมาโดยไม่ตั้งใจ

**Tech Stack:** Kotlin, Android `AudioRecord`, Kotlin Coroutines, JUnit4, Jetpack Compose UI tests, Gradle Kotlin DSL

## Global Constraints

- Repository: `D:\security\MotorcycleAntiTheftSensor`
- Approved design: `docs/superpowers/specs/2026-08-15-remove-manual-microphone-self-test-design.md`
- `AI_WORKFLOW.md` was not present at plan-review time. Re-check before starting; if it now exists, read and follow it. If it is still absent, record that fact and continue under repository `AGENTS.md`; do not invent or create a replacement file.
- Baseline reviewed on 2026-08-15: HEAD `868e2b7f24feb161a4a1185c85db322c26e7f88d`; worktree มี GPS/audio/UI และเอกสารที่ยังไม่ commit จำนวนมาก
- ก่อนแก้ให้บันทึก `git status --short`; ห้าม reset, clean, checkout, stash, move, delete หรือ revert งานเดิมเพื่อทำให้ tree ดูสะอาด
- ห้าม broad-stage หรือ commit; เจ้าของยังไม่ได้อนุญาตให้สร้าง commit
- แก้เฉพาะไฟล์ใน Allowed Files; หากต้องแก้นอกขอบเขตให้หยุดและรายงาน
- ห้ามเพิ่ม dependency
- ห้ามเปลี่ยน audio sample rate 16 kHz, frame size 1,600, YAMNet input, classifier, label mapping, calibration, audio gate, candidate buffer, recovery, Sensor Fusion, incident, Telegram, SMS, GPS, navigation destinations หรือ protection state rules
- ห้ามเพิ่ม self-test รูปแบบใหม่ เช่น automatic five-second test, hidden debug action, secret gesture, command หรือ developer menu
- ห้ามส่ง popup, Snackbar, Telegram, SMS, notification หรือ Events entry เพื่อทดแทน self-test
- Automatic Armed audio health/telemetry ต้องคงอยู่และเป็นหลักฐานเดียวว่าไมค์ทำงาน
- Startup `OFFLINE` flash ไม่อยู่ในแผนนี้ ห้ามแก้ปนกัน
- รัน Gradle ทีละคำสั่งด้วย Android Studio JBR, `--no-daemon`, `--max-workers=1` และ in-process Kotlin compiler
- Host/build evidence แยกจาก exact-APK real-device acceptance; ห้ามอ้างว่าไมค์ทำงานจริงจาก unit test อย่างเดียว

## Current Baseline

Fresh review before this plan found:

- Full host suite: 451 tests, 0 failures, 0 errors, 0 skipped
- `AudioThreatPipelineTest`: 24 tests, 0 failures, 0 errors
- `ProtectionViewModelTest`: 36 tests, 0 failures, 0 errors
- `assembleDebug`: PASS
- `compileDebugAndroidTestKotlin`: PASS
- Installed APK SHA-256 matched the then-current build: `C8916E228BFA1F70B4F27436ED3347CFC3823BE29AD724EFE1EF060C460A4908`
- Manual self-test is still present across UI, ViewModel, coordinator, runtime, detector, pipeline, tests, and fakes

The final test count may decrease because self-test-only tests are deleted and will increase by the new removal-contract tests. Record the actual count; do not preserve obsolete tests merely to match 451.

## Allowed Files

### Production

- `app/src/main/java/com/example/motorcycleantitheftsensor/Navigation.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntime.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/AudioPeakDetector.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/audio/AudioThreatPipeline.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreen.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt`

### Tests and Evidence

- `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt`
- `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinatorTest.kt`
- `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/AudioPeakDetectorSourceContractTest.kt`
- `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/audio/AudioThreatPipelineTest.kt`
- `app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt`
- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/ManualMicrophoneSelfTestRemovalContractTest.kt`
- Create: `docs/superpowers/status/2026-08-15-manual-microphone-self-test-removal-evidence.md`

`AndroidProtectionRuntimeTest.kt`, `SensorServiceControllerTest.kt`, and `TelegramCommandHandlerTest.kt` may require no edits because the interface currently supplies a default method and their fakes do not override it. If compilation proves otherwise, stop and request scope expansion with the exact compiler error; do not silently edit them.

---

### Task 1: Lock Scope and Add Removal Contracts RED

**Files:**
- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/ManualMicrophoneSelfTestRemovalContractTest.kt`
- Modify test only: `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt`
- Production: read-only in this task

**Interfaces:**
- Produces: repository-level prohibition of manual microphone self-test production symbols
- Produces: Settings UI absence and automatic-diagnostics presence contract

- [ ] **Step 1: Record the exact dirty baseline**

Run:

```powershell
Set-Location 'D:\security\MotorcycleAntiTheftSensor'
git rev-parse HEAD
git status --short
git diff --check
```

Save the output outside Git or paste it into the final evidence later. Do not modify existing files reported by `git diff --check` unless they are in Allowed Files and directly touched by this plan.

- [ ] **Step 2: Record every current self-test reference**

```powershell
rg -n "MicrophoneSelfTest|testMicrophone|runSelfTest|MICROPHONE_SELF_TEST|AudioReadMode|SELF_TEST_TIMEOUT|NO_AUDIO_OBSERVED|Test Microphone|Last Self-Test|testingMicrophone" app/src/main app/src/test app/src/androidTest
```

Expected before removal: references in all ten production paths and self-test-only tests. Save this output as the before-list.

- [ ] **Step 3: Add the production source removal contract**

Create this exact file:

```kotlin
package com.example.motorcycleantitheftsensor.sensor

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualMicrophoneSelfTestRemovalContractTest {
    @Test
    fun productionSourceContainsNoManualMicrophoneSelfTestSurface() {
        val forbidden = listOf(
            "MicrophoneSelfTest",
            "testMicrophone",
            "runSelfTest",
            "MICROPHONE_SELF_TEST",
            "SELF_TEST_TIMEOUT",
            "NO_AUDIO_OBSERVED",
            "Test Microphone",
            "Last Self-Test",
            "testingMicrophone",
        )
        val hits = File("src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val content = file.readText()
                forbidden.asSequence()
                    .filter(content::contains)
                    .map { token -> "${file.invariantSeparatorsPath}: $token" }
            }
            .toList()

        assertTrue(
            "Manual microphone self-test production references remain:\n${hits.joinToString("\n")}",
            hits.isEmpty(),
        )
    }
}
```

This test scans only `src/main/java`; its own forbidden strings do not cause a false failure.

- [ ] **Step 4: Add the Settings UI removal contract**

In `ProtectionAppScreenTest.kt`, add exact test:

```kotlin
@Test
fun settingsHasAutomaticAudioDiagnosticsButNoManualMicrophoneTest() {
    showWithLocalNavigation(configuredSettingsState())
    openSettings()

    compose.onNodeWithText("Audio Threat Detection").assertExists()
    compose.onNodeWithText("Audio Runtime").assertExists()
    compose.onNodeWithText("Last Sample Age").assertExists()
    compose.onAllNodes(hasText("Test Microphone", substring = true)).assertCountEquals(0)
    compose.onAllNodes(hasText("Last Self-Test", substring = true)).assertCountEquals(0)
}
```

Import only APIs already used in this file. Do not add a hidden test tag or replacement button.

- [ ] **Step 5: Run RED gates**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*ManualMicrophoneSelfTestRemovalContractTest"
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' compileDebugAndroidTestKotlin
```

Expected:

- Removal contract FAILS and lists production references.
- Android test source compiles; the new UI assertion remains runtime RED until executed on device. If a connected device is already authorized, run only the new test and record its assertion failure.

Do not edit production until the source contract has failed for the intended reason.

---

### Task 2: Remove UI, ViewModel, and UI-State Ownership

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/Navigation.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreen.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt`
- Modify: `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt`

**Interfaces:**
- Removes: manual self-test action, operation, state projection, result model, and visible controls
- Preserves: `AudioUiTelemetry` automatic runtime fields and Settings diagnostics

- [ ] **Step 1: Remove the action from Navigation and app actions**

Delete this property from `ProtectionAppActions`:

```kotlin
val testMicrophone: () -> Unit = {},
```

Delete this binding from `Navigation.kt`:

```kotlin
testMicrophone = protectionViewModel::runMicrophoneTest,
```

Do not change destinations, `selectDestination`, arm/disarm, SMS, bot-token, permission, or retry actions.

- [ ] **Step 2: Remove self-test UI models without deleting automatic telemetry**

In `ProtectionUiModels.kt`:

- Delete `MICROPHONE_SELF_TEST` from `SettingsOperation`.
- Delete the entire `MicrophoneSelfTestUiResult` data class.
- Delete `selfTestResult` from `AudioUiTelemetry`.
- Remove `selfTestResult` from `AudioTelemetry.toAudioUiTelemetry(...)` parameters and returned construction.

The remaining projection signature must be:

```kotlin
internal fun AudioTelemetry.toAudioUiTelemetry(
    elapsedNowMs: Long,
): AudioUiTelemetry
```

Keep state, gate, model readiness, dBFS, baselines, sample age, inference, counters, and current candidate unchanged.

- [ ] **Step 3: Remove ViewModel self-test execution and state**

In `ProtectionViewModel.kt` delete:

- imports `MicrophoneSelfTestResult` and `TimeoutCancellationException` if no longer used;
- `suspend fun testMicrophone()`;
- the complete `fun runMicrophoneTest()` block;
- `microphoneSelfTestResult` from `PresentationInputs`;
- every `selfTestResult = inputs.microphoneSelfTestResult` and `selfTestResult = null` argument.

Every telemetry projection becomes:

```kotlin
coordinator.audioTelemetry.value.toAudioUiTelemetry(
    elapsedNowMs = elapsedNowMs(),
)
```

Do not change `settingsMutex` because it still serializes bot token, SMS fallback, permissions, sensitivity, and pairing operations.

- [ ] **Step 4: Remove the Settings manual control only**

In `SettingsScreen.kt` delete:

- `val testingMicrophone = ...`;
- the `audio.selfTestResult?.let { ... }` result/time block;
- the complete Test Microphone button;
- any imports used only by those blocks.

Keep the `Audio Threat Detection` card and every automatic row:

- Microphone Sensor
- Audio Runtime
- Audio Gate
- Model Classifier
- Audio Level
- Baseline Noise
- Last Sample Age
- Inference Latency
- Dropped / Restarts
- Current Threat Candidate and expiry

Do not add replacement copy such as “tested automatically.” The live rows are sufficient.

- [ ] **Step 5: Remove ViewModel tests and fake behavior that exist only for self-test**

In `ProtectionViewModelTest.kt` delete:

- `MicrophoneSelfTestResult` import;
- tests whose names contain `MicrophoneSelfTest` or test `runMicrophoneTest()`;
- `microphoneTestAction`, `microphoneTestCalls`, and fake `override suspend fun testMicrophone()`;
- assertions for `SettingsOperation.MICROPHONE_SELF_TEST` or `audio.selfTestResult`.

Keep:

- `microphoneHealthTextDistinguishesDetectedUnavailableStaleAndFailed`;
- audio telemetry projection/cadence tests;
- SMS ownership test and its `ViewModelStore` cleanup;
- bot-token, permission, navigation and operation ownership tests.

- [ ] **Step 6: Remove obsolete Compose test and keep the new absence contract**

Delete `microphoneSelfTestInFlightDoesNotShowBotOrSmsSpinners()` because its operation no longer exists. Keep `settingsHasAutomaticAudioDiagnosticsButNoManualMicrophoneTest()` from Task 1 and all protection audio runtime tests.

- [ ] **Step 7: Compile UI layers**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*ProtectionViewModelTest"
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' compileDebugAndroidTestKotlin
```

Expected: both commands PASS. Coordinator/runtime/pipeline self-test symbols still exist at this point, but they remain internally consistent until Tasks 3-4 remove them. Do not continue with a broken intermediate build; fix only UI/ViewModel removal errors inside this task's Allowed Files.

---

### Task 3: Remove Coordinator and Runtime Self-Test APIs

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntime.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinatorTest.kt`

**Interfaces:**
- Removes: `ProtectionCoordinator.testMicrophone()` and `ProtectionRuntime.testMicrophone()`
- Preserves: command mutex and all real protection commands

- [ ] **Step 1: Remove the coordinator entry point**

Delete the `MicrophoneSelfTestResult` import and the complete `suspend fun testMicrophone(): MicrophoneSelfTestResult` method from `ProtectionCoordinator.kt`, including its Disarmed-state guard, result construction, and `runtime.testMicrophone()` call.

Do not alter `commandMutex`, arm, disarm, sensitivity, recovery, incident close, persistence or live pursuit ordering.

- [ ] **Step 2: Remove the runtime interface method**

In `ProtectionRuntime.kt` delete the `MicrophoneSelfTestResult` import and the complete default `testMicrophone()` method. Do not replace it with `Unit`, unsupported result, or a deprecated alias.

- [ ] **Step 3: Remove Android runtime forwarding methods**

In `AndroidProtectionRuntime.kt` delete:

- `MicrophoneSelfTestResult` import;
- detector-suite/manual fallback `testMicrophone()` declaration;
- `AndroidProtectionRuntime.override suspend fun testMicrophone()`;
- the detector ownership forwarding method that returns `audio.testMicrophone()`.

Keep detector start/stop/readiness, audio telemetry, sensitivity and candidate reset ownership unchanged.

- [ ] **Step 4: Remove coordinator self-test tests and fake handler**

In `ProtectionCoordinatorTest.kt` delete:

- every test that calls `coordinator.testMicrophone()`;
- `selfTestHandler` from the fake runtime;
- fake `override suspend fun testMicrophone()`;
- fully qualified `MicrophoneSelfTestResult` references.

Do not weaken arm/disarm serialization tests unrelated to self-test. The coordinator still needs command-ordering coverage for actual protection commands.

- [ ] **Step 5: Run protection-layer GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*ProtectionCoordinatorTest" --tests "*ProtectionViewModelTest"
```

Expected: PASS with no manual self-test symbols required by these layers.

---

### Task 4: Remove Pipeline Self-Test and Collapse Recorder to Armed Blocking Read

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/audio/AudioThreatPipeline.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/AudioPeakDetector.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/audio/AudioThreatPipelineTest.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/AudioPeakDetectorSourceContractTest.kt`

**Interfaces:**
- Removes: `MicrophoneSelfTestResult`, `AudioThreatPipeline.runSelfTest()`, `AudioReadMode`, detector forwarding method
- Produces: single `AudioRecorderBackend.read(target: ShortArray): Int` for Armed capture
- Android implementation explicitly uses `AudioRecord.READ_BLOCKING`

- [ ] **Step 1: Simplify the recorder backend interface**

Delete `AudioReadMode` and change the interface to:

```kotlin
interface AudioRecorderBackend {
    val initialized: Boolean
    val recording: Boolean
    fun start()
    fun read(target: ShortArray): Int
    fun stop()
    fun release()
}
```

No default parameter and no Boolean `blocking` flag may remain.

- [ ] **Step 2: Delete pipeline self-test code**

In `AudioThreatPipeline.kt` delete:

- `MicrophoneSelfTestResult` data class;
- complete `runSelfTest()` method;
- imports used only by that method after checking remaining active pipeline use;
- result strings `MIC_BUSY_ARMED`, `INIT_FAILED`, `NOT_INITIALIZED`, `START_RECORDING_FAILED`, `TIMEOUT`, `NO_AUDIO_OBSERVED`, `NO_SAMPLES_READ`, `SELF_TEST_TIMEOUT`, and `EXCEPTION` only when they belong solely to self-test.

Do not delete similarly named active-capture health/recovery detail codes if they are used by `runCaptureWithRecovery()`.

Change the active capture call from:

```kotlin
backend.read(frameBuffer, AudioReadMode.BLOCKING)
```

to:

```kotlin
backend.read(frameBuffer)
```

No capture-loop timing, error handling, calibration or inference code may change.

- [ ] **Step 3: Keep explicit blocking at the Android boundary**

In `AudioPeakDetector.kt`, remove `AudioReadMode` and `MicrophoneSelfTestResult` imports and change the backend to:

```kotlin
override fun read(target: ShortArray): Int {
    val recorder = audioRecord ?: return AudioRecord.ERROR_INVALID_OPERATION
    return recorder.read(
        target,
        0,
        target.size,
        AudioRecord.READ_BLOCKING,
    )
}
```

Delete `AudioPeakDetector.testMicrophone()`. Keep `startListening`, `stopListening`, `freezeAdaptation`, telemetry and candidate buffer unchanged.

- [ ] **Step 4: Update the fake recorder and remove self-test-only tests**

In `AudioThreatPipelineTest.kt`:

- remove `readModes`;
- change fake signature to `override fun read(target: ShortArray): Int` while preserving counters/error/frame behavior;
- delete tests `selfTestRequestsOnlyNonBlockingReads`, `selfTestHandlesCancellationGracefully`, and `selfTestBoundsByMonotonicTimeLimit`;
- replace `armedCaptureRequestsOnlyBlockingReads` with a behavior test named `armedCaptureReadsFramesThroughSingleRecorderPath`.

The replacement test must assert:

```kotlin
assertTrue(recorder.readCalls.get() > 0)
assertEquals(AudioRuntimeState.OFF, pipeline.currentTelemetry.state)
```

after start, virtual capture progress, stop, and scheduled cleanup. It must not inspect a removed read-mode enum.

Delete `println("TEST_RESULT...`) and every self-test raw-detail output.

- [ ] **Step 5: Strengthen Android source contract**

Replace mode-mapping assertions in `AudioPeakDetectorSourceContractTest` with:

```kotlin
assertTrue("Armed capture must use explicit blocking read", content.contains("AudioRecord.READ_BLOCKING"))
assertTrue("Must call four-argument AudioRecord.read", content.contains("recorder.read("))
assertFalse("Manual non-blocking self-test mode must be removed", content.contains("AudioRecord.READ_NON_BLOCKING"))
assertFalse("AudioReadMode must be removed", content.contains("AudioReadMode"))
assertFalse("Manual detector self-test must be removed", content.contains("testMicrophone"))
```

Keep existing 16 kHz, no `Thread.sleep(200)`, and no PCM/WAV-file assertions. Do not depend on LF-only newline matching.

- [ ] **Step 6: Run audio GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*AudioThreatPipelineTest" --tests "*AudioPeakDetectorSourceContractTest"
```

Expected: all remaining active-capture tests pass. Test count is lower only by deleted manual self-test tests plus the renamed/replacement Armed test.

---

### Task 5: Prove Complete Removal and Preserve Automatic Audio Behavior

**Files:**
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/ManualMicrophoneSelfTestRemovalContractTest.kt`
- Verify: every Allowed File

**Interfaces:**
- Consumes: Tasks 2-4
- Produces: no manual self-test production surface and green focused behavior

- [ ] **Step 1: Run the removal contract GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*ManualMicrophoneSelfTestRemovalContractTest"
```

Expected: PASS with no forbidden production token.

- [ ] **Step 2: Run exhaustive text audit**

```powershell
rg -n "MicrophoneSelfTest|testMicrophone|runSelfTest|MICROPHONE_SELF_TEST|AudioReadMode|SELF_TEST_TIMEOUT|NO_AUDIO_OBSERVED|Test Microphone|Last Self-Test|testingMicrophone|TEST_RESULT" app/src/main app/src/test app/src/androidTest
```

Expected output is limited to the forbidden-string list and test method name inside `ManualMicrophoneSelfTestRemovalContractTest.kt`, plus the new Compose assertion strings that verify absence. No production result, API, operation, action, button, timeout or forwarding method may remain.

- [ ] **Step 3: Audit automatic audio references that must remain**

```powershell
rg -n "AudioRuntimeState|AudioTelemetry|currentLevelDbfs|lastSampleAtMs|AudioThreatClassifier|AudioThreatCandidateBuffer|SensorKind\.MICROPHONE|READ_BLOCKING" app/src/main/java/com/example/motorcycleantitheftsensor
```

Expected: active runtime, classifier, candidate, telemetry, microphone health and explicit blocking read remain. If this search is unexpectedly empty in any category, stop; the implementation deleted real protection behavior.

- [ ] **Step 4: Run focused regression tests**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*AudioThreatPipelineTest" --tests "*AudioPeakDetectorSourceContractTest" --tests "*ProtectionCoordinatorTest" --tests "*ProtectionViewModelTest" --tests "*AudioMovementFusionIntegrationTest" --rerun-tasks
```

Expected: PASS, no hang, no Gradle test worker after completion.

- [ ] **Step 5: Review exact diff and scope**

```powershell
git diff --check
git status --short
git diff -- app/src/main/java/com/example/motorcycleantitheftsensor/Navigation.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntime.kt app/src/main/java/com/example/motorcycleantitheftsensor/sensor/AudioPeakDetector.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreen.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinatorTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/sensor/AudioPeakDetectorSourceContractTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt
```

Because `sensor/audio/` is currently untracked, also read these files directly; ordinary `git diff` will not show them:

```powershell
rg -n "MicrophoneSelfTest|testMicrophone|runSelfTest|AudioReadMode|backend\.read" app/src/main/java/com/example/motorcycleantitheftsensor/sensor/audio app/src/test/java/com/example/motorcycleantitheftsensor/sensor/audio
```

Compare with Task 1 baseline. Do not claim unrelated pre-existing modifications were made by this plan.

---

### Task 6: Full Host, APK, and Android-Test Gates

**Files:**
- Verify all source/test files
- Do not change production during this task unless a failure is directly caused by self-test removal

**Interfaces:**
- Produces: full host/build evidence for the exact source tree

- [ ] **Step 1: Run full unit tests without cache reuse for the test task**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --rerun-tasks
```

Expected: PASS. Sum `tests`, `failures`, `errors`, and `skipped` from every `app/build/test-results/testDebugUnitTest/TEST-*.xml` file and record the actual values.

- [ ] **Step 2: Build the exact APK**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' assembleDebug
Get-FileHash -Algorithm SHA256 'app\build\outputs\apk\debug\app-debug.apk'
```

Expected: build passes and a new APK SHA-256 is recorded.

- [ ] **Step 3: Compile Android tests**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' compileDebugAndroidTestKotlin
```

Expected: PASS with the new Settings absence contract and without obsolete self-test operation symbols.

- [ ] **Step 4: Check for stale test workers only**

```powershell
& 'C:\Program Files\Android\Android Studio\jbr\bin\jps.exe' -lv
```

Expected: no `GradleWorkerMain` or Gradle test executor from completed commands. Do not kill IDE, Kotlin LSP, Serena, or unrelated JVMs.

---

### Task 7: Exact-APK Device Acceptance and Evidence

**Files:**
- Create: `docs/superpowers/status/2026-08-15-manual-microphone-self-test-removal-evidence.md`
- Verify: freshly built APK on one authorized device

**Interfaces:**
- Consumes: Task 6 APK
- Produces: proof that manual self-test is absent while Armed audio remains functional

- [ ] **Step 1: Record device and install exact APK**

```powershell
$adb='C:\Users\ASUS\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb devices -l
Get-FileHash -Algorithm SHA256 'app\build\outputs\apk\debug\app-debug.apk'
& $adb install -r 'app\build\outputs\apk\debug\app-debug.apk'
& $adb shell am force-stop com.example.motorcycleantitheftsensor
& $adb logcat -c
& $adb shell monkey -p com.example.motorcycleantitheftsensor -c android.intent.category.LAUNCHER 1
```

Expected: exactly one intended authorized device, install succeeds, app launches, and the installed package corresponds to the recorded APK. Do not uninstall or clear app data because that would erase pairing/settings and broaden acceptance scope.

- [ ] **Step 2: Run focused UI instrumentation**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.example.motorcycleantitheftsensor.ui.ProtectionAppScreenTest'
```

Expected: all focused UI tests pass, including `settingsHasAutomaticAudioDiagnosticsButNoManualMicrophoneTest`.

- [ ] **Step 3: Verify Settings while Disarmed**

Manual matrix:

1. Disarm protection.
2. Open Settings and scroll through the complete Audio Threat Detection card.
3. Confirm `Test Microphone`, `Last Self-Test Result`, `Last Self-Test Time`, self-test spinner and manual test failure are absent.
4. Confirm Microphone Sensor, Audio Runtime, Audio Gate, Model, Audio Level, Baseline, Last Sample Age, latency, counters and candidate rows remain.
5. Confirm runtime truthfully shows Off while Disarmed.
6. Confirm opening Settings sends no Telegram, SMS, notification, event or popup.

- [ ] **Step 4: Verify automatic Armed microphone operation**

1. Arm protection.
2. Confirm runtime transitions from Starting to Calibrating and then Listening, or a truthful bounded Degraded/Failed state.
3. Generate quiet and louder ordinary sounds.
4. Confirm Audio Level changes and Last Sample Age repeatedly returns near 0 seconds.
5. Leave Armed for at least 60 seconds.
6. Confirm Model remains Ready when initialization succeeds, the screen stays responsive, and no rapid-loop/flicker/navigation reset appears.
7. Disarm and confirm runtime returns to Off and sample age stops presenting fresh capture.

If no recent samples appear or Armed audio fails to start, device acceptance FAILS even though the self-test button is correctly absent.

- [ ] **Step 5: Inspect crash and ANR evidence**

```powershell
& $adb logcat -d -v threadtime AndroidRuntime:E ActivityManager:E AudioRecord:E '*:S'
& $adb shell dumpsys activity processes | Select-String -Pattern 'com.example.motorcycleantitheftsensor|ANR'
```

Expected: no new crash/ANR attributable to the removal and Armed runtime matrix.

- [ ] **Step 6: Write final evidence**

Create the evidence file with these exact headings:

```markdown
# Manual Microphone Self-Test Removal Evidence

## Baseline and Dirty-Worktree Preservation
## Files Changed by This Plan
## Removal Contract Result
## Focused Test Results
## Full Host Test Counts
## APK Build and SHA-256
## Android-Test Compile and Instrumentation
## Device Identity and Installed APK
## Disarmed Settings Matrix
## Armed Automatic Audio Matrix
## Crash and ANR Check
## Remaining Risks
## Final Verdict
```

Every command must include result/exit code. `Final Verdict` may be `COMPLETE` only when source removal, all host gates, exact-APK instrumentation, Disarmed Settings matrix, 60-second Armed audio matrix, and crash/ANR checks pass. Otherwise use `HOST COMPLETE / DEVICE NOT ACCEPTED` or `FAILED` with the exact missing evidence.

---

## Final Acceptance Checklist

- [ ] No manual microphone self-test button, result, spinner, action, command, operation or hidden entry remains
- [ ] No `MicrophoneSelfTestResult` or `MicrophoneSelfTestUiResult` remains
- [ ] No `runSelfTest`, `testMicrophone`, `MICROPHONE_SELF_TEST` or `SELF_TEST_TIMEOUT` production symbol remains
- [ ] No self-test timeout/cancellation/raw-detail code remains
- [ ] `AudioReadMode` and `READ_NON_BLOCKING` introduced for self-test are removed
- [ ] `AudioRecorderBackend` has one `read(target: ShortArray)` method
- [ ] Android recorder uses the four-argument API with explicit `AudioRecord.READ_BLOCKING`
- [ ] Automatic audio telemetry and Settings diagnostics remain
- [ ] Armed classifier, candidate buffer and Sensor Fusion remain unchanged
- [ ] SMS, bot token, Telegram, GPS, incidents, navigation destinations and protection commands remain unchanged
- [ ] Removal source contract passes
- [ ] Settings Compose test proves diagnostics exist and manual test does not
- [ ] Focused and full unit tests pass without hang
- [ ] APK build and Android-test compile pass
- [ ] Exact APK is installed without clearing user data
- [ ] Focused instrumentation passes
- [ ] Disarmed Settings matrix passes
- [ ] Armed audio receives fresh samples and remains responsive for at least 60 seconds
- [ ] No crash or ANR is introduced
- [ ] Startup `OFFLINE` flash remains explicitly out of scope
- [ ] Unrelated dirty-worktree files are preserved
- [ ] Evidence verdict is `COMPLETE`
- [ ] No commit is created without separate owner authorization

## Stop Conditions

Stop and report instead of guessing if:

- removal requires changes outside Allowed Files;
- automatic Armed capture stops compiling or no longer receives samples;
- any proposed fix changes classifier, calibration, Sensor Fusion, Telegram, SMS, GPS, incidents, navigation or protection states;
- a test is deleted because it fails for an unrelated behavior;
- the exact APK cannot be installed/tested on the authorized device;
- any crash, ANR, stuck runtime, UI flicker or rapid loop appears;
- implementation would require reset, clean, stash, deletion, broad staging or commit of unrelated work.

Do not start another feature until the evidence file verdict is `COMPLETE`, unless the owner explicitly accepts a named missing device gate.
