# Three Protection Profiles Task 1 Checkpoint — 2026-08-22

## Resume point

- Worktree: `D:\security\.worktrees\continuity-recovery-tdd`
- Branch: `codex/continuity-recovery-tdd`
- Approved design/plan commit: `324cfa4`
- Task 1 implementation commit: `0adff63`
- Next task: Task 2, persist the complete profile aggregate atomically

## Completed

- Added the typed `VEHICLE`, `ENTRY`, and `POWER` profile aggregate.
- Added field-level sensor and profile-specific overrides so editing one profile does not mutate another profile.
- Added recommended profile resolution:
  - Vehicle retains the current Balanced sensor policy as the compatibility baseline.
  - Entry uses rotation evidence as primary and movement/light evidence as supporting.
  - Power uses ambient light as primary witness evidence and keeps motion/orientation off by default.
- Added profile-specific range validation and validation of the resolved sensor configuration through the existing `SensorConfigurationPolicy`.
- Added restore-recommended behavior scoped to one profile.
- Updated Task 1 in the implementation plan to match the tested field-level override model.
- Preserved the completed Direct Boot implementation unchanged.

## Verification evidence

All Gradle verification used one worker and no daemon because this machine has limited RAM.

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\ASUS\AppData\Local\Android\Sdk'
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*ProtectionProfilePolicyTest' --tests '*SensorConfigurationPolicyTest'
```

- Exit code: `0`
- `ProtectionProfilePolicyTest`: 10 tests, 0 failures, 0 errors, 0 skipped.
- `SensorConfigurationPolicyTest`: 7 tests, 0 failures, 0 errors, 0 skipped.
- `git diff 324cfa4..0adff63 --check`: no errors.
- Worktree was clean immediately after commit `0adff63`.
- A static diff review against the approved Task 1 requirements found no blocking issue. The delegated read-only reviewer did not return before the checkpoint deadline, so no independent-review claim is made.

## Decisions

- This checkpoint stops after Task 1. Task 2 has not started.
- Do not expose a profile picker from disconnected UI-only state. Persistence, immutable armed snapshots, Arm-time freezing, and recoverable profile switching are implemented before the picker is wired.
- Entry and Power remain `SETUP_REQUIRED` until their dedicated detector work is implemented and accepted.
- Do not alter or re-run the already accepted Direct Boot flow as part of Task 1.
- Do not use this worktree's `hcp.cmd`; it targets `D:\security` and can affect the unrelated main worktree. This project-local Markdown file is the authoritative continuation record.

## Current user-visible state

- No app screen changes are expected from Task 1.
- The installed app will still look the same because the visible picker is Task 7.
- No APK was built or installed for this domain-only checkpoint.

## Pending

1. Task 2: versioned atomic profile persistence and legacy-configuration preservation.
2. Task 3: immutable armed-profile snapshots and stable configuration fingerprints.
3. Task 4: freeze the resolved profile configuration at Arm.
4. Task 5: recoverable, durable profile switching.
5. Task 6: runtime graph and recovery wiring.
6. Task 7: visible three-profile picker with truthful setup blocking.
7. Task 8: focused/full host tests, APK build/install, Huawei UI acceptance, and a separate Direct Boot regression gate.

## Risks and boundaries

- Task 1 proves only the pure profile domain and resolver. It does not prove persistence, runtime wiring, recovery, UI, APK, or device behavior.
- Vehicle uses the current Balanced preset intentionally for compatibility in this foundation phase; later profile-specific detector work must not silently change the running armed snapshot.
- Entry and Power must not be armed or described as detector-ready before their dedicated implementation plans pass.
- The Android SDK XML version warning remains a toolchain warning; it did not fail these tests.

## Exact resume commands

```powershell
Set-Location D:\security\.worktrees\continuity-recovery-tdd
git status --short
git log -3 --oneline
Get-Content -Raw .\plans\checkpoint-2026-08-22-three-profiles-task-1.md

Set-Location .\MotorcycleAntiTheftSensor
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\ASUS\AppData\Local\Android\Sdk'
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*ProtectionProfilePolicyTest' --tests '*SensorConfigurationPolicyTest'
```

Then resume at Task 2 in:

`MotorcycleAntiTheftSensor/docs/superpowers/plans/2026-08-22-three-protection-profiles-foundation.md`
