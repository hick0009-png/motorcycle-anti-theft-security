# Anti-Theft Security Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make remote control fail closed, deliver sensor alerts independently of the UI, and prevent unauthenticated SMS alteration.

**Architecture:** Add small JVM-testable policy and command units, then connect them to encrypted preferences, Telegram, telephony, and the existing service. `SensorService` becomes the single owner of arm/disarm and alert dispatch; UI requests operations and renders readiness.

**Tech Stack:** Kotlin, Android SDK, Jetpack Compose, OkHttp, Android Keystore, EncryptedSharedPreferences, JUnit 4.

## Global Constraints

- Do not add dependencies.
- Owner, pairing, token, and TOTP state stays in encrypted preferences.
- Pair only through `/pair <code>`; never authorize the first incoming chat.
- `/disarm` always requires valid TOTP.
- Internal arm, disarm, and alert flows must not use exported implicit broadcasts.
- AES-GCM must generate a fresh 96-bit nonce per message and reject alteration.
- Device behavior requires a fresh device test.

---

### Task 1: Pairing policy and state

**Files:**
- Create: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/security/PairingCodePolicy.kt`
- Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/data/EncryptedPrefsManager.kt`
- Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClient.kt`
- Test: `MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/security/PairingCodePolicyTest.kt`

**Interfaces:** `PairingCodePolicy.generate(nowMs): PairingCode` and `validate(submitted, stored, nowMs): PairingResult`. `PairingCode(value, expiresAtMs)`; result is `Accepted`, `Expired`, `Rejected`, or `AlreadyPaired`.

- [ ] Write the failing tests:

```kotlin
@Test fun matchingUnexpiredCodeIsAccepted() {
  assertEquals(Accepted, policy.validate("123456", PairingCode("123456", 2_000), 1_000))
}
@Test fun expiredOrMismatchedCodeIsRejected() {
  assertEquals(Expired, policy.validate("123456", PairingCode("123456", 999), 1_000))
  assertEquals(Rejected, policy.validate("654321", PairingCode("123456", 2_000), 1_000))
}
```

- [ ] Run `cd MotorcycleAntiTheftSensor; .\gradlew.bat testDebugUnitTest --tests *PairingCodePolicyTest`; expect FAIL because the policy does not exist.
- [ ] Implement a SecureRandom six-digit code with a ten-minute expiry; persist code and expiry in encrypted preferences; consume them only after saving the owner. Replace first-message auto-bind with `/pair <code>`.
- [ ] Re-run the focused test; expect PASS.
- [ ] Commit only these files with `fix: require verified telegram pairing`.

### Task 2: Fail-closed remote commands

**Files:**
- Create: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/telegram/RemoteCommand.kt`
- Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClient.kt`
- Test: `MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/telegram/RemoteCommandTest.kt`

**Interfaces:** `RemoteCommand.parse(text): RemoteCommand`; commands include `Arm`, `Disarm(code: String?)`, `Sensitivity(level: Int?)`, `Status`, `Pair(code: String?)`, and `Unknown`. Telegram calls `onAuthorizedCommand(command)` only after owner and TOTP checks.

- [ ] Write failing tests that parse `/disarm` as `Disarm(null)` and prove only `VerificationResult.SUCCESS` permits disarm; missing seed and invalid code must return false.
- [ ] Run `cd MotorcycleAntiTheftSensor; .\gradlew.bat testDebugUnitTest --tests *RemoteCommandTest`; expect FAIL.
- [ ] Implement the sealed parser. Reject state changes from unpaired chats. For disarm, invoke the callback only after TOTP success; remove direct armed-preference writes from the Telegram client.
- [ ] Re-run the focused test; expect PASS.
- [ ] Commit only these files with `fix: fail closed for remote disarm`.

### Task 3: Service-owned control path

**Files:**
- Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt`
- Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/Navigation.kt`
- Modify: `MotorcycleAntiTheftSensor/app/src/main/AndroidManifest.xml`
- Test: `MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/service/SensorServiceActionTest.kt`

**Interfaces:** `SensorService.ACTION_ARM`, `ACTION_DISARM`, `ACTION_START_SERVICE`, and `ACTION_STOP_SERVICE`; `SensorServiceAction.from(action)` returns `Arm`, `Disarm`, `Start`, `Stop`, or `Ignore`.

- [ ] Write the failing test:

```kotlin
@Test fun armAndDisarmMapOnlyFromServiceConstants() {
  assertEquals(Arm, SensorServiceAction.from(SensorService.ACTION_ARM))
  assertEquals(Ignore, SensorServiceAction.from("third.party.DISARM"))
}
```

- [ ] Run `cd MotorcycleAntiTheftSensor; .\gradlew.bat testDebugUnitTest --tests *SensorServiceActionTest`; expect FAIL.
- [ ] Handle arm/disarm in the explicit service intent: persist state, start/stop sensors, and start/stop polling. Remove the Navigation runtime receiver and send explicit service intents. Mark application receivers non-exported unless Android requires a system receiver.
- [ ] Re-run the focused test; expect PASS.
- [ ] Commit only these files with `fix: own remote commands in sensor service`.

### Task 4: Alert dispatch and fallback

**Files:**
- Create: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/service/AlertDispatcher.kt`
- Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt`
- Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClient.kt`
- Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/telephony/SmsFallbackManager.kt`
- Test: `MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/service/AlertDispatcherTest.kt`

**Interfaces:** `AlertDispatcher.dispatch(AlertEvent): AlertDispatchResult`, `AlertEvent(type, message, timestampMs)`, and `AlertTransport.send(message): Boolean`.

- [ ] Write failing tests that prove Telegram success does not send SMS and Telegram failure uses SMS only when SMS configuration is valid.
- [ ] Run `cd MotorcycleAntiTheftSensor; .\gradlew.bat testDebugUnitTest --tests *AlertDispatcherTest`; expect FAIL.
- [ ] Implement direct dispatcher invocation from `SensorService.handleAlertTrigger`. Telegram sends first; a false result uses configured SMS on a background thread. Preserve UI alarm display only as UI state. Report thermal events as alerts, not power cuts.
- [ ] Re-run the focused test; expect PASS.
- [ ] Commit only these files with `fix: dispatch sensor alerts through fallback chain`.

### Task 5: Authenticated SMS codec

**Files:**
- Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/telephony/EncryptedSmsCodec.kt`
- Test: `MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/telephony/EncryptedSmsCodecTest.kt`

**Interfaces:** `encryptSmsPayload(plainText, secret)` returns an `[ENC_ALARM_V2]` payload; `decryptSmsPayload(payload, secret)` returns plaintext only for a valid V2 payload.

- [ ] Write failing tests asserting two encryptions of the same message differ, a valid result decrypts, and a modified final character returns null.
- [ ] Run `cd MotorcycleAntiTheftSensor; .\gradlew.bat testDebugUnitTest --tests *EncryptedSmsCodecTest`; expect FAIL because current CBC output is deterministic.
- [ ] Implement AES/GCM/NoPadding with a SHA-256-derived 256-bit key, new 12-byte nonce, and Base64(nonce+ciphertext+tag). Reject V1, malformed, wrong-key, and tampered payloads without logging plaintext.
- [ ] Re-run the focused test; expect PASS.
- [ ] Commit only these files with `fix: authenticate encrypted SMS payloads`.

### Task 6: Permission-gated arming and full verification

**Files:**
- Modify: `MotorcycleAntiTheftSensor/app/src/main/AndroidManifest.xml`
- Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/Navigation.kt`
- Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/ui/DashboardScreen.kt`
- Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/sensor/AudioPeakDetector.kt`
- Test: `MotorcycleAntiTheftSensor/app/src/androidTest/java/com/example/motorcycleantitheftsensor/PermissionGateTest.kt`

**Interfaces:** `ProtectionReadiness(missingPermissions: Set<String>, canArm: Boolean)`; UI requests `RECORD_AUDIO` and Android 13+ `POST_NOTIFICATIONS` before presenting full protection as active.

- [ ] Write a failing test proving denied microphone permission produces `canArm == false`.
- [ ] Run `cd MotorcycleAntiTheftSensor; .\gradlew.bat connectedDebugAndroidTest --tests *PermissionGateTest`; expect FAIL. If no device is connected, record a device-test blocker and continue JVM verification.
- [ ] Implement permission requests and readiness state; prevent arm when microphone is missing; show degraded state and reason. Remove permission suppressions only after service and detector guard runtime permissions.
- [ ] Run `cd MotorcycleAntiTheftSensor; .\gradlew.bat testDebugUnitTest assembleDebug lintDebug`; expect PASS. On a device test pairing expiry, unpaired disarm rejection, valid-TOTP disarm, sensor alert, Telegram-to-SMS fallback, and microphone denial.
- [ ] Commit only these files with `fix: block arming without required permissions`.

## Plan Self-Review

- Tasks 1–6 cover each audit finding; hardware power cut-off remains explicitly excluded.
- The plan adds no dependency or backend and exposes no implicit cross-app control path.
- Every production change follows a failing focused test and a passing re-run.

