package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ProtectionModelsTest {
    @Test
    fun offlineSnapshotDoesNotClaimLiveMonitoring() {
        val snapshot = ProtectionSnapshot.offline(nowMs = 1_000L)

        assertEquals(ProtectionState.OFFLINE, snapshot.state)
        assertFalse(snapshot.serviceRunning)
        assertFalse(snapshot.telegramPolling)
        assertEquals(1_000L, snapshot.lastTransitionAtMs)
        assertEquals(emptySet<String>(), snapshot.permissionBlockers)
        assertNull(snapshot.protectionActivatedAtMs)
        assertEquals(5, snapshot.sensitivityLevel)
        assertEquals(ChargingState.UNKNOWN, snapshot.chargingState)
        assertNull(snapshot.lastServiceHeartbeatAtMs)
    }

    @Test
    fun sensorHealthSupportsTypedDetails() {
        val locationDetail = LocationHealthDetail(
            trackingMode = "GPS_HIGH_ACCURACY",
            isRegistered = true,
            lastFixWallClockMs = 1_000L,
            lastFixElapsedMs = 500L,
            accuracyMeters = 4.5f,
            failureReason = null,
        )
        val micDetail = MicrophoneHealthDetail(
            audioState = AudioRuntimeState.LISTENING,
            modelReady = true,
            lastAudioSampleAtMs = 2_000L,
            hardwareAvailable = true,
            permissionGranted = true,
            failureReason = null,
        )
        val powerDetail = PowerThermalHealthDetail(
            chargingState = ChargingState.CHARGING,
            batteryLevelPercent = 85,
            temperatureCelsius = 31.5f,
            lastUpdateWallClockMs = 3_000L,
        )
        val vibDetail = VibrationHealthDetail(
            isRegistered = true,
            lastSampleWallClockMs = 4_000L,
            lastSampleElapsedMs = 3500L,
            failureReason = null,
        )
        val lightDetail = LightHealthDetail(
            hardwareSupported = true,
            isRegistered = true,
            lastLux = 120.0,
            lastSampleWallClockMs = 5_000L,
            failureReason = null,
        )

        val health = SensorHealth(
            state = SensorHealthState.HEALTHY,
            lastSampleAtMs = 1_000L,
            locationDetail = locationDetail,
            microphoneDetail = micDetail,
            powerThermalDetail = powerDetail,
            vibrationDetail = vibDetail,
            lightDetail = lightDetail,
        )

        assertEquals(SensorHealthState.HEALTHY, health.state)
        assertEquals(locationDetail, health.locationDetail)
        assertEquals(micDetail, health.microphoneDetail)
        assertEquals(powerDetail, health.powerThermalDetail)
        assertEquals(vibDetail, health.vibrationDetail)
        assertEquals(lightDetail, health.lightDetail)
    }

    @Test
    fun enumsContainExpectedValues() {
        assertEquals(
            listOf("CHARGING", "DISCHARGING", "FULL", "NOT_CHARGING", "UNKNOWN"),
            ChargingState.values().map { it.name },
        )
        assertEquals(
            listOf("STOPPED", "WAITING_FOR_FIX", "TRACKING", "STALE", "UNAVAILABLE", "FAILED"),
            LocationTrackingState.values().map { it.name },
        )
        assertEquals(
            listOf("HARDWARE_UNAVAILABLE", "PERMISSION_DENIED", "NO_PROVIDERS_AVAILABLE", "REGISTRATION_FAILED"),
            LocationFailureCode.values().map { it.name },
        )
        assertEquals(
            listOf("MISSING", "FRESH", "STALE", "CLOCK_ANOMALY"),
            FreshnessState.values().map { it.name },
        )
    }

    @Test
    fun locationTrackingStateIsTyped() {
        val detail = LocationHealthDetail(
            trackingState = LocationTrackingState.WAITING_FOR_FIX,
            isRegistered = true,
        )
        assertEquals(LocationTrackingState.WAITING_FOR_FIX, detail.trackingState)
    }

    @Test
    fun microphoneSampleTimeIsExplicitlyMonotonic() {
        val detail = MicrophoneHealthDetail(lastAudioSampleElapsedMs = 12_345L)
        assertEquals(12_345L, detail.lastAudioSampleElapsedMs)
    }

    @Test
    fun incidentSummaryCarriesTypedIncidentType() {
        val summary = IncidentSummary(
            id = "550e8400-e29b-41d4-a716-446655440000",
            severity = IncidentSeverity.WARNING,
            lifecycle = IncidentLifecycle.OPEN,
            updatedAtMs = 1_000L,
            deliveryState = DeliveryState.PENDING,
            type = IncidentType.TAMPER,
        )
        assertEquals(IncidentType.TAMPER, summary.type)
    }
}
