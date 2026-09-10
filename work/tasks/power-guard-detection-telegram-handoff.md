# HANDOFF: Power Guard — แก้การตรวจจับเซนเซอร์และการแจ้งเตือน Telegram

- **สร้างเมื่อ:** 2026-09-01
- **ผู้สั่งงาน:** เจ้าของโปรเจกต์ (ผ่าน session วิเคราะห์บั๊ก)
- **Branch เป้าหมาย:** `feature/motorcycle-guard-protection`
- **Repo root:** `D:\security`
- **โมดูล:** `D:\security\MotorcycleAntiTheftSensor`

---

## 0. อ่านตรงนี้ก่อนแตะโค้ด

อาการที่เจ้าของรายงาน: **"โหมดไฟเลี้ยง (Power Guard) และขั้นตอนยืนยัน — การตรวจจับเซนเซอร์และข้อความที่ส่งไป Telegram ยังไม่ถูกต้อง"**

การวิเคราะห์เสร็จแล้ว พบบั๊กที่ยืนยันได้ **7 จุด** สิ่งที่ทำให้งานนี้ไม่ตรงไปตรงมาคือ:

> **6 ใน 7 บั๊กถูกแก้ไปแล้ว แต่โค้ดที่แก้อยู่คนละ branch** — `codex/continuity-recovery-tdd`
> (worktree: `D:\security\.worktrees\continuity-recovery-tdd`, HEAD `e06f9cf`, สะอาด ไม่มีไฟล์ค้าง)
>
> branch ที่เจ้าของ build อยู่ (`feature/motorcycle-guard-protection`, HEAD `81d4845`) **ยังเป็นโค้ดเก่าทั้งหมด**

ดังนั้น **งานหลักคือ merge ไม่ใช่เขียนใหม่** ห้ามเริ่มด้วยการประดิษฐ์ logic ใหม่เองเด็ดขาด

ตรวจสอบข้อเท็จจริงข้างต้นด้วยตัวเองก่อนเริ่ม:

```bash
git rev-list --left-right --count feature/motorcycle-guard-protection...codex/continuity-recovery-tdd
```

ต้องได้ `1` และ `37` (feature นำหน้า merge-base แค่ 1 commit / codex นำหน้า 37 commit) merge-base คือ `0765e0f`

ถ้าตัวเลขไม่ตรงนี้ **หยุดและรายงานกลับทันที** อย่าเดาต่อ

---

## 1. กฎบังคับ — ห้ามละเมิด

ทำผิดข้อใดข้อหนึ่ง = งานถูกตีกลับทั้งหมด

### 1.1 TDD บังคับ RED ก่อน GREEN
- โค้ดใหม่ทุกบรรทัดต้องมีเทสต์ที่ **fail ก่อน** แล้วจึงเขียน implementation
- ต้องแปะ output ของ RED run (ชื่อเทสต์ + จำนวน failure) ลงในรายงาน ไม่ใช่แค่บอกว่า "ทำ TDD แล้ว"
- ห้ามเขียน implementation ก่อนแล้วค่อยเขียนเทสต์ตามหลัง

### 1.2 ห้ามแก้ด้วยการเขียน logic ใหม่ทับของเดิม
- ถ้าฟีเจอร์มีอยู่แล้วใน `codex/continuity-recovery-tdd` ให้ **นำเข้าโค้ดนั้น** ไม่ใช่ประดิษฐ์ทางแก้ของตัวเอง
- `PowerCompositeArbiter`, `PowerArmedSessionController`, `AndroidProtectionRuntime` ฝั่ง codex ผ่าน review และ device acceptance มาแล้ว ให้ถือเป็นของจริง

### 1.3 ห้ามลด scope เงียบๆ
- ถ้าติดบั๊กใดแก้ไม่ได้ ให้ทำที่เหลือให้ครบ แล้ว **ระบุชัดเจน** ว่าข้อไหนไม่ได้ทำและเพราะอะไร
- ห้ามรายงานว่า "เสร็จ" ถ้ายังมีข้อที่ไม่ได้แตะ

### 1.4 ห้ามยิงข้อความจริงออก Telegram
- ห้ามรัน flow ที่ส่งข้อความจริงไปหาเจ้าของ ห้ามรัน `/status` จริง
- การพิสูจน์ทั้งหมดต้องทำผ่าน unit / integration test ที่ mock transport
- การทดสอบทางกายภาพ (ถอดสายจริง, ปิด-เปิดหลอดไฟจริง) เป็นหน้าที่เจ้าของ ไม่ใช่ของ agent

### 1.5 ห้ามแตะสิ่งที่อยู่นอก scope
- ห้าม reformat ไฟล์ ห้ามจัด import ใหม่ ห้าม rename ที่ไม่เกี่ยวข้อง
- ห้าม revert / reset / stash การเปลี่ยนแปลงที่มีอยู่เดิมใน working tree
- ไฟล์ที่ยัง untracked อยู่ตอนนี้ (`moto-guard-*.xml`, `.hermes/`, `MotorcycleAntiTheftSensor/docs/user-guide-power-guard.md`, `plans/checkpoint-*.md`) **ห้ามลบ ห้ามแก้**

### 1.6 ห้าม commit / push โดยไม่ได้รับอนุญาต
- ทำงานให้เสร็จใน working tree แล้วรายงาน
- ยกเว้นข้อเดียว: การ merge ใน Phase 0 สร้าง merge commit ได้ เพราะเป็นเนื้องานเอง
- **ห้าม `push` ทุกกรณี**

### 1.7 Gate การตรวจต้องผ่านครบก่อนรายงานเสร็จ

```bash
cd /d/security/MotorcycleAntiTheftSensor && JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ANDROID_HOME='C:\Users\ASUS\AppData\Local\Android\Sdk' ./gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug --no-daemon --max-workers=1
```

ต้องได้ exit code 0 และต้องรายงานตัวเลขจริงจาก XML report: suites / tests / failures / errors / skipped
(baseline ฝั่ง codex ก่อนหน้าคือ 113 suites, 913 tests, 0 failures — หลัง merge ตัวเลขจะเปลี่ยน รายงานตามจริง ห้ามลอกตัวเลขนี้)

---

## 2. แผนงาน

### Phase 0 — Merge (ปิดบั๊ก #1–#5 และ #7 พร้อมกัน)

```bash
cd /d/security && git checkout feature/motorcycle-guard-protection && git merge codex/continuity-recovery-tdd
```

**คาดการณ์ conflict:** ~62 hunk ใน ~20 ไฟล์ ดูรายชื่อล่วงหน้าได้ด้วย:

```bash
cd /d/security && git merge-tree $(git merge-base feature/motorcycle-guard-protection codex/continuity-recovery-tdd) feature/motorcycle-guard-protection codex/continuity-recovery-tdd | grep -B1 "<<<<<<<" | head -60
```

**กฎการตัดสิน conflict — บังคับ:**

| ประเภทไฟล์ | ยึดฝั่งไหน | เหตุผล |
|---|---|---|
| `protection/Power*.kt`, `protection/AndroidProtectionRuntime.kt`, `protection/IncidentEngine.kt`, `protection/IncidentMessageFormatter.kt`, `protection/PresentationTextCatalog.kt`, `protection/ProtectionDiagnostics.kt`, `sensor/PowerThermalMonitor.kt` | **codex** เกือบทั้งหมด | นี่คือตัวแก้บั๊ก ห้ามให้ฝั่ง feature ทับ |
| `ui/**` (ยกเว้น `PowerGuardSection.kt`) | **feature** เป็นหลัก | คือ profile-aware home UI + Thai l10n ที่เจ้าของเพิ่งทำเสร็จและ verify บนเครื่องจริงแล้ว |
| `ui/protection/PowerGuardSection.kt` | **ผสม** | เก็บ layout/สไตล์ฝั่ง feature **และ** ต้องเก็บปุ่ม `actions.powerMarkChallengePassed` จากฝั่ง codex ให้ครบ (ดูบั๊ก #5) |
| `protection/UserGuidance.kt`, `telegram/ProtectionStatusProjection.kt` | **ผสม** | feature ทำ installation-neutral copy, codex ทำ `GuidanceDetail.IncidentTypeValue` — ต้องได้ทั้งสองอย่าง |
| `service/SensorService.kt`, `protection/ProtectionRuntime.kt` | **ผสม** | feature เพิ่ม resource-leak fix (`stopPowerStatusMonitoring`), codex ย้าย/เพิ่ม interface method เดียวกัน — รวมให้ได้ทั้งคู่ ห้ามเหลือ method ซ้ำหรือหาย |
| `app/src/test/**`, `app/src/androidTest/**` | **ผสม** | ห้ามลบเทสต์ทิ้งเพื่อให้ผ่าน ถ้า assertion ขัดกันเพราะข้อความไทยเปลี่ยน ให้แก้ assertion ให้ตรงกับ copy ที่ merge แล้ว |

**ห้ามเด็ดขาด:** ห้ามใช้ `-X ours` / `-X theirs` แบบเหมารวมทั้ง merge ห้ามใช้ `git checkout --ours/--theirs` กับไฟล์ในหมวดที่ระบุว่า "ผสม"

---

### Phase 1 — Anti-regression checklist หลัง merge (บังคับ ห้ามข้าม)

conflict resolution ที่พลาดจะ "กลืน" ตัวแก้บั๊กหายไปเงียบๆ โดยที่ยัง compile ผ่าน ให้รัน grep ต่อไปนี้ **ทุกข้อ** และแปะผลจริงลงรายงาน — ทุกข้อต้องเจอ:

```bash
cd /d/security/MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor
grep -n "powerConfirmationRunnable"      protection/AndroidProtectionRuntime.kt
grep -n "PowerWitnessContinuityCache"    protection/AndroidProtectionRuntime.kt
grep -n "powerSessionActive"             protection/AndroidProtectionRuntime.kt
grep -n "nextConfirmationAtMs"           protection/PowerArmedSessionController.kt
grep -n "lastConclusiveWitnessLit"       protection/PowerCompositeArbiter.kt
grep -n "invalidateEvidenceContinuity"   protection/PowerCompositeArbiter.kt
grep -n "incidentTypeLabel"              protection/IncidentMessageFormatter.kt
grep -n "recoveryConfirmationMs: Long = 10_000L" protection/ProtectionProfileModels.kt
grep -n "powerMarkChallengePassed"       ui/protection/PowerGuardSection.kt
```

และ grep ต่อไปนี้ **ต้องไม่เจออะไรเลย** (ของเก่าต้องหายหมด):

```bash
cd /d/security/MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor
grep -rn "incident.type.name" protection/
grep -n  "private var lastWitnessLux" protection/AndroidProtectionRuntime.kt
```

ถ้าข้อใดผิดคาด = conflict resolution ผิด ให้ย้อนไปแก้ **ห้ามเดินหน้าต่อ**

---

### Phase 2 — บั๊ก #6 (งานใหม่ ยังไม่มีใครแก้ในทั้งสอง branch)

#### อาการ
ขั้นตอนปรับเทียบไฟยืนยัน ("ขั้นที่ 1/2: ปิดไฟยืนยันทิ้งไว้…") ค้างไม่จบ เจ้าของจึงผ่านขั้นยืนยันไม่ได้เลย

#### สาเหตุราก
`Sensor.TYPE_LIGHT` บน Android เป็น **on-change sensor** — ค่าคงที่แปลว่า **ไม่ส่ง event ใหม่**

- `PowerWitnessCommissioningPolicy` ต้องการ sample ต่อเนื่อง ห่างกันไม่เกิน `maxSampleGapMs = 2_000` (`PowerWitnessCommissioningPolicy.kt:77`) มิฉะนั้น `beginWindow()` รีเซ็ต window ใหม่
- แต่เงื่อนไขที่กำลังวัดคือ "ปิดไฟทิ้งไว้ให้นิ่ง" — **ยิ่งนิ่ง ยิ่งไม่มี event** → ห่างเกิน 2 วิ → รีเซ็ตวนไม่จบ
- `powerSampleFlow.tryEmit(...)` ถูกเรียกจาก **ภายใน `onSensorChanged` เท่านั้น** ไม่มีตัวป้อนซ้ำตามเวลา
- ฝั่ง codex ยัง**ทำให้แย่ลง**: เพิ่ม `windowDurationMs` จาก `5_000L` เป็น `10_000L` (`ui/ProtectionViewModel.kt` ราวบรรทัด 363) ต้องนิ่งนานขึ้นอีกเท่าตัว

นี่คือปัญหาเดียวกับบั๊ก #1 ฝั่ง armed session ซึ่ง codex แก้แล้วด้วย `powerConfirmationRunnable` แต่ **ไม่ได้แก้ฝั่ง commissioning**

#### แนวทางที่บังคับให้ใช้ — ห้ามเลือกทางอื่น

**ให้แก้ที่ runtime ไม่ใช่ที่ policy** โดยเลียนแบบ pattern ของ `powerConfirmationRunnable` ที่มีอยู่แล้วในไฟล์เดียวกัน (`PowerWitnessCommissioningPolicy` ต้องคง purity ไว้ ห้ามยัด Handler/นาฬิกาเข้าไป)

ใน `protection/AndroidProtectionRuntime.kt` (คลาส detector set ตัวจริง ไม่ใช่ interface default):

1. เพิ่ม `powerCommissioningRepeatRunnable: Runnable` คู่กับ `powerConfirmationRunnable` ที่มีอยู่
2. ทำงานเมื่อ `powerCommissioningStreamActive == true` เท่านั้น: อ่านค่าล่าสุดจาก `powerWitnessContinuity.latest()` แล้ว emit `PowerWitnessSample(lux = cached.lux, timestampMs = SystemClock.elapsedRealtime(), fresh = true)` เข้า `powerSampleFlow` แล้ว post ตัวเองซ้ำที่ **1000 ms**
3. เริ่ม post ครั้งแรกใน `startPowerCommissioningStream()` **หลัง** `registerPowerLightSource()` สำเร็จ
4. `handlerOwner.handler.removeCallbacks(powerCommissioningRepeatRunnable)` ต้องถูกเรียกที่ **ทุกจุดต่อไปนี้ ห้ามขาดข้อใดข้อหนึ่ง**:
   - `stopPowerCommissioningStream()`
   - `unregisterPowerLightSource()`
   - `stop()` (ที่เดียวกับที่ removeCallbacks ของ `powerConfirmationRunnable`)
5. ใช้ handler เดิม `handlerOwner.handler` เท่านั้น **ห้ามสร้าง thread / coroutine / Timer ใหม่**

**เงื่อนไขความถูกต้องของหลักฐาน — สำคัญที่สุดของข้อนี้:**

- ห้าม emit ถ้ายังไม่เคยมี reading จริงเข้ามาใน listener continuity ปัจจุบัน (`latest() == null` → ไม่ emit แต่ post ตัวเองซ้ำได้)
- ห้าม emit ถ้า `powerLightListener == null` (listener หลุดไปแล้ว)
- ค่าที่ emit ซ้ำ **ต้องเป็นค่าเดิมจริงๆ ห้ามประมาณ ห้ามหน่วง ห้าม interpolate ห้ามใส่ noise** — เหตุผลที่ทำแบบนี้ถูกต้องคือสัญญาของ on-change sensor: ไม่มี event ใหม่ = ค่ายังเท่าเดิม ค่าที่ re-emit จึงเป็นค่าปัจจุบันจริง ไม่ใช่หลักฐานปลอม **ให้เขียนเหตุผลนี้เป็น comment กำกับไว้ในโค้ด**
- เมื่อ `unregisterPowerLightSource()` ทำงาน ต้องเรียก `powerWitnessContinuity.endListenerContinuity()` เพื่อไม่ให้ค่าข้าม listener gap (ถ้าโค้ดที่ merge มายังไม่ทำ ให้เพิ่ม)

**ลด `windowDurationMs` กลับเป็น `5_000L`** ที่ `ui/ProtectionViewModel.kt` — เมื่อมี repeater 1 Hz แล้ว 5 วินาที = ~5 sample ซึ่งเพียงพอ และคืนประสบการณ์ผู้ใช้ให้ตรงกับข้อความ UI

> UI เขียนว่า "5 วินาที" ใน `ui/protection/PowerGuardSection.kt` — ห้ามปล่อยให้ UI บอก 5 วิ แต่โค้ดใช้ 10 วิ ถ้าเลือกค่าอื่นต้องแก้ข้อความ UI ให้ตรงกันด้วย

#### เทสต์ที่บังคับต้องมี (RED ก่อน)

ใน `app/src/test/java/.../protection/AndroidProtectionRuntimeTest.kt` (หรือไฟล์เทสต์ที่คุม runtime ตัวนี้หลัง merge):

1. `commissioningStream_stableLux_reEmitsSampleWithinGapBudget` — stream active + มี cached reading → ต้องมี sample ใหม่ห่างจากตัวก่อนหน้า `<= maxSampleGapMs`
2. `commissioningStream_noRealReadingYet_doesNotEmit` — ยังไม่เคยมี reading จริง → ต้องไม่ emit อะไรเลย
3. `commissioningStream_stopped_stopsRepeating` — หลัง `stopPowerCommissioningStream()` → ต้องไม่มี sample เพิ่มอีก
4. `commissioningStream_listenerGap_doesNotReuseOldLux` — หลัง unregister/re-register → ค่าเก่าต้องไม่ถูก emit ซ้ำ

ใน `PowerWitnessCommissioningPolicyTest.kt`:

5. `darkWindow_completesWithRepeatedIdenticalLux` — ป้อน sample ค่าเดิมทุก 1 วิ ติดกัน 5 วิ → ต้องเลื่อนไปเฟส `LIT_WINDOW`

---

## 3. ตารางบั๊กทั้งหมด — ใช้เป็น checklist ปิดงาน

| # | บั๊ก | ตำแหน่งเดิม (branch feature) | แก้โดย | ยืนยันด้วย |
|---|---|---|---|---|
| 1 | timer 10 วิ ไม่มีวันครบ เพราะ arbiter เดินเวลาได้เฉพาะตอนมี sensor event → ถอดสายแล้วไม่แจ้ง Telegram | `AndroidProtectionRuntime.kt:830, :393`, `PowerCompositeArbiter.kt:128` | Phase 0 | grep `powerConfirmationRunnable`, `nextConfirmationAtMs` |
| 2 | lux ตกใน guard band → `return null to resetWindows(state)` ทิ้งสัญญาณ charging ที่ชัดเจนทั้งก้อน + ล้าง timer | `PowerCompositeArbiter.kt:69-73` | Phase 0 | grep `lastConclusiveWitnessLit` |
| 3 | `fresh = witnessLux != null` ไม่เช็คอายุค่าเลย ใช้ lux ค้างข้าม listener gap → verdict ผิดชนิด → ข้อความ Telegram ผิดประเภท | `AndroidProtectionRuntime.kt:859-868, :483-489` | Phase 0 | grep `PowerWitnessContinuityCache`; ต้องไม่เหลือ `lastWitnessLux` |
| 4a | raw `charger_disconnected` เปิด incident POWER/CRITICAL ทันทีโดยไม่สนว่ามี armed POWER session → แจ้งเร็วกว่ากติกา 10 วิ และซ้ำกับ verdict | `IncidentEngine.kt:150-164`, `PowerThermalMonitor.kt:41-46` | Phase 0 | grep `powerSessionActive` |
| 4b | Telegram ส่งข้อความดิบ `🚨 ตรวจพบ POWER` (ชื่อ enum ไม่แปล) | `IncidentMessageFormatter.kt:232, :267` | Phase 0 | grep `incidentTypeLabel`; ต้องไม่เหลือ `incident.type.name` |
| 5 | `powerMarkChallengePassed` ต่อสายจาก Navigation แต่**ไม่มี composable ไหนเรียก** → `isSatisfied()` false เสมอ → Arm ติด `POWER_CHALLENGE_DEGRADED` ตลอด เจ้าของผ่านขั้นยืนยันไม่ได้ | `ProtectionAppScreen.kt:52`, `Navigation.kt:143`, ไม่มีใน `PowerGuardSection.kt` | Phase 0 (ระวัง conflict) | grep `powerMarkChallengePassed` ใน `PowerGuardSection.kt` |
| 6 | commissioning window ไม่มีวันจบ เพราะ on-change sensor ไม่ส่ง event ตอนค่านิ่ง | `ProtectionViewModel.kt:381-386`, `PowerWitnessCommissioningPolicy.kt:77` | **Phase 2 (งานใหม่)** | เทสต์ข้อ 1–5 ในหัวข้อ 2 |
| 7 | `recoveryConfirmationMs = 30_000L` ขัดกับสเปกที่อนุมัติแล้ว (10 วิ) | `ProtectionProfileModels.kt:47` | Phase 0 | grep `recoveryConfirmationMs: Long = 10_000L` |

---

## 4. Definition of Done

ปิดงานได้เมื่อครบทุกข้อ:

- [ ] Merge สำเร็จ ไม่มี conflict marker หลงเหลือ — `grep -rn "<<<<<<<\|>>>>>>>" MotorcycleAntiTheftSensor/app/src/` ต้องว่าง
- [ ] Anti-regression checklist Phase 1 ผ่านครบทุกบรรทัด พร้อมแปะผล grep จริง
- [ ] บั๊ก #6 แก้แล้วตามแนวทางในหัวข้อ 2 พร้อมเทสต์ครบ 5 ตัว
- [ ] มีหลักฐาน RED (output ที่ fail จริง พร้อมชื่อเทสต์) ก่อน GREEN ของทุกเทสต์ที่เขียนใหม่
- [ ] Gate เต็มผ่าน exit code 0 พร้อมตัวเลข suites/tests/failures/errors/skipped จริงจาก XML
- [ ] `git diff --check` ไม่มี whitespace error (LF-to-CRLF notice เดิมไม่นับ)
- [ ] `assembleDebug` สร้าง APK ได้ พร้อมรายงาน path / ขนาด / เวลา build
- [ ] ไม่มีการ push ไม่มีการลบหรือแก้ไฟล์ untracked เดิม

**ไม่ต้องทำ (นอก scope):**
- ติดตั้งลงเครื่องจริง (`adb install`) — ทำได้ถ้าเครื่อง `JUCDU18811013149` authorized แต่ไม่บังคับ
- ทดสอบทางกายภาพ: ถอดสายจริง / ปิด-เปิดหลอดไฟจริง / ส่ง Telegram จริง — **เป็นหน้าที่เจ้าของเท่านั้น**
- แก้ UI นอกเหนือจาก conflict resolution และปุ่มยืนยันในบั๊ก #5
- แตะโปรไฟล์ Vehicle / Entry นอกเหนือจากที่ merge พามา

---

## 5. รูปแบบรายงานที่ต้องส่งกลับ

```
## ผลการทำงาน

### Phase 0 — Merge
- คำสั่ง merge ที่ใช้ + จำนวนไฟล์ที่ conflict จริง
- ตารางการตัดสิน conflict: ไฟล์ / เลือกฝั่งไหน / เหตุผล
  (เฉพาะไฟล์ที่ตัดสินไม่ตรงตารางในหัวข้อ 2 ต้องอธิบายเพิ่ม)

### Phase 1 — Anti-regression
- ผล grep ทั้ง 9 + 2 บรรทัด (แปะจริง ไม่ใช่สรุป)

### Phase 2 — บั๊ก #6
- RED: ชื่อเทสต์ที่ fail + จำนวน
- GREEN: ชื่อเทสต์ที่ผ่าน
- ไฟล์ที่แก้ + สรุปการเปลี่ยนแปลงแต่ละไฟล์

### Gate
- คำสั่งเต็ม + exit code
- suites / tests / failures / errors / skipped
- git diff --check
- APK: path, ขนาด, เวลา

### สิ่งที่ไม่ได้ทำ และเหตุผล
- (ถ้ามี — ห้ามเว้นว่างถ้ามีจริง)

### ความเสี่ยงที่เหลืออยู่
- (เช่น พฤติกรรม vendor HAL ที่ยังต้องให้เจ้าของทดสอบกายภาพ)
```

---

## 6. อ้างอิง

- `work/tasks/power-guard-witness-recovery-fix.md` — สเปกและ safety amendment ที่อนุมัติแล้ว (2026-09-01) **ข้อกำหนดในไฟล์นั้นยังมีผลบังคับ**
- `plans/checkpoint-2026-08-30-power-guard-restart-recovery.md` — บันทึกงานฝั่ง `codex/continuity-recovery-tdd` ทั้งหมด อ่านหัวข้อ "Last-known witness guard-band fallback (2026-09-01)" และ "Power alert ownership and localized delivery follow-up (2026-08-31)"
- worktree อ้างอิง (อ่านได้ **ห้ามแก้**): `D:\security\.worktrees\continuity-recovery-tdd`
