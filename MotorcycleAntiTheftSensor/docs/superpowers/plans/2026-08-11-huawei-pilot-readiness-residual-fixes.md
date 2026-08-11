# Huawei Pilot Readiness Residual Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the remaining protection-state ordering and truth defects, then produce host and controlled Huawei evidence sufficient for a single-device pilot.

**Architecture:** `ProtectionCoordinator` remains the sole state owner and stamps persisted truth with a process-local monotonic revision. A process-wide `ProtectionStatePersistenceArbiter` serializes every recovery-snapshot and compatibility-armed write, rejects stale revisions, and supplies the durable Disarm barrier. Recovery carries a coordinator-owned generation token; explicit owner commands invalidate the token before a recovery transition can begin.

**Tech Stack:** Kotlin, Android foreground service, coroutines/StateFlow/Mutex, SharedPreferences and encrypted preferences, JUnit 4, Jetpack Compose Android tests, Gradle on Android Studio JBR.

## Global Constraints

- Preserve `ProtectionCoordinator` as the only authoritative protection-state owner.
- Do not add third-party dependencies or restructure unrelated modules.
- Keep UI, foreground notification, recovery storage, and Telegram derived from the authoritative snapshot.
- Real Telegram is allowed only for the paired owner during the later Huawei gate.
- Keep `SEND_SMS` denied or revoked during Huawei acceptance; never send a real SMS or invoke an emergency call.
- Demo paths may use marked Telegram delivery but may never invoke SMS or calls.
- Do not log or checkpoint bot tokens, TOTP values, SMS destinations, encryption keys, or token-bearing request URLs.
- Do not run ADB until the host gate and scoped review pass and the user separately authorizes device execution.
- Use Android Studio JBR with `GRADLE_OPTS=-Xmx256m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m`, `--no-daemon`, `--max-workers=1`, and `-Dkotlin.compiler.execution.strategy=in-process`.
- Run no overlapping Gradle or ADB processes.
- Stage and commit only the exact paths named by each task; preserve unrelated untracked files.
- Production edits require a failing deterministic test first.

## File responsibility map

- `ProtectionModels.kt`: authoritative snapshot revision and persistence-source vocabulary.
- `ProtectionCoordinator.kt`: revision publication, owner-command recovery invalidation, source-specific degradation, and truthful command results.
- `ProtectionStatePersistenceArbiter.kt`: the only ordered protection-state persistence boundary.
- `ProtectionSnapshotStore.kt`: recovery serialization, including stored revision evidence.
- `EncryptedPrefsManager.kt`: synchronous compatibility armed-flag writer used by the arbiter.
- `ProtectionRuntimeGraph.kt`: constructs and injects the shared arbiter; reports incident-storage health by source.
- `SensorService.kt`: routes projections and durable writes through the arbiter and passes recovery tokens.
- `IncidentCloseDispatcher.kt`: returns whether local close history was durably saved before async external delivery.
- `AndroidProtectionRuntime.kt`: truthful microphone readiness and battery-status-only routing.
- `PowerThermalMonitor.kt`: emits typed diagnostics whose battery sample is status-only upstream.
- Focused test files mirror each production responsibility.

---

### Task 1: Monotonic snapshot revision and ordered persistence arbiter

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionStatePersistenceArbiter.kt`
- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionStatePersistenceArbiterTest.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionModels.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinatorTest.kt`

**Interfaces:**
- Produces: `ProtectionSnapshot.revision: Long`.
- Produces: `ProtectionPersistenceRequest(snapshot, lastServiceHeartbeatAtMs)`.
- Produces: `ProtectionPersistenceOutcome.COMMITTED` and `SUPERSEDED`.
- Produces: `ProtectionStatePersistenceArbiter.persist(request): ProtectionPersistenceOutcome`.
- Consumes later: synchronous lambdas that persist the compatibility armed flag and recovery snapshot.

- [ ] **Step 1: Add a RED concurrency test for stale Armed after durable Disarm**

```kotlin
@Test
fun olderArmedWriteCannotOverwriteNewerDisarmedRevision() = runTest {
    val enteredOldWrite = CompletableDeferred<Unit>()
    val releaseOldWrite = CompletableDeferred<Unit>()
    val committed = mutableListOf<ProtectionState>()
    val arbiter = ProtectionStatePersistenceArbiter(
        writeCompatibilityArmed = { },
        writeSnapshot = { snapshot, _ ->
            if (snapshot.revision == 4L) {
                enteredOldWrite.complete(Unit)
                releaseOldWrite.await()
            }
            committed += snapshot.state
        },
    )
    val armed = ProtectionSnapshot.offline(1L).copy(
        revision = 4L,
        state = ProtectionState.ARMED_HEALTHY,
    )
    val disarmed = armed.copy(revision = 5L, state = ProtectionState.DISARMED_ONLINE)

    val old = async { arbiter.persist(ProtectionPersistenceRequest(armed, 10L)) }
    enteredOldWrite.await()
    val newest = async { arbiter.persist(ProtectionPersistenceRequest(disarmed, 11L)) }
    releaseOldWrite.complete(Unit)

    assertEquals(ProtectionPersistenceOutcome.COMMITTED, old.await())
    assertEquals(ProtectionPersistenceOutcome.COMMITTED, newest.await())
    assertEquals(ProtectionState.DISARMED_ONLINE, committed.last())
    assertEquals(
        ProtectionPersistenceOutcome.SUPERSEDED,
        arbiter.persist(ProtectionPersistenceRequest(armed, 12L)),
    )
    assertEquals(ProtectionState.DISARMED_ONLINE, committed.last())
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_OPTS='-Xmx256m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*ProtectionStatePersistenceArbiterTest.olderArmedWriteCannotOverwriteNewerDisarmedRevision'
```

Expected: compilation fails because `ProtectionStatePersistenceArbiter`, request/outcome types, and `revision` do not exist.

- [ ] **Step 3: Implement revision publication and the minimal arbiter**

Add to `ProtectionSnapshot` with a compatibility default:

```kotlin
val revision: Long = 0L,
```

Use one coordinator helper for every authoritative mutation:

```kotlin
private inline fun updateSnapshot(transform: (ProtectionSnapshot) -> ProtectionSnapshot) {
    mutableSnapshot.update { current ->
        transform(current).copy(revision = current.revision + 1L)
    }
}
```

Implement the arbiter with one mutex and stale-revision rejection:

```kotlin
data class ProtectionPersistenceRequest(
    val snapshot: ProtectionSnapshot,
    val lastServiceHeartbeatAtMs: Long?,
)

enum class ProtectionPersistenceOutcome { COMMITTED, SUPERSEDED }

class ProtectionStatePersistenceArbiter(
    private val writeCompatibilityArmed: suspend (Boolean) -> Unit,
    private val writeSnapshot: suspend (ProtectionSnapshot, Long?) -> Unit,
) {
    private val mutex = Mutex()
    private var highestCommittedRevision = Long.MIN_VALUE

    suspend fun persist(request: ProtectionPersistenceRequest): ProtectionPersistenceOutcome =
        mutex.withLock {
            if (request.snapshot.revision < highestCommittedRevision) {
                return@withLock ProtectionPersistenceOutcome.SUPERSEDED
            }
            val armed = request.snapshot.state in setOf(
                ProtectionState.ARMING,
                ProtectionState.ARMED_HEALTHY,
                ProtectionState.ARMED_DEGRADED,
                ProtectionState.ALERT_ACTIVE,
            )
            writeSnapshot(request.snapshot, request.lastServiceHeartbeatAtMs)
            writeCompatibilityArmed(armed)
            highestCommittedRevision = request.snapshot.revision
            ProtectionPersistenceOutcome.COMMITTED
        }
}
```

Replace direct `mutableSnapshot.update` calls that change persisted truth with `updateSnapshot`; retain no path that changes state, blockers, degradation, channel health, sensor health, incident, delivery, or Demo truth without incrementing revision.

- [ ] **Step 4: Run arbiter and coordinator suites GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*ProtectionStatePersistenceArbiterTest' --tests '*ProtectionCoordinatorTest'
```

Expected: both suites pass with zero failures.

- [ ] **Step 5: Commit Task 1 exact paths**

```powershell
git add -- app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionStatePersistenceArbiter.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionModels.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionStatePersistenceArbiterTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinatorTest.kt
git commit -m "fix(protection): serialize authoritative state persistence"
```

### Task 2: Route every state write through the durable arbiter

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/data/EncryptedPrefsManager.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionSnapshotStore.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionSnapshotStoreTest.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinatorTest.kt`

**Interfaces:**
- Consumes: `ProtectionStatePersistenceArbiter.persist` from Task 1.
- Produces: `EncryptedPrefsManager.commitSystemArmed(armed: Boolean): Boolean` without removing the existing public setter.
- Produces: `Graph.statePersistence: ProtectionStatePersistenceArbiter`.
- Produces: coordinator `durableSnapshotWriter` that awaits the shared arbiter.

- [ ] **Step 1: Add RED tests for the shared Disarm barrier**

Add a coordinator test where a stale Armed request is held inside the arbiter, Disarm starts, then the stale request is released:

```kotlin
val oldWriteEntered = CompletableDeferred<Unit>()
val releaseOldWrite = CompletableDeferred<Unit>()
val committedSnapshots = mutableListOf<ProtectionSnapshot>()
val compatibilityArmedValues = mutableListOf<Boolean>()
val armedSnapshot = ProtectionSnapshot.offline(1L).copy(
    revision = 4L,
    state = ProtectionState.ARMED_HEALTHY,
)
val arbiter = ProtectionStatePersistenceArbiter(
    writeCompatibilityArmed = { armed -> compatibilityArmedValues += armed },
    writeSnapshot = { snapshot, _ ->
        if (snapshot.state == ProtectionState.ARMED_HEALTHY) {
            oldWriteEntered.complete(Unit)
            releaseOldWrite.await()
        }
        committedSnapshots += snapshot
    },
)
val oldWrite = async {
    arbiter.persist(ProtectionPersistenceRequest(armedSnapshot, 10L))
}
oldWriteEntered.await()
val disarm = async { coordinator.disarm("owner", CommandOrigin.LOCAL) }
assertFalse(disarm.isCompleted)
releaseOldWrite.complete(Unit)
oldWrite.await()
assertEquals(CommandOutcome.APPLIED, disarm.await().outcome)
assertEquals(ProtectionState.DISARMED_ONLINE, committedSnapshots.last().state)
assertFalse(compatibilityArmedValues.last())
```

Add a snapshot-store round-trip assertion that `revision` is persisted and loaded in `ProtectionRecoveryHints`.

- [ ] **Step 2: Run focused tests and verify RED for missing shared wiring**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*ProtectionCoordinatorTest.disarmWaitsBehindOlderWriteAndRemainsLastCommitted' --tests '*ProtectionSnapshotStoreTest*revision*'
```

Expected: failure because the service/coordinator do not share an arbiter and recovery hints do not store revision.

- [ ] **Step 3: Implement exact wiring**

Add a synchronous compatibility method while preserving the old API:

```kotlin
fun commitSystemArmed(armed: Boolean): Boolean =
    prefs.edit().putBoolean(KEY_SYSTEM_ARMED, armed).commit()
```

Persist `KEY_REVISION` in `ProtectionSnapshotStore.save` and expose it as `ProtectionRecoveryHints.revision`. Construct one arbiter in `ProtectionRuntimeGraph`:

```kotlin
val statePersistence = ProtectionStatePersistenceArbiter(
    writeCompatibilityArmed = { armed ->
        check(preferences.commitSystemArmed(armed)) { "Unable to persist armed compatibility state" }
    },
    writeSnapshot = { snapshot, heartbeat -> snapshotStore.save(snapshot, heartbeat) },
)
```

Inject the same instance into `ProtectionCoordinator.durableSnapshotWriter` and return it from `Graph`. Replace the direct `preferences.setSystemArmed` and `snapshotStore.save` block in `SensorService.handleSnapshot` with:

```kotlin
graph.statePersistence.persist(
    ProtectionPersistenceRequest(snapshot, lastServiceHeartbeatAtMs),
)
```

Treat `SUPERSEDED` as a normal no-op. Only a thrown write failure records snapshot persistence degradation.

- [ ] **Step 4: Run persistence-focused suites GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*ProtectionStatePersistenceArbiterTest' --tests '*ProtectionSnapshotStoreTest' --tests '*ProtectionCoordinatorTest'
```

Expected: all selected tests pass.

- [ ] **Step 5: Commit Task 2 exact paths**

```powershell
git add -- app/src/main/java/com/example/motorcycleantitheftsensor/data/EncryptedPrefsManager.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionSnapshotStore.kt app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionSnapshotStoreTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinatorTest.kt
git commit -m "fix(protection): await one durable state writer"
```

### Task 3: Make explicit owner commands atomically supersede recovery

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionSnapshotStore.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinatorTest.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionSnapshotStoreTest.kt`

**Interfaces:**
- Produces: `RecoveryGenerationToken(value: Long)`.
- Produces: `ProtectionCoordinator.captureRecoveryToken()` and `invalidateRecovery()`.
- Extends: `arm` and `disarm` with optional `recoveryToken: RecoveryGenerationToken? = null`.

- [ ] **Step 1: Write deterministic RED tests for the exact race**

```kotlin
@Test
fun recoveryCapturedBeforeExplicitDisarmCannotArmAfterDisarmCompletes() = runTest {
    val token = coordinator.captureRecoveryToken()
    val disarm = coordinator.disarm("owner", CommandOrigin.LOCAL)
    assertEquals(CommandOutcome.APPLIED, disarm.outcome)

    val recovery = coordinator.arm("recovery", CommandOrigin.RECOVERY, token)

    assertEquals(CommandOutcome.REJECTED, recovery.outcome)
    assertEquals(ProtectionState.DISARMED_ONLINE, coordinator.snapshot.value.state)
    assertEquals(0, runtime.startCount)
}
```

Add the corresponding explicit-Arm invalidation test and a service/gate test where supersession occurs after recovery planning but before the coordinator call.

- [ ] **Step 2: Run recovery-focused tests and verify RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*ProtectionCoordinatorTest*recovery*' --tests '*ProtectionSnapshotStoreTest*recovery*'
```

Expected: compilation failure for the token API or behavioral failure showing recovery can still apply.

- [ ] **Step 3: Implement coordinator-owned recovery generation**

```kotlin
@JvmInline
value class RecoveryGenerationToken(val value: Long)

private val recoveryGeneration = AtomicLong(0L)

fun captureRecoveryToken(): RecoveryGenerationToken =
    RecoveryGenerationToken(recoveryGeneration.get())

fun invalidateRecovery() {
    recoveryGeneration.incrementAndGet()
    armingEpoch.incrementAndGet()
}
```

Explicit `LOCAL` and `TELEGRAM` commands call `invalidateRecovery()` before waiting for `commandMutex`. Recovery commands require a non-null token and re-check `token.value == recoveryGeneration.get()` inside `commandMutex` immediately before readiness, detector startup, or Disarm transition.

`SensorService` captures the token when recovery begins, calls `coordinator.invalidateRecovery()` as soon as an explicit action is observed, and passes the captured token to the recovery command. Keep `ProtectionRecoveryGate` responsible only for lifecycle completion/persistence gating, not the final authorization decision.

- [ ] **Step 4: Run coordinator, gate, and service-action suites GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*ProtectionCoordinatorTest' --tests '*ProtectionSnapshotStoreTest' --tests '*SensorServiceActionTest'
```

Expected: all selected tests pass and no recovery test uses timing sleeps.

- [ ] **Step 5: Commit Task 3 exact paths**

```powershell
git add -- app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionSnapshotStore.kt app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinatorTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionSnapshotStoreTest.kt
git commit -m "fix(protection): let owner commands supersede recovery"
```

### Task 4: Track persistence health by source and make Disarm result truthful

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionModels.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentCloseDispatcher.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinatorTest.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/IncidentCloseDispatcherTest.kt`

**Interfaces:**
- Produces: `PersistenceSource.SNAPSHOT` and `INCIDENT_HISTORY`.
- Replaces: boolean persistence failure with `Set<PersistenceSource>`.
- Produces: `recordPersistenceFailure(source)` and `recordPersistenceRecovered(source)`.
- Changes: `IncidentCloseDispatcher.persistAndDispatch` returns `Boolean` for local durability.
- Changes: coordinator `incidentCloser` returns `Boolean`.

- [ ] **Step 1: Add RED tests for independent recovery and partial Disarm**

```kotlin
coordinator.recordPersistenceFailure(PersistenceSource.INCIDENT_HISTORY)
coordinator.recordPersistenceFailure(PersistenceSource.SNAPSHOT)
coordinator.recordPersistenceRecovered(PersistenceSource.SNAPSHOT)
assertTrue(coordinator.snapshot.value.degradationReasons.contains("INCIDENT history unavailable"))
assertFalse(coordinator.snapshot.value.degradationReasons.contains("SNAPSHOT persistence unavailable"))
```

Add a Disarm test where `incidentCloser` returns `false` but durable snapshot persistence succeeds:

```kotlin
val coordinator = coordinator(
    runtime = runtime,
    incidentCloser = { false },
    durableSnapshotWriter = { },
)

val result = coordinator.disarm("owner", CommandOrigin.LOCAL)

assertEquals(CommandOutcome.UNKNOWN, result.outcome)
assertEquals(ProtectionState.DISARMED_ONLINE, result.resultingState)
assertTrue(result.reason.contains("incident history", ignoreCase = true))
assertEquals(1, runtime.stopCount)
```

- [ ] **Step 2: Run focused tests and verify RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*ProtectionCoordinatorTest*persistence*' --tests '*ProtectionCoordinatorTest*incidentHistory*' --tests '*IncidentCloseDispatcherTest'
```

Expected: compile/behavior failure because health is still one boolean and close persistence returns `Unit`.

- [ ] **Step 3: Implement source-specific truth**

```kotlin
enum class PersistenceSource { SNAPSHOT, INCIDENT_HISTORY }
```

Update failure/recovery methods atomically and derive named degradations from the complete set. Use `SNAPSHOT persistence unavailable` and `INCIDENT history unavailable` as stable labels:

```kotlin
private val unavailablePersistence = AtomicReference<Set<PersistenceSource>>(emptySet())

fun recordPersistenceFailure(source: PersistenceSource) {
    unavailablePersistence.updateAndGet { current -> current + source }
    refreshPersistenceDegradations()
}

fun recordPersistenceRecovered(source: PersistenceSource) {
    unavailablePersistence.updateAndGet { current -> current - source }
    refreshPersistenceDegradations()
}

private fun persistenceDegradations(): Set<String> =
    unavailablePersistence.get().mapTo(mutableSetOf()) { source ->
        when (source) {
            PersistenceSource.SNAPSHOT -> "SNAPSHOT persistence unavailable"
            PersistenceSource.INCIDENT_HISTORY -> "INCIDENT history unavailable"
        }
    }

private fun refreshPersistenceDegradations() {
    val persistenceReasons = persistenceDegradations()
    updateSnapshot { current ->
        val remaining = current.degradationReasons - persistenceReasonLabels
        val updated = remaining + persistenceReasons
        current.copy(
            state = when {
                current.state == ProtectionState.ARMED_HEALTHY && updated.isNotEmpty() ->
                    ProtectionState.ARMED_DEGRADED
                current.state == ProtectionState.ARMED_DEGRADED &&
                    updated.isEmpty() &&
                    current.sensorHealth[SensorKind.VIBRATION]?.state == SensorHealthState.HEALTHY ->
                    ProtectionState.ARMED_HEALTHY
                else -> current.state
            },
            degradationReasons = updated,
        )
    }
}

private val persistenceReasonLabels = setOf(
    "SNAPSHOT persistence unavailable",
    "INCIDENT history unavailable",
)
```

Make `IncidentCloseDispatcher.persistAndDispatch` return `false` on local failure and `true` after local persistence, while external delivery remains asynchronous. In Disarm, await both local-close result and the durable snapshot barrier. Return `APPLIED` only when both are confirmed; otherwise return `UNKNOWN` while retaining the truthful `DISARMED_ONLINE` state and stopped detectors.

Route every repository success/failure in `ProtectionRuntimeGraph` to `INCIDENT_HISTORY`; route arbiter success/failure in `SensorService` to `SNAPSHOT` only.

- [ ] **Step 4: Run persistence and delivery suites GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*ProtectionCoordinatorTest' --tests '*IncidentCloseDispatcherTest' --tests '*IncidentDeliveryCoordinatorTest'
```

Expected: all selected tests pass.

- [ ] **Step 5: Commit Task 4 exact paths**

```powershell
git add -- app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionModels.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentCloseDispatcher.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinatorTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/IncidentCloseDispatcherTest.kt
git commit -m "fix(protection): report persistence health by source"
```

### Task 5: Restore OFFLINE state with correct channel truth

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinatorTest.kt`

**Interfaces:**
- Consumes: source-specific degradation state from Task 4.
- Produces: freshness evaluation based on restored `liveState`, never stale `current.state == OFFLINE`.

- [ ] **Step 1: Add the missing RED branch**

```kotlin
@Test
fun freshServiceRecoveryKeepsArmedStateDegradedWhenTelegramIsStillStale() {
    coordinator.recordServiceHeartbeat(1_000L)
    coordinator.recordTelegramContact(1_000L)
    armWithoutDelay(coordinator)
    coordinator.evaluateFreshness(70_000L)
    coordinator.recordServiceHeartbeat(70_001L)

    coordinator.evaluateFreshness(70_001L)

    assertEquals(ProtectionState.ARMED_DEGRADED, coordinator.snapshot.value.state)
    assertFalse(coordinator.snapshot.value.telegramReachable)
    assertTrue(coordinator.snapshot.value.degradationReasons.any { it.contains("TELEGRAM") })
}
```

- [ ] **Step 2: Run the single test and verify RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*ProtectionCoordinatorTest.freshServiceRecoveryKeepsArmedStateDegradedWhenTelegramIsStillStale'
```

Expected: `ARMED_HEALTHY` or missing Telegram degradation.

- [ ] **Step 3: Calculate degradations from `liveState`**

Resolve `liveState` before channel degradation calculation, then use:

```kotlin
val channelDegradations = if (liveState in ARMED_STATES) {
    telegramDegradationReasons(evaluatedChannels)
} else {
    emptySet()
}
```

Combine channel, sensor, base, and persistence degradations only after `liveState` is known.

- [ ] **Step 4: Run freshness/coordinator suites GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*ProtectionCoordinatorTest' --tests '*ProtectionHealthPolicyTest'
```

Expected: all selected tests pass.

- [ ] **Step 5: Commit Task 5**

```powershell
git add -- app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinatorTest.kt
git commit -m "fix(protection): preserve channel truth after offline"
```

### Task 6: Make microphone readiness reflect actual startup and samples

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntime.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntimeTest.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/RemoteControlReadinessTest.kt`

**Interfaces:**
- Produces: pure `MicrophoneReadiness(hardwareAvailable, permissionGranted).degradations()`.
- Keeps: `PlatformAndroidDetectorSet.start()` as the source of actual listener-start failure.
- Consumes: existing `SensorHealth` states for post-start/fresh-sample truth.

- [ ] **Step 1: Add RED pure-readiness and runtime-health tests**

```kotlin
@Test
fun availablePermittedMicrophoneHasNoPreStartDegradation() {
    assertTrue(MicrophoneReadiness(true, true).degradations().isEmpty())
}

@Test
fun missingMicrophonePermissionRemainsDegraded() {
    assertEquals(
        setOf("RECORD_AUDIO permission unavailable"),
        MicrophoneReadiness(true, false).degradations(),
    )
}
```

Extend the fake detector test so a successful start plus emitted fresh microphone sample returns `HEALTHY`, while a start returning `UNAVAILABLE` remains degraded.

- [ ] **Step 2: Run focused tests and verify RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*RemoteControlReadinessTest*Microphone*' --tests '*AndroidProtectionRuntimeTest*microphone*'
```

Expected: compile failure for `MicrophoneReadiness` or retained pre-start degradation.

- [ ] **Step 3: Implement truthful readiness**

```kotlin
data class MicrophoneReadiness(
    val hardwareAvailable: Boolean,
    val permissionGranted: Boolean,
) {
    fun degradations(): Set<String> = when {
        !hardwareAvailable -> setOf("MICROPHONE unavailable")
        !permissionGranted -> setOf("RECORD_AUDIO permission unavailable")
        else -> emptySet()
    }
}
```

Use this helper in `AndroidRuntimeReadiness.report`. Initialize platform microphone health as `AVAILABLE` when hardware and permission exist. Keep `startOptional` failure as `UNAVAILABLE`; a fresh valid audio observation changes it to `HEALTHY`. Do not retain `MICROPHONE requires user-visible start` anywhere.

- [ ] **Step 4: Run runtime and coordinator suites GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*AndroidProtectionRuntimeTest' --tests '*RemoteControlReadinessTest' --tests '*ProtectionCoordinatorTest'
```

Expected: all selected tests pass.

- [ ] **Step 5: Commit Task 6 exact paths**

```powershell
git add -- app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntime.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntimeTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/RemoteControlReadinessTest.kt
git commit -m "fix(protection): report microphone startup truth"
```

### Task 7: Keep battery percentage out of incident evidence

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentMessageFormatter.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/PowerThermalMonitor.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntimeTest.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/IncidentMessageFormatterTest.kt`

**Interfaces:**
- Keeps diagnostic: `battery_level_percent` for snapshot telemetry.
- Keeps diagnostic: `temperature_celsius` and `charger_disconnected` for incident processing.
- Produces: status-only early return in `AndroidProtectionRuntime.handleObservation` after `sensorSampleRecorder`.

- [ ] **Step 1: Add RED routing and formatter tests**

```kotlin
@Test
fun batteryPercentageUpdatesStatusWithoutOpeningIncident() {
    val recorded = mutableListOf<String?>()
    val incidents = mutableListOf<IncidentObservationBatch>()
    lateinit var detectors: RecordingDetectorSet
    val runtime = runtime(
        processor = powerProcessor(),
        state = ProtectionState.ARMED_HEALTHY,
        sensorSampleRecorder = { _, _, diagnostic, _ -> recorded += diagnostic },
        incidentConsumer = incidents::add,
        detectorCapture = { detectors = it },
    )
    detectors.emit(powerObservation(74.0, "battery_level_percent"))

    assertEquals(listOf("battery_level_percent"), recorded)
    assertTrue(incidents.isEmpty())
}

private fun powerProcessor(): SensorObservationProcessor = SensorObservationProcessor(
    staleAfterMs = 5_000L,
    debounceSamples = mapOf(SensorKind.POWER_THERMAL to 1),
    thresholdDeltas = mapOf(SensorKind.POWER_THERMAL to 0.0),
)

private fun powerObservation(value: Double, diagnostic: String): SensorObservation = SensorObservation(
    kind = SensorKind.POWER_THERMAL,
    eventElapsedMs = 900L,
    wallClockMs = 5_000L,
    normalizedValue = value,
    baselineDelta = 0.0,
    valid = true,
    diagnostic = diagnostic,
)
```

Add formatter assertions that only `temperature_celsius` renders `Temperature:` and an unknown power diagnostic renders a neutral `Power/thermal evidence unavailable` label.

- [ ] **Step 2: Run focused tests and verify RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*AndroidProtectionRuntimeTest.batteryPercentageUpdatesStatusWithoutOpeningIncident' --tests '*IncidentMessageFormatterTest*power*'
```

Expected: battery observation reaches the incident consumer or is formatted as temperature.

- [ ] **Step 3: Implement status-only battery routing**

Immediately after `sensorSampleRecorder`:

```kotlin
if (observation.diagnostic == "battery_level_percent") return
```

Tighten power formatting:

```kotlin
SensorKind.POWER_THERMAL -> when (evidence.diagnostic) {
    "charger_disconnected" -> "Charger disconnected"
    "temperature_celsius" -> "Temperature: %.1f C".format(Locale.US, evidence.normalizedValue)
    else -> "Power/thermal evidence unavailable"
}
```

Retain battery and temperature emissions in `PowerThermalMonitor`; the runtime boundary decides status-only versus incident evidence.

- [ ] **Step 4: Run sensor, processor, formatter, and coordinator suites GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*AndroidProtectionRuntimeTest' --tests '*SensorObservationProcessorTest' --tests '*IncidentMessageFormatterTest' --tests '*ProtectionCoordinatorTest'
```

Expected: all selected tests pass.

- [ ] **Step 5: Commit Task 7 exact paths**

```powershell
git add -- app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentMessageFormatter.kt app/src/main/java/com/example/motorcycleantitheftsensor/sensor/PowerThermalMonitor.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntimeTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/IncidentMessageFormatterTest.kt
git commit -m "fix(protection): separate battery status from incidents"
```

### Task 8: Fresh host gate and scoped residual review

**Files:**
- Create: `.superpowers/sdd/2026-08-11-huawei-pilot-readiness/residual-fix-report.md`
- Create: `.superpowers/sdd/2026-08-11-huawei-pilot-readiness/residual-fix-review.md`
- Modify only if a verification failure identifies a scoped defect: exact Task 1-7 production/test paths.

**Interfaces:**
- Consumes: all Task 1-7 commits.
- Produces: fresh host evidence and a verdict for the two Critical plus four Important residual findings.

- [ ] **Step 1: Run the full low-memory host command from a fresh invocation**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_OPTS='-Xmx256m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' --rerun-tasks :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin
```

Expected: exit code 0. Count XML suites/tests/failures/errors/skips; do not infer counts from Gradle task names.

- [ ] **Step 2: Run diff and secret/logging checks**

```powershell
git diff --check a69f845..HEAD
rg -n "printStackTrace|bot[0-9]+:|api\.telegram\.org/bot[^/ ]+|BEGIN (RSA|EC|OPENSSH) PRIVATE KEY" app/src/main app/src/test
```

Expected: `git diff --check` exits 0 and the credential-shaped scan has no secret-bearing match. Generic test fixtures must not contain production credentials.

- [ ] **Step 3: Review exactly the residual diff**

Review `a69f845..HEAD` against every acceptance item in `docs/superpowers/specs/2026-08-11-huawei-pilot-readiness-residual-fixes-design.md`. Record an explicit verdict for:

- recovery after explicit Disarm;
- stale Armed write after durable Disarm;
- Offline/Telegram truth;
- microphone truth;
- battery/temperature separation;
- source-specific persistence recovery and partial Disarm.

Expected: no unresolved Critical or Important finding before device preparation.

- [ ] **Step 4: Record exact evidence and commit only tracked verification docs if repository convention tracks them**

If `.superpowers/sdd` remains ignored, keep the report durable on disk without force-adding it. If tracked by current repository convention, stage exact report paths only.

### Task 9: Controlled Huawei pilot acceptance

**Files:**
- Create: `.superpowers/sdd/2026-08-11-huawei-pilot-readiness/huawei-acceptance-report.md`
- Update: final HCP checkpoint and `latest.json` through `D:\security\hcp.cmd` after acceptance.

**Interfaces:**
- Consumes: host-reviewed debug APK and the paired-owner Telegram configuration already on the test device.
- Produces: device-specific pilot verdict; never a general production-readiness claim.

- [ ] **Step 1: Stop and obtain explicit device authorization**

Ask the user to connect, wake, and unlock the Huawei device and authorize ADB execution. Do not infer authorization from approval of this implementation plan.

- [ ] **Step 2: Preflight without external delivery**

Run the live ADB device check, identify the exact serial, verify package/application identity, and verify `SEND_SMS` is denied or revoke it with user-authorized device control. Do not print secrets or dump application preferences.

```powershell
$adbExe='C:\Users\ASUS\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$deviceSerial='JUCDU18811013149'
& $adbExe devices -l
& $adbExe -s $deviceSerial shell pm list packages com.example.motorcycleantitheftsensor
& $adbExe -s $deviceSerial shell dumpsys package com.example.motorcycleantitheftsensor | Select-String 'android.permission.SEND_SMS'
& $adbExe -s $deviceSerial shell pm revoke com.example.motorcycleantitheftsensor android.permission.SEND_SMS
```

Expected: the live device list contains exactly the authorized Huawei serial, the package identity matches, and the permission is not granted after the revoke command. If the live serial differs or the device is unauthorized, stop and ask the user rather than substituting another target.

Expected: one authorized Huawei target and `SEND_SMS` not granted.

- [ ] **Step 3: Install the freshly verified APK and record its SHA-256**

Install only the APK produced by Task 8. Record build commit, APK path, hash, device model, Android version, and install result.

```powershell
$adbExe='C:\Users\ASUS\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$deviceSerial='JUCDU18811013149'
$apkPath='D:\security\MotorcycleAntiTheftSensor\app\build\outputs\apk\debug\app-debug.apk'
Get-FileHash -Algorithm SHA256 -LiteralPath $apkPath
git rev-parse HEAD
& $adbExe -s $deviceSerial shell getprop ro.product.model
& $adbExe -s $deviceSerial shell getprop ro.build.version.release
& $adbExe -s $deviceSerial install -r $apkPath
```

Expected: hash and commit are recorded before installation, device identity is Huawei `INE-LX2` unless the live preflight proves a user-approved replacement, and installation exits successfully.

- [ ] **Step 4: Execute the local/recovery matrix**

Verify local Arm, 10-second grace suppression, Disarm during grace, stable Disarmed state, Armed restart, Disarmed restart, controlled process recovery, and Offline-to-online recovery. Capture state labels and focused logs without credentials.

- [ ] **Step 5: Execute paired-owner Telegram matrix**

Verify `/status`, `/arm`, and mandatory-TOTP `/disarm` only from the paired owner. Compare each final Telegram state with UI and notification. Do not expose the OTP in the report.

- [ ] **Step 6: Execute controlled sensor/UI/accessibility matrix**

Verify vibration, light/tamper correlation, charger disconnect, measured temperature, microphone health, battery status, populated Events, notification truth, and TalkBack labels. Confirm battery percentage never appears as temperature evidence.

- [ ] **Step 7: Prove the no-SMS/no-call boundary**

Confirm `SEND_SMS` remained denied, no SMS attempt was logged, no SMS was received, and no call intent/path was invoked. Telegram delivery is allowed only to the paired owner.

- [ ] **Step 8: Create and validate the final checkpoint**

Record completed work, remaining risks, decisions, host evidence, device evidence, artifact hashes, exact commits, and resume commands. Align `.hcp/checkpoints/latest.json`, then run `hcp.cmd resume`, `hcp.cmd audit`, and `hcp.cmd recover -DryRun` when supported by the current wrapper.

```powershell
Set-Location 'D:\security'
.\hcp.cmd checkpoint -Title "Huawei pilot readiness residual fixes and device acceptance"
.\hcp.cmd resume
.\hcp.cmd audit
.\hcp.cmd recover -DryRun
```

Expected: a populated, re-readable checkpoint that labels the result `Huawei pilot ready` only if every blocking matrix item passed.
