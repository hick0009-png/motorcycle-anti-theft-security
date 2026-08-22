# Three Protection Profiles Task 6 Checkpoint — 2026-08-22

## Resume point

- Worktree: `D:\security\.worktrees\continuity-recovery-tdd`
- Branch: `codex/continuity-recovery-tdd`
- Approved design/plan commit: `324cfa4`
- Task 1 implementation commit: `0adff63`
- Task 2 implementation commit: `1bbf298`
- Task 3 implementation commit: `0655d22`
- Task 4 implementation commit: `f8ed4f7`
- Task 5 implementation commit: `c11699a`
- Task 6 implementation commit: `a7fc2aa`
- Next task: Task 7, expose truthful profile state through ViewModel and Compose

## Completed

- `ProtectionRuntimeGraph.buildGraph(...)`: constructs ONE process-shared
  `SharedPreferencesProtectionProfileRepository` (credential-protected
  `sharedPrefs` + legacy `sensorRepository`), injects it into the single
  `ProtectionCoordinator`, and exposes it on `Graph.profileRepository` for the
  upcoming UI layer (Task 7).
- `SensorService.applyRecovery(...)`: calls
  `coordinator.resumeProfileSwitchIfNeeded()` BEFORE normal armed recovery. When
  a switch was resumed, the recovery plan is forced to
  `RecoveryPlan(DISARMED_ONLINE, restartDetectors = false)` so the stale
  pre-switch armed intent captured at process start can never rearm the old
  profile. No transaction pending → the resume call is a no-op (returns false
  without invalidating recovery), preserving the existing recovery path.
- `DirectBootProtectionStore`: added companion `persistedKeysForTest()` returning
  exactly `setOf("armed", "auto_recovery_after_boot")`; key constants stay
  private inside the internal companion. No Direct Boot behavior change; the
  bootstrap service still builds no profile/encrypted/incident/Telegram graph.
- Marked Task 6 Steps 1–5 complete in the implementation plan.

## Verification evidence

All Gradle verification used one worker and no daemon because this machine has
limited RAM.

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\ASUS\AppData\Local\Android\Sdk'
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*ProtectionSnapshotStoreTest' --tests '*ProtectionRecoveryPolicyTest' --tests '*DirectBootBootstrapPolicyTest'
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest
```

- RED confirmed first: `Unresolved reference 'persistedKeysForTest'`
  (DirectBootBootstrapPolicyTest.kt:67) — the marker-key guard lacked its seam.
- GREEN after implementation: focused run `BUILD SUCCESSFUL` (SnapshotStore +
  RecoveryPolicy + DirectBoot tests).
- Full host suite `testDebugUnitTest`: `BUILD SUCCESSFUL`; XML evidence shows
  100 test classes, 697 tests, 0 failures, 0 errors (fresh timestamps).
- Two GREEN-phase fixes: (1) `persistedKeysForTest` moved from an instance
  member to the companion object because the test calls it on the class;
  (2) the snapshot-store fake was renamed to
  `SnapshotStoreProfileRepositoryFake` because a same-package file-private
  `InMemoryProtectionProfileRepository` already exists in
  `ProtectionCoordinatorTest.kt` (Kotlin redeclaration).
- `git diff --check` before commit: clean. Worktree clean after commit `a7fc2aa`.

## Decisions

- `ProtectionSnapshotStore.kt` was listed in the plan but needed NO change: the
  armed-profile snapshot persistence/recovery it provides landed in Task 3, and
  the new `recoveryUsesPersistedArmedProfileNotEditableSelectedProfile` test
  passes against it unchanged. No artificial edit was made.
- Recovery still rearms through `coordinator.arm(RECOVERY)` which freezes the
  CURRENT selected profile; the frozen `armedProfileSnapshot` remains the
  durable proof of the armed session and is what the store-level test pins.
  Deeper old-generation fencing for late location callbacks stays deferred to
  the Entry Guard / recovery-readiness plans as recorded in Task 5.
- If `resumeProfileSwitchIfNeeded()` throws, recovery continues with the normal
  plan and the failure is logged; convergence retries on the next resume call.

## Current user-visible state

- No UI changes yet. The production coordinator now has the profile repository,
  so Task 7's ViewModel can read profile state through the graph without new
  construction paths.

## Pending

1. Task 7: visible three-profile picker with truthful setup blocking
   (ViewModel + Compose + confirmation flow).
2. Task 8: focused/full host tests, APK build/install, Huawei UI acceptance,
   and a separate Direct Boot regression gate.

## Risks and boundaries

- The resume-before-recovery ordering is proven at the coordinator/policy level
  with fakes; process-death proof on device arrives with Task 8 acceptance.
- `Graph.profileRepository` is nullable to keep the data class backward
  compatible; production `buildGraph` always supplies it.

## Exact resume commands

```powershell
Set-Location D:\security\.worktrees\continuity-recovery-tdd
git status --short
git log -6 --oneline
Get-Content -Raw .\plans\checkpoint-2026-08-22-three-profiles-task-6.md

Set-Location .\MotorcycleAntiTheftSensor
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\ASUS\AppData\Local\Android\Sdk'
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*ProtectionViewModelTest'
```

Then resume at Task 7 in:

`MotorcycleAntiTheftSensor/docs/superpowers/plans/2026-08-22-three-protection-profiles-foundation.md`
