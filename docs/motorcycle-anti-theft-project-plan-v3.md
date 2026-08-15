# แผนพัฒนาโปรเจค: ระบบกันขโมยมอเตอร์ไซค์ด้วยมือถือเก่า (Vibration & Multi-Sensor Sensor + Telegram Bot)

**เวอร์ชัน:** 2.4  
**วันที่:** 6 สิงหาคม 2026  
**สถานะ:** แผนพัฒนาฉบับสมบูรณ์ (อัปเดตปิดช่องโหว่ความปลอดภัย + Multi-Sensor Fusion + Offline Fallback + Heartbeat Monitor)  
**ผู้พัฒนา:** ทีม DIY (ช่าง + นักพัฒนาแอป)

---

## 1. ภาพรวมโปรเจค

### 1.1 ปัญหาที่ต้องการแก้
- ปัญหาขโมยกล่อง ECU ของมอเตอร์ไซค์กำลังระบาดหนักในประเทศไทย (โดยเฉพาะพื้นที่ดอนเมืองและปริมณฑล)
- ระบบกันขโมยโรงงาน (Immobilizer / Smart Key) ไม่สามารถป้องกันการถอดกล่อง ECU ได้
- ระบบ GPS Tracker ทั่วไปมีราคาสูงและอาจถูกตัดสัญญาณ

### 1.2 แนวทางแก้ไข
ใช้ **มือถือเก่า** ที่ใส่ซิมเทพ (ซิมรายปี) เป็นเซ็นเซอร์สั่นสะเทือนและเซ็นเซอร์ตรวจจับมัลติฟังก์ชัน (**Multi-Sensor Engine**)  
เมื่อตรวจพบการสั่นสะเทือน/การเปิดเบาะ/การถอดสายชาร์จผิดปกติ → ส่งแจ้งเตือนไปยังมือถือหลักของผู้ใช้ทันทีผ่าน Telegram Bot / SMS / Direct Call

---

## 2. เป้าหมายของโปรเจค

### เป้าหมายหลัก
1. ตรวจจับการสั่นสะเทือนจากการถอดชิ้นส่วน (กล่อง ECU), การเปิดเบาะรถ, หรือการเข็น/ยกตัวรถ
2. ส่งแจ้งเตือนไปยังมือถือหลักภายใน 1-3 วินาที ( Telegram / SMS Fallback )
3. ใช้งานได้ต่อเนื่อง 24 ชั่วโมง โดยมีระบบตัดไฟป้องกันแบตเตอรี่ร้อนเกิน 45°C (Thermal Safety)
4. มีระบบ Heartbeat Monitor ตรวจสอบสถานะการเชื่อมต่อเน็ตและแอปทุก 15-30 นาที

---

## 3. สถาปัตยกรรมระบบ (System Architecture)

```
[มือถือเก่า - Sensor Device (ใต้เบาะรถ)]
    │
    ├── Accelerometer + Gyroscope (ตรวจจับการสั่น / เอียงรถ)
    ├── Light Sensor (ตรวจจับแสงเมื่อเปิดเบาะ/ถอดกล่อง ECU)
    ├── Power Disconnect Sensor (ตรวจจับสายชาร์จหลุด)
    ├── Thermal Sensor (ตรวจจับความร้อนแบตเตอรี่)
    └── ซิมเทพ (Internet + SMS + Direct Call)
    │
    ▼
[แอป Sensor App - Background Foreground Service] ──► Internet / Cellular Network
    │
    ├──► Telegram Bot API (รูปภาพ, เสียง, GPS, Telegram Alert)
    ├──► Offline Fallback (SMS Alert + Direct Phone Call เมื่อไร้เน็ต)
    └──► Heartbeat Monitor (ยิง Silent Ping ตรวจเช็กระบบล่ม)
    │
    ▼
[มือถือหลัก - Owner Phone]
    │
    └──► Telegram Notification / SMS Receiver / Incoming Call Notification
```

---

## 4. การออกแบบแอปพลิเคชันฝั่ง Sensor (Sensor App)

### 4.1 ฟีเจอร์หลัก & Multi-Sensor Fusion Engine

#### 4.1.1 Multi-Sensor Fusion
- **Accelerometer + Gyroscope:** ตรวจจับแรงสั่นและการเอียงตัวรถ (Tilt Angle > 15°)
- **Light Sensor (`Sensor.TYPE_LIGHT`):** ตรวจจับแสงสว่างใต้เบาะ (ถ้าคนร้ายเปิดเบาะหรือเปิดกล่อง ECU ค่า Lux > 10 แจ้งเตือนทันที!)
- **Charger Removal Detector:** ดักจับ Event `ACTION_POWER_DISCONNECTED` เมื่อสายชาร์จหลุด
- **Audio Peak Detection:** ตรวจวัดระดับเสียงเดซิเบลรอบข้าง หากมีเสียงตัดเหล็ก/เคาะโลหะดังเกิน 85 dB

#### 4.1.2 Telegram Remote Control & Commands
- `/status` หรือ `เช็ค`: ตรวจสอบสถานะระบบ, แบตเตอรี่, อุณหภูมิเครื่อง, พิกัด GPS
- `/arm` / `/disarm`: เปิด/ปิดโหมดกันขโมย (ต้องยืนยันตัวตนสองชั้น)
- `/sensitivity 1-10`: ปรับระดับความไว
- `/record`: อัดเสียงรอบข้าง 15-30 วินาทีแล้วส่งไฟล์
- `/photo` หรือ `/cam`: ถ่ายรูปกล้องหลังส่ง Telegram
- `/location` หรือ `/gps`: ส่งตำแหน่งปัจจุบัน (GPS + Cell Tower LBS Backup)

#### 4.1.3 Hardware Safety & Thermal Guard (ปิดช่องโหว่ความร้อน)
- **Battery Temperature Guard:** อ่านค่าอุณหภูมิแบตผ่าน `BatteryManager.EXTRA_TEMPERATURE` หาก > 45°C สั่งหยุดชาร์จไฟผ่าน Relay หรือส่งเตือนเพื่อความปลอดภัย
- **Battery Elimination Mod (แนะนำสำหรับช่าง):** ถอดแบตเตอรี่ลิเธียมออกจากมือถือเดิม แล้วต่อวงจรแปลงไฟ DC 12V->5V 2A ตรงเข้าขั้วบอร์ดมือถือ ป้องกันแบตบวมถาวร

#### 4.1.4 Anti-Tampering & Kiosk Lockdown (ปิดช่องโหว่คนร้ายกดปิดเครื่อง)
- **Lock Task Mode / Kiosk Mode:** ล็อกแอปเป็น Device Owner ปิดไม่ให้กด Power Off Menu หรือเปิด Quick Settings Bar
- **Instant Photo & GPS Upload:** อัปโหลดภาพถ่ายและพิกัดทันทีใน 1 วินาทีแรกของการสั่น ก่อนที่คนร้ายจะมีเวลาทำลายเครื่อง

---

## 5. การปิดช่องโหว่ความปลอดภัย & Offline Fallback

### 5.1 Offline Fallback System (กรณีถูกตัดเน็ต/สัญญาณอับ)
- หากส่งผ่าน Telegram Bot ไม่สำเร็จภายใน 10 วินาที ระบบจะสลับไปใช้ **SMS Fallback (`SmsManager`)**
- โทรออกด่วนแบบ **Direct Call Alert (Missed Call)** ไปยังเบอร์มือถือหลักเมื่อเกิดเหตุฉุกเฉิน

### 5.2 Heartbeat Monitoring (ปิดช่องโหว่ Silent Failure)
- มือถือในรถจะส่ง Silent Ping หา Telegram Bot Server ทุก 15 นาที
- หาก Telegram Bot Server ไม่ได้รับ Ping เกิน 30 นาที จะส่งเตือนหามือถือหลักว่า *"⚠️ ขาดการติดต่อกับรถเกิน 30 นาที"*

### 5.3 Two-Step Confirmation (การยืนยันตัวตนสองชั้น)
- คำสั่งวิกฤต เช่น `/disarm`, `/wipe`, `/disable` ต้องใส่รหัสผ่าน OTP 6 หลัก หรือพิมพ์ยืนยันรหัสซ้ำภายใน 30 วินาที

### 5.4 Privacy by Design
- ลบไฟล์ภาพและเสียงชั่วคราวหลังส่งเสร็จภายใน 24 ชม.
- เก็บ Log ในเครื่องไม่เกิน 14 วัน
- รองรับคำสั่ง `/wipe` ลบข้อมูลในเครื่องระยะไกล

---

## 6. ขั้นตอนการพัฒนา (Updated Development Roadmap)

### Phase 1: Proof of Concept & Workspace (`d:\security`) [เสร็จสิ้น]
- [x] จัดเตรียมโครงสร้างโปรเจคและเอกสารสเปก v2.4
- [x] เซ็ตอัพสภาพแวดล้อม JDK 21 + Android SDK + Android CLI + PowerShell script

### Phase 2: Core Sensor Engine & Multi-Sensor Fusion (2 สัปดาห์)
- [ ] พัฒนา `VibrationDetector` (Accelerometer + Debounce Window Filter)
- [ ] พัฒนา `LightIntrusionDetector` (Light Sensor ตรวจแสงใต้เบาะ)
- [ ] พัฒนา `PowerStateReceiver` (ดักจับสายชาร์จหลุด + อุณหภูมิแบตเกิน 45°C)

### Phase 3: Telegram Bot & Offline Fallback (2 สัปดาห์)
- [ ] เชื่อมต่อ Telegram Bot Long Polling / Webhook Receiver
- [ ] พัฒนาคำสั่งควบคุม `/status`, `/arm`, `/disarm`, `/record`, `/photo`, `/location`
- [ ] พัฒนาระบบ SMS Fallback & Direct Phone Call Alert
- [ ] พัฒนา Heartbeat Monitor Ping (ยิง Ping ทุก 15 นาที)

### Phase 4: Security Hardening & Field Testing (2 สัปดาห์)
- [ ] ระบบ Two-Step Confirmation (OTP 6 หลัก)
- [ ] Kiosk Mode / Anti-Tamper Lockdown ป้องกันการกดปิดเครื่อง
- [ ] ทดสอบความร้อนและระบบไฟบนรถมอเตอร์ไซค์จริง
