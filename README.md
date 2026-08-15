# Motorcycle Anti-Theft Project (ระบบกันขโมยมอเตอร์ไซค์ด้วยมือถือเก่า v2.4)

โครงการพัฒนาระบบป้องกันการโจรกรรมมอเตอร์ไซค์ (โดยเฉพาะการถอดกล่อง ECU หรือการยกรถ) โดยใช้สมาร์ตโฟน Android เครื่องเก่าที่ไม่ได้ใช้งาน ร่วมกับซิมอินเทอร์เน็ตในการเป็น **Multi-Sensor Anti-Theft Device** แจ้งเตือนไปยังมือถือหลักผ่าน **Telegram Bot API**, **SMS Fallback** และ **Direct Call Alert**

---

## 📋 โครงสร้างโปรเจค (Directory Structure)

```
d:/security/
├── docs/                                          # เอกสารการออกแบบและแผนพัฒนา
│   ├── motorcycle-anti-theft-project-plan-v3.md   # แผนพัฒนาหลัก v2.4 (ฉบับอัปเดตสมบูรณ์)
│   ├── godkiller_analysis_report.md               # รายงานการวิเคราะห์ Godkiller MCP
│   └── loopholes_and_github_insights.md           # เจาะลึก 5 ช่องโหว่ & ไอเดียจาก GitHub
├── MotorcycleAntiTheftSensor/                    # Android Sensor App Project (Kotlin)
│   ├── app/src/main/
│   │   ├── java/com/example/motorcycleantitheftsensor/
│   │   │   ├── MainActivity.kt                    # UI หน้าหลักเปิด/ปิดระบบและตั้งค่า
│   │   │   ├── service/                           # Foreground Service ตรวจจับตลอด 24 ชม.
│   │   │   ├── sensor/                            # Vibration & Light Intrusion Detectors
│   │   │   ├── telegram/                          # Telegram Bot Client & Heartbeat Ping
│   │   │   ├── telephony/                         # SMS Fallback & Direct Call Alert
│   │   │   └── data/                              # Device Configuration & Log Storage
│   │   └── AndroidManifest.xml                    # Permissions
│   ├── build.gradle.kts                           # Gradle Build Configuration
│   └── gradlew.bat                                # Gradle Wrapper
├── scripts/                                       # สคริปต์ช่วยตั้งค่าสภาวะแวดล้อม
│   └── setup_env.ps1                              # สคริปต์ตั้งค่า JAVA_HOME และ Android SDK
└── README.md                                      # คู่มือโปรเจค (ไฟล์นี้)
```

---

## 🛠️ เครื่องมือและสภาพแวดล้อม (Tools & Environment)

- **Android SDK:** `C:\Users\ASUS\AppData\Local\Android\Sdk` (Min SDK 26, Target SDK 35)
- **Java / JDK:** OpenJDK 21 (JetBrains Runtime) ที่ `C:\Program Files\Android\Android Studio\jbr`
- **Android CLI Tool:** `C:\Users\ASUS\AppData\AndroidCLI\android.exe`
- **Git Version Control:** Git 2.54.0

### วิธีเริ่มต้นสภาวะแวดล้อม:
```powershell
powershell -ExecutionPolicy Bypass -File d:\security\scripts\setup_env.ps1
```

---

## 🚀 ฟีเจอร์หลักในระบบ (Core Features v2.4)

1. **Multi-Sensor Fusion Engine:**
   - **Accelerometer + Gyroscope:** ตรวจจับแรงสั่นสะเทือนและการเอียงตัวรถ (Tilt Angle > 15°)
   - **Light Intrusion Sensor:** ตรวจจับแสงสว่างเมื่อเบาะหรือกล่อง ECU ถูกเปิด (`Sensor.TYPE_LIGHT`)
   - **Power Disconnect Sensor:** ดักจับสายชาร์จหลุด (`ACTION_POWER_DISCONNECTED`)
   - **Audio Peak Detector:** ตรวจจับระดับเสียงเคาะ/ตัดเหล็ก (> 85 dB)
2. **Offline Fallback & Call Alert:** ส่ง SMS หรือยิงสายด่วน (Missed Call) เข้าเบอร์มือถือหลักทันทีเมื่อไร้สัญญาณอินเทอร์เน็ต
3. **Heartbeat Monitor:** ยิง Silent Ping หา Telegram Bot ทุก 15 นาที เตือนทันทีเมื่อขาดการติดต่อเกิน 30 นาที
4. **Thermal Safety Cut-off:** ตรวจวัดอุณหภูมิแบตเตอรี่ สั่งตัดไฟเมื่อความร้อนเกิน 45°C ป้องกันแบตเตอรี่บวม
5. **Kiosk Mode / Anti-Tamper Lockdown:** ล็อกแอปเป็น Device Owner ป้องกันคนร้ายกด Power Off Menu
6. **Telegram Remote Control:** สั่งงานผ่าน Telegram Bot (`/status`, `/arm`, `/disarm`, `/record`, `/photo`, `/location`)
7. **Two-Step Confirmation & Privacy:** ยืนยันสองชั้นก่อนสั่งคำสั่งวิกฤต + ลบไฟล์สื่อชั่วคราวอัตโนมัติภายใน 24 ชม.
