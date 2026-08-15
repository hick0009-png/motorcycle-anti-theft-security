# GPS Live Pursuit Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Repair the installed GPS/Telegram Live Pursuit implementation so confirmed displacement starts at most one 15-minute owner-only Telegram Live Location session, survives expected lifecycle transitions, and never attaches GPS to ordinary vibration alerts.

**Architecture:** Keep `ProtectionCoordinator` as the authoritative protection-state owner. Feed state transitions and location fixes into one serialized `DefaultLivePursuitCoordinator`; use `MovementDisplacementPolicy` only for displacement decisions, a minimal encrypted record for dedupe/recovery, and a typed owner-only Telegram transport for native Live Location. GPS tracking begins only after protection reaches `ARMED_HEALTHY` or `ARMED_DEGRADED`; ordinary incidents remain independent.

**Tech Stack:** Android/Kotlin, coroutines, `LocationManager`, encrypted preferences already used by the app, pinned OkHttp Telegram transport, JUnit4, Mockito only where Kotlin callback matchers are not involved.

## Global Constraints

- This is a repair of the current dirty checkout. Do not reset, clean, revert, broadly stage, or overwrite unrelated developer changes.
- Read `D:\security\AGENTS.md` before editing. `AI_WORKFLOW.md` was not present during plan creation; do not invent its contents. If it appears, read it before production edits.
- Do not add a location, Telegram, Places, maps, or geocoding dependency.
- Do not change `/arm`, `/disarm <TOTP>`, pairing, TOTP, SMS, or incident-delivery authorization semantics.
- GPS data may go only to paired owner chat IDs through Telegram. Never add coordinates, an address, a Maps URL, or tracking state to SMS.
- Vibration/light/audio/power incidents must not start Live Pursuit and must not gain GPS payloads.
- No tracking or parking anchor may start during the 10-second `ARMING` calibration period.
- One armed cycle permits at most one pursuit attempt, including when all Telegram `sendLocation` requests fail.
- Live Location lasts 900 seconds. Later fixes edit the same Telegram message; they never create a second map message in the same armed cycle.
- Do not log bot tokens, token-bearing URLs, real chat IDs, TOTP material, raw real-world coordinates, parking anchors, or Telegram message IDs.
- Preserve UTF-8 Thai text. Do not copy the mojibake currently visible in parts of `IncidentMessageFormatter.kt`.
- Host tests/builds are not real-device proof. Report device acceptance separately.
- Use synthetic coordinates in tests, for example `(0.0, 0.0)` and `(0.002, 0.0)`.
- Run Gradle/ADB sequentially with Android Studio JBR and one worker.

## Current defects this plan must close

1. `LivePursuitCoordinator.onFix()` calls `displacementPolicy.reset(anchor)` before every evaluation, so consecutive outside fixes can never confirm movement.
2. Tracking and the parking anchor currently start in `ARMING`, before calibration finishes.
3. The coordinator can launch more than one `sendLocation` batch before the asynchronous session save completes.
4. Expiry is only stored as a timestamp; nothing schedules stop, calls `stopMessageLiveLocation`, or returns to armed cadence.
5. Recovery calls `enterPursuitMode()` while tracking is still stopped and does not restore a coherent session/anchor state.
6. `onMovementIncident` converts movement into a `SensorObservation`, but `IncidentEngine` cannot open an incident from `LOCATION`; this route is ineffective and risks coupling GPS to ordinary incidents.
7. Location callbacks run while the provider lock is held, stale callbacks can overwrite newer fixes, and coordinate/NaN validation is incomplete.
8. `LocationObservationProviderTest` currently fails before exercising behavior because Mockito's Kotlin function matcher produces null/NPE.
9. There is no `stopMessageLiveLocation` transport.
10. Synchronous reverse geocoding inside `IncidentMessageFormatter` is on the ordinary incident path and should not be there.

## File map

**Create**

- `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramLiveLocationTransport.kt` — owner-only typed start/update/stop boundary.
- `app/src/main/java/com/example/motorcycleantitheftsensor/location/LocationLabelResolver.kt` — best-effort Android Geocoder boundary used only by pursuit start.
- `app/src/test/java/com/example/motorcycleantitheftsensor/location/LocationLabelResolverTest.kt` — label selection, timeout, and failure tests.

**Modify**

- `app/src/main/java/com/example/motorcycleantitheftsensor/location/MovementDisplacementPolicy.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/location/MovementTrackingStore.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/LocationObservationProvider.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinator.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentMessageFormatter.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClient.kt`
- `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramLiveLocationTransportTest.kt` — extend the current transport test with owner authorization, payload, and failure cases.
- `app/src/main/AndroidManifest.xml` and the existing permission UI/policy only if Task 7 proves background permission is missing.
- Corresponding existing unit tests listed in each task.

---

### Task 0: Freeze the dirty-tree baseline and repair the GPS test harness

**Files:**

- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/LocationObservationProviderTest.kt`
- Inspect only: all current modified/untracked files from `git status --short`

**Interfaces:**

- Produces a deterministic `FakeLocationUpdatesClient` that captures callbacks without Mockito nullable matcher failures.

- [ ] **Step 1: Record the scoped baseline without changing it**

```powershell
git status --short
git diff -- app/src/main/java/com/example/motorcycleantitheftsensor/location app/src/main/java/com/example/motorcycleantitheftsensor/sensor/LocationObservationProvider.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinator.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClient.kt app/src/main/AndroidManifest.xml
```

Save the command output outside tracked source or quote it in the handoff. Do not stage anything yet.

- [ ] **Step 2: Reproduce the current focused result**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests 'com.example.motorcycleantitheftsensor.location.*' --tests 'com.example.motorcycleantitheftsensor.sensor.LocationObservationProviderTest' --tests 'com.example.motorcycleantitheftsensor.protection.LivePursuitCoordinatorTest' --tests 'com.example.motorcycleantitheftsensor.telegram.TelegramLiveLocationTransportTest'
```

Expected baseline from review: 16 tests execute and the four `LocationObservationProviderTest` cases fail from Mockito matcher/NPE. If the result differs, record the fresh result and use it as authoritative.

- [ ] **Step 3: Replace callback Mockito matchers with a fake**

Use this test-local shape; do not alter production code merely to accommodate Mockito:

```kotlin
private class FakeLocationUpdatesClient : LocationUpdatesClient {
    data class Registration(val minTimeMs: Long, val minDistanceMeters: Float)

    val registrations = mutableListOf<Registration>()
    var callback: ((TrackedLocationFix) -> Unit)? = null
    var fixes: List<TrackedLocationFix> = emptyList()
    var startResult = true
    var stopCalls = 0

    override fun start(
        minTimeMs: Long,
        minDistanceMeters: Float,
        onFix: (TrackedLocationFix) -> Unit,
    ): Boolean {
        registrations += Registration(minTimeMs, minDistanceMeters)
        callback = onFix
        return startResult
    }

    override fun stop() {
        stopCalls++
        callback = null
    }

    override fun lastKnownFixes(): List<TrackedLocationFix> = fixes

    fun emit(fix: TrackedLocationFix) {
        callback?.invoke(fix)
    }
}
```

- [ ] **Step 4: Run only the repaired test harness**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*LocationObservationProviderTest'
```

Expected: existing intended cases execute without matcher/NPE. Behavioral failures are acceptable RED evidence for Task 2; infrastructure failures are not.

- [ ] **Step 5: Commit only if the user/branch workflow authorizes commits**

```powershell
git add app/src/test/java/com/example/motorcycleantitheftsensor/sensor/LocationObservationProviderTest.kt
git commit -m "test: make location provider tests deterministic"
```

If commit authorization is absent, leave the scoped change unstaged and continue.

### Task 1: Make displacement confirmation stateful and validate every fix

**Files:**

- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/location/MovementDisplacementPolicy.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/location/MovementDisplacementPolicyTest.kt`

**Interfaces:**

- Consumes: `ParkingAnchor`, `TrackedLocationFix`.
- Produces: unchanged `MovementDecision` API; no coordinator or Telegram calls.

- [ ] **Step 1: Add RED tests for the real coordinator failure**

Add these cases using an anchor at `(0.0, 0.0)`:

```kotlin
@Test
fun twoOutsideFixesSeparatedByFifteenSecondsConfirmWithoutReset() {
    val policy = MovementDisplacementPolicy()
    policy.reset(ParkingAnchor(fix(0.0, 0.0, 1_000L, 5f), "armed-1"))

    assertTrue(policy.evaluate(fix(0.002, 0.0, 2_000L, 5f), 2_000L) is MovementDecision.Candidate)
    assertTrue(policy.evaluate(fix(0.002, 0.0, 17_000L, 5f), 17_000L) is MovementDecision.Confirmed)
}

@Test
fun unusableFixBetweenCandidatesDoesNotEraseCandidate() {
    val policy = MovementDisplacementPolicy()
    policy.reset(ParkingAnchor(fix(0.0, 0.0, 1_000L, 5f), "armed-1"))

    policy.evaluate(fix(0.002, 0.0, 2_000L, 5f), 2_000L)
    policy.evaluate(fix(Double.NaN, 0.0, 10_000L, 5f), 10_000L)

    assertTrue(policy.evaluate(fix(0.002, 0.0, 17_000L, 5f), 17_000L) is MovementDecision.Confirmed)
}
```

Also test latitude outside `-90.0..90.0`, longitude outside `-180.0..180.0`, `Float.NaN` accuracy, negative accuracy, future elapsed time, and stale fixes. None may increment or erase a valid candidate.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*MovementDisplacementPolicyTest'
```

- [ ] **Step 3: Implement minimal validation and candidate retention**

Add one private predicate and call it before distance calculation:

```kotlin
private fun isUsable(fix: TrackedLocationFix, nowElapsedMs: Long): Boolean =
    fix.latitude.isFinite() && fix.latitude in -90.0..90.0 &&
        fix.longitude.isFinite() && fix.longitude in -180.0..180.0 &&
        fix.accuracyMeters.isFinite() && fix.accuracyMeters in 0f..MAX_FIX_ACCURACY_METERS &&
        fix.elapsedRealtimeMs <= nowElapsedMs &&
        nowElapsedMs - fix.elapsedRealtimeMs <= MAX_FIX_AGE_MS
```

An unusable fix returns `AwaitingUsableFix` or the prior candidate view but must not mutate `outsideCandidateTimestampMs` or `outsideFixCount`. `reset(anchor)` is reserved for a new/restored anchor; the coordinator must not call it per fix.

- [ ] **Step 4: Run GREEN**

Run the Task 1 command again. Expected: all policy cases pass.

### Task 2: Make `LocationObservationProvider` lifecycle-safe

**Files:**

- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/LocationObservationProvider.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/LocationObservationProviderTest.kt`

**Interfaces:**

- Preserve the existing `MovementLocationTracking` public method names.
- `startArmedTracking()` is idempotent in armed mode, `enterPursuitMode()` is idempotent in pursuit mode, and `stopTracking()` clears volatile fix/callback state.

- [ ] **Step 1: Add RED lifecycle and ordering cases**

Prove all of the following with `FakeLocationUpdatesClient`:

- Calling `startArmedTracking()` twice registers only once and updates the callback safely.
- Armed cadence is exactly `60_000L/20f`; pursuit cadence is exactly `30_000L/20f`.
- Calling `enterPursuitMode()` twice registers pursuit only once.
- `stopTracking()` unregisters once, clears `lastFix`, and prevents a captured stale callback from forwarding a fix.
- A callback with lower `elapsedRealtimeMs` cannot replace a newer fix.
- The caller callback can call `currentUsableFix()` without deadlock because it is invoked outside the provider lock.
- Provider/permission failure returns `false` and leaves a truthful diagnostic.
- Coordinates and accuracy use the same finite/range/freshness rules as Task 1.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*LocationObservationProviderTest'
```

- [ ] **Step 3: Refactor registration through one private method**

Use a monotonically increasing generation to reject callbacks from removed listeners:

```kotlin
private var registrationGeneration = 0L

private fun acceptFix(generation: Long, fix: TrackedLocationFix) {
    val callback = synchronized(lock) {
        if (trackingMode == TrackingMode.STOPPED || generation != registrationGeneration) return
        val previous = lastFix
        if (previous != null && fix.elapsedRealtimeMs <= previous.elapsedRealtimeMs) return
        lastFix = fix
        currentCallback
    }
    callback?.invoke(fix)
}
```

Do not call `currentCallback`, store methods, Telegram, or coordinator code while `lock` is held. Increment the generation before every re-registration and stop.

- [ ] **Step 4: Run GREEN**

Run the Task 2 command again. Expected: all provider tests pass with no Mockito matcher errors.

### Task 3: Add typed owner-only Telegram Live Location start/update/stop

**Files:**

- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramLiveLocationTransport.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramLiveLocationTransportTest.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClient.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClientAuthorizationTest.kt`

**Interfaces:**

```kotlin
interface TelegramLiveLocationTransport {
    suspend fun startForOwners(fix: TrackedLocationFix, livePeriodSeconds: Int): List<LiveLocationHandle>
    suspend fun update(handle: LiveLocationHandle, fix: TrackedLocationFix): Boolean
    suspend fun stop(handle: LiveLocationHandle): Boolean
    suspend fun alertOwners(text: String): Boolean
}
```

The production implementation alone reads `EncryptedPrefsManager.getAllowedChatIds()`. The coordinator never accepts or constructs an arbitrary destination chat ID.

- [ ] **Step 1: Add RED HTTP contract tests**

Assert exact Bot API methods and required fields:

```json
{"chat_id":"999","latitude":13.0,"longitude":100.0,"horizontal_accuracy":8.0,"live_period":900}
```

- `sendLocation` parses a successful `message_id`.
- `editMessageLiveLocation` includes `chat_id`, `message_id`, latitude, longitude, and horizontal accuracy.
- `stopMessageLiveLocation` includes only the stored owner handle fields plus optional reply markup if already required by the existing client.
- HTTP failure, `ok:false`, malformed JSON, and missing `message_id` return failure without throwing.
- Test logs/exceptions do not contain the bot token.

- [ ] **Step 2: Add RED owner-authorization tests**

Prove empty owner allowlist makes zero network calls; two allowlisted owners produce at most two start requests; no unauthorized chat can be supplied through the coordinator-facing API.

- [ ] **Step 3: Run RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*TelegramLiveLocationTransportTest' --tests '*TelegramBotClientAuthorizationTest' --tests '*TelegramBotClientSourceContractTest'
```

- [ ] **Step 4: Implement `stopMessageLiveLocation` and the wrapper**

Reuse the pinned existing `OkHttpClient` and the current JSON POST pattern. Execute all network calls on `Dispatchers.IO`. Log only a fixed failure label such as `Telegram stop live location failed`; never log the URL because it contains the token.

- [ ] **Step 5: Run GREEN**

Run the Task 3 command again.

### Task 4: Persist one pursuit attempt per armed session

**Files:**

- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/location/MovementTrackingStore.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/data/EncryptedPrefsManager.kt` only if the existing key methods cannot express the record.
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/location/MovementTrackingStoreTest.kt`

**Interfaces:**

Keep one durable record even after expiry so another movement fix cannot restart pursuit before disarm:

```kotlin
data class PersistedLivePursuitSession(
    val armedSessionId: String,
    val attemptedAtMs: Long,
    val expiresAtMs: Long,
    val handles: List<LiveLocationHandle>,
)
```

An empty `handles` list means “attempted, but no Telegram start succeeded,” not “safe to retry.”

- [ ] **Step 1: Add RED persistence semantics tests**

Test valid round-trip, empty-handle attempted record, malformed/non-finite coordinates where applicable, malformed JSON, an expired record still loading as an attempt marker, and `clearAnchorAndSession()` affecting only its two encrypted keys.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*MovementTrackingStoreTest'
```

- [ ] **Step 3: Change expiry ownership**

Remove the current behavior where `loadLiveSession()` silently deletes an expired record. The coordinator in Task 5 owns stop/expiry processing. The record remains until disarm or a new explicit armed cycle after a confirmed disarmed transition.

- [ ] **Step 4: Run GREEN**

Run the Task 4 command again.

### Task 5: Rewrite the coordinator as a serialized state machine

**Files:**

- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinator.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinatorTest.kt`

**Interfaces:**

```kotlin
interface LivePursuitCoordinator {
    suspend fun onProtectionStateChanged(newState: ProtectionState, proposedSessionId: String?)
    suspend fun onLocationFix(fix: TrackedLocationFix)
    suspend fun shutdown()
}
```

Inject wall and elapsed clocks instead of calling `System.currentTimeMillis()` or `SystemClock.elapsedRealtime()` inside testable policy code.

When armed tracking is registered, bridge its non-suspending callback onto the serialized coordinator scope:

```kotlin
tracking.startArmedTracking { fix ->
    scope.launch { onLocationFix(fix) }
}
```

Do not call `onLocationFix()` directly while the provider lock is held; Task 2 must release that lock first.

- [ ] **Step 1: Replace shallow mock tests with a deterministic harness**

Use fake tracking/store/Telegram/clock implementations plus `kotlinx-coroutines-test` if it is already available. Do not mock `MovementDisplacementPolicy`; use the real policy so the regression is observable.

- [ ] **Step 2: Add RED state and anchor tests**

Prove:

- `ARMING` performs zero location registration and creates no anchor.
- First transition to `ARMED_HEALTHY` or `ARMED_DEGRADED` starts armed tracking once.
- First usable fix becomes the parking anchor and calls `movementPolicy.reset(anchor)` exactly once.
- Subsequent fixes evaluate against the same anchor without reset.
- Repeated identical armed snapshots are idempotent.
- `ALERT_ACTIVE` preserves tracking, anchor, and the armed session.
- `DISARMED_ONLINE`, `SETUP_REQUIRED`, and `OFFLINE` stop tracking and clear volatile policy state; disarm also clears durable anchor/session.
- A restored unexpired anchor is reused only if its durable armed session has not been explicitly disarmed.

- [ ] **Step 3: Add RED movement/dedupe tests**

Prove:

- Vibration has no API path into this coordinator.
- One outside fix starts nothing.
- A second usable outside fix at least 15 seconds later starts one attempt.
- Before any network call, an empty-handle attempt marker is saved for that armed session.
- Reentrant/concurrent confirming fixes still produce one `startForOwners()` call.
- All-start-failed persists the attempt and does not retry later in the same armed session.
- Partial owner success stores only successful handles and enters pursuit cadence once.
- A successful start sends exactly one Thai owner alert and one native location per successful owner; later fixes send no extra text.

Use this UTF-8 alert copy:

```text
🚨 ตรวจพบว่ารถหรืออุปกรณ์กำลังถูกเคลื่อนย้าย
เริ่มติดตามตำแหน่งแบบสดเป็นเวลา 15 นาที
```

- [ ] **Step 4: Add RED update, expiry, disarm, and recovery tests**

Prove:

- Updates edit stored handles only when 30 seconds passed or displacement since the last sent fix exceeds 20m.
- Identical/older/unusable fixes produce no edit.
- At `expiresAtMs`, every successful handle is stopped once and provider returns to armed cadence.
- Expiry retains an attempted marker with empty handles until disarm, preventing restart.
- Disarm and `shutdown()` stop active handles immediately and are idempotent.
- Process recovery with an unexpired record restores tracking in the correct order: `startArmedTracking()` first, then `enterPursuitMode()`.
- Process recovery never calls `sendLocation` again for an already-attempted session.
- If network completion races with disarm, newly returned handles are stopped immediately and are not persisted as active.

- [ ] **Step 5: Run RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*LivePursuitCoordinatorTest'
```

- [ ] **Step 6: Implement the serialized coordinator**

Use one `Mutex` around state mutations. Mark the attempt durably before releasing the mutex for network I/O. Re-acquire it after network completion and confirm `currentState`, `armedSessionId`, and attempt generation still match before saving handles or switching cadence.

Do not keep the mutex held during OkHttp calls. Store an attempt generation/session ID so late network completion can be rejected safely.

Delete these current behaviors:

- The `init` block that starts tracking as a construction side effect.
- `displacementPolicy.reset(anchor)` from the normal per-fix branch.
- Tracking/anchor creation on `ARMING`.
- `onMovementIncident` constructor callback and the synthetic `SensorKind.LOCATION` observation.
- Unthrottled edit launches on every callback.

- [ ] **Step 7: Run GREEN**

Run the Task 5 command again. All coordinator cases must pass deterministically without real time or network.

### Task 6: Wire state, lifecycle, and ordinary incidents correctly

**Files:**

- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentMessageFormatter.kt`
- Modify associated graph/service/formatter tests.

**Interfaces:**

- The snapshot collector calls the coordinator's suspend state method in order.
- `SensorService.onDestroy()` schedules `LivePursuitCoordinator.shutdown()` on the process graph scope before the service scope is cancelled; do not block the Android main thread waiting for Telegram.
- Ordinary incident formatting contains no reverse geocoding or GPS lookup.

- [ ] **Step 1: Add RED wiring/lifecycle tests**

Prove state ordering `DISARMED -> ARMING -> ARMED_*` produces no GPS call until `ARMED_*`; service shutdown calls pursuit shutdown once; `ALERT_ACTIVE` does not start a new armed session; disarm clears the active session ID.

- [ ] **Step 2: Remove GPS from the incident path**

Delete the `onMovementIncident` graph wiring. Remove `Geocoder` and the `SensorKind.LOCATION` parsing block from `IncidentMessageFormatter`. The live-pursuit coordinator owns its one location alert; `IncidentDeliveryCoordinator` remains unchanged for vibration/light/audio/power incidents.

- [ ] **Step 3: Fix graph session identity**

Create a proposed UUID on the first `ARMING` transition but let the coordinator prefer a restored durable session when recovery exists. Do not assign `previousState = snapshot.state` before using the previous value. Update the previous value only after dispatch succeeds.

- [ ] **Step 4: Fix service shutdown order and foreground type**

Expose the live-pursuit coordinator through the existing process `Graph`. In `onDestroy()`, first record the authoritative service-stopped transition, then schedule `graph.livePursuitCoordinator.shutdown()` on `graph.scope`, then cancel `serviceScope`. The shutdown method must stop the local location listener synchronously within its serialized state step and perform Telegram stop as best effort on I/O; never use `runBlocking` in `onDestroy()`. `requiresLocationForeground()` should include only states where tracking is actually active (`ARMED_HEALTHY`, `ARMED_DEGRADED`, and an active `ALERT_ACTIVE` inherited from an armed state), not calibration-only `ARMING`.

- [ ] **Step 5: Run focused GREEN tests**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*LivePursuitCoordinatorTest' --tests '*IncidentMessageFormatterTest' --tests '*SensorService*Test' --tests '*ProtectionRuntimeGraph*Test'
```

Expected: no ordinary incident test expects GPS/address data; pursuit tests own all GPS delivery expectations.

### Task 7: Add a non-blocking approximate label and verify Android background readiness

**Files:**

- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/location/LocationLabelResolver.kt`
- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/location/LocationLabelResolverTest.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinator.kt`
- Inspect/modify only if required: `app/src/main/AndroidManifest.xml`, existing permission policy, settings gateway, and settings UI tests.

**Interfaces:**

```kotlin
fun interface LocationLabelResolver {
    suspend fun resolve(fix: TrackedLocationFix): String?
}
```

- [ ] **Step 1: Add RED label tests**

Use a fake geocoder boundary. Prefer nonblank `featureName`, thoroughfare, sublocality, locality, and admin area; deduplicate components; cap at 160 characters; return `null` on empty result, timeout, disabled backend, or exception.

- [ ] **Step 2: Implement without delaying the map**

Start native Live Location immediately. Resolve the label concurrently with a hard three-second timeout. Include it only in the one start alert:

```text
📍 บริเวณโดยประมาณ: {label}
```

If unavailable, omit the line. Do not send a late follow-up message. Do not claim the phone is inside a named restaurant/building, and do not add nearby POI search or a Places API.

- [ ] **Step 3: Run label/coordinator tests**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*LocationLabelResolverTest' --tests '*LivePursuitCoordinatorTest'
```

- [ ] **Step 4: Audit permission reality before editing permissions**

Confirm target SDK, device Android version, current foreground/background grants, and whether armed GPS fixes continue after the app UI has been backgrounded and screen locked. Do not add `ACCESS_BACKGROUND_LOCATION` merely to silence a test. If the target/OEM scenario requires it, add the manifest declaration plus a separate explanatory settings flow; foreground location must be requested first, background access separately, with no prompt loop.

- [ ] **Step 5: Add permission tests only for the chosen behavior**

Missing permission/provider must produce honest location degradation and zero false movement. The special-use protection service must continue without GPS rather than crash or pretend tracking is active.

### Task 8: Full verification and controlled two-phone acceptance

**Files:** Verify only. Do not opportunistically refactor.

- [ ] **Step 1: Run all focused GPS/Telegram suites**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*MovementDisplacementPolicyTest' --tests '*MovementTrackingStoreTest' --tests '*LocationObservationProviderTest' --tests '*TelegramLiveLocationTransportTest' --tests '*TelegramBotClientAuthorizationTest' --tests '*LivePursuitCoordinatorTest' --tests '*LocationLabelResolverTest' --tests '*IncidentMessageFormatterTest'
```

Expected: exit `0`, with no ignored GPS test and no Mockito matcher/NPE failure.

- [ ] **Step 2: Run full host gates sequentially**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:assembleDebug
git diff --check
rg -n -i 'Demo Mode|demoModeEnabled|IncidentSource|DEMO —' app/src/main
rg -n 'sendLocation|editMessageLiveLocation|stopMessageLiveLocation' app/src/main app/src/test
```

Compare full-suite failures with the Task 0 baseline. Do not disable unrelated failures or claim a clean suite if failures remain.

- [ ] **Step 3: Inspect the exact scoped diff**

```powershell
git diff --check
git diff -- app/src/main/java/com/example/motorcycleantitheftsensor/location app/src/main/java/com/example/motorcycleantitheftsensor/sensor/LocationObservationProvider.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinator.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentMessageFormatter.kt app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt app/src/main/java/com/example/motorcycleantitheftsensor/telegram app/src/test
```

Reject any unrelated refactor, public API rename, new dependency, secret, real coordinate, SMS location content, or token-bearing log.

- [ ] **Step 4: Install the freshly built APK and record identity**

Record APK path, SHA-256, install timestamp, package version, device serial/model, Android version, and current fine/background location grants. Do not rely on the previously installed APK after production changes.

- [ ] **Step 5: Run the controlled acceptance matrix**

1. `/arm`: Telegram sends only the immediate calibration reply; GPS produces no anchor/map during the 10-second calibration.
2. After `ARMED_HEALTHY`/`ARMED_DEGRADED`, wait for a usable anchor while stationary.
3. Shake the device without moving beyond the radius: existing incident behavior may occur, but no GPS text or map appears.
4. Move beyond the threshold and obtain one usable outside fix: no pursuit yet.
5. Obtain the second usable outside fix at least 15 seconds later: exactly one movement alert and one native Live Location per paired owner appear.
6. Move again: the same Telegram map message updates; no second map/text appears.
7. Intentionally carry the armed phone: it follows the same confirmed-movement behavior; document this as expected.
8. Lock the screen/background the app and repeat updates: record whether the OEM preserves fixes, FGS, network, and wake lock.
9. Disable GPS or revoke location: no false movement claim; protection continues with honest degraded status.
10. Restart the service/process during active pursuit: no duplicate `sendLocation`; existing handle updates resume or stop safely according to the recovered expiry.
11. Disarm during an in-flight Telegram start: any late-created handle is stopped, tracking stops, and encrypted anchor/session keys clear.
12. Re-arm: a new anchor and one new attempt are allowed; the prior session cannot suppress it.
13. Wait through 15 minutes: Live Location stops once, tracking returns to armed cadence, and movement does not restart pursuit until the next armed cycle.
14. Verify Telegram unauthorized chats and every SMS path receive no coordinate, label, Maps URL, handle, or pursuit state.

- [ ] **Step 6: Produce an evidence-based handoff**

Report:

- Exact files changed and why.
- RED failure and GREEN pass for each task.
- Focused/full test counts and exact failures, if any.
- APK build/install identity.
- Each device acceptance row as PASS/FAIL/NOT RUN with timestamp.
- Permission and OEM/background observations.
- Confirmation that ordinary incidents contain no GPS and that only owner chats receive pursuit.
- Remaining risks. Do not claim theft detection, location accuracy, 15-minute survival, or recovery unless the corresponding real-device row passed.

## Final acceptance criteria

- Calibration (`ARMING`) produces no GPS registration, anchor, location alert, or live map.
- Two fresh/accurate fixes outside the parking threshold, separated by at least 15 seconds, confirm displacement.
- Exactly one pursuit attempt occurs per armed session, including all-network-failure cases.
- Each paired owner gets at most one native Telegram Live Location; subsequent fixes edit that message.
- Ordinary vibration/light/audio/power incidents contain no GPS and never start pursuit.
- Expiry, disarm, and shutdown stop native Live Location and location cadence correctly.
- A process/service race cannot create duplicate maps or retain a late handle after disarm.
- Invalid, stale, older, out-of-range, or NaN fixes cannot anchor, confirm, or update pursuit.
- Approximate place text is best effort, does not delay native Live Location beyond its own call, and never claims exact POI occupancy.
- SMS and unauthorized chats receive no location data.
- Focused tests and `assembleDebug` pass; full-suite and real-device status are reported separately and honestly.
