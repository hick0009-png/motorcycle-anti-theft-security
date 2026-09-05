# Checkpoint — `/status` ต่อโหมด: **ลงมือครบ S1-S9 แล้ว** (2026-09-05)

**สเปกเต็ม:** [telegram-status-per-mode-design-2026-09-05.md](telegram-status-per-mode-design-2026-09-05.md)
**สถานะโค้ด:** เขียนครบทั้ง S1-S9 · เทสต์เขียวทั้งหมด
**branch:** `feature/motorcycle-guard-protection` · **head:** `ce2b67c`

---

## 1. คอมมิต

| commit | ขั้น | สาระ |
|---|---|---|
| `c27e6ac` | S1 + S2 | `ProtectionModeContext` ลง snapshot · `entryLevel` ลง `ArmedProfileSnapshot` · ปั๊มวันปรับเทียบลงโมเดลทั้งสอง |
| `2ddc5db` | S3-S5, S7 | รายงานแบบรู้โหมด: กรองเซ็นเซอร์ · ตัวหารจริง · ส่วน A-G ใหม่ · เนื้อเฉพาะโหมด · สถานะขอบ |
| `df58adb` | S6 | `LiveStatusReader` ค่าอ่านสด ต่อสาย graph → handler |
| `ce2b67c` | S8 + S9 | ทักเมื่อเกินเพดาน drift · `/help` · คู่มือ |

## 2. การตรวจสอบ

```
gradlew --offline --no-daemon --max-workers=1 testDebugUnitTest compileDebugAndroidTestKotlin
→ BUILD SUCCESSFUL · 1,256 tests · 0 failed
```

เทสต์ใหม่ 25 ข้อ:
`ProtectionStatusModeReportTest` (15) · `EntryDriftCeilingWarningPolicyTest` (8) ·
`HeartbeatCeilingWarningTest` (2) · เพิ่มใน `ArmedProfileSnapshotCodecTest` (3) และ
`TelegramCommandHandlerTest` (2)

ครบทั้ง 15 ข้อของ §11 ในสเปก

---

## 3. สามข้อที่ตัดสินต่างจากสเปก — ต้องให้เจ้าของรับทราบ

### 3.1 ⚠️ ไม่พิมพ์พิกัดลงในแชต (ขัดกับตัวอย่าง §13.1)

§13.1 เขียนตัวอย่างไว้ว่า `พิกัดล่าสุด: 13.7563, 100.5018 (±19 ม.)`
แต่ `ProtectionStatusFormatterTest.test08_privacyAssertionsStrictNoLeaks` ตรึงไว้ตรงข้าม:
`/status` ห้ามมีรูปแบบพิกัดทศนิยมเด็ดขาด — กฎนี้มาจากการแก้ VULN-08 และคอมเมนต์ของ
`HeartbeatPinger` ก็ยืนยันเจตนาเดียวกัน

ประวัติแชต Telegram ค้างอยู่บนทุกเครื่องที่ล็อกอินไว้ ถ้าเครื่องถูกขโมยไปพร้อมรถ
พิกัดบ้านจะอยู่ในมือคนร้าย จึงเลือกกฎเดิม พิมพ์เป็น:

```
พิกัดล่าสุด: มีแล้ว ±19 เมตร (ดูตำแหน่งด้วย /where)
```

**ถ้าเจ้าของยืนยันว่าต้องการพิกัดใน `/status` จริง ๆ ต้องแก้เทสต์ความเป็นส่วนตัวด้วย — ยังไม่ได้ทำ**

### 3.2 ตัวเลข "เกณฑ์ระยะห่างจากจุดจอด" คือ 100 เมตร ไม่ใช่ 30

§13.1 เขียน `(ยังไม่เกินเกณฑ์ 30 เมตร)` แต่ค่าจริงคือ
`MovementDisplacementPolicy.BASE_DISPLACEMENT_METERS = 100.0`
(และเกณฑ์จริงคือ `max(100, ความแม่นยำสองฝั่ง + 30)`) ใช้ค่าจริงตามกฎ §5 ข้อ 2

### 3.3 ลูกค้าที่ยังไม่เคยเลือกโหมด ได้รายงาน "แบบเดิมทั้งอัน" ไม่ใช่แค่เซ็นเซอร์ครบห้า

§8.1 เขียนว่า "พิมพ์แบบเดิมทั้งห้าชนิด พร้อมบรรทัดนำหน้า" — ตีความตามตัวอักษร คือ
เก็บเลย์เอาต์เดิมไว้ทั้งหมด (รวมลำดับส่วนเดิมและป้าย `[ 🏃 การเคลื่อนไหว ]`)
แล้วเติมบรรทัดเตือนไว้ข้างบน

ผลคือเทสต์เดิมทุกตัวยังเขียวโดยไม่ต้องแก้ (`test02_sectionOrderIsConstant` รวมอยู่ด้วย)
และอาการ §1.2 ถูกแก้ตรงที่มันเป็นปัญหาจริง — คือในโหมดประตูกับไฟเลี้ยง
ส่วนลูกค้าที่ไม่มีโหมด สไลเดอร์ความไวยังคุมตัวตรวจการสั่นอยู่จริง การพิมพ์จึงไม่ผิด

**สรุป: มีสองเลย์เอาต์** — `formatForMode` กับ `formatWithoutMode` ในไฟล์เดียวกัน

---

## 4. โครงที่ได้

```
ProtectionSnapshot.modeContext : ProtectionModeContext?   ← ชั้น 1 คงทน เข้า ProjectionKey
  ├ selectedProfile / entryLevel / setupState / support / switchingTo
  └ modeFacts : VehicleModeFacts | EntryModeFacts | PowerModeFacts

LiveStatusReadings                                        ← ชั้น 2 อ่านตอนถูกถาม ไม่ลง snapshot
  └ อ่านผ่าน LiveStatusReader ที่ graph สร้าง handler เรียกตอนตอบ /status เท่านั้น

ProtectionStatusProjection.evaluate(snapshot, wall, elapsed, live)
  └ modeIdentity (A) · watchScope (B) · modeSections (C) · sensorSummary (D)
    · primarySystems + battery (E) · lastIncident (F) · issuesSummary (G)
```

**กฎที่กันปัญหาไม่ให้กลับมา:** ทุกอย่างในส่วน B/D/G อ่านจาก
`ProtectionProfilePolicy.signalRoles()` โดยตรง เทสต์
`sensorRolesInStatusMatchProtectionProfilePolicy` เทียบผลลัพธ์กับ policy ทุกโหมด×ทุกระดับ

---

## 5. รายละเอียดที่ควรรู้เมื่อกลับมาแก้

- **`renameRow()`** (`ProtectionStatusProjection.kt`) แปลงบรรทัดเซ็นเซอร์ที่เรนเดอร์แล้ว
  `<ไอคอน> <ชื่อ>: <รายละเอียด>` ให้เป็นชื่อของโหมดพร้อมป้ายบทบาท
  เลือกทางนี้แทนการร้อยชื่อกับบทบาทผ่านจุดสร้าง `SensorItemProjection` ~40 จุดในห้าฟังก์ชัน
  ซึ่งทุกจุดจะกลายเป็นที่ที่ตารางบทบาทแตกต่างกันได้ · บรรทัดที่รูปแบบไม่ตรงจะคืนของเดิมไม่แตะ
- **แถว `ทิศทาง/มุม`** ในโหมดประตูระดับ `DOOR_ANGLE` เป็นแถวแสดงผลเพิ่ม
  ใช้ health ตัวเดียวกับ `VIBRATION` **ไม่ถูกนับในตัวหาร** (ตัวหาร = จำนวน `SensorKind` ที่ไม่ OFF)
  จึงเห็น 6 แถวบนตัวหาร 5 ในโหมดนั้น
- **`refreshModeContext()`** ถูกเรียกใน `init`, ทุก `transition()`, `selectProfile`,
  `updateSelectedProfile`, `changeProfile` และ `ProtectionViewModel.refreshProfile()`
  **ไม่ได้เรียกใน `evaluateFreshness`** (ทุก 5 วินาที) โดยตั้งใจ — จะเป็นการถอด JSON ทิ้งเปล่า
- **`commissionedAtWallMs`** เป็นฟิลด์ใหม่ใน `EntryHingeModel`/`PowerWitnessModel`
  ปั๊มที่ `ProtectionProfilePolicy.commissionEntry/commissionPower` (ชั้นเดียวที่มีนาฬิกา)
  **ไม่อยู่ใน `fingerprint()`** จึงไม่ทำให้ต้อง recommission · โมเดลเก่าได้ null
  แล้วพิมพ์ว่า "ปรับเทียบแล้ว (ไม่ได้บันทึกวันเวลาไว้)"
- **`entryLevel` ใน `ArmedProfileSnapshotCodec`** เป็น optional key ใน schema version 1 เดิม
  session ที่แช่แข็งไว้ก่อนหน้านี้ decode ได้เป็น null

## 6. หนี้ที่เหลือ

- ~~คู่มือ PDF ยังเป็นของเก่า~~ — **จบแล้ว** re-render จาก HTML เรียบร้อย (13 → 14 หน้า)
  และลบสำเนาซ้ำ `MotorcycleAntiTheftSensor_User_Manual_v2.5.pdf` ที่รากทิ้ง
  (มันเป็นไฟล์เดียวกับ `docs/...User_Manual.pdf` ทุก byte ไม่ใช่คนละเวอร์ชัน)
- **S6 ยังไม่ได้ทดสอบบนเครื่องจริง** — `liveDoorAngleDeg` / `liveWitnessLit` /
  `liveConfirmationCountdownMs` ผ่านเทสต์ระดับ host แต่ยังไม่เคยเห็นค่าจริงจากเซ็นเซอร์
- **ยังไม่ทำ (ตาม §12):** `/status <โหมด>` · inline keyboard ·
  `ProtectionStateTelegramNotifier` ยังไม่รู้จักโหมด · ข้อความ heartbeat ทุก 15 นาที
  ยังพูดกลาง ๆ ว่า Armed/Disarmed (S9 ยืมแค่จังหวะของมัน)

## 7. คำสั่ง resume

```
cd D:\security\MotorcycleAntiTheftSensor
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
gradlew --offline --no-daemon --max-workers=1 testDebugUnitTest
```

## 8. สร้างคู่มือ PDF ใหม่

`docs/MotorcycleAntiTheftSensor_User_Manual.html` คือต้นฉบับ · PDF เป็นผลผลิต
เดิมสร้างด้วย Chrome print-to-PDF (metadata ของไฟล์เก่าเขียนว่า `Skia/PDF m151`)
ไม่มีสคริปต์ในรีโป จึงบันทึกคำสั่งไว้ตรงนี้:

```
"C:\Program Files\Google\Chrome\Application\chrome.exe" --headless=new --disable-gpu ^
  --no-pdf-header-footer ^
  --print-to-pdf="D:\security\docs\MotorcycleAntiTheftSensor_User_Manual.pdf" ^
  "file:///D:/security/docs/MotorcycleAntiTheftSensor_User_Manual.html"
```

**แก้ HTML แล้วต้องรันคำสั่งนี้เสมอ** ไม่งั้น PDF กับ HTML จะพูดคนละเรื่องกันเงียบ ๆ
ซึ่งเป็นสิ่งที่เกิดขึ้นมาแล้วรอบนี้
