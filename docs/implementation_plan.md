# แผนการดำเนินงาน v2.5 — Security-First Implementation Plan
## ระบบกันขโมยมอเตอร์ไซค์ด้วยมือถือเก่า

Updated: 6 สิงหาคม 2026 — Full plan in artifact: implementation_plan.md

### User Decisions Applied:
1. ✅ Telegram Bot decode SMS (ไม่ต้องติดตั้งแอปเพิ่มบนมือถือหลัก)
2. ✅ FRP Lock enabled (Factory Reset Protection)
3. ✅ Priority 1 Security Foundation ก่อนเขียน Sensor/UI
4. ✅ TOTP QR Code สำหรับ Google Authenticator

### 22 Sub-Tasks / 6 Priorities:
- P1 🔴 SEC-01~05: Security Foundation (Keystore, EncryptedPrefs, CertPin, SMS Encrypt, TOTP+QR)
- P2 🟠 SEC-06~09: Anti-Tamper (Kiosk, APK Integrity, DeviceAdmin+FRP, NetworkSecConfig)
- P3 🟢 SEN-01~04: Sensors (Vibration, Light, Thermal, Audio)
- P4 🔵 SVC-01~03: Service (Foreground 24/7, Watchdog, Boot)
- P5 🟣 COM-01~03: Communication (Telegram+Decode, Heartbeat, SMS Fallback)
- P6 ⚪ UI-01~03: UI (Dashboard+QR Setup, Config, Manifest)
