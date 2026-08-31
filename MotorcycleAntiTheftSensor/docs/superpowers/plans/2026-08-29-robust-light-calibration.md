# Robust Light Calibration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Power Guard light calibration save reliably across daylight, nighttime, sensor outliers, and Android on-change sensor delivery.

**Architecture:** Keep the existing commissioning state machine and stored-model shape, but replace extrema-based admission with robust log-lux median/MAD statistics. Drive the window by elapsed time independently of sensor callbacks, then preserve the existing per-Arm baseline adaptation and surface persistence failures instead of hiding them.

**Tech Stack:** Kotlin, coroutines/Flow, JUnit 4, Android SharedPreferences; no new dependencies.

**Spec:** User-approved design in the 2026-08-29 conversation: two 10-second windows, robust day/night calculation, current Arm-time reference, hysteresis, and truthful save failure.

## Global Constraints

- Preserve the current architecture and dirty worktree.
- Do not add dependencies or change unrelated UI.
- Run exactly one Gradle process at a time with `--no-daemon --max-workers=1`.
- Verify every edit on disk before building.

---

### Task 1: Robust commissioning statistics

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/PowerWitnessCommissioningPolicy.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/PowerWitnessCommissioningPolicyTest.kt`

**Interfaces:**
- Consumes: timestamped `PowerWitnessSample` readings.
- Produces: the existing `PowerWitnessModel`, populated from outlier-resistant window bounds and robust noise.

- [ ] Add failing tests proving a single spike cannot reject an otherwise separated calibration, stable 4-lux contrast works in bright ambient light, non-distinguishable windows fail, and a window can advance on elapsed time without repeated sensor events.
- [ ] Run the focused policy test and confirm failures are caused by extrema/gap behavior.
- [ ] Store window samples in log-lux space, calculate median and scaled MAD, reject Hampel outliers, and make elapsed-time advancement independent from sample frequency.
- [ ] Bump the algorithm version so incompatible saved models are recommissioned.
- [ ] Run the focused policy test and confirm all cases pass.

### Task 2: Reliable window clock and persistence result

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt`

**Interfaces:**
- Consumes: sensor events plus the injected clock and `ProtectionProfileRepository.update` result.
- Produces: regularly advanced commissioning state; closes the flow only after durable save succeeds.

- [ ] Add failing tests proving an unchanged on-change light reading still completes its timed window and a failed repository update keeps calibration open with a save error.
- [ ] Run the focused ViewModel tests and confirm both failures.
- [ ] Add a bounded commissioning heartbeat based on the latest valid sample and handle `repository.update` success/failure explicitly.
- [ ] Run the focused ViewModel tests and confirm they pass.

### Task 3: Regression and device delivery

**Files:**
- Modify only if compilation requires compatibility updates to existing Power Guard tests.

**Interfaces:**
- Consumes: completed Tasks 1-2.
- Produces: verified debug APK installed on the connected device.

- [ ] Run focused Power commissioning and armed-session tests.
- [ ] Run `:app:testDebugUnitTest :app:compileDebugAndroidTestKotlin`.
- [ ] Assemble the debug APK.
- [ ] Install it with `adb install -r` on the connected Huawei device.
- [ ] Report exact tests, install result, and the remaining physical limitation when lamp contrast is below sensor noise.
