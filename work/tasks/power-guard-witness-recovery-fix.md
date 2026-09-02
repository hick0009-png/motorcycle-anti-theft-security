# CURSOR IMPLEMENTATION TASK

## Objective

ปรับปรุง Guard-Band Logic ใน PowerCompositeArbiter ให้ใช้ "สถานะไฟยืนยันล่าสุดที่ชัดเจน" แทนการทิ้งข้อมูลทั้งหมด เมื่อค่า lux ตกอยู่ใน guard band — เพื่อแก้ 2 ปัญหา:

1. **สายชาร์จหลุดแต่แสงกำกวม → ไม่ได้แจ้งเตือนเลย** (ปัญหาใหม่)
2. **เปิดไฟกลับแล้ว recovery timer ถูก reset** (ปัญหาเดิมที่แก้พร้อมกัน)

พร้อมลด `recoveryConfirmationMs` จาก `30_000L` เป็น `10_000L`

## Approved Safety Amendments (2026-09-01)

ข้อกำหนดส่วนนี้แทนที่ snippet/Definition of Done ด้านล่างเมื่อข้อความขัดกัน:

- ห้ามใช้ `currentSemantic` เป็นแหล่ง last-known witness โดยตรง เพราะค่านี้อยู่ข้าม stale/unknown, process restore และ sensor-generation change
- เก็บสถานะแสงชัดเจนล่าสุดแยกต่างหาก และใช้ fallback ได้เฉพาะใน evidence continuity เดียวกัน
- stale/unknown sample, sensor-generation change, listener unregister/re-register หรือ register failure ต้องล้างสิทธิ์ fallback
- ถ้าสายชาร์จหลุดแต่ไม่มี witness baseline ที่ยังใช้ได้ ให้ยืนยันเฉพาะ `ChargingHealthAlert` หลัง 10 วินาที ห้ามสร้าง `ConfirmedLossOpened`
- guard-band จะรักษา recovery timer ได้ต่อเมื่อ last-known witness เป็น lit ที่ยังใช้ได้และสายชาร์จยังเชื่อมต่อ
- guard-band lux ห้ามนำไปปรับ adaptive Arm reference

## Root Cause

Line 72 ใน `PowerCompositeArbiter.evaluate()` เป็น **full bail-out** เมื่อ lux ตกใน guard band:

```kotlin
else -> return null to resetWindows(state)
```

ปัญหา:
- ทิ้ง charging signal ที่ชัดเจนไปด้วย → สายชาร์จหลุดแน่นอนแต่ไม่แจ้ง
- reset `streakStartMs` / `healthySinceMs` → loss timer และ recovery timer ถูกล้าง
- ข้อความ Telegram ถูกเลื่อนออกไปเรื่อยๆ จนกว่าแสงจะชัดเจน

## Files

### Source Code (แก้ไข)
1. `app/src/main/java/com/example/motorcycleantitheftsensor/protection/PowerCompositeArbiter.kt`
2. `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionProfileModels.kt`
3. `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionProfileCodec.kt`
4. `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionProfilePolicy.kt`

### Tests (แก้ไข)
5. `app/src/test/java/com/example/motorcycleantitheftsensor/protection/PowerCompositeArbiterTest.kt`
6. `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionProfilePolicyTest.kt`

### Docs (แก้ไข)
7. `docs/superpowers/plans/2026-08-22-three-protection-profiles-foundation.md`

---

## Required Changes

### Change 1: Last-Known-Witness Fallback for Guard-Band Samples (CORE FIX)

#### File: `PowerCompositeArbiter.kt` — Lines 63-85

**BEFORE:**
```kotlin
    fun evaluate(state: State, sample: PowerSignalSample): Pair<PowerArbiterVerdict?, State> {
        // Gate 1: evidence quality. Stale, unknown, or guard-band-ambiguous samples
        // can never advance any window nor conclude an outage.
        if (!sample.fresh || sample.chargingConnected == null || sample.witnessLux == null) {
            return null to resetWindows(state)
        }
        val witnessLit = when {
            sample.witnessLux <= model.darkMaxLux -> false
            sample.witnessLux >= model.litMinLux -> true
            else -> return null to resetWindows(state)
        }
        val semantic = when {
            sample.chargingConnected && witnessLit -> SemanticState.HEALTHY_DUAL
            !sample.chargingConnected && witnessLit -> SemanticState.CHARGING_LOST
            sample.chargingConnected && !witnessLit -> SemanticState.WITNESS_LOST
            else -> SemanticState.DUAL_LOST
        }

        if (semantic != state.currentSemantic) {
            return onSemanticChange(state, semantic, sample.timestampMs)
        }
        return onSameSemantic(state, semantic, sample.timestampMs)
    }
```

**AFTER:**
```kotlin
    fun evaluate(state: State, sample: PowerSignalSample): Pair<PowerArbiterVerdict?, State> {
        // Gate 1: evidence quality. Stale or fully unknown samples cannot advance
        // any window nor conclude an outage.
        if (!sample.fresh || sample.chargingConnected == null || sample.witnessLux == null) {
            return null to resetWindows(state)
        }
        val witnessLit = when {
            sample.witnessLux <= model.darkMaxLux -> false
            sample.witnessLux >= model.litMinLux -> true
            else -> {
                // Guard-band: lux is ambiguous but charging signal may still be
                // clear. Infer the witness component from the last known semantic
                // so the charging signal is not silently discarded.
                val lastKnown = state.currentSemantic
                    ?: return null to state   // no baseline yet — wait
                when (lastKnown) {
                    SemanticState.HEALTHY_DUAL,
                    SemanticState.CHARGING_LOST -> true   // witness was lit
                    SemanticState.WITNESS_LOST,
                    SemanticState.DUAL_LOST -> false       // witness was dark
                }
            }
        }
        val semantic = when {
            sample.chargingConnected && witnessLit -> SemanticState.HEALTHY_DUAL
            !sample.chargingConnected && witnessLit -> SemanticState.CHARGING_LOST
            sample.chargingConnected && !witnessLit -> SemanticState.WITNESS_LOST
            else -> SemanticState.DUAL_LOST
        }

        if (semantic != state.currentSemantic) {
            return onSemanticChange(state, semantic, sample.timestampMs)
        }
        return onSameSemantic(state, semantic, sample.timestampMs)
    }
```

**Why**: Guard band = "ไม่แน่ใจเรื่องไฟยืนยัน" ไม่ใช่ "ไม่รู้อะไรเลย"
- ถ้าสายชาร์จหลุดชัดเจน → ยังคง process ได้โดยใช้ค่าไฟยืนยันล่าสุด
- ถ้า Recovery อยู่ → timer ยังเดินต่อ (ครอบคลุม fix เดิม)
- ถ้าไม่เคยมี baseline (`currentSemantic == null`) → รอค่าชัดเจน (ปลอดภัย)

**ตารางพฤติกรรมใหม่:**

| State ก่อนหน้า | สายชาร์จ | Lux | ผลลัพธ์ |
|----------------|---------|-----|---------|
| HEALTHY_DUAL | หลุด | guard band | CHARGING_LOST → เริ่มนับ 10s แจ้ง charging หาย |
| HEALTHY_DUAL | เสียบอยู่ | guard band | HEALTHY_DUAL → recovery timer ยังเดิน |
| CHARGING_LOST | หลุด | guard band | CHARGING_LOST → timer เดินต่อ |
| WITNESS_LOST | หลุด | guard band | DUAL_LOST → escalate เป็น confirmed loss! |
| DUAL_LOST | เสียบกลับ | guard band | WITNESS_LOST → partial recovery |
| null (เริ่มต้น) | ใดๆ | guard band | null → รอค่าชัดเจน |

---

### Change 2: Reduce Recovery Confirmation from 30s to 10s

#### File: `ProtectionProfileModels.kt` — Line 47

**BEFORE:**
```kotlin
    val recoveryConfirmationMs: Long = 30_000L,
```

**AFTER:**
```kotlin
    val recoveryConfirmationMs: Long = 10_000L,
```

---

#### File: `ProtectionProfileCodec.kt` — Lines 283-286

**BEFORE:**
```kotlin
                val recoveryConfirmationMs = decodeOptionalLong(obj, "recoveryConfirmationMs")
                require(recoveryConfirmationMs == null || recoveryConfirmationMs == 30_000L) {
                    "Power recovery confirmation must be 30000 ms"
                }
```

**AFTER:**
```kotlin
                val recoveryConfirmationMs = decodeOptionalLong(obj, "recoveryConfirmationMs")
                require(recoveryConfirmationMs == null || recoveryConfirmationMs == 10_000L) {
                    "Power recovery confirmation must be 10000 ms"
                }
```

---

#### File: `ProtectionProfilePolicy.kt` — Lines 163-168

**BEFORE:**
```kotlin
                require(
                    overrides.recoveryConfirmationMs == null ||
                        overrides.recoveryConfirmationMs == 30_000L
                ) {
                    "Power recovery confirmation must be 30000 ms"
                }
```

**AFTER:**
```kotlin
                require(
                    overrides.recoveryConfirmationMs == null ||
                        overrides.recoveryConfirmationMs == 10_000L
                ) {
                    "Power recovery confirmation must be 10000 ms"
                }
```

---

### Change 3: Update Tests

#### File: `PowerCompositeArbiterTest.kt`

**3a.** Rename + update test `closeRequiresBothSignalsHealthyThirtySeconds` (Line 208-235)

**BEFORE:**
```kotlin
    @Test
    fun closeRequiresBothSignalsHealthyThirtySeconds() {
        val a = arbiter()
        var state = a.initialState()
        for (t in 0L..10_000L step 1_000L) {
            val (_, next) = a.evaluate(state, sample(false, false, t))
            state = next
        }
        // Both signals healthy again: no close before 30 s.
        for (t in 11_000L..39_000L step 1_000L) {
            val (verdict, next) = a.evaluate(state, sample(true, true, t))
            state = next
            assertTrue(verdict !is PowerArbiterVerdict.RecoveredClosed)
        }
        val (closed, closedState) = a.evaluate(state, sample(true, true, 41_000L))
        assertTrue(closed is PowerArbiterVerdict.RecoveredClosed)
        assertEquals("POWER-1", (closed as PowerArbiterVerdict.RecoveredClosed).episodeId)
        assertNull(closedState.episodeId)
        // A later abnormality receives a NEW powerEpisodeId.
        var reopened: PowerArbiterVerdict.ConfirmedLossOpened? = null
        var walk = closedState
        for (t in 42_000L..52_000L step 1_000L) {
            val (verdict, next) = a.evaluate(walk, sample(false, false, t))
            walk = next
            if (verdict is PowerArbiterVerdict.ConfirmedLossOpened) reopened = verdict
        }
        assertEquals("POWER-2", reopened!!.episodeId)
    }
```

**AFTER:**
```kotlin
    @Test
    fun closeRequiresBothSignalsHealthyTenSeconds() {
        val a = arbiter()
        var state = a.initialState()
        for (t in 0L..10_000L step 1_000L) {
            val (_, next) = a.evaluate(state, sample(false, false, t))
            state = next
        }
        // Both signals healthy again: no close before 10 s.
        for (t in 11_000L..20_000L step 1_000L) {
            val (verdict, next) = a.evaluate(state, sample(true, true, t))
            state = next
            assertTrue(verdict !is PowerArbiterVerdict.RecoveredClosed)
        }
        val (closed, closedState) = a.evaluate(state, sample(true, true, 21_000L))
        assertTrue(closed is PowerArbiterVerdict.RecoveredClosed)
        assertEquals("POWER-1", (closed as PowerArbiterVerdict.RecoveredClosed).episodeId)
        assertNull(closedState.episodeId)
        // A later abnormality receives a NEW powerEpisodeId.
        var reopened: PowerArbiterVerdict.ConfirmedLossOpened? = null
        var walk = closedState
        for (t in 22_000L..32_000L step 1_000L) {
            val (verdict, next) = a.evaluate(walk, sample(false, false, t))
            walk = next
            if (verdict is PowerArbiterVerdict.ConfirmedLossOpened) reopened = verdict
        }
        assertEquals("POWER-2", reopened!!.episodeId)
    }
```

---

**3b.** Update test `staleLightSamplesProduceDegradedNotOutage` (Line 255-273)

This test asserts that guard-band samples with `charging=false` produce no verdict and no episodeId.
After the fix, guard-band samples with `charging=false` will use last-known witness state.
Since `currentSemantic` starts as `null`, the first set of guard-band samples should still be inert.
But the second set (lines 267-272) feeds guard-band lux after the stale samples — `currentSemantic` is still `null`, so behavior is unchanged: no verdict.

**No change needed** — the test should still pass as-is because `state.currentSemantic` remains `null` throughout (stale samples in the first loop produce `resetWindows()` which does not set `currentSemantic`, and guard-band samples in the second loop hit the `null -> return null to state` branch).

HOWEVER: verify carefully that `resetWindows()` does NOT set `currentSemantic`. Looking at the code:
```kotlin
private fun resetWindows(state: State): State = state.copy(
    streakStartMs = null,
    streakFired = false,
    healthySinceMs = null,
)
```
It does NOT touch `currentSemantic` — but `currentSemantic` starts as `null` and is only set by `onSemanticChange()`. Since stale/unknown samples return `resetWindows()` without going through `onSemanticChange()`, `currentSemantic` stays `null` ✅.

**No modification required for this test.**

---

**3c.** Add NEW tests — append before the closing `}` of the test class (before line 289)

```kotlin
    // --- Guard-band last-known-witness fallback tests ---

    @Test
    fun chargerDisconnectedWithGuardBandLuxUsesLastKnownWitness() {
        val a = arbiter()
        var state = a.initialState()
        // Establish HEALTHY_DUAL baseline with clear readings.
        for (t in 0L..2_000L step 1_000L) {
            val (_, next) = a.evaluate(state, sample(true, true, t))
            state = next
        }
        // Charger disconnects but lux falls into guard band (50 lux).
        // Last known witness was lit → arbiter should infer CHARGING_LOST.
        for (t in 3_000L..12_000L step 1_000L) {
            val guardBandSample = PowerSignalSample(
                chargingConnected = false,
                witnessLux = 50.0,
                fresh = true,
                timestampMs = t,
            )
            val (verdict, next) = a.evaluate(state, guardBandSample)
            state = next
            // At t=13_000 (3_000 + 10_000) the charging health alert should fire.
            if (t == 12_000L) {
                // 10 s have not yet elapsed since semantic change at t=3_000.
                // The alert fires at exactly t=13_000.
            }
        }
        val at13 = PowerSignalSample(false, 50.0, true, 13_000L)
        val (verdict, _) = a.evaluate(state, at13)
        assertTrue(
            "Expected ChargingHealthAlert, got $verdict",
            verdict is PowerArbiterVerdict.ChargingHealthAlert
        )
    }

    @Test
    fun witnessLostThenChargerDisconnectsWithGuardBandEscalatesToDualLoss() {
        val a = arbiter()
        var state = a.initialState()
        // Witness goes dark (charging still connected) for 10 s → fires WitnessHealthAlert.
        for (t in 0L..10_000L step 1_000L) {
            val (_, next) = a.evaluate(state, sample(true, false, t))
            state = next
        }
        // Now: WITNESS_LOST, streakFired=true.
        // Charger disconnects, lux enters guard band.
        // Last known witness was dark → arbiter should infer DUAL_LOST.
        for (t in 11_000L..20_000L step 1_000L) {
            val guardBandSample = PowerSignalSample(
                chargingConnected = false,
                witnessLux = 50.0,
                fresh = true,
                timestampMs = t,
            )
            val (_, next) = a.evaluate(state, guardBandSample)
            state = next
        }
        val at21 = PowerSignalSample(false, 50.0, true, 21_000L)
        val (verdict, _) = a.evaluate(state, at21)
        assertTrue(
            "Expected ConfirmedLossOpened (dual-loss escalation), got $verdict",
            verdict is PowerArbiterVerdict.ConfirmedLossOpened
        )
    }

    @Test
    fun guardBandDuringRecoveryPreservesHealthyTimer() {
        val a = arbiter()
        var state = a.initialState()
        // 10 s of witness loss → fires health alert.
        for (t in 0L..10_000L step 1_000L) {
            val (_, next) = a.evaluate(state, sample(true, false, t))
            state = next
        }
        // Witness returns: healthy from t=11_000.
        val (_, healthyState) = a.evaluate(state, sample(true, true, 11_000L))
        state = healthyState
        // Healthy for 4 s, then a guard-band reading at t=15_000.
        for (t in 12_000L..14_000L step 1_000L) {
            val (_, next) = a.evaluate(state, sample(true, true, t))
            state = next
        }
        val guardBandSample = PowerSignalSample(
            chargingConnected = true,
            witnessLux = 50.0,
            fresh = true,
            timestampMs = 15_000L,
        )
        val (gbVerdict, gbState) = a.evaluate(state, guardBandSample)
        assertNull(gbVerdict)
        // Continue healthy from t=16_000; recovery must close at t=21_000 (11_000 + 10_000).
        var walk = gbState
        for (t in 16_000L..20_000L step 1_000L) {
            val (verdict, next) = a.evaluate(walk, sample(true, true, t))
            walk = next
            assertTrue("should not close early at $t", verdict !is PowerArbiterVerdict.RecoveredClosed)
        }
        val (closed, _) = a.evaluate(walk, sample(true, true, 21_000L))
        assertTrue(closed is PowerArbiterVerdict.RecoveredClosed)
    }

    @Test
    fun noBaselineGuardBandStillInert() {
        val a = arbiter()
        var state = a.initialState()
        // No clear reading ever seen (currentSemantic == null).
        // Guard-band + charger disconnected: must NOT produce any verdict.
        for (t in 0L..20_000L step 1_000L) {
            val guardBandSample = PowerSignalSample(
                chargingConnected = false,
                witnessLux = 50.0,
                fresh = true,
                timestampMs = t,
            )
            val (verdict, next) = a.evaluate(state, guardBandSample)
            state = next
            assertNull("should not produce verdict without baseline at $t", verdict)
        }
        assertNull(state.episodeId)
    }
```

---

**3d.** Update test in `ProtectionProfilePolicyTest.kt` — Line 159

**BEFORE:**
```kotlin
            PowerProfileOverrides(recoveryConfirmationMs = 29_999L),
```

**AFTER:**
```kotlin
            PowerProfileOverrides(recoveryConfirmationMs = 9_999L),
```

---

### Change 4: Update Documentation

#### File: `docs/superpowers/plans/2026-08-22-three-protection-profiles-foundation.md` — Line 116

**BEFORE:**
```kotlin
    val recoveryConfirmationMs: Long = 30_000L,
```

**AFTER:**
```kotlin
    val recoveryConfirmationMs: Long = 10_000L,
```

---

## Constraints

- Must pass `gradlew assembleDebug`
- Must pass `gradlew testDebugUnitTest --tests "*.PowerCompositeArbiterTest"`
- Must pass `gradlew testDebugUnitTest --tests "*.ProtectionProfilePolicyTest"`
- Must not modify security-critical files (SecureKeyManager, TotpAuthenticator, etc.)
- Must follow existing architecture patterns
- `ArmedProfileSnapshotCodec.kt` serializes from settings at runtime — no change needed
- `ArmedProfileSnapshotCodecTest.kt` uses `PowerProfileSettings()` default — auto-updated

## Definition of Done

- [ ] Code compiles without errors
- [ ] `gradlew assembleDebug` succeeds
- [ ] `gradlew testDebugUnitTest` passes (all tests)
- [ ] Guard-band samples use last-known witness state instead of full bail-out
- [ ] Charger disconnect is reported even when lux is in guard band (if baseline exists)
- [ ] WITNESS_LOST + charger disconnect + guard-band lux escalates to DUAL_LOST
- [ ] Recovery timer preserved during guard-band readings in HEALTHY_DUAL
- [ ] No false alarms when baseline hasn't been established (currentSemantic == null)
- [ ] Recovery confirmation closes the incident within 10 seconds (not 30)
- [ ] New test `chargerDisconnectedWithGuardBandLuxUsesLastKnownWitness` passes
- [ ] New test `witnessLostThenChargerDisconnectsWithGuardBandEscalatesToDualLoss` passes
- [ ] New test `guardBandDuringRecoveryPreservesHealthyTimer` passes
- [ ] New test `noBaselineGuardBandStillInert` passes
- [ ] Existing test `staleLightSamplesProduceDegradedNotOutage` still passes
- [ ] Existing functionality not broken
- [ ] Changes are minimal and focused
