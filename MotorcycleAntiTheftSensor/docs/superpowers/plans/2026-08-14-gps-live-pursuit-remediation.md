# GPS Live Pursuit Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` or `superpowers:executing-plans` to implement this plan task-by-task. Track every completed step by changing its checkbox from `- [ ]` to `- [x]` only after its stated verification passes.

**Goal:** Repair the current GPS Live Pursuit implementation so a valid confirmed movement opens exactly one owner-only 15-minute Telegram Live Location session, safely closes on expiry/disarm/shutdown, restores safely after process recovery, and never anchors from bad GPS data.

**Architecture:** `ProtectionCoordinator` remains the authoritative protection-state owner. `DefaultLivePursuitCoordinator` becomes the single serialized owner of GPS session state; it receives state transitions and fixes, persists only anchor/attempt/handles, and calls the owner-only `TelegramLiveLocationTransport`. `LocationObservationProvider` owns Android listener registration only; it must never execute coordinator work while holding its internal lock.

**Tech Stack:** Kotlin, Android `LocationManager`, existing coroutines/OkHttp/encrypted preferences, JUnit4, Mockito, no new dependency.

## Review evidence and scope

The prior review verified `:app:testDebugUnitTest` and `:app:assembleDebug` exit `0` (228 unit tests, zero failures/errors) but found source-level defects that the present tests do not exercise:

1. `expiresAtMs` is persisted in `LivePursuitCoordinator.kt` but no expiry task reads it, stops the Telegram message, or exits pursuit cadence.
2. The first callback becomes an anchor without freshness, accuracy, finite-value, or coordinate-range validation.
3. `mutex` is declared but unused; a late Telegram-start coroutine can repopulate handles/session after disarm.
4. The coordinator never loads a persisted anchor/session, so it cannot recover an active session correctly.
5. `TelegramLiveLocationTransport` and `LocationLabelResolver` are dead production code; coordinator bypasses the former and never uses the latter.
6. Live Pursuit alert source is mojibake, so Thai message text is not trustworthy.
7. `LocationObservationProvider` invokes callbacks inside its lock, re-registers repeatedly in the same mode, and lets old callbacks overwrite the latest fix.
8. Tests do not cover confirmed movement, one-attempt dedupe, update throttling, expiry, recovery, or a start/disarm race.

This is a remediation plan only. Do not redesign unrelated UI, pairing, TOTP, SMS, ordinary incidents, or Telegram command polling.

## Global constraints

- Work in the existing dirty checkout. Never run `git reset`, `git clean`, broad staging, or revert files outside this GPS scope.
- Read `D:\security\AGENTS.md`; `AI_WORKFLOW.md` was absent when this plan was created. If it exists at implementation time, read it before source edits.
- Preserve `/arm`'s single immediate calibration reply; never send an extra armed-success Telegram message after the 10-second calibration.
- GPS starts only after `ARMED_HEALTHY` or `ARMED_DEGRADED`, never in `ARMING`.
- Do not add dependencies, API keys, Google Places, route-history persistence, or SMS location delivery.
- GPS text/maps go only to `EncryptedPrefsManager.getAllowedChatIds()`. No method exposed to the coordinator may accept an arbitrary chat ID.
- A session attempt is durable before network I/O. An all-failed start is still an attempt and must not retry until the next armed cycle.
- Native Live Location duration is exactly 900 seconds. Later valid fixes update the existing messages only when at least 30 seconds elapsed or the last published position moved more than 20 m.
- On disarm, setup-required, offline, expiry, or shutdown: stop tracking immediately, stop known native Live Location handles best-effort, and prevent late network results from reviving the session.
- `DISARMED_ONLINE` is the only state transition that fully deletes the durable anchor/attempt. `SETUP_REQUIRED`, `OFFLINE`, and shutdown stop active work and persist an empty-handle attempt marker, so an initial process `OFFLINE` snapshot or a later recovery cannot accidentally unlock a duplicate pursuit.
- Treat only finite latitude `[-90, 90]`, longitude `[-180, 180]`, nonnegative finite accuracy `<=100m`, and elapsed timestamps not in the future/not older than 30 seconds as usable.
- Save Kotlin/Markdown as UTF-8. Exact Thai alert text below must not become mojibake.
- Do not log bot tokens, token-bearing URLs, real chat IDs, real coordinates, TOTP material, or Telegram message IDs. Remove existing `println`/interceptor debug output that places even test token URLs in test reports.
- Do not claim real-device success from host tests. Install a newly built APK and use the acceptance matrix after host gates pass.

## File map

Every shortened path inside Task sections is relative to this exact source root:

```text
D:\security\MotorcycleAntiTheftSensor\app\src
```

Use the full paths in this File map when staging or reviewing changes; never stage the whole repository.

**Modify**

- `app/src/main/java/com/example/motorcycleantitheftsensor/location/MovementDisplacementPolicy.kt` — expose one reusable usable-fix predicate.
- `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/LocationObservationProvider.kt` — generation-safe/idempotent listener registration and callback outside lock.
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinator.kt` — serialized lifecycle, attempt generation, start/update/expiry/recovery.
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt` — inject transport/resolver/clocks and bridge suspend callbacks.
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt` `Graph` data class — expose the coordinator for service shutdown.
- `app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt` — synchronously stop location tracking and schedule safe pursuit close during destruction; remove `ARMING` from location-FGS need.
- `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramLiveLocationTransport.kt` — use the existing pinned client consistently and preserve owner-only API.
- `app/src/main/java/com/example/motorcycleantitheftsensor/location/LocationLabelResolver.kt` — retain its 3-second best-effort behavior and use it only from pursuit start.
- `app/src/test/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinatorTest.kt`
- `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/LocationObservationProviderTest.kt`
- `app/src/test/java/com/example/motorcycleantitheftsensor/location/MovementDisplacementPolicyTest.kt`
- `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramLiveLocationTransportTest.kt`
- `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClientAuthorizationTest.kt` — remove debug output only.

**Create**

- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/PursuitExpiryScheduler.kt` — small production/test boundary for the 15-minute timer.

---

### Task 1: Lock down GPS fix validity and provider listener lifecycle

**Files:**

- Modify: `location/MovementDisplacementPolicy.kt`
- Modify: `sensor/LocationObservationProvider.kt`
- Modify: `test/location/MovementDisplacementPolicyTest.kt`
- Modify: `test/sensor/LocationObservationProviderTest.kt`

**Interfaces:**

```kotlin
class MovementDisplacementPolicy {
    fun isUsableFix(fix: TrackedLocationFix, nowElapsedMs: Long): Boolean
    fun reset(anchor: ParkingAnchor)
    fun clear()
    fun evaluate(fix: TrackedLocationFix, nowElapsedMs: Long): MovementDecision
}
```

`isUsableFix()` must be the one predicate used by both anchor creation and displacement evaluation.

- [x] **Step 1: Write RED tests for invalid anchor candidates**

Add parameterized-style individual cases in `MovementDisplacementPolicyTest` proving each of these returns `false`: `Double.NaN` coordinate, latitude `91.0`, longitude `181.0`, `Float.NaN` accuracy, accuracy `-1f`, accuracy `101f`, future elapsed time, and a fix exactly `30_001L` ms old.

```kotlin
@Test
fun futureFixCannotBecomeUsable() {
    val policy = MovementDisplacementPolicy()
    assertFalse(policy.isUsableFix(fix(0.0, 0.0, 11_000L, 5f), 10_000L))
}
```

- [x] **Step 2: Write RED provider behavior tests**

Extend the existing fake client so it retains every callback, including callbacks removed by later registrations. Add tests that prove:

```kotlin
@Test
fun oldCallbackCannotOverwriteNewerFix() {
    provider.startArmedTracking { }
    val oldCallback = fakeClient.callbacks.single()
    provider.enterPursuitMode()
    fakeClient.emitCurrent(fix(1.0, 1.0, 20_000L, 5f))
    oldCallback(fix(2.0, 2.0, 30_000L, 5f))
    assertEquals(1.0, provider.currentUsableFix(20_001L)!!.latitude, 0.0)
}

@Test
fun callbackCanReenterProviderWithoutDeadlock() {
    provider.startArmedTracking { provider.currentUsableFix(it.elapsedRealtimeMs) }
    fakeClient.emitCurrent(fix(1.0, 1.0, 20_000L, 5f))
}
```

Also assert repeated `startArmedTracking()` in armed mode makes exactly one registration, repeated `enterPursuitMode()` makes exactly one pursuit registration, and `stopTracking()` clears `lastFix` and ignores a callback captured before stop.

- [x] **Step 3: Run RED**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*MovementDisplacementPolicyTest' --tests '*LocationObservationProviderTest'
```

Expected: new lifecycle/anchor cases fail against the current code.

- [x] **Step 4: Implement the smallest safe provider change**

In `LocationObservationProvider`, add `registrationGeneration: Long`. Increment it before each actual registration and before stop. Capture the generation in the `LocationUpdatesClient.start` lambda. Copy `currentCallback` under `lock`, release the lock, then invoke it only when the callback generation matches and its elapsed timestamp is newer than the stored fix.

Use the following pattern; do not invoke coordinator/store/Telegram code inside `synchronized(lock)`:

```kotlin
private fun acceptFix(generation: Long, fix: TrackedLocationFix) {
    val callback = synchronized(lock) {
        if (trackingMode == TrackingMode.STOPPED || generation != registrationGeneration) return
        if (lastFix?.elapsedRealtimeMs?.let { fix.elapsedRealtimeMs <= it } == true) return
        lastFix = fix
        currentCallback
    }
    callback?.invoke(fix)
}
```

If the mode and callback are already the requested ones, update only the callback and do not re-register. `stopTracking()` must set `lastFix = null`, increment generation, clear callback, then call `client.stop()` outside the lock.

- [x] **Step 5: Run GREEN**

Run the Task 1 command again. Expected: every provider/policy test passes and no test name contradicts the actual registration count.

### Task 2: Make Live Pursuit a serialized, testable state machine

**Files:**

- Create: `protection/PursuitExpiryScheduler.kt`
- Modify: `protection/LivePursuitCoordinator.kt`
- Modify: `test/protection/LivePursuitCoordinatorTest.kt`

**Interfaces:**

Replace the current synchronous fix callback API with these exact methods:

```kotlin
interface LivePursuitCoordinator {
    suspend fun onProtectionStateChanged(newState: ProtectionState, proposedSessionId: String?)
    suspend fun onLocationFix(fix: TrackedLocationFix)
    fun shutdown()
}

interface PursuitExpiryScheduler {
    fun schedule(delayMs: Long, action: () -> Unit): PursuitExpiryHandle
}

fun interface PursuitExpiryHandle { fun cancel() }
```

Production scheduler starts `scope.launch { delay(delayMs); action() }`; fake scheduler records `delayMs` and executes a selected recorded action in a test. Inject `wallClockMs: () -> Long` and `elapsedClockMs: () -> Long` into the coordinator with production defaults.

- [x] **Step 1: Replace shallow mocks with deterministic fakes**

Use fake tracking/store/transport/label resolver/scheduler/clock. Do not mock `MovementDisplacementPolicy`; use the real policy. The fake transport must record `starts`, `alerts`, `updates`, and `stops`, and support a `CompletableDeferred<List<LiveLocationHandle>>` to hold a start operation in flight.

- [x] **Step 2: Write RED anchor and movement cases**

Add tests for this sequence:

```kotlin
coordinator.onProtectionStateChanged(ProtectionState.ARMING, "arm-1")
assertEquals(0, tracking.armedStarts)

coordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, "arm-1")
coordinator.onLocationFix(fix(0.0, 0.0, 1_000L, 5f))
coordinator.onLocationFix(fix(0.002, 0.0, 2_000L, 5f))
coordinator.onLocationFix(fix(0.002, 0.0, 17_000L, 5f))
assertEquals(1, transport.starts.size)
```

Also prove an invalid first fix creates no anchor; one outside fix creates no attempt; repeated confirmed fixes create one start; all-start-failed saves an empty-handle attempt and never retries; partial success stores only returned handles and calls `enterPursuitMode()` once.

- [x] **Step 3: Write RED update, expiry, race, and recovery cases**

Prove all of these exact outcomes:

- A valid later fix at +29 seconds and <20m produces zero `update`; at +30 seconds it updates every stored handle once.
- Expiry action stops every handle once, calls `exitPursuitMode()`, and leaves a durable empty-handle attempted record so later fixes do not start another map.
- Disarm while `transport.startForOwners()` is suspended clears anchor/session and stops tracking. When that start later returns a handle, it is immediately stopped and never becomes active/persisted.
- `shutdown()` synchronously calls `tracking.stopTracking()` and its later async close is idempotent.
- On a restored unexpired record, tracking first starts armed then enters pursuit, existing handles update, and `startForOwners()` is never called again.
- On a restored expired record, it remains attempted but does not enter pursuit or start another map.
- An initial `OFFLINE` snapshot emitted while the graph is being constructed does not delete a stored anchor/session before the authoritative recovery transition is processed.

- [x] **Step 4: Run RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*LivePursuitCoordinatorTest'
```

- [x] **Step 5: Implement state serialization and expiry**

Use the existing coroutine `Mutex` for every mutation of `currentState`, armed session ID, anchor, attempt flag, attempt generation, handles, last-published fix, and expiry handle. Never hold it across network or address lookup.

When the second valid outside fix confirms movement:

1. Inside `mutex.withLock`, set `pursuitAttempted = true`, increment an `attemptGeneration`, save an empty-handle session with `expiresAtMs = wallClockMs() + 900_000L`, and capture a start request.
2. Outside the lock, call `transport.startForOwners(fix, 900)` immediately.
3. Re-enter the lock. Accept handles only if state/session/generation are still current and armed. Persist only accepted handles, schedule the expiry, save the published fix, and call `tracking.enterPursuitMode()` only when at least one handle exists.
4. If the start result is stale because disarm/offline/shutdown happened, call `transport.stop(handle)` outside the lock for every returned handle and do not persist it.

Use this exact valid UTF-8 text; add a test comparing the complete string:

```text
🚨 ตรวจพบว่ารถหรืออุปกรณ์กำลังถูกเคลื่อนย้าย
เริ่มติดตามตำแหน่งแบบสดเป็นเวลา 15 นาที
```

Do not call `TelegramBotClient` directly from this coordinator. Do not call `displacementPolicy.reset()` except when accepting a new or restored valid anchor.

- [x] **Step 6: Implement recovery and valid anchor selection**

On the first `ARMED_HEALTHY`/`ARMED_DEGRADED` transition, read both store records under the coordinator mutex:

- If a stored anchor exists and its fix passes `isUsableFix`, restore its `armedSessionId`, reset policy once, and keep it.
- If its paired session is unexpired and has handles, restore attempted/handles, start armed tracking, then enter pursuit and schedule only the remaining time.
- If its paired session is expired or has no handles, restore attempted but use armed cadence; do not start a second session.
- If records are missing, use `proposedSessionId` for a fresh cycle. If an attempted session record exists but its anchor is invalid, retain the attempted session marker and do not start a second pursuit; only explicit disarm may unlock a new attempt.

`DISARMED_ONLINE` cancels expiry, invalidates the generation, closes handles, and clears durable records. `SETUP_REQUIRED`, `OFFLINE`, and `shutdown()` cancel active work and close handles but persist the same armed-session attempt marker with an empty handle list. The graph's initial `OFFLINE` snapshot must not erase recovery data. `ALERT_ACTIVE` changes none of the live pursuit state.

- [x] **Step 7: Run GREEN**

Run the Task 2 command again. Expected: movement, expiry, recovery, and disarm-race cases all pass deterministically without a real 15-minute wait.

### Task 3: Wire only the typed owner transport, label, and lifecycle paths

**Files:**

- Modify: `telegram/TelegramLiveLocationTransport.kt`
- Modify: `location/LocationLabelResolver.kt`
- Modify: `protection/ProtectionRuntimeGraph.kt`
- Modify: `service/SensorService.kt`
- Modify: `test/telegram/TelegramLiveLocationTransportTest.kt`
- Modify: `test/telegram/TelegramBotClientAuthorizationTest.kt`
- Modify/add focused graph/service tests if they already exist; otherwise cover graph behavior through coordinator fakes.

**Interfaces:**

The coordinator receives only:

```kotlin
private val transport: TelegramLiveLocationTransport
private val labelResolver: LocationLabelResolver
```

`TelegramLiveLocationTransport` remains the only component that reads the owner allowlist. `LocationLabelResolver` returns `String?` and cannot send a message itself.

- [x] **Step 1: Write RED production-wiring/transport tests**

Add tests that assert `startForOwners()` uses only allowed IDs, sends `live_period=900`, and uses `stopMessageLiveLocation` on stop. Decode the JSON request body and assert fields rather than asserting the token-bearing URL.

Add a source/behavior test that an unavailable label produces the exact two-line alert above, while a label produces exactly one additional line:

```text
📍 บริเวณโดยประมาณ: {label}
```

The map start must be recorded before the label resolver completes. A label failure/timeout must not create another Telegram message.

Add a graph/coordinator recovery regression proving a persisted session survives the initial `OFFLINE` snapshot and is considered when the later authoritative armed recovery state arrives.

- [x] **Step 2: Run RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*TelegramLiveLocationTransportTest' --tests '*LivePursuitCoordinatorTest' --tests '*LocationLabelResolverTest'
```

- [x] **Step 3: Wire graph and bridge callbacks**

Build `TelegramLiveLocationTransportImpl` and `AndroidLocationLabelResolver` in `ProtectionRuntimeGraph`; inject them into the coordinator. The provider callback must bridge onto the graph scope:

```kotlin
locationTracking.startArmedTracking { fix ->
    scope.launch { livePursuitCoordinator.onLocationFix(fix) }
}
```

Do not create a second location provider. `TelegramBotClient` remains the command-polling client; the typed transport reuses the existing pinned `OkHttpClient` policy and never exposes chat IDs to its caller.

- [x] **Step 4: Fix destruction and foreground location state**

Expose `livePursuitCoordinator` on the process `Graph`. In `SensorService.onDestroy()`, call its non-suspending `shutdown()` before cancelling the service scope, then record service stopped. Do not use `runBlocking` on the Android main thread.

Change `requiresLocationForeground()` so `ARMING` alone does not request location FGS type; leave `ARMED_HEALTHY`, `ARMED_DEGRADED`, and an active `ALERT_ACTIVE` path eligible when runtime location permission exists. Do not add `ACCESS_BACKGROUND_LOCATION` unless separate device evidence shows it is required; if it is required later, plan its Android settings flow separately.

- [x] **Step 5: Remove unsafe test diagnostics**

Delete `println`, `System.out`, and token-URL interceptor diagnostics from `TelegramBotClientAuthorizationTest`. Test only request path/body fields and synthetic IDs. Verify `app/build/test-results` contains no `bot123456:TEST` string after focused tests.

- [x] **Step 6: Run GREEN**

Run the Task 3 command again. Expected: only one production transport route exists, map starts before label work, and test reports contain no token-bearing URL.

### Task 4: Scope hygiene and host verification

**Files:** Verify all GPS files above. Do not change unrelated UI/status/QR files.

- [x] **Step 1: Run all focused suites**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*MovementDisplacementPolicyTest' --tests '*MovementTrackingStoreTest' --tests '*LocationObservationProviderTest' --tests '*TelegramLiveLocationTransportTest' --tests '*TelegramBotClientAuthorizationTest' --tests '*LivePursuitCoordinatorTest' --tests '*LocationLabelResolverTest' --tests '*IncidentMessageFormatterTest'
```

Expected: exit `0`, no Mockito callback matcher failure, and explicit test coverage for movement confirmation, one-attempt dedupe, expiry, recovery, disarm race, update throttle, UTF-8 alert, and invalid anchor rejection.

- [x] **Step 2: Run full host gates sequentially**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:assembleDebug
git diff --check
rg -n -S 'sendLiveLocation\(|sendTelegramMessage\(|editMessageLiveLocation\(' app/src/main/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinator.kt
rg -n -S 'bot[0-9]|api.telegram.org/bot' app/build/test-results
```

Expected: both Gradle commands exit `0`; coordinator direct Telegram-client search has zero matches; test-report secret scan has zero matches. `git diff --check` currently has whitespace failures in unrelated files. Fix only failures in files touched by this plan; list every unrelated failure separately and do not report a clean diff until its owner resolves it.

- [x] **Step 3: Inspect the scoped diff and secrets**

```powershell
git diff --check -- app/src/main/java/com/example/motorcycleantitheftsensor/location app/src/main/java/com/example/motorcycleantitheftsensor/sensor/LocationObservationProvider.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinator.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/PursuitExpiryScheduler.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt app/src/main/java/com/example/motorcycleantitheftsensor/telegram app/src/test/java/com/example/motorcycleantitheftsensor/location app/src/test/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinatorTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/sensor/LocationObservationProviderTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/telegram
rg -n -i 'Demo Mode|demoModeEnabled|IncidentSource|DEMO' app/src/main
```

Reject changes that add a new dependency, public arbitrary-chat API, SMS GPS data, route history, token-bearing log, real coordinate fixture, or ordinary-incident GPS coupling.

### Task 5: Fresh APK and controlled two-phone acceptance

**Files:** Verify only; do not alter source while testing.

- [x] **Step 1: Install a freshly built APK and record identity**

Record APK path/SHA-256, install timestamp, package version, device model, Android version, `ACCESS_FINE_LOCATION` status, background-location status, GPS enabled status, and `SEND_SMS` denial. Never place a real Telegram token or real coordinate in the handoff.

- [ ] **Step 2: Run the acceptance matrix**

1. `/arm` receives only the immediate Thai calibration reply; there is no GPS anchor/map during 10 seconds of `ARMING`.
2. Once armed, wait for one stationary valid anchor.
3. Shake within the anchor radius: ordinary incident behavior may occur, but no map/location text appears.
4. One valid outside fix: no map yet.
5. Second valid outside fix at least 15 seconds later: exactly one UTF-8 Thai warning and one native Live Location per paired owner.
6. At +29 seconds with <20m movement: no edit. At +30 seconds or >=20m movement: the existing map updates and no new map/text appears.
7. Disable GPS/revoke location: no false movement; status truthfully degrades.
8. Restart service/process while pursuit is active: no duplicate map; existing map resumes updates only before its expiry.
9. Disarm while a network request is intentionally delayed: no map remains active and no persisted handle returns after disarm.
10. Let the 15-minute period expire: map stops, tracking returns to armed cadence, and no further map starts until disarm/re-arm.
11. Intentionally carry the armed device: confirmed movement behavior is expected and must be documented.
12. Verify an unauthorized chat and all SMS paths receive no coordinate, label, Maps link, pursuit state, or handle.

- [ ] **Step 3: Produce an evidence-based completion report**

Report each host/device row as `PASS`, `FAIL`, or `NOT RUN`; include test count, APK hash, focused/full test commands, device identity, permission state, and remaining OEM/background risks. Do not claim theft detection, GPS accuracy, recovery, or 15-minute survival without the matching device row passing.

## Final acceptance criteria

- No invalid/stale GPS fix can become the parking anchor, confirm movement, or update a map.
- Exactly one durable pursuit attempt occurs per armed session, including all-start-failed cases.
- Two qualifying outside fixes separated by 15 seconds produce one owner-only Live Location session.
- The exact Thai alert is UTF-8 and appears at most once per session; label is optional and cannot delay the native map start.
- Update, expiry, disarm, shutdown, and recovery behavior are deterministic and covered by tests.
- A late network completion cannot revive handles after disarm/offline/shutdown.
- Ordinary sensor incidents, SMS, and unauthorized chats contain no GPS data.
- New focused/full host results and fresh real-device evidence are reported separately.
