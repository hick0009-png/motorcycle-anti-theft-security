# TASK-TG-001: Reconcile Telegram Bot Verification

## Objective

Make Telegram bot-token verification compile and behave correctly again while
preserving HTTPS-only transport, certificate pinning, token secrecy, secure
pairing, mandatory TOTP, and the existing SMS boundary.

## Current State and Root Cause

The TLS pin refresh is already committed in `f9d7756`, and its migration plan
is documented by `c0ca4ce`. The next migration step was left halfway through:

- `TelegramBotVerifier` and `TelegramBotVerificationResult` exist and provide
  `Verified`, `Rejected`, and `ConnectionFailure` results.
- The pending `TelegramBotClient.kt` edit replaced the existing three-value
  `verifyBotToken` callback with a single result callback.
- `AndroidProtectionSettingsGateway.kt` still calls the old callback shape:
  `telegram.verifyBotToken(token) { isValid, _, _ -> ... }`.
- The pending source-contract test expects a new
  `verifyBotTokenResult(...)` API plus an old compatibility callback, but the
  client currently exposes neither exact shape.

This API mismatch prevents Kotlin compilation and leaves the Settings flow
unable to distinguish an invalid token from a TLS/connection failure.

## Scope

### Files to modify

- `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClient.kt`
- `MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClientSourceContractTest.kt`
- `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/ui/AndroidProtectionSettingsGateway.kt`
- `MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/ui/AndroidProtectionSettingsGatewayTest.kt`

### Files to review and retain

- `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotVerifier.kt`
- `MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotVerifierTest.kt`
- `MotorcycleAntiTheftSensor/app/src/main/res/xml/network_security_config.xml`

Do not modify pairing, TOTP, command dispatch, incident delivery, SMS, QR
setup, protection state, or certificate pins in this task.

## Required Implementation

### 1. Restore a safe two-step API migration

In `TelegramBotClient`, expose the new internal result API:

```kotlin
internal fun verifyBotTokenResult(
    token: String,
    onResult: (TelegramBotVerificationResult) -> Unit,
)
```

It must normalize the token, reject blank input on the main thread, execute
`botVerifier.verify(cleanToken)` on the existing background thread, and post
the token-free result to the main thread.

Retain a compatibility callback temporarily:

```kotlin
fun verifyBotToken(
    token: String,
    onResult: (isValid: Boolean, botUsername: String?, botId: String?) -> Unit,
)
```

The compatibility method must only map `verifyBotTokenResult`; it must not
construct a request, perform a second network call, log a token, or inspect a
response body. Map `Verified` to `(true, username, botId)` and all other
results to `(false, null, null)`.

### 2. Move the Settings gateway to result-aware verification

Change `AndroidProtectionSettingsOperations.verifyBotToken` to accept:

```kotlin
onResult: (TelegramBotVerificationResult) -> Unit
```

The production operations implementation must forward directly to
`telegram.verifyBotTokenResult(token, onResult)`.

Keep the existing single-resume/cancellation protection in the suspending
adapter. `replaceBotToken` must behave as follows:

| Verification result | Stored token | Refresh polling | UI result |
| --- | --- | --- | --- |
| `Verified(username, _)` | Save normalized candidate | Yes, once | `Bot @<username> verified and token updated` |
| `Rejected` | Do not change prior token | No | `Bot token could not be verified` |
| `ConnectionFailure` | Do not change prior token | No | `Telegram connection could not be established` |

The UI must not show or log the submitted token. A failed replacement must
leave the prior verified token in encrypted storage and keep the existing
polling configuration untouched.

### 3. Remove the compatibility callback only after migration

After all production and test call sites use the result API, run:

```powershell
rg -n "verifyBotToken\(" app/src/main app/src/test
```

If no caller requires the three-value callback, remove it in the same focused
change. Update the source-contract test to assert the final result API rather
than requiring a compatibility method. Do not remove it earlier.

## Tests: RED-GREEN Sequence

1. Update/add gateway tests first:
   - valid response saves the normalized token and refreshes once;
   - authenticated rejection persists nothing and does not refresh;
   - connection failure retains an existing token and does not refresh.
2. Run the focused gateway suite and record the expected RED failure caused by
   the Boolean callback/API mismatch.
3. Add `verifyBotTokenResult` plus the compatibility mapping in the client;
   update the gateway operation interface and production adapter.
4. Update the client source-contract test so it verifies that the result API
   delegates to `botVerifier.verify(cleanToken)` and contains no raw exception,
   response-body, or token-bearing log output.
5. Run the focused suites to GREEN.
6. Only then remove the compatibility method if the final call-site search
   proves it is unused, and re-run the focused suites.

## Verification Commands

Run sequentially from `D:\security\MotorcycleAntiTheftSensor`; do not run
Gradle or ADB concurrently.

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_OPTS='-Xmx192m -XX:+UseSerialGC -XX:MaxMetaspaceSize=320m'
$env:JAVA_TOOL_OPTIONS='-Xms16m -Xmx96m -XX:+UseSerialGC -XX:MaxMetaspaceSize=256m'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*TelegramBotVerifierTest' --tests '*TelegramBotClientSourceContractTest' --tests '*AndroidProtectionSettingsGatewayTest' --tests '*TelegramPollingSessionBoundaryTest'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest assembleDebug
git diff --check
rg -n --hidden -g '!build/**' -g '!.gradle/**' '(bot[0-9]{6,}:|[0-9]{6,}:[A-Za-z0-9_-]{20,})' app docs
```

If Gradle must first download its declared wrapper distribution, record that
as an environment prerequisite. Do not report test success until a test task
actually completes with exit code 0.

## Device Acceptance (manual, after automated gate)

On the Huawei test device, install the fresh APK, revoke `SEND_SMS`, set
`SEND_SMS` app-op to `deny`, then enter a real bot token manually. Verify:

1. Settings shows the actual bot username after successful verification.
2. A failed replacement does not replace the old verified token.
3. `/pair <code>` works from the intended owner account.
4. `/status` and one non-destructive Telegram test notification are delivered.

Never record a token, token-bearing URL, pairing code, TOTP code, chat ID,
secret QR data, or a screenshot/UI dump containing any of them.

## Definition of Done

- [ ] No callback-shape compile error remains.
- [ ] Settings distinguishes rejected token from connection/TLS failure.
- [ ] Failed replacement preserves the previous verified token and does not
      refresh polling.
- [ ] Verification result callbacks reach the UI main thread.
- [ ] No cleartext fallback, unpinned client, raw exception, response body, or
      token-bearing logging is introduced.
- [ ] Focused suites, unit suite, and `assembleDebug` finish successfully.
- [ ] Fresh device acceptance proves `getMe`, pairing, polling, and outbound
      non-destructive messaging while `SEND_SMS` remains denied.
- [ ] `git diff --check` and the secret scan are clean for the changed scope.

## Handoff Notes

- Start by inspecting `git status --short` and the exact Telegram diff; this
  worktree is intentionally dirty, so never reset, clean, broadly stage, or
  overwrite unrelated changes.
- Stage only the files listed in this task after review.
- The TLS pin implementation is already in commit `f9d7756`; do not rework it
  unless new, token-free live certificate evidence proves it incorrect.
- Do not claim the Telegram outage resolved until both automated evidence and
  the manual device acceptance above are fresh.
