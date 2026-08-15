# Telegram `/status` Full Health Report Design

**วันที่:** 2026-08-15

**สถานะ:** อนุมัติการออกแบบแล้ว

**ขอบเขต:** MotorcycleAntiTheftSensor — คำสั่ง Telegram `/status` และการรายงานสุขภาพระบบที่คำสั่งนี้ใช้เท่านั้น

## 1. เป้าหมาย

เปลี่ยน `/status` จากข้อความสั้นที่อาจอ้างสถานะเซนเซอร์เก่า ให้เป็นรายงานสุขภาพระบบฉบับเต็มทุกครั้ง โดยผู้ใช้ต้องอ่านแล้วตอบได้ทันทีว่า:

1. รถอยู่ในสถานะ Arm, Disarm, Arming, Alert หรือ Offline
2. Service และ Telegram ยังทำงานหรือไม่
3. เซนเซอร์ทั้ง 5 กลุ่มพร้อมและกำลังทำงานครบหรือไม่
4. GPS กำลังติดตามจริงหรือเพียงมีพิกัดเก่าค้างอยู่
5. ไมโครโฟนกำลังฟังและโมเดลจำแนกเสียงพร้อมหรือไม่
6. แบตเตอรี่ อุณหภูมิที่ Android รายงาน และแหล่งจ่ายไฟเป็นอย่างไร
7. เหตุการณ์และการส่งแจ้งเตือนล่าสุดสำเร็จหรือไม่
8. หากมีปัญหา ผู้ใช้ควรแก้ไขอย่างไร

คำสั่งต้องตอบเร็วจากสถานะกลางที่ระบบอัปเดตต่อเนื่อง ห้ามเริ่ม หยุด หรืออ่านฮาร์ดแวร์แบบ blocking เพียงเพราะผู้ใช้ส่ง `/status`

## 2. ปัญหาปัจจุบันที่ต้องแก้

`ProtectionStatusFormatter` อ่าน `ProtectionCoordinator.snapshot.value` โดยตรง แต่สถานะ Location ใน Snapshot อาจถูกบันทึกก่อน `LivePursuitCoordinator` เริ่มติดตาม GPS และไม่ถูกซิงก์กลับเมื่อมีพิกัดใหม่ ผลคือ `/status` แสดง `LOCATION: stopped` ทั้งที่ข้อความแจ้งเหตุเพิ่งส่งพิกัดอายุประมาณ 6 วินาทีและความแม่นยำประมาณ 19 เมตรได้

สาเหตุเชิงแบบแผนคือระบบใช้ข้อความ diagnostic เช่น `"stopped"` หรือข้อความที่ขึ้นต้นด้วย `"fix "` เป็นตัวแทนสถานะจริง ข้อความเหล่านี้เหมาะสำหรับ log แต่ไม่ควรเป็น source of truth หรือถูก Formatter แกะความหมาย

การออกแบบใหม่นี้ต้องแก้ทั้งความถูกต้องของข้อมูลต้นทางและการแสดงผล ห้ามแก้เฉพาะการซ่อนบรรทัด `LOCATION: stopped`

## 3. การตัดสินใจหลัก

เลือกแนวทาง **Snapshot-first, continuously updated**:

```text
Sensor/runtime callback
    -> typed health update
    -> ProtectionCoordinator authoritative snapshot
    -> status projection using one snapshot revision
    -> pure Thai formatter
    -> Telegram reply
```

ไม่เลือกแนวทางต่อไปนี้:

- ไม่อ่านเซนเซอร์ใหม่ตามคำสั่ง เพราะ GPS อาจใช้เวลานาน ไมค์อาจชนกับ listener ที่กำลังทำงาน และ network thread อาจค้าง
- ไม่ใช้ hybrid refresh ตอนกดคำสั่ง เพราะเวลาตอบและข้อมูลที่ได้จะไม่แน่นอน
- ไม่ให้ Formatter ติดต่อ runtime, Android API, SharedPreferences หรือ network
- ไม่ให้ Formatter วิเคราะห์ diagnostic string เพื่อเดาสถานะ

## 4. Source of truth และขอบเขตองค์ประกอบ

### 4.1 Sensor/runtime producers

ผู้ผลิตข้อมูลแต่ละตัวรายงานสถานะแบบมีชนิดชัดเจนเมื่อ lifecycle หรือค่าที่เกี่ยวข้องเปลี่ยน:

- Vibration: การลงทะเบียน listener และตัวอย่างล่าสุด
- Light: การรองรับฮาร์ดแวร์ การลงทะเบียน listener และค่า lux ล่าสุด
- Power/Thermal: แบตเตอรี่ อุณหภูมิแบตเตอรี่ สถานะเสียบชาร์จ และความพร้อมของ Android battery source
- Microphone: permission, runtime state, model readiness และเวลาตัวอย่างเสียงล่าสุดจาก `AudioTelemetry`
- Location: tracking mode, registration state, failure reason, พิกัดล่าสุด อายุพิกัด และความแม่นยำ

Producer ห้ามส่งข้อความภาษาไทยสำเร็จรูปเข้า Snapshot และห้ามให้ข้อความ diagnostic เป็นตัวกำหนด state

### 4.2 ProtectionCoordinator

`ProtectionCoordinator` ยังคงเป็น source of truth ของสถานะการป้องกันและ Snapshot ที่ `/status` ใช้ ต้องรับ health update แบบ atomic และเพิ่ม revision ตามรูปแบบเดิม

Snapshot สำหรับรายงานต้องมีข้อมูลที่มีชนิดชัดเจนอย่างน้อย:

- protection state
- protection activation timestamp แยกจาก last transition timestamp
- sensitivity level ปัจจุบัน
- service running และ last successful heartbeat
- Telegram polling, reachability และ last successful contact
- sensor health ของเซนเซอร์ 5 กลุ่ม
- battery level
- battery temperature ที่ Android รายงาน
- charging state
- degradation reasons และ permission blockers
- last incident summary
- last delivery state

`protectionActivatedAtMs` ต้องไม่ถูกรีเซ็ตเพียงเพราะ ARMED เปลี่ยนเป็น ALERT แล้วกลับเป็น ARMED เพื่อให้ “ทำงานมาแล้ว” หมายถึงระยะเวลาตั้งแต่ Arm รอบปัจจุบันจริง

### 4.3 Typed sensor detail

คง `SensorHealthState` เดิมเมื่อเหมาะสม แต่เพิ่มรายละเอียดแบบ typed แทนการ parse `detail` เช่น:

- Location: tracking mode, registration result, last fix timestamp, accuracy และ failure reason
- Microphone: `AudioRuntimeState`, modelReady และ last audio sample timestamp
- Power/Thermal: charging state และ last Android battery update

ช่อง `detail` เดิมอาจคงไว้สำหรับ log/diagnostic compatibility แต่ห้ามใช้ตัดสิน state หรือสร้างข้อความผู้ใช้

### 4.4 Status projection

สร้าง projection จาก Snapshot revision เดียว โดยคำนวณ:

- ระยะเวลาที่ Arm
- อายุ heartbeat/Telegram contact/sample/fix
- จำนวนเซนเซอร์ที่พร้อม
- จำนวนเซนเซอร์ที่กำลังตรวจจับ
- รายการ warning/error พร้อม user action
- ข้อมูลเหตุการณ์ล่าสุด

Projection ใช้ clock ที่ inject ได้เพื่อให้ Unit Test ควบคุมเวลาได้ และต้องไม่มี side effect

### 4.5 ProtectionStatusFormatter

Formatter รับ projection ที่สมบูรณ์แล้วและทำหน้าที่แสดงภาษาไทยเท่านั้น ต้องเป็น pure function, deterministic, null-safe และไม่เข้าถึง Android API หรือ runtime

### 4.6 TelegramCommandHandler

`RemoteCommand.Status` ต้อง:

1. อ่าน Snapshot revision เดียว
2. สร้าง projection
3. format ข้อความ
4. reply หนึ่งครั้ง

ห้ามเรียก `start`, `stop`, `currentLocationObservation`, ขอพิกัด, เปิด AudioRecord หรือรอ callback ใด ๆ

## 5. นิยามสถานะเซนเซอร์

สถานะผู้ใช้ต้องแยกความหมายต่อไปนี้:

- **กำลังเริ่ม:** runtime เริ่มทำงานแต่ยังไม่พร้อมอ่านค่า
- **พร้อม/รอข้อมูล:** ลงทะเบียนสำเร็จแล้ว แต่ยังไม่มี sample/fix แรก
- **ทำงาน:** มีข้อมูลใหม่ตามเกณฑ์ของเซนเซอร์
- **ข้อมูลเก่า:** เคยมีข้อมูล แต่เกิน freshness threshold
- **ไม่พร้อม:** ไม่มีฮาร์ดแวร์, ไม่มี permission, provider ปิด หรือ registration ถูกปฏิเสธ
- **ผิดพลาด:** runtime เริ่มแล้วล้มเหลวหรือหยุดเพราะ error
- **หยุดตามคำสั่ง:** Protection อยู่ใน Disarm/Setup และ sensor ถูกหยุดโดยตั้งใจ

คำว่า `stopped` จะแสดงเป็นปัญหาได้ต่อเมื่อ Protection ควรทำงานอยู่ แต่ tracking/listener หยุดจริง หาก Disarm อยู่ต้องแสดงว่าเป็นการหยุดตามคำสั่ง ไม่ใช่ระบบเสีย

## 6. กติกาความสด

ห้ามใช้ threshold 5 วินาทีร่วมกันทุกเซนเซอร์:

| แหล่งข้อมูล | เกณฑ์ | เหตุผล |
|---|---:|---|
| Vibration sample | 5 วินาที | accelerometer ควรมีข้อมูลต่อเนื่องเมื่อ Arm |
| Light sample | 5 วินาที | light listener ควรมีข้อมูลต่อเนื่องเมื่อมีฮาร์ดแวร์ |
| Microphone sample | 5 วินาที | pipeline ถือว่าไม่มีข้อมูลเสียงเกิน 5 วินาทีเป็น stale อยู่แล้ว |
| GPS fix | 30 วินาที | สอดคล้องกับ usable-fix policy และ armed interval 10 วินาที |
| Service heartbeat | 10 วินาที | heartbeat ปัจจุบันทุก 5 วินาที |
| Telegram contact | 20 วินาที | สอดคล้องกับ health policy ปัจจุบัน |
| Power/Thermal | event-driven | Android battery broadcast ไม่ได้ส่งทุก 5 วินาที จึงห้ามตัดเป็น stale ด้วย sensor threshold ทั่วไป |

อายุข้อมูลติดลบจาก clock anomaly ต้องถือว่าผิดปกติและห้ามแสดงเป็นข้อมูลใหม่

## 7. กติกานับเซนเซอร์

เซนเซอร์ทั้งหมดมี 5 กลุ่ม:

1. การสั่น
2. แสง
3. ไมโครโฟน
4. GPS
5. พลังงาน/อุณหภูมิ

เมื่อ Protection อยู่ใน ARMED หรือ ALERT:

- แสดง `เซนเซอร์กำลังตรวจจับ X/5`
- นับ `ทำงาน` และ `พร้อม/รอข้อมูล` เป็นพร้อมสำหรับ runtime
- ใช้ `⏳` กับตัวที่ลงทะเบียนสำเร็จแต่ยังรอ sample/fix แรก
- ไม่นับ stale, unavailable, failed หรือ stopped

เมื่อ Protection อยู่ใน DISARMED หรือ SETUP:

- ห้ามรายงาน `0/5` เป็นความเสียหาย
- แสดง `เซนเซอร์หยุดตามคำสั่ง` และ `พร้อมใช้งาน X/5`
- คำว่า `พร้อมใช้งาน` ในสถานะนี้หมายถึงมีฮาร์ดแวร์และ permission ที่จำเป็น โดยตรวจ readiness โดยไม่เริ่ม listener

เมื่ออยู่ใน ARMING:

- แสดงสถานะของแต่ละตัวตามจริง เช่น กำลังเริ่ม คาลิเบรต หรือรอข้อมูล
- ห้ามสรุปว่าทำงานครบก่อนครบเงื่อนไข

ฮาร์ดแวร์เสริมที่เครื่องไม่มี เช่น Light Sensor ให้แสดง `เครื่องนี้ไม่รองรับ` ไม่เป็น critical failure แต่จำนวนยังต้องสะท้อนตามจริงและห้ามแสดง `ระบบทำงานครบ 5/5`

## 8. สัญญาการแสดงผล

`/status` ส่งรายงานฉบับเต็มทุกครั้ง ไม่มี `/status full` และไม่มีคำสั่งใหม่

ลำดับส่วนต้องคงที่:

1. สถานะการป้องกันและระยะเวลาที่ Arm
2. Service และ Telegram
3. สรุปเซนเซอร์
4. รายละเอียดเซนเซอร์ทั้ง 5 กลุ่ม
5. แบตเตอรี่ อุณหภูมิ และแหล่งจ่ายไฟ
6. เหตุการณ์และการส่งล่าสุด
7. สรุปปัญหาและคำแนะนำ

ตัวอย่างกรณีทำงานครบ:

```text
🛡️ สถานะระบบป้องกัน

สถานะรถ: กำลังป้องกัน
ทำงานมาแล้ว: 24 นาที
ระดับความไว: 8/10

📡 ระบบหลัก
✅ Service: ทำงาน
✅ Telegram: เชื่อมต่อ
ติดต่อล่าสุด: 3 วินาทีที่แล้ว

🔎 เซนเซอร์กำลังตรวจจับ: 5/5
✅ การสั่น: ทำงาน | ล่าสุด 1 วินาที
✅ แสง: ทำงาน | 126 lux | ล่าสุด 2 วินาที
✅ ไมโครโฟน: กำลังฟัง | ตัวจำแนกเสียงพร้อม
✅ GPS: กำลังติดตาม | ล่าสุด 6 วินาที | ±19 เมตร
✅ พลังงาน/อุณหภูมิ: ทำงาน

🔋 แบตเตอรี่: 100%
🌡️ อุณหภูมิเครื่อง (แบตเตอรี่): 32.0°C
🔌 แหล่งจ่ายไฟ: กำลังชาร์จ

🚨 เหตุการณ์ล่าสุด
ประเภท: ตรวจพบการสั่น
เวลา: 20:22
สถานะ: เหตุการณ์สิ้นสุดแล้ว
📤 Telegram: ส่งสำเร็จ

✅ ระบบทำงานครบ ไม่พบปัญหา
```

ตัวอย่างเมื่อ GPS รอ fix แรก:

```text
⏳ GPS: กำลังติดตาม | รอพิกัดแรก
```

ตัวอย่างเมื่อข้อมูล GPS เก่า:

```text
⚠️ GPS: พิกัดล่าสุด 47 วินาทีที่แล้ว | ข้อมูลเก่า
วิธีแก้: ตรวจว่าเปิดตำแหน่งและวางโทรศัพท์ในจุดรับสัญญาณได้
```

ตัวอย่างเมื่อไมค์ไม่มี permission:

```text
❌ ไมโครโฟน: ไม่มีสิทธิ์ใช้งาน
วิธีแก้: เปิดสิทธิ์ Microphone ในการตั้งค่าแอป แล้ว Arm ใหม่
```

ตัวอย่างขณะ Disarm:

```text
🔎 เซนเซอร์: หยุดตามคำสั่ง Disarm | พร้อมใช้งาน 5/5
```

## 9. การแปลสถานะและความรุนแรง

- `✅` ใช้เฉพาะข้อมูลที่ยืนยันว่าพร้อมหรือทำงานตาม state ปัจจุบัน
- `⏳` ใช้กับ startup/calibration/waiting-for-first-data ที่ยังไม่ถือเป็น failure
- `⚠️` ใช้กับ degraded, stale, optional hardware absent หรือปัญหาที่ระบบยังทำงานต่อได้
- `❌` ใช้กับ permission blocker, required runtime failure, provider ปิด หรือ sensor หยุดผิด lifecycle

ข้อความต้องใช้ภาษาไทยที่ผู้ใช้แก้ปัญหาได้ ห้ามแสดง raw exception, stack trace หรือ diagnostic code โดยไม่มีคำแปล

หากบางข้อมูลเป็น null ให้แสดง `ยังไม่มีข้อมูล` และรายงานส่วนอื่นต่อ ห้ามให้ Formatter ล้มทั้งข้อความ

## 10. ความเป็นส่วนตัวและความปลอดภัย

รายงาน `/status` ห้ามมี:

- bot token
- Telegram Chat ID
- pairing code
- stack trace
- filesystem path
- พิกัด latitude/longitude เต็ม
- Google Maps URL
- raw audio หรือไฟล์เสียง

GPS ใน `/status` แสดงเฉพาะ tracking state, fix age และ accuracy โดยพิกัด/แผนที่ยังอยู่ในข้อความเหตุการณ์ตาม flow เดิม

การตรวจ owner authorization ของ `/status` ต้องคงเดิมและห้ามอ่อนลง

## 11. การจัดการข้อผิดพลาด

- Status projection ต้องรองรับ Snapshot ที่ข้อมูลไม่ครบและสร้างรายงานได้เสมอ
- หาก sensor producer ล้มเหลว ให้บันทึก typed failure reason และรักษาส่วนอื่นของ Snapshot
- หาก Formatter เกิดข้อผิดพลาดที่ไม่คาดคิด Command Handler ต้องตอบ fallback ภาษาไทยแบบสั้นว่าดึงรายงานเต็มไม่สำเร็จ โดยไม่ค้างและไม่เปิดเผยรายละเอียดภายใน
- การสร้างรายงานห้ามรอ network, GPS callback, audio callback หรือ Android broadcast
- คำสั่งต้อง reply เพียงหนึ่งครั้งต่อ `/status`
- ห้ามการอัปเดต sensor health ทำให้ protection state ย้อนกลับหรือสั่ง Arm/Disarm โดยตรง

## 12. ขอบเขตที่อนุญาต

- ปรับ protection/status model เท่าที่จำเป็นโดยรักษา API เดิมเมื่อทำได้
- เพิ่ม typed GPS, microphone และ power health details
- เชื่อม GPS lifecycle/fix และ `AudioTelemetry` เข้าสถานะกลาง
- แยก freshness policy ตามชนิดเซนเซอร์
- ปรับ `ProtectionStatusFormatter` และ status projection
- ปรับ wiring ของ `TelegramCommandHandler` เท่าที่จำเป็นเพื่อรับ projection โดยไม่แตะ authorization
- เพิ่มข้อความภาษาไทยและคำแนะนำ
- เพิ่ม Unit Test, integration test ที่เหมาะสม และ real-device acceptance evidence

งานมีแนวโน้มกระทบมากกว่าห้าไฟล์ เนื่องจาก producer, model, coordinator, formatter และ tests อยู่คนละขอบเขต แต่ทุกไฟล์ต้องเชื่อมโดยตรงกับ `/status` truth path เท่านั้น

## 13. สิ่งที่อยู่นอกขอบเขต

- กติกา Sensor Fusion
- threshold การตรวจขโมยและระดับการแจ้งเตือน
- YAMNet model, audio class หรือ audio correlation
- Arm/Disarm behavior และ arming delay
- Telegram owner authentication/authorization
- Telegram Live Location transport, pursuit duration หรือ movement policy
- SMS และ emergency call
- UI ที่ไม่ใช้สถานะเดียวกันโดยตรง
- dependency ใหม่
- การแก้ไขพลังงาน/แบตเตอรี่เพื่อประหยัดไฟ
- command ใหม่ เช่น `/status full`
- refactor โครงสร้างโปรเจกต์หรือ rename public API ที่ไม่จำเป็น

## 14. การทดสอบอัตโนมัติ

ต้องเขียน test ก่อน production change และครอบคลุมอย่างน้อย:

1. รายงาน healthy แบบเต็มและเซนเซอร์ 5/5
2. ลำดับ section คงที่
3. GPS tracking และ waiting for first fix
4. GPS fix ใหม่พร้อม age/accuracy
5. GPS fix เกิน 30 วินาทีเป็น stale
6. GPS `stopped` เป็นปัญหาเฉพาะเมื่อ ARMED/ALERT ควรติดตาม
7. Disarm แสดง stopped-by-command โดยไม่สร้าง false alarm
8. ALERT_ACTIVE ยังคง GPS tracking
9. Microphone listening/model ready
10. Microphone calibrating และ waiting state
11. Microphone permission missing พร้อมวิธีแก้ภาษาไทย
12. Microphone stale หลังไม่มี sample เกิน 5 วินาที
13. Light hardware unavailable
14. Vibration/Light freshness boundary ที่ 5 วินาที
15. Battery percentage และ battery temperature ไม่สลับกัน
16. Charging state ถูกต้อง
17. Power/Thermal ไม่กลายเป็น stale เพียงเพราะไม่มี broadcast ภายใน 5 วินาที
18. Service heartbeat 10 วินาทีและ Telegram contact 20 วินาที
19. Arm duration ไม่รีเซ็ตเมื่อเข้า/ออก ALERT
20. Incident lifecycle และ delivery state mapping
21. ค่า null/missing data ไม่ทำให้ Formatter throw
22. นับจำนวน ready/active sensors ถูกต้องทุก protection state
23. ไม่มี token, Chat ID, full coordinate, map URL หรือ raw diagnostic รั่ว
24. `/status` ไม่เรียก start/stop/read sensor และไม่รอ callback
25. หนึ่งคำสั่งตอบหนึ่งข้อความ
26. Projection ใช้ Snapshot revision เดียว ไม่มีข้อความผสมจากคนละ state

Focused tests ต้องผ่านก่อน full `testDebugUnitTest` และ `assembleDebug` ตามลำดับ โดยใช้ JBR และ low-memory Gradle settings ของโปรเจกต์เมื่อจำเป็น

## 15. การยืนยันบนอุปกรณ์จริง

Automated tests และ APK build ไม่ถือเป็นหลักฐานว่า runtime บนโทรศัพท์ถูกต้อง ต้องติดตั้ง APK ใหม่และเก็บ Telegram/UI evidence อย่างน้อย:

1. Disarm: sensor stopped-by-command และ readiness จากฮาร์ดแวร์/permission ถูกต้อง
2. Arm ผ่าน calibration: รายงานครบทุก section
3. GPS เปิดแต่รอ fix แรก
4. GPS ได้ fix ใหม่และแสดง age/accuracy ตรงกับ log/device evidence
5. ปิด Location แล้วแสดงคำแนะนำ
6. เปิด Location กลับและ Arm ใหม่แล้ว recovery ถูกต้อง
7. ปิด Microphone permission แล้วแสดงคำแนะนำ
8. เปิด permission กลับและ Arm ใหม่แล้วขึ้น listening/model ready
9. สร้างเหตุการณ์ทดลองแล้ว last incident/delivery ตรงกับข้อความแจ้งเตือน
10. ALERT_ACTIVE ยังคง GPS tracking
11. ส่ง `/status` ซ้ำหลายครั้งโดยไม่มี ANR, listener ซ้ำ, false incident หรือ sensor restart
12. เปรียบเทียบ Telegram กับ UI/notification ณ เวลาเดียวกัน

## 16. เกณฑ์ยอมรับและจบงาน

งานถือว่าจบเมื่อครบทุกข้อ:

- `/status` ส่งรายงานเต็มทุกครั้งและตอบหนึ่งข้อความ
- รายงานมาจาก Snapshot/status projection ไม่อ่านฮาร์ดแวร์ตามคำสั่ง
- GPS ไม่แสดง `stopped` ค้างเมื่อ tracking ทำงานและมี fix
- ไมค์แสดง lifecycle และ model readiness ตาม `AudioTelemetry` จริง
- จำนวนเซนเซอร์และ freshness ถูกต้องตามชนิดและ protection state
- แบตเตอรี่ อุณหภูมิ และ charging state ถูกต้องและไม่สลับความหมาย
- missing/stale/failure มีภาษาไทยและวิธีแก้ที่เหมาะสม
- ไม่เปลี่ยน Sensor Fusion, alert thresholds, Arm/Disarm, authorization หรือ Live Location
- focused tests, full unit tests และ assemble ผ่าน
- real-device matrix มีหลักฐานครบและตรงกับ Telegram/UI/notification
- ไม่มี secret, พิกัดเต็ม หรือข้อมูลภายในรั่วในรายงาน
