package com.example.motorcycleantitheftsensor.protection

class ProtectionHealthPolicy(
    private val sensorFreshnessMs: Long = DEFAULT_SENSOR_FRESHNESS_MS,
    private val serviceFreshnessMs: Long = DEFAULT_SERVICE_FRESHNESS_MS,
    private val telegramFreshnessMs: Long = DEFAULT_TELEGRAM_FRESHNESS_MS,
    private val gpsFreshnessMs: Long = DEFAULT_GPS_FRESHNESS_MS,
) {
    fun sensorState(
        kind: SensorKind,
        health: SensorHealth,
        nowMs: Long,
    ): SensorHealthState {
        if (health.state != SensorHealthState.HEALTHY) {
            return health.state
        }
        val sampleMs = sampleTimestamp(kind, health) ?: return SensorHealthState.AVAILABLE
        val ageMs = nowMs - sampleMs
        if (ageMs < 0L) {
            return SensorHealthState.STALE
        }
        return when (kind) {
            SensorKind.POWER_THERMAL -> SensorHealthState.HEALTHY
            SensorKind.LOCATION -> if (ageMs <= gpsFreshnessMs) SensorHealthState.HEALTHY else SensorHealthState.STALE
            SensorKind.VIBRATION,
            SensorKind.LIGHT,
            SensorKind.MICROPHONE -> if (ageMs <= sensorFreshnessMs) SensorHealthState.HEALTHY else SensorHealthState.STALE
        }
    }

    fun sensorState(
        health: SensorHealth,
        nowMs: Long,
    ): SensorHealthState {
        if (health.state != SensorHealthState.HEALTHY) {
            return health.state
        }
        val sampleMs = health.lastSampleAtMs ?: return SensorHealthState.AVAILABLE
        val ageMs = nowMs - sampleMs
        if (ageMs < 0L) {
            return SensorHealthState.STALE
        }
        return if (ageMs <= sensorFreshnessMs) SensorHealthState.HEALTHY else SensorHealthState.STALE
    }

    fun freshness(lastAtMs: Long?, nowMs: Long, maxAgeMs: Long): FreshnessState {
        if (lastAtMs == null) return FreshnessState.MISSING
        if (lastAtMs < 0L || nowMs < 0L || lastAtMs > nowMs) return FreshnessState.CLOCK_ANOMALY
        return if (nowMs - lastAtMs <= maxAgeMs) FreshnessState.FRESH else FreshnessState.STALE
    }

    fun runtimeState(
        current: ProtectionState,
        lastServiceHeartbeatAtMs: Long?,
        nowMs: Long,
    ): ProtectionState = if (!serviceIsFresh(lastServiceHeartbeatAtMs, nowMs)) {
        ProtectionState.OFFLINE
    } else {
        current
    }

    fun serviceIsFresh(lastServiceHeartbeatAtMs: Long?, nowMs: Long): Boolean =
        freshness(lastServiceHeartbeatAtMs, nowMs, serviceFreshnessMs) == FreshnessState.FRESH

    fun telegramReachable(
        lastContactAtMs: Long?,
        nowMs: Long,
    ): Boolean =
        freshness(lastContactAtMs, nowMs, telegramFreshnessMs) == FreshnessState.FRESH

    private fun sampleTimestamp(kind: SensorKind, health: SensorHealth): Long? =
        health.lastSampleAtMs ?: when (kind) {
            SensorKind.LOCATION -> health.locationDetail?.lastFixWallClockMs
            SensorKind.MICROPHONE -> health.microphoneDetail?.lastAudioSampleAtMs
            SensorKind.POWER_THERMAL -> health.powerThermalDetail?.lastUpdateWallClockMs
            SensorKind.VIBRATION -> health.vibrationDetail?.lastSampleWallClockMs
            SensorKind.LIGHT -> health.lightDetail?.lastSampleWallClockMs
        }

    private companion object {
        const val CLOCK_SKEW_TOLERANCE_MS = 5_000L
        const val DEFAULT_SENSOR_FRESHNESS_MS = 5_000L
        const val DEFAULT_SERVICE_FRESHNESS_MS = 10_000L
        const val DEFAULT_TELEGRAM_FRESHNESS_MS = 20_000L
        const val DEFAULT_GPS_FRESHNESS_MS = 30_000L
    }
}
