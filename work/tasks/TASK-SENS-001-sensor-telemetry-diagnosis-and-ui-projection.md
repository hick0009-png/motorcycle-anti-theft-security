# TASK-SENS-001: Diagnose and Present Truthful Sensor Telemetry

## Objective

Determine why the Android app shows no useful sensor information despite a
working Telegram connection, then make the Protection screen show truthful,
privacy-safe sensor telemetry after monitoring is armed.

## Current Evidence (do not treat as final device diagnosis)

- Telegram connectivity and sensor monitoring are separate subsystems. A
  reachable Telegram bot does not start hardware detectors.
- `PlatformAndroidDetectorSet.start()` is reached only through
  `ProtectionCoordinator.arm(...)`; when the app is `DISARMED_ONLINE`, it does
  not register the accelerometer, light, microphone, or battery listeners.
- `ProtectionScreen` renders all `SensorKind` cards, but currently displays
  only health state, diagnostic text, and last-sample time. It does not expose
  a latest sensor reading.
- `ProtectionCoordinator.recordSensorSample(...)` stores health/timestamp and
  battery level/temperature. It discards non-battery numeric values after
  incident processing, so raw vibration and light data cannot reach the UI.
- Fresh device/log evidence was not collected: an earlier broad ADB logcat
  command stalled and was stopped. Do not call the issue fixed based on source
  inspection alone.

## Scope

### May modify

- `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionModels.kt`
- `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt`
- `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt`
- `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/ProtectionScreen.kt`
- Focused tests under `app/src/test/.../protection/` and
  `app/src/androidTest/.../ui/`

### Review before changing

- `.../protection/AndroidProtectionRuntime.kt`
- `.../protection/ProtectionRuntimeGraph.kt`
- `.../service/SensorService.kt`
- `.../sensor/VibrationDetector.kt`, `LightIntrusionDetector.kt`,
  `PowerThermalMonitor.kt`, `AudioPeakDetector.kt`, and
  `LocationObservationProvider.kt`

### Do not modify

- Telegram token verification, TLS pins, `/pair <code>`, TOTP, SMS fallback,
  incident delivery policy, QR setup, encrypted preference storage, or the
  existing Task 5 UI work.

## Required Behavior

1. While **disarmed**, sensor cards must state that live monitoring begins
   after arming. They must not falsely report an unavailable hardware sensor
   merely because no listener is active.
2. After **Arm** and the 10-second calibration period, the screen must show,
   for every available detector, its current health and last sample time.
3. The screen must expose a safe latest-value summary only where it is useful:
   - vibration: latest accelerometer magnitude with unit/meaning stated;
   - light: latest ambient light in lux;
   - power: battery percentage and temperature (already shown in the battery
     card; do not duplicate conflicting values);
   - microphone: `sample received` only, never raw audio or an inferred dB
     SPL value;
   - location: availability/fix age/accuracy only, never coordinates.
4. If a detector cannot start, its card must state the concrete reason such as
   missing permission, absent hardware, listener registration failure, or a
   stale sample. Do not silently label it healthy.
5. Sensor display must reflect the authoritative `ProtectionSnapshot`; UI must
   not read `SensorManager` or create its own sensor listeners.

## Phase 1: Reproduce and Locate the Failing Boundary (no production edit)

1. Confirm one authorized device without running Gradle concurrently:

```powershell
$adb='C:\Users\ASUS\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb devices -l
& $adb get-state
```

2. Capture only non-secret device state. Never type a bot token through ADB,
   capture Settings text, dump UI hierarchy, or include token/pairing/TOTP
   values in logs/screenshots.

```powershell
& $adb shell dumpsys package com.example.motorcycleantitheftsensor | Select-String -Pattern 'versionName=|versionCode=|android.permission.RECORD_AUDIO|android.permission.POST_NOTIFICATIONS|android.permission.ACCESS_FINE_LOCATION|android.permission.ACCESS_COARSE_LOCATION'
& $adb shell dumpsys activity services com.example.motorcycleantitheftsensor
```

3. In the app, record redacted observations for this exact sequence:
   - open Protection while disarmed;
   - arm locally;
   - wait until calibration completes;
   - move/tilt the phone, change ambient light, and observe battery card;
   - return to Protection and record each sensor card's state and last sample.

4. Capture a **bounded** log window only if tags already exist and do not emit
   secrets. Use a short timeout; do not repeat the earlier unbounded logcat
   command.

```powershell
& $adb logcat -c
# Perform the arm/test sequence above for at most 30 seconds.
& $adb logcat -d -v brief -t 400 | Select-String -Pattern 'SensorService|VibrationDetector|LightIntrusionDetector|PowerThermalMonitor|AudioPeakDetector|AndroidProtectionRuntime|ProtectionCoordinator'
```

5. Classify the evidence before editing:

| Boundary | Evidence | Next action |
| --- | --- | --- |
| Service not running | no foreground service/process | fix service start/recovery path in a separate reviewed task |
| Service runs, state remains disarmed | detectors intentionally never start | implement the disarmed explanatory UI and test the Arm flow |
| Armed but a permission is denied | explicit missing permission | fix only permission request/state reporting |
| Armed, listener registration fails | detector diagnostic reports failure | fix that detector's startup path with a focused regression test |
| Observations arrive but cards have no values | snapshot/UI projection gap | continue to Phase 2 |
| No observations arrive with all permissions granted | hardware/runtime integration issue | stop and write a narrower root-cause task with the exact device log evidence |

## Phase 2: Truthful Telemetry Projection (only if Phase 1 proves a UI gap)

### Data contract

Add an optional, display-safe latest-reading field to the sensor-health model
or an adjacent immutable value object owned by `ProtectionSnapshot`.

```kotlin
data class SensorReadingSummary(
    val value: Double?,
    val unit: String?,
    val label: String,
)
```

The snapshot may contain summaries for vibration, light, and power only.
For microphone and location use a non-numeric label generated by the runtime;
never persist or render raw audio, audio amplitudes as calibrated dB, location
coordinates, bot tokens, pairing codes, or TOTP data.

### TDD sequence

1. Add a focused failing coordinator test proving that a valid vibration
   observation updates health, timestamp, and a `m/s^2`-labelled latest value.
2. Add a focused failing test proving a light observation updates a `lux`
   latest value.
3. Run the focused tests and record RED caused by the missing projection.
4. Implement the smallest immutable snapshot update in
   `recordSensorSample(...)`; retain existing battery behavior unchanged.
5. Add/update an Android Compose test showing an armed snapshot with a sensor
   summary displays health, last sample, and the correctly labelled value.
6. Add a Compose test showing a disarmed snapshot with no samples displays
   `Live samples begin after arming`, not `Unavailable`.
7. Implement the smallest `ProtectionScreen` rendering change to make both
   tests GREEN.

## Automated Verification

Run sequentially from `D:\security\MotorcycleAntiTheftSensor`:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_OPTS='-Xmx192m -XX:+UseSerialGC -XX:MaxMetaspaceSize=320m'
$env:JAVA_TOOL_OPTIONS='-Xms16m -Xmx96m -XX:+UseSerialGC -XX:MaxMetaspaceSize=256m'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*ProtectionCoordinatorTest' --tests '*AndroidProtectionRuntimeTest'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:connectedDebugAndroidTest
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest :app:assembleDebug
git diff --check
```

Do not claim the defect fixed if the Gradle wrapper download, build, or device
test fails before its relevant test task runs.

## Device Acceptance

- Telegram stays connected after sensor work; this task must not change its
  transport code.
- `SEND_SMS` remains revoked and `SEND_SMS: deny`; no sensor check may send
  SMS or initiate an emergency call.
- From the armed flow, the vibration/light/battery cards show current,
  truthfully-labelled evidence. Microphone and location remain privacy-safe.
- Disarm stops live detector collection; the UI must not claim values are
  current after the freshness window expires.

## Outstanding Work to Preserve

1. `TASK-TG-001-telegram-verification-reconciliation.md`: the Telegram
   callback migration is still pending automated verification, even though a
   live connection was reported. Do not overwrite its dirty files.
2. Authenticator QR checkpoint `CKP-20260811-2357-YD5EKY`: requires scoped
   review of `3ace729..3322c36`, then fresh build/install, SMS-deny, and two-
   phone QR/TOTP acceptance.
3. UI Task 5 checkpoint `CKP-20260809-0955-4I1V0U`: partial uncommitted
   ViewModel/UI-state work remains; it requires GREEN unit tests, Compose RED
   tests, and the shell/UI completion described in its task brief.

## Definition of Done

- [ ] Device evidence identifies the correct failing boundary.
- [ ] The Protection screen distinguishes disarmed/no-listener from unavailable
      hardware and genuine failure.
- [ ] Armed available sensors show fresh health/timestamps and safe values.
- [ ] No secret, precise location, raw audio, false dB claim, SMS, or call is
      introduced.
- [ ] Focused unit tests, Compose tests, and debug build have fresh evidence.
- [ ] Manual device acceptance is recorded without sensitive data.
- [ ] Only exact task files are staged; unrelated dirty work is preserved.
