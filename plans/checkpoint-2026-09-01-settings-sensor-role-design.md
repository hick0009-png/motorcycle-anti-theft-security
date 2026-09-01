# Checkpoint — ออกแบบการล็อก/อธิบายบทบาทเซ็นเซอร์ตามโหมด (2026-09-01)

> อัปเดตล่าสุด: 2026-09-02 — T1/T2 ลงมือทำเสร็จแล้ว ดูหัวข้อ "ความคืบหน้า"

## Resume point

- **Worktree:** `D:\security` (main worktree)
- **Branch:** `feature/motorcycle-guard-protection`
- **HEAD:** `e065013` — `feat(settings): lock the sensors a protection use does not detect with` (2026-09-02)
- **สถานะการทำงาน:** T1 และ T2 ทำเสร็จแล้ว (ดู "ความคืบหน้า" ด้านล่าง)
- **เครื่องทดสอบ:** Huawei INE-LX2 `JUCDU18811013149` — **ติดตั้งบิลด์จาก HEAD แล้ว**
- push ขึ้น `origin/feature/motorcycle-guard-protection` แล้ว

### ไฟล์ untracked — ห้ามลบหรือแก้
```
.hermes/
moto-guard-advanced.xml
moto-guard-details.xml
moto-guard-window.xml
plans/checkpoint-2026-08-30-power-guard-restart-recovery.md
plans/checkpoint-2026-09-01-power-guard-detection-telegram.md
plans/settings-profile-locked-sensors-design-2026-09-01.md
plans/settings-sensor-contribution-design-2026-09-01.md
plans/device-diversity-and-entry-sensor-analysis-2026-09-01.md
work/tasks/power-guard-detection-telegram-handoff.md
work/tasks/power-guard-witness-recovery-fix.md
work/tasks/vehicle-live-pursuit-extension.md
```

`MotorcycleAntiTheftSensor/docs/user-guide-power-guard.md` ถูก commit ไปแล้วใน `fcac7da`
เอกสารออกแบบสามฉบับยังเป็น untracked ตามเดิม — ยังไม่ได้ตกลงว่าจะ commit หรือไม่

### เอกสารที่เซสชันออกแบบสร้างไว้
```
plans/settings-profile-locked-sensors-design-2026-09-01.md      ← ส่วนที่ 1
plans/settings-sensor-contribution-design-2026-09-01.md         ← ส่วนที่ 2
plans/device-diversity-and-entry-sensor-analysis-2026-09-01.md  ← ส่วนที่ 3
work/tasks/vehicle-live-pursuit-extension.md                    ← งานแยก GPS
plans/checkpoint-2026-09-01-settings-sensor-role-design.md       ← ไฟล์นี้
```

---

## ความคืบหน้า (อัปเดต 2026-09-02)

| งาน | สถานะ | commit |
|---|---|---|
| T1 ล็อกเซ็นเซอร์ตามโหมด | ✅ เสร็จ | `e065013` |
| T2 ไดอะล็อกสองส่วนพับได้ | ✅ เสร็จ | `e065013` |
| T9 เดินสายไป profile overrides | ⏸ ยังไม่ทำ (เฟส 2 ของส่วนที่ 1 ข้อ 7) | — |

### สิ่งที่ทำจริงใน T1/T2

- `ProtectionProfilePolicy` เปิด `lockedSources` / `lockedCapabilities` / `presetSelectable`
  — **ต่างจากเอกสารส่วนที่ 1 ข้อ 3**: เอกสารเสนอให้ hardcode ชุดเซ็นเซอร์ซ้ำใน companion
  แต่ของเดิมคำนวณจากตาราง recommendation ถ้าทำตามจะมีความจริงสองชุด
  → ทำเป็นนิยามเดียว แล้วเพิ่มเทสต์ที่ยิง override PRIMARY ทุก source
  แล้วยืนยันว่า `resolve()` กดกลับเป็น OFF ครบทุกโปรไฟล์
- `SensorEditabilityUiModel` ใน `ProtectionUiModels.kt` — Compose ไม่ derive เองเลย
- `SettingsScreen.kt`: แบนเนอร์ 🔒, preset เป็นชิปอ่านอย่างเดียว + ซ่อนป้าย CUSTOM,
  การ์ดกลุ่มจาง + สไลเดอร์ disabled, ไดอะล็อกสองส่วนพับได้, normalize ก่อนเซฟ
- test tags ที่เพิ่ม: `ui.settings.sensor.LOCK_BANNER`, `.PRESET_LOCKED`,
  `.LOCKED_GROUP_TOGGLE`, `.ROLE_LIST`, `ui.settings.sensor.capability.<NAME>.SLIDER`,
  `ui.settings.sensor.source.<NAME>.role.<ROLE>`
- เทสต์: host 959 ตัวผ่าน (เพิ่ม 9), instrumented `SensorLockUiTest` 7/7 ผ่านบน INE-LX2

### บทเรียนจากการรันบนเครื่องจริง

`performScrollTo()` ใช้กับแถวใน LazyColumn ที่ยังไม่ถูก compose ไม่ได้
→ ต้องใช้ `onNodeWithTag(SENSOR_ROLE_LIST_TAG).performScrollToNode(hasTestTag(...))`
จำไว้ตอนเขียนเทสต์ UI ตัวต่อไป

### ข้อมูลเซ็นเซอร์จริงของเครื่องทดสอบ (ตอบคำถามที่ค้างไว้ — จำเป็นต่อ T6/T10)

INE-LX2 (`adb shell dumpsys sensorservice`):

**มี:** accelerometer, gyroscope (+uncalibrated), magnetic field (+uncalibrated),
orientation, light (LTR578 — on-change), proximity, gravity, linear acceleration,
rotation vector, **game rotation vector (Huawei + AOSP สำรอง)**,
geomagnetic rotation vector, significant motion, step counter, hall

**ไม่มี:** barometer, ambient temperature, humidity, heart rate

⚠️ เครื่องนี้**มี** gyro และ game RV → ข้อค้นพบ #5 (เครื่องไม่มี gyro แล้วโหมดประตูตายเงียบ)
**ทดสอบบนเครื่องนี้ไม่เจอ** — T11 ต้องจำลองด้วยการบังคับ path สำรอง หรือ fake catalog

---

## เซสชันออกแบบทำอะไร

เริ่มจากคำถามของเจ้าของ: **"อยู่โหมดไฟเลี้ยง แต่หน้าตั้งค่าเซ็นเซอร์ขั้นสูงจุดอื่น
ยังตั้งค่าได้อยู่ไหม อยากให้ปิดลาง ๆ ตั้งค่าไม่ได้"**

ตรวจโค้ดแล้วขยายเป็นสามส่วน + งานแยกหนึ่งก้อน ตามที่เจ้าของสั่งเพิ่มระหว่างทาง
ไม่มีการแก้โค้ดใด ๆ ในเซสชันนั้น

---

## ข้อค้นพบสำคัญจากการอ่านโค้ด (ทั้งหมดยืนยันด้วยเลขบรรทัดแล้ว)

| # | ข้อค้นพบ | ที่ | ความร้ายแรง |
|---|---|---|---|
| 1 | หน้าเซ็นเซอร์ขั้นสูงวน `SensorCapability.entries` / `SensorSource.entries` **โดยไม่ดูโหมด** — ปรับได้หมดทุกโหมด | `SettingsScreen.kt:544,645` | ✅ แก้แล้วใน `e065013` |
| 2 | โดเมนบังคับ source ที่ไม่ใช่ `AMBIENT_LIGHT` เป็น OFF สำหรับ POWER อยู่แล้วอย่างเงียบ ๆ | `ProtectionProfilePolicy.kt:213` | — (พฤติกรรมถูก) |
| 3 | หน้าตั้งค่าอ่าน/เขียน **config กลางตัวเก่า** ไม่ใช่ config ที่โปรไฟล์ใช้จริง | `AndroidProtectionSettingsGateway.kt` (`ui/`) | 🟠 รากของ "ตั้งแล้วไม่มีผล" — ยังไม่แก้ (T9) |
| 4 | **โหมดประตูไม่ได้ใช้ค่าที่ตั้งในหน้านี้เลย** — ลงทะเบียน `TYPE_GAME_ROTATION_VECTOR` เอง และ `role = PRIMARY` ตายตัว | `AndroidProtectionRuntime.kt:1186,1241` | 🟠 |
| 5 | **เครื่องไม่มี gyro = โหมดประตูตายเงียบ** `?: return` ไม่ลงทะเบียน listener แต่ Arm ผ่านปกติ | `AndroidProtectionRuntime.kt:1188` | 🔴 |
| 6 | ดริฟท์ yaw + baseline แช่แข็งทั้งเซสชัน เกณฑ์ 15° → อาจเตือนผิดตอนอาร์มข้ามคืน | `EntryDetectionPolicy` KDoc | 🔴 ต้องวัดจริง |
| 7 | `fresh = true` ตายตัว + `onAccuracyChanged` ว่าง → Gate 1 ของ `EntryDetectionPolicy` เป็นโค้ดตาย | `AndroidProtectionRuntime.kt:1196` | 🟠 |
| 8 | `sensorIdentity()` hardcode `"game-rotation-vector/..."` → เครื่องที่ตกไปใช้ตัวสำรอง ลายนิ้วมือโกหก ไม่บังคับปรับเทียบใหม่ | `EntryCommissioningEnvironment.kt:17` | 🟠 |
| 9 | `isPrimaryRole()` = `role == PRIMARY \|\| role == null` → **ไมค์ / ตำแหน่ง / สายชาร์จ เป็นเจ้าภาพเสมอ** เพราะสร้าง observation โดยไม่ใส่ role | `IncidentEngine.kt:507`, `AudioThreatPipeline.kt:468`, `ProtectionRuntimeGraph.kt:374` | 🔴 |
| 10 | `openIncident()` ที่ไม่ส่ง `supersede` **เขียนทับ** `activeIncident` โดยไม่ปิดตัวเดิม → เหตุการณ์ค้าง OPEN ตลอดกาล | `IncidentEngine.kt:392` | 🔴 บั๊กตระกูลเดียวกับที่แก้ไปแล้วฝั่ง POWER |
| 11 | `AndroidSensorCatalog` มีข้อมูลความพร้อมเซ็นเซอร์ครบแล้ว (isAvailable/vendor/powerMa/minDelay) **แต่ไม่มีใครส่งขึ้นถึง UI** | `AndroidSensorCatalog.kt:46` | ข่าวดี — T10/T11 เป็นงานเดินสาย |

---

## ⚠️ กับดักที่ต้องจำให้ได้เมื่อกลับมาทำต่อ

**การแจ้งเตือน GPS ทำงานได้ทุกวันนี้เพราะค่าเริ่มต้น `role = null` ถูกนับเป็นเจ้าภาพพอดี**

T14/T16 จะกลับค่าเริ่มต้นเป็น "null = เปิดเหตุการณ์เองไม่ได้"
ถ้าทำโดยไม่ประกาศ `LOCATION = เจ้าภาพของโหมดเฝ้ารถ` ไปพร้อมกันในคอมมิตเดียวกัน
**การเตือนว่ารถถูกเคลื่อนย้ายจะเงียบไปทั้งหมด ไม่มี error ไม่มี crash**
ตรวจไม่เจอจนกว่ารถจะหายจริง

→ เขียนเทสต์ล็อกพฤติกรรมนี้ **ก่อน** แตะ `isPrimaryRole()` เสมอ
รายละเอียดอยู่ในภาค E ของเอกสารส่วนที่ 3

---

## รายการงาน T1-T16 (สรุปหนึ่งบรรทัดต่อก้อน)

| # | งาน | เอกสาร |
|---|---|---|
| T1 | ✅ ล็อกเซ็นเซอร์ที่โหมดไฟเลี้ยงไม่ใช้ (จาง + กดไม่ได้จริง + a11y) | ส่วนที่ 1 |
| T2 | ✅ ไดอะล็อกบทบาทแยก "ที่โหมดนี้ใช้" / "ถูกล็อก" พับได้ | ส่วนที่ 1 |
| T3 | ป้าย ⭐ แนะนำต่อโหมด + ปุ่มคืนค่าที่แนะนำ | ส่วนที่ 2 |
| T4 | บรรทัด "ช่วยตรวจจับ" ต่อโปรไฟล์ | ส่วนที่ 2 |
| T5 | แผงเทียบผล 3 บทบาท กางได้ | ส่วนที่ 2 |
| T6 | กันตั้ง "หลัก" ให้เซ็นเซอร์ที่เครื่องไม่มี | ส่วนที่ 2 |
| T7 | หมายเหตุความจริงโหมดประตู (ข้อค้นพบ #4) | ส่วนที่ 2 |
| T8 | สรุป "หลัก N · ประกอบ N · ปิด N" + ผลต่อการ Arm | ส่วนที่ 2 |
| T9 | เดินสายหน้าตั้งค่าไป profile overrides (ข้อค้นพบ #3) | ส่วนที่ 1 |
| T10 | เดินสายสถานะเซ็นเซอร์จริงขึ้นหน้าจอ 🟢/🟡/⚪ | ส่วนที่ 3 |
| T11 | กันเลือกโหมดที่เครื่องรองรับไม่ได้ (ข้อค้นพบ #5) | ส่วนที่ 3 |
| T12 | **วัดดริฟท์จริง 8 ชม.** (ข้อค้นพบ #6) | ส่วนที่ 3 |
| T13 | บันไดแหล่งทิศทาง 5 ขั้น + ตรึงด้วยเข็มทิศ (#5,#6,#7,#8) | ส่วนที่ 3 |
| T14 | ปิดช่องแย่งเหตุการณ์ (#9,#10) | ส่วนที่ 3 |
| T15 | ใช้การสั่นแยกดริฟท์ออกจากการเปิดประตูจริง | ส่วนที่ 3 |
| T16 | ทำให้ "เจ้าภาพ/ตัวสนับสนุน" เป็นจริงทั้งระบบ | ส่วนที่ 3 |
| G1 | (แยกสาขา) ต่ออายุ live location เกิน 15 นาที | `work/tasks/vehicle-live-pursuit-extension.md` |
| G2 | (แยกสาขา) เริ่มติดตามทันทีเมื่อมีเหตุการณ์วิกฤต | เอกสารเดียวกัน |

---

## ลำดับที่ตกลงไว้

```
T1 → T2                    ✅ เสร็จ (e065013)
T10 → T11                  ← กำลังทำต่อ
T3 → T4 → T5               อธิบายว่าเซ็นเซอร์แต่ละตัวช่วยอะไร
T6 → T7 → T8               กับดักฮาร์ดแวร์ · ความจริงโหมดประตู · สรุปภาพรวม
T14 + T16                  ⚠️ คอมมิตเดียวกัน + ประกาศ LOCATION = เจ้าภาพเฝ้ารถ
T12 → T13 → T15            รากของโหมดประตู — วัดก่อน แก้ทีหลัง
T9                         เดินสายไป profile overrides
G1, G2                     สาขาแยก ไม่ชนกับข้างบน
```

**T12 ควรเริ่มขนานตั้งแต่วันแรก** เพราะกินเวลารอข้อมูลข้ามคืน ไม่ใช่เวลาเขียนโค้ด

---

## จุดกลับมาทำต่อ

1. อ่านไฟล์นี้ก่อน แล้วอ่านเอกสารของก้อนงานที่จะทำ
2. **ก้อนถัดไปตามลำดับที่ตกลง: T10 → T11** (เจ้าของสั่งเมื่อ 2026-09-02)
   ถ้าจะสลับไปก้อนอื่น ต้องถามก่อนลงมือ
3. ถ้าเริ่ม T10: ข้อมูลอยู่ใน `AndroidSensorCatalog.kt:46` แล้ว — เดินสายขึ้น UI อย่างเดียว
   ดูภาค A ของเอกสารส่วนที่ 3
4. ถ้าเริ่ม T12: เป็นงานวัดผล ไม่ต้องแก้ UI เขียนตัวบันทึกมุมสะสมแล้วปล่อยทิ้งข้ามคืน
5. เครื่องทดสอบ: Huawei INE-LX2 `JUCDU18811013149` — ติดตั้งบิลด์จาก HEAD แล้ว
   รันเทสต์บนเครื่อง: `.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<FQCN>`
   (ต้องตั้ง `JAVA_HOME` = `C:\Program Files\Android\Android Studio\jbr` ก่อน — ดู `scripts/setup_env.ps1`)
6. เส้นทางในแอปที่ใช้ตรวจบ่อย:
   แท็บตั้งค่า → การปกป้อง → "แสดงการวินิจฉัยขั้นสูง" → การ์ด "เซ็นเซอร์ขั้นสูงและบทบาทการตรวจจับ"

## สิ่งที่ยังไม่ได้ทำและอาจต้องทบทวน

- ยังไม่ได้ยืนยันด้วยการรันจริงว่าโหมดประตูตรวจจับได้แม้ปิดบทบาทแกนหมุดทั้งหมด
  (ข้อค้นพบ #4 มาจากการอ่านโค้ด — T7 มีขั้นตอนพิสูจน์บนเครื่องไว้แล้ว ถ้าผลไม่ตรง ต้องกลับมาทบทวน)
- ตัวเลขดริฟท์ของ GAME_ROTATION_VECTOR ยังไม่มี → ห้ามตัดสินใจเรื่อง T13 ก่อนได้ผล T12
- T9 ยังค้าง: ตอนนี้ "ล็อกถูกที่แล้ว แต่ตัวเลขที่โชว์ยังมาจาก config กลาง ไม่ใช่ของโปรไฟล์"
