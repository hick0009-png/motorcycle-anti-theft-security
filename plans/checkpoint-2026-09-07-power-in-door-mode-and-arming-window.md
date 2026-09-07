# Checkpoint — เหตุ POWER รั่วเข้าโหมดประตู + ช่วงบอด 10 วิตอนอาร์ม + ข้อความปิดเหตุพูดผิด (2026-09-07)

> ไฟล์นี้คือ **จุดกลับมาทำต่อล่าสุด** อ่านไฟล์นี้ก่อนไฟล์อื่น
> รอบก่อนหน้า (คำว่า "รถ" ถึงต้นตอ + ส่งย้อนหลัง) อยู่ใน
> `checkpoint-2026-09-06-alert-delivery-and-wording.md`

---

## Resume point

- **Worktree:** `D:\security` · **Branch:** `feature/motorcycle-guard-protection`
- **HEAD:** `c4aa26e` — `fix(entry): a door flung wide is confirmed fast, not dropped for not dwelling` **(บั๊ก #6)**
- **บั๊ก #1–#6 แก้และ commit ครบแล้วรอบนี้** · ชุดเทสต์ unit ทั้งหมดเขียว (`:app:testDebugUnitTest` ผ่าน) · ยังไม่ push
  - `87a07da` บั๊ก #1 · `5508f0f` บั๊ก #4 · `c3d3cd2` บั๊ก #2+#3 · `39e23a8` บั๊ก #5 · `c4aa26e` บั๊ก #6
- **ยังต้องทำ:** ทดสอบบนเครื่องจริง (Huawei INE-LX2) — โดยเฉพาะ **บั๊ก #5** (provider wiring ของ arming เข้า `PlatformAndroidDetectorSet` รันเฉพาะบน detector set จริง พิสูจน์ด้วย unit test ที่ระดับ controller เท่านั้น) และ **บั๊ก #2/#3** (สายชาร์จหลุดในโหมดประตูได้คำเตือนภาษาประตู ไม่ใช่ POWER)
- **บั๊ก #1 commit แล้วใน `87a07da`** (IncidentEngine.kt fix + EntryCloseMessageIntegrationTest.kt)
  - **พิสูจน์แล้วว่าเทสต์จับบั๊กได้:** stash fix ออก → เทสต์ล้มทั้ง 3 · ใส่กลับ → เขียว · ชุด Incident*/Entry*/UserGuidance* = **321 ผ่าน 0 fail**
- **ค้าง working tree:** เหลือแค่ `?? plans/checkpoint-2026-09-07-…md` (ไฟล์นี้เอง)
- **เครื่องทดสอบ:** Huawei INE-LX2 `JUCDU18811013149` — **เชื่อมต่ออยู่** · โหมด ENTRY ปรับเทียบไว้ 15°
- **กล่องดำวันนี้:** `blackbox-20260907.csv` (530 บรรทัด) ดึงมาแล้ว — สำเนาอยู่ใน scratchpad ของ session (หายเมื่อ session จบ)
- **env รันเทสต์/บิลด์:**
  - `JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'`
  - `ANDROID_HOME="$LOCALAPPDATA\Android\Sdk"` (local.properties ว่าง ต้อง set เอง)
  - adb: `C:\Users\ASUS\AppData\Local\Android\Sdk\platform-tools\adb.exe` · Git Bash ต้อง `export MSYS_NO_PATHCONV=1`

---

## ✅ ทำเสร็จรอบนี้ (commit `87a07da`) — บั๊ก #1: ประตูปิดแล้วไม่แจ้ง "ปิด"

**อาการ (เจ้าของรายงาน + ภาพ Telegram):** ปรับเทียบ+อาร์มเสร็จ เปิดประตูแจ้ง "ประตูเปิด" ปกติ แต่**ปิดประตูกลับได้ "โทรศัพท์หรือขายึดถูกขยับ กรุณาปรับเทียบใหม่"** ไม่เคยได้ "ประตูปิด"

**ต้นตอ:** ตอนปิดเหตุ Entry (`acceptEntry` สาขา `ENTRY_DOOR_CLOSED/…`) เรียก `close()` **โดยไม่ append หลักฐาน terminal** เข้า incident ก่อน → `IncidentMessageFormatter.entryMessage()` เลือกคำจาก `evidence.lastOrNull{entry diagnostic}` เลยไปเจอ evidence เก่า (`entry_mount_moved`) แทน `entry_door_closed`
- path Power (`POWER_RECOVERED`) ทำถูกอยู่แล้ว (append ก่อน close) — Entry ลืมทำ = ความไม่สมมาตร
- เทสต์เดิม `entryDoorClosedRendersStillCopy` ใส่ `entry_door_closed` เข้า evidence เอง เลยเขียวทั้งที่ของจริงพัง

**แก้แล้วที่:** [IncidentEngine.kt:755](../MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentEngine.kt#L755) — append terminal evidence ก่อน `close()` เลียนแบบ Power
**เทสต์:** `EntryCloseMessageIntegrationTest.kt` (เปิด→ปิด, เปิด→ขายึดขยับ→ปิด, ขายึดกลับที่)

> ผลข้างเคียงที่ fix นี้แก้ด้วย: การปิดประตู**ปกติ** (เดิมเด้งผิดเป็น "ประตูเปิด X°") และเคส mount-restored

**commit แล้ว:** `87a07da` — ทำต่อได้เลยที่บั๊ก #2–#6

---

## ✅ แก้ครบแล้วรอบนี้ (#2–#6) — สรุปสั้น

- **#4 (`5508f0f`)** ข้อความปิดเลือกจาก `closeReason` ไม่ใช่หลักฐานล่าสุด · รวมคำศัพท์ปิดใน `IncidentCloseReason` · ปิดคลาสเดียวกันทั้ง entry+power
- **#2+#3 (`c3d3cd2`)** สายชาร์จหลุดในโหมดประตู = tamper → เปิดเหตุ ENTRY_DOOR CRITICAL ได้เอง (เจ้าของเลือก "เตือนเสมอ") ผ่าน `acceptDoorModeChargerTamper` · ไม่รีไทป์/เปิดเป็น POWER · พูดคำภาษาประตู
- **#5 (`39e23a8`)** baseline เป็น provisional ช่วง ARMING (จับใหม่ทุก sample สด จนจบ countdown) · เดินสาย `armingProvider` เข้า detector set · เพิ่มคำเตือน "ปิดประตูให้สนิทระหว่างนับถอยหลัง"
- **#6 (`c4aa26e`)** มุมเปิดกว้าง ≥45° ยืนยันเร็ว (floor 200ms) แทน 750ms · กันสะบัดเปิด-ปิดเร็วหลุด · floor ไม่เป็นศูนย์ กัน spike sample เดียว

> รายละเอียดต้นตอเดิมด้านล่างเก็บไว้อ้างอิง

## 🔴 (แก้แล้ว — เก็บไว้อ้างอิง) — จากการอ่านกล่องดำ 2026-09-07 เทียบภาพ

ไทม์ไลน์จริงช่วง 07:17–07:19 (ICT) จาก `blackbox-20260907.csv`
(schema: `type,elapsedMs,wallMs,armed,mode,srcMask,samples,accMaxG,accRmsG,rotMaxDeg,lux,accuracyMin,incidents,battPct,charging,note,writeFails`):

```
07:17:43  M  CHARGING 1→0   INCIDENTS 2→3   rotMaxDeg=135.9°   armed ENTRY
07:17:49  S  disarm
07:17:50  D  tg:ok         ← ส่ง "ไฟเลี้ยง...กลับมาคงที่แล้ว" ตรงนี้ (charging ไม่เคยกลับเป็น 1)
07:18:37  S  arm    (mode="-")
07:18:43  M  rotMaxDeg=148.9°   mode ยังเป็น "-"    ← เปิดประตูตรงนี้
07:18:47  S  mode+config → ENTRY  ← เพิ่งพร้อมตรวจ (10 วิหลัง arm)
07:19:43  M  rotMaxDeg=29.7°   INCIDENTS ค้าง 3    ← ไม่มีเหตุใหม่
07:19:56  S  disarm
```
เทียบเคสสำเร็จ 05:41: เปิดประตู (47.6°) เกิด**หลัง** `mode+config` → incidents เดินจริง

### บั๊ก #2 — การเฝ้าสายชาร์จรั่วมาโฮสต์เหตุ POWER ในโหมดประตู (พี่น้องของบั๊ก "2 ท่อ")
- ตั้งใจ: `signalRoles(ENTRY, DOOR_ANGLE)` = `POWER_THERMAL → SUPPORTING` — [ProtectionProfilePolicy.kt:498](../MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionProfilePolicy.kt#L498)
- แต่ callback สายชาร์จหลุดกันไว้ **เฉพาะโหมด POWER** — [AndroidProtectionRuntime.kt:388](../MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt#L388) (`if (powerSessionActive && …CHARGER_DISCONNECTED) return`)
- `hostsIncident()` ปลดแค่ VIBRATION — [IncidentEngine.kt:629](../MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentEngine.kt#L629) (`if (doorAngleWatch && kind==VIBRATION) return false`) — **ไม่ปลด POWER_THERMAL**
- ผล: สายชาร์จถูกสะกิด (charging 1→0) → เปิด/รีไทป์เหตุเป็น POWER "แหล่งจ่ายไฟผิดปกติ" ทั้งที่อยู่โหมดประตู

### บั๊ก #3 — charger รีไทป์เหตุที่เปิดอยู่เป็น POWER โดยไม่เช็ค role
- [IncidentEngine.kt:518](../MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentEngine.kt#L518) `updatedClassification`: charger_disconnected → คืน `POWER` เสมอ แม้ role=SUPPORTING และเหตุที่เปิดอยู่เป็น ENTRY_DOOR

### บั๊ก #4 — ข้อความ "ปิดเหตุ" พูดคนละเรื่องกับเหตุผลที่ปิด (คลาสเดียวกับบั๊ก #1)
- `powerMessage` fallback: `else -> if (Closed) "ไฟเลี้ยงที่จุดเฝ้าระวังกลับมาคงที่แล้ว"` — [IncidentMessageFormatter.kt:368](../MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentMessageFormatter.kt#L368)
- เหตุ POWER ปิดเพราะ **disarm** (ไม่ใช่ไฟกลับมา) แต่ประกาศ "ไฟกลับมาคงที่แล้ว" — หยิบคำจาก evidence ที่หลงเหลือ/ค่า default ไม่ใช่จาก `closeReason`
- (สมมุติฐานกลไก: incident เปิดจากประตู แล้ว charger รีไทป์เป็น POWER แล้ว vibration ต่อเนื่อง evict charger ออกจาก 30 หลักฐานที่เก็บ → ตอนปิด `lastOrNull{power diag}` = null → เข้า else) — **ยังไม่ยืนยัน 100% ทางโค้ด**

### บั๊ก #5 — ช่วงบอด 10 วิตอนอาร์ม + จับ baseline ผิด (สาเหตุหลักของ "เปิด-ปิดเร็วไม่แจ้ง")
- `ArmingDelay { delay(10_000L) }` — [ProtectionRuntimeGraph.kt:662](../MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt#L662) · `ARMING_GRACE_MS=10_000L` (SensorService) · observation ช่วงนี้ `arming=true` ไม่ยกเหตุ ([AndroidProtectionRuntime.kt:423](../MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt#L423))
- baseline แช่แข็งจาก sample สดตัวแรก ([EntryArmedSessionController.kt:122](../MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/EntryArmedSessionController.kt#L122)) → เปิดประตูช่วง 10 วินี้ = จับ baseline ตอนประตูขยับ = อ้างอิงเพี้ยนทั้ง session
- **เจ้าของทดสอบโดยอาร์มแล้วเปิดประตูทันที (6 วิหลัง arm)** → ตกในช่วงนี้พอดี

### บั๊ก #6 (ดีไซน์) — เกณฑ์ "ค้างเปิด 750ms" ตัดการแกว่งเร็ว
- `openConfirmationMs=750L` — [ProtectionProfileModels.kt:62](../MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionProfileModels.kt#L62) — สะบัดเปิด-ปิดเร็วไม่ค้าง ≥15° นาน 750ms = ไม่ยืนยัน · single active-slot ของ engine บังเหตุอื่นได้

---

## 🧭 แนวทางแก้ที่เสนอ (เรียงตามควรทำก่อน)

1. ~~commit บั๊ก #1~~ **เสร็จแล้ว (`87a07da`)**
2. **บั๊ก #4 → ทำให้เป็นการแก้ระดับคลาส:** ข้อความปิดเลือกจาก `closeReason` (disarm/quiet/recovery/interrupted) แทนการเดาจาก evidence ล่าสุด — ปิดคลาสเดียวกันทั้ง entry+power พร้อมกัน
3. **บั๊ก #2 + #3 → อุด POWER_THERMAL:** ขยาย `hostsIncident()` ให้โหมด `doorAngleWatch` ปลด POWER_THERMAL ด้วย + ให้ `updatedClassification` ไม่รีไทป์เป็น POWER ถ้า role ไม่ใช่ host
   - **ต้องตัดสินใจก่อน:** ในโหมดประตู "ควรเตือนสายชาร์จหลุดไหม" (คนร้ายถอดมือถือก็สำคัญ) — ถ้าควร ให้เตือนด้วย**คำของโหมดประตู** ไม่ใช่ POWER
4. **บั๊ก #5 → กัน baseline เพี้ยน:** ถ้าเจอการหมุนใหญ่ช่วง arming ให้เลื่อน/รีจับ baseline + โชว์ตัวนับถอยหลัง (มี `armingSecondsRemaining` แล้ว) + เตือน "อย่าเพิ่งขยับประตูระหว่างนับถอยหลัง"
5. **บั๊ก #6 → ทบทวน 750ms:** ให้มุมพีคสูงมาก (>45°) ยืนยันเร็วกว่า 750ms หรือเพิ่ม trigger "อัตราเปิดเร็ว"
6. **เทสต์กันคลาส:** วนทุก observation ที่ไม่ใช่ host ในโหมด ENTRY แล้ว assert ว่าเปิด/รีไทป์เหตุ generic ไม่ได้ (แนวเดียวกับ vocabulary ปิดของคำว่า "รถ")

---

## วิธีดึงกล่องดำ + วิเคราะห์ซ้ำ

```bash
export PATH="/c/Users/ASUS/AppData/Local/Android/Sdk/platform-tools:$PATH"
export MSYS_NO_PATHCONV=1
adb exec-out "run-as com.example.motorcycleantitheftsensor cat files/blackbox/blackbox-$(date +%Y%m%d).csv" > bb.csv
# คอลัมน์สำคัญ: 12=incidents(สะสม) 14=charging(1/0) 9=rotMaxDeg 4=mode
```
วิธียืนยัน "เปิดระหว่าง arming" ชัด ๆ: ทดสอบใหม่โดย **อาร์มแล้วรอ >10 วิ** (จน state = ทำงานปกติ) ค่อยเปิด-ปิดประตู → ควรแจ้ง

## รันเทสต์ host
```bash
export JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
export ANDROID_HOME="$LOCALAPPDATA\Android\Sdk"
cd /d/security/MotorcycleAntiTheftSensor
./gradlew.bat :app:testDebugUnitTest --tests "*EntryCloseMessageIntegrationTest" --tests "*Incident*" --tests "*Entry*"
```
