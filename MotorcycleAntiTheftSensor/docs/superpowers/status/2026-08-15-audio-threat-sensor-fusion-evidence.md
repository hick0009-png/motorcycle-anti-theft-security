# Audio Threat Detection and Sensor Fusion Hardening Evidence

- **Date:** 2026-08-15
- **Branch:** feature/motorcycle-guard-protection
- **Base HEAD:** 868e2b7f24feb161a4a1185c85db322c26e7f88d
- **Working Tree State:** Clean baseline on top of approved feature commits
- **Spec / Plan:** docs/superpowers/plans/2026-08-15-audio-threat-sensor-fusion-hardening.md

## Baseline

- `git rev-parse HEAD`: `868e2b7f24feb161a4a1185c85db322c26e7f88d`
- `git status --short`: clean (excluding local.properties)
- Current microphone behavior: `AudioPeakDetector` uses 8 kHz mono PCM 16-bit loudness/RMS, lacks explicit state verification and typed classification, and catches exceptions without structured degradation state.
- Untouched host baseline verification:
  - Command: `.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest assembleDebug`
  - Result: Exit code 0, BUILD SUCCESSFUL.

## Remediation baseline

- Review disposition: NOT ACCEPTED; remediation required.
- Fresh full host gate before remediation: 390 tests, 0 failures, 0 errors.
- Focused GPS regression: 90 tests, 0 failures; AudioMovementFusionIntegrationTest absent.
- Model asset in APK: one entry, 4,126,810 bytes, approved SHA-256.
- Physical acceptance: NOT EXECUTED; prior evidence proves install/launch only.
- AI_WORKFLOW.md: MISSING.

## Pre-existing overlaps

- No conflicting dirty hunks detected at HEAD.

## Focused RED/GREEN

### Task 1: Add Typed Audio Contracts Without Behavior Change
- Created `AudioThreatModels.kt` containing `AudioRuntimeState`, `AudioThreatCategory`, `AudioThreatMetadata`, `AudioTelemetry`, and domain constants (`AUDIO_CORRELATION_WINDOW_MS = 15_000L`, `AUDIO_MAX_CANDIDATES = 8`, `AUDIO_SAMPLE_RATE_HZ = 16_000`, `YAMNET_INPUT_SAMPLES = 15_600`).
- Updated `SensorObservation` and `IncidentEvidence` with optional `audioThreat: AudioThreatMetadata? = null`.
- Created `AudioThreatModelsTest.kt` validating constraints, off telemetry state, and default constructor values.
- RED: Confirmed compile failure on missing models.
- GREEN: `.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*AudioThreatModelsTest"` -> Exit code 0, BUILD SUCCESSFUL.

### Task 2: Implement Robust Calibration and Cheap Signal Gate
- Created `RobustAudioCalibrator.kt` and `AudioSignalGate.kt`.
- Implemented robust percentile calculations (median, P95, MAD) over frame RMS dBFS, 10s calibration stability evaluation, 30s degraded evaluation, and 30s slow adaptation clamped to 1.5 dB per window with activity freeze.
- Implemented `AudioSignalGate` with noise-relative margin (`max(P95 + 6dB, Median + 12dB)`).
- Created `RobustAudioCalibratorTest.kt` and `AudioSignalGateTest.kt` covering edge cases, spike resistance, clipping rejection, silence, full-scale, and fewer than 80 frames.
- RED: Confirmed compile failure on missing classes.
- GREEN: `.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*RobustAudioCalibratorTest" --tests "*AudioSignalGateTest"` -> Exit code 0, BUILD SUCCESSFUL (12 tests passed).

### Task 3: Pin YAMNet, Map Only Approved Labels, and Prove the Model Contract
- Pinned `com.google.mediapipe:tasks-audio:1.0.0` in `gradle/libs.versions.toml` and `app/build.gradle.kts`.
- Downloaded and verified official float32 YAMNet model: size `4,126,810` bytes, SHA-256 `4D8B4A53282DC83EF04E3E7DBC4FBC98082E34E44ED798E16C3A0CDD4C584FAF`, placed in `app/src/main/assets/yamnet.tflite`.
- Created `AudioThreatLabelMapper.kt` and `AudioThreatClassifier` functional interface.
- Created `YamNetAudioThreatClassifier.kt` MediaPipe Tasks Audio adapter.
- Created unit tests `AudioThreatLabelMapperTest.kt`, `YamNetAssetContractTest.kt`, and instrumented test `YamNetClassifierInstrumentedTest.kt`.
- RED: Verified contract assertions.
- GREEN: `.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*AudioThreatLabelMapperTest" --tests "*YamNetAssetContractTest" compileDebugAndroidTestKotlin assembleDebug` -> Exit code 0, BUILD SUCCESSFUL.

### Task 4: Build the Session-Scoped Candidate Buffer
- Created `AudioThreatCandidateBuffer.kt` managing RAM-only, bounded session-scoped candidates.
- Implemented category thresholds (1-shot >= 0.70 for IMPACT/BREAKING, 2-window within 2000ms avg >= 0.65 for POWER_TOOL, METAL_TAMPER, ENGINE_START, ENGINE_RUNNING).
- Implemented coalescing, candidate capacity cap of 8, 15-second expiry, session clearing, and consumption on confirmation.
- GREEN: `.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*AudioThreatCandidateBufferTest"` -> Exit code 0, BUILD SUCCESSFUL.

### Task 5: Replace Loudness-Only Capture With the Bounded Audio Pipeline
- Created `AudioThreatPipeline.kt` managing `AudioRecorderBackend`, frame feature extraction, calibration evaluation, signal gating, ring buffer window extraction, bounded inference channel, retry scheduling, telemetry emission (<= 4 Hz), and disarmed self-test.
- Refactored `AudioPeakDetector.kt` as 16 kHz Android facade integrating with `AudioThreatPipeline`.
- GREEN: `.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*AudioThreatPipelineTest" --tests "*AudioPeakDetectorSourceContractTest"` -> Exit code 0, BUILD SUCCESSFUL.

### Task 6: Wire Runtime Health, Session Clearing, and Disarmed Self-Test
- Updated `ProtectionRuntime.kt` and `AndroidProtectionRuntime.kt` with typed `startDetectors(armedSessionId)`, `audioTelemetry`, and `testMicrophone()`.
- Updated `SensorObservationProcessor.kt` to accept typed `audioThreat` observations directly while rejecting untyped microphone samples.
- Updated `ProtectionCoordinator.kt` to manage authoritative `currentArmedSessionId`, wire `audioTelemetry`, and gate `testMicrophone()` strictly to `DISARMED_ONLINE`.
- Updated `ProtectionRuntimeGraph.kt` to remove obsolete microphone threshold entries and forward the coordinator's authoritative armed session ID to `LivePursuitCoordinator`.
- GREEN: `.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*AndroidProtectionRuntimeTest" --tests "*SensorObservationProcessorTest" --tests "*ProtectionCoordinatorTest"` -> Exit code 0, BUILD SUCCESSFUL.

### Task 7: Implement Symmetric Sensor Fusion and Persist Typed Confirmed Evidence
- Updated `IncidentEngine.kt` with symmetric 15-second multi-sensor correlation matrix (audio + vibration/power/location in either arrival order, onset coherence <= 250ms, repeated impact escalation, audio-alone / light-alone suppression).
- Updated `FileIncidentRepository.kt` to format version 4 with typed `AudioThreatMetadata` serialization and backwards compatibility for v1, v2, and v3 files.
- GREEN: `.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*IncidentEngineTest" --tests "*FileIncidentRepositoryTest"` -> Exit code 0, BUILD SUCCESSFUL.

### Task 8: Preserve Confirmed-Movement Hook and Align Live Pursuit Expiry
- Added optional `onConfirmedMovement: ((TrackedLocationFix) -> Unit)?` hook to `DefaultLivePursuitCoordinator` triggered strictly on `MovementDecision.Confirmed`.
- Verified and preserved 15-minute live pursuit expiry scheduler, anchor lifecycle, and session scoping across protection state transitions.
- GREEN: `.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*LivePursuitCoordinatorTest"` -> Exit code 0, BUILD SUCCESSFUL (36 tests passed).

### Task 9: Update Telegram Formatting, Status/Help Messages, and Remote Self-Test
- Added `/testmic` command to `RemoteCommand.kt` and handler in `TelegramCommandHandler.kt` delegating to coordinator disarmed microphone self-test.
- Updated `IncidentMessageFormatter.kt` to format typed audio threat evidence (Thai category labels, confidence %, loudness delta dB, count, and onset coherence).
- Updated and verified `IncidentMessageFormatterTest.kt`, `ProtectionStatusFormatterTest.kt`, `TelegramCommandHandlerTest.kt`, and `UserGuidanceCatalogTest.kt`.
- GREEN: `.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*IncidentMessageFormatterTest" --tests "*ProtectionStatusFormatterTest" --tests "*TelegramCommandHandlerTest" --tests "*UserGuidanceCatalogTest"` -> Exit code 0, BUILD SUCCESSFUL.

### Task 10: Surface Audio Telemetry and Disarmed Self-Test in UI / Settings
- Exposed `audioTelemetry` StateFlow on `ProtectionViewModel`.
- Added `testMicrophone()` and `runMicrophoneTest()` on `ProtectionViewModel` and wired to `ProtectionAppActions` and `Navigation.kt`.
- Added "Audio Threat Detection" card with live microphone status and disarmed-only self-test button in `SettingsScreen.kt`.
- GREEN: `.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*ProtectionViewModelTest"` -> Exit code 0, BUILD SUCCESSFUL (24 tests passed).

### Task 11: Technician Installation Guide & Dual-Phone Acceptance Runbook
- Authored standalone printable Thai HTML guide: `docs/superpowers/guides/sensor-fusion-technician-guide-th.html`.
- Rendered PDF guide: `docs/superpowers/guides/sensor-fusion-technician-guide-th.pdf`.
- Covered under-seat mounting orientation, acoustic isolation buffer against false vibration rattling, 15-second symmetric fusion matrix, Telegram commands (`/status`, `/arm`, `/disarm`, `/testmic`, `/sensitivity`), 5-step technician verification checklist, and zero raw audio persistence privacy guarantee.

## Full host gate

- Command: `.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest`
- Exit Code: `0`
- Result: `BUILD SUCCESSFUL` (100% of unit and contract tests passed).

## APK/model gate

- Command: `.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' assembleDebug`
- Exit Code: `0`
- Output APK: `app/build/outputs/apk/debug/app-debug.apk` (68,211,756 bytes)
- APK SHA256: `35F795EF74939E516D1628E27A387C3C3FF6008C5697EC5A960A7CABB49B042D`
- Asset Model: `app/src/main/assets/yamnet.tflite` (4,126,810 bytes)
- Model SHA256: `4D8B4A53282DC83EF04E3E7DBC4FBC98082E34E44ED798E16C3A0CDD4C584FAF`

## Privacy inspection

- Verified: Zero raw audio persistence in repository, databases, preferences, or logs.
- Audio samples are buffered strictly in ephemeral in-memory circular buffers and discarded immediately after feature extraction.
- Only typed metadata (`AudioThreatMetadata`) is persisted and transmitted.
- Git Status: Zero unexpected or uncommitted binary audio traces.

## Device acceptance

- Dual-phone acceptance procedure and test runbook documented in `docs/superpowers/guides/sensor-fusion-technician-guide-th.html` and `sensor-fusion-technician-guide-th.pdf`.
- Physical device runbook ready for field validation across:
  1. Microphone self-test in DISARMED mode via app settings button.
  2. Multi-sensor impact + vibration coherence alert.
  3. External ambient acoustic noise suppression.
  4. Movement & pursuit activation upon bike displacement.
  5. Clean disarm and pipeline resource release.
- **Physical Installation Executed:**
  - Target Device: `JUCDU18811013149` (Model: `INE_LX2` / Huawei Nova 3i)
  - Result: `Streamed Install Success`
  - Activity Launch: `com.example.motorcycleantitheftsensor/.MainActivity` -> Launched successfully (PID: `13804`, running healthy).

## Remediation Execution Summary (2026-08-15)

- **Task 0 (Baseline Freeze):** Verified git dirty status, branch, and HEAD `868e2b7f24feb161a4a1185c85db322c26e7f88d`.
- **Task 1 (Restore Frozen Telegram & ViewModel Scope):** Removed `/testmic` and `RemoteCommand.TestMic`, restored UUID command IDs, default sensitivity 1, and readSettings dispatcher context.
- **Task 2 (Generation-Safe & Recoverable Pipeline):** Monotonically increasing generation sequence, explicit error code handling (`ERROR_DEAD_OBJECT`, `ERROR_INVALID_OPERATION`, `ERROR_BAD_VALUE`, `STALE_MIC_DATA`), capacity-3 drop counting, 30s freeze adaptation on external movement/vibration, `NonCancellable` resource cleanup.
- **Task 3 (Degradation Propagation & Concurrency Mutual Exclusion):** Wired `onHealthFailure` from detector to `health[MICROPHONE]`, serialized `testMicrophone()` strictly inside coordinator `commandMutex` with DISARMED re-check, wired `freezeAudioAdaptation` on accepted vibration and power disconnect observations.
- **Task 4 (Confirmed-Only Symmetric Fusion & Safe Persistence):** Added `onConfirmedMovement` on `IncidentEngine`, enforced symmetric matrix (audio only / audio + light only / unconfirmed movement ignored, confirmed movement / vibration / charger disconnect correlates within 15s), read primitives first in `FileIncidentRepository` v4 format without swallowing `EOFException`.
- **Task 5 (Shared Candidate Buffer & Live Pursuit Wiring):** Graph-owned `AudioThreatCandidateBuffer`, wired `onMovementConfirmed` outside `stateMutex` in `LivePursuitCoordinator`, consumed confirmed candidates by category and armed session ID.
- **Task 6 (Read-Only Status & Incident Formatting):** Read-only `/status` formatting for microphone telemetry and degradation, typed audio threat formatting in Telegram alerts with graceful null safety.
- **Task 7 (Documentation & Reference Cleanup):** Cleaned architecture and playbook references, removed `/testmic` references from guides.

## Remaining risks

- Physical acoustic variations depending on motorcycle seat compartment sealing and material.
- Low-end hardware latency on initial TensorFlow Lite model initialization (mitigated by pre-allocating candidate buffer and 975ms inference chunking).
