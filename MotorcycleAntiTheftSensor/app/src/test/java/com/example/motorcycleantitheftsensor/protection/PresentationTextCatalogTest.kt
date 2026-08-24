package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentationTextCatalogTest {

    @Test
    fun allCapabilitiesHaveNonEmptyThaiNames() {
        SensorCapability.entries.forEach { capability ->
            val name = PresentationTextCatalog.capabilityName(capability)
            assertNotNull(name)
            assertTrue(name.isNotBlank())
        }
    }

    @Test
    fun allSourcesHaveNonEmptyThaiNames() {
        SensorSource.entries.forEach { source ->
            val name = PresentationTextCatalog.sourceName(source)
            assertNotNull(name)
            assertTrue(name.isNotBlank())
        }
    }

    @Test
    fun neutralIncidentGuidanceMatchesApprovedConstant() {
        assertEquals("ตรวจสอบสถานการณ์และดำเนินการตามความเหมาะสม", PresentationTextCatalog.NEUTRAL_INCIDENT_GUIDANCE)
    }

    @Test
    fun smsFormattingNeverContainsLocation() {
        val incident = SecurityIncident(
            id = "INC-TEST-001",
            type = IncidentType.VIBRATION,
            severity = IncidentSeverity.CRITICAL,
            lifecycle = IncidentLifecycle.OPEN,
            evidence = emptyList(),
            openedAtMs = 1787245200000L,
            updatedAtMs = 1787245200000L,
            closedAtMs = null,
            protectionState = ProtectionState.ARMED_HEALTHY,
            deliveryState = DeliveryState.SENT,
            location = IncidentLocation(13.7563, 100.5018, 10f, 1787245200000L),
        )

        val presentation = IncidentMessagePresentationFactory.create(incident)
        val sms = IncidentMessagePresentationFactory.formatSmsMessage(presentation)

        assertFalse("SMS fallback must never leak GPS coordinates", sms.contains("13.7563"))
        assertFalse("SMS fallback must never leak GPS coordinates", sms.contains("100.5018"))
        assertTrue(sms.contains("INC-TEST-001"))
        assertTrue(sms.contains("วิกฤต"))
    }

    @Test
    fun profilesMatchApprovedCardNamesAndPromises() {
        assertEquals("ยานพาหนะ", PresentationTextCatalog.profile(ProtectionProfile.VEHICLE).name)
        assertEquals(
            "แจ้งเตือนเมื่อรถยนต์หรือรถจักรยานยนต์ถูกกระทบ ขยับ หรือเคลื่อนย้าย",
            PresentationTextCatalog.profile(ProtectionProfile.VEHICLE).promise,
        )
        assertEquals("ประตูและทางเข้า", PresentationTextCatalog.profile(ProtectionProfile.ENTRY).name)
        assertEquals(
            "แจ้งเตือนเมื่อประตูที่ติดตั้งโทรศัพท์ไว้เปิดเกินมุมที่กำหนด หรือเกิดแรงกระแทก",
            PresentationTextCatalog.profile(ProtectionProfile.ENTRY).promise,
        )
        assertEquals("ไฟเลี้ยงจุดติดตั้ง", PresentationTextCatalog.profile(ProtectionProfile.POWER).name)
        assertEquals(
            "เฝ้าระวังสายชาร์จและไฟยืนยันของปลั๊กหรือรางไฟที่ตั้งค่าไว้",
            PresentationTextCatalog.profile(ProtectionProfile.POWER).promise,
        )
    }

    @Test
    fun vehicleMovementIsThePrimaryAdjustableControl() {
        val presentation = PresentationTextCatalog.capability(ProtectionProfile.VEHICLE, SensorCapability.MOVEMENT)
        assertEquals("การขยับที่ต้องการให้แจ้งเตือน", presentation.title)
        assertTrue(presentation.isPrimaryControl)
        assertTrue(presentation.isGenericSensitivityControl)
        assertTrue(presentation.explanation.contains("ต้องขยับมากจึงตรวจพบ"))
        assertTrue(presentation.explanation.contains("ขยับเล็กน้อยก็ตรวจพบ"))
    }

    @Test
    fun entryMagneticIsSupportingEvidenceOnly() {
        val presentation = PresentationTextCatalog.capability(ProtectionProfile.ENTRY, SensorCapability.MAGNETIC)
        assertFalse(presentation.isPrimaryControl)
        assertFalse(presentation.isGenericSensitivityControl)
    }

    @Test
    fun powerLightIsWitnessLogicNotGenericSensitivity() {
        val presentation = PresentationTextCatalog.capability(ProtectionProfile.POWER, SensorCapability.LIGHT)
        assertFalse(presentation.isGenericSensitivityControl)
    }

    @Test
    fun evidenceRolesRenderApprovedThaiLabels() {
        assertEquals("ใช้ยืนยันหลัก", PresentationTextCatalog.evidenceRoleLabel(SensorRole.PRIMARY))
        assertEquals("ใช้ประกอบการยืนยัน", PresentationTextCatalog.evidenceRoleLabel(SensorRole.SUPPORTING))
        assertEquals("ไม่ใช้", PresentationTextCatalog.evidenceRoleLabel(SensorRole.OFF))
    }

    @Test
    fun allIncidentTypesRenderApprovedThaiTitles() {
        assertEquals("🚨 รถอาจถูกเคลื่อนย้าย", PresentationTextCatalog.incidentTitle(IncidentType.VIBRATION))
        assertEquals("⚠️ พบการงัดแงะหรือเปิดเบาะ", PresentationTextCatalog.incidentTitle(IncidentType.TAMPER))
        assertEquals("🔌 แหล่งจ่ายไฟถูกตัด", PresentationTextCatalog.incidentTitle(IncidentType.POWER))
        assertEquals("🌡️ อุณหภูมิผิดปกติ", PresentationTextCatalog.incidentTitle(IncidentType.THERMAL))
        assertEquals("🔊 เสียงผิดปกติรอบตัวรถ", PresentationTextCatalog.incidentTitle(IncidentType.AUDIO))
        assertEquals("🚪 ตรวจพบประตูเปิด", PresentationTextCatalog.incidentTitle(IncidentType.ENTRY_DOOR))
    }

    @Test
    fun allSeveritiesRenderApprovedThaiLabels() {
        assertEquals("เตือนภัย", PresentationTextCatalog.severityLabel(IncidentSeverity.WARNING))
        assertEquals("วิกฤต", PresentationTextCatalog.severityLabel(IncidentSeverity.CRITICAL))
    }

    @Test
    fun allLifecyclesRenderApprovedThaiLabels() {
        assertEquals("กำลังดำเนินเหตุการณ์", PresentationTextCatalog.incidentLifecycleLabel(IncidentLifecycle.OPEN))
        assertEquals("สิ้นสุดแล้ว", PresentationTextCatalog.incidentLifecycleLabel(IncidentLifecycle.CLOSED))
        assertEquals("ยกระดับเป็นวิกฤต", PresentationTextCatalog.incidentLifecycleLabel(IncidentLifecycle.INTERRUPTED))
    }

    @Test
    fun allDeliveryStatesRenderApprovedThaiLabels() {
        assertEquals("รอส่ง", PresentationTextCatalog.deliveryStateLabel(DeliveryState.PENDING))
        assertEquals("ส่งสำเร็จ", PresentationTextCatalog.deliveryStateLabel(DeliveryState.SENT))
        assertEquals("ส่งไม่สำเร็จ", PresentationTextCatalog.deliveryStateLabel(DeliveryState.FAILED))
        assertEquals("ไม่เข้าเงื่อนไขการส่ง", PresentationTextCatalog.deliveryStateLabel(DeliveryState.NOT_ELIGIBLE))
    }

    @Test
    fun eventsScreenTermsMatchApprovedThaiStrings() {
        assertEquals("กำลังโหลดเหตุการณ์", PresentationTextCatalog.EVENTS_LOADING)
        assertEquals("ยังไม่มีเหตุการณ์", PresentationTextCatalog.EVENTS_EMPTY_TITLE)
        assertEquals("เหตุการณ์จะแสดงที่นี่เมื่อระบบป้องกันบันทึกไว้", PresentationTextCatalog.EVENTS_EMPTY_DETAIL)
        assertEquals("เกิดข้อผิดพลาดในการโหลดเหตุการณ์", PresentationTextCatalog.EVENTS_ERROR_TITLE)
        assertEquals("ลองใหม่", PresentationTextCatalog.EVENTS_RETRY)
        assertEquals("ประวัติเหตุการณ์", PresentationTextCatalog.EVENTS_HEADER)
        assertEquals("ล้างประวัติ", PresentationTextCatalog.EVENTS_CLEAR_HISTORY)
        assertEquals("ยืนยันการล้างประวัติ", PresentationTextCatalog.EVENTS_CLEAR_CONFIRM_TITLE)
        assertEquals("การดำเนินการนี้จะลบประวัติเหตุการณ์ในเครื่องอย่างถาวร", PresentationTextCatalog.EVENTS_CLEAR_CONFIRM_BODY)
        assertEquals("ยืนยัน", PresentationTextCatalog.EVENTS_CONFIRM_CLEAR)
        assertEquals("ยกเลิก", PresentationTextCatalog.EVENTS_CANCEL)
        assertEquals("เหตุการณ์จริง", PresentationTextCatalog.REAL_EVENT_SOURCE)
    }

    @Test
    fun eventRowsNeverLeakRawEnumNamesOrEnglishFieldPrefixes() {
        val bannedTokens = listOf(
            "OPEN",
            "CLOSED",
            "INTERRUPTED",
            "PENDING",
            "SENT",
            "FAILED",
            "NOT_ELIGIBLE",
            "Source:",
            "Severity:",
            "Lifecycle:",
            "Evidence:",
            "Time:",
            "Delivery:",
            "REAL",
        )
        IncidentSeverity.entries.forEach { severity ->
            val line = PresentationTextCatalog.formatEventSeverityLine(severity)
            bannedTokens.forEach { token ->
                assertFalse("banned '$token' leaked into '$line'", line.contains(token))
            }
        }
        IncidentLifecycle.entries.forEach { lifecycle ->
            val line = PresentationTextCatalog.formatEventLifecycleLine(lifecycle)
            bannedTokens.forEach { token ->
                assertFalse("banned '$token' leaked into '$line'", line.contains(token))
            }
        }
        DeliveryState.entries.forEach { state ->
            val line = PresentationTextCatalog.formatEventDeliveryLine(state)
            bannedTokens.forEach { token ->
                assertFalse("banned '$token' leaked into '$line'", line.contains(token))
            }
        }
        assertFalse(
            "event source line must stay truthful Thai without raw 'REAL'",
            PresentationTextCatalog.formatEventSourceLine().contains("REAL"),
        )
    }
}
