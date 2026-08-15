# TASK-RC-002: Restore Telegram `/status` Replies and Verify Sensor Runtime

## Objective

Restore reliable, secret-safe replies to Telegram `/status`, determine why the
installed app still has no visible sensor telemetry, and prove the repaired
flow on the real device without enabling SMS or weakening TLS pinning.

## Confirmed Root Cause: `/status` Reply Is Rejected by Telegram Markdown

`TelegramBotClient.sendTelegramMessageSync(...)` currently serializes every
outbound message with:

```kotlin
json.put("parse_mode", "Markdown")
```

`ProtectionStatusFormatter.format(...)` always emits enum names and detector
diagnostics that contain underscores, for example:

```text
POWER_THERMAL
accelerometer_magnitude_sensitivity_5
```

Those strings are not escaped for Telegram legacy Markdown. Telegram can
therefore reject `sendMessage` with a `400 Bad Request` parsing error even
though polling and `/pair <code>` work. This matches the observed symptom:
pairing succeeds but `/status` produces no reply.

The same changed client also logs a token prefix, inbound `getUpdates` body,
and outbound response body. Those can contain a bot token, chat IDs, pairing
codes, command text, or other sensitive content. Treat the presently saved
token as compromised and rotate it manually in BotFather before final device
acceptance. Do not include the replacement token in source, tests, logs,
screenshots, shell history, or handoff notes.

## Sensor Finding: Current State Is Not Yet a Hardware Failure

The latest commit `52a8eaf` added `SensorReadingSummary` and Protection-screen
rendering. However:

- detectors start only after `ProtectionCoordinator.arm(...)`;
- while disarmed the screen deliberately says `Live samples begin after
  arming`;
- fresh device evidence did not prove that the installed APK was built from
  `52a8eaf`, that it reached Armed after its ten-second grace period, or that
  Android listener callbacks arrived;
- the old `DashboardScreen` diagnostics and `SensorScanner` are a separate,
  legacy surface and must not become a second live sensor authority.

Do not alter sensor code until the device investigation below identifies the
first broken boundary.

## Scope

### Modify for Telegram fix

- `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClient.kt`
- `MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClientSourceContractTest.kt`
- Add a focused transport/request test in
  `MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/telegram/`

### Review for `/status`

- `.../telegram/ProtectionStatusFormatter.kt`
- `.../telegram/TelegramCommandHandler.kt`
- `.../telegram/RemoteCommand.kt`
- `.../service/SensorService.kt`

### Sensor investigation only; edit only after its boundary is proven

- `.../protection/AndroidProtectionRuntime.kt`
- `.../protection/ProtectionCoordinator.kt`
- `.../ui/protection/ProtectionScreen.kt`
- `.../service/SensorService.kt`
- relevant focused unit/Compose tests

### Do not modify

- TLS pin configuration, token-verification policy, `/pair <code>`, TOTP,
  authorization checks, SMS fallback, incident delivery, QR setup, encrypted
  preference storage, or unrelated pending Task 5 UI work.

## Task 1: Secure and Repair Generic Telegram Reply Transport

**Files:**

- Modify: `.../telegram/TelegramBotClient.kt`
- Test: create a transport/request test beside `TelegramBotVerifierTest.kt`
- Modify: `.../telegram/TelegramBotClientSourceContractTest.kt`

**Required behavior:** Generic Telegram reply transport sends command/status
text as plain text. It must not attach `parse_mode=Markdown`; it must not
attempt to escape arbitrary sensor diagnostics at the transport boundary.
This prevents an arbitrary trusted status field from becoming Telegram markup.
Messages that need rich text must use a future explicit formatter with tests;
do not silently treat arbitrary diagnostics as Markdown.

- [ ] **Step 1: Write a failing transport test**

Extract or inject the narrow request-building/execution boundary so the test
can inspect the actual request body without making a real network call. The
test must send this literal status text:

```kotlin
val status = "POWER_THERMAL: healthy (accelerometer_magnitude_sensitivity_5)"
```

Assert that the sent JSON has `chat_id` and exact `text`, but has no
`parse_mode` key. Return a controlled `200 {\"ok\":true}` transport response
and assert the send result is true.

- [ ] **Step 2: Run the test RED**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_OPTS='-Xmx192m -XX:+UseSerialGC -XX:MaxMetaspaceSize=320m'
$env:JAVA_TOOL_OPTIONS='-Xms16m -Xmx96m -XX:+UseSerialGC -XX:MaxMetaspaceSize=256m'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*TelegramBotClientTransportTest'
```

Expected: fail because the current request adds `parse_mode: Markdown`.

- [ ] **Step 3: Apply the smallest transport change**

Remove the generic `parse_mode` field. Keep HTTPS, `TlsPinningClient`, JSON
body encoding, owner authorization, and the existing success condition.

Remove all logs that expose any of the following:

- token or token prefix;
- complete inbound Telegram response body;
- complete outbound Telegram response body;
- chat ID or command text.

Allowed diagnostics are token-free and aggregate-only, such as a numeric HTTP
class only when it cannot be tied to sensitive contents. Prefer no success
logging. Failure logs must not include exception text, URL, response body, or
request body.

- [ ] **Step 4: Strengthen source contract tests**

The source contract must fail if `TelegramBotClient.kt` contains any of these:

```text
botToken.take(
getUpdates response body
sendMessage response:
"parse_mode", "Markdown"
```

It must also continue rejecting `printStackTrace` and interpolation of token,
chat ID, request body, or response body into a log statement.

- [ ] **Step 5: Run focused Telegram suites GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*TelegramBotClientTransportTest' --tests '*TelegramBotClientSourceContractTest' --tests '*TelegramCommandHandlerTest' --tests '*ProtectionStatusFormatterTest' --tests '*TelegramPollingSessionBoundaryTest'
```

- [ ] **Step 6: Commit only Task 1 paths**

```powershell
git add -- app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClient.kt app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClientSourceContractTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClientTransportTest.kt
git commit -m "fix(telegram): send status replies as safe plain text"
```

## Task 2: Prove Sensor Runtime Boundary Before Editing Sensor Code

**Files:** no production modification unless evidence points to a specific
boundary.

- [ ] **Step 1: Confirm fresh package and service state**

```powershell
$adb='C:\Users\ASUS\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb devices -l
& $adb shell dumpsys package com.example.motorcycleantitheftsensor | Select-String -Pattern 'versionName=|versionCode=|lastUpdateTime=|android.permission.RECORD_AUDIO|android.permission.ACCESS_FINE_LOCATION|android.permission.ACCESS_COARSE_LOCATION'
& $adb shell dumpsys activity services com.example.motorcycleantitheftsensor
```

Stop if there is not exactly one authorized test phone. Do not run Gradle at
the same time as ADB.

- [ ] **Step 2: Use the controlled, non-destructive test sequence**

1. Install the freshly built APK only after Task 1 passes.
2. Confirm the app reports `DISARMED_ONLINE`; the expected sensor copy is
   `Live samples begin after arming`.
3. Arm locally and wait 10 seconds.
4. Move/tilt the device; change ambient light; observe battery status.
5. Verify the Protection screen reports Vibration and Light health, latest
   values, and a current last-sample time. Verify power data appears in the
   Battery card.
6. Disarm and wait past the freshness window. Verify the UI does not label
   stale data as current.

Never create a theft incident deliberately, send SMS, make a call, expose a
token, reveal a pairing/TOTP code, dump the UI hierarchy, or capture raw
audio/location.

- [ ] **Step 3: Capture bounded evidence only when needed**

```powershell
& $adb logcat -c
# Perform Steps 3-5 above within 30 seconds.
& $adb logcat -d -v brief -t 400 | Select-String -Pattern 'SensorService|AndroidProtectionRuntime|VibrationDetector|LightIntrusionDetector|PowerThermalMonitor|AudioPeakDetector'
```

Classify the first broken boundary:

| Result | Meaning | Follow-up |
| --- | --- | --- |
| Service absent | foreground service startup/recovery defect | create a dedicated service-start regression task |
| Disarmed only | expected non-monitoring state | no detector fix; improve copy only if unclear |
| Armed with permission blocker | permission setup defect | test/fix permission reporting only |
| Armed, listener registration fails | detector startup defect | add one focused detector regression test and fix that detector only |
| Listener observations present but UI unchanged | snapshot/UI projection defect | continue Task 3 |
| No listener observations with all required permissions | device/runtime integration defect | stop and attach redacted logs plus device capability results; do not guess a code fix |

## Task 3: Sensor Projection Fix Only If Task 2 Proves It

**Possible files:** `ProtectionCoordinator.kt`, `ProtectionModels.kt`,
`ProtectionUiModels.kt`, `ProtectionScreen.kt`, and focused unit/Compose tests.

- [ ] **Step 1: Write one failing test for the first observed gap**

Examples:

- a vibration observation updates `SensorHealth.latestReading` with a labelled
  `m/s^2` value;
- a light observation updates its labelled `lux` value;
- a disarmed snapshot with no sample renders `Live samples begin after arming`;
- an armed snapshot with a health value renders that exact latest reading.

The test must target the evidence from Task 2, not add a broad hardware
scanner or a second state store.

- [ ] **Step 2: Run the exact test RED, then make the smallest correction**

The UI continues to render the authoritative `ProtectionSnapshot`; it must
not register `SensorManager` listeners or use legacy `SensorScanner` for live
readings.

- [ ] **Step 3: Run focused tests GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*ProtectionCoordinatorTest' --tests '*AndroidProtectionRuntimeTest'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:connectedDebugAndroidTest
```

## Task 4: Verify Every Supported and Rejected Telegram Command

Run this after Task 1's safe plain-text transport fix and after installing the
fresh APK. Add/update unit tests for each parser and handler branch before the
manual test. The test matrix below is mandatory; do not mark the task complete
from `/status` alone.

| Input from paired owner | Expected reply/result | Safety assertion |
| --- | --- | --- |
| `/start` | Help text | no state change |
| `/help` | Help text | no state change |
| `/status` | Current protection, Telegram, permission, sensor, battery, and delivery state | plain text; no Markdown parse failure; no secret/precise location |
| `/arm` with readiness satisfied | receipt then applied/degraded result after grace | detectors start; no SMS/call |
| `/arm` with a named blocker | rejected result naming only the blocker | never claims armed; detectors do not start |
| `/disarm` without code | usage reply | remains armed; no bypass |
| `/disarm <invalid six digits>` | invalid/locked-out reply | remains armed; no TOTP value echoed |
| `/disarm <current valid TOTP>` | applied/disarmed reply | detectors stop; polling remains responsive |
| `/sensitivity` | `Usage: /sensitivity 1-10` | no sensitivity write |
| `/sensitivity 0` and `/sensitivity 11` | rejected result | no sensitivity write |
| `/sensitivity 7` | applied result | running vibration detector receives level 7; no service restart |
| `/decode` with no payload | safe rejection/failure reply | no decrypted content or key disclosure |
| `/decode <malformed test payload>` | safe rejection/failure reply | no crash or secret disclosure |
| unknown command, e.g. `/ping` | `Unknown command. Use /help.` | no state change |
| `/pair <wrong code>` before ownership exists | pairing-rejected reply | no owner is added |
| `/pair <expired code>` before ownership exists | expiry reply | no owner is added |
| `/pair <fresh code>` before ownership exists | owner-paired reply | exactly that chat becomes owner |
| `/pair <any code>` after ownership exists | unknown-command reply | cannot replace/add owner implicitly |
| every operational command from an unpaired chat | `Unauthorized command.` | no operational detail, no state change, no pairing reset |

### Required parser and handler tests

- [ ] Test `/start`, `/help`, `/status`, `/arm`, `/disarm`, `/sensitivity`,
  `/decode`, `/pair`, and an unknown command in `RemoteCommand` tests.
- [ ] Test each invalid-argument row above independently; an invalid argument
  must not be accepted by a nearby valid-command test.
- [ ] Test the command handler's reply and authoritative coordinator effect for
  help, status, arm, disarm, sensitivity, and unsupported commands.
- [ ] Test pairing/authorization in `TelegramBotClient` with fake preferences:
  wrong/expired/fresh pair code, already-paired owner, and unpaired sender.
- [ ] Test every reply through the same plain-text request builder introduced
  by Task 1, including a literal status string with underscores.

### Required manual device sequence

Use one paired owner account and one unpaired account. Reset/re-pair only in a
controlled test configuration after preserving any production owner access.
Do not write or photograph pairing codes, TOTP codes, chat IDs, bot tokens,
AES keys, or decode payloads. Record only a redacted pass/fail row, timestamp,
command name, and observed state.

For `/decode`, use only a deliberately malformed non-secret fixture and verify
the safe failure branch; do not send a real encrypted alarm or any real key.
For valid `/disarm`, enter the current code manually and record only that the
authorization succeeded. Before and after the command matrix, verify:

```powershell
& $adb shell appops get com.example.motorcycleantitheftsensor SEND_SMS
```

Expected: `SEND_SMS: deny`. Any SMS attempt, call attempt, plaintext secret,
or authorization bypass stops acceptance immediately.

## Final Verification and Acceptance

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest :app:assembleDebug
git diff --check
rg -n --hidden -g '!build/**' -g '!.gradle/**' '(bot[0-9]{6,}:|[0-9]{6,}:[A-Za-z0-9_-]{20,})' app docs
```

On the real device:

1. Rotate the token through BotFather outside source control, then enter it
   manually in the app.
2. Install the fresh APK; deny `SEND_SMS` and set `SEND_SMS: deny`.
3. Pair the owner with `/pair <code>`.
4. Send `/status`; receive a plain-text status reply containing sensor health.
5. Arm locally, create only safe sensor movement/light changes, and confirm
   both screen telemetry and the next `/status` reply reflect current health.

Do not claim success until the automated gate and this live test both succeed.

## Definition of Done

- [ ] `/status` receives a reply after pairing, including sensor health.
- [ ] Every supported, malformed, unauthorized, and pairing command in Task 4
      has fresh automated and redacted manual-device evidence.
- [ ] Generic Telegram replies cannot fail because a sensor diagnostic contains
  Markdown metacharacters.
- [ ] No token prefix, inbound body, outbound body, chat ID, or command text
  is logged.
- [ ] Current bot token is rotated after the observed logging exposure.
- [ ] Sensor issue is classified by fresh device evidence; only the proven
  boundary is changed.
- [ ] Sensor UI differentiates disarmed, unavailable, failed, healthy, and
  stale data truthfully.
- [ ] TLS pinning remains enabled and `SEND_SMS` stays denied.
- [ ] Mixed dirty worktree is preserved; stage only exact reviewed paths.

## Existing Work to Preserve

- `TASK-TG-001-telegram-verification-reconciliation.md`: verification callback
  migration has related history; do not overwrite its scope without review.
- `TASK-SENS-001-sensor-telemetry-diagnosis-and-ui-projection.md`: retain its
  privacy rules and sensor evidence boundary; this task supersedes only its
  response-status investigation with the confirmed Markdown cause.
- QR checkpoint `CKP-20260811-2357-YD5EKY` and UI Task 5 checkpoint
  `CKP-20260809-0955-4I1V0U` remain separate pending work.
