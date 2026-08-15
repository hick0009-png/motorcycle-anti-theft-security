# Authenticator Removal Completeness Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the approved Authenticator removal by deleting the remaining dead TOTP UI/model surface and making legacy-seed cleanup unable to block `SensorService` startup.

**Architecture:** Keep the working paired-owner `/disarm` flow unchanged. Remove only the obsolete TOTP members still present in the unused legacy dashboard/config model, strengthen source contracts so those members cannot return, and contain non-cancellation preference failures inside the pure legacy migration so `SensorService` receives `FAILED` and continues initialization.

**Tech Stack:** Kotlin, Android, Jetpack Compose, Kotlin coroutines, JUnit 4, Gradle wrapper, ADB.

## Global Constraints

- Treat `docs/superpowers/specs/2026-08-15-telegram-disarm-without-authenticator-design.md` and `docs/superpowers/plans/2026-08-15-remove-authenticator-telegram-disarm.md` as the approved behavior contract.
- Preserve `/pair <code>` and the persisted allowed Telegram Chat ID set as the only Telegram-owner authorization boundary.
- Preserve exact parser behavior: `/disarm` is accepted; `/disarm 123456` and every other argument-bearing form are `RemoteCommand.Unknown` and must not change protection state.
- Do not change the existing three-failed-attempt/five-minute lock behavior outside this removed Authenticator path.
- Do not modify GPS pursuit, notification, sensor, SMS, call, incident, or protection-state behavior.
- Do not add dependencies. ZXing must remain absent.
- Never log or expose bot tokens, Chat IDs, pairing codes, removed TOTP data, preference ciphertext, or command arguments.
- Do not clear app data during device verification. Upgrade behavior and preservation of the existing Telegram owner pairing must be tested with `adb install -r`.
- Preserve coroutine cancellation. `CancellationException` must be rethrown, not converted to `FAILED`.
- The checkout is a mixed dirty worktree containing Authenticator, GPS, Settings, and other user changes. Never run `git reset`, `git clean`, `git checkout --`, `git stash`, or `git add .`; never revert unrelated hunks.
- `DashboardScreen.kt` already contains an unrelated working-tree change that displays `pairingCode` without checking `allowedChatIds.isEmpty()`. Preserve that hunk exactly.
- `AI_WORKFLOW.md` was not present at plan-writing time. If it appears before execution, read and follow it before editing.
- Use the Android Studio JBR and low-concurrency Gradle baseline:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' <tasks>
```

---

## Current Verified Baseline and Findings

- Working-tree implementation is based on `c5b3cd6` (`docs: plan authenticator removal`) and is not isolated in its own implementation commit.
- The new Telegram flow is correct in current source: the allowed-chat check precedes delegation, exact `/disarm` delegates once, and `/disarm 123456` is rejected.
- Fresh focused gate: 58 tests, 0 failures, 0 errors, 0 skipped.
- Fresh full host gate: 62 suites / 323 tests, 0 failures, 0 errors, 0 skipped.
- `:app:assembleDebug` and `:app:compileDebugAndroidTestKotlin` succeeded.
- Current debug APK SHA-256 before this remediation: `A2A6DC7D02750092C26CC565E4CF4C983FD760288F02908D723F7CD615B06FAD`.
- Finding 1: `DashboardScreen.kt` still contains TOTP parameters, state, clipboard handling, setup button, status, secret display, OTP input, and verification dialog. `DeviceConfig.kt` still contains `hasTotpSeed`.
- Finding 2: `LegacyAuthenticatorMigration.run()` does not contain exceptions from encrypted preference access. A thrown non-cancellation exception escapes the initialization coroutine before `initialization.complete(Unit)`, leaving `onStartCommand()` waiting at `initialization.await()`.
- Existing removal contracts pass despite Finding 1 because their file/token coverage does not include the residual names.

## File Responsibility Map

- Modify `app/src/main/java/com/example/motorcycleantitheftsensor/ui/DashboardScreen.kt` — remove only the dead Authenticator/TOTP surface while preserving all non-Authenticator dashboard behavior and the existing pairing-code hunk.
- Modify `app/src/main/java/com/example/motorcycleantitheftsensor/data/DeviceConfig.kt` — remove only the obsolete `hasTotpSeed` property.
- Modify `app/src/test/java/com/example/motorcycleantitheftsensor/security/AuthenticatorRemovalContractTest.kt` — make the production-source gate detect the exact residual model/UI identifiers.
- Modify `app/src/test/java/com/example/motorcycleantitheftsensor/ui/AuthenticatorUiRemovalContractTest.kt` — include the legacy dashboard in UI removal coverage.
- Modify `app/src/main/java/com/example/motorcycleantitheftsensor/data/LegacyAuthenticatorMigration.kt` — convert non-cancellation failures into `LegacyAuthenticatorMigrationResult.FAILED`.
- Modify `app/src/test/java/com/example/motorcycleantitheftsensor/data/LegacyAuthenticatorMigrationTest.kt` — cover contains/remove exceptions and cancellation propagation.
- Modify `docs/superpowers/status/2026-08-15-remove-authenticator-telegram-disarm-evidence.md` — replace the stale no-device statement with fresh host, APK, upgrade-install, and real-device acceptance evidence.

---

### Task 1: Remove Residual Authenticator UI and Model State

**Files:**
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/security/AuthenticatorRemovalContractTest.kt:18-31`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/ui/AuthenticatorUiRemovalContractTest.kt:9-26`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/DashboardScreen.kt:45-46,112-114,127-139,498-675`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/data/DeviceConfig.kt:7-14`

**Interfaces:**
- Consumes: existing `DashboardScreen(...)` Compose entry point and `DeviceConfig` data model; neither currently has a production or test call site outside its own declaration.
- Produces: the same `DashboardScreen(...)` behavior without TOTP parameters/callbacks/dialog, and `DeviceConfig(deviceUuid, isArmed, sensitivityLevel, hasBotToken, allowedChatCount)`.

- [ ] **Step 1: Protect the unrelated dashboard change before editing**

Run:

```powershell
git status --short
git diff -- app/src/main/java/com/example/motorcycleantitheftsensor/ui/DashboardScreen.kt
rg -n "DashboardScreen\(|DeviceConfig\(" app/src/main app/src/test app/src/androidTest
```

Required observations:

- The dashboard diff contains the pre-existing `if (pairingCode != null)` hunk; retain it.
- `DashboardScreen(` and `DeviceConfig(` have no call sites beyond their declarations. If a new call site exists, update that call site only to remove the deleted TOTP argument; do not redesign it.

- [ ] **Step 2: Strengthen the production-source contract and make it RED**

Append these exact residual tokens to `forbiddenTokens` in `AuthenticatorRemovalContractTest.kt`:

```kotlin
"totpSecret",
"totpUri",
"totpStatusMessage",
"onSetupTotp",
"onVerifyTotpCode",
"hasTotpSeed",
"Google Authenticator",
"Setup Auth",
"TOTP ED25519 READY",
```

Do not forbid the generic words `Authenticator` or `TOTP`; the intentionally named one-shot legacy migration still needs those words until the old encrypted key has been cleaned.

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*AuthenticatorRemovalContractTest'
```

Expected: FAIL listing `DashboardScreen.kt` and `DeviceConfig.kt` residual tokens. If it passes, stop and correct the test path before production edits.

- [ ] **Step 3: Extend the UI-specific contract and keep it RED**

Add this path to `paths` in `AuthenticatorUiRemovalContractTest.kt`:

```kotlin
"src/main/java/com/example/motorcycleantitheftsensor/ui/DashboardScreen.kt",
```

Append these exact UI tokens to `forbidden`:

```kotlin
"totpSecret",
"totpUri",
"totpStatusMessage",
"onSetupTotp",
"onVerifyTotpCode",
"Google Authenticator",
"Setup Auth",
"TOTP ED25519 READY",
```

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*AuthenticatorUiRemovalContractTest'
```

Expected: FAIL against `DashboardScreen.kt` for the exact TOTP UI tokens.

- [ ] **Step 4: Remove only the residual dashboard TOTP surface**

Make these precise edits in `DashboardScreen.kt`:

1. Delete imports `LocalClipboardManager` and `AnnotatedString` after confirming `rg` shows they have no non-TOTP use in this file.
2. Delete parameters `totpSecret`, `totpUri`, `totpStatusMessage`, `onSetupTotp`, and `onVerifyTotpCode` from `DashboardScreen(...)`.
3. Delete state `showAuthDialog`, `verifyCodeInput`, and `verificationResultText`, plus `clipboardManager`.
4. In the Telegram action row, retain the existing `Save Token` button and delete only the `Setup Auth` button and its callback body.
5. Delete the conditional `totpStatusMessage` rendering block.
6. Change the footer string from `SECURITY STATUS: ONLINE • TOTP ED25519 READY` to exactly `SECURITY STATUS: ONLINE`.
7. Delete the complete `if (showAuthDialog) { AlertDialog(...) }` block, ending immediately before the active-alarm dialog.
8. Remove imports made unused by those deletions only. Do not delete `AlertDialog`, `OutlinedTextField`, `Card`, or other imports that still serve the token, diagnostics, or alarm UI.

Do not alter the pairing-code condition, Telegram token handling, sensor cards, alarm dialog, or layout outside the removed Authenticator controls.

- [ ] **Step 5: Remove the obsolete model property**

Change `DeviceConfig` to this exact shape:

```kotlin
data class DeviceConfig(
    val deviceUuid: String,
    val isArmed: Boolean,
    val sensitivityLevel: Int,
    val hasBotToken: Boolean,
    val allowedChatCount: Int,
)
```

Do not delete the class or change the remaining property names.

- [ ] **Step 6: Run the Task 1 GREEN gate**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*AuthenticatorRemovalContractTest' --tests '*AuthenticatorUiRemovalContractTest' :app:compileDebugKotlin
rg -n -i "totpSecret|totpUri|totpStatusMessage|onSetupTotp|onVerifyTotpCode|hasTotpSeed|Google Authenticator|Setup Auth|TOTP ED25519 READY" app/src/main
git diff --check -- app/src/main/java/com/example/motorcycleantitheftsensor/ui/DashboardScreen.kt app/src/main/java/com/example/motorcycleantitheftsensor/data/DeviceConfig.kt app/src/test/java/com/example/motorcycleantitheftsensor/security/AuthenticatorRemovalContractTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/ui/AuthenticatorUiRemovalContractTest.kt
```

Required:

- Both contract suites pass.
- Kotlin compilation succeeds.
- The residual-token scan returns no production hits.
- Scoped `git diff --check` returns exit code `0`.
- The pre-existing pairing-code diff remains present and unchanged.

- [ ] **Step 7: Checkpoint Task 1 without capturing unrelated work**

Inspect exact diffs first:

```powershell
git diff -- app/src/main/java/com/example/motorcycleantitheftsensor/ui/DashboardScreen.kt app/src/main/java/com/example/motorcycleantitheftsensor/data/DeviceConfig.kt app/src/test/java/com/example/motorcycleantitheftsensor/security/AuthenticatorRemovalContractTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/ui/AuthenticatorUiRemovalContractTest.kt
```

Because `DashboardScreen.kt` already contains an unrelated user hunk, do not stage or commit the entire file in the shared dirty checkout. Record Task 1 as a reviewed working-tree checkpoint. Only in an isolated clean worktree where all four diffs belong to this task may the agent commit with:

```powershell
git add app/src/main/java/com/example/motorcycleantitheftsensor/ui/DashboardScreen.kt app/src/main/java/com/example/motorcycleantitheftsensor/data/DeviceConfig.kt app/src/test/java/com/example/motorcycleantitheftsensor/security/AuthenticatorRemovalContractTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/ui/AuthenticatorUiRemovalContractTest.kt
git commit -m "refactor(ui): remove residual authenticator surface"
```

---

### Task 2: Make Legacy Authenticator Cleanup Exception-Safe

**Files:**
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/data/LegacyAuthenticatorMigrationTest.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/data/LegacyAuthenticatorMigration.kt`

**Interfaces:**
- Consumes: `LegacyAuthenticatorMigration(containsLegacySeed: () -> Boolean, removeLegacySeed: () -> Boolean)` and `LegacyAuthenticatorMigrationResult`.
- Produces: `run(): LegacyAuthenticatorMigrationResult`, returning `FAILED` for non-cancellation exceptions and rethrowing `kotlin.coroutines.cancellation.CancellationException`.

- [ ] **Step 1: Add failing exception-containment tests**

Add the import:

```kotlin
import kotlin.coroutines.cancellation.CancellationException
```

Add these tests to `LegacyAuthenticatorMigrationTest`:

```kotlin
@Test
fun returnsFailedWhenContainsThrows() {
    val migration = LegacyAuthenticatorMigration(
        containsLegacySeed = { throw IllegalStateException("encrypted preferences unavailable") },
        removeLegacySeed = { true },
    )

    assertEquals(LegacyAuthenticatorMigrationResult.FAILED, migration.run())
}

@Test
fun returnsFailedWhenRemoveThrows() {
    val migration = LegacyAuthenticatorMigration(
        containsLegacySeed = { true },
        removeLegacySeed = { throw SecurityException("keystore read failed") },
    )

    assertEquals(LegacyAuthenticatorMigrationResult.FAILED, migration.run())
}

@Test(expected = CancellationException::class)
fun propagatesCancellation() {
    LegacyAuthenticatorMigration(
        containsLegacySeed = { throw CancellationException("service stopping") },
        removeLegacySeed = { true },
    ).run()
}
```

The exception messages are fixed test literals and contain no real preference data.

- [ ] **Step 2: Run the migration test and verify RED**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*LegacyAuthenticatorMigrationTest'
```

Expected: the two non-cancellation exception tests fail because the exceptions currently escape. The cancellation test should already pass. If a different baseline failure appears, diagnose it before editing production code.

- [ ] **Step 3: Implement the minimal exception boundary**

Add this import to `LegacyAuthenticatorMigration.kt`:

```kotlin
import kotlin.coroutines.cancellation.CancellationException
```

Replace `run()` with exactly this behavior:

```kotlin
fun run(): LegacyAuthenticatorMigrationResult = try {
    when {
        !containsLegacySeed() -> LegacyAuthenticatorMigrationResult.NOT_PRESENT
        removeLegacySeed() -> LegacyAuthenticatorMigrationResult.REMOVED
        else -> LegacyAuthenticatorMigrationResult.FAILED
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    LegacyAuthenticatorMigrationResult.FAILED
}
```

Do not log the exception object or preference contents. `SensorService` already logs the generic message `Legacy authenticator cleanup failed` when it receives `FAILED`, then proceeds to recovery setup and `initialization.complete(Unit)`.

- [ ] **Step 4: Run the Task 2 GREEN and startup-adjacent gates**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*LegacyAuthenticatorMigrationTest' --tests '*RemoteControlReadinessTest' --tests '*TelegramBotClientAuthorizationTest' :app:compileDebugKotlin
git diff --check -- app/src/main/java/com/example/motorcycleantitheftsensor/data/LegacyAuthenticatorMigration.kt app/src/test/java/com/example/motorcycleantitheftsensor/data/LegacyAuthenticatorMigrationTest.kt
```

Required:

- All selected tests pass.
- Kotlin compilation succeeds.
- The absent, removed, false-return failure, contains-exception failure, remove-exception failure, idempotence, and cancellation cases are covered.
- Scoped `git diff --check` returns exit code `0`.

- [ ] **Step 5: Checkpoint Task 2 without staging unrelated files**

Run:

```powershell
git diff -- app/src/main/java/com/example/motorcycleantitheftsensor/data/LegacyAuthenticatorMigration.kt app/src/test/java/com/example/motorcycleantitheftsensor/data/LegacyAuthenticatorMigrationTest.kt
```

If these files contain only Task 2 changes in an isolated clean worktree, commit:

```powershell
git add app/src/main/java/com/example/motorcycleantitheftsensor/data/LegacyAuthenticatorMigration.kt app/src/test/java/com/example/motorcycleantitheftsensor/data/LegacyAuthenticatorMigrationTest.kt
git commit -m "fix(service): contain legacy authenticator cleanup failures"
```

In the shared dirty checkout, leave them as a reviewed checkpoint rather than mixing commits with unrelated work.

---

### Task 3: Full Verification, Upgrade Acceptance, and Evidence

**Files:**
- Modify: `docs/superpowers/status/2026-08-15-remove-authenticator-telegram-disarm-evidence.md`
- Verify only: all files changed by Tasks 1 and 2 plus the existing Telegram disarm implementation.

**Interfaces:**
- Consumes: the complete working-tree implementation and debug APK.
- Produces: reproducible host/build/device evidence sufficient to accept the Authenticator removal as complete.

- [ ] **Step 1: Run the focused Authenticator/Telegram gate with task reruns**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*AuthenticatorRemovalContractTest' --tests '*AuthenticatorUiRemovalContractTest' --tests '*LegacyAuthenticatorMigrationTest' --tests '*RemoteCommandTest' --tests '*PrioritizedCommandDispatcherTest' --tests '*TelegramBotClientAuthorizationTest' --tests '*TelegramCommandHandlerTest' --tests '*AndroidProtectionSettingsGatewayTest' --tests '*ProtectionViewModelTest' --tests '*UserGuidanceCatalogTest' --tests '*RemoteControlReadinessTest'
```

Required: exit code `0`, failures `0`, errors `0`, skipped `0`. Parse the fresh XML rather than relying only on the Gradle summary.

- [ ] **Step 2: Run the full host gate and record XML totals**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest
$totals = [ordered]@{ Suites = 0; Tests = 0; Failures = 0; Errors = 0; Skipped = 0 }
Get-ChildItem -Path 'app\build\test-results\testDebugUnitTest' -Filter 'TEST-*.xml' | ForEach-Object {
    $suite = [xml](Get-Content -LiteralPath $_.FullName -Raw)
    $totals.Suites += 1
    $totals.Tests += [int]$suite.testsuite.tests
    $totals.Failures += [int]$suite.testsuite.failures
    $totals.Errors += [int]$suite.testsuite.errors
    $totals.Skipped += [int]$suite.testsuite.skipped
}
Write-Output ('FULL_UNIT suites={0} tests={1} failures={2} errors={3} skipped={4}' -f $totals.Suites,$totals.Tests,$totals.Failures,$totals.Errors,$totals.Skipped)
```

Required: Gradle exit code `0`, failures `0`, errors `0`. Record the new test total; it will be higher than the 323-test baseline because Task 2 adds three tests.

- [ ] **Step 3: Rebuild the APK and compile Android tests**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 --rerun-tasks '-Dkotlin.compiler.execution.strategy=in-process' :app:assembleDebug :app:compileDebugAndroidTestKotlin
$apk = Get-Item -LiteralPath 'app\build\outputs\apk\debug\app-debug.apk'
$hash = Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256
Write-Output ('APK path={0} bytes={1} sha256={2}' -f $apk.FullName,$apk.Length,$hash.Hash)
```

Required: build and Android-test compilation exit `0`; record exact APK bytes and the new SHA-256.

- [ ] **Step 4: Run final source and scoped hygiene scans**

Run:

```powershell
rg -n -i "totpSecret|totpUri|totpStatusMessage|onSetupTotp|onVerifyTotpCode|hasTotpSeed|Google Authenticator|Setup Auth|TOTP ED25519 READY|TotpAuthenticator|AuthenticatorQrCodeEncoder|AuthenticatorSetupDetails|authenticatorConfigured|beginAuthenticatorSetup|cancelAuthenticatorSetup|verifyAuthenticator|saveTotpSeed|getTotpSeed|otpauth://|TOTP_|OPEN_AUTHENTICATOR_SETTINGS|com.google.zxing" app/src/main app/build.gradle.kts
rg -n '"/disarm" -> if \(argument == null\) Disarm else Unknown|isChatIdAllowed|RemoteCommand.Disarm -> delegate|coordinator.disarm\(commandId, CommandOrigin.TELEGRAM\)' app/src/main/java/com/example/motorcycleantitheftsensor
git diff --check -- app/src/main/java/com/example/motorcycleantitheftsensor/ui/DashboardScreen.kt app/src/main/java/com/example/motorcycleantitheftsensor/data/DeviceConfig.kt app/src/main/java/com/example/motorcycleantitheftsensor/data/LegacyAuthenticatorMigration.kt app/src/test/java/com/example/motorcycleantitheftsensor/security/AuthenticatorRemovalContractTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/ui/AuthenticatorUiRemovalContractTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/data/LegacyAuthenticatorMigrationTest.kt docs/superpowers/status/2026-08-15-remove-authenticator-telegram-disarm-evidence.md
```

Required:

- The first scan may contain only intentionally named legacy-cleanup symbols such as `LegacyAuthenticatorMigration`, `LEGACY_AUTHENTICATOR_SEED_KEY`, and their tests; it must contain no UI, QR, verification, seed read/write API, ZXing, or TOTP authorization path.
- The second scan confirms the exact parser, authorization, delegation, and authoritative coordinator path.
- Scoped `git diff --check` exits `0`. Do not repair unrelated GPS/Settings whitespace as part of this plan.

- [ ] **Step 5: Perform no-data-clear upgrade installation on the connected Huawei**

Confirm the exact target first:

```powershell
& 'C:\Users\ASUS\AppData\Local\Android\Sdk\platform-tools\adb.exe' devices -l
```

Required: exactly one authorized target, Huawei `INE-LX2`. If more than one target appears, add `-s <exact-serial>` to every following ADB command. Redact the serial when copying evidence.

Install as an upgrade without clearing preferences:

```powershell
& 'C:\Users\ASUS\AppData\Local\Android\Sdk\platform-tools\adb.exe' install -r 'app\build\outputs\apk\debug\app-debug.apk'
& 'C:\Users\ASUS\AppData\Local\Android\Sdk\platform-tools\adb.exe' shell am force-stop com.example.motorcycleantitheftsensor
& 'C:\Users\ASUS\AppData\Local\Android\Sdk\platform-tools\adb.exe' shell monkey -p com.example.motorcycleantitheftsensor -c android.intent.category.LAUNCHER 1
```

Required: install reports `Success`; do not run `pm clear`, uninstall the app, delete encrypted preferences, or re-pair unless the acceptance flow itself proves pairing was lost unexpectedly.

- [ ] **Step 6: Execute and record real-device acceptance**

On the installed build, perform these checks in order:

1. Open Settings and confirm there is no Authenticator status, setup/replace button, QR, manual secret, OTP input, or Authenticator popup.
2. Confirm the existing Telegram bot token and paired owner still work after `adb install -r`; do not expose their values in evidence.
3. From the paired owner, send `/status`, then `/arm`, then exact `/disarm`. Required reply for the final command: `✅ ปลดการป้องกันสำเร็จ`, with protection state visibly disarmed.
4. Arm again, record the armed state, then send `/disarm 123456`. Required: unknown/help guidance, no disarm delegation, and protection remains armed.
5. From a Telegram account that is not paired, send exact `/disarm`. Required: unauthorized response and protection remains armed.
6. Trigger or use an active alert, then send exact `/disarm` from the paired owner. Required: the authoritative coordinator stops protection/alert behavior and returns the successful Thai reply.
7. Force-stop and relaunch once more without clearing data. Required: app/service initialization finishes, Telegram polling resumes, `/status` replies, and no endless loading state appears. This is the device-level guard against migration failure wedging initialization.

Do not paste bot tokens, Chat IDs, pairing codes, Telegram command arguments containing old OTP values, encrypted preferences, or secret-bearing screenshots into the evidence file.

- [ ] **Step 7: Update the evidence document with bounded claims**

In `docs/superpowers/status/2026-08-15-remove-authenticator-telegram-disarm-evidence.md`, record:

- date/time and current commit plus an explicit note that the implementation is in a mixed working tree if still uncommitted;
- Task 1 and Task 2 exact changed-file list;
- focused XML suite/test/failure/error/skipped totals;
- full XML suite/test/failure/error/skipped totals;
- build and Android-test compilation exit codes;
- APK absolute/relative path, byte count, and SHA-256;
- Huawei model and Android version with serial redacted;
- each of the seven device checks as PASS or FAIL with no secrets;
- any failure, limitation, or unexecuted gate stated plainly.

Delete or replace the old statement that no ADB device was present; do not leave contradictory evidence in the same document.

- [ ] **Step 8: Final diff review and handoff**

Run:

```powershell
git status --short
git diff -- app/src/main/java/com/example/motorcycleantitheftsensor/ui/DashboardScreen.kt app/src/main/java/com/example/motorcycleantitheftsensor/data/DeviceConfig.kt app/src/main/java/com/example/motorcycleantitheftsensor/data/LegacyAuthenticatorMigration.kt app/src/test/java/com/example/motorcycleantitheftsensor/security/AuthenticatorRemovalContractTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/ui/AuthenticatorUiRemovalContractTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/data/LegacyAuthenticatorMigrationTest.kt docs/superpowers/status/2026-08-15-remove-authenticator-telegram-disarm-evidence.md
```

Final acceptance requires all of the following:

- Paired-owner exact `/disarm` still succeeds once through `ProtectionCoordinator`.
- `/disarm 123456` and unpaired-owner `/disarm` cannot disarm.
- No Authenticator/TOTP UI, QR, secret, verification, callback, model field, or dependency remains outside the intentionally named one-shot legacy cleanup.
- Preference cleanup returns `FAILED` for non-cancellation exceptions, preserves cancellation, and cannot prevent normal service initialization.
- Focused tests, full host tests, APK assembly, and Android-test compilation are green.
- The no-data-clear Huawei acceptance passes and is recorded without secrets.
- Unrelated pairing, GPS, Settings, notification, sensor, SMS, call, incident, and protection changes remain untouched.
- Shared dirty-worktree files are not broadly staged or committed. If an isolated worktree is used, commit only reviewed task files with the two task-specific commit messages above and a final evidence-only commit:

```powershell
git add docs/superpowers/status/2026-08-15-remove-authenticator-telegram-disarm-evidence.md
git commit -m "docs: record authenticator removal acceptance"
```

## Expected Outcome

The application contains no residual user-facing or model-level Authenticator/TOTP surface, legacy encrypted-seed cleanup is idempotent and exception-safe without swallowing cancellation, and the already-working paired-owner `/disarm` flow remains unchanged. Host, build, upgrade-install, and real-device evidence support the completion claim without relying on the user's earlier manual result alone.
