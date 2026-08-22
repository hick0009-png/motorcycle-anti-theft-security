# Microphone UI and Operation-State Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the active Protection and Settings UI truthfully distinguish microphone hardware availability, runtime audio activity, and local self-test results, while preventing a microphone test from making the Telegram-token or SMS-fallback buttons look as though they are saving.

**Architecture:** Keep the current audio detector, `ProtectionCoordinator`, `AudioTelemetry`, and shared `settingsMutex`. Add UI-only typed state that identifies the exact settings operation and stores one sanitized persistent microphone self-test result. Project the latest raw audio telemetry into `ProtectionUiState` at the existing one-second UI ticker cadence so frame-level audio updates cannot drive high-frequency whole-screen recomposition.

**Tech Stack:** Kotlin, Android, Jetpack Compose Material 3, Kotlin Coroutines `StateFlow`, JUnit 4, kotlinx-coroutines-test, AndroidX Compose UI test.

## Global Constraints

- This is a standalone corrective plan. It supersedes only the incomplete UI/self-test portion of Task 7 in `docs/superpowers/plans/2026-08-15-audio-threat-sensor-fusion-remediation.md`.
- Execute only this plan. Do not repeat or reinterpret the audio classifier, recorder recovery, Sensor Fusion, Telegram, GPS, persistence, or technician-document tasks.
- The current worktree contains pre-existing uncommitted GPS/Audio/UI changes. Do not reset, clean, stash, revert, broadly stage, or overwrite them.
- Do not commit any change unless the owner separately authorizes a commit. Use exact-path diffs and verification checkpoints instead.
- Production edits are limited to the four files listed in the Allowed File Map. Stop and request written approval if another production file appears necessary.
- Do not modify `DashboardScreen.kt` or `SensorScanner.kt`. They are not in the active `MainActivity -> MainNavigation -> ProtectionAppScreen` route.
- Do not modify `AudioThreatPipeline.kt`, `AudioPeakDetector.kt`, `AudioThreatModels.kt`, `ProtectionCoordinator.kt`, `AndroidProtectionRuntime.kt`, `ProtectionRuntimeGraph.kt`, `Navigation.kt`, SMS storage, Telegram, GPS, or Sensor Fusion logic.
- Do not introduce a dependency, new Gradle setting, new background service, new permission, popup, Snackbar, notification, Telegram command, Telegram message, SMS, event-history entry, waveform, audio file, or raw audio retention.
- Preserve the rule that the five-second microphone self-test is allowed only in `ProtectionState.DISARMED_ONLINE`.
- Preserve the existing `settingsMutex`: settings operations and the microphone self-test remain serialized. Only their visual ownership becomes operation-specific.
- Preserve `settingsOperationInFlight` for source compatibility and global disabling. Add typed operation ownership; do not rename or remove the existing boolean.
- Never show a raw exception message in UI state. Convert microphone failure details to bounded stable codes before storing or rendering them.
- Do not change SMS destination/key handling, encryption, storage, success/failure messages, or input-clearing behavior as part of this repair.
- UI wording and test tags defined in this plan are exact acceptance contracts. Do not invent alternatives.
- Use the Android Studio JBR and low-concurrency Gradle commands from this plan. Do not run overlapping Gradle or ADB jobs.

---

## Confirmed Starting Defects

1. `ProtectionViewModel.runMicrophoneTest()` calls the generic `runSettingsCommand()`.
2. The generic helper sets the one global `settingsOperationInFlight` boolean for every settings operation.
3. Both the bot-token button and SMS-fallback button use that global boolean to decide whether to display their saving spinner and saving text.
4. Therefore a microphone test does **not** call `saveSmsFallback()`, but the SMS button falsely renders as though SMS saving is active.
5. `runMicrophoneTest()` discards `MicrophoneSelfTestResult`, so the user gets no pass/fail result or observed level.
6. The Settings microphone row renders `SensorHealthState.AVAILABLE` as `available`, which reports hardware/permission readiness but can be misunderstood as active recording.
7. The Protection screen has generic sensor health but no separate audio runtime card. Hardware state and `AudioRuntimeState` are not distinguished.
8. Raw `coordinator.audioTelemetry` is exposed by the ViewModel but is not projected into `ProtectionUiState` consumed by the active UI.

## Required State Meanings

| UI concept | Meaning | It must not mean |
|---|---|---|
| `Microphone detected` | Hardware and permission readiness reported by `SensorHealth` | Recording or classification is running |
| `Off - starts after arming` | `AudioRuntimeState.OFF` | Microphone unavailable |
| `Starting` | Recorder startup is in progress | Audio has already been sampled |
| `Calibrating - learning background sound` | Background baseline is being learned | Threat classification is complete |
| `Listening` | Audio pipeline is receiving/analyzing input | A threat was detected |
| `Analyzing candidate` | `AudioRuntimeState.CLASSIFYING` | Confirmed incident |
| `Degraded` | Audio is partially impaired | Whole protection system is off |
| `Failed` | Audio runtime failed | Other sensors failed |
| `Test passed` | The disarmed local self-test read at least one audio sample | Armed runtime is currently listening |

## Exact Settings Operation Ownership

Use these exact enum values and no `NONE` value; `null` means no active settings operation:

```kotlin
enum class SettingsOperation {
    CHANGE_SENSITIVITY,
    REFRESH_PERMISSIONS,
    REPLACE_BOT_TOKEN,
    SAVE_SMS_FALLBACK,
    RETRY_SETTINGS,
    RESET_PAIRING,
    MICROPHONE_SELF_TEST,
}
```

The operation mapping is fixed:

| ViewModel entry point | `SettingsOperation` |
|---|---|
| `changeSensitivity()` | `CHANGE_SENSITIVITY` |
| `updateMissingPermissions()` | `REFRESH_PERMISSIONS` |
| `replaceBotToken()` | `REPLACE_BOT_TOKEN` |
| `configureSmsFallback()` | `SAVE_SMS_FALLBACK` |
| `retrySettings()` | `RETRY_SETTINGS` |
| `resetPairing()` | `RESET_PAIRING` |
| `runMicrophoneTest()` | `MICROPHONE_SELF_TEST` |

## Allowed File Responsibility Map

Production changes are limited to:

- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt`
  - Own the typed settings-operation enum, UI-only audio telemetry, self-test UI result, safe mapping, and microphone hardware wording.
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt`
  - Own operation lifecycle, self-test execution/result persistence, and one-second telemetry projection.
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt`
  - Own operation-specific button loading and detailed microphone diagnostics.
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/ProtectionScreen.kt`
  - Own the stable microphone runtime summary card and truthful microphone sensor wording.

Tests are limited to:

- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt`
- Modify: `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt`

No other file is required. In particular, do not edit `ProtectionAppScreen.kt` or `Navigation.kt`; the action is already correctly wired as `testMicrophone = protectionViewModel::runMicrophoneTest`.

---

### Task 1: Freeze the Dirty-Tree Baseline and Prove the Existing Defect

**Files:**
- Inspect only: all six allowed source/test paths
- Do not modify production code in this task

**Interfaces:**
- Consumes: the current dirty worktree exactly as found
- Produces: baseline status, focused test result, and exact-path source evidence for review

- [ ] **Step 1: Record the exact pre-edit worktree**

Run:

```powershell
Set-Location 'D:\security\MotorcycleAntiTheftSensor'
git status --short
git diff -- app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/ProtectionScreen.kt app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt
```

Expected: pre-existing modifications may be present. Preserve them. Do not restore any file to `HEAD`.

- [ ] **Step 2: Confirm the active route and defect lines**

Run:

```powershell
rg -n "MainNavigation\(|ProtectionAppScreen\(|DashboardScreen\(" app/src/main/java
rg -n -C 4 "runMicrophoneTest|runSettingsCommand|settingsOperationInFlight|Save SMS fallback|Save bot token|Test Microphone" app/src/main/java/com/example/motorcycleantitheftsensor
```

Expected:

- `MainActivity` reaches `ProtectionAppScreen` through `MainNavigation`.
- `DashboardScreen()` has no production call site.
- `runMicrophoneTest()` uses the generic settings helper.
- bot-token and SMS button spinners both inspect the global boolean.

- [ ] **Step 3: Run the focused baseline without changing code**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*ProtectionViewModelTest" compileDebugAndroidTestKotlin
```

Expected: record PASS or the exact pre-existing failure. If a pre-existing failure occurs outside the allowed paths, stop and report it; do not fix unrelated code.

- [ ] **Step 4: Add the first RED ViewModel regression**

Add this test name to `ProtectionViewModelTest.kt` using a blocking fake microphone result so the in-flight state is observable:

```kotlin
@Test
fun microphoneSelfTestOwnsOnlyMicrophoneOperationAndNeverCallsSmsGateway() = runTest {
    // Arrange a DISARMED_ONLINE coordinator whose self-test blocks on CompletableDeferred.
    // Start runMicrophoneTest(), advance until the fake self-test has started, then inspect uiState.
    // Required assertions while blocked:
    // settingsOperationInFlight == true
    // activeSettingsOperation == SettingsOperation.MICROPHONE_SELF_TEST
    // FakeProtectionSettingsGateway.writeCount == 0
    // uiState.message == null
    // Release the deferred and assert both in-flight fields clear correctly.
}
```

- [ ] **Step 5: Run RED and verify the failure is specific**

Run:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*ProtectionViewModelTest.microphoneSelfTestOwnsOnlyMicrophoneOperationAndNeverCallsSmsGateway"
```

Expected: FAIL because `activeSettingsOperation` does not exist and the self-test result/lifecycle is not modeled. A failure caused by unrelated compilation is not an acceptable RED result.

---

### Task 2: Add Typed UI State and Bounded Audio Projection

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt`

**Interfaces:**
- Consumes: domain `AudioTelemetry`, `AudioRuntimeState`, `AudioGateState`, `AudioThreatMetadata`, `SensorHealth`, and `AUDIO_CORRELATION_WINDOW_MS`
- Produces: `SettingsOperation`, `MicrophoneSelfTestUiResult`, `AudioUiTelemetry`, `AudioTelemetry.toAudioUiTelemetry(...)`, and `microphoneHealthText(...)`

- [ ] **Step 1: Add RED mapper tests with exact names**

Add these tests before implementation:

```kotlin
@Test fun audioUiProjectionMapsOffWithoutCallingItAvailable()
@Test fun audioUiProjectionCalculatesNonNegativeSampleAgeAndCandidateExpiry()
@Test fun audioUiProjectionRejectsFutureMonotonicTimestamps()
@Test fun audioUiProjectionDropsExpiredCandidate()
@Test fun microphoneHealthTextDistinguishesDetectedUnavailableStaleAndFailed()
```

Required assertions:

- OFF remains `AudioRuntimeState.OFF`; it is never converted to `AVAILABLE`.
- sample age is integer seconds and is `null` when `lastSampleAtMs > elapsedNowMs`.
- a candidate is retained only when its age is within `0..AUDIO_CORRELATION_WINDOW_MS`.
- candidate expiry uses ceiling seconds and never becomes negative.
- `AVAILABLE` and `HEALTHY` both render `Microphone detected`.
- `UNAVAILABLE`, `STALE`, `FAILED`, and `null` render `Microphone unavailable`, `Microphone data stale`, `Microphone failed`, and `Microphone status unknown` respectively.

- [ ] **Step 2: Run mapper tests RED**

Run:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*ProtectionViewModelTest.audioUiProjection*" --tests "*ProtectionViewModelTest.microphoneHealthText*"
```

Expected: FAIL on missing types/functions.

- [ ] **Step 3: Add the exact UI-only models**

Add the following shapes to `ProtectionUiModels.kt`. Use imports from the existing protection package; do not duplicate domain enums.

```kotlin
enum class SettingsOperation {
    CHANGE_SENSITIVITY,
    REFRESH_PERMISSIONS,
    REPLACE_BOT_TOKEN,
    SAVE_SMS_FALLBACK,
    RETRY_SETTINGS,
    RESET_PAIRING,
    MICROPHONE_SELF_TEST,
}

data class MicrophoneSelfTestUiResult(
    val success: Boolean,
    val detailCode: String,
    val observedLevelDbfs: Double?,
    val completedAtMs: Long,
)

data class AudioUiTelemetry(
    val state: AudioRuntimeState = AudioRuntimeState.OFF,
    val detailCode: String? = null,
    val modelReady: Boolean = false,
    val lastSampleAgeSeconds: Long? = null,
    val approximateLevelDbfs: Double? = null,
    val baselineMedianDbfs: Double? = null,
    val baselineP95Dbfs: Double? = null,
    val gateState: AudioGateState = AudioGateState.DISABLED,
    val lastInferenceMs: Long? = null,
    val averageInferenceMs: Long? = null,
    val droppedFrames: Long = 0L,
    val restartCount: Int = 0,
    val currentCandidate: AudioThreatMetadata? = null,
    val candidateExpiresInSeconds: Int? = null,
    val selfTestResult: MicrophoneSelfTestUiResult? = null,
)
```

Extend `ProtectionUiState` additively:

```kotlin
val activeSettingsOperation: SettingsOperation? = null,
val audio: AudioUiTelemetry = AudioUiTelemetry(),
```

Add matching default parameters to `ProtectionUiState.from(...)`. Preserve every existing field and default, including `settingsOperationInFlight`.

- [ ] **Step 4: Implement deterministic safe mapping**

Add internal functions in `ProtectionUiModels.kt` with these exact signatures:

```kotlin
internal fun AudioTelemetry.toAudioUiTelemetry(
    elapsedNowMs: Long,
    selfTestResult: MicrophoneSelfTestUiResult?,
): AudioUiTelemetry

internal fun microphoneHealthText(health: SensorHealth?): String
```

Mapping rules:

```kotlin
val sampleAgeMs = lastSampleAtMs?.let { elapsedNowMs - it }
val sampleAgeSeconds = sampleAgeMs
    ?.takeIf { it >= 0L }
    ?.div(1_000L)

val candidateAgeMs = currentCandidate?.let { elapsedNowMs - it.lastDetectedElapsedMs }
val candidateIsFresh = candidateAgeMs != null &&
    candidateAgeMs in 0L..AUDIO_CORRELATION_WINDOW_MS
val expiresInSeconds = candidateAgeMs
    ?.takeIf { candidateIsFresh }
    ?.let { age -> kotlin.math.ceil((AUDIO_CORRELATION_WINDOW_MS - age) / 1_000.0).toInt() }
```

- Strip raw diagnostic payloads with `substringBefore(':').trim().take(64)` before assigning the UI `detailCode`.
- Retain `currentCandidate` only when `candidateIsFresh`; otherwise set both candidate fields to `null`.
- Copy finite numeric telemetry values only. Map non-finite dBFS/baseline values to `null`.
- `microphoneHealthText()` must use the exact copy specified in Step 1.

- [ ] **Step 5: Run GREEN**

Run:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*ProtectionViewModelTest.audioUiProjection*" --tests "*ProtectionViewModelTest.microphoneHealthText*"
```

Expected: PASS.

- [ ] **Step 6: Review checkpoint without committing**

Run:

```powershell
git diff --check
git diff -- app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt
```

Expected: no whitespace errors; no domain/runtime behavior changed.

---

### Task 3: Give Every Settings Command Exact Ownership and Persist the Self-Test Result

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt`

**Interfaces:**
- Consumes: Task 2 `SettingsOperation`, `MicrophoneSelfTestUiResult`, and `AudioTelemetry.toAudioUiTelemetry(...)`
- Produces: consistent `settingsOperationInFlight + activeSettingsOperation`, persistent sanitized self-test result, and at-most-one-second UI telemetry refresh

- [ ] **Step 1: Complete the ViewModel RED matrix**

Add these exact test names:

```kotlin
@Test fun smsSaveOwnsOnlySaveSmsFallbackOperation()
@Test fun botTokenSaveOwnsOnlyReplaceBotTokenOperation()
@Test fun microphoneSelfTestOwnsOnlyMicrophoneOperationAndNeverCallsSmsGateway()
@Test fun successfulMicrophoneSelfTestPersistsResultWithoutMessage()
@Test fun typedMicrophoneSelfTestFailurePersistsSafeCodeWithoutMessage()
@Test fun microphoneSelfTestExceptionPersistsSafeFailureAndAlwaysClearsOperation()
@Test fun microphoneSelfTestCancellationAlwaysClearsOperationWithoutFakeFailureResult()
@Test fun audioTelemetryProjectsOnlyWhenUiTickerOrOtherUiInputAdvances()
```

Extend the test `FakeRuntime` rather than mocking `ProtectionCoordinator`:

```kotlin
private class FakeRuntime(
    private val blockers: Set<String>,
    initialAudioTelemetry: AudioTelemetry = AudioTelemetry.off(),
    private val microphoneTestAction: suspend () -> MicrophoneSelfTestResult = {
        MicrophoneSelfTestResult(true, "OK", -42.0)
    },
) : ProtectionRuntime {
    private val mutableAudioTelemetry = MutableStateFlow(initialAudioTelemetry)
    override val audioTelemetry: StateFlow<AudioTelemetry> = mutableAudioTelemetry
    var microphoneTestCalls: Int = 0
        private set

    fun emitAudioTelemetry(value: AudioTelemetry) {
        mutableAudioTelemetry.value = value
    }

    override suspend fun testMicrophone(): MicrophoneSelfTestResult {
        microphoneTestCalls += 1
        return microphoneTestAction()
    }
}
```

Add an injectable monotonic clock to ViewModel construction in tests:

```kotlin
elapsedNowMs = { monotonicNowMs }
```

For the projection cadence test, use a `MutableSharedFlow<Unit>` ticker and a mutable `nowMs` test value. Emit 100 distinct raw telemetry values without emitting a ticker and assert the full `uiState` does not produce 100 audio recompositions. Increment the wall-clock test value, emit one ticker, and assert the latest telemetry appears exactly once. The wall-clock value must change because assigning the same value to `currentTimeMs: MutableStateFlow<Long>` does not emit.

- [ ] **Step 2: Run the new tests RED**

Run:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*ProtectionViewModelTest.*Microphone*" --tests "*ProtectionViewModelTest.smsSaveOwnsOnlySaveSmsFallbackOperation" --tests "*ProtectionViewModelTest.botTokenSaveOwnsOnlyReplaceBotTokenOperation" --tests "*ProtectionViewModelTest.audioTelemetryProjectsOnlyWhenUiTickerOrOtherUiInputAdvances"
```

Expected: FAIL on missing operation ownership, discarded result, or unprojected UI telemetry.

- [ ] **Step 3: Add operation ownership to presentation state**

Extend private `PresentationInputs`:

```kotlin
val activeSettingsOperation: SettingsOperation? = null,
val microphoneSelfTestResult: MicrophoneSelfTestUiResult? = null,
```

Change the generic helper signature:

```kotlin
private fun runSettingsCommand(
    operation: SettingsOperation,
    failureCode: GuidanceCode,
    action: suspend () -> Unit,
)
```

Inside `settingsMutex.withLock`, set both fields atomically before the action:

```kotlin
presentation.update {
    it.copy(
        settingsOperationInFlight = true,
        activeSettingsOperation = operation,
    )
}
```

In `finally`, clear both fields atomically:

```kotlin
presentation.update {
    it.copy(
        settingsOperationInFlight = false,
        activeSettingsOperation = null,
    )
}
```

Update every generic-helper call using the fixed mapping table near the top of this plan. Do not leave a call that omits an operation.

If the unused private `runSensitiveSettingsCommand()` remains, give it the same required `operation: SettingsOperation` parameter and the same atomic set/clear behavior. Do not delete or activate it in this task.

- [ ] **Step 4: Implement a dedicated self-test lifecycle**

`runMicrophoneTest()` must not call `runSettingsCommand()` because the generic helper publishes a Snackbar on exceptions. Implement it directly with `scope.launch` and the same `settingsMutex`:

```kotlin
fun runMicrophoneTest() {
    scope.launch {
        settingsMutex.withLock {
            presentation.update {
                it.copy(
                    settingsOperationInFlight = true,
                    activeSettingsOperation = SettingsOperation.MICROPHONE_SELF_TEST,
                )
            }
            try {
                val domainResult = coordinator.testMicrophone()
                presentation.update { current ->
                    current.copy(
                        microphoneSelfTestResult = MicrophoneSelfTestUiResult(
                            success = domainResult.success,
                            detailCode = domainResult.detail.substringBefore(':').trim().take(64),
                            observedLevelDbfs = domainResult.observedLevelDbfs
                                ?.takeIf(Double::isFinite),
                            completedAtMs = nowMs(),
                        ),
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Throwable) {
                presentation.update { current ->
                    current.copy(
                        microphoneSelfTestResult = MicrophoneSelfTestUiResult(
                            success = false,
                            detailCode = "SELF_TEST_EXCEPTION",
                            observedLevelDbfs = null,
                            completedAtMs = nowMs(),
                        ),
                    )
                }
            } finally {
                presentation.update {
                    it.copy(
                        settingsOperationInFlight = false,
                        activeSettingsOperation = null,
                    )
                }
            }
        }
    }
}
```

Required behavior:

- Do not call `publishMessage()` anywhere in this method.
- Do not call `settings.saveSmsFallback()` or `readSettings()`.
- Do not change `ProtectionState`, create an incident, or send anything externally.
- A typed coordinator rejection such as `MICROPHONE_TEST_NOT_ALLOWED_IN_ARMED_HEALTHY` is stored as a failed result.
- Cancellation clears in-flight state but does not fabricate a completed failure result.

- [ ] **Step 5: Project audio into the existing UI state without frame-rate recomposition**

Add this constructor dependency:

```kotlin
private val elapsedNowMs: () -> Long = { System.nanoTime() / 1_000_000L },
```

Do **not** add raw `coordinator.audioTelemetry` as another directly collected input to the full `uiState` combine. The audio pipeline may update much faster than the UI should recompose.

Inside the existing combine projection, read the latest `StateFlow.value` when an existing UI input or the one-second ticker advances:

```kotlin
activeSettingsOperation = inputs.activeSettingsOperation,
audio = coordinator.audioTelemetry.value.toAudioUiTelemetry(
    elapsedNowMs = elapsedNowMs(),
    selfTestResult = inputs.microphoneSelfTestResult,
),
```

Apply the same audio default to `stateIn(initialValue = ...)`. Keep the existing public `val audioTelemetry: StateFlow<AudioTelemetry>` unchanged for source compatibility.

This intentionally bounds routine meter refresh to the existing one-second ticker while allowing self-test result and operation changes to appear immediately through `presentation`.

- [ ] **Step 6: Run ViewModel GREEN**

Run:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*ProtectionViewModelTest"
```

Expected: all `ProtectionViewModelTest` tests pass, including prior timeout/permission/projection tests.

- [ ] **Step 7: Review exact-path diff without committing**

Run:

```powershell
git diff --check
git diff -- app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt
rg -n "runSettingsCommand\(" app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt
```

Expected: every generic call declares an exact `SettingsOperation`; microphone self-test has its own lifecycle and no message publishing.

---

### Task 4: Correct Settings Button Loading and Add Persistent Audio Diagnostics

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt`
- Test: `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt`

**Interfaces:**
- Consumes: `ProtectionUiState.activeSettingsOperation` and `ProtectionUiState.audio`
- Produces: operation-specific button visuals, persistent self-test result, and detailed diagnostics without transient UI

- [ ] **Step 1: Add stable test tags**

Use these exact tags:

```kotlin
const val BOT_TOKEN_SAVE_TAG = "bot_token_save_button"
const val SMS_FALLBACK_SAVE_TAG = "sms_fallback_save_button"
const val MICROPHONE_SELF_TEST_TAG = "microphone_self_test_button"
const val MICROPHONE_SELF_TEST_RESULT_TAG = "microphone_self_test_result"
const val AUDIO_DIAGNOSTICS_CARD_TAG = "audio_diagnostics_card"
```

Declare them as `internal const val` in `SettingsScreen.kt` so the Android test source set can import them. Attach tags to the actual Button/card/result nodes, not to an unrelated parent list. If the private `SettingsCard` composable does not accept a `Modifier`, add only a defaulted `modifier: Modifier = Modifier` parameter in this file and pass it to its root `Card`; do not redesign the component.

- [ ] **Step 2: Add Compose RED tests with exact names**

Add:

```kotlin
@Test fun microphoneLoadingDoesNotMakeSmsOrBotButtonsLookLikeSaving()
@Test fun microphoneTestButtonIsEnabledOnlyWhenDisarmedAndAllCommandsAreIdle()
@Test fun microphoneTestResultIsPersistentInsideDiagnosticsWithoutSnackbar()
@Test fun audioDiagnosticsDistinguishHardwareFromRuntimeState()
```

For `microphoneLoadingDoesNotMakeSmsOrBotButtonsLookLikeSaving()` render Settings with:

```kotlin
settingsOperationInFlight = true
activeSettingsOperation = SettingsOperation.MICROPHONE_SELF_TEST
```

Required assertions:

- `sms_fallback_save_button` still contains `Save SMS fallback`.
- `bot_token_save_button` still contains `Save bot token`.
- Neither button contains its saving text or a progress indicator owned by another operation.
- `microphone_self_test_button` contains `Testing microphone...` and is disabled.

For the persistent-result test, render a successful `MicrophoneSelfTestUiResult`, wait longer than Snackbar duration with the Compose clock, and assert `microphone_self_test_result` remains displayed while no Snackbar node exists.

- [ ] **Step 3: Run Compose tests RED by compiling the Android test source**

Run:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' compileDebugAndroidTestKotlin
```

Expected: FAIL until tags, fields, and rendering exist.

- [ ] **Step 4: Make button loading ownership exact**

Use these booleans inside `SettingsScreen`:

```kotlin
val savingBotToken = state.activeSettingsOperation == SettingsOperation.REPLACE_BOT_TOKEN
val savingSmsFallback = state.activeSettingsOperation == SettingsOperation.SAVE_SMS_FALLBACK
val testingMicrophone = state.activeSettingsOperation == SettingsOperation.MICROPHONE_SELF_TEST
```

Rules:

- Keep existing global `enabled = ... && !state.settingsOperationInFlight` checks for serialization.
- Bot-token spinner/saving copy is controlled only by `savingBotToken`.
- SMS spinner/saving copy is controlled only by `savingSmsFallback`.
- Microphone button content is `Testing microphone...` only when `testingMicrophone`; otherwise it is `Test Microphone (Disarmed only)`.
- Microphone button enabled condition is exactly:

```kotlin
state.protection.state == ProtectionState.DISARMED_ONLINE &&
    !state.protectionOperationInFlight &&
    !state.settingsOperationInFlight
```

- Do not clear or mutate bot-token/SMS Compose input state when a microphone operation starts or completes.

- [ ] **Step 5: Replace the misleading microphone row and render exact diagnostics**

The current `Microphone Status: available` row must be replaced. Under the existing stable list key `audio-diagnostics`, render these rows in this order:

1. `Microphone hardware` -> `microphoneHealthText(state.protection.sensorHealth[SensorKind.MICROPHONE])`
2. `Runtime` -> exact state copy below
3. `Model` -> `Ready` or `Not ready`
4. `Approx. level` -> one decimal `dBFS (approximate)` or `Unavailable`
5. `Baseline median` -> one decimal `dBFS (approximate)` or `Unavailable`
6. `Baseline P95` -> one decimal `dBFS (approximate)` or `Unavailable`
7. `Gate` -> `Disabled`, `Quiet`, or `Open`
8. `Last sample` -> `<N>s ago` or `No sample`
9. `Last inference` -> `<N> ms` or `Unavailable`
10. `Average inference` -> `<N> ms` or `Unavailable`
11. `Dropped frames` -> decimal count
12. `Restarts` -> decimal count
13. `Candidate` -> `None` or `<Category>, <confidence percent>%, expires in <N>s`
14. the local self-test result block
15. the self-test button

Runtime copy is exact:

```kotlin
AudioRuntimeState.OFF -> "Off - starts after arming"
AudioRuntimeState.STARTING -> "Starting"
AudioRuntimeState.CALIBRATING -> "Calibrating - learning background sound"
AudioRuntimeState.LISTENING -> "Listening"
AudioRuntimeState.CLASSIFYING -> "Analyzing candidate"
AudioRuntimeState.DEGRADED -> "Degraded"
AudioRuntimeState.FAILED -> "Failed"
```

Self-test block rules:

- No result and idle: `Not tested yet`
- Running: `Testing microphone...`
- Success: `Test passed` plus formatted completion time and approximate observed dBFS when present
- Failure: `Test failed` plus safe friendly text from the stable code mapping below

Stable self-test detail mapping:

```kotlin
"OK" -> "Microphone captured audio"
"MIC_BUSY_ARMED" -> "Microphone is currently used by protection"
"NOT_INITIALIZED" -> "Microphone could not be initialized"
"START_RECORDING_FAILED" -> "Recording could not start"
"NO_SAMPLES_READ" -> "No audio samples were received"
"INIT_FAILED" -> "Microphone test failed"
"EXCEPTION" -> "Microphone test failed"
"SELF_TEST_EXCEPTION" -> "Microphone test failed"
else -> if (detailCode.startsWith("MICROPHONE_TEST_NOT_ALLOWED_IN_")) {
    "Disarm protection before testing"
} else {
    "Microphone test failed"
}
```

Do not display `detailCode` directly. Do not show raw exception text.

- [ ] **Step 6: Compile and run the focused Android UI tests when a device is available**

Compile gate:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' compileDebugAndroidTestKotlin
```

Device gate:

```powershell
adb devices
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.example.motorcycleantitheftsensor.ui.ProtectionAppScreenTest'
```

Expected: compile passes. If no authorized device is connected, report the device gate as NOT RUN; do not describe compilation as device acceptance.

---

### Task 5: Add a Truthful Stable Microphone Runtime Card to Protection

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/ProtectionScreen.kt`
- Test: `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt`

**Interfaces:**
- Consumes: Task 2 `AudioUiTelemetry` and `microphoneHealthText(...)`
- Produces: one stable home-screen audio card and truthful microphone hardware wording

- [ ] **Step 1: Add RED Protection-screen tests**

Add exact tests:

```kotlin
@Test fun protectionMicrophoneCardShowsOffSeparatelyFromDetectedHardware()
@Test fun protectionMicrophoneCardShowsCalibratingListeningDegradedAndFailed()
@Test fun protectionMicrophoneSensorRowNeverUsesAvailableAsRuntimeCopy()
@Test fun audioCardKeepsStableNodeAcrossRepeatedTelemetryStates()
```

Use exact tag:

```kotlin
const val AUDIO_RUNTIME_CARD_TAG = "audio_runtime_card"
```

For OFF with `SensorHealthState.AVAILABLE`, assert the same card/screen shows both:

- `Microphone detected`
- `Off - starts after arming`

This proves hardware availability is not being used as runtime status.

For `audioCardKeepsStableNodeAcrossRepeatedTelemetryStates()`, host the screen with `mutableStateOf(ProtectionUiState)` and replace only `state.audio` 100 times. Assert:

- the node tagged `audio_runtime_card` remains displayed;
- the selected destination remains Protection;
- the arm/disarm button is not recreated into the opposite action;
- no Snackbar or transient message appears.

- [ ] **Step 2: Run Android-test compile RED**

Run:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' compileDebugAndroidTestKotlin
```

Expected: FAIL until the card/tag/copy exist.

- [ ] **Step 3: Add the stable card without restructuring the screen**

In the existing `LazyColumn`, add exactly one item after `runtime-health` and before `sensor-health-heading`:

```kotlin
item(key = "audio-threat-status") {
    StatusCard(
        title = "Microphone detection",
        modifier = Modifier.testTag(AUDIO_RUNTIME_CARD_TAG),
    ) {
        // Hardware, Runtime, Model, Approx. level, Last sample, Candidate
    }
}
```

If `StatusCard` does not currently accept `modifier`, add a defaulted `modifier: Modifier = Modifier` parameter to that private composable only. Do not move it to another file or redesign other cards.

Render only these concise rows on Protection:

1. `Hardware`
2. `Runtime`
3. `Model`
4. `Relative level`
5. `Last sample`
6. `Candidate`

Use the same exact formatting functions/copy as Settings. Do not add the self-test button or self-test result to Protection; those belong only to Settings.

- [ ] **Step 4: Correct the existing generic microphone sensor row**

Keep all `SensorKind` entries and stable keys. Only special-case the displayed health text for `SensorKind.MICROPHONE`:

```kotlin
val healthText = if (sensor == SensorKind.MICROPHONE) {
    microphoneHealthText(health)
} else {
    health.healthText()
}
```

Do not change health wording for other sensors. Do not remove the microphone from the generic Sensor health list.

- [ ] **Step 5: Compile and run focused UI tests**

Run:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' compileDebugAndroidTestKotlin
adb devices
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.example.motorcycleantitheftsensor.ui.ProtectionAppScreenTest'
```

Expected: compile and, when a device is connected, all focused UI tests pass.

- [ ] **Step 6: Exact-path diff checkpoint without committing**

Run:

```powershell
git diff --check
git diff -- app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/ProtectionScreen.kt app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt
```

Expected: no change to navigation, unrelated cards, SMS logic, Telegram, GPS, or audio runtime.

---

### Task 6: Full Verification and Real-Device Acceptance

**Files:**
- Verify: all allowed files
- Do not edit new production paths during this task

**Interfaces:**
- Consumes: completed Tasks 1-5
- Produces: host evidence, exact-APK device evidence, manual acceptance record, and remaining-risk statement

- [ ] **Step 1: Run focused host tests**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*ProtectionViewModelTest"
```

Expected: PASS.

- [ ] **Step 2: Run the full host and compile gates sequentially**

Run each command only after the previous command completes:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' assembleDebug
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' compileDebugAndroidTestKotlin
```

Expected: all three pass. Record test counts from the XML results; do not state only `BUILD SUCCESSFUL`.

- [ ] **Step 3: Audit scope and forbidden changes**

Run:

```powershell
git status --short
git diff --check
git diff -- app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/ProtectionScreen.kt app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt
rg -n -C 24 "fun runMicrophoneTest" app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt
rg -n "testmic|sendMessage|sendSms" app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/ProtectionScreen.kt
```

Expected:

- compare the exact-path diff with the Task 1 baseline and confirm this plan's implementation changes are confined to allowed paths; do not misclassify unrelated pre-existing dirty files as changes made by this plan;
- no `/testmic` command;
- `runMicrophoneTest()` contains no `publishMessage`, SMS call, Telegram call, or event write;
- unrelated pre-existing dirty files remain untouched by this plan.

- [ ] **Step 4: Install the exact freshly built APK**

Run sequentially:

```powershell
adb devices
Get-FileHash -Algorithm SHA256 'app\build\outputs\apk\debug\app-debug.apk'
adb install -r 'app\build\outputs\apk\debug\app-debug.apk'
adb shell am force-stop com.example.motorcycleantitheftsensor
adb shell monkey -p com.example.motorcycleantitheftsensor -c android.intent.category.LAUNCHER 1
```

Expected: one authorized device, successful install, and app launch without `AndroidRuntime` crash. Record the APK SHA-256 with the acceptance evidence.

- [ ] **Step 5: Run the real-device manual matrix**

Use the freshly installed APK and verify each item explicitly:

1. Disarm protection.
2. Open Settings and confirm microphone hardware says `Microphone detected` or a truthful unavailable/failed status; it must not imply listening.
3. Enter disposable test text into the SMS destination and key fields but do not press Save.
4. Press `Test Microphone (Disarmed only)`.
5. During the five-second test, confirm the microphone button alone says `Testing microphone...`.
6. Confirm the SMS button continues to say `Save SMS fallback`; it may be disabled during serialization but must not show its saving spinner/text.
7. Confirm the bot-token button continues to say `Save bot token`; it must not show its saving spinner/text.
8. Confirm the unsaved SMS destination remains present and no SMS configuration success message appears.
9. Confirm no Snackbar/top popup, Telegram message, SMS, notification, or Events entry is created by the self-test.
10. Confirm the persistent Settings result shows pass/fail, safe explanation, completion time, and approximate dBFS when supplied.
11. Leave Settings and return; confirm the result remains for the current ViewModel lifetime.
12. Open Protection while disarmed; confirm the microphone card shows hardware separately from `Off - starts after arming`.
13. Arm protection; confirm runtime progresses truthfully through available states such as `Starting`, `Calibrating - learning background sound`, and `Listening` without screen flashing or destination changes.
14. Disarm; confirm runtime returns to `Off - starts after arming`.
15. While armed, confirm the microphone self-test button is disabled and no second recorder can start.

Any missing or failed item means device acceptance is FAIL, even if host tests pass.

- [ ] **Step 6: Inspect crash evidence after the manual matrix**

Run:

```powershell
adb logcat -d -v threadtime AndroidRuntime:E '*:S'
```

Expected: no new crash attributable to the tested APK. Do not claim microphone capture quality solely from absence of a crash.

- [ ] **Step 7: Final report without commit**

The implementer must report:

- files changed, exactly;
- focused and full test commands;
- unit-test counts and failures/errors;
- Android-test compile result;
- connected-device test result or `NOT RUN`;
- APK SHA-256 installed;
- every manual matrix item as PASS/FAIL;
- remaining risks;
- confirmation that no commit was created.

Do not mark the task complete if the exact freshly built APK was not installed and manually tested. Report host completion and device acceptance separately.

---

## Final Acceptance Checklist

- [ ] Pressing Test Microphone never calls or visually impersonates SMS save.
- [ ] Pressing Test Microphone never visually impersonates bot-token save.
- [ ] Global settings serialization remains intact.
- [ ] Self-test remains disarmed-only and serialized with arm/disarm through the coordinator.
- [ ] Self-test result persists in Settings without Snackbar, popup, Telegram, SMS, notification, or Events.
- [ ] Microphone hardware state and audio runtime state are separate concepts in both active screens.
- [ ] No active microphone UI uses `Available` to mean recording/listening.
- [ ] `AudioRuntimeState.OFF` is shown as off, not unavailable and not listening.
- [ ] Audio UI refresh is bounded by the existing one-second UI ticker rather than raw audio-frame telemetry.
- [ ] Candidate/sample ages use a monotonic clock and reject future timestamps.
- [ ] Raw exception text is not stored or rendered in UI state.
- [ ] Protection destination and card identity remain stable across repeated audio updates.
- [ ] No domain detector, classifier, fusion, Telegram, SMS, GPS, persistence, or Gradle file changed.
- [ ] Focused tests, full host tests, APK build, Android-test compile, and exact-APK device acceptance are reported separately.
- [ ] No commit is created without separate owner authorization.
