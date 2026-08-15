# GPS and Telegram Fixed-Power Hardening Implementation Plan

> **Post-implementation review:** Do not treat this plan as accepted-complete. Fresh review found remaining GPS recovery/concurrency defects plus Settings verification and repeating heads-up notification issues. Execute `docs/superpowers/plans/2026-08-14-gps-settings-silent-notification-follow-up.md` before claiming production readiness.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve the working GPS-to-Telegram behavior while reducing detection and publishing latency, making provider/lifecycle/persistence/network behavior deterministic, and preventing coordinates from crossing an unintended delivery boundary.

**Architecture:** Keep Android framework `LocationManager` so Huawei devices remain supported without Google Play Services. Use an explicit fixed-power GPS + network request profile, validate and arbitrate fixes before one bounded single-consumer ingress, keep `DefaultLivePursuitCoordinator` as the sole pursuit-state owner, persist anchor/session state atomically before remote effects, and expose typed/cancellable Telegram results for bounded retry. Location presentation is typed: owner Telegram may receive coordinates, a Maps URL, accuracy, and a best-effort Thai label; SMS and generic diagnostics never receive raw coordinates.

**Tech Stack:** Android/Kotlin, coroutines, framework `LocationManager`, Android `Geocoder`, encrypted preferences already in the app, pinned OkHttp 4.12.0 transport, JUnit4, coroutine-test, existing Android instrumentation stack.

## Global Constraints

- Work in the current dirty checkout. Never reset, clean, revert, move, overwrite, or broadly stage unrelated changes.
- Read `D:\security\AGENTS.md` before edits. `AI_WORKFLOW.md` was not present on 2026-08-14; if it appears, read it before production edits.
- Do not add Google Play Services, HMS Location, OSM/Nominatim, Places, Maps SDK, or any new dependency.
- This is a fixed-power profile: battery duration is not an acceptance criterion. Reliability, freshness, latency, bounded resource use, and thermal stability remain acceptance criteria.
- Active tracking profile: ARMED requests GPS and network fixes at `10_000 ms` / `5 m`; PURSUIT requests GPS and network fixes at `5_000 ms` / `5 m`. Do not register `PASSIVE_PROVIDER` or arbitrary providers returned by `getProviders(true)`.
- Telegram publication is separately coalesced: the normal gate is `10_000 ms` or at least `10 m` since the last successful publication, with an absolute minimum of `5_000 ms` between attempts per owner handle. GPS callback rate must never directly equal Telegram request rate.
- Movement confirmation remains two usable outside fixes separated by at least `15_000 ms`; base displacement remains `100 m` plus the existing accuracy margin.
- Live Location remains `900 s`; one armed cycle permits at most one start attempt, including failed starts.
- Only currently paired owner Telegram chat IDs may receive coordinates or a place label. SMS must never contain latitude, longitude, Maps URLs, place labels, parking anchors, Live Location handles, or tracking state.
- Raw coordinates must not appear in logs, exceptions, generic sensor diagnostics, snapshots, UI health cards, accessibility semantics, screenshots, or test fixtures that use real locations.
- Use synthetic coordinates in tests. Do not record real chat IDs, bot tokens, TOTP values, device positions, or Telegram message IDs in evidence.
- Preserve HTTPS-only Telegram transport and the current verified pinning policy. Never log a token-bearing URL or Telegram response description.
- Preserve `/pair <code>`, mandatory TOTP, `/arm`, `/disarm <TOTP>`, Demo Mode, incident authorization, and existing SMS eligibility semantics except for explicit coordinate redaction.
- Host tests/builds are not device acceptance. Report source, build, install, and real-device evidence separately.
- Run Gradle and ADB sequentially with Android Studio JBR, `--no-daemon`, `--max-workers=1`, and in-process Kotlin compilation.

## Verified Review Baseline

- Current branch: `feature/motorcycle-guard-protection`; current HEAD observed during review: `c168ae9`.
- GPS source and tests are partly modified/untracked in a mixed dirty worktree; `git diff` alone does not show untracked GPS files.
- Fresh focused command passed on 2026-08-14: 8 test classes, 78 tests, 0 failures, 0 errors, 0 skipped.
- Passing tests prove the implemented happy paths and existing race tests only. They do not cover failed provider re-registration, start/stop registration races, fix backpressure/reordering, failed durable writes, failed-update retry, missing Telegram `message_id`, owner revocation, Android 14+ background FGS location, or the real Android Geocoder implementation.
- `LocationLabelResolverTest` currently tests fake lambdas; it does not exercise `AndroidLocationLabelResolver`, address formatting, the timeout, or API 33+ asynchronous geocoding.

## Review Findings This Plan Must Close

1. `LocationObservationProvider.startArmedTracking()` sets mode to ARMED before registration succeeds. A failed start leaves false internal state, and the next start returns `true` without registering.
2. Provider registration occurs outside the provider lock without a registration handle. `stopTracking()` can race a late `client.start()` and leave Android updates active after stop.
3. `AndroidLocationUpdatesClient` registers GPS, network, passive, and every enabled provider with one listener. This creates duplicate callback streams with no source policy.
4. Every accepted provider callback launches a new coroutine in `LivePursuitCoordinator`; there is no bounded queue, conflation, or final monotonic guard at the coordinator boundary.
5. `lastPublishedFix` advances before Telegram confirms success. A failed update can suppress the next useful retry.
6. Telegram live-location helpers collapse IO, HTTP 429/5xx, authorization, expired message, malformed JSON, and missing `message_id` into `Boolean`/`null`. `optLong("message_id")` can turn a missing field into message ID `0`.
7. The shared OkHttp client has no call timeout. Sequential owner operations can hold `remoteMutex` for the sum of multiple 30-second read timeouts, delaying disarm/expiry stop.
8. Anchor and session are stored under separate preference keys and cleared in two commits. Save results are ignored before Telegram start, so a remote effect can occur without a durable one-attempt marker.
9. `AndroidLocationLabelResolver` uses the blocking deprecated Geocoder call on every Android version. Coroutine timeout does not cancel that blocking framework call. Its current unit test does not test production behavior.
10. Raw coordinates are placed in `SensorObservation.diagnostic`, copied into snapshots/UI/incident evidence, formatted into the Telegram incident, and the same formatted message is reused by SMS fallback. The destination policy is implicit rather than enforced by types.
11. The service targets SDK 36 and adds the location FGS type dynamically, but it only checks fine/coarse permission. Android 14+ can reject a background-created location FGS even when `checkSelfPermission()` returns granted.
12. `shutdown()` starts asynchronous cleanup and returns immediately. The service does not await final stop/persistence, making normal service destruction nondeterministic.

## File Map

**Create**

- `app/src/main/java/com/example/motorcycleantitheftsensor/location/LocationFixArbiter.kt` — shared validation, provider preference, monotonic selection, and duplicate suppression.
- `app/src/main/java/com/example/motorcycleantitheftsensor/location/ConflatedLocationFixIngress.kt` — one bounded single-consumer bridge from Android callbacks to the coordinator.
- `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramLiveLocationApi.kt` — cancellable OkHttp boundary with typed results.
- `app/src/test/java/com/example/motorcycleantitheftsensor/location/LocationFixArbiterTest.kt`.
- `app/src/test/java/com/example/motorcycleantitheftsensor/location/ConflatedLocationFixIngressTest.kt`.
- `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramLiveLocationApiTest.kt`.
- `app/src/test/java/com/example/motorcycleantitheftsensor/service/LocationForegroundPolicyTest.kt` — pure policy tests for API/background/start conditions.

**Modify**

- `app/src/main/java/com/example/motorcycleantitheftsensor/location/MovementDisplacementPolicy.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/location/MovementTrackingStore.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/location/LocationLabelResolver.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/LocationObservationProvider.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/SecurityIncident.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/FileIncidentRepository.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentMessageFormatter.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentDeliveryCoordinator.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinator.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramLiveLocationTransport.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/data/EncryptedPrefsManager.kt`
- Existing matching unit/instrumentation tests named in each task.

The typed incident-location boundary affects more than five files because the current single diagnostic string crosses runtime, persistence, Telegram, SMS, UI, and history boundaries. Review Task 1 as an independent gate before any other production edit.

---

### Task 0: Freeze Scope and Reproduce the Baseline

**Files:**

- Inspect only: all current modified/untracked files.
- Verify only: the 8 focused GPS classes and their production dependencies.

**Interfaces:**

- Produces a recorded dirty-tree snapshot and a fresh baseline; no source change.

- [ ] **Step 1: Record tracked and untracked GPS scope**

```powershell
git status --short --branch
rg --files app/src/main app/src/test | rg -i '(location|gps|pursuit|telegram|incident|sensorservice)'
git diff -- app/src/main/java/com/example/motorcycleantitheftsensor/sensor/LocationObservationProvider.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection app/src/main/java/com/example/motorcycleantitheftsensor/telegram app/src/main/AndroidManifest.xml
```

Do not stage or normalize the worktree. Read untracked files directly because `git diff` omits them.

- [ ] **Step 2: Run the focused baseline fresh**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*MovementDisplacementPolicyTest' --tests '*MovementTrackingStoreTest' --tests '*LocationObservationProviderTest' --tests '*LocationEvidencePolicyTest' --tests '*TelegramLiveLocationTransportTest' --tests '*TelegramBotClientAuthorizationTest' --tests '*LivePursuitCoordinatorTest' --tests '*LocationLabelResolverTest'
```

Expected review baseline: 78 tests, 0 failures/errors. A different fresh result becomes authoritative and must be recorded before Task 1.

---

### Task 1: Enforce a Typed Location Privacy Boundary

**Files:**

- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/SecurityIncident.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/FileIncidentRepository.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentMessageFormatter.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentDeliveryCoordinator.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/LocationObservationProvider.kt`
- Test: corresponding runtime, repository, formatter, delivery, and provider tests.

**Interfaces:**

```kotlin
data class IncidentLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val capturedAtWallClockMs: Long,
)

data class IncidentObservationBatch(
    val primary: SensorObservation,
    val supplementalEvidence: List<SensorObservation> = emptyList(),
    val location: IncidentLocation? = null,
)
```

`SecurityIncident` gains `val location: IncidentLocation? = null`. Generic `SensorObservation.diagnostic` becomes redacted metadata such as `fix age_ms=1200 accuracy_m=8.0`; it never carries latitude/longitude.

- [ ] **Step 1: Add RED data-routing tests**

Prove:

- a fresh usable fix becomes `IncidentObservationBatch.location`;
- stale/inaccurate/invalid fixes produce `location == null`;
- `SensorObservation.diagnostic`, snapshot health detail, UI-facing health data, and persisted evidence contain no coordinate pair;
- Telegram formatting includes the typed Maps URL and accuracy;
- SMS formatting excludes coordinates, Maps URLs, labels, handles, and tracking text;
- incident repository v2 files still load with `location == null` and new v3 records round-trip typed location.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*AndroidProtectionRuntimeTest' --tests '*FileIncidentRepositoryTest' --tests '*IncidentMessageFormatterTest' --tests '*IncidentDeliveryCoordinatorTest' --tests '*LocationObservationProviderTest'
```

- [ ] **Step 3: Split channel formatting**

Use explicit methods rather than one message reused by both transports:

```kotlin
fun formatTelegram(incident: SecurityIncident, presentation: LocationPresentation?): String
fun formatSms(incident: SecurityIncident): String
```

`IncidentDeliveryCoordinator.deliver()` sends the Telegram form first and constructs the SMS form only after Telegram failure and existing critical/SMS eligibility checks. Do not change when SMS is eligible; change only the content boundary.

- [ ] **Step 4: Version incident persistence**

Bump `FILE_VERSION` from `2` to `3`. Keep dedicated v1 and v2 readers. Write a presence boolean followed by latitude, longitude, accuracy, and captured wall time for v3. Reject invalid numeric/range values on read by replacing the location with `null`; do not discard the whole incident.

- [ ] **Step 5: Run GREEN and scan leakage**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*AndroidProtectionRuntimeTest' --tests '*FileIncidentRepositoryTest' --tests '*IncidentMessageFormatterTest' --tests '*IncidentDeliveryCoordinatorTest' --tests '*LocationObservationProviderTest'
rg -n "fix lat=|lon=|maps.google|latitude|longitude" app/src/main/java/com/example/motorcycleantitheftsensor/protection app/src/main/java/com/example/motorcycleantitheftsensor/sensor
```

Expected scan: coordinates appear only in typed models, validation, repository serialization, and explicit Telegram presentation—not diagnostic strings or SMS construction.

---

### Task 2: Make Android Provider Registration Truthful and Race-Safe

**Files:**

- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/LocationObservationProvider.kt`
- Create/modify: `app/src/main/java/com/example/motorcycleantitheftsensor/location/LocationFixArbiter.kt`
- Test: `LocationObservationProviderTest.kt`, `LocationFixArbiterTest.kt`.

**Interfaces:**

```kotlin
enum class LocationTrackingMode { ARMED, PURSUIT }

data class LocationTrackingRequest(
    val mode: LocationTrackingMode,
    val minTimeMs: Long,
    val minDistanceMeters: Float,
    val providers: List<String>,
)

fun interface LocationRegistration { fun cancel() }

sealed interface LocationRegistrationResult {
    data class Started(val registration: LocationRegistration) : LocationRegistrationResult
    data class Unavailable(val reason: LocationStartFailure) : LocationRegistrationResult
}
```

`LocationUpdatesClient.register()` returns a registration owned by the caller. `AndroidLocationUpdatesClient` registers only enabled GPS/network providers requested by policy, removes partial registrations on total failure, and never activates passive/arbitrary providers.

- [ ] **Step 1: Add RED failure and race tests**

Use a blocking fake registration to prove:

- failed ARMED start leaves mode STOPPED and a second call retries registration;
- failed PURSUIT transition remains in ARMED and is retryable;
- stop racing a late registration cancels the returned registration and forwards no fix;
- ARMED request is exactly `10_000L`, `5f`, GPS then network;
- PURSUIT request is exactly `5_000L`, `5f`, GPS then network;
- passive/custom providers are never registered;
- callbacks with invalid coordinates/accuracy, future time, or non-increasing elapsed time never leave the provider;
- valid GPS/network duplicates are reduced to one monotonic stream.

- [ ] **Step 2: Add the arbiter tests**

The arbiter accepts the newest valid fix, rejects older/equal elapsed timestamps, and for fixes within the same one-second bucket prefers the lower accuracy radius. Do not use wall clock for ordering.

- [ ] **Step 3: Run RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*LocationObservationProviderTest' --tests '*LocationFixArbiterTest' --tests '*MovementDisplacementPolicyTest'
```

- [ ] **Step 4: Implement two-phase registration**

Allocate a generation under the provider lock, call `register()` outside the lock, then commit the returned registration only if the generation and desired mode still match. Otherwise cancel it immediately. Never invoke a consumer callback while holding the provider lock.

- [ ] **Step 5: Run GREEN**

Run the Task 2 command with `--rerun-tasks`. Expected: all provider, arbiter, and movement-policy tests pass with no delay-based race tests.

---

### Task 3: Bound and Order Fix Ingress

**Files:**

- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/location/ConflatedLocationFixIngress.kt`
- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/location/ConflatedLocationFixIngressTest.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinator.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinatorTest.kt`

**Interfaces:**

```kotlin
interface LocationFixIngress {
    fun offer(fix: TrackedLocationFix): Boolean
    suspend fun close()
}

class ConflatedLocationFixIngress(
    scope: CoroutineScope,
    consume: suspend (TrackedLocationFix) -> Unit,
) : LocationFixIngress
```

The ingress has capacity one plus one active consumer. A newer offered fix replaces only a queued fix; it never cancels a fix currently being processed. `close()` rejects later offers and joins the consumer.

- [ ] **Step 1: Add RED burst/order tests**

Prove a burst of 1,000 monotonic fixes creates one consumer job, bounded queued work, and delivers the first in-flight plus latest queued fix. Prove an older timestamp offered after a newer one is rejected. Prove close drains/cancels deterministically without `Thread.sleep()`.

- [ ] **Step 2: Add coordinator monotonic regression tests**

Pause the first coordinator call, offer newer then older fixes, release it, and assert Telegram never receives a coordinate older than the last processed/published elapsed timestamp.

- [ ] **Step 3: Run RED, implement, and run GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*ConflatedLocationFixIngressTest' --tests '*LivePursuitCoordinatorTest'
```

Replace `scope.launch { onLocationFix(fix) }` in the Android callback with `ingress.offer(fix)`. Keep direct suspending `onLocationFix()` for deterministic unit tests.

---

### Task 4: Persist Anchor and Attempt State Atomically

**Files:**

- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/location/MovementTrackingStore.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/data/EncryptedPrefsManager.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/location/MovementTrackingStoreTest.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinator.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinatorTest.kt`

**Interfaces:**

```kotlin
data class MovementTrackingState(
    val schemaVersion: Int = 2,
    val anchor: ParkingAnchor? = null,
    val session: PersistedLivePursuitSession? = null,
)

interface MovementTrackingStore {
    suspend fun load(): MovementTrackingState
    suspend fun save(state: MovementTrackingState): Boolean
    suspend fun clear(): Boolean
}
```

One encrypted preference key stores one JSON object. On first load, valid legacy anchor/session keys are migrated in one commit; legacy keys are removed only after the new commit succeeds.

- [ ] **Step 1: Add RED atomicity/migration tests**

Prove round-trip, legacy migration, malformed/oversized arrays, invalid coordinates, empty session IDs, non-positive message IDs, mismatched anchor/session IDs, clear failure, and write failure. A mismatch loads a fail-closed attempt marker and never permits a duplicate Telegram start.

- [ ] **Step 2: Add RED durable-effect tests**

When saving the empty-handle attempt marker fails, assert zero Telegram calls and a truthful internal failure state. When saving returned handles fails after Telegram start, assert those new handles are stopped before returning.

- [ ] **Step 3: Run RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*MovementTrackingStoreTest' --tests '*LivePursuitCoordinatorTest'
```

- [ ] **Step 4: Implement one-record persistence**

Perform encrypted preference `commit()` on `Dispatchers.IO`. It is acceptable to hold the coroutine state mutex across the short durable commit; never hold it across Geocoder or Telegram calls.

- [ ] **Step 5: Run GREEN**

Run Task 4 with `--rerun-tasks`. Expected: no remote start is possible without a durable attempt record.

---

### Task 5: Add a Typed, Cancellable Telegram Live Location API

**Files:**

- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramLiveLocationApi.kt`
- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramLiveLocationApiTest.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramLiveLocationTransport.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramLiveLocationTransportTest.kt`
- Modify only if required for delegation: `TelegramBotClient.kt` and its source-contract test.

**Interfaces:**

```kotlin
sealed interface TelegramCallResult<out T> {
    data class Success<T>(val value: T) : TelegramCallResult<T>
    data class Retryable(val retryAfterMs: Long?) : TelegramCallResult<Nothing>
    data class Terminal(val code: TelegramFailureCode) : TelegramCallResult<Nothing>
}

enum class TelegramFailureCode {
    UNAUTHORIZED, FORBIDDEN, MESSAGE_UNAVAILABLE, INVALID_RESPONSE, INVALID_REQUEST
}
```

Use OkHttp `Call.enqueue()` with `suspendCancellableCoroutine`; cancellation calls `Call.cancel()`. Use a live-location client derived from the pinned client with `callTimeout(10, SECONDS)` while preserving its connection pool, TLS policy, and certificate policy.

- [ ] **Step 1: Add RED protocol tests**

Cover success, missing/zero `message_id`, malformed JSON, `ok:false`, HTTP 401/403/400/429/500, IO failure, cancellation, and 10-second call timeout. Parse `parameters.retry_after` into milliseconds. Never expose Telegram `description` outside the API parser.

- [ ] **Step 2: Add parameter validation tests**

Reject before network I/O when latitude/longitude are invalid, accuracy is outside `0..1500`, `live_period` is outside `60..86400`, chat ID is blank, or message ID is non-positive. The production coordinator continues to use `900` seconds and accuracy at most `100 m`.

- [ ] **Step 3: Add owner-revocation tests**

Start may target only `getAllowedChatIds()`. Update must return `Terminal(FORBIDDEN)` without network I/O if the handle chat ID is no longer allowed. Stop remains permitted for a stored handle after revocation so location sharing can be terminated.

- [ ] **Step 4: Run RED, implement, and run GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*TelegramLiveLocationApiTest' --tests '*TelegramLiveLocationTransportTest' --tests '*TelegramBotClientAuthorizationTest' --tests '*TelegramBotClientSourceContractTest'
```

Expected: no synchronous blocking OkHttp execution remains on the Live Pursuit path; generic Telegram polling/message behavior is unchanged.

---

### Task 6: Make Publishing Success-Aware and Retry-Bounded

**Files:**

- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinator.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/LivePursuitCoordinatorTest.kt`

**Interfaces:**

```kotlin
private data class HandlePublicationState(
    val handle: LiveLocationHandle,
    val lastSuccessfulFix: TrackedLocationFix,
    val consecutiveRetryableFailures: Int = 0,
    val retryNotBeforeElapsedMs: Long = 0L,
)
```

Throttle uses each handle's last successful fix, never its last attempted fix. Retry delay is `max(serverRetryAfter, 5s, min(60s, 5s * 2^failures))`. A success resets failures. A terminal failure removes the handle and atomically persists the remaining list.

The normal publication gate opens after `10_000 ms` or `10 m` from the last successful fix, but a hard `5_000 ms` attempt floor applies even during fast movement. A retryable failure is also subject to `retryNotBeforeElapsedMs`; the stricter of the attempt floor and retry delay wins.

- [ ] **Step 1: Add RED result-state tests**

Prove:

- a failed update does not advance `lastSuccessfulFix`;
- a retryable failure retries after the bounded delay and not before;
- Telegram 429 honors the larger server delay;
- terminal owner revocation/message loss removes only that handle;
- zero surviving handles exits PURSUIT cadence but keeps the one-attempt marker;
- a newer queued fix replaces an older queued retry;
- disarm/expiry remains the final remote action after an admitted update.

- [ ] **Step 2: Add wall-clock recovery clamps**

Persist the Telegram wall-clock expiry, but on recovery clamp remaining duration to `0..900_000 ms`. A wall clock moved backward cannot extend a recovered session beyond 15 minutes from recovery; a clock moved forward expires immediately.

- [ ] **Step 3: Run RED, implement, and run GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*LivePursuitCoordinatorTest' --tests '*MovementTrackingStoreTest' --tests '*TelegramLiveLocationTransportTest'
```

Keep remote ordering under `remoteMutex`, but do not hold `stateMutex` during a Telegram call. Do not add unlimited retries; one armed cycle still has one start attempt.

---

### Task 7: Make Thai Location Presentation Non-Blocking and Testable

**Files:**

- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/location/LocationLabelResolver.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/location/LocationLabelResolverTest.kt`
- Modify: `IncidentMessageFormatterTest.kt`, `LivePursuitCoordinatorTest.kt`.

**Interfaces:**

```kotlin
data class LocationPresentation(
    val labelTh: String?,
    val mapsUrl: String,
    val accuracyMeters: Int,
)

fun interface GeocoderGateway {
    suspend fun reverse(latitude: Double, longitude: Double): List<Address>
}
```

Use `Locale.forLanguageTag("th-TH")`. On API 33+, bridge `Geocoder.getFromLocation(..., GeocodeListener)` without blocking a thread. On API 24-32, isolate the deprecated blocking call on a dedicated limited-parallelism IO context; timeout ignores the late result and prevents a second in-flight legacy request until the first finishes.

- [ ] **Step 1: Replace fake-interface tests with production-boundary tests**

Inject `GeocoderGateway` and test feature, thoroughfare, sublocality, locality, admin area ordering; blank/duplicate parts; Thai/English fallback; 160-character cap; invalid fix; empty result; exception; 1.5-second timeout; and late callback suppression.

- [ ] **Step 2: Test deterministic presentation**

Maps URL is generated from the fix using locale-independent decimal formatting; for example, latitude `13.756300` and longitude `100.501800` produce `https://maps.google.com/?q=13.756300,100.501800`. It is always present for a valid fix even when Geocoder is absent, slow, or inaccurate. Accuracy is rounded up to a whole meter.

- [ ] **Step 3: Preserve alert latency ordering**

Native Telegram Live Location starts before reverse geocoding. The text alert may wait at most `1_500 ms`; on timeout it sends Maps URL + accuracy without a blank label. Ordinary Telegram incident formatting uses the same bounded presenter. SMS never receives `LocationPresentation`.

- [ ] **Step 4: Run GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*LocationLabelResolverTest' --tests '*IncidentMessageFormatterTest' --tests '*IncidentDeliveryCoordinatorTest' --tests '*LivePursuitCoordinatorTest'
```

---

### Task 8: Make Android 14+ Location FGS and Shutdown Explicit

**Files:**

- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt`
- Modify only after device/API evidence: `app/src/main/AndroidManifest.xml`, permission UI/policy/tests.
- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/service/LocationForegroundPolicyTest.kt`
- Modify: `LivePursuitCoordinator.kt`, `SensorServiceControllerTest.kt`, relevant instrumentation tests.

**Interfaces:**

```kotlin
data class LocationForegroundDecision(
    val mayUseLocationType: Boolean,
    val degradationReason: String? = null,
)

suspend fun LivePursuitCoordinator.prepareForStop()
fun LivePursuitCoordinator.abortLocal()
```

- [ ] **Step 1: Add RED pure-policy tests**

Cover API 24/29/33/34/36, app visible/background, service already running/newly created, fine/coarse/background permission, notification action start, boot recovery, and remote Telegram arm. `checkSelfPermission(FINE/COARSE) == GRANTED` alone must not imply safe background location-FGS startup on API 34+.

- [ ] **Step 2: Choose behavior from controlled API 34+ evidence**

Default safe behavior is: start/upgrade to location FGS only from an allowed visible or notification-interaction path; otherwise remain ARMED_DEGRADED with a clear Thai action asking the owner to open the app. Add `ACCESS_BACKGROUND_LOCATION` only if the product owner explicitly accepts the permission/policy impact and API 34+ device evidence proves remote/boot tracking requires it. Do not add the permission preemptively.

- [ ] **Step 3: Make controlled shutdown awaited without blocking main**

Route user/controller Stop through suspending `prepareForStop()` before `stopSelf()`: close fix ingress, invalidate generation, stop local tracking, persist final state, and best-effort stop handles under a 10-second overall timeout. `SensorService.onDestroy()` remains non-blocking and idempotent: it calls `abortLocal()` to synchronously invalidate callbacks and unregister local tracking, then returns. Unexpected process/service destruction relies on the durable attempt/handle record for recovery and Telegram's 900-second expiry; it must not erase handles before a remote stop succeeds. Never use `runBlocking`, `Thread.join`, network IO, or encrypted preference commits on the main thread.

- [ ] **Step 4: Run host tests**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*LocationForegroundPolicyTest' --tests '*SensorServiceControllerTest' --tests '*LivePursuitCoordinatorTest'
```

- [ ] **Step 5: Run API 34+ background-start instrumentation**

Exercise visible app start, notification action, Telegram remote arm while the service is already running, reboot recovery, permission revoke, and provider toggle. Capture only exception class/state transitions; redact coordinates and identifiers.

---

### Task 9: Full Verification and Fixed-Power Device Acceptance

**Files:**

- Verify only: all Task 1-8 files plus manifest/network security configuration.
- Update this plan's evidence section only after each command actually completes.

- [ ] **Step 1: Run focused GPS suites fresh**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*Location*Test' --tests '*Movement*Test' --tests '*LivePursuitCoordinatorTest' --tests '*IncidentMessageFormatterTest' --tests '*IncidentDeliveryCoordinatorTest' --tests '*FileIncidentRepositoryTest' --tests '*TelegramLiveLocation*Test' --tests '*LocationForegroundPolicyTest'
```

Record test count, failures, errors, skipped from `app/build/test-results/testDebugUnitTest/TEST-*.xml`.

- [ ] **Step 2: Run full unit and APK gates sequentially**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:assembleDebug
Get-FileHash .\app\build\outputs\apk\debug\app-debug.apk -Algorithm SHA256
```

Expected: exit 0, zero test failures/errors, `BUILD SUCCESSFUL`, and a recorded new APK SHA-256. SDK XML warnings are non-blocking only when exit is 0.

- [ ] **Step 3: Scan scoped source for leakage and forbidden coupling**

```powershell
rg -n "fix lat=|lon=|System\.out|println\(|api\.telegram\.org/bot" app/src/main/java/com/example/motorcycleantitheftsensor/location app/src/main/java/com/example/motorcycleantitheftsensor/sensor/LocationObservationProvider.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection app/src/main/java/com/example/motorcycleantitheftsensor/telegram
rg -n "maps\.google|latitude|longitude|LocationPresentation" app/src/main/java/com/example/motorcycleantitheftsensor/telephony app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentDeliveryCoordinator.kt
git diff --check
```

Expected: no raw diagnostic/log leak, no token-bearing log, no SMS location presentation, and zero whitespace errors in scoped changes.

- [ ] **Step 4: Install only on the owner-approved device**

```powershell
$adb='C:\Users\ASUS\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb devices -l
$targetSerial=Read-Host 'Enter the approved target serial shown above'
& $adb -s $targetSerial install -r '.\app\build\outputs\apk\debug\app-debug.apk'
```

Never uninstall, clear data, or guess a serial. Record the installed APK SHA-256 and redact the serial in reports.

- [ ] **Step 5: Execute the two-phone fixed-power matrix**

| Scenario | Action | Required result |
|---|---|---|
| Calibration isolation | `/arm`, observe 10 seconds | No anchor/Live Location during ARMING |
| First-fix latency | Arm outdoors with location enabled | First usable fix within 15 seconds; no duplicate provider-derived event |
| Confirmed movement | Move beyond threshold | Exactly one native owner Live Location, normally within 25 seconds after two qualifying fixes |
| High-rate burst | Feed/walk through dense GPS + network callbacks | No coroutine growth, ANR, out-of-order Telegram position, or duplicate start |
| Telegram coalescing | Continue moving for 5 minutes | Same message; normal gate is 10 seconds or 10 m, but attempts are never faster than one per 5 seconds per handle; no 429 storm |
| Network loss | Disable data for 60 seconds, continue moving, restore | Local tracking continues; latest fix is sent after bounded retry; no backlog replay |
| Telegram 429/5xx | Use test transport/staging response | Retry respects backoff/Retry-After; disarm remains responsive |
| Provider toggle | Disable/re-enable GPS while armed | Network fallback remains; GPS registration recovers truthfully without duplicate listener |
| Owner revocation | Remove owner during pursuit | Updates to revoked owner stop; stored handle can still receive final stop request |
| Disarm race | Disarm during in-flight update/label | Tracking stops and stop is the final remote action |
| Process kill | Kill process without normal service callback, relaunch | One durable attempt; no duplicate Live Location; unexpired handle recovery is coherent |
| Normal service stop | Stop service normally | Awaited best-effort stop completes; no later update |
| Clock change | Move wall clock forward/back during session | Recovery remaining time is clamped to 0..15 minutes |
| API 34+ background path | Remote arm/reboot while app not visible | No SecurityException; either allowed tracking or explicit ARMED_DEGRADED guidance |
| Geocoder unavailable | Disable network/geocoder backend | Native map and Maps URL still arrive; label omission is explicit, not blank |
| Incident Telegram | Trigger an incident with a fresh typed fix | Telegram contains valid Maps URL/accuracy and optional Thai label |
| SMS fallback | Force eligible Telegram failure in controlled test | Encrypted SMS contains no coordinate, Maps URL, label, handle, or tracking state |
| Thermal soak | Keep charged and armed for 2 hours, then pursue 15 minutes | No ANR/crash/listener multiplication; record device temperature trend without coordinates |

- [ ] **Step 6: Report evidence without overclaiming**

```text
Source verification: scoped files and leakage scan
Host verification: exact commands, exit codes, test counts, APK SHA-256
Device verification: installed SHA-256, Android/device class, each scenario PASS/FAIL/NOT RUN
Performance: first-fix latency, movement-to-Live-Location latency, callback offered/consumed/dropped counts, Telegram attempts/success/retry counts
Remaining risk: OEM background policy, satellite visibility, Android permission path, Telegram availability, Geocoder accuracy
```

Do not claim production readiness while any required device scenario is FAIL or NOT RUN.

## Final Acceptance Criteria

- Provider mode is truthful after every success/failure/race; stop cannot leave a late Android registration active.
- Only GPS and network providers are actively registered under the exact fixed-power 10-second/5-second profiles.
- Fix processing has bounded memory, one consumer, monotonic ordering, shared validity rules, and no raw-coordinate diagnostics.
- Movement confirmation and one-attempt-per-armed-cycle semantics remain unchanged.
- No Telegram start occurs without a durable atomic attempt marker; save failure fails closed.
- Telegram calls are cancellable and bounded; missing message ID is failure; retryable versus terminal results are explicit.
- Publication throttling is based on the last successful fix, uses the 10-second-or-10-metre normal gate, and enforces the absolute 5-second attempt floor. Network failure cannot silently suppress a later useful retry.
- Disarm/expiry/shutdown remains the final remote action after any admitted update; no queued stale work runs after generation invalidation.
- Native Live Location starts before reverse geocoding. Thai label resolution is best-effort and bounded; Maps URL/accuracy fallback is always usable.
- Coordinates can reach only currently paired owner Telegram destinations. SMS, logs, UI health, generic diagnostics, and accessibility data contain no coordinates.
- API 34+ background/FGS behavior is device-verified or fails into explicit ARMED_DEGRADED guidance without a crash.
- Fresh focused/full host gates pass, a fresh APK is installed, and the full two-phone fixed-power matrix passes before completion is claimed.
