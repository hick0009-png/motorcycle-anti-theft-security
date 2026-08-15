# GPS Live Pursuit Final Race and Recovery Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Do not mark a step complete until its stated command and assertion pass.

**Goal:** Close the remaining GPS Live Pursuit recovery and concurrency gaps so an armed cycle survives process restart, stale asynchronous work cannot run after disarm/expiry, and the final remote action for every known Telegram Live Location is a stop.

**Architecture:** Keep `DefaultLivePursuitCoordinator` as the sole owner of pursuit state. Separate durable-anchor validation from fresh incoming-fix validation, fail closed when persisted records disagree, and use two serialization boundaries: the existing state mutex for in-memory state/local tracking transitions and a new remote-operation mutex for ordered Telegram start/update/alert/stop calls. Network calls must never execute while holding the state mutex.

**Tech Stack:** Kotlin, Android coroutines, existing `LocationManager`/OkHttp/encrypted preferences, JUnit4, existing test fakes; no new dependency.

## Current verified baseline

On 2026-08-14 the post-remediation source was reviewed and these commands completed successfully:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest :app:assembleDebug
.\gradlew.bat --no-daemon --max-workers=1 '--rerun-tasks' '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*MovementDisplacementPolicyTest' --tests '*MovementTrackingStoreTest' --tests '*LocationObservationProviderTest' --tests '*TelegramLiveLocationTransportTest' --tests '*TelegramBotClientAuthorizationTest' --tests '*LivePursuitCoordinatorTest' --tests '*LocationLabelResolverTest'
```

Focused GPS result: `64 tests, 0 failures, 0 errors`. This evidence proves the existing tests pass; it does not prove the four missing race/recovery cases below.

## Remaining defects this plan must close

1. A persisted parking anchor is rejected after 30 seconds because recovery calls `isUsableFix()`. Freshness is correct for an incoming sensor fix but incorrect for a durable parking reference that must remain valid for the whole armed cycle.
2. A process that resumes directly into `ARMED_HEALTHY`, `ARMED_DEGRADED`, or `ALERT_ACTIVE` may not have seen `ARMING`; therefore `proposedSessionId` can be `null`. With no durable record, the coordinator then ignores every fix and never creates an anchor.
3. Initial recovery from `ALERT_ACTIVE` is currently a no-op, so tracking and an unexpired persisted Live Location are not restored.
4. `start`, label alert, update, disarm, expiry, and shutdown use independent coroutines. A request captured before invalidation can execute after a remote stop, or an alert can begin after disarm.

## Global constraints

- Work only in `D:\security\MotorcycleAntiTheftSensor`. Read `D:\security\AGENTS.md` first. `AI_WORKFLOW.md` was absent when this plan was written; read it if it exists when execution begins.
- Preserve the dirty worktree. Never run `git reset`, `git clean`, broad `git add .`, broad formatting, or revert unrelated developer changes.
- Do not edit `ProtectionRuntimeGraph.kt`, `SensorService.kt`, Telegram command handling, UI, pairing, TOTP, SMS, ordinary incident delivery, or sensor thresholds unless a RED test in this plan proves that exact edit is required.
- Do not add dependencies or introduce a second coordinator/state owner.
- `/arm` retains one immediate calibration reply. Do not add an `ARMED_HEALTHY` success message.
- No GPS tracking or anchor creation during `ARMING`.
- A movement attempt remains one attempt per armed cycle, including an all-owner Telegram start failure.
- Telegram recipients remain exclusively `EncryptedPrefsManager.getAllowedChatIds()` through `TelegramLiveLocationTransport`; never accept an arbitrary chat ID in the coordinator.
- Native Live Location remains exactly 900 seconds. Updates remain throttled to `>=30s` or `>=20m` from the last published fix.
- `DISARMED_ONLINE` is the only transition that deletes durable anchor/session data. `OFFLINE`, `SETUP_REQUIRED`, and shutdown preserve the fail-closed attempt marker already defined by the previous remediation.
- Incoming fixes require valid coordinates/accuracy and age `<=30_000ms`. Persisted anchors require valid coordinates/accuracy but deliberately do not require freshness.
- State invalidation and local `MovementLocationTracking` mode changes are serialized by the state mutex. Telegram network calls are serialized by the remote-operation mutex. Never hold the state mutex across network or reverse-geocoding I/O.
- After disarm/expiry/shutdown invalidates a generation, no new start/update/alert operation for that generation may begin. If a remote operation already began, allow it to finish, then ensure stop is the final operation for every known handle.
- Preserve exact UTF-8 Thai text: `🚨 ตรวจพบว่ารถหรืออุปกรณ์กำลังถูกเคลื่อนย้าย\nเริ่มติดตามตำแหน่งแบบสดเป็นเวลา 15 นาที`.
- Never log tokens, token-bearing URLs, real chat IDs, real coordinates, TOTP material, or real Telegram message IDs.
- Host tests/build are not real-device acceptance. Report them separately.

## File map

**Modify**

- `app/src/main/java/com/example/motorcycleantitheftsensor/location/MovementDisplacementPolicy.kt` — distinguish structurally valid persisted anchors from fresh incoming fixes.
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinator.kt` — recovery matrix, fallback session ID, eligible-state entry, ordered remote operations, generation checks.
- `app/src/test/java/com/example/motorcycleantitheftsensor/location/MovementDisplacementPolicyTest.kt` — persisted-anchor validation tests.
- `app/src/test/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinatorTest.kt` — recovery and concurrency regression tests plus deterministic fakes.

**Do not create or modify production files outside this map.** The existing transport, scheduler, provider, graph, and service are dependencies to verify, not rewrite targets.

---

### Task 1: Separate persisted-anchor validity from incoming-fix freshness

**Files:**

- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/location/MovementDisplacementPolicy.kt:51`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/location/MovementDisplacementPolicyTest.kt`

**Interfaces:**

```kotlin
fun isValidStoredFix(fix: TrackedLocationFix): Boolean
fun isUsableFix(fix: TrackedLocationFix, nowElapsedMs: Long): Boolean
```

`isValidStoredFix()` validates finite latitude/longitude, legal coordinate ranges, and finite accuracy in `0f..100f`. `isUsableFix()` calls it and adds elapsed-time freshness checks.

- [x] **Step 1: Add RED tests for a durable anchor older than 30 seconds**

Add these tests without weakening any current `isUsableFix()` test:

```kotlin
@Test
fun storedFixMayBeOlderThanIncomingFreshnessWindow() {
    val policy = MovementDisplacementPolicy()
    val fix = TrackedLocationFix(
        latitude = 13.7563,
        longitude = 100.5018,
        elapsedRealtimeMs = 1_000L,
        wallClockMs = 1_000L,
        accuracyMeters = 20f,
    )

    assertTrue(policy.isValidStoredFix(fix))
    assertFalse(policy.isUsableFix(fix, nowElapsedMs = 31_001L))
}

@Test
fun storedFixStillRejectsInvalidCoordinatesAndAccuracy() {
    val policy = MovementDisplacementPolicy()
    val invalidCoordinate = fix(lat = Double.NaN, lon = 100.5018, elapsed = 1_000L, accuracy = 20f)
    val invalidAccuracy = fix(lat = 13.7563, lon = 100.5018, elapsed = 1_000L, accuracy = 101f)

    assertFalse(policy.isValidStoredFix(invalidCoordinate))
    assertFalse(policy.isValidStoredFix(invalidAccuracy))
}
```

Use the test file's existing fixture/factory names if present; do not create a duplicate model.

- [x] **Step 2: Run the two tests and prove RED**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*MovementDisplacementPolicyTest.storedFixMayBeOlderThanIncomingFreshnessWindow' --tests '*MovementDisplacementPolicyTest.storedFixStillRejectsInvalidCoordinatesAndAccuracy'
```

Expected: compilation fails because `isValidStoredFix` does not exist. A test that passes before production implementation is not an acceptable RED result.

- [x] **Step 3: Implement the minimal validation split**

Implement exactly this behavior:

```kotlin
fun isValidStoredFix(fix: TrackedLocationFix): Boolean =
    fix.latitude.isFinite() && fix.latitude in -90.0..90.0 &&
        fix.longitude.isFinite() && fix.longitude in -180.0..180.0 &&
        fix.accuracyMeters.isFinite() &&
        fix.accuracyMeters in 0f..MAX_FIX_ACCURACY_METERS

fun isUsableFix(fix: TrackedLocationFix, nowElapsedMs: Long): Boolean =
    isValidStoredFix(fix) &&
        fix.elapsedRealtimeMs <= nowElapsedMs &&
        nowElapsedMs - fix.elapsedRealtimeMs <= MAX_FIX_AGE_MS
```

Do not use wall-clock age for incoming-fix freshness and do not increase the 30-second limit.

- [x] **Step 4: Run the complete policy test class and prove GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '--rerun-tasks' '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*MovementDisplacementPolicyTest'
```

Expected: all policy tests pass, including future elapsed time, `30_001ms` old, invalid coordinate, and invalid accuracy cases.

- [x] **Step 5: Review and commit only Task 1 files**

```powershell
git diff --check -- app/src/main/java/com/example/motorcycleantitheftsensor/location/MovementDisplacementPolicy.kt app/src/test/java/com/example/motorcycleantitheftsensor/location/MovementDisplacementPolicyTest.kt
git add -- app/src/main/java/com/example/motorcycleantitheftsensor/location/MovementDisplacementPolicy.kt app/src/test/java/com/example/motorcycleantitheftsensor/location/MovementDisplacementPolicyTest.kt
git diff --cached --name-only
git commit -m "fix: preserve valid GPS anchors across recovery"
```

Expected staged list: exactly the two Task 1 files. If the repository workflow does not authorize commits, leave them unstaged and record the verified diff instead.

---

### Task 2: Make process recovery deterministic and fail closed

**Files:**

- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinator.kt:27`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinator.kt:59`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinatorTest.kt:251`

**Interfaces:**

Add one deterministic constructor dependency with a production default:

```kotlin
private val armedSessionIdFactory: () -> String = { UUID.randomUUID().toString() },
```

Define an eligible-state helper in the coordinator file:

```kotlin
private fun ProtectionState.isPursuitEligible(): Boolean =
    this == ProtectionState.ARMED_HEALTHY ||
        this == ProtectionState.ARMED_DEGRADED ||
        this == ProtectionState.ALERT_ACTIVE
```

- [x] **Step 1: Add RED recovery tests**

Add four named tests:

```kotlin
@Test
fun recoveryAcceptsStructurallyValidAnchorOlderThanThirtySeconds()

@Test
fun directArmedRecoveryWithoutDurableRecordsCreatesFallbackSessionAndAnchor()

@Test
fun initialAlertActiveRestoresUnexpiredPursuit()

@Test
fun mismatchedAnchorAndAttemptRecordsFailClosedWithoutStartingAgain()
```

Required assertions:

- `recoveryAccepts...`: persist an anchor whose elapsed time is more than 30 seconds behind `simulatedElapsedClockMs`; enter `ARMED_HEALTHY`; emit two fresh outside fixes separated by 15 seconds; assert exactly one `startForOwners` call and that the stored armed session ID was retained.
- `directArmedRecovery...`: no stored anchor/session, call `ARMED_HEALTHY` with `proposedSessionId = null`, inject `armedSessionIdFactory = { "generated-arm" }`, then submit a usable fix; assert the saved anchor uses `generated-arm`.
- `initialAlertActive...`: persist a structurally valid anchor plus an unexpired session with one handle, make the first state `ALERT_ACTIVE`, and assert armed tracking starts, pursuit mode starts, no second Telegram start occurs, and expiry is scheduled for the remaining duration.
- `mismatched...`: store anchor ID `arm-anchor` and attempted session ID `arm-attempt`; recover, provide movement-confirming fixes, and assert `startForOwners` remains zero. The session attempt is authoritative for deduplication; inconsistent persistence must never unlock a second pursuit.

- [x] **Step 2: Run the four recovery tests and prove RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*LivePursuitCoordinatorTest.recoveryAcceptsStructurallyValidAnchorOlderThanThirtySeconds' --tests '*LivePursuitCoordinatorTest.directArmedRecoveryWithoutDurableRecordsCreatesFallbackSessionAndAnchor' --tests '*LivePursuitCoordinatorTest.initialAlertActiveRestoresUnexpiredPursuit' --tests '*LivePursuitCoordinatorTest.mismatchedAnchorAndAttemptRecordsFailClosedWithoutStartingAgain'
```

Expected: at least one behavioral assertion fails against current production code; record the exact failing test names.

- [x] **Step 3: Implement an explicit recovery matrix under the state mutex**

Replace the nested recovery branch with behavior equivalent to this table:

| Durable state | Active ID | Anchor | Attempted | Handles/recovery action |
|---|---|---|---|---|
| valid anchor, no session | anchor ID | restore | false | start armed tracking |
| valid anchor, matching unexpired session with handles | session ID | restore | true | restore handles, pursuit cadence, remaining expiry |
| valid anchor, matching expired/empty session | session ID | restore | true | no retry; stop any stale handles best-effort |
| session exists, anchor missing/invalid | session ID | wait for new usable anchor | true | no retry |
| anchor/session IDs mismatch | session ID | discard anchor and wait for new usable anchor | true | no retry; fail closed |
| neither record exists | proposed ID or factory ID | wait for first usable fix | false | start armed tracking |

Implementation rules:

```kotlin
val enteringEligibleState = newState.isPursuitEligible() &&
    !previousState.isPursuitEligible()

val storedAnchorValid = storedAnchor?.let {
    displacementPolicy.isValidStoredFix(it.fix)
} == true

val freshSessionId = proposedSessionId ?: armedSessionIdFactory()
```

- Treat an initial `ALERT_ACTIVE` exactly as an eligible recovery entry, while preserving its protection state.
- Do not call `clearAnchorAndSession()` merely because `proposedSessionId` is null.
- Do not evaluate persisted-anchor elapsed freshness.
- If an expired session still contains handles, move those handles into the existing best-effort stop path and persist the empty-handle attempt marker; never start a replacement session.
- Preserve the current initial-`OFFLINE` rule and its regression test.
- Remove the unused local `previousState` variable from `ProtectionRuntimeGraph.kt` only if compilation proves it is necessary; otherwise leave that file untouched.

- [x] **Step 4: Run all coordinator and policy tests**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '--rerun-tasks' '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*MovementDisplacementPolicyTest' --tests '*LivePursuitCoordinatorTest'
```

Expected: all existing and new tests pass; `armingDoesNotStartTracking`, `initialOfflineSnapshotDoesNotEraseRecoveryData`, recovery, expiry, and one-attempt tests remain GREEN.

- [x] **Step 5: Review and commit only Task 2 files**

```powershell
git diff --check -- app/src/main/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinator.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinatorTest.kt
git add -- app/src/main/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinator.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinatorTest.kt
git diff --cached --name-only
git commit -m "fix: recover live pursuit without duplicate attempts"
```

Expected staged list: exactly the coordinator and its test, plus no Task 1 file if Task 1 was already committed.

---

### Task 3: Serialize Telegram operations and close disarm/expiry races

**Files:**

- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinator.kt:37`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinator.kt:47`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinator.kt:189`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinator.kt:266`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinator.kt:321`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinatorTest.kt`

**Interfaces and ordering contract:**

```kotlin
private val stateMutex = Mutex()   // rename the existing mutex
private val remoteMutex = Mutex()  // serializes Telegram calls

private data class UpdatePursuitRequest(
    val handles: List<LiveLocationHandle>,
    val fix: TrackedLocationFix,
    val sid: String,
    val generation: Long,
)
```

Every start/update/alert request carries `sid` and `generation`. Every operation rechecks both after acquiring `remoteMutex` and before it is admitted as an in-flight Telegram operation. An operation admitted immediately before invalidation may finish, but the lifecycle stop must execute after it and remain the final remote operation.

- [x] **Step 1: Upgrade the fakes for deterministic concurrency tests**

Extend `FakeTelegramLiveLocationTransport` with:

```kotlin
val remoteEvents = mutableListOf<String>()
var updateEntered: CompletableDeferred<Unit>? = null
var updateRelease: CompletableDeferred<Boolean>? = null
var alertEntered: CompletableDeferred<Unit>? = null
var alertRelease: CompletableDeferred<Boolean>? = null
```

Record events using synthetic message IDs only:

```kotlin
remoteEvents += "update:${handle.messageId}"
remoteEvents += "alert"
remoteEvents += "stop:${handle.messageId}"
```

Extend `FakeLocationLabelResolver` with `entered`/`release` deferred controls so a test can pause label resolution before any alert call begins. Do not use delay-based race tests.

- [x] **Step 2: Add RED concurrency tests**

Add these named tests:

```kotlin
@Test
fun disarmWhileLabelResolutionIsPendingDoesNotBeginAlert()

@Test
fun disarmDuringInFlightUpdateLeavesStopAsFinalRemoteOperation()

@Test
fun updateQueuedBeforeDisarmButExecutedAfterInvalidationIsSkipped()

@Test
fun expiryDuringInFlightUpdateLeavesStopAsFinalRemoteOperation()
```

Required orchestration and assertions:

- `disarmWhileLabel...`: start pursuit, pause resolver, disarm, release resolver, join all jobs; assert `alerts` is empty and the known handle was stopped once.
- `disarmDuringInFlightUpdate...`: pause inside the first `update`, launch disarm, release update, join; assert the final event for the handle is `stop:<id>` and no event appears after it.
- `updateQueuedBeforeDisarm...`: hold one update in flight, queue a second eligible update, launch disarm so generation is invalidated, release the first; assert only one update reached the transport and stop is last.
- `expiryDuringInFlightUpdate...`: pause update, trigger the fake expiry action, release update; assert pursuit cadence exits once and stop is the final remote event.

- [x] **Step 3: Run the four race tests and prove RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*LivePursuitCoordinatorTest.disarmWhileLabelResolutionIsPendingDoesNotBeginAlert' --tests '*LivePursuitCoordinatorTest.disarmDuringInFlightUpdateLeavesStopAsFinalRemoteOperation' --tests '*LivePursuitCoordinatorTest.updateQueuedBeforeDisarmButExecutedAfterInvalidationIsSkipped' --tests '*LivePursuitCoordinatorTest.expiryDuringInFlightUpdateLeavesStopAsFinalRemoteOperation'
```

Expected: behavioral failures or time-bounded test failure against the current implementation. No test may use `Thread.sleep()`.

- [x] **Step 4: Add state/generation helpers without network I/O**

Use one non-suspending predicate only while holding `stateMutex`:

```kotlin
private fun isCurrentAttemptLocked(sid: String, generation: Long): Boolean =
    currentState.isPursuitEligible() &&
        activeSessionId == sid &&
        attemptGeneration == generation
```

Requirements:

- Invalidate `attemptGeneration` before releasing `stateMutex` on disarm, offline, setup-required, expiry, or shutdown.
- Treat a remote request as admitted/in-flight only after it owns `remoteMutex` and passes the state/generation check. A lifecycle invalidation may race an already-admitted operation, but no request that is still queued may pass its check afterward.
- Perform synchronous local tracking changes (`enterPursuitMode`, `exitPursuitMode`, `stopTracking`) in an order serialized with state invalidation. The provider callback is already outside its provider lock, so do not reintroduce callback-under-lock behavior.
- Never call `transport.*` or `labelResolver.resolve()` while holding `stateMutex`.

- [x] **Step 5: Serialize start and stale-start cleanup**

Implement this ordering:

```text
remoteMutex acquired
  -> stateMutex recheck sid/generation
  -> if stale: return without Telegram start
  -> startForOwners
  -> stateMutex recheck and commit handles/session/expiry/local pursuit mode
  -> if stale: stop every newly returned handle before releasing remoteMutex
remoteMutex released
```

The durable empty-handle attempt must still be saved before initial network I/O. A failed start remains attempted and must not retry.

- [x] **Step 6: Serialize updates and lifecycle stops**

Implement update execution equivalent to:

```kotlin
private suspend fun executePursuitUpdate(request: UpdatePursuitRequest) {
    remoteMutex.withLock {
        val allowed = stateMutex.withLock {
            isCurrentAttemptLocked(request.sid, request.generation) &&
                activeHandles == request.handles
        }
        if (!allowed) return
        request.handles.forEach { handle ->
            transport.update(handle, request.fix)
        }
    }
}
```

Route disarm/expiry/offline/setup-required stop loops through the same `remoteMutex`. This produces one of two valid orders:

```text
update finishes -> stop finishes
```

or:

```text
generation invalidated -> queued update skips -> stop finishes
```

`stop -> update` is forbidden.

- [x] **Step 7: Gate label resolution and owner alert by the active generation**

Resolve the label outside both mutexes. After resolution, acquire `remoteMutex`, then recheck state under `stateMutex`. Call `alertOwners()` only when the request is still current and its handles are still active.

```kotlin
val label = labelResolver.resolve(request.fix)
remoteMutex.withLock {
    val allowed = stateMutex.withLock {
        isCurrentAttemptLocked(request.sid, request.generation) &&
            activeHandles.isNotEmpty()
    }
    if (allowed) {
        transport.alertOwners(formatMovementAlert(label))
    }
}
```

Keep the exact Thai text in a small private formatter/function so the existing exact-text regression test remains readable. Do not send an alert when every owner Live Location start failed.

- [x] **Step 8: Run the complete coordinator test class**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '--rerun-tasks' '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*LivePursuitCoordinatorTest'
```

Expected: all prior and new tests pass. Specifically verify start/disarm, update/disarm, update/expiry, shutdown, alert suppression, exact Thai text, recovery, dedupe, and throttling.

- [x] **Step 9: Review and commit only Task 3 files**

```powershell
git diff --check -- app/src/main/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinator.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinatorTest.kt
git add -- app/src/main/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinator.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinatorTest.kt
git diff --cached --name-only
git commit -m "fix: order live pursuit network lifecycle"
```

---

### Task 4: Host gates, APK evidence, and controlled two-phone acceptance

**Files:**

- Verify only: all Task 1–3 files and existing GPS dependencies.
- Update checkboxes/evidence in this plan only after each gate completes.

- [x] **Step 1: Scan scoped source for forbidden coupling and debug leakage**

```powershell
rg -n "TelegramBotClient|sendLocation|sendLiveLocation|sendSms|SEND_SMS|println\(|System\.out|api\.telegram\.org/bot" app/src/main/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinator.kt app/src/main/java/com/example/motorcycleantitheftsensor/location app/src/test/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinatorTest.kt
```

Expected:

- Coordinator depends only on `TelegramLiveLocationTransport`, not `TelegramBotClient` or raw Telegram URLs.
- No SMS path appears in GPS code.
- No token-bearing URL or debug print was added to scoped tests.

- [x] **Step 2: Run focused GPS tests fresh**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 '--rerun-tasks' '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*MovementDisplacementPolicyTest' --tests '*MovementTrackingStoreTest' --tests '*LocationObservationProviderTest' --tests '*TelegramLiveLocationTransportTest' --tests '*TelegramBotClientAuthorizationTest' --tests '*LivePursuitCoordinatorTest' --tests '*LocationLabelResolverTest'
```

Expected: exit `0`, zero failures/errors. Record the fresh test count from `app/build/test-results/testDebugUnitTest/TEST-*.xml`.

- [x] **Step 3: Run full unit and debug APK gates**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '--rerun-tasks' '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest :app:assembleDebug
Get-FileHash .\app\build\outputs\apk\debug\app-debug.apk -Algorithm SHA256
```

Expected: `BUILD SUCCESSFUL`, zero unit failures/errors, and a recorded SHA-256 for the newly built APK. An SDK XML version warning is non-blocking if build exit is `0`; any compile/test failure is blocking.

- [x] **Step 4: Review the exact dirty scope before installation**

```powershell
git status --short -- app/src/main/java/com/example/motorcycleantitheftsensor/location/MovementDisplacementPolicy.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinator.kt app/src/test/java/com/example/motorcycleantitheftsensor/location/MovementDisplacementPolicyTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinatorTest.kt
git diff --check -- app/src/main/java/com/example/motorcycleantitheftsensor/location/MovementDisplacementPolicy.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinator.kt app/src/test/java/com/example/motorcycleantitheftsensor/location/MovementDisplacementPolicyTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinatorTest.kt
```

Expected: only intentional GPS files; zero whitespace errors in these files. Do not clean unrelated repository changes.

- [ ] **Step 5: Install only after the owner identifies the target phone and approves replacement**

```powershell
$adb='C:\Users\ASUS\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb devices -l
& $adb -s '<approved-device-serial>' install -r '.\app\build\outputs\apk\debug\app-debug.apk'
```

Expected: exactly one approved target serial and `Success`. Replace `<approved-device-serial>` with the serial shown and approved by the owner; never guess it and never uninstall or clear app data.

- [ ] **Step 6: Execute the two-phone acceptance matrix**

Use the protected phone plus the paired owner's Telegram phone. Do not capture screenshots/logs containing real coordinates, chat IDs, tokens, or TOTP values.

| Scenario | Action | Required result |
|---|---|---|
| Calibration isolation | Send `/arm` and observe the first 10 seconds | One immediate calibration reply; no GPS tracking, anchor, Live Location, or extra armed-success reply |
| Ordinary vibration | Shake/tap while the protected phone stays within the parking area | Existing incident behavior only; no GPS map and no Live Pursuit |
| Confirmed displacement | Move beyond the configured displacement and obtain two usable outside fixes at least 15 seconds apart | Exactly one owner-only native Live Location plus one readable Thai movement alert |
| Update throttle | Continue moving during the pursuit | Same Telegram message updates; no duplicate Live Location; updates obey `>=30s` or `>=20m` |
| Disarm race | Send `/disarm` while movement/update is active | Local tracking stops, native Live Location stops, and no later update revives it |
| Old-anchor recovery | Arm, obtain an anchor, wait over 30 seconds, restart the app process without clearing data, then move | Existing anchor remains usable and exactly one pursuit can start |
| Active-session recovery | Restart the app process during a Live Location and reopen the app without clearing data | Existing handles resume updating; no second Live Location; expiry uses remaining time |
| Initial alert recovery | Restart while persisted protection state is `ALERT_ACTIVE` | Tracking/session recovery occurs without a duplicate attempt |
| Expiry | Let the 15-minute session finish | Native Live Location is stopped, pursuit cadence exits, no automatic second attempt in the same armed cycle |
| New armed cycle | Disarm, then arm again and confirm movement | Exactly one new pursuit attempt is allowed for the new cycle |

- [ ] **Step 7: Record evidence without overclaiming**

Report separately:

```text
Host verification: command, exit code, test count, failure/error count, APK SHA-256
Device verification: APK SHA-256 installed, device model/serial redacted, each scenario PASS/FAIL/NOT RUN
Remaining risk: OEM background restrictions, GPS availability/accuracy, Telegram connectivity
```

Do not report the work complete if any required device scenario is `FAIL` or `NOT RUN`. A host-only completion statement must explicitly say device acceptance remains pending.

## Final acceptance criteria

- A valid persisted anchor remains valid beyond 30 seconds and across process restart, while incoming stale fixes remain rejected.
- Direct recovery into `ARMED_HEALTHY`, `ARMED_DEGRADED`, or `ALERT_ACTIVE` always has a non-null armed-cycle ID.
- Persisted record mismatch fails closed and cannot create a duplicate Telegram Live Location.
- No queued Telegram start/update/alert is admitted after its generation is invalidated. An operation admitted just before invalidation may finish only if the lifecycle stop is guaranteed to run afterward.
- If disarm/expiry races an already-running Telegram operation, stop is the final remote operation for every known handle.
- No network/reverse-geocoder call executes under the state mutex.
- `ARMING`, ordinary vibration, and ordinary incidents never start Live Pursuit or attach GPS.
- All focused and full host gates pass from fresh execution.
- A newly built APK is installed and every required two-phone scenario passes before claiming production readiness.
