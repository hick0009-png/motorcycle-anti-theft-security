# SPEC-UX-002 — ระบบสถานะและคำแนะนำภาษาไทยทั้งแอป

## สถานะเอกสาร

ออกแบบที่ผู้ใช้อนุมัติให้จัดทำสเปกเพื่อส่งต่อ agent แล้ว

## เป้าหมาย

ทำให้ผู้ใช้รู้ได้ทันทีทั้งในแอปและ Telegram ว่า:

1. ระบบอยู่ในสถานะใด
2. สิ่งใดทำให้คำสั่งหรือการป้องกันใช้งานไม่ได้
3. ต้องทำอะไรต่อ และต้องทำบนมือถือที่รถหรือทำจาก Telegram ได้
4. เมื่อทำสำเร็จแล้วต้องเห็นผลอะไร

ปัญหาปัจจุบันคือหลาย operation มีผลลัพธ์ภายในโค้ด แต่ไม่มี feedback
ภาษาไทยที่สม่ำเสมอ ผู้ใช้จึงไม่รู้ว่า `/disarm` ถูกล็อกชั่วคราว, TOTP
ยังไม่ได้บันทึก, service offline หรือ sensor อยู่ในเหตุการณ์เดิม

## ขอบเขต

ครอบคลุมทุก surface ที่มีอยู่:

* หน้า Protection/Dashboard
* หน้า Events
* หน้า Settings รวม Bot, pairing, Authenticator, สิทธิ์ และ sensitivity
* foreground notification/service state เมื่อเหมาะสม
* Telegram ทุกคำสั่งและทุกผลลัพธ์
* เหตุการณ์ sensor และการส่ง Telegram

นอกขอบเขต:

* เปลี่ยนอัลกอริทึม TOTP, ระยะล็อก 5 นาที, pairing authorization หรือ
  เพิ่มทางลัดในการปลดรถ
* รีเซ็ต/ตั้งค่า Authenticator จาก Telegram
* เก็บ token, QR, TOTP seed, URI, pairing code หรือรหัสหกหลักไว้ใน history
  หรือ status payload

## ข้อกำหนดความปลอดภัย

1. `/disarm` ต้องใช้ TOTP ที่ตั้งค่าบนมือถือรถและยืนยันสำเร็จเท่านั้น
2. เมื่อ lockout ห้ามแสดงจำนวนครั้งที่ลองผิด, TOTP ที่คาดหวัง, seed,
   เวลา lockout ที่ละเอียด หรือปุ่ม retry ที่ทำให้เดารหัสซ้ำง่ายขึ้น
3. ผู้ใช้นอกสถานที่ต้องได้รับคำตอบตรงไปตรงมาเมื่อปัญหาต้องแก้บนมือถือรถ;
   ห้ามแสดงปุ่มหรือข้อความที่ทำให้เข้าใจว่าแก้ผ่าน Telegram ได้
4. ข้อความ, log, accessibility semantics, screenshot, event history และ
   test failure ต้องไม่มี bot token, chat ID, pairing code, QR, secret,
   `otpauth://` URI หรือรหัส TOTP ที่ผู้ใช้กรอก
5. QR/secret setup คง `FLAG_SECURE`, explicit Show/Hide และ transient state
   ตาม checkpoint `CKP-20260811-2357-YD5EKY`.
6. Demo Mode ต้องถูกทำเครื่องหมายว่า DEMO และห้ามส่ง SMS หรือโทรฉุกเฉิน
   ไม่ว่าข้อความ feedback จะบอกอะไร

## แนวคิด UX ที่เลือก: Hybrid feedback

ใช้ feedback สองชั้นเสมอ:

| ชั้น | ใช้เมื่อ | อายุ | ตัวอย่าง |
| --- | --- | --- | --- |
| Popup/Snackbar | เริ่มทำงาน, สำเร็จ, ข้อผิดพลาดที่ผู้ใช้เพิ่งกด | 3–6 วินาที | `กำลังตรวจสอบรหัสยืนยัน…` |
| Status banner/card | ปัญหาที่ยังต้องแก้หรือสถานะความปลอดภัย | ค้างจนสถานะเปลี่ยน | `ยังไม่ได้ตั้งค่า Authenticator` |
| Events | ประวัติ security/delivery ที่ไม่ลับ | คงตาม retention เดิม | `ตรวจพบแรงสั่นสะเทือน — ส่ง Telegram แล้ว` |
| Telegram reply | ผลของคำสั่งระยะไกล | ข้อความตอบกลับหนึ่งรายการ | `🔒 ปลดระบบไม่ได้…` |

ห้ามใช้ popup อย่างเดียวกับปัญหาที่ไม่หายเอง เพราะผู้ใช้อาจพลาดข้อความแล้ว
กลับมาไม่รู้ว่าต้องทำอะไร

## สถาปัตยกรรม feedback

### 1. ข้อมูลกลางที่ไม่ลับ

สร้าง model กลาง เช่น `UserGuidance` แยกจาก `ProtectionSnapshot`:

```kotlin
data class UserGuidance(
    val code: GuidanceCode,
    val severity: GuidanceSeverity,
    val title: String,
    val body: String,
    val action: GuidanceAction?,
    val surface: GuidanceSurface,
    val isPersistent: Boolean,
    val createdAtMs: Long,
)
```

`UserGuidance` เก็บเฉพาะ code/สถานะที่ sanitize แล้ว เช่น
`TOTP_LOCKED`, `BOT_TOKEN_MISSING`, `SENSOR_LIGHT_UNAVAILABLE` ห้ามเก็บ text
คำสั่งดิบ, รหัสที่กรอก, token, QR หรือ secret.

`ProtectionCoordinator` ยังคงเป็นเจ้าของ authoritative protection state
เหมือนเดิม. `UserGuidance` เป็น presentation/operation feedback แยกต่างหาก;
ห้ามบิด `ProtectionState` เป็น `ALERT_ACTIVE` เพียงเพราะ TOTP ผิดหรือ
Telegram สั่งงานไม่สำเร็จ.

### 2. แหล่งกำเนิดและการแสดงผล

* Settings/ViewModel สร้าง feedback จากผล save, pairing, permission และ
  Authenticator setup.
* TelegramBotClient แปลงผล authorization/TOTP/parser เป็น `GuidanceCode`
  แล้วใช้ catalog เดียวกันสร้างข้อความ Telegram. หากแอปกำลังเปิดอยู่ให้
  publish feedback แบบ sanitize ไปยัง UI store ด้วย.
* SensorService/ProtectionRuntimeGraph สร้าง feedback จาก service lifecycle,
  detector health, incident และ delivery state.
* `GuidanceCatalog` เป็นแหล่งข้อความภาษาไทยเพียงที่เดียว; UI และ Telegram
  ใช้ key เดียวกัน แต่ Telegram ใช้ข้อความแบบย่อ.

### 3. การจัดลำดับ

1. `ALERT_ACTIVE` และการส่งเหตุการณ์ล้มเหลวสำหรับเหตุการณ์จริง มีลำดับสูงสุด
2. ปัญหาที่ทำให้ arm/disarm ไม่ได้ (TOTP, pairing, token, service offline)
3. sensor degraded หรือ permission ที่ขาด
4. ข้อความสำเร็จและข้อมูลทั่วไป

แสดง persistent banner เพียงหนึ่งรายการที่มีลำดับสูงสุดต่อหน้า เพื่อลดการ
ทับกันของ popup. Events ยังบันทึกเหตุการณ์รองได้ตามปกติ.

### 4. รูปแบบ visual และ accessibility

* สำเร็จ: ไอคอน check + สี success + ข้อความ
* กำลังทำงาน: progress indicator + ข้อความ
* ต้องดำเนินการ: ไอคอน warning + สี warning + primary action
* วิกฤต: ไอคอน shield/alert + สี error + action ที่ปลอดภัย
* ทุก state ต้องสื่อความหมายด้วยไอคอนและข้อความ ไม่ใช้สีเพียงอย่างเดียว
* ปุ่มและ touch target อย่างน้อย 48dp; title อ่านได้ในหนึ่งบรรทัดเท่าที่ทำได้
* content description ต้องเป็นข้อความทั่วไปที่ไม่รวมข้อมูลลับ

## องค์ประกอบหน้าจอ

### Protection/Dashboard

เหนือการควบคุม Arm/Disarm แสดง `ProtectionStatusCard` หนึ่งใบ:

```text
🟡 การป้องกันทำงานแบบจำกัด
ยังไม่ได้ตั้งค่า Authenticator
การปลดระบบผ่าน Telegram จะยังใช้งานไม่ได้
[ตั้งค่า Authenticator]
```

การ์ดแสดงสถานะความปลอดภัยหลักและปัญหาที่ต้องแก้สูงสุด; ปัญหารองอยู่ใน
`ดูรายละเอียดระบบ` หรือ Events. ขณะ `ARMING` ให้บอกเวลานับถอยหลัง. ขณะ
`ALERT_ACTIVE` ให้แสดง source/severity/time ที่ sanitize แล้ว พร้อมปุ่ม
`ดูเหตุการณ์` ไม่ใช่ปุ่มปลดที่ข้าม TOTP.

### Settings

เพิ่ม card `ความพร้อมการควบคุมผ่าน Telegram` ไว้ก่อน section ตั้งค่า:

```text
ความพร้อมการควบคุมผ่าน Telegram
✓ เชื่อมต่อ Bot แล้ว
✓ จับคู่เจ้าของแล้ว
⚠ ยังไม่ได้ตั้งค่า Authenticator
○ สิทธิ์การแจ้งเตือนยังไม่พร้อม
[ตั้งค่า Authenticator]
```

การแสดง pairing code อยู่ได้เฉพาะบนมือถือรถตาม flow เดิมและต้องไม่ถูกส่งต่อ
ไป Events, Snackbar, accessibility หรือ Telegram. สถานะทุกข้อกดเพื่อไปยัง
section ที่แก้ปัญหานั้นได้.

Authenticator dialog ต้องมีลำดับชัดเจน:

1. `ขั้นที่ 1 จาก 2: สแกน QR หรือกรอกรหัสลับในแอป Authenticator`
2. `ขั้นที่ 2 จาก 2: กรอกรหัส 6 หลักที่กำลังแสดงเพื่อยืนยัน`
3. ยืนยันสำเร็จ: ปิด dialog, refresh summary, popup สำเร็จ และ Settings
   แสดง `Authenticator ตั้งค่าแล้ว`
4. ยกเลิก/ล้มเหลว: seed candidate และ bitmap ถูกทิ้งทันที; state เดิมยังอยู่

### Events

แต่ละ row ต้องแสดงชนิด, เวลา, delivery state และคำอธิบายที่ทำให้เข้าใจ
anti-spam behavior เช่น `เหตุการณ์เดิมกำลังบันทึกหลักฐานเพิ่ม — ยังไม่ส่ง
ข้อความซ้ำ`. ห้ามแสดง raw sensor payload ที่ระบุตัวตน/ตำแหน่งเกินความจำเป็น
หรือความลับการตั้งค่า.

### Telegram

Telegram ไม่มี popup; ใช้ข้อความย่อเดียวกับ catalog, ขึ้นต้นด้วยสัญลักษณ์
สม่ำเสมอ (`✅`, `ℹ️`, `⚠️`, `🔒`, `🚨`). ทุก reply ต้องบอกผลและ next step
เมื่อผู้ใช้แก้เองได้. ไม่ใช้ Markdown parse mode กับข้อความ status ทั่วไป.

## Message catalog ภาษาไทย

### A. Service และสถานะป้องกัน

| Code | App: title / body | Telegram: ข้อความย่อ | Action |
| --- | --- | --- | --- |
| `SETUP_REQUIRED` | `ต้องตั้งค่าระบบก่อนเปิดการป้องกัน` / `ตั้งค่า Bot, จับคู่เจ้าของ และ Authenticator ให้ครบ` | `⚠️ ระบบยังตั้งค่าไม่ครบ ดูหน้าการตั้งค่าบนมือถือรถ` | ไป Settings |
| `DISARMED` | `การป้องกันปิดอยู่` / `ระบบออนไลน์และพร้อมเปิดการป้องกัน` | `ℹ️ ปิดการป้องกันแล้ว` | Arm |
| `ARMING` | `กำลังเปิดการป้องกัน` / `กำลังปรับเทียบเซนเซอร์ เหลือ {seconds} วินาที` | `ℹ️ กำลังเปิดการป้องกัน รอการปรับเทียบเซนเซอร์` | ไม่มี |
| `ARMED_HEALTHY` | `การป้องกันทำงานปกติ` / `ระบบหลักพร้อมทำงาน` | `✅ การป้องกันทำงานปกติ` | ดูสถานะ |
| `ARMED_DEGRADED` | `การป้องกันทำงานแบบจำกัด` / `{reason}` | `⚠️ การป้องกันทำงานแบบจำกัด: {reason}` | ดูรายละเอียด |
| `ALERT_ACTIVE` | `ตรวจพบเหตุการณ์ความปลอดภัย` / `{incidentType} เวลา {time}` | `🚨 ตรวจพบเหตุการณ์: {incidentType}` | ดู Events |
| `OFFLINE` | `บริการป้องกันไม่ทำงาน` / `ตรวจสอบว่าแอปยังทำงานบนมือถือรถ` | `⚠️ บริการป้องกัน offline ต้องตรวจสอบมือถือรถ` | เปิดแอป/เริ่ม service |
| `SERVICE_RECOVERED` | `บริการป้องกันกลับมาทำงานแล้ว` / `กำลังกู้คืนสถานะล่าสุด` | `ℹ️ บริการป้องกันกลับมาทำงานแล้ว` | ไม่มี |

`{reason}` ต้องเป็น label ที่ปลอดภัย เช่น `Telegram ยังไม่พร้อม`,
`เซนเซอร์แสงไม่พร้อม`, `ไม่มีสิทธิ์การแจ้งเตือน`; ห้ามใส่ exception, token
หรือ identifier.

### B. Bot, pairing และ Telegram connectivity

| Code | App: title / body | Telegram: ข้อความย่อ | Action |
| --- | --- | --- | --- |
| `BOT_VERIFYING` | `กำลังตรวจสอบ Bot…` / `กำลังเชื่อมต่อ Telegram อย่างปลอดภัย` | ไม่ตอบเพิ่ม | รอ |
| `BOT_CONNECTED` | `เชื่อมต่อ Bot สำเร็จ` / `พร้อมรับคำสั่งจากเจ้าของที่จับคู่แล้ว` | `✅ Telegram พร้อมใช้งาน` | ไม่มี |
| `BOT_TOKEN_INVALID` | `เชื่อมต่อ Bot ไม่สำเร็จ` / `ตรวจสอบ token แล้วลองบันทึกใหม่` | ไม่ตอบเพิ่ม | แก้ token |
| `TELEGRAM_UNREACHABLE` | `เชื่อมต่อ Telegram ไม่ได้` / `ตรวจสอบอินเทอร์เน็ตของมือถือรถ` | `⚠️ ส่งคำตอบไม่ได้ โปรดลองใหม่ภายหลัง` | ตรวจเครือข่าย |
| `PAIRING_REQUIRED` | `ยังไม่ได้จับคู่เจ้าของ` / `ใช้รหัสจับคู่ในหน้า Settings จาก Telegram` | `ℹ️ ใช้ /pair <รหัสจับคู่> เพื่อเชื่อมบัญชีนี้` | ดู pairing |
| `PAIRING_ACCEPTED` | `จับคู่เจ้าของสำเร็จ` / `บัญชี Telegram นี้สั่งงานได้แล้ว` | `✅ จับคู่เจ้าของสำเร็จ` | ไม่มี |
| `PAIRING_INVALID_OR_EXPIRED` | `จับคู่ไม่สำเร็จ` / `รหัสไม่ถูกต้องหรือหมดอายุ สร้างรหัสใหม่บนมือถือรถ` | `⚠️ รหัสจับคู่ไม่ถูกต้องหรือหมดอายุ` | สร้างรหัสใหม่ |
| `UNAUTHORIZED_COMMAND` | `คำสั่งจากบัญชีที่ไม่ได้รับอนุญาต` / `ไม่มีการเปลี่ยนสถานะรถ` | `🔒 บัญชีนี้ไม่ได้รับอนุญาตให้สั่งงาน` | ไม่มี |

### C. Authenticator และ TOTP

| Code | App: title / body | Telegram: ข้อความย่อ | Action |
| --- | --- | --- | --- |
| `TOTP_SETUP_START` | `ตั้งค่า Authenticator` / `สแกน QR แล้วกรอกรหัส 6 หลักเพื่อยืนยัน` | ไม่ตอบเพิ่ม | แสดง QR |
| `TOTP_SETUP_VERIFYING` | `กำลังยืนยัน Authenticator…` / `ตรวจสอบรหัสที่กรอก` | ไม่ตอบเพิ่ม | รอ |
| `TOTP_CONFIGURED` | `ตั้งค่า Authenticator สำเร็จ` / `ใช้ปลดระบบผ่าน Telegram ได้แล้ว` | `✅ Authenticator พร้อมใช้งาน` | เสร็จสิ้น |
| `TOTP_SETUP_INVALID` | `ยืนยัน Authenticator ไม่สำเร็จ` / `ตรวจสอบเวลาอัตโนมัติ แล้วรอรหัสรอบใหม่` | ไม่ตอบเพิ่ม | ลองใหม่ใน app |
| `TOTP_NOT_CONFIGURED` | `ยังไม่ได้ตั้งค่า Authenticator` / `ต้องตั้งค่าบนมือถือรถก่อนปลดระบบผ่าน Telegram` | `🔒 ต้องตั้งค่า Authenticator บนมือถือรถก่อน` | ไป Authenticator |
| `TOTP_REQUIRED` | `ต้องใช้รหัสยืนยัน` / `กรอกคำสั่งพร้อมรหัส 6 หลักจาก Authenticator` | `🔒 ใช้ /disarm ตามด้วยรหัส 6 หลักจาก Authenticator` | ไม่มี |
| `TOTP_INVALID` | `รหัสยืนยันไม่ถูกต้อง` / `ตรวจสอบเวลาอัตโนมัติและใช้รหัสรอบใหม่` | `⚠️ รหัสยืนยันไม่ถูกต้อง ใช้รหัสรอบใหม่แล้วลองอีกครั้ง` | ไม่มี |
| `TOTP_LOCKED` | `รหัสยืนยันถูกล็อกชั่วคราว` / `เพื่อความปลอดภัย โปรดรออย่างน้อย 5 นาที` | `🔒 รหัสยืนยันถูกล็อกชั่วคราว โปรดรออย่างน้อย 5 นาที` | ไม่มี retry |
| `TOTP_REMOTE_RECOVERY_REQUIRED` | `ต้องเข้าถึงมือถือที่รถ` / `การตั้งค่าใหม่และการกู้คืน Authenticator ทำผ่าน Telegram ไม่ได้` | `🔒 ต้องเข้าถึงมือถือที่รถเพื่อตั้งค่า Authenticator ใหม่` | ไม่มี |

### D. คำสั่ง Telegram

| Code | Telegram reply | App feedback |
| --- | --- | --- |
| `COMMAND_STATUS_SUCCESS` | `ℹ️ สถานะรถ: {protectionStatus}` | update status card only |
| `COMMAND_ARM_APPLIED` | `✅ กำลังเปิดการป้องกัน โปรดรอการปรับเทียบเซนเซอร์` | `กำลังเปิดการป้องกัน…` |
| `COMMAND_ARM_REJECTED` | `⚠️ เปิดการป้องกันไม่ได้: {safeReason}` | banner + action จาก reason |
| `COMMAND_DISARM_APPLIED` | `✅ ปลดการป้องกันสำเร็จ` | `ปลดการป้องกันสำเร็จ` |
| `COMMAND_DISARM_REJECTED` | `⚠️ ปลดการป้องกันไม่ได้: {safeReason}` | banner + action จาก reason |
| `COMMAND_SENSITIVITY_APPLIED` | `✅ ปรับความไวเป็นระดับ {level} แล้ว` | `บันทึกความไวแล้ว` |
| `COMMAND_SENSITIVITY_INVALID` | `⚠️ ระดับความไวต้องอยู่ระหว่าง 1 ถึง 10` | `ระดับความไวไม่ถูกต้อง` |
| `COMMAND_HELP` | `ℹ️ คำสั่ง: /status, /arm, /disarm <รหัส>, /sensitivity 1-10` | ไม่มี |
| `COMMAND_UNKNOWN` | `ℹ️ ไม่พบคำสั่ง พิมพ์ /help เพื่อดูคำสั่งที่ใช้ได้` | ไม่มี |

`{protectionStatus}` และ `{safeReason}` ต้องผ่าน formatter ภาษาไทยกลาง
ห้ามส่งชื่อ class, exception หรือ internal state ที่ทำให้สับสน.

### E. Sensor, incident และ delivery

| Code | App: title / body | Telegram |
| --- | --- | --- |
| `SENSOR_HEALTHY` | `เซนเซอร์พร้อมใช้งาน` / `{sensorName} กำลังอ่านค่า` | ไม่ส่งข้อความ |
| `SENSOR_UNAVAILABLE` | `เซนเซอร์ไม่พร้อมใช้งาน` / `{sensorName}: {safeReason}` | รวมใน `/status` |
| `SENSOR_PERMISSION_MISSING` | `ต้องอนุญาตสิทธิ์` / `เปิดสิทธิ์ {permissionName} เพื่อใช้ {featureName}` | ไม่ส่งข้อความ |
| `SENSOR_SAMPLE_FAILED` | `อ่านค่าเซนเซอร์ไม่สำเร็จ` / `ระบบยังทำงานด้วยเซนเซอร์ที่พร้อม` | รวมใน `/status` |
| `INCIDENT_OPENED` | `ตรวจพบ {incidentType}` / `กำลังส่งการแจ้งเตือน` | `🚨 ตรวจพบ {incidentType}` |
| `INCIDENT_UPDATED` | `เหตุการณ์เดิมกำลังอัปเดต` / `บันทึกหลักฐานเพิ่มโดยไม่ส่งข้อความซ้ำ` | ไม่ส่งข้อความ |
| `INCIDENT_ESCALATED` | `เหตุการณ์รุนแรงขึ้น` / `{incidentType}` | `🚨 เหตุการณ์รุนแรงขึ้น: {incidentType}` |
| `INCIDENT_CLOSED` | `เหตุการณ์สิ้นสุดแล้ว` / `ไม่มีความเคลื่อนไหวต่อเนื่อง 30 วินาที` | `ℹ️ เหตุการณ์สิ้นสุดแล้ว` |
| `TELEGRAM_DELIVERY_SENDING` | `กำลังส่ง Telegram…` | ไม่มีข้อความเพิ่ม |
| `TELEGRAM_DELIVERY_SENT` | `ส่ง Telegram สำเร็จ` | ข้อความเหตุการณ์หลัก |
| `TELEGRAM_DELIVERY_FAILED` | `ส่ง Telegram ไม่สำเร็จ` / `ระบบจะรายงานสถานะการเชื่อมต่อ` | ไม่มีการตอบซ้ำ |
| `SMS_FALLBACK_USED` | `ใช้ช่องทางสำรองสำหรับเหตุการณ์วิกฤต` | ไม่เปิดเผยปลายทางหรือ key |

เหตุการณ์เดียวกันส่ง Telegram ตอนเปิด, ตอนยกระดับ และตอนปิดเท่านั้น;
movement ต่อเนื่องเป็น `INCIDENT_UPDATED` เพื่อป้องกัน spam.

### F. Permissions และ input validation

| Code | App: title / body | Action |
| --- | --- | --- |
| `NOTIFICATION_PERMISSION_MISSING` | `ยังไม่ได้อนุญาตการแจ้งเตือน` / `เปิดสิทธิ์เพื่อเห็นสถานะสำคัญบนมือถือรถ` | เปิด permission request |
| `MICROPHONE_PERMISSION_MISSING` | `ไมโครโฟนยังไม่พร้อม` / `การตรวจจับเสียงจะไม่ทำงาน แต่ระบบส่วนอื่นยังทำงาน` | เปิด permission request |
| `LOCATION_PERMISSION_MISSING` | `ตำแหน่งยังไม่พร้อม` / `ข้อมูลตำแหน่งจะไม่ถูกรวมในเหตุการณ์` | เปิด permission request |
| `SMS_PERMISSION_DENIED` | `SMS ถูกปิดเพื่อความปลอดภัย` / `ไม่มีการส่ง SMS จนกว่าผู้ใช้จะอนุญาตตาม flow ที่กำหนด` | ดูรายละเอียด |
| `SETTINGS_SAVE_SUCCESS` | `บันทึกการตั้งค่าสำเร็จ` | ไม่มี |
| `SETTINGS_SAVE_FAILED` | `บันทึกการตั้งค่าไม่สำเร็จ` / `ตรวจสอบข้อมูลแล้วลองใหม่` | ลองใหม่ |

## การกระทำจาก feedback

| Action | พฤติกรรม |
| --- | --- |
| `OpenAuthenticatorSettings` | เปิด section Authenticator ใน Settings; ไม่เปิด QR อัตโนมัติ |
| `OpenTelegramSettings` | เปิด token/pairing section ใน Settings |
| `RequestPermission(permission)` | ขอ permission ผ่าน Android flow เดิม; ถ้าถูกปฏิเสธ บอกวิธีเปิดใน Settings ระบบ |
| `OpenProtectionDetails` | เปิดหน้า Protection และเลื่อนไป status card |
| `OpenEventDetails` | เปิด Events และเลือก incident ล่าสุด |
| `RetrySafeOperation` | ใช้เฉพาะ token verify/settings save ที่ไม่ใช่ TOTP หรือ remote disarm |
| `None` | สำหรับ lockout, unauthorized และข้อมูลทั่วไป |

## กฎการแสดงผลตามจุดใช้งาน

1. ผู้ใช้กดปุ่มในแอป: แสดง `in progress` ทันที แล้วตามด้วย success/error
   popup; ถ้ายังแก้ไม่เสร็จให้คง banner.
2. คำสั่งมาจาก Telegram: ตอบ Telegram ก่อนเสมอ. หากแอป foreground ให้แสดง
   popup sanitize; หาก background ให้ status card/Events อัปเดตเมื่อเปิดแอป.
3. Sensor เริ่ม incident: ส่ง Telegram ตาม delivery policy, อัปเดต
   Protection card และ Events. ห้ามแสดง popup ซ้ำสำหรับทุก sample.
4. Network/delivery ขัดข้อง: ไม่กล่าวว่าส่งสำเร็จจน transport ยืนยัน;
   `/status` ต้องสะท้อนสถานะจริงโดยไม่เปิดเผยข้อมูลลับ.
5. เปลี่ยน setting สำเร็จ: refresh `ProtectionSettingsSummary` ก่อนแสดง
   success final เพื่อไม่ให้ UI กล่าวว่าสำเร็จแต่ state เก่ายังปรากฏ.

## ไฟล์คาดว่าจะเกี่ยวข้อง

agent ต้องยืนยัน scope กับ working tree อีกครั้งก่อนแก้ แต่จุดเชื่อมหลักคือ:

* `ui/ProtectionUiModels.kt` — UI feedback model/state
* `ui/ProtectionViewModel.kt` — UI event stream, persistent guidance และ actions
* `ui/ProtectionAppScreen.kt` — host Snackbar/status banner
* `ui/protection/ProtectionScreen.kt` — protection status card
* `ui/events/EventsScreen.kt` — event feedback labels
* `ui/settings/SettingsScreen.kt` — remote readiness checklist และ Authenticator guidance
* `telegram/TelegramBotClient.kt` และ `telegram/TelegramCommandHandler.kt` —
  command outcome → Thai Telegram message, no secret logging
* `protection/ProtectionRuntimeGraph.kt`, `protection/IncidentUpdateDeliveryPolicy.kt`
  และ `service/SensorService.kt` — service/sensor/delivery guidance source
* tests ที่อยู่ใกล้แต่ละ layer; เพิ่มไฟล์ใหม่เฉพาะเมื่อไม่มีที่เหมาะสม

ห้าม refactor ใหญ่หรือย้าย ownership ของ `ProtectionCoordinator`.

## คู่มือปฏิบัติงานสำหรับ agent (ต้องทำตามลำดับนี้)

ส่วนนี้ตั้งใจเขียนให้ทำงานตามได้โดยไม่ต้องเดา ห้ามข้ามข้อ, ห้ามรวมหลาย task
เป็น commit เดียว และห้ามแก้ข้อความ production ก่อนมี test ที่พิสูจน์ผลลัพธ์
ของ task นั้น

### ขั้นเตรียมงาน

- [ ] อ่าน `AGENTS.md`, `docs/ENGINEERING_PLAYBOOK.md`, สเปกนี้ และ
      `work/tasks/TASK-TOTP-002-authenticator-enrollment-and-remote-disarm.md`
- [ ] ดู `git status --short`; ห้าม reset, clean, checkout, stage ทั้ง tree
      หรือแก้ไฟล์ที่ไม่อยู่ใน scope นี้
- [ ] รัน baseline จาก `MotorcycleAntiTheftSensor`:

  ```powershell
  $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
  .\gradlew.bat testDebugUnitTest --no-daemon --max-workers=1
  ```

  บันทึกจำนวน tests/failures ตามจริง. ณ เวลาสร้างสเปก full suite เคยล้ม 3
  tests เพราะ `TotpAuthenticator.kt` ใช้ `android.util.Log` ใน local unit test
  และพิมพ์ input/expected code/seed. ห้ามปิดหรือ ignore tests เพื่อให้ผ่าน.

### Task 1 — ปิด secret leak ก่อนเริ่ม UX work

**เป้าหมาย:** ไม่มี code path ใดพิมพ์หรือส่งต่อข้อมูล TOTP ที่เป็นความลับ

**ไฟล์:**

* Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/security/TotpAuthenticator.kt`
* Create: `MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/security/TotpAuthenticatorSourceContractTest.kt`
* Test: `MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/security/TotpAuthenticatorTest.kt`

**ลำดับทำงาน:**

- [ ] เขียน `TotpAuthenticatorSourceContractTest` ให้เปิด source file แล้ว assert
      ว่าไม่มี `TOTP_DEBUG`, `android.util.Log`, `Checking input:`,
      `TOTP mismatch!`, `expectedCode` และ `base32Seed` อยู่ใน log template
- [ ] รัน test นี้; ต้อง RED เพราะ source ปัจจุบันมี logging ดังกล่าว
- [ ] ลบเฉพาะ debug logging ที่พิมพ์ input TOTP, expected code และ seed จาก
      `verifyCodeAgainstSeed`; ห้ามเปลี่ยน SHA-1, 6 digits, 30-second period,
      +/-1 time window, max 3 failures หรือ lockout 5 นาที
- [ ] รัน `TotpAuthenticatorTest` และ source-contract test; ต้อง GREEN
- [ ] รัน full `testDebugUnitTest`; ถ้ามี failure อื่น ให้รายงานชื่อ test และ
      root cause ก่อนทำ Task ถัดไป

**ผลที่ต้องได้:** ไม่มี secret leak และ local unit suite สามารถ test TOTP
behavior ได้โดยไม่เรียก Android `Log` ที่ไม่ได้ mock.

### Task 2 — สร้าง domain guidance model และ Thai catalog

**เป้าหมาย:** ใช้ code เดียวเป็นต้นทางข้อความใน UI และ Telegram; ห้ามให้
แต่ละ screen หรือ command handler แต่งข้อความเอง

**ไฟล์:**

* Create: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/UserGuidance.kt`
* Create: `MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/protection/UserGuidanceCatalogTest.kt`
* Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt`

**ให้สร้าง interface ตามนี้:**

```kotlin
enum class GuidanceSeverity { INFO, SUCCESS, WARNING, CRITICAL }

enum class GuidanceAction {
    NONE,
    OPEN_AUTHENTICATOR_SETTINGS,
    OPEN_TELEGRAM_SETTINGS,
    OPEN_PERMISSION_SETTINGS,
    OPEN_PROTECTION,
    OPEN_EVENTS,
    RETRY_NON_SENSITIVE_SETTINGS,
}

data class GuidanceContent(
    val titleTh: String,
    val bodyTh: String,
    val telegramTh: String?,
    val severity: GuidanceSeverity,
    val action: GuidanceAction,
    val persistent: Boolean,
)

object UserGuidanceCatalog {
    fun content(code: GuidanceCode, detail: GuidanceDetail = GuidanceDetail.None): GuidanceContent
}
```

`GuidanceDetail` ต้องเป็น sealed type ที่รับเฉพาะข้อมูลปลอดภัยต่อไปนี้:

* `None`
* `ArmingSeconds(Int)` — จำกัด 0..10
* `SensitivityLevel(Int)` — จำกัด 1..10
* `ProtectionStateValue(ProtectionState)`
* `IncidentTypeValue(IncidentType)`
* `SensorKindValue(SensorKind)`
* `SafeReason(ReasonLabel)` โดย `ReasonLabel` เป็น enum ที่ whitelist เท่านั้น

ห้ามใช้ `String` อิสระ, `Map<String, Any>`, exception message, command text,
chat ID, token, pairing code, TOTP/seed/URI/QR หรือ location detail เป็น
`GuidanceDetail`.

**`GuidanceCode` ที่ต้องมีอย่างน้อย:**

```text
SETUP_REQUIRED, DISARMED, ARMING, ARMED_HEALTHY, ARMED_DEGRADED,
ALERT_ACTIVE, OFFLINE, SERVICE_RECOVERED,
BOT_VERIFYING, BOT_CONNECTED, BOT_TOKEN_INVALID, TELEGRAM_UNREACHABLE,
PAIRING_REQUIRED, PAIRING_ACCEPTED, PAIRING_INVALID_OR_EXPIRED,
UNAUTHORIZED_COMMAND,
TOTP_SETUP_START, TOTP_SETUP_VERIFYING, TOTP_CONFIGURED, TOTP_SETUP_INVALID,
TOTP_NOT_CONFIGURED, TOTP_REQUIRED, TOTP_INVALID, TOTP_LOCKED,
TOTP_REMOTE_RECOVERY_REQUIRED,
COMMAND_STATUS_SUCCESS, COMMAND_ARM_APPLIED, COMMAND_ARM_REJECTED,
COMMAND_DISARM_APPLIED, COMMAND_DISARM_REJECTED,
COMMAND_SENSITIVITY_APPLIED, COMMAND_SENSITIVITY_INVALID, COMMAND_HELP,
COMMAND_UNKNOWN,
SENSOR_HEALTHY, SENSOR_UNAVAILABLE, SENSOR_PERMISSION_MISSING,
SENSOR_SAMPLE_FAILED, INCIDENT_OPENED, INCIDENT_UPDATED, INCIDENT_ESCALATED,
INCIDENT_CLOSED, TELEGRAM_DELIVERY_SENDING, TELEGRAM_DELIVERY_SENT,
TELEGRAM_DELIVERY_FAILED, SMS_FALLBACK_USED,
NOTIFICATION_PERMISSION_MISSING, MICROPHONE_PERMISSION_MISSING,
LOCATION_PERMISSION_MISSING, SMS_PERMISSION_DENIED,
SETTINGS_SAVE_SUCCESS, SETTINGS_SAVE_FAILED
```

**ลำดับทำงาน:**

- [ ] เขียน catalog tests ก่อน โดย assert exact Thai title/body/telegram,
      severity, action และ `persistent` ของทุก code ในตาราง Message catalog
- [ ] เพิ่ม negative tests ที่วนทุก `GuidanceContent` และ assert ว่าไม่มี
      string ต่อไปนี้ใน title/body/telegram: `otpauth://`, `secret=`,
      `TOTP_DEBUG`, `chat_id`, test seed `JBSWY3DPEHPK3PXP` และ test code
      `123456`. คำว่า `Bot` ใช้ได้ เพราะเป็นชื่อบริการในข้อความที่ผู้ใช้ต้องเห็น
- [ ] รัน tests; ต้อง RED เพราะ catalog ยังไม่มี
- [ ] Implement `UserGuidance.kt`; แก้ code ได้เฉพาะเพื่อให้ tests ผ่าน
- [ ] ใช้ Thai copy ตามตารางด้านบนแบบตัวต่อตัว ไม่แปลใหม่เอง และห้ามใช้ raw
      enum `.name` หรือ raw exception เป็นข้อความผู้ใช้
- [ ] รัน `UserGuidanceCatalogTest`; ต้อง GREEN

**Definition of done:** catalog เป็น exhaustive `when` หรือมี test ที่ fail
ทันทีเมื่อเพิ่ม `GuidanceCode` ใหม่แต่ไม่ได้เพิ่มข้อความ. `TOTP_LOCKED` ต้อง
มี `GuidanceAction.NONE` และ `persistent=true`.

### Task 3 — ขยาย UI event เดิม โดยไม่สร้าง event bus ซ้ำซ้อน

ปัจจุบันมี `ProtectionUiMessage(id, text, isError)` และ `SnackbarHost` อยู่แล้ว.
ให้เปลี่ยนจาก raw English text เป็น typed guidance และให้ `ProtectionViewModel`
เป็นผู้ publish app-local feedback.

**ไฟล์:**

* Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt`
* Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt`
* Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreen.kt`
* Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/Navigation.kt`
* Test: `MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt`
* Test: `MotorcycleAntiTheftSensor/app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt`

**รูปแบบ model ที่ต้องได้:**

```kotlin
data class ProtectionUiMessage(
    val id: Long,
    val code: GuidanceCode,
    val detail: GuidanceDetail = GuidanceDetail.None,
)

data class PersistentGuidance(
    val code: GuidanceCode,
    val detail: GuidanceDetail = GuidanceDetail.None,
)
```

`ProtectionUiState` เพิ่ม `persistentGuidance: PersistentGuidance?`.
`ProtectionAppScreen` resolve content ผ่าน `UserGuidanceCatalog`, จึงใช้
`content.bodyTh` ใน Snackbar. ไม่รับ pre-formatted string จาก ViewModel.

**ลำดับทำงาน:**

- [ ] เขียน ViewModel tests สำหรับ `arm`, `disarm`, `changeSensitivity`,
      `replaceBotToken`, `resetPairing`, `beginAuthenticatorSetup` และ
      `verifyAuthenticator` ว่า publish code ที่ถูกต้อง ไม่ใช่ raw English
      reason
- [ ] เขียน Compose test ให้ Snackbar แสดงข้อความไทยจาก `TOTP_CONFIGURED`
      และ Consume แล้วหายเพียง popup; persistent card ยังอยู่ถ้าปัญหายังไม่หาย
- [ ] ทำ tests ให้ RED
- [ ] เปลี่ยน `publishMessage(text, isError)` เป็น
      `publishMessage(code, detail)`; เปลี่ยน caller ทุกตัวตาม mapping นี้:

  | Existing outcome | Required code |
  | --- | --- |
  | arm applied/rejected | `COMMAND_ARM_APPLIED` / `COMMAND_ARM_REJECTED` |
  | local disarm applied/rejected | `COMMAND_DISARM_APPLIED` / `COMMAND_DISARM_REJECTED` |
  | sensitivity applied/invalid | `COMMAND_SENSITIVITY_APPLIED` / `COMMAND_SENSITIVITY_INVALID` |
  | token save applied/failed | `BOT_CONNECTED` / `BOT_TOKEN_INVALID` |
  | pairing reset applied/failed | `SETTINGS_SAVE_SUCCESS` / `SETTINGS_SAVE_FAILED` |
  | Authenticator started | `TOTP_SETUP_START` |
  | Authenticator verification in-flight | `TOTP_SETUP_VERIFYING` |
  | Authenticator verified / rejected | `TOTP_CONFIGURED` / `TOTP_SETUP_INVALID` |
  | caught settings/event error | `SETTINGS_SAVE_FAILED` only; never exception text |

- [ ] ให้ in-flight guidance แสดงก่อน launch operation, final guidance หลัง
      result. ถ้า coroutine ถูก cancel ห้ามแสดง success/error final message
- [ ] เพิ่ม `GuidanceAction` handler ใน `ProtectionAppActions` เฉพาะ actions
      ที่มีอยู่จริง: Settings/Authenicator ใช้ `selectDestination(SETTINGS)`,
      Events ใช้ `selectDestination(EVENTS)`, Protection ใช้
      `selectDestination(PROTECTION)`. อย่าเพิ่ม Android intent ใหม่ใน task นี้
- [ ] รัน ViewModel + Compose tests จน GREEN

### Task 4 — ทำ persistent status card และ readiness checklist

**ไฟล์:**

* Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/ProtectionScreen.kt`
* Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt`
* Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt`
* Test: `MotorcycleAntiTheftSensor/app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt`

**กฎการคำนวณ `persistentGuidance` (เรียงลำดับจากสูงไปต่ำ):**

1. `ALERT_ACTIVE` เมื่อ `protection.state == ALERT_ACTIVE`
2. `OFFLINE` เมื่อ `serviceRunning == false` หรือ state เป็น `OFFLINE`
3. `snapshot.lastRemoteGuidance` เมื่อ catalog ของ code นั้นมี
   `persistent=true` (ตัวอย่าง `TOTP_LOCKED`); ใช้ code นี้เพียงจนกว่าจะมี
   remote guidance ใหม่ หรือ state ปัจจุบันข้อ 1–2 เปลี่ยน
4. `TOTP_NOT_CONFIGURED` เมื่อ `settings.authenticatorConfigured == false`
5. `PAIRING_REQUIRED` เมื่อ `pairedOwnerCount == 0`
6. `BOT_TOKEN_INVALID` เมื่อ `tokenConfigured == false`
7. permission blocker ที่มีอยู่จริง → `NOTIFICATION_PERMISSION_MISSING` หรือ
   `SENSOR_PERMISSION_MISSING`
8. `ARMED_DEGRADED` เมื่อ `degradationReasons` ไม่ว่าง
9. ไม่มี card พิเศษเมื่อ state คือ `ARMED_HEALTHY` หรือ `DISARMED_ONLINE`

หากหลายเงื่อนไขจริง ให้แสดง card เดียวตามลำดับนี้เท่านั้น; checklist ใน
Settings แสดงรายละเอียดครบทุกข้อ. การ์ดไม่ต้องเก็บ/แสดง raw reason string
จาก `degradationReasons`; map ผ่าน `ReasonLabel` ก่อน.

**ProtectionScreen ต้องเปลี่ยนข้อความเหล่านี้เป็น Thai catalog/copy:**

```text
Armed in {seconds} seconds       -> ระบบจะเปิดในอีก {seconds} วินาที
Arm protection                   -> เปิดการป้องกัน
Disarm protection                -> ปิดการป้องกัน
Protection blockers              -> สิ่งที่ต้องตั้งค่า
Reduced sensor coverage          -> ความครอบคลุมของเซนเซอร์ลดลง
Runtime health                   -> สถานะการทำงาน
Sensor health                    -> สถานะเซนเซอร์
Last Telegram contact            -> ติดต่อ Telegram ล่าสุด
Active incident                  -> เหตุการณ์ที่กำลังดำเนินอยู่
Latest incident                  -> เหตุการณ์ล่าสุด
Latest delivery                  -> สถานะการส่งล่าสุด
```

**SettingsScreen ต้องเพิ่ม:**

* Card test tag: `remote_control_readiness`
* Row tags: `readiness_bot`, `readiness_pairing`, `readiness_authenticator`,
  `readiness_permissions`
* ข้อความแสดงตาม state:

  ```text
  Bot: เชื่อมต่อแล้ว / ยังไม่ได้เชื่อมต่อ
  เจ้าของ: จับคู่แล้ว / ยังไม่ได้จับคู่
  Authenticator: ตั้งค่าแล้ว / ยังไม่ได้ตั้งค่า
  สิทธิ์: พร้อมใช้งาน / ต้องตรวจสอบสิทธิ์
  ```

* primary action ต้องเป็น `ตั้งค่า Authenticator` ถ้า TOTP ขาด, เป็น
  `ตั้งค่า Telegram` ถ้า Bot/pairing ขาด, หรือ `ตรวจสอบสิทธิ์` ถ้า permission
  เป็นปัญหาสูงสุด

**tests ที่ต้องมี:**

- [ ] state `TOTP_NOT_CONFIGURED` แสดง card ชื่อ `ยังไม่ได้ตั้งค่า
      Authenticator`, body ว่า remote disarm ใช้ไม่ได้ และปุ่มไป Settings
- [ ] state `ALERT_ACTIVE` override `TOTP_NOT_CONFIGURED` เป็น card เหตุการณ์
      ไม่แสดงสอง card แข่งกัน
- [ ] Settings checklist แสดงสี่ rows ตาม state จริง, ไม่มี pairing code อยู่
      ใน row หรือ accessibility content description
- [ ] ทุกปุ่ม action มี min height 48dp และ content description ภาษาไทยที่
      ไม่เปิดเผย secret

### Task 5 — เชื่อม Telegram เข้ากับ catalog และ remote feedback

**ไฟล์:**

* Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClient.kt`
* Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramCommandHandler.kt`
* Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt`
* Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt`
* Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionModels.kt`
* Test: `MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClientAuthorizationTest.kt`
* Test: `MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClientTransportTest.kt`
* Test: `MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramCommandHandlerTest.kt`

**interface ที่ต้องเพิ่ม:**

```kotlin
data class RemoteGuidanceSummary(
    val code: GuidanceCode,
    val detail: GuidanceDetail,
    val updatedAtMs: Long,
)

fun ProtectionCoordinator.recordRemoteGuidance(summary: RemoteGuidanceSummary)
```

เพิ่ม `lastRemoteGuidance: RemoteGuidanceSummary?` ใน `ProtectionSnapshot`.
นี่เป็น read-only feedback summary; ห้ามใช้เปลี่ยน `ProtectionState`, ห้าม
persist secret และห้ามให้ remote failure ปลด/arm ระบบ.

เพิ่ม callback ที่ Telegram client รับจาก `SensorService`:

```kotlin
onRemoteGuidance: (RemoteGuidanceSummary) -> Unit = {}
```

`SensorService` ส่ง `graph.coordinator::recordRemoteGuidance` ให้ callback.
UI ใช้ `snapshot.lastRemoteGuidance` เพื่อแสดง popup เมื่อแอป foreground และ
persistent card เมื่อ code เป็น persistent. Client ที่ใช้ verify bot token
ใน `Navigation.kt` ไม่ได้เป็น command owner; ห้ามใส่ command handler หรือ
remote callback ให้ client นั้น.

**กฎป้องกัน popup ซ้ำ:** `ProtectionViewModel` ต้องเก็บค่า
`lastHandledRemoteGuidanceAtMs: Long?` ภายใน ViewModel (ไม่บันทึกลง storage).
เมื่อ snapshot ใหม่มี `lastRemoteGuidance.updatedAtMs` ที่มากกว่าค่าที่เคยจัดการ
จึงค่อย publish `ProtectionUiMessage` หนึ่งครั้ง; snapshot เดิม, recomposition
หรือการหมุนจอห้ามทำให้ Snackbar เด้งซ้ำ. ถ้า code มี `persistent=true` ให้
`persistentGuidance` ใช้ remote code นั้นตามกฎ priority ใน Task 4; ถ้าไม่
persistent ให้แสดงเฉพาะ popup ครั้งเดียว. ห้ามแสดง TOTP code, pairing code,
chat ID หรือ token ใน popup/card ไม่ว่ากรณีใด.

**mapping ใน `TelegramBotClient` ที่ห้ามตกหล่น:**

| Current branch | Publish `GuidanceCode` | Reply |
| --- | --- | --- |
| unpaired non-pair command | `UNAUTHORIZED_COMMAND` | catalog.telegramTh |
| pair accepted | `PAIRING_ACCEPTED` | catalog.telegramTh |
| pair wrong/expired | `PAIRING_INVALID_OR_EXPIRED` | catalog.telegramTh |
| no seed before disarm | `TOTP_NOT_CONFIGURED` | catalog.telegramTh |
| `/disarm` ไม่มี argument | `TOTP_REQUIRED` | catalog.telegramTh |
| verify success then handler applied | `COMMAND_DISARM_APPLIED` | catalog.telegramTh from handler result |
| verify returns invalid | `TOTP_INVALID` | catalog.telegramTh |
| verify returns locked | `TOTP_LOCKED` | catalog.telegramTh |
| `/arm`, `/status`, `/sensitivity`, `/help`, unknown | corresponding `COMMAND_*` code | catalog.telegramTh |

`sendTelegramMessageToApi` ต้องส่ง plain text JSON ต่อไป. ห้ามใส่
`parse_mode=Markdown`, ห้าม echo command body และห้าม log request/response
body, token, chat ID หรือ TOTP.

**tests ที่ต้องมี:**

- [ ] ทุก branch ใน table ตรวจ exact Thai Telegram text ที่ catalog ให้มา
- [ ] test valid `/disarm` ยืนยัน callback ได้ `COMMAND_DISARM_APPLIED` เพียง
      1 ครั้งและ handler ถูกเรียก 1 ครั้ง
- [ ] test invalid/locked/no seed ยืนยัน handler ไม่ถูกเรียก และ callback code
      ถูกต้อง
- [ ] source-contract scan `TelegramBotClient.kt` ต้อง fail ถ้าพบ
      `parse_mode`, `botToken.take(`, `getUpdates response body`,
      `sendMessage response:` หรือ log template ที่ interpolate token/chat/TOTP
- [ ] test unpaired user ใช้ `/status` แล้วไม่มี mutation ของ coordinator
      นอกจาก safe guidance record

### Task 6 — Sensor/Events copy และ anti-spam explanation

**ไฟล์:**

* Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/ui/events/EventsScreen.kt`
* Modify: `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt`
* Modify only if the mapping is absent:
  `MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt`
* Test: `MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/protection/IncidentUpdateDeliveryPolicyTest.kt`
* Test: `MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/protection/IncidentEngineTest.kt`
* Test: `MotorcycleAntiTheftSensor/app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt`

**exact copy:**

```text
Events                       -> เหตุการณ์
Loading events               -> กำลังโหลดเหตุการณ์…
No protection events         -> ยังไม่มีเหตุการณ์ด้านความปลอดภัย
Incidents will appear...     -> เหตุการณ์จะปรากฏที่นี่เมื่อระบบตรวจพบความผิดปกติ
Clear history                -> ล้างประวัติเหตุการณ์
Clear event history?         -> ล้างประวัติเหตุการณ์หรือไม่?
This permanently removes...  -> การดำเนินการนี้ลบประวัติเหตุการณ์ในเครื่องและย้อนกลับไม่ได้
Confirm clear / Cancel       -> ยืนยันการล้าง / ยกเลิก
Unable to load events        -> โหลดเหตุการณ์ไม่สำเร็จ
Retry                        -> ลองใหม่
Source: REAL                 -> แหล่งที่มา: เหตุการณ์จริง
Severity / Lifecycle         -> ระดับความรุนแรง / สถานะเหตุการณ์
Evidence / Time / Delivery   -> หลักฐาน / เวลา / สถานะการส่ง
```

ห้ามสร้าง event row ปลอมหรือเพิ่ม Telegram send เพื่ออธิบาย
`IncidentUpdate.Updated` เพราะ update ปัจจุบันไม่ใช่ record ในประวัติเหตุการณ์.
ให้ `EventsScreen` แสดง caption นี้เหนือรายการ **เฉพาะเมื่อ**
`lastIncident.lifecycle == OPEN`:

> `เหตุการณ์เดิมกำลังบันทึกหลักฐานเพิ่ม — ยังไม่ส่งข้อความซ้ำ`

เมื่อ incident ปิด, ไม่มี incident หรือกำลังโหลด history ต้องไม่แสดง caption.
แถวเหตุการณ์ที่เก็บจริงให้ derive Thai lifecycle/delivery copy จาก state ที่มี
อยู่; ห้ามแสดง raw enum name, raw exception หรือ detail ที่เป็นความลับ.

ห้ามสร้าง Telegram send ใหม่สำหรับ `Updated`; policy ปัจจุบันต้องคง
`PERSIST_ONLY`. Tests ต้องพิสูจน์ Opened=SEND, Updated=PERSIST_ONLY,
Escalated=SEND, Closed=SEND_CLOSE_SUMMARY.

เพิ่ม Compose test อีก 2 กรณี: incident ที่ `OPEN` ต้องเห็น caption ข้างต้น
เพียงหนึ่งครั้ง; incident ที่ `CLOSED` ต้องไม่เห็น caption. Test นี้ต้องไม่
สร้าง incident ปลอมใน production code.

### Task 7 — Full verification และ acceptance gate

- [ ] รัน focused unit tests ของ Tasks 1–6 หลังแต่ละ task
- [ ] รัน full unit suite, assemble และ instrumented suite แบบไม่ซ้อน Gradle/ADB:

  ```powershell
  $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
  .\gradlew.bat testDebugUnitTest --no-daemon --max-workers=1
  .\gradlew.bat connectedDebugAndroidTest --no-daemon --max-workers=1
  .\gradlew.bat assembleDebug --no-daemon --max-workers=1
  ```

- [ ] ห้าม report ว่าผ่านถ้า command ใด command หนึ่งยังไม่ได้ run หรือถูก
      skip; ระบุ blocker ตามจริง
- [ ] install APK ที่เพิ่ง assemble บน `JUCDU18811013149`, แล้วตรวจก่อนและหลัง
      test ว่า `SEND_SMS: deny`
- [ ] ทำ manual matrix ในส่วน Device acceptance ของสเปกนี้ครบทุกแถว
- [ ] ปิดงานได้หลัง scoped review QR checkpoint `3ace729..3322c36`, full gate,
      two-phone scan และ `/disarm` acceptance ผ่านตาม
      `TASK-TOTP-002-telegram...md` เท่านั้น

## แผนทดสอบ

### Unit tests

1. `GuidanceCatalogTest`: ทุก `GuidanceCode` มี title/body ภาษาไทย, Telegram
   text เมื่อจำเป็น, severity, persistence และ action ถูกต้อง.
2. Source-contract test: catalog/formatter และ error path ไม่มี token, seed,
   URI, QR, pairing code หรือ input TOTP ถูก interpolate ในข้อความหรือ log.
3. `RemoteCommand`/`TelegramBotClient` tests:
   `/start`, `/help`, `/status`, `/pair`, `/arm`, `/disarm`, `/sensitivity` และ
   unknown command ได้ข้อความไทยตาม catalog; ตรวจ success, unauthorized,
   missing TOTP, missing code, invalid code, lockout และ command rejection.
4. TOTP tests: setup success/invalid/cancel/replacement; lockout ยังปฏิเสธ
   remote disarm และ `TOTP_LOCKED` ไม่มี retry action. แก้ test suite ที่ล้ม
   จาก `android.util.Log` ก่อน claim ว่าทั้ง suite ผ่าน และลบ logging ที่พิมพ์
   input/expected code/seed.
5. Protection/incident tests: Opened ส่ง alert, Updated ไม่ส่งซ้ำ, Escalated
   ส่ง alert, quiet 30 วินาทีปิด incident และส่ง close summary.
6. Permission/readiness tests: ทุก blocker/degradation maps เป็นข้อความไทย
   ที่บอก next action โดยไม่กล่าวเกินความจริง.

### ViewModel และ Compose tests

1. Action ในแอป emit in-progress → final popup ตามลำดับ และ persistent
   guidance หายเมื่อ underlying state แก้สำเร็จ.
2. Protection screen แสดง card ที่มีลำดับความสำคัญสูงสุดเพียงใบเดียว,
   icon/text/action ถูกต้อง และ touch target อย่างน้อย 48dp.
3. Settings checklist แสดง Bot/pairing/TOTP/permission ตาม summary สด;
   success TOTP refresh แล้วแสดง `Authenticator ตั้งค่าแล้ว`.
4. Authenticator dialog มีข้อความขั้นที่ 1/2, ไม่มี secret ใน semantics,
   cleanup QR/candidate เมื่อ cancel และ `FLAG_SECURE` คงทำงาน.
5. Events screen แสดง `INCIDENT_UPDATED` เป็นการบันทึกหลักฐานเพิ่ม ไม่ใช่
   “ส่งแจ้งเตือนสำเร็จ” ซ้ำ.
6. ทดสอบ TalkBack semantics ว่าข้อความ error/action อ่านได้ แต่ไม่มีข้อมูลลับ.

### Device acceptance: Huawei `JUCDU18811013149`

ทำบนรถที่อยู่ในสภาพปลอดภัยและใช้ bot/credentials จริงเฉพาะบนอุปกรณ์:

1. ตรวจ `SEND_SMS: deny` ก่อนเริ่มและหลังจบ; ห้ามส่ง SMS/โทรฉุกเฉินระหว่าง test.
2. เปิด app ใหม่และตรวจ status card ของ Setup required, Disarmed, Arming,
   Armed healthy/degraded, Alert active และ Offline เท่าที่สร้างได้อย่างปลอดภัย.
3. ทดสอบ setup Bot → pairing → two-phone QR scan → local TOTP verification
   โดยไม่ถ่ายภาพ/ดึง UI dump ของ QR, secret หรือ code.
4. ทดสอบ Telegram command matrix ทุกคำสั่งและทุก error ที่ระบุใน unit test;
   บันทึกเฉพาะ pass/fail และประเภทข้อความ ไม่บันทึกรหัสจริง.
5. ทดสอบ lockout: ทำใน environment ที่ปลอดภัยด้วยรหัสทดสอบ, ยืนยัน
   `TOTP_LOCKED`, หยุดส่งคำสั่ง, รออย่างน้อย 5 นาที แล้วใช้ code ใหม่ที่ถูกต้อง.
6. ทดสอบ motion: เปิด protection, ทำให้เกิด alert หนึ่งครั้ง, ขยับต่อเนื่อง
   เพื่อยืนยันว่าไม่ spam, วางนิ่งเกิน 30 วินาทีเพื่อรับ close summary, แล้ว
   ทำให้เกิดเหตุการณ์ใหม่เพื่อตรวจ alert รอบถัดไป.
7. ทดสอบแอป foreground/background และ service restart: UI/Telegram ต้อง
   สื่อสถานะถูกต้อง ไม่กล่าวว่าส่งสำเร็จหากไม่มีหลักฐาน transport.

## เกณฑ์รับงาน

- [ ] ทุกปัญหาที่ทำให้ protection หรือ remote command ใช้งานไม่ได้มี
      persistent Thai guidance พร้อม next action ที่ปลอดภัย
- [ ] ทุก operation ที่ผู้ใช้กดในแอปมี in-progress และ final feedback
- [ ] Telegram ทุกคำสั่งตอบภาษาไทยสม่ำเสมอและไม่รั่วข้อมูลลับ
- [ ] Events อธิบาย anti-spam incident behavior ได้ถูกต้อง
- [ ] Status card/checklist/Telegram ใช้ underlying state เดียวกันและไม่ขัดกัน
- [ ] ไม่มี popup flood จาก sensor samples หรือ operation ซ้ำ
- [ ] full unit suite, instrumented suite, assembleDebug และ device acceptance
      ผ่านหลังแก้; report จำนวน test และกรณีที่ทดสอบไม่ได้ตามจริง
- [ ] `CKP-20260811-2357-YD5EKY` ไม่ถูกอ้างว่าปิดจนกว่าจะผ่าน scoped review,
      full gate และ two-phone QR acceptance ตาม checkpoint

## สิ่งที่ agent ต้องรายงานกลับ

* รายการ `GuidanceCode` ที่ implement พร้อม screen/Telegram mapping
* ไฟล์ที่แก้และเหตุผลว่าทำไมไม่กระทบ security ownership
* ผล test แยก unit, Compose/instrumented, build และ device matrix
* หลักฐานว่าไม่มี secret ใน UI semantics/log/test output
* สิ่งที่ยังทดสอบบนเครื่องจริงไม่ได้และความเสี่ยงที่เหลือ
