package com.example.motorcycleantitheftsensor.service

import com.example.motorcycleantitheftsensor.protection.PresentationTextCatalog
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundNotificationPolicyTest {
    @Test
    fun serviceNotificationContractIsSilentOngoingAndVersioned() {
        val spec = ForegroundNotificationSpec()
        assertEquals("anti_theft_protection_silent_v2", spec.channelId)
        assertTrue(spec.silent)
        assertTrue(spec.onlyAlertOnce)
        assertTrue(spec.ongoing)
    }

    @Test
    fun identicalNotificationIsNotRepublished() {
        val policy = ForegroundNotificationPolicy()
        val current = ForegroundNotificationFingerprint("Armed — all required protection healthy", 1)

        assertFalse(policy.shouldPublish(current, current))
    }

    @Test
    fun changedTextOrForegroundTypesIsRepublished() {
        val policy = ForegroundNotificationPolicy()
        val initial = ForegroundNotificationFingerprint("Armed — all required protection healthy", 1)
        val textChanged = ForegroundNotificationFingerprint("Disarmed — remote control online", 1)
        val typeChanged = ForegroundNotificationFingerprint("Armed — all required protection healthy", 9)

        assertTrue(policy.shouldPublish(null, initial))
        assertTrue(policy.shouldPublish(initial, textChanged))
        assertTrue(policy.shouldPublish(initial, typeChanged))
    }

    @Test
    fun notificationTitleIsInstallationNeutralWithoutBrandOrVehicleWording() {
        val title = PresentationTextCatalog.NOTIFICATION_TITLE

        assertTrue(title.isNotBlank())
        assertFalse(title.contains("Motorcycle Guard"))
        assertFalse(title.contains("Guard"))
        listOf("รถยนต์", "รถจักรยานยนต์", "ไฟดับทั้งอาคาร").forEach { banned ->
            assertFalse("title must stay installation-neutral: $title", title.contains(banned))
        }
    }

    @Test
    fun foregroundChannelNameIsNeutralThaiWithoutLegacyBrand() {
        val name = PresentationTextCatalog.FOREGROUND_CHANNEL_NAME

        assertTrue(name.isNotBlank())
        assertFalse(name.contains("Motorcycle Guard"))
    }

    @Test
    fun everyForegroundBodyIsThaiWithoutRawEnumNamesOrLegacyEnglishPhrases() {
        val bannedFragments = listOf(
            "Motorcycle Guard",
            "SETUP_REQUIRED",
            "DISARMED_ONLINE",
            "ARMING",
            "ARMED_HEALTHY",
            "ARMED_DEGRADED",
            "ALERT_ACTIVE",
            "OFFLINE",
            "Setup required",
            "Disarmed —",
            "remote control online",
            "calibrating sensors",
            "all required protection healthy",
            "degraded:",
            "Alert active:",
            "Protection service offline",
        )
        ProtectionState.entries.forEach { state ->
            val body = PresentationTextCatalog.foregroundNotificationBody(state, "x")
            bannedFragments.forEach { fragment ->
                assertFalse("banned '$fragment' leaked into '$body'", body.contains(fragment))
            }
        }
    }

    @Test
    fun foregroundBodiesUseSharedStateLabelsAndTruthfulDetails() {
        assertEquals(
            "การป้องกันทำงานสมบูรณ์",
            PresentationTextCatalog.foregroundNotificationBody(ProtectionState.ARMED_HEALTHY),
        )
        assertEquals(
            "ระบบปิดอยู่ — ควบคุมจากระยะไกลได้",
            PresentationTextCatalog.foregroundNotificationBody(ProtectionState.DISARMED_ONLINE),
        )
        assertEquals(
            "กำลังเปิดระบบและปรับเทียบ อีก 7 วินาที",
            PresentationTextCatalog.foregroundNotificationBody(ProtectionState.ARMING, "7"),
        )
        assertTrue(
            PresentationTextCatalog.foregroundNotificationBody(ProtectionState.ARMED_DEGRADED, "ไมโครโฟน")
                .startsWith("การป้องกันทำงานแบบจำกัด: "),
        )
        assertEquals(
            "พบเหตุการณ์ผิดปกติ",
            PresentationTextCatalog.foregroundNotificationBody(ProtectionState.ALERT_ACTIVE),
        )
        assertTrue(
            PresentationTextCatalog.foregroundNotificationBody(ProtectionState.ALERT_ACTIVE, "INC-9")
                .startsWith("พบเหตุการณ์ผิดปกติ: "),
        )
        assertEquals(
            "ต้องตั้งค่าก่อนใช้งาน: POST_NOTIFICATIONS",
            PresentationTextCatalog.foregroundNotificationBody(ProtectionState.SETUP_REQUIRED, "POST_NOTIFICATIONS"),
        )
        assertEquals(
            "บริการป้องกันออฟไลน์",
            PresentationTextCatalog.foregroundNotificationBody(ProtectionState.OFFLINE),
        )
    }
}
