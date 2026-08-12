# Telegram TLS Pin Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore Telegram bot verification, polling, and outbound messages on Huawei while preserving HTTPS-only transport, certificate pinning, token secrecy, and the existing secure pairing model.

**Architecture:** Android Network Security Config remains the single certificate-pinning authority and receives a verified leaf plus intermediate-CA backup pin. A small pure Kotlin `TelegramBotVerifier` owns `getMe` request execution and maps authenticated rejection separately from TLS/transport failure. Task 2 introduces its result callback alongside the pre-existing three-value callback; Task 3 moves the settings gateway to the result callback and removes the compatibility callback in the same commit, so every task compiles independently.

**Tech Stack:** Kotlin 2.2, Android Network Security Config, OkHttp 4.12.0, `org.json`, JUnit 4, kotlinx-coroutines-test, Android Studio JBR 17, ADB.

## Global Constraints

- Keep `cleartextTrafficPermitted="false"`, system trust anchors, and certificate pinning enabled.
- Pin `api.telegram.org` with primary SPKI `AgyCmTysFOI6aQCSyQJ+QIXpnGn0v7n+D+mv6jWAtQc=` and backup intermediate SPKI `8Rw90Ej3Ttt8RRkrg+WYDS9n7IS03bk5bjP/UXPtaY8=`; remove stale SPKI `Y9mvm0exBk1JoQ557r9S1aB2cBZn6EesEARfNSc8dOf=`.
- Use pin-set expiry `2027-12-01`; re-verify the live certificate chain before implementation and device acceptance.
- Never log, display, checkpoint, persist in test fixtures, or pass through shell commands a real bot token or a token-bearing URL.
- A failed replacement must retain the previously verified token and must not refresh polling.
- Do not change `/pair <code>`, mandatory TOTP, command dispatch, QR setup, SMS fallback, or protection-state behavior.
- Keep `SEND_SMS` revoked and app-op `deny` during device verification; never send SMS or emergency calls.
- Do not run overlapping Gradle or ADB jobs.
- Before Gradle, require at least 4 GiB free on C:. If not available, stop and obtain approval for exact-path cache cleanup; never delete user files or the active Gradle 9.1.0 cache.
- Use `$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'`, `$env:GRADLE_OPTS='-Xmx192m -XX:+UseSerialGC -XX:MaxMetaspaceSize=320m'`, `$env:JAVA_TOOL_OPTIONS='-Xms16m -Xmx96m -XX:+UseSerialGC -XX:MaxMetaspaceSize=256m'`, `--no-daemon`, `--max-workers=1`, and in-process Kotlin compilation.
- Exact-stage only the files named by each task; preserve every unrelated tracked or untracked file.
- Approved sequencing correction: Task 2 adds `verifyBotTokenResult(token, onResult)` without changing the existing `verifyBotToken(token, onResult)` callback; Task 3 migrates every remaining caller and then removes the compatibility callback. This prevents the Task 2 commit from breaking the unchanged settings gateway before its own task.

## File Structure

- Modify `app/src/main/res/xml/network_security_config.xml`: authoritative Telegram domain and SPKI pin policy.
- Create `app/src/test/java/com/example/motorcycleantitheftsensor/network/TelegramNetworkSecurityConfigTest.kt`: resource-level regression test for HTTPS-only, domain, pin count, pin values, expiry, and stale-pin removal.
- Create `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotVerifier.kt`: pure verification boundary, HTTP response model, and token-free result model.
- Create `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotVerifierTest.kt`: real parser and injected transport tests with only literal fake tokens.
- Modify `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClient.kt`: delegate `getMe` verification and return the token-free result on the main thread.
- Modify `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClientSourceContractTest.kt`: retain redaction constraints after delegation.
- Modify `app/src/main/java/com/example/motorcycleantitheftsensor/ui/AndroidProtectionSettingsGateway.kt`: map verification results and gate persistence/polling refresh.
- Modify `app/src/test/java/com/example/motorcycleantitheftsensor/ui/AndroidProtectionSettingsGatewayTest.kt`: verify success, authenticated rejection, connection failure, and previous-token retention.

---

### Task 1: Replace the stale Telegram pin with a primary and backup pin

**Files:**
- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/network/TelegramNetworkSecurityConfigTest.kt`
- Modify: `app/src/main/res/xml/network_security_config.xml`

**Interfaces:**
- Consumes: Android manifest reference `android:networkSecurityConfig="@xml/network_security_config"`.
- Produces: one HTTPS-only `api.telegram.org` domain config with two SHA-256 SPKI pins and expiry `2027-12-01`.

- [ ] **Step 1: Re-verify the live public certificate chain without a bot token**

Run from a shell with network access:

```powershell
$bashPath='C:\Users\ASUS\AppData\Local\hermes\git\usr\bin\bash.exe'
& $bashPath -lc 'openssl s_client -connect api.telegram.org:443 -servername api.telegram.org </dev/null 2>/dev/null | openssl x509 -pubkey -noout | openssl pkey -pubin -outform DER | openssl dgst -sha256 -binary | openssl base64 -A'
& $bashPath -lc 'openssl s_client -showcerts -connect api.telegram.org:443 -servername api.telegram.org </dev/null 2>/dev/null | awk ''BEGIN{c=0} /BEGIN CERTIFICATE/{c++} c==2{print} /END CERTIFICATE/&&c==2{exit}'' | openssl x509 -pubkey -noout | openssl pkey -pubin -outform DER | openssl dgst -sha256 -binary | openssl base64 -A'
```

Expected: leaf `AgyCmTysFOI6aQCSyQJ+QIXpnGn0v7n+D+mv6jWAtQc=` and intermediate `8Rw90Ej3Ttt8RRkrg+WYDS9n7IS03bk5bjP/UXPtaY8=`. If either differs, stop and revise the approved spec rather than silently substituting a new trust policy.

- [ ] **Step 2: Write the failing resource regression test**

Create `TelegramNetworkSecurityConfigTest.kt`:

```kotlin
package com.example.motorcycleantitheftsensor.network

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramNetworkSecurityConfigTest {
    private val source = File("src/main/res/xml/network_security_config.xml").readText()

    @Test
    fun telegramUsesHttpsOnlyPrimaryAndBackupPins() {
        val pins = Regex("""<pin digest="SHA-256">([^<]+)</pin>""")
            .findAll(source)
            .map { it.groupValues[1] }
            .toList()

        assertTrue(source.contains("cleartextTrafficPermitted=\"false\""))
        assertTrue(source.contains("<domain includeSubdomains=\"true\">api.telegram.org</domain>"))
        assertTrue(source.contains("<pin-set expiration=\"2027-12-01\">"))
        assertEquals(
            listOf(
                "AgyCmTysFOI6aQCSyQJ+QIXpnGn0v7n+D+mv6jWAtQc=",
                "8Rw90Ej3Ttt8RRkrg+WYDS9n7IS03bk5bjP/UXPtaY8=",
            ),
            pins,
        )
        assertFalse(source.contains("Y9mvm0exBk1JoQ557r9S1aB2cBZn6EesEARfNSc8dOf="))
    }
}
```

- [ ] **Step 3: Run the test and verify RED**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_OPTS='-Xmx192m -XX:+UseSerialGC -XX:MaxMetaspaceSize=320m'
$env:JAVA_TOOL_OPTIONS='-Xms16m -Xmx96m -XX:+UseSerialGC -XX:MaxMetaspaceSize=256m'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*TelegramNetworkSecurityConfigTest'
```

Expected: FAIL because the resource has one stale pin and the old expiry.

- [ ] **Step 4: Apply the minimal pin configuration**

Replace only the Telegram `pin-set` with:

```xml
<pin-set expiration="2027-12-01">
    <pin digest="SHA-256">AgyCmTysFOI6aQCSyQJ+QIXpnGn0v7n+D+mv6jWAtQc=</pin>
    <pin digest="SHA-256">8Rw90Ej3Ttt8RRkrg+WYDS9n7IS03bk5bjP/UXPtaY8=</pin>
</pin-set>
```

- [ ] **Step 5: Run the focused test and verify GREEN**

Run the Step 3 command again. Expected: `TelegramNetworkSecurityConfigTest` PASS with zero failures.

- [ ] **Step 6: Inspect and commit only Task 1**

```powershell
git diff --check -- app/src/main/res/xml/network_security_config.xml app/src/test/java/com/example/motorcycleantitheftsensor/network/TelegramNetworkSecurityConfigTest.kt
git add -- app/src/main/res/xml/network_security_config.xml app/src/test/java/com/example/motorcycleantitheftsensor/network/TelegramNetworkSecurityConfigTest.kt
git commit -m "fix(network): refresh Telegram TLS pins"
```

---

### Task 2: Introduce a token-free Telegram verification boundary

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotVerifier.kt`
- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotVerifierTest.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClient.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClientSourceContractTest.kt`

**Interfaces:**
- Consumes: `normalizeTelegramBotToken(String): String` and `TlsPinningClient.client`.
- Produces: `TelegramBotVerificationResult`, `TelegramVerificationHttpResponse`, `TelegramBotVerifier.verify(String): TelegramBotVerificationResult`, and `TelegramBotClient.verifyBotTokenResult(String, (TelegramBotVerificationResult) -> Unit)`.

- [ ] **Step 1: Write failing verifier tests**

Create `TelegramBotVerifierTest.kt`:

```kotlin
package com.example.motorcycleantitheftsensor.telegram

import java.io.IOException
import javax.net.ssl.SSLPeerUnverifiedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramBotVerifierTest {
    @Test
    fun validTelegramResponseReturnsVerifiedIdentity() {
        val verifier = TelegramBotVerifier {
            TelegramVerificationHttpResponse(
                code = 200,
                body = """{"ok":true,"result":{"id":42,"username":"guard_bot"}}""",
            )
        }

        assertEquals(
            TelegramBotVerificationResult.Verified("guard_bot", "42"),
            verifier.verify("123456:TEST_ONLY"),
        )
    }

    @Test
    fun authenticatedTelegramRejectionIsNotReportedAsTransportFailure() {
        val verifier = TelegramBotVerifier {
            TelegramVerificationHttpResponse(401, """{"ok":false,"error_code":401}""")
        }

        assertEquals(
            TelegramBotVerificationResult.Rejected,
            verifier.verify("123456:TEST_ONLY"),
        )
    }

    @Test
    fun tlsFailureReturnsTokenFreeConnectionFailure() {
        val verifier = TelegramBotVerifier {
            throw SSLPeerUnverifiedException("pin mismatch for test host")
        }

        val result = verifier.verify("123456:TEST_ONLY")

        assertEquals(TelegramBotVerificationResult.ConnectionFailure, result)
        assertTrue(result.toString().contains("123456:TEST_ONLY").not())
    }

    @Test
    fun ioFailureReturnsConnectionFailure() {
        val verifier = TelegramBotVerifier { throw IOException("offline") }

        assertEquals(
            TelegramBotVerificationResult.ConnectionFailure,
            verifier.verify("123456:TEST_ONLY"),
        )
    }
}
```

- [ ] **Step 2: Run the verifier test and verify RED**

Run the low-memory profile from Task 1 with:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*TelegramBotVerifierTest'
```

Expected: compilation FAIL because `TelegramBotVerifier` and its result types do not exist.

- [ ] **Step 3: Implement the minimal pure verifier**

Create `TelegramBotVerifier.kt`:

```kotlin
package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.network.TlsPinningClient
import java.io.IOException
import okhttp3.Request
import org.json.JSONObject

internal sealed interface TelegramBotVerificationResult {
    data class Verified(val username: String, val botId: String) : TelegramBotVerificationResult
    data object Rejected : TelegramBotVerificationResult
    data object ConnectionFailure : TelegramBotVerificationResult
}

internal data class TelegramVerificationHttpResponse(
    val code: Int,
    val body: String?,
)

internal class TelegramBotVerifier(
    private val execute: (Request) -> TelegramVerificationHttpResponse = { request ->
        TlsPinningClient.client.newCall(request).execute().use { response ->
            TelegramVerificationHttpResponse(response.code, response.body?.string())
        }
    },
) {
    fun verify(rawToken: String): TelegramBotVerificationResult {
        val token = normalizeTelegramBotToken(rawToken)
        if (token.isBlank()) return TelegramBotVerificationResult.Rejected
        val request = try {
            Request.Builder()
                .url("https://api.telegram.org/bot$token/getMe")
                .build()
        } catch (_: IllegalArgumentException) {
            return TelegramBotVerificationResult.Rejected
        }
        val response = try {
            execute(request)
        } catch (_: IOException) {
            return TelegramBotVerificationResult.ConnectionFailure
        }
        if (response.code !in 200..299) return TelegramBotVerificationResult.Rejected
        val json = try {
            JSONObject(response.body ?: return TelegramBotVerificationResult.Rejected)
        } catch (_: RuntimeException) {
            return TelegramBotVerificationResult.Rejected
        }
        if (!json.optBoolean("ok", false)) return TelegramBotVerificationResult.Rejected
        val result = json.optJSONObject("result") ?: return TelegramBotVerificationResult.Rejected
        val username = result.optString("username").trim()
        val botId = result.optLong("id", 0L)
        if (username.isBlank() || botId <= 0L) return TelegramBotVerificationResult.Rejected
        return TelegramBotVerificationResult.Verified(username, botId.toString())
    }
}
```

- [ ] **Step 4: Add a result callback while retaining the compatibility callback**

Add `private val botVerifier = TelegramBotVerifier()` beside the existing client fields. Add this result callback:

```kotlin
internal fun verifyBotTokenResult(
    token: String,
    onResult: (TelegramBotVerificationResult) -> Unit,
) {
    val cleanToken = normalizeTelegramBotToken(token)
    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    if (cleanToken.isBlank()) {
        mainHandler.post { onResult(TelegramBotVerificationResult.Rejected) }
        return
    }
    thread {
        val result = botVerifier.verify(cleanToken)
        mainHandler.post { onResult(result) }
    }
}
```

Retain the existing public three-value callback until Task 3, and implement it only as a mapping over the result callback:

```kotlin
fun verifyBotToken(
    token: String,
    onResult: (isValid: Boolean, botUsername: String?, botId: String?) -> Unit,
) = verifyBotTokenResult(token) { result ->
    when (result) {
        is TelegramBotVerificationResult.Verified -> onResult(true, result.username, result.botId)
        TelegramBotVerificationResult.Rejected,
        TelegramBotVerificationResult.ConnectionFailure,
        -> onResult(false, null, null)
    }
}
```

Do not log `Throwable`, request URLs, response bodies, or token values.

- [ ] **Step 5: Strengthen the existing source redaction contract**

In `TelegramBotClientSourceContractTest.kt`, replace the old callback-shape-specific verification test with assertions that the `verifyBotTokenResult` section delegates to `botVerifier.verify(cleanToken)` and does not contain `printStackTrace`, `response.body`, `Log.`, or token interpolation in any log statement. Also assert that the compatibility `verifyBotToken` body delegates to `verifyBotTokenResult(token)` and contains no network request construction.

- [ ] **Step 6: Run focused Telegram tests and verify GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*TelegramBotVerifierTest' --tests '*TelegramBotClientSourceContractTest'
```

Expected: all focused tests PASS with zero failures.

- [ ] **Step 7: Inspect and commit only Task 2**

```powershell
git diff --check -- app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotVerifier.kt app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClient.kt app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotVerifierTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClientSourceContractTest.kt
git add -- app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotVerifier.kt app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClient.kt app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotVerifierTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClientSourceContractTest.kt
git commit -m "fix(telegram): classify bot verification failures"
```

---

### Task 3: Persist and activate only a verified replacement token

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/AndroidProtectionSettingsGateway.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/ui/AndroidProtectionSettingsGatewayTest.kt`

**Interfaces:**
- Consumes: `TelegramBotVerificationResult` and `TelegramBotClient.verifyBotTokenResult` from Task 2.
- Produces: `replaceBotToken(String): SettingsOperationResult` with distinct rejected/connection messages and no state mutation on failure.

- [ ] **Step 1: Extend the fake operations and write failing gateway tests**

Change `AndroidProtectionSettingsOperations.verifyBotToken` and its fake implementation to use `(TelegramBotVerificationResult) -> Unit`. Give the fake constructor these fields:

```kotlin
private val botVerificationResult: TelegramBotVerificationResult =
    TelegramBotVerificationResult.Verified("guard_bot", "42"),
initialBotToken: String? = null,
```

Import `com.example.motorcycleantitheftsensor.telegram.TelegramBotVerificationResult` in both gateway files.

Initialize `savedToken = initialBotToken`. Add:

```kotlin
@Test
fun connectionFailureRetainsPreviousTokenAndDoesNotRefreshPolling() = runTest {
    val operations = FakeAndroidProtectionSettingsOperations(
        botVerificationResult = TelegramBotVerificationResult.ConnectionFailure,
        initialBotToken = "OLD_VERIFIED_TOKEN",
    )
    val gateway = AndroidProtectionSettingsGateway(operations, PairingCodePolicy())

    val result = gateway.replaceBotToken("NEW_TEST_TOKEN")

    assertFalse(result.applied)
    assertEquals("Telegram connection could not be established", result.message)
    assertEquals("OLD_VERIFIED_TOKEN", operations.savedToken)
    assertEquals(listOf("verify:NEW_TEST_TOKEN"), operations.events)
}

@Test
fun authenticatedRejectionDoesNotPersistOrRefresh() = runTest {
    val operations = FakeAndroidProtectionSettingsOperations(
        botVerificationResult = TelegramBotVerificationResult.Rejected,
    )
    val gateway = AndroidProtectionSettingsGateway(operations, PairingCodePolicy())

    val result = gateway.replaceBotToken("REJECTED_TEST_TOKEN")

    assertFalse(result.applied)
    assertEquals("Bot token could not be verified", result.message)
    assertEquals(null, operations.savedToken)
    assertEquals(listOf("verify:REJECTED_TEST_TOKEN"), operations.events)
}
```

Update the existing normalized-token success assertion to expect `Bot @guard_bot verified and token updated`.

- [ ] **Step 2: Run the gateway tests and verify RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*AndroidProtectionSettingsGatewayTest'
```

Expected: compilation or assertion FAIL because operations still return Boolean and connection failures are not distinguished.

- [ ] **Step 3: Implement result-aware token replacement**

Change `AndroidProtectionSettingsOperations.verifyBotToken` to:

```kotlin
fun verifyBotToken(
    token: String,
    onResult: (TelegramBotVerificationResult) -> Unit,
)
```

Change the suspending adapter to return `TelegramBotVerificationResult`, preserving its existing atomic single-resume cancellation guard. Implement `replaceBotToken` as:

```kotlin
override suspend fun replaceBotToken(token: String): SettingsOperationResult {
    val candidate = normalizeTelegramBotToken(token)
    if (candidate.isBlank()) {
        return SettingsOperationResult(applied = false, message = "Bot token is required")
    }
    return when (val verification = verifyBotToken(candidate)) {
        is TelegramBotVerificationResult.Verified -> {
            operations.saveBotToken(candidate)
            operations.refreshControlService()
            SettingsOperationResult(
                applied = true,
                message = "Bot @${verification.username} verified and token updated",
            )
        }
        TelegramBotVerificationResult.Rejected -> SettingsOperationResult(
            applied = false,
            message = "Bot token could not be verified",
        )
        TelegramBotVerificationResult.ConnectionFailure -> SettingsOperationResult(
            applied = false,
            message = "Telegram connection could not be established",
        )
    }
}
```

In `EncryptedAndroidProtectionSettingsOperations`, forward the result callback directly to the new API:

```kotlin
override fun verifyBotToken(
    token: String,
    onResult: (TelegramBotVerificationResult) -> Unit,
) = telegram.verifyBotTokenResult(token, onResult)
```

After this migration, remove the compatibility `TelegramBotClient.verifyBotToken(token, (Boolean, String?, String?) -> Unit)` method that Task 2 retained temporarily. Search `app/src/main` and `app/src/test` for `verifyBotToken(` before removing it; every remaining production caller must use `verifyBotTokenResult` or the gateway operation result callback.

- [ ] **Step 4: Run gateway and Telegram focused suites and verify GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*AndroidProtectionSettingsGatewayTest' --tests '*TelegramBotVerifierTest' --tests '*TelegramBotClientSourceContractTest' --tests '*TelegramPollingSessionBoundaryTest'
```

Expected: all focused tests PASS with zero failures.

- [ ] **Step 5: Inspect and commit only Task 3**

```powershell
git diff --check -- app/src/main/java/com/example/motorcycleantitheftsensor/ui/AndroidProtectionSettingsGateway.kt app/src/test/java/com/example/motorcycleantitheftsensor/ui/AndroidProtectionSettingsGatewayTest.kt
git add -- app/src/main/java/com/example/motorcycleantitheftsensor/ui/AndroidProtectionSettingsGateway.kt app/src/test/java/com/example/motorcycleantitheftsensor/ui/AndroidProtectionSettingsGatewayTest.kt
git commit -m "fix(settings): preserve token on Telegram connection failure"
```

---

### Task 4: Run the full gate and Huawei Telegram acceptance

**Files:**
- Verify only; no planned source changes.

**Interfaces:**
- Consumes: Tasks 1-3 commits and Huawei `JUCDU18811013149`.
- Produces: fresh test counts, APK SHA-256, installed package evidence, `SEND_SMS: deny`, verified bot identity, successful `/pair <code>`, polling reply, and outbound-message evidence.

- [ ] **Step 1: Verify host and device gates**

```powershell
Get-PSDrive -Name C | Select-Object Name,Free,Used
$adbPath='C:\Users\ASUS\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adbPath devices -l
Get-Process java,adb -ErrorAction SilentlyContinue | Select-Object Id,ProcessName,Path
```

Expected: at least 4 GiB free on C:, exactly one authorized Huawei `JUCDU18811013149`, and no overlapping Gradle Java process. Stop if any condition fails.

- [ ] **Step 2: Run the complete automated gate**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_OPTS='-Xmx192m -XX:+UseSerialGC -XX:MaxMetaspaceSize=320m'
$env:JAVA_TOOL_OPTIONS='-Xms16m -Xmx96m -XX:+UseSerialGC -XX:MaxMetaspaceSize=256m'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' clean testDebugUnitTest connectedDebugAndroidTest assembleDebug
```

Expected: Gradle exits 0; record exact unit and connected-test counts rather than relying only on `BUILD SUCCESSFUL`.

- [ ] **Step 3: Hash and install the fresh APK, then deny SMS**

```powershell
$apkPath='D:\security\MotorcycleAntiTheftSensor\app\build\outputs\apk\debug\app-debug.apk'
Get-FileHash -Algorithm SHA256 -LiteralPath $apkPath
& $adbPath install -r $apkPath
& $adbPath shell pm revoke com.example.motorcycleantitheftsensor android.permission.SEND_SMS
& $adbPath shell appops set com.example.motorcycleantitheftsensor SEND_SMS deny
& $adbPath shell dumpsys package com.example.motorcycleantitheftsensor | Select-String -Pattern 'versionName=|versionCode=|lastUpdateTime=|android.permission.SEND_SMS'
& $adbPath shell appops get com.example.motorcycleantitheftsensor SEND_SMS
```

Expected: install `Success`, fresh `lastUpdateTime`, runtime permission not granted, and `SEND_SMS: deny`.

- [ ] **Step 4: Verify bot identity without exposing the token**

Manually enter the token on Huawei and select `Save bot token`. Expected: UI reports the actual Telegram username in `Bot @name verified and token updated`; the token field clears; no token appears in logs. Capture only redacted tags if needed:

```powershell
& $adbPath logcat -v brief -T 1 TelegramBotClient:W TelegramTransport:W AndroidRuntime:E '*:S'
```

- [ ] **Step 5: Verify secure pairing and bidirectional Telegram traffic**

Generate the device pairing code, send `/pair <code>` from the intended owner Telegram account, and confirm the owner-paired reply. Then send `/status` and confirm a current protection-status reply. Trigger only the app's non-destructive Telegram test notification and confirm receipt. Do not record the token, pairing code, TOTP, chat ID, or token-bearing request URL.

- [ ] **Step 6: Re-verify SMS denial and inspect the exact branch diff**

```powershell
& $adbPath shell appops get com.example.motorcycleantitheftsensor SEND_SMS
git status --short
git diff --check HEAD~3..HEAD
git log -4 --oneline
```

Expected: `SEND_SMS: deny`, no whitespace errors in the three implementation commits, and no unrelated file included.

- [ ] **Step 7: Run final review before completion**

Review the complete design-to-HEAD diff for Critical/Important security, correctness, and reliability findings. If a load-bearing finding exists, write one failing regression test, apply one minimal fix wave, rerun the focused and full gates, reinstall, and repeat the non-secret Telegram acceptance. Do not claim the Telegram issue fixed until all automated and device evidence is fresh.
