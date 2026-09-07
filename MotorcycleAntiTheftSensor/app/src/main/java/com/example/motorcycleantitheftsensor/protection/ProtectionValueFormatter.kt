package com.example.motorcycleantitheftsensor.protection

import java.text.NumberFormat
import java.util.Locale

/**
 * Bounded, locale-aware formatting for owner-facing values (Task 1 of the profile-aware
 * Thai UX plan). Durations at or above one second render in seconds; millisecond
 * precision appears only below that threshold. Proximity stays near/far semantic and is
 * never rendered as centimetres. Advanced units are returned together with a one-sentence
 * Thai interpretation via [FormattedMeasurement].
 */
object ProtectionValueFormatter {

    private val numberFormat: NumberFormat = NumberFormat.getNumberInstance(Locale("th", "TH")).apply {
        isGroupingUsed = true
        maximumFractionDigits = 1
        minimumFractionDigits = 0
    }

    fun temperatureCelsius(celsius: Double): String = "${format(celsius)} °C"

    fun duration(millis: Long): String =
        if (millis >= 1_000) {
            "${format(millis / 1_000.0)} วินาที"
        } else {
            "${format(millis.toDouble())} มิลลิวินาที"
        }

    fun lux(luxValue: Double): FormattedMeasurement = FormattedMeasurement(
        value = "${format(luxValue)} lux",
        interpretation = "ความสว่างบริเวณจุดติดตั้ง ค่ายิ่งมากยิ่งสว่าง",
    )

    fun measurement(value: Double, unit: SensorUnit): FormattedMeasurement = when (unit) {
        SensorUnit.METERS_PER_SECOND_SQUARED -> FormattedMeasurement(
            value = "${format(value)} m/s²",
            interpretation = "ขนาดความเร่งที่วัดได้ ใช้ประกอบการยืนยันการสั่นหรือกระแทก",
        )
        SensorUnit.RADIANS_PER_SECOND -> FormattedMeasurement(
            value = "${format(value)} rad/s",
            interpretation = "อัตราการหมุนที่วัดได้ ใช้ประกอบการยืนยันการหมุนหรือเอียง",
        )
        SensorUnit.MICROTESLA -> FormattedMeasurement(
            value = "${format(value)} µT",
            interpretation = "การเปลี่ยนแปลงของสนามแม่เหล็ก ใช้ประกอบการยืนยันและอาจได้รับผลกระทบจากสนามแม่เหล็กรอบข้าง",
        )
        SensorUnit.DEGREES -> FormattedMeasurement(
            value = "${format(value)}°",
            interpretation = "มุมเทียบตำแหน่งอ้างอิงที่ตั้งค่าไว้",
        )
        SensorUnit.LUX_RATIO -> FormattedMeasurement(
            value = format(value),
            interpretation = "อัตราส่วนความสว่างเทียบกับค่าตอนเปิดระบบ",
        )
        SensorUnit.NORMALIZED_STATE -> FormattedMeasurement(
            value = format(value),
            interpretation = "สถานะเชิงตรรกะของเซ็นเซอร์ เช่น ใกล้หรือไกล",
        )
        SensorUnit.TRIGGER -> FormattedMeasurement(
            value = format(value),
            interpretation = "สัญญาณแจ้งเหตุจากเซ็นเซอร์ฮาร์ดแวร์",
        )
    }

    fun audioDbfs(dbfs: Double): FormattedMeasurement = FormattedMeasurement(
        value = "${format(dbfs)} dBFS",
        interpretation = "ระดับเสียงที่วัดได้ ค่ายิ่งติดลบมากยิ่งเงียบ",
    )

    private fun format(value: Double): String = numberFormat.format(value)
}