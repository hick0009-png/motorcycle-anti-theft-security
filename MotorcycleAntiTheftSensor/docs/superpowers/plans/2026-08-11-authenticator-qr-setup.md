# Authenticator QR Setup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Display a locally generated, explicitly revealed TOTP QR code that an authenticator app on a second phone can scan and verify.

**Architecture:** A pure Kotlin `AuthenticatorQrCodeEncoder` converts the existing transient `otpauth://` URI into ARGB pixels with ZXing Core. `SettingsScreen` requests that work on `Dispatchers.Default`, converts the pixels to an in-memory Compose image, and owns all visibility, loading, error, and cleanup state inside the existing `FLAG_SECURE` setup dialog.

**Tech Stack:** Kotlin 2.3.20, Jetpack Compose BOM 2026.03.01, coroutines 1.10.2, ZXing Core 3.5.4, JUnit 4, Android Compose UI tests

## Global Constraints

- Add only `com.google.zxing:core:3.5.4`; the project release and Maven Central both identify 3.5.4 as the current release.
- Generate QR data entirely on device; never use a remote QR service.
- Use the existing `AuthenticatorSetupDetails.uri` as the only QR payload source.
- Never persist, log, copy, screenshot, or expose the URI, secret, or QR pixels through accessibility semantics.
- Keep the existing `FLAG_SECURE` behavior for the full setup-dialog lifetime.
- Do not put setup details or QR state in `ProtectionUiState` or saved instance state.
- Do not change TOTP verification, Telegram authorization, pairing, protection state, SMS configuration, or service ownership.
- QR generation and bitmap allocation must run off the main thread.
- Keep the manual `Reveal secret` flow as an independent fallback.
- Keep `SEND_SMS: deny` throughout real-device testing.

---

### Task 1: Local QR Encoder

**Files:**
- Modify: `app/build.gradle.kts:46-91`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/AuthenticatorQrCodeEncoder.kt`
- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/ui/settings/AuthenticatorQrCodeEncoderTest.kt`

**Interfaces:**
- Consumes: a complete `otpauth://totp/` URI and requested square pixel size.
- Produces: `AuthenticatorQrCodeEncoder.encode(contents: String, sizePx: Int = 512): Result<QrCodePixels>` where `QrCodePixels` exposes `sizePx: Int` and `argb: IntArray`.

- [ ] **Step 1: Add the pinned test/runtime dependency and write the failing behavioral tests**

Add inside `dependencies` in `app/build.gradle.kts`:

```kotlin
implementation("com.google.zxing:core:3.5.4")
```

Create `AuthenticatorQrCodeEncoderTest.kt`:

```kotlin
package com.example.motorcycleantitheftsensor.ui.settings

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticatorQrCodeEncoderTest {
    @Test
    fun encodedQrDecodesToTheExactAuthenticatorUri() {
        val uri = "otpauth://totp/MotorcycleGuard:VehicleOwner" +
            "?secret=JBSWY3DPEHPK3PXP&issuer=MotorcycleGuard" +
            "&algorithm=SHA1&digits=6&period=30"

        val qr = AuthenticatorQrCodeEncoder.encode(uri, sizePx = 256).getOrThrow()
        val source = RGBLuminanceSource(qr.sizePx, qr.sizePx, qr.argb)
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text

        assertEquals(uri, decoded)
    }

    @Test
    fun nonTotpPayloadReturnsControlledFailure() {
        val result = AuthenticatorQrCodeEncoder.encode("https://example.invalid/secret")

        assertTrue(result.isFailure)
    }

    @Test
    fun undersizedQrReturnsControlledFailure() {
        val result = AuthenticatorQrCodeEncoder.encode(
            "otpauth://totp/MotorcycleGuard:VehicleOwner?secret=JBSWY3DPEHPK3PXP",
            sizePx = 64,
        )

        assertTrue(result.isFailure)
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_OPTS='-Xmx192m -XX:+UseSerialGC -XX:MaxMetaspaceSize=320m'
$env:JAVA_TOOL_OPTIONS='-Xms16m -Xmx96m -XX:+UseSerialGC -XX:MaxMetaspaceSize=256m'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests '*AuthenticatorQrCodeEncoderTest'
```

Expected: FAIL during test compilation because `AuthenticatorQrCodeEncoder` and `QrCodePixels` do not exist.

- [ ] **Step 3: Implement the minimal pure Kotlin encoder**

Create `AuthenticatorQrCodeEncoder.kt`:

```kotlin
package com.example.motorcycleantitheftsensor.ui.settings

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

internal class QrCodePixels(
    val sizePx: Int,
    val argb: IntArray,
)

internal object AuthenticatorQrCodeEncoder {
    private const val MINIMUM_SIZE_PX = 128
    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    fun encode(contents: String, sizePx: Int = 512): Result<QrCodePixels> = runCatching {
        require(contents.startsWith("otpauth://totp/"))
        require(sizePx >= MINIMUM_SIZE_PX)
        val matrix = QRCodeWriter().encode(
            contents,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 4,
            ),
        )
        val argb = IntArray(sizePx * sizePx) { index ->
            val x = index % sizePx
            val y = index / sizePx
            if (matrix[x, y]) BLACK else WHITE
        }
        QrCodePixels(sizePx, argb)
    }
}
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the command from Step 2 again.

Expected: 3 tests pass; the exact literal URI survives a real ZXing encode/decode round trip.

- [ ] **Step 5: Inspect dependency scope and commit Task 1**

Run:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :app:dependencies --configuration debugRuntimeClasspath
git diff --check -- app/build.gradle.kts app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/AuthenticatorQrCodeEncoder.kt app/src/test/java/com/example/motorcycleantitheftsensor/ui/settings/AuthenticatorQrCodeEncoderTest.kt
git add -- app/build.gradle.kts app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/AuthenticatorQrCodeEncoder.kt app/src/test/java/com/example/motorcycleantitheftsensor/ui/settings/AuthenticatorQrCodeEncoderTest.kt
git commit -m "feat(settings): add local authenticator QR encoder"
```

Expected: debug runtime includes `com.google.zxing:core:3.5.4`; only the three named files enter the commit.

---

### Task 2: Secure Explicit-Reveal QR UI

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt:65-445`
- Modify: `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt:465-515`

**Interfaces:**
- Consumes: `AuthenticatorQrCodeEncoder.encode(contents: String, sizePx: Int = 512)` from Task 1 and the existing transient `AuthenticatorSetupDetails.uri`.
- Produces: a `Show QR code` / `Hide QR code` interaction with image tag `authenticator_qr_code` and non-secret content description `Authenticator setup QR code`.

- [ ] **Step 1: Write the failing explicit-reveal and cleanup UI test**

In `ProtectionAppScreenTest.kt`, add a test that uses a literal test secret and valid URI:

```kotlin
@Test
fun authenticatorQrRequiresExplicitActionAndClearsOnCancel() {
    val secret = "JBSWY3DPEHPK3PXP"
    val uri = "otpauth://totp/MotorcycleGuard:VehicleOwner" +
        "?secret=$secret&issuer=MotorcycleGuard&algorithm=SHA1&digits=6&period=30"
    var setupCompletion: ((AuthenticatorSetupDetails?) -> Unit)? = null
    val actions = fakeActions().copy(
        beginAuthenticatorSetup = { onComplete ->
            setupCompletion = onComplete
            {}
        },
    )
    compose.setContent {
        ProtectionAppScreen(
            baseState(ProtectionState.DISARMED_ONLINE).copy(
                destination = ProtectionDestination.SETTINGS,
            ),
            actions,
        )
    }

    compose.onNode(hasScrollAction()).performScrollToNode(hasText("Set up authenticator"))
    compose.onNodeWithText("Set up authenticator").performClick()
    compose.runOnIdle {
        requireNotNull(setupCompletion)(AuthenticatorSetupDetails(secret, uri))
    }

    compose.onNodeWithTag("authenticator_qr_code").assertDoesNotExist()
    compose.onNodeWithText("Show QR code").performClick()
    compose.onNodeWithTag("authenticator_qr_code").assertIsDisplayed()
    compose.onNodeWithContentDescription("Authenticator setup QR code").assertExists()
    compose.onAllNodes(hasText(uri, substring = true)).assertCountEquals(0)
    compose.onAllNodes(hasText(secret, substring = true)).assertCountEquals(0)
    compose.runOnIdle {
        assertTrue(
            compose.activity.window.attributes.flags and
                WindowManager.LayoutParams.FLAG_SECURE != 0,
        )
    }

    compose.onNodeWithText("Hide QR code").performClick()
    compose.onNodeWithTag("authenticator_qr_code").assertDoesNotExist()
    compose.onNodeWithText("Show QR code").performClick()
    compose.onNodeWithTag("authenticator_qr_code").assertIsDisplayed()
    compose.onNodeWithText("Cancel").performClick()
    compose.onNodeWithTag("authenticator_qr_code").assertDoesNotExist()
}
```

- [ ] **Step 2: Write the failing controlled-error UI test**

Add a candidate with an invalid URI to exercise the real encoder failure:

```kotlin
@Test
fun authenticatorQrFailureKeepsManualSecretFallback() {
    val secret = "TEST-ONLY-SECRET"
    var setupCompletion: ((AuthenticatorSetupDetails?) -> Unit)? = null
    val actions = fakeActions().copy(
        beginAuthenticatorSetup = { onComplete ->
            setupCompletion = onComplete
            {}
        },
    )
    compose.setContent {
        ProtectionAppScreen(
            baseState(ProtectionState.DISARMED_ONLINE).copy(
                destination = ProtectionDestination.SETTINGS,
            ),
            actions,
        )
    }

    compose.onNode(hasScrollAction()).performScrollToNode(hasText("Set up authenticator"))
    compose.onNodeWithText("Set up authenticator").performClick()
    compose.runOnIdle {
        requireNotNull(setupCompletion)(AuthenticatorSetupDetails(secret, uri = "invalid"))
    }
    compose.onNodeWithText("Show QR code").performClick()

    compose.onNodeWithText("Unable to create QR code. Use the secret instead.").assertExists()
    compose.onNodeWithText("Reveal secret").assertExists()
    compose.onNodeWithTag("authenticator_qr_code").assertDoesNotExist()
    compose.onAllNodes(hasText("invalid", substring = true)).assertCountEquals(0)
}
```

- [ ] **Step 3: Run both new tests and verify RED**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_OPTS='-Xmx192m -XX:+UseSerialGC -XX:MaxMetaspaceSize=320m'
$env:JAVA_TOOL_OPTIONS='-Xms16m -Xmx96m -XX:+UseSerialGC -XX:MaxMetaspaceSize=256m'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.example.motorcycleantitheftsensor.ui.ProtectionAppScreenTest#authenticatorQrRequiresExplicitActionAndClearsOnCancel,com.example.motorcycleantitheftsensor.ui.ProtectionAppScreenTest#authenticatorQrFailureKeepsManualSecretFallback'
```

Expected: FAIL because `Show QR code` and `authenticator_qr_code` do not exist.

- [ ] **Step 4: Add transient QR state and off-main generation**

In `SettingsScreen.kt`, add non-saveable state beside the existing authenticator state:

```kotlin
var authenticatorQrRequested by remember { mutableStateOf(false) }
var authenticatorQrLoading by remember { mutableStateOf(false) }
var authenticatorQrImage by remember { mutableStateOf<ImageBitmap?>(null) }
var authenticatorQrError by remember { mutableStateOf<String?>(null) }
```

Extend `clearAuthenticatorUi()` and the setup-completion callback to reset all four values. Add this effect before rendering the dialog:

```kotlin
LaunchedEffect(authenticatorSetup?.uri, authenticatorQrRequested) {
    val setup = authenticatorSetup
    if (setup == null || !authenticatorQrRequested) {
        authenticatorQrLoading = false
        authenticatorQrImage = null
        authenticatorQrError = null
        return@LaunchedEffect
    }
    authenticatorQrLoading = true
    authenticatorQrImage = null
    authenticatorQrError = null
    val encoded = withContext(Dispatchers.Default) {
        AuthenticatorQrCodeEncoder.encode(setup.uri).map { qr ->
            Bitmap.createBitmap(
                qr.argb,
                qr.sizePx,
                qr.sizePx,
                Bitmap.Config.ARGB_8888,
            ).asImageBitmap()
        }
    }
    if (authenticatorSetup?.uri == setup.uri && authenticatorQrRequested) {
        authenticatorQrLoading = false
        encoded.fold(
            onSuccess = { authenticatorQrImage = it },
            onFailure = {
                authenticatorQrError = "Unable to create QR code. Use the secret instead."
            },
        )
    }
}
```

Required imports are `android.graphics.Bitmap`, Compose `Image`, `ImageBitmap`, `asImageBitmap`, `size`, `verticalScroll`, `rememberScrollState`, `CircularProgressIndicator`, and coroutine `Dispatchers` / `withContext`. Do not log the exception or include it in the UI message.

- [ ] **Step 5: Render the explicit show/hide controls and accessible image**

Make the dialog text column vertically scrollable, then place this block after `Reveal secret` and before the verification field:

```kotlin
TextButton(
    onClick = {
        if (authenticatorQrRequested) {
            authenticatorQrRequested = false
        } else {
            authenticatorQrRequested = true
        }
    },
    modifier = Modifier.heightIn(min = 48.dp),
) {
    Text(if (authenticatorQrRequested) "Hide QR code" else "Show QR code")
}
if (authenticatorQrLoading) {
    CircularProgressIndicator(
        modifier = Modifier.semantics {
            contentDescription = "Creating authenticator QR code"
        },
    )
}
authenticatorQrImage?.let { image ->
    Image(
        bitmap = image,
        contentDescription = "Authenticator setup QR code",
        modifier = Modifier
            .size(220.dp)
            .align(Alignment.CenterHorizontally)
            .testTag(AUTHENTICATOR_QR_TAG),
    )
}
authenticatorQrError?.let { Text(it) }
```

Declare `private const val AUTHENTICATOR_QR_TAG = "authenticator_qr_code"`. Apply `verticalScroll(rememberScrollState())` to the dialog's existing `Column`; do not make the URI or secret part of content descriptions or test tags.

- [ ] **Step 6: Run the focused UI tests and verify GREEN**

Run the command from Step 3 again.

Expected: both tests pass on Huawei; the real valid QR appears only after the button, the invalid candidate yields the fixed fallback message, and no secret-bearing semantics are found.

- [ ] **Step 7: Run the existing authenticator regression test**

Run:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.example.motorcycleantitheftsensor.ui.ProtectionAppScreenTest#authenticatorSecretAppearsOnlyAfterAsyncCompletionAndClearsOnCancel'
```

Expected: PASS; manual reveal/cancel and `FLAG_SECURE` behavior remain intact.

- [ ] **Step 8: Check the scoped diff and commit Task 2**

Run:

```powershell
git diff --check -- app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt
git add -- app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt
git commit -m "feat(settings): show secure authenticator QR"
```

Expected: only the two named files enter the commit.

---

### Task 3: Full Gate and Huawei Scan Acceptance

**Files:**
- Verify only; no source files are modified.

**Interfaces:**
- Consumes: the completed encoder and secure UI from Tasks 1-2.
- Produces: fresh host, connected-device, APK, SMS-denial, and live cross-device scan evidence.

- [ ] **Step 1: Run the clean automated gate**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_OPTS='-Xmx192m -XX:+UseSerialGC -XX:MaxMetaspaceSize=320m'
$env:JAVA_TOOL_OPTIONS='-Xms16m -Xmx96m -XX:+UseSerialGC -XX:MaxMetaspaceSize=256m'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' clean testDebugUnitTest connectedDebugAndroidTest assembleDebug
```

Expected: exit 0; all unit and connected UI tests have zero failures, errors, and skips; `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 2: Record exact test counts and APK digest**

Run:

```powershell
$unitFiles = Get-ChildItem -Recurse app\build\test-results\testDebugUnitTest\TEST-*.xml
$unitTests = 0; $unitFailures = 0; $unitErrors = 0; $unitSkipped = 0
foreach ($file in $unitFiles) {
    [xml]$xml = Get-Content $file.FullName
    $unitTests += [int]$xml.testsuite.tests
    $unitFailures += [int]$xml.testsuite.failures
    $unitErrors += [int]$xml.testsuite.errors
    $unitSkipped += [int]$xml.testsuite.skipped
}
"UNIT suites=$($unitFiles.Count) tests=$unitTests failures=$unitFailures errors=$unitErrors skipped=$unitSkipped"
Get-ChildItem app\build\outputs\androidTest-results\connected\debug\TEST-*.xml | ForEach-Object {
    [xml]$xml = Get-Content $_.FullName
    "DEVICE tests=$($xml.testsuite.tests) failures=$($xml.testsuite.failures) errors=$($xml.testsuite.errors) skipped=$($xml.testsuite.skipped)"
}
Get-FileHash app\build\outputs\apk\debug\app-debug.apk -Algorithm SHA256
```

Expected: every failure/error/skip count is 0 and an exact SHA-256 is printed.

- [ ] **Step 3: Install on Huawei and re-assert the SMS safety boundary**

Run:

```powershell
$adb='C:\Users\ASUS\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb -s JUCDU18811013149 install -r 'D:\security\MotorcycleAntiTheftSensor\app\build\outputs\apk\debug\app-debug.apk'
& $adb -s JUCDU18811013149 shell pm revoke com.example.motorcycleantitheftsensor android.permission.SEND_SMS
& $adb -s JUCDU18811013149 shell appops set com.example.motorcycleantitheftsensor SEND_SMS deny
& $adb -s JUCDU18811013149 shell appops get com.example.motorcycleantitheftsensor SEND_SMS
& $adb -s JUCDU18811013149 shell am force-stop com.example.motorcycleantitheftsensor
& $adb -s JUCDU18811013149 shell am start -W -n com.example.motorcycleantitheftsensor/.MainActivity
```

Expected: install succeeds, `SEND_SMS: deny` is printed, and `MainActivity` starts. Do not capture the screen while the authenticator dialog is open.

- [ ] **Step 4: Perform the live two-phone acceptance**

On Huawei:

1. Open Settings and select `Set up authenticator` or `Replace authenticator`.
2. Confirm the QR is absent until `Show QR code` is selected.
3. Select `Show QR code` and confirm the complete black-on-white QR is visible without clipping.

On the second phone:

1. Open a TOTP-compatible authenticator app.
2. Choose its scan-QR action and scan the Huawei display.
3. Confirm the account label is `MotorcycleGuard:VehicleOwner` and a six-digit code appears.

Back on Huawei:

1. Enter the current six-digit code and select `Verify`.
2. Confirm the dialog closes and Settings reports `Authenticator configured`.
3. Reopen replacement setup and cancel it; confirm the existing configured authenticator remains authoritative.

Expected: one scan succeeds, one current six-digit code verifies, and cancellation of a later replacement does not replace the verified seed.

- [ ] **Step 5: Final safety and repository check**

Run:

```powershell
& $adb -s JUCDU18811013149 shell appops get com.example.motorcycleantitheftsensor SEND_SMS
git status --short
git log -3 --oneline
```

Expected: `SEND_SMS: deny`; the two feature commits are present; no tracked implementation changes remain uncommitted. Preserve all unrelated untracked workspace files.
