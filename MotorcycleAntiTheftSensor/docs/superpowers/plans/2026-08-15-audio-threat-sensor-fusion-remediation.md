# Audio Threat Sensor Fusion Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Repair the current uncommitted audio-threat implementation so that audio remains RAM-only and silent until independently confirmed, GPS movement reaches fusion exactly once, recorder recovery is bounded and leak-free, and the approved UI/Telegram/documentation contracts are complete.

**Architecture:** Keep the existing `AudioPeakDetector` facade, `AndroidProtectionRuntime`, `ProtectionCoordinator`, `IncidentEngine`, and Live Pursuit architecture. One graph-owned `AudioThreatCandidateBuffer` is shared with the audio pipeline and consumed only after a current-session, non-ignored fusion result. `LivePursuitCoordinator` emits one non-blocking confirmed-movement callback outside its mutex; normal location samples never count as confirmed movement.

**Tech Stack:** Kotlin, Android `AudioRecord`, Kotlin coroutines/Flow, MediaPipe Tasks Audio `1.0.0`, YAMNet `yamnet.tflite`, JUnit4, kotlinx-coroutines-test, Jetpack Compose UI tests, Gradle/JBR.

## Global Constraints

- Work from repository `D:\security\MotorcycleAntiTheftSensor` at reviewed HEAD `868e2b7f24feb161a4a1185c85db322c26e7f88d` and branch `feature/motorcycle-guard-protection` unless the owner explicitly supplies a newer checkpoint.
- The starting tree already contains the reviewed partial implementation. Do not run `git reset`, `git clean`, `git checkout --`, `git stash`, broad `git add .`, or delete unrelated files.
- `AI_WORKFLOW.md` was absent during review. Do not invent its contents; record `MISSING` in evidence and continue under `AGENTS.md` plus this plan.
- Battery optimization is out of scope. Do not add Doze, duty cycling, charging-only capture, battery UI, or power-management dependencies.
- Keep MediaPipe Tasks Audio pinned to `1.0.0`. Do not add repositories, classifiers, models, ABI filters, or dependencies.
- Preserve the exact YAMNet asset: `4,126,810` bytes; SHA-256 `4D8B4A53282DC83EF04E3E7DBC4FBC98082E34E44ED798E16C3A0CDD4C584FAF`.
- Capture format remains mono PCM16 at `16,000 Hz`; every classifier input is exactly `15,600` normalized float samples.
- Raw PCM is RAM-only. Never persist, log, encode, attach, upload, include in Telegram, or expose through UI/accessibility semantics.
- Unconfirmed audio never creates or enriches an incident, Events history, Telegram/SMS/Live Location, Snackbar, top popup, notification, or navigation event.
- Candidate lifetime is exactly `15,000 ms`, maximum eight entries, session-scoped, and cleared on disarm, new arm, service stop, process restart, pipeline restart, and expiry.
- Correlation uses monotonic elapsed time and accepts exactly `15,000 ms`; `15,001 ms` is rejected.
- Preserve the approved fusion matrix from `2026-08-15-audio-threat-sensor-fusion-hardening.md:131-148` without threshold tuning.
- Preserve the existing five-minute lockout after three incorrect local unlock attempts. Authenticator/QR/TOTP work is out of scope.
- Preserve `/pair <code>`, `/disarm`, GPS thresholds, GPS persistence, Telegram Live Location ordering, TLS pinning, SMS rules, and all existing authorization checks.
- Remove the unapproved `/testmic` Telegram command. The five-second microphone self-test is local Settings UI only.
- Do not use popup/Snackbar/Telegram/Events for self-test results; keep the result in the Settings diagnostics card.
- Do not change public APIs, copy, defaults, error text, settings behavior, or `SnapshotProjectionGate` unless a task below explicitly names the change.
- Do not commit while the tree contains the pre-existing uncommitted partial implementation unless the owner explicitly requests a commit. If authorized, stage exact files only after all gates pass.

## Reviewed Starting Failures

1. `ProtectionRuntimeGraph` does not wire confirmed GPS movement into fusion.
2. `LivePursuitCoordinator` invokes the new callback inside `stateMutex`.
3. `IncidentEngine` adds unconfirmed microphone evidence to an already-open incident.
4. `IncidentEngine.close()` and `interrupted()` leave precursors behind when no incident is active.
5. The shared candidate buffer is not graph-owned and `consume()` is unused.
6. Recorder error codes, rebuilds, generation rejection, classifier closure, drop counting, and adaptation freezes are incomplete.
7. `/testmic` was added contrary to the frozen command contract, while `/status` and final `/arm` lack audio summaries.
8. `AudioUiTelemetry`, the Protection microphone card, full Settings diagnostics, persistent self-test result, and flicker tests are missing.
9. v4 optional audio parsing can desynchronize the stream and can swallow structural truncation.
10. Technician files were created in `docs/superpowers/guides/` instead of updating the approved root `docs/` artifacts.

## Allowed File Responsibility Map

Production changes are limited to these paths:

- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/AudioThreatModels.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/audio/AudioThreatCandidateBuffer.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/audio/AudioThreatPipeline.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/audio/RobustAudioCalibrator.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/AudioPeakDetector.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntime.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentEngine.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/FileIncidentRepository.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinator.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/RemoteCommand.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramCommandHandler.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusFormatter.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreen.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/ProtectionScreen.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/Navigation.kt`
- `docs/sensor-fusion-technician-guide-th.html`
- `docs/sensor-fusion-technician-guide-th.pdf`
- `docs/superpowers/status/2026-08-15-audio-threat-sensor-fusion-evidence.md`

Matching tests named in the tasks are allowed. Do not modify `build.gradle.kts`, `libs.versions.toml`, the YAMNet asset, GPS policy/transport/store files, authentication, SMS, TLS, or unrelated UI files. Stop and request a written amendment if another production file appears necessary.

---

### Task 0: Freeze the Reviewed Dirty Baseline

**Files:**
- Modify: `docs/superpowers/status/2026-08-15-audio-threat-sensor-fusion-evidence.md`
- Read only: current worktree and original hardening plan

**Interfaces:**
- Consumes: reviewed HEAD and dirty implementation.
- Produces: an auditable pre-remediation inventory; no production behavior change.

- [ ] **Step 1: Verify repository identity and stop on drift**

```powershell
git rev-parse --show-toplevel
git branch --show-current
git rev-parse HEAD
git status --short
git diff --check
```

Expected root is `D:/security`, branch is `feature/motorcycle-guard-protection`, and HEAD is `868e2b7f24feb161a4a1185c85db322c26e7f88d`. If HEAD differs, stop before editing and ask the owner whether this plan should be rebased onto the new checkpoint.

- [ ] **Step 2: Record the current verification baseline**

Add a `Remediation baseline` section containing:

```markdown
- Review disposition: NOT ACCEPTED; remediation required.
- Fresh full host gate before remediation: 390 tests, 0 failures, 0 errors.
- Focused GPS regression: 90 tests, 0 failures; AudioMovementFusionIntegrationTest absent.
- Model asset in APK: one entry, 4,126,810 bytes, approved SHA-256.
- Physical acceptance: NOT EXECUTED; prior evidence proves install/launch only.
- AI_WORKFLOW.md: MISSING.
```

- [ ] **Step 3: Confirm no source was changed by this task**

```powershell
git diff --name-only
```

Expected: only the evidence document differs beyond the already-recorded dirty baseline.

---

### Task 1: Restore the Frozen Telegram and ViewModel Scope

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/RemoteCommand.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramCommandHandler.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramCommandHandlerTest.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt`

**Interfaces:**
- Consumes: existing command parser and pre-audio ViewModel behavior at HEAD.
- Produces: no `/testmic`; restored non-audio UI behavior; self-test presentation is deferred to Task 7.

- [ ] **Step 1: Write command-contract tests**

Add exact assertions:

```kotlin
@Test fun testMicIsNotARemoteCommand() {
    assertEquals(RemoteCommand.Unknown, RemoteCommand.parse("/testmic"))
}

@Test fun existingCommandParsingIsUnchanged() {
    assertEquals(RemoteCommand.Arm, RemoteCommand.parse("/arm"))
    assertEquals(RemoteCommand.Disarm, RemoteCommand.parse("/disarm"))
    assertEquals(RemoteCommand.Status, RemoteCommand.parse("/status"))
    assertEquals(RemoteCommand.Sensitivity(3), RemoteCommand.parse("/sensitivity 3"))
}
```

Remove the test that expects `/testmic` to execute.

- [ ] **Step 2: Run RED**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*TelegramCommandHandlerTest"
```

Expected: FAIL because `/testmic` is currently parsed and handled.

- [ ] **Step 3: Remove only the unapproved command path**

Delete `RemoteCommand.TestMic`, its parser branch, its handler branch, and its matching test/help/document references. Do not change authorization, polling, timeout, `/arm`, `/disarm`, `/status`, `/pair`, `/sensitivity`, or `/decode` behavior.

- [ ] **Step 4: Restore unrelated ViewModel behavior from HEAD**

Keep only imports/members needed by the approved audio UI. Restore these exact pre-audio behaviors from `git show HEAD:MotorcycleAntiTheftSensor/app/src/main/java/.../ProtectionViewModel.kt`:

```kotlin
private fun nextCommandId(): String = "ui-${UUID.randomUUID()}"
```

Restore sensitivity placeholder `1`, `withContext(dispatcher) { settings.read(missingPermissions) }`, original `Unable to load event history` / `Unable to load settings` fallbacks, and original `publishResult()` mapping in which `ARMING`, `ARMED_HEALTHY`, and `ARMED_DEGRADED` map to `COMMAND_ARM_APPLIED`. Remove `runMicrophoneTest()` popup publishing; Task 7 will add a card-owned result.

- [ ] **Step 5: Run GREEN and diff audit**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*TelegramCommandHandlerTest" --tests "*ProtectionViewModelTest"
git diff -- app/src/main/java/com/example/motorcycleantitheftsensor/telegram/RemoteCommand.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt
```

Expected: tests pass; no `/testmic`; no unrelated change to settings, command IDs, result mapping, defaults, or error text.

---

### Task 2: Make the Audio Pipeline Generation-Safe and Recoverable

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/AudioThreatModels.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/audio/AudioThreatPipeline.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/audio/RobustAudioCalibrator.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/AudioPeakDetector.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/audio/AudioThreatPipelineTest.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/AudioPeakDetectorSourceContractTest.kt`

**Interfaces:**
- Consumes: `AudioRecorderBackend`, `AudioThreatClassifier`, `AudioThreatCandidateBuffer`, injected clocks/dispatcher/delay.
- Produces: one generation-owned recorder/classifier pair, bounded queue, explicit recovery, truthful telemetry, and reset/freeze callbacks.

Use these exact contracts:

```kotlin
enum class AudioGateState { DISABLED, QUIET, OPEN }

class AudioThreatPipeline(
    private val recorderBackendFactory: () -> AudioRecorderBackend,
    private val classifierFactory: () -> AudioThreatClassifier,
    private val candidateBuffer: AudioThreatCandidateBuffer,
    private val onCandidate: (SensorObservation) -> Unit,
    private val onTelemetry: (AudioTelemetry) -> Unit,
    private val onHealthFailure: (String) -> Unit,
    private val onCandidatesReset: () -> Unit,
    // retain deterministic dispatcher/clock/delay injection
) {
    fun start(armedSessionId: String): Boolean
    fun stop()
    fun freezeAdaptation(nowElapsedMs: Long)
    suspend fun runSelfTest(durationMs: Long = 5_000L): MicrophoneSelfTestResult
}
```

Add `gateState: AudioGateState` to `AudioTelemetry`; `AudioTelemetry.off()` uses `DISABLED`.

- [ ] **Step 1: Replace the four-test pipeline suite with the required failure matrix**

Add named tests that assert:

```kotlin
startReportsStartingUntilRecorderIsActuallyRecording()
recordingFalseTransitionsToFailedAndReportsHealth()
deadObjectReleasesAndRebuildsRecorder()
invalidOperationFailsAttemptAndRebuilds()
badValueFailsAttemptAndRebuilds()
fiveSecondsWithoutPositiveSamplesRebuilds()
retryScheduleIsOneTwoFourThenSixtySeconds()
queueCapacityIsThreeAndDropsOldest()
droppedFrameCounterIncrementsForEveryEviction()
inferenceConcurrencyNeverExceedsOne()
classifierReceivesExactly15600NormalizedSamples()
noInferenceDuringFirstTenSeconds()
classifyingStateReturnsToListeningOrDegraded()
classifierExceptionReportsBoundedFailureAndDoesNotEmitCandidate()
stopRejectsAllOldGenerationCallbacks()
restartWaitsForPriorRecorderReleaseBeforeNewRecorderStarts()
everyRecorderAndClassifierIsClosedExactlyOnce()
adaptationFreezesForSuspiciousActivityWindow()
telemetryIsEmittedAtMostFourTimesPerSecond()
startStopRestartOneHundredTimesHasNoOverlappingOwner()
```

Fakes must count `start`, `read`, `stop`, `release`, concurrent classifier calls, and close calls. Do not use real time or `Thread.sleep`.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*AudioThreatPipelineTest" --tests "*AudioPeakDetectorSourceContractTest"
```

Expected: failures for error-code handling, restart ownership, resource closure, drop counting, freeze state, and truthful model state.

- [ ] **Step 3: Implement generation-owned resources**

Replace global `isRunning` loop decisions with a monotonically increasing generation and a per-run record:

```kotlin
private data class ActiveRun(
    val generation: Long,
    val sessionId: String,
)

private var activeRun: ActiveRun? = null
private var activeJob: Job? = null

private fun isCurrent(generation: Long, sessionId: String): Boolean =
    activeRun?.generation == generation && activeRun?.sessionId == sessionId
```

Every telemetry/candidate/health callback checks `isCurrent`. `stop()` invalidates the generation first, clears candidates, invokes `onCandidatesReset`, cancels the job, and closes the current backend to unblock a blocking read. A new generation must join the prior job before creating/starting another recorder.

- [ ] **Step 4: Implement explicit recorder recovery**

Interpret reads exactly:

```kotlin
when {
    readCount > 0 -> processSamples(readCount)
    readCount == AudioRecord.ERROR_DEAD_OBJECT -> rebuild("DEAD_OBJECT")
    readCount == AudioRecord.ERROR_INVALID_OPERATION -> rebuild("INVALID_OPERATION")
    readCount == AudioRecord.ERROR_BAD_VALUE -> rebuild("BAD_VALUE")
    noPositiveSamplesForMs >= 5_000L -> rebuild("STALE_MIC_DATA")
}
```

Each rebuild closes the old recorder exactly once, increments `restartCount`, clears candidates/precursors through `onCandidatesReset`, waits `1_000`, `2_000`, `4_000`, then `60_000 ms` for subsequent failures, creates a new backend, verifies `initialized`, calls `start()`, verifies `recording`, and recalibrates from zero. Disarm cancels pending backoff.

- [ ] **Step 5: Make queue/drop and classifier ownership explicit**

Use a capacity-three channel without automatic `DROP_OLDEST`. On failed `trySend`, remove exactly one oldest frame with `tryReceive`, increment `droppedFrames`, then enqueue the new frame. Create one classifier per generation via `classifierFactory`; set `modelReady=true` only after creation; close it exactly once when the generation ends.

- [ ] **Step 6: Preserve gate onset and freeze calibration**

Carry `AudioGateDecision.Classify.onsetElapsedMs` alongside the sample window instead of replacing it with inference-start time:

```kotlin
private data class InferenceFrame(
    val samples: FloatArray,
    val onsetElapsedMs: Long,
    val capturedLevelDbfs: Double,
)
```

Set metadata `onsetElapsedMs` from the frame. `freezeAdaptation(now)` prevents the next 30-second adaptation when accepted vibration, confirmed movement, charger change, candidate, mapped threat, or active incident occurs. Never pass a hard-coded `false` to `adaptWindow`.

- [ ] **Step 7: Make the facade truthful**

`AudioPeakDetector` passes `classifierFactory = { YamNetAudioThreatClassifier(context) }`; it must not replace model failure with `AudioThreatClassifier { emptyList() }`. Forward stable health codes and candidate-reset/freeze calls. Keep `startListening`, `stopListening`, and `testMicrophone` public compatibility.

- [ ] **Step 8: Run GREEN and source contracts**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*AudioThreatPipelineTest" --tests "*AudioPeakDetectorSourceContractTest" --tests "*RobustAudioCalibratorTest" --tests "*AudioSignalGateTest"
rg -n "ERROR_DEAD_OBJECT|ERROR_INVALID_OPERATION|ERROR_BAD_VALUE|CLASSIFYING|restartCount|close\(" app/src/main/java/com/example/motorcycleantitheftsensor/sensor
```

Expected: all focused tests pass and every required recovery/state token has an exercised production path.

---

### Task 3: Wire Runtime Health, Reset Hooks, and Serialized Self-Test

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntime.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntimeTest.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinatorTest.kt`

**Interfaces:**
- Consumes: Task 2 `freezeAdaptation` and `onCandidatesReset`.
- Produces: audio failure/recovery health without stopping other sensors, lifecycle reset propagation, and arm/self-test mutual exclusion.

- [ ] **Step 1: Write runtime RED tests**

Add tests proving:

```kotlin
audioStartFailureDegradesOnlyMicrophoneAndKeepsOtherDetectorsRunning()
audioRecoveryRestoresMicrophoneHealth()
acceptedVibrationFreezesAudioAdaptation()
chargerDisconnectFreezesAudioAdaptation()
stopClearsCandidatesAndCancelsRetry()
newArmPassesOneNewNonblankSessionId()
```

- [ ] **Step 2: Write coordinator concurrency RED tests**

```kotlin
@Test fun armWaitsUntilDisarmedMicrophoneSelfTestCompletes()
@Test fun selfTestRechecksDisarmedStateInsideCommandMutex()
@Test fun selfTestNeverChangesProtectionStateOrCreatesIncident()
```

The fake self-test blocks on a deferred signal; start `arm()` concurrently and assert detector start count remains zero until self-test releases.

- [ ] **Step 3: Run RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*AndroidProtectionRuntimeTest" --tests "*ProtectionCoordinatorTest"
```

- [ ] **Step 4: Add narrow runtime hooks**

Add safe defaults to test-fake interfaces:

```kotlin
interface AndroidDetectorSet {
    fun freezeAudioAdaptation(nowElapsedMs: Long) {}
}
```

In `AndroidProtectionRuntime.handleObservation`, call the freeze hook only after `ObservationDecision.Accepted` for vibration or charger-change observations. Do not freeze on ordinary battery/temperature/location samples. Wire pipeline health callbacks to `SensorHealth[MICROPHONE]` without turning microphone failure into a required arming blocker.

- [ ] **Step 5: Serialize self-test with arm/disarm**

Implement `ProtectionCoordinator.testMicrophone()` inside `commandMutex.withLock` and check `DISARMED_ONLINE` after acquiring the mutex. Hold the mutex until the five-second diagnostic finishes so an arm cannot create a second recorder. Preserve the existing typed rejection result for every non-disarmed state.

- [ ] **Step 6: Run GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*AndroidProtectionRuntimeTest" --tests "*ProtectionCoordinatorTest" --tests "*SensorObservationProcessorTest"
```

Expected: microphone failure is a bounded degradation; accelerometer/light/power/location continue; self-test and arm never overlap.

---

### Task 4: Enforce Confirmed-Only Symmetric Fusion and Safe Persistence

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentEngine.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/FileIncidentRepository.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/IncidentEngineTest.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/FileIncidentRepositoryTest.kt`

**Interfaces:**
- Consumes: typed `AudioThreatMetadata` observations and normal accepted sensors.
- Produces: `IncidentUpdate.Ignored` until the exact matrix confirms audio; a dedicated confirmed-movement entry point; structurally safe v4 records.

Use this exact additional method because an ordinary location sample is not proof of movement:

```kotlin
@Synchronized
fun onConfirmedMovement(
    observation: SensorObservation,
    protectionState: ProtectionState,
    location: IncidentLocation?,
): IncidentUpdate
```

Require `observation.kind == SensorKind.LOCATION`; only this method may populate the movement precursor. `accept()` must not treat supplemental/current location samples as confirmed movement.

- [ ] **Step 1: Write the complete fusion RED matrix**

Add both event orders where applicable and assert the exact result:

```text
IMPACT + vibration <=15s -> WARNING
repeated IMPACT + continuing vibration -> CRITICAL
BREAKING + vibration -> CRITICAL
POWER_TOOL + vibration -> CRITICAL
POWER_TOOL + light + vibration -> CRITICAL
METAL_TAMPER + vibration -> WARNING
ENGINE_START + vibration -> CRITICAL
ENGINE_START/ENGINE_RUNNING + confirmed movement -> CRITICAL
any candidate + charger disconnect -> CRITICAL
any candidate + confirmed movement -> CRITICAL
audio only -> Ignored
audio + light only -> Ignored
ordinary LOCATION sample + audio -> Ignored
ENGINE_RUNNING without own vibration/confirmed movement -> Ignored
exactly 15000ms -> confirmed
15001ms -> Ignored and expired
```

Also add:

```kotlin
audioDuringUnrelatedActiveIncidentIsNotAppendedOrPersisted()
activeIncidentIsEnrichedOnlyWhenRequiredCorroboratorMatches()
closeWithoutActiveIncidentStillClearsAllPrecursors()
interruptedWithoutActiveIncidentStillClearsAllPrecursors()
audioPipelineResetClearsAudioPrecursors()
precursorCollectionNeverExceedsEight()
nonActiveProtectionStatesRejectAndClearAudio()
```

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*IncidentEngineTest"
```

- [ ] **Step 3: Implement confirmed-only state transitions**

When no exact corroborator exists, store a bounded precursor and return `IncidentUpdate.Ignored`. When an incident is already active, do not append microphone evidence until a matching vibration, charger disconnect, or confirmed-movement precursor exists within `15_000 ms`. `updatedClassification()` must never escalate solely because `observation.kind == MICROPHONE`.

Create private clear functions and call them before checking whether an active incident exists:

```kotlin
private fun clearAudioPrecursors()
private fun clearAllPrecursors()

fun close(...): IncidentUpdate.Closed? {
    clearAllPrecursors()
    val active = activeIncident ?: return null
    // close active incident
}
```

Expose this exact internal reset method for Task 3/5 graph wiring; it clears only pending audio and never closes or sends an incident:

```kotlin
@Synchronized
fun clearPendingAudio()
```

- [ ] **Step 4: Write repository RED tests**

Add handcrafted v4 fixtures for:

```kotlin
invalidCategoryConsumesEntireOptionalBlockAndPreservesFollowingFields()
invalidConfidenceLoadsAudioAsNullAndPreservesIncident()
invalidOccurrenceCountLoadsAudioAsNullAndPreservesIncident()
truncatedAudioBlockThrowsIOExceptionWithTruncatedCause()
mixedV3AndV4FixturesRemainReadable()
```

- [ ] **Step 5: Fix v4 parsing without swallowing EOF**

Read every primitive first, outside validation handling:

```kotlin
val categoryToken = input.readUTF()
val confidence = input.readDouble()
val deltaDb = input.readDouble()
val firstDetected = input.readLong()
val lastDetected = input.readLong()
val count = input.readInt()
val onset = input.readLong()
val coherent = input.readBoolean()

val audioThreat = runCatching {
    AudioThreatMetadata(
        category = AudioThreatCategory.valueOf(categoryToken),
        confidence = confidence,
        loudnessDeltaDb = deltaDb,
        firstDetectedElapsedMs = firstDetected,
        lastDetectedElapsedMs = lastDetected,
        occurrenceCount = count,
        onsetElapsedMs = onset,
        onsetCoherent = coherent,
    )
}.getOrNull()
```

This allows invalid optional metadata to become null after consuming its complete block, while `EOFException` from primitive reads still reaches `readRecords()` and becomes `IOException("Truncated incident file", cause)`.

- [ ] **Step 6: Run GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*IncidentEngineTest" --tests "*FileIncidentRepositoryTest"
```

---

### Task 5: Wire One Shared Candidate Buffer and Confirmed GPS Movement

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt`
- Modify narrowly: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinator.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/AudioPeakDetector.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinatorTest.kt`
- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/AudioMovementFusionIntegrationTest.kt`

**Interfaces:**
- Consumes: Task 4 `onConfirmedMovement()` and Task 2/3 reset/freeze hooks.
- Produces: one callback outside the GPS lock, one graph-owned candidate buffer, and deterministic consume-on-confirm.

The Live Pursuit constructor contract is exactly:

```kotlin
private val onMovementConfirmed: (TrackedLocationFix) -> Unit = {},
```

- [ ] **Step 1: Add complete GPS callback RED tests**

Assert one callback for the first current-session `MovementDecision.Confirmed`; zero callbacks for unusable, inside-anchor, candidate, stale, disarmed, expired-generation, and post-disarm queued fixes. Add a lock-safety test whose callback re-enters a read path guarded by `stateMutex`; it must complete without deadlock.

- [ ] **Step 2: Add the missing integration test**

`AudioMovementFusionIntegrationTest` must prove:

```kotlin
audioBeforeConfirmedMovementWithin15SecondsCreatesOneCriticalIncident()
confirmedMovementBeforeAudioWithin15SecondsCreatesOneCriticalIncident()
audioAndMovementAt15001MillisecondsDoNotCreateIncident()
ordinaryLocationObservationNeverConfirmsAudio()
confirmedMovementStartsLivePursuitExactlyOnce()
staleSessionMovementDoesNotConsumeCandidateOrCreateIncident()
confirmedCandidateIsRemovedFromBufferAfterIncidentUpdate()
ignoredCandidateRemainsUntilExpiry()
```

- [ ] **Step 3: Run RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*LivePursuitCoordinatorTest" --tests "*AudioMovementFusionIntegrationTest"
```

- [ ] **Step 4: Move the callback outside `stateMutex`**

Inside `onLocationFix`, capture a local value only:

```kotlin
var confirmedFixToNotify: TrackedLocationFix? = null

stateMutex.withLock {
    // existing checks and state updates
    if (decision is MovementDecision.Confirmed && !pursuitAttempted) {
        pursuitAttempted = true
        confirmedFixToNotify = fix
        // existing start request construction remains unchanged
    }
}

confirmedFixToNotify?.let(onMovementConfirmed)
```

Do not change GPS thresholds, ordering, persistence, expiry, transport, retry floor, or Live Location calls.

- [ ] **Step 5: Make the buffer graph-owned**

Construct exactly one `AudioThreatCandidateBuffer` in `ProtectionRuntimeGraph`, inject it into `PlatformAndroidDetectorSet`, then into `AudioPeakDetector`, then into `AudioThreatPipeline`. Remove the facade-created fallback from the production graph path; test-only defaults may remain if source compatibility requires them.

Use this constructor boundary:

```kotlin
class PlatformAndroidDetectorSet(
    context: Context,
    private val location: LocationObservationProvider,
    private val onObservation: (SensorObservation) -> Unit,
    audioCandidateBuffer: AudioThreatCandidateBuffer,
    onAudioCandidatesReset: () -> Unit,
) : AndroidDetectorSet
```

`onAudioCandidatesReset` queues `incidentEngine.clearPendingAudio()` under `incidentMutex`; it performs no persistence or delivery.

- [ ] **Step 6: Route confirmed movement into IncidentEngine**

The callback creates a `SensorObservation(kind = LOCATION)` from the confirmed `TrackedLocationFix` timestamps and calls `incidentEngine.onConfirmedMovement()` under `incidentMutex` only after validating the captured incident epoch/current armed session. It also freezes audio adaptation. The callback itself performs no Telegram, persistence, or network I/O; it only queues graph work.

- [ ] **Step 7: Consume only confirmed current-session candidates**

After a non-ignored engine update contains typed audio evidence, obtain the current nonblank armed session ID and call:

```kotlin
candidateBuffer.consume(audioEvidence.audioThreat.category, armedSessionId)
```

Do not consume on `Ignored`, stale incident epoch, or rejected session. The next telemetry tick, bounded to 250 ms, must publish `currentCandidate = null`. Call the engine audio-reset hook whenever pipeline start/rebuild/stop clears the buffer.

- [ ] **Step 8: Run the complete GPS regression**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*LivePursuitCoordinatorTest" --tests "*MovementDisplacementPolicyTest" --tests "*LocationObservationProviderTest" --tests "*TelegramLiveLocationApiTest" --tests "*TelegramLiveLocationTransportTest" --tests "*AudioMovementFusionIntegrationTest"
```

Expected: six test suites are present and pass. Listing only the five legacy suites is a failure even if Gradle exits zero.

---

### Task 6: Add Only the Approved Telegram Audio Summaries

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusFormatter.kt`
- Modify narrowly: `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramCommandHandler.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusFormatterTest.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramCommandHandlerTest.kt`

**Interfaces:**
- Consumes: `ProtectionCoordinator.audioTelemetry.value` and monotonic now.
- Produces: requested `/status` diagnostics and one final successful `/arm` audio line; no new command.

Use typed formatting:

```kotlin
fun formatAudioSummary(telemetry: AudioTelemetry, nowElapsedMs: Long): String
fun format(
    snapshot: ProtectionSnapshot,
    telemetry: AudioTelemetry = AudioTelemetry.off(),
    nowElapsedMs: Long = 0L,
): String
```

Add `elapsedClockMs: () -> Long = { System.nanoTime() / 1_000_000L }` to `TelegramCommandHandler`; tests inject a deterministic value.

- [ ] **Step 1: Write RED formatter tests**

Cover OFF, STARTING, CALIBRATING, LISTENING/model ready, CLASSIFYING, DEGRADED/retry, FAILED, stale sample age, and optional candidate with remaining seconds. Assert no raw audio, path, base64, dBA/SPL, or absolute source identity appears.

- [ ] **Step 2: Write final-arm RED tests**

Keep exactly two `/arm` replies: immediate acknowledgement and final result. Only an `APPLIED` final result appends one truthful audio line. Timeout/cancellation rollback, unauthorized filtering, `/disarm`, parsing, and `/status` command counts remain unchanged.

- [ ] **Step 3: Run RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*ProtectionStatusFormatterTest" --tests "*TelegramCommandHandlerTest"
```

- [ ] **Step 4: Implement typed rendering only**

Pass `coordinator.audioTelemetry.value` directly; do not parse diagnostic strings. Do not reintroduce `/testmic`, add a third arm reply, or send candidate messages automatically.

- [ ] **Step 5: Run GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*IncidentMessageFormatterTest" --tests "*ProtectionStatusFormatterTest" --tests "*TelegramCommandHandlerTest"
```

---

### Task 7: Complete Stable Audio UI and Card-Owned Self-Test Result

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreen.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/ProtectionScreen.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/Navigation.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt`
- Test: `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt`

**Interfaces:**
- Consumes: separate `StateFlow<AudioTelemetry>`; never stores it in `ProtectionSnapshot`.
- Produces: stable `AudioUiTelemetry`, Protection card, full Settings diagnostics, and persistent local self-test result.

Add these UI-only models:

```kotlin
data class MicrophoneSelfTestUiResult(
    val success: Boolean,
    val detail: String,
    val observedLevelDbfs: Double?,
    val completedAtMs: Long,
)

data class AudioUiTelemetry(
    val state: AudioRuntimeState,
    val detailCode: String?,
    val modelReady: Boolean,
    val lastSampleAgeSeconds: Long?,
    val approximateLevelDbfs: Double?,
    val baselineMedianDbfs: Double?,
    val baselineP95Dbfs: Double?,
    val gateState: AudioGateState,
    val lastInferenceMs: Long?,
    val averageInferenceMs: Long?,
    val droppedFrames: Long,
    val restartCount: Int,
    val currentCandidate: AudioThreatMetadata?,
    val candidateExpiresInSeconds: Int?,
    val selfTestResult: MicrophoneSelfTestUiResult?,
)
```

Add `audio: AudioUiTelemetry` to `ProtectionUiState` with an OFF default for source compatibility.

Add `elapsedNowMs: () -> Long = { System.nanoTime() / 1_000_000L }` to `ProtectionViewModel`; use it only for audio sample age and candidate expiry. Keep the existing wall-clock `nowMs` for arming and display timestamps.

- [ ] **Step 1: Write ViewModel RED tests**

Assert telemetry is combined into `uiState` separately; 100 logical 250-ms updates do not change destination, message, operation flags, protection state, snapshot revision, or Events loading. Candidate expiry uses monotonic time. Self-test calls the coordinator only when disarmed and stores its result without creating `ProtectionUiMessage`.

- [ ] **Step 2: Write Compose RED tests**

Assert Protection card text for OFF, STARTING, CALIBRATING with remaining learning time, LISTENING/model ready, CLASSIFYING, DEGRADED, FAILED, relative meter, last-sample age, candidate type/confidence/expiry. Assert Settings rows for `16 kHz / mono`, recorder state, baseline median/P95, gate, model, last/average inference, dropped frames, restart count, candidate count, and latest self-test result.

Assert self-test is disabled unless:

```kotlin
state.protection.state == ProtectionState.DISARMED_ONLINE &&
    !state.protectionOperationInFlight &&
    !state.settingsOperationInFlight
```

- [ ] **Step 3: Run RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*ProtectionViewModelTest" --tests "*ProtectionAppScreenTest"
```

- [ ] **Step 4: Combine telemetry without transient side effects**

Add `coordinator.audioTelemetry` to the ViewModel `combine`. Preserve destination/message/operation inputs unchanged. Do not add a `LaunchedEffect` for telemetry. `runMicrophoneTest()` updates `MicrophoneSelfTestUiResult` in presentation state and never calls `publishMessage()`.

- [ ] **Step 5: Render stable cards**

Add a stable LazyColumn key such as `audio-threat-status` to Protection and retain `audio-diagnostics` in Settings. Label dBFS values `relative/approximate`; show no waveform and no raw sample count. Accessibility descriptions may expose only state/category/confidence/relative level.

- [ ] **Step 6: Add the flicker regression**

Emit 100 telemetry updates at logical 250-ms intervals while armed. Assert the protection card semantic node remains displayed, selected destination remains Protection, and no Snackbar/top-popup/message node appears.

- [ ] **Step 7: Run GREEN and Android-test compile**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*ProtectionViewModelTest" --tests "*ProtectionAppScreenTest"
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' compileDebugAndroidTestKotlin
```

---

### Task 8: Correct Technician Documents and Evidence

**Files:**
- Modify: `docs/sensor-fusion-technician-guide-th.html`
- Regenerate: `docs/sensor-fusion-technician-guide-th.pdf`
- Remove after content migration: `docs/superpowers/guides/sensor-fusion-technician-guide-th.html`
- Remove after content migration: `docs/superpowers/guides/sensor-fusion-technician-guide-th.pdf`
- Modify: `docs/superpowers/status/2026-08-15-audio-threat-sensor-fusion-evidence.md`

**Interfaces:**
- Consumes: final verified runtime rules from Tasks 2-7.
- Produces: one authoritative HTML/PDF pair at the approved paths and bounded evidence.

- [ ] **Step 1: Update the authoritative HTML**

Document STARTING/CALIBRATING/LISTENING/CLASSIFYING/DEGRADED/FAILED, 10-to-30-second roadside calibration, median/P95/MAD, 30-second safe adaptation freezes, mapped categories, exact fusion matrix, relative dBFS wording, retries, UI meanings, local-only self-test, and field checklist. State explicitly that YAMNet estimates a category, not identity/source/distance.

- [ ] **Step 2: Remove wrong command/path references**

Remove `/testmic` and references to `docs/superpowers/guides`. After migrating useful content and verifying the root HTML, remove only the two duplicate guide files under `docs/superpowers/guides/`. Do not remove the directory if it contains any other file.

- [ ] **Step 3: Regenerate the root PDF**

```powershell
$chrome='C:\Program Files\Google\Chrome\Application\chrome.exe'
$html=(Resolve-Path 'docs\sensor-fusion-technician-guide-th.html').Path.Replace('\','/')
$pdf=(Resolve-Path 'docs').Path + '\sensor-fusion-technician-guide-th.pdf'
& $chrome --headless --disable-gpu --no-pdf-header-footer "--print-to-pdf=$pdf" "file:///$html"
```

- [ ] **Step 4: Verify document integrity**

Confirm `%PDF-` header, `%%EOF`, nonzero page count, and manually inspect rendered page images for Thai text/table clipping. Record HTML bytes, PDF bytes, page count, and hashes in evidence.

- [ ] **Step 5: Correct evidence claims**

Replace `100% complete`, `/testmic`, and device-accepted statements with exact command outputs. Mark every physical scenario not actually run as `NOT EXECUTED`. Installation and Activity launch are evidence only for install/launch.

---

### Task 9: Full Host, APK, Privacy, Scope, and Physical Gates

**Files:**
- Modify only: `docs/superpowers/status/2026-08-15-audio-threat-sensor-fusion-evidence.md`

**Interfaces:**
- Consumes: completed Tasks 1-8.
- Produces: final acceptance evidence; no production edits after a gate begins without rerunning all affected gates.

- [ ] **Step 1: Run the full host gate fresh**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' --rerun-tasks testDebugUnitTest assembleDebug compileDebugAndroidTestKotlin
```

Record suite/test/failure/error/skipped counts from `app/build/test-results/testDebugUnitTest/TEST-*.xml`, APK byte size, and fresh APK SHA-256.

- [ ] **Step 2: Verify the model inside the APK**

Open `app/build/outputs/apk/debug/app-debug.apk` as ZIP and assert exactly one `assets/yamnet.tflite`, size `4,126,810`, and approved SHA-256. Do not treat the source asset alone as packaging evidence.

- [ ] **Step 3: Run privacy scan and manually classify hits**

```powershell
rg -n "FileOutputStream|openFileOutput|\.wav|\.pcm|MediaRecorder\.OutputFormat|Log\..*samples|println\(.*samples|audio.*base64" app/src/main/java app/src/test app/src/androidTest
```

Expected: no raw-audio persistence/transmission/logging. `FileIncidentRepository` metadata persistence is legitimate only when it contains typed metadata, never PCM.

- [ ] **Step 4: Run forbidden-scope audit**

```powershell
git diff --check
git diff --name-only
git diff -- app/src/main/java/com/example/motorcycleantitheftsensor/location
git diff -- app/src/main/java/com/example/motorcycleantitheftsensor/security
git diff -- app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClient.kt
rg -n "/testmic|TestMic" app/src docs
```

Expected: only authorized `LivePursuitCoordinator.kt` changed under GPS-related code; forbidden diffs are empty; `/testmic` scan has zero hits.

- [ ] **Step 5: Run physical Huawei/two-phone acceptance**

Use the freshly hashed APK and execute all scenarios from the original plan Task 13: permission/startup, recorder denial/recovery, quiet calibration, noisy roadside calibration, ten fusion cases, Telegram content, 30-minute armed/charging soak, and disarm during calibration/classification/backoff/candidate. Record device model/API and exact results. Unsafe or unreproducible cases remain `NOT EXECUTED`; never convert host results into physical accuracy claims.

- [ ] **Step 6: Final stop gate**

The implementation is accepted only when all of these are true:

```text
Full host and Android-test compilation: PASS
Audio pipeline failure matrix: PASS
Six-suite GPS regression including AudioMovementFusionIntegrationTest: PASS
Unconfirmed audio suppression in inactive and active incidents: PASS
Model-in-APK size/hash: PASS
Privacy scan: PASS after manual classification
Forbidden-scope audit: PASS
Root technician HTML/PDF verified: PASS
Physical scenarios: PASS or explicitly NOT EXECUTED with reason
No /testmic command: PASS
No popup/Snackbar/Telegram/Events from candidate or self-test: PASS
```

Do not write “complete”, create a completion checkpoint, or request commit/merge while any automated gate fails or any executed physical scenario fails.

## Handoff Result Expected From the Implementing Agent

The final report must contain:

1. Exact files changed, separated into production/tests/docs.
2. Each remediation finding mapped to the task that fixed it.
3. Every test command with exit code and suite/test counts.
4. APK and model byte sizes plus SHA-256.
5. Privacy and forbidden-scope scan results with legitimate hits explained.
6. Physical results separated from host/build/install evidence.
7. Remaining risks and all `NOT EXECUTED` cases.
8. Confirmation that no unrelated files were reset, cleaned, staged, committed, or overwritten.
