# Power Guard restart recovery checkpoint (2026-08-30)

## Resume point

- Worktree: `D:\security\.worktrees\continuity-recovery-tdd\MotorcycleAntiTheftSensor`
- Branch: `codex/continuity-recovery-tdd`
- HEAD: `716ada439eb14d6e864c72fa6a1c48b3b28ee34f` (`Record project cleanup closeout`)
- State: implementation and final verification complete on 2026-08-31; relevant changes remain uncommitted in a pre-existing dirty worktree.

## Commits

| Commit | Description |
|---|---|
| None | This task did not commit, merge, or revert any worktree changes. |

## Completed behavior

- Restore the newest persisted open POWER incident only when continuity still requests an armed POWER profile.
- Seed the resumed Power arbiter with the last owner-visible semantic condition, suppressing the same opening after process restart.
- Preserve the commissioned witness model during resume instead of recalibrating from a possibly dark lamp.
- Close the restored incident once charging and witness light remain healthy for the configured 30-second recovery window; the recovery evidence formats as `ไฟเลี้ยงที่จุดเฝ้าระวังกลับมาคงที่แล้ว`.
- Discard pending restoration on controlled session clear so a later fresh Arm cannot consume stale state.
- Freeze the per-Arm witness-placement confirmation in `PowerArmedCalibrationSnapshot`; recovery reuses it only for that armed session, while a later fresh Arm still requires an owner confirmation.
- Decode older armed snapshots without the confirmation field as `false` (fail-safe) instead of silently treating them as confirmed.

## Verification evidence

- Focused regression gate: `PowerRuntimeRestorationTest`, `PowerArmedSessionControllerTest`, and `IncidentEnginePowerTest` passed.
- Full host gate: `:app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug --rerun-tasks --no-daemon --max-workers=1` exited 0.
- Unit-test XML: 113 suites, 893 tests, 0 failures, 0 errors, 0 skipped.
- `git diff --check` exited 0; only existing LF-to-CRLF notices were printed.
- APK: `app\build\outputs\apk\debug\app-debug.apk`, 68,584,875 bytes, built 2026-08-31 05:10:51 +07:00.
- Device `JUCDU18811013149` (Huawei INE-LX2): final `adb install -r` succeeded; package update time 2026-08-31 05:11:24; MainActivity launched with status `ok`; process PID 22747 remained alive; no matching FATAL/ANR errors; UI rendered disarmed, charging, 39 lux, and witness light not detected.

## Decisions and risks

- No UI code was changed for this fix.
- Physical lamp off/on plus Telegram delivery was not triggered automatically because that would create a real external alert. The restart/recovery path is covered by deterministic regression tests; final physical acceptance remains an owner-operated check.
- The device's persisted armed snapshot predates the new confirmation field and therefore fails safe. The owner must perform one fresh off/on confirmation and Arm once; subsequent process restarts preserve that armed session's confirmation.
- The full worktree contains pre-existing related modifications. Do not reset or revert them when resuming.
- Baseline warnings remain: SDK XML version mismatch and an unnecessary safe-call warning in `AndroidProtectionRuntime.kt`.

## Battery cable status follow-up (2026-08-31)

- Fixed `/status` so it reports the physical cable fact explicitly: plugged and charging, plugged and full, unplugged, or unknown.
- `PowerThermalMonitor.resolveChargingState` now treats `BatteryManager.EXTRA_PLUGGED > 0` as authoritative for cable presence. A stale/raw `BATTERY_STATUS_FULL` with `plugged = 0` maps to `NOT_CHARGING` instead of `FULL`.
- Telegram examples are now `🔌 สายชาร์จ: เสียบอยู่ | แบตเตอรี่เต็ม` and `🔌 สายชาร์จ: ไม่ได้เสียบ`.
- TDD RED evidence: the focused suite failed 6 tests against the previous implementation, including `resolveChargingState_unpluggedFull_returnsNotCharging` and status text expectations.
- TDD GREEN evidence: the same focused `PowerThermalMonitorTest`, `ProtectionStatusProjectionTest`, and `ProtectionStatusFormatterTest` suite passed after the minimal implementation.
- Fresh full gate passed: `:app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug --rerun-tasks --no-daemon --max-workers=1`; 113 suites, 893 tests, 0 failures, 0 errors, 0 skipped.
- `git diff --check` passed for the five files in this slice; only existing LF-to-CRLF notices were emitted.
- APK was rebuilt at 2026-08-31 05:30:21 +07:00 (68,584,875 bytes), installed successfully on Huawei `JUCDU18811013149`, and MainActivity launched with `Status: ok`. PID 27378 remained alive and no AndroidRuntime error was present in the sampled log.
- Live device battery evidence matched the target case: `USB powered: true`, `status: 5` (`FULL`), `level: 100`.
- A real Telegram `/status` message was not triggered automatically to avoid sending an external message; owner acceptance is to send `/status` once and confirm the new cable line.

## Exact resume commands

```powershell
Set-Location 'D:\security\.worktrees\continuity-recovery-tdd\MotorcycleAntiTheftSensor'
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\ASUS\AppData\Local\Android\Sdk'
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug --rerun-tasks --no-daemon --max-workers=1
& 'C:\Users\ASUS\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s JUCDU18811013149 install -r 'app\build\outputs\apk\debug\app-debug.apk'
```

## Witness recovery guard-band follow-up (2026-08-31)

- Power recovery now requires 10 seconds of established `HEALTHY_DUAL` state instead of 30 seconds.
- An ambiguous guard-band lux reading preserves an already-established recovery window only while charging remains connected; charging loss, dark, stale, or unknown evidence resets/does not confirm recovery.
- An elapsed/ambiguous confirmation deadline is no longer reposted at zero delay, preventing a Handler busy loop while waiting for the next conclusive light sample.
- Persisted Power overrides using the former 30-second contract migrate to 10 seconds while preserving profile selection and the commissioned witness model; new policy updates accept only 10 seconds.
- TDD RED: the first focused run reported 5 expected failures across arbiter timing, guard-band, policy, and codec behavior.
- Focused GREEN passed for `PowerCompositeArbiterTest`, `PowerArmedSessionControllerTest`, `ProtectionProfilePolicyTest`, `ProtectionProfileCodecTest`, `PowerIncidentFormatterTest`, and `AndroidProtectionRuntimeTest`.
- Fresh full gate passed: `:app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug --rerun-tasks --no-daemon --max-workers=1`; XML reports 113 suites, 905 tests, 0 failures, 0 errors, 0 skipped.
- APK `app\build\outputs\apk\debug\app-debug.apk` (68,584,875 bytes) installed successfully on Huawei `JUCDU18811013149`; MainActivity launched as PID 9613 and was the resumed activity with no matching FATAL/ANR in the sampled log.
- Physical lamp off/on and real Telegram delivery remain owner acceptance steps; they were not triggered automatically.
- No commit, merge, or unrelated cleanup was performed; the pre-existing dirty worktree remains intact.

## Power alert ownership and localized delivery follow-up (2026-08-31)

- `AndroidProtectionRuntime` now suppresses raw `charger_disconnected` observations only while an armed POWER session is active. The raw callback remains health telemetry; only typed `PowerCompositeArbiter` verdicts can enter the POWER incident pipeline.
- Vehicle and Entry sessions retain the legacy raw disconnect incident path and now receive the explicit owner copy `ตรวจพบว่าสายชาร์จถูกถอดออก`.
- Telegram, SMS, progress, continuation, event guidance, and fallback incident copy use centralized Thai incident labels and no longer render `IncidentType.name`, severity enum text, or raw evidence diagnostics.
- Regression coverage connects detector observation -> runtime -> incident engine -> delivery coordinator -> Telegram transport. It proves no immediate POWER message from the raw disconnect, exactly one localized message after the typed verdict even when repeated, and a localized disconnect message outside POWER.
- Legacy open POWER incidents containing only `charger_disconnected` remain in history but are not restored as Power arbiter state after process restart.
- TDD RED: the new focused suite initially reported 7 failures for raw POWER ownership and leaked copy, followed by 2 additional failures for progress/guidance leakage.
- GREEN focused suites passed after the minimal routing and presentation changes.
- Fresh full host gate passed with exit code 0: `:app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug --rerun-tasks --no-daemon --max-workers=1`; XML reports 113 suites, 898 tests, 0 failures, 0 errors, 0 skipped.
- Debug APK rebuilt at `app\build\outputs\apk\debug\app-debug.apk`. Installation was not attempted because ADB reported device `JUCDU18811013149` as `unauthorized`; accept the USB-debugging prompt before installing.

## Last-known witness guard-band fallback (2026-09-01)

- Guard-band samples now use an explicit `lastConclusiveWitnessLit` value instead of inferring witness state from `currentSemantic`.
- The fallback is valid only inside uninterrupted evidence continuity. Stale/unknown samples, sensor-generation changes, dedicated light-listener unregister/re-register, and listener registration failures invalidate it.
- With a valid lit baseline, a cable disconnect plus guard-band lux becomes `CHARGING_LOST` and emits one `ChargingHealthAlert` after the 10-second loss confirmation.
- With a valid dark baseline, the same cable disconnect becomes `DUAL_LOST` and can open the confirmed Power-loss incident after 10 seconds.
- Without a valid witness baseline, a definite cable disconnect still emits only `ChargingHealthAlert` after 10 seconds; it cannot produce `ConfirmedLossOpened`.
- A guard-band sample preserves recovery only when the cached witness is valid/lit and charging remains connected. Guard-band lux is excluded from adaptive Arm-reference tuning.
- Cached lux is stored atomically with the sensor generation that delivered it. Pre-generation and pre-listener-gap values cannot seed a later session.
- The approved safety amendments were recorded in `D:\security\work\tasks\power-guard-witness-recovery-fix.md`.
- TDD RED: the arbiter/controller suite reported 7 expected failures for fallback, stale/generation invalidation, cable-only alerting, escalation, and adaptive-reference protection. Runtime generation freshness then reported 1 expected failure, and listener-continuity clearing reported 1 expected failure.
- Focused GREEN passed for `PowerCompositeArbiterTest`, `PowerArmedSessionControllerTest`, and `AndroidProtectionRuntimeTest` after each fix.
- Independent review found one Important listener-continuity cache issue; the scoped re-review marked it `ADDRESSED` with no new breakage.
- Fresh full host gate exited 0: `:app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug --rerun-tasks --no-daemon --max-workers=1`; XML reports 113 suites, 913 tests, 0 failures, 0 errors, 0 skipped.
- `git diff --check` found no whitespace errors in the six touched implementation/test files; existing LF-to-CRLF notices and the repository fsmonitor `daemon terminated` notice remain non-blocking.
- APK: `app\build\outputs\apk\debug\app-debug.apk`, 68,584,875 bytes, SHA-256 `7DBAAEC93D3C649BA3FB501E340EA3FBB11AA38EF71809B90525A67324F9B946`, built 2026-09-01 05:23:53 +07:00.
- Device `JUCDU18811013149` (Huawei INE-LX2): `adb install -r` succeeded; package update time 2026-09-01 05:25:09; MainActivity launched with status `ok`; PID 12790 was alive and resumed; sampled process log contained no FATAL/ANR match.
- Physical cable removal, lamp-state manipulation, and real Telegram delivery were not triggered automatically because they affect real hardware/external messaging. They remain owner acceptance steps.
- No commit, merge, revert, or unrelated cleanup was performed; the pre-existing dirty worktree remains intact.
