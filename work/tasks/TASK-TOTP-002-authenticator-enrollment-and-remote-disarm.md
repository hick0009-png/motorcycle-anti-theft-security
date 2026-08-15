# TASK-TOTP-002 — Restore verified Authenticator enrollment for Telegram remote disarm

## Objective

Resolve the condition where Telegram replies:

> `TOTP must be configured on the device before remote disarm is allowed.`

without weakening the security rule.  Remote `/disarm` must remain unavailable
until the app has locally verified a current six-digit TOTP code and saved the
corresponding seed in encrypted device storage.

This task is a diagnose-first repair.  The reply is correct when enrollment was
cancelled, the entered code was invalid/expired, or the device has no saved
seed.  It is a defect only when Settings reports **Authenticator configured**
after a successful verification but the service still receives no seed.

## Current architecture and confirmed control flow

* `SettingsScreen` shows **Set up authenticator** (or **Replace
  authenticator**) and lets the owner scan a QR code or explicitly reveal the
  secret, then enter a verification code.
* `AndroidProtectionSettingsGateway.beginAuthenticatorSetup()` creates a
  temporary `TotpAuthenticator.SetupCandidate`; it must not persist anything.
* `verifyAuthenticator(code)` verifies the temporary candidate, then calls
  `activateAuthenticatorSetup()` only after a successful result.
* `TotpAuthenticator.activateSetup()` writes the seed through
  `EncryptedPrefsManager.saveTotpSeed()`; the value is stored by encrypted
  preferences.
* `TelegramBotClient` checks `EncryptedPrefsManager.getTotpSeed()` before
  accepting `/disarm <code>`, then verifies the supplied code using the same
  authenticated source.

The most likely field failure is therefore an incomplete enrollment flow, not
a Telegram pairing failure.  The plan below proves each boundary before any
source change.

## Non-negotiable security constraints

1. Do not add a bypass, fallback password, or a remote command that enrolls,
   resets, exposes, or disables TOTP.
2. Do not change owner-pairing authorization.  `/pair <code>` remains the only
   command allowed before an owner chat is paired.
3. Never log, commit, screenshot, accessibility-dump, or put in test output:
   bot token, pairing code, TOTP seed/URI/QR image, or live six-digit code.
4. Keep the seed only in `EncryptedPrefsManager`; it may be persisted there
   after local verification, but not in plaintext preferences, UI state saved
   across process death, analytics, or logs.
5. Preserve the existing rate limit: do not repeatedly guess TOTP codes.  A
   lockout must continue to deny remote disarm.

## Scope

In scope:

* Authenticator setup, cancellation, verification, encrypted persistence, UI
  state refresh, service visibility, and the `/disarm` gate.
* Complete the paused QR implementation checkpoint
  `CKP-20260811-2357-YD5EKY`: scoped fix review, full automated gate, APK
  install, and two-phone live acceptance.
* Clear, actionable setup/error text that does not reveal secrets.
* Unit/UI/device verification for every supported Telegram command.

Out of scope:

* Changing the TOTP algorithm, issuer, code period, owner authorization model,
  or the sensor/protection coordinator.
* Adding cloud backup/sync of TOTP secrets, alternate remote-disarm channels,
  or a new dependency.

## Phase 0 — Reproduce safely and collect only redacted evidence

1. Record app version/build SHA, device model/API level, and whether Android
   **Automatic date & time** and **Automatic time zone** are enabled.  TOTP is
   time-based; a clock that is significantly wrong makes a valid enrollment
   code fail.
2. In the app, open **Settings → Authenticator** and record only the visible
   state: `Authenticator not configured` or `Authenticator configured`.
3. Start setup, scan the QR in Google Authenticator/Authy, enter the currently
   displayed six-digit code, and tap **Verify**.  Do not capture the QR, secret,
   or code in screenshots or logs.
4. Close/reopen Settings and record the visible state again.  Force-stop and
   reopen the app once, then confirm the same state.  This distinguishes a
   transient UI success from persisted enrollment.
5. With an already paired owner chat and the vehicle in a safe, controlled test
   state, send `/disarm` with no code and record the reply; then send a freshly
   generated current code.  Do not paste either command or code into the ticket.
6. Classify the result before editing:

| Observed state | Diagnosis path |
| --- | --- |
| Verify is rejected; Settings remains unconfigured | Validate phone clock, authenticator entry/issuer, QR generation, and candidate verification. No persistence repair yet. |
| Verify succeeds but reopening Settings shows unconfigured | Trace `activateAuthenticatorSetup` → `saveTotpSeed`; inspect encrypted-preferences write failures without printing the value. |
| Settings remains configured but Telegram says no TOTP | Trace service construction and preference instance/context; prove the service reads the same encrypted key after restart. |
| Telegram says invalid/expired code | Check automatic time/timezone, use the next code period once, and ensure the scanned entry is the current MotorcycleGuard entry. |
| Telegram says locked out | Stop attempts until the five-minute lockout expires; do not reduce/disable rate limiting. |

Exit criterion: attach redacted observations to the task and choose exactly one
root-cause branch.  Do not make speculative changes.

## Phase 0.5 - Complete QR checkpoint `CKP-20260811-2357-YD5EKY`

This phase is mandatory, not merely a check that a QR bitmap is visible.  The
checkpoint is currently **IN_PROGRESS**: Task 1 (local encoder) is
review-clean; Task 2 (explicit Show/Hide QR UI) has a security-fix commit but
its required scoped re-review and all full/device gates are still pending.

### QR completion sequence

1. Resume from `.hcp/checkpoints/CKP-20260811-2357-YD5EKY.json`; read the
   approved QR plan, the SDD ledger
   `.superpowers/sdd/2026-08-11-authenticator-qr-setup/progress.md`,
   `task-2-brief.md`, and the fix section of `task-2-report.md`.
   Do not redispatch the completed encoder Task 1.
2. Perform an independent **scoped re-review** of only
   `3ace729..3322c36`.  The review question is precise: the earlier Critical
   finding was a secret-bearing accessibility description.  Confirm the fixed
   semantics are static text only, and look for regressions introduced by that
   exact fix.  A review finding at Critical or Important severity must be fixed
   and re-reviewed before continuing.
3. Mark QR Task 2 complete in the SDD ledger only after the scoped review is
   clean.  Then create the Task 3 execution brief from the existing approved
   QR plan; retain the present design: QR is generated locally with ZXing,
   encoding and bitmap allocation are off the main thread, and the QR is shown
   only after an explicit user action.
4. Run the focused tests before the full gate:

   ```powershell
   $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
   .\gradlew.bat testDebugUnitTest --tests '*AuthenticatorQrCodeEncoderTest' --no-daemon --max-workers=1
   .\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.motorcycleantitheftsensor.ui.ProtectionAppScreenTest --no-daemon --max-workers=1
   ```

   The focused evidence must include: URI encode/decode, rejection of a
   non-TOTP URI and undersized QR, explicit Show/Hide behavior, cancel cleanup,
   fallback when QR generation fails, `FLAG_SECURE`, and secret/URI semantic
   redaction.  Test fixtures must remain synthetic.
5. Run the full automated gate and assemble a fresh APK using the commands in
   **Verification commands** below.  Record exact test counts, build SHA, and
   the APK SHA-256; do not run Gradle and ADB concurrently.  If the machine is
   low on disk space, stop and record the free-space blocker rather than
   deleting caches or user files outside the approved scope.
6. Before installing the APK on Huawei `JUCDU18811013149`, ensure the device is
   awake/unlocked and USB-debug authorized.  Install only the freshly assembled
   APK, immediately set and verify `SEND_SMS` to `deny`, and record that
   non-sensitive app-op result.  Do not open the setup dialog before this check.

   ```powershell
   $adb = 'C:\Users\ASUS\AppData\Local\Android\Sdk\platform-tools\adb.exe'
   $serial = 'JUCDU18811013149'
   $package = 'com.example.motorcycleantitheftsensor'
   & $adb -s $serial install -r '.\app\build\outputs\apk\debug\app-debug.apk'
   & $adb -s $serial shell pm revoke $package android.permission.SEND_SMS
   & $adb -s $serial shell appops set $package SEND_SMS deny
   & $adb -s $serial shell appops get $package SEND_SMS
   ```

   Expected final line: `SEND_SMS: deny`.  A failed install, unauthorized
   device, or any other final state stops acceptance; do not substitute an
   emulator for the required Huawei/two-phone acceptance.
7. Perform one redacted two-phone acceptance run:

   a. On the vehicle phone, open **Settings -> Authenticator -> Set up
      authenticator**.  Confirm the QR is absent until **Show QR code** is
      pressed and `FLAG_SECURE` is active while the dialog is present.

   b. On the second phone, scan the QR with a TOTP-compatible authenticator.
      Do not take a screenshot, screen recording, clipboard copy, UI dump, or
      log while the QR/secret is visible.

   c. Enter the current six-digit code locally in the vehicle app and verify.
      Confirm only the non-sensitive label **Authenticator configured**.

   d. Close/reopen Settings and force-stop/reopen the app.  Confirm the label
      remains configured, then perform the valid owner `/disarm` test in the
      controlled condition defined in Phase 4.

   e. Check again that `SEND_SMS` is `deny`; no SMS or emergency call may occur
      during the test.
8. Run the final whole-branch review after the QR automated and two-phone gates
   pass.  Only then update the HCP checkpoint status to complete, including
   review result, test counts, APK SHA-256, redacted device outcome, residual
   risks, and the exact resume command.

### QR checkpoint completion criteria

* The `3ace729..3322c36` scoped review is clean and recorded in the SDD ledger.
* Encoder and Compose tests pass, including redaction and cleanup behavior.
* Fresh full unit/instrumented/build gates pass after the QR changes.
* A freshly installed Huawei build passes one two-phone QR scan and local TOTP
  verification, then survives app restart and supports the controlled valid
  `/disarm` acceptance case.
* `SEND_SMS` is verified `deny` immediately before and after device acceptance.
* No QR/secret/code appears in evidence, logs, screenshots, accessibility data,
  clipboard, test output, or checkpoint metadata.

## Phase 1 — Add the missing regression tests first (RED)

Keep existing tests and extend the nearest test file; do not replace coverage
with a broad mock-only test.

### A. Enrollment domain: `TotpAuthenticatorTest`

Add/retain deterministic tests using synthetic fixtures only:

1. Creating a candidate does not alter an existing configured seed.
2. Invalid/blank/expired candidate code does not save or replace a seed.
3. Valid candidate verification alone does not save a seed; explicit activation
   after success saves exactly that candidate.
4. A new authenticator instance reading the same storage abstraction can verify
   a current code after activation (process/service visibility contract).
5. Previous/current/next 30-second window behavior remains as designed, and
   three failed attempts lead to lockout; success resets the failed-attempt
   counter.

### B. Setup transaction: `AndroidProtectionSettingsGatewayTest`

1. Successful `begin → verify → activate` changes `read()` from
   `authenticatorConfigured=false` to `true`.
2. Cancel, invalid verification, and a stale in-flight verification never call
   activation and leave the configured state unchanged.
3. Replacing an existing authenticator does not replace the existing seed until
   the new candidate is verified; cancel keeps the old one usable.
4. The test double must record only operation names/state booleans.  It must
   not put code or candidate-secret values in assertion messages/events.

### C. UI state: `ProtectionViewModelTest` and Settings UI tests

1. After successful verification, refresh the settings summary and render
   **Authenticator configured**.
2. Rejected verification leaves the dialog open with a generic error and keeps
   the seed unconfigured.
3. Cancel/dismiss clears the pending candidate and QR bitmap; a later Verify
   cannot activate it.
4. Preserve secret/QR semantics redaction: accessibility descriptions may say
   `hidden`/`revealed`, but must not contain the seed or the `otpauth://` URI.

### D. Telegram integration seam: `TelegramBotClientAuthorizationTest`

Use synthetic `EncryptedPrefsManager`/`TotpAuthenticator` values and a fake
command handler to prove:

1. No saved seed returns the existing configuration-required reply and does
   not invoke the handler.
2. A saved seed plus missing code returns usage and does not invoke the
   handler.
3. A saved seed plus invalid code returns denial; lockout remains denial.
4. A saved seed plus a valid current code delegates exactly one `Disarm` to
   the service command handler.
5. The test must exercise a newly constructed Telegram client after activation,
   not reuse a cached value from setup, to prove the persisted-device boundary.

## Phase 2 — Implement only the root-cause repair (GREEN)

Follow the branch selected in Phase 0.

### Branch A: enrollment verification cannot succeed

Repair only the proven QR/URI, candidate verification, input normalization, or
UI lifecycle defect.  Maintain RFC 6238 parameters already used by the app:
six digits, SHA-1, 30-second period, with the existing limited clock-skew
window.  Do not accept a code merely because it has six digits.

### Branch B: verified candidate is not durable

Repair the exact failed persistence boundary.  The success path must be:

`verify candidate successfully → atomically activate candidate → refresh
Settings summary → a new service/client reads a nonblank encrypted seed`

If a durable write is required for correctness, use the existing encrypted
preferences API deliberately and add a failure result/UI retry rather than
claiming enrollment completed before the write is known to have succeeded.
Keep candidate data memory-only until that point.

### Branch C: Settings and service read different state

Repair dependency wiring so Settings and `SensorService` use the same
application-context `EncryptedPrefsManager` and the service constructs its
`TotpAuthenticator` from that manager.  Do not give the UI-only Telegram
verification client command-handling responsibility; command execution remains
owned by the service/coordinator.

After a successful enrollment, refresh/restart the control service only if the
actual lifecycle analysis proves it caches authentication state.  The current
client reads the seed at command time, so do not restart it gratuitously.

### Branch D: only device time or stale authenticator entry is wrong

No source change.  Document the user recovery steps below and mark the code
path verified.  If the user has scanned multiple entries, cancel the unverified
dialog and restart setup once; do not expose the seed to resolve ambiguity.

## Phase 3 — User-facing recovery text

Ensure the app/Telegram guidance is concise and precise, without echoing
sensitive input:

1. `TOTP must be configured…` should direct the owner to
   **Settings → Authenticator → Set up authenticator**.
2. State that the owner must scan the locally displayed QR (or explicitly
   reveal/enter the secret), then enter a *current* six-digit code in the app
   and wait for **Authenticator configured**.
3. State the Telegram syntax only after enrollment:
   `/disarm <current-six-digit-code>`.
4. For rejection, advise checking automatic date/time and using the next code
   period.  Do not show the expected code, seed, URI, or remaining failed count.
5. For lockout, say to wait and retry with a fresh code; do not reveal an exact
   cooldown timestamp if that creates an attack oracle.

## Phase 4 — Mandatory command-by-command verification

Run this matrix with an owner chat, a separate unpaired chat when available,
and a safe non-moving test vehicle.  Capture only pass/fail and redacted reply
category.  Check the Android service log only for lifecycle/error categories;
it must contain no token, chat ID, pairing code, seed, URI, QR payload, or TOTP
code.

| Command / state | Required result |
| --- | --- |
| `/start` | Safe help/onboarding reply; no state change. |
| `/help` | Lists supported syntax without secrets. |
| `/status` | One status reply from the service; no Markdown parse error or sensitive fields. |
| `/pair <wrong>` from unpaired chat | Denied; no owner added. |
| `/pair <valid>` from unpaired chat | Exactly one owner added; replay is denied/harmless. |
| Any non-`/pair` command from unpaired chat | `Unauthorized command`; no action. |
| `/arm` from owner | Delegates once; authoritative armed state/status updates. |
| `/sensitivity <valid>` from owner | Delegates once; valid range/state reflected. |
| `/sensitivity <invalid>` from owner | Validation reply; no setting change. |
| `/disarm` before enrollment | Configuration-required reply; no disarm delegation. |
| `/disarm` after enrollment, no code | Usage reply; no disarm delegation. |
| `/disarm <invalid>` | Denied; no disarm delegation. |
| `/disarm <current valid>` | Exactly one disarm delegation and authoritative state becomes disarmed. |
| Repeated invalid `/disarm` until limit | Lockout denial; no command delegation; wait rather than bypass. |
| Unknown command | Safe help/error reply; no state change. |

For physical acceptance, run the valid `/disarm` case only after an explicit
local arm and with the motorcycle secured.  Confirm Android `SEND_SMS` remains
denied before and after the exercise, so this verification cannot trigger an
SMS fallback or emergency call.

## Verification commands

Run from `D:\security\MotorcycleAntiTheftSensor` with the Android Studio JBR;
avoid concurrent Gradle/ADB work on this machine:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat testDebugUnitTest --no-daemon --max-workers=1
.\gradlew.bat connectedDebugAndroidTest --no-daemon --max-workers=1
.\gradlew.bat assembleDebug --no-daemon --max-workers=1
```

If a full connected suite is not available, report that explicitly and run the
focused unit tests plus the manual device matrix.  A build or unit test alone
does not prove live Telegram polling, QR scanning, persistence across restart,
or physical disarm behavior.

## Acceptance criteria

1. The configuration-required Telegram reply occurs only when encrypted
   device storage has no activated TOTP seed.
2. A locally verified enrollment survives Settings reopen and app/service
   restart, and Settings displays **Authenticator configured**.
3. A fresh Telegram service/client instance observes the activated seed and
   accepts only a valid, current TOTP for `/disarm`.
4. Missing, invalid, expired, unauthorized, and locked-out cases cannot reach
   the disarm command handler.
5. Every Telegram command in the matrix has current unit/device evidence.
6. No test artifact, UI semantic, log, screenshot, or commit exposes a secret
   or live verification code.
7. Relevant unit tests, instrumented tests, build, and the redacted physical
acceptance record are attached to the handoff.
8. `CKP-20260811-2357-YD5EKY` is closed only after every QR checkpoint
   completion criterion in Phase 0.5 is evidenced; a visible QR alone is not
   sufficient.

## Handoff report required from the implementing agent

Report the selected Phase-0 root cause, exact files changed, tests added or
updated, command output summary, the completed command matrix, and any device
steps that could not be run.  Do not report the issue fixed solely because the
project compiles or because a QR code appears.
