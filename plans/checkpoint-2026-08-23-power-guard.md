# Power Guard Checkpoint — 2026-08-23

## Resume point

- Worktree: `D:\security\.worktrees\continuity-recovery-tdd`
- Branch: `codex/continuity-recovery-tdd`
- Head: `d30c120` (feat: add power guard ambiguity policy and typed delivery text)
- **Status: Power Guard plan (slice 3 of 4) — plan doc + Tasks 1–4 complete and committed.
  Task 5 (coordinator/runtime/UI wiring) NOT started. Task 6 (UI) and Task 7 (full gate +
  device acceptance) NOT started. Entry Guard manual device acceptance still pending with owner.**

## Plan document

`MotorcycleAntiTheftSensor/docs/superpowers/plans/2026-08-23-power-guard-charging-witness.md`
(commit `12f32e5`) — 7 tasks, mirrors the Entry Guard plan structure. Spec source:
parent spec sections 4.3 / 5 / 8.

## Commits (Power Guard plan)

| Commit | Content |
|---|---|
| `12f32e5` | docs: stage power guard charging-witness plan (slice 3) |
| `56393f6` | feat: add power guard witness commissioning policy (Task 1) |
| `535912a` | feat: add power guard composite-state arbiter (Task 2) |
| `c2f050e` | feat: persist power witness model and durable episode outbox policy (Task 3) |
| `d30c120` | feat: add power guard ambiguity policy and typed delivery text (Task 4) |

## Completed this session (Tasks 1–4)

### Task 1 — `PowerWitnessCommissioningPolicy.kt` (+test)
Pure state machine IDLE→DARK_WINDOW→LIT_WINDOW→COMMISSIONED. Guided lamp off/on cycle
records separate dark/lit windows while charger stays connected; acceptance requires both
windows continuous (maxSampleGapMs), stable (maxRangeSpanLux), separated by guardBandLux;
stale/variance/gap restarts current window; overlap → full retry from DARK_WINDOW with
rejectionReason `ranges-not-separated-by-guard-band`. `fingerprint()` covers every
invalidating field; `requiresRecommission()` matrix: sensor/hood/algorithm bump/
powerUseContinuous=false invalidate; threshold/notification changes never do.

### Task 2 — `PowerCompositeArbiter.kt` (+test)
Pure armed-session evaluator over `PowerSignalSample(chargingConnected?, witnessLux?,
fresh, ts)`. Witness classified via model ranges (≤darkMax=dark, ≥litMin=lit, else
ambiguous→degraded). Only DUAL_LOST opens `ConfirmedLossOpened`; one-signal states are
health alerts (`ChargingHealthAlert`/`WitnessHealthAlert`) never called outage. Per-state
continuous debounce: entering dual loss restarts its 10 s from zero (no inheritance);
leaving resets. Episode id `POWER-<n>` per armed session; escalation supersedes inside
same episode; crossover emits `ConditionChanged(from,to)` after stable 10 s; partial
recovery keeps episode open without close message; close requires both healthy 30 s;
repeated samples never re-fire (no continuation spam); stale/ambiguous evidence degrades
honestly (windows reset, no episode).

### Task 3 — `PowerEpisodeOutboxPolicy.kt` + additive codec (+tests)
`PowerTransitionKind`, `PowerOutboxState` (PENDING/IN_FLIGHT/ACCEPTED/DELIVERY_UNCERTAIN/
SUPERSEDED/FAILED_FINAL), `PowerOutboxItem` keyed `session|episode|ordinal|kind`.
Enqueue supersedes same-episode PENDING copies first; ordinal monotonic per genuine
semantic change (caller = serialized arbiter). Transition guards read the DURABLE stored
state by key, not the caller snapshot (fixed after RED). Codec: `StoredProfileConfiguration
.powerWitnessModel` added (line ~130 of ProtectionProfileModels.kt); encode/decode
additive (`powerWitnessModel` key, NULL when absent, legacy payloads load null).

### Task 4 — `PowerAmbiguityPolicy.kt` + formatter POWER copy (+tests)
Safety-weighted: uncertain one-signal messages never auto-retried; uncertain confirmed
opening allows AT MOST ONE labeled retry after 30 s only while still continuously lost
and opening still current (`safetyRetryDue` guards all three conditions); recovery/
supersession/close cancels not-started retry. Settlement chosen from
`PowerOwnerNotificationState`: none→silent retire (null), one-signal accepted→
ONE_SIGNAL_RECOVERY, confirmed accepted→CONFIRMED_CLOSE, uncertain→UNCERTAINTY_SETTLEMENT.
Retry prefix pinned: `ส่งซ้ำเพื่อยืนยันเหตุเดิม—ผลการส่งครั้งแรกไม่แน่นอน`.
Formatter: POWER routes to `powerMessage()` keyed off latest `power_*` diagnostic
(`power_charging_health`→การชาร์จโทรศัพท์หยุด…, `power_witness_dark`→ไฟยืนยันไม่พบ…,
`power_confirmed_loss`→ยืนยันไฟเลี้ยงขาดในจุดที่เฝ้าระวัง,
`power_recovered`→ไฟเลี้ยงที่จุดเฝ้าระวังกลับมาคงที่แล้ว). Both Telegram and SMS paths.

## Verification evidence so far

All runs: one Gradle invocation at a time, `--no-daemon --max-workers=1`,
`JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`,
`ANDROID_HOME=C:\Users\ASUS\AppData\Local\Android\Sdk`.

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests "*PowerWitnessCommissioningPolicyTest"   # GREEN
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests "*PowerCompositeArbiterTest"             # GREEN
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests "*PowerEpisodeOutboxPolicyTest" --tests "*ProtectionProfileCodecTest"  # GREEN (after durable-state guard fix)
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests "*PowerAmbiguityPolicyTest" --tests "*PowerIncidentFormatterTest" --tests "*IncidentMessageFormatterTest"  # GREEN
```

## Remaining (in order)

1. **Task 5 — Coordinator/runtime wiring (TDD):**
   - `ProtectionProfilePolicy`: add `commissionPower(state, model)` /
     `decommissionPower(state)` mirroring `commissionEntry`/`decommissionEntry`
     (~lines 81–105).
   - `ProtectionRuntime.kt` + `AndroidProtectionRuntime.kt`: POWER hooks mirroring the
     Entry pattern (ProtectionRuntime lines ~93–118): `beginPowerSession(sessionId,
     model, settings)`, `clearPowerSession()`, `startPowerCommissioningStream()`,
     `stopPowerCommissioningStream()`, `powerWitnessSamples(): Flow<PowerWitnessSample>`
     (default emptyFlow/no-op keeps host fakes unaffected).
   - `ProtectionCoordinator.arm(...)`: for selectedProfile==POWER require stored
     `powerWitnessModel != null` else refuse arm (SETUP_REQUIRED); validate fingerprint
     context via `PowerWitnessCommissioningPolicy.requiresRecommission`; freeze into
     `PowerArmedCalibrationSnapshot(generation, fingerprint)` (type already exists in
     models); skipped challenge → Armed Degraded (degraded set, no incident).
   - Tests: extend `ProtectionCoordinatorTest` (host fakes): arm blocked without
     commissioning; frozen fingerprint in snapshot; late Settings edit cannot mutate;
     generation change restarts debounce windows; no confirmed-outage claim without
     compatible calibration.
2. **Task 6 — Commissioning UI + Power summary rows** (ViewModel actions, guided lamp
   off/on flow, two independent rows charging/witness, skip-challenge → Armed Degraded
   scoped copy; extend `ProtectionViewModelTest` + androidTest compile-only).
3. **Task 7 — Full host gate** (`testDebugUnitTest` ≥750 tests zero failures +
   `compileDebugAndroidTestKotlin`), `assembleDebug`, install on Huawei INE-LX2 `-r`,
   device acceptance checklist (spec section 8 Power bullet), screenshots
   `plans/powertask*-*.png`, then final checkpoint update.
4. **Slice 4** — Recovery/readiness + paper-light completion plan (not yet written).
5. **Entry Guard manual device acceptance** (owner-assisted, spec section 11).

## Decisions

- Silent-revert workaround CONFIRMED: files open in VSCode tabs
  (`ProtectionProfileModels.kt` was one) silently reverted `edit_file`/`apply_diff`
  writes. Fix that worked: direct disk edit via PowerShell `[IO.File]::ReadAllText/
  Replace/WriteAllText`, then verify with `findstr /N "symbol" <file>` BEFORE building.
  For remaining edits prefer: close the file's VSCode tab first, or use PowerShell
  replace, or full-file `write_to_file` + findstr verification.
- Outbox transition guard reads durable stored item state by key (caller snapshots may
  be stale within chained expressions).
- Arbiter fire(): same-condition repeats after firing return null (no continuation spam);
  `LossStillConfirmed` reserved for resumed dual loss after a crossover round-trip.
- Formatter POWER copy keyed off latest `power_*` diagnostic (mirrors Entry pattern);
  new tests live in `PowerIncidentFormatterTest.kt` (separate file avoids touching the
  existing formatter test file).
- `ProtectionState` enum values: SETUP_REQUIRED/DISARMED_ONLINE/ARMING/ARMED_HEALTHY/
  ARMED_DEGRADED/ALERT_ACTIVE/OFFLINE (use ARMED_HEALTHY in host fakes).

## Risks / notes

- RAM discipline unchanged: never run parallel Gradle invocations.
- Untracked scratch logs exist in worktree root (`green-task*.log` etc.) — clean up or
  leave untracked deliberately; never commit logs.
- `PowerIncidentFormatterTest` uses a local `assertFalse` wrapper — harmless but could
  be replaced with a direct static import on next touch.

## Exact resume commands

```powershell
Set-Location D:\security\.worktrees\continuity-recovery-tdd
git status --short
git log -6 --oneline

Set-Location .\MotorcycleAntiTheftSensor
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\ASUS\AppData\Local\Android\Sdk'
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests "*Power*"
```

Then start Task 5 Step 1: failing coordinator tests for arm(POWER) gating, per the plan
document Task 5 section.
