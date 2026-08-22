# Three Protection Profiles Task 4 Checkpoint — 2026-08-22

## Resume point

- Worktree: `D:\security\.worktrees\continuity-recovery-tdd`
- Branch: `codex/continuity-recovery-tdd`
- Approved design/plan commit: `324cfa4`
- Task 1 implementation commit: `0adff63`
- Task 2 implementation commit: `1bbf298`
- Task 3 implementation commit: `0655d22`
- Task 4 implementation commit: `f8ed4f7`
- Next task: Task 5, recoverable durable profile switching

## Completed

- `ProtectionRuntime` gained the frozen-configuration contract:
  `startDetectors(armedSessionId, configuration)` with a default delegation to
  `startDetectors(armedSessionId)` so existing runtimes stay source-compatible.
- `ProtectionCoordinator` now owns the Arm freeze boundary (all under `commandMutex`):
  - Loads the profile store once per Arm. If a profile is selected:
    - Rejects Arm with `SETUP_REQUIRED` when the resolved profile is not `READY`.
    - Builds an `ArmedProfileSnapshot` (session id, profile, preset version, frozen
      effective configuration, SHA-256 fingerprint, vehicle calibration generation).
    - Persists it durably **before** detector start; persistence failure rejects Arm and
      remains disarmed.
    - Starts detectors from `armedProfileSnapshot.effectiveConfiguration`; startup failure
      durably clears the frozen snapshot and remains disarmed.
    - Publishes the frozen snapshot with the final armed state so the durable armed write
      carries the authoritative policy.
  - Legacy customers (`selectedProfile == null`) keep the previous behavior and arm without
    an armed-profile snapshot.
  - Every cancellation/superseded/primary-failure path after the pre-start freeze durably
    clears the orphaned snapshot (best effort, records persistence failure on error).
- `disarm()` clears `armedProfileSnapshot` before the durable disarmed write.
- Added coordinator commands:
  - `selectProfile(commandId, profile)` — persists selection; while disarmed applies the
    resolved configuration to the editable runtime; while armed returns "saved for next Arm".
  - `updateSelectedProfile(commandId) { state -> StoredProfileConfiguration }` — persists the
    edit for the selected profile only; never calls `applySensorConfiguration` while armed;
    rejects edits to a non-selected profile.
- Marked Task 4 Steps 1–5 complete in the implementation plan.

## Verification evidence

All Gradle verification used one worker and no daemon because this machine has limited RAM.

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\ASUS\AppData\Local\Android\Sdk'
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*ProtectionCoordinatorTest' --tests '*SensorCapabilityControllerTest'
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest
```

- RED confirmed first: unresolved `updateSelectedProfile`, missing `profileRepository`
  constructor parameter, and no frozen-config `startDetectors` overload.
- GREEN after implementation: focused run `BUILD SUCCESSFUL` — 58 tests completed, 0 failed
  (coordinator suite grew by 4 new tests: freeze-before-start, settings-edit-while-armed,
  settings-edit-while-disarmed, legacy-unselected compatibility).
- Full host suite `testDebugUnitTest`: `BUILD SUCCESSFUL` — no regression from the runtime
  interface change.
- One test-only fix during GREEN: the two equality assertions needed the same fixed-clock
  `ProtectionProfilePolicy` injected into the coordinator so `updatedAtMs` matched.
- `git diff --cached --check` before commit: clean. Worktree clean after commit `f8ed4f7`.

## Decisions

- The pre-start durable persist uses `durableSnapshotWriter(snapshot.copy(armedProfileSnapshot))`;
  continuity-intent wiring stays in Task 6 where the runtime graph is wired.
- `AndroidProtectionRuntime` currently inherits the default delegation of
  `startDetectors(sessionId, configuration)` → `startDetectors(sessionId)`; consuming the
  frozen configuration inside the Android runtime is deliberately deferred to Task 6
  (runtime graph wiring). No production detector behavior changed in this slice.
- Entry/Power remain blocked at Arm via their `SETUP_REQUIRED` setup state until their
  dedicated detector plans land.
- Do not use this worktree's `hcp.cmd`; it targets `D:\security`. This project-local Markdown
  file remains the authoritative continuation record.

## Current user-visible state

- No UI changes yet. Arming behavior is unchanged for existing customers; a selected profile
  now freezes at Arm, but nothing in the app selects a profile yet (picker is Task 7).

## Pending

1. Task 5: recoverable, durable profile switching (STOP_REQUESTED transaction phases).
2. Task 6: runtime graph and recovery wiring (including Android runtime consuming the frozen
   configuration and continuity intent carrying the armed snapshot).
3. Task 7: visible three-profile picker with truthful setup blocking.
4. Task 8: focused/full host tests, APK build/install, Huawei UI acceptance, and a separate
   Direct Boot regression gate.

## Risks and boundaries

- Freeze semantics are proven at the coordinator level with fakes only; device-level proof
  arrives in Task 8 acceptance.
- If arming is cancelled after the pre-start durable persist but the durable clear itself
  fails, a stale armed snapshot could survive until the next authoritative write; this is
  recorded as a persistence degradation rather than silently ignored.

## Exact resume commands

```powershell
Set-Location D:\security\.worktrees\continuity-recovery-tdd
git status --short
git log -5 --oneline
Get-Content -Raw .\plans\checkpoint-2026-08-22-three-profiles-task-4.md

Set-Location .\MotorcycleAntiTheftSensor
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\ASUS\AppData\Local\Android\Sdk'
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*ProtectionCoordinatorTest'
```

Then resume at Task 5 in:

`MotorcycleAntiTheftSensor/docs/superpowers/plans/2026-08-22-three-protection-profiles-foundation.md`
