# Power Guard Checkpoint — 2026-08-23 (updated: end of session 2)

## Resume point

- Worktree: `D:\security\.worktrees\continuity-recovery-tdd`
- Branch: `codex/continuity-recovery-tdd`
- Head: `c7cf72f` (feat: wire power guard coordinator and runtime hooks (Task 5))
- **Status: Power Guard plan (slice 3 of 4) — Tasks 1–5 complete and committed.
  Task 6 (Commissioning UI + Power summary rows) JUST STARTED: additive UI models
  already committed (`a3`-series below); ViewModel/Screen/tests NOT started.
  Task 7 (full gate + device acceptance) NOT started. Entry Guard manual device
  acceptance still pending with owner.**

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
| (next)    | feat: stage power guard ui models for commissioning flow (Task 6 wip) — see "Uncommitted work" |

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

## Task 6 progress (JUST STARTED)

### Already done (committed as "feat: stage power guard ui models...")
`ProtectionUiModels.kt` — additive only, compiles standalone:
- `PowerCommissioningPhase { DARK_WINDOW, LIT_WINDOW, COMMISSIONED, FAILED }`
- `PowerCommissioningUiState(phase, liveLux, failureReason)`
- `ChargingRowState { CONNECTED, DISCONNECTED, UNKNOWN }`,
  `WitnessRowState { DETECTED, DARK, UNAVAILABLE }`, `PowerSummaryRows(charging, witness)`
- `ProtectionProfileUiState` += `powerCommissioning: PowerCommissioningUiState? = null`
  and `powerSummary: PowerSummaryRows? = null` (both defaulted at END → source-compatible).

### NOT started (exact next steps, in order)
1. **PowerArmChallengeRegistry.kt** (new, protection pkg): `@Volatile passedAtMs`;
   `markPassed(nowMs)`; `isSatisfied(nowMs)` = passed within
   `DEFAULT_VALIDITY_MS = 10L * 60_000L`. (A draft was interrupted mid-write — file does
   NOT exist yet; recreate from this spec.)
2. **ProtectionViewModel.kt**: add params `powerRuntime: ProtectionRuntime? = null`,
   `powerArmChallenge: PowerArmChallengeRegistry? = null`; state
   `powerCommissioningState: MutableStateFlow<PowerCommissioningUiState?>`; policy vars
   (`PowerWitnessCommissioningPolicy` + State + job); add 4th flow to the uiState
   combine; actions `startPowerCommissioning()` / `cancelPowerCommissioning()` /
   `markPowerChallengePassed()` mirroring the Entry trio (lines ~238–329). Policy
   construction suggestion: windowDurationMs=5_000, maxSampleGapMs=2_000,
   maxRangeSpanLux=20.0, guardBandLux=10.0, sensorIdentity=
   `EntryCommissioningEnvironment.sensorIdentity()`, hoodSignature=constant
   (e.g. "hood-default-v1" — pin real value at Task 7 device acceptance).
   On COMMISSIONED: `repository.update { profilePolicy.commissionPower(it, model) }`,
   stop stream, refreshProfile.
3. **Summary rows derivation**: `snapshot.chargingState: ChargingState` already exists
   (ProtectionModels.kt line ~194) → charging row CONNECTED/DISCONNECTED/UNKNOWN;
   witness row from degradation reasons ("witness placement not revalidated" →
   UNAVAILABLE) + light sensor health; neither row alone claims an outage. Project into
   `profileState` inside `refreshProfile()` when selectedProfile==POWER.
4. **Composition wiring**: coordinator is built in `ProtectionRuntimeGraph.kt:456`
   (entry context provider at :487 — add power context provider + challenge registry
   there); ViewModel built in `Navigation.kt:90` (pass `powerRuntime = graph.runtime`
   equivalent + registry).
5. **Compose UI**: new `PowerGuardSection.kt` mirroring `EntryGuardSection.kt` (guided
   lamp off/on flow, live lux, two independent summary rows with text+icon+color and
   recovery instruction, skip-challenge → Armed Degraded scoped copy); hook into
   `ProtectionScreen.kt` where the Entry section renders.
6. **Tests**: extend `ProtectionViewModelTest` (commissioning drives setupState READY;
   skip-challenge arms degraded with scoped copy; summary always shows both rows
   independently; neither row alone claims outage); extend androidTest
   `ProtectionProfilesUiTest` compile-only.

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

Then start Task 6 step 1: create `PowerArmChallengeRegistry.kt` per the spec above,
then failing ViewModel tests (Task 6 Step 1) per the plan document Task 6 section.
