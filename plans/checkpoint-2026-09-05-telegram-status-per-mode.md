# Checkpoint — `/status` ต่อโหมด: **ลงมือครบ S1-S9 แล้ว** + ทุกช่องทางรู้จักโหมด (2026-09-05)

**สเปกเต็ม:** [telegram-status-per-mode-design-2026-09-05.md](telegram-status-per-mode-design-2026-09-05.md)
**สถานะโค้ด:** เขียนครบทั้ง S1-S9 · เจ้าของเคาะข้อค้างครบแล้ว · เทสต์เขียวทั้งหมด
**ต่อยอดแล้ว:** ทั้งสามช่องทางที่คุยกับเจ้าของทาง Telegram รู้จักโหมดครบแล้ว (นอกสเปก ทำหลังปิด S9)
— `/status` (S1-S9) · แจ้งเตือนตอนเปลี่ยนสถานะ (`376ae0d`) · heartbeat ทุก 15 นาที (`3c21e4e`)

| | |
|---|---|
| worktree | `D:\security` (checkout หลัก ไม่ได้ใช้ worktree ย่อยรอบนี้) |
| branch | `feature/motorcycle-guard-protection` |
| head | `595ab6e` |
| working tree | สะอาด ไม่มีไฟล์ค้าง |
| remote | ยังไม่มี — push ไม่ได้จนกว่าเจ้าของจะตั้ง |

---

## 0. กลับมาแล้วเริ่มตรงไหน

**ทั้งสามช่องทางที่คุยกับเจ้าของทาง Telegram รู้จักโหมดครบแล้ว ไม่มีอะไรค้างกลางทาง**
ไม่ต้องอ่านทั้งไฟล์นี้ ถ้าจะทำงานถัดไป เลือกจาก §6 ได้เลย เรียงตามที่ผมคิดว่าคุ้มที่สุด:

1. **ทดสอบบนเครื่องจริง** — เป็นหนี้ก้อนเดียวที่เป็นความเสี่ยงจริง
   ทุกอย่างผ่านเทสต์ระดับ host แต่ยังไม่เคยเห็นค่าจากเซ็นเซอร์จริงสักครั้ง
   วิธี: อาร์มโหมดประตู (ระดับมุม) แล้วสั่ง `/status` ดูว่าบรรทัด `มุมขณะนี้:` มีเลขจริงไหม
   จากนั้นโหมดไฟเลี้ยง ดู `ค่าที่วัดได้ขณะนี้:` กับ `กำลังนับถอยหลัง:`
   รอบนี้ดูข้อความอาร์ม/ปลดอาร์ม กับ heartbeat นัดถัดไปด้วย ว่าบรรทัดโหมดมาจริงไหม
2. **`/status <โหมด>` กับ inline keyboard** — ยังไม่ทำตาม §12 ของสเปก ไม่ใช่หนี้ เป็นของใหม่

ก่อนแตะอะไร รันเกตหนึ่งรอบให้เห็นเขียวก่อน (§7)

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
| `8766919` | — | อัปเดตเช็คพอยต์นี้ |
| `8a0bd2b` | — | ทำให้เช็คพอยต์กลับมาทำงานต่อได้โดยไม่ต้องอ่านทั้งไฟล์ |
| `376ae0d` | นอกสเปก | แจ้งเตือนตอนเปลี่ยนสถานะบอกโหมด · คำของโหมดย้ายเข้า catalog |
| `0939bc7` | — | อัปเดตเช็คพอยต์ + §9 |
| `3c21e4e` | นอกสเปก | heartbeat ทุก 15 นาทีบอกโหมด · แยก `modeName` ออกจาก `modeLabel` |
| `9e8d28e` | — | อัปเดตเช็คพอยต์ |
| `595ab6e` | นอกสเปก | ลบตัวกรองอาการอุ่นเครื่อง ทุกเหตุผลถึงเจ้าของ (§9) |

## 2. การตรวจสอบ

```
gradlew --offline --no-daemon --max-workers=1 testDebugUnitTest compileDebugAndroidTestKotlin
→ BUILD SUCCESSFUL · 1,272 tests · 0 failed · 0 skipped
```

รันครั้งสุดท้ายที่ head `595ab6e` · ไม่มีเทสต์ตัวไหนถูกลบ ปิด หรือ `@Ignore`

เทสต์ใหม่ของ S1-S9 25 ข้อ:
`ProtectionStatusModeReportTest` (15) · `EntryDriftCeilingWarningPolicyTest` (8) ·
`HeartbeatCeilingWarningTest` (2) · เพิ่มใน `ArmedProfileSnapshotCodecTest` (3) และ
`TelegramCommandHandlerTest` (2)

ครบทั้ง 15 ข้อของ §11 ในสเปก

เทสต์ใหม่ของงานแจ้งเตือน 10 ข้อ ใน `ProtectionStateTelegramNotifierTest`
เทสต์ใหม่ของงาน heartbeat 5 ข้อ ใน `HeartbeatModeLineTest`

ตัวที่กันปัญหาไม่ให้กลับมาคือคู่ `everyModeWordComesFromTheCatalog` และ
`everyModeLineComesFromTheCatalog` — เทียบทุกโหมด×ทุกระดับกับ `PresentationTextCatalog`
โดยตรง ทั้งสองคลาสจึงงอกตารางโหมดของตัวเองขึ้นมาไม่ได้

เทสต์เดิมสองตัวไม่ถูกแตะเลยแม้แต่บรรทัดเดียว และทั้งคู่คือหลักฐานว่าของเดิมไม่เปลี่ยน:
`generatesExpectedMessagesForStateTransitions` (ลูกค้าที่ไม่เคยเลือกโหมดได้ข้อความเดิมทุกตัวอักษร)
และ `heartbeatSendsProfessionalBilingualArmedStatus` (บิลด์ที่ไม่มี snapshot supplier
ส่ง heartbeat เดิมทุกไบต์)

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

ช่องทางที่เหลืออีกสองทาง (คอมมิต `376ae0d`, `3c21e4e`) ต่อเข้าโครงเดียวกัน:

```
PresentationTextCatalog                          ← คำของโหมดอยู่ที่เดียว
  ├ profilePromise(profile, entryLevel)          ← โหมดนี้สัญญาว่าจะจับอะไร
  ├ entryLevelLabel(level)                       ← ระดับการเฝ้าของโหมดประตู
  ├ modeName(profile, entryLevel)                ← "ประตูและทางเข้า · มุมประตู (วัดองศาได้)"
  └ modeLabel(profile, entryLevel)               ← "โหมด" + modeName()
       ▲                    ▲                     ▲
       │                    │                     │
  ModeStatusSections   ProtectionState        HeartbeatPinger
  (/status)            TelegramNotifier       (ทุก 15 นาที)
                       (เปลี่ยนสถานะ)
```

`modeLabel` ใช้กับบรรทัดที่ยังไม่มีคำว่า "โหมด" อยู่ · `modeName` ใช้กับบรรทัดที่มีแล้ว
(`โหมด: ประตูและทางเข้า …` ของ heartbeat) ทั้งสองมาจากตัวเดียวกันจึงเพี้ยนจากกันไม่ได้

ประโยคสัญญาของโหมดประตูระดับ `SOUND_AND_MOVEMENT` เคยฝังอยู่ใน `watchScope()`
ย้ายเข้า catalog แล้ว ทุกที่จึงอ่านจากตัวเดียวกัน — หน้าจอที่ตอบคำถามเดียวของเจ้าของ
จากตารางคนละใบ คือสิ่งที่ทำให้เกิดรายงานที่สั่งให้ไปแก้ GPS ในโหมดที่ปิด GPS

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
- **`ProtectionStateTelegramNotifier.messagesFor()`** รับ `context` เป็นพารามิเตอร์ตัวที่สี่
  ค่า default `null` แปลว่าไม่มี call site หรือเทสต์ตัวไหนต้องแก้ · graph ส่ง
  `snapshot.modeContext` จาก snapshot **ตัวเดียวกับที่ให้ `state` มา** จึงตั้งชื่อโหมดผิด
  จากการเปลี่ยนสถานะนั้นไม่ได้
- **`context == null` ต้องได้ข้อความเดิมทุกตัวอักษร** — คือลูกค้าที่อัปเกรดมาจากก่อนมีโหมด
  และไม่เคยเลือกสักครั้ง การเดาโหมดจากเซ็นเซอร์ที่บังเอิญทำงานอยู่ แล้วเอาไปใส่บรรทัดที่บอกว่า
  กำลังปกป้องอะไร แย่กว่าการเงียบที่มันไปแทน · `noSelectedProfileKeepsTheOldMessagesWordForWord`
  ตรึงข้อนี้ไว้ทั้ง `null` และ `ProtectionModeContext(selectedProfile = null)`
- **`HeartbeatPinger` มี `snapshotSupplier` อยู่แล้วตั้งแต่ S9** (`SensorService.kt:190`
  ส่ง `graph.coordinator.snapshot.value`) บรรทัดโหมดจึงไม่ต้องเดินสายใหม่ · บิลด์ที่ไม่มี
  supplier (เทสต์เก่า, บิลด์ไม่มี coordinator) ไม่ได้บรรทัดนี้เลย ข้อความจึงเท่าเดิมทุกไบต์
- **บรรทัด `การป้องกัน:` ของ heartbeat ยังอ่าน `prefsManager.isSystemArmed()` ไม่ใช่ snapshot**
  ตั้งใจไว้อย่างนั้น — แฟล็กนั้นถูกเขียนจากชุดสถานะเดียวกับที่บรรทัดนี้หมายถึงพอดี
  (`ProtectionRuntimeGraph.kt:121-128` `writeCompatibilityArmed`) การไปอ่าน `snapshot.state`
  ตรง ๆ แปลว่าต้องคัดลอกชุดสถานะนั้นเป็นใบที่ห้า ในโค้ดเบสที่มีอยู่แล้วสี่ใบ
  (`ProtectionCoordinator.ARMED_STATES` · `BlackBoxRecorder.ARMED_STATES` ·
  ชุด inline ที่ `ProtectionRuntimeGraph.kt:131-136` · `ModeStatusSections.ARMED_OR_ALERT`
  — ทั้งสี่ต่างกันจริงเพราะตอบคนละคำถาม)

## 6. หนี้ที่เหลือ

- **S6 และ S9 ยังไม่ได้ยืนยันบนเครื่องจริง** — `liveDoorAngleDeg` / `liveWitnessLit` /
  `liveConfirmationCountdownMs` และข้อความเตือนเกินเพดาน ผ่านเทสต์ระดับ host แต่ยังไม่เคย
  เห็นค่าจริงจากเซ็นเซอร์ · S9 ต้องรอเครื่องที่วัด drift แล้วได้ verdict `Limited`
  แล้วอาร์มข้ามเพดาน จึงจะเห็นข้อความจริง
  · บรรทัดโหมดในข้อความเปลี่ยนสถานะกับ heartbeat ก็ยังไม่เคยเห็นบนเครื่องจริงเหมือนกัน
- **`EncryptedPrefsManager.setSystemArmed()` ไม่มีใครเรียกเลย** (`:201`) โค้ดตาย
  ตัวที่ใช้จริงคือ `commitSystemArmed()` ซึ่ง `ProtectionRuntimeGraph` เรียกแบบ blocking
  เพราะต้องรู้ว่าเขียนสำเร็จไหม · ลบทิ้งได้ แต่ไม่ใช่ตอนนี้
- **โหมดประตูระดับมุมแสดง 6 แถวบนตัวหาร 5** — แถว `ทิศทาง/มุม` เป็นแถวแสดงผลเพิ่ม
  ใช้ health ตัวเดียวกับ `การสั่น` ตั้งใจตาม §6 แต่เจ้าของอาจสะดุดตา
- ~~ตัวกรองอาการอุ่นเครื่องยังพูดแทนทุกโหมด~~ — แก้แล้วที่ `595ab6e` ดู §9
- **ยังไม่ทำ (ตาม §12):** `/status <โหมด>` · inline keyboard
- ~~`ProtectionStateTelegramNotifier` ยังไม่รู้จักโหมด~~ — ทำแล้วที่ `376ae0d`
- ~~ข้อความ heartbeat ทุก 15 นาทียังพูดกลาง ๆ ว่า Armed/Disarmed~~ — ทำแล้วที่ `3c21e4e`

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

---

## 9. ตัวกรองอาการอุ่นเครื่อง — **แก้แล้วที่ `595ab6e`**

### 9.1 อาการ

อาร์มโหมดยานพาหนะตอนที่สิทธิ์ตำแหน่งถูกปฏิเสธ → เจ้าของได้ `✅ การป้องกันทำงานปกติ`
ทั้งที่โหมดนั้นขาดตัวเปิดเหตุไปหนึ่งตัว และเจ้าของยังยืนอยู่ข้างรถ แก้ได้ทันที

`ProtectionStateTelegramNotifier` ตัดเหตุผล `LOCATION not healthy` และ
`MICROPHONE not healthy` ทิ้งเสมอ ในฐานะ "อาการอุ่นเครื่องชั่วคราว"

### 9.2 สิ่งที่ตามเจอ: ตัวกรองนี้ไม่เคยเจออาการอุ่นเครื่องสักครั้ง

ตอนแรกผมเข้าใจว่าเป็นเรื่องของโหมด (ไมค์เป็น `PRIMARY` ในโหมดประตูระดับเสียงและการขยับ)
และร่างทางเลือกไว้สามทาง **นั่นตั้งอยู่บนสมมติฐานที่ผิด** ตัวแยกแยะมีอยู่แล้วที่ต้นทาง:

| ชั้น | สิ่งที่เกิด |
|---|---|
| เซ็นเซอร์รายงานตัวเอง | ไมค์ปรับ noise floor → `AVAILABLE` (`AndroidProtectionRuntime.kt:602`) · GPS รอ fix แรก → `AVAILABLE` (`ProtectionModels.kt:284`) |
| `ProtectionHealthPolicy` | ถ้าไม่ใช่ `HEALTHY` คืนสถานะเดิมทันที (`:14-16`) `AVAILABLE` จึงไม่กลายเป็นอย่างอื่น · `STALE` เกิดได้เฉพาะกับตัวที่**เคย `HEALTHY` แล้วหยุดส่งค่า** |
| `unhealthySensorReasons` | สร้างเหตุผลเฉพาะ `UNAVAILABLE` / `STALE` / `FAILED` (`ProtectionCoordinator.kt:1508-1514`) — **ไม่มี `AVAILABLE`** |
| ผู้ผลิตเหตุผลอีกที่ (`:1037`) | ใช้ `SensorSource` ซึ่งไม่มีสมาชิกชื่อ `MICROPHONE` หรือ `LOCATION` เลย (`SensorConfigurationModels.kt:63-77`) |

⇒ เวลา `"MICROPHONE not healthy"` มาถึงตัวแจ้งเตือน ไมค์อยู่ในสภาพ **ไม่มีฮาร์ดแวร์ ·
ไม่ได้รับสิทธิ์ · พังจริง · หรือเคยทำงานแล้วหยุดส่งค่า** เท่านั้น
ตัวกรองจึงลบได้แต่ของจริงอย่างเดียว

### 9.3 ที่แก้

ลบตัวกรองทิ้ง ไม่ได้ส่ง `sensorHealth` เข้าไป ไม่ได้เพิ่มกฎต่อโหมด — เพราะไม่ต้องใช้เลย

**เจ้าของเคาะเรื่อง `STALE` แล้ว: ให้เตือนด้วย** เซ็นเซอร์ที่เคยทำงานแล้วหยุดส่งค่า
(เช่น GPS ที่จอดในโรงรถ) จะถูกพูดถึงตอนกดอาร์ม เหตุผลคือจังหวะนั้นเจ้าของยังอยู่กับรถ
ย้ายที่จอดหรือแก้ได้เลย ดีกว่ามารู้ตอนตีสาม

**`if (degradationReasons.isEmpty())` ยังอยู่** เพราะ `ARMED_DEGRADED` ไปถึงได้โดยไม่มีเหตุผล
ให้รายงาน (`armedStateFrom` ดู `sensorHealth[VIBRATION]` ด้วย) ตอนนั้นไม่มีอะไรจะบอกจริง ๆ

เทสต์ที่กันไม่ให้กลับมา: `aWarmingUpSensorNeverProducedAReasonToDrop` ตรึงสัญญาไว้
**จากฝั่งผู้ผลิตเหตุผล** ไม่ใช่ฝั่งข้อความ ถ้าวันหนึ่งมีคนทำให้ `AVAILABLE` สร้างเหตุผลได้
มันต้องผ่านเทสต์ตัวนี้ก่อน
