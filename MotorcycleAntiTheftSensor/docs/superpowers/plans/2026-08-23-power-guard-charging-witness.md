# Power Guard Charging-Witness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. This checkout is intentionally executed inline and sequentially because the development machine has limited RAM; do not dispatch parallel Gradle builds or test workers.

**Goal:** Turn `ProtectionProfile.POWER` from the foundation's truthful `SETUP_REQUIRED` placeholder into a real dual-signal detector: lamp off/on witness commissioning, a composite-state arbiter with per-state continuous debounce windows, one durable episode per abnormality with a monotonic transition ordinal and a durable outbox, a safety-weighted ambiguity policy, typed Thai delivery text, and device acceptance — without weakening the completed profile foundation, the Entry Guard slice, or the Direct Boot baseline.

**Architecture:** Pure-domain first. The witness commissioning state machine and the composite arbiter are pure Kotlin classes beside the existing profile models; Android charging/light plumbing arrives only after host proofs. `ProtectionCoordinator` remains the sole armed-state owner; charging state becomes the typed runtime observation promised by the foundation plan, ambient-light witness samples reuse the existing light source path, and verdicts publish through the existing observation pipeline into `IncidentType.POWER`. Commissioned witness models, the active power episode, its transition ordinal, durable owner-notification state, outbox items, and accepted receipts persist inside the credential-protected profile store as an additive schema extension. Delivery text flows through the single existing `IncidentMessageFormatter`; no second delivery owner is created.

**Tech Stack:** Kotlin, JUnit 4, existing SharedPreferences/`org.json` codec, coroutines/StateFlow, Jetpack Compose, existing Android/Compose test stack. No new dependency.

**Spec:** `MotorcycleAntiTheftSensor/docs/superpowers/specs/2026-08-22-mobile-only-three-protection-profiles-design.md` sections 4.3 (Power Guard), 5 (health/notification semantics), 5.1 (delivery-path readiness is slice 4 scope — only the sensor-side rows land here), and 8 (verification).

## Global Constraints

- Preserve every foundation behavior from commit `a51c907` and every Entry Guard behavior from commit `181df0f`: profile aggregate, immutable armed snapshots, crash-safe switch transaction, two-cycle Entry commissioning, Entry delivery text.
- Keep the commissioned witness model, active episode, transition ordinal, outbox, and receipts in credential-protected storage; never touch the two-boolean Direct Boot marker contract.
- Only dual-signal loss (charging disconnected **and** witness dark) may open `PHONE_POWER_LOST`. A one-signal condition is a health alert (`การชาร์จโทรศัพท์หยุด` / `ไฟยืนยันไม่พบ`) and must never be called a power outage (spec section 6).
- Each composite state owns its own continuous debounce window: dual loss runs a fresh 10 s from zero and never inherits elapsed time from a preceding one-signal condition; close requires both signals healthy for 30 s.
- One physical abnormality sequence = one power episode identified by `armedSessionId + powerEpisodeId`; escalation supersedes one-signal copy inside the same episode; single-signal crossovers stay in the same episode.
- `transitionOrdinal` advances only when the stable semantic state genuinely changes; repeated samples never enqueue another outbox item.
- Outbox states are exactly `PENDING`, `IN_FLIGHT`, `ACCEPTED`, `DELIVERY_UNCERTAIN`, `SUPERSEDED`, `FAILED_FINAL`; a superseded item must never send later when connectivity returns.
- Ambiguity policy is safety-weighted: no automatic retry of uncertain one-signal health/recovery messages; at most one labeled safety retry for a confirmed-outage opening after 30 s while still continuously lost.
- Commissioned thresholds and hysteresis are frozen for the armed session; they never adapt downward to a dimming lamp or shifted hood. Excessive variance, guard-band values, or stale samples produce `Witness unavailable/Degraded`, never an outage conclusion.
- Skipping the per-arm integrity challenge arms as `Armed Degraded / witness placement not revalidated`; missing or incompatible calibration cannot produce a confirmed-outage claim.
- Do not add a dependency, rename a public API, revive a legacy dashboard, or alter Telegram/SMS authorization. GPS/Live Map/door-angle/motion alerts stay off by default for POWER.
- Run exactly one focused Gradle invocation at a time: `--no-daemon --max-workers=1` with the repository's `-Xmx1536m`, in-process Kotlin compiler, and SerialGC settings.

## Staged Delivery Map position

This is slice 3 of 4 from the foundation plan's delivery map:

1. ~~Profile foundation and visible selection~~ (done, `a51c907`).
2. ~~Entry Guard plan~~ (host-complete, `181df0f`; manual device acceptance pending with the owner).
3. **This plan — Power Guard:** charging/witness commissioning, composite-state arbiter, durable episode/outbox, ambiguity policy, device acceptance.
4. Recovery/readiness + paper-light completion plan (last).

---

### Task 1: Pure-domain witness commissioning policy

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/PowerWitnessCommissioningPolicy.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/PowerWitnessCommissioningPolicyTest.kt`

**Interfaces:**
- Consumes: nothing (pure Kotlin).
- Produces: `PowerWitnessSample(lux, timestampMs, fresh)`, `PowerWitnessModel(darkMinLux, darkMaxLux, litMinLux, litMaxLux, guardBandLux, algorithmVersion, sensorIdentity, hoodSignature)`, phase machine `IDLE → DARK_WINDOW → LIT_WINDOW → COMMISSIONED`, `onSample(state, sample): State`, `fingerprint(model): String`, `requiresRecommission(previous, current): Boolean`.

- [ ] **Step 1: Write failing commissioning tests**

```kotlin
class PowerWitnessCommissioningPolicyTest {
    @Test fun stableDarkWindowThenStableLitWindowCommissions()
    @Test fun overlappingDarkAndLitRangesAreRejected()
    @Test fun rangesInsideGuardBandAreRejected()
    @Test fun brightValueAloneIsNotProofWithoutLitWindow()
    @Test fun excessiveVarianceRestartsCurrentWindow()
    @Test fun staleSampleRestartsCurrentWindow()
    @Test fun fingerprintCoversEveryInvalidatingField()
    @Test fun invalidationMatrixMatchesSpec() // remount/hood/sensor/algorithm bump → recommission; threshold/notification change → keep
}
```

- [ ] **Step 2: RED** — `.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*PowerWitnessCommissioningPolicyTest'` fails to compile.

- [ ] **Step 3: Implement the minimal pure policy.** Deterministic, injectable window lengths; separate dark/lit sample windows recorded during a guided lamp off/on cycle while the charger stays connected; acceptance requires both windows stable and separated by the configured guard band; no Android imports.

- [ ] **Step 4: GREEN + commit** — focused test green; commit Task 1 files only.

### Task 2: Composite-state arbiter

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/PowerCompositeArbiter.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/PowerCompositeArbiterTest.kt`

**Interfaces:**
- Consumes: `PowerWitnessModel`, `PowerProfileSettings`.
- Produces: `PowerSignalSample(chargingConnected: Boolean?, witnessLux: Double?, fresh: Boolean, timestampMs)`, sealed `PowerArbiterVerdict` (`MonitoringReady`, `ChargingHealthAlert(episodeId)`, `WitnessHealthAlert(episodeId)`, `ConfirmedLossOpened(episodeId)`, `LossStillConfirmed(episodeId)`, `ConditionChanged(from, to, episodeId)`, `PartialRecovery(episodeId)`, `RecoveredClosed(episodeId)`), pure `evaluate(sample, state): Pair<PowerArbiterVerdict?, State>`.

- [ ] **Step 1: Failing tests covering the whole section-4.3 decision contract**

```kotlin
class PowerCompositeArbiterTest {
    @Test fun healthyDualSignalProducesNoIncident()
    @Test fun chargingOnlyLossOpensHealthAlertAfterTenSecondsNeverOutage()
    @Test fun witnessOnlyLossOpensHealthAlertAfterTenSecondsNeverOutage()
    @Test fun dualLossRequiresContinuousTenSecondsFromZero()
    @Test fun oneSignalTimerDoesNotInheritIntoDualLossWindow()
    @Test fun enteringDualLossCancelsUndeliveredOneSignalTimer()
    @Test fun escalationSupersedesOneSignalInsideSameEpisode()
    @Test fun crossoverRemainsSameEpisodeAndEmitsConditionChanged()
    @Test fun closeRequiresBothSignalsHealthyThirtySeconds()
    @Test fun partialRecoveryKeepsEpisodeOpenWithoutCloseMessage()
    @Test fun staleLightSamplesProduceDegradedNotOutage()
    @Test fun repeatedSamplesDoNotAdvanceEpisodeState()
}
```

- [ ] **Step 2: RED** — compile failure.

- [ ] **Step 3: Implement the strict-order evaluator** (injectable clock; per-state debounce windows reset on generation change; dual loss outranks either one-signal condition; episode id format `POWER-<n>` within the armed session).

- [ ] **Step 4: GREEN + commit.**

### Task 3: Durable episode/outbox model and persistence

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/PowerEpisodeOutboxPolicy.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionProfileModels.kt` (add nullable `powerWitnessModel` to POWER stored configuration)
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionProfileCodec.kt` (additive encode/decode of the witness model)
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/PowerEpisodeOutboxPolicyTest.kt`; extend the existing codec round-trip test file.

**Interfaces:**
- Produces: `PowerTransitionKind` (`CHARGING_HEALTH_OPENING`, `WITNESS_HEALTH_OPENING`, `CONFIRMED_LOSS_OPENING`, `CONDITION_CHANGED`, `ONE_SIGNAL_RECOVERY`, `CONFIRMED_CLOSE`, `UNCERTAINTY_SETTLEMENT`), `PowerOutboxItem(outboxKey, armedSessionId, powerEpisodeId, transitionOrdinal, kind, text, state, createdAtMs)`, `PowerOutboxState` enum, pure `onSemanticChange(...)` / `markAccepted(...)` / `markUncertain(...)` / `supersedePending(...)` transitions; schema bumps additively so old payloads load with `powerWitnessModel = null`.

- [ ] **Step 1: Failing tests**

```kotlin
class PowerEpisodeOutboxPolicyTest {
    @Test fun ordinalAdvancesOnlyOnGenuineSemanticChange()
    @Test fun outboxKeyIsSessionEpisodeOrdinalKind()
    @Test fun pendingObsoleteCopyIsSupersededBeforeEnqueueingNewCopy()
    @Test fun supersededItemNeverSendsLater()
    @Test fun inFlightBlocksParallelImmediateRetry()
    @Test fun acceptedReceiptSuppressesDuplicateDelivery()
    @Test fun uncertainMarksDeliveryUncertainNotFailure()
    @Test fun failedFinalRetiresEpisodeForNewPowerEpisodeId()
    @Test fun codecRoundTripPreservesWitnessModelAndSetupFlip()
    @Test fun legacyPayloadLoadsWithNullWitnessModel()
}
```

- [ ] **Step 2: RED**, **Step 3: implement policy + additive codec**, **Step 4: GREEN + commit.**

### Task 4: Ambiguity policy and typed delivery text

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/PowerAmbiguityPolicy.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentMessageFormatter.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/PowerAmbiguityPolicyTest.kt`; extend `IncidentMessageFormatterTest`.

- [ ] **Step 1: Failing tests pinning the ambiguity contract and all Thai strings from spec sections 4.3/5:**

```kotlin
class PowerAmbiguityPolicyTest {
    @Test fun uncertainOneSignalMessageIsNeverAutoRetried()
    @Test fun confirmedOpeningGetsAtMostOneLabeledRetryAfterThirtySecondsWhileStillLost()
    @Test fun recoveryOrSupersessionOrCloseCancelsNotStartedSafetyRetry()
    @Test fun settlementChosenFromDurableOwnerNotificationState()
    @Test fun silentRetireWhenNoOpeningWasAcceptedOrAmbiguous()
    @Test fun uncertaintySettlementReferencesSameEpisodeId()
    @Test fun retryCarriesVisibleEpisodeIdAndPrefix()
}
// Formatter pins:
// การชาร์จโทรศัพท์หยุด … / ไฟยืนยันไม่พบ … / ยืนยันไฟเลี้ยงขาดในจุดที่เฝ้าระวัง /
// ไฟเลี้ยงที่จุดเฝ้าระวังกลับมาคงที่แล้ว /
// ส่งซ้ำเพื่อยืนยันเหตุเดิม—ผลการส่งครั้งแรกไม่แน่นอน (+ episode ID)
```

- [ ] **Step 2: RED**, **Step 3: render typed POWER messages through the single formatter**, **Step 4: GREEN + commit.**

### Task 5: Coordinator/runtime wiring

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntime.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionProfilePolicy.kt` (`commissionPower` / `decommissionPower`)
- Test: extend `ProtectionCoordinatorTest` (host fakes only).

**Interfaces:**
- Runtime gains POWER hooks mirroring the Entry pattern: `beginPowerSession(sessionId, model, settings)`, `clearPowerSession()`, `startPowerCommissioningStream()` / `stopPowerCommissioningStream()`, `powerWitnessSamples(): Flow<PowerWitnessSample>`, plus a typed charging observation (`chargingConnected: Boolean?`) published through the existing pipeline — this is the "typed runtime observation" the foundation deferred to this plan.
- Arm(POWER) requires a commissioned witness model whose fingerprint still matches the current context; freezes it into `PowerArmedCalibrationSnapshot(generation, modelFingerprint)`; full `Healthy` readiness requires the per-arm lamp off/on integrity challenge while charging stays connected; a challenge passed within 10 minutes in the same uninterrupted flow satisfies the immediately following first Arm; skipping yields `Armed Degraded`; verdicts open/update/close `IncidentType.POWER` incidents only.

- [ ] **Step 1: Failing coordinator tests** — arm blocked into SETUP_REQUIRED without commissioning; armed snapshot carries frozen model fingerprint; late Settings edit cannot mutate it; skipped challenge arms degraded; generation change restarts debounce windows; no confirmed-outage claim without compatible calibration.

- [ ] **Step 2: RED**, **Step 3: wire coordinator/runtime/graph/policy**, **Step 4: GREEN + commit.**

### Task 6: Commissioning UI and Power summary rows

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt` (witness commissioning actions, live lux state, per-arm challenge actions)
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/ProtectionScreen.kt` (guided lamp off/on flow; two independent summary rows: charging state and witness-light state, each with text + icon + color and a recovery instruction when action is needed)
- Test: extend `ProtectionViewModelTest`; extend androidTest `ProtectionProfilesUiTest` (compile-only this slice).

- [ ] **Step 1: Failing ViewModel tests** — commissioning drives setupState to READY; skip-challenge arms degraded with scoped copy; summary always shows both rows independently (`Charging connected/disconnected/unknown`, `Witness light detected/dark/unavailable`); neither row alone claims an outage.

- [ ] **Step 2: RED**, **Step 3: implement Compose surfaces per spec section 3.6** (paper-light tokens, 48 dp targets, TalkBack text), **Step 4: GREEN + `compileDebugAndroidTestKotlin` + commit.**

### Task 7: Full verification, device acceptance, checkpoint

- [ ] **Step 1: Full host gate** — `testDebugUnitTest` (expect ≥750 tests, zero failures) plus `compileDebugAndroidTestKotlin`.
- [ ] **Step 2: APK build/install** — `assembleDebug`, record path/size/SHA-256, install on Huawei INE-LX2 (`-r`).
- [ ] **Step 3: Device acceptance per spec section 8 (Power bullet)** — lamp off/on commissioning; immediate first-Arm reuse within the 10-minute rule; a later per-arm integrity challenge; skipped challenge yielding `Armed Degraded`; stable hood baseline; gradual dimming; shifted hood; stale samples; charger-only loss; witness-only loss; dual loss; single-signal crossover; transient abnormality with no orphan recovery; partial recovery; complete recovery. Screenshots under `plans/powertask*-*.png` (untracked). Owner-assisted like the Entry acceptance.
- [ ] **Step 4: Checkpoint document** — write `plans/checkpoint-2026-08-23-power-guard.md` with resume point, commits, verification evidence, decisions, remaining slice 4, risks, and exact resume commands.

## Verification commands (reference)

```powershell
Set-Location D:\security\.worktrees\continuity-recovery-tdd\MotorcycleAntiTheftSensor
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\ASUS\AppData\Local\Android\Sdk'
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*Power*'
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest
.\gradlew.bat --no-daemon --max-workers=1 assembleDebug
```
