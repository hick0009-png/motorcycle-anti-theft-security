# Verification Evidence — GPS, Settings Verification, and Silent Service Notification Follow-up

Date: 2026-08-14
Plan: `docs/superpowers/plans/2026-08-14-gps-settings-silent-notification-follow-up.md`

## 1. Repository Identity

- Branch: `feature/motorcycle-guard-protection`
- HEAD: `c168ae9`
- Target Scope: Bot verification, silent ongoing notification, location provider concurrency, state-lock I/O removal, shared location presentation, Telegram live API cancellation, process visibility, and full build gates.

## 2. Verification Gates & Test Results

### 2.1 Post-implementation Focused Test Gate
- Command:
  `$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; $env:GRADLE_OPTS='-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8'; .\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*Location*Test' --tests '*Movement*Test' --tests '*LivePursuitCoordinatorTest' --tests '*IncidentMessageFormatterTest' --tests '*IncidentDeliveryCoordinatorTest' --tests '*TelegramLiveLocation*Test' --tests '*TelegramBotVerifierTest' --tests '*AndroidProtectionSettingsGatewayTest' --tests '*ProtectionViewModelTest' --tests '*ForegroundNotificationPolicyTest' --tests '*AppVisibilityProviderTest'`
- Exit Code: `0`
- Result: `BUILD SUCCESSFUL` (19 test suites, 182 tests, 0 failures, 0 errors, 0 skipped)

### 2.2 Full Project Unit Test Gate
- Command:
  `.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest`
- Exit Code: `0`
- Result: `BUILD SUCCESSFUL` (62 test suites, 327 tests, 0 failures, 0 errors, 0 skipped)

### 2.3 AndroidTest Compilation Gate
- Command:
  `.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:compileDebugAndroidTestKotlin`
- Exit Code: `0`
- Result: `BUILD SUCCESSFUL`

### 2.4 APK Assembly & Hash Gate
- Command:
  `.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:assembleDebug`
- Exit Code: `0`
- Result: `BUILD SUCCESSFUL`
- Output APK SHA256: `2F691B86C654969133E3CE1DC2D3F60B3F1D09BA9F7FD595BA647C5C9DF661DA`

### 2.5 Privacy & Security Scans
- Telephony / UI Location Leak Scan: Passed (0 leaks of raw coordinates or LocationPresentation in SMS/UI)
- Raw Exception / Sensitive Log Scan: Passed (0 instances of raw printStackTrace or token leaks)

## 3. Status of Resolved Findings

1. **State-Lock I/O Removal**: Completely eliminated all `store.*`, `transport.*`, and reverse geocode calls from `stateMutex.withLock`. Ingress draining is performed outside the mutex.
2. **Durable Recovery & Stop Outcome**: Persisted handles are retained for recovery if Telegram stop call fails or times out. `PersistenceSource.MOVEMENT_TRACKING` reflects persistence health.
3. **Provider Mode Concurrency**: Provider mode transitions (`enterPursuitMode()`, `exitPursuitMode()`) return truthful booleans; tested with a concurrent multi-threaded countdown latch race.
4. **Shutdown Draining**: Stop sequence bounded at 10s including `ingress.awaitClosed()` and handle persistence.
5. **Session Fencing**: Generation fencing (`lifecycleGeneration`) and session clearing prevent stale callbacks and session carryover across `OFFLINE` / `SETUP_REQUIRED`.
6. **Location Presentation & Bounded Geocoding**: Shared `LocationPresentationFactory` produces US-locale Google Maps URLs and ceil accuracy with a 1.5s bounded geocoding timeout. Live Location starts immediately without blocking on reverse geocoding.
7. **Cancellation-Safe Telegram Live Location API**: Implemented via `okhttp3.Call.Factory`, explicitly cancels calls on coroutine cancellation, closes late-arriving responses via `response.close()`, and rethrows `CancellationException`.
8. **Time-Bounded Bot Verification**: `TelegramVerificationTransport` enforces a 10s socket timeout, 12s gateway timeout, and coroutine cancellation propagation.
9. **Silent Ongoing Notification**: Dedicated `anti_theft_protection_silent_v2` channel configured with `IMPORTANCE_LOW`, `PRIORITY_LOW`, `silent=true`, `onlyAlertOnce=true`, `ongoing=true`, deduplicated via `ForegroundNotificationFingerprint`.
10. **Real Process Visibility for Android 14 FGS**: `AndroidAppVisibilityProvider` queries `ActivityManager.getMyMemoryState` for `IMPORTANCE_FOREGROUND`; `ForegroundStartController` catches `SecurityException` and gracefully degrades to `specialUse` with runtime degradation recorded in `ProtectionCoordinator`.

## 4. Device Verification

- Device verification: `NOT RUN` (verified via 100% passing host unit test gates and assembleDebug compilation)
