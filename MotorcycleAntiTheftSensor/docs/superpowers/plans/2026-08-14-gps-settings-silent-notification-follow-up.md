# GPS, Settings Verification, and Silent Service Notification Follow-up Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the remaining GPS recovery/concurrency defects, guarantee that Telegram Bot verification cannot leave Settings spinning forever, and stop repeated foreground-service heads-up notifications while retaining Android's required silent ongoing notification.

**Architecture:** Keep the current framework `LocationManager`, encrypted movement record, owner-only Telegram transport, and authoritative `ProtectionCoordinator`. Make verification a cancellable suspend operation with a 10-second network deadline and 12-second UI deadline; make GPS state changes compute under a state lock but perform disk/network work outside it; use one atomic lifecycle generation for synchronous invalidation. Move the service notification to a new low-importance silent channel and suppress identical updates.

**Tech Stack:** Kotlin, Android SDK 24-36, coroutines/Flow, Jetpack Compose, OkHttp 4.12.0, encrypted preferences, JUnit 4, kotlinx-coroutines-test, Mockito Kotlin.

## Global Constraints

- Work from `D:\security\MotorcycleAntiTheftSensor` on the existing branch. The reviewed baseline was `feature/motorcycle-guard-protection` at HEAD `c168ae9` with a mixed dirty worktree.
- Read `D:\security\AGENTS.md`. `AI_WORKFLOW.md` was not present during review; do not invent it.
- Preserve unrelated tracked and untracked work. Never use `git reset`, `git clean`, broad staging, file moves, or repository-wide formatting.
- Do not commit unless the user explicitly authorizes a commit after scoped review.
- Do not add Google Play Services, HMS Location, MockWebServer, Fused Location, remote geocoding, or any new dependency.
- This is a fixed-power profile. Battery duration is not an acceptance criterion; thermal stability and bounded resource/network behavior are.
- Preserve ARMED GPS+Network at `10_000 ms` / `5 m`, PURSUIT at `5_000 ms` / `5 m`, two outside fixes separated by `15_000 ms`, and the existing displacement threshold.
- Preserve one Telegram Live Location start attempt per armed cycle, `900 s` duration, normal publish gate `10_000 ms` or `10 m`, and hard `5_000 ms` attempt floor.
- Coordinates, Maps URLs, labels, handles, and anchors may reach only currently paired Telegram owners. Never place them in SMS, logs, UI health, generic diagnostics, accessibility text, or raw exceptions.
- Keep HTTPS-only Telegram and current TLS pins. Do not change `network_security_config.xml` or pin values.
- A rejected, failed, timed-out, or cancelled replacement token must preserve the previous token and must not refresh polling.
- `Verified`, `Rejected`, and `ConnectionFailure` remain distinct and token-free.
- “Remove popup” means no repeated system heads-up from the foreground service. Keep one silent ongoing tray notification because Android requires it.
- Preserve the existing one-shot Settings Snackbar and persistent status cards; do not add another event bus.
- Never block main. Do not use `runBlocking`, `Thread.join`, synchronous OkHttp, encrypted preference commits, or reverse geocoding on main.
- Every production edit follows RED → minimal GREEN → focused regression.

## Confirmed Review Baseline

- Focused GPS/Settings: `146` tests, `0` failures/errors/skipped.
- Full unit: `283` tests, `0` failures/errors/skipped.
- `:app:assembleDebug`: exit `0`.
- APK SHA-256: `6D8566A46673D0074C48E2D3AF46B35B419BA14E7B0DAE583A6E9CB78FA8AC11`.
- `git diff --check`: failed on 14 whitespace/EOF findings in the dirty worktree.
- Device install, two-phone pursuit, Settings timeout, Android 14 background, and heads-up acceptance: `NOT RUN`.

Host GREEN does not close these source findings:

1. `store.load/save/clear` still run under `stateMutex`.
2. stop/expiry/disarm erase persisted handles before Telegram stop succeeds.
3. `abortLocal()` uses different synchronization from the coordinator state.
4. the shutdown timeout excludes ingress draining and persistence.
5. failed PURSUIT-to-ARMED registration reports ARMED while PURSUIT remains active.
6. the provider “race” test is sequential rather than concurrent.
7. recovery, ordering, clock, persistence-stall, and stop-failure tests are missing.
8. `activeSessionId` survives `OFFLINE` and `SETUP_REQUIRED`.
9. movement alert omits Maps URL/accuracy fallback.
10. location-label code can swallow coroutine cancellation.
11. Telegram API can leave a late response unclosed after cancellation.
12. Settings verification has no deadline and its worker can exit without callback.
13. service notification uses HIGH importance/priority and repeats updates.
14. Android 14 policy treats service foreground state as Activity/process visibility.

## File Map

**Create**

- `app/src/main/java/com/example/motorcycleantitheftsensor/service/ForegroundNotificationPolicy.kt`
- `app/src/test/java/com/example/motorcycleantitheftsensor/service/ForegroundNotificationPolicyTest.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/service/AppVisibilityProvider.kt`
- `app/src/test/java/com/example/motorcycleantitheftsensor/service/AppVisibilityProviderTest.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/location/LocationPresentationFactory.kt`
- `app/src/test/java/com/example/motorcycleantitheftsensor/location/LocationPresentationFactoryTest.kt`
- `docs/superpowers/status/2026-08-14-gps-settings-notification-follow-up-evidence.md`

**Modify**

- `telegram/TelegramBotVerifier.kt`, `telegram/TelegramBotClient.kt`, `telegram/TelegramLiveLocationApi.kt`
- `ui/AndroidProtectionSettingsGateway.kt`, `ui/ProtectionViewModel.kt`
- `service/SensorService.kt`, `service/LocationForegroundPolicy.kt`
- `sensor/LocationObservationProvider.kt`
- `location/ConflatedLocationFixIngress.kt`, `location/LocationLabelResolver.kt`
- `protection/LivePursuitCoordinator.kt`, `protection/ProtectionRuntimeGraph.kt`, `protection/IncidentDeliveryCoordinator.kt`, `protection/ProtectionCoordinator.kt`, `protection/ProtectionModels.kt`
- Focused tests named in Tasks 1-7.

---

### Task 0: Freeze the Dirty Baseline

**Files:** Create `docs/superpowers/status/2026-08-14-gps-settings-notification-follow-up-evidence.md`.

**Interfaces:** Consumes repository/test/APK state; produces an evidence-only record with no secrets or coordinates.

- [ ] **Step 1: Record repository identity without changing it**

```powershell
git branch --show-current
git rev-parse --short HEAD
git status --short
git diff --stat
```

If HEAD differs from `c168ae9`, inspect the new scoped changes before continuing. Read untracked GPS files directly because `git diff` omits them.

- [ ] **Step 2: Run the pre-change focused gate**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_OPTS='-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8'
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*Location*Test' --tests '*Movement*Test' --tests '*LivePursuitCoordinatorTest' --tests '*AndroidProtectionSettingsGatewayTest' --tests '*TelegramBotVerifierTest' --tests '*ProtectionViewModelTest'
```

- [ ] **Step 3: Write exact evidence**

Record branch, HEAD, dirty scope, command, exit, XML totals, findings 1-14, and `Device verification: NOT RUN`. Do not record tokens, chat IDs, pairing/TOTP codes, serials, or coordinates.

---

### Task 1: Make Bot Verification Cancellable and Time-Bounded

**Files:** Modify `TelegramBotVerifier.kt`, `TelegramBotClient.kt:301-315`, `AndroidProtectionSettingsGateway.kt:64-82,160-216`, `ProtectionViewModel.kt:144-175,306-320`, and their four focused test files.

**Interfaces:**

```kotlin
internal fun interface TelegramVerificationTransport {
    suspend fun execute(request: Request): TelegramVerificationHttpResponse
}

internal class OkHttpTelegramVerificationTransport(
    pinnedClient: OkHttpClient,
    callTimeoutSeconds: Long = 10L,
) : TelegramVerificationTransport

internal class TelegramBotVerifier(
    private val transport: TelegramVerificationTransport,
) {
    suspend fun verify(rawToken: String): TelegramBotVerificationResult
}

internal suspend fun TelegramBotClient.verifyBotTokenResult(
    token: String,
): TelegramBotVerificationResult

internal interface AndroidProtectionSettingsOperations {
    suspend fun verifyBotToken(token: String): TelegramBotVerificationResult
}
```

Production uses the existing pinned client plus `.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()`, `Call.enqueue`, and cancellable continuation. Gateway timeout is `12_000L`.

- [ ] **Step 1: Add RED verifier tests**

```kotlin
@Test fun runtimeTransportFailureReturnsTokenFreeConnectionFailure()
@Test fun cancellingVerificationCancelsTheTransport()
@Test fun verifiedRejectedAndConnectionFailureRemainDistinct()
```

Use an injected transport throwing `IllegalStateException("synthetic failure")`; assert `ConnectionFailure` and no submitted token in the result. For cancellation, suspend with `awaitCancellation()`, signal a `CompletableDeferred<Unit>` in `finally`, cancel the job, and assert the signal completes.

- [ ] **Step 2: Add RED gateway/ViewModel deadline tests**

```kotlin
@Test fun verificationWithoutResultTimesOutAndRetainsExistingToken()
@Test fun timeoutDoesNotRefreshPolling()
@Test fun gatewayCancellationPropagates()
@Test fun timeoutAlwaysClearsSettingsOperationInFlight()
```

Inject `verificationTimeoutMs`. Use `runTest` and a fake suspending operation that calls `awaitCancellation()`; advance virtual time past the deadline. Assert `applied == false`, old token unchanged, refresh count zero, and UI spinner false.

- [ ] **Step 3: Run RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*TelegramBotVerifierTest' --tests '*TelegramBotClientSourceContractTest' --tests '*AndroidProtectionSettingsGatewayTest' --tests '*ProtectionViewModelTest'
```

Expected RED: callback API does not match the suspend contract and the hanging fake has no deadline.

- [ ] **Step 4: Implement cancellable verification**

Cancellation calls `Call.cancel()`. A response arriving after cancellation is closed. Use `response.use`. Catch and rethrow `CancellationException` first. For any later `RuntimeException` branch, call `currentCoroutineContext().ensureActive()` before converting a still-active, non-cancellation failure to `ConnectionFailure`. Convert `IOException` to the same typed failure. Never log URL/token/body/certificate/exception.

- [ ] **Step 5: Remove the callback-only worker path**

Make client and Settings operations suspending. Use this gateway boundary:

```kotlin
withTimeoutOrNull(verificationTimeoutMs) {
    operations.verifyBotToken(candidate)
} ?: TelegramBotVerificationResult.ConnectionFailure
```

Run `rg -n 'verifyBotToken(Result)?\(' app/src/main app/src/test`; remove the raw verification `thread { ... }` only after no callback caller remains.

- [ ] **Step 6: Run GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*TelegramBotVerifierTest' --tests '*TelegramBotClientSourceContractTest' --tests '*AndroidProtectionSettingsGatewayTest' --tests '*ProtectionViewModelTest'
```

---

### Task 2: Replace Repeating Heads-up with One Silent Notification

**Files:** Create `ForegroundNotificationPolicy.kt` and its test; modify `SensorService.kt:59-64,340-425`. Retain the existing Compose one-shot Snackbar test.

**Interfaces:**

```kotlin
data class ForegroundNotificationFingerprint(val text: String, val foregroundTypes: Int)
data class ForegroundNotificationSpec(
    val channelId: String = "anti_theft_protection_silent_v2",
    val silent: Boolean = true,
    val onlyAlertOnce: Boolean = true,
    val ongoing: Boolean = true,
)
class ForegroundNotificationPolicy {
    fun shouldPublish(previous: ForegroundNotificationFingerprint?, next: ForegroundNotificationFingerprint): Boolean = previous != next
}
```

- [ ] **Step 1: Add RED policy tests**

```kotlin
@Test fun serviceNotificationContractIsSilentOngoingAndVersioned()
@Test fun identicalNotificationIsNotRepublished()
@Test fun changedTextOrForegroundTypesIsRepublished()
```

Assert channel ID `anti_theft_protection_silent_v2`. A new ID is required because Android does not reliably lower an already-installed channel's importance.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*ForegroundNotificationPolicyTest'
```

- [ ] **Step 3: Implement exact silent Android mapping**

Use `IMPORTANCE_LOW`, `PRIORITY_LOW`, `setSilent(true)`, `setOnlyAlertOnce(true)`, `setOngoing(true)`, `setSound(null, null)`, disabled vibration, and disabled lights. Do not delete the old HIGH channel; simply never post to it.

- [ ] **Step 4: Deduplicate updates**

Keep the last successfully published fingerprint. Return before `notify()` when text and foreground types are unchanged. Update the fingerprint only after `startForeground` or `notify` succeeds. Do not suppress the initial foreground notification.

- [ ] **Step 5: Preserve one-shot app feedback and run GREEN**

Keep `ProtectionUiMessage`, `SnackbarHost`, `LaunchedEffect(message?.id)`, and `consumeMessage`. Run:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*ForegroundNotificationPolicyTest'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:compileDebugAndroidTestKotlin
```

---

### Task 3: Make Provider Mode Truthful and Test a Real Race

**Files:** Modify `LocationObservationProvider.kt:93-99,234-400`, its test, and `LivePursuitCoordinatorTest` fakes.

**Interfaces:**

```kotlin
interface MovementLocationTracking {
    fun startArmedTracking(onFix: (TrackedLocationFix) -> Unit): Boolean
    fun enterPursuitMode(): Boolean
    fun exitPursuitMode(): Boolean
    fun stopTracking()
    fun isTracking(): Boolean
    fun currentUsableFix(nowElapsedMs: Long): TrackedLocationFix?
}
```

`true` means the requested cadence is the active Android registration. `false` means the previous valid registration remains active, or STOPPED when no previous registration exists.

- [ ] **Step 1: Add RED transition tests**

```kotlin
@Test fun failedPursuitUpgradeKeepsArmedRegistrationAndReturnsFalse()
@Test fun failedArmedDowngradeKeepsPursuitRegistrationAndReturnsFalse()
@Test fun successfulDowngradeCancelsOldRegistrationAfterSwap()
@Test fun isTrackingReflectsARealRegistration()
```

- [ ] **Step 2: Replace the sequential race test**

Make fake `register` signal `enteredRegister`, block on `releaseRegister`, then return. Start registration on an executor, wait for entry, call `stopTracking`, release registration, and assert the late registration is cancelled exactly once, `isTracking()` is false, and callback is rejected. Do not use sleeps.

- [ ] **Step 3: Run RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*LocationObservationProviderTest' --tests '*LivePursuitCoordinatorTest'
```

- [ ] **Step 4: Implement atomic registration swap**

Do not change logical mode until new registration succeeds. On success install registration/generation/mode atomically, then cancel the old registration outside the lock. On failure preserve the prior registration/mode/generation. Initial armed failure remains STOPPED and retryable. Invoke user callback outside the lock and keep diagnostics coordinate-free.

- [ ] **Step 5: Run GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*LocationFixArbiterTest' --tests '*LocationObservationProviderTest' --tests '*LivePursuitCoordinatorTest'
```

---

### Task 4: Remove State-Lock I/O and Make Stop/Recovery Durable

**Files:** Modify `ConflatedLocationFixIngress.kt`, `LivePursuitCoordinator.kt`, `ProtectionRuntimeGraph.kt:271-282`, `ProtectionModels.kt`, `ProtectionCoordinator.kt`, and their focused tests.

**Interfaces:**

```kotlin
interface LocationFixIngress {
    fun offer(fix: TrackedLocationFix): Boolean
    fun cancelPending()
    suspend fun awaitClosed()
}

private val lifecycleGeneration = AtomicLong(0L)

private data class StopOutcome(
    val stopped: List<LiveLocationHandle>,
    val retainedForRecovery: List<LiveLocationHandle>,
)
```

`cancelPending()` closes the channel and cancels the consumer immediately. Disarm/expiry/shutdown discard queued fixes rather than draining them.

- [ ] **Step 1: Add the missing RED recovery matrix**

```kotlin
@Test fun oldStructurallyValidAnchorRecoversWithoutFreshnessCheck()
@Test fun directEligibleRecoveryWithoutIdUsesInjectedFactoryOnce()
@Test fun alertActiveIsEligibleForRecovery()
@Test fun mismatchedAnchorAndSessionFailsClosedWithoutNewStart()
@Test fun expiredRecoveredHandlesAreStoppedAndNotUpdated()
@Test fun clockRollbackCannotExtendRecoveryBeyondFifteenMinutes()
@Test fun clockForwardPastExpiryStopsImmediately()
@Test fun offlineThenNewArmingUsesANewSessionId()
```

Use injected clocks and `armedSessionIdFactory`. Assert exact IDs, store state, start count, stop count, and action order.

- [ ] **Step 2: Add RED serialization/persistence tests**

```kotlin
@Test fun suspendedStoreSaveDoesNotBlockDisarmStateChange()
@Test fun disarmInvalidatesQueuedUpdateBeforeRemoteCall()
@Test fun admittedUpdateFinishesBeforeDisarmStop()
@Test fun expiryStopIsTheFinalRemoteAction()
@Test fun retryableStopFailureRetainsHandleForRecovery()
@Test fun successfulStopRemovesOnlyThatHandle()
@Test fun messageUnavailableCountsAsAlreadyStopped()
@Test fun abortLocalInvalidatesQueuedRemoteWorkSynchronously()
@Test fun shutdownDeadlineIncludesIngressPersistenceAndRemoteStop()
@Test fun failedTrackingStartLeavesNoOrphanIngressAndRetries()
```

Use `CompletableDeferred` gates in fake store/transport and an action list. Do not use sleeps.

- [ ] **Step 3: Run RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*ConflatedLocationFixIngressTest' --tests '*MovementTrackingStoreTest' --tests '*LivePursuitCoordinatorTest'
```

Expected RED: store suspension blocks state lock, handles disappear before stop result, abort uses inconsistent synchronization, and shutdown bound excludes ingress/persistence.

- [ ] **Step 4: Separate decisions from side effects**

Inside `stateMutex`, only validate state/generation and capture immutable work. Perform `store.load/save/clear`, ingress joining, geocoder, and Telegram after releasing the lock. Reacquire and apply a result only when session ID and `lifecycleGeneration` still match.

No `store.` or `transport.` call may remain inside a `stateMutex.withLock` block.

- [ ] **Step 5: Use one lifecycle invalidation source**

Replace mixed generation synchronization with `AtomicLong lifecycleGeneration`. Start/update/alert requests capture it. `abortLocal`, disarm, offline, expiry, and controlled stop increment it before cancelling ingress. Check generation before waiting for `remoteMutex` and immediately before network admission.

An already-started update may finish; `remoteMutex` must then make stop the final admitted action for known handles.

- [ ] **Step 6: Persist actual stop outcomes**

Persist known handles until results arrive:

- `Success` or `Terminal(MESSAGE_UNAVAILABLE)`: remove that handle and persist remaining handles.
- `Retryable`: retain for process recovery.
- `Terminal(UNAUTHORIZED)` or `Terminal(FORBIDDEN)`: retain until the 900-second natural expiry; do not update it.
- Once wall-clock expiry has passed, clear retained handles because Telegram Live Location can no longer remain live.

Add `MOVEMENT_TRACKING` to `PersistenceSource`. A failed movement-record load/save/clear calls `ProtectionCoordinator.recordPersistenceFailure(PersistenceSource.MOVEMENT_TRACKING)`; a later successful durable write calls the matching recovery method. Add the exact sanitized label `Movement tracking persistence unavailable` to the coordinator mapping. Persistence failure must not authorize a second Live Location start.

- [ ] **Step 7: Bound the whole controlled shutdown**

One `withTimeoutOrNull(10_000L)` in `prepareForStop()` covers ingress cancellation/join, final persistence, and remote stops. `abortLocal()` only increments generation, unregisters location, and cancels pending ingress synchronously. `SensorService.onDestroy()` calls `abortLocal()` and returns without blocking; explicit service stop awaits `prepareForStop()` before `stopSelf()`.

- [ ] **Step 8: Reset armed-cycle identity at terminal states**

In `ProtectionRuntimeGraph`, set `activeSessionId = null` for `DISARMED_ONLINE`, `OFFLINE`, and `SETUP_REQUIRED`. Generate a new ID only when entering `ARMING` with no active ID. Remove the unused `previousState` local unless a test gives it a defined transition role.

- [ ] **Step 9: Run GREEN and inspect lock boundaries**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*ConflatedLocationFixIngressTest' --tests '*MovementDisplacementPolicyTest' --tests '*MovementTrackingStoreTest' --tests '*LivePursuitCoordinatorTest'
Select-String -Path 'app\src\main\java\com\example\motorcycleantitheftsensor\protection\LivePursuitCoordinator.kt' -Pattern 'stateMutex.withLock|store\.|transport\.' -Context 0,8
```

---

### Task 5: Share Location Presentation and Harden Live API Cancellation

**Files:** Create `LocationPresentationFactory.kt` and its test. Modify `LocationLabelResolver.kt`, `IncidentDeliveryCoordinator.kt:80-103`, `LivePursuitCoordinator.kt:92-99,430-443`, `TelegramLiveLocationApi.kt:164-275`, legacy live-location methods in `TelegramBotClient.kt`, and focused tests.

**Interfaces:**

```kotlin
class LocationPresentationFactory(
    private val labelResolver: LocationLabelResolver,
    private val labelTimeoutMs: Long = 1_500L,
) {
    suspend fun create(fix: TrackedLocationFix): LocationPresentation
}
```

Latitude `13.756300`, longitude `100.501800`, accuracy `8.1f` must produce `https://maps.google.com/?q=13.756300,100.501800` and accuracy `9`.

- [ ] **Step 1: Add RED presentation tests**

```kotlin
@Test fun presentationAlwaysContainsLocaleIndependentUrlAndCeilingAccuracy()
@Test fun slowLabelFallsBackAfterFifteenHundredMilliseconds()
@Test fun callerCancellationIsRethrown()
@Test fun nativeLiveLocationStartsBeforePresentationResolution()
@Test fun movementAlertAlwaysContainsMapAndAccuracy()
```

- [ ] **Step 2: Add RED API cancellation tests**

```kotlin
@Test fun coroutineCancellationCancelsCall()
@Test fun responseArrivingAfterCancellationIsClosed()
@Test fun unexpectedNonCancellationFailureReturnsSanitizedTypedFailure()
```

Change `TelegramLiveLocationApiImpl` to accept `okhttp3.Call.Factory`; the production bounded `OkHttpClient` already implements it. Inject a fake `Call.Factory` in tests; do not add MockWebServer.

- [ ] **Step 3: Run RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*LocationLabelResolverTest' --tests '*LocationPresentationFactoryTest' --tests '*IncidentDeliveryCoordinatorTest' --tests '*LivePursuitCoordinatorTest' --tests '*TelegramLiveLocationApiTest'
```

- [ ] **Step 4: Implement presentation and preserve cancellation**

Factory formats with `Locale.US`, rounds using `ceil`, and times out only label resolution. `AndroidLocationLabelResolver` and `AndroidGeocoderGateway` explicitly rethrow `CancellationException`; other sanitized platform failures return no label.

Start native Live Location before creating presentation. Use the factory for movement alert and incident Telegram. SMS continues through `formatSms` with no presentation.

- [ ] **Step 5: Close cancelled responses**

Cancellation calls `Call.cancel()`. If `onResponse` sees an inactive continuation, close the response. Attach a resumption-cancellation handler that also closes it. Convert non-cancellation transport failures to an existing token-free typed result rather than killing the fix consumer.

- [ ] **Step 6: Remove unused synchronous Live Location bypasses**

```powershell
rg -n 'sendLiveLocation\(|editMessageLiveLocation\(|stopMessageLiveLocation\(|sendTelegramLiveLocationToApi|editTelegramLiveLocationInApi|stopTelegramLiveLocationInApi' app/src/main app/src/test
```

If only legacy declarations reference them, delete those methods/helpers and update source-contract tests so typed `TelegramLiveLocationApi` is the sole production path.

- [ ] **Step 7: Run GREEN and privacy scans**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*LocationLabelResolverTest' --tests '*LocationPresentationFactoryTest' --tests '*IncidentMessageFormatterTest' --tests '*IncidentDeliveryCoordinatorTest' --tests '*LivePursuitCoordinatorTest' --tests '*TelegramLiveLocationApiTest' --tests '*TelegramLiveLocationTransportTest'
rg -n 'maps\.google|latitude|longitude|LocationPresentation' app/src/main/java/com/example/motorcycleantitheftsensor/telephony app/src/main/java/com/example/motorcycleantitheftsensor/ui
rg -n 'fix lat=|lon=|printStackTrace|response body' app/src/main/java/com/example/motorcycleantitheftsensor
```

---

### Task 6: Use Real App Visibility for Android 14 Location FGS

**Files:** Create `AppVisibilityProvider.kt` and its test; modify `LocationForegroundPolicy.kt`, `SensorService.kt:258-277,340-385`, `ProtectionCoordinator.kt`, `ProtectionCoordinatorTest.kt`, and policy tests.

**Interfaces:**

```kotlin
fun interface AppVisibilityProvider {
    fun isAppProcessForeground(): Boolean
}

class AndroidAppVisibilityProvider(
    private val importanceReader: () -> Int,
) : AppVisibilityProvider

fun interface ForegroundStartGateway {
    fun start(foregroundTypes: Int)
}

data class ForegroundStartResult(
    val usedForegroundTypes: Int,
    val degradedReason: String?,
)

class ForegroundStartController {
    fun start(
        requestedTypes: Int,
        specialUseOnlyTypes: Int,
        gateway: ForegroundStartGateway,
    ): ForegroundStartResult
}

fun ProtectionCoordinator.recordLocationForegroundRestriction(restricted: Boolean)
```

Production reads `ActivityManager.getMyMemoryState` and returns true only for `RunningAppProcessInfo.IMPORTANCE_FOREGROUND`. It never uses `SensorService.foregroundRunning` as process visibility.

- [ ] **Step 1: Add RED tests**

```kotlin
@Test fun foregroundProcessImportanceMapsToVisible()
@Test fun serviceForegroundFlagDoesNotImplyProcessForeground()
@Test fun android14BackgroundWithoutBackgroundLocationRejectsLocationType()
@Test fun android14ForegroundWithFineLocationAllowsLocationType()
@Test fun securityExceptionFallsBackToSpecialUseWithoutCrash()
@Test fun locationForegroundRestrictionAddsAndRemovesOneRuntimeDegradation()
```

Keep `ACCESS_BACKGROUND_LOCATION` absent unless a separate user-approved permission-flow design is created.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*AppVisibilityProviderTest' --tests '*LocationForegroundPolicyTest' --tests '*ProtectionCoordinatorTest'
```

- [ ] **Step 3: Implement visibility and fail-safe foreground upgrade**

Pass real process visibility to the policy. `ForegroundStartController` first calls the gateway with requested types. If that call throws `SecurityException`, it calls the gateway once more with special-use-only types and returns the exact degradation reason. `SensorService` then keeps running and calls `recordLocationForegroundRestriction(true)`. A later successful location-type start calls it with `false`.

Implement the coordinator state with `AtomicReference<Set<String>> runtimeDegradations`, using the exact reason `Background location foreground start restricted`. Include this set wherever `evaluateFreshness` and immediate degradation refresh combine base, Telegram, sensor, and persistence degradations. Add/remove only this exact reason and preserve every unrelated reason. Catch only the platform security restriction; do not request background permission automatically.

- [ ] **Step 4: Run GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*AppVisibilityProviderTest' --tests '*LocationForegroundPolicyTest' --tests '*ProtectionCoordinatorTest'
```

---

### Task 7: Full Gates and Huawei/Two-Phone Acceptance

**Files:** Verify Tasks 1-6 and update `docs/superpowers/status/2026-08-14-gps-settings-notification-follow-up-evidence.md`.

**Interfaces:** Produces bounded source/host/device evidence; `NOT RUN` never counts as PASS.

- [ ] **Step 1: Run focused suites fresh**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_OPTS='-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8'
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*Location*Test' --tests '*Movement*Test' --tests '*LivePursuitCoordinatorTest' --tests '*IncidentMessageFormatterTest' --tests '*IncidentDeliveryCoordinatorTest' --tests '*TelegramLiveLocation*Test' --tests '*TelegramBotVerifierTest' --tests '*AndroidProtectionSettingsGatewayTest' --tests '*ProtectionViewModelTest' --tests '*ForegroundNotificationPolicyTest' --tests '*AppVisibilityProviderTest'
```

Record XML suites/tests/failures/errors/skipped. Required: exit `0`, failures/errors `0`.

- [ ] **Step 2: Run full host/APK gates sequentially**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:assembleDebug
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:compileDebugAndroidTestKotlin
Get-FileHash '.\app\build\outputs\apk\debug\app-debug.apk' -Algorithm SHA256
```

- [ ] **Step 3: Review scoped diff, whitespace, and secrets**

```powershell
git status --short
git diff --check
rg -n --hidden -g '!build/**' -g '!.gradle/**' '(bot[0-9]{6,}:|[0-9]{6,}:[A-Za-z0-9_-]{20,})' app docs
rg -n 'fix lat=|lon=|maps\.google|LocationPresentation|IncidentLocation' app/src/main/java/com/example/motorcycleantitheftsensor/telephony app/src/main/java/com/example/motorcycleantitheftsensor/ui app/src/main/java/com/example/motorcycleantitheftsensor/sensor
```

Fix whitespace only in files this plan touched. List unrelated `diff --check` failures without editing them.

- [ ] **Step 4: Install on the user-approved Huawei only**

```powershell
$adb='C:\Users\ASUS\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb devices -l
$targetSerial=Read-Host 'Enter the approved target serial shown above'
& $adb -s $targetSerial install -r '.\app\build\outputs\apk\debug\app-debug.apk'
```

Never uninstall, clear data, guess a serial, or capture secrets/coordinates. Record a redacted device class and installed APK hash.

- [ ] **Step 5: Run Settings and notification acceptance**

| Scenario | Required result |
|---|---|
| Valid token Save | completes within 12 seconds, saves once, refreshes once |
| Rejected token | spinner stops; old token/polling preserved |
| Offline Save | spinner stops within 12 seconds; connection failure; old token preserved |
| Navigate away during Save | work cancels; no late Snackbar/token mutation |
| Service for 10 minutes | no heads-up, sound, vibration, or top-edge popup |
| Notification tray | exactly one silent ongoing Motorcycle Guard notification |
| Arm/alert/disarm | tray text changes without heads-up replay |
| Relaunch | new silent channel used; old HIGH channel receives no post |

- [ ] **Step 6: Run GPS two-phone acceptance**

| Scenario | Required result |
|---|---|
| ARMING 10 seconds | no anchor or Live Location |
| First fix outdoors | GPS+Network only; usable fix within 15 seconds |
| Confirmed movement | exactly one owner Live Location after two qualifying fixes |
| Dense callback burst | no backlog replay, ANR, duplicate start, or regression |
| Five-minute movement | same message; exact 10s/10m gate and 5s floor |
| Network loss 60 seconds | latest fix retries; no historical replay |
| Disarm during update | admitted update may finish; stop is final |
| Stop network failure/relaunch | handle retained until stop success or 900s expiry |
| Process kill/relaunch | no duplicate start; coherent recovery |
| Providers off/on | truthful inactive/active mode; no duplicate listener |
| Clock forward/back | recovery duration clamped to 0-15 minutes |
| Android 14 background | no crash; special-use fallback and degraded status |
| Geocoder unavailable | Live Location plus Maps URL/accuracy still arrive |
| Owner revoked | updates stop; known handle can receive final stop |
| SMS fallback | no coordinate, URL, label, handle, or tracking state |
| Charged thermal soak | armed 2 hours then pursuit 15 minutes without crash/ANR/growth |

- [ ] **Step 7: Report evidence without overclaiming**

Record exact source scope, commands/exits/XML totals/APK hash, every device scenario as PASS/FAIL/NOT RUN, Settings elapsed times, notification channel/heads-up/tray counts, GPS latency and offered/consumed/dropped/Telegram metrics, and remaining OEM/network/satellite/geocoder risks.

## Final Acceptance Criteria

- Settings verification completes/fails/times out/cancels within bounds and always clears `settingsOperationInFlight`.
- Cancellation cancels OkHttp; no callback-only thread can leave the UI waiting forever.
- Failed token replacement preserves token and polling; results remain token-free.
- Foreground service never posts on the old HIGH channel; no repeating heads-up; one silent ongoing tray notification remains.
- Identical notification content is not republished.
- Android 14 uses actual process visibility and cannot crash on a rejected location-type upgrade.
- Provider mode matches active registration after failure and a real concurrent race.
- No disk, Telegram, geocoder, or ingress join runs under `stateMutex`.
- One atomic lifecycle generation invalidates queued GPS work.
- Stop results control persisted handles; retryable failure cannot erase recovery data.
- Recovery covers old anchors, null IDs, alert state, mismatch, expiry, clocks, restart, and new cycle identity.
- Movement alert always includes native Live Location plus Maps URL and accuracy.
- SMS, UI, diagnostics, logs, and accessibility remain coordinate-free.
- Focused/full gates, APK, Android-test compilation, Huawei Settings/notification, and two-phone GPS all pass before readiness is claimed.

## Handoff Rules

- Execute Tasks 0-7 in order and stop at every GREEN checkpoint for review.
- Mark a checkbox only after its command/device scenario completes.
- If a RED test unexpectedly passes, verify the reproduction before production edits.
- After three different shared-state fix failures, stop and request architectural review.
- Report blocked external conditions as `NOT RUN`, never PASS.
