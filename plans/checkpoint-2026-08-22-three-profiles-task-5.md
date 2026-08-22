# Three Protection Profiles Task 5 Checkpoint — 2026-08-22

## Resume point

- Worktree: `D:\security\.worktrees\continuity-recovery-tdd`
- Branch: `codex/continuity-recovery-tdd`
- Approved design/plan commit: `324cfa4`
- Task 1 implementation commit: `0adff63`
- Task 2 implementation commit: `1bbf298`
- Task 3 implementation commit: `0655d22`
- Task 4 implementation commit: `f8ed4f7`
- Task 5 implementation commit: `c11699a`
- Next task: Task 6, wire the shared runtime graph and recovery store

## Completed

- `ProtectionProfileModels.kt`: added `ProfileSwitchPhase`
  (`STOP_REQUESTED → OLD_RUNTIME_QUIESCED → SNAPSHOT_CLEARED → NEW_PROFILE_SELECTED`),
  `ProfileSwitchTransaction`, and `switchTransaction` on `ProtectionProfileStoreState`.
- Created `ProfileSwitchPolicy.kt`: pure convergence machine — every persisted phase
  resumes to disarmed with the target profile selected, armed snapshot cleared, and no
  transaction left; resume is idempotent and phases advance in durable order.
- `ProtectionProfileCodec.kt`: the switch transaction is now part of the versioned atomic
  aggregate (strict decode of transaction id/target profile/phase).
- `ProtectionCoordinator.changeProfile(commandId, targetProfile, confirmed)`:
  - Unconfirmed requests change nothing.
  - Phase 1 persists `STOP_REQUESTED` BEFORE invalidating recovery or stopping detectors
    (verified by an ordered event log in tests).
  - Runtime effects then run as a durable owner stop: recovery invalidated, detectors
    stopped, incident history closed as owner-stopped, state to `DISARMED_ONLINE`,
    armed snapshot cleared, durable write.
  - Phases 2–3 persist quiesced/snapshot-cleared; phase 4 selects the target, applies its
    resolved configuration to the editable runtime, clears the transaction, and never arms.
- `ProtectionCoordinator.resumeProfileSwitchIfNeeded()`: converges any interrupted
  transaction at startup/recovery toward disarmed/selected-target; returns false when
  nothing is pending.
- Marked Task 5 Steps 1–5 complete in the implementation plan.

## Verification evidence

All Gradle verification used one worker and no daemon because this machine has limited RAM.

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\ASUS\AppData\Local\Android\Sdk'
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*ProfileSwitchPolicyTest' --tests '*ProtectionCoordinatorTest' --tests '*ProtectionRecoveryPolicyTest' --tests '*ProtectionProfileCodecTest'
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest
```

- RED confirmed first: unresolved switch types/APIs across both new and existing tests.
- GREEN after implementation: focused run `BUILD SUCCESSFUL` — 73 tests completed, 0 failed
  (new: ProfileSwitchPolicyTest 3 tests + coordinator switch tests 4 tests).
- Full host suite `testDebugUnitTest`: `BUILD SUCCESSFUL` — no regression.
- One fix during GREEN: the in-memory fake repository's `update()` now mirrors the real
  repository by persisting through `save()` so the ordering log observes every durable phase.
- Ordering proof: `save:STOP_REQUESTED` appears strictly before the first `stop` event.
- `git diff --cached --check` before commit: clean. Worktree clean after commit `c11699a`.

## Decisions

- The plan's `ProtectionRuntimeGraph.kt` modification is deliberately deferred to Task 6:
  the graph does not yet construct the coordinator with a profile repository, so there is
  nothing meaningful to wire until Task 6 injects it and calls
  `resumeProfileSwitchIfNeeded()` during recovery. No graph code was touched in this slice.
- Live Map stop remains the existing best-effort behavior inside the owner-stop path;
  the dedicated old-generation fencing for late location updates lands with Task 6 wiring.
- Do not use this worktree's `hcp.cmd`; it targets `D:\security`. This project-local Markdown
  file remains the authoritative continuation record.

## Current user-visible state

- No UI changes yet. Switching exists as coordinator commands only; the confirmation sheet
  and visible picker arrive with Task 7.

## Pending

1. Task 6: runtime graph and recovery wiring (inject profile repository into the production
   coordinator, call `resumeProfileSwitchIfNeeded()` during recovery, Android runtime
   consuming the frozen configuration).
2. Task 7: visible three-profile picker with truthful setup blocking.
3. Task 8: focused/full host tests, APK build/install, Huawei UI acceptance, and a separate
   Direct Boot regression gate.

## Risks and boundaries

- Crash-convergence is proven at the policy/coordinator level with fakes; process-death
  proof on device arrives with Task 8 acceptance.
- If the final selection persist fails mid-switch, the command returns UNKNOWN and the
  transaction stays persisted; convergence happens on the next resume call, which Task 6
  wires into recovery.

## Exact resume commands

```powershell
Set-Location D:\security\.worktrees\continuity-recovery-tdd
git status --short
git log -6 --oneline
Get-Content -Raw .\plans\checkpoint-2026-08-22-three-profiles-task-5.md

Set-Location .\MotorcycleAntiTheftSensor
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\ASUS\AppData\Local\Android\Sdk'
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*ProfileSwitchPolicyTest' --tests '*ProtectionCoordinatorTest'
```

Then resume at Task 6 in:

`MotorcycleAntiTheftSensor/docs/superpowers/plans/2026-08-22-three-protection-profiles-foundation.md`
