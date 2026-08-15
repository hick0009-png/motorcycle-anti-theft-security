# สมุดแสดงภาพต้นแบบ UI (UI Design Gallery Showcase & Figma Specifications)

**โปรเจค:** ระบบกันขโมยมอเตอร์ไซค์ด้วยมือถือเก่า (Motorcycle Anti-Theft System v2.4)  
**พื้นที่โปรเจค:** [`d:\security`](file:///d:/security)  

---

## 📸 ภาพต้นแบบ UI ทั้ง 2 ภาพที่สร้างไว้ก่อนหน้านี้ (Generated UI Concepts)

1. **Sensor App Dashboard UI (ฝั่งมือถือเก่าใต้เบาะรถ):**  
   ภาพต้นแบบแดชบอร์ดหลักบนมือถือเก่า สไตล์ Dark Neon Glassmorphism มีปุ่มวงกลม Arm/Disarm, แถบวัดความสั่นสะเทือน Real-time, แถบปรับ Sensitivity และการ์ดแสดงสถานะแบตเตอรี่/อุณหภูมิเครื่อง

2. **Telegram Bot Control Interface UI (ฝั่งมือถือหลักเจ้าของรถ):**  
   ภาพต้นแบบหน้าอินเทอร์เฟซแชต Telegram แสดงการแจ้งเตือนฉุกเฉินพร้อมภาพถ่ายจากกล้องหลัง, การ์ดพิกัด GPS แผนที่ และปุ่มกด Inline Keyboard (`🔒 Arm`, `🔓 Disarm`, `🎤 Record`, `📸 Photo`, `📍 Location`)

---

## 🎨 รายละเอียดดีไซน์ของแต่ละรูปภาพ (Design Specifications breakdown)

### รูปที่ 1: Sensor App Dashboard UI (แอปฝั่งมือถือเก่าใต้เบาะรถ)

* **วัตถุประสงค์:** หน้าจอแสดงสถานะหลักสำหรับมือถือเก่าที่วางไว้ในรถมอเตอร์ไซค์ อ่านค่าง่าย เด่นชัด มองเห็นจากระยะไกล
* **องค์ประกอบในรูปภาพ:**
  1. **Top Status Shield Badge:** สัญลักษณ์โล่แจ้งสถานะ `🛡️ SYSTEM ARMED` สีเขียวนีออน (#10B981)
  2. **Central Pulse Button:** ปุ่มสวิตช์วงกลมขนาดใหญ่ Tap to Arm/Disarm พร้อมวงแหวน Pulse Glow 
  3. **Real-time Motion Intensity Graph:** กราฟและตัวเลขแสดงระดับความสั่นสะเทือน (m/s²) แบบเรียลไทม์
  4. **Sensitivity Slider:** แถบสไลด์ปรับระดับความไว 1-10
  5. **System Health Metric Cards:** การ์ด Glassmorphism แสดงระดับแบตเตอรี่ (87%), อุณหภูมิเครื่อง (38°C) และสถานะการเชื่อมต่อเน็ต

---

### รูปที่ 2: Telegram Control Bot UI (อินเทอร์เฟซฝั่งมือถือหลัก)

* **วัตถุประสงค์:** หน้าอินเทอร์เฟซแชต Telegram บนมือถือหลักของผู้ใช้ สำหรับรับการแจ้งเตือนฉุกเฉินและสั่งการข้ามระยะไกล
* **องค์ประกอบในรูปภาพ:**
  1. **Alert Notification Banner:** แบนเนอร์เตือนภัยสีแดงฉุกเฉิน (`🚨 MOTION DETECTED!`)
  2. **Camera Snapshot Attachment:** รูปถ่ายจากกล้องหลังมือถือเก่าส่งตรงถึง Telegram ทันทีที่ตรวจเจอความสั่น
  3. **GPS Map Location Card:** การ์ดพิกัดตำแหน่งปัจจุบันพร้อมลิงก์แผนที่ Google Maps
  4. **Inline Keyboard Quick Actions:** เมนูปุ่มกดตอบโต้ออโต้:
     - `🔒 Arm` / `🔓 Disarm`
     - `🎤 Record Audio`
     - `📸 Take Photo`
     - `📍 Get Location`
     - `⚠️ Wipe Data`

---

## 🛠️ วิธีดึงสเปกภาพจาก Figma MCP Server

เมื่อคุณเปิดใช้ **Figma MCP Server** สามารถใช้คำสั่งเหล่านี้ในการแปลงภาพและเลย์เอาต์จาก Figma เป็นโค้ด:

1. **ดึงข้อมูล Node Tree & Layout:**
   ```json
   get_figma_data(fileKey="YOUR_FIGMA_FILE_KEY")
   ```
2. **ดาวน์โหลดรูปภาพประกอบ Assets จาก Figma:**
   ```json
   download_figma_images(fileKey="YOUR_FIGMA_FILE_KEY", nodes=[{"id": "1:10", "image_ref": "status_badge"}])
   ```
