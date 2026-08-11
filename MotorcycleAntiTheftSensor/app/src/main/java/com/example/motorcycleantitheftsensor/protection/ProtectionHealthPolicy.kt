package com.example.motorcycleantitheftsensor.protection

class ProtectionHealthPolicy(
    private val sensorFreshnessMs: Long = DEFAULT_SENSOR_FRESHNESS_MS,
    private val serviceFreshnessMs: Long = DEFAULT_SERVICE_FRESHNESS_MS,
    private val telegramFreshnessMs: Long = DEFAULT_TELEGRAM_FRESHNESS_MS,
) {
    fun sensorState(
        health: SensorHealth,
        nowMs: Long,
    ): SensorHealthState = when {
        health.state != SensorHealthState.HEALTHY -> health.state
        health.lastSampleAtMs == null -> SensorHealthState.AVAILABLE
        nowMs - health.lastSampleAtMs > sensorFreshnessMs -> SensorHealthState.STALE
        else -> SensorHealthState.HEALTHY
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
        lastServiceHeartbeatAtMs != null && nowMs - lastServiceHeartbeatAtMs <= serviceFreshnessMs

    fun telegramReachable(
        lastContactAtMs: Long?,
        nowMs: Long,
    ): Boolean = lastContactAtMs != null && nowMs - lastContactAtMs <= telegramFreshnessMs

    private companion object {
        const val DEFAULT_SENSOR_FRESHNESS_MS = 5_000L
        const val DEFAULT_SERVICE_FRESHNESS_MS = 10_000L
        const val DEFAULT_TELEGRAM_FRESHNESS_MS = 20_000L
    }
}
