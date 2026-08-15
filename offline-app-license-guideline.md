# แนวทางล็อกแอปให้ใช้งานได้เฉพาะเครื่องที่ได้รับสิทธิ์

## เป้าหมาย

ระบบนี้ออกแบบสำหรับช่วงเริ่มต้นที่ยังไม่มี Server และติดตั้งแอปให้ลูกค้าเอง

เป้าหมายคือ:

- ใช้ APK ตัวเดียวสำหรับลูกค้าทุกคน
- 1 License ใช้งานได้กับ 1 เครื่อง
- ต่อให้ลูกค้าคัดลอก APK ไปให้คนอื่น เครื่องอื่นก็เปิดใช้งานไม่ได้
- ถ้าลูกค้าลบแอปโดยไม่ตั้งใจ สามารถติดตั้ง APK ใหม่บนเครื่องเดิมและใช้ License เดิมได้
- ไม่ต้อง Build APK ใหม่ให้ลูกค้าทีละคน
- ในอนาคตสามารถย้ายไปใช้ระบบ Server / Play Store ได้

---

## แนวคิดหลัก

ไม่ควรผูกสิทธิ์กับไฟล์ APK โดยตรง ให้แยกออกเป็น 2 ส่วน:

1. **APK**
   - เป็นไฟล์เดียวกันทุกคน
   - ภายในมี Public Key สำหรับตรวจ License
   - ไม่มี Private Key อยู่ในแอป

2. **License**
   - สร้างเฉพาะสำหรับเครื่องของลูกค้า
   - ผูกกับ Device ID ของเครื่อง
   - ลงลายเซ็นด้วย Private Key ที่เก็บไว้กับผู้พัฒนาเท่านั้น

```text
มือถือของลูกค้า
      │
      ▼
ติดตั้ง APK
      │
      ▼
แอปอ่าน Device ID
      │
      ▼
แสดง Installation ID
      │
      ▼
ผู้พัฒนานำ ID ไปสร้าง License
      │
      ▼
Private Key เซ็น License
      │
      ▼
ส่ง License ให้เครื่องลูกค้า
      │
      ▼
แอปใช้ Public Key ตรวจสอบ
      │
      ├── ถูกต้อง + ID ตรง → ใช้งานได้
      └── ไม่ถูกต้อง / ID ไม่ตรง → ไม่เปิดระบบ
```

---

# 1. Device ID

แอปต้องมีข้อมูลบางอย่างที่ใช้แยกเครื่องหนึ่งออกจากอีกเครื่องหนึ่ง สำหรับ Android ช่วง MVP สามารถพิจารณาใช้ `ANDROID_ID` เป็นส่วนหนึ่งของ Installation ID

ตัวอย่าง:

```text
ANDROID_ID:
9f21c38ab0174e52
```

ไม่ควรแสดง ID ดิบให้ลูกค้าโดยตรงหากไม่จำเป็น สามารถนำมาผ่าน Hash ก่อน เช่น:

```text
SHA-256(
    packageName
    + androidId
    + appSalt
)
```

แล้วเอาเพียงบางส่วนมาแสดง:

```text
Installation ID:
MC-A81F-293C-77D2
```

> หมายเหตุ: Device Identifier บางชนิดอาจเปลี่ยนเมื่อ Factory Reset หรือมีการเปลี่ยนแปลงระบบ ดังนั้นต้องมีขั้นตอนสำหรับออก License ใหม่ให้ลูกค้า

---

# 2. ห้ามใช้ IMEI เป็นแกนหลัก

ไม่แนะนำให้พึ่ง IMEI เพราะ Android รุ่นใหม่จำกัดสิทธิ์การเข้าถึงข้อมูลดังกล่าวสำหรับแอปทั่วไป ดังนั้นระบบควรออกแบบให้ทำงานได้โดยไม่ต้องใช้ IMEI

---

# 3. Public Key / Private Key

ส่วนสำคัญที่สุดของระบบคือการใช้ Digital Signature

สร้าง Key Pair จำนวนหนึ่งชุด:

```text
Private Key
    │
    └── อยู่กับผู้พัฒนาเท่านั้น

Public Key
    │
    └── ฝังอยู่ใน APK
```

## Private Key

ใช้สำหรับออก License

ห้าม:

- ฝัง Private Key ใน APK
- ส่ง Private Key ให้ลูกค้า
- Upload Private Key ขึ้น GitHub
- ใส่ Private Key ใน Source Code Repository
- เก็บ Private Key ในไฟล์ที่เผยแพร่พร้อม APK

ควร Backup Private Key ไว้อย่างปลอดภัย ถ้า Private Key หลุด คนอื่นสามารถสร้าง License ปลอมที่แอปยอมรับได้

## Public Key

Public Key สามารถอยู่ใน APK ได้ หน้าที่คือยืนยันว่า License ถูกสร้างและเซ็นโดย Private Key ของผู้พัฒนาจริง

---

# 4. ข้อมูลภายใน License

ตัวอย่างข้อมูลที่สามารถเก็บใน License:

```json
{
  "licenseVersion": 1,
  "licenseId": "LIC-000001",
  "deviceId": "MC-A81F-293C-77D2",
  "product": "MotorcycleGuard",
  "edition": "early-adopter",
  "issuedAt": "2026-08-07",
  "customerRef": "C0001"
}
```

จากนั้นนำข้อมูลทั้งหมดไปเซ็นด้วย Private Key และจัดเก็บเป็นไฟล์ เช่น `license.dat`

---

# 5. ขั้นตอนติดตั้งให้ลูกค้า

## ขั้นที่ 1 — ติดตั้ง APK

ช่างติดตั้ง APK ตัวหลักลงมือถือที่จะอยู่กับรถ

```text
motorcycle-guard.apk
```

## ขั้นที่ 2 — แอปแสดง Installation ID

เปิดแอปครั้งแรก แอปยังไม่ทำงานเต็มระบบ แต่แสดง:

```text
อุปกรณ์ยังไม่ได้เปิดใช้งาน

Installation ID
MC-A81F-293C-77D2
```

## ขั้นที่ 3 — สร้าง License

ผู้พัฒนาเปิด License Generator ของตัวเอง กรอก Installation ID แล้วกด Generate License

โปรแกรมใช้ Private Key เซ็นข้อมูลและสร้าง:

```text
license.dat
```

## ขั้นที่ 4 — Activate

นำ License เข้าเครื่องลูกค้า แอปตรวจ:

```text
Digital Signature
        +
Device ID
        +
Product ID
        +
License Version
```

ถ้าทุกอย่างถูกต้อง:

```text
✓ เปิดใช้งานสำเร็จ
```

---

# 6. กรณีลูกค้าก๊อป APK

ลูกค้า A ส่ง `motorcycle-guard.apk` ให้ลูกค้า B ได้ แต่เมื่อเปิดบนเครื่อง B จะได้ Installation ID คนละค่า จึงไม่มี License ที่ตรงกับเครื่องนั้น และใช้งานระบบไม่ได้

---

# 7. กรณีก๊อปทั้ง APK และ License

License ของลูกค้า A มี Device ID ของเครื่อง A ฝังอยู่ หากนำไปใส่เครื่อง B แอปตรวจพบว่า Device ID ไม่ตรงกัน และไม่เปิดระบบ

---

# 8. กรณีลูกค้าลบแอป

ถ้าลูกค้าลบแอป แล้วติดตั้ง APK ตัวเดิมใหม่ หาก Device ID ที่ระบบใช้ยังคงเดิม License เดิมก็สามารถใช้ Activate เครื่องเดิมได้อีกครั้ง

ดังนั้นควรเก็บข้อมูลไว้ฝั่งผู้พัฒนาด้วย:

```text
Customer ID
ชื่อ
เบอร์โทร
รุ่นรถ
รุ่นมือถือ
Installation ID
License ID
วันที่ติดตั้ง
สถานะ License
```

---

# 9. กรณี Factory Reset

Factory Reset อาจทำให้ Identifier บางชนิดเปลี่ยนได้ จึงควรกำหนดนโยบายตั้งแต่แรกว่า:

```text
เครื่องเดิม + Factory Reset
→ ติดต่อผู้ติดตั้ง
→ ตรวจสอบลูกค้า
→ ออก License ใหม่
```

ไม่ควรออกแบบระบบโดยสมมติว่า Identifier จะไม่มีวันเปลี่ยน

---

# 10. กรณีเปลี่ยนมือถือ

License เก่าไม่ควรทำงานกับมือถือเครื่องใหม่

ขั้นตอน:

```text
ลูกค้าติดต่อ
      │
      ▼
ตรวจสอบข้อมูลลูกค้า
      │
      ▼
รับ Installation ID เครื่องใหม่
      │
      ▼
บันทึกว่า License เดิมถูกย้ายแล้ว
      │
      ▼
Generate License ใหม่
```

ในระบบ Offline จริง ๆ เราไม่สามารถสั่งยกเลิก License เก่าจากระยะไกลได้ คำว่า “ยกเลิก” ในช่วง MVP จึงหมายถึงการบันทึกสถานะไว้ในทะเบียนของเรา

---

# 11. License Generator

ควรสร้างเครื่องมือแยกจาก APK

```text
license-generator/
├── private_key
├── generator
└── customer_records
```

Generator อาจเป็นโปรแกรม Desktop, Command Line Tool หรือโปรแกรมเล็ก ๆ บน Notebook ของผู้พัฒนา ไม่จำเป็นต้องมี Server

---

# 12. Algorithm สำหรับ Digital Signature

ควรใช้ Algorithm มาตรฐาน เช่น:

- Ed25519
- ECDSA
- RSA Signature

สำหรับโปรเจกต์ใหม่ Ed25519 เป็นตัวเลือกที่น่าสนใจ เพราะ Key และ Signature มีขนาดเล็กและ Library สมัยใหม่รองรับดี

ไม่ควรคิดระบบเข้ารหัสหรือ Signature Algorithm ขึ้นเอง

---

# 13. การตรวจ License ภายในแอป

ควรมี License Manager กลาง เช่น:

```text
LicenseManager
    │
    ├── verifySignature()
    ├── verifyDevice()
    ├── verifyProduct()
    ├── verifyLicenseVersion()
    └── isLicensed()
```

ฟังก์ชันสำคัญควรเรียก `LicenseManager.isLicensed()` ก่อนทำงาน

---

# 14. ข้อจำกัดของ Offline License

ต้องเข้าใจว่า:

```text
Offline License
≠
ป้องกันการ Crack 100%
```

คนที่มีความรู้สามารถ Reverse Engineer, Patch หรือ Rebuild APK ได้ ดังนั้นเป้าหมายไม่ใช่ทำให้ Crack ไม่ได้ แต่คือทำให้ผู้ใช้ทั่วไปไม่สามารถ Copy → Install → ใช้งานฟรี ได้ทันที

---

# 15. เพิ่มความยากในการแกะ APK

สามารถใช้ Release Build และ Code Obfuscation เช่น `R8 / ProGuard` เพื่อทำให้ชื่อ Class และ Method อ่านยากขึ้น

แต่ Obfuscation เป็นเพียงการเพิ่มความยาก ไม่ใช่ระบบรักษาความปลอดภัยที่สมบูรณ์

---

# 16. ห้ามฝัง Master Password

ไม่ควรมี Shared Secret เช่น:

```text
MASTER_PASSWORD = "123456"
SECRET_LICENSE_KEY = "abc123"
```

อยู่ใน APK เพราะคนที่ Decompile APK อาจหาเจอได้

ถ้าต้องการตรวจว่า License ออกโดยผู้พัฒนาจริง ให้ใช้ Digital Signature แทน

---

# 17. ทะเบียนลูกค้า

ช่วงแรกเก็บใน Spreadsheet ได้ เช่น:

| Customer ID | เบอร์โทร | รถ | Device ID | License ID | วันที่ | สถานะ |
|---|---|---|---|---|---|---|
| C0001 | xxx | PCX | MC-A81F... | LIC-000001 | 2026-08-07 | Active |
| C0002 | xxx | NMAX | MC-C732... | LIC-000002 | 2026-08-08 | Active |

ไม่จำเป็นต้องมี Backend ตั้งแต่วันแรก

---

# 18. Workflow ช่วง Early Adopter

```text
ลูกค้านัดติดตั้ง
        │
        ▼
ติดตั้งสายชาร์จ
        │
        ▼
ติดตั้ง APK
        │
        ▼
เปิดแอป
        │
        ▼
อ่าน Installation ID
        │
        ▼
Generate License
        │
        ▼
Import License
        │
        ▼
ทดสอบการทำงาน
        │
        ▼
บันทึกลูกค้า + License
        │
        ▼
ส่งมอบ
```

---

# 19. สิ่งที่ยังไม่ต้องทำในช่วงแรก

ยังไม่จำเป็นต้องมี:

- Account System
- Login
- OTP
- Cloud Database
- Subscription Server
- License Server
- Play Integrity
- Remote License Revocation
- ระบบชำระเงินอัตโนมัติ

เพราะผู้พัฒนาเป็นผู้ติดตั้งเองอยู่แล้ว Offline Signed License เพียงพอสำหรับการพิสูจน์ตลาดช่วงแรก

---

# 20. แนวทางต่อยอดในอนาคต

```text
Version 1
Offline Signed License
        │
        ▼
Version 2
License API + Database
        │
        ▼
Version 3
User Account
        │
        ▼
Version 4
Google Play + Play Integrity
```

ควรมี `licenseVersion` ตั้งแต่แรก เพื่อให้อัปเกรดระบบในอนาคตได้ง่าย

---

# สรุป Architecture ที่แนะนำ

```text
┌─────────────────────────┐
│      Developer PC       │
│                         │
│  Private Key            │
│       │                 │
│       ▼                 │
│  License Generator      │
└───────────┬─────────────┘
            │
            │ license.dat
            ▼
┌─────────────────────────┐
│    Customer Android     │
│                         │
│  APK                    │
│   │                     │
│   ├── Public Key        │
│   ├── Device ID         │
│   └── License Checker   │
└─────────────────────────┘
```

หลักสำคัญ:

```text
APK = แจกซ้ำได้
License = เฉพาะเครื่อง
Private Key = อยู่กับผู้พัฒนาเท่านั้น
Public Key = อยู่ใน APK
Device ID = ใช้ผูก License กับเครื่อง
```

## เป้าหมายทางธุรกิจ

ระบบนี้ไม่ได้ออกแบบมาเพื่อหยุด Hacker ระดับสูงทั้งหมด แต่ป้องกันสถานการณ์ทั่วไป:

```text
ลูกค้าซื้อ 1 คน
→ Copy APK
→ ส่งเข้ากลุ่ม
→ ทุกคนติดตั้งใช้ฟรี
```

ให้กลายเป็น:

```text
Copy APK ได้
        │
        ▼
ติดตั้งได้
        │
        ▼
แต่ไม่มี License ที่ตรงกับเครื่อง
        │
        ▼
ใช้งานระบบไม่ได้
```

สำหรับช่วงที่ขายแบบติดตั้งเองและยังไม่มี Server นี่เป็นโครงสร้างที่เรียบง่าย ดูแลได้ และสามารถต่อยอดภายหลังได้
