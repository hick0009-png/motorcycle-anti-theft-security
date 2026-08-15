# คู่มือการออกแบบ UI/UX ด้วย Figma สำหรับโปรเจคกันขโมยมอเตอร์ไซค์ (Figma UI Design Guide)

**โปรเจค:** ระบบกันขโมยมอเตอร์ไซค์ด้วยมือถือเก่า (Motorcycle Anti-Theft Sensor App)  
**อัปเดต:** 6 สิงหาคม 2026  

---

## 1. ตัวอย่างภาพต้นแบบ UI Design Concepts (Visual Mockups)

ในการออกแบบด้วย Figma สำหรับโปรเจคนี้ เราจะแบ่งการออกแบบออกเป็น **2 ส่วนหลัก**:

1. **Sensor App Dashboard (ฝั่งมือถือเก่าในรถ):** เน้นความชัดเจน อ่านค่าได้จากระยะไกล สไตล์ Dark Neon Glassmorphic มีปุ่มสวิตช์ Arm/Disarm ขนาดใหญ่ แถบวัดแรงสั่นสะเทือน Real-time และการแสดงผลอุณหภูมิแบตเตอรี่
2. **Telegram Bot Interface (ฝั่งมือถือหลัก):** ออกแบบปุ่ม Inline Keyboard เมนูทางลัด (`🔒 Armed`, `🔓 Disarm`, `🎤 Record`, `📸 Photo`, `📍 Location`) และการแสดงการเตือนเหตุการณ์พร้อมภาพถ่าย

---

## 2. โครงสร้างและระบบดีไซน์ (Design System & Color Tokens)

สามารถนำค่า Color Tokens และ Typography ต่อไปนี้ไปสร้างใน **Figma Styles / Variables** ได้ทันที:

### 2.1 จานสี (Color Palette)
- **Background Main (Dark):** `#0D1117` (พื้นหลังเข้ม ประหยัดแบตเตอรี่ OLED/AMOLED)
- **Surface / Glassmorphism:** `#161B22` (โปร่งแสง Blur 16px, Border `#30363D`)
- **Armed State (Emerald Green):** `#10B981` / Glow `#059669`
- **Danger Alert (Crimson Red):** `#EF4444` / Glow `#DC2626`
- **Accent Cyan (Active Sensor):** `#06B6D4`
- **Text Main:** `#F3F4F6`, **Text Muted:** `#9CA3AF`

### 2.2 ตัวอักษร (Typography & Hierarchy)
- **Font Family:** `Inter` หรือ `Outfit` (Google Fonts)
- **Display Status:** Bold 28px / Height 34px
- **Heading:** SemiBold 20px / Height 26px
- **Body Text:** Regular 14px / Height 20px
- **Caption / Badge:** Medium 12px / Height 16px

---

## 3. ขั้นตอนการออกแบบใน Figma (Step-by-Step Figma Workflow)

### 📐 Step 1: การตั้งค่า Frame & Layout Grid
1. สร้าง **Frame** ขนาดสมาร์ตโฟน Android: เลือก Preset **Android Small (360 x 800 px)** หรือ **Android Large (360 x 840 px)**
2. กำหนด **Layout Grid**: 
   - Type: **Columns** | Count: **4** | Margin: **16px** | Gutter: **12px**
   - Type: **Rows** (8-pt Grid System) | Height: **8px**

### 🧩 Step 2: สร้าง Atomic Components
1. **Status Shield Badge:** สร้าง Component แสดงสถานะ `Armed` (เขียว) / `Disarm` (เทา) / `Alerting` (แดง)
2. **Large Pulse Toggle Button:** สร้างปุ่มวงกลมขนาด 140x140px มีวงแหวน Glow Pulse
3. **Sensitivity Slider Component:** แถบปรับความไว 1-10 พร้อม Number Bubble Indicator
4. **Metric Cards:** การ์ด Glassmorphism สำหรับแสดงแบตเตอรี่ (%), อุณหภูมิ (°C) และสถานะเน็ต

### ⚡ Step 3: การใช้ Auto Layout (Flexbox ใน Figma)
- ใช้ **Auto Layout (`Shift + A`)** กับทุกการ์ดและคอนเทนเนอร์
- กำหนด `Resizing`: Width = **Fill Container**, Height = **Hug Contents** เพื่อให้ UI ขยายตามขนาดหน้าจอมือถือแต่ละรุ่นอัตโนมัติ

### 🔄 Step 4: การสร้าง Interactive Prototype
1. สร้าง Component Variant สำหรับปุ่ม Arm/Disarm
2. ลากเส้นเชื่อมในโหมด **Prototype**:
   - Event: `On Click` -> Action: `Change to` (Variant Disarmed)
   - Animation: `Smart Animate` (Ease Out 300ms) ให้วงแหวนเปลี่ยนสีและเปลี่ยนข้อความอย่างนุ่มนวล

---

## 4. โครงสร้างหน้าจอหลัก 3 หน้า (Screen Layout Blueprint)

```
[ Screen 1: Dashboard ]       [ Screen 2: Settings ]       [ Screen 3: Telegram Menu ]
┌─────────────────────┐       ┌─────────────────────┐       ┌─────────────────────┐
│ 🛡️ ARMED (Green)    │       │ ⚙️ System Settings   │       │ 🤖 Telegram Bot     │
│                     │       │ ─────────────────── │       │ ─────────────────── │
│   (🔴 PULSE BTN)    │       │ Sensitivity: [ 7 ]  │       │ 🚨 ALERT DETECTED!  │
│   Tap to Disarm     │       │ Debounce: 500ms     │       │ [Photo Attachment]  │
│                     │       │ Temp Guard: 45°C    │       │                     │
│ 📊 Motion: 0.12m/s² │       │ Chat ID: 987654321  │       │ [ 🔒 Arm ] [ 🔓 Disarm]│
│ 🔋 87% | 🌡️ 38°C   │       │ 🔒 Kiosk Lock Mode  │       │ [ 📸 Photo] [ 📍 GPS]│
└─────────────────────┘       └─────────────────────┘       └─────────────────────┘
```

---

## 5. การส่งออกไฟล์และนำไปพัฒนาต่อด้วย Android Kotlin

1. **Export Assets:** ส่งออกไอคอนเป็นรูปแบบ **SVG** หรือ **PNG @2x / @3x**
2. **Color Tokens Mapping:** นำค่าสีกำหนดลงใน `Color.kt` ของ Android Compose:
   ```kotlin
   val DarkBackground = Color(0xFF0D1117)
   val SurfaceGlass = Color(0xFF161B22)
   val StatusArmed = Color(0xFF10B981)
   val StatusAlert = Color(0xFFEF4444)
   ```
3. **Figma to Code:** ใช้ฟีเจอร์ Figma Dev Mode เพื่อดูค่า Padding, Spacing และ Typography สำหรับเขียน UI ด้วย **Jetpack Compose**
