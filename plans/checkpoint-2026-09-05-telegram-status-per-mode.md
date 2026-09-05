# Checkpoint — `/status` ต่อโหมด: **ลงมือครบ S1-S9 แล้ว** (2026-09-05)

**สเปกเต็ม:** [telegram-status-per-mode-design-2026-09-05.md](telegram-status-per-mode-design-2026-09-05.md)
**สถานะโค้ด:** เขียนครบทั้ง S1-S9 · เทสต์เขียวทั้งหมด
**branch:** `feature/motorcycle-guard-protection` · **head:** `f63aab7`

---

## 1. คอมมิต

| commit | ขั้น | สาระ |
|---|---|---|
| `c27e6ac` | S1 + S2 | `ProtectionModeContext` ลง snapshot · `entryLevel` ลง `ArmedProfileSnapshot` · ปั๊มวันปรับเทียบลงโมเดลทั้งสอง |
| `2ddc5db` | S3-S5, S7 | รายงานแบบรู้โหมด: กรองเซ็นเซอร์ · ตัวหารจริง · ส่วน A-G ใหม่ · เนื้อเฉพาะโหมด · สถานะขอบ |
| `df58adb` | S6 | `LiveStatusReader` ค่าอ่านสด ต่อสาย graph → handler |
| `ce2b67c` | S8 + S9 | ทักเมื่อเกินเพดาน drift · `/help` · คู่มือ |
| `57c1d55` | — | สร้างคู่มือ PDF ใหม่จาก HTML · ลบสำเนาซ้ำที่ราก |
| `f63aab7` | — | เจ้าของเคาะสามข้อ: เหลือเลย์เอาต์เดียว · เกณฑ์ระยะที่ใช้จริง · แก้สเปก |

## 2. การตรวจสอบ

```
gradlew --offline --no-daemon --max-workers=1 testDebugUnitTest compileDebugAndroidTestKotlin
→ BUILD SUCCESSFUL · 1,257 tests · 0 failed
```

เทสต์ใหม่ 25 ข้อ:
`ProtectionStatusModeReportTest` (15) · `EntryDriftCeilingWarningPolicyTest` (8) ·
`HeartbeatCeilingWarningTest` (2) · เพิ่มใน `ArmedProfileSnapshotCodecTest` (3) และ
`TelegramCommandHandlerTest` (2)

ครบทั้ง 15 ข้อของ §11 ในสเปก

---

## 3. สามข้อที่เคยต่างจากสเปก — เจ้าของเคาะแล้วทั้งหมด (รอบบ่าย)

### 3.1 ✅ พิกัดไม่พิมพ์ลงแชต — ยืนยันตามโค้ด แก้ที่เอกสาร

§13.1 ของสเปกร่างไว้ว่าพิมพ์ `13.7563, 100.5018` แต่
`ProtectionStatusFormatterTest.test08_privacyAssertionsStrictNoLeaks` ตรึงตรงข้ามไว้
ตั้งแต่แก้ VULN-08 · เจ้าของเลือกกฎเดิม → **แก้สเปก §13.1 และเพิ่ม §14.0 อธิบายเหตุผล**
`/status` พิมพ์ `พิกัดล่าสุด: มีแล้ว ±19 เมตร (ดูตำแหน่งด้วย /where)`

### 3.2 ✅ ไม่ลดเกณฑ์ระยะ แต่พิมพ์เกณฑ์ที่ใช้จริง

เลข 30 ในร่างคือ `ACCURACY_MARGIN_METERS` ไม่ใช่เกณฑ์การขยับ · เกณฑ์จริงคือ
`max(100, ความแม่นยำสองจุด + 30)` และ 100 นั้นคุม **การเริ่มไล่ตามสด**
(`LivePursuitCoordinator.kt:394` สตรีมพิกัด 15 นาที) ไม่ได้คุมการแจ้งเตือนโจรกรรม
ซึ่งมาจากการสั่น/สายชาร์จ/แสง → **คงไว้ที่ 100 ไม่แตะการตรวจจับ**

`/status` เปลี่ยนจากพิมพ์ค่าคงที่ เป็นพิมพ์เกณฑ์ที่จะถูกบังคับใช้กับพิกัดคู่นั้นจริง ๆ
สูตรย้ายเข้า `MovementDisplacementPolicy.displacementThresholdMeters()` เพื่อให้
รายงานกับตัวตรวจจับตอบเป็นเลขเดียวกันเสมอ — ตอนแรกผมเขียนสูตรซ้ำไว้ที่ graph
ซึ่งคือความผิดพลาดชนิดเดียวกับที่งานนี้เกิดมาเพื่อลบ แก้แล้วในคอมมิตเดียวกัน

### 3.3 ✅ เหลือเลย์เอาต์เดียว ลบของเก่าทิ้ง

`formatWithoutMode` ถูกลบ · ทุก installation ได้ลำดับเดียวกัน
คนที่ยังไม่เลือกโหมดยังเห็นเซ็นเซอร์ครบห้าชนิด (กรองไม่ได้เพราะไม่รู้โหมด และการเดาโหมด
คือสิ่งที่สเปกห้าม) พร้อมหัวข้อ `🎯 ตอนนี้เฝ้าอะไรอยู่` บรรทัดเตือน และความไว 1-10
ซึ่งในกรณีนี้คุมตัวตรวจการสั่นจริง

ผลกับเทสต์: แก้ assertion ที่ตรึงข้อความเลย์เอาต์เดิม 31 บรรทัดใน 5 ไฟล์
**ไม่มีเทสต์ตัวไหนถูกลบหรือปิด** และ assertion แถวเซ็นเซอร์ 35 บรรทัดใช้ได้เหมือนเดิม
เพราะข้อความแถวไม่เปลี่ยนเมื่อไม่มีโหมดให้ติดป้ายบทบาท

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

- **S6 และ S9 ยังไม่ได้ยืนยันบนเครื่องจริง** — `liveDoorAngleDeg` / `liveWitnessLit` /
  `liveConfirmationCountdownMs` และข้อความเตือนเกินเพดาน ผ่านเทสต์ระดับ host แต่ยังไม่เคย
  เห็นค่าจริงจากเซ็นเซอร์ · S9 ต้องรอเครื่องที่วัด drift แล้วได้ verdict `Limited`
  แล้วอาร์มข้ามเพดาน จึงจะเห็นข้อความจริง
- **โหมดประตูระดับมุมแสดง 6 แถวบนตัวหาร 5** — แถว `ทิศทาง/มุม` เป็นแถวแสดงผลเพิ่ม
  ใช้ health ตัวเดียวกับ `การสั่น` ตั้งใจตาม §6 แต่เจ้าของอาจสะดุดตา
- **ยังไม่ทำ (ตาม §12):** `/status <โหมด>` · inline keyboard ·
  `ProtectionStateTelegramNotifier` ยังไม่รู้จักโหมด (ยืนยันแล้วว่าไม่มีการอ้าง
  `modeContext` เลย) · ข้อความ heartbeat ทุก 15 นาทียังพูดกลาง ๆ ว่า Armed/Disarmed
  (`HeartbeatPinger.kt:104-108` — S9 ยืมแค่จังหวะของมัน ไม่ได้แก้ข้อความ)

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
