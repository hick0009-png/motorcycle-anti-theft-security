# Motorcycle Guard Protection System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build one truthful, recoverable motorcycle-protection runtime shared by the Android service, local UI, notifications, sensors, incident history, Telegram, and safe Demo Mode.

**Architecture:** Add a pure Kotlin `protection` domain centered on `ProtectionCoordinator` and immutable `ProtectionSnapshot`, then adapt the existing service, sensor, Telegram, preference, and Compose layers to that domain incrementally. Keep the existing Android application, foreground service, encrypted preferences, sensor implementations, Telegram transport, TOTP, TLS pinning, and SMS transport; isolate Android and network side effects behind narrow interfaces so state transitions, incident correlation, recovery, and delivery policy remain local-unit-testable.

**Tech Stack:** Kotlin 2.3.20, Android SDK 24-36, Java 17, Jetpack Compose Material 3, Android foreground service, `StateFlow`, Kotlin coroutines, JUnit 4, Android Compose tests, existing OkHttp 4.12.0 and `androidx.security:security-crypto`.

## Global Constraints

- Preserve the existing service, encrypted-preference, sensor, Telegram, TOTP, TLS-pinning, and SMS implementations behind adapters; do not add a backend server or a third-party dependency.
- `ProtectionCoordinator` is the only authority allowed to transition protection state. UI, notification, service, persistence, and Telegram render `ProtectionSnapshot`; they must not infer armed state independently.
- Supported states are exactly `SETUP_REQUIRED`, `DISARMED_ONLINE`, `ARMING`, `ARMED_HEALTHY`, `ARMED_DEGRADED`, `ALERT_ACTIVE`, and `OFFLINE`.
- Arming must validate named blockers, start detectors, suppress theft incidents during a 10,000 ms grace/baseline period, and only then report an armed result.
- The vibration detector is the minimum required detector. Missing optional sensors or delivery channels result in `ARMED_DEGRADED`, not a crash or false success.
- Disarm stops theft detectors while keeping the foreground remote-control service and Telegram polling active.
- Sensor health becomes `HEALTHY` only after a valid sample within its freshness window; hardware presence alone is `AVAILABLE`.
- Sensor output is measurement evidence. Relative microphone amplitude must never be labelled calibrated dB SPL, and thermal alerts must never claim a power cutoff.
- Persist each incident before external delivery. Record Telegram `SENT` only after a successful API response.
- SMS fallback is eligible only for a real critical incident after Telegram failure and only when destination and encryption key are configured. Emergency calls are outside scope.
- Demo events are marked `DEMO` from origin through history, UI, and Telegram; they may send Telegram but must never send SMS or start a call.
- Incident history retains the newest 200 records. Clearing history must not clear Telegram ownership or configuration.
- No blocking sensor, persistence, or network work may run on the main thread.
- Bot tokens are masked by default and must never appear in logs, screenshots, tests, checkpoints, or plan evidence. The previously exposed token must be revoked through BotFather before customer use.
- The worktree is already dirty. Before every commit, run `git status --short` and `git diff -- <exact paths>`; stage only the paths named by the current task. Never reset, clean, broadly stage, or overwrite unrelated changes.
- `EncryptedPrefsManager.kt` is security-critical under `.agents/AGENTS.md`. Task 10 may modify it only after explicit confirmation that the approved spec authorizes that exact reset operation and after the required Godkiller security review is available; otherwise stop Task 10 at that gate without weakening storage.
- Use strict RED-GREEN-REFACTOR: write one behavior test, run it and observe the expected failure, implement the minimum production behavior, rerun the focused test, then run the task suite.
- Project root for commands is `D:\security\MotorcycleAntiTheftSensor`. Set `$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'` before Gradle commands.
- Baseline evidence on 2026-08-08: `./gradlew.bat testDebugUnitTest assembleDebug` completed successfully with 42 actionable tasks up-to-date after Gradle network access was allowed.

## Delivery map

| Phase | Tasks | Independently reviewable result |
|---|---:|---|
| A. Truthful state | 1-3 | One authoritative state model, readiness/grace behavior, health freshness, and restart decisions |
| B. Evidence and incidents | 4-6 | Typed observations, correlated incidents, bounded persistence, truthful Telegram/SMS outcomes |
| C. Runtime integration | 7-8 | Foreground service, detector adapters, notification, boot recovery, and Telegram commands use the coordinator |
| D. Product experience | 9-10 | Safe Demo Mode and three-destination Compose UI with masked credentials |
| E. Release evidence | 11 | Automated verification, device matrix, documentation, and checkpoint handoff |

---

### Task 1: Define the immutable protection contract

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionModels.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionModelsTest.kt`

**Interfaces:**
- Consumes: no new project interfaces; Kotlin value types only.
- Produces: `ProtectionState`, `SensorKind`, `SensorHealthState`, `SensorHealth`, `IncidentSeverity`, `IncidentSource`, `IncidentLifecycle`, `DeliveryState`, `IncidentSummary`, `CommandOrigin`, `CommandOutcome`, `ProtectionCommandResult`, and `ProtectionSnapshot`.

- [ ] **Step 1: Write the failing contract test**

```kotlin
package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProtectionModelsTest {
    @Test
    fun offlineSnapshotDoesNotClaimLiveMonitoring() {
        val snapshot = ProtectionSnapshot.offline(nowMs = 1_000L)

        assertEquals(ProtectionState.OFFLINE, snapshot.state)
        assertFalse(snapshot.serviceRunning)
        assertFalse(snapshot.telegramPolling)
        assertEquals(1_000L, snapshot.lastTransitionAtMs)
        assertEquals(emptySet<String>(), snapshot.permissionBlockers)
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat testDebugUnitTest --tests '*ProtectionModelsTest'
```

Expected: compilation fails because `ProtectionSnapshot` and `ProtectionState` do not exist.

- [ ] **Step 3: Add the minimum complete domain model**

```kotlin
package com.example.motorcycleantitheftsensor.protection

enum class ProtectionState { SETUP_REQUIRED, DISARMED_ONLINE, ARMING, ARMED_HEALTHY, ARMED_DEGRADED, ALERT_ACTIVE, OFFLINE }
enum class SensorKind { VIBRATION, LIGHT, POWER_THERMAL, MICROPHONE, LOCATION }
enum class SensorHealthState { UNAVAILABLE, AVAILABLE, HEALTHY, STALE, FAILED }
enum class IncidentSeverity { WARNING, CRITICAL }
enum class IncidentSource { REAL, DEMO }
enum class IncidentLifecycle { OPEN, CLOSED, INTERRUPTED }
enum class DeliveryState { PENDING, SENT, FAILED, NOT_ELIGIBLE }
enum class CommandOrigin { LOCAL, TELEGRAM, RECOVERY }
enum class CommandOutcome { RECEIVED, APPLIED, REJECTED, UNKNOWN }

data class SensorHealth(
    val state: SensorHealthState,
    val lastSampleAtMs: Long? = null,
    val detail: String? = null,
)

data class IncidentSummary(
    val id: String,
    val source: IncidentSource,
    val severity: IncidentSeverity,
    val lifecycle: IncidentLifecycle,
    val updatedAtMs: Long,
    val deliveryState: DeliveryState,
)

data class ProtectionCommandResult(
    val commandId: String,
    val outcome: CommandOutcome,
    val resultingState: ProtectionState,
    val reason: String,
)

data class ProtectionSnapshot(
    val state: ProtectionState,
    val lastTransitionAtMs: Long,
    val serviceRunning: Boolean,
    val telegramPolling: Boolean,
    val telegramReachable: Boolean,
    val lastTelegramContactAtMs: Long?,
    val permissionBlockers: Set<String>,
    val sensorHealth: Map<SensorKind, SensorHealth>,
    val degradationReasons: Set<String>,
    val batteryLevelPercent: Int?,
    val batteryTemperatureCelsius: Float?,
    val lastIncident: IncidentSummary?,
    val lastDeliveryState: DeliveryState?,
    val demoModeEnabled: Boolean,
) {
    companion object {
        fun offline(nowMs: Long) = ProtectionSnapshot(
            state = ProtectionState.OFFLINE,
            lastTransitionAtMs = nowMs,
            serviceRunning = false,
            telegramPolling = false,
            telegramReachable = false,
            lastTelegramContactAtMs = null,
            permissionBlockers = emptySet(),
            sensorHealth = emptyMap(),
            degradationReasons = emptySet(),
            batteryLevelPercent = null,
            batteryTemperatureCelsius = null,
            lastIncident = null,
            lastDeliveryState = null,
            demoModeEnabled = false,
        )
    }
}
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the Step 2 command. Expected: `ProtectionModelsTest` passes.

- [ ] **Step 5: Commit only the domain contract**

```powershell
git add app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionModels.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionModelsTest.kt
git commit -m "feat: define authoritative protection contract"
```

### Task 2: Implement readiness, arming grace, cancellation, and sensitivity

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntime.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinatorTest.kt`

**Interfaces:**
- Consumes: Task 1 model types.
- Produces: `ProtectionRuntime`, `ReadinessReport`, `DetectorStartResult`, `ArmingDelay`, `ProtectionClock`, and `ProtectionCoordinator.snapshot: StateFlow<ProtectionSnapshot>` plus `suspend fun arm`, `suspend fun disarm`, and `fun changeSensitivity`.

- [ ] **Step 1: Write failing readiness and truthful-result tests**

```kotlin
package com.example.motorcycleantitheftsensor.protection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProtectionCoordinatorTest {
    @Test
    fun armRejectsNamedBlockerWithoutStartingDetectors() = runTest {
        val runtime = FakeRuntime(readiness = ReadinessReport(setOf("POST_NOTIFICATIONS"), emptySet()))
        val coordinator = coordinator(runtime, ArmingDelay { })

        val result = coordinator.arm("cmd-1", CommandOrigin.LOCAL)

        assertEquals(CommandOutcome.REJECTED, result.outcome)
        assertEquals(ProtectionState.SETUP_REQUIRED, result.resultingState)
        assertEquals("Missing required: POST_NOTIFICATIONS", result.reason)
        assertFalse(runtime.started)
    }

    @Test
    fun armReportsAppliedOnlyAfterGraceAndUsesDegradedReasons() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), setOf("MICROPHONE unavailable")),
            health = healthyVibration(),
        )
        val coordinator = coordinator(runtime, ArmingDelay { gate.await() })

        val pending = async { coordinator.arm("cmd-2", CommandOrigin.TELEGRAM) }
        assertEquals(ProtectionState.ARMING, coordinator.snapshot.value.state)
        gate.complete(Unit)

        val result = pending.await()
        assertEquals(CommandOutcome.APPLIED, result.outcome)
        assertEquals(ProtectionState.ARMED_DEGRADED, result.resultingState)
    }

    @Test
    fun disarmCancelsArmingAndKeepsRemoteControlOnline() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runtime = FakeRuntime(ReadinessReport(emptySet(), emptySet()), healthyVibration())
        val coordinator = coordinator(runtime, ArmingDelay { gate.await() })
        val arm = async { coordinator.arm("cmd-3", CommandOrigin.LOCAL) }

        val disarm = coordinator.disarm("cmd-4", CommandOrigin.LOCAL)
        gate.complete(Unit)
        val armResult = arm.await()

        assertEquals(CommandOutcome.UNKNOWN, armResult.outcome)
        assertEquals(ProtectionState.DISARMED_ONLINE, disarm.resultingState)
        assertEquals(ProtectionState.DISARMED_ONLINE, coordinator.snapshot.value.state)
        assertFalse(runtime.detectorsRunning)
    }
}
```

Add test-local `FakeRuntime`, `coordinator(...)`, and `healthyVibration()` in the same test file. `FakeRuntime.startDetectors()` records `started = true`, `stopDetectors()` records `detectorsRunning = false`, and `applySensitivity(level)` records the applied integer. Do not assert that the fake exists; assert coordinator output and externally visible fake side effects.

- [ ] **Step 2: Run the focused test and verify RED**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat testDebugUnitTest --tests '*ProtectionCoordinatorTest'
```

Expected: compilation fails because coordinator/runtime interfaces do not exist.

- [ ] **Step 3: Define the runtime boundary**

```kotlin
package com.example.motorcycleantitheftsensor.protection

data class ReadinessReport(
    val blockers: Set<String>,
    val degradations: Set<String>,
)

data class DetectorStartResult(
    val started: Boolean,
    val failureReason: String? = null,
)

fun interface ArmingDelay { suspend fun await() }
fun interface ProtectionClock { fun nowMs(): Long }

interface ProtectionRuntime {
    fun readiness(): ReadinessReport
    fun startDetectors(): DetectorStartResult
    fun stopDetectors()
    fun applySensitivity(level: Int)
    fun currentSensorHealth(): Map<SensorKind, SensorHealth>
}
```

- [ ] **Step 4: Implement the minimum coordinator transition logic**

Implement `ProtectionCoordinator` with a private `MutableStateFlow`, an incrementing `armingEpoch`, and these exact public signatures:

```kotlin
class ProtectionCoordinator(
    initialSnapshot: ProtectionSnapshot,
    private val runtime: ProtectionRuntime,
    private val armingDelay: ArmingDelay,
    private val clock: ProtectionClock,
) {
    val snapshot: StateFlow<ProtectionSnapshot>

    suspend fun arm(commandId: String, origin: CommandOrigin): ProtectionCommandResult
    suspend fun disarm(commandId: String, origin: CommandOrigin): ProtectionCommandResult
    fun changeSensitivity(commandId: String, level: Int): ProtectionCommandResult
}
```

Required behavior in `arm`:

```kotlin
val readiness = runtime.readiness()
if (readiness.blockers.isNotEmpty()) {
    runtime.stopDetectors()
    transition(ProtectionState.SETUP_REQUIRED, blockers = readiness.blockers)
    return result(commandId, CommandOutcome.REJECTED, "Missing required: ${readiness.blockers.sorted().joinToString()}")
}
val epoch = ++armingEpoch
transition(ProtectionState.ARMING, degradations = readiness.degradations)
val start = runtime.startDetectors()
if (!start.started) {
    runtime.stopDetectors()
    transition(ProtectionState.DISARMED_ONLINE, degradations = setOfNotNull(start.failureReason))
    return result(commandId, CommandOutcome.REJECTED, start.failureReason ?: "Detector startup failed")
}
armingDelay.await()
if (epoch != armingEpoch || snapshot.value.state != ProtectionState.ARMING) {
    return result(commandId, CommandOutcome.UNKNOWN, "Arming was cancelled")
}
val health = runtime.currentSensorHealth()
val finalState = if (readiness.degradations.isEmpty() && health[SensorKind.VIBRATION]?.state == SensorHealthState.HEALTHY) {
    ProtectionState.ARMED_HEALTHY
} else {
    ProtectionState.ARMED_DEGRADED
}
transition(finalState, sensorHealth = health, degradations = readiness.degradations)
return result(commandId, CommandOutcome.APPLIED, "Protection active")
```

`disarm` increments `armingEpoch`, stops detectors, transitions to `DISARMED_ONLINE` while leaving `serviceRunning` and `telegramPolling` unchanged, and returns `APPLIED`. `changeSensitivity` rejects values outside `1..10`; valid values call `runtime.applySensitivity(level)` immediately and return the current state.

- [ ] **Step 5: Run coordinator tests and verify GREEN**

Run the Step 2 command. Expected: all coordinator tests pass.

- [ ] **Step 6: Add the sensitivity behavior test and keep GREEN**

```kotlin
@Test
fun sensitivityIsValidatedAndAppliedToRunningRuntime() = runTest {
    val runtime = FakeRuntime(ReadinessReport(emptySet(), emptySet()), healthyVibration())
    val coordinator = coordinator(runtime, ArmingDelay { })

    assertEquals(CommandOutcome.REJECTED, coordinator.changeSensitivity("bad", 11).outcome)
    assertEquals(CommandOutcome.APPLIED, coordinator.changeSensitivity("ok", 7).outcome)
    assertEquals(7, runtime.appliedSensitivity)
}
```

Run the focused test. Expected: PASS.

- [ ] **Step 7: Commit the authoritative command path**

```powershell
git add app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntime.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinatorTest.kt
git commit -m "feat: coordinate truthful arm and disarm transitions"
```

### Task 3: Add health freshness, runtime liveness, and restart recovery

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionHealthPolicy.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRecoveryPolicy.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionHealthPolicyTest.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionRecoveryPolicyTest.kt`

**Interfaces:**
- Consumes: `ProtectionSnapshot`, `SensorHealth`, and states from Task 1.
- Produces: `ProtectionHealthPolicy.sensorState/runtimeState/telegramReachable`, `ProtectionRecoveryPolicy.plan(...)`, `RecoveryPlan`, and coordinator methods `recordServiceHeartbeat`, `recordTelegramContact`, and `recordSensorSample`.

- [ ] **Step 1: Write failing freshness and recovery tests**

```kotlin
class ProtectionHealthPolicyTest {
    @Test
    fun hardwareAvailabilityIsNotHealthyUntilFreshSampleArrives() {
        val policy = ProtectionHealthPolicy(sensorFreshnessMs = 5_000L, serviceFreshnessMs = 10_000L, telegramFreshnessMs = 20_000L)
        val available = SensorHealth(SensorHealthState.AVAILABLE)
        val sampled = SensorHealth(SensorHealthState.HEALTHY, lastSampleAtMs = 10_000L)

        assertEquals(SensorHealthState.AVAILABLE, policy.sensorState(available, nowMs = 10_001L))
        assertEquals(SensorHealthState.HEALTHY, policy.sensorState(sampled, nowMs = 14_999L))
        assertEquals(SensorHealthState.STALE, policy.sensorState(sampled, nowMs = 15_001L))
    }

    @Test
    fun staleServiceHeartbeatForcesOffline() {
        val policy = ProtectionHealthPolicy(5_000L, 10_000L, 20_000L)
        assertEquals(ProtectionState.OFFLINE, policy.runtimeState(ProtectionState.ARMED_HEALTHY, 1_000L, 11_001L))
    }
}

class ProtectionRecoveryPolicyTest {
    @Test
    fun persistedArmedStateReentersArmingInsteadOfTrustingStaleHealth() {
        val plan = ProtectionRecoveryPolicy.plan(ProtectionState.ARMED_HEALTHY)
        assertEquals(ProtectionState.ARMING, plan.initialState)
        assertTrue(plan.restartDetectors)
    }

    @Test
    fun previousActiveIncidentIsInterruptedDuringRecovery() {
        val plan = ProtectionRecoveryPolicy.plan(ProtectionState.ALERT_ACTIVE)
        assertEquals(IncidentLifecycle.INTERRUPTED, plan.previousIncidentLifecycle)
    }
}
```

- [ ] **Step 2: Run both tests and verify RED**

```powershell
.\gradlew.bat testDebugUnitTest --tests '*ProtectionHealthPolicyTest' --tests '*ProtectionRecoveryPolicyTest'
```

Expected: compilation fails because both policy classes are missing.

- [ ] **Step 3: Implement freshness and recovery policies**

```kotlin
class ProtectionHealthPolicy(
    private val sensorFreshnessMs: Long,
    private val serviceFreshnessMs: Long,
    private val telegramFreshnessMs: Long,
) {
    fun sensorState(health: SensorHealth, nowMs: Long): SensorHealthState = when {
        health.state != SensorHealthState.HEALTHY -> health.state
        health.lastSampleAtMs == null -> SensorHealthState.AVAILABLE
        nowMs - health.lastSampleAtMs > sensorFreshnessMs -> SensorHealthState.STALE
        else -> SensorHealthState.HEALTHY
    }

    fun runtimeState(current: ProtectionState, lastServiceHeartbeatAtMs: Long?, nowMs: Long): ProtectionState =
        if (lastServiceHeartbeatAtMs == null || nowMs - lastServiceHeartbeatAtMs > serviceFreshnessMs) ProtectionState.OFFLINE else current

    fun telegramReachable(lastContactAtMs: Long?, nowMs: Long): Boolean =
        lastContactAtMs != null && nowMs - lastContactAtMs <= telegramFreshnessMs
}

data class RecoveryPlan(
    val initialState: ProtectionState,
    val restartDetectors: Boolean,
    val previousIncidentLifecycle: IncidentLifecycle? = null,
)

object ProtectionRecoveryPolicy {
    fun plan(persistedState: ProtectionState): RecoveryPlan = when (persistedState) {
        ProtectionState.DISARMED_ONLINE, ProtectionState.SETUP_REQUIRED, ProtectionState.OFFLINE ->
            RecoveryPlan(ProtectionState.DISARMED_ONLINE, restartDetectors = false)
        ProtectionState.ALERT_ACTIVE ->
            RecoveryPlan(ProtectionState.ARMING, restartDetectors = true, previousIncidentLifecycle = IncidentLifecycle.INTERRUPTED)
        ProtectionState.ARMING, ProtectionState.ARMED_HEALTHY, ProtectionState.ARMED_DEGRADED ->
            RecoveryPlan(ProtectionState.ARMING, restartDetectors = true)
    }
}
```

- [ ] **Step 4: Extend coordinator liveness inputs**

Add these exact methods and update a copied snapshot instead of maintaining parallel booleans:

```kotlin
fun recordServiceHeartbeat(atMs: Long)
fun recordTelegramContact(atMs: Long)
fun recordSensorSample(kind: SensorKind, atMs: Long, detail: String? = null)
fun evaluateFreshness(nowMs: Long)
```

`evaluateFreshness` applies `ProtectionHealthPolicy`; an armed snapshot becomes `OFFLINE` when the service heartbeat is stale and becomes `ARMED_DEGRADED` when a required live channel or configured sensor is stale. It must never upgrade to healthy without fresh vibration evidence.

- [ ] **Step 5: Run Task 2-3 tests and verify GREEN**

```powershell
.\gradlew.bat testDebugUnitTest --tests '*ProtectionCoordinatorTest' --tests '*ProtectionHealthPolicyTest' --tests '*ProtectionRecoveryPolicyTest'
```

Expected: all selected tests pass.

- [ ] **Step 6: Commit health and recovery policy**

```powershell
git add app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionHealthPolicy.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRecoveryPolicy.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionHealthPolicyTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionRecoveryPolicyTest.kt
git commit -m "feat: evaluate protection health and restart safely"
```

### Task 4: Normalize sensor samples and establish arming baselines

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/SensorObservation.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/SensorObservationProcessor.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/SensorObservationProcessorTest.kt`

**Interfaces:**
- Consumes: `SensorKind` from Task 1.
- Produces: `SensorObservation`, `SensorBaseline`, `ObservationDecision`, and `SensorObservationProcessor.accept` used by incident correlation and Demo Mode.

- [ ] **Step 1: Write failing validation, baseline, and grace tests**

```kotlin
class SensorObservationProcessorTest {
    private val processor = SensorObservationProcessor(
        staleAfterMs = 5_000L,
        debounceSamples = mapOf(SensorKind.VIBRATION to 3, SensorKind.LIGHT to 2),
        thresholdDeltas = mapOf(SensorKind.VIBRATION to 2.0, SensorKind.LIGHT to 10.0),
    )

    @Test
    fun invalidAndStaleSamplesAreRejected() {
        assertEquals(ObservationDecision.Rejected("invalid sample"), processor.accept(observation(valid = false), nowElapsedMs = 10_000L, arming = false))
        assertEquals(ObservationDecision.Rejected("stale sample"), processor.accept(observation(eventElapsedMs = 1_000L), nowElapsedMs = 6_001L, arming = false))
    }

    @Test
    fun armingSamplesUpdateBaselineButDoNotEmitEvidence() {
        val decision = processor.accept(observation(normalizedValue = 9.8), nowElapsedMs = 1_000L, arming = true)
        assertEquals(ObservationDecision.BaselineUpdated, decision)
        assertEquals(9.8, processor.baseline(SensorKind.VIBRATION)?.average, 0.001)
    }

    @Test
    fun vibrationNeedsThreeConsecutiveThresholdExceedances() {
        processor.seedBaseline(SensorKind.VIBRATION, SensorBaseline(9.8, 3))
        assertEquals(ObservationDecision.Debounced, processor.accept(observation(normalizedValue = 12.0), 1_000L, false))
        assertEquals(ObservationDecision.Debounced, processor.accept(observation(normalizedValue = 12.1), 1_100L, false))
        assertTrue(processor.accept(observation(normalizedValue = 12.2), 1_200L, false) is ObservationDecision.Accepted)
    }
}
```

The test helper returns a complete literal `SensorObservation` with `kind = VIBRATION`, wall-clock `1_700_000_000_000L`, event elapsed time `1_000L`, normalized value `9.8`, baseline delta `0.0`, validity, and diagnostic text. Do not derive expected deltas using production helpers.

- [ ] **Step 2: Run the focused test and verify RED**

```powershell
.\gradlew.bat testDebugUnitTest --tests '*SensorObservationProcessorTest'
```

Expected: compilation fails because observation types do not exist.

- [ ] **Step 3: Add the typed observation contract**

```kotlin
data class SensorObservation(
    val kind: SensorKind,
    val eventElapsedMs: Long,
    val wallClockMs: Long,
    val normalizedValue: Double,
    val baselineDelta: Double,
    val valid: Boolean,
    val diagnostic: String? = null,
    val source: IncidentSource = IncidentSource.REAL,
)

data class SensorBaseline(val average: Double, val sampleCount: Int)

sealed interface ObservationDecision {
    data class Rejected(val reason: String) : ObservationDecision
    data object BaselineUpdated : ObservationDecision
    data object Debounced : ObservationDecision
    data class Accepted(val observation: SensorObservation) : ObservationDecision
}
```

- [ ] **Step 4: Implement validation, baseline averaging, and per-sensor debounce**

Use `eventElapsedMs` for freshness and correlation, `wallClockMs` only for display/persistence. During `arming = true`, update the incremental average:

```kotlin
val nextCount = current.sampleCount + 1
val nextAverage = current.average + (observation.normalizedValue - current.average) / nextCount
baselines[observation.kind] = SensorBaseline(nextAverage, nextCount)
return ObservationDecision.BaselineUpdated
```

Outside arming, recompute `baselineDelta = normalizedValue - baseline.average`, compare it with the sensor's literal `thresholdDeltas` entry, count consecutive above-threshold samples per sensor, reset the counter when a sample falls below threshold, and return `Accepted(observation.copy(baselineDelta = delta))` only when the configured debounce count is met. Add `fun setVibrationSensitivity(level: Int)` that validates `1..10` and maps levels to the existing threshold range (`1 -> 3.5`, `10 -> 0.5`) so Task 7 can apply sensitivity without restarting the service.

- [ ] **Step 5: Run the focused test and verify GREEN**

Run the Step 2 command. Expected: all processor tests pass.

- [ ] **Step 6: Commit observation normalization**

```powershell
git add app/src/main/java/com/example/motorcycleantitheftsensor/protection/SensorObservation.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/SensorObservationProcessor.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/SensorObservationProcessorTest.kt
git commit -m "feat: normalize and debounce sensor observations"
```

### Task 5: Correlate observations into one incident lifecycle

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/SecurityIncident.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentEngine.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/IncidentEngineTest.kt`

**Interfaces:**
- Consumes: accepted `SensorObservation` from Task 4 and protection state from Task 1.
- Produces: `IncidentEvidence`, `SecurityIncident`, `IncidentUpdate`, `IncidentIdGenerator`, and `IncidentEngine.accept/close/interrupted`.

- [ ] **Step 1: Write failing correlation and escalation tests**

```kotlin
class IncidentEngineTest {
    private val engine = IncidentEngine(
        idGenerator = IncidentIdGenerator { "incident-1" },
        correlationWindowMs = 15_000L,
    )

    @Test
    fun sustainedVibrationCreatesOneWarningAndRepeatedEvidenceUpdatesIt() {
        val first = engine.accept(accepted(SensorKind.VIBRATION, 1_000L, 2.2), ProtectionState.ARMED_HEALTHY) as IncidentUpdate.Opened
        val second = engine.accept(accepted(SensorKind.VIBRATION, 2_000L, 2.4), ProtectionState.ALERT_ACTIVE) as IncidentUpdate.Updated

        assertEquals("incident-1", second.incident.id)
        assertEquals(2, second.incident.evidence.size)
        assertEquals(IncidentSeverity.WARNING, second.incident.severity)
    }

    @Test
    fun vibrationAndLightWithinFifteenSecondsEscalateSameIncidentToCritical() {
        engine.accept(accepted(SensorKind.VIBRATION, 1_000L, 2.2), ProtectionState.ARMED_HEALTHY)
        val update = engine.accept(accepted(SensorKind.LIGHT, 15_999L, 120.0), ProtectionState.ALERT_ACTIVE) as IncidentUpdate.Escalated

        assertEquals(IncidentSeverity.CRITICAL, update.incident.severity)
        assertEquals(setOf(SensorKind.VIBRATION, SensorKind.LIGHT), update.incident.evidence.map { it.kind }.toSet())
    }

    @Test
    fun audioCannotOpenOrEscalateCriticalIncidentByItself() {
        assertEquals(IncidentUpdate.Ignored, engine.accept(accepted(SensorKind.MICROPHONE, 1_000L, 0.82), ProtectionState.ARMED_HEALTHY))
    }

    @Test
    fun powerDisconnectIsCriticalButThermalIsMeasuredWarning() {
        val power = engine.accept(accepted(SensorKind.POWER_THERMAL, 1_000L, 1.0, "charger_disconnected"), ProtectionState.ARMED_HEALTHY) as IncidentUpdate.Opened
        assertEquals(IncidentSeverity.CRITICAL, power.incident.severity)

        engine.close(2_000L, "test reset")
        val thermal = engine.accept(accepted(SensorKind.POWER_THERMAL, 3_000L, 46.5, "temperature_celsius"), ProtectionState.ARMED_HEALTHY) as IncidentUpdate.Opened
        assertEquals(IncidentSeverity.WARNING, thermal.incident.severity)
        assertEquals(46.5, thermal.incident.evidence.single().normalizedValue, 0.001)
    }

    @Test
    fun quietIncidentClosesOnceInsteadOfRepeatingAlerts() {
        engine.accept(accepted(SensorKind.VIBRATION, 1_000L, 2.2), ProtectionState.ARMED_HEALTHY)
        assertEquals(null, engine.closeIfQuiet(nowElapsedMs = 30_999L, quietWindowMs = 30_000L))
        val closed = engine.closeIfQuiet(nowElapsedMs = 31_000L, quietWindowMs = 30_000L) as IncidentUpdate.Closed
        assertEquals(IncidentLifecycle.CLOSED, closed.incident.lifecycle)
        assertEquals(null, engine.closeIfQuiet(nowElapsedMs = 61_000L, quietWindowMs = 30_000L))
    }
}
```

The `accepted(...)` helper constructs literal observations and must distinguish power diagnostics exactly as shown. Add a separate test proving light alone does not create a critical incident and a location observation with stale/poor diagnostics is stored only as unavailable supporting evidence.

- [ ] **Step 2: Run the focused test and verify RED**

```powershell
.\gradlew.bat testDebugUnitTest --tests '*IncidentEngineTest'
```

Expected: compilation fails because incident types are missing.

- [ ] **Step 3: Add the incident data contract**

```kotlin
data class IncidentEvidence(
    val kind: SensorKind,
    val eventElapsedMs: Long,
    val wallClockMs: Long,
    val normalizedValue: Double,
    val baselineDelta: Double,
    val diagnostic: String?,
)

data class SecurityIncident(
    val id: String,
    val source: IncidentSource,
    val severity: IncidentSeverity,
    val lifecycle: IncidentLifecycle,
    val evidence: List<IncidentEvidence>,
    val openedAtMs: Long,
    val updatedAtMs: Long,
    val closedAtMs: Long?,
    val protectionState: ProtectionState,
    val deliveryState: DeliveryState,
    val closeReason: String? = null,
)

fun interface IncidentIdGenerator { fun nextId(): String }

sealed interface IncidentUpdate {
    data object Ignored : IncidentUpdate
    data class Opened(val incident: SecurityIncident) : IncidentUpdate
    data class Updated(val incident: SecurityIncident) : IncidentUpdate
    data class Escalated(val incident: SecurityIncident) : IncidentUpdate
    data class Closed(val incident: SecurityIncident) : IncidentUpdate
}
```

- [ ] **Step 4: Implement the bounded correlation rules**

`IncidentEngine.accept` must reject observations unless protection state is `ARMED_HEALTHY`, `ARMED_DEGRADED`, or `ALERT_ACTIVE`. Implement these exact initial rules:

```kotlin
private fun initialSeverity(observation: SensorObservation): IncidentSeverity? = when {
    observation.kind == SensorKind.VIBRATION -> IncidentSeverity.WARNING
    observation.kind == SensorKind.POWER_THERMAL && observation.diagnostic == "charger_disconnected" -> IncidentSeverity.CRITICAL
    observation.kind == SensorKind.POWER_THERMAL && observation.diagnostic == "temperature_celsius" -> IncidentSeverity.WARNING
    else -> null
}

private fun correlatedSeverity(evidence: List<IncidentEvidence>): IncidentSeverity {
    val vibration = evidence.lastOrNull { it.kind == SensorKind.VIBRATION }
    val light = evidence.lastOrNull { it.kind == SensorKind.LIGHT }
    return if (vibration != null && light != null && kotlin.math.abs(vibration.eventElapsedMs - light.eventElapsedMs) <= correlationWindowMs) {
        IncidentSeverity.CRITICAL
    } else {
        IncidentSeverity.WARNING
    }
}
```

An accepted observation updates the current open incident rather than opening another. Return `Escalated` only when severity changes from warning to critical. `close(nowMs, reason)` returns a single closed incident and clears the active reference. `closeIfQuiet(nowElapsedMs, quietWindowMs)` closes once when the latest evidence has been quiet for the full window. `interrupted(nowMs)` closes with lifecycle `INTERRUPTED`.

- [ ] **Step 5: Run incident tests and verify GREEN**

Run the Step 2 command. Expected: all incident tests pass.

- [ ] **Step 6: Commit incident correlation**

```powershell
git add app/src/main/java/com/example/motorcycleantitheftsensor/protection/SecurityIncident.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentEngine.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/IncidentEngineTest.kt
git commit -m "feat: correlate sensor evidence into incidents"
```

### Task 6: Persist incidents and enforce truthful delivery policy

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentRepository.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/FileIncidentRepository.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentDeliveryCoordinator.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentUpdateDeliveryPolicy.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentMessageFormatter.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/telephony/SmsFallbackManager.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/FileIncidentRepositoryTest.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/IncidentDeliveryCoordinatorTest.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/IncidentUpdateDeliveryPolicyTest.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/IncidentMessageFormatterTest.kt`

**Interfaces:**
- Consumes: `SecurityIncident` from Task 5 and the existing Telegram/SMS transports through adapters.
- Produces: `IncidentRepository`, `FileIncidentRepository`, `IncidentTransport`, `DeliveryConfiguration`, `IncidentDeliveryCoordinator.deliver`, and `IncidentMessageFormatter.format`.

- [ ] **Step 1: Write the failing bounded-history test**

```kotlin
class FileIncidentRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun retainsNewestTwoHundredIncidentsAcrossRepositoryReopen() {
        val file = temporaryFolder.newFile("incidents.bin")
        val repository = FileIncidentRepository(file, maxRecords = 200)
        (1..205).forEach { index -> repository.upsert(incident(id = "i-$index", updatedAtMs = index.toLong())) }

        val reloaded = FileIncidentRepository(file, maxRecords = 200).listNewestFirst()

        assertEquals(200, reloaded.size)
        assertEquals("i-205", reloaded.first().id)
        assertEquals("i-6", reloaded.last().id)
    }

    @Test
    fun clearingHistoryDoesNotInvokeConfigurationStorage() {
        val repository = FileIncidentRepository(temporaryFolder.newFile("incidents.bin"), 200)
        repository.upsert(incident("i-1", 1L))
        repository.clearHistory()
        assertTrue(repository.listNewestFirst().isEmpty())
    }
}
```

Use `org.junit.rules.TemporaryFolder`; the repository owns only its incident file and receives no Telegram configuration object, making configuration deletion structurally impossible.

- [ ] **Step 2: Run repository test and verify RED**

```powershell
.\gradlew.bat testDebugUnitTest --tests '*FileIncidentRepositoryTest'
```

Expected: compilation fails because the repository does not exist.

- [ ] **Step 3: Implement atomic bounded file persistence**

Define:

```kotlin
interface IncidentRepository {
    fun upsert(incident: SecurityIncident)
    fun findById(id: String): SecurityIncident?
    fun listNewestFirst(): List<SecurityIncident>
    fun clearHistory()
}
```

`FileIncidentRepository` uses `DataInputStream`/`DataOutputStream`, writes every scalar explicitly, writes nullable values with a preceding boolean, writes evidence count followed by each evidence value, and writes to `<name>.tmp` before `Files.move(..., StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)`. If atomic move is unsupported, retry with `REPLACE_EXISTING`. Under one synchronized lock: replace by ID, sort by `updatedAtMs`, retain `takeLast(maxRecords)`, persist, and return newest-first copies.

- [ ] **Step 4: Write failing delivery-policy tests**

```kotlin
class IncidentDeliveryCoordinatorTest {
    @Test
    fun persistsPendingBeforeTelegramAndRecordsSentOnlyAfterSuccess() {
        val events = mutableListOf<String>()
        val repository = RecordingRepository(events)
        val coordinator = IncidentDeliveryCoordinator(
            repository = repository,
            formatter = IncidentMessageFormatter(),
            telegram = IncidentTransport { events += "telegram"; true },
            sms = IncidentTransport { events += "sms"; true },
        )

        val delivered = coordinator.deliver(criticalReal(), DeliveryConfiguration(smsConfigured = true))

        assertEquals(listOf("persist:PENDING", "telegram", "persist:SENT"), events)
        assertEquals(DeliveryState.SENT, delivered.deliveryState)
    }

    @Test
    fun demoNeverUsesSmsAfterTelegramFailure() {
        var smsCalls = 0
        val coordinator = coordinator(telegramSuccess = false, onSms = { smsCalls++; true })
        val delivered = coordinator.deliver(criticalDemo(), DeliveryConfiguration(smsConfigured = true))
        assertEquals(0, smsCalls)
        assertEquals(DeliveryState.FAILED, delivered.deliveryState)
    }

    @Test
    fun warningNeverUsesSmsAfterTelegramFailure() {
        var smsCalls = 0
        val delivered = coordinator(false) { smsCalls++; true }
            .deliver(warningReal(), DeliveryConfiguration(smsConfigured = true))
        assertEquals(0, smsCalls)
        assertEquals(DeliveryState.FAILED, delivered.deliveryState)
    }
}
```

- [ ] **Step 5: Run delivery test and verify RED**

```powershell
.\gradlew.bat testDebugUnitTest --tests '*IncidentDeliveryCoordinatorTest'
```

Expected: compilation fails because delivery coordinator types are missing.

- [ ] **Step 6: Implement delivery order and eligibility**

```kotlin
fun interface IncidentTransport { fun send(message: String): Boolean }
data class DeliveryConfiguration(val smsConfigured: Boolean)

class IncidentDeliveryCoordinator(
    private val repository: IncidentRepository,
    private val formatter: IncidentMessageFormatter,
    private val telegram: IncidentTransport,
    private val sms: IncidentTransport,
) {
    fun deliver(incident: SecurityIncident, configuration: DeliveryConfiguration): SecurityIncident {
        val pending = incident.copy(deliveryState = DeliveryState.PENDING)
        repository.upsert(pending)
        val message = formatter.format(pending)
        val telegramSent = telegram.send(message)
        val smsEligible = !telegramSent && pending.source == IncidentSource.REAL &&
            pending.severity == IncidentSeverity.CRITICAL && configuration.smsConfigured
        val delivered = pending.copy(
            deliveryState = if (telegramSent || (smsEligible && sms.send(message))) DeliveryState.SENT else DeliveryState.FAILED,
        )
        repository.upsert(delivered)
        return delivered
    }
}
```

Keep channel-specific attempt results in the persisted incident by adding `DeliveryAttempt(channel, state, attemptedAtMs, detail)` to `SecurityIncident`; `DeliveryState.SENT` means at least one eligible configured channel confirmed success, while the attempts list preserves which channel succeeded or failed.

Remove the unused `SmsFallbackManager.triggerEmergencyDirectCall` method and remove `android.permission.CALL_PHONE` from `AndroidManifest.xml`. No coordinator, transport, Demo path, or UI action may accept an emergency-call dependency; this makes the out-of-scope side effect unavailable rather than relying only on a branch check.

- [ ] **Step 7: Write and implement the formatter test**

```kotlin
@Test
fun demoMessageIsExplicitAndUnavailableLocationIsHonest() {
    val message = IncidentMessageFormatter().format(criticalDemo(locationDiagnostic = "no recent fix"))
    assertTrue(message.startsWith("DEMO — CRITICAL"))
    assertTrue(message.contains("Location: unavailable (no recent fix)"))
    assertTrue(message.contains("Incident: demo-1"))
}
```

The formatter output contains source marker, incident type/severity, local-time value supplied with the incident, concise evidence, protection state, location age/accuracy when valid or the explicit unavailable reason, and incident ID. It never contains “power cutoff”, “live GPS”, or a success claim not derived from delivery results.

- [ ] **Step 8: Write and implement incident-level suppression policy**

```kotlin
class IncidentUpdateDeliveryPolicyTest {
    @Test
    fun onlyOpenEscalationAndCloseProduceExternalMessages() {
        val policy = IncidentUpdateDeliveryPolicy()
        assertEquals(DeliveryAction.SEND, policy.action(IncidentUpdate.Opened(warningReal())))
        assertEquals(DeliveryAction.PERSIST_ONLY, policy.action(IncidentUpdate.Updated(warningReal())))
        assertEquals(DeliveryAction.SEND, policy.action(IncidentUpdate.Escalated(criticalReal())))
        assertEquals(DeliveryAction.SEND_CLOSE_SUMMARY, policy.action(IncidentUpdate.Closed(closedReal())))
        assertEquals(DeliveryAction.NONE, policy.action(IncidentUpdate.Ignored))
    }
}
```

Implement `enum class DeliveryAction { NONE, PERSIST_ONLY, SEND, SEND_CLOSE_SUMMARY }`. The runtime always persists `Updated`; it calls `IncidentDeliveryCoordinator.deliver` only for `SEND`, and sends one concise lifecycle summary for `SEND_CLOSE_SUMMARY`. This replaces per-type `AlertCooldown` and prevents repeated evidence from producing independent Telegram messages.

- [ ] **Step 9: Run all Task 6 tests and verify GREEN**

```powershell
.\gradlew.bat testDebugUnitTest --tests '*FileIncidentRepositoryTest' --tests '*IncidentDeliveryCoordinatorTest' --tests '*IncidentMessageFormatterTest' --tests '*IncidentUpdateDeliveryPolicyTest'
```

Expected: all selected tests pass.

- [ ] **Step 10: Commit persistence and delivery**

```powershell
git add app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentRepository.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/FileIncidentRepository.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentDeliveryCoordinator.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentMessageFormatter.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentUpdateDeliveryPolicy.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/SecurityIncident.kt app/src/main/java/com/example/motorcycleantitheftsensor/telephony/SmsFallbackManager.kt app/src/main/AndroidManifest.xml app/src/test/java/com/example/motorcycleantitheftsensor/protection/FileIncidentRepositoryTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/IncidentDeliveryCoordinatorTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/IncidentMessageFormatterTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/IncidentUpdateDeliveryPolicyTest.kt
git commit -m "feat: persist incidents and record delivery outcomes"
```

### Task 7: Adapt Android detectors and foreground service to the coordinator

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionSnapshotStore.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/LocationObservationProvider.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorServiceController.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/VibrationDetector.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/LightIntrusionDetector.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/PowerThermalMonitor.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/AudioPeakDetector.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/security/ProtectionPermissionPolicy.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/service/BootCompletedReceiver.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/service/AlarmWatchdogReceiver.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/service/SensorServiceControllerTest.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionSnapshotStoreTest.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/LocationEvidencePolicyTest.kt`
- Modify test: `app/src/test/java/com/example/motorcycleantitheftsensor/security/ProtectionPermissionPolicyTest.kt`

**Interfaces:**
- Consumes: Tasks 1-6 domain, processor, incident, repository, and delivery interfaces; existing Android detectors and transports.
- Produces: process-wide `ProtectionRuntimeGraph.coordinator`, `ProtectionRuntimeGraph.incidents`, `AndroidProtectionRuntime`, `ProtectionSnapshotStore`, and a thin foreground service driven by `SensorServiceController`.

- [ ] **Step 1: Write failing service-action tests against real coordinator behavior**

```kotlin
class SensorServiceControllerTest {
    @Test
    fun disarmStopsDetectorsButKeepsForegroundAndPolling() = runTest {
        val environment = RecordingServiceEnvironment()
        val fixture = realCoordinatorFixture()
        val controller = SensorServiceController(fixture.coordinator, environment)

        controller.handle(SensorServiceAction.Disarm, "service-1")

        assertFalse(fixture.runtime.detectorsRunning)
        assertTrue(environment.foregroundRunning)
        assertTrue(environment.telegramPolling)
        assertEquals(ProtectionState.DISARMED_ONLINE, controller.snapshot.value.state)
    }

    @Test
    fun explicitStopIsTheOnlyActionThatStopsRemoteControl() = runTest {
        val environment = RecordingServiceEnvironment()
        val controller = SensorServiceController(realCoordinator(), environment)
        controller.handle(SensorServiceAction.Stop, "service-2")
        assertFalse(environment.foregroundRunning)
        assertFalse(environment.telegramPolling)
    }
}
```

- [ ] **Step 2: Run service controller test and verify RED**

```powershell
.\gradlew.bat testDebugUnitTest --tests '*SensorServiceControllerTest'
```

Expected: compilation fails because the service controller is missing.

- [ ] **Step 3: Implement a testable service controller**

```kotlin
interface ServiceEnvironment {
    fun ensureForeground()
    fun stopForegroundAndSelf()
    fun ensureTelegramPolling()
    fun stopTelegramPolling()
    fun renderNotification(snapshot: ProtectionSnapshot)
}

class SensorServiceController(
    private val coordinator: ProtectionCoordinator,
    private val environment: ServiceEnvironment,
) {
    val snapshot: StateFlow<ProtectionSnapshot> = coordinator.snapshot
    suspend fun handle(action: SensorServiceAction, commandId: String) {
        when (action) {
            SensorServiceAction.Stop -> {
                coordinator.disarm(commandId, CommandOrigin.LOCAL)
                environment.stopTelegramPolling()
                environment.stopForegroundAndSelf()
            }
            SensorServiceAction.Disarm -> {
                environment.ensureForeground()
                environment.ensureTelegramPolling()
                coordinator.disarm(commandId, CommandOrigin.LOCAL)
            }
            SensorServiceAction.Arm -> {
                environment.ensureForeground()
                environment.ensureTelegramPolling()
                coordinator.arm(commandId, CommandOrigin.LOCAL)
            }
            SensorServiceAction.Start, SensorServiceAction.Ignore -> {
                environment.ensureForeground()
                environment.ensureTelegramPolling()
            }
        }
        environment.renderNotification(coordinator.snapshot.value)
    }
}
```

- [ ] **Step 4: Adapt detectors to emit observations instead of alerts**

Change detector callbacks to these exact shapes:

```kotlin
VibrationDetector(context, onObservation: (SensorObservation) -> Unit)
LightIntrusionDetector(context, onObservation: (SensorObservation) -> Unit)
PowerThermalMonitor(context, onObservation: (SensorObservation) -> Unit)
AudioPeakDetector(context, onObservation: (SensorObservation) -> Unit)
```

Each valid first sample emits health evidence even below an alert threshold. Vibration emits acceleration magnitude in `normalizedValue`; light emits lux; thermal emits Celsius with diagnostic `temperature_celsius`; charger disconnection emits `1.0` with diagnostic `charger_disconnected`; microphone emits a normalized relative amplitude ratio with diagnostic `relative_amplitude` and never formats it as calibrated physical dB.

- [ ] **Step 5: Implement the Android runtime adapter**

`AndroidProtectionRuntime` owns detector start/stop idempotence, applies sensitivity immediately through `VibrationDetector.setSensitivity(level)` and `SensorObservationProcessor.setVibrationSensitivity(level)`, checks minimum accelerometer presence plus required permissions, and forwards observations in this order:

Readiness classification is exact: missing accelerometer, missing foreground-service capability, or missing notification permission on API 33+ is a blocker; missing microphone/location permission or missing light/microphone/location hardware is a named degradation. Microphone permission must be removed from `ProtectionPermissionPolicy.requiredPermissions` as an arming blocker and represented as optional health instead.

```kotlin
coordinator.recordSensorSample(observation.kind, observation.wallClockMs, observation.diagnostic)
when (val decision = observationProcessor.accept(observation, elapsedClock.nowMs(), coordinator.snapshot.value.state == ProtectionState.ARMING)) {
    is ObservationDecision.Accepted -> incidentConsumer(decision.observation)
    ObservationDecision.BaselineUpdated, ObservationDecision.Debounced, is ObservationDecision.Rejected -> Unit
}
```

Do not call `AlertDispatcher` or Telegram from detectors.

Add `LocationEvidencePolicy(maxAgeMs = 30_000L, maxAccuracyMeters = 100f)` as a pure class with local tests for fresh/accurate, stale, and inaccurate fixes. `LocationObservationProvider` uses the platform `LocationManager` only when location permission is granted, emits `SensorObservation(kind = LOCATION)` with diagnostic text containing age/accuracy for a valid fix, and emits an unavailable diagnostic without coordinates otherwise. Location is optional and may degrade evidence quality; it must never block arming.

- [ ] **Step 6: Persist only restart-safe snapshot fields**

Write `ProtectionSnapshotStore` over application `SharedPreferences` named `protection_runtime_state`, storing state name, transition time, last service heartbeat, last Telegram contact, demo flag, and last incident ID. Its `loadForRecovery()` returns `ProtectionSnapshot.offline(now)` plus persisted recovery hints; it must not restore sensor health as fresh or restore `ALERT_ACTIVE` unchanged.

Add a local test proving persisted `ARMED_HEALTHY` is returned as a recovery hint and that the reconstructed live snapshot is `OFFLINE` until the coordinator runs `ProtectionRecoveryPolicy`.

- [ ] **Step 7: Wire one process-wide runtime graph**

```kotlin
object ProtectionRuntimeGraph {
    @Volatile private var instance: Graph? = null
    fun from(context: Context): Graph = instance ?: synchronized(this) {
        instance ?: buildGraph(context.applicationContext).also { instance = it }
    }

    data class Graph(
        val coordinator: ProtectionCoordinator,
        val incidents: IncidentRepository,
        val delivery: IncidentDeliveryCoordinator,
    )
}
```

`buildGraph` uses `Dispatchers.Default` for observation/correlation work and `Dispatchers.IO` for file persistence and delivery. No Android component creates a second coordinator.

- [ ] **Step 8: Make `SensorService` a lifecycle adapter**

Replace private arm booleans, direct detector starts, type cooldown, raw alert threads, and alarm broadcasts with the graph/controller. Use `CoroutineScope(SupervisorJob() + Dispatchers.Default)` for command work and collect snapshots to update the notification. Notification text maps states honestly, including `ARMING` countdown, degraded reasons, `ALERT_ACTIVE`, and persistent `DEMO` marker.

`onDestroy` cancels the scope, stops detectors and Telegram, releases the wake lock, and never writes a healthy state after shutdown.

- [ ] **Step 9: Apply recovery policy from boot/watchdog**

`BootCompletedReceiver` and `AlarmWatchdogReceiver` start only `ACTION_START_SERVICE`. `SensorService.onCreate` loads the recovery hint: disarmed starts remote-control-only; previously armed starts an `ARMING` command with `CommandOrigin.RECOVERY`; prior active incident is persisted as interrupted before arming.

- [ ] **Step 10: Run Tasks 1-7 unit tests and build**

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

Expected: all unit tests pass and `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 11: Commit runtime integration using exact paths**

```powershell
git add app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionSnapshotStore.kt app/src/main/java/com/example/motorcycleantitheftsensor/sensor/LocationObservationProvider.kt app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorServiceController.kt app/src/main/java/com/example/motorcycleantitheftsensor/sensor/VibrationDetector.kt app/src/main/java/com/example/motorcycleantitheftsensor/sensor/LightIntrusionDetector.kt app/src/main/java/com/example/motorcycleantitheftsensor/sensor/PowerThermalMonitor.kt app/src/main/java/com/example/motorcycleantitheftsensor/sensor/AudioPeakDetector.kt app/src/main/java/com/example/motorcycleantitheftsensor/security/ProtectionPermissionPolicy.kt app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt app/src/main/java/com/example/motorcycleantitheftsensor/service/BootCompletedReceiver.kt app/src/main/java/com/example/motorcycleantitheftsensor/service/AlarmWatchdogReceiver.kt app/src/test/java/com/example/motorcycleantitheftsensor/service/SensorServiceControllerTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionSnapshotStoreTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/sensor/LocationEvidencePolicyTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/security/ProtectionPermissionPolicyTest.kt
git commit -m "feat: drive service and sensors from protection coordinator"
```

### Task 8: Route Telegram commands through authoritative state

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusFormatter.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramCommandHandler.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/RemoteCommand.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClient.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusFormatterTest.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramCommandHandlerTest.kt`
- Modify test: `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/RemoteCommandTest.kt`

**Interfaces:**
- Consumes: real `ProtectionCoordinator`, immutable snapshots, existing pairing whitelist, and existing TOTP authorization.
- Produces: `ProtectionStatusFormatter.format(snapshot)`, `TelegramCommandHandler.handle(commandId, command, reply)`, and Telegram contact freshness updates.

- [ ] **Step 1: Write failing status-format tests**

```kotlin
class ProtectionStatusFormatterTest {
    @Test
    fun statusReportsRuntimeTruthAndNamedDegradation() {
        val snapshot = snapshot(
            state = ProtectionState.ARMED_DEGRADED,
            serviceRunning = true,
            telegramReachable = true,
            degradationReasons = setOf("MICROPHONE unavailable"),
            batteryLevelPercent = 74,
            lastDeliveryState = DeliveryState.FAILED,
        )

        val message = ProtectionStatusFormatter().format(snapshot)

        assertTrue(message.contains("Protection: ARMED_DEGRADED"))
        assertTrue(message.contains("Service: running"))
        assertTrue(message.contains("MICROPHONE: unavailable"))
        assertTrue(message.contains("Battery: 74%"))
        assertTrue(message.contains("Last delivery: FAILED"))
    }
}
```

- [ ] **Step 2: Run formatter test and verify RED**

```powershell
.\gradlew.bat testDebugUnitTest --tests '*ProtectionStatusFormatterTest'
```

Expected: compilation fails because the formatter is missing.

- [ ] **Step 3: Implement snapshot-only status formatting**

`ProtectionStatusFormatter` must read no preferences and perform no Android service lookup. Format state, service, Telegram reachability and last contact, sorted permission blockers, one compact line per `SensorKind`, battery/temperature when present, last incident ID/severity/lifecycle, and last delivery state. Use “unknown” or “unavailable” for absent evidence; never infer healthy from configuration.

- [ ] **Step 4: Write failing acknowledgement-order and timeout tests**

```kotlin
class TelegramCommandHandlerTest {
    @Test
    fun armRepliesReceivedBeforeFinalAppliedResult() = runTest {
        val gate = CompletableDeferred<Unit>()
        val replies = Channel<String>(Channel.UNLIMITED)
        val handler = handler(armingDelay = ArmingDelay { gate.await() })

        val job = launch { handler.handle("tg-1", RemoteCommand.Arm) { replies.send(it) } }
        assertEquals("ARM RECEIVED — checking readiness", replies.receive())
        gate.complete(Unit)
        assertTrue(replies.receive().contains("APPLIED — ARMED_HEALTHY"))
        job.join()
    }

    @Test
    fun commandTimeoutSaysOutcomeUnknownInsteadOfSuccess() = runTest {
        val replies = mutableListOf<String>()
        val handler = handler(
            armingDelay = ArmingDelay { awaitCancellation() },
            commandTimeoutMs = 1L,
        )
        handler.handle("tg-2", RemoteCommand.Arm) { replies += it }
        assertTrue(replies.last().contains("UNKNOWN"))
        assertFalse(replies.last().contains("ARMED!"))
    }
}
```

- [ ] **Step 5: Run handler test and verify RED**

```powershell
.\gradlew.bat testDebugUnitTest --tests '*TelegramCommandHandlerTest'
```

Expected: compilation fails because the handler is missing.

- [ ] **Step 6: Implement command handling with final results**

```kotlin
class TelegramCommandHandler(
    private val coordinator: ProtectionCoordinator,
    private val statusFormatter: ProtectionStatusFormatter,
    private val commandTimeoutMs: Long = 20_000L,
) {
    suspend fun handle(commandId: String, command: RemoteCommand, reply: suspend (String) -> Unit) {
        when (command) {
            RemoteCommand.Arm -> {
                reply("ARM RECEIVED — checking readiness")
                val result = withTimeoutOrNull(commandTimeoutMs) { coordinator.arm(commandId, CommandOrigin.TELEGRAM) }
                reply(result?.toTelegramText() ?: "UNKNOWN — command timed out; request /status")
            }
            is RemoteCommand.Disarm -> {
                val result = coordinator.disarm(commandId, CommandOrigin.TELEGRAM)
                reply(result.toTelegramText())
            }
            RemoteCommand.Status -> reply(statusFormatter.format(coordinator.snapshot.value))
            is RemoteCommand.Sensitivity -> {
                val level = command.level
                val result = if (level == null) ProtectionCommandResult(commandId, CommandOutcome.REJECTED, coordinator.snapshot.value.state, "Usage: /sensitivity 1-10") else coordinator.changeSensitivity(commandId, level)
                reply(result.toTelegramText())
            }
            RemoteCommand.Help -> reply(HELP_TEXT)
            is RemoteCommand.Decode, is RemoteCommand.Pair, RemoteCommand.Unknown -> Unit
        }
    }
}
```

`toTelegramText()` includes outcome, resulting state, and concise reason. Do not invoke disarm until `TelegramBotClient` has verified TOTP successfully.

- [ ] **Step 7: Make `TelegramBotClient` a transport/authentication adapter**

Parse once with `RemoteCommand.parse(text)`. Keep pairing and TOTP checks in the client, but delegate authorized arm/disarm/status/sensitivity/help commands to `TelegramCommandHandler` on `Dispatchers.IO`. Replace immediate “SYSTEM ARMED” and “DISARM COMMAND ACCEPTED” claims. A successful `getUpdates` or `sendMessage` response calls `coordinator.recordTelegramContact(nowMs)`; failed responses do not update freshness.

Unauthorized chats receive only `Unauthorized command.` and no state, UUID, sensor, pairing-owner, or operational detail. Parse errors return usage text and do not change coordinator state.

- [ ] **Step 8: Preserve and extend parser coverage**

```kotlin
@Test
fun sensitivityOutsideNumericSyntaxRemainsRejectedInput() {
    assertEquals(RemoteCommand.Sensitivity(null), RemoteCommand.parse("/sensitivity fast"))
}

@Test
fun unknownCommandDoesNotMapToOperationalAction() {
    assertEquals(RemoteCommand.Unknown, RemoteCommand.parse("/shutdown"))
}
```

Run all Telegram unit tests. Expected: PASS.

- [ ] **Step 9: Run the Telegram suite and full unit suite**

```powershell
.\gradlew.bat testDebugUnitTest --tests '*RemoteCommandTest' --tests '*ProtectionStatusFormatterTest' --tests '*TelegramCommandHandlerTest'
.\gradlew.bat testDebugUnitTest
```

Expected: both commands pass.

- [ ] **Step 10: Commit truthful Telegram command handling**

```powershell
git add app/src/main/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusFormatter.kt app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramCommandHandler.kt app/src/main/java/com/example/motorcycleantitheftsensor/telegram/RemoteCommand.kt app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClient.kt app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt app/src/test/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusFormatterTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramCommandHandlerTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/telegram/RemoteCommandTest.kt
git commit -m "feat: report truthful Telegram command outcomes"
```

### Task 9: Add isolated Demo Mode through the real incident path

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/DemoModeController.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/DemoModeStore.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentEngine.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/RemoteCommand.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramCommandHandler.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/DemoModeControllerTest.kt`
- Modify test: `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/RemoteCommandTest.kt`
- Modify test: `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramCommandHandlerTest.kt`

**Interfaces:**
- Consumes: real observation, correlation, repository, delivery, snapshot, and Telegram reply paths.
- Produces: `DemoEventType`, `DemoModeController.setEnabled/trigger`, persisted local enablement, and `/demo <vibration|tamper|power|audio>`.

- [ ] **Step 1: Write failing Demo isolation tests**

```kotlin
class DemoModeControllerTest {
    @Test
    fun disabledModeRejectsDemoWithoutCreatingHistory() = runTest {
        val fixture = fixture(enabled = false)
        val result = fixture.controller.trigger(DemoEventType.VIBRATION, nowMs = 1_000L)
        assertEquals(CommandOutcome.REJECTED, result.outcome)
        assertTrue(fixture.repository.listNewestFirst().isEmpty())
    }

    @Test
    fun tamperDemoUsesIncidentHistoryAndTelegramButNeverSms() = runTest {
        val fixture = fixture(enabled = true, telegramSuccess = true)
        val result = fixture.controller.trigger(DemoEventType.TAMPER, nowMs = 1_000L)
        val incident = fixture.repository.listNewestFirst().single()

        assertEquals(CommandOutcome.APPLIED, result.outcome)
        assertEquals(IncidentSource.DEMO, incident.source)
        assertEquals(IncidentSeverity.CRITICAL, incident.severity)
        assertEquals(1, fixture.telegramCalls)
        assertEquals(0, fixture.smsCalls)
    }

    @Test
    fun demoDoesNotMutateRealSensorBaseline() = runTest {
        val fixture = fixture(enabled = true)
        val before = fixture.processor.baseline(SensorKind.VIBRATION)
        fixture.controller.trigger(DemoEventType.VIBRATION, 1_000L)
        assertEquals(before, fixture.processor.baseline(SensorKind.VIBRATION))
    }

    @Test
    fun demoEvidenceNeverMergesIntoOpenRealIncident() = runTest {
        val fixture = fixture(enabled = true)
        fixture.openRealWarning("real-1")
        fixture.controller.trigger(DemoEventType.TAMPER, 2_000L)
        val history = fixture.repository.listNewestFirst()
        assertEquals(setOf(IncidentSource.REAL, IncidentSource.DEMO), history.map { it.source }.toSet())
        assertEquals(2, history.size)
    }
}
```

- [ ] **Step 2: Run Demo tests and verify RED**

```powershell
.\gradlew.bat testDebugUnitTest --tests '*DemoModeControllerTest'
```

Expected: compilation fails because Demo types do not exist.

- [ ] **Step 3: Implement local Demo configuration**

```kotlin
enum class DemoEventType { VIBRATION, TAMPER, POWER, AUDIO }

interface DemoSettings {
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean)
}

class DemoModeStore(context: Context) : DemoSettings {
    private val preferences = context.getSharedPreferences("demo_mode", Context.MODE_PRIVATE)
    override fun isEnabled(): Boolean = preferences.getBoolean("enabled", false)
    override fun setEnabled(enabled: Boolean) { preferences.edit().putBoolean("enabled", enabled).apply() }
}
```

`ProtectionCoordinator.setDemoModeEnabled(enabled)` persists via the store and copies the value to `ProtectionSnapshot.demoModeEnabled`; no remote command can enable this setting.

Use this exact constructor and public surface:

```kotlin
class DemoModeController(
    private val settings: DemoSettings,
    private val coordinator: ProtectionCoordinator,
    private val incidentEngine: IncidentEngine,
    private val repository: IncidentRepository,
    private val delivery: IncidentDeliveryCoordinator,
    private val deliveryConfiguration: () -> DeliveryConfiguration,
) {
    fun setEnabled(enabled: Boolean)
    fun trigger(type: DemoEventType, nowMs: Long): ProtectionCommandResult
}
```

- [ ] **Step 4: Implement Demo event generation without baseline mutation**

`DemoModeController.trigger` rejects when local Demo Mode is disabled. When enabled, create literal `SensorObservation(source = DEMO)` sequences and send them directly to `IncidentEngine`, bypassing `SensorObservationProcessor` baseline updates:

```kotlin
when (type) {
    DemoEventType.VIBRATION -> listOf(demoObservation(VIBRATION, nowMs, 2.5, "demo_vibration"))
    DemoEventType.TAMPER -> listOf(
        demoObservation(VIBRATION, nowMs, 2.5, "demo_vibration"),
        demoObservation(LIGHT, nowMs + 1, 150.0, "demo_under_seat_light"),
    )
    DemoEventType.POWER -> listOf(demoObservation(POWER_THERMAL, nowMs, 1.0, "charger_disconnected"))
    DemoEventType.AUDIO -> listOf(demoObservation(MICROPHONE, nowMs, 0.9, "relative_amplitude"))
}
```

Permit Demo audio to open a warning Demo incident so the customer action has visible history; retain the Task 5 rule that real audio cannot open or escalate a critical incident alone. `IncidentEngine` must close or retain the current real incident independently and must never merge evidence across `IncidentSource`. Persist and deliver through `IncidentDeliveryCoordinator` with `IncidentSource.DEMO`.

- [ ] **Step 5: Add the owner-only `/demo` parser and handler**

Add `data class Demo(val type: DemoEventType?) : RemoteCommand`; parse only the four exact lowercase arguments. `TelegramBotClient` delegates it only after paired-owner validation. `TelegramCommandHandler` calls `DemoModeController.trigger`; disabled mode returns `REJECTED — enable Demo Mode locally`, and the command cannot change the local setting.

- [ ] **Step 6: Run Demo, delivery, incident, and Telegram tests**

```powershell
.\gradlew.bat testDebugUnitTest --tests '*DemoModeControllerTest' --tests '*IncidentDeliveryCoordinatorTest' --tests '*IncidentEngineTest' --tests '*RemoteCommandTest' --tests '*TelegramCommandHandlerTest'
```

Expected: all selected tests pass and the SMS call count remains zero for every Demo branch.

- [ ] **Step 7: Commit Demo Mode**

```powershell
git add app/src/main/java/com/example/motorcycleantitheftsensor/protection/DemoModeController.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/DemoModeStore.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentEngine.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt app/src/main/java/com/example/motorcycleantitheftsensor/telegram/RemoteCommand.kt app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramCommandHandler.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/DemoModeControllerTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/telegram/RemoteCommandTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramCommandHandlerTest.kt
git commit -m "feat: add SMS-safe protection demo mode"
```

### Task 10: Replace independent dashboard state with the three-destination UI

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreen.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/ProtectionScreen.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/events/EventsScreen.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/Navigation.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/DashboardScreen.kt`
- Security-gated modify: `app/src/main/java/com/example/motorcycleantitheftsensor/data/EncryptedPrefsManager.kt`
- Create test: `app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt`
- Create test: `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt`

**Interfaces:**
- Consumes: `ProtectionRuntimeGraph`, coordinator snapshots, incident repository, Demo controller, and existing Telegram/TOTP setup operations.
- Produces: `ProtectionUiState`, `ProtectionViewModel`, `ProtectionAppScreen`, Protection/Events/Settings destinations, masked-token state, and reset/re-pair action.

- [ ] **Step 1: Write failing ViewModel state tests**

```kotlin
class ProtectionViewModelTest {
    @Test
    fun uiStateComesFromSnapshotAndIncidentRepository() = runTest {
        val coordinator = realCoordinator(initialState = ProtectionState.ARMED_DEGRADED)
        val repository = FakeIncidentRepository(listOf(incident("i-1", 1L)))
        val viewModel = ProtectionViewModel(coordinator, repository, DemoModeController.fake())

        val state = viewModel.uiState.first()

        assertEquals(ProtectionState.ARMED_DEGRADED, state.snapshot.state)
        assertEquals("i-1", state.incidents.single().id)
    }

    @Test
    fun armIntentDoesNotOptimisticallyFlipUiState() = runTest {
        val gate = CompletableDeferred<Unit>()
        val viewModel = viewModel(armingDelay = ArmingDelay { gate.await() })
        viewModel.arm()
        assertEquals(ProtectionState.ARMING, viewModel.uiState.value.snapshot.state)
    }
}
```

- [ ] **Step 2: Run ViewModel test and verify RED**

```powershell
.\gradlew.bat testDebugUnitTest --tests '*ProtectionViewModelTest'
```

Expected: compilation fails because the ViewModel/UI state does not exist.

- [ ] **Step 3: Implement one UI state and intent surface**

```kotlin
data class ProtectionUiState(
    val snapshot: ProtectionSnapshot,
    val incidents: List<SecurityIncident>,
    val armingSecondsRemaining: Int?,
    val tokenConfigured: Boolean,
    val tokenRevealed: Boolean,
)

class ProtectionViewModel(
    private val coordinator: ProtectionCoordinator,
    private val incidents: IncidentRepository,
    private val demoModeController: DemoModeController,
) : ViewModel() {
    val uiState: StateFlow<ProtectionUiState>
    fun arm()
    fun disarm()
    fun setSensitivity(level: Int)
    fun setDemoModeEnabled(enabled: Boolean)
    fun triggerDemo(type: DemoEventType)
    fun clearHistory()
    fun setTokenRevealed(revealed: Boolean)
}
```

All command functions dispatch on `viewModelScope`; state changes only through coordinator snapshots or repository refreshes. The ViewModel never writes `isSystemArmed` directly.

- [ ] **Step 4: Build the three destinations**

`ProtectionAppScreen` uses Material 3 navigation with exactly three destinations:

- Protection: dominant text/icon state, one arm/disarm action, 10-second countdown, named blockers/degradations, compact sensor/channel health, persistent Demo banner, non-blocking active-incident card, and latest incident.
- Events: newest-first real/Demo history with explicit source, severity, lifecycle, evidence, and delivery outcome plus a clear-history confirmation.
- Settings: pairing state, masked token field, permissions, sensitivity 1-10, SMS fallback configuration, diagnostics, security controls, local Demo switch, and four Demo action buttons enabled only while Demo Mode is active.

State is conveyed by text and icon as well as color. Do not reintroduce the removed red modal. Keep `DashboardScreen` as a small compatibility wrapper that delegates to `ProtectionAppScreen` during this migration; remove its independent arm/sensitivity/sensor/Telegram state parameters only after `Navigation.kt` compiles against the new ViewModel.

- [ ] **Step 5: Write failing Compose behavior tests**

```kotlin
@Test
fun degradedProtectionShowsReasonAndSingleDisarmAction() {
    compose.setContent { ProtectionAppScreen(state = degradedState("MICROPHONE unavailable"), actions = fakeActions()) }
    compose.onNodeWithText("ARMED — DEGRADED").assertExists()
    compose.onNodeWithText("MICROPHONE unavailable").assertExists()
    compose.onNodeWithText("Disarm").assertExists()
    compose.onNodeWithText("Arm").assertDoesNotExist()
}

@Test
fun demoAndDeliveryLabelsAreVisibleWithoutColorDependence() {
    compose.setContent { ProtectionAppScreen(state = stateWithDemoFailedIncident(), actions = fakeActions()) }
    compose.onNodeWithText("DEMO MODE").assertExists()
    compose.onNodeWithText("Delivery failed").assertExists()
}

@Test
fun botTokenIsMaskedUntilExplicitReveal() {
    compose.setContent { SettingsScreen(state = configuredTokenState(), actions = fakeActions()) }
    compose.onNodeWithContentDescription("Reveal bot token").assertExists()
    compose.onNodeWithText(TEST_TOKEN).assertDoesNotExist()
}
```

Use the literal test token `123456:TEST_ONLY_NOT_A_REAL_TOKEN`; never load the real preference value into test output.

- [ ] **Step 6: Run Compose test and verify RED on the connected test target**

```powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest
& 'C:\Users\ASUS\AppData\Local\Android\Sdk\platform-tools\adb.exe' shell am instrument -w com.example.motorcycleantitheftsensor.test/androidx.test.runner.AndroidJUnitRunner
```

Expected before implementation: the new Compose test class fails to compile or the named semantics are absent.

- [ ] **Step 7: Implement token masking without exposing the secret in UI state**

Keep the actual token in the existing encrypted manager and pass it only to the editable field while Settings is active. Use `PasswordVisualTransformation()` by default and `VisualTransformation.None` only after the local reveal action. Never include token text in `ProtectionUiState.toString`, logs, accessibility content descriptions, screenshots, or status messages.

- [ ] **Step 8: Pass the security-critical reset gate before editing encrypted preferences**

Record the user’s approved-spec decision as authorization for the narrow reset operation, then run the required Godkiller security review against the exact proposed diff. If that review capability is unavailable, stop before editing `EncryptedPrefsManager.kt` and ask the user to enable the required security-review path.

After the gate, add only:

```kotlin
fun clearTelegramOwnership(): Boolean = prefs.edit()
    .remove(KEY_BOT_TOKEN)
    .remove(KEY_ALLOWED_CHAT_IDS)
    .remove(KEY_PAIRING_CODE)
    .remove(KEY_PAIRING_EXPIRES_AT_MS)
    .commit()
```

Do not clear TOTP seed, SMS key/destination, sensitivity, device UUID, incident history, or protection history. The UI calls this after confirmation, stops the current Telegram client, generates a fresh pairing code, and returns to setup state.

- [ ] **Step 9: Run UI tests, unit tests, and build**

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest
& 'C:\Users\ASUS\AppData\Local\Android\Sdk\platform-tools\adb.exe' shell am instrument -w com.example.motorcycleantitheftsensor.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: unit/build tasks pass; Compose tests pass on the connected target; test output contains no token.

- [ ] **Step 10: Commit only reviewed UI/security paths**

```powershell
git add app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreen.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/ProtectionScreen.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/events/EventsScreen.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt app/src/main/java/com/example/motorcycleantitheftsensor/Navigation.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/DashboardScreen.kt app/src/main/java/com/example/motorcycleantitheftsensor/data/EncryptedPrefsManager.kt app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt
git commit -m "feat: present authoritative protection experience"
```

### Task 11: Prove release behavior on the real device and refresh project truth

**Files:**
- Modify: `docs/ARCHITECTURE.md`
- Create: `docs/verification/2026-08-08-motorcycle-guard-protection-system.md`
- Update through HCP: `../.hcp/checkpoints/<generated-checkpoint>.json`

**Interfaces:**
- Consumes: the completed implementation and acceptance criteria from the approved spec.
- Produces: clean automated evidence, device evidence without credentials, updated architecture truth, and a resumable HCP handoff.

- [ ] **Step 1: Run a fresh non-up-to-date unit/build verification**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --rerun-tasks testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL`; record suite/test counts from generated XML and APK paths. Do not describe a dependency-download failure as a test run.

- [ ] **Step 2: Install the app and test APK without deleting app data**

```powershell
$adb='C:\Users\ASUS\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb devices -l
& $adb install -r 'app\build\outputs\apk\debug\app-debug.apk'
& $adb install -r 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
& $adb shell am instrument -w com.example.motorcycleantitheftsensor.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: one authorized target, both installs succeed, instrumentation reports `OK`. If the device is absent/unauthorized, record device verification as blocked instead of claiming success.

- [ ] **Step 3: Verify local and Telegram command truth**

On the paired owner chat and local UI, record redacted timestamps/results for:

1. Local Arm → UI `ARMING` countdown → final healthy/degraded state.
2. `/status` during arming and after completion; UI, notification, and Telegram must agree.
3. `/sensitivity 7` while armed; verify the running vibration detector reports the applied level without service restart.
4. `/disarm <OTP>`; detectors stop while `/status` remains responsive and reports `DISARMED_ONLINE`.
5. Missing required permission; `/arm` returns rejected with the named blocker and never says armed.

Never capture the bot token or OTP in evidence.

- [ ] **Step 4: Verify grace suppression and real incident correlation**

Arm and induce vibration during the 10-second grace period; confirm health/baseline samples update but no incident, Telegram alert, SMS, or blocking modal appears. After grace, perform one controlled vibration followed by an under-seat light change within 15 seconds; confirm one incident ID escalates warning→critical, one immediate escalation update is sent, repeated evidence updates the same history record, and incident-level suppression prevents alert spam.

- [ ] **Step 5: Verify all Demo events and forbidden side effects**

Enable Demo Mode locally. Trigger vibration, tamper, power, and audio from the local panel, then repeat through paired-owner `/demo`. For each event confirm UI/history/Telegram display `DEMO`, incident IDs are stored, and captured logs show zero `SmsManager.sendTextMessage` and zero `ACTION_CALL` attempts. Confirm an unauthorized chat obtains no operational detail and cannot trigger Demo.

- [ ] **Step 6: Verify location, delivery failure, and honest wording**

Test once with a fresh accurate location fix and once with location unavailable/stale. Confirm alert text includes fix age/accuracy only for valid evidence and otherwise states why no recent fix is available. Disable network for a controlled real critical incident with configured SMS fallback; verify Telegram attempt `FAILED`, eligible SMS attempt outcome is recorded, and no channel is marked sent before confirmed success. Repeat with Demo and verify SMS is never attempted.

- [ ] **Step 7: Verify process and reboot recovery**

Test two persisted modes:

1. Disarmed: force-stop/process death then restart/boot; service returns remote-control-only and Telegram remains reachable.
2. Armed: process death then restart/boot; state becomes `ARMING`, readiness/baselines rerun, stale health is not shown as healthy, and an earlier open incident is marked interrupted rather than restored active.

Record final UI, notification, and `/status` state for each case.

- [ ] **Step 8: Update architecture and write the verification report**

Replace the stale “All 22 tasks are COMPLETE” statement with the actual verified state. Document the coordinator ownership boundary, incident flow, persistence limit, delivery policy, Demo isolation, three UI destinations, automated commands/results, device identifier redacted to the last four characters, manual matrix results, known risks, and any blocked checks. Do not include secrets or inferred success.

- [ ] **Step 9: Run final diff and secret scans**

```powershell
git status --short
git diff --check
rg -n --hidden -g '!build/**' -g '!.gradle/**' '(bot[0-9]{6,}:|[0-9]{6,}:[A-Za-z0-9_-]{20,})' app docs
```

Expected: `git diff --check` has no whitespace errors and the secret scan returns no real Telegram credential. Investigate every match before proceeding.

- [ ] **Step 10: Create and validate the HCP handoff**

From `D:\security` run:

```powershell
.\hcp.cmd checkpoint -Title "Motorcycle Guard protection system implementation and device verification"
.\hcp.cmd resume
.\hcp.cmd audit
.\hcp.cmd recover -DryRun
```

Populate the generated checkpoint with explicit completed, pending, risks, decisions, verification, artifacts, git dirty-state warning, and the exact resume command. Ensure the latest pointer resolves to that populated checkpoint and rendered resume matches the JSON.

- [ ] **Step 11: Commit documentation and checkpoint using exact paths**

```powershell
git add docs/ARCHITECTURE.md docs/verification/2026-08-08-motorcycle-guard-protection-system.md
git commit -m "docs: record motorcycle guard verification evidence"
```

Do not stage unrelated `.hcp`, build output, screenshots containing credentials, or pre-existing dirty files. Commit checkpoint artifacts separately only if the repository’s established HCP policy requires them and their content has been reviewed.

## Final acceptance checklist

- [ ] UI, foreground notification, service, and Telegram render the same `ProtectionSnapshot` after every command.
- [ ] Required-readiness failure rejects Arm; optional loss results in named degradation.
- [ ] Ten-second arming grace suppresses incidents while collecting baseline and health samples.
- [ ] Disarm stops detectors but preserves foreground remote control and Telegram polling.
- [ ] `/status` reports live service, Telegram freshness, permissions, sensor health, battery, incident, and delivery evidence.
- [ ] Sensitivity 1-10 is validated, persisted, and applied to the running vibration detector.
- [ ] One physical scenario produces one incident thread with explicit escalation and close behavior.
- [ ] Telegram `SENT` and SMS fallback reflect confirmed outcomes only.
- [ ] Demo events are visibly marked everywhere and never invoke SMS or calls.
- [ ] Bot token is masked by default, reset/re-pair is narrow and security-reviewed, and evidence contains no secrets.
- [ ] No blocking red alarm modal exists; active incidents are non-blocking and retained in history.
- [ ] Restart recovery revalidates readiness and baselines instead of trusting stale armed/healthy state.
- [ ] Unit tests, build, Compose instrumentation, and device verification have fresh recorded evidence.
