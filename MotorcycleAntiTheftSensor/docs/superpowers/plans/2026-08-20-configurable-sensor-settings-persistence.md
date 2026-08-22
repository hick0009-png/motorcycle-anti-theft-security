# Configurable Sensor Settings and Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver versioned configurable-sensor persistence, authoritative desired-versus-effective state, main and advanced Settings controls, persistent configuration-apply feedback, recovery behavior, and backward-compatible Telegram sensitivity handling for the approved multi-sensor system.

**Architecture:** `ProtectionCoordinator` remains authoritative. Settings edits produce immutable desired configurations, the repository validates and commits one encrypted JSON document, and the coordinator then asks the runtime to apply an affected-group diff while publishing effective state and operation progress. Compose renders a manual nested `SettingsPage` because the current shell switches destinations directly rather than using Navigation3.

**Tech Stack:** Kotlin 2.3.20, Android API 24+, Jetpack Compose Material 3, coroutines/StateFlow, `EncryptedSharedPreferences`, `org.json`, JUnit 4, kotlinx-coroutines-test, Android Compose UI tests.

**Spec:** `docs/superpowers/specs/2026-08-20-configurable-sensor-fusion-design.md`

## Global Constraints

- Preserve `ProtectionCoordinator` as the only owner of protection-state transitions and configuration-apply results.
- Every source role is exactly `OFF`, `SUPPORTING`, or `PRIMARY`; `OFF` sources are not degradation reasons.
- The default `BALANCED` profile is Movement primary, Rotation supporting, Magnetic supporting, Light primary, and Proximity supporting.
- A structurally valid configuration with no `PRIMARY` is saveable while disarmed, but `arm()` must reject it as arm-ineligible.
- Arming requires at least one configured primary source that becomes present, registered, and ready after the 10-second calibration phase.
- While armed, a change that may remove the last effective primary requires confirmation; after confirmed application, the coordinator must leave the armed state if no effective primary remains.
- Persist desired configuration only; never persist baselines, samples, calibration generations, listener state, or effective health.
- Store the complete versioned sensor configuration as one encrypted JSON string committed atomically.
- A persistence failure leaves runtime and authoritative desired state unchanged.
- A runtime apply failure retains the newly persisted desired configuration for retry, rolls back partial listener mutations to the last safe effective configuration when possible, and publishes the remaining effective truth.
- Configuration changes restart and recalibrate only affected capability groups; unaffected groups remain operational.
- `CUSTOM` is derived from deviations; persist the named `basePreset` so Reset has deterministic behavior.
- Settings, Protection, notification, Events, and Telegram must consume the same desired/effective projection.
- Message Task 1 owns exhaustive Thai labels for shared capability/source/role values. `SensorSettingsText` delegates those labels to that catalog and contains only Settings-specific descriptions, validation, derived effective-state wording, and control text; do not create a second shared-domain label truth table.
- The compatibility command `/sensitivity N` atomically changes Movement and Light sensitivities to `N`, leaves the other groups unchanged, and derives `CUSTOM` when this differs from the base preset.
- Do not add a dependency. Use the existing `org.json` support and encrypted preference boundary.
- Do not reuse `SensorScanner`, `DashboardScreen`, or `DeviceConfig`; they are not part of the current authoritative app path.
- Preserve all unrelated dirty-worktree changes. Before every task run `git status --short` and inspect the exact target-file diff; never reset, clean, broadly stage, or overwrite unrelated work.
- Before Task 1, use `superpowers:using-git-worktrees` to create an isolated execution worktree from the coordinating agent's approved integration baseline. The exact `git add` commands below are safe only in that isolated worktree; if execution is intentionally kept in the current dirty checkout, stop before the first commit and obtain an explicit path-by-path integration decision.
- Settings Task 1 and Runtime Task 1 are one shared foundation task, not two implementations. Execute the detailed Settings Task 1 once, mark Runtime Task 1 satisfied by the same files/tests/commit, and then begin Runtime Tasks 2-8 and Settings Task 2. Before Settings Task 3, integrate the companion runtime plan through its configuration-aware `SensorCapabilityController` boundary so `ProtectionRuntime.applySensorConfiguration()` has a real implementation; do not ship a stub that reports success.
- Use Android Studio JBR and serialize Gradle/ADB work on this Windows host.

All task commands run from the Android project root after the clean approved worktree is selected:

```powershell
$approvedProjectRoot = Read-Host 'Enter the approved clean worktree MotorcycleAntiTheftSensor path'
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
Set-Location -LiteralPath $approvedProjectRoot
git status --short
```

Enter the exact project root inside the approved clean worktree. Stop if `git status --short` shows any unowned change in a task target.

## Persisted JSON Schema Version 1

The single encrypted key is `sensor_fusion_configuration_json`. The canonical payload is:

```json
{
  "schemaVersion": 1,
  "basePreset": "BALANCED",
  "samplingProfile": "BALANCED",
  "updatedAtMs": 1787245200000,
  "capabilities": [
    {
      "capability": "MOVEMENT",
      "sensitivity": 5,
      "correlationWindowMs": 3000,
      "confirmationDurationMs": 1500,
      "sources": [
        {
          "source": "SIGNIFICANT_MOTION",
          "role": "SUPPORTING",
          "thresholdOverride": null,
          "debounceOverrideMs": null,
          "samplingProfileOverride": null
        },
        {
          "source": "ACCELEROMETER",
          "role": "PRIMARY",
          "thresholdOverride": null,
          "debounceOverrideMs": null,
          "samplingProfileOverride": null
        },
        {
          "source": "LINEAR_ACCELERATION",
          "role": "SUPPORTING",
          "thresholdOverride": null,
          "debounceOverrideMs": null,
          "samplingProfileOverride": null
        }
      ]
    },
    {
      "capability": "ROTATION",
      "sensitivity": 5,
      "correlationWindowMs": 3000,
      "confirmationDurationMs": 1500,
      "sources": [
        {"source":"GYROSCOPE","role":"SUPPORTING","thresholdOverride":null,"debounceOverrideMs":null,"samplingProfileOverride":null},
        {"source":"ROTATION_VECTOR","role":"SUPPORTING","thresholdOverride":null,"debounceOverrideMs":null,"samplingProfileOverride":null},
        {"source":"GAME_ROTATION_VECTOR","role":"SUPPORTING","thresholdOverride":null,"debounceOverrideMs":null,"samplingProfileOverride":null}
      ]
    },
    {
      "capability": "MAGNETIC",
      "sensitivity": 5,
      "correlationWindowMs": 3000,
      "confirmationDurationMs": 1500,
      "sources": [
        {"source":"MAGNETIC_FIELD","role":"SUPPORTING","thresholdOverride":null,"debounceOverrideMs":null,"samplingProfileOverride":null},
        {"source":"GEOMAGNETIC_ROTATION_VECTOR","role":"SUPPORTING","thresholdOverride":null,"debounceOverrideMs":null,"samplingProfileOverride":null}
      ]
    },
    {
      "capability": "LIGHT",
      "sensitivity": 5,
      "correlationWindowMs": 3000,
      "confirmationDurationMs": 1500,
      "sources": [
        {"source":"AMBIENT_LIGHT","role":"PRIMARY","thresholdOverride":null,"debounceOverrideMs":null,"samplingProfileOverride":null}
      ]
    },
    {
      "capability": "PROXIMITY",
      "sensitivity": 5,
      "correlationWindowMs": 3000,
      "confirmationDurationMs": 1500,
      "sources": [
        {"source":"PROXIMITY","role":"SUPPORTING","thresholdOverride":null,"debounceOverrideMs":null,"samplingProfileOverride":null}
      ]
    }
  ]
}
```

The encoder writes capabilities and sources in enum order for deterministic tests. The decoder rejects duplicate capabilities, duplicate sources, wrong source-to-capability membership, unknown enum names, non-finite overrides, sensitivity outside `1..10`, debounce outside `250..10_000`, confirmation outside `250..10_000`, and correlation outside `500..30_000` milliseconds. An unsupported future schema uses an in-memory Balanced fallback with diagnostic code `SENSOR_CONFIG_UNSUPPORTED_SCHEMA`; it does not overwrite the future payload.

Threshold overrides use the source's fixed unit and these initial safe storage bounds. `null` means “derive from sensitivity through `SensorConfigurationPolicy`.” Sources marked “none” reject a threshold override because their behavior is transition/trigger based.

| Source | Unit | Inclusive override bounds |
| --- | --- | --- |
| Significant Motion | trigger | none |
| Accelerometer | `m/s²` | `0.1..20.0` |
| Linear Acceleration | `m/s²` | `0.1..20.0` |
| Gyroscope | `rad/s` | `0.01..10.0` |
| Rotation Vector | degrees | `1.0..180.0` |
| Game Rotation Vector | degrees | `1.0..180.0` |
| Magnetic Field | `µT` | `1.0..200.0` |
| Geomagnetic Rotation Vector | degrees | `1.0..180.0` |
| Ambient Light | baseline ratio | `1.05..20.0` |
| Proximity | normalized distance/state delta | `0.1..0.9` |

---

### Task 1: Immutable Configuration, Presets, Derived Custom State, and Eligibility

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/SensorConfigurationModels.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/SensorConfigurationPolicy.kt`
- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/SensorConfigurationPolicyTest.kt`

**Interfaces:**
- Produces: `SensorRole`, `SensorCapability`, `SensorSource`, `SensorSourceFamily`, `SensorPreset`, `SensorPresetDisplay`, `SensorSamplingProfile`, `SensorSourceConfiguration`, `SensorCapabilityConfiguration`, `SensorFusionConfiguration`, `SensorConfigurationValidation`, and `SensorArmEligibility`.
- Consumed by: repository, coordinator, runtime, Telegram compatibility mapping, UI mapper, and Compose actions in later tasks.

- [ ] **Step 1: Write RED tests for the canonical source map and Balanced defaults**

```kotlin
@Test
fun balancedPresetMatchesApprovedSourceRoles() {
    val config = policy.forPreset(SensorPreset.BALANCED, nowMs = 100L)

    assertEquals(SensorRole.SUPPORTING, config.source(SensorSource.SIGNIFICANT_MOTION).role)
    assertEquals(SensorRole.PRIMARY, config.source(SensorSource.ACCELEROMETER).role)
    assertEquals(SensorRole.SUPPORTING, config.source(SensorSource.LINEAR_ACCELERATION).role)
    assertEquals(SensorRole.SUPPORTING, config.source(SensorSource.GYROSCOPE).role)
    assertEquals(SensorRole.SUPPORTING, config.source(SensorSource.ROTATION_VECTOR).role)
    assertEquals(SensorRole.SUPPORTING, config.source(SensorSource.GAME_ROTATION_VECTOR).role)
    assertEquals(SensorRole.SUPPORTING, config.source(SensorSource.MAGNETIC_FIELD).role)
    assertEquals(SensorRole.SUPPORTING, config.source(SensorSource.GEOMAGNETIC_ROTATION_VECTOR).role)
    assertEquals(SensorRole.PRIMARY, config.source(SensorSource.AMBIENT_LIGHT).role)
    assertEquals(SensorRole.SUPPORTING, config.source(SensorSource.PROXIMITY).role)
}
```

- [ ] **Step 2: Write RED tests for derived `CUSTOM`, Reset, and no-primary eligibility**

```kotlin
@Test
fun deviationDisplaysCustomButRetainsBasePresetForReset() {
    val balanced = policy.forPreset(SensorPreset.BALANCED, nowMs = 100L)
    val changed = policy.withSourceRole(balanced, SensorSource.GYROSCOPE, SensorRole.PRIMARY, 200L)

    assertEquals(SensorPreset.BALANCED, changed.basePreset)
    assertEquals(SensorPresetDisplay.CUSTOM, policy.displayPreset(changed))
    assertEquals(
        policy.forPreset(SensorPreset.BALANCED, 300L).capability(SensorCapability.ROTATION),
        policy.resetCapability(changed, SensorCapability.ROTATION, 300L).capability(SensorCapability.ROTATION),
    )
}

@Test
fun noPrimaryConfigurationIsSaveableWhileDisarmedButArmIneligible() {
    val noPrimary = SensorSource.entries.fold(policy.forPreset(SensorPreset.BALANCED, 100L)) {
        current, source -> policy.withSourceRole(current, source, SensorRole.OFF, 200L)
    }

    assertTrue(policy.validateForSave(noPrimary) is SensorConfigurationValidation.Valid)
    assertEquals(SensorArmEligibility.NoConfiguredPrimary, policy.armEligibility(noPrimary))
}
```

- [ ] **Step 3: Run the focused test and confirm RED**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.protection.SensorConfigurationPolicyTest"
```

Expected: compilation fails because the configuration types and policy do not exist.

- [ ] **Step 4: Implement the immutable models and pure policy**

Use these enum definitions and keep Android integer sensor constants out of the domain model:

```kotlin
enum class SensorRole { OFF, SUPPORTING, PRIMARY }
enum class SensorCapability { MOVEMENT, ROTATION, MAGNETIC, LIGHT, PROXIMITY }
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

enum class SensorSource(val capability: SensorCapability, val family: SensorSourceFamily) {
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
    val sources: Map<SensorSource, SensorSourceConfiguration>,
    val correlationWindowMs: Long = 3_000L,
    val confirmationDurationMs: Long = 1_500L,
)

data class SensorFusionConfiguration(
    val schemaVersion: Int = 1,
    val basePreset: SensorPreset,
    val capabilities: Map<SensorCapability, SensorCapabilityConfiguration>,
    val samplingProfile: SensorSamplingProfile,
    val updatedAtMs: Long,
)

sealed interface SensorConfigurationValidation {
    data object Valid : SensorConfigurationValidation
    data class Invalid(val code: String) : SensorConfigurationValidation
}

sealed interface SensorArmEligibility {
    data object Eligible : SensorArmEligibility
    data object NoConfiguredPrimary : SensorArmEligibility
}

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

data class SensorConfigurationIssue(
    val capability: SensorCapability?,
    val source: SensorSource?,
    val code: SensorConfigurationIssueCode,
)

interface SensorConfigurationPolicy {
    companion object { const val MIN_DEBOUNCE_MS: Long = 250L }

    fun forPreset(preset: SensorPreset, nowMs: Long): SensorFusionConfiguration
    fun validateForSave(configuration: SensorFusionConfiguration): SensorConfigurationValidation
    fun armEligibility(configuration: SensorFusionConfiguration): SensorArmEligibility
    fun displayPreset(configuration: SensorFusionConfiguration): SensorPresetDisplay
    fun parameters(source: SensorSource, sensitivity: Int): SensorDetectionParameters
    fun withSourceRole(
        configuration: SensorFusionConfiguration,
        source: SensorSource,
        role: SensorRole,
        nowMs: Long,
    ): SensorFusionConfiguration
    fun withCapabilityRole(
        configuration: SensorFusionConfiguration,
        capability: SensorCapability,
        role: SensorRole,
        nowMs: Long,
    ): SensorFusionConfiguration
    fun withSensitivity(
        configuration: SensorFusionConfiguration,
        capability: SensorCapability,
        sensitivity: Int,
        nowMs: Long,
    ): SensorFusionConfiguration
    fun resetCapability(
        configuration: SensorFusionConfiguration,
        capability: SensorCapability,
        nowMs: Long,
    ): SensorFusionConfiguration
    fun allSourcesOff(
        configuration: SensorFusionConfiguration,
        nowMs: Long,
    ): SensorFusionConfiguration
}

fun SensorFusionConfiguration.capability(
    capability: SensorCapability,
): SensorCapabilityConfiguration = requireNotNull(capabilities[capability])

fun SensorFusionConfiguration.source(
    source: SensorSource,
): SensorSourceConfiguration = requireNotNull(capability(source.capability).sources[source])
```

`SensorConfigurationPolicy.validateForSave()` validates structure and bounds but does not require a primary. `armEligibility()` performs the separate primary check. Define preset source roles exactly as follows:

| Preset | Primary | Supporting | Off |
| --- | --- | --- | --- |
| Battery Saver | Significant Motion, Ambient Light | Accelerometer, Geomagnetic Rotation Vector, Proximity | Linear Acceleration, Gyroscope, Rotation Vector, Game Rotation Vector, Magnetic Field |
| Balanced | Accelerometer, Ambient Light | Significant Motion, Linear Acceleration, Gyroscope, Rotation Vector, Game Rotation Vector, Magnetic Field, Geomagnetic Rotation Vector, Proximity | none |
| Maximum Protection | Accelerometer, Gyroscope, Magnetic Field, Ambient Light, Proximity | Significant Motion, Linear Acceleration, Rotation Vector, Game Rotation Vector, Geomagnetic Rotation Vector | none |

Group bulk roles are deterministic: `OFF` sets every source in the capability to `OFF`; `SUPPORTING` sets every source to `SUPPORTING`; `PRIMARY` sets the preferred source to `PRIMARY` and the remaining sources to `SUPPORTING`. Preferred sources are Accelerometer, Gyroscope, Magnetic Field, Ambient Light, and Proximity for their respective capability groups. Any per-source deviation makes that group and the whole displayed preset `CUSTOM` without changing `basePreset`.

Implement `DefaultSensorConfigurationPolicy` in `SensorConfigurationPolicy.kt` with the exact preset table, group bulk-role rules, source membership, and bounds in this plan; tests instantiate that concrete class through the `SensorConfigurationPolicy` interface.

- [ ] **Step 5: Run the focused test and confirm GREEN**

Run the Step 3 command. Expected: all `SensorConfigurationPolicyTest` tests pass.

- [ ] **Step 6: Commit Task 1 files only**

```powershell
git add app/src/main/java/com/example/motorcycleantitheftsensor/protection/SensorConfigurationModels.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/SensorConfigurationPolicy.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/SensorConfigurationPolicyTest.kt
git commit -m "feat: define configurable sensor policy"
```

### Task 2: Atomic Encrypted JSON Repository and Legacy Migration

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/data/SensorConfigurationJsonCodec.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/data/SensorConfigurationRepository.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/data/EncryptedPrefsManager.kt:19-36,175-183`
- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/data/SensorConfigurationJsonCodecTest.kt`
- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/data/SensorConfigurationRepositoryTest.kt`

**Interfaces:**
- Consumes: Task 1 configuration types and `SensorConfigurationPolicy`.
- Produces: `SensorConfigurationRepository.load()`, `save()`, `SensorConfigurationLoadResult`, `SensorConfigurationSaveResult`, and non-secret `SensorConfigurationLoadNotice`.

- [ ] **Step 1: Write RED codec tests for deterministic round-trip and rejection**

```kotlin
@Test
fun canonicalRoundTripPreservesDesiredConfigurationOnly() {
    val expected = policy.withSensitivity(
        policy.forPreset(SensorPreset.BALANCED, 100L),
        SensorCapability.MOVEMENT,
        8,
        200L,
    )

    val json = codec.encode(expected)
    val actual = codec.decode(json)

    assertEquals(expected, actual)
    assertFalse(json.contains("baseline", ignoreCase = true))
    assertFalse(json.contains("lastSample", ignoreCase = true))
    assertFalse(json.contains("calibrationGeneration", ignoreCase = true))
    assertEquals(json, codec.encode(actual))
}

@Test(expected = SensorConfigurationFormatException::class)
fun duplicateSourceIsRejected() {
    codec.decode(duplicateAccelerometerPayload)
}
```

- [ ] **Step 2: Write RED repository tests for migration and commit failure**

```kotlin
@Test
fun missingNewRecordMigratesLegacySensitivityIntoMovementAndLight() {
    val preferences = FakeSensorConfigurationPreferences(legacySensitivity = 8)
    val result = repository(preferences).load()

    assertEquals(8, result.configuration.capability(SensorCapability.MOVEMENT).sensitivity)
    assertEquals(8, result.configuration.capability(SensorCapability.LIGHT).sensitivity)
    assertEquals(5, result.configuration.capability(SensorCapability.ROTATION).sensitivity)
    assertEquals(5, result.configuration.capability(SensorCapability.MAGNETIC).sensitivity)
    assertEquals(5, result.configuration.capability(SensorCapability.PROXIMITY).sensitivity)
    assertEquals(1, preferences.commitCalls)
    assertEquals(8, preferences.legacySensitivity)
}

@Test
fun failedCommitLeavesPreviousPayloadAndReturnsFailure() {
    val previous = codec.encode(policy.forPreset(SensorPreset.BALANCED, 100L))
    val preferences = FakeSensorConfigurationPreferences(payload = previous, commitSucceeds = false)
    val changed = policy.withSensitivity(policy.forPreset(SensorPreset.BALANCED, 100L), SensorCapability.LIGHT, 9, 200L)

    val result = repository(preferences).save(changed)

    assertEquals(SensorConfigurationSaveResult.PersistenceFailed, result)
    assertEquals(previous, preferences.payload)
}
```

Also cover malformed JSON, invalid bounds, future schema retention, default-restored notice, and exact enum ordering.

- [ ] **Step 3: Run repository tests and confirm RED**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.data.SensorConfiguration*Test"
```

Expected: compilation fails because repository and codec classes do not exist.

- [ ] **Step 4: Implement the storage boundary and observable commit**

Add to `EncryptedPrefsManager`:

```kotlin
private const val KEY_SENSOR_FUSION_CONFIGURATION = "sensor_fusion_configuration_json"

fun getSensorFusionConfigurationJson(): String? =
    prefs.getString(KEY_SENSOR_FUSION_CONFIGURATION, null)

fun commitSensorFusionConfigurationJson(json: String): Boolean =
    prefs.edit().putString(KEY_SENSOR_FUSION_CONFIGURATION, json).commit()

fun getLegacySensorSensitivity(): Int = getSensitivity()
```

Do not remove `KEY_SENSITIVITY` in schema version 1. Stop calling `setSensitivity()` from new code.

Use an injectable boundary for host tests:

```kotlin
internal interface SensorConfigurationPreferences {
    fun readPayload(): String?
    fun readLegacySensitivity(): Int
    fun commitPayload(payload: String): Boolean
}

sealed interface SensorConfigurationSaveResult {
    data class Saved(val configuration: SensorFusionConfiguration) : SensorConfigurationSaveResult
    data object PersistenceFailed : SensorConfigurationSaveResult
    data class Invalid(val code: String) : SensorConfigurationSaveResult
}

data class SensorConfigurationLoadResult(
    val configuration: SensorFusionConfiguration,
    val notice: SensorConfigurationLoadNotice?,
)

enum class SensorConfigurationLoadNotice {
    DEFAULTS_RESTORED_INVALID_PAYLOAD,
    SENSOR_CONFIG_UNSUPPORTED_SCHEMA,
    DEFAULTS_NOT_PERSISTED,
}

class SensorConfigurationFormatException(
    val diagnosticCode: String,
) : IllegalArgumentException(diagnosticCode)

interface SensorConfigurationRepository {
    fun load(): SensorConfigurationLoadResult
    fun save(configuration: SensorFusionConfiguration): SensorConfigurationSaveResult
}
```

The repository sequence is exact:

1. Read one payload.
2. If absent, build Balanced, copy the clamped legacy sensitivity to Movement and Light, validate, encode, and commit.
3. If present and schema 1, decode and validate.
4. If malformed or invalid, build Balanced and commit it; return `DEFAULTS_RESTORED_INVALID_PAYLOAD` only after the fallback commit succeeds.
5. If schema is greater than 1, return an in-memory Balanced fallback with `SENSOR_CONFIG_UNSUPPORTED_SCHEMA` and retain the stored bytes.
6. Never include payload or parsing exception text in the notice or logs.

- [ ] **Step 5: Run codec/repository tests and confirm GREEN**

Run the Step 3 command. Expected: both test classes pass.

- [ ] **Step 6: Run legacy preference regression tests**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.data.LegacyAuthenticatorMigrationTest"
```

Expected: the legacy sensitivity key remains readable and unrelated encrypted values remain untouched.

- [ ] **Step 7: Commit Task 2 files only**

```powershell
git add app/src/main/java/com/example/motorcycleantitheftsensor/data/EncryptedPrefsManager.kt app/src/main/java/com/example/motorcycleantitheftsensor/data/SensorConfigurationJsonCodec.kt app/src/main/java/com/example/motorcycleantitheftsensor/data/SensorConfigurationRepository.kt app/src/test/java/com/example/motorcycleantitheftsensor/data/SensorConfigurationJsonCodecTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/data/SensorConfigurationRepositoryTest.kt
git commit -m "feat: persist versioned sensor configuration"
```

### Task 3: Authoritative Desired/Effective Projection and Atomic Apply Boundary

This is the same physical integration task as Runtime Task 9. Execute both descriptions together after Runtime Tasks 2-8 and Settings Task 2, and produce one reviewed commit; do not implement a second coordinator/runtime apply model.

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/SensorConfigurationApplyModels.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionModels.kt:178-220`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntime.kt:45-60`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt:19-42,47-176,254-264`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionHealthPolicy.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinatorTest.kt`
- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/SensorConfigurationApplyTest.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntimeTest.kt`
- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ConfigurableSensorRuntimeIntegrationTest.kt`
- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModelsTest.kt`

**Interfaces:**
- Consumes: `SensorConfigurationRepository` and runtime/controller diff support.
- Produces: authoritative `ProtectionSnapshot.sensorConfiguration`, `previewSensorConfigurationChange()`, `applySensorConfiguration()`, and `acknowledgeSensorConfigurationResult()`.

- [ ] **Step 1: Write RED tests for structurally valid no-primary persistence and arm rejection**

```kotlin
@Test
fun disarmedNoPrimaryConfigurationSavesButSubsequentArmIsRejected() = runTest {
    val noPrimary = policy.allSourcesOff(policy.forPreset(SensorPreset.BALANCED, 100L), 200L)

    val apply = coordinator.applySensorConfiguration("config-1", noPrimary, confirmedLastPrimaryRemoval = false)
    val arm = coordinator.arm("arm-1", CommandOrigin.LOCAL)

    assertEquals(SensorConfigurationApplyOutcome.APPLIED, apply.outcome)
    assertEquals(noPrimary, coordinator.snapshot.value.sensorConfiguration.desired)
    assertEquals(CommandOutcome.REJECTED, arm.outcome)
    assertEquals(ProtectionState.DISARMED_ONLINE, arm.resultingState)
}
```

- [ ] **Step 2: Write RED tests for two-phase atomicity and partial runtime rollback**

```kotlin
@Test
fun persistenceFailureLeavesDesiredAndRuntimeUntouched() = runTest {
    repository.nextSaveResult = SensorConfigurationSaveResult.PersistenceFailed
    val before = coordinator.snapshot.value.sensorConfiguration

    val result = coordinator.applySensorConfiguration("config-2", changed, false)

    assertEquals(SensorConfigurationApplyOutcome.REJECTED, result.outcome)
    assertEquals(before, coordinator.snapshot.value.sensorConfiguration)
    assertEquals(0, runtime.applyConfigurationCalls)
}

@Test
fun runtimeFailureRetainsDesiredAndPublishesRolledBackEffectiveTruth() = runTest {
    runtime.nextConfigurationResult = RuntimeSensorConfigurationResult.Failed(
        effective = previousEffective,
        failedSources = setOf(SensorSource.GYROSCOPE),
        issues = setOf(
            SensorConfigurationIssue(
                capability = SensorCapability.ROTATION,
                source = SensorSource.GYROSCOPE,
                code = SensorConfigurationIssueCode.REGISTRATION_FAILED,
            ),
        ),
        rollbackComplete = true,
    )

    val result = coordinator.applySensorConfiguration("config-3", changed, false)

    assertEquals(SensorConfigurationApplyOutcome.APPLIED_DEGRADED, result.outcome)
    assertEquals(changed, coordinator.snapshot.value.sensorConfiguration.desired)
    assertEquals(previousEffective, coordinator.snapshot.value.sensorConfiguration.effective)
    assertEquals(changed, repository.lastSaved)
}
```

Also test last-primary confirmation, affected capability set, stale operation generation, no obsolete listener preservation, and fallback to stopped failed groups when runtime rollback is incomplete.

- [ ] **Step 3: Run coordinator tests and confirm RED**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.protection.SensorConfigurationApplyTest" --tests "com.example.motorcycleantitheftsensor.protection.ProtectionCoordinatorTest"
```

Expected: compilation fails on the new projection and coordinator methods.

- [ ] **Step 4: Add immutable apply/effective models**

```kotlin
enum class SensorConfigurationApplyPhase {
    VALIDATING, PERSISTING, APPLYING_RUNTIME, CALIBRATING, APPLIED, DEGRADED, REJECTED
}

enum class SensorConfigurationApplyOutcome { APPLIED, APPLIED_DEGRADED, REJECTED }

data class SensorConfigurationProjection(
    val desired: SensorFusionConfiguration,
    val effective: Map<SensorSource, SensorSourceRuntimeStatus>,
    val operation: SensorConfigurationApplyOperation? = null,
    val loadNotice: SensorConfigurationLoadNotice? = null,
)

data class SensorConfigurationApplyOperation(
    val id: String,
    val affectedCapabilities: Set<SensorCapability>,
    val phase: SensorConfigurationApplyPhase,
    val outcome: SensorConfigurationApplyOutcome? = null,
    val failedSources: Set<SensorSource> = emptySet(),
    val acknowledged: Boolean = false,
)

data class SensorConfigurationApplyResult(
    val operationId: String,
    val outcome: SensorConfigurationApplyOutcome,
    val resultingState: ProtectionState,
    val affectedCapabilities: Set<SensorCapability>,
    val failedSources: Set<SensorSource>,
    val issues: Set<SensorConfigurationIssue>,
)

data class SensorConfigurationChangePreview(
    val affectedCapabilities: Set<SensorCapability>,
    val requiresLastPrimaryConfirmation: Boolean,
)

sealed interface RuntimeSensorConfigurationResult {
    data class Applied(
        val effective: Map<SensorSource, SensorSourceRuntimeStatus>,
    ) : RuntimeSensorConfigurationResult

    data class Degraded(
        val effective: Map<SensorSource, SensorSourceRuntimeStatus>,
        val failedSources: Set<SensorSource>,
        val issues: Set<SensorConfigurationIssue>,
    ) : RuntimeSensorConfigurationResult

    data class Failed(
        val effective: Map<SensorSource, SensorSourceRuntimeStatus>,
        val failedSources: Set<SensorSource>,
        val issues: Set<SensorConfigurationIssue>,
        val rollbackComplete: Boolean,
    ) : RuntimeSensorConfigurationResult
}
```

Add `val sensorConfiguration: SensorConfigurationProjection` to `ProtectionSnapshot`.

- [ ] **Step 5: Replace the runtime sensitivity method with configuration application**

Evolve `ProtectionRuntime` without leaving two active configuration paths:

```kotlin
interface ProtectionRuntime {
    val audioTelemetry: StateFlow<AudioTelemetry>
    val sensorHealth: StateFlow<Map<SensorKind, SensorHealth>>
    val sensorRuntimeStatus: StateFlow<SensorRuntimeStatus>

    fun desiredSensorConfiguration(): SensorFusionConfiguration
    fun completeSensorCalibration(nowElapsedMs: Long): SensorControllerApplyResult

    fun readiness(): ReadinessReport
    fun startDetectors(): DetectorStartResult
    fun startDetectors(armedSessionId: String): DetectorStartResult
    fun stopDetectors()

    suspend fun applySensorConfiguration(
        desired: SensorFusionConfiguration,
        affectedCapabilities: Set<SensorCapability>,
        operationId: String,
    ): RuntimeSensorConfigurationResult

    fun currentSensorHealth(): Map<SensorKind, SensorHealth>
    fun currentEffectiveSensorConfiguration(): Map<SensorSource, SensorSourceRuntimeStatus>
}
```

Remove `applySensitivity(level)` only after `AndroidProtectionRuntime`, coordinator, Telegram, and tests compile against the new interface.

- [ ] **Step 6: Implement coordinator ordering and safety**

Under a dedicated `sensorConfigurationMutex`:

1. Validate the full desired document.
2. Compute affected capabilities and whether an armed state may lose its last effective primary.
3. Reject with `CONFIRM_LAST_PRIMARY_REMOVAL` when confirmation is absent.
4. Publish `VALIDATING`, then `PERSISTING` with the operation ID.
5. Commit desired configuration.
6. If commit fails, publish persistent `REJECTED` and do not call runtime.
7. Publish `APPLYING_RUNTIME`; call runtime once with the diff.
8. Publish runtime effective truth, not an inferred copy of desired.
9. If the runtime reports no ready primary while currently armed, stop theft detectors and transition to `DISARMED_ONLINE` or `SETUP_REQUIRED` according to existing readiness blockers.
10. Retain a failed/degraded final operation in the snapshot until retry, resolution, or explicit acknowledgement.

- [ ] **Step 7: Run coordinator tests and confirm GREEN**

Run the Step 3 command. Expected: both test classes pass.

- [ ] **Step 8: Commit Task 3 files only**

```powershell
git add app/src/main/java/com/example/motorcycleantitheftsensor/protection/SensorConfigurationApplyModels.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionModels.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntime.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionHealthPolicy.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionModelsTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntimeTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinatorTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/SensorConfigurationApplyTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/ConfigurableSensorRuntimeIntegrationTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModelsTest.kt
git commit -m "feat: apply sensor configuration authoritatively"
```

### Task 4: Sensor Settings UI Models, Thai Text, and Persistent Apply Cards

This task starts after Runtime Task 10 and Message Task 1. Reuse `PresentationTextCatalog` for shared sensor capability/source/role labels; add only Settings-specific explanatory/control and derived effective-state copy in `SensorSettingsText.kt`.

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/SensorSettingsUiModels.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/SensorSettingsUiMapper.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/SensorSettingsText.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt:21-30,49-56,70-85,105-167,169-202`
- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/ui/SensorSettingsUiMapperTest.kt`
- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/ui/SensorSettingsTextTest.kt`

**Interfaces:**
- Consumes: authoritative `SensorConfigurationProjection` plus catalog metadata supplied in the protection snapshot.
- Produces: `SensorSettingsUiState`, `SettingsPage`, group/source row models, pending confirmation model, and ordered `PersistentStatusCardUiModel` values.

- [ ] **Step 1: Write RED tests that prohibit desired/effective conflation**

```kotlin
@Test
fun unavailableDesiredPrimaryIsNotPresentedAsMonitoring() {
    val projection = fixtureProjection(
        source = SensorSource.GYROSCOPE,
        desiredRole = SensorRole.PRIMARY,
        effectiveState = SensorEffectiveStateUi.UNAVAILABLE,
    )

    val row = mapper.map(projection).source(SensorSource.GYROSCOPE)

    assertEquals("เตือนได้เอง", row.desiredRoleTh)
    assertEquals("ไม่รองรับในเครื่องนี้", row.effectiveStateTh)
    assertFalse(row.monitoring)
}
```

- [ ] **Step 2: Write RED tests for persistent card priority and complete Thai labels**

```kotlin
@Test
fun failedApplyAndArmedDegradedRemainVisibleTogether() {
    val state = mapper.map(failedApplyDegradedProjection())

    assertEquals(
        listOf(PersistentStatusCardKind.ARMED_DEGRADED, PersistentStatusCardKind.SENSOR_CONFIGURATION_FAILED),
        state.persistentCards.map { it.kind },
    )
}

@Test
fun everyDomainEnumHasHumanReadableThaiText() {
    SensorCapability.entries.forEach { assertFalse(sensorCapabilityTitleTh(it).contains(it.name)) }
    SensorSource.entries.forEach { assertFalse(sensorSourceTitleTh(it).contains(it.name)) }
    SensorRole.entries.forEach { assertFalse(sensorRoleTitleTh(it).contains(it.name)) }
    SensorEffectiveStateUi.entries.forEach { assertFalse(sensorRuntimeStateTitleTh(it).contains(it.name)) }
}

@Test
fun restoredDefaultsRemainVisibleUntilAcknowledged() {
    val state = mapper.map(projectionWithLoadNotice(
        SensorConfigurationLoadNotice.DEFAULTS_RESTORED_INVALID_PAYLOAD,
    ))

    assertTrue(state.persistentCards.any {
        it.kind == PersistentStatusCardKind.SENSOR_CONFIGURATION_DEFAULTS_RESTORED
    })
}
```

- [ ] **Step 3: Run mapper/text tests and confirm RED**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.ui.SensorSettings*Test"
```

Expected: compilation fails because the UI models and mapper do not exist.

- [ ] **Step 4: Implement focused UI models**

```kotlin
enum class SettingsPage { OVERVIEW, ADVANCED_SENSORS }
enum class SensorEffectiveStateUi { OFF, UNAVAILABLE, REGISTERING, CALIBRATING, WATCHING, CONFIRMING, STALE, FAILED }

data class SensorSettingsUiState(
    val page: SettingsPage = SettingsPage.OVERVIEW,
    val displayedPreset: SensorPresetDisplay,
    val draft: SensorFusionConfiguration,
    val groups: List<SensorGroupSettingsUiModel>,
    val pendingChange: PendingSensorConfigurationChange? = null,
    val operation: SensorConfigurationOperationUiState = SensorConfigurationOperationUiState.Idle,
)

data class SensorGroupSettingsUiModel(
    val capability: SensorCapability,
    val titleTh: String,
    val desiredRoleTh: String,
    val desiredRoleSummaryTh: String,
    val sensitivity: Int,
    val availableSourceCount: Int,
    val totalSourceCount: Int,
    val effectiveStateTh: String,
    val lastSampleTh: String?,
    val calibrationProgressPercent: Int?,
    val degradationReasonTh: String?,
    val sources: List<SensorSourceSettingsUiModel>,
)

data class SensorSourceSettingsUiModel(
    val source: SensorSource,
    val titleTh: String,
    val hardwareName: String?,
    val vendor: String?,
    val classificationTh: String,
    val desiredRole: SensorRole,
    val desiredRoleTh: String,
    val effectiveState: SensorEffectiveStateUi,
    val effectiveStateTh: String,
    val monitoring: Boolean,
    val thresholdTh: String,
    val latestReadingTh: String?,
    val unavailableReasonTh: String?,
)

data class PendingSensorConfigurationChange(
    val proposed: SensorFusionConfiguration,
    val affectedCapabilities: Set<SensorCapability>,
    val explanationTh: String,
)

sealed interface SensorConfigurationOperationUiState {
    data object Idle : SensorConfigurationOperationUiState
    data class Active(
        val operationId: String,
        val phase: SensorConfigurationApplyPhase,
        val affectedCapabilities: Set<SensorCapability>,
        val statusTh: String,
    ) : SensorConfigurationOperationUiState
}

enum class PersistentStatusCardKind {
    ALERT_ACTIVE,
    SETUP_OR_NO_PRIMARY,
    OFFLINE,
    ARMED_DEGRADED,
    SENSOR_CONFIGURATION_FAILED,
    SENSOR_CONFIGURATION_DEFAULTS_RESTORED,
}

data class PersistentStatusCardUiModel(
    val id: String,
    val kind: PersistentStatusCardKind,
    val severity: GuidanceSeverity,
    val titleTh: String,
    val bodyTh: String,
    val action: GuidanceAction,
    val dismissible: Boolean,
)
```

Add `sensorSettings: SensorSettingsUiState` to `ProtectionUiState`. The test fixture extension `SensorSettingsUiState.source(source)` returns the unique source row from `groups.flatMap { it.sources }` and fails when a source is absent or duplicated.

Replace `ProtectionStatusUiState.persistentGuidance: GuidanceContent?` with `persistentCards: List<PersistentStatusCardUiModel>`. Order cards as active alert, setup/no-primary blocker, offline, armed degraded, configuration apply failure, then defaults-restored notice. Acknowledging an apply/load-notice card hides only that result; it never hides a still-current degraded or non-armed truth card.

- [ ] **Step 5: Implement mapper rules**

- Display preset comes only from `SensorConfigurationPolicy.displayPreset(desired)`.
- Group cards show desired aggregate role separately from effective state.
- A group with mixed source roles displays `CUSTOM` and its source-role summary.
- Latest sample text is absent when no fresh effective sample exists.
- Technical failure codes map through an allowlisted Thai table; raw codes and exception strings are never rendered.
- Apply phase labels are `กำลังตรวจสอบ`, `กำลังบันทึก`, `กำลังใช้การตั้งค่า`, and `กำลังปรับเทียบ`.
- `APPLIED` produces a one-shot success message. `DEGRADED` and `REJECTED` produce both immediate Snackbar content and a persistent card.

- [ ] **Step 6: Run mapper/text tests and confirm GREEN**

Run the Step 3 command. Expected: all mapper and text tests pass.

- [ ] **Step 7: Commit Task 4 files only**

```powershell
git add app/src/main/java/com/example/motorcycleantitheftsensor/ui/SensorSettingsUiModels.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/SensorSettingsUiMapper.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/SensorSettingsText.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt app/src/test/java/com/example/motorcycleantitheftsensor/ui/SensorSettingsUiMapperTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/ui/SensorSettingsTextTest.kt
git commit -m "feat: project sensor settings truth to ui"
```

### Task 5: ViewModel Drafts, Manual Settings Page, Confirmation, and Operation Isolation

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt:48-159,277-400,422-508`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/AndroidProtectionSettingsGateway.kt:33-56,109-146`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/ui/AndroidProtectionSettingsGatewayTest.kt:154-210`

**Interfaces:**
- Consumes: Task 3 coordinator commands and Task 4 mapper.
- Produces: `SensorSettingsActions`, ViewModel methods for preset/group/source edits, manual nested-page state, pending confirmation, and persistent-result acknowledgement.

- [ ] **Step 1: Write RED tests for page and draft behavior**

```kotlin
@Test
fun advancedEditsRemainDraftUntilApply() = runTest {
    val fixture = fixture()
    fixture.viewModel.openAdvancedSensorSettings()
    fixture.viewModel.editSensorSourceRole(SensorSource.GYROSCOPE, SensorRole.PRIMARY)
    advanceUntilIdle()

    assertEquals(SettingsPage.ADVANCED_SENSORS, fixture.viewModel.uiState.value.sensorSettings.page)
    assertEquals(SensorRole.PRIMARY, fixture.viewModel.uiState.value.sensorSettings.draft.source(SensorSource.GYROSCOPE).role)
    assertEquals(0, fixture.coordinator.applyConfigurationCalls)
}

@Test
fun leavingAdvancedSettingsDiscardsUnappliedDraft() = runTest {
    val fixture = fixture()
    fixture.viewModel.openAdvancedSensorSettings()
    fixture.viewModel.editSensorSourceRole(SensorSource.GYROSCOPE, SensorRole.PRIMARY)

    fixture.viewModel.closeAdvancedSensorSettings()

    assertEquals(fixture.coordinator.snapshot.value.sensorConfiguration.desired, fixture.viewModel.uiState.value.sensorSettings.draft)
}
```

- [ ] **Step 2: Write RED tests for confirmation and operation isolation**

```kotlin
@Test
fun dangerousChangeWaitsForConfirmationBeforeCoordinatorApply() = runTest {
    val fixture = armedSinglePrimaryFixture()

    fixture.viewModel.changeSensorGroupRole(SensorCapability.MOVEMENT, SensorRole.OFF)
    advanceUntilIdle()

    assertNotNull(fixture.viewModel.uiState.value.sensorSettings.pendingChange)
    assertEquals(0, fixture.coordinator.applyConfigurationCalls)
}

@Test
fun blockedSensorCalibrationDoesNotBlockBotTokenSave() = runTest {
    val fixture = fixture(blockSensorApply = true)
    fixture.viewModel.changeSensorGroupSensitivity(SensorCapability.MOVEMENT, 8)
    fixture.awaitSensorApplyStarted()

    fixture.viewModel.replaceBotToken("123456:VALID_TEST_TOKEN")
    fixture.awaitTokenSave()

    assertEquals(1, fixture.settings.replaceBotTokenCalls)
}
```

- [ ] **Step 3: Run ViewModel tests and confirm RED**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.ui.ProtectionViewModelTest"
```

Expected: new methods and sensor UI state do not compile.

- [ ] **Step 4: Add grouped actions and dedicated configuration serialization**

```kotlin
data class SensorSettingsActions(
    val selectPreset: (SensorPreset) -> Unit,
    val changeGroupRole: (SensorCapability, SensorRole) -> Unit,
    val changeGroupSensitivity: (SensorCapability, Int) -> Unit,
    val editSourceRole: (SensorSource, SensorRole) -> Unit,
    val editThreshold: (SensorSource, Double?) -> Unit,
    val editDebounce: (SensorSource, Long?) -> Unit,
    val editCorrelation: (SensorCapability, Long) -> Unit,
    val editConfirmation: (SensorCapability, Long) -> Unit,
    val editSampling: (SensorSource, SensorSamplingProfile?) -> Unit,
    val applyAdvancedGroup: (SensorCapability) -> Unit,
    val resetGroup: (SensorCapability) -> Unit,
    val openAdvanced: () -> Unit,
    val closeAdvanced: () -> Unit,
    val confirmPendingChange: () -> Unit,
    val cancelPendingChange: () -> Unit,
    val acknowledgeApplyFailure: (String) -> Unit,
)
```

Add `sensorConfigurationMutex` separately from `settingsMutex`. Serialize sensor writes, but do not set the old global `settingsOperationInFlight` during the 10-second runtime calibration. Project affected group IDs from the coordinator operation so only their controls show progress. Main group role/preset/sensitivity actions apply immediately after policy preview; advanced fields stay in a ViewModel draft until `applyAdvancedGroup()`.

- [ ] **Step 5: Implement confirmation and stale-result safety**

- Ask the coordinator for `previewSensorConfigurationChange(proposed)`.
- Store a non-secret pending change when confirmation is required.
- Revalidate through `applySensorConfiguration(commandId = nextCommandId(), desired = proposed, confirmedLastPrimaryRemoval = true)` after confirmation.
- Cancel clears only the pending draft and performs no write.
- Compare operation IDs/generations before publishing completion so a stale apply cannot replace a newer projection.
- Selecting another primary destination resets `SettingsPage` to `OVERVIEW` and discards an unapplied advanced draft.

- [ ] **Step 6: Remove the old UI global-sensitivity path**

Delete `ProtectionSettingsSummary.sensitivity`, `SettingsOperation.CHANGE_SENSITIVITY`, `ProtectionSettingsGateway.saveSensitivity()`, and `ProtectionViewModel.changeSensitivity()` only after all call sites use sensor actions. Leave the legacy preference getter solely for repository migration.

- [ ] **Step 7: Run ViewModel tests and confirm GREEN**

Run the Step 3 command. Expected: all existing and new ViewModel tests pass.

- [ ] **Step 8: Commit Task 5 files only**

```powershell
git add app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/AndroidProtectionSettingsGateway.kt app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/ui/AndroidProtectionSettingsGatewayTest.kt
git commit -m "feat: orchestrate sensor settings in viewmodel"
```

### Task 6: Main Sensor Settings Section and Persistent Feedback

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SensorProtectionSettingsSection.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SensorSettingsComponents.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/PersistentStatusCards.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt:69-150,245-256,490-600`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/ProtectionScreen.kt:39-80`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreen.kt:45-58,60-178`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/Navigation.kt:110-125`
- Modify: `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt`

**Interfaces:**
- Consumes: `SensorSettingsUiState` and `SensorSettingsActions`.
- Produces: the “เซ็นเซอร์ป้องกัน” overview section with preset controls, five capability cards, desired/effective truth, accessible sensitivity, operation progress, and Advanced navigation.

- [ ] **Step 1: Write RED Compose tests for main-group content and operation truth**

```kotlin
@Test
fun sensorSettingsShowsFiveCapabilityCardsAndBalancedRoles() {
    showSettings(balancedSensorSettingsState())

    compose.onNodeWithText("เซ็นเซอร์ป้องกัน").assertExists()
    compose.onAllNodes(hasTestTag("sensor_capability_card")).assertCountEquals(5)
    compose.onNodeWithText("การเคลื่อนไหว").assertExists()
    compose.onNodeWithText("การหมุน").assertExists()
    compose.onNodeWithText("สนามแม่เหล็ก").assertExists()
    compose.onNodeWithText("แสง").assertExists()
    compose.onNodeWithText("ระยะใกล้").assertExists()
}

@Test
fun desiredPrimaryUnavailableDoesNotRenderAsActive() {
    showSettings(unavailableDesiredPrimaryState())

    compose.onNodeWithText("ต้องการ: เตือนได้เอง").assertExists()
    compose.onNodeWithText("ใช้งานจริง: ไม่รองรับในเครื่องนี้").assertExists()
    compose.onAllNodesWithText("กำลังตรวจจับ").assertCountEquals(0)
}
```

- [ ] **Step 2: Write RED accessibility and persistent-card tests**

```kotlin
@Test
fun movementSensitivityHasThaiAdjustableSemanticsAndTouchTarget() {
    showSettings(balancedSensorSettingsState())

    val node = compose.onNode(
        hasTestTag("sensor_sensitivity_MOVEMENT") and
            hasContentDescription("ความไวการเคลื่อนไหว 5 จาก 10"),
    )
    node.assertExists().assert(
        SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress),
    )
    assertMinimumTouchHeight(node, 48f)
}

@Test
fun degradedApplyShowsSnackbarAndPersistentCard() {
    showSettings(degradedApplyState())

    compose.onNodeWithText("ใช้การตั้งค่าบางส่วน").assertExists()
    compose.onNodeWithTag("persistent_sensor_configuration_status").assertExists()
}

@Test
fun failedConfigurationCardAppearsOnSettingsAndProtection() {
    val state = failedConfigurationApplyState()
    showWithLocalNavigation(state)

    openSettings()
    compose.onNodeWithTag("persistent_sensor_configuration_status").assertExists()
    compose.onNodeWithText("Protection").performClick()
    compose.onNodeWithTag("persistent_sensor_configuration_status").assertExists()
}
```

- [ ] **Step 3: Run the focused instrumentation class and confirm RED**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.example.motorcycleantitheftsensor.ui.ProtectionAppScreenTest"
```

Expected: assertions fail because the sensor section is absent.

- [ ] **Step 4: Implement overview Compose components**

- Render preset choices `ประหยัดแบตเตอรี่`, `สมดุล`, and `ป้องกันสูงสุด`; show `กำหนดเอง` as the derived selected display state, not as a fourth mutation command.
- Render exactly five group cards in enum order.
- Each card shows desired aggregate role, sensitivity 1–10, available/total source count, effective state, last fresh sample time, calibration progress, and bounded degradation reason.
- Use `OFF` as the off control; do not add a second switch that can contradict the role.
- On a mixed role bundle, show `กำหนดเอง` plus the resulting source-role summary before Apply.
- Replace the old single global sensitivity card and custom overlay slider.
- Use native adjustable Slider semantics, `Modifier.heightIn(min = 48.dp)`, Thai content description, and `stateDescription`.
- Disable sensor mutation controls while a sensor configuration operation is serialized, but leave Telegram, SMS, permission, and diagnostic controls enabled.
- Render the shared persistent-card list above the editable sensor section and near the top of Protection; Snackbar remains immediate acknowledgement only.

- [ ] **Step 5: Run instrumentation and confirm GREEN**

Run the Step 3 command. Expected: all `ProtectionAppScreenTest` tests pass on the connected device.

- [ ] **Step 6: Commit Task 6 files only**

```powershell
git add app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SensorProtectionSettingsSection.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SensorSettingsComponents.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/PersistentStatusCards.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/ProtectionScreen.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreen.kt app/src/main/java/com/example/motorcycleantitheftsensor/Navigation.kt app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt
git commit -m "feat: add grouped sensor settings"
```

### Task 7: Advanced Per-Source Settings, Manual Page, and Back Handling

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/AdvancedSensorSettingsScreen.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SensorConfigurationConfirmDialog.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreen.kt:157-177`
- Modify: `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt`

**Interfaces:**
- Consumes: ViewModel-owned advanced draft and `SettingsPage`.
- Produces: per-source role/override editing, group Reset/Apply, dangerous-change confirmation, and Android back behavior.

- [ ] **Step 1: Write RED tests for advanced content and unavailable controls**

```kotlin
@Test
fun advancedSettingsShowsHardwareMetadataDesiredRoleAndEffectiveState() {
    showAdvancedSettings(gyroscopeAvailableState())

    compose.onNodeWithText("Gyroscope").assertExists()
    compose.onNodeWithText("st-lsm6ds3").assertExists()
    compose.onNodeWithText("STMicroelectronics").assertExists()
    compose.onNodeWithText("เซ็นเซอร์กายภาพ").assertExists()
    compose.onNodeWithText("ต้องการ: ใช้ประกอบ").assertExists()
    compose.onNodeWithText("ใช้งานจริง: กำลังเฝ้าระวัง").assertExists()
}

@Test
fun unavailableSourceIsVisibleDisabledAndExplained() {
    showAdvancedSettings(gyroscopeUnavailableState())

    compose.onNodeWithText("Gyroscope").assertExists()
    compose.onNodeWithTag("sensor_role_GYROSCOPE").assertIsNotEnabled()
    compose.onNodeWithText("โทรศัพท์เครื่องนี้ไม่มีเซ็นเซอร์ Gyroscope").assertExists()
}
```

- [ ] **Step 2: Write RED tests for bounds, Reset, confirmation, and BackHandler**

```kotlin
@Test
fun invalidDebounceCannotApplyAndShowsBoundedError() {
    showAdvancedSettings(rotationDraftState())
    compose.onNodeWithTag("debounce_GYROSCOPE").performTextReplacement("100")

    compose.onNodeWithText("ต้องอยู่ระหว่าง 250 ถึง 10000 มิลลิวินาที").assertExists()
    compose.onNodeWithTag("apply_sensor_group_ROTATION").assertIsNotEnabled()
}

@Test
fun systemBackReturnsToSettingsOverviewWithoutApplyingDraft() {
    val actions = recordingSensorActions()
    showAdvancedSettings(rotationDraftState(), actions)

    compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

    compose.runOnIdle {
        assertEquals(1, actions.closeAdvancedCalls)
        assertEquals(0, actions.applyCalls)
    }
}
```

- [ ] **Step 3: Run instrumentation and confirm RED**

Run the Task 6 Step 3 instrumentation command. Expected: advanced screen assertions fail.

- [ ] **Step 4: Implement manual page switching and BackHandler**

At the Settings branch in `ProtectionAppScreen`:

```kotlin
when (state.sensorSettings.page) {
    SettingsPage.OVERVIEW -> SettingsScreen(state, actions, contentPadding)
    SettingsPage.ADVANCED_SENSORS -> {
        BackHandler(onBack = actions.sensorSettings.closeAdvanced)
        AdvancedSensorSettingsScreen(state, actions.sensorSettings, contentPadding)
    }
}
```

Do not add a second NavHost. Selecting Protection or Events resets the manual Settings page through ViewModel state.

- [ ] **Step 5: Implement the advanced editor**

- List capability sections and sources in stable enum order with stable LazyColumn keys.
- Show hardware name/vendor, physical/virtual/wake-up/trigger classification, desired role, effective listener state, derived threshold plus unit, bounded override, debounce, confirmation, correlation, sampling profile, latest valid reading, and age.
- Use a radio/selectable group for `ปิด`, `ใช้ประกอบ`, and `เตือนได้เอง`.
- Keep unavailable sources visible. Disable their edit controls and keep the reason as separate readable text.
- Keep numeric text in the ViewModel draft. Parse finite values only and show exact bounds from `SensorConfigurationPolicy`; Compose does not duplicate constants.
- Reset calls `resetGroup(capability)` and clears only that group’s overrides back to `basePreset`.
- Apply calls `applyAdvancedGroup(capability)` only when validation is valid and the draft differs from desired.
- The dangerous-change dialog identifies the affected capability, explains that protection will stop if no replacement primary becomes ready, and exposes Cancel and Confirm buttons with 48 dp minimum targets.
- Restore focus to the initiating control after dialog dismissal. Do not place sample-age updates in a live region.

- [ ] **Step 6: Run instrumentation and confirm GREEN**

Run the Task 6 Step 3 command. Expected: all overview, advanced, accessibility, and existing screen tests pass.

- [ ] **Step 7: Commit Task 7 files only**

```powershell
git add app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/AdvancedSensorSettingsScreen.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SensorConfigurationConfirmDialog.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreen.kt app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt
git commit -m "feat: add advanced sensor settings"
```

### Task 8: Telegram `/sensitivity` Compatibility and Unified Status

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClient.kt:250-258`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramCommandHandler.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusProjection.kt:150-175`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusFormatter.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/UserGuidance.kt:230-253`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramCommandHandlerTest.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusProjectionTest.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusTruthPathIntegrationTest.kt`

**Interfaces:**
- Consumes: coordinator configuration command and authoritative desired/effective projection.
- Produces: backward-compatible `/sensitivity N` semantics without writing legacy preferences directly.

- [ ] **Step 1: Write RED compatibility tests**

```kotlin
@Test
fun sensitivityCommandUpdatesMovementAndLightAtomicallyThroughCoordinator() = runTest {
    val reply = handler.handle(RemoteCommand.Sensitivity(7), authorizedContext)

    assertEquals(7, coordinator.lastDesired.capability(SensorCapability.MOVEMENT).sensitivity)
    assertEquals(7, coordinator.lastDesired.capability(SensorCapability.LIGHT).sensitivity)
    assertEquals(5, coordinator.lastDesired.capability(SensorCapability.ROTATION).sensitivity)
    assertTrue(reply.contains("การเคลื่อนไหวและแสง"))
    assertEquals(0, preferences.legacySensitivityWrites)
}

@Test
fun statusShowsDesiredAndEffectiveWithoutGlobalSensitivityClaim() {
    val output = formatter.format(projectionWithUnavailableDesiredPrimary())

    assertTrue(output.contains("ต้องการ: เตือนได้เอง"))
    assertTrue(output.contains("ใช้งานจริง: ไม่รองรับ"))
    assertFalse(output.contains("ระดับความไว: 7/10"))
}
```

- [ ] **Step 2: Run Telegram tests and confirm RED**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.telegram.TelegramCommandHandlerTest" --tests "com.example.motorcycleantitheftsensor.telegram.ProtectionStatusProjectionTest" --tests "com.example.motorcycleantitheftsensor.telegram.ProtectionStatusTruthPathIntegrationTest"
```

Expected: the old code writes `sensor_sensitivity_level` and status still exposes one global value.

- [ ] **Step 3: Route compatibility command through coordinator**

For valid `N in 1..10`, copy the current desired configuration, set Movement and Light sensitivity to `N` with one `updatedAtMs`, and call `applySensorConfiguration()` once. Invalid values retain the existing rejection behavior. A persistence or runtime failure returns the coordinator’s human-readable result and does not claim success.

Remove the direct `prefsManager.setSensitivity(sensitivity)` call from `TelegramBotClient`. Keep parsing `/sensitivity` for compatibility and update `/help` text to state that it changes Movement and Light.

- [ ] **Step 4: Replace global status sensitivity with per-group desired/effective summaries**

Render group lines from the same `SensorConfigurationProjection` used by Settings. Include desired role, group sensitivity, effective primary availability, calibration state, and named degraded sources. Do not read encrypted preferences or rescan hardware in Telegram formatting.

- [ ] **Step 5: Run Telegram tests and confirm GREEN**

Run the Step 2 command. Expected: all three test classes pass.

- [ ] **Step 6: Commit Task 8 files only**

```powershell
git add app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClient.kt app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramCommandHandler.kt app/src/main/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusProjection.kt app/src/main/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusFormatter.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/UserGuidance.kt app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramCommandHandlerTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusProjectionTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusTruthPathIntegrationTest.kt
git commit -m "feat: map telegram sensitivity to sensor groups"
```

### Task 9: Process Recovery, Interrupted Apply, and End-to-End Persistence Truth

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt:27-80,335-390`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt:138-188,204-253,341-350`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionRecoveryPolicyTest.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionSnapshotStoreTest.kt`
- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/SensorConfigurationRecoveryIntegrationTest.kt`

**Interfaces:**
- Consumes: versioned repository and coordinator desired/effective configuration projection.
- Produces: deterministic process/reboot reload behavior without stale calibration or false active status.

- [ ] **Step 1: Write RED restart and interrupted-apply tests**

```kotlin
@Test
fun processRestartLoadsDesiredButDropsEffectiveAndCalibrationState() {
    val recovered = recoveryFixture(savedDesired = customConfiguration).restart()

    assertEquals(customConfiguration, recovered.snapshot.sensorConfiguration.desired)
    assertTrue(recovered.snapshot.sensorConfiguration.effective.values.none { it.registeredOrRequested })
    assertTrue(recovered.calibrationBaselines.isEmpty())
}

@Test
fun armedRecoveryReentersArmingWithLatestPersistedConfiguration() = runTest {
    val recovered = recoveryFixture(
        persistedProtectionState = ProtectionState.ARMED_DEGRADED,
        savedDesired = customConfiguration,
    ).recover()

    assertEquals(ProtectionState.ARMING, recovered.firstActiveSnapshot.state)
    assertEquals(customConfiguration, recovered.firstActiveSnapshot.sensorConfiguration.desired)
    assertEquals(1, recovered.runtime.fullCalibrationStarts)
}

@Test
fun interruptedAfterCommitUsesNewCompleteDocumentOnRestart() {
    val fixture = recoveryFixture(savedDesired = oldConfiguration)
    fixture.repository.save(newConfiguration)
    fixture.simulateProcessDeathBeforeRuntimeApply()

    assertEquals(newConfiguration, fixture.restart().snapshot.sensorConfiguration.desired)
}
```

- [ ] **Step 2: Run recovery tests and confirm RED**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.protection.SensorConfigurationRecoveryIntegrationTest" --tests "com.example.motorcycleantitheftsensor.protection.ProtectionRecoveryPolicyTest" --tests "com.example.motorcycleantitheftsensor.protection.ProtectionSnapshotStoreTest"
```

Expected: recovery does not yet load configuration or distinguish desired from effective state.

- [ ] **Step 3: Wire repository load into the runtime graph**

- Construct one `SensorConfigurationRepository` beside the existing encrypted preferences.
- Load and validate desired configuration before runtime configuration is exposed.
- Initialize effective source states as `OFF`, `UNAVAILABLE`, or `REGISTERING`; never copy desired roles into `registered = true`.
- Propagate a defaults-restored or unsupported-schema notice into the coordinator projection without raw payload or exception text.
- Remove both legacy `runtime.applySensitivity(configuredSensitivity)` calls and the initial global `sensitivityLevel` snapshot field.

- [ ] **Step 4: Integrate service recovery with fresh calibration**

Preserve the existing recovery gate. When persisted protection state was `ARMED_HEALTHY`, `ARMED_DEGRADED`, or `ALERT_ACTIVE`, call the normal coordinator arm path with the latest desired configuration. It must enter `ARMING`, allocate new session/group generations, register enabled sources, and run the full 10-second readiness phase. Failed primary readiness leaves the service online but non-armed with a persistent reason.

An interrupted configuration operation is resolved only from the atomic desired document and current runtime discovery:

- death before commit: old complete desired configuration loads;
- death after commit: new complete desired configuration loads;
- no stored operation phase is trusted as success;
- no previous effective listener or baseline is restored.

- [ ] **Step 5: Run recovery tests and confirm GREEN**

Run the Step 2 command. Expected: all recovery tests pass.

- [ ] **Step 6: Run focused Settings, coordinator, repository, and Telegram host gate**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.data.SensorConfiguration*Test" --tests "com.example.motorcycleantitheftsensor.protection.SensorConfiguration*Test" --tests "com.example.motorcycleantitheftsensor.protection.ProtectionCoordinatorTest" --tests "com.example.motorcycleantitheftsensor.ui.SensorSettings*Test" --tests "com.example.motorcycleantitheftsensor.ui.ProtectionViewModelTest" --tests "com.example.motorcycleantitheftsensor.telegram.TelegramCommandHandlerTest" --tests "com.example.motorcycleantitheftsensor.telegram.ProtectionStatus*Test"
```

Expected: zero failures and zero errors.

- [ ] **Step 7: Commit Task 9 files only**

```powershell
git add app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionRecoveryPolicyTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionSnapshotStoreTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/SensorConfigurationRecoveryIntegrationTest.kt
git commit -m "feat: recover persisted sensor configuration"
```

### Task 10: Full Verification and Controlled Device Acceptance

**Files:**
- Create evidence only: `docs/superpowers/status/2026-08-20-configurable-sensor-fusion-implementation-evidence.md`
- No production or test source changes are authorized in this verification task.

**Interfaces:**
- Consumes: completed Tasks 1–9 plus the runtime sensor adapters/controller delivered by the companion implementation plan.
- Produces: bounded host/build evidence and separate real-device acceptance evidence.

- [ ] **Step 1: Inspect scope before verification**

```powershell
git status --short
git diff --check
git diff --name-only
```

Expected: no whitespace errors; every changed file is attributable to an approved task or pre-existing dirty work.

- [ ] **Step 2: Run the full host gate serially**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest :app:assembleDebug
```

Expected: all unit tests pass and `app/build/outputs/apk/debug/app-debug.apk` is produced.

- [ ] **Step 3: Compile instrumentation tests**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process :app:compileDebugAndroidTestKotlin
```

Expected: instrumentation sources compile.

- [ ] **Step 4: Run the connected Settings suite on an explicitly identified device**

```powershell
adb devices -l
.\gradlew.bat --no-daemon --max-workers=1 :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.example.motorcycleantitheftsensor.ui.ProtectionAppScreenTest"
```

Expected: exactly one intended device is used and all Settings overview/advanced/accessibility tests pass.

- [ ] **Step 5: Perform controlled device persistence and accessibility checks**

On the target phone:

1. Select Balanced and verify five group cards show desired and effective values separately.
2. Change one group, confirm only that group shows apply/calibration progress, and verify other effective groups remain active.
3. Configure a valid no-primary document while disarmed and verify Save succeeds but Arm is rejected with a persistent reason.
4. While armed with one effective primary, attempt to turn it off, verify confirmation appears, confirm, and verify the system does not remain armed without a ready primary.
5. Force-stop/restart the process and reboot once; verify desired configuration returns, effective values do not claim active until registration, and armed recovery repeats calibration.
6. Run `/sensitivity 7`; verify Movement and Light become 7, other group sensitivities remain unchanged, and local Settings matches Telegram `/status`.
7. Exercise one persistence failure using an injected/debug test boundary; verify runtime stays unchanged and failure remains visible until acknowledged.
8. Enable maximum Android font size and verify no preset, role, threshold, Apply, Reset, or confirmation control is clipped.
9. With TalkBack, verify reading order is heading, desired role, effective state, availability, sensitivity, action; rapidly updating sample age must not be repeatedly announced.

- [ ] **Step 6: Run a secret/raw-diagnostic output scan**

```powershell
rg -n "accelerometer_magnitude|SensorRole\.|SensorEffectiveStateUi\.|thresholdOverride=|enc_telegram_bot_token|enc_sms_aes_key" app/src/main/java/com/example/motorcycleantitheftsensor/ui app/src/main/java/com/example/motorcycleantitheftsensor/telegram
```

Expected: no user-facing formatter or Compose text emits raw enums, raw diagnostic strings, or credential keys. Legitimate type references in pure mapping code are reviewed directly and must map to allowlisted text.

- [ ] **Step 7: Route every verification failure back to its owning task**

Do not edit or commit from Task 10. Identify the first failing assertion or compiler error, reopen the owning Task 1–9, add the regression test to that task's exact test file, make the smallest correction in that task's listed production files, rerun that task's focused command, and use that task's exact `git add` and commit message. After the owning task is GREEN, restart Task 10 from Step 1. If all gates pass, create no verification-only commit.

## Acceptance Checklist

- [ ] The persisted document is one encrypted, versioned JSON value and a failed commit is observable.
- [ ] Legacy sensitivity migrates once into Movement and Light; the legacy key remains readable for schema version 1 but receives no new writes.
- [ ] `basePreset` remains persisted and `CUSTOM` is derived deterministically.
- [ ] Structurally valid no-primary configuration saves while disarmed and remains arm-ineligible.
- [ ] Settings shows desired role separately from effective registration/readiness.
- [ ] Runtime apply starts only after persistence succeeds.
- [ ] Partial runtime mutation is rolled back to the last safe effective configuration when possible; desired remains persisted for retry after runtime failure.
- [ ] A last-primary removal while armed requires confirmation and cannot leave the coordinator falsely armed.
- [ ] Main Settings contains presets and exactly five group cards.
- [ ] Advanced Settings contains every supported source, bounded overrides, Reset, Apply, and truthful unavailable state.
- [ ] Manual `SettingsPage` and `BackHandler` return safely without applying drafts.
- [ ] Failed/degraded applies use immediate Snackbar feedback plus persistent status; acknowledgement cannot hide current degraded truth.
- [ ] `/sensitivity N` updates only Movement and Light through the coordinator and never writes the legacy value.
- [ ] Process/reboot recovery reloads desired configuration, discards effective/baseline state, and recalibrates before claiming healthy protection.
- [ ] Thai labels, 48 dp targets, large text, TalkBack role/state/action semantics, and non-chatty live-region behavior pass device checks.
- [ ] Focused tests, full unit tests, APK assembly, instrumentation compilation, and controlled device evidence are reported separately.

## Handoff Boundary

This plan owns configuration models needed by Settings, encrypted JSON persistence/migration, coordinator configuration transactions, UI projection, ViewModel actions, Settings overview/advanced UI, persistent configuration-apply feedback, Telegram sensitivity compatibility, and recovery of desired configuration. Sensor catalog discovery, Android listener adapters, calibration mathematics, adaptive sampling internals, observation fusion, and incident detection are implemented by the companion runtime plan and must satisfy the interfaces defined in Tasks 1 and 3 before Tasks 6–10 can be accepted on device.
