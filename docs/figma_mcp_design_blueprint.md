# พิมพ์เขียวโครงสร้างออกแบบ UI สำหรับ Figma MCP Server (Figma Design Data Payload)

**โปรเจค:** ระบบกันขโมยมอเตอร์ไซค์ด้วยมือถือเก่า (Motorcycle Anti-Theft Sensor App v2.4)  
**เซิร์ฟเวอร์:** Figma MCP Server (`figma-developer-mcp`)  
**อัปเดต:** 6 สิงหาคม 2026  

---

## 1. คำแนะนำการเชื่อมต่อกับไฟล์ Figma จริง (Live Figma File Connection)

หากคุณมี **Figma File** ในบัญชีของคุณแล้ว สามารถคัดลอก URL หรือ **File Key** มาให้ AI ดึงข้อมูลผ่าน Figma MCP ได้ทันที:

```
รูปแบบ URL Figma:
https://www.figma.com/design/ABC123456XYZ/Motorcycle-Anti-Theft-UI

-> File Key คือ: ABC123456XYZ
```

เมื่อคุณส่ง URL หรือ File Key มา AI จะใช้คำสั่ง `get_figma_data(fileKey="ABC123456XYZ")` เพื่ออ่านเลย์เอาต์ สี และคอมโพเนนต์จากไฟล์ของคุณมาสร้างโค้ดแบบอัตโนมัติ

---

## 2. โครงสร้าง Figma Node Tree JSON Data (Figma MCP Schema)

โครงสร้างต่อไปนี้จำลองเป็น **Figma Document Tree** สำหรับสร้างหน้าจอ **Sensor App Dashboard (Android 360x800)** ใน Figma:

```json
{
  "document": {
    "id": "0:0",
    "name": "Document",
    "type": "DOCUMENT",
    "children": [
      {
        "id": "1:2",
        "name": "Sensor App Dashboard (Android Small)",
        "type": "FRAME",
        "absoluteBoundingBox": { "x": 0, "y": 0, "width": 360, "height": 800 },
        "backgroundColor": { "r": 0.05, "g": 0.06, "b": 0.09, "a": 1.0 },
        "children": [
          {
            "id": "1:10",
            "name": "Header / Status Bar",
            "type": "FRAME",
            "layoutMode": "HORIZONTAL",
            "paddingLeft": 16,
            "paddingRight": 16,
            "children": [
              {
                "id": "1:11",
                "name": "Status Shield Badge",
                "type": "COMPONENT",
                "fills": [{ "type": "SOLID", "color": { "r": 0.06, "g": 0.72, "b": 0.5, "a": 0.2 } }],
                "strokes": [{ "type": "SOLID", "color": { "r": 0.06, "g": 0.72, "b": 0.5, "a": 1.0 } }],
                "children": [
                  { "id": "1:12", "name": "Text", "type": "TEXT", "characters": "🛡️ ARMED - SYSTEM ACTIVE" }
                ]
              }
            ]
          },
          {
            "id": "1:20",
            "name": "Arm/Disarm Pulse Button Container",
            "type": "FRAME",
            "layoutMode": "VERTICAL",
            "primaryAxisAlignItems": "CENTER",
            "children": [
              {
                "id": "1:21",
                "name": "Pulsing Ring Button",
                "type": "VECTOR",
                "absoluteBoundingBox": { "width": 140, "height": 140 },
                "fills": [{ "type": "GRADIENT_RADIAL", "color": { "r": 0.06, "g": 0.72, "b": 0.5, "a": 0.8 } }]
              },
              {
                "id": "1:22",
                "name": "Button Label",
                "type": "TEXT",
                "characters": "TAP TO DISARM",
                "style": { "fontFamily": "Inter", "fontSize": 14, "fontWeight": 700 }
              }
            ]
          },
          {
            "id": "1:30",
            "name": "Motion Sensor Real-time Card",
            "type": "FRAME",
            "layoutMode": "VERTICAL",
            "paddingAll": 16,
            "backgroundColor": { "r": 0.08, "g": 0.1, "b": 0.13, "a": 0.8 },
            "children": [
              { "id": "1:31", "name": "Metric Title", "type": "TEXT", "characters": "VIBRATION INTENSITY" },
              { "id": "1:32", "name": "Metric Value", "type": "TEXT", "characters": "0.12 m/s² (NORMAL)", "style": { "fontSize": 20 } }
            ]
          },
          {
            "id": "1:40",
            "name": "System Health Grid",
            "type": "FRAME",
            "layoutMode": "HORIZONTAL",
            "itemSpacing": 12,
            "children": [
              { "id": "1:41", "name": "Battery Card", "type": "FRAME", "children": [{ "type": "TEXT", "characters": "🔋 87% (CHARGING)" }] },
              { "id": "1:42", "name": "Temp Card", "type": "FRAME", "children": [{ "type": "TEXT", "characters": "🌡️ 38°C (SAFE)" }] }
            ]
          }
        ]
      }
    ]
  }
}
```

---

## 3. ซอร์สโค้ด Jetpack Compose UI (ตรงตามพิมพ์เขียว Figma)

ไฟล์โค้ดสำหรับสร้าง UI บน Android Studio ให้ตรงตาม Figma Blueprint:

```kotlin
// d:\security\MotorcycleAntiTheftSensor\app\src\main\java\com\example\motorcycleantitheftsensor\ui\DashboardScreen.kt

package com.example.motorcycleantitheftsensor.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val DarkBackground = Color(0xFF0D1117)
val CardGlassSurface = Color(0xFF161B22)
val BorderGlass = Color(0xFF30363D)
val ArmedGreen = Color(0xFF10B981)
val AlertRed = Color(0xFFEF4444)
val CyanAccent = Color(0xFF06B6D4)

@Composable
fun DashboardScreen(
    isArmed: Boolean = true,
    batteryPct: Int = 87,
    batteryTemp: Int = 38,
    vibrationValue: Float = 0.12f,
    onToggleArm: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 1. Header Status Badge
        StatusBadgeHeader(isArmed = isArmed)

        // 2. Pulse Arm/Disarm Button
        PulsingArmButton(isArmed = isArmed, onClick = onToggleArm)

        // 3. Motion & Health Metrics
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            VibrationMetricCard(vibrationValue = vibrationValue)
            SystemHealthGrid(batteryPct = batteryPct, batteryTemp = batteryTemp)
        }
    }
}

@Composable
fun StatusBadgeHeader(isArmed: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (isArmed) ArmedGreen.copy(alpha = 0.15f) else AlertRed.copy(alpha = 0.15f))
            .border(1.dp, if (isArmed) ArmedGreen else AlertRed, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = if (isArmed) "🛡️ SYSTEM ARMED" else "⚠️ DISARMED",
            color = if (isArmed) ArmedGreen else AlertRed,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun PulsingArmButton(isArmed: Boolean, onClick: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "scale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(160.dp * if (isArmed) scale else 1.0f)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = if (isArmed) listOf(ArmedGreen, ArmedGreen.copy(alpha = 0.3f))
                             else listOf(AlertRed, AlertRed.copy(alpha = 0.3f))
                )
            )
            .clickable { onClick() }
    ) {
        Text(
            text = if (isArmed) "TAP TO\nDISARM" else "TAP TO\nARM",
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = 16.sp,
            alignment = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
fun VibrationMetricCard(vibrationValue: Float) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = CardGlassSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderGlass)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("MOTION SENSOR INTENSITY", color = Color.Gray, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text("$vibrationValue m/s² (NORMAL)", color = CyanAccent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun SystemHealthGrid(batteryPct: Int, batteryTemp: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(16.dp),
            color = CardGlassSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderGlass)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("BATTERY", color = Color.Gray, fontSize = 11.sp)
                Text("🔋 $batteryPct%", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(16.dp),
            color = CardGlassSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderGlass)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("TEMP GUARD", color = Color.Gray, fontSize = 11.sp)
                Text("🌡️ $batteryTemp°C", color = if (batteryTemp > 45) AlertRed else ArmedGreen, fontWeight = FontWeight.Bold)
            }
        }
    }
}
```
