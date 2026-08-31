# Three Protection Profiles Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. This checkout is intentionally executed inline and sequentially because the development machine has limited RAM; do not dispatch parallel Gradle builds or test workers.

**Goal:** Add a truthful, persisted Vehicle/Entry/Power profile layer, immutable armed-profile snapshots, crash-safe profile switching, and visible profile selection without weakening the completed continuity/Direct Boot baseline.

**Architecture:** Add one credential-protected profile aggregate above the existing `SensorFusionConfiguration`; keep `ProtectionCoordinator` as the sole armed-state owner. Existing customers remain in an explicit legacy-unselected state until they choose a profile. A selected profile resolves to one effective configuration, while an active arm freezes that resolution into an `ArmedProfileSnapshot`; Settings edits affect only the next arm. The existing minimal Direct Boot bootstrap remains local-only and continues to hand off to the normal runtime after unlock.

**Tech Stack:** Kotlin, Android SharedPreferences, `org.json`, Kotlin coroutines/StateFlow, Jetpack Compose, JUnit 4, existing Android/Compose test stack. No new dependency.

**Spec:** `MotorcycleAntiTheftSensor/docs/superpowers/specs/2026-08-22-mobile-only-three-protection-profiles-design.md`

## Global Constraints

- Preserve the completed Direct Boot behavior from commit `dcbf397`: before unlock it stores only `armed` and `autoRecoveryAfterBoot`, observes accelerometer deviation locally, sends no Telegram/SMS, persists no incident, and builds no normal runtime graph.
- Keep Telegram, TOTP, SMS keys, chat IDs, allowlists, encrypted incident data, profile state, and armed-profile snapshots in credential-protected storage.
- `ProtectionCoordinator` remains the only authority for armed/disarmed state and profile switching.
- Existing customers retain the current single sensor configuration until they explicitly select a profile or choose recommended values.
- Vehicle, Entry, and Power each keep independent overrides. Restoring one profile must not mutate another.
- Once armed, profile/configuration/calibration/session identity are immutable until controlled disarm; late callbacks and Settings writes cannot change the running policy.
- Entry and Power must project `Setup required` in this foundation phase. They must not claim detector readiness before their dedicated detector plans pass host and device acceptance.
- Vehicle preserves the existing 10-second calibration, movement/Live Map behavior, Telegram authorization, GPS privacy, and incident quiet-window behavior.
- Do not add a dependency, rename a public API, revive a legacy dashboard, or touch unrelated security/Telegram/SMS behavior.
- Run exactly one focused Gradle invocation at a time. Use the repository's `-Xmx1536m`, in-process Kotlin compiler, and SerialGC settings; never run Android Studio builds and command-line Gradle concurrently.

## Staged Delivery Map

This plan is the first independently testable slice. It deliberately exposes truthful setup state rather than pretending Entry or Power detectors already exist.

1. **This plan — Profile foundation and visible selection:** domain model, atomic credential-protected persistence, legacy migration, immutable armed snapshot, crash-safe switch transaction, Vehicle compatibility, and UI cards.
2. **Entry Guard plan:** two-cycle hinge commissioning, quaternion/axis validation, door episodes, Entry-specific delivery, and device acceptance.
3. **Power Guard plan:** charging/witness commissioning, composite-state arbiter, durable episode/outbox, ambiguity policy, and device acceptance.
4. **Recovery/readiness and paper-light completion plan:** profile-specific recovery truth, delivery-path readiness, remaining paper-light surfaces, TalkBack/large-font/device matrix, and full regression gates.

---

### Task 1: Define the profile aggregate and resolver

**Files:**
- Create: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionProfileModels.kt`
- Create: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionProfilePolicy.kt`
- Test: `MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionProfilePolicyTest.kt`

**Interfaces:**
- Consumes: `SensorFusionConfiguration`, `SensorConfigurationPolicy`, `SensorRole`, and `SensorSource`.
- Produces: `ProtectionProfile`, `ProfileSetupState`, field-level `SensorFusionProfileOverrides`, typed profile-specific overrides, `StoredProfileConfiguration`, `ResolvedProfileConfiguration`, `ProtectionProfileStoreState`, and `ProtectionProfilePolicy.resolve(...)` for later tasks.

- [x] **Step 1: Write failing domain tests**

```kotlin
class ProtectionProfilePolicyTest {
    private val policy = ProtectionProfilePolicy(nowMs = { 1_000L })

    @Test fun newStoreHasNoImplicitCustomerSelection() {
        val state = policy.newStoreState()
        assertNull(state.selectedProfile)
        assertTrue(state.profiles.keys.containsAll(ProtectionProfile.entries))
    }

    @Test fun profileOverridesRemainIsolated() {
        val initial = policy.newStoreState()
        val entry = initial.profiles.getValue(ProtectionProfile.ENTRY)
        val changed = policy.updateProfile(
            initial,
            entry.copy(specificOverrides = EntryProfileOverrides(angleThresholdDegrees = 30)),
        )
        assertEquals(30, (policy.resolve(changed, ProtectionProfile.ENTRY).specificSettings as EntryProfileSettings).angleThresholdDegrees)
        assertEquals(
            initial.profiles.getValue(ProtectionProfile.POWER),
            changed.profiles.getValue(ProtectionProfile.POWER),
        )
    }

    @Test fun restoreRecommendedChangesOnlyRequestedProfile() {
        val customized = policy.updateProfile(
            policy.newStoreState(),
            policy.newStoreState().profiles.getValue(ProtectionProfile.ENTRY).copy(
                specificOverrides = EntryProfileOverrides(angleThresholdDegrees = 45),
            ),
        )
        val restored = policy.restoreRecommended(customized, ProtectionProfile.ENTRY)
        assertEquals(15, (policy.resolve(restored, ProtectionProfile.ENTRY).specificSettings as EntryProfileSettings).angleThresholdDegrees)
        assertEquals(customized.profiles.getValue(ProtectionProfile.POWER), restored.profiles.getValue(ProtectionProfile.POWER))
    }
}
```

- [x] **Step 2: Run the focused test and confirm RED**

Run from `MotorcycleAntiTheftSensor`:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*ProtectionProfilePolicyTest'
```

Expected: compilation fails because the profile types and policy do not exist.

- [x] **Step 3: Add the minimal typed profile model**

```kotlin
enum class ProtectionProfile { VEHICLE, ENTRY, POWER }

enum class ProfileSetupState { READY, SETUP_REQUIRED, UNAVAILABLE }

sealed interface ProfileSpecificSettings
data object VehicleProfileSettings : ProfileSpecificSettings
data class EntryProfileSettings(
    val angleThresholdDegrees: Int = 15,
    val openConfirmationMs: Long = 750L,
    val closeThresholdDegrees: Int = 3,
    val closeConfirmationMs: Long = 5_000L,
) : ProfileSpecificSettings
data class PowerProfileSettings(
    val lossConfirmationMs: Long = 10_000L,
    val recoveryConfirmationMs: Long = 10_000L,
) : ProfileSpecificSettings

data class SensorCapabilityProfileOverrides(
    val sensitivity: Int? = null,
    val correlationWindowMs: Long? = null,
    val confirmationDurationMs: Long? = null,
)

data class SensorSourceProfileOverrides(
    val role: SensorRole? = null,
    val thresholdOverride: Double? = null,
    val debounceOverrideMs: Long? = null,
    val samplingProfileOverride: SensorSamplingProfile? = null,
)

data class SensorFusionProfileOverrides(
    val samplingProfile: SensorSamplingProfile? = null,
    val capabilities: Map<SensorCapability, SensorCapabilityProfileOverrides> = emptyMap(),
    val sources: Map<SensorSource, SensorSourceProfileOverrides> = emptyMap(),
)

sealed interface ProfileSpecificOverrides
data object VehicleProfileOverrides : ProfileSpecificOverrides
data class EntryProfileOverrides(
    val angleThresholdDegrees: Int? = null,
    val openConfirmationMs: Long? = null,
) : ProfileSpecificOverrides
data class PowerProfileOverrides(
    val lossConfirmationMs: Long? = null,
    val recoveryConfirmationMs: Long? = null,
) : ProfileSpecificOverrides

data class StoredProfileConfiguration(
    val profile: ProtectionProfile,
    val presetVersion: Int,
    val sensorOverrides: SensorFusionProfileOverrides,
    val specificOverrides: ProfileSpecificOverrides,
    val setupState: ProfileSetupState,
)

data class ResolvedProfileConfiguration(
    val profile: ProtectionProfile,
    val presetVersion: Int,
    val sensorConfiguration: SensorFusionConfiguration,
    val specificSettings: ProfileSpecificSettings,
    val setupState: ProfileSetupState,
    val customized: Boolean,
)

data class ProtectionProfileStoreState(
    val schemaVersion: Int = 1,
    val selectedProfile: ProtectionProfile?,
    val profiles: Map<ProtectionProfile, StoredProfileConfiguration>,
    val legacyConfiguration: SensorFusionConfiguration?,
)
```

`ProfileSwitchTransaction` is introduced only in Task 5, together with its
repository recovery tests. It is intentionally absent from the Task 1 domain
slice.

Implement `ProtectionProfilePolicy` with these exact rules:

- `newStoreState(legacyConfiguration)` creates all three stored profiles but leaves `selectedProfile = null`.
- Vehicle recommended configuration is the current `BALANCED` policy so existing detector behavior has one known compatibility baseline.
- Entry recommended configuration makes rotation sources primary, movement/light supporting, and GPS remains outside sensor fusion and unavailable.
- Power recommended configuration makes ambient light primary and motion/orientation off by default; charging remains a typed runtime observation added by the Power plan.
- Entry and Power start as `SETUP_REQUIRED`; Vehicle starts `READY` only after the existing runtime readiness passes.
- Validate Entry angle `5..90`, open confirmation `250..3_000`, fixed close threshold `3`, and fixed close confirmation `5_000`.
- Validate Power loss `10_000` and recovery `10_000` for this release.
- Resolve only explicit field-level overrides on top of the current profile preset, then pass the resolved sensor configuration through `SensorConfigurationPolicy.validateForSave`.

- [x] **Step 4: Run the focused test and confirm GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*ProtectionProfilePolicyTest'
```

Expected: all `ProtectionProfilePolicyTest` tests pass.

- [x] **Step 5: Commit the domain slice**

```powershell
git add MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionProfileModels.kt MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionProfilePolicy.kt MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionProfilePolicyTest.kt
git commit -m "feat: add protection profile domain"
```

---

### Task 2: Persist the complete profile aggregate atomically

**Files:**
- Create: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionProfileCodec.kt`
- Create: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionProfileRepository.kt`
- Test: `MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionProfileCodecTest.kt`
- Test: `MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionProfileRepositoryTest.kt`

**Interfaces:**
- Consumes: Task 1 profile types plus the existing `SensorConfigurationCodec` and `SensorConfigurationRepository`.
- Produces: `ProtectionProfileRepository.load()`, `save(state)`, and `update(transform)`; all later tasks use this repository as the only profile-store writer.

- [x] **Step 1: Write failing codec and migration tests**

```kotlin
@Test fun roundTripPreservesPerProfileSettingsAndNullSelection() {
    val original = ProtectionProfilePolicy(nowMs = { 1_000L }).newStoreState()
    assertEquals(original, codec.decode(codec.encode(original)))
}

@Test fun firstLoadPreservesLegacyConfigurationWithoutSelectingProfile() {
    val legacy = SensorConfigurationPolicy().forPreset(SensorPreset.MAXIMUM_PROTECTION, 1_000L)
    whenever(sensorRepository.loadConfiguration()).thenReturn(legacy)
    val loaded = repository.load()
    assertNull(loaded.selectedProfile)
    assertEquals(legacy, loaded.legacyConfiguration)
    assertEquals(legacy, sensorRepository.loadConfiguration())
}

@Test fun failedCommitReturnsFailureAndKeepsPreviousAggregate() {
    preferences.failNextCommit = true
    val result = repository.save(changedState)
    assertTrue(result.isFailure)
    assertEquals(originalState, repository.load())
}
```

- [x] **Step 2: Run the two tests and confirm RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*ProtectionProfileCodecTest' --tests '*ProtectionProfileRepositoryTest'
```

Expected: compilation fails because codec/repository types do not exist.

- [x] **Step 3: Implement one versioned JSON aggregate and one commit boundary**

Use one credential-protected preference key:

```kotlin
interface ProtectionProfileRepository {
    fun load(): ProtectionProfileStoreState
    fun save(state: ProtectionProfileStoreState): Result<Unit>
    fun update(transform: (ProtectionProfileStoreState) -> ProtectionProfileStoreState): Result<ProtectionProfileStoreState>
}

class SharedPreferencesProtectionProfileRepository(
    private val preferences: SharedPreferences,
    private val legacyRepository: SensorConfigurationRepository,
    private val codec: ProtectionProfileCodec,
    private val policy: ProtectionProfilePolicy,
) : ProtectionProfileRepository {
    companion object {
        const val KEY_PROFILE_STATE = "protection_profile_state_json"
    }
}
```

`ProtectionProfileCodec` must encode fields in fixed profile-enum order and reuse `SensorConfigurationCodec` for nested sensor configurations. Reject unknown future schema versions, missing profile entries, invalid enum values, and invalid profile-specific ranges. `save` must call `SharedPreferences.Editor.commit()` once for the whole JSON aggregate. On first load with no aggregate, return `newStoreState(legacyRepository.loadConfiguration())`; do not write or select a profile until the owner acts.

- [x] **Step 4: Run the focused tests and confirm GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*ProtectionProfileCodecTest' --tests '*ProtectionProfileRepositoryTest'
```

Expected: codec round-trip, corrupt/future-schema rejection, atomic failure, and legacy-preservation tests pass.

- [x] **Step 5: Commit persistence**

```powershell
git add MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionProfileCodec.kt MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionProfileRepository.kt MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionProfileCodecTest.kt MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionProfileRepositoryTest.kt
git commit -m "feat: persist protection profiles atomically"
```

---

### Task 3: Add immutable armed-profile snapshots and fingerprints

**Files:**
- Create: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ArmedProfileSnapshotCodec.kt`
- Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionProfileModels.kt`
- Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionModels.kt`
- Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionSnapshotStore.kt`
- Test: `MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/protection/ArmedProfileSnapshotCodecTest.kt`
- Test: `MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionSnapshotStoreTest.kt`

**Interfaces:**
- Consumes: resolved Task 1 configuration and existing snapshot persistence.
- Produces: `ArmedProfileSnapshot`, `ArmedCalibrationSnapshot`, and `ConfigurationFingerprint.sha256(...)` for coordinator/recovery use.

- [x] **Step 1: Write failing immutability and recovery round-trip tests**

```kotlin
@Test fun snapshotRoundTripKeepsProfileSessionAndFrozenConfiguration() {
    val armed = ArmedProfileSnapshot(
        armedSessionId = "session-1",
        profile = ProtectionProfile.VEHICLE,
        resolvedPresetVersion = 1,
        effectiveConfiguration = vehicleConfig,
        configurationFingerprint = ConfigurationFingerprint.sha256(vehicleConfig, VehicleProfileSettings),
        commissionedModelFingerprint = null,
        armedCalibrationSnapshot = VehicleArmedCalibrationSnapshot(generation = 7L),
    )
    store.save(snapshot.copy(armedProfileSnapshot = armed), heartbeatAtMs = 2_000L)
    assertEquals(armed, store.loadForRecovery().liveSnapshot.armedProfileSnapshot)
}

@Test fun fingerprintIsStableForEqualCanonicalConfiguration() {
    assertEquals(
        ConfigurationFingerprint.sha256(vehicleConfig, VehicleProfileSettings),
        ConfigurationFingerprint.sha256(vehicleConfig.copy(), VehicleProfileSettings),
    )
}
```

- [x] **Step 2: Run focused tests and confirm RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*ArmedProfileSnapshotCodecTest' --tests '*ProtectionSnapshotStoreTest'
```

Expected: compilation fails because armed-profile snapshot types do not exist.

- [x] **Step 3: Implement immutable snapshot storage**

```kotlin
sealed interface ArmedCalibrationSnapshot { val generation: Long }
data class VehicleArmedCalibrationSnapshot(override val generation: Long) : ArmedCalibrationSnapshot
data class EntryArmedCalibrationSnapshot(override val generation: Long, val modelFingerprint: String) : ArmedCalibrationSnapshot
data class PowerArmedCalibrationSnapshot(override val generation: Long, val modelFingerprint: String) : ArmedCalibrationSnapshot

data class ArmedProfileSnapshot(
    val armedSessionId: String,
    val profile: ProtectionProfile,
    val resolvedPresetVersion: Int,
    val effectiveConfiguration: SensorFusionConfiguration,
    val configurationFingerprint: String,
    val commissionedModelFingerprint: String?,
    val armedCalibrationSnapshot: ArmedCalibrationSnapshot,
)
```

Add `armedProfileSnapshot: ArmedProfileSnapshot? = null` to `ProtectionSnapshot`. Encode the armed snapshot in the same atomic `ProtectionSnapshotStore.save(...)` commit as owner intent and normal snapshot fields. Generate fingerprints with JDK `MessageDigest.getInstance("SHA-256")` over canonical codec output; never use object `hashCode()` or wall-clock values.

- [x] **Step 4: Run focused tests and confirm GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*ArmedProfileSnapshotCodecTest' --tests '*ProtectionSnapshotStoreTest'
```

Expected: snapshot round-trip, corrupt/future-schema rejection, and stable-fingerprint tests pass.

- [x] **Step 5: Commit immutable snapshot support**

```powershell
git add MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ArmedProfileSnapshotCodec.kt MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionProfileModels.kt MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionModels.kt MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionSnapshotStore.kt MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/protection/ArmedProfileSnapshotCodecTest.kt MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionSnapshotStoreTest.kt
git commit -m "feat: persist immutable armed profile snapshots"
```

---

### Task 4: Freeze the running configuration at Arm

**Files:**
- Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt`
- Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntime.kt`
- Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt`
- Test: `MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinatorTest.kt`

**Interfaces:**
- Consumes: `ProtectionProfileRepository`, `ProtectionProfilePolicy.resolve(...)`, and Task 3 snapshots.
- Produces: `ProtectionCoordinator.selectProfile`, `updateSelectedProfile`, and an Arm path that calls `runtime.startDetectors(sessionId, frozenConfiguration)`.

- [x] **Step 1: Add failing coordinator tests**

```kotlin
@Test fun armFreezesSelectedProfileConfigurationBeforeDetectorStart() = runTest {
    profileRepository.save(selectedVehicleState)
    coordinator.arm("arm-1", CommandOrigin.LOCAL)
    val armed = coordinator.snapshot.value.armedProfileSnapshot!!
    profileRepository.save(vehicleEditedForNextArm)
    assertEquals(profilePolicy.resolve(selectedVehicleState, ProtectionProfile.VEHICLE).sensorConfiguration, armed.effectiveConfiguration)
    verify(runtime).startDetectors(armed.armedSessionId, armed.effectiveConfiguration)
}

@Test fun settingsEditWhileArmedDoesNotReconfigureRunningDetectors() = runTest {
    coordinator.arm("arm-1", CommandOrigin.LOCAL)
    coordinator.updateSelectedProfile("settings-1", editedVehicle)
    verify(runtime, never()).applySensorConfiguration(profilePolicy.resolve(vehicleEditedForNextArm, ProtectionProfile.VEHICLE).sensorConfiguration)
    assertEquals(originalArmedConfig, coordinator.snapshot.value.armedProfileSnapshot!!.effectiveConfiguration)
}

@Test fun legacyUnselectedCustomerKeepsExistingArmBehavior() = runTest {
    profileRepository.save(unselectedLegacyState)
    val result = coordinator.arm("arm-legacy", CommandOrigin.LOCAL)
    assertEquals(CommandOutcome.APPLIED, result.outcome)
    assertNull(coordinator.snapshot.value.armedProfileSnapshot)
}
```

- [x] **Step 2: Run the coordinator test and confirm RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*ProtectionCoordinatorTest'
```

Expected: new tests fail because Arm reads mutable runtime configuration and `startDetectors` has no frozen-config parameter.

- [x] **Step 3: Implement the coordinator-owned freeze boundary**

Change the runtime contract to:

```kotlin
fun startDetectors(
    armedSessionId: String,
    configuration: SensorFusionConfiguration,
): DetectorStartResult
```

Inside `arm`, under `commandMutex`, load selected profile state once, validate profile setup/readiness, generate the armed session ID, build `ArmedProfileSnapshot`, persist it with owner intent, then start detectors from `armedProfileSnapshot.effectiveConfiguration`. If persistence fails before detector start, reject Arm and remain disarmed. If detector startup fails, clear the armed snapshot durably and remain disarmed.

`updateSelectedProfile` persists only the selected profile. While disarmed, apply its sensor configuration to the editable runtime. While armed, do not call `runtime.applySensorConfiguration`; return applied text meaning “saved for next Arm.” Preserve the old mutable behavior only while `selectedProfile == null` so an existing customer is not silently migrated.

- [x] **Step 4: Run focused coordinator/runtime tests and confirm GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*ProtectionCoordinatorTest' --tests '*SensorCapabilityControllerTest'
```

Expected: immutable configuration, persistence-failure rollback, startup-failure rollback, and legacy compatibility tests pass.

- [x] **Step 5: Commit the Arm freeze boundary**

```powershell
git add MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntime.kt MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinatorTest.kt
git commit -m "feat: freeze selected profile when arming"
```

---

### Task 5: Add crash-safe profile switching

**Files:**
- Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionProfileModels.kt`
- Create: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProfileSwitchPolicy.kt`
- Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt`
- Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt`
- Test: `MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProfileSwitchPolicyTest.kt`
- Test: `MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinatorTest.kt`

**Interfaces:**
- Consumes: atomic Task 2 repository and Task 4 coordinator freeze boundary.
- Produces: `ProfileSwitchTransaction`, `ProfileSwitchPhase`, `ProtectionCoordinator.changeProfile(...)`, and `resumeProfileSwitchIfNeeded()`.

- [x] **Step 1: Write failing state-machine tests**

```kotlin
@Test fun armedSwitchPersistsStopIntentBeforeRuntimeEffects() = runTest {
    coordinator.changeProfile("switch-1", ProtectionProfile.ENTRY, confirmed = true)
    assertEquals(ProfileSwitchPhase.STOP_REQUESTED, repository.savedStates.first().switchTransaction?.phase)
    assertTrue(runtime.stopCallsOccurredAfterFirstSave)
}

@Test fun crashAtEveryPhaseConvergesToDisarmedSelectedTarget() = runTest {
    ProfileSwitchPhase.entries.forEach { phase ->
        val recovered = policy.resume(transactionAt(phase))
        assertEquals(ProtectionProfile.ENTRY, recovered.selectedProfile)
        assertNull(recovered.switchTransaction)
        assertNull(recovered.armedProfileSnapshot)
        assertEquals(ProtectionState.DISARMED_ONLINE, recovered.protectionState)
    }
}

@Test fun switchNeverAutoArmsTargetProfile() = runTest {
    coordinator.changeProfile("switch-1", ProtectionProfile.POWER, confirmed = true)
    verify(runtime, never()).startDetectors(any(), any())
}
```

- [x] **Step 2: Run focused switching tests and confirm RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*ProfileSwitchPolicyTest' --tests '*ProtectionCoordinatorTest'
```

Expected: compilation fails because switch transaction APIs do not exist.

- [x] **Step 3: Implement the durable phase machine**

```kotlin
enum class ProfileSwitchPhase {
    STOP_REQUESTED,
    OLD_RUNTIME_QUIESCED,
    SNAPSHOT_CLEARED,
    NEW_PROFILE_SELECTED,
}

data class ProfileSwitchTransaction(
    val transactionId: String,
    val oldArmedSessionId: String?,
    val targetProfile: ProtectionProfile,
    val phase: ProfileSwitchPhase,
)
```

Implement phases in this exact order, committing the aggregate after each transition. `STOP_REQUESTED` is persisted before invalidating recovery or stopping detectors. `OLD_RUNTIME_QUIESCED` fences the sensor/incident generation and finishes the existing best-effort Vehicle Live Map stop. `SNAPSHOT_CLEARED` clears the armed snapshot and records owner-stopped incident history without automatic-resolution copy. `NEW_PROFILE_SELECTED` selects the target, leaves it disarmed, then clears the transaction. A repeated transaction ID is idempotent. Cancellation before confirmation changes nothing; failure after `STOP_REQUESTED` resumes toward disarmed/selected-target, never toward rearming the old profile.

- [x] **Step 4: Run focused switching/recovery tests and confirm GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*ProfileSwitchPolicyTest' --tests '*ProtectionCoordinatorTest' --tests '*ProtectionRecoveryPolicyTest'
```

Expected: all phases converge, old callbacks are rejected, target never auto-arms, and owner Stop/Disarm still wins recovery races.

- [x] **Step 5: Commit profile switching**

```powershell
git add MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionProfileModels.kt MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProfileSwitchPolicy.kt MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProfileSwitchPolicyTest.kt MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinatorTest.kt
git commit -m "feat: switch protection profiles safely"
```

---

### Task 6: Wire the shared runtime graph and recovery store

**Files:**
- Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt`
- Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionSnapshotStore.kt`
- Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt`
- Test: `MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionSnapshotStoreTest.kt`
- Test: `MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/service/DirectBootBootstrapPolicyTest.kt`

**Interfaces:**
- Consumes: Task 2 repository, Task 3 armed snapshot, Task 5 resume policy.
- Produces: one process-shared profile repository and recovery path used by both UI and `SensorService`.

- [x] **Step 1: Write failing graph/recovery tests**

```kotlin
@Test fun recoveryUsesPersistedArmedProfileNotEditableSelectedProfile() {
    val recovery = store.loadForRecovery()
    assertEquals(ProtectionProfile.VEHICLE, recovery.liveSnapshot.armedProfileSnapshot?.profile)
    assertEquals(ProtectionProfile.ENTRY, profileRepository.load().selectedProfile)
}

@Test fun directBootMarkerContainsNoProfileOrSecretFields() {
    assertEquals(setOf("armed", "auto_recovery_after_boot"), DirectBootProtectionStore.persistedKeysForTest())
}
```

- [x] **Step 2: Run focused tests and confirm RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*ProtectionSnapshotStoreTest' --tests '*DirectBootBootstrapPolicyTest'
```

Expected: armed-profile recovery assertion fails until graph/store wiring is complete; marker-key guard initially lacks its test seam.

- [x] **Step 3: Wire profile state without expanding Direct Boot**

Construct one `SharedPreferencesProtectionProfileRepository` in `ProtectionRuntimeGraph.from(applicationContext)` and inject it into the single `ProtectionCoordinator`. On credential-unlocked service startup, call `resumeProfileSwitchIfNeeded()` before normal armed recovery. Recover detectors only from `liveSnapshot.armedProfileSnapshot`; editable `selectedProfile` is never proof of an armed runtime.

Keep `DirectBootProtectionStore` unchanged except for a package-visible `persistedKeysForTest()` returning exactly the two current marker keys. Do not instantiate profile repository, encrypted preferences, incident repository, Telegram, or full sensor graph in `DirectBootBootstrapService`.

- [x] **Step 4: Run focused recovery and Direct Boot tests and confirm GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*ProtectionSnapshotStoreTest' --tests '*ProtectionRecoveryPolicyTest' --tests '*DirectBootBootstrapPolicyTest'
```

Expected: recovery uses frozen profile state, unfinished switches converge to disarmed, and the Direct Boot boundary remains two non-secret booleans.

- [x] **Step 5: Commit graph/recovery wiring**

```powershell
git add MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionSnapshotStore.kt MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/DirectBootProtectionStore.kt MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionSnapshotStoreTest.kt MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/service/DirectBootBootstrapPolicyTest.kt
git commit -m "feat: recover frozen protection profiles"
```

---

### Task 7: Expose truthful profile state through ViewModel and Compose

**Files:**
- Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt`
- Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt`
- Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreen.kt`
- Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/ProtectionScreen.kt`
- Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt`
- Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/Navigation.kt`
- Test: `MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt`
- Test: `MotorcycleAntiTheftSensor/app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionProfilesUiTest.kt`

**Interfaces:**
- Consumes: coordinator profile APIs and repository-backed profile read model.
- Produces: visible profile picker/cards, persistent active-profile identity, confirmation flow, and profile-scoped Settings state.

- [x] **Step 1: Write failing ViewModel tests**

```kotlin
@Test fun unselectedCustomerSeesWhatAreYouProtectingPicker() = runTest {
    assertTrue(viewModel.uiState.value.profile.showPicker)
    assertNull(viewModel.uiState.value.profile.selectedProfile)
}

@Test fun selectingEntryShowsSetupRequiredAndDoesNotArm() = runTest {
    viewModel.selectProfile(ProtectionProfile.ENTRY)
    assertEquals(ProtectionProfile.ENTRY, viewModel.uiState.value.profile.selectedProfile)
    assertEquals(ProfileSetupState.SETUP_REQUIRED, viewModel.uiState.value.profile.setupState)
    viewModel.arm()
    assertEquals(ProtectionState.SETUP_REQUIRED, viewModel.uiState.value.protection.state)
}

@Test fun armedProfileChangeRequiresConfirmation() = runTest {
    viewModel.selectProfile(ProtectionProfile.POWER)
    assertEquals(ProtectionProfile.POWER, viewModel.uiState.value.profile.pendingSwitchTarget)
    verify(coordinator, never()).changeProfile(any(), any(), eq(true))
}
```

- [x] **Step 2: Run ViewModel tests and confirm RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*ProtectionViewModelTest'
```

Expected: compilation fails because profile UI state/actions do not exist.

- [x] **Step 3: Add profile read model and actions**

```kotlin
data class ProtectionProfileUiState(
    val selectedProfile: ProtectionProfile?,
    val armedProfile: ProtectionProfile?,
    val setupState: ProfileSetupState?,
    val customized: Boolean,
    val showPicker: Boolean,
    val pendingSwitchTarget: ProtectionProfile?,
)

data class ProtectionAppActions(
    // retain every existing action
    val selectProfile: (ProtectionProfile) -> Unit,
    val confirmProfileSwitch: () -> Unit,
    val cancelProfileSwitch: () -> Unit,
    val restoreRecommendedProfile: () -> Unit,
)
```

Project profile state only from coordinator/repository data. Do not infer readiness in Compose. `ProtectionScreen` shows a profile chip and Change use action. When no profile is selected, show the three “What are you protecting?” cards. `SettingsScreen` places current use/profile before detection settings and labels customized values. Entry and Power cards show `Setup required`; their Arm action remains blocked by domain state. Vehicle uses the existing protection summary and Arm path. Keep the existing three bottom destinations; profiles are not tabs.

- [x] **Step 4: Add Compose UI tests for semantics and confirmation**

```kotlin
@Test fun profilePickerHasThreeNamedCardsAndNoFalseReadyClaim() {
    composeRule.onNodeWithText("Vehicle Guard").assertExists()
    composeRule.onNodeWithText("Entry Guard").assertExists()
    composeRule.onNodeWithText("Power Guard").assertExists()
    composeRule.onAllNodesWithText("Setup required").assertCountEquals(2)
}

@Test fun armedChangeUseKeepsProtectionUntilExplicitConfirmation() {
    composeRule.onNodeWithText("Change use").performClick()
    composeRule.onNodeWithText("Keep current protection").assertExists()
    composeRule.onNodeWithText("Stop protection and change use").assertExists()
}
```

- [x] **Step 5: Run host UI-model tests, then compile instrumentation tests separately**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*ProtectionViewModelTest'
.\gradlew.bat --no-daemon --max-workers=1 compileDebugAndroidTestKotlin
```

Expected: ViewModel tests pass and Android-test sources compile. Do not run emulator and Huawei instrumentation simultaneously.

- [x] **Step 6: Commit visible profile selection**

```powershell
git add MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreen.kt MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/ProtectionScreen.kt MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/Navigation.kt MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt MotorcycleAntiTheftSensor/app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionProfilesUiTest.kt
git commit -m "feat: add protection profile picker"
```

---

### Task 8: Run sequential verification and checkpoint the foundation

**Files:**
- Create: `plans/checkpoint-2026-08-22-three-profiles-foundation.md`

**Interfaces:**
- Consumes: all prior tasks.
- Produces: fresh host/build/device evidence and an exact continuation boundary for the Entry Guard plan.

- [ ] **Step 1: Confirm worktree scope before verification**

```powershell
git status --short
git diff --check
```

Expected: only files listed by this plan are changed and `git diff --check` has no output.

- [ ] **Step 2: Run focused profile tests in one Gradle process**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*ProtectionProfile*' --tests '*ArmedProfileSnapshot*' --tests '*ProfileSwitchPolicyTest' --tests '*ProtectionCoordinatorTest' --tests '*ProtectionSnapshotStoreTest' --tests '*DirectBootBootstrapPolicyTest'
```

Expected: all focused tests pass.

- [ ] **Step 3: Stop and inspect memory pressure before the full host gate**

```powershell
Get-Process java,kotlin,adb -ErrorAction SilentlyContinue | Select-Object ProcessName,Id,@{Name='WorkingSetMB';Expression={[math]::Round($_.WorkingSet64/1MB)}}
```

Expected: no overlapping Gradle daemons or duplicate ADB servers. If another build is active, wait; do not start a second build.

- [ ] **Step 4: Run the full host suite and APK build sequentially**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest
.\gradlew.bat --no-daemon --max-workers=1 assembleDebug
```

Expected: both commands pass. Record them separately; a successful APK build is not device acceptance.

- [ ] **Step 5: Install and verify the exact APK on Huawei**

```powershell
$adb='C:\Users\ASUS\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb devices -l
& $adb install -r '.\app\build\outputs\apk\debug\app-debug.apk'
& $adb shell dumpsys package com.example.motorcycleantitheftsensor | Select-String 'lastUpdateTime|versionName|versionCode'
```

Expected: one Huawei INE-LX2 device, install success, and a fresh package update time. Verify manually without arming Entry/Power: three profile cards appear; legacy configuration remains unchanged until selection; Vehicle can use the existing Arm path; Entry/Power display Setup required; armed Change use requires confirmation and never auto-arms the target.

- [ ] **Step 6: Re-run the completed Direct Boot acceptance separately**

Use the exact controlled steps from `plans/checkpoint-2026-08-22-direct-boot-bootstrap.md`. Expected: the existing marker/service handoff behavior remains unchanged, no secret is added to device-protected storage, and no Telegram/SMS is sent before credential unlock. Do not claim true locked-window acceptance on a no-PIN device.

- [ ] **Step 7: Write the checkpoint**

Record:

- completed commits and files;
- focused/full/build commands with exit results;
- installed APK SHA-256 and package update time;
- Huawei UI evidence for the picker and safe blocking;
- Direct Boot regression evidence kept separate;
- pending Entry Guard, Power Guard, recovery/readiness, and paper-light plans;
- risks, including any unverified device state;
- exact resume commands using `--no-daemon --max-workers=1`.

- [ ] **Step 8: Commit the checkpoint**

```powershell
git add plans/checkpoint-2026-08-22-three-profiles-foundation.md
git commit -m "docs: checkpoint protection profile foundation"
```
