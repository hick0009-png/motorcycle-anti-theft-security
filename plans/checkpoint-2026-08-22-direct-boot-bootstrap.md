# Direct Boot Bootstrap Checkpoint — 2026-08-22

## Resume point

- Worktree: `D:\security\.worktrees\continuity-recovery-tdd`
- Branch: `codex/continuity-recovery-tdd`
- Direct Boot implementation commit: `dcbf397`
- Installed package: `com.example.motorcycleantitheftsensor` on Huawei INE-LX2 (Android 9)

## Completed

- Added a device-protected recovery marker containing only `armed` and `autoRecoveryAfterBoot`.
- Added a direct-boot-aware `LOCKED_BOOT_COMPLETED` receiver path and a local-only foreground bootstrap service.
- The bootstrap service observes accelerometer deviation only; it does not build the normal runtime graph, read encrypted preferences, send Telegram/SMS, or persist incidents.
- Added a `UserManager.isUserUnlocked` polling handoff because Huawei did not reliably deliver the expected `USER_UNLOCKED` receiver path on the no-PIN device.
- When storage becomes available, bootstrap starts the normal `SensorService` with `ANDROID_USER_UNLOCKED` recovery trigger and stops itself.
- Marker is written after the normal recovery snapshot succeeds. A failed marker write is logged and fails safe by preventing the next locked-boot bootstrap.

## Verification evidence

- RED: `DirectBootBootstrapPolicyTest` failed as expected before the policy existed.
- GREEN: `testDebugUnitTest --tests '*DirectBootBootstrapPolicyTest'` passed after implementation.
- Full host suite: `testDebugUnitTest` passed after the handoff change.
- APK build: `assembleDebug` passed.
- Device: debug APK installed on Huawei INE-LX2. With `armed=true` and `auto_recovery_after_boot=true` confirmed in device-protected storage, a reboot after Huawei Manual Launch was enabled returned a running foreground `SensorService`; `DirectBootBootstrapService` was no longer running.

## Decisions

- Do not migrate Telegram, TOTP, SMS keys, chat IDs, or encrypted incident data to device-protected storage.
- Do not claim that a no-PIN reboot proves the pre-unlock local notification path: credential storage becomes available immediately on that device.
- Keep Huawei Manual Launch enabled: Auto-launch, Secondary launch, and Run in background. Without it, reboot recovery was inconsistent even with a valid marker and manifest receiver.
- Do not use this worktree's `hcp.cmd` for this checkpoint: it targets `D:\security` and may auto-commit unrelated main-worktree state. This project-local file is the authoritative continuation record for this branch.

## Remaining acceptance / risks

- Test with a temporary PIN/Pattern on a sacrificial device to verify the true locked-before-unlock window: local bootstrap notification may appear, but no Telegram/SMS must be sent until unlock.
- Verify Telegram exact message count on a controlled reboot after unlock; current device evidence proves service recovery, not outbound-message counting.
- The device-protected marker is intentionally not encrypted. It contains only two booleans, but it is not transactionally atomic with credential-protected snapshot storage; a marker-write failure fails closed.
- Do not merge this branch into `main` until the requested next work is complete and reviewed.

## Exact resume commands

```powershell
Set-Location D:\security\.worktrees\continuity-recovery-tdd
git status --short
Set-Location .\MotorcycleAntiTheftSensor
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\ASUS\AppData\Local\Android\Sdk'
.\gradlew.bat testDebugUnitTest assembleDebug
& 'C:\Users\ASUS\AppData\Local\Android\Sdk\platform-tools\adb.exe' devices -l
```
