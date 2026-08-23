# Entry Guard Tasks 1–4 Checkpoint — 2026-08-23

## Resume point

- Worktree: `D:\security\.worktrees\continuity-recovery-tdd`
- Branch: `codex/continuity-recovery-tdd`
- Spec/plan approval commit: `604d1af`
  - Spec: `MotorcycleAntiTheftSensor/docs/superpowers/specs/2026-08-23-entry-guard-door-angle-design.md`
  - Plan: `MotorcycleAntiTheftSensor/docs/superpowers/plans/2026-08-23-entry-guard-door-angle.md`
- Task 1 implementation commit: `09d09ef` — `EntryOrientationMath` (+`totalRotationDeg`)
- Task 2 implementation commit: `9ce977a` — `EntryCommissioningPolicy`, `EntryHingeModel`, `EntryOrientationSample`
- Task 3 implementation commit: `185e8e5` — `EntryDetectionPolicy`, `EntryDetectionVerdict`, `EntryDoorEpisode`
- Interim checkpoint commit: `2f889cd`
- Task 4 implementation commit: `b38d1bc` — additive hinge-model persistence + commission API
- Next task: **Task 5** of the Entry Guard plan (coordinator/runtime wiring)

## Completed

### Task 1 — Orientation math (`EntryOrientationMath.kt`)
- Quaternion normalize, sign canonicalization (`q` ≡ `-q`), Hamilton multiply,
  relative rotation (`baseline⁻¹ ⊗ current`), signed hinge-axis twist,
  `doorAngleDeltaDeg` clamped 0–180, swing residual via twist removal, and
  `totalRotationDeg` for axis-less movement detection. No Android imports.
- Evidence: `EntryOrientationMathTest` tests=7 failures=0.

### Task 2 — Two-cycle commissioning (`EntryCommissioningPolicy.kt`)
- Five-second still check restarting on movement or stale samples; two guided
  cycles requiring peak ≥ selected angle, return below close threshold, axis
  agreement within tolerance, and same opening direction; sign-canonicalized
  averaged-axis model; fingerprint covering axis/direction/tolerance/version/
  sensor identity/mount/source policy; invalidation matrix where alert-angle and
  confirmation-time changes never invalidate.
- Evidence: `EntryCommissioningPolicyTest` tests=9 failures=0.

### Task 3 — Detection policy (`EntryDetectionPolicy.kt`)
- Strict evaluation order: freshness → residual → direction → angle threshold.
- One door episode per physical opening (`ENTRY-N` ids), peak updates as
  `DoorStillOpen`, close = below 3° stable 5 s, hysteresis band resets streaks.
- Stale source opens one deduplicated health episode; recovery needs 5 s fresh
  below-close evidence; interrupted episodes close only through that path;
  mount-moved outranks door events and is terminal until recommissioning.
- Baseline frozen for the armed session (slow-drift test proves no rebaseline).
- Evidence: `EntryDetectionPolicyTest` tests=13 failures=0.

### Task 4 — Persistence (`ProtectionProfileModels/Codec/Policy`)
- `StoredProfileConfiguration.entryHingeModel: EntryHingeModel? = null` — additive,
  schema stays v1: old payloads decode with null model; older readers ignore the key.
- Codec encode/decode with validation: unit axis (±0.01), direction ±1,
  tolerance in (0, 45], algorithm version ≥ 1, non-blank identity strings.
- `ProtectionProfilePolicy.commissionEntry(state, model)` → READY + model stored;
  `decommissionEntry(state)` → SETUP_REQUIRED + model cleared (profile-isolated).
- Tests: `ProtectionProfileCodecTest` +4 (round-trip READY/model, legacy-null decode,
  decommission isolation, non-unit axis rejection); focused run BUILD SUCCESSFUL.

## Verification evidence

All runs: one Gradle invocation at a time, `--no-daemon --max-workers=1`,
`JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`,
`ANDROID_HOME=C:\Users\ASUS\AppData\Local\Android\Sdk`.

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests "*EntryOrientationMathTest"   # GREEN 7/7
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests "*EntryCommissioningPolicyTest" --tests "*EntryOrientationMathTest"  # GREEN 9+7
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests "*EntryDetectionPolicyTest"   # GREEN 13/13
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests "*ProtectionProfileCodecTest" --tests "*ProtectionProfilePolicyTest"  # GREEN
```

RED was confirmed before each implementation (compile failure on missing types).
Two test-side defects were fixed during Task 3 GREEN (Pair destructuring order in
three early tests; a `break` before `state = s` discarded the post-close state) —
implementation code needed no changes for them.

## Decisions

- Axis sign convention: largest-absolute component positive, so commissioning
  records always carry `allowedDirection = +1`; opposite-direction detection
  compares the signed twist against that stored axis.
- Episode IDs are deterministic counters (`ENTRY-<n>`) scoped per policy instance;
  durable session-scoped IDs arrive with coordinator wiring (Task 5).
- `totalRotationDeg` added to the math object rather than duplicating geodesic math.
- Schema stays v1 with an optional field instead of bumping: the existing decoder
  hard-rejects unknown versions (`require(schemaVersion == SCHEMA_VERSION)`), so a
  bump would destroy old payloads instead of migrating them.
- Tooling note: one edit_file change to ProtectionProfileModels.kt silently did not
  persist (suspected IDE auto-revert of externally changed files). Re-applied via a
  PowerShell line-insertion. If an edit appears to vanish, verify with Select-String
  before rebuilding.

## Pending (Entry Guard plan tasks 5–8)

5. Coordinator/runtime wiring: Arm(ENTRY) requires commissioned fingerprint match;
   freeze baseline+model into `EntryArmedCalibrationSnapshot`; disarm clears runtime
   baseline; re-arm re-baselines; verdicts flow through the existing observation
   pipeline; register rotation-vector source when Entry armed. Extend
   `ProtectionCoordinatorTest` with host fakes.
6. Commissioning UI: live relative angle, door-angle control 5–90° (quick choices
   5/15/30), Entry summary `ประตูปิด · 0°` / `แจ้งเมื่อเกิน 15°`, armed angle change
   routes through controlled disarm/calibrate/re-arm. Extend `ProtectionViewModelTest`;
   androidTest compile-only.
7. Delivery text: pin all six Thai strings from spec section 8 in formatter tests.
8. Full host gate ≥700 tests, `assembleDebug`, APK hash, Huawei INE-LX2 acceptance
   per spec section 11, final checkpoint `plans/checkpoint-2026-08-23-entry-guard.md`.

## Risks / notes

- Pure domain + persistence only so far: nothing wired to sensors/UI yet; app
  behavior unchanged for users.
- `data object` verdicts require the project's current Kotlin (compiles fine here).
- Watch RAM discipline: never run parallel Gradle invocations.

## Exact resume commands

```powershell
Set-Location D:\security\.worktrees\continuity-recovery-tdd
git status --short
git log -8 --oneline

Set-Location .\MotorcycleAntiTheftSensor
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\ASUS\AppData\Local\Android\Sdk'
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*Entry*' --tests '*ProtectionProfile*'
```

Then resume at Task 5 in
`MotorcycleAntiTheftSensor/docs/superpowers/plans/2026-08-23-entry-guard-door-angle.md`.
