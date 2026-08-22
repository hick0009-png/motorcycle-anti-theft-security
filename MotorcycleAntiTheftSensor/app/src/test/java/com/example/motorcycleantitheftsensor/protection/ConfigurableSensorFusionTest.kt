package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurableSensorFusionTest {

    private val idGenerator = IncidentIdGenerator { "INC-123456" }
    private val incidentEngine = IncidentEngine(idGenerator = idGenerator)

    @Test
    fun supportingSensorCannotOpenIncidentAlone() {
        val supportingObs = SensorObservation(
            kind = SensorKind.VIBRATION,
            source = SensorSource.GYROSCOPE,
            capability = SensorCapability.ROTATION,
            role = SensorRole.SUPPORTING,
            generationId = 1L,
            unit = SensorUnit.RADIANS_PER_SECOND,
            eventElapsedMs = 1000L,
            wallClockMs = 1000L,
            normalizedValue = 2.0,
            baselineDelta = 2.0,
            valid = true,
        )

        val update = incidentEngine.accept(
            observation = supportingObs,
            protectionState = ProtectionState.ARMED_HEALTHY,
        )

        // Supporting evidence alone is ignored from opening a new incident
        assertTrue(update is IncidentUpdate.Ignored)
    }

    @Test
    fun multipleSupportingSensorsCannotOpenIncidentWithoutPrimary() {
        val supportingGyro = SensorObservation(
            kind = SensorKind.VIBRATION,
            source = SensorSource.GYROSCOPE,
            capability = SensorCapability.ROTATION,
            role = SensorRole.SUPPORTING,
            generationId = 1L,
            unit = SensorUnit.RADIANS_PER_SECOND,
            eventElapsedMs = 1000L,
            wallClockMs = 1000L,
            normalizedValue = 2.0,
            baselineDelta = 2.0,
            valid = true,
        )
        val supportingMag = SensorObservation(
            kind = SensorKind.VIBRATION,
            source = SensorSource.MAGNETIC_FIELD,
            capability = SensorCapability.MAGNETIC,
            role = SensorRole.SUPPORTING,
            generationId = 1L,
            unit = SensorUnit.MICROTESLA,
            eventElapsedMs = 1100L,
            wallClockMs = 1100L,
            normalizedValue = 30.0,
            baselineDelta = 10.0,
            valid = true,
        )

        val update1 = incidentEngine.accept(supportingGyro, ProtectionState.ARMED_HEALTHY)
        val update2 = incidentEngine.accept(supportingMag, ProtectionState.ARMED_HEALTHY)

        assertTrue(update1 is IncidentUpdate.Ignored)
        assertTrue(update2 is IncidentUpdate.Ignored)
    }

    @Test
    fun supportingPrecursorCorroboratesPrimarySensorToOpenIncident() {
        val supportingGyro = SensorObservation(
            kind = SensorKind.VIBRATION,
            source = SensorSource.GYROSCOPE,
            capability = SensorCapability.ROTATION,
            role = SensorRole.SUPPORTING,
            generationId = 1L,
            unit = SensorUnit.RADIANS_PER_SECOND,
            eventElapsedMs = 1000L,
            wallClockMs = 1000L,
            normalizedValue = 2.0,
            baselineDelta = 2.0,
            valid = true,
        )
        val primaryAccel = SensorObservation(
            kind = SensorKind.VIBRATION,
            source = SensorSource.ACCELEROMETER,
            capability = SensorCapability.MOVEMENT,
            role = SensorRole.PRIMARY,
            generationId = 1L,
            unit = SensorUnit.METERS_PER_SECOND_SQUARED,
            eventElapsedMs = 1500L,
            wallClockMs = 1500L,
            normalizedValue = 15.0,
            baselineDelta = 5.2,
            valid = true,
        )

        val update1 = incidentEngine.accept(supportingGyro, ProtectionState.ARMED_HEALTHY)
        assertTrue(update1 is IncidentUpdate.Ignored)

        val update2 = incidentEngine.accept(primaryAccel, ProtectionState.ARMED_HEALTHY)
        assertTrue(update2 is IncidentUpdate.Opened)
        val opened = (update2 as IncidentUpdate.Opened).incident
        assertEquals(2, opened.evidence.size)
        assertEquals(SensorSource.GYROSCOPE, opened.evidence[0].source)
        assertEquals(SensorSource.ACCELEROMETER, opened.evidence[1].source)
    }

    @Test
    fun primarySensorOpensIncident() {
        val primaryObs = SensorObservation(
            kind = SensorKind.VIBRATION,
            source = SensorSource.ACCELEROMETER,
            capability = SensorCapability.MOVEMENT,
            role = SensorRole.PRIMARY,
            generationId = 1L,
            unit = SensorUnit.METERS_PER_SECOND_SQUARED,
            eventElapsedMs = 2000L,
            wallClockMs = 2000L,
            normalizedValue = 15.0,
            baselineDelta = 5.2,
            valid = true,
        )

        val update = incidentEngine.accept(
            observation = primaryObs,
            protectionState = ProtectionState.ARMED_HEALTHY,
        )

        assertTrue("Primary vibration opens or updates incident", update is IncidentUpdate.Opened || update is IncidentUpdate.Updated)
    }
}
