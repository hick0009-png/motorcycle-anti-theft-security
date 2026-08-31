package com.example.motorcycleantitheftsensor.ui

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
}
