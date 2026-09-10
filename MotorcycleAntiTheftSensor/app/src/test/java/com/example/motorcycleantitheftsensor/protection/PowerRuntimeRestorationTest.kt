package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PowerRuntimeRestorationTest {

    @Test
    fun newestOpenPowerIncidentRestoresItsLastOwnerVisibleCondition() {
        val olderOpen = powerIncident(
            id = "open-power",
            diagnostic = "power_witness_dark",
            lifecycle = IncidentLifecycle.OPEN,
            updatedAtMs = 2_000L,
        )
        val newerClosed = powerIncident(
            id = "closed-power",
            diagnostic = "power_confirmed_loss",
            lifecycle = IncidentLifecycle.CLOSED,
            updatedAtMs = 3_000L,
        )

        val restored = restorePowerRuntimeState(listOf(newerClosed, olderOpen))

        assertEquals("open-power", restored?.incident?.id)
        assertEquals(PowerCompositeArbiter.SemanticState.WITNESS_LOST, restored?.semantic)
    }

    @Test
    fun everyPersistedPowerDiagnosticMapsToItsCompositeSemantic() {
        val expected = mapOf(
            "power_charging_health" to PowerCompositeArbiter.SemanticState.CHARGING_LOST,
            "power_witness_dark" to PowerCompositeArbiter.SemanticState.WITNESS_LOST,
            "power_confirmed_loss" to PowerCompositeArbiter.SemanticState.DUAL_LOST,
            "power_partial_witness_dark" to PowerCompositeArbiter.SemanticState.WITNESS_LOST,
            "power_partial_charging_lost" to PowerCompositeArbiter.SemanticState.CHARGING_LOST,
        )

        expected.forEach { (diagnostic, semantic) ->
            val restored = restorePowerRuntimeState(
                listOf(powerIncident("power-$diagnostic", diagnostic, IncidentLifecycle.OPEN, 1_000L)),
            )
            assertEquals(semantic, restored?.semantic)
        }
    }

    @Test
    fun closedOrUnrecognizedPowerIncidentIsNotResumed() {
        assertNull(
            restorePowerRuntimeState(
                listOf(powerIncident("closed", "power_witness_dark", IncidentLifecycle.CLOSED, 1_000L)),
            ),
        )
        assertNull(
            restorePowerRuntimeState(
                listOf(powerIncident("unknown", "power_unknown", IncidentLifecycle.OPEN, 2_000L)),
            ),
        )
        assertNull(
            restorePowerRuntimeState(
                listOf(powerIncident("legacy-raw", "charger_disconnected", IncidentLifecycle.OPEN, 3_000L)),
            ),
        )
    }

    private fun powerIncident(
        id: String,
        diagnostic: String,
        lifecycle: IncidentLifecycle,
        updatedAtMs: Long,
    ): SecurityIncident = SecurityIncident(
        id = id,
        type = IncidentType.POWER,
        severity = IncidentSeverity.WARNING,
        lifecycle = lifecycle,
        evidence = listOf(
            IncidentEvidence(
                kind = SensorKind.LIGHT,
                eventElapsedMs = updatedAtMs,
                wallClockMs = updatedAtMs,
                normalizedValue = 0.0,
                baselineDelta = 0.0,
                diagnostic = diagnostic,
            ),
        ),
        openedAtMs = 500L,
        updatedAtMs = updatedAtMs,
        closedAtMs = updatedAtMs.takeIf { lifecycle == IncidentLifecycle.CLOSED },
        protectionState = ProtectionState.ARMED_HEALTHY,
        deliveryState = DeliveryState.SENT,
    )
}
