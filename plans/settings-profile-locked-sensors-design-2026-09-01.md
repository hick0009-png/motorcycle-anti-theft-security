# Design — ล็อกเซ็นเซอร์ที่ไม่เกี่ยวข้องในหน้า "เซ็นเซอร์ขั้นสูง" ตามโหมดการใช้งาน (2026-09-01)

สถานะ: **ข้อเสนอการออกแบบ ยังไม่ได้ลงมือแก้โค้ด**
ขอบเขต: `ui/settings/SettingsScreen.kt`, `ui/ProtectionUiModels.kt`,
`protection/ProtectionProfilePolicy.kt`, `protection/PresentationTextCatalog.kt`

---

## 1. สิ่งที่เป็นอยู่จริงตอนนี้ (ตรวจจากโค้ด)

คำตอบของคำถาม "ตอนอยู่โหมดไฟเลี้ยง ตั้งค่าเซ็นเซอร์จุดอื่นได้อยู่ไหม" คือ **ได้ และแย่กว่าที่คิด**

1. `SettingsScreen.kt:544` วน `SensorCapability.entries` ทั้ง 5 กลุ่ม และ
   `SettingsScreen.kt:645` วน `SensorSource.entries` ทั้ง 10 ตัว **โดยไม่ดูโหมดเลย**
   ทั้งสไลเดอร์ความไว ปุ่ม preset และปุ่มบทบาท หลัก/ประกอบ/ปิด กดได้หมดในโหมดไฟเลี้ยง
2. โดเมนกลับกันไว้อยู่แล้วอย่างเงียบ ๆ — `ProtectionProfilePolicy.lockedOffSources()`
   (`ProtectionProfilePolicy.kt:213`) บังคับทุก source ที่ไม่ใช่ `AMBIENT_LIGHT` ให้เป็น
   `SensorRole.OFF` ตอน `resolve()` สำหรับโปรไฟล์ `POWER`
3. ที่หนักกว่านั้น: ค่าที่หน้าตั้งค่าอ่าน/เขียนคือ **config กลางตัวเก่า** จาก
   `AndroidProtectionSettingsGateway.read()` → `operations.getSensorConfiguration()`
   ซึ่งไม่ใช่ config ที่โปรไฟล์ใช้จริง

สรุปอาการที่เจ้าของเครื่องเห็น: ปรับได้ กดได้ เซฟผ่าน ขึ้น "รูปแบบ: กำหนดเอง" —
แต่ไม่มีผลอะไรกับการตรวจจับในโหมดไฟเลี้ยงแม้แต่นิดเดียว **UI โกหกผู้ใช้**
สิ่งที่ต้องแก้จึงไม่ใช่แค่ "ทำให้จาง" แต่คือทำให้หน้าจอพูดความจริงตรงกับโดเมน

---

## 2. หลักการออกแบบ

| # | หลักการ | เหตุผล |
|---|---|---|
| P1 | **ล็อก ไม่ใช่ซ่อน** | เจ้าของต้องเห็นว่ามีเซ็นเซอร์เหล่านี้อยู่ และเห็นว่าโหมดนี้ปิดมันไว้ ถ้าซ่อนหมดจะกลายเป็น "ของหาย" และเดาไม่ออกว่าทำไมโหมดไฟเลี้ยงกินแบตน้อยลง |
| P2 | **จางแล้วต้องกดไม่ได้จริง** | จางอย่างเดียวคือกับดัก ผู้ใช้จะกดแล้วสงสัยว่าพัง ทุกตัวควบคุมที่จางต้อง `enabled = false` + `semantics { disabled() }` |
| P3 | **คำอธิบายห้ามจาง** | ตัวควบคุมจางที่ alpha 0.38 ได้ (WCAG ยกเว้น disabled control) แต่ชิป 🔒 และบรรทัดเหตุผล **ต้องคอนทราสต์เต็ม** ไม่งั้นข้อความที่สำคัญที่สุดกลายเป็นอ่านไม่ออก — ขัดกับ header comment ของไฟล์ที่รับปาก AA ไว้ |
| P4 | **UI ห้ามคิดเองว่าเซ็นเซอร์ไหนเกี่ยว** | ตามคอนเวนชันในไฟล์ ("Readiness is never inferred in Compose") รายชื่อที่ล็อกต้องมาจาก `ProtectionProfilePolicy` ตัวเดียว ไม่ใช่ `when (profile)` ที่ลอกไว้ใน Compose |
| P5 | **บอกทางออก** | ทุกที่ที่ล็อก ต้องมีทางไป "เปลี่ยนการใช้งาน" ไม่ใช่ปิดตายแล้วปล่อยผู้ใช้ค้าง |

---

## 3. ชั้นโดเมน — เปิดข้อเท็จจริงให้ UI ถามได้

ใน `ProtectionProfilePolicy` ย้าย logic ที่มีอยู่แล้วขึ้นมาเป็น API สาธารณะ
(ไม่เปลี่ยนพฤติกรรม แค่เปิดให้ query ได้):

```kotlin
companion object {
    /** Sources ที่โปรไฟล์ปักไว้ที่ OFF ตามสัญญาการตรวจจับ — override ใด ๆ ยกกลับไม่ได้ */
    fun lockedSources(profile: ProtectionProfile): Set<SensorSource> = when (profile) {
        ProtectionProfile.POWER -> SensorSource.entries.toSet() - SensorSource.AMBIENT_LIGHT
        ProtectionProfile.VEHICLE, ProtectionProfile.ENTRY -> emptySet()
    }

    /** กลุ่มที่ทุก source ข้างในถูกล็อก → ทั้งการ์ดล็อกรวมถึงสไลเดอร์ความไว */
    fun lockedCapabilities(profile: ProtectionProfile): Set<SensorCapability> =
        SensorCapability.entries.filter { cap ->
            SensorSource.entries.filter { it.capability == cap }
                .all { it in lockedSources(profile) }
        }.toSet()

    /** preset สมดุล/ประหยัด/สูงสุด ไม่มีความหมายเมื่อโปรไฟล์กำหนดบทบาทเองทั้งหมด */
    fun presetSelectable(profile: ProtectionProfile): Boolean =
        lockedSources(profile).isEmpty()
}
```

`lockedOffSources()` ตัวเดิม (private) ให้เรียกต่อจาก `lockedSources()` เพื่อไม่ให้มีความจริงสองชุด

สำหรับ POWER ผลลัพธ์คือ:
- ล็อก 9 sources: SIGNIFICANT_MOTION, ACCELEROMETER, LINEAR_ACCELERATION, GYROSCOPE,
  ROTATION_VECTOR, GAME_ROTATION_VECTOR, MAGNETIC_FIELD, GEOMAGNETIC_ROTATION_VECTOR, PROXIMITY
- ล็อก 4 capabilities: MOVEMENT, ROTATION, MAGNETIC, PROXIMITY
- เหลือแก้ได้: LIGHT / AMBIENT_LIGHT เท่านั้น

---

## 4. ชั้น read-model — projection ตัวเดียวส่งเข้า Compose

เพิ่มใน `ProtectionUiModels.kt`:

```kotlin
data class SensorEditabilityUiModel(
    val profile: ProtectionProfile? = null,
    val lockedSources: Set<SensorSource> = emptySet(),
    val lockedCapabilities: Set<SensorCapability> = emptySet(),
    val presetSelectable: Boolean = true,
    val noticeTh: String? = null,        // แบนเนอร์หัวการ์ด
    val rowReasonTh: String? = null,     // เหตุผลต่อแถว
) {
    val anyLocked: Boolean get() = lockedSources.isNotEmpty()
    fun isLocked(source: SensorSource) = source in lockedSources
    fun isLocked(capability: SensorCapability) = capability in lockedCapabilities
}
```

สร้างใน `ProtectionUiState.from(...)` (ที่นั่นมี `profile: ProtectionProfileUiState` อยู่แล้ว)
แล้วเพิ่มเป็นฟิลด์ `val sensorEditability: SensorEditabilityUiModel` ของ `ProtectionUiState`
— Compose ไม่ต้อง derive อะไรเลย

ข้อความจาก `PresentationTextCatalog` (ใช้เหตุผลที่ KDoc ของ policy เขียนไว้อยู่แล้ว):

```
noticeTh (POWER):
  "โหมดไฟเลี้ยงใช้เฉพาะเซ็นเซอร์แสง (ไฟยืนยัน) และสถานะการชาร์จ
   เซ็นเซอร์อื่นถูกล็อกไว้ในโหมดนี้ ปรับค่าไม่ได้"

rowReasonTh (POWER):
  "โหมดไฟเลี้ยงไม่ใช้เซ็นเซอร์นี้ — การขยับตอนถอด/เสียบสายชาร์จ
   จะเปิดเหตุการณ์ซ้อนกับเหตุการณ์ไฟเลี้ยง"
```

---

## 5. ชั้น UI — หน้าตาที่ออกแบบ

### 5.1 โทเคนใหม่ในไฟล์ (ต่อจากชุดสีเดิม)

```kotlin
private const val LockedContentAlpha = 0.38f   // ค่าเดียวกับ M3 disabled content
private val LockedChipSurface = Color(0xFFE9E9E6)
```

### 5.2 แบนเนอร์หัวการ์ด (แสดงเมื่อ `anyLocked`)

อยู่บนสุดใน `SettingsCard(title = "เซ็นเซอร์ขั้นสูงและบทบาทการตรวจจับ")`
คอนทราสต์เต็ม ไม่จาง (P3)

```
┌────────────────────────────────────────────────┐
│ 🔒  โหมดไฟเลี้ยงใช้เฉพาะเซ็นเซอร์แสง (ไฟยืนยัน)      │
│     และสถานะการชาร์จ เซ็นเซอร์อื่นถูกล็อกไว้          │
│     ในโหมดนี้ ปรับค่าไม่ได้                          │
│                              [ เปลี่ยนการใช้งาน ]   │
└────────────────────────────────────────────────┘
```

ปุ่ม "เปลี่ยนการใช้งาน" = `actions.selectDestination(ProtectionDestination.PROTECTION)`
(action มีอยู่แล้ว ไม่ต้องเดินสายใหม่) ไปโผล่ที่ปุ่มเปลี่ยนโหมดบนหน้าปกป้อง

### 5.3 แถว preset

เมื่อ `!presetSelectable` → ไม่แสดงปุ่มสามตัว แต่แสดงชิปอ่านอย่างเดียว:

```
รูปแบบการทำงาน (Preset):
[ กำหนดโดยโหมดไฟเลี้ยง ]        ← ชิป อ่านอย่างเดียว ไม่จาง
```

**สำคัญ:** ต้องซ่อนป้าย "รูปแบบ: กำหนดเอง" ด้วย เพราะ config ของ POWER
ไม่มีทางตรงกับ preset ใดเลย ป้าย CUSTOM จะค้างตลอดและอ่านเหมือนระบบเพี้ยน

### 5.4 การ์ดกลุ่ม capability ที่ถูกล็อก

โครงเดิมทุกอย่าง เปลี่ยนแค่:

```kotlin
val locked = editability.isLocked(capability)

Card(
    colors = CardDefaults.cardColors(
        containerColor = if (locked) RowSurface.copy(alpha = 0.28f)
                         else RowSurface.copy(alpha = 0.5f),
    ),
    ...
) {
    Column(...) {
        // หัวเรื่อง + ชิปล็อก อยู่แถวเดียวกัน  ← ไม่จาง
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            Text(capName, ...)
            if (locked) LockChip()          // 🔒 ล็อกโดยโหมด
        }
        if (locked) {
            Text(editability.rowReasonTh!!, style = bodySmall, color = onSurfaceVariant)
        }
        // ส่วนที่ปรับค่าได้ → จาง + ปิดจริง
        Column(modifier = Modifier.alpha(if (locked) LockedContentAlpha else 1f)) {
            Text("ความไวการตรวจจับ") ...
            AccessibleSensitivitySlider(
                enabled = !state.settingsOperationInFlight && !locked,   // ← จุดเดียวจบ
                ...
            )
        }
    }
}
```

`AccessibleSensitivitySlider` รองรับ `enabled` ครบอยู่แล้ว (`SettingsScreen.kt:1313-1330`) —
มี `disabled()` ใน semantics, ตัด `setProgress` และตัด `pointerInput` ให้เอง
ไม่ต้องแก้ composable นี้เลย

`LockChip()` เป็น composable เล็ก ๆ ตัวใหม่ในไฟล์นี้:

```kotlin
@Composable
private fun LockChip(label: String = "ล็อกโดยโหมด") {
    Surface(color = LockedChipSurface, shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, BorderNeutral)) {
        Text("🔒 $label", style = labelSmall.copy(fontWeight = SemiBold),
             color = MaterialTheme.colorScheme.onSurfaceVariant,
             modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}
```

### 5.5 ไดอะล็อก "กำหนดบทบาทเซ็นเซอร์ขั้นสูง"

ปุ่มเปิดไดอะล็อก **ยังกดได้ตามปกติ** — เจ้าของควรเข้าไปดูได้ว่าอะไรถูกปิดไว้บ้าง
ข้างในจัดใหม่เป็นสองส่วน:

```
เซ็นเซอร์ที่โหมดนี้ใช้ (1)
┌──────────────────────────────┐
│ เซ็นเซอร์แสง                    │
│ กลุ่ม: แสง                      │
│ [ หลัก ] [ ประกอบ ] [ ปิด ]      │  ← กดได้ปกติ
└──────────────────────────────┘

▸ ถูกล็อกในโหมดไฟเลี้ยง (9)          ← พับไว้ ค่าเริ่มต้น = ปิด
```

กางออกแล้วแต่ละแถว:

```
┌──────────────────────────────┐
│ มาตรวัดความเร่ง     🔒 ล็อกโดยโหมด │  ← ไม่จาง
│ โหมดไฟเลี้ยงไม่ใช้เซ็นเซอร์นี้ —      │  ← ไม่จาง
│ การขยับตอนถอด/เสียบสายชาร์จ       │
│ จะเปิดเหตุการณ์ซ้อนกับไฟเลี้ยง       │
│ ┌ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┐  │
│ │ [หลัก] [ประกอบ] [ ปิด ✓ ] │  │  ← alpha .38 + enabled=false
│ └ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┘  │
└──────────────────────────────┘
```

จุดที่ต้องระวังในการเขียน: แถวปุ่มบทบาทเดิมแยกทางเป็น `Button` (เลือกอยู่) กับ
`OutlinedButton` (ยังไม่เลือก) และตัวที่เลือกอยู่ใช้ `onClick = {}` อยู่แล้ว
เวลาล็อก **ต้องใส่ `enabled = false` ทั้งสามปุ่ม** ไม่ใช่แค่ปล่อย `onClick` ว่าง
มิฉะนั้น TalkBack ยังประกาศว่ากดได้ (P2) และปุ่มที่เลือกอยู่ยังคงสีทึบ `StatusRed`
ให้ลดเป็นสีจางตามการ์ด

การพับกลุ่มที่ล็อก: `var lockedExpanded by rememberSaveable { mutableStateOf(false) }`
นี่คือส่วนที่ตอบโจทย์ "ปิดลาง ๆ" ตรงที่สุด — เห็นว่ามีอยู่ แต่ไม่เกะกะ

### 5.6 Accessibility

ทุกตัวควบคุมที่ล็อก:

```kotlin
Modifier.semantics {
    disabled()
    contentDescription = "$srcName ล็อกโดยโหมดไฟเลี้ยง ตั้งค่าไม่ได้"
}
```

การทำจางเป็นสัญญาณทางสายตาล้วน ๆ — screen reader มองไม่เห็น ถ้าไม่ใส่ `disabled()`
ผู้ใช้ TalkBack จะเจอปุ่มที่ประกาศว่ากดได้แต่ไม่มีอะไรเกิดขึ้น
(`disabled` import มีอยู่ในไฟล์แล้วที่บรรทัด 56)

---

## 6. Defense in depth — อย่าพึ่ง UI อย่างเดียว

การล็อกที่ UI เป็นชั้นแรกเท่านั้น ให้เพิ่มอีกสองชั้น:

1. **ชั้นบันทึก:** ก่อนเรียก `actions.updateSensorConfiguration(...)` ให้ normalize
   source ที่ล็อกกลับเป็น `OFF` เสมอ ป้องกันกรณี config ค้างจากเวอร์ชันเก่า
   หรือจากการ restore ที่ทำให้ UI แสดงค่าที่ยกขึ้นมาแล้ว
2. **ชั้นโดเมน:** `lockedOffSources` ใน `resolve()` คงไว้ตามเดิม (มีแล้ว)

---

## 7. ราก — หน้าตั้งค่ายังเขียนลง config กลางตัวเก่า (แนะนำทำเป็นเฟส 2)

แม้จะทำข้อ 3-6 ครบ ยังเหลือข้อบกพร่องเชิงโครงสร้าง:
`AndroidProtectionSettingsGateway.read()` อ่าน config กลาง ไม่ใช่ผลของ
`ProtectionProfilePolicy.resolve(state, selectedProfile)` แปลว่า

- ค่าที่หน้านี้แสดง ไม่ใช่ค่าที่โปรไฟล์ที่เลือกอยู่ใช้จริง แม้ในโหมด VEHICLE/ENTRY
- แก้ในโหมดหนึ่งแล้วไปโผล่อีกโหมดหนึ่ง

ทางแก้ที่ถูกต้อง: ให้หน้าเซ็นเซอร์ขั้นสูงอ่านจาก `resolve()` และเขียนผ่าน
`ProtectionProfilePolicy.updateProfile()` (เก็บเป็น `sensorOverrides` ของโปรไฟล์)
แทน `SensorConfigurationRepository` ตัวกลาง

เป็นงานคนละก้อน ควรแยก commit ออกจากงานล็อก UI นี้ แต่ต้องบันทึกไว้ —
ถ้าไม่ทำ ข้อ 3-6 จะ "ล็อกถูกที่ แต่ยังโชว์ตัวเลขผิดชุด"

---

## 8. แผนทดสอบ

| ระดับ | เทสต์ |
|---|---|
| `ProtectionProfilePolicyTest` | `lockedSources(POWER)` = 9 ตัว ไม่รวม AMBIENT_LIGHT; `lockedSources(VEHICLE/ENTRY)` ว่าง; `lockedCapabilities(POWER)` = MOVEMENT, ROTATION, MAGNETIC, PROXIMITY |
| model test | `ProtectionUiState.from(profile = POWER).sensorEditability.presetSelectable` = false, `noticeTh != null`; VEHICLE ได้ค่าตรงข้าม |
| Compose UI test | ในโหมด POWER: `onNodeWithTag("ui.settings.sensor.source.ACCELEROMETER.role.PRIMARY").assertIsNotEnabled()` และกดแล้ว fake action ต้องไม่ถูกเรียก; สไลเดอร์ MOVEMENT `assertIsNotEnabled()`; ในโหมด VEHICLE ทั้งคู่ enabled |
| Compose UI test | แบนเนอร์ล็อกแสดงเฉพาะ POWER; กด "เปลี่ยนการใช้งาน" แล้ว `selectDestination(PROTECTION)` ถูกเรียก |

ต้องเพิ่ม test tag ใหม่ตามแบบเดิมของไฟล์ (`ui.settings.*`):
`ui.settings.sensor.LOCK_BANNER`, `ui.settings.sensor.capability.<NAME>.SLIDER`,
`ui.settings.sensor.source.<NAME>.role.<ROLE>`, `ui.settings.sensor.LOCKED_GROUP_TOGGLE`

---

## 9. ลำดับการลงมือ

1. `ProtectionProfilePolicy` — เปิด `lockedSources` / `lockedCapabilities` / `presetSelectable` + เทสต์
2. `PresentationTextCatalog` — ข้อความ notice/reason ต่อโปรไฟล์
3. `ProtectionUiModels` — `SensorEditabilityUiModel` + derive ใน `from()` + เทสต์
4. `SettingsScreen` — แบนเนอร์, ชิปล็อก, preset อ่านอย่างเดียว, การ์ดจาง+ปิด, ไดอะล็อกสองส่วนพับได้, test tags
5. normalize ก่อนเซฟ (ข้อ 6.1)
6. Compose UI tests
7. (เฟส 2 แยก commit) เดินสายหน้าเซ็นเซอร์ขั้นสูงไปที่ profile overrides ตามข้อ 7
