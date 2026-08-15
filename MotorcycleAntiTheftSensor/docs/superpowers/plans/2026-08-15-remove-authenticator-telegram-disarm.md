# Remove Authenticator and Simplify Telegram Disarm Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove Authenticator, QR, and TOTP completely so an already-paired Telegram owner can disarm protection with the exact command `/disarm` and no code.

**Architecture:** Keep the existing `/pair <code>` owner allowlist as the only Telegram command-authorization boundary. Parse `RemoteCommand.Disarm` as a parameterless command, authorize the sender in `TelegramBotClient`, and delegate exactly once through `TelegramCommandHandler` to the authoritative `ProtectionCoordinator`. Remove the entire Authenticator stack from Settings and application wiring, then run an idempotent upgrade cleanup that deletes only the unreachable legacy encrypted TOTP seed.

**Tech Stack:** Kotlin, Android SDK 24-36, coroutines, Jetpack Compose, OkHttp 4.12.0, EncryptedSharedPreferences, JUnit 4, kotlinx-coroutines-test, Mockito Kotlin, Gradle 9.1.0.

## Global Constraints

- Authoritative spec: `docs/superpowers/specs/2026-08-15-telegram-disarm-without-authenticator-design.md` at commit `71ed34a`.
- Preserve `/pair <code>`, the existing allowed-owner chat ID set, Telegram Bot Token verification, TLS pins, polling, and polling cursor behavior.
- Accept only the exact argument-free `/disarm` command, case-insensitively. `/disarm 123456` and every other argument form parse as `RemoteCommand.Unknown`.
- Do not add a compatibility branch, ignored argument, special migration reply, password, challenge, confirmation button, recovery code, or replacement authentication factor.
- Only an allowed owner chat may reach the command handler. An unpaired chat must never reach `ProtectionCoordinator`.
- `ProtectionCoordinator` remains authoritative. Report success only after its existing disarm result reports success.
- Remove Authenticator UI, QR, TOTP verification, setup, lockout, models, guidance, tests, and the unused ZXing dependency.
- The upgrade cleanup may remove only the legacy encrypted TOTP seed. Preserve token, owners, pairing, sensitivity, SMS, protection snapshot, incidents, and GPS state.
- Never log or echo bot tokens, chat IDs, command arguments, legacy seeds, QR content, or coordinates.
- Do not change GPS pursuit, notification, sensor, SMS, call, incident, or protection-state behavior except where constructor cleanup is required to remove TOTP.
- Never block the main thread. Legacy preference cleanup runs on `Dispatchers.IO`.
- The checkout is a mixed dirty worktree. Do not reset, clean, stash, revert, overwrite, or broadly stage existing changes.
- This removal crosses more than five files because the current feature spans Telegram, dependency wiring, Settings, storage, guidance, and tests. Keep edits restricted to the removal inventory in this plan.
- Follow RED → minimal GREEN → focused regression for every production behavior change.

## Dirty-Worktree Commit Gate

Several task-owned paths are already modified by prior GPS/Settings work. Before every commit:

1. Run `git status --short` and `git diff --cached --name-only`.
2. Inspect `git diff --cached` completely.
3. Commit only if every staged hunk belongs to this plan and no pre-existing hunk is included.
4. If a task-owned file mixes prior work with this task and cannot be staged safely, leave the task uncommitted, record the GREEN evidence, and create a project checkpoint instead. Never use `git add .`, `git add -A`, or a path-level add that captures unrelated hunks.

## File Map

**Create**

- `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramCommandExecutor.kt` — narrow injectable command boundary used by `TelegramBotClient` and implemented by `TelegramCommandHandler`.
- `app/src/main/java/com/example/motorcycleantitheftsensor/data/LegacyAuthenticatorMigration.kt` — pure, idempotent decision wrapper for deleting the legacy seed.
- `app/src/test/java/com/example/motorcycleantitheftsensor/data/LegacyAuthenticatorMigrationTest.kt` — migration outcome and preservation tests.
- `app/src/test/java/com/example/motorcycleantitheftsensor/ui/AuthenticatorUiRemovalContractTest.kt` — host RED/GREEN contract for removing Settings/UI call sites.
- `app/src/test/java/com/example/motorcycleantitheftsensor/security/AuthenticatorRemovalSourceContractTest.kt` — final production-source and dependency removal gate.
- `docs/superpowers/status/2026-08-15-remove-authenticator-telegram-disarm-evidence.md` — exact host/device evidence and remaining risks.

**Modify**

- `app/build.gradle.kts`
- `app/src/main/java/com/example/motorcycleantitheftsensor/Navigation.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/data/EncryptedPrefsManager.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/UserGuidance.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/PrioritizedCommandDispatcher.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/RemoteCommand.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClient.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramCommandHandler.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/AndroidProtectionSettingsGateway.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreen.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt`
- focused tests named in each task below.

**Delete after all call sites are removed**

- `app/src/main/java/com/example/motorcycleantitheftsensor/security/TotpAuthenticator.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/AuthenticatorQrCodeEncoder.kt`
- `app/src/test/java/com/example/motorcycleantitheftsensor/security/TotpAuthenticatorTest.kt`
- `app/src/test/java/com/example/motorcycleantitheftsensor/security/TotpAuthenticatorSourceContractTest.kt`
- `app/src/test/java/com/example/motorcycleantitheftsensor/ui/settings/AuthenticatorQrCodeEncoderTest.kt`

---

### Task 0: Freeze the Current Baseline

**Files:** Read the spec and every path in the File Map. Create the evidence document only after recording the command outputs.

**Interfaces:**

- Consumes: design commit `71ed34a` and the current mixed worktree.
- Produces: a bounded baseline showing which target paths were already modified before this plan.

- [ ] **Step 1: Confirm repository identity and worktree state**

```powershell
git rev-parse --show-toplevel
git branch --show-current
git rev-parse --short HEAD
git status --short
git diff --cached --name-only
```

Required: root resolves to `D:\security`, branch is recorded, HEAD includes `71ed34a`, and no unexpected staged file is left from another task.

- [ ] **Step 2: Read the approved spec and project instructions**

```powershell
Get-Content -Raw 'docs\superpowers\specs\2026-08-15-telegram-disarm-without-authenticator-design.md'
if (Test-Path '..\AI_WORKFLOW.md') { Get-Content -Raw '..\AI_WORKFLOW.md' } else { 'AI_WORKFLOW.md NOT FOUND' }
```

Record `AI_WORKFLOW.md NOT FOUND` as a repository documentation gap if it is still absent; do not invent its contents.

- [ ] **Step 3: Record the complete removal inventory before editing**

```powershell
rg -n -i "TotpAuthenticator|AuthenticatorQrCodeEncoder|AuthenticatorSetupDetails|authenticatorConfigured|beginAuthenticatorSetup|cancelAuthenticatorSetup|verifyAuthenticator|getTotpSeed|saveTotpSeed|otpauth://|TOTP_|OPEN_AUTHENTICATOR_SETTINGS|/disarm" app/src app/build.gradle.kts
rg -n "TelegramBotClient\(|RemoteCommand\.Disarm|Disarm\(" app/src/main app/src/test app/src/androidTest --glob '*.kt'
```

Required: paste path/line results into the evidence document without copying any real secret value.

- [ ] **Step 4: Run the pre-change focused baseline**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_OPTS='-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8'
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*TotpAuthenticatorTest' --tests '*RemoteCommandTest' --tests '*PrioritizedCommandDispatcherTest' --tests '*TelegramBotClientAuthorizationTest' --tests '*TelegramCommandHandlerTest' --tests '*AndroidProtectionSettingsGatewayTest' --tests '*ProtectionViewModelTest'
```

Record exit code plus XML suites/tests/failures/errors/skipped. A failure is baseline evidence, not permission to weaken or delete unrelated tests.

---

### Task 1: Make `/disarm` Parameterless and Pairing-Authorized

**Files:**

- Create: `telegram/TelegramCommandExecutor.kt`
- Modify: `telegram/RemoteCommand.kt`, `telegram/TelegramBotClient.kt`, `telegram/TelegramCommandHandler.kt`, `telegram/PrioritizedCommandDispatcher.kt`
- Modify wiring: `Navigation.kt`, `protection/ProtectionRuntimeGraph.kt`, `service/SensorService.kt`
- Test: `telegram/RemoteCommandTest.kt`, `telegram/PrioritizedCommandDispatcherTest.kt`, `telegram/TelegramBotClientAuthorizationTest.kt`, `telegram/TelegramCommandHandlerTest.kt`

**Interfaces:**

- Consumes: existing allowed-owner methods `getAllowedChatIds()` and `isChatIdAllowed(chatId)` plus `ProtectionCoordinator.disarm(commandId, CommandOrigin.TELEGRAM)`.
- Produces:

```kotlin
sealed interface RemoteCommand {
    data object Disarm : RemoteCommand
}

interface TelegramCommandExecutor {
    suspend fun handle(
        commandId: String,
        command: RemoteCommand,
        reply: suspend (String) -> Unit,
    )
}

class TelegramCommandHandler(...) : TelegramCommandExecutor
```

`TelegramBotClient` no longer has a `TotpAuthenticator` constructor parameter.

- [ ] **Step 1: Write parser RED tests**

Replace the old code-bearing assertions in `RemoteCommandTest` with:

```kotlin
@Test
fun parsesExactDisarmWithoutArgument() {
    assertEquals(RemoteCommand.Disarm, RemoteCommand.parse("/disarm"))
    assertEquals(RemoteCommand.Disarm, RemoteCommand.parse("/DISARM"))
}

@Test
fun disarmWithAnyArgumentIsUnknown() {
    assertEquals(RemoteCommand.Unknown, RemoteCommand.parse("/disarm 123456"))
    assertEquals(RemoteCommand.Unknown, RemoteCommand.parse("/disarm anything"))
}
```

Delete `onlySuccessfulTotpPermitsDisarm`; authorization no longer consumes `VerificationResult`.

- [ ] **Step 2: Write owner-boundary RED tests**

In `TelegramBotClientAuthorizationTest`, remove the mocked authenticator and add a recording `TelegramCommandExecutor`:

```kotlin
private class RecordingCommandExecutor : TelegramCommandExecutor {
    val received = java.util.concurrent.LinkedBlockingQueue<RemoteCommand>()

    override suspend fun handle(
        commandId: String,
        command: RemoteCommand,
        reply: suspend (String) -> Unit,
    ) {
        received.offer(command)
        reply("handled")
    }
}
```

Add these tests using the existing fake OkHttp polling helper:

```kotlin
@Test
fun pairedOwnerDisarmReachesExecutorWithoutTotp() {
    whenever(mockPrefsManager.getAllowedChatIds()).thenReturn(setOf("111"))
    whenever(mockPrefsManager.isChatIdAllowed("111")).thenReturn(true)
    val executor = RecordingCommandExecutor()

    runPollingWithUpdates(
        updates = listOf(updateJson(1, "111", "/disarm")),
        commandExecutor = executor,
    )

    assertEquals(RemoteCommand.Disarm, executor.received.poll(2, TimeUnit.SECONDS))
}

@Test
fun unpairedDisarmNeverReachesExecutor() {
    whenever(mockPrefsManager.getAllowedChatIds()).thenReturn(setOf("111"))
    whenever(mockPrefsManager.isChatIdAllowed("222")).thenReturn(false)
    val executor = RecordingCommandExecutor()

    runPollingWithUpdates(
        updates = listOf(updateJson(1, "222", "/disarm")),
        commandExecutor = executor,
    )

    assertTrue(executor.received.isEmpty())
}
```

Extend the helper with `commandExecutor: TelegramCommandExecutor? = null` and pass it to `TelegramBotClient(commandHandler = commandExecutor)`.

- [ ] **Step 3: Update dispatcher RED coverage**

Change `PrioritizedCommandDispatcherTest` to submit `RemoteCommand.Disarm` and match it by equality:

```kotlin
isDisarm = { it == RemoteCommand.Disarm }
```

Preserve the existing assertion that disarm supersedes/cancels queued arm work.

- [ ] **Step 4: Run RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*RemoteCommandTest' --tests '*PrioritizedCommandDispatcherTest' --tests '*TelegramBotClientAuthorizationTest'
```

Expected RED: `Disarm` still requires `code`, `TelegramCommandExecutor` is absent, and `TelegramBotClient` still requires `TotpAuthenticator`.

- [ ] **Step 5: Implement the exact parser contract**

In `RemoteCommand.parse` use:

```kotlin
"/disarm" -> if (argument == null) Disarm else Unknown
```

Delete `isDisarmAuthorized` and the `VerificationResult` import.

- [ ] **Step 6: Remove TOTP from the Telegram client path**

Make `TelegramCommandHandler` implement `TelegramCommandExecutor`. Change the client constructor to accept `TelegramCommandExecutor?`. Replace the complete code/seed branch with:

```kotlin
RemoteCommand.Disarm -> delegate(chatId, commandId, command)
```

Use equality in dispatcher wiring:

```kotlin
isDisarm = { queued -> queued.command == RemoteCommand.Disarm }
```

Do not add a seed read, verifier call, argument ignore, or special legacy reply.

- [ ] **Step 7: Update constructor wiring**

- `SensorService`: remove the TOTP import and `totpAuthenticator = TotpAuthenticator(preferences)` argument.
- `ProtectionRuntimeGraph`: remove the unused TOTP construction from its alert-delivery `TelegramBotClient`.
- `Navigation`: keep the Settings authenticator object temporarily for Task 2, but construct its local `TelegramBotClient` without passing it.
- Update every test constructor returned by `rg -n "TelegramBotClient\("`.

- [ ] **Step 8: Add handler success coverage**

In `TelegramCommandHandlerTest`, add a test using the existing real coordinator fixture:

```kotlin
@Test
fun disarmDelegatesToCoordinatorAndRepliesSuccess() = runTest {
    val replies = mutableListOf<String>()
    val handler = handler(
        armingDelay = ArmingDelay { },
        initialState = ProtectionState.ARMED_HEALTHY,
    )

    handler.handle("tg-disarm", RemoteCommand.Disarm) { replies += it }

    assertEquals("✅ ปลดการป้องกันสำเร็จ", replies.single())
}
```

Extend the existing `handler` helper with `initialState: ProtectionState = ProtectionState.DISARMED_ONLINE` and use `state = initialState` in its initial snapshot. This makes the test prove an Armed → Disarmed transition rather than only formatting an already-disarmed result.

- [ ] **Step 9: Run GREEN and source scan**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*RemoteCommandTest' --tests '*PrioritizedCommandDispatcherTest' --tests '*TelegramBotClientAuthorizationTest' --tests '*TelegramCommandHandlerTest'
rg -n "command\.code|getTotpSeed|verifyCode|TOTP_" app/src/main/java/com/example/motorcycleantitheftsensor/telegram
```

Required: focused tests exit `0`; the scan returns no TOTP authorization path; allowed owner dispatch count is one and unpaired dispatch count is zero.

- [ ] **Step 10: Apply the commit gate**

Use the Dirty-Worktree Commit Gate. Suggested commit message only if staged hunks are clean:

```text
feat(telegram): allow paired owner disarm without TOTP
```

---

### Task 2: Remove Authenticator From Settings End-to-End

**Files:**

- Create test: `ui/AuthenticatorUiRemovalContractTest.kt`
- Modify production: `ui/ProtectionUiModels.kt`, `ui/ProtectionAppScreen.kt`, `ui/ProtectionViewModel.kt`, `ui/AndroidProtectionSettingsGateway.kt`, `ui/settings/SettingsScreen.kt`, `Navigation.kt`
- Modify tests: `ui/AndroidProtectionSettingsGatewayTest.kt`, `ui/ProtectionViewModelTest.kt`, `androidTest/.../ui/ProtectionAppScreenTest.kt`

**Interfaces:**

- Consumes: existing non-authenticator `ProtectionSettingsGateway` operations.
- Produces:

```kotlin
data class ProtectionSettingsSummary(
    val tokenConfigured: Boolean,
    val pairedOwnerCount: Int,
    val pairingCode: String?,
    val sensitivity: Int,
    val smsFallbackConfigured: Boolean,
    val missingPermissions: Set<String>,
)
```

`ProtectionSettingsGateway`, `ProtectionAppActions`, `ProtectionViewModel`, and `AndroidProtectionSettingsOperations` expose no Authenticator member.

- [ ] **Step 1: Write the host removal-contract RED test**

Create `AuthenticatorUiRemovalContractTest.kt`:

```kotlin
class AuthenticatorUiRemovalContractTest {
    @Test
    fun uiAndSettingsSourcesContainNoAuthenticatorFlow() {
        val paths = listOf(
            "src/main/java/com/example/motorcycleantitheftsensor/Navigation.kt",
            "src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt",
            "src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreen.kt",
            "src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt",
            "src/main/java/com/example/motorcycleantitheftsensor/ui/AndroidProtectionSettingsGateway.kt",
            "src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt",
        )
        val forbidden = listOf(
            "AuthenticatorSetupDetails",
            "authenticatorConfigured",
            "beginAuthenticatorSetup",
            "cancelAuthenticatorSetup",
            "verifyAuthenticator",
            "AuthenticatorQrCodeEncoder",
            "authenticator_qr_code",
            "otpauth://",
        )

        paths.forEach { path ->
            val source = java.io.File(path).readText()
            forbidden.forEach { token ->
                assertFalse("$path still contains $token", source.contains(token))
            }
        }
    }
}
```

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*AuthenticatorUiRemovalContractTest'
```

Expected RED lists the existing model, ViewModel, Settings dialog, QR, and wiring tokens.

- [ ] **Step 3: Remove Settings contracts and backend operations**

- Delete `AuthenticatorSetupDetails`.
- Delete `authenticatorConfigured` from `ProtectionSettingsSummary` and every constructor/copy call.
- Delete begin/cancel/verify methods from `ProtectionSettingsGateway`.
- Delete authenticator lock/version/pending candidate and methods from `AndroidProtectionSettingsGateway`.
- Delete `getTotpSeed`, candidate creation, verification, and activation from `AndroidProtectionSettingsOperations` and `EncryptedAndroidProtectionSettingsOperations`.
- Keep bot token timeout, pairing reset, sensitivity, SMS, and settings-read behavior unchanged.

- [ ] **Step 4: Remove ViewModel and action wiring**

- Delete `beginAuthenticatorSetup` and `verifyAuthenticator` from `ProtectionViewModel`.
- Delete their TOTP guidance failure codes from those call sites.
- Remove the Authenticator callbacks from `ProtectionAppActions`.
- Remove callback assignments from `Navigation`.
- Remove Authenticator fields/methods from test fakes.
- Preserve `runSensitiveSettingsCommand` because bot token and other sensitive Settings operations still use it.

- [ ] **Step 5: Remove the complete Compose UI block**

From `SettingsScreen` remove:

- Authenticator imports, state, cancellation function, `DisposableEffect`, and `FLAG_SECURE` effect used only by the setup dialog;
- the `item(key = "authenticator")` card;
- QR `LaunchedEffect`, bitmap generation, show/hide controls, secret reveal, verification field, errors, and dialog;
- Authenticator tags and accessibility descriptions.

Do not change bot token, pairing, sensitivity, permissions, SMS, diagnostics, scrolling, or loading state.

- [ ] **Step 6: Replace obsolete tests with absence coverage**

- Delete Authenticator setup/verification cases and fake fields from `AndroidProtectionSettingsGatewayTest` and `ProtectionViewModelTest`.
- Remove `authenticatorConfigured` from every test fixture.
- Delete the three QR/secret UI tests from `ProtectionAppScreenTest`.
- Add one Settings UI test that navigates to Settings and asserts zero nodes for `Authenticator`, `Set up authenticator`, `Replace authenticator`, `Show QR code`, and tag `authenticator_qr_code`.
- Keep every unrelated Settings, navigation, sensitivity, and permission test.

- [ ] **Step 7: Run GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*AuthenticatorUiRemovalContractTest' --tests '*AndroidProtectionSettingsGatewayTest' --tests '*ProtectionViewModelTest'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:compileDebugAndroidTestKotlin
```

Required: host tests and Android-test compilation exit `0`; no Authenticator field remains in UI production sources.

- [ ] **Step 8: Apply the commit gate**

Suggested clean commit message:

```text
refactor(settings): remove authenticator setup flow
```

---

### Task 3: Delete TOTP/QR Code and Clean the Legacy Seed

**Files:**

- Create: `data/LegacyAuthenticatorMigration.kt`, `data/LegacyAuthenticatorMigrationTest.kt`
- Modify: `data/EncryptedPrefsManager.kt`, `service/SensorService.kt`, `app/build.gradle.kts`
- Delete: `security/TotpAuthenticator.kt`, `ui/settings/AuthenticatorQrCodeEncoder.kt` and their three unit/source-contract tests.

**Interfaces:**

- Consumes: encrypted preference key `enc_totp_seed` only as a legacy migration key.
- Produces:

```kotlin
internal enum class LegacyAuthenticatorMigrationResult {
    NOT_PRESENT,
    REMOVED,
    FAILED,
}

internal class LegacyAuthenticatorMigration(
    private val containsLegacySeed: () -> Boolean,
    private val removeLegacySeed: () -> Boolean,
) {
    fun run(): LegacyAuthenticatorMigrationResult
}

internal fun EncryptedPrefsManager.removeLegacyAuthenticatorState(): LegacyAuthenticatorMigrationResult
```

- [ ] **Step 1: Write migration RED tests**

Create tests for absent, removed, failed, and idempotent cases. Use a fake map to prove unrelated values survive:

```kotlin
@Test
fun removesOnlyLegacySeedAndIsIdempotent() {
    val values = mutableMapOf(
        "enc_totp_seed" to "legacy-test-seed",
        "enc_telegram_bot_token" to "keep-token",
        "enc_allowed_chat_ids" to "keep-owner",
        "sensor_sensitivity_level" to "keep-sensitivity",
    )
    val migration = LegacyAuthenticatorMigration(
        containsLegacySeed = { values.containsKey("enc_totp_seed") },
        removeLegacySeed = { values.remove("enc_totp_seed") != null },
    )

    assertEquals(LegacyAuthenticatorMigrationResult.REMOVED, migration.run())
    assertEquals(LegacyAuthenticatorMigrationResult.NOT_PRESENT, migration.run())
    assertEquals("keep-token", values["enc_telegram_bot_token"])
    assertEquals("keep-owner", values["enc_allowed_chat_ids"])
    assertEquals("keep-sensitivity", values["sensor_sensitivity_level"])
}
```

Add a failure test whose remove lambda returns `false`; expect `FAILED`, not an exception.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*LegacyAuthenticatorMigrationTest'
```

Expected RED: migration types do not exist.

- [ ] **Step 3: Implement the pure migration and encrypted wrapper**

Implement:

```kotlin
internal class LegacyAuthenticatorMigration(
    private val containsLegacySeed: () -> Boolean,
    private val removeLegacySeed: () -> Boolean,
) {
    fun run(): LegacyAuthenticatorMigrationResult = when {
        !containsLegacySeed() -> LegacyAuthenticatorMigrationResult.NOT_PRESENT
        removeLegacySeed() -> LegacyAuthenticatorMigrationResult.REMOVED
        else -> LegacyAuthenticatorMigrationResult.FAILED
    }
}
```

Rename `KEY_TOTP_SEED` to `LEGACY_AUTHENTICATOR_SEED_KEY` while preserving its stored value `"enc_totp_seed"`. Delete `saveTotpSeed` and `getTotpSeed`. Add a wrapper that calls `prefs.contains` and one `prefs.edit().remove(...).commit()` through the pure migration.

- [ ] **Step 4: Run cleanup on IO before service initialization completes**

At the start of the existing `serviceScope.launch` initialization block in `SensorService`:

```kotlin
val legacyCleanup = withContext(Dispatchers.IO) {
    preferences.removeLegacyAuthenticatorState()
}
if (legacyCleanup == LegacyAuthenticatorMigrationResult.FAILED) {
    Log.w(TAG, "Legacy authenticator cleanup failed")
}
```

Continue recovery and complete `initialization` regardless of `FAILED`. Do not log the key or old value. Do not move this commit to the main thread.

- [ ] **Step 5: Delete implementation, QR, and dependency**

- Delete both production files and the three obsolete tests listed above.
- Remove `implementation("com.google.zxing:core:3.5.4")` from `app/build.gradle.kts`.
- Run `rg -n "com.google.zxing|TotpAuthenticator|AuthenticatorQrCodeEncoder|saveTotpSeed|getTotpSeed" app app/build.gradle.kts`; only an explicitly justified test token may remain, and production must have no hit.

- [ ] **Step 6: Run GREEN and compile gates**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*LegacyAuthenticatorMigrationTest' --tests '*TelegramBotClientAuthorizationTest' --tests '*AndroidProtectionSettingsGatewayTest'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin
```

Required: all commands exit `0`; no production constructor or storage API references TOTP.

- [ ] **Step 7: Apply the commit gate**

Suggested clean commit message:

```text
refactor(security): remove TOTP and QR implementation
```

---

### Task 4: Remove TOTP Guidance and Update Telegram Help

**Files:** Modify `protection/UserGuidance.kt`, `protection/UserGuidanceCatalogTest.kt`, and any exact references returned by the Task 0 inventory.

**Interfaces:**

- Consumes: existing `GuidanceCode.COMMAND_HELP`, `COMMAND_UNKNOWN`, `UNAUTHORIZED_COMMAND`, and `COMMAND_DISARM_APPLIED`.
- Produces: help text `ℹ️ คำสั่ง: /status, /arm, /disarm, /sensitivity 1-10` with no TOTP guidance codes or Authenticator action.

- [ ] **Step 1: Write guidance RED tests**

Replace obsolete TOTP assertions with:

```kotlin
@Test
fun commandHelpDocumentsArgumentFreeDisarm() {
    val help = UserGuidanceCatalog.content(GuidanceCode.COMMAND_HELP)
    assertEquals(
        "ℹ️ คำสั่ง: /status, /arm, /disarm, /sensitivity 1-10",
        help.telegramTh,
    )
    assertFalse(help.telegramTh.orEmpty().contains("<รหัส>"))
    assertFalse(help.telegramTh.orEmpty().contains("TOTP", ignoreCase = true))
}
```

Add a source-enum assertion that no guidance enum name starts with `TOTP_` and `GuidanceAction` contains no `OPEN_AUTHENTICATOR_SETTINGS`.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*UserGuidanceCatalogTest'
```

Expected RED: help still says `/disarm <รหัส>` and TOTP codes/actions still exist.

- [ ] **Step 3: Remove obsolete guidance**

Delete all `TOTP_SETUP_*`, `TOTP_CONFIGURED`, `TOTP_NOT_CONFIGURED`, `TOTP_REQUIRED`, `TOTP_INVALID`, `TOTP_LOCKED`, and `TOTP_REMOTE_RECOVERY_REQUIRED` enum entries and catalog branches. Delete `OPEN_AUTHENTICATOR_SETTINGS` if the repository-wide search shows no remaining consumer. Update `COMMAND_HELP` exactly as specified.

Preserve pairing, unauthorized, unknown, disarm applied/rejected, and all unrelated guidance text.

- [ ] **Step 4: Run GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*UserGuidanceCatalogTest' --tests '*RemoteCommandTest' --tests '*TelegramCommandHandlerTest'
```

- [ ] **Step 5: Apply the commit gate**

Suggested clean commit message:

```text
refactor(guidance): remove authenticator messaging
```

---

### Task 5: Add a Repository-Wide Removal Contract

**Files:** Create `security/AuthenticatorRemovalSourceContractTest.kt`; modify only exact remaining call sites found by its RED output.

**Interfaces:**

- Consumes: final source tree after Tasks 1-4.
- Produces: a host gate preventing reintroduction of the removed Authenticator stack.

- [ ] **Step 1: Write the aggregate contract test**

```kotlin
class AuthenticatorRemovalSourceContractTest {
    @Test
    fun productionAndBuildFilesContainNoAuthenticatorImplementation() {
        val productionRoot = java.io.File("src/main/java")
        val production = productionRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        val build = java.io.File("build.gradle.kts").readText()
        val forbidden = listOf(
            "TotpAuthenticator",
            "AuthenticatorQrCodeEncoder",
            "AuthenticatorSetupDetails",
            "authenticatorConfigured",
            "beginAuthenticatorSetup",
            "cancelAuthenticatorSetup",
            "verifyAuthenticator",
            "saveTotpSeed",
            "getTotpSeed",
            "otpauth://",
            "TOTP_",
            "OPEN_AUTHENTICATOR_SETTINGS",
        )

        forbidden.forEach { token ->
            assertFalse("Production still contains $token", production.contains(token))
        }
        assertFalse(build.contains("com.google.zxing:core"))
    }
}
```

The isolated legacy key name is allowed only inside the migration/storage files and therefore must not be included as a forbidden token.

- [ ] **Step 2: Run the aggregate gate**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*AuthenticatorRemovalSourceContractTest'
```

If RED, remove only the exact obsolete call sites it lists. Do not delete unrelated pairing or encryption code.

- [ ] **Step 3: Run all focused removal tests**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*AuthenticatorRemovalSourceContractTest' --tests '*AuthenticatorUiRemovalContractTest' --tests '*LegacyAuthenticatorMigrationTest' --tests '*RemoteCommandTest' --tests '*PrioritizedCommandDispatcherTest' --tests '*TelegramBotClientAuthorizationTest' --tests '*TelegramCommandHandlerTest' --tests '*AndroidProtectionSettingsGatewayTest' --tests '*ProtectionViewModelTest' --tests '*UserGuidanceCatalogTest'
```

Record XML suite/test/failure/error/skipped totals. Required: exit `0`, failures `0`, errors `0`.

- [ ] **Step 4: Verify scoped repository search**

```powershell
rg -n -i "TotpAuthenticator|AuthenticatorQrCodeEncoder|AuthenticatorSetupDetails|authenticatorConfigured|beginAuthenticatorSetup|cancelAuthenticatorSetup|verifyAuthenticator|saveTotpSeed|getTotpSeed|otpauth://|TOTP_|OPEN_AUTHENTICATOR_SETTINGS|com.google.zxing" app/src/main app/build.gradle.kts
rg -n "disarm.*code|/disarm <|/disarm 123456|Disarm\(" app/src/main app/src/test app/src/androidTest --glob '*.kt'
```

Required: first scan has no production hit except the isolated legacy migration key where applicable. The second scan may contain test-only invalid-input literals but no production compatibility branch.

- [ ] **Step 5: Apply the commit gate**

Suggested clean commit message:

```text
test(security): enforce authenticator removal
```

---

### Task 6: Full Host, APK, and Real-Device Acceptance

**Files:** Create/update `docs/superpowers/status/2026-08-15-remove-authenticator-telegram-disarm-evidence.md`. Do not modify production to make evidence look green.

**Interfaces:**

- Consumes: Tasks 1-5 GREEN implementation.
- Produces: reproducible host/build/device evidence separated into PASS, FAIL, and NOT RUN.

- [ ] **Step 1: Run the full host unit suite**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_OPTS='-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8'
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest
```

Parse every `app/build/test-results/testDebugUnitTest/TEST-*.xml` file. Record suites/tests/failures/errors/skipped and exit code.

- [ ] **Step 2: Build APK and compile Android tests**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:assembleDebug :app:compileDebugAndroidTestKotlin
Get-FileHash -Algorithm SHA256 'app\build\outputs\apk\debug\app-debug.apk'
```

Record exact APK SHA-256. Do not reuse a prior APK hash.

- [ ] **Step 3: Check staged/scoped whitespace before integration**

```powershell
git diff --cached --check
git diff --cached --name-only
git status --short
```

If the work is intentionally uncommitted because of overlapping dirty files, run `git diff --check --` only on the exact task-owned paths and report pre-existing findings separately.

- [ ] **Step 4: Install without clearing application data**

Resolve one connected Huawei device explicitly. Never guess a serial, uninstall, or clear data.

```powershell
$adb='C:\Users\ASUS\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$deviceRows=@(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice$' })
if ($deviceRows.Count -ne 1) { throw "Expected exactly one authorized Android device; found $($deviceRows.Count)" }
$deviceSerial=($deviceRows[0] -split '\s+')[0]
& $adb -s $deviceSerial install -r 'app\build\outputs\apk\debug\app-debug.apk'
```

If no single authorized device is available, mark device work `NOT RUN`; do not guess a serial or weaken the count check.

- [ ] **Step 5: Verify upgrade cleanup and Settings**

Without dumping secrets or clearing data:

1. Launch the upgraded app.
2. Confirm existing bot token/owner pairing/sensitivity/SMS configuration indicators remain unchanged.
3. Open Settings and confirm there is no Authenticator card, setup/replace button, QR, secret, or six-digit verification field.
4. Confirm no crash/ANR occurs during service initialization.
5. Record the non-secret cleanup outcome; do not print encrypted preferences.

- [ ] **Step 6: Verify Telegram commands using two accounts**

| Scenario | Required result |
|---|---|
| Paired owner sends `/status` | Existing truthful status reply |
| Paired owner sends `/arm` | Existing arm flow and result |
| Paired owner sends exact `/disarm` | Coordinator disarms once; reply is `✅ ปลดการป้องกันสำเร็จ` |
| Paired owner sends `/disarm 123456` | Generic unknown-command reply; no state change |
| Unpaired account sends `/disarm` | Unauthorized response; no state change |
| `/disarm` during active alert | Sensors/pursuit stop through existing coordinator flow; truthful persistence result |
| `/disarm` while already disarmed | Safe, deterministic existing idempotent result; no crash |
| `/sensitivity 1-10` | Existing behavior unchanged |

Use the app state, Telegram reply, and non-secret logs to correlate one command ID with one coordinator transition. Do not capture tokens, chat IDs, command arguments, QR/seed remnants, or coordinates.

- [ ] **Step 7: Regression-smoke unrelated systems**

Confirm Telegram polling resumes after service restart, foreground notification remains silent/ongoing, GPS still obtains/sends location under its existing acceptance flow, and local Arm/Disarm still work. These are regression checks only; do not redesign them in this task.

- [ ] **Step 8: Write final evidence**

The status document must include:

- source/spec/plan commit IDs and dirty-worktree boundary;
- exact commands and exits;
- XML totals and APK hash;
- every device scenario as PASS, FAIL, or NOT RUN;
- confirmation that app data was preserved;
- confirmation that paired owner configuration survived upgrade;
- remaining OEM, Telegram-network, or unavailable-device risks;
- explicit statement that no GPS re-review was performed unless separately requested.

## Final Acceptance Criteria

- Exact `/disarm` is argument-free and case-insensitive.
- `/disarm 123456` and every other argument form are generic unknown commands with no special compatibility code.
- Only an existing allowed owner reaches the executor/coordinator; an unpaired chat never does.
- A successful coordinator disarm produces `✅ ปลดการป้องกันสำเร็จ` exactly once.
- Telegram production code contains no TOTP seed read, code verification, or lockout path.
- Settings and UI models/actions contain no Authenticator status, callback, dialog, QR, secret, or verification input.
- TOTP and QR implementation files/tests are deleted and ZXing is removed when no other consumer exists.
- The upgrade migration deletes only the legacy encrypted TOTP seed and is idempotent/non-blocking to main.
- Token, paired owners, pairing, sensitivity, SMS, protection, incidents, and GPS state survive upgrade.
- TOTP guidance/actions are deleted and Telegram help documents `/disarm` without an argument.
- Focused removal tests, full host tests, APK build, Android-test compilation, Huawei upgrade, paired-owner, unpaired-account, and regression smoke all pass before readiness is claimed.

## Agent Handoff Rules

- Execute Tasks 0-6 in order and stop at every GREEN checkpoint for review.
- Use `apply_patch` for hand edits; do not use bulk rewrites across unrelated files.
- Re-run `rg` after every removal task because untracked files are not shown by ordinary `git diff`.
- Never delete a file until repository-wide search confirms all production/test call sites assigned to the same task are removed.
- Never weaken pairing authorization or coordinator truthfulness to make `/disarm` tests pass.
- Never claim device acceptance from host tests or APK assembly.
- Never commit mixed pre-existing hunks. Use the Dirty-Worktree Commit Gate or leave the task checkpointed and uncommitted.
- Report blocked external conditions as `NOT RUN`, never PASS.
