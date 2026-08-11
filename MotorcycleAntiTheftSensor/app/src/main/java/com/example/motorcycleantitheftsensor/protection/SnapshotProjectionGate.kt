package com.example.motorcycleantitheftsensor.protection

/** Coalesces volatile sample timestamps while preserving immediate semantic state changes. */
class SnapshotProjectionGate(
    private val minIntervalMs: Long,
) {
    private var lastProjectionAtMs: Long? = null
    private var lastKey: ProjectionKey? = null

    init {
        require(minIntervalMs >= 0L) { "minIntervalMs must not be negative" }
    }

    @Synchronized
    fun shouldProject(snapshot: ProtectionSnapshot, nowMs: Long): Boolean {
        val key = snapshot.projectionKey()
        val previousAtMs = lastProjectionAtMs
        val semanticChange = key != lastKey
        val intervalElapsed = previousAtMs == null ||
            nowMs < previousAtMs ||
            nowMs - previousAtMs >= minIntervalMs
        if (!semanticChange && !intervalElapsed) return false
        lastKey = key
        lastProjectionAtMs = nowMs
        return true
    }
}

private data class ProjectionKey(
    val state: ProtectionState,
    val serviceRunning: Boolean,
    val telegramPolling: Boolean,
    val telegramReachable: Boolean,
    val permissionBlockers: Set<String>,
    val sensorHealth: Map<SensorKind, Pair<SensorHealthState, String?>>,
    val degradationReasons: Set<String>,
    val batteryLevelPercent: Int?,
    val batteryTemperatureCelsius: Float?,
    val lastIncident: IncidentProjection?,
    val lastDeliveryState: DeliveryState?,
)

private data class IncidentProjection(
    val id: String,
    val source: IncidentSource,
    val severity: IncidentSeverity,
    val lifecycle: IncidentLifecycle,
    val deliveryState: DeliveryState,
)

private fun ProtectionSnapshot.projectionKey(): ProjectionKey = ProjectionKey(
    state = state,
    serviceRunning = serviceRunning,
    telegramPolling = telegramPolling,
    telegramReachable = telegramReachable,
    permissionBlockers = permissionBlockers,
    sensorHealth = sensorHealth.mapValues { (_, health) -> health.state to health.detail },
    degradationReasons = degradationReasons,
    batteryLevelPercent = batteryLevelPercent,
    batteryTemperatureCelsius = batteryTemperatureCelsius,
    lastIncident = lastIncident?.let { incident ->
        IncidentProjection(
            id = incident.id,
            source = incident.source,
            severity = incident.severity,
            lifecycle = incident.lifecycle,
            deliveryState = incident.deliveryState,
        )
    },
    lastDeliveryState = lastDeliveryState,
)
