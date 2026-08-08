# Demo-Free Authoritative UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the active dashboard with a Demo-free, three-destination Compose experience driven by the authoritative protection coordinator and verified from current-run UI evidence.

**Architecture:** `ProtectionViewModel` observes the coordinator snapshot and reads bounded incident/settings data through narrow interfaces. Stateless Compose screens render Protection, Events, and Settings; `MainNavigation` owns only Android host concerns such as permission launchers and dependency construction. Sensitive values are accepted through transient password fields and are never stored in `ProtectionUiState`.

**Tech Stack:** Kotlin 2.3.20, Jetpack Compose Material 3, AndroidX ViewModel/StateFlow, Kotlin coroutines, JUnit 4, Android Compose UI tests, Gradle 9.0.1.

## Global Constraints

- Demo Mode must not appear in any active UI, ViewModel field, action, semantics tag, navigation item, or UI test fixture.
- `ProtectionRuntimeGraph.coordinator.snapshot` is the only arm/protection state source; do not read or write `isSystemArmed()` from UI code.
- Do not modify security-critical `EncryptedPrefsManager.kt`; use its existing methods through an adapter.
- Do not load the stored Telegram token or SMS key into `ProtectionUiState`, logs, saved-state handles, screenshots, accessibility content descriptions, or test output.
- Do not add dependencies. Use Material 3 and Android platform drawable resources already available.
- Keep network, repository, and protection commands off the main thread.
- Preserve the existing black/white visual language and the user-authored red-modal removal in the dirty worktree.
- Target layout verification is 1080 x 2340; primary interactive targets are at least 48 dp.
- Before every commit, inspect `git status --short`, exact-path diffs, and `git diff --check`; never broadly stage unrelated files.
- Project root for commands is `D:\security\MotorcycleAntiTheftSensor`. Set `$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'` before Gradle commands.

## File Structure

- Create `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt`: presentation-only destinations, settings summaries, event rows, messages, and gateway interface.
- Create `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt`: authoritative snapshot collection, event refresh, commands, and settings operations.
- Create `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreen.kt`: three-destination shell and action contract.
- Create `app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/ProtectionScreen.kt`: protection state, countdown, health, blockers, degradation, and incident card.
- Create `app/src/main/java/com/example/motorcycleantitheftsensor/ui/events/EventsScreen.kt`: newest-first history and clear confirmation.
- Create `app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt`: masked replacement inputs, permissions, sensitivity, pairing, SMS fallback, and diagnostics summary.
- Create `app/src/main/java/com/example/motorcycleantitheftsensor/ui/AndroidProtectionSettingsGateway.kt`: adapter over existing encrypted preferences and Telegram verification.
- Modify `app/src/main/java/com/example/motorcycleantitheftsensor/Navigation.kt`: construct the graph/ViewModel and host the permission launcher; remove independent arm and sensor state.
- Leave `app/src/main/java/com/example/motorcycleantitheftsensor/ui/DashboardScreen.kt` unchanged during implementation because it contains pre-existing dirty work; remove it from active navigation.
- Create `app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt`: deterministic ViewModel and mapping tests.
- Create `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt`: navigation, semantics, masking, confirmation, and Demo-absence tests.

---

### Task 1: Authoritative presentation model and ViewModel

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt`
- Create test: `app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt`

**Interfaces:**
- Consumes: `ProtectionCoordinator.snapshot: StateFlow<ProtectionSnapshot>`, `ProtectionCoordinator.arm/disarm/changeSensitivity`, and `IncidentRepository.listNewestFirst/clearHistory`.
- Produces: `ProtectionDestination`, `ProtectionSettingsSummary`, `ProtectionSettingsGateway`, `ProtectionUiState`, and `ProtectionViewModel` methods used by Tasks 2-3.

- [ ] **Step 1: Write failing state-mapping tests**

Create tests that use literal production models and never a Demo fixture:

```kotlin
@Test
fun stateUsesCoordinatorSnapshotAndNewestFirstRealEvents() = runTest {
    val coordinator = fakeCoordinator(ProtectionState.ARMED_DEGRADED)
    val incidents = FakeIncidentRepository(
        listOf(realIncident("older", 1_000L), realIncident("newer", 2_000L)),
    )
    val viewModel = ProtectionViewModel(
        coordinator = coordinator,
        incidents = incidents,
        settings = FakeProtectionSettingsGateway(),
        nowMs = { 2_500L },
        ticker = emptyFlow(),
        dispatcher = StandardTestDispatcher(testScheduler),
    )
    runCurrent()

    assertEquals(ProtectionState.ARMED_DEGRADED, viewModel.uiState.value.protection.state)
    assertEquals(listOf("newer", "older"), viewModel.uiState.value.events.map { it.id })
    assertFalse(viewModel.uiState.value.toString().contains("Demo", ignoreCase = true))
}

@Test
fun armingCountdownDerivesFromAuthoritativeTransitionTime() = runTest {
    val state = ProtectionUiState.from(
        snapshot = snapshot(ProtectionState.ARMING, lastTransitionAtMs = 1_000L),
        incidents = emptyList(),
        settings = settingsSummary(),
        nowMs = 4_100L,
    )
    assertEquals(7, state.armingSecondsRemaining)
}
```

- [ ] **Step 2: Run the focused tests and verify RED**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat testDebugUnitTest --tests '*ProtectionViewModelTest'
```

Expected: compilation fails because the new UI model and ViewModel do not exist.

- [ ] **Step 3: Implement the presentation types and pure mapping**

Use these exact contracts:

```kotlin
enum class ProtectionDestination { PROTECTION, EVENTS, SETTINGS }

data class ProtectionSettingsSummary(
    val tokenConfigured: Boolean,
    val pairedOwnerCount: Int,
    val pairingCode: String?,
    val authenticatorConfigured: Boolean,
    val sensitivity: Int,
    val smsFallbackConfigured: Boolean,
    val missingPermissions: Set<String>,
)

data class ProtectionEventRow(
    val id: String,
    val type: IncidentType,
    val severity: IncidentSeverity,
    val lifecycle: IncidentLifecycle,
    val evidenceSummary: String,
    val updatedAtMs: Long,
    val deliveryState: DeliveryState,
)

data class ProtectionUiMessage(val id: Long, val text: String, val isError: Boolean)

data class ProtectionStatusUiState(
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
    val lastIncident: IncidentRowSummary?,
    val lastDeliveryState: DeliveryState?,
)

data class IncidentRowSummary(
    val id: String,
    val severity: IncidentSeverity,
    val lifecycle: IncidentLifecycle,
    val updatedAtMs: Long,
    val deliveryState: DeliveryState,
)

data class SettingsOperationResult(val applied: Boolean, val message: String)

interface ProtectionSettingsGateway {
    fun read(missingPermissions: Set<String>): ProtectionSettingsSummary
    fun saveSensitivity(level: Int)
    suspend fun replaceBotToken(token: String): SettingsOperationResult
    fun saveSmsFallback(destination: String, aesKey: String): SettingsOperationResult
}

data class ProtectionUiState(
    val destination: ProtectionDestination,
    val protection: ProtectionStatusUiState,
    val events: List<ProtectionEventRow>,
    val settings: ProtectionSettingsSummary,
    val armingSecondsRemaining: Int?,
    val eventsLoading: Boolean,
    val eventsError: String?,
    val operationInFlight: Boolean,
    val message: ProtectionUiMessage?,
) {
    companion object {
        fun from(
            snapshot: ProtectionSnapshot,
            incidents: List<SecurityIncident>,
            settings: ProtectionSettingsSummary,
            nowMs: Long,
            destination: ProtectionDestination = ProtectionDestination.PROTECTION,
            eventsLoading: Boolean = false,
            eventsError: String? = null,
            operationInFlight: Boolean = false,
            message: ProtectionUiMessage? = null,
        ): ProtectionUiState
    }
}
```

`from` copies only production fields from the coordinator snapshot, filters incident history to `IncidentSource.REAL`, sorts by `updatedAtMs` descending, creates concise evidence text from sensor kind plus diagnostic, and computes `ceil((lastTransitionAtMs + 10_000 - nowMs) / 1_000)` only while `ARMING`, clamped to `0..10`. The ViewModel sets `eventsLoading` during repository work and retains an `eventsError` plus retry action when loading fails. Do not expose the raw `ProtectionSnapshot`, `IncidentSummary`, or `IncidentSource` in presentation types because those core models still contain legacy Demo concepts outside this UI task.

- [ ] **Step 4: Implement ViewModel collection and production commands**

Use this public surface:

```kotlin
class ProtectionViewModel(
    private val coordinator: ProtectionCoordinator,
    private val incidents: IncidentRepository,
    private val settings: ProtectionSettingsGateway,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val ticker: Flow<Unit> = flow {
        while (currentCoroutineContext().isActive) { emit(Unit); delay(1_000L) }
    },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    val uiState: StateFlow<ProtectionUiState>
    fun selectDestination(destination: ProtectionDestination)
    fun arm()
    fun disarm()
    fun changeSensitivity(level: Int)
    fun clearHistory()
    fun updateMissingPermissions(permissions: Set<String>)
    fun replaceBotToken(token: String)
    fun configureSmsFallback(destination: String, aesKey: String)
    fun retry()
    fun consumeMessage(id: Long)
}
```

Serialize commands with a `Mutex`, generate local command IDs as `ui-<UUID>`, execute repository/settings calls on `dispatcher`, and map `ProtectionCommandResult.reason` into one-shot messages. Never call `EncryptedPrefsManager.isSystemArmed/setSystemArmed`.

- [ ] **Step 5: Add command, validation, clear, and message-consumption tests**

```kotlin
@Test
fun rejectedArmPublishesConfirmedCoordinatorReason() = runTest {
    val viewModel = fixtureWithBlocker("POST_NOTIFICATIONS", testScheduler)
    viewModel.arm()
    advanceUntilIdle()
    assertEquals(ProtectionState.SETUP_REQUIRED, viewModel.uiState.value.protection.state)
    assertEquals("Missing required: POST_NOTIFICATIONS", viewModel.uiState.value.message?.text)
}

@Test
fun invalidSensitivityNeverWritesSettings() = runTest {
    val fixture = fixture(testScheduler)
    fixture.viewModel.changeSensitivity(11)
    advanceUntilIdle()
    assertEquals(emptyList<Int>(), fixture.settings.savedSensitivity)
    assertTrue(fixture.viewModel.uiState.value.message?.isError == true)
}

@Test
fun clearHistoryRefreshesEventsWithoutTouchingSettings() = runTest {
    val fixture = fixture(testScheduler, incidents = listOf(realIncident("i-1", 1L)))
    fixture.viewModel.clearHistory()
    advanceUntilIdle()
    assertTrue(fixture.viewModel.uiState.value.events.isEmpty())
    assertEquals(0, fixture.settings.writeCount)
}
```

- [ ] **Step 6: Run focused and full unit tests**

```powershell
.\gradlew.bat testDebugUnitTest --tests '*ProtectionViewModelTest'
.\gradlew.bat testDebugUnitTest
```

Expected: all tests pass; no production UI state property or method contains `demo`.

- [ ] **Step 7: Commit only Task 1 paths**

```powershell
git status --short
git diff --check
git add app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt
git commit -m "feat: add authoritative protection presentation state"
```

---

### Task 2: Three-destination Compose UI

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreen.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/ProtectionScreen.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/events/EventsScreen.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt`
- Create test: `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt`

**Interfaces:**
- Consumes: Task 1 `ProtectionUiState`, `ProtectionDestination`, and production ViewModel callbacks.
- Produces: stateless screen composables and `ProtectionAppActions` consumed by Task 3.

- [ ] **Step 1: Write failing shell and Demo-absence Compose tests**

```kotlin
@Test
fun shellHasExactlyThreePrimaryDestinationsAndNoDemoControls() {
    compose.setContent { ProtectionAppScreen(healthyState(), fakeActions()) }
    compose.onNodeWithText("Protection").assertExists()
    compose.onNodeWithText("Events").assertExists()
    compose.onNodeWithText("Settings").assertExists()
    compose.onAllNodes(hasText("Demo", substring = true, ignoreCase = true)).assertCountEquals(0)
}

@Test
fun protectionHasOneStateCorrectPrimaryAction() {
    compose.setContent { ProtectionAppScreen(disarmedState(), fakeActions()) }
    compose.onAllNodes(hasText("Arm protection")).assertCountEquals(1)
    compose.onAllNodes(hasText("Disarm protection")).assertCountEquals(0)
}
```

- [ ] **Step 2: Run instrumentation compile/test and verify RED**

```powershell
.\gradlew.bat compileDebugAndroidTestKotlin
```

Expected: compilation fails because `ProtectionAppScreen` does not exist.

- [ ] **Step 3: Implement the application shell**

Use one action object so every visible control has a real callback:

```kotlin
data class ProtectionAppActions(
    val selectDestination: (ProtectionDestination) -> Unit,
    val arm: () -> Unit,
    val disarm: () -> Unit,
    val clearHistory: () -> Unit,
    val changeSensitivity: (Int) -> Unit,
    val requestPermissions: () -> Unit,
    val replaceBotToken: (String) -> Unit,
    val configureSmsFallback: (String, String) -> Unit,
    val beginAuthenticatorSetup: () -> String?,
    val verifyAuthenticator: (String) -> Boolean,
    val retry: () -> Unit,
)
```

`ProtectionAppScreen` uses a Material 3 `Scaffold`, a bottom `NavigationBar`, and exactly three `NavigationBarItem`s. Use `painterResource` with Android platform drawables for icons; do not add an icon dependency or substitute emoji/text symbols.

- [ ] **Step 4: Implement Protection and Events screens**

`ProtectionScreen` renders state title, state explanation, Arm/Disarm, countdown, blockers, degradation reasons, service/Telegram/sensor health rows, battery values, and active/latest incident. Use `LazyColumn`, window insets, 16 dp horizontal padding, 12 dp card spacing, and 48 dp minimum actions.

`EventsScreen` renders progress while `eventsLoading`, a retryable inline error for `eventsError`, a clear empty state, or a stable-key `LazyColumn`. Every event row shows type, literal `REAL` source, severity, lifecycle, evidence, time, and delivery text. Clear history opens an `AlertDialog` and invokes `clearHistory` only from its confirm action.

- [ ] **Step 5: Implement Settings without loading stored secrets**

Use local `rememberSaveable` only for new user input; begin both secret fields blank:

```kotlin
var replacementToken by rememberSaveable { mutableStateOf("") }
var smsKey by rememberSaveable { mutableStateOf("") }

OutlinedTextField(
    value = replacementToken,
    onValueChange = { replacementToken = it },
    label = { Text(if (state.tokenConfigured) "Replace bot token" else "Bot token") },
    visualTransformation = PasswordVisualTransformation(),
)
```

Show configured/not-configured, paired owner count or pairing code, missing permission rows with blocking explanation, sensitivity 1-10, SMS fallback configured status and blank replacement inputs, authenticator configured status, plus read-only diagnostics from the authoritative snapshot. The authenticator setup callback returns the generated secret only to a local non-saveable dialog state; clear the secret and verification code on dismiss, never add either to `ProtectionUiState`, and do not capture that dialog in audit evidence. Clear other sensitive local input immediately after dispatching a save action.

- [ ] **Step 6: Add Compose tests for edge states and accessibility semantics**

```kotlin
@Test
fun settingsNeverDisplaysStoredCredential() {
    compose.setContent { ProtectionAppScreen(configuredSettingsState(), fakeActions()) }
    openSettings()
    compose.onNodeWithText("Token configured").assertExists()
    compose.onNodeWithText(TEST_ONLY_TOKEN).assertDoesNotExist()
}

@Test
fun eventsClearRequiresConfirmation() {
    val actions = recordingActions()
    compose.setContent { ProtectionAppScreen(historyState(), actions.value) }
    openEvents()
    compose.onNodeWithText("Clear history").performClick()
    assertEquals(0, actions.clearCalls)
    compose.onNodeWithText("Confirm clear").performClick()
    assertEquals(1, actions.clearCalls)
}

@Test
fun degradedStateIsReadableWithoutColor() {
    compose.setContent { ProtectionAppScreen(degradedState("VIBRATION not healthy"), fakeActions()) }
    compose.onNodeWithText("Protection degraded").assertExists()
    compose.onNodeWithText("VIBRATION not healthy").assertExists()
}
```

Use only `123456:TEST_ONLY_NOT_A_REAL_TOKEN` in tests.

- [ ] **Step 7: Run Compose compilation and device tests when a target is connected**

```powershell
.\gradlew.bat compileDebugAndroidTestKotlin
& 'C:\Users\ASUS\AppData\Local\Android\Sdk\platform-tools\adb.exe' devices
& 'C:\Users\ASUS\AppData\Local\Android\Sdk\platform-tools\adb.exe' shell am instrument -w com.example.motorcycleantitheftsensor.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: instrumentation passes on an available target. If no target is listed, record Compose runtime verification as pending rather than claiming it passed.

- [ ] **Step 8: Commit only Task 2 paths**

```powershell
git add app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreen.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/ProtectionScreen.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/events/EventsScreen.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt
git commit -m "feat: add demo-free protection experience"
```

---

### Task 3: Android host integration and legacy-state removal

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/AndroidProtectionSettingsGateway.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/Navigation.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt`

**Interfaces:**
- Consumes: Tasks 1-2 contracts, `ProtectionRuntimeGraph.from(context)`, existing `EncryptedPrefsManager`, `TelegramBotClient`, `PairingCodePolicy`, and Android permission launcher.
- Produces: the active application route backed by one coordinator; legacy `DashboardScreen` remains compiled but unreachable.

- [ ] **Step 1: Add a failing adapter behavior test through the gateway interface**

Add a ViewModel-level contract test proving token replacement rejects blank input before the gateway writes:

```kotlin
@Test
fun blankReplacementTokenIsRejectedWithoutGatewayCall() = runTest {
    val fixture = fixture(testScheduler)
    fixture.viewModel.replaceBotToken("   ")
    advanceUntilIdle()
    assertEquals(0, fixture.settings.tokenReplaceCalls)
    assertTrue(fixture.viewModel.uiState.value.message?.isError == true)
}
```

- [ ] **Step 2: Run the focused test and verify RED**

```powershell
.\gradlew.bat testDebugUnitTest --tests '*ProtectionViewModelTest.blankReplacementTokenIsRejectedWithoutGatewayCall'
```

Expected: failure until blank validation is implemented at the ViewModel boundary.

- [ ] **Step 3: Implement the Android settings adapter without editing encrypted storage**

`AndroidProtectionSettingsGateway` receives `EncryptedPrefsManager`, `TelegramBotClient`, `PairingCodePolicy`, and `startControlService: () -> Unit`. Its `read` method returns booleans/counts only, including whether a TOTP seed exists. `replaceBotToken` trims and verifies the candidate first, then calls the existing `saveBotToken` and starts the service only after Telegram confirms validity. `saveSmsFallback` validates nonblank destination/key, then calls existing encrypted save methods. It never logs or returns secret text.

- [ ] **Step 4: Replace `MainNavigation` local arm/sensor state with the graph/ViewModel**

Construct dependencies once with `remember`, obtain the ViewModel with `viewModel { ProtectionViewModel(...) }`, collect `uiState` with lifecycle awareness, and render:

```kotlin
ProtectionAppScreen(
    state = uiState,
    actions = ProtectionAppActions(
        selectDestination = viewModel::selectDestination,
        arm = viewModel::arm,
        disarm = viewModel::disarm,
        clearHistory = viewModel::clearHistory,
        changeSensitivity = viewModel::changeSensitivity,
        requestPermissions = { permissionLauncher.launch(missingPermissions.toTypedArray()) },
        replaceBotToken = viewModel::replaceBotToken,
        configureSmsFallback = viewModel::configureSmsFallback,
        beginAuthenticatorSetup = totpAuth::setupNewTotpSeed,
        verifyAuthenticator = { code ->
            val verified = totpAuth.verifyCode(code) == TotpAuthenticator.VerificationResult.SUCCESS
            if (verified) viewModel.retry()
            verified
        },
        retry = viewModel::retry,
    ),
)
```

Delete active-route ownership of `isArmed`, direct `setSystemArmed`, raw sensor listeners, `ArmStateChangedEvent` receiver, and the `DashboardScreen` call. Keep startup of the remote-control service when a token is configured. After permission results, call `viewModel.updateMissingPermissions(...)`.

Because current `Navigation.kt` already contains the user-approved removal of the alarm receiver path, review the complete exact-path diff before staging; the rewrite intentionally subsumes that overlapping change. Do not stage the dirty `DashboardScreen.kt`.

- [ ] **Step 5: Prove active UI/ViewModel sources contain no Demo contract**

```powershell
rg -n -i 'demo(mode)?|triggerDemo|DemoEvent' app/src/main/java/com/example/motorcycleantitheftsensor/ui app/src/main/java/com/example/motorcycleantitheftsensor/Navigation.kt
```

Expected: zero matches in production UI and Navigation sources. Negative assertions may retain the literal only in test source.

- [ ] **Step 6: Run unit tests and build**

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

Expected: all unit suites pass and `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 7: Commit only the adapter, Navigation, and added test hunk**

```powershell
git status --short
git diff -- app/src/main/java/com/example/motorcycleantitheftsensor/Navigation.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/AndroidProtectionSettingsGateway.kt app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt
git diff --check
git add app/src/main/java/com/example/motorcycleantitheftsensor/Navigation.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/AndroidProtectionSettingsGateway.kt
git add -p app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt
git commit -m "feat: route UI through protection coordinator"
```

---

### Task 4: Current-run UI audit, fixes, and release evidence

**Files:**
- Modify only UI/test files with evidence-backed defects.
- Create: `docs/audits/2026-08-08-demo-free-ui-audit.md`

**Interfaces:**
- Consumes: completed Demo-free application and Product Design audit workflow.
- Produces: fresh automated evidence, current-run screenshots, categorized findings, fixes, and honest limitations.

- [ ] **Step 1: Run a fresh automated verification**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat cleanTestDebugUnitTest testDebugUnitTest assembleDebug
```

Expected: all tasks pass from a fresh test invocation; record suite/test totals from XML rather than estimating.

- [ ] **Step 2: Install the exact APK when a device is connected**

```powershell
$adb='C:\Users\ASUS\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb devices
& $adb install -r 'app\build\outputs\apk\debug\app-debug.apk'
```

Expected: one authorized device and `Success`. If unavailable, mark device/UI capture pending.

- [ ] **Step 3: Capture and inspect all three destinations from this run**

Read `product-design:audit/references/design-audit-framework.md` before capture. Launch the installed app, capture Protection, Events, and Settings screenshots with ADB into a timestamped directory under `docs/audits/artifacts/`, and inspect every image with the local image viewer. Never capture a token, SMS key, pairing secret, or OTP.

For each destination, record viewport/device, state, visible hierarchy, clipping, scrolling, insets, typography, spacing, touch-target risk, contrast risk, text/icon status clarity, and accessibility evidence limitations.

- [ ] **Step 4: Fix critical and important findings one at a time**

For each defect, add or tighten a Compose test first, verify RED, make the smallest UI change, rerun the focused test, recapture the affected screen, and compare it with the previous current-run screenshot. Do not redesign unaffected areas.

- [ ] **Step 5: Run font-scale and long-content checks**

Use the connected device to test at default and enlarged font scale, long blocker/degradation text, empty event history, populated event history, and keyboard-visible Settings inputs. Restore device font scale after capture. Record any scenario that cannot be exercised.

- [ ] **Step 6: Run final source, secret, whitespace, and test gates**

```powershell
rg -n -i 'demo(mode)?|triggerDemo|DemoEvent' app/src/main/java/com/example/motorcycleantitheftsensor/ui app/src/main/java/com/example/motorcycleantitheftsensor/Navigation.kt
rg -n --hidden -g '!build/**' -g '!.gradle/**' '(bot[0-9]{6,}:|[0-9]{6,}:[A-Za-z0-9_-]{20,})' app docs
git diff --check
.\gradlew.bat testDebugUnitTest assembleDebug
```

Expected: no Demo UI/ViewModel matches, no real credential match, no whitespace errors, and all automated gates pass.

- [ ] **Step 7: Write the audit report and commit reviewed evidence**

The audit report lists screenshots inline, findings by severity, fixes applied, automated results, device facts, and limitations. It must not claim full accessibility compliance from screenshots alone.

```powershell
git add docs/audits/2026-08-08-demo-free-ui-audit.md docs/audits/artifacts
git add -p app/src/main/java/com/example/motorcycleantitheftsensor/ui app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui
git commit -m "test: verify demo-free protection UI"
```

Do not stage unrelated `.hcp`, legacy dirty UI files, build output, or screenshots containing credentials.

## Final Acceptance Checklist

- [ ] Active UI and ViewModel source scans contain no Demo Mode concepts.
- [ ] Protection, Events, and Settings are the only primary destinations.
- [ ] Active UI arm/disarm state comes only from the coordinator snapshot.
- [ ] One state-correct primary protection action is visible.
- [ ] Blockers, degradations, health, severity, lifecycle, and delivery remain understandable without color.
- [ ] Event empty/history/error and clear-confirmation states work.
- [ ] Stored credentials never enter presentation state or captured evidence.
- [ ] Unit tests and APK build pass from a fresh invocation.
- [ ] Compose instrumentation and three current-run screenshots pass on a connected target, or are explicitly recorded as pending when no target exists.
- [ ] Critical and important audit findings are fixed and re-captured.
