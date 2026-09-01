package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host proofs pinning the typed Power Guard delivery copy (parent spec sections
 * 4.3/5): one-signal conditions are health alerts never called a power outage, only
 * the dual-signal state claims a confirmed loss, and recovery states the monitored
 * point is stable again. Both Telegram and SMS route through the same formatter.
 */
class PowerIncidentFormatterTest {

    private val formatter = IncidentMessageFormatter { null }

    private fun powerIncident(diagnostic: String, lifecycle: IncidentLifecycle = IncidentLifecycle.OPEN) =
        SecurityIncident(
            id = "INC-POWER-1",
            type = IncidentType.POWER,
            severity = IncidentSeverity.CRITICAL,
            lifecycle = lifecycle,
            evidence = listOf(
                IncidentEvidence(
                    kind = SensorKind.POWER_THERMAL,
                    eventElapsedMs = 0L,
                    wallClockMs = 0L,
                    normalizedValue = 0.0,
                    baselineDelta = 0.0,
                    diagnostic = diagnostic,
                ),
            ),
            openedAtMs = 0L,
            updatedAtMs = 0L,
            closedAtMs = null,
            protectionState = ProtectionState.ARMED_HEALTHY,
            deliveryState = DeliveryState.PENDING,
        )

    @Test
    fun chargingOnlyLossRendersHealthAlertNeverOutage() {
        val message = formatter.format(powerIncident("power_charging_health"))
        assertTrue(message.startsWith("การชาร์จโทรศัพท์หยุด"))
        assertFalse(message.contains("ไฟดับ"))
        assertFalse(message.contains("ยืนยันไฟเลี้ยงขาด"))
    }

    @Test
    fun witnessOnlyLossRendersHealthAlertNeverOutage() {
        val message = formatter.format(powerIncident("power_witness_dark"))
        assertTrue(message.startsWith("ไฟยืนยันไม่พบ"))
        assertFalse(message.contains("ยืนยันไฟเลี้ยงขาด"))
    }

    @Test
    fun dualLossRendersConfirmedOutageCopy() {
        val message = formatter.format(powerIncident("power_confirmed_loss"))
        assertEquals("ยืนยันไฟเลี้ยงขาดในจุดที่เฝ้าระวัง", message)
    }

    @Test
    fun chargingBackWhileWitnessStaysDarkNamesBothFactsAndClaimsNoRecovery() {
        val message = formatter.format(powerIncident("power_partial_witness_dark"))

        assertTrue(message.contains("สายชาร์จกลับมาแล้ว"))
        assertTrue(message.contains("ไฟยืนยันยังไม่มา"))
        assertFalse(message.contains("กลับมาคงที่แล้ว"))
        assertFalse(message.contains("ยืนยันไฟเลี้ยงขาด"))
    }

    @Test
    fun witnessBackWhileChargingStaysLostNamesBothFactsAndClaimsNoRecovery() {
        val message = formatter.format(powerIncident("power_partial_charging_lost"))

        assertTrue(message.contains("ไฟยืนยันกลับมาแล้ว"))
        assertTrue(message.contains("สายชาร์จยังไม่กลับมา"))
        assertFalse(message.contains("กลับมาคงที่แล้ว"))
        assertFalse(message.contains("ยืนยันไฟเลี้ยงขาด"))
    }

    @Test
    fun partialRecoveryCopyAlsoReachesTheSmsFallback() {
        val sms = formatter.formatSms(powerIncident("power_partial_witness_dark"))

        assertTrue(sms.contains("สายชาร์จกลับมาแล้ว"))
        assertTrue(sms.contains("ไฟยืนยันยังไม่มา"))
    }

    @Test
    fun recoveryRendersStableSettlementCopy() {
        val message = formatter.format(powerIncident("power_recovered"))
        assertEquals("ไฟเลี้ยงที่จุดเฝ้าระวังกลับมาคงที่แล้ว", message)
    }

    @Test
    fun closedPowerIncidentWithoutDiagnosticStillRendersSettlement() {
        val incident = powerIncident("power_confirmed_loss").copy(
            lifecycle = IncidentLifecycle.CLOSED,
            closedAtMs = 1_000L,
            evidence = emptyList(),
        )
        val message = formatter.format(incident)
        assertEquals("ไฟเลี้ยงที่จุดเฝ้าระวังกลับมาคงที่แล้ว", message)
    }

    @Test
    fun smsPathUsesTheSameTypedPowerCopy() {
        val sms = formatter.formatSms(powerIncident("power_confirmed_loss"))
        assertEquals("ยืนยันไฟเลี้ยงขาดในจุดที่เฝ้าระวัง", sms)
    }

    @Test
    fun legacyCableDisconnectUsesLocalizedCopyWithoutInternalTokens() {
        val message = formatter.format(powerIncident("charger_disconnected"))

        assertEquals("ตรวจพบว่าสายชาร์จถูกถอดออก", message)
        assertFalse(message.contains("POWER"))
        assertFalse(message.contains("charger_disconnected"))
    }

    private fun assertFalse(value: Boolean) = org.junit.Assert.assertFalse(value)
}
