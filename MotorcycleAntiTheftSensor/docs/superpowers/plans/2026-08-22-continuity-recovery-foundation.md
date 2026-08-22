# Continuity Recovery Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make protection recovery truthful and deterministic across process recreation, Android boot, package replacement, and watchdog assistance before adding profile-specific UI or delivery features.

**Architecture:** Add a small pure continuity policy beside the existing `ProtectionRecoveryPolicy`, persist its owner intent atomically with the protection recovery snapshot, and make service/receiver startup consult it before detectors, heartbeat, or Telegram status work. `ProtectionCoordinator` remains the sole state owner; continuity only decides whether recovery is eligible and how its phase projects to the existing state model.

**Tech Stack:** Kotlin, coroutines, Android foreground service/receivers, SharedPreferences persistence, JUnit 4, Mockito-Kotlin, Gradle Android unit tests.

**Spec:** `MotorcycleAntiTheftSensor/docs/superpowers/specs/2026-08-22-mobile-only-three-protection-profiles-design.md`

## Global Constraints

- Preserve `ProtectionCoordinator` as the authoritative owner of `ProtectionSnapshot` and existing top-level `ProtectionState` values.
- `STOPPED_BY_OWNER` implies `DISARMED`; it wins over boot, sticky restart, package replacement, watchdog, and late callbacks.
- Only `RUNNING + ARMED + autoRecoveryAfterBoot=true` restores detectors after Android boot. Same-boot process/package recovery remains eligible while boot recovery is off.
- Never migrate Telegram, TOTP, SMS, allowlist, or other secrets to device-protected storage. Phase 1 has no Direct Boot support.
- A recovery phase is orthogonal metadata. `RECOVERY_BLOCKED` projects through existing `SETUP_REQUIRED`, never a new top-level protection state.
- No production code is written before the named RED test fails for the stated missing behavior.

---

## File Structure

| File | Responsibility |
|---|---|
| `protection/ProtectionContinuity.kt` | Pure persisted owner intent, recovery trigger/phase, and eligibility policy. |
| `protection/ProtectionSnapshotStore.kt` | Atomically store and load continuity intent with existing recovery hints. |
| `protection/ProtectionRecoveryPolicy.kt` | Convert legacy snapshot state plus continuity decision into existing `ProtectionState` and detector restart behavior. |
| `service/SensorServiceController.kt` | Persist explicit owner Stop/Disarm intent before service teardown; seed continuity recovery without changing state ownership. |
| `service/BootCompletedReceiver.kt` | Deliver only trusted boot/package-replacement recovery triggers. |
| `service/AlarmWatchdogReceiver.kt` | Treat watchdog as best effort and suppress it for owner-stopped intent. |
| `telegram/HeartbeatPinger.kt` | Delay first autonomous heartbeat until a committed terminal recovery outcome. |

## Task 1: Pure continuity intent and recovery eligibility

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionContinuity.kt`
- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionContinuityPolicyTest.kt`

**Interfaces:**

```kotlin
enum class DesiredService { RUNNING, STOPPED_BY_OWNER }
enum class DesiredProtection { DISARMED, ARMED }
enum class RecoveryTrigger { PROCESS_RECREATION, ANDROID_BOOT, PACKAGE_REPLACED, WATCHDOG, MANUAL_REOPEN }
enum class RecoveryPhase { RECOVERY_PENDING, RECALIBRATING, RECOVERED_HEALTHY, RECOVERED_DEGRADED, RECOVERY_BLOCKED, NOT_RECOVERED_OWNER_STOPPED, NOT_RECOVERED_BOOT_DISABLED }

data class ProtectionContinuityIntent(
    val desiredService: DesiredService,
    val desiredProtection: DesiredProtection,
    val autoRecoveryAfterBoot: Boolean,
    val armedSessionId: String? = null,
)

data class RecoveryEligibility(
    val phase: RecoveryPhase,
    val restartDetectors: Boolean,
    val resultingDesiredProtection: DesiredProtection,
)

object ProtectionContinuityPolicy {
    fun eligibility(intent: ProtectionContinuityIntent, trigger: RecoveryTrigger): RecoveryEligibility
}
```

- [ ] **Step 1: Write the failing policy tests**

```kotlin
@Test
fun ownerStoppedIntentNeverRestartsDetectors() {
    val result = ProtectionContinuityPolicy.eligibility(
        ProtectionContinuityIntent(DesiredService.STOPPED_BY_OWNER, DesiredProtection.DISARMED, true),
        RecoveryTrigger.ANDROID_BOOT,
    )

    assertFalse(result.restartDetectors)
    assertEquals(RecoveryPhase.NOT_RECOVERED_OWNER_STOPPED, result.phase)
}

@Test
fun disabledBootRecoveryDoesNotRestoreDetectorsAfterAndroidBoot() {
    val result = ProtectionContinuityPolicy.eligibility(
        ProtectionContinuityIntent(DesiredService.RUNNING, DesiredProtection.ARMED, false, "session-1"),
        RecoveryTrigger.ANDROID_BOOT,
    )

    assertFalse(result.restartDetectors)
    assertEquals(DesiredProtection.DISARMED, result.resultingDesiredProtection)
    assertEquals(RecoveryPhase.NOT_RECOVERED_BOOT_DISABLED, result.phase)
}

@Test
fun disabledBootRecoveryStillAllowsSameBootProcessRecovery() {
    val result = ProtectionContinuityPolicy.eligibility(
        ProtectionContinuityIntent(DesiredService.RUNNING, DesiredProtection.ARMED, false, "session-1"),
        RecoveryTrigger.PROCESS_RECREATION,
    )

    assertTrue(result.restartDetectors)
    assertEquals(RecoveryPhase.RECOVERY_PENDING, result.phase)
}
```

- [ ] **Step 2: Run RED tests**

Run: `set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr && set ANDROID_HOME=C:\Users\ASUS\AppData\Local\Android\Sdk && gradlew.bat testDebugUnitTest --tests "*ProtectionContinuityPolicyTest"`

Expected: compilation failure because `ProtectionContinuityPolicy` and its types do not exist.

- [ ] **Step 3: Implement the pure policy**

Implement the interfaces above with these exact decisions:

```kotlin
if (intent.desiredService == DesiredService.STOPPED_BY_OWNER)
    RecoveryEligibility(RecoveryPhase.NOT_RECOVERED_OWNER_STOPPED, false, DesiredProtection.DISARMED)
else if (intent.desiredProtection == DesiredProtection.DISARMED)
    RecoveryEligibility(RecoveryPhase.RECOVERY_BLOCKED, false, DesiredProtection.DISARMED)
else if (trigger == RecoveryTrigger.ANDROID_BOOT && !intent.autoRecoveryAfterBoot)
    RecoveryEligibility(RecoveryPhase.NOT_RECOVERED_BOOT_DISABLED, false, DesiredProtection.DISARMED)
else RecoveryEligibility(RecoveryPhase.RECOVERY_PENDING, true, DesiredProtection.ARMED)
```

- [ ] **Step 4: Run GREEN tests**

Run the command from Step 2.

Expected: all three tests pass.

- [ ] **Step 5: Commit the isolated policy**

```bash
git add app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionContinuity.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionContinuityPolicyTest.kt
git commit -m "feat: add protection continuity policy"
```

## Task 2: Persist and validate continuity intent atomically

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionSnapshotStore.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionSnapshotStoreTest.kt`

**Interfaces:**

```kotlin
data class ProtectionRecoveryState(
    val liveSnapshot: ProtectionSnapshot,
    val hints: ProtectionRecoveryHints,
    val continuityIntent: ProtectionContinuityIntent,
    val continuityValid: Boolean,
)

fun save(
    snapshot: ProtectionSnapshot,
    lastServiceHeartbeatAtMs: Long?,
    continuityIntent: ProtectionContinuityIntent,
)
```

- [ ] **Step 1: Write failing persistence tests**

```kotlin
@Test
fun recoveryLoadKeepsOwnerStoppedIntentAcrossProcessDeath() {
    store.save(snapshot, null, ProtectionContinuityIntent(
        DesiredService.STOPPED_BY_OWNER, DesiredProtection.DISARMED, true,
    ))

    assertEquals(DesiredService.STOPPED_BY_OWNER, store.loadForRecovery().continuityIntent.desiredService)
    assertTrue(store.loadForRecovery().continuityValid)
}

@Test
fun malformedContinuityEnumIsInvalidInsteadOfArmed() {
    preferences.put(mapOf("continuity_desired_service" to "NOT_A_STATE"))

    val recovery = store.loadForRecovery()

    assertFalse(recovery.continuityValid)
    assertEquals(DesiredProtection.DISARMED, recovery.continuityIntent.desiredProtection)
}
```

- [ ] **Step 2: Run RED tests**

Run: `gradlew.bat testDebugUnitTest --tests "*ProtectionSnapshotStoreTest"`

Expected: compilation failure because continuity fields and the three-argument `save` overload do not exist.

- [ ] **Step 3: Implement one atomic preference write**

Add keys for desired service, desired protection, boot switch, and armed session ID to the same `preferences.put(...)` call that persists the snapshot. Parse enum values with `runCatching`; malformed/missing new values load as `RUNNING + DISARMED`, `continuityValid=false`, and must never infer an armed session. Keep the existing two-argument `save` only as a compatibility wrapper that writes `RUNNING + DISARMED + autoRecoveryAfterBoot=false`.

- [ ] **Step 4: Run GREEN tests**

Run the command from Step 2.

Expected: snapshot round-trip tests and the new safety tests pass.

- [ ] **Step 5: Commit persistence**

```bash
git add app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionSnapshotStore.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionSnapshotStoreTest.kt
git commit -m "feat: persist protection continuity intent"
```

## Task 3: Integrate continuity with recovery planning and coordinator lifecycle

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRecoveryPolicy.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionRecoveryPolicyTest.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinatorTest.kt`

**Interfaces:**

```kotlin
fun ProtectionRecoveryPolicy.plan(
    persistedState: ProtectionState,
    intent: ProtectionContinuityIntent,
    trigger: RecoveryTrigger,
    continuityValid: Boolean,
): RecoveryPlan

fun ProtectionCoordinator.captureRecoveryToken(): RecoveryGenerationToken
```

- [ ] **Step 1: Write failing integration tests**

```kotlin
@Test
fun invalidContinuityProjectsToSetupRequiredWithoutRestartingDetectors() {
    val plan = ProtectionRecoveryPolicy.plan(
        ProtectionState.ARMED_HEALTHY,
        ProtectionContinuityIntent(DesiredService.RUNNING, DesiredProtection.ARMED, true, "session-1"),
        RecoveryTrigger.ANDROID_BOOT,
        continuityValid = false,
    )

    assertEquals(ProtectionState.SETUP_REQUIRED, plan.initialState)
    assertFalse(plan.restartDetectors)
}

@Test
fun ownerDisarmSupersedesCapturedRecoveryBeforeRecoveryArms() = runTest {
    val token = coordinator.captureRecoveryToken()
    coordinator.disarm("owner-disarm", CommandOrigin.LOCAL)

    val recovery = coordinator.arm("recovery", CommandOrigin.RECOVERY, token)

    assertEquals(CommandOutcome.REJECTED, recovery.outcome)
    assertEquals(ProtectionState.DISARMED_ONLINE, coordinator.snapshot.value.state)
}
```

- [ ] **Step 2: Run RED tests**

Run: `gradlew.bat testDebugUnitTest --tests "*ProtectionRecoveryPolicyTest" --tests "*ProtectionCoordinatorTest"`

Expected: first test does not compile until the overload exists; the coordinator test remains a regression guard for owner precedence.

- [ ] **Step 3: Implement recovery projection**

Make `ProtectionRecoveryPolicy` call `ProtectionContinuityPolicy.eligibility`. Invalid continuity returns `SETUP_REQUIRED` with `restartDetectors=false`. A disabled Android boot returns `DISARMED_ONLINE` with `restartDetectors=false`; process/package recovery of a valid armed intent returns `ARMING` with `restartDetectors=true`. Do not add a new `ProtectionState`. Retain the existing coordinator recovery token fencing and ensure all explicit Arm/Disarm calls invalidate recovery before detector work.

- [ ] **Step 4: Run GREEN tests**

Run the command from Step 2.

Expected: policy and coordinator tests pass, including existing recovery-race tests.

- [ ] **Step 5: Commit recovery integration**

```bash
git add app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRecoveryPolicy.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionRecoveryPolicyTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinatorTest.kt
git commit -m "feat: gate protection recovery by owner intent"
```

## Task 4: Make service, boot, and watchdog honor durable owner intent

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorServiceController.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/service/BootCompletedReceiver.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/service/AlarmWatchdogReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/service/BootRecoveryTriggerPolicyTest.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/service/AlarmWatchdogReceiverTest.kt`

**Interfaces:**

```kotlin
enum class ServiceStartTrigger { NORMAL_START, STICKY_RESTART, ANDROID_BOOT, PACKAGE_REPLACED, WATCHDOG }

fun shouldStartForTrigger(
    trigger: ServiceStartTrigger,
    intent: ProtectionContinuityIntent,
): Boolean
```

- [ ] **Step 1: Write failing receiver/watchdog tests**

```kotlin
@Test
fun ownerStoppedIntentSuppressesWatchdogServiceStart() {
    assertFalse(shouldStartForTrigger(
        ServiceStartTrigger.WATCHDOG,
        ProtectionContinuityIntent(DesiredService.STOPPED_BY_OWNER, DesiredProtection.DISARMED, true),
    ))
}

@Test
fun packageReplacementIsAcceptedButQuickBootIsNotTrusted() {
    assertTrue(BootRecoveryTriggerPolicy.isSupported(Intent.ACTION_MY_PACKAGE_REPLACED))
    assertFalse(BootRecoveryTriggerPolicy.isSupported("android.intent.action.QUICKBOOT_POWERON"))
}
```

- [ ] **Step 2: Run RED tests**

Run: `gradlew.bat testDebugUnitTest --tests "*BootRecoveryTriggerPolicyTest" --tests "*AlarmWatchdogReceiverTest"`

Expected: compilation failure because trigger policy and `MY_PACKAGE_REPLACED` handling do not exist.

- [ ] **Step 3: Implement receiver boundaries**

Create a pure `BootRecoveryTriggerPolicy` in the service package. Accept only `Intent.ACTION_BOOT_COMPLETED` and `Intent.ACTION_MY_PACKAGE_REPLACED`; remove vendor quick-boot equivalence. Pass an explicit trigger action into `SensorService`. Before watchdog reschedules or starts the service, load continuity intent and return for `STOPPED_BY_OWNER`. Seed watchdog scheduling from normal successful service startup, cancel it synchronously after durable owner Stop, and record scheduler failure as degraded health rather than claiming a reliable resurrection guarantee. Do not add exact-alarm permission solely for this feature.

- [ ] **Step 4: Run GREEN tests**

Run the command from Step 2 and then `gradlew.bat testDebugUnitTest --tests "*SensorServiceControllerTest"`.

Expected: owner Stop remains stopped, supported boot triggers are deterministic, and existing service command semantics stay green.

- [ ] **Step 5: Commit service recovery boundaries**

```bash
git add app/src/main/java/com/example/motorcycleantitheftsensor/service app/src/main/AndroidManifest.xml app/src/test/java/com/example/motorcycleantitheftsensor/service
git commit -m "feat: honor owner intent during service recovery"
```

## Task 5: Gate autonomous heartbeat behind terminal recovery

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/HeartbeatPinger.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/HeartbeatPingerTest.kt`

**Interfaces:**

```kotlin
interface HeartbeatScheduler {
    fun schedule(initialDelayMinutes: Long, periodMinutes: Long, task: () -> Unit)
    fun cancel()
}

fun startHeartbeatAfterRecovery()
```

- [ ] **Step 1: Write failing heartbeat tests**

```kotlin
@Test
fun firstHeartbeatWaitsOneFullIntervalAfterRecovery() {
    val scheduler = RecordingHeartbeatScheduler()
    val pinger = HeartbeatPinger(mockContext, mockPrefs, mockTelegram, scheduler)

    pinger.startHeartbeatAfterRecovery()

    assertEquals(15L, scheduler.initialDelayMinutes)
    assertEquals(15L, scheduler.periodMinutes)
}

@Test
fun heartbeatDoesNotStartBeforeRecoveryTerminalState() {
    val scheduler = RecordingHeartbeatScheduler()
    val pinger = HeartbeatPinger(mockContext, mockPrefs, mockTelegram, scheduler)

    pinger.markRecoveryPending()

    assertFalse(pinger.startHeartbeatAfterRecovery())
}
```

- [ ] **Step 2: Run RED tests**

Run: `gradlew.bat testDebugUnitTest --tests "*HeartbeatPingerTest"`

Expected: compilation failure because scheduler injection and recovery-gated methods do not exist.

- [ ] **Step 3: Implement delayed autonomous heartbeat**

Introduce the scheduler interface with a production `ScheduledExecutorService` adapter. Preserve idempotent `startHeartbeat`/`stopHeartbeat` behavior for callers that are already terminal; add a recovery-pending flag so service startup calls `markRecoveryPending()` before recovery and `startHeartbeatAfterRecovery()` only after a terminal result is committed. Schedule first send after exactly one configured 15-minute interval, not at zero delay. `/status` remains in its command path and does not invoke the heartbeat scheduler.

- [ ] **Step 4: Run GREEN tests**

Run the command from Step 2 and then `gradlew.bat testDebugUnitTest --tests "*TelegramCommandHandlerTest" --tests "*ProtectionStatusTruthPathIntegrationTest"`.

Expected: heartbeat timing is deterministic and command status remains one response.

- [ ] **Step 5: Commit heartbeat gate**

```bash
git add app/src/main/java/com/example/motorcycleantitheftsensor/telegram/HeartbeatPinger.kt app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt app/src/test/java/com/example/motorcycleantitheftsensor/telegram/HeartbeatPingerTest.kt
git commit -m "fix: delay heartbeat until recovery completes"
```

## Verification after Phase 1

- [ ] Run `gradlew.bat testDebugUnitTest` with `JAVA_HOME` set to Android Studio JBR and `ANDROID_HOME` set to the installed SDK.
- [ ] Run `gradlew.bat assembleDebug`.
- [ ] Inspect `git diff --check`, then run `git status --short` and confirm only Phase 1 files are present before each commit.
- [ ] On Huawei, arm with boot recovery on and no PIN, reboot, and verify one recovery status with no immediate heartbeat; repeat with boot recovery off and verify sensors do not resume automatically.
- [ ] On Huawei, issue owner Stop, wait beyond the watchdog interval, and verify the service/detectors do not restart.

## Follow-on implementation boundaries

The approved spec still requires a durable per-chat recovery outbox, no-PIN readiness UI, the three profile resolver/detectors, and the paper-light UI. Those are intentionally not coupled into Phase 1 because they depend on the owner-intent and recovery boundary above; each will receive a separate TDD plan after this phase has host and device evidence.
