package com.example.motorcycleantitheftsensor.ui

import com.example.motorcycleantitheftsensor.protection.LightHealthDetail
import com.example.motorcycleantitheftsensor.protection.PowerWitnessCommissioningPolicy
import com.example.motorcycleantitheftsensor.protection.SensorHealth
import com.example.motorcycleantitheftsensor.protection.SensorHealthState
import com.example.motorcycleantitheftsensor.protection.SensorKind
import com.example.motorcycleantitheftsensor.protection.VibrationHealthDetail
import com.example.motorcycleantitheftsensor.security.ProtectionPermissionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionUiTextTest {
    @Test
    fun recordAudioIsOptionalMicrophoneCoverageNotAProtectionBlocker() {
        val permission = ProtectionPermissionPolicy.RECORD_AUDIO
        val readiness = ProtectionPermissionPolicy.readiness(
            missingPermissions = setOf(permission),
            sdkInt = 28,
        )

        assertTrue(readiness.blockers.isEmpty())
        assertEquals(setOf("RECORD_AUDIO unavailable"), readiness.degradations)
        assertEquals("ไมโครโฟน", friendlyPermissionName(permission))
        assertEquals(
            "ยังไม่ได้ให้สิทธิ์ไมโครโฟน การตรวจจับเสียงผิดปกติจะใช้ไม่ได้",
            friendlyPermissionExplanation(permission),
        )
    }

    @Test
    fun notificationPermissionCanRemainATrueProtectionBlocker() {
        val permission = ProtectionPermissionPolicy.POST_NOTIFICATIONS
        val readiness = ProtectionPermissionPolicy.readiness(
            missingPermissions = setOf(permission),
            sdkInt = 33,
        )

        assertEquals(setOf("POST_NOTIFICATIONS"), readiness.blockers)
        assertEquals("การแจ้งเตือน", friendlyPermissionName(permission))
        assertEquals(
            "ยังไม่ได้ให้สิทธิ์การแจ้งเตือน การแจ้งเหตุอาจไม่แสดง",
            friendlyPermissionExplanation(permission),
        )
    }

    @Test
    fun locationPermissionUsesThaiNameAndPlainExplanation() {
        val permission = ProtectionPermissionPolicy.ACCESS_FINE_LOCATION
        assertEquals("ตำแหน่ง", friendlyPermissionName(permission))
        assertEquals(
            "ยังไม่ได้ให้สิทธิ์ตำแหน่ง การติดตามตำแหน่งจะใช้ไม่ได้",
            friendlyPermissionExplanation(permission),
        )
    }

    @Test
    fun smsPermissionUsesThaiNameAndPlainExplanation() {
        val permission = ProtectionPermissionPolicy.SEND_SMS
        assertEquals("SMS", friendlyPermissionName(permission))
        assertEquals(
            "ยังไม่ได้ให้สิทธิ์ SMS ช่องทางสำรองผ่าน SMS จะใช้ไม่ได้",
            friendlyPermissionExplanation(permission),
        )
    }

    @Test
    fun unknownPermissionFallsBackToNeutralThaiWithoutRawPackage() {
        val permission = "android.permission.FOO_BAR"
        val name = friendlyPermissionName(permission)
        val explanation = friendlyPermissionExplanation(permission)

        assertEquals("สิทธิ์อื่น", name)
        assertEquals(
            "ยังไม่ได้ให้สิทธิ์นี้ ฟีเจอร์บางอย่างอาจใช้ไม่ได้",
            explanation,
        )
        assertFalse(name.contains("FOO_BAR"))
        assertFalse(explanation.contains("FOO_BAR"))
        assertFalse(explanation.contains("android.permission"))
    }

    @Test
    fun powerCalibrationFailureTextFollowsTheActualReason() {
        assertEquals(
            "เครื่องนี้ไม่มีเซนเซอร์แสง — โหมดไฟเลี้ยงต้องใช้ไฟยืนยัน จึงใช้งานไม่ได้",
            powerCommissioningFailureText(PowerCommissioningFailure.NO_LIGHT_SENSOR),
        )
        assertEquals(
            "ไม่ได้รับค่าแสงจากเซนเซอร์ — ลองรีสตาร์ทเครื่องแล้วปรับเทียบใหม่",
            powerCommissioningFailureText(PowerCommissioningFailure.NO_LIGHT_SAMPLES),
        )
        assertEquals(
            "ช่วงแสงไม่แยกกันพอ — ตรวจสอบฝาครอบแล้วเริ่มใหม่",
            powerCommissioningFailureText(PowerWitnessCommissioningPolicy.REJECTION_NOT_SEPARATED),
        )
        assertEquals(
            "บันทึกค่าปรับเทียบไม่สำเร็จ ลองใหม่อีกครั้ง",
            powerCommissioningFailureText(PowerCommissioningFailure.SAVE_FAILED),
        )
        // An unrecognised reason must not send the owner to inspect the lamp hood.
        assertEquals(
            "ปรับเทียบไม่สำเร็จ กรุณาลองใหม่",
            powerCommissioningFailureText("something-nobody-mapped-yet"),
        )
    }

    @Test
    fun idleSensorRowAdmitsMissingHardwareInsteadOfPromisingReadings() {
        val missingLight = SensorHealth(
            state = SensorHealthState.UNAVAILABLE,
            lightDetail = LightHealthDetail(hardwareSupported = false),
        )
        assertEquals(
            "ไม่พบเซนเซอร์แสงบนเครื่องนี้",
            idleSensorRowText(SensorKind.LIGHT, missingLight),
        )

        val presentLight = SensorHealth(
            state = SensorHealthState.AVAILABLE,
            lightDetail = LightHealthDetail(hardwareSupported = true),
        )
        assertEquals(
            "จะเริ่มอ่านค่าหลังเปิดการป้องกัน",
            idleSensorRowText(SensorKind.LIGHT, presentLight),
        )

        val missingAccelerometer = SensorHealth(
            state = SensorHealthState.UNAVAILABLE,
            vibrationDetail = VibrationHealthDetail(hardwareAvailable = false),
        )
        assertEquals(
            "ไม่พบเซนเซอร์ความเคลื่อนไหวบนเครื่องนี้",
            idleSensorRowText(SensorKind.VIBRATION, missingAccelerometer),
        )

        // Nothing known yet is not the same as hardware that is absent.
        assertEquals(
            "จะเริ่มอ่านค่าหลังเปิดการป้องกัน",
            idleSensorRowText(SensorKind.LIGHT, null),
        )
    }
}
