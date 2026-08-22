# Three Protection Profiles Task 2 Checkpoint — 2026-08-22

## Resume point

- Worktree: `D:\security\.worktrees\continuity-recovery-tdd`
- Branch: `codex/continuity-recovery-tdd`
- Approved design/plan commit: `324cfa4`
- Task 1 implementation commit: `0adff63`
- Task 2 implementation commit: `1bbf298`
- Next task: Task 3, immutable armed-profile snapshots and stable configuration fingerprints

## Completed

- Added `ProtectionProfileCodec`: one versioned JSON aggregate (schema version 1) for the whole
  `ProtectionProfileStoreState`.
  - Profiles are written in fixed canonical enum order (`VEHICLE`, `ENTRY`, `POWER`).
  - Nested legacy sensor configuration reuses the existing `SensorConfigurationCodec`.
  - Decoding rejects future schema versions, missing profile entries, duplicate entries,
    invalid enum values, and invalid profile-specific ranges instead of guessing.
- Added `ProtectionProfileRepository` / `SharedPreferencesProtectionProfileRepository`:
  - `load()`, `save(state)`, and `update(transform)`; this repository is now the only
    intended profile-store writer for later tasks.
  - One credential-protected preference key `protection_profile_state_json` with a single
    `SharedPreferences.Editor.commit()` boundary per aggregate write.
  - First load with no aggregate returns `newStoreState(legacyRepository.loadConfiguration())`;
    it does not write or select a profile until the owner acts.
  - Corrupt or future-schema stored aggregates fall back to the legacy-preserving initial state.
- Marked Task 2 Steps 1–5 complete in the implementation plan.

## Verification evidence

All Gradle verification used one worker and no daemon because this machine has limited RAM.

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\ASUS\AppData\Local\Android\Sdk'
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*ProtectionProfileCodecTest' --tests '*ProtectionProfileRepositoryTest'
```

- RED confirmed first: compilation failed with unresolved `ProtectionProfileCodec` /
  `SharedPreferencesProtectionProfileRepository` before implementation.
- GREEN after implementation: `BUILD SUCCESSFUL`, 14 tests total
  (`ProtectionProfileCodecTest` 9 tests + `ProtectionProfileRepositoryTest` 5 tests),
  0 failures.
- Round-trip coverage includes: null selection, selected profile + field-level sensor
  overrides + Entry/Power specific overrides, legacy `MAXIMUM_PROTECTION` configuration,
  canonical encode order, atomic failure keeping the previous aggregate, and corrupt-JSON fallback.
- `git diff --cached --check` before commit: clean. Worktree clean after commit `1bbf298`.

## Decisions

- Decode is strict (throws) while repository load is forgiving (falls back to
  `newStoreState(legacy)`); this keeps corruption visible at the codec layer without ever
  losing the customer's legacy sensor configuration at the storage layer.
- The nested legacy configuration is stored as an embedded JSON object and re-serialized to a
  string only at the `SensorConfigurationCodec` boundary.
- No UI, runtime graph, or Direct Boot behavior was touched in Task 2.
- Do not use this worktree's `hcp.cmd`; it targets `D:\security`. This project-local Markdown
  file remains the authoritative continuation record.

## Current user-visible state

- No app screen changes are expected from Task 2. Persistence is domain/storage-only;
  nothing reads the new repository yet outside tests.

## Pending

1. Task 3: immutable armed-profile snapshots and stable configuration fingerprints.
2. Task 4: freeze the resolved profile configuration at Arm.
3. Task 5: recoverable, durable profile switching.
4. Task 6: runtime graph and recovery wiring.
5. Task 7: visible three-profile picker with truthful setup blocking.
6. Task 8: focused/full host tests, APK build/install, Huawei UI acceptance, and a separate
   Direct Boot regression gate.

## Risks and boundaries

- Task 2 proves persistence only. It does not prove runtime wiring, arming behavior, recovery,
  UI, APK, or device behavior.
- Existing customers remain in the explicit legacy-unselected state until they choose a profile;
  no migration writes happen on first load by design.
- The Android SDK XML version warning remains a toolchain warning; it did not fail these tests.

## Exact resume commands

```powershell
Set-Location D:\security\.worktrees\continuity-recovery-tdd
git status --short
git log -3 --oneline
Get-Content -Raw .\plans\checkpoint-2026-08-22-three-profiles-task-2.md

Set-Location .\MotorcycleAntiTheftSensor
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\ASUS\AppData\Local\Android\Sdk'
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*ProtectionProfileCodecTest' --tests '*ProtectionProfileRepositoryTest'
```

Then resume at Task 3 in:

`MotorcycleAntiTheftSensor/docs/superpowers/plans/2026-08-22-three-protection-profiles-foundation.md`
