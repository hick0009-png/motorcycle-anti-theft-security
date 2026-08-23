# Entry Guard Door-Angle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. This checkout is intentionally executed inline and sequentially because the development machine has limited RAM; do not dispatch parallel Gradle builds or test workers.

**Goal:** Turn `ProtectionProfile.ENTRY` from the foundation's truthful `SETUP_REQUIRED` placeholder into a real, commissionable door-angle detector with hinge-axis math, one-episode-per-opening lifecycle, deduplicated health episodes, Entry-specific Thai delivery text, and device acceptance — without weakening the completed profile foundation or Direct Boot baseline.

**Architecture:** Pure-domain first. Quaternion/twist math and the detection state machine are pure Kotlin classes beside the existing profile models; Android sensor plumbing arrives only after host proofs. `ProtectionCoordinator` remains the sole armed-state owner; the detector consumes frozen baseline/model from the armed snapshot and publishes typed verdicts through the existing observation pipeline. Commissioning results persist inside the credential-protected profile store as an additive schema extension.

**Tech Stack:** Kotlin, JUnit 4, existing SharedPreferences/`org.json` codec, coroutines/StateFlow, Jetpack Compose, existing Android/Compose test stack. No new dependency.

**Spec:** `MotorcycleAntiTheftSensor/docs/superpowers/specs/2026-08-23-entry-guard-door-angle-design.md`

## Global Constraints

- Preserve every foundation behavior from commit `a51c907`: profile aggregate, immutable armed snapshots, crash-safe switch transaction, picker/change-use UI, white/black theme.
- Keep the commissioned hinge model, overrides, setup state, and armed snapshots in credential-protected storage; never touch the two-boolean Direct Boot marker contract.
- Strict evaluation order per sample: source freshness → hinge residual → allowed direction → angle threshold. A failed earlier gate can never produce `DOOR_OPEN`.
- One physical opening = one door episode; invalid evidence interrupts, never synthesizes close; `ENTRY_MOUNT_MOVED` outranks `DOOR_OPEN`.
- No auto-rebaseline while armed; no heading wrap anywhere in the math; `q` ≡ `-q`.
- Magnetic-field evidence alone can never produce `DOOR_OPEN`; GPS/Live Map stay unavailable for Entry.
- Do not add a dependency, rename a public API, revive a legacy dashboard, or alter Telegram/SMS authorization.
- Run exactly one focused Gradle invocation at a time: `--no-daemon --max-workers=1` with the repository's `-Xmx1536m`, in-process Kotlin compiler, and SerialGC settings.

## Staged Delivery Map position

This is slice 2 of 4 from the foundation plan's delivery map:

1. ~~Profile foundation and visible selection~~ (done, `a51c907`).
2. **This plan — Entry Guard:** two-cycle hinge commissioning, quaternion/axis validation, door episodes, Entry-specific delivery, device acceptance.
3. Power Guard plan (next).
4. Recovery/readiness + paper-light completion plan (last).

---

### Task 1: Pure-domain orientation math

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/EntryOrientationMath.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/EntryOrientationMathTest.kt`

**Interfaces:**
- Consumes: nothing (pure Kotlin).
- Produces: `EntryQuaternion(w,x,y,z)`, `normalize`, `canonicalizeSign`, `relativeRotation(baseline, current)`, `twistAroundAxis(qRel, axis): Double` (degrees), `doorAngleDeltaDeg(qRel, axis): Double` clamped 0–180, `swingResidualDeg(qRel, axis)`.

- [ ] **Step 1: Write failing math tests**

Pin exact expected values: identity rotation → 0°; 90° twist around Z axis → 90°; sign-equivalent `-q` equals `q`; composed twist+swing separates correctly; result always within 0–180; no discontinuity across any synthetic full-circle sweep.

```kotlin
class EntryOrientationMathTest {
    @Test fun signEquivalentQuaternionsProduceSameAngle() { /* q vs -q → same doorAngleDeltaDeg */ }
    @Test fun ninetyDegreeTwistAroundZReadsNinetyDegrees() { /* pinned value */ }
    @Test fun angleAlwaysWithinZeroToOneEighty() { /* sweep */ }
    @Test fun swingResidualSeparatesFromTwist() { /* off-axis composition */ }
}
```

- [ ] **Step 2: RED** — `.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*EntryOrientationMathTest'` fails to compile.

- [ ] **Step 3: Implement the minimal pure math class.** No Android imports.

- [ ] **Step 4: GREEN + commit** — focused test green; commit Task 1 files only.

### Task 2: Two-cycle commissioning policy

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/EntryCommissioningPolicy.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/EntryCommissioningPolicyTest.kt`

**Interfaces:**
- Produces: `EntryCommissioningState`, `EntryHingeModel(axis, allowedDirection, residualToleranceDeg, algorithmVersion, sensorIdentity, mountSignature)`, `fingerprint(model): String`, `onSample(...)` transitions, `invalidationRequiresRecommission(oldFp, newContext): Boolean`.

- [ ] **Step 1: Failing tests** — still-check restarts on movement; cycle mismatch (axis/direction/closed-return) rejects; two consistent cycles commission; fingerprint covers all five required fields; invalidation matrix (leave/re-enter, remount, source-policy change, sensor change, algorithm bump → recommission; angle/confirmation/notification change → keep).

- [ ] **Step 2: RED** — focused run fails to compile.

- [ ] **Step 3: Implement policy** — deterministic, injectable clock.

- [ ] **Step 4: GREEN + commit.**

### Task 3: Detection policy and episode state machine

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/EntryDetectionPolicy.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/EntryDetectionPolicyTest.kt`

**Interfaces:**
- Consumes: `EntryOrientationMath`, `EntryHingeModel`, `EntryProfileSettings`.
- Produces: `EntryDetectionVerdict`, `EntryDoorEpisode`, `EntryHealthEpisodeKind`, pure `evaluate(sample, state): Pair<EntryDetectionVerdict, NewState>`.

- [ ] **Step 1: Failing tests covering the whole section-6/7 contract:**

```kotlin
class EntryDetectionPolicyTest {
    @Test fun staleSourceBlocksDoorOpenEvenWithLargeTwist()
    @Test fun offAxisSwingReportsMountMovedNotDoorOpen()
    @Test fun oppositeDirectionMotionReportsMountMoved()
    @Test fun openRequiresAngleForConfirmationWindow()
    @Test fun closeRequiresBelowThreeDegreesStableFiveSeconds()
    @Test fun onePhysicalOpeningCreatesOneEpisodeAndUpdatesIt()
    @Test fun invalidEvidenceMidOpenMarksInterruptedNeverSynthesizesClose()
    @Test fun validSameModelEvidenceClosesInterruptedEpisodeAfterFiveSeconds()
    @Test fun mountMovedOutranksDoorOpen()
    @Test fun healthEpisodesDeduplicateUntilFiveSecondFreshEvidence()
    @Test fun magneticOnlyInterferenceCannotProduceDoorOpen()
    @Test fun noAutoRebaselineWhileArmed()
}
```

- [ ] **Step 2: RED** — compile failure.

- [ ] **Step 3: Implement the strict-order evaluator and episode machine** (injectable clock; debounce windows reset on generation change).

- [ ] **Step 4: GREEN + commit.**

### Task 4: Persist the commissioned model (additive codec)

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/SensorConfigurationCodec.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionProfileModels.kt` (add nullable `entryHingeModel` to Entry stored configuration)
- Test: extend the existing codec round-trip test file.

**Interfaces:**
- Schema version bumps additively; old payloads migrate with `entryHingeModel = null`.

- [ ] **Step 1: Failing round-trip tests** — new payload persists model + flips ENTRY setupState to READY only via explicit commissioning API; legacy v1 payload loads unchanged.

- [ ] **Step 2: RED**, **Step 3: implement additive codec**, **Step 4: GREEN + commit.**

### Task 5: Coordinator/runtime wiring

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt` (rotation-vector source registration when Entry armed)
- Test: extend `ProtectionCoordinatorTest` (host fakes only).

**Interfaces:**
- Arm(ENTRY) requires commissioned fingerprint match; freezes baseline + model into `EntryArmedCalibrationSnapshot(generation, modelFingerprint)`; disarm clears runtime baseline; re-arm re-baselines; verdicts flow through the existing observation pipeline; no second delivery owner.

- [ ] **Step 1: Failing coordinator tests** — arm blocked into SETUP_REQUIRED without commissioning; armed snapshot carries frozen model; late Settings edit cannot mutate it; generation change resets debounce windows.

- [ ] **Step 2: RED**, **Step 3: wire coordinator/graph/service**, **Step 4: GREEN + commit.**

### Task 6: Commissioning UI and Entry summary

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt` (commissioning actions, live angle state)
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/ProtectionScreen.kt` (เข็มทิศประตู flow, live angle, door-angle control 5–90°, summary `ประตูปิด · 0°` / `แจ้งเมื่อเกิน 15°`)
- Test: extend `ProtectionViewModelTest`; extend androidTest `ProtectionProfilesUiTest` (compile-only this slice).

- [ ] **Step 1: Failing ViewModel tests** — commissioning state machine drives setupState to READY; angle quick choices and slider bounds enforced; armed angle change requests controlled disarm/calibrate/re-arm instead of applying live.

- [ ] **Step 2: RED**, **Step 3: implement Compose surfaces per spec section 9** (paper-light tokens, 48 dp targets, TalkBack text), **Step 4: GREEN + `compileDebugAndroidTestKotlin` + commit.**

### Task 7: Entry delivery text

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentMessageFormatter.kt`
- Test: extend the formatter test file.

- [ ] **Step 1: Failing tests pinning all six Thai strings from spec section 8** (including evidence-interrupted owner-stop copy).

- [ ] **Step 2: RED**, **Step 3: render typed Entry messages**, **Step 4: GREEN + commit.**

### Task 8: Full verification, device acceptance, checkpoint

- [ ] **Step 1: Full host gate** — `testDebugUnitTest` (expect ≥700 tests, zero failures) plus `compileDebugAndroidTestKotlin`.
- [ ] **Step 2: APK build/install** — `assembleDebug`, record path/size/SHA-256, install on Huawei INE-LX2 (`-r`).
- [ ] **Step 3: Device acceptance per spec section 11** — commissioning, 20 open/close cycles, slow opening, off-axis movement, source loss before/during open episode, 5-second recovery, forced recommissioning only on mount failure, metal-frame smoke test, no false door-closed message. Screenshots under `plans/entrytask*-*.png` (untracked).
- [ ] **Step 4: Checkpoint document** — write `plans/checkpoint-2026-08-23-entry-guard.md` with resume point, commits, verification evidence, decisions, pending Power Guard plan, risks, and exact resume commands.

## Verification commands (reference)

```powershell
Set-Location D:\security\.worktrees\continuity-recovery-tdd\MotorcycleAntiTheftSensor
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\ASUS\AppData\Local\Android\Sdk'
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*Entry*'
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest
.\gradlew.bat --no-daemon --max-workers=1 assembleDebug
```
