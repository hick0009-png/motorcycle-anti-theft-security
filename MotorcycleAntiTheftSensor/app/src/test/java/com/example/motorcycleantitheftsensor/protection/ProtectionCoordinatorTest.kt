package com.example.motorcycleantitheftsensor.protection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProtectionCoordinatorTest {
    @Test
    fun authoritativeMutationsIncrementSnapshotRevision() {
        val coordinator = coordinator(
            runtime = FakeRuntime(
                readiness = ReadinessReport(emptySet(), emptySet()),
                health = healthyVibration(),
            ),
            armingDelay = ArmingDelay { },
        )
        val initialRevision = coordinator.snapshot.value.revision

        coordinator.recordServiceHeartbeat(1_000L)
        coordinator.recordTelegramPolling(true)

        assertEquals(initialRevision + 2L, coordinator.snapshot.value.revision)
    }

    @Test
    fun armRejectsNamedBlockerWithoutStartingDetectors() = runTest {
        val runtime = FakeRuntime(
            readiness = ReadinessReport(setOf("POST_NOTIFICATIONS"), emptySet()),
        )
        val coordinator = coordinator(runtime, ArmingDelay { })

        val result = coordinator.arm("cmd-1", CommandOrigin.LOCAL)

        assertEquals(CommandOutcome.REJECTED, result.outcome)
        assertEquals(ProtectionState.SETUP_REQUIRED, result.resultingState)
        assertEquals("Missing required: POST_NOTIFICATIONS", result.reason)
        assertFalse(runtime.started)
    }

    @Test
    fun armReportsAppliedOnlyAfterGraceAndUsesDegradedReasons() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), setOf("MICROPHONE unavailable")),
            health = healthyVibration(),
        )
        val coordinator = coordinator(runtime, ArmingDelay { gate.await() })

        val pending = async { coordinator.arm("cmd-2", CommandOrigin.TELEGRAM) }
        runCurrent()
        assertEquals(ProtectionState.ARMING, coordinator.snapshot.value.state)
        gate.complete(Unit)

        val result = pending.await()
        assertEquals(CommandOutcome.APPLIED, result.outcome)
        assertEquals(ProtectionState.ARMED_DEGRADED, result.resultingState)
    }

    @Test
    fun armIsDegradedWhileTelegramPollingHasNotStarted() = runTest {
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = healthyVibration(),
        )
        val coordinator = ProtectionCoordinator(
            initialSnapshot = ProtectionSnapshot.offline(0L).copy(
                state = ProtectionState.DISARMED_ONLINE,
                serviceRunning = true,
            ),
            runtime = runtime,
            armingDelay = ArmingDelay { },
            clock = ProtectionClock { 1_000L },
        )

        val result = coordinator.arm("arm-no-telegram", CommandOrigin.LOCAL)

        assertEquals(ProtectionState.ARMED_DEGRADED, result.resultingState)
        assertTrue(coordinator.snapshot.value.degradationReasons.contains("TELEGRAM polling inactive"))
    }

    @Test
    fun disarmCancelsArmingAndKeepsRemoteControlOnline() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = healthyVibration(),
        )
        val coordinator = coordinator(runtime, ArmingDelay { gate.await() })
        val arm = async { coordinator.arm("cmd-3", CommandOrigin.LOCAL) }
        runCurrent()

        val disarm = coordinator.disarm("cmd-4", CommandOrigin.LOCAL)
        gate.complete(Unit)
        val armResult = arm.await()

        assertEquals(CommandOutcome.UNKNOWN, armResult.outcome)
        assertEquals(ProtectionState.DISARMED_ONLINE, disarm.resultingState)
        assertEquals(ProtectionState.DISARMED_ONLINE, coordinator.snapshot.value.state)
        assertFalse(runtime.detectorsRunning)
        assertEquals(true, coordinator.snapshot.value.serviceRunning)
        assertEquals(true, coordinator.snapshot.value.telegramPolling)
    }

    @Test
    fun sensitivityIsValidatedAndAppliedToRunningRuntime() {
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = healthyVibration(),
        )
        val coordinator = coordinator(runtime, ArmingDelay { })

        assertEquals(CommandOutcome.REJECTED, coordinator.changeSensitivity("bad", 11).outcome)
        assertEquals(CommandOutcome.APPLIED, coordinator.changeSensitivity("ok", 7).outcome)
        assertEquals(7, runtime.appliedSensitivity)
    }

    @Test
    fun incidentLifecycleUpdatesSnapshotAndRestoresPreviousArmedState() = runTest {
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = healthyVibration(),
        )
        val coordinator = coordinator(runtime, ArmingDelay { })
        coordinator.arm("arm", CommandOrigin.LOCAL)
        val opened = incident(
            id = "incident-1",
            updatedAtMs = 2_000L,
            severity = IncidentSeverity.WARNING,
        )

        coordinator.recordIncident(opened)

        assertEquals(ProtectionState.ALERT_ACTIVE, coordinator.snapshot.value.state)
        assertEquals("incident-1", coordinator.snapshot.value.lastIncident?.id)

        coordinator.recordIncident(
            opened.copy(
                lifecycle = IncidentLifecycle.CLOSED,
                closedAtMs = 3_000L,
                updatedAtMs = 3_000L,
                deliveryState = DeliveryState.SENT,
            ),
        )

        assertEquals(ProtectionState.ARMED_HEALTHY, coordinator.snapshot.value.state)
        assertEquals(DeliveryState.SENT, coordinator.snapshot.value.lastDeliveryState)
    }

    @Test
    fun lateIncidentDeliveryCannotRearmAfterDisarm() = runTest {
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = healthyVibration(),
        )
        val coordinator = coordinator(runtime, ArmingDelay { })
        coordinator.arm("arm", CommandOrigin.LOCAL)
        val incident = incident("late", 2_000L)
        coordinator.recordIncident(incident)
        coordinator.disarm("disarm", CommandOrigin.LOCAL)

        coordinator.recordIncident(incident.copy(deliveryState = DeliveryState.SENT))

        assertEquals(ProtectionState.DISARMED_ONLINE, coordinator.snapshot.value.state)
        assertEquals(DeliveryState.SENT, coordinator.snapshot.value.lastDeliveryState)
    }

    @Test
    fun disarmClosesActiveIncidentBeforePublishingDisarmed() = runTest {
        val closeStates = mutableListOf<ProtectionState>()
        var capturedIncidentEpoch = -1L
        lateinit var coordinator: ProtectionCoordinator
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = healthyVibration(),
        )
        coordinator = coordinator(
            runtime = runtime,
            armingDelay = ArmingDelay { },
            incidentCloser = { reason ->
                assertEquals("owner disarmed", reason)
                assertFalse(coordinator.acceptsIncident(capturedIncidentEpoch))
                closeStates += coordinator.snapshot.value.state
            },
        )
        coordinator.arm("arm", CommandOrigin.LOCAL)
        capturedIncidentEpoch = coordinator.currentIncidentEpoch()
        assertTrue(coordinator.acceptsIncident(capturedIncidentEpoch))
        coordinator.recordIncident(incident("active", 2_000L))

        coordinator.disarm("disarm", CommandOrigin.LOCAL)

        assertEquals(listOf(ProtectionState.ALERT_ACTIVE), closeStates)
        assertEquals(ProtectionState.DISARMED_ONLINE, coordinator.snapshot.value.state)
    }

    @Test
    fun pendingDisarmRejectsConcurrentArmWithoutRestartingDetectors() = runTest {
        val closeStarted = CompletableDeferred<Unit>()
        val allowClose = CompletableDeferred<Unit>()
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = healthyVibration(),
        )
        val coordinator = coordinator(
            runtime = runtime,
            armingDelay = ArmingDelay { },
            incidentCloser = {
                closeStarted.complete(Unit)
                allowClose.await()
            },
        )
        coordinator.arm("initial-arm", CommandOrigin.LOCAL)
        assertEquals(1, runtime.startCalls)

        val disarm = async { coordinator.disarm("disarm", CommandOrigin.LOCAL) }
        closeStarted.await()
        val competingArm = async { coordinator.arm("competing-arm", CommandOrigin.TELEGRAM) }
        runCurrent()

        assertTrue(competingArm.isCompleted)
        assertEquals(CommandOutcome.REJECTED, competingArm.await().outcome)
        assertEquals(1, runtime.startCalls)

        allowClose.complete(Unit)
        assertEquals(CommandOutcome.APPLIED, disarm.await().outcome)
        assertFalse(runtime.detectorsRunning)
    }

    @Test
    fun disarmAcknowledgementWaitsForDurableDisarmedSnapshot() = runTest {
        val persistStarted = CompletableDeferred<ProtectionSnapshot>()
        val allowPersistence = CompletableDeferred<Unit>()
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = healthyVibration(),
        )
        val coordinator = ProtectionCoordinator(
            initialSnapshot = ProtectionSnapshot.offline(0L).copy(
                state = ProtectionState.ARMED_HEALTHY,
                serviceRunning = true,
                telegramPolling = true,
            ),
            runtime = runtime,
            armingDelay = ArmingDelay { },
            clock = ProtectionClock { 1_000L },
            durableSnapshotWriter = { snapshot ->
                persistStarted.complete(snapshot)
                allowPersistence.await()
            },
        )

        val disarm = async { coordinator.disarm("durable-disarm", CommandOrigin.LOCAL) }
        val persisted = persistStarted.await()

        assertEquals(ProtectionState.DISARMED_ONLINE, persisted.state)
        assertFalse(disarm.isCompleted)
        allowPersistence.complete(Unit)
        assertEquals(CommandOutcome.APPLIED, disarm.await().outcome)
    }

    @Test
    fun durableDisarmFailureReturnsUnknownWithoutRestartingProtection() = runTest {
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = healthyVibration(),
        )
        val coordinator = ProtectionCoordinator(
            initialSnapshot = ProtectionSnapshot.offline(0L).copy(
                state = ProtectionState.ARMED_HEALTHY,
                serviceRunning = true,
                telegramPolling = true,
            ),
            runtime = runtime,
            armingDelay = ArmingDelay { },
            clock = ProtectionClock { 1_000L },
            durableSnapshotWriter = { error("storage unavailable") },
        )

        val result = coordinator.disarm("failed-durable-disarm", CommandOrigin.LOCAL)

        assertEquals(CommandOutcome.UNKNOWN, result.outcome)
        assertEquals(ProtectionState.DISARMED_ONLINE, result.resultingState)
        assertEquals("Protection stopped, but durable disarm could not be confirmed", result.reason)
        assertFalse(runtime.detectorsRunning)
    }

    @Test
    fun recordedHealthAndHeartbeatBecomeOfflineWhenTheyExpire() {
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = healthyVibration(),
        )
        val coordinator = coordinator(
            runtime = runtime,
            armingDelay = ArmingDelay { },
            healthPolicy = ProtectionHealthPolicy(5_000L, 10_000L, 20_000L),
        )

        coordinator.recordServiceHeartbeat(atMs = 1_000L)
        coordinator.recordTelegramContact(atMs = 1_000L)
        coordinator.recordSensorSample(SensorKind.VIBRATION, atMs = 1_000L, detail = "baseline")
        coordinator.evaluateFreshness(nowMs = 11_001L)

        assertEquals(ProtectionState.OFFLINE, coordinator.snapshot.value.state)
        assertFalse(coordinator.snapshot.value.serviceRunning)
        assertEquals(SensorHealthState.STALE, coordinator.snapshot.value.sensorHealth[SensorKind.VIBRATION]?.state)
    }

    @Test
    fun recoveredChannelsClearDynamicDegradationsAndRestoreHealthyState() = runTest {
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = healthyVibration(),
        )
        val coordinator = coordinator(
            runtime = runtime,
            armingDelay = ArmingDelay { },
            healthPolicy = ProtectionHealthPolicy(5_000L, 10_000L, 20_000L),
        )
        coordinator.arm("arm", CommandOrigin.LOCAL)
        coordinator.recordServiceHeartbeat(1_000L)
        coordinator.recordTelegramContact(1_000L)
        coordinator.recordSensorSample(SensorKind.VIBRATION, 1_000L)

        coordinator.evaluateFreshness(7_000L)

        assertEquals(ProtectionState.ARMED_DEGRADED, coordinator.snapshot.value.state)
        assertTrue(coordinator.snapshot.value.degradationReasons.contains("VIBRATION not healthy"))

        coordinator.recordServiceHeartbeat(7_000L)
        coordinator.recordTelegramContact(7_000L)
        coordinator.recordSensorSample(SensorKind.VIBRATION, 7_000L)
        coordinator.evaluateFreshness(7_000L)

        assertEquals(ProtectionState.ARMED_HEALTHY, coordinator.snapshot.value.state)
        assertTrue(coordinator.snapshot.value.degradationReasons.isEmpty())
    }

    @Test
    fun freshHeartbeatRecoversOfflineArmedState() = runTest {
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = healthyVibration(),
        )
        val coordinator = coordinator(
            runtime = runtime,
            armingDelay = ArmingDelay { },
            healthPolicy = ProtectionHealthPolicy(5_000L, 10_000L, 20_000L),
        )
        coordinator.arm("arm", CommandOrigin.LOCAL)
        coordinator.recordServiceHeartbeat(1_000L)
        coordinator.recordTelegramContact(1_000L)
        coordinator.recordSensorSample(SensorKind.VIBRATION, 1_000L)
        coordinator.evaluateFreshness(11_001L)
        assertEquals(ProtectionState.OFFLINE, coordinator.snapshot.value.state)

        coordinator.recordServiceHeartbeat(12_000L)
        coordinator.recordTelegramContact(12_000L)
        coordinator.recordSensorSample(SensorKind.VIBRATION, 12_000L)
        coordinator.evaluateFreshness(12_000L)

        assertEquals(ProtectionState.ARMED_HEALTHY, coordinator.snapshot.value.state)
        assertTrue(coordinator.snapshot.value.serviceRunning)
    }

    @Test
    fun powerStatusSamplesPopulateBatteryLevelAndTemperature() {
        val coordinator = coordinator(
            runtime = FakeRuntime(ReadinessReport(emptySet(), emptySet())),
            armingDelay = ArmingDelay { },
        )

        coordinator.recordSensorSample(
            kind = SensorKind.POWER_THERMAL,
            atMs = 1_000L,
            detail = "battery_level_percent",
            normalizedValue = 74.0,
        )
        coordinator.recordSensorSample(
            kind = SensorKind.POWER_THERMAL,
            atMs = 1_001L,
            detail = "temperature_celsius",
            normalizedValue = 36.5,
        )

        assertEquals(74, coordinator.snapshot.value.batteryLevelPercent)
        assertEquals(36.5f, coordinator.snapshot.value.batteryTemperatureCelsius)
    }

    @Test
    fun persistenceFailureDegradesAuthoritativeArmedState() = runTest {
        val coordinator = coordinator(
            runtime = FakeRuntime(
                readiness = ReadinessReport(emptySet(), emptySet()),
                health = healthyVibration(),
            ),
            armingDelay = ArmingDelay { },
        )
        coordinator.arm("arm", CommandOrigin.LOCAL)

        coordinator.recordPersistenceFailure()

        assertEquals(ProtectionState.ARMED_DEGRADED, coordinator.snapshot.value.state)
        assertTrue(
            coordinator.snapshot.value.degradationReasons.contains("LOCAL persistence unavailable"),
        )
    }
}

private fun coordinator(
    runtime: FakeRuntime,
    armingDelay: ArmingDelay,
    healthPolicy: ProtectionHealthPolicy = ProtectionHealthPolicy(),
    incidentCloser: suspend (String) -> Unit = { },
): ProtectionCoordinator = ProtectionCoordinator(
    initialSnapshot = ProtectionSnapshot.offline(nowMs = 0L).copy(
        state = ProtectionState.DISARMED_ONLINE,
        serviceRunning = true,
        telegramPolling = true,
        telegramReachable = true,
        lastTelegramContactAtMs = 900L,
    ),
    runtime = runtime,
    armingDelay = armingDelay,
    clock = ProtectionClock { 1_000L },
    healthPolicy = healthPolicy,
    incidentCloser = incidentCloser,
)

private fun healthyVibration(): Map<SensorKind, SensorHealth> = mapOf(
    SensorKind.VIBRATION to SensorHealth(
        state = SensorHealthState.HEALTHY,
        lastSampleAtMs = 900L,
    ),
)

private class FakeRuntime(
    private val readiness: ReadinessReport,
    private val health: Map<SensorKind, SensorHealth> = emptyMap(),
    private val startResult: DetectorStartResult = DetectorStartResult(started = true),
) : ProtectionRuntime {
    var started = false
        private set
    var startCalls = 0
        private set
    var detectorsRunning = false
        private set
    var appliedSensitivity: Int? = null
        private set

    override fun readiness(): ReadinessReport = readiness

    override fun startDetectors(): DetectorStartResult {
        startCalls += 1
        started = true
        detectorsRunning = startResult.started
        return startResult
    }

    override fun stopDetectors() {
        detectorsRunning = false
    }

    override fun applySensitivity(level: Int) {
        appliedSensitivity = level
    }

    override fun currentSensorHealth(): Map<SensorKind, SensorHealth> = health
}
