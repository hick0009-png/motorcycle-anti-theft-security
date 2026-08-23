# Power Guard Checkpoint — 2026-08-23 (updated: end of session 3)

## Resume point

- Worktree: `D:\security\.worktrees\continuity-recovery-tdd`
- Branch: `codex/continuity-recovery-tdd`
- Head: `6c068d7` (feat: add power guard commissioning ui and summary rows (Task 6))
- **Status: Power Guard plan (slice 3 of 4) — Tasks 1–6 complete and committed.
  Task 7 (full gate + device acceptance + final checkpoint) NOT started.
  Entry Guard manual device acceptance still pending with owner.**

## Plan document

`MotorcycleAntiTheftSensor/docs/superpowers/plans/2026-08-23-power-guard-charging-witness.md`
(commit `12f32e5`) — 7 tasks. Spec source: parent spec sections 4.3 / 5 / 8.

## Commits (Power Guard plan)

| Commit | Content |
|---|---|
| `12f32e5` | docs: stage power guard charging-witness plan (slice 3) |
| `56393f6` | feat: add power guard witness commissioning policy (Task 1) |
| `535912a` | feat: add power guard composite-state arbiter (Task 2) |
| `c2f050e` | feat: persist power witness model and durable episode outbox policy (Task 3) |
| `d30c120` | feat: add power guard ambiguity policy and typed delivery text (Task 4) |
| `a0c0291` | docs: checkpoint power guard progress through task 4 |
| `c7cf72f` | feat: wire power guard coordinator and runtime hooks (Task 5) |
| `c9eb27f` | feat: stage power guard ui models for commissioning flow (task 6 wip) |
| `48c987c` | docs: checkpoint power guard task 5 complete |
| `6c068d7` | feat: add power guard commissioning ui and summary rows (Task 6) |

## Completed this session (Task 5 — commit `c7cf72f`, 7 files, +579)

### ProtectionProfilePolicy.kt
`commissionPower(state, model)` / `decommissionPower(state)` mirroring the Entry pair
(stores `powerWitnessModel`, flips `setupState` READY ↔ SETUP_REQUIRED).

### ProtectionRuntime.kt (interface defaults)
`beginPowerSession(sessionId, model, settings)`, `clearPowerSession()`,
`startPowerCommissioningStream()`, `stopPowerCommissioningStream()`,
`powerWitnessSamples(): Flow<PowerWitnessSample>` — all default no-op/emptyFlow so host
fakes are unaffected.

### PowerArmedSessionController.kt (NEW pure file)
Process-wide armed POWER session holder mirroring `EntryArmedSessionController`:
`begin(generation, model, settings)` builds a `PowerCompositeArbiter` + initial state;
`onSample(sample, currentGeneration)` resets debounce windows (`streakStartMs`,
`streakFired`, `healthySinceMs`) on generation change while keeping the frozen model and
open episode; returns empty verdict list when no session is active (no compatible
calibration → no outage claim possible).

### AndroidProtectionRuntime.kt
- `AndroidDetectorSet` interface: POWER hooks added (default no-ops).
- `PlatformAndroidDetectorSet`: `powerSession = PowerArmedSessionController()` +
  `powerSampleFlow = MutableSharedFlow<PowerWitnessSample>(64)`; `beginPowerSession`
  begins with `controller.currentGenerationId()`; `powerWitnessSamples()` returns the
  flow (emission wiring from the light source is Task 7 device work).
- Outer `AndroidProtectionRuntime`: delegates all five hooks to `detectors.*`.

### ProtectionCoordinator.kt
- New constructor params: `powerCommissioningContextProvider:
  (() -> PowerWitnessCommissioningPolicy.CommissioningContext)? = null` and
  `powerIntegrityChallenge: (() -> Boolean)? = null`.
- `arm(POWER)`: requires stored `powerWitnessModel != null` else SETUP_REQUIRED reject;
  validates fingerprint via `PowerWitnessCommissioningPolicy.requiresRecommission(
  storedContextOf(storedModel), currentContext)` when provider non-null → decommissions
  durably + rejects ("Power commissioning invalidated; recommission required");
  challenge not passed → adds degradation `POWER_CHALLENGE_DEGRADED`
  = "Power witness placement not revalidated" (file-private const, line ~22) → final
  state ARMED_DEGRADED, no incident.
- Freeze: `modelFingerprint` when-chain (entry → power → null);
  `PowerArmedCalibrationSnapshot(generation, modelFingerprint)` when POWER.
- `runtime.beginPowerSession(...)` after detector start (mirrors beginEntrySession);
  `runtime.clearPowerSession()` added at all 5 clear sites (cancel ×3, primary-not-ready,
  disarm).

### PowerWitnessCommissioningPolicy.kt
Added `storedContextOf(model)` companion helper (rebuilds the CommissioningContext a
stored model was commissioned under; `powerUseContinuous = true`).

### Tests (ProtectionCoordinatorTest.kt) — 8 new POWER tests, all GREEN
`powerArmBlockedIntoSetupRequiredWithoutCommissioning`,
`powerArmFreezesCommissionedWitnessModelIntoArmedSnapshot`,
`latePowerSettingsEditCannotMutateFrozenArmedSnapshot`,
`stalePowerCommissioningContextDecommissionsAndBlocksArm`,
`skippedPowerChallengeArmsDegradedWithoutIncident`,
`disarmClearsRuntimePowerSessionAndRearmBeginsFreshSession`,
`powerGenerationChangeResetsDebounceWindowsWithoutReconfirming` (direct
PowerArmedSessionController test), `noConfirmedOutageClaimWithoutCompatibleCalibration`.
FakeRuntime extended with `powerBeginCalls/powerClearCalls/lastBeganPowerSessionId/
lastBeganPowerModel`; `coordinator()` helper forwards the two new params.

## Task 6 progress (COMPLETE — commit `6c068d7`, 11 files, +602)

All six steps from the session-2 plan landed in one commit:
1. `PowerArmChallengeRegistry.kt` (new): `markPassed(nowMs)` / `isSatisfied(nowMs)`
   within `DEFAULT_VALIDITY_MS = 10 min`, fail-closed on expiry/absence.
2. `ProtectionViewModel.kt`: params `powerRuntime` / `powerArmChallenge`;
   `powerCommissioningState` flow (4th combine input); policy vars; actions
   `startPowerCommissioning()` / `cancelPowerCommissioning()` / `markPowerChallengePassed()`;
   `advancePowerCommissioning()` persists via `commissionPower` on COMMISSIONED then
   stops stream + refreshes profile. Hood signature now shared:
   `PowerWitnessCommissioningPolicy.DEFAULT_HOOD_SIGNATURE` ("hood-default-v1").
3. Summary rows: internal `powerSummaryRows(chargingState, degradationReasons,
   lightSensorHealth)` at the bottom of ProtectionViewModel.kt; charging CHARGING/FULL →
   CONNECTED, DISCHARGING/NOT_CHARGING → DISCONNECTED, else UNKNOWN; witness UNAVAILABLE
   when `POWER_CHALLENGE_DEGRADED` (now `internal const`) present or light sensor not
   HEALTHY/AVAILABLE; projected in `refreshProfile()` only for selectedProfile==POWER.
4. Wiring: `Graph.powerArmChallenge` exposed; graph builds registry +
   `powerCommissioningContextProvider` (sensor identity + DEFAULT_HOOD_SIGNATURE +
   ALGORITHM_VERSION + continuous=true) + `powerIntegrityChallenge =
   { registry.isSatisfied(wallClock.nowMs()) }`; Navigation passes
   `powerRuntime = graph.runtime` and `powerArmChallenge = graph.powerArmChallenge`.
5. Compose UI: new `PowerGuardSection.kt` (guided lamp off/on with live lux, glyph+color
   summary rows with recovery hints, single-signal disclaimer copy, placement-confirm
   button, Armed-Degraded scoped copy); hooked into ProtectionScreen next to Entry;
   three defaulted callbacks added to `ProtectionAppActions` and wired in Navigation.
6. Tests: 4 new host tests GREEN (`powerCommissioningPersistsWitnessModelAndDrivesSetupToReady`,
   `skippedPowerChallengeArmsDegradedWithScopedWitnessCopy`,
   `powerSummaryRowsDeriveIndependentlyFromEachSignal`,
   `powerSummaryProjectedOnlyForSelectedPowerProfile`) + FakePowerSampleRuntime +
   extended fakeCoordinator; androidTest `powerSummaryShowsBothRowsIndependently`
   added compile-only.

## Verification evidence so far

All runs: one Gradle invocation at a time, `--no-daemon --max-workers=1`,
`JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`,
`ANDROID_HOME=C:\Users\ASUS\AppData\Local\Android\Sdk`.

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests "*PowerWitnessCommissioningPolicyTest"   # GREEN
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests "*PowerCompositeArbiterTest"             # GREEN
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests "*PowerEpisodeOutboxPolicyTest" --tests "*ProtectionProfileCodecTest"  # GREEN
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests "*PowerAmbiguityPolicyTest" --tests "*PowerIncidentFormatterTest" --tests "*IncidentMessageFormatterTest"  # GREEN
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests "*ProtectionCoordinatorTest*"  # GREEN: 71 tests, 0 failures (session 2, Task 5)
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests "*ProtectionViewModelTest"     # GREEN incl. 4 new POWER tests (session 3)
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests "*ProtectionViewModelTest" compileDebugAndroidTestKotlin  # BUILD SUCCESSFUL in 1m 8s (session 3, pre-commit)
```

## Decisions

- **Silent-revert workaround CONFIRMED again this session**: an `apply_diff` with 8
  blocks partially applied to `ProtectionCoordinator.kt` (first 3 landed, rest
  silently skipped). Fix: re-read the file, re-apply remaining blocks with fresh line
  anchors, then `findstr` verify BEFORE building. Always verify edits on disk.
- **AndroidDetectorSet vs ProtectionRuntime**: `PlatformAndroidDetectorSet` implements
  `AndroidDetectorSet` (declared top of AndroidProtectionRuntime.kt), NOT
  ProtectionRuntime. POWER hooks had to be added to BOTH interfaces. First build failed
  with "overrides nothing"/"Unresolved reference" until AndroidDetectorSet gained them.
- Coordinator challenge design: `powerIntegrityChallenge: (() -> Boolean)?` constructor
  lambda; null/false → degradation "Power witness placement not revalidated" →
  ARMED_DEGRADED (no incident). Registry class (step 1 above) will back the lambda at
  graph composition.
- `PowerWitnessCommissioningPolicy.storedContextOf(model)` rebuilds commissioning
  context with `powerUseContinuous = true` (a commissioned model predates any
  discontinuity; the CURRENT context comes from the provider at Arm time).
- Outbox transition guard reads durable stored item state by key (caller snapshots may
  be stale within chained expressions).
- Arbiter fire(): same-condition repeats after firing return null (no continuation spam);
  `LossStillConfirmed` reserved for resumed dual loss after a crossover round-trip.
- Formatter POWER copy keyed off latest `power_*` diagnostic (mirrors Entry pattern);
  new tests live in `PowerIncidentFormatterTest.kt`.
- `ProtectionState` enum values: SETUP_REQUIRED/DISARMED_ONLINE/ARMING/ARMED_HEALTHY/
  ARMED_DEGRADED/ALERT_ACTIVE/OFFLINE (use ARMED_HEALTHY in host fakes).

## Risks / notes

- RAM discipline unchanged: never run parallel Gradle invocations.
- Untracked scratch logs in worktree (`green-task*.log`, `red-task*.log`,
  `task5-coordinator.log`, `task8-*.png` etc.) — leave untracked, never commit.
- `PowerIncidentFormatterTest` uses a local `assertFalse` wrapper — harmless.
- Kotlin incremental compile produced one stale-ABI-looking failure that vanished on
  rerun after the AndroidDetectorSet fix; if a build fails with errors that contradict
  `findstr` output, rerun once before deeper debugging.
- `powerWitnessSamples()` currently has no emitter in PlatformAndroidDetectorSet —
  Task 7 device work must wire ambient-light → PowerWitnessSample emission (and
  charging observation into the arbiter pipeline) before device acceptance.

## Exact resume commands

```powershell
Set-Location D:\security\.worktrees\continuity-recovery-tdd
git status --short
git log -4 --oneline

Set-Location .\MotorcycleAntiTheftSensor
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\ASUS\AppData\Local\Android\Sdk'
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests "*ProtectionCoordinatorTest*"
```

Then start Task 7 step 1 (full host gate) per the plan document:
`.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest` (expect ≥750 tests,
zero failures) plus `compileDebugAndroidTestKotlin`, then assembleDebug + device
acceptance on Huawei INE-LX2. Device work still required: wire ambient-light →
`PowerWitnessSample` emission and charging observation into the arbiter pipeline in
`PlatformAndroidDetectorSet` before acceptance.
