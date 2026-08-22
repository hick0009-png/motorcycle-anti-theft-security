package com.example.motorcycleantitheftsensor.protection

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PresentationTextCatalog {

    const val NEUTRAL_INCIDENT_GUIDANCE = "ตรวจสอบสถานการณ์และดำเนินการตามความเหมาะสม"

    fun capabilityName(capability: SensorCapability): String = when (capability) {
        SensorCapability.MOVEMENT -> "การเคลื่อนไหว"
        SensorCapability.ROTATION -> "การหมุนและเอียง"
        SensorCapability.MAGNETIC -> "สนามแม่เหล็ก"
        SensorCapability.LIGHT -> "แสงใต้เบาะ"
        SensorCapability.PROXIMITY -> "ระยะประชิด"
    }

    fun sourceName(source: SensorSource): String = when (source) {
        SensorSource.SIGNIFICANT_MOTION -> "ตรวจจับการเคลื่อนไหวหลัก"
        SensorSource.ACCELEROMETER -> "เซ็นเซอร์วัดความเร่ง"
        SensorSource.LINEAR_ACCELERATION -> "เซ็นเซอร์วัดความเร่งเชิงเส้น"
        SensorSource.GYROSCOPE -> "ไจโรสโคป"
        SensorSource.ROTATION_VECTOR -> "เวกเตอร์การหมุน 3 มิติ"
        SensorSource.GAME_ROTATION_VECTOR -> "เวกเตอร์การหมุนสัมพัทธ์"
        SensorSource.MAGNETIC_FIELD -> "เซ็นเซอร์วัดสนามแม่เหล็ก"
        SensorSource.GEOMAGNETIC_ROTATION_VECTOR -> "เวกเตอร์สนามแม่เหล็กโลก"
        SensorSource.AMBIENT_LIGHT -> "เซ็นเซอร์วัดแสง"
        SensorSource.PROXIMITY -> "เซ็นเซอร์วัดระยะประชิด"
    }

    fun roleName(role: SensorRole): String = when (role) {
        SensorRole.PRIMARY -> "เซ็นเซอร์หลัก"
        SensorRole.SUPPORTING -> "เซ็นเซอร์สนับสนุน"
        SensorRole.OFF -> "ปิดใช้งาน"
    }

    fun presetName(preset: SensorPresetDisplay): String = when (preset) {
        SensorPresetDisplay.BATTERY_SAVER -> "ประหยัดแบตเตอรี่"
        SensorPresetDisplay.BALANCED -> "สมดุล (แนะนำ)"
        SensorPresetDisplay.MAXIMUM_PROTECTION -> "ป้องกันสูงสุด"
        SensorPresetDisplay.CUSTOM -> "กำหนดเอง"
    }

    fun incidentTitle(type: IncidentType): String = when (type) {
        IncidentType.VIBRATION -> "🚨 รถอาจถูกเคลื่อนย้าย"
        IncidentType.TAMPER -> "⚠️ พบการงัดแงะหรือเปิดเบาะ"
        IncidentType.POWER -> "🔌 แหล่งจ่ายไฟถูกตัด"
        IncidentType.THERMAL -> "🌡️ อุณหภูมิผิดปกติ"
        IncidentType.AUDIO -> "🔊 เสียงผิดปกติรอบตัวรถ"
    }

    fun severityLabel(severity: IncidentSeverity): String = when (severity) {
        IncidentSeverity.WARNING -> "เตือนภัย"
        IncidentSeverity.CRITICAL -> "วิกฤต"
    }

    fun protectionStateLabel(state: ProtectionState): String = when (state) {
        ProtectionState.SETUP_REQUIRED -> "ต้องตั้งค่าเริ่มต้น"
        ProtectionState.DISARMED_ONLINE -> "ระบบปิดอยู่"
        ProtectionState.ARMING -> "กำลังเปิดระบบและปรับเทียบ"
        ProtectionState.ARMED_HEALTHY -> "การป้องกันทำงานสมบูรณ์"
        ProtectionState.ARMED_DEGRADED -> "การป้องกันทำงานแบบจำกัด"
        ProtectionState.ALERT_ACTIVE -> "พบเหตุการณ์ผิดปกติ"
        ProtectionState.OFFLINE -> "ออฟไลน์"
    }

    fun formatEvidence(evidence: IncidentEvidence): IncidentEvidencePresentation {
        val src = evidence.source
        val label = if (src != null) sourceName(src) else capabilityName(evidence.capability ?: SensorCapability.MOVEMENT)
        val desc = when (evidence.unit) {
            SensorUnit.METERS_PER_SECOND_SQUARED -> "ตรวจพบแรงสั่นต่อเนื่อง (${String.format(Locale.US, "%.1f", evidence.baselineDelta)} m/s²)"
            SensorUnit.DEGREES -> "มุมของรถเปลี่ยนประมาณ ${String.format(Locale.US, "%.1f", evidence.baselineDelta)}°"
            SensorUnit.RADIANS_PER_SECOND -> "การหมุนความเร็ว ${String.format(Locale.US, "%.2f", evidence.normalizedValue)} rad/s"
            SensorUnit.MICROTESLA -> "สนามแม่เหล็กรอบรถเปลี่ยนจากค่าตอนเปิดระบบ (${String.format(Locale.US, "%.1f", evidence.baselineDelta)} µT)"
            SensorUnit.LUX_RATIO -> "แสงใต้เบาะเพิ่มขึ้นจากค่าตอนเปิดระบบ"
            SensorUnit.NORMALIZED_STATE -> "สถานะวัตถุใกล้โทรศัพท์เปลี่ยนจาก ใกล้ เป็น ไกล"
            SensorUnit.TRIGGER -> "เซ็นเซอร์ตรวจพบการเคลื่อนไหวของตัวรถ"
            null -> "ตรวจพบสัญญาณความผิดปกติ"
        }
        return IncidentEvidencePresentation(
            label = label,
            valueDescription = desc,
        )
    }

    fun formatTimestamp(epochMs: Long): String {
        val sdf = SimpleDateFormat("d MMM yyyy HH:mm", Locale("th", "TH"))
        return sdf.format(Date(epochMs))
    }
}
