# Three Protection Profiles Task 3 Checkpoint — 2026-08-22

## Resume point

- Worktree: `D:\security\.worktrees\continuity-recovery-tdd`
- Branch: `codex/continuity-recovery-tdd`
- Approved design/plan commit: `324cfa4`
- Task 1 implementation commit: `0adff63`
- Task 2 implementation commit: `1bbf298`
- Task 3 implementation commit: `0655d22`
- Next task: Task 4, freeze the resolved profile configuration at Arm

## Completed

- Added immutable armed-session types to `ProtectionProfileModels.kt`:
  - `ArmedCalibrationSnapshot` sealed interface with `VehicleArmedCalibrationSnapshot`,
    `EntryArmedCalibrationSnapshot` (requires `modelFingerprint`), and
    `PowerArmedCalibrationSnapshot` (requires `modelFingerprint`).
  - `ArmedProfileSnapshot` (session id, profile, preset version, frozen effective
    configuration, configuration fingerprint, optional commissioned-model fingerprint,
    armed calibration snapshot).
- Added `ArmedProfileSnapshotCodec.kt`:
  - `ConfigurationFingerprint.sha256(config, specificSettings)` — SHA-256 over canonical
    codec output plus a deterministic profile-settings string; no object hashCode() and no
    wall-clock values.
  - Strict snapshot codec (schema v1): rejects future schemas, blank session ids or
    fingerprints, invalid enums, calibration kinds that do not match the armed profile,
    and Entry/Power calibrations without a model fingerprint.
- Extended `ProtectionSnapshot` with `armedProfileSnapshot: ArmedProfileSnapshot? = null`.
- Extended `ProtectionSnapshotStore`:
  - The armed snapshot JSON is encoded inside the same atomic `preferences.put(...)` /
    single-commit boundary as owner intent and all other snapshot fields.
  - `loadForRecovery()` decodes the armed snapshot into the offline live snapshot; a corrupt
    payload fails safe to null while continuity intent still governs whether recovery may rearm.
- Marked Task 3 Steps 1–5 complete in the implementation plan.

## Verification evidence

All Gradle verification used one worker and no daemon because this machine has limited RAM.

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\ASUS\AppData\Local\Android\Sdk'
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*ArmedProfileSnapshotCodecTest' --tests '*ProtectionSnapshotStoreTest'
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest
```

- RED confirmed first: compilation failed with unresolved `ArmedProfileSnapshot`,
  `ConfigurationFingerprint`, calibration types, and the new `ProtectionSnapshot` field.
- GREEN after implementation: focused run `BUILD SUCCESSFUL`
  (`ArmedProfileSnapshotCodecTest` 8 tests + `ProtectionSnapshotStoreTest` 9 tests = 17 tests).
- Full host suite `testDebugUnitTest`: `BUILD SUCCESSFUL` — no regression from the
  `ProtectionSnapshot` data-class change.
- Fingerprint coverage: stable for equal canonical configurations, changes when sensor
  configuration or profile-specific settings change.
- Store coverage: armed snapshot round-trips through one atomic save; corrupt stored JSON
  fails safe to null.
- `git diff --cached --check` before commit: clean. Worktree clean after commit `0655d22`.

## Decisions

- Calibration kind must match the armed profile at decode time; a mismatched pair is treated
  as corruption rather than silently reinterpreted.
- Corrupt armed snapshots fail safe to null instead of blocking recovery load; the durable
  continuity intent remains the sole authority on whether an armed recovery may proceed.
- No coordinator/runtime wiring yet — Arm-time freezing is Task 4. Nothing constructs an
  `ArmedProfileSnapshot` outside tests in this slice.
- Do not use this worktree's `hcp.cmd`; it targets `D:\security`. This project-local Markdown
  file remains the authoritative continuation record.

## Current user-visible state

- No app screen or runtime behavior changes are expected from Task 3. This is storage/domain
  groundwork for the coordinator freeze in Task 4.

## Pending

1. Task 4: freeze the resolved profile configuration at Arm.
2. Task 5: recoverable, durable profile switching.
3. Task 6: runtime graph and recovery wiring.
4. Task 7: visible three-profile picker with truthful setup blocking.
5. Task 8: focused/full host tests, APK build/install, Huawei UI acceptance, and a separate
   Direct Boot regression gate.

## Risks and boundaries

- Task 3 proves persistence and fingerprints only. It does not prove that arming actually
  freezes a snapshot, that recovery honors it, or any device behavior.
- Entry/Power model fingerprints are placeholder strings until their dedicated commissioning
  plans define real models.

## Exact resume commands

```powershell
Set-Location D:\security\.worktrees\continuity-recovery-tdd
git status --short
git log -4 --oneline
Get-Content -Raw .\plans\checkpoint-2026-08-22-three-profiles-task-3.md

Set-Location .\MotorcycleAntiTheftSensor
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\ASUS\AppData\Local\Android\Sdk'
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*ArmedProfileSnapshotCodecTest' --tests '*ProtectionSnapshotStoreTest'
```

Then resume at Task 4 in:

`MotorcycleAntiTheftSensor/docs/superpowers/plans/2026-08-22-three-protection-profiles-foundation.md`
