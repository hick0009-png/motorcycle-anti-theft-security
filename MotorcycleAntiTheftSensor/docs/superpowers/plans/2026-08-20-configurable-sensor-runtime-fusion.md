# Configurable Sensor Runtime and Fusion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace scanner-only declarations and fixed accelerometer/light wiring with real configurable Android sensor adapters, calibration, adaptive sampling, fusion, and authoritative arm eligibility.

**Architecture:** Pure configuration and policy types define desired behavior. `AndroidSensorCatalog` discovers hardware, `SensorSourceAdapter` implementations own registrations, and `DefaultSensorCapabilityController` owns lifecycle/calibration generations. `ProtectionRuntime` and `ProtectionCoordinator` consume controller results while existing observation/incident paths remain authoritative.

**Tech Stack:** Kotlin 2.3.20, Android `SensorManager`, coroutines/StateFlow, JUnit 4, AndroidX test, no new dependencies.

**Spec:** `docs/superpowers/specs/2026-08-20-configurable-sensor-fusion-design.md`

## Global Constraints

- Follow the master handoff `docs/superpowers/plans/2026-08-20-configurable-sensor-fusion-agent-handoff.md`.
- Preserve all unrelated dirty changes; stage exact files only.
- Before each task, prove every listed target is clean relative to the owner-approved execution baseline. If not, stop and reconcile the baseline first; exact-path staging does not separate old and new hunks.
- Keep `ProtectionCoordinator` authoritative and keep sensor callbacks off the main thread.
- Do not use `TYPE_ORIENTATION`; do not expose unrestricted 250 Hz.
- Do not remove `VibrationDetector`, `LightIntrusionDetector`, or `SensorScanner` until replacement wiring and contract tests are green.
- Every task uses RED-GREEN-REFACTOR and an exact-path commit.

All commands in this plan run from the Android project root. Start every execution/review session with:

```powershell
$approvedProjectRoot = Read-Host 'Enter the approved clean worktree MotorcycleAntiTheftSensor path'
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
Set-Location -LiteralPath $approvedProjectRoot
git status --short
```

Stop before editing if any task target is dirty relative to the owner-approved baseline.

---

## File Structure

### New production files

- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/SensorConfigurationModels.kt`: shared domain enums/configuration/issues.
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/SensorConfigurationPolicy.kt`: presets, sensitivity mapping, bounds, arm-role validation.
- `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/AndroidSensorCatalog.kt`: hardware discovery only.
- `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/SensorSourceAdapter.kt`: adapter contract and compact raw sample.
- `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/AndroidSensorClient.kt`: injectable continuous/trigger registration and cancellation boundary over `SensorManager`.
- `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/SensorHandlerOwner.kt`: dedicated handler-thread ownership and deterministic shutdown.
- `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/ContinuousSensorAdapter.kt`: background `SensorEventListener` adapter.
- `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/SignificantMotionAdapter.kt`: one-shot trigger adapter.
- `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/SensorCalibrationManager.kt`: per-group generation and robust baseline/readiness.
- `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/SensorObservationNormalizer.kt`: source-specific finite/range/delta/orientation normalization.
- `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/SensorCapabilityController.kt`: interface plus `DefaultSensorCapabilityController` lifecycle, configuration diff, adaptive sampling, health.

### Existing production files to modify

- `protection/ProtectionModels.kt`
- `protection/ProtectionRuntime.kt`
- `protection/AndroidProtectionRuntime.kt`
- `protection/ProtectionCoordinator.kt`
- `protection/ProtectionHealthPolicy.kt`
- `protection/SensorObservation.kt`
- `protection/SensorObservationProcessor.kt`
- `protection/IncidentEngine.kt`
- `protection/SecurityIncident.kt`
- `protection/ProtectionRuntimeGraph.kt`
- `sensor/VibrationDetector.kt`
- `sensor/LightIntrusionDetector.kt`
- `sensor/SensorScanner.kt`

### New focused tests

- `app/src/test/java/com/example/motorcycleantitheftsensor/protection/SensorConfigurationPolicyTest.kt`
- `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/AndroidSensorCatalogTest.kt`
- `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/SensorSourceAdapterTest.kt`
- `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/SensorCalibrationManagerTest.kt`
- `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/SensorObservationNormalizerTest.kt`
- `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/SensorCapabilityControllerTest.kt`
- `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ConfigurableSensorFusionTest.kt`
- `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ConfigurableSensorRuntimeIntegrationTest.kt`

## Task 1: Domain Configuration and Policy

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/SensorConfigurationModels.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/SensorConfigurationPolicy.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/SensorConfigurationPolicyTest.kt`

**Interfaces:**
- Produces: `SensorCapability`, `SensorSource`, `SensorRole`, `SensorPreset`, `SensorSamplingProfile`, `SensorSourceConfiguration`, `SensorCapabilityConfiguration`, `SensorFusionConfiguration`, `SensorConfigurationIssue`, and `SensorConfigurationPolicy`.
- Consumed by: every later runtime/settings/message task.

- [ ] **Step 1: Write failing policy tests**

```kotlin
@Test fun balancedProfileHasApprovedRoles() {
    val config = policy.forPreset(SensorPreset.BALANCED, nowMs = 100L)
    assertEquals(SensorRole.PRIMARY, config.source(SensorSource.ACCELEROMETER).role)
    assertEquals(SensorRole.PRIMARY, config.source(SensorSource.AMBIENT_LIGHT).role)
    assertEquals(SensorRole.SUPPORTING, config.source(SensorSource.GYROSCOPE).role)
    assertEquals(SensorRole.SUPPORTING, config.source(SensorSource.PROXIMITY).role)
}

@Test fun sensitivityMappingIsMonotonicAndNeverDisablesDebounce() {
    val low = policy.parameters(SensorSource.ACCELEROMETER, 1)
    val high = policy.parameters(SensorSource.ACCELEROMETER, 10)
    assertTrue(low.threshold > high.threshold)
    assertTrue(high.debounceMs >= SensorConfigurationPolicy.MIN_DEBOUNCE_MS)
}
```

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.protection.SensorConfigurationPolicyTest" --no-daemon --max-workers=1 --console=plain
```

Expected: compile/test failure because the configuration types do not exist.

- [ ] **Step 3: Implement the minimal immutable model**

```kotlin
enum class SensorCapability { MOVEMENT, ROTATION, MAGNETIC, LIGHT, PROXIMITY }
enum class SensorRole { OFF, SUPPORTING, PRIMARY }
enum class SensorPreset { BATTERY_SAVER, BALANCED, MAXIMUM_PROTECTION }
enum class SensorPresetDisplay { BATTERY_SAVER, BALANCED, MAXIMUM_PROTECTION, CUSTOM }
enum class SensorSamplingProfile { BATTERY_SAVER, BALANCED, RESPONSIVE }
enum class SensorSourceFamily { MOTION, ORIENTATION, MAGNETIC_ORIENTATION, LIGHT, PROXIMITY }
enum class SensorUnit { METERS_PER_SECOND_SQUARED, RADIANS_PER_SECOND, DEGREES, MICROTESLA, LUX_RATIO, NORMALIZED_STATE, TRIGGER }

data class SensorDetectionParameters(
    val threshold: Double?,
    val unit: SensorUnit,
    val absoluteSafetyLimit: Double?,
    val debounceMs: Long,
    val confirmationDurationMs: Long,
    val requestedSamplingPeriodUs: Int?,
)

enum class SensorSource(
    val capability: SensorCapability,
    val family: SensorSourceFamily,
) {
    SIGNIFICANT_MOTION(SensorCapability.MOVEMENT, SensorSourceFamily.MOTION),
    ACCELEROMETER(SensorCapability.MOVEMENT, SensorSourceFamily.MOTION),
    LINEAR_ACCELERATION(SensorCapability.MOVEMENT, SensorSourceFamily.MOTION),
    GYROSCOPE(SensorCapability.ROTATION, SensorSourceFamily.ORIENTATION),
    ROTATION_VECTOR(SensorCapability.ROTATION, SensorSourceFamily.ORIENTATION),
    GAME_ROTATION_VECTOR(SensorCapability.ROTATION, SensorSourceFamily.ORIENTATION),
    MAGNETIC_FIELD(SensorCapability.MAGNETIC, SensorSourceFamily.MAGNETIC_ORIENTATION),
    GEOMAGNETIC_ROTATION_VECTOR(SensorCapability.MAGNETIC, SensorSourceFamily.MAGNETIC_ORIENTATION),
    AMBIENT_LIGHT(SensorCapability.LIGHT, SensorSourceFamily.LIGHT),
    PROXIMITY(SensorCapability.PROXIMITY, SensorSourceFamily.PROXIMITY),
}

data class SensorSourceConfiguration(
    val source: SensorSource,
    val role: SensorRole,
    val thresholdOverride: Double? = null,
    val debounceOverrideMs: Long? = null,
    val samplingProfileOverride: SensorSamplingProfile? = null,
)

data class SensorCapabilityConfiguration(
    val capability: SensorCapability,
    val sensitivity: Int,
    val correlationWindowMs: Long = 3_000L,
    val confirmationDurationMs: Long = 1_500L,
    val sources: Map<SensorSource, SensorSourceConfiguration>,
)

data class SensorFusionConfiguration(
    val schemaVersion: Int = 1,
    val basePreset: SensorPreset,
    val samplingProfile: SensorSamplingProfile,
    val capabilities: Map<SensorCapability, SensorCapabilityConfiguration>,
    val updatedAtMs: Long,
)

data class SensorConfigurationIssue(
    val capability: SensorCapability?,
    val source: SensorSource?,
    val code: SensorConfigurationIssueCode,
)

enum class SensorConfigurationIssueCode {
    INVALID_VALUE,
    UNAVAILABLE,
    REGISTRATION_FAILED,
    CALIBRATION_FAILED,
    STALE,
    NO_READY_PRIMARY,
}

enum class SensorDiagnosticCode {
    BELOW_THRESHOLD,
    INVALID_VECTOR,
    UNRELIABLE_ACCURACY,
    ABSOLUTE_LIMIT,
    STALE_GENERATION,
}

```

Implement `forPreset`, `validateForSave`, `parameters`, `source`, group-summary/derived-`CUSTOM` display, and safe override bounds. `CUSTOM` is never persisted as a reset target: edits retain `basePreset`, and Reset re-applies that preset. A structurally valid configuration may contain no `PRIMARY` source while disarmed; it remains saveable but is explicitly arm-ineligible. Hardware readiness is a later runtime concern.

Lock the initial bounded policy in tests; these are conservative requested defaults to validate on device, not claims about hardware-delivered cadence:

| Source | Sensitivity 1 threshold | Sensitivity 10 threshold | Absolute sanity limit | Unit |
| --- | ---: | ---: | ---: | --- |
| Accelerometer | 4.0 | 0.4 | 20.0 | `m/s²` baseline delta |
| Linear Acceleration | 3.0 | 0.3 | 15.0 | `m/s²` baseline delta |
| Significant Motion | trigger | trigger | platform trigger | one-shot |
| Gyroscope | 2.5 | 0.25 | 10.0 | `rad/s` |
| Rotation Vector | 35 | 6 | 120 | degrees from reference |
| Game Rotation Vector | 35 | 6 | 120 | degrees from reference |
| Magnetic Field | 30 | 6 | 200 | `µT` magnitude/direction delta |
| Geomagnetic Rotation Vector | 45 | 8 | 120 | degrees from reference |
| Ambient Light | 4.0 | 1.25 | 20.0 | baseline ratio; absolute fallback capped at 10,000 lux |
| Proximity | 0.8 | 0.2 | 0.0-1.0 | normalized distance/state delta |

Interpolate monotonically between endpoints. Validate advanced thresholds against the exact per-source storage bounds in Settings Task 1; debounce is 250-10,000 ms, correlation window 500-30,000 ms, confirmation duration 250-10,000 ms, and calibration sample storage is capped at 512 samples per source. Requested continuous rates are 1/5/10 Hz in watch mode and 25/50/100 Hz in confirmation for Battery Saver/Balanced/Responsive, further capped by each descriptor's delay limits. Light and proximity never request faster than 10 Hz. Any measured battery/thermal problem discovered during device acceptance requires a new reviewed constant change, not an unbounded UI value.

- [ ] **Step 4: Run GREEN and boundary tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.protection.SensorConfigurationPolicyTest" --no-daemon --max-workers=1 --console=plain
```

Expected: all policy tests pass, including sensitivity 1 and 10, invalid 0/11, negative/NaN overrides, and `CUSTOM` detection.

- [ ] **Step 5: Commit exact files**

```powershell
git add -- app/src/main/java/com/example/motorcycleantitheftsensor/protection/SensorConfigurationModels.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/SensorConfigurationPolicy.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/SensorConfigurationPolicyTest.kt
git commit -m "feat: define configurable sensor policy"
```

## Task 2: Android Sensor Catalog

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/AndroidSensorCatalog.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/AndroidSensorCatalogTest.kt`
- Preserve until Task 10: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/SensorScanner.kt`

**Interfaces:**
- Consumes: `SensorSource` from Task 1.
- Produces: `SensorDescriptor`, `SensorCatalog`, `AndroidSensorCatalog`, `SensorTypeMap.androidType(source)`.

- [ ] **Step 1: Write failing catalog tests with a fake query boundary**

```kotlin
@Test fun catalogReturnsEverySupportedSourceAndMarksMissingHardware() {
    val catalog = AndroidSensorCatalog(FakeSensorQuery(availableTypes = setOf(1, 4)))
    val result = catalog.scan()
    assertEquals(SensorSource.entries.toSet(), result.keys)
    assertTrue(result.getValue(SensorSource.ACCELEROMETER).available)
    assertFalse(result.getValue(SensorSource.PROXIMITY).available)
}

@Test fun deprecatedOrientationTypeIsNeverQueried() {
    val query = RecordingSensorQuery()
    AndroidSensorCatalog(query).scan()
    assertFalse(query.requestedTypes.contains(3))
}
```

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.sensor.AndroidSensorCatalogTest" --no-daemon --max-workers=1 --console=plain
```

Expected: failure because the catalog/query boundary does not exist.

- [ ] **Step 3: Implement catalog mapping and metadata**

```kotlin
data class SensorDescriptor(
    val source: SensorSource,
    val androidType: Int,
    val available: Boolean,
    val name: String? = null,
    val vendor: String? = null,
    val reportingMode: Int? = null,
    val wakeUp: Boolean = false,
    val minDelayUs: Int? = null,
    val maxDelayUs: Int? = null,
    val maximumRange: Float? = null,
    val resolution: Float? = null,
    val powerMa: Float? = null,
)

data class AndroidSensorMetadata(
    val name: String,
    val vendor: String,
    val reportingMode: Int,
    val wakeUp: Boolean,
    val minDelayUs: Int,
    val maxDelayUs: Int,
    val maximumRange: Float,
    val resolution: Float,
    val powerMa: Float,
)

fun interface AndroidSensorQuery { fun metadata(androidType: Int): AndroidSensorMetadata? }
fun interface SensorCatalog { fun scan(): Map<SensorSource, SensorDescriptor> }
```

`SensorManagerSensorQuery(sensorManager)` is the concrete implementation and `AndroidSensorCatalog` consumes the query. Map only the ten approved types. The catalog does not register listeners and never reports `HEALTHY`.

- [ ] **Step 4: Run GREEN**, then add tests for wake-up/reporting-mode metadata and absent virtual sensors.

- [ ] **Step 5: Commit exact files** with message `feat: add Android sensor catalog`.

## Task 3: Adapter Contract and Background Registration

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/SensorSourceAdapter.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/AndroidSensorClient.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/SensorHandlerOwner.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/ContinuousSensorAdapter.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/SignificantMotionAdapter.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/SensorSourceAdapterTest.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/SensorThreadDispatchingContractTest.kt`

**Interfaces:**
- Consumes: `SensorSource`, `SensorDescriptor`.
- Produces: `RawSensorSample`, `SensorStartRequest`, `SensorRegistrationResult`, `SensorSourceAdapter`.

- [ ] **Step 1: Write RED lifecycle tests**

```kotlin
@Test fun continuousAdapterRegistersWithBackgroundHandlerAndStopsIdempotently() {
    val platform = FakeContinuousRegistration()
    val adapter = ContinuousSensorAdapter(SensorSource.ACCELEROMETER, platform)
    assertTrue(adapter.start(request(), ::record).registered)
    adapter.stop()
    adapter.stop()
    assertEquals(1, platform.unregisterCount)
}

@Test fun significantMotionWaitsForControllerBeforeRerequest() {
    val platform = FakeTriggerRegistration()
    val adapter = SignificantMotionAdapter(platform)
    adapter.start(request(), ::record)
    platform.fire()
    assertEquals(1, platform.requestCount)
    adapter.rearm()
    assertEquals(2, platform.requestCount)
}
```

Define `request()` in the same test file as `SensorStartRequest(armedSessionId = "session-1", generation = 7, samplingPeriodUs = 200_000, maxReportLatencyUs = 0)`, define `recordedSamples` as a mutable list, and implement `record(sample)` by appending to that list. The two fakes implement `AndroidSensorClient`, record the handler/thread and register/unregister or request/cancel counts, and expose typed asynchronous failure callbacks so every assertion observes the real platform boundary.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.sensor.SensorSourceAdapterTest" --tests "com.example.motorcycleantitheftsensor.sensor.SensorThreadDispatchingContractTest" --no-daemon --max-workers=1 --console=plain
```

Expected: adapter tests fail to compile while the existing thread test remains a regression baseline.

- [ ] **Step 3: Implement compact callback contracts**

```kotlin
data class SensorValueVector private constructor(val values: List<Double>) {
    init { require(values.size in 1..5) }

    companion object {
        fun copyOf(platformValues: FloatArray): SensorValueVector =
            SensorValueVector(platformValues.take(5).map(Float::toDouble))
    }
}

data class RawSensorSample(
    val source: SensorSource,
    val eventElapsedMs: Long,
    val wallClockMs: Long,
    val values: SensorValueVector,
    val accuracy: Int? = null,
    val armedSessionId: String,
    val generation: Long,
)

data class SensorStartRequest(
    val armedSessionId: String,
    val generation: Long,
    val samplingPeriodUs: Int,
    val maxReportLatencyUs: Int,
)

data class SensorRegistrationResult(
    val registered: Boolean,
    val issueCode: SensorConfigurationIssueCode? = null,
)

interface SensorSourceAdapter {
    val source: SensorSource
    fun start(request: SensorStartRequest, onSample: (RawSensorSample) -> Unit): SensorRegistrationResult
    fun stop()
}

interface TriggerSensorSourceAdapter : SensorSourceAdapter {
    fun rearm(): SensorRegistrationResult
}

interface SensorRegistrationHandle { fun cancel(): Boolean }

interface AndroidSensorClient {
    fun registerContinuous(
        source: SensorSource,
        samplingPeriodUs: Int,
        maxReportLatencyUs: Int,
        onSample: (eventElapsedNanos: Long, values: FloatArray, accuracy: Int) -> Unit,
        onFailure: (SensorConfigurationIssueCode) -> Unit,
    ): SensorRegistrationHandle?

    fun requestTrigger(
        source: SensorSource,
        onTriggered: (eventElapsedNanos: Long, values: FloatArray) -> Unit,
        onFailure: (SensorConfigurationIssueCode) -> Unit,
    ): SensorRegistrationHandle?
}
```

Implement `SensorManagerSensorClient(sensorManager, sensorTypeMap, handlerOwner)` as the concrete `AndroidSensorClient`. It owns `SensorManager` calls and always binds continuous callbacks to `SensorHandlerOwner.handler`; the trigger boundary uses Android trigger APIs. The adapter copies the bounded value vector before forwarding and never retains Android's mutable `event.values`. `SensorHandlerOwner.close()` cancels registrations before `quitSafely()` and is idempotent. Registration/re-request failures are delivered as typed issue codes outside internal locks.

- [ ] **Step 4: Run GREEN** and confirm Significant Motion uses trigger APIs, never `registerListener`.

- [ ] **Step 5: Commit exact files** with message `feat: add sensor source adapters`.

## Task 4: Calibration Generations and Normalization

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/SensorCalibrationManager.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/SensorObservationNormalizer.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/SensorCalibrationManagerTest.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/SensorObservationNormalizerTest.kt`

**Interfaces:**
- Consumes: `RawSensorSample`, `SensorCapability`, `SensorSource`, configuration policy parameters.
- Produces: `SensorCalibrationState`, `SensorBaselineProfile`, `SensorCalibrationReport`, normalized typed readings.

- [ ] **Step 1: Write RED tests for generation, stability, and timeout**

```kotlin
@Test fun sampleFromPreviousGenerationIsRejected() {
    manager.begin(SensorSource.ACCELEROMETER, generation = 2, startedAtMs = 0)
    assertEquals(CalibrationDecision.STALE_GENERATION, manager.accept(sample(generation = 1)))
}

@Test fun unstableSamplesFailAtTenSecondDeadline() {
    manager.begin(SensorSource.ACCELEROMETER, generation = 1, startedAtMs = 0)
    feedAlternatingAccelerationSpikes(manager)
    assertEquals(SensorCalibrationState.FAILED, manager.finish(SensorSource.ACCELEROMETER, 1, 10_000).state)
}
```

Add per-source tests for gyro bias, rotation reference, magnetic unreliable accuracy, dark-light baseline, proximity stable state, and Significant Motion readiness without baseline.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.sensor.SensorCalibrationManagerTest" --tests "com.example.motorcycleantitheftsensor.sensor.SensorObservationNormalizerTest" --no-daemon --max-workers=1 --console=plain
```

Expected: failure because generation-aware calibration and normalization do not exist.

- [ ] **Step 3: Implement generation-safe robust baselines**

```kotlin
enum class SensorCalibrationState { DISABLED, CALIBRATING, READY, FAILED }
enum class CalibrationDecision { ACCEPTED, REJECTED_INVALID, STALE_GENERATION }

sealed interface SensorBaselineProfile {
    val sampleCount: Int

    data class Scalar(
        val center: Double,
        val noise: Double,
        override val sampleCount: Int,
    ) : SensorBaselineProfile

    data class Vector(
        val center: List<Double>,
        val magnitudeCenter: Double,
        val noise: Double,
        override val sampleCount: Int,
    ) : SensorBaselineProfile

    data class Quaternion(
        val x: Double,
        val y: Double,
        val z: Double,
        val w: Double,
        val angularNoiseDegrees: Double,
        override val sampleCount: Int,
    ) : SensorBaselineProfile

    data class Discrete(
        val baselineNear: Boolean,
        override val sampleCount: Int,
    ) : SensorBaselineProfile

    data object TriggerReady : SensorBaselineProfile { override val sampleCount: Int = 0 }
}

data class SensorCalibrationReport(
    val source: SensorSource,
    val generation: Long,
    val state: SensorCalibrationState,
    val baseline: SensorBaselineProfile?,
    val issueCode: SensorConfigurationIssueCode?,
)

data class NormalizedSensorReading(
    val source: SensorSource,
    val family: SensorSourceFamily,
    val eventElapsedMs: Long,
    val wallClockMs: Long,
    val normalizedValue: Double,
    val baselineDelta: Double,
    val unit: SensorUnit,
    val reliable: Boolean,
    val accuracy: Int?,
    val armedSessionId: String,
    val generation: Long,
    val diagnosticCode: SensorDiagnosticCode?,
    val correlationId: String? = null,
)

sealed interface NormalizationResult {
    data class Accepted(val reading: NormalizedSensorReading) : NormalizationResult
    data class Rejected(val code: NormalizationRejectionCode) : NormalizationResult
}

enum class NormalizationRejectionCode { NON_FINITE, OUT_OF_RANGE, UNRELIABLE, STALE_GENERATION, MALFORMED_VECTOR }

interface SensorCalibrationManager {
    fun begin(source: SensorSource, generation: Long, startedAtMs: Long)
    fun accept(sample: RawSensorSample): CalibrationDecision
    fun finish(source: SensorSource, generation: Long, deadlineElapsedMs: Long): SensorCalibrationReport
    fun baseline(source: SensorSource, generation: Long): SensorBaselineProfile?
    fun invalidate(capability: SensorCapability)
    fun invalidateAll()
}

interface SensorObservationNormalizer {
    fun normalize(
        sample: RawSensorSample,
        baseline: SensorBaselineProfile,
        parameters: SensorDetectionParameters,
    ): NormalizationResult
}

```

Key every calibration accumulator by `(SensorSource, generation)`; capability generations may advance together, but source baselines/readiness never share one accumulator. Use median and median absolute deviation over at most 512 samples per source. Finish exactly at the caller's 10-second deadline; never extend indefinitely.

- [ ] **Step 4: Implement normalization tests and minimal code**

Verify finite/range checks and derive orientation from rotation vectors. Return a typed normalized result; do not put raw diagnostic text into user-facing fields.

- [ ] **Step 5: Run GREEN** for calibration and normalizer tests.

- [ ] **Step 6: Commit exact files** with message `feat: add sensor calibration and normalization`.

## Task 5: Movement Source Migration

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/VibrationDetector.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/SensorSourceAdapterTest.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntimeTest.kt`

**Interfaces:**
- Consumes: adapter/calibration/normalization contracts.
- Produces: working Significant Motion, Accelerometer, and Linear Acceleration sources with source metadata and generation.

- [ ] **Step 1: Add RED tests** proving all three sources can be registered according to role, Significant Motion is one-shot/re-requested, and `OFF` sources are not registered.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.sensor.SensorSourceAdapterTest" --tests "com.example.motorcycleantitheftsensor.protection.AndroidProtectionRuntimeTest" --no-daemon --max-workers=1 --console=plain
```

Expected: the new movement-source assertions fail against accelerometer-only fixed wiring.

- [ ] **Step 3: Adapt existing vibration behavior**

Keep current background-thread and event-throttle guarantees while moving magnitude/threshold responsibility into the normalizer/policy. Preserve a compatibility wrapper until Task 10 so existing callers compile.

- [ ] **Step 4: Run GREEN** and the existing `SensorThreadDispatchingContractTest`.

- [ ] **Step 5: Commit exact files** with message `feat: wire configurable movement sensors`.

## Task 6A: Rotation Sources

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/SensorSourceAdapterTest.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/SensorObservationNormalizerTest.kt`

**Interfaces:**
- Consumes: generic continuous adapter and normalizer.
- Produces: real Gyroscope, Rotation Vector, and Game Rotation Vector paths.

- [ ] **Step 1: Add RED parameterized source tests**

```kotlin
@Test fun everyConfiguredRotationSourceReachesNormalizer() {
    val expectedSources = setOf(
        SensorSource.GYROSCOPE,
        SensorSource.ROTATION_VECTOR,
        SensorSource.GAME_ROTATION_VECTOR,
    )
    expectedSources.forEach { source ->
        harness.enable(source, SensorRole.SUPPORTING)
        harness.emit(source, validSampleValues.getValue(source))
        assertEquals(source, harness.normalizedSamples.last().source)
    }
}
```

Create `validSampleValues` explicitly with a finite three-axis gyro vector and normalized four/five-value rotation vectors. The harness passes them through the real adapter callback and normalizer.

Include a contract assertion that Android type 3 (`TYPE_ORIENTATION`) never appears.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.sensor.SensorSourceAdapterTest" --tests "com.example.motorcycleantitheftsensor.sensor.SensorObservationNormalizerTest" --no-daemon --max-workers=1 --console=plain
```

Expected: failures identify unwired rotation sources, quaternion validation, and angle-delta rules.

- [ ] **Step 3: Wire rotation sources through the generic adapter** and implement typed `rad/s` magnitude plus quaternion-to-reference angle delta.

- [ ] **Step 4: Run GREEN**, including invalid/non-finite quaternion and no-`TYPE_ORIENTATION` tests.

- [ ] **Step 5: Commit exact files** with message `feat: wire configurable rotation sensors`.

## Task 6B: Magnetic Sources

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/SensorSourceAdapterTest.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/SensorObservationNormalizerTest.kt`

- [ ] **Step 1: Add RED tests** for Magnetic Field magnitude/direction delta, `SENSOR_STATUS_UNRELIABLE` rejection as a primary trigger, Geomagnetic Rotation Vector mapping, absolute limits, and shared-family deduplication metadata.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.sensor.SensorSourceAdapterTest" --tests "com.example.motorcycleantitheftsensor.sensor.SensorObservationNormalizerTest" --no-daemon --max-workers=1 --console=plain
```

- [ ] **Step 3: Wire both magnetic sources** through the generic adapter and normalizer using typed `µT` or degrees, reliability, generation, and `MAGNETIC_ORIENTATION` family.

- [ ] **Step 4: Run GREEN** for the same focused classes.

- [ ] **Step 5: Commit exact files** with message `feat: wire configurable magnetic sensors`.

## Task 6C: Light and Proximity Sources

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/LightIntrusionDetector.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/SensorSourceAdapterTest.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/SensorObservationNormalizerTest.kt`

- [ ] **Step 1: Add RED tests** for dark/non-dark robust light baseline, baseline ratio plus absolute fallback, proximity normalization against descriptor maximum range, stable near/far transition, debounce, and `OFF` registration exclusion.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.sensor.SensorSourceAdapterTest" --tests "com.example.motorcycleantitheftsensor.sensor.SensorObservationNormalizerTest" --no-daemon --max-workers=1 --console=plain
```

- [ ] **Step 3: Wire Light and Proximity** through the generic adapter; keep `LightIntrusionDetector` only as a compile-safe compatibility wrapper until Task 10.

- [ ] **Step 4: Run GREEN** for the same focused classes.

- [ ] **Step 5: Commit exact files** with message `feat: wire configurable light and proximity sensors`.

## Task 7: Capability Controller and Adaptive Sampling

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/SensorCapabilityController.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/SensorCapabilityControllerTest.kt`

**Interfaces:**
- Consumes: config, catalog, adapters, calibration, normalizer.
- Produces: `SensorRuntimeStatus`, `SensorSourceRuntimeStatus`, `SensorConfigurationReadiness`, `SensorCapabilityController`.

- [ ] **Step 1: Write RED state-machine tests**

Cover `OFF -> CALIBRATING -> WATCHING`, candidate `WATCHING -> CONFIRMING -> WATCHING`, affected-group-only reconfiguration, idempotent stop, partial registration failure, stale callback rejection, and no-ready-primary result.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.sensor.SensorCapabilityControllerTest" --no-daemon --max-workers=1 --console=plain
```

Expected: failure because the lifecycle/adaptive-sampling controller does not exist.

- [ ] **Step 3: Implement the controller contract**

```kotlin
enum class SensorRuntimeMode { OFF, CALIBRATING, WATCHING, CONFIRMING, FAILED }
enum class SensorReadiness { DISABLED, CALIBRATING, READY, UNAVAILABLE, FAILED, STALE }

data class SensorSourceRuntimeStatus(
    val source: SensorSource,
    val desired: SensorSourceConfiguration,
    val available: Boolean,
    val registeredOrRequested: Boolean,
    val mode: SensorRuntimeMode,
    val readiness: SensorReadiness,
    val generation: Long?,
    val lastSampleElapsedMs: Long?,
    val issueCode: SensorConfigurationIssueCode?,
)

data class SensorRuntimeStatus(
    val desiredConfiguration: SensorFusionConfiguration,
    val armedSessionId: String?,
    val sources: Map<SensorSource, SensorSourceRuntimeStatus>,
)

data class SensorConfigurationReadiness(
    val hasConfiguredAvailablePrimary: Boolean,
    val hasReadyPrimary: Boolean,
    val issues: Set<SensorConfigurationIssue>,
)

sealed interface SensorControllerApplyResult {
    val status: SensorRuntimeStatus
    val affectedCapabilities: Set<SensorCapability>

    data class Applied(
        override val status: SensorRuntimeStatus,
        override val affectedCapabilities: Set<SensorCapability>,
    ) : SensorControllerApplyResult

    data class Degraded(
        override val status: SensorRuntimeStatus,
        override val affectedCapabilities: Set<SensorCapability>,
        val issues: Set<SensorConfigurationIssue>,
    ) : SensorControllerApplyResult

    data class Failed(
        override val status: SensorRuntimeStatus,
        override val affectedCapabilities: Set<SensorCapability>,
        val issues: Set<SensorConfigurationIssue>,
        val rollbackComplete: Boolean,
    ) : SensorControllerApplyResult
}

interface SensorCapabilityController {
    val status: StateFlow<SensorRuntimeStatus>
    fun readiness(configuration: SensorFusionConfiguration): SensorConfigurationReadiness
    fun start(configuration: SensorFusionConfiguration, armedSessionId: String): DetectorStartResult
    fun completeArmingCalibration(nowElapsedMs: Long): SensorControllerApplyResult
    fun completeCapabilityCalibration(
        capability: SensorCapability,
        generation: Long,
        nowElapsedMs: Long,
    ): SensorControllerApplyResult
    fun beginConfirmation(
        capability: SensorCapability,
        correlationId: String,
        nowElapsedMs: Long,
    )
    fun endConfirmation(correlationId: String, nowElapsedMs: Long)
    suspend fun applyConfiguration(
        configuration: SensorFusionConfiguration,
        armedSessionId: String?,
    ): SensorControllerApplyResult
    fun stop()
}

fun interface SensorMonotonicClock { fun elapsedRealtimeMs(): Long }
fun interface CalibrationDelay { suspend fun untilElapsedRealtime(deadlineElapsedMs: Long) }
```

Implement `DefaultSensorCapabilityController` in the same file with constructor dependencies `SensorCatalog`, `Map<SensorSource, SensorSourceAdapter>`, `SensorCalibrationManager`, `SensorObservationNormalizer`, `SensorMonotonicClock`, `CalibrationDelay`, and `(NormalizedSensorReading) -> Unit`. Inject clocks/delay so tests do not sleep. Use one lifecycle mutex/lock, but never hold it across `untilElapsedRealtime`; snapshot the operation generation, release the lock, await, then reacquire and reject a stale completion. `applyConfiguration` starts only affected groups, assigns their new generations, and awaits their bounded calibration deadlines before returning the final structured result; `completeCapabilityCalibration` is the deterministic clock-driven completion boundary used by production scheduling and tests. `beginConfirmation`/`endConfirmation` own bounded watch-to-confirm-to-watch transitions and always re-request Significant Motion on every success, timeout, rejection, or failure exit path.

- [ ] **Step 4: Implement bounded sampling profiles**

Use named requested periods and confirmation windows in policy. Tests assert ordering (`BATTERY_SAVER` slower than `BALANCED`, which is slower than `RESPONSIVE`) rather than pretending the platform guarantees exact Hz.

- [ ] **Step 5: Run GREEN** and verify no main-thread/disk/network calls in callbacks via contract tests.

- [ ] **Step 6: Commit exact files** with message `feat: control adaptive sensor capabilities`.

## Task 8: Typed Observations, Roles, Fusion, and Deduplication

Runtime Task 8 and Message Task 2 share `SecurityIncident`, `IncidentEngine`, and `FileIncidentRepository`. Execute their persistence/model steps as one reviewed task and one schema-v5 commit; do not create sequential incompatible version-5 formats.

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/SensorObservation.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/SecurityIncident.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/SensorObservationProcessor.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentEngine.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/FileIncidentRepository.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/SensorObservationProcessorTest.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/IncidentEngineTest.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/FileIncidentRepositoryTest.kt`
- Create test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ConfigurableSensorFusionTest.kt`

**Interfaces:**
- Consumes: configuration role/source/capability and normalized readings.
- Produces: generation-safe `SensorObservation` and source-aware `IncidentEvidence`.

- [ ] **Step 1: Write RED role and deduplication tests**

```kotlin
@Test fun supportingObservationCannotOpenIncident() {
    val fixture = fixture()
    val decision = fixture.process(observation(role = SensorRole.SUPPORTING))
    assertTrue(decision is ObservationDecision.EvidenceAccepted)
    assertTrue(fixture.incidentUpdates.isEmpty())
}

@Test fun primaryObservationOpensAtMostWarning() {
    val fixture = fixture()
    assertTrue(fixture.process(observation(role = SensorRole.PRIMARY)) is ObservationDecision.CandidateAccepted)
    val opened = fixture.incidentUpdates.single() as IncidentUpdate.Opened
    assertEquals(IncidentSeverity.WARNING, opened.incident.severity)
}

@Test fun overlappingPhysicalAndVirtualSourcesUpdateOneIncident() {
    val fixture = fixture(correlationWindowMs = 2_000)
    fixture.process(observation(sourceFamily = SensorSourceFamily.MOTION))
    fixture.process(observation(sourceFamily = SensorSourceFamily.MOTION, eventElapsedMs = 1_500))
    val opened = fixture.incidentUpdates.first() as IncidentUpdate.Opened
    val updated = fixture.incidentUpdates.last() as IncidentUpdate.Updated
    assertEquals(opened.incident.id, updated.incident.id)
}

@Test fun approvedMovementPlusRotationPolicyCanEscalateExistingIncident() {
    val fixture = fixture(correlationWindowMs = 2_000)
    fixture.process(observation(source = SensorSource.ACCELEROMETER, role = SensorRole.PRIMARY, eventElapsedMs = 1_000))
    assertTrue(
        fixture.process(
            observation(source = SensorSource.GYROSCOPE, role = SensorRole.SUPPORTING, eventElapsedMs = 2_000),
        ) is ObservationDecision.EvidenceAccepted,
    )
    assertTrue(fixture.incidentUpdates.last() is IncidentUpdate.Escalated)
}

@Test fun staleCalibrationGenerationIsRejected() {
    val decision = fixture(activeGeneration = 4).process(observation(calibrationGeneration = 3))
    assertEquals(
        ObservationDecision.Rejected(ObservationRejectionCode.STALE_GENERATION),
        decision,
    )
}
```

Extend the existing decision model with typed rejection codes plus `EvidenceAccepted` and `CandidateAccepted`; do not treat valid supporting evidence as ignored:

```kotlin
enum class ObservationRejectionCode { INVALID, STALE_SESSION, STALE_GENERATION, UNRELIABLE }

sealed interface ObservationDecision {
    data class Rejected(val reason: ObservationRejectionCode) : ObservationDecision
    data object BaselineUpdated : ObservationDecision
    data object Debounced : ObservationDecision
    data class EvidenceAccepted(val observation: SensorObservation) : ObservationDecision
    data class CandidateAccepted(val observation: SensorObservation) : ObservationDecision
}
```

Create the test fixture and builders in `ConfigurableSensorFusionTest.kt`; they must invoke the real processor and existing `IncidentEngine` with injected clocks, route supporting evidence to the engine only for correlation/update (never as a new-incident trigger), and capture typed decisions/incident updates rather than compare logs or display strings. Encode escalation as an exhaustive source-pair/confidence policy: only explicitly tested combinations such as Movement primary plus Rotation supporting may escalate; generic supporting evidence is buffered/added without an automatic severity increase.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.protection.SensorObservationProcessorTest" --tests "com.example.motorcycleantitheftsensor.protection.IncidentEngineTest" --tests "com.example.motorcycleantitheftsensor.protection.ConfigurableSensorFusionTest" --no-daemon --max-workers=1 --console=plain
```

Expected: the new role/source/generation/correlation assertions fail while retained incident regression tests expose compatibility breaks.

- [ ] **Step 3: Extend observations without breaking existing audio/location callers**

```kotlin
data class SensorObservation(
    val kind: SensorKind,
    val eventElapsedMs: Long,
    val wallClockMs: Long,
    val normalizedValue: Double,
    val baselineDelta: Double,
    val valid: Boolean,
    val diagnostic: String? = null,
    val audioThreat: AudioThreatMetadata? = null,
    val capability: SensorCapability? = null,
    val source: SensorSource? = null,
    val sourceFamily: SensorSourceFamily? = null,
    val unit: SensorUnit? = null,
    val role: SensorRole? = null,
    val armedSessionId: String? = null,
    val calibrationGeneration: Long? = null,
    val accuracy: Int? = null,
    val reliable: Boolean? = null,
    val diagnosticCode: SensorDiagnosticCode? = null,
    val correlationId: String? = null,
)

```

Defaults preserve existing producers during migration. New sensor sources must populate every new field and must not populate the legacy raw `diagnostic` string.

Keep microphone, location, and power/thermal producers on their existing specialized adapters and aggregate them with the new controller at the runtime boundary. They are not configurable members of the five new capability groups.

- [ ] **Step 4: Implement role-aware processor and incident correlation** using session, generation, source family, and bounded time window.

- [ ] **Step 5: Migrate source-aware incident evidence persistence**

Bump `FileIncidentRepository.FILE_VERSION` from 4 to 5 once, together with Message Task 2. Version 5 persists optional capability, source, source family, unit, reliability, bounded diagnostic code, and correlation ID after existing evidence fields, followed by the typed origin/transition chronology defined by Message Task 2. Keep V1-V4 readers byte-compatible and default new sensor fields to null while synthesizing only fact-supported legacy transitions. Add V4 fixture coverage plus one V5 round trip proving both source metadata and chronology survive process recovery; never persist raw platform sample arrays or presentation strings.

- [ ] **Step 6: Run GREEN**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.protection.SensorObservationProcessorTest" --tests "com.example.motorcycleantitheftsensor.protection.IncidentEngineTest" --tests "com.example.motorcycleantitheftsensor.protection.ConfigurableSensorFusionTest" --tests "com.example.motorcycleantitheftsensor.protection.FileIncidentRepositoryTest" --tests "com.example.motorcycleantitheftsensor.protection.AudioMovementFusionIntegrationTest" --no-daemon --max-workers=1 --console=plain
```

- [ ] **Step 7: Commit the shared Runtime 8 / Message 2 files once**

```powershell
git add -- app/src/main/java/com/example/motorcycleantitheftsensor/protection/SensorObservation.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/SecurityIncident.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/SensorObservationProcessor.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentEngine.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentUpdateDeliveryPolicy.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/FileIncidentRepository.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/SensorObservationProcessorTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/IncidentEngineTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/IncidentUpdateDeliveryPolicyTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/FileIncidentRepositoryTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/ConfigurableSensorFusionTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/AudioMovementFusionIntegrationTest.kt
git diff --cached --check
git diff --cached
git commit -m "feat: persist fused incident evidence and chronology"
```

## Task 9: Runtime and Coordinator Arm/Live-Apply Contract

Runtime Task 9 and Settings Task 3 are one shared authoritative-apply task. Execute them after Runtime Tasks 2-8 and Settings Task 2, then create one reviewed commit covering the coordinator, runtime contract, desired/effective projection, and tests.

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionModels.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntime.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionHealthPolicy.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionModelsTest.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntimeTest.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinatorTest.kt`
- Create test: `app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModelsTest.kt`
- Create test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ConfigurableSensorRuntimeIntegrationTest.kt`

**Interfaces:**
- Consumes: controller and runtime status.
- Produces: `applySensorConfiguration`, source/group health in snapshot, primary-ready arm decision.

- [ ] **Step 1: Write RED coordinator tests**

Cover: reject arm when no ready primary; arm healthy with primary and all enabled sources ready; arm degraded with ready primary plus failed supporting; disabled source does not degrade; after 10 seconds failed calibration blocks when it was the last primary; live apply recalibrates one group; removing last primary transitions out of armed state and persists the result.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.protection.ProtectionModelsTest" --tests "com.example.motorcycleantitheftsensor.protection.AndroidProtectionRuntimeTest" --tests "com.example.motorcycleantitheftsensor.protection.ProtectionCoordinatorTest" --tests "com.example.motorcycleantitheftsensor.protection.ConfigurableSensorRuntimeIntegrationTest" --tests "com.example.motorcycleantitheftsensor.ui.ProtectionUiModelsTest" --no-daemon --max-workers=1 --console=plain
```

Expected: failures show missing health states, apply contract, and primary-ready arm policy.

- [ ] **Step 3: Add compatibility-first runtime methods**

```kotlin
interface ProtectionRuntime {
    // Existing members remain until callers migrate.
    fun desiredSensorConfiguration(): SensorFusionConfiguration
    val sensorRuntimeStatus: StateFlow<SensorRuntimeStatus>
    fun completeSensorCalibration(nowElapsedMs: Long): SensorControllerApplyResult
    suspend fun applySensorConfiguration(
        desired: SensorFusionConfiguration,
        affectedCapabilities: Set<SensorCapability>,
        operationId: String,
    ): RuntimeSensorConfigurationResult
}
```

Add `DISABLED` and `CALIBRATING` health states and update all exhaustive `when` expressions, including `ProtectionUiModels`, in the same compile-green task. Add structured source/group health rather than encoding it in degradation strings.

- [ ] **Step 4: Change arm evaluation** so preflight checks available configured primary, post-delay checks registered/ready primary, and final state uses enabled-source health.

- [ ] **Step 5: Add suspend coordinator command**

```kotlin
suspend fun applySensorConfiguration(
    operationId: String,
    desired: SensorFusionConfiguration,
    confirmedLastPrimaryRemoval: Boolean,
): SensorConfigurationApplyResult
```

Implement this together with Settings Task 3 after Settings Task 2 provides the repository. Serialize it with the dedicated sensor-configuration mutex, persist the full validated desired document before runtime apply, and publish actual controller effective state. Do not land a runtime-only success stub or a Balanced-default fallback.

- [ ] **Step 6: Run GREEN** plus full `ProtectionCoordinatorTest`.

- [ ] **Step 7: Commit the shared Runtime 9 / Settings 3 files once** using the exact union staging command in Settings Task 3 and message `feat: apply sensor configuration authoritatively`.

## Task 10: Runtime Graph Integration and Legacy Cleanup

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/SensorScanner.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/DashboardScreen.kt`
- Modify or remove after proof: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/VibrationDetector.kt`
- Modify or remove after proof: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/LightIntrusionDetector.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ConfigurableSensorRuntimeIntegrationTest.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/SensorThreadDispatchingContractTest.kt`
- Create instrumented test: `app/src/androidTest/java/com/example/motorcycleantitheftsensor/sensor/AndroidSensorCatalogInstrumentedTest.kt`
- Create instrumented test: `app/src/androidTest/java/com/example/motorcycleantitheftsensor/sensor/SensorAdapterInstrumentedTest.kt`

**Interfaces:**
- Consumes: final controller/runtime contracts.
- Produces: one injected catalog/controller truth path; no floating scanner.

- [ ] **Step 1: Write RED graph contract tests** proving catalog, configuration, controller, runtime, coordinator, and health stream are wired once and `SensorScanner.scanHardwareSensors()` has no remaining production caller. Add a compile-level UI mapper test for any `SensorStatusItem` data still imported by `DashboardScreen`; move that presentation type to the authoritative Settings/UI model before deleting the scanner container.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.protection.ConfigurableSensorRuntimeIntegrationTest" --tests "com.example.motorcycleantitheftsensor.sensor.SensorThreadDispatchingContractTest" --no-daemon --max-workers=1 --console=plain
```

Expected: graph/call-site contract fails until the authoritative controller is wired and legacy callers are migrated.

- [ ] **Step 3: Wire the graph** with injected clocks, handler factory, adapters, calibration manager, normalizer, and the repository from Settings Task 2. This task is blocked until Settings Tasks 1-3 are integrated. Load the persisted desired configuration through that boundary; do not ship a hard-coded Balanced fallback except inside the repository's tested first-run/corrupt-schema recovery result.

- [ ] **Step 4: Remove compatibility wrappers only after searches are empty**

```powershell
rg -n "SensorScanner|SensorStatusItem|VibrationDetector|LightIntrusionDetector" app/src/main app/src/test
```

If a legacy type still has a caller, migrate it in this task; do not leave an uncalled declaration or delete a working caller. Preserve scanner projections for microphone, barometer, GPS, and biometrics until their callers are deliberately migrated; this program replaces only the ten approved sensor-source discovery entries and must not silently remove unrelated device-capability UI. Keep `applySensitivity` only as a delegating compatibility bridge at this point; Settings Task 8 migrates Telegram `/sensitivity` and performs the final zero-caller scan before removing it.

- [ ] **Step 5: Add device-only contract tests**

`AndroidSensorCatalogInstrumentedTest` compares every available descriptor with `SensorManager.getDefaultSensor`, records missing hardware as `UNAVAILABLE`, and never asserts that all phones contain all sensors. `SensorAdapterInstrumentedTest` registers each available enabled adapter on the dedicated handler, waits for bounded fresh evidence, stops it, and proves no callback is accepted after stop/generation invalidation. Tests must restore registrations and must not create/send incidents.

- [ ] **Step 6: Run focused host and Android-test compile gates**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.protection.ConfigurableSensorRuntimeIntegrationTest" --no-daemon --max-workers=1 --console=plain
.\gradlew.bat :app:compileDebugAndroidTestKotlin --no-daemon --max-workers=1 --console=plain
.\gradlew.bat :app:testDebugUnitTest --no-daemon --max-workers=1 --console=plain
.\gradlew.bat :app:assembleDebug --no-daemon --max-workers=1 --console=plain
```

Expected: all exit 0. This does not establish device acceptance.

- [ ] **Step 7: Commit exact migrated files** with message `feat: integrate configurable sensor runtime`.

## Runtime Plan Acceptance

- Every approved source has catalog and adapter coverage.
- No source shown active lacks registration/readiness evidence.
- No-ready-primary arm requests are rejected.
- Supporting failure degrades; disabled does not.
- Ten-second calibration and per-group generation rejection are deterministic.
- Adaptive sampling and Significant Motion re-request are test-proven.
- Supporting observations cannot open incidents; overlapping sources produce one incident.
- No deprecated orientation sensor, main-thread callback work, direct listener alert, or floating scanner remains.
- Focused tests, full unit tests, and assembly are green before Settings or message work claims integration.
