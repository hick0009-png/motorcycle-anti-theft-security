# Audio Threat Detection and Sensor Fusion Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Use `superpowers:test-driven-development` for every production change and `superpowers:verification-before-completion` before reporting success. Mark each checkbox only after its stated evidence exists.

**Goal:** Replace the current microphone loudness-only path with a reliable, observable, on-device audio threat detector that classifies theft-related sounds with YAMNet, retains only short-lived metadata, and alerts only when an independent motorcycle sensor confirms the event.

**Architecture:** Preserve `ProtectionCoordinator` as the authoritative protection owner. Keep one `AudioRecord` capture source at mono PCM16/16 kHz, pass bounded frames through a cheap adaptive signal gate, run one CPU YAMNet inference worker only for suspicious windows, retain typed candidates in RAM for at most 15 seconds, and correlate them symmetrically with vibration, confirmed GPS movement, charger disconnect, and light. Audio telemetry is a separate flow from `ProtectionSnapshot` so 4 Hz meter updates cannot recreate the arm-screen flicker. Only confirmed incidents enter persistence, Events, Telegram, or SMS.

**Tech Stack:** Kotlin 2.3.20/JVM 17, Android SDK 24-36, `AudioRecord`, Kotlin coroutines/StateFlow, MediaPipe Tasks Audio `1.0.0`, official float32 YAMNet, Jetpack Compose, JUnit 4, Android instrumented tests, Gradle wrapper, ADB.

## Global Constraints

### Product and privacy boundaries

- Battery consumption is explicitly out of scope. The phone may remain connected to a charger. Do not add battery saver, Doze, charging-only inference, duty cycling, or power-management UI.
- Charger disconnect remains a theft-confirmation signal; it is not a reason to stop audio processing.
- Never save, upload, transmit, log, serialize, or expose raw PCM. Raw samples may exist only in bounded RAM buffers owned by the active audio session.
- Never create `.wav`, `.pcm`, `.m4a`, cache recordings, debug recordings, or a recording export action.
- Persist only metadata belonging to a confirmed incident: threat category, confidence, approximate loudness/baseline delta, first/last detection time, occurrence count, and corroborating sensor evidence.
- Unconfirmed candidates live in RAM for at most `15_000 ms`, maximum eight entries, and are cleared on disarm, new arm session, service stop, process restart, audio restart, and expiry.
- A microphone candidate alone must never open an incident, enter Events, send Telegram/SMS, start Live Location, show a Snackbar, or show a top-edge popup.
- Do not attempt speaker identification, speech recognition, person identification, distance estimation, or direction finding. A single phone microphone cannot establish those facts.
- Keep `/pair <code>`, owner Chat ID authorization, `/disarm`, three-failure/five-minute lock behavior, and all authenticator-removal behavior unchanged.
- Keep Demo Mode boundaries unchanged: no new SMS or emergency-call behavior.

### Model and dependency pins: no substitution

- Add exactly `com.google.mediapipe:tasks-audio:1.0.0`; never use `latest.release`, a dynamic version, TensorFlow Lite Task Audio `0.4.4`, PyTorch, ONNX, TarsosDSP, or a second ML runtime.
- Use exactly the official model URL:
  `https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/latest/yamnet.tflite`
- The downloaded model must be exactly `4,126,810` bytes and SHA-256:
  `4D8B4A53282DC83EF04E3E7DBC4FBC98082E34E44ED798E16C3A0CDD4C584FAF`
- Store it only as `app/src/main/assets/yamnet.tflite`.
- Use CPU inference in this plan. Do not enable GPU, NNAPI, NPU, delegates, model quantization, or custom model training.
- Input is mono float audio derived from PCM16 at `16_000 Hz`. Each inference window is exactly `15_600` samples (`0.975 s`), matching the pinned model input.
- YAMNet is a general AudioSet classifier. It does not prove that the sound came from this motorcycle. Sensor fusion remains mandatory.
- The approved dependency/model may be replaced only by a separately reviewed plan with a pinned version, model hash, build proof, and physical-device benchmark.

### Repository and dirty-worktree boundaries

- The plan was written from commit `c5b3cd6` in a heavily mixed dirty worktree. Treat current working files as user-owned work, not disposable changes.
- `AI_WORKFLOW.md` was not present under `D:\security` when this plan was written. Follow `D:\security\AGENTS.md` and this plan; do not invent missing workflow rules.
- Before editing, capture `git rev-parse HEAD`, `git status --short`, and exact diffs for every allowed existing file. Save them in the evidence document created by Task 0.
- Never run `git reset`, `git clean`, `git checkout --`, `git restore`, `git stash`, or a bulk formatter.
- Never run `git add .` or `git add -A`. Do not commit from the shared dirty checkout unless the user explicitly authorizes it after inspecting exact staged hunks.
- Do not create a clean worktree from `HEAD` and assume it contains the approved GPS/authenticator/UI work; much of that work is currently uncommitted or untracked.
- If an allowed existing file already has unrelated changes and the executor cannot isolate this plan's hunks with certainty, stop before editing that file and report `BLOCKED: overlapping user changes`. Do not overwrite or reconstruct it from memory.
- Do not rename public APIs, move packages, replace `ProtectionCoordinator`, or restructure the app.
- No unsolicited cleanup of mojibake text, imports, formatting, warnings, deprecated APIs, tests, or dead code.

### Explicitly forbidden files and systems

- Do not modify authenticator/TOTP/QR files, pairing storage, `/disarm`, Telegram authorization, Telegram TLS pins, bot verification, settings network timeout, SMS crypto, emergency calling, foreground notification policy, or the popup-removal work. `TelegramCommandHandler.kt` has one narrowly permitted final-`/arm` text append in Task 9; command parsing, authorization, timeout, cancellation, disarm, and reply count are frozen.
- Do not modify `MovementDisplacementPolicy.kt`, location thresholds, GPS fix validation, pursuit expiry, Telegram Live Location transport, remote mutex ordering, or pursuit recovery.
- `LivePursuitCoordinator.kt` has one narrowly permitted change in Task 8: add and invoke a typed `onMovementConfirmed` callback after the existing `MovementDecision.Confirmed`. No other line of GPS behavior is authorized.
- Do not change vibration sensitivity, accelerometer sampling, light thresholds, thermal threshold, existing 10-second arm delay, or incident delivery retry rules.
- Do not change `SnapshotProjectionGate.kt`. Audio telemetry must bypass it through a separate flow, not by weakening or rewriting the proven projection gate.
- Do not add a top popup, heads-up notification, navigation event, or Snackbar for audio samples/candidates/health changes.

### Performance and failure boundaries

- Never block the main thread with audio reads, DSP, model loading, inference, hashing, sleeps, or retries.
- Use one capture owner, a bounded frame queue of capacity three with drop-oldest behavior, one DSP worker, and one inference worker. Never overlap inference calls.
- A slow classifier drops stale inference work; it must not backpressure `AudioRecord` or the other sensors.
- Verify `AudioRecord.state == STATE_INITIALIZED` before start and `recordingState == RECORDSTATE_RECORDING` after `startRecording()`.
- Handle `read()` results explicitly: positive count = data; `ERROR_DEAD_OBJECT` = release/rebuild; `ERROR_INVALID_OPERATION` or `ERROR_BAD_VALUE` = fail that attempt; no positive samples for five seconds = stale/rebuild.
- Retry audio startup/recovery after 1, 2, and 4 seconds. After the third failure, report audio degraded and retry every 60 seconds while armed. Stop all retry jobs immediately on disarm/service stop.
- Audio failure degrades only microphone coverage. Vibration, light, power, GPS, Telegram polling, and protection commands must continue.
- Every recorder and classifier instance must be closed exactly once. A stale session/generation callback must be ignored.
- Runtime diagnostic logs may contain only stable error codes and stack traces. Do not log PCM, model output lists, Chat IDs, coordinates, tokens, or candidate histories.

---

## Approved Detection Contract

### Runtime states

Use exactly:

```kotlin
enum class AudioRuntimeState {
    OFF,
    STARTING,
    CALIBRATING,
    LISTENING,
    CLASSIFYING,
    DEGRADED,
    FAILED,
}
```

`DEGRADED` means audio coverage is limited but the protection system remains active. `FAILED` is an individual attempt state before retry scheduling; it must not shut down the protection service.

### Threat categories and exact YAMNet label groups

Use exactly these domain categories and exact case-sensitive YAMNet category names:

| Domain category | Accepted YAMNet labels |
|---|---|
| `IMPACT` | `Knock`, `Tap`, `Slam`, `Thump, thud`, `Thunk`, `Bang`, `Slap, smack`, `Whack, thwack`, `Hammer` |
| `BREAKING` | `Crack`, `Splinter`, `Shatter`, `Smash, crash`, `Breaking` |
| `POWER_TOOL` | `Tools`, `Power tool`, `Drill`, `Sawing`, `Filing (rasp)`, `Sanding`, `Jackhammer`, `Chainsaw` |
| `METAL_TAMPER` | `Ratchet, pawl`, `Gears`, `Keys jangling`, `Clang`, `Scrape` |
| `ENGINE_START` | `Engine starting` |
| `ENGINE_RUNNING` | `Motorcycle`, `Engine`, `Light engine (high frequency)`, `Medium engine (mid frequency)`, `Heavy engine (low frequency)`, `Engine knocking`, `Idling`, `Accelerating, revving, vroom` |

All other 521-class outputs are benign/unsupported for incident purposes. Do not map `Speech`, `Traffic noise, roadway noise`, `Car passing by`, wind, rain, music, alarm, or generic `Noise` to a theft category.

### Candidate thresholds

- `IMPACT` and `BREAKING`: one gated inference with mapped score `>= 0.70` creates/updates a candidate.
- `POWER_TOOL`, `METAL_TAMPER`, `ENGINE_START`, and `ENGINE_RUNNING`: require two qualifying results no more than `2_000 ms` apart with average mapped score `>= 0.65`.
- If multiple labels in one domain category appear in one result, use the maximum score, not their sum.
- If multiple domain categories qualify, retain each as a separate candidate, subject to maximum eight entries.
- Coalescing the same category updates maximum confidence, running average loudness delta, first/last monotonic time, and occurrence count; expiry is `lastDetectedElapsedMs + 15_000`.
- Confidence thresholds are initial production constants. Do not tune them from one phone or one sound clip. Threshold changes require a documented labeled-device dataset and a separate review.

### Adaptive roadside calibration

- Compute frame RMS in dBFS, peak, and clipping ratio from 100 ms PCM blocks. Use `20 * log10(max(rms, 1e-6))`; never present dBFS as calibrated SPL/dBA.
- For the first 10 seconds after capture succeeds, state is `CALIBRATING`; microphone candidates are disabled while other sensors protect normally.
- Use robust statistics from valid frames: median RMS dBFS, P95 RMS dBFS, and median absolute deviation (MAD). Do not use the current arithmetic average baseline.
- A 10-second baseline is stable only with at least 80 valid frames, clipping ratio below 1%, `P95 - median <= 12 dB`, and `MAD <= 4 dB`.
- If unstable at 10 seconds, continue learning in the background until 30 seconds. UI detail is `Learning in noisy environment`; other sensors remain active.
- If still unstable at 30 seconds, enter `DEGRADED` with reason `MICROPHONE noisy environment`; loudness alone can never create a candidate. The classifier may still create candidates when the gate sees a clear onset, but fusion remains mandatory.
- The dynamic gate opens when `rmsDbfs >= max(baselineP95 + 6 dB, baselineMedian + 12 dB)` or a short peak/onset exceeds the same noise-relative margin. It does not create an incident.
- Every 30 seconds, adapt baseline only if there is no accepted vibration, confirmed movement, charger change, candidate, classifier threat result, or active incident during that window.
- Safe adaptation uses `new = old * 0.90 + windowMedian * 0.10`, clamped to at most `1.5 dB` change per 30-second window. Freeze adaptation immediately on suspicious or corroborating activity.
- Wind, rain, traffic, and nearby motorcycles may update the baseline only after the mapper classifies them as benign and the independent sensors remain quiet.

### Sensor fusion matrix

Correlation uses monotonic elapsed time and is symmetric: audio may arrive before or after corroborating evidence within 15 seconds.

| Audio category | Required independent confirmation | Result |
|---|---|---|
| `IMPACT` | vibration within 15 s | `WARNING`; repeated impact plus continuing vibration becomes `CRITICAL` |
| `BREAKING` | vibration within 15 s | `CRITICAL` |
| `POWER_TOOL` | vibration within 15 s | `CRITICAL` |
| `POWER_TOOL` | light plus vibration within 15 s | `CRITICAL` (light adds context, not extra severity) |
| `METAL_TAMPER` | vibration within 15 s | `WARNING` |
| `ENGINE_START` | vibration within 15 s | `CRITICAL` |
| `ENGINE_START` or `ENGINE_RUNNING` | confirmed real GPS movement within 15 s | `CRITICAL` and retain existing Live Pursuit behavior |
| any candidate | charger disconnect within 15 s | `CRITICAL` |
| any candidate | confirmed real GPS movement within 15 s | `CRITICAL` and retain existing Live Pursuit behavior |
| any category | light only | no incident |
| `ENGINE_RUNNING` | no own-bike vibration/movement | no incident; treat as nearby traffic/motorcycle |
| any category | no independent confirmation before expiry | discard candidate silently |

- Audio/vibration onset separation `<= 250 ms` is marked `onsetCoherent = true` and is strong evidence that contact occurred at the bike. Do not require a minimum 50 ms gap; simultaneous timestamps are valid.
- `IMPACT` becomes repeated only when at least two qualified impact occurrences exist within the same 15-second correlation window.
- Do not change the fact that vibration can open the existing warning incident. Audio arriving later may enrich/escalate that incident according to the table.
- Confirmed GPS movement remains owned by `MovementDisplacementPolicy`/`LivePursuitCoordinator`; do not duplicate distance math in audio code.

### Telegram output for a confirmed incident

For a confirmed audio-fused incident, the owner receives one incident message through the existing delivery coordinator containing:

```text
🚨 ตรวจพบเหตุผิดปกติ: <ประเภทเหตุ> (<WARNING หรือ CRITICAL>)
เสียงที่ตรวจพบ: <ชื่อประเภทภาษาไทย>
ความมั่นใจ: <0-100>%
ระดับเสียงเทียบพื้นหลัง: <delta dB>
ตรวจพบซ้ำ: <count> ครั้ง
ยืนยันร่วมกับ: <แรงสั่น/ถอดสายชาร์จ/การเคลื่อนที่จริง/แสงประกอบ>
เวลา: <เวลาท้องถิ่น>
ตำแหน่ง/ลิงก์แผนที่: <มีเฉพาะเมื่อ fix ใช้งานได้>
```

- The wording must say `ระดับเสียงเทียบพื้นหลัง`; never claim calibrated decibels or sound pressure level.
- Do not send an unconfirmed candidate. Do not send a separate message for every YAMNet window.
- `/status` may show current audio state, model readiness, last sample age, and current unconfirmed category/expiry only because the owner explicitly requested status.
- The final `/arm` result or existing armed-state Telegram notification must state one truthful condition: `ไมโครโฟนพร้อม / โมเดลพร้อม`, `ไมโครโฟนกำลังปรับเทียบ / โมเดลพร้อม`, or the bounded degraded reason. Do not add a second unsolicited arm message if the existing state notifier already sends the final state.

---

## Allowed File Responsibility Map

Existing files may be modified only for the responsibility stated here:

- `gradle/libs.versions.toml` — pin MediaPipe Tasks Audio `1.0.0` and its library alias only.
- `app/build.gradle.kts` — add the one version-catalog dependency and ensure `.tflite` assets are not compressed only if the current AGP requires it.
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/AudioThreatModels.kt` — new typed domain models, telemetry, and constants.
- `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/audio/RobustAudioCalibrator.kt` — new robust calibration/slow adaptation logic.
- `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/audio/AudioSignalGate.kt` — new cheap RMS/peak/onset gate.
- `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/audio/AudioThreatLabelMapper.kt` — new exact label allowlist/category mapper.
- `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/audio/YamNetAudioThreatClassifier.kt` — new MediaPipe adapter; no capture ownership.
- `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/audio/AudioThreatCandidateBuffer.kt` — new bounded session-scoped in-memory metadata buffer.
- `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/audio/AudioThreatPipeline.kt` — new queue/worker/retry/session orchestration.
- `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/AudioPeakDetector.kt` — keep the class name; turn it into the Android `AudioRecord` owner/facade for the new pipeline.
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/SensorObservation.kt` — add optional typed audio metadata with a null default.
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/SensorObservationProcessor.kt` — typed audio candidates bypass the old microphone arithmetic baseline/threshold; other kinds unchanged.
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt` — wire audio telemetry/health/self-test, session clearing, and typed candidate observations.
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntime.kt` — add the narrowly typed microphone self-test contract with a safe default for test fakes.
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/SecurityIncident.kt` — add typed persisted audio metadata with null defaults.
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentEngine.kt` — implement only the approved symmetric fusion matrix.
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/FileIncidentRepository.kt` — version-4 backward-compatible typed audio metadata serialization.
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt` — expose separate audio telemetry and gate self-test to `DISARMED_ONLINE`.
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt` — construct/wire classifier, audio callbacks, and one movement-confirmation hook.
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinator.kt` — only the typed callback described in Task 8.
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentMessageFormatter.kt` — render confirmed typed audio metadata only.
- `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusFormatter.kt` — append requested audio diagnostics to `/status` only.
- `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramCommandHandler.kt` — append one audio-status line to the existing final successful `/arm` reply only; no command/control changes.
- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt` — carry `AudioUiTelemetry`; do not put it in `ProtectionSnapshot`.
- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt` — combine the separate telemetry flow and invoke the disarmed self-test.
- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreen.kt` — add only the self-test action field.
- `app/src/main/java/com/example/motorcycleantitheftsensor/Navigation.kt` — wire only `testMicrophone = protectionViewModel::testMicrophone`.
- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/ProtectionScreen.kt` — render the microphone card states/meter/candidate without transient popups.
- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt` — add read-only audio diagnostics and a disarmed-only 5-second self-test button.
- `docs/sensor-fusion-technician-guide-th.html` and regenerated `.pdf` — document final implemented rules and roadside calibration.

Every production file not listed above is forbidden. Test additions are limited to counterparts named in tasks below. If implementation appears to require another production file, stop and request a plan amendment before editing it.

---

### Task 0: Freeze the Baseline and Establish Stop Gates

**Files:**
- Create: `docs/superpowers/status/2026-08-15-audio-threat-sensor-fusion-evidence.md`
- Read only: every file in the Allowed File Responsibility Map

**Interfaces:**
- Input: current mixed checkout and connected-device state.
- Output: reproducible baseline inventory; no production changes.

- [ ] **Step 1: Record repository evidence**

Run:

```powershell
git rev-parse HEAD
git status --short
git diff --stat
git diff -- app/build.gradle.kts gradle/libs.versions.toml app/src/main/java/com/example/motorcycleantitheftsensor/sensor/AudioPeakDetector.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentEngine.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/ProtectionScreen.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt
```

Create the evidence file with headings: `Baseline`, `Pre-existing overlaps`, `Focused RED/GREEN`, `Full host gate`, `APK/model gate`, `Device acceptance`, `Privacy inspection`, `Remaining risks`.

Expected: HEAD is recorded; all pre-existing changes are classified. If any overlapping hunk cannot be preserved, stop before Task 1.

- [ ] **Step 2: Capture current microphone behavior**

Run:

```powershell
rg -n "SAMPLE_RATE|AudioRecord|relative_amplitude|MICROPHONE to 0.25|MICROPHONE to 2|catch \(_:" app/src/main/java/com/example/motorcycleantitheftsensor
```

Record that the current implementation uses 8 kHz loudness/RMS, has no recording-state verification, no typed classifier, and silently catches recorder-thread exceptions. This is diagnostic baseline, not permission to edit unrelated code.

- [ ] **Step 3: Run the untouched host baseline**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest assembleDebug
```

Expected: record exact pass/fail and test count. Existing failures unrelated to this plan are a baseline condition; do not fix them silently. A production edit begins only after the user accepts any pre-existing failure.

Do not commit Task 0.

---

### Task 1: Add Typed Audio Contracts Without Behavior Change

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/AudioThreatModels.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/SensorObservation.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/SecurityIncident.kt`
- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/AudioThreatModelsTest.kt`

**Interfaces:**

```kotlin
enum class AudioThreatCategory { IMPACT, BREAKING, POWER_TOOL, METAL_TAMPER, ENGINE_START, ENGINE_RUNNING }
data class AudioThreatMetadata(
    val category: AudioThreatCategory,
    val confidence: Double,
    val loudnessDeltaDb: Double,
    val firstDetectedElapsedMs: Long,
    val lastDetectedElapsedMs: Long,
    val occurrenceCount: Int,
    val onsetElapsedMs: Long,
    val onsetCoherent: Boolean = false,
)
data class AudioTelemetry(
    val state: AudioRuntimeState,
    val detailCode: String?,
    val modelReady: Boolean,
    val lastSampleAtMs: Long?,
    val approximateLevelDbfs: Double?,
    val baselineMedianDbfs: Double?,
    val baselineP95Dbfs: Double?,
    val lastInferenceMs: Long?,
    val averageInferenceMs: Long?,
    val droppedFrames: Long,
    val restartCount: Int,
    val currentCandidate: AudioThreatMetadata?,
)
```

Add `audioThreat: AudioThreatMetadata? = null` to `SensorObservation` and `IncidentEvidence`. Constructors outside audio remain source-compatible through the null default.

- [ ] **Step 1: Write RED invariant tests** for confidence `0.0..1.0`, finite dB values, positive occurrence count, `first <= last`, and `AudioTelemetry.off()` returning the exact OFF/empty/zero state.
- [ ] **Step 2: Run RED**:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests "*AudioThreatModelsTest"
```

Expected: compilation fails because models do not exist.

- [ ] **Step 3: Implement the smallest immutable models** with `require(...)` validation and constants `AUDIO_CORRELATION_WINDOW_MS = 15_000L`, `AUDIO_MAX_CANDIDATES = 8`, `AUDIO_SAMPLE_RATE_HZ = 16_000`, `YAMNET_INPUT_SAMPLES = 15_600`.
- [ ] **Step 4: Run GREEN** with the same command; expected all tests pass.
- [ ] **Step 5: Inspect `git diff`** and confirm no non-audio constructor was behaviorally changed.

Do not commit in the shared dirty checkout.

---

### Task 2: Implement Robust Calibration and Cheap Signal Gate

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/audio/RobustAudioCalibrator.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/audio/AudioSignalGate.kt`
- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/audio/RobustAudioCalibratorTest.kt`
- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/audio/AudioSignalGateTest.kt`

**Interfaces:**

```kotlin
data class AudioFrameFeatures(val rmsDbfs: Double, val peak: Double, val clippingRatio: Double, val atElapsedMs: Long)
data class AudioBaseline(val medianDbfs: Double, val p95Dbfs: Double, val madDb: Double, val stable: Boolean)
sealed interface AudioGateDecision {
    data class Suppress(val features: AudioFrameFeatures) : AudioGateDecision
    data class Classify(val features: AudioFrameFeatures, val onsetElapsedMs: Long) : AudioGateDecision
}
```

- [ ] **Step 1: Write RED tests** proving median/P95/MAD resist isolated spikes; 10-second stable acceptance; noisy extension to 30 seconds; clipping rejection; safe 30-second adaptation; 1.5 dB clamp; baseline freeze on every forbidden activity; and gate threshold relative to P95/median.
- [ ] **Step 2: Run RED**:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests "*RobustAudioCalibratorTest" --tests "*AudioSignalGateTest"
```

- [ ] **Step 3: Implement pure Kotlin math only.** Process exactly the positive `readSize`; never scan stale tail samples. Reuse preallocated arrays/ring storage; do not add FFT or DSP dependencies.
- [ ] **Step 4: Run GREEN** and confirm deterministic tests do not depend on wall-clock sleeps.
- [ ] **Step 5: Add boundary/property cases** for silence, full-scale samples, NaN avoidance, time moving backward, and fewer than 80 frames. Run GREEN again.

---

### Task 3: Pin YAMNet, Map Only Approved Labels, and Prove the Model Contract

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create binary: `app/src/main/assets/yamnet.tflite`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/audio/AudioThreatLabelMapper.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/audio/YamNetAudioThreatClassifier.kt`
- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/audio/AudioThreatLabelMapperTest.kt`
- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/audio/YamNetAssetContractTest.kt`
- Create: `app/src/androidTest/java/com/example/motorcycleantitheftsensor/sensor/audio/YamNetClassifierInstrumentedTest.kt`

**Interfaces:**

```kotlin
fun interface AudioThreatClassifier {
    fun classify(samples: FloatArray): List<AudioLabelScore>
}
data class AudioLabelScore(val label: String, val score: Double)
class AudioThreatLabelMapper {
    fun map(scores: List<AudioLabelScore>): Map<AudioThreatCategory, Double>
}
```

- [ ] **Step 1: Write mapper RED tests** for every exact approved label, case sensitivity, max-not-sum aggregation, and explicit benign labels (`Speech`, `Traffic noise, roadway noise`, `Car passing by`, `Wind`, `Rain`, `Music`, `Noise`).
- [ ] **Step 2: Run mapper RED**, then implement only the table above and run GREEN.
- [ ] **Step 3: Add dependency pin**:

```toml
[versions]
mediaPipeTasksAudio = "1.0.0"

[libraries]
mediapipe-tasks-audio = { module = "com.google.mediapipe:tasks-audio", version.ref = "mediaPipeTasksAudio" }
```

and `implementation(libs.mediapipe.tasks.audio)` in `app/build.gradle.kts`. Do not add another repository or ABI filter.

- [ ] **Step 4: Download and verify the model before copying**:

```powershell
$modelUrl='https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/latest/yamnet.tflite'
$modelTemp=Join-Path $env:TEMP 'motorcycle-guard-yamnet.tflite'
Invoke-WebRequest -UseBasicParsing $modelUrl -OutFile $modelTemp
(Get-Item -LiteralPath $modelTemp).Length
(Get-FileHash -Algorithm SHA256 -LiteralPath $modelTemp).Hash
```

Expected exact size/hash from Global Constraints. If either differs, delete only the named temporary file and stop; never accept a new hash automatically. Copy the verified file to the exact asset path, then delete only the named temporary file.

- [ ] **Step 5: Write asset contract RED/GREEN** that reads the asset file from the project filesystem during host tests and asserts size/hash. It must fail if the model is missing or changed.
- [ ] **Step 6: Implement classifier adapter** using `RunningMode.AUDIO_CLIPS`, `BaseOptions.setModelAssetPath("yamnet.tflite")`, `AudioData` from the provided float array, CPU default, synchronous `classify()` called only by the dedicated inference worker, and `close()`.
- [ ] **Step 7: Instrumented contract test** loads the asset, classifies 15,600 zero samples off the main thread, verifies a non-crashing result with category names/scores, then closes the classifier. Do not assert that silence maps to a theft category.
- [ ] **Step 8: Compile and run gates**:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests "*AudioThreatLabelMapperTest" --tests "*YamNetAssetContractTest"
.\gradlew.bat --no-daemon --max-workers=1 compileDebugAndroidTestKotlin assembleDebug
```

Expected: dependency resolves, tests pass, APK builds, asset is packaged.

---

### Task 4: Build the Session-Scoped Candidate Buffer

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/audio/AudioThreatCandidateBuffer.kt`
- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/audio/AudioThreatCandidateBufferTest.kt`

**Interfaces:**

```kotlin
class AudioThreatCandidateBuffer(
    private val maxCandidates: Int = 8,
    private val expiryMs: Long = 15_000L,
) {
    fun beginSession(armedSessionId: String)
    fun record(sessionId: String, detection: AudioThreatMetadata): AudioThreatMetadata?
    fun candidates(sessionId: String, nowElapsedMs: Long): List<AudioThreatMetadata>
    fun consume(category: AudioThreatCategory, sessionId: String): AudioThreatMetadata?
    fun clear()
}
```

- [ ] **Step 1: Write RED tests** for one-shot versus two-window thresholds, 2-second sustained window, coalescing, max confidence, average loudness, count, eight-entry cap, 15-second expiry, stale-session rejection, consume-on-confirm, and all clear paths.
- [ ] **Step 2: Run RED**, implement without persistence/static globals, then run GREEN.
- [ ] **Step 3: Add a source contract test** that fails if candidate code imports `java.io`, Android storage, preferences, repository, Telegram, or logging APIs.

---

### Task 5: Replace Loudness-Only Capture With the Bounded Audio Pipeline

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/audio/AudioThreatPipeline.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/AudioPeakDetector.kt`
- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/audio/AudioThreatPipelineTest.kt`
- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/AudioPeakDetectorSourceContractTest.kt`

**Interfaces:**

```kotlin
interface AudioRecorderBackend {
    val initialized: Boolean
    val recording: Boolean
    fun start()
    fun read(target: ShortArray): Int
    fun stop()
    fun release()
}

class AudioThreatPipeline(
    classifier: AudioThreatClassifier,
    onCandidate: (SensorObservation) -> Unit,
    onTelemetry: (AudioTelemetry) -> Unit,
    onHealthFailure: (String) -> Unit,
) {
    fun start(armedSessionId: String): Boolean
    fun stop()
    suspend fun runSelfTest(durationMs: Long = 5_000L): MicrophoneSelfTestResult
}
```

- [ ] **Step 1: Write RED tests with fakes** for state sequence, recording-state failure, bounded queue capacity three/drop oldest, single inference concurrency, DSP gating, 15,600-sample float conversion, no inference during first 10 seconds, noisy 30-second state, 1/2/4-second retries, 60-second degraded recovery, dead-object rebuild, invalid-operation failure, five-second stale recovery, disarm cancellation, generation rejection, classifier exception, exactly-once release, and telemetry throttled to at most 4 Hz.
- [ ] **Step 2: Run RED** for the two new test classes.
- [ ] **Step 3: Implement pipeline workers** with injected dispatchers/clocks/delay for deterministic tests. Queue policy is capacity three/drop oldest. Reuse PCM/float/ring buffers where ownership is unambiguous.
- [ ] **Step 4: Refactor `AudioPeakDetector` narrowly**: keep its public class name and `startListening()/stopListening()` compatibility; change sample rate to 16 kHz; own the Android backend; verify initialized/recording state; translate read codes; forward no raw samples beyond the pipeline; remove silent exception swallowing and report stable codes.
- [ ] **Step 5: Source contract assertions** must find 16 kHz, recording-state verification, explicit error constants, bounded capacity, and must not find file output/audio encoders/raw-sample logging or `Thread.sleep(200)`.
- [ ] **Step 6: Run GREEN** and repeat start/stop/restart tests 100 times in a loop inside the unit test to expose ownership races.

---

### Task 6: Wire Runtime Health, Session Clearing, and Disarmed Self-Test

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntime.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/SensorObservationProcessor.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt`
- Modify tests: matching existing `AndroidProtectionRuntimeTest.kt`, `SensorObservationProcessorTest.kt`, and `ProtectionCoordinatorTest.kt`

**Interfaces:**

```kotlin
data class MicrophoneSelfTestResult(val success: Boolean, val detail: String, val observedLevelDbfs: Double?)
// ProtectionRuntime safe default returns unsupported for non-Android fakes.
// ProtectionCoordinator exposes val audioTelemetry: StateFlow<AudioTelemetry>.
suspend fun ProtectionCoordinator.testMicrophone(): MicrophoneSelfTestResult
```

- [ ] **Step 1: RED tests** prove typed audio candidates bypass the legacy mic arithmetic threshold/debounce while raw/untyped mic observations are rejected; other sensor decisions remain byte-for-byte equivalent.
- [ ] **Step 2: RED coordinator tests** prove self-test is allowed only in `DISARMED_ONLINE`, rejected during ARMING/armed/alert/offline/setup, cannot open an incident or change protection state, and concurrent arm waits for or cancels the diagnostic safely.
- [ ] **Step 3: RED runtime tests** prove audio health/telemetry failures do not stop other detectors, start/reset session correctly, and stop clears candidates/retries.
- [ ] **Step 4: Make one arm-session ID authoritative.** Add `ProtectionRuntime.startDetectors(armedSessionId: String)` as a default overload that delegates to the existing no-argument method so existing test fakes remain source-compatible. `AndroidProtectionRuntime` overrides the typed overload and passes the ID to the audio pipeline. `ProtectionCoordinator` creates one nonblank UUID at the beginning of each accepted arm attempt, exposes it read-only through `currentArmedSessionId()`, passes it to `startDetectors(id)`, and clears it on failed arm/disarm/service stop. Change the existing graph collector to pass this same ID to `livePursuitCoordinator.onProtectionStateChanged`; remove its local session-ID generator. Do not persist the ID in audio code.
- [ ] **Step 5: Implement the remaining minimal contracts and wiring.** Remove microphone entries from the old `debounceSamples`/`thresholdDeltas` maps only after typed pipeline tests are green. Do not alter other values.
- [ ] **Step 6: Add session RED tests** proving the exact same nonblank ID reaches audio runtime and Live Pursuit for one arm, changes on the next arm, and stale callbacks from the prior ID are ignored. Failed arm and disarm must clear the ID.
- [ ] **Step 7: Keep telemetry separate.** `ProtectionCoordinator.audioTelemetry` is not a property of `ProtectionSnapshot`; updating it must not increment snapshot revision, persist a snapshot, or trigger state transitions except a stable degradation/recovery reason when audio truly becomes degraded/recovered.
- [ ] **Step 8: Run focused GREEN**:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests "*AndroidProtectionRuntimeTest" --tests "*SensorObservationProcessorTest" --tests "*ProtectionCoordinatorTest"
```

---

### Task 7: Implement Symmetric Sensor Fusion and Persist Typed Confirmed Evidence

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentEngine.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/FileIncidentRepository.kt`
- Modify tests: `IncidentEngineTest.kt`, `FileIncidentRepositoryTest.kt`

**Interfaces:**
- `IncidentEngine.accept()` remains the public entry point.
- Add `IncidentEngine.onConfirmedMovement(observation: SensorObservation, protectionState: ProtectionState, location: IncidentLocation?): IncidentUpdate` only if a typed LOCATION observation cannot be supplied through `accept()` without falsifying current location semantics.
- File format becomes version 4; versions 1-3 remain readable with `audioThreat = null`.

- [ ] **Step 1: Write the complete RED fusion matrix** covering both event orders for every row, exact 15-second boundary, 15,001 ms rejection, light-only rejection, audio-only rejection, nearby motorcycle rejection, repeated impact escalation, charger disconnect, movement, onset coherence, stale session, active incident enrichment, and non-active protection states.
- [ ] **Step 2: Run RED**:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests "*IncidentEngineTest"
```

- [ ] **Step 3: Implement smallest fusion state**. Keep only bounded precursors needed for 15 seconds; purge on every accept; clear them on close/interrupted/disarm reset. Do not create an audio incident type solely because audio exists.
- [ ] **Step 3a: Consume confirmed candidates deterministically.** Construct one `AudioThreatCandidateBuffer` in `ProtectionRuntimeGraph` and inject that same instance into the audio pipeline. After `IncidentEngine` returns a non-ignored update containing typed audio evidence, consume the matching category/session from the buffer and publish telemetry with no current candidate. This must work when vibration/movement arrives after audio. Do not consume on `Ignored` or a stale incident epoch; delivery/persistence outcome does not change the fact that sensor fusion confirmed the candidate.
- [ ] **Step 4: Write repository RED tests** for v4 typed metadata round trip, invalid confidence/category handling, and v1/v2/v3 backward compatibility.
- [ ] **Step 5: Implement v4 serialization** by appending optional audio fields per evidence. Never alter historical v1-v3 field order. Invalid optional audio metadata loads as null without discarding the whole incident; structural truncation remains an error.
- [ ] **Step 6: Run both test classes GREEN** and reopen a repository containing mixed v3/v4 fixtures.

---

### Task 8: Add the One-Way Confirmed-Movement Hook Without Changing GPS Logic

**Files:**
- Modify narrowly: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinator.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinatorTest.kt`
- Modify/add: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/AudioMovementFusionIntegrationTest.kt`

**Exact authorized GPS change:**

```kotlin
private val onMovementConfirmed: (TrackedLocationFix) -> Unit = {},
```

Invoke it exactly once when the existing `MovementDecision.Confirmed` is accepted for the current armed session. Invoke outside `stateMutex`; stale/disarmed generations must not invoke it. The callback may feed a typed movement confirmation into the incident engine. It must not send Telegram, persist movement state, start pursuit, or perform network I/O itself.

- [ ] **Step 1: RED GPS tests** prove one callback per confirmed movement, none for candidate/unusable/stale/disarmed fixes, and none after generation changes.
- [ ] **Step 2: RED integration tests** prove audio-before-movement and movement-before-audio within 15 seconds become critical, while existing pursuit begins exactly once.
- [ ] **Step 3: Implement only the callback** and graph wiring. Do not touch thresholds, locks, ordering, persistence, remote requests, retry floors, expiry, or Telegram Live Location.
- [ ] **Step 4: Run the full GPS regression set**:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests "*LivePursuitCoordinatorTest" --tests "*MovementDisplacementPolicyTest" --tests "*LocationObservationProviderTest" --tests "*TelegramLiveLocationApiTest" --tests "*TelegramLiveLocationTransportTest" --tests "*AudioMovementFusionIntegrationTest"
```

Expected: all existing GPS tests and new integration tests pass. Any GPS regression blocks the task; do not loosen a GPS assertion.

---

### Task 9: Format Confirmed Telegram Evidence and Requested Status Only

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentMessageFormatter.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusFormatter.kt`
- Modify narrowly: `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramCommandHandler.kt`
- Modify matching tests: `IncidentMessageFormatterTest.kt`, `ProtectionStatusFormatterTest.kt`, `TelegramCommandHandlerTest.kt`

- [ ] **Step 1: RED formatter tests** assert the approved Thai fields for confirmed audio, integer confidence percent, one-decimal relative dB, count, corroborators, time, optional map, and no raw PCM/absolute dBA claim.
- [ ] **Step 2: RED suppression tests** assert no formatter is called for an unconfirmed candidate and no duplicate message is created per inference window.
- [ ] **Step 3: RED `/status` tests** cover OFF, calibrating, listening/model ready, degraded/retry, stale last sample, and optional current candidate with remaining seconds.
- [ ] **Step 4: RED final-`/arm` tests** preserve the existing two replies (immediate arming acknowledgement and final command result) and append exactly one truthful audio line to the second reply: ready, calibrating, or degraded. `/disarm`, `/status`, timeout rollback, cancellation rollback, unauthorized filtering, and command parsing must remain identical.
- [ ] **Step 5: Implement only typed rendering.** Add `ProtectionStatusFormatter.formatAudioSummary(telemetry)` and reuse it for `/status` and the final successful `/arm` result. Do not parse semicolon-delimited diagnostic strings. Do not add a reply or change bot authorization/commands/transports/timeouts.
- [ ] **Step 6: Run GREEN**:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests "*IncidentMessageFormatterTest" --tests "*ProtectionStatusFormatterTest" --tests "*TelegramCommandHandlerTest"
```

---

### Task 10: Add Stable UI Telemetry and Disarmed Microphone Test

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreen.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/Navigation.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/ProtectionScreen.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt`
- Modify tests: `ProtectionViewModelTest.kt`, `ProtectionAppScreenTest.kt`
- Modify instrumented test: `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt`

**UI contract:**

- Protection microphone card shows: Off; Starting; Calibrating with remaining learning time; Listening/model ready; approximate relative meter; last sample age; candidate type/confidence and `รอยืนยันจากเซนเซอร์อื่น (หมดอายุใน N วินาที)`; degraded reason/retry.
- Settings Diagnostics shows read-only: recorder state, 16 kHz/mono, last sample, baseline median/P95, gate state, model ready, last/average inference ms, dropped frames, restart count, candidate count.
- `ทดสอบไมโครโฟน 5 วินาที` is enabled only in `DISARMED_ONLINE` and when no protection/settings operation is running. It displays result in the diagnostics card, not Snackbar/popup/Telegram/Events.
- Meter is explicitly labeled relative/approximate; no waveform and no stored audio UI.

- [ ] **Step 1: RED ViewModel tests** prove audio telemetry is combined separately, 4 Hz changes do not change destination/message/operation state, candidates do not load Events, and self-test calls coordinator only while disarmed.
- [ ] **Step 2: RED Compose tests** assert every state text, disabled self-test while armed, candidate expiry text, degraded state, and accessibility descriptions that never reveal PCM.
- [ ] **Step 3: Implement minimal UI.** Keep stable LazyColumn keys. Do not add `LaunchedEffect` navigation/message triggers from audio telemetry. Do not alter `SnapshotProjectionGate`.
- [ ] **Step 4: Flicker regression test** emit 100 telemetry updates at 250 ms logical intervals while protection state remains armed; assert destination and semantic protection card remain stable and no Snackbar/top popup event exists.
- [ ] **Step 5: Run focused GREEN and Android-test compile**:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests "*ProtectionViewModelTest" --tests "*ProtectionAppScreenTest"
.\gradlew.bat --no-daemon --max-workers=1 compileDebugAndroidTestKotlin
```

---

### Task 11: Update the Technician Guide and Regenerate the PDF

**Files:**
- Modify: `docs/sensor-fusion-technician-guide-th.html`
- Regenerate: `docs/sensor-fusion-technician-guide-th.pdf`

- [ ] **Step 1: Update the HTML** with the exact runtime states, 10-to-30-second roadside calibration, median/P95/MAD, safe 30-second adaptation/freeze rules, label categories, 15-second fusion matrix, relative-dB wording, failure/retry states, UI meanings, and field acceptance checklist.
- [ ] **Step 2: Keep technician wording bounded.** State that YAMNet estimates a category, not identity/source/distance; light alone and audio alone do not alert; real movement remains GPS-policy owned.
- [ ] **Step 3: Regenerate PDF** using installed Chrome headless:

```powershell
$chrome='C:\Program Files\Google\Chrome\Application\chrome.exe'
$html=(Resolve-Path 'docs\sensor-fusion-technician-guide-th.html').Path.Replace('\','/')
$pdf=(Resolve-Path 'docs').Path + '\sensor-fusion-technician-guide-th.pdf'
& $chrome --headless --disable-gpu --no-pdf-header-footer "--print-to-pdf=$pdf" "file:///$html"
```

- [ ] **Step 4: Verify artifacts**: PDF starts `%PDF-`, ends with `%%EOF`, is non-empty, and rendered pages contain Thai text without clipped tables. Record byte size/page count in evidence.

---

### Task 12: Full Host, APK, Privacy, and Regression Gates

**Files:**
- Modify only the evidence document.

- [ ] **Step 1: Full host gate**:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest assembleDebug compileDebugAndroidTestKotlin
```

Expected: zero failures and debug APK produced. Record task/test counts and APK path/hash; do not say device-tested yet.

- [ ] **Step 2: Model-in-APK gate**:

```powershell
$apk='app\build\outputs\apk\debug\app-debug.apk'
tar -tf $apk | Select-String 'assets/yamnet.tflite'
```

Extract the single asset to a temporary directory, re-check exact size/hash, then remove only that temporary directory after resolving and confirming it is under `$env:TEMP`.

- [ ] **Step 3: Static privacy scan**:

```powershell
rg -n "FileOutputStream|openFileOutput|\.wav|\.pcm|MediaRecorder\.OutputFormat|Log\..*samples|println\(.*samples|audio.*base64" app/src/main/java app/src/test app/src/androidTest
```

Expected: no raw-audio persistence/transmission/logging path. Review legitimate `FileOutputStream` hits such as incident persistence; do not mechanically delete them.

- [ ] **Step 4: Forbidden-scope diff audit**:

```powershell
git diff --name-only
git diff -- app/src/main/java/com/example/motorcycleantitheftsensor/location app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClient.kt app/src/main/java/com/example/motorcycleantitheftsensor/security app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/AuthenticatorQrCodeEncoder.kt
git diff -- app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramCommandHandler.kt
```

Expected: no new plan-owned hunks in forbidden files. The handler diff contains only the Task 9 final-success audio-summary append and matching import/dependency wiring; every pre-existing baseline hunk matches Task 0 evidence.

---

### Task 13: Physical Huawei and Two-Phone Acceptance

**Files:**
- Modify only the evidence document.

**Precondition:** exactly one authorized Android target for ADB; already-paired owner Telegram on a second phone; no screen recording during performance capture.

- [ ] **Step 1: Install the freshly built APK** and record APK SHA-256 plus device model/API. Do not treat an older installed APK as evidence.
- [ ] **Step 2: Permission/startup test**: grant microphone permission, arm, verify UI progresses STARTING → CALIBRATING → LISTENING, model ready, last sample advances, and no popup appears.
- [ ] **Step 3: Recorder failure test**: deny microphone permission then arm; verify armed degraded, other sensors update, Telegram commands work, retry stops on disarm, and `/status` reports the bounded reason. Restore permission and verify recovery/recalibration.
- [ ] **Step 4: Quiet calibration test**: stationary quiet environment for 10 seconds; record median/P95/MAD and stable state without recording audio.
- [ ] **Step 5: Roadside/noisy calibration test**: continuous traffic for 30 seconds; verify background learning then bounded noisy degradation if unstable, no audio-only Telegram, and no nearby motorcycle alert without own vibration/movement.
- [ ] **Step 6: Fusion matrix field test** using safe prerecorded/non-destructive sounds near the mounted phone and controlled bike movement/taps. Do not use a real drill on the motorcycle. Verify at minimum:
  1. loud sound only → candidate UI, expiry, no Telegram/Event;
  2. vibration only → existing vibration behavior unchanged;
  3. impact candidate + vibration → warning;
  4. repeated impact + continuing vibration → critical;
  5. drill/power-tool playback + vibration → critical;
  6. engine-start playback without vibration/movement → no alert;
  7. engine-start playback + bike vibration → critical;
  8. candidate + charger disconnect → critical;
  9. candidate + confirmed GPS movement → critical and existing Live Location starts once;
  10. light only and audio+light without vibration → no alert.
- [ ] **Step 7: Telegram content test** verifies type, confidence, relative background delta, count, corroborator, time, and optional map. Confirm no raw audio/file/link exists.
- [ ] **Step 8: 30-minute soak** while armed and charging: monitor last-sample age, dropped frames, inference latency, restart count, duplicate alerts, and UI stability. Pass criteria: no main-thread ANR, no recorder leak, no unbounded counters/queue growth, no overlapping inference, no screen flicker, and other sensors remain live.
- [ ] **Step 9: Disarm lifecycle test** during calibration, classification, retry backoff, and an unconfirmed candidate. Each case must release recorder/classifier work, clear candidate, stop retries, retain Telegram polling, and never deliver stale callbacks.

If a scenario is unsafe or cannot be reproduced, mark it `NOT EXECUTED` with reason. Never convert a host test into a claim of physical audio accuracy.

---

## Completion and Handoff Rules

The implementation is complete only when all of the following are true:

- Model version, byte size, SHA-256, input length, label map, and CPU execution match this plan.
- Full host gate and Android-test compilation pass after all changes.
- The fresh APK contains the verified model.
- Every row in the automated fusion matrix passes in both event orders where applicable.
- GPS focused regression tests pass after the one-way hook.
- No raw audio persistence/log/transmission path exists.
- UI shows live microphone state and diagnostic self-test without recreating top popups or arm flicker.
- Unconfirmed audio never enters Events/Telegram/SMS.
- Physical-device evidence is clearly separated from host evidence.
- The technician HTML and PDF match the implemented rules.
- Final diff contains only allowed files and preserved pre-existing hunks.

Final report must list: exact files changed, dependency/model hashes, focused RED/GREEN commands, full host result and test counts, APK hash, device scenarios executed, privacy scan result, GPS regression result, unexecuted acceptance cases, and remaining false-positive/false-negative risks.

Do not claim “detects theft accurately” from synthetic playback. The bounded claim after acceptance is: “The device classifies the approved sound categories on-device and only delivers an audio-related incident when the approved independent sensor-fusion rule confirms it.”

## Authoritative References

- Google MediaPipe Audio Classifier Android guide: `https://developers.google.com/edge/mediapipe/solutions/audio/audio_classifier/android`
- Google MediaPipe Audio Classifier model overview: `https://developers.google.com/edge/mediapipe/solutions/audio/audio_classifier/index`
- Official YAMNet model: `https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/latest/yamnet.tflite`
- Official 521-label list: `https://storage.googleapis.com/mediapipe-tasks/audio_classifier/yamnet_label_list.txt`
- Google Maven metadata used to pin Tasks Audio 1.0.0: `https://dl.google.com/dl/android/maven2/com/google/mediapipe/tasks-audio/maven-metadata.xml`
