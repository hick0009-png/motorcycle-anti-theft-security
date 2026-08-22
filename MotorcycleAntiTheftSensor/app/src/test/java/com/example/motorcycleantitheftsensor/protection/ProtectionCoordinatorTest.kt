package com.example.motorcycleantitheftsensor.protection

import com.example.motorcycleantitheftsensor.sensor.SensorConfigurationApplyResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun healthyPrimaryDoesNotSkipMandatoryArmingDelay() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = healthyVibration(),
            sourceHealthMap = mapOf(SensorSource.ACCELEROMETER to SensorHealthState.HEALTHY),
        )
        val coordinator = coordinator(runtime, ArmingDelay { gate.await() })

        val pending = async { coordinator.arm("mandatory-delay", CommandOrigin.LOCAL) }
        runCurrent()

        assertFalse(pending.isCompleted)
        assertEquals(ProtectionState.ARMING, coordinator.snapshot.value.state)

        gate.complete(Unit)
        assertEquals(CommandOutcome.APPLIED, pending.await().outcome)
    }

    @Test
    fun oneReadyPrimaryAllowsArmWhenAnotherPrimaryIsUnavailable() = runTest {
        val config = SensorConfigurationPolicy().forPreset(SensorPreset.BALANCED)
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = healthyVibration(),
            sourceHealthMap = mapOf(
                SensorSource.ACCELEROMETER to SensorHealthState.HEALTHY,
                SensorSource.AMBIENT_LIGHT to SensorHealthState.UNAVAILABLE,
            ),
            effectiveConfiguration = config,
        )
        val coordinator = coordinator(runtime, ArmingDelay { })

        val result = coordinator.arm("one-ready-primary", CommandOrigin.LOCAL)

        assertEquals(CommandOutcome.APPLIED, result.outcome)
        assertTrue(result.resultingState in setOf(ProtectionState.ARMED_HEALTHY, ProtectionState.ARMED_DEGRADED))
    }

    @Test
    fun repeatedOwnerArmDoesNotCancelTheOwnerArmingAlreadyInProgress() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = healthyVibration(),
        )
        val coordinator = coordinator(runtime, ArmingDelay { gate.await() })
        val firstArm = async { coordinator.arm("first", CommandOrigin.LOCAL) }
        runCurrent()

        val repeatedArm = coordinator.arm("repeat", CommandOrigin.TELEGRAM)
        gate.complete(Unit)

        assertEquals(CommandOutcome.REJECTED, repeatedArm.outcome)
        assertEquals(CommandOutcome.APPLIED, firstArm.await().outcome)
        assertTrue(coordinator.snapshot.value.state in setOf(
            ProtectionState.ARMED_HEALTHY,
            ProtectionState.ARMED_DEGRADED,
        ))
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
    fun recoveryCapturedBeforeExplicitDisarmCannotArmAfterDisarmCompletes() = runTest {
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = healthyVibration(),
        )
        val coordinator = coordinator(runtime, ArmingDelay { })
        val token = coordinator.captureRecoveryToken()

        val disarm = coordinator.disarm("owner", CommandOrigin.LOCAL)
        val recovery = coordinator.arm("recovery", CommandOrigin.RECOVERY, token)

        assertEquals(CommandOutcome.APPLIED, disarm.outcome)
        assertEquals(CommandOutcome.REJECTED, recovery.outcome)
        assertEquals(ProtectionState.DISARMED_ONLINE, coordinator.snapshot.value.state)
        assertEquals(0, runtime.startCalls)
    }

    @Test
    fun recoveryCapturedBeforeExplicitArmCannotDisarmAfterArmCompletes() = runTest {
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = healthyVibration(),
        )
        val coordinator = coordinator(runtime, ArmingDelay { })
        val token = coordinator.captureRecoveryToken()

        val arm = coordinator.arm("owner", CommandOrigin.TELEGRAM)
        val recovery = coordinator.disarm("recovery", CommandOrigin.RECOVERY, token)

        assertEquals(CommandOutcome.APPLIED, arm.outcome)
        assertEquals(CommandOutcome.REJECTED, recovery.outcome)
        assertTrue(coordinator.snapshot.value.state in setOf(
            ProtectionState.ARMED_HEALTHY,
            ProtectionState.ARMED_DEGRADED,
        ))
        assertEquals(1, runtime.startCalls)
    }

    @Test
    fun disarmWaitsBehindOlderWriteAndRemainsLastCommitted() = runTest {
        val oldWriteEntered = CompletableDeferred<Unit>()
        val releaseOldWrite = CompletableDeferred<Unit>()
        val committedSnapshots = mutableListOf<ProtectionSnapshot>()
        val compatibilityArmedValues = mutableListOf<Boolean>()
        val armedSnapshot = ProtectionSnapshot.offline(1L).copy(
            revision = 4L,
            state = ProtectionState.ARMED_HEALTHY,
            serviceRunning = true,
        )
        val arbiter = ProtectionStatePersistenceArbiter(
            writeCompatibilityArmed = { armed -> compatibilityArmedValues += armed },
            writeSnapshot = { snapshot, _ ->
                if (snapshot.revision == armedSnapshot.revision) {
                    oldWriteEntered.complete(Unit)
                    releaseOldWrite.await()
                }
                committedSnapshots += snapshot
            },
        )
        val coordinator = ProtectionCoordinator(
            initialSnapshot = armedSnapshot,
            runtime = FakeRuntime(
                readiness = ReadinessReport(emptySet(), emptySet()),
                health = healthyVibration(),
            ),
            armingDelay = ArmingDelay { },
            clock = ProtectionClock { 2_000L },
            durableSnapshotWriter = { snapshot ->
                arbiter.persist(ProtectionPersistenceRequest(snapshot, 2_000L))
            },
        )

        val oldWrite = async {
            arbiter.persist(ProtectionPersistenceRequest(armedSnapshot, 1_000L))
        }
        oldWriteEntered.await()
        val disarm = async { coordinator.disarm("owner", CommandOrigin.LOCAL) }
        runCurrent()
        assertFalse(disarm.isCompleted)

        releaseOldWrite.complete(Unit)
        oldWrite.await()

        assertEquals(CommandOutcome.APPLIED, disarm.await().outcome)
        assertEquals(ProtectionState.DISARMED_ONLINE, committedSnapshots.last().state)
        assertFalse(compatibilityArmedValues.last())
    }

    @Test
    fun sensitivityIsValidatedAndAppliedToRunningRuntime() = runTest {
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
    fun configurationPersistenceFailureRejectsUpdateSensorConfigurationAndDegradesState() = runTest {
        val failingRepo = object : SensorConfigurationRepository {
            override fun hasPersistedConfiguration(): Boolean = true
            override fun loadConfiguration(): SensorFusionConfiguration = SensorConfigurationPolicy().forPreset(SensorPreset.BALANCED)
            override fun saveConfiguration(config: SensorFusionConfiguration): Result<Unit> =
                Result.failure(java.io.IOException("Disk full"))
        }
        val coordinator = ProtectionCoordinator(
            initialSnapshot = ProtectionSnapshot.offline(0L),
            runtime = FakeRuntime(ReadinessReport(emptySet(), emptySet()), healthyVibration()),
            armingDelay = ArmingDelay { },
            clock = ProtectionClock { 1_000L },
            sensorRepository = failingRepo,
        )

        val result = coordinator.changeSensitivity("sens-cmd", 8)
        assertEquals(CommandOutcome.REJECTED, result.outcome)
        assertTrue(result.reason.contains("Failed to persist sensitivity configuration"))
        assertTrue(coordinator.snapshot.value.degradationReasons.contains("SNAPSHOT persistence unavailable"))
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
                true
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
                true
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
    fun freshServiceRecoveryKeepsArmedStateDegradedWhenTelegramIsStillStale() = runTest {
        val coordinator = coordinator(
            runtime = FakeRuntime(
                readiness = ReadinessReport(emptySet(), emptySet()),
                health = healthyVibration(),
            ),
            armingDelay = ArmingDelay { },
        )
        coordinator.recordServiceHeartbeat(1_000L)
        coordinator.recordTelegramContact(1_000L)
        coordinator.arm("arm", CommandOrigin.LOCAL)
        coordinator.evaluateFreshness(70_000L)
        coordinator.recordServiceHeartbeat(70_001L)

        coordinator.evaluateFreshness(70_001L)

        assertEquals(ProtectionState.ARMED_DEGRADED, coordinator.snapshot.value.state)
        assertFalse(coordinator.snapshot.value.telegramReachable)
        assertTrue(
            coordinator.snapshot.value.degradationReasons.any { reason ->
                reason.contains("TELEGRAM")
            },
        )
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

        coordinator.recordPersistenceFailure(PersistenceSource.SNAPSHOT)

        assertEquals(ProtectionState.ARMED_DEGRADED, coordinator.snapshot.value.state)
        assertTrue(
            coordinator.snapshot.value.degradationReasons.contains("SNAPSHOT persistence unavailable"),
        )
    }

    @Test
    fun movementTrackingPersistenceFailureDegradesCoordinatorWithExactLabel() = runTest {
        val coordinator = coordinator(
            runtime = FakeRuntime(
                readiness = ReadinessReport(emptySet(), emptySet()),
                health = healthyVibration(),
            ),
            armingDelay = ArmingDelay { },
        )
        coordinator.arm("arm", CommandOrigin.LOCAL)

        coordinator.recordPersistenceFailure(PersistenceSource.MOVEMENT_TRACKING)

        assertEquals(ProtectionState.ARMED_DEGRADED, coordinator.snapshot.value.state)
        assertTrue(
            coordinator.snapshot.value.degradationReasons.contains("Movement tracking persistence unavailable"),
        )

        coordinator.recordPersistenceRecovered(PersistenceSource.MOVEMENT_TRACKING)
        assertEquals(ProtectionState.ARMED_HEALTHY, coordinator.snapshot.value.state)
        assertFalse(
            coordinator.snapshot.value.degradationReasons.contains("Movement tracking persistence unavailable"),
        )
    }


    @Test
    fun incidentHistoryFailureMakesDisarmUnknownAfterProtectionStops() = runTest {
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = healthyVibration(),
        )
        val coordinator = coordinator(
            runtime = runtime,
            armingDelay = ArmingDelay { },
            incidentCloser = { false },
            durableSnapshotWriter = { },
        )

        val result = coordinator.disarm("owner", CommandOrigin.LOCAL)

        assertEquals(CommandOutcome.UNKNOWN, result.outcome)
        assertEquals(ProtectionState.DISARMED_ONLINE, result.resultingState)
        assertTrue(result.reason.contains("incident history", ignoreCase = true))
        assertEquals(1, runtime.stopCalls)
    }

    @Test
    fun recoveredIncidentPersistenceDoesNotReturnDuringFreshnessEvaluation() = runTest {
        val coordinator = coordinator(
            runtime = FakeRuntime(ReadinessReport(emptySet(), emptySet())),
            armingDelay = ArmingDelay { },
            incidentCloser = { false },
        )
        coordinator.disarm("owner", CommandOrigin.LOCAL)
        coordinator.recordPersistenceRecovered(PersistenceSource.INCIDENT_HISTORY)
        coordinator.recordServiceHeartbeat(1_000L)

        coordinator.evaluateFreshness(1_000L)

        assertFalse(
            coordinator.snapshot.value.degradationReasons.contains("INCIDENT history unavailable"),
        )
    }

    @Test
    fun recordSensorSampleMapsReadingSummaryCorrectly() {
        val coordinator = coordinator(
            runtime = FakeRuntime(ReadinessReport(emptySet(), emptySet())),
            armingDelay = ArmingDelay { },
        )

        coordinator.recordSensorSample(SensorKind.VIBRATION, 1000L, null, 9.8)
        coordinator.recordSensorSample(SensorKind.LIGHT, 1001L, null, 400.0)
        coordinator.recordSensorSample(SensorKind.POWER_THERMAL, 1002L, null, 99.0)
        coordinator.recordSensorSample(SensorKind.MICROPHONE, 1003L, null, 120.0)
        coordinator.recordSensorSample(SensorKind.LOCATION, 1004L, null, 1.0)

        val health = coordinator.snapshot.value.sensorHealth

        val vib = health[SensorKind.VIBRATION]?.latestReading!!
        assertEquals(9.8, vib.value)
        assertEquals("m/s²", vib.unit)
        assertEquals("Acceleration", vib.label)

        val lux = health[SensorKind.LIGHT]?.latestReading!!
        assertEquals(400.0, lux.value)
        assertEquals("lux", lux.unit)
        assertEquals("Ambient Light", lux.label)

        val pwr = health[SensorKind.POWER_THERMAL]?.latestReading!!
        assertEquals(null, pwr.value)
        assertEquals(null, pwr.unit)
        assertEquals("Power Status", pwr.label)

        val mic = health[SensorKind.MICROPHONE]?.latestReading!!
        assertEquals(null, mic.value)
        assertEquals(null, mic.unit)
        assertEquals("Signal Received", mic.label)

        val loc = health[SensorKind.LOCATION]?.latestReading!!
        assertEquals(null, loc.value)
        assertEquals(null, loc.unit)
        assertEquals("Fix Acquired", loc.label)
    }

    @Test
    fun locationForegroundRestrictionAddsAndRemovesOneRuntimeDegradation() = runTest {
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = healthyVibration(),
        )
        val coordinator = coordinator(runtime, ArmingDelay { })
        coordinator.arm("arm", CommandOrigin.LOCAL)

        assertEquals(ProtectionState.ARMED_HEALTHY, coordinator.snapshot.value.state)
        assertTrue(coordinator.snapshot.value.degradationReasons.isEmpty())

        // Restrict location foreground start
        coordinator.recordLocationForegroundRestriction(true)
        assertEquals(ProtectionState.ARMED_DEGRADED, coordinator.snapshot.value.state)
        assertEquals(
            setOf("Background location foreground start restricted"),
            coordinator.snapshot.value.degradationReasons
        )

        // Recover location foreground start
        coordinator.recordLocationForegroundRestriction(false)
        assertEquals(ProtectionState.ARMED_HEALTHY, coordinator.snapshot.value.state)
        assertTrue(coordinator.snapshot.value.degradationReasons.isEmpty())
    }


    @Test
    fun armAssignsSessionIdAndDisarmClearsIt() = runTest {
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = healthyVibration(),
        )
        val coordinator = coordinator(runtime, ArmingDelay { })

        assertEquals(null, coordinator.currentArmedSessionId())

        coordinator.arm("cmd-1", CommandOrigin.LOCAL)
        val sessionId = coordinator.currentArmedSessionId()
        assertTrue(!sessionId.isNullOrBlank())

        coordinator.disarm("cmd-2", CommandOrigin.LOCAL)
        assertEquals(null, coordinator.currentArmedSessionId())
    }

    @Test
    fun typedHealthUpdatePreservesDetailsAndIncrementsOneRevision() {
        val c = coordinator(FakeRuntime(ReadinessReport(emptySet(), emptySet())), ArmingDelay { })
        val detail = MicrophoneHealthDetail(
            audioState = AudioRuntimeState.LISTENING,
            isRegistered = true,
            modelReady = true,
            lastAudioSampleElapsedMs = 900L,
        )
        val before = c.snapshot.value.revision
        c.recordSensorHealth(
            SensorKind.MICROPHONE,
            SensorHealth(SensorHealthState.HEALTHY, microphoneDetail = detail),
        )
        assertEquals(detail, c.snapshot.value.sensorHealth[SensorKind.MICROPHONE]?.microphoneDetail)
        assertEquals(before + 1L, c.snapshot.value.revision)
    }

    @Test
    fun partialHealthSnapshotDoesNotEraseExistingLocation() {
        val c = coordinator(FakeRuntime(ReadinessReport(emptySet(), emptySet())), ArmingDelay { })
        val gps = SensorHealth(
            SensorHealthState.AVAILABLE,
            locationDetail = LocationHealthDetail(
                trackingState = LocationTrackingState.WAITING_FOR_FIX,
                isRegistered = true,
            ),
        )
        c.recordSensorHealth(SensorKind.LOCATION, gps)
        c.recordSensorHealthSnapshot(
            mapOf(SensorKind.MICROPHONE to SensorHealth(SensorHealthState.AVAILABLE)),
        )
        assertEquals(gps, c.snapshot.value.sensorHealth[SensorKind.LOCATION])
        assertTrue(c.snapshot.value.sensorHealth.containsKey(SensorKind.MICROPHONE))
    }

    @Test
    fun powerHealthCopiesBatteryTemperatureAndChargingToTopLevelSnapshot() {
        val c = coordinator(FakeRuntime(ReadinessReport(emptySet(), emptySet())), ArmingDelay { })
        c.recordSensorHealth(
            SensorKind.POWER_THERMAL,
            SensorHealth(
                SensorHealthState.HEALTHY,
                powerThermalDetail = PowerThermalHealthDetail(
                    sourceAvailable = true,
                    isRegistered = true,
                    chargingState = ChargingState.CHARGING,
                    batteryLevelPercent = 85,
                    temperatureCelsius = 32.1f,
                    lastUpdateWallClockMs = 1_000L,
                ),
            ),
        )
        assertEquals(85, c.snapshot.value.batteryLevelPercent)
        assertEquals(32.1f, c.snapshot.value.batteryTemperatureCelsius)
        assertEquals(ChargingState.CHARGING, c.snapshot.value.chargingState)
    }

    @Test
    fun genericRecordSensorSampleDoesNotDiscardExistingTypedDetail() {
        val c = coordinator(FakeRuntime(ReadinessReport(emptySet(), emptySet())), ArmingDelay { })
        val detail = VibrationHealthDetail(hardwareAvailable = true, isRegistered = true)
        c.recordSensorHealth(
            SensorKind.VIBRATION,
            SensorHealth(SensorHealthState.AVAILABLE, vibrationDetail = detail),
        )
        c.recordSensorSample(SensorKind.VIBRATION, 1_000L, "sample", 9.8)
        assertEquals(detail, c.snapshot.value.sensorHealth[SensorKind.VIBRATION]?.vibrationDetail)
    }

    @Test
    fun successfulArmSetsActivationTimeOnce() = runTest {
        var now = 1_000L
        val runtime = FakeRuntime(ReadinessReport(emptySet(), emptySet()), healthyVibration())
        val c = coordinator(runtime, ArmingDelay { }, clock = ProtectionClock { now })
        c.arm("arm", CommandOrigin.LOCAL)
        assertEquals(1_000L, c.snapshot.value.protectionActivatedAtMs)
    }

    @Test
    fun alertRoundTripDoesNotResetActivationTime() = runTest {
        var now = 1_000L
        val runtime = FakeRuntime(ReadinessReport(emptySet(), emptySet()), healthyVibration())
        val c = coordinator(runtime, ArmingDelay { }, clock = ProtectionClock { now })
        c.arm("arm", CommandOrigin.LOCAL)
        val activatedAt = c.snapshot.value.protectionActivatedAtMs
        now = 2_000L
        val opened = SecurityIncident(
            id = "550e8400-e29b-41d4-a716-446655440000",
            type = IncidentType.AUDIO,
            severity = IncidentSeverity.CRITICAL,
            lifecycle = IncidentLifecycle.OPEN,
            openedAtMs = now,
            updatedAtMs = now,
            closedAtMs = null,
            protectionState = ProtectionState.ARMED_HEALTHY,
            evidence = emptyList(),
            deliveryState = DeliveryState.PENDING,
        )
        c.recordIncident(opened)
        now = 3_000L
        c.recordIncident(opened.copy(lifecycle = IncidentLifecycle.CLOSED, updatedAtMs = now, closedAtMs = now))
        assertEquals(activatedAt, c.snapshot.value.protectionActivatedAtMs)
    }

    @Test
    fun disarmClearsActivationTime() = runTest {
        val runtime = FakeRuntime(ReadinessReport(emptySet(), emptySet()), healthyVibration())
        val c = coordinator(runtime, ArmingDelay { })
        c.arm("arm", CommandOrigin.LOCAL)
        c.disarm("disarm", CommandOrigin.LOCAL)
        org.junit.Assert.assertNull(c.snapshot.value.protectionActivatedAtMs)
    }

    @Test
    fun changeSensitivityUpdatesRuntimeAndSnapshot() = runTest {
        val runtime = FakeRuntime(ReadinessReport(emptySet(), emptySet()))
        val c = coordinator(runtime, ArmingDelay { })
        assertEquals(CommandOutcome.APPLIED, c.changeSensitivity("s8", 8).outcome)
        assertEquals(8, runtime.appliedSensitivity)
        assertEquals(8, c.snapshot.value.sensitivityLevel)
    }

    @Test
    fun rejectedSensitivityDoesNotMutateSnapshot() = runTest {
        val runtime = FakeRuntime(ReadinessReport(emptySet(), emptySet()))
        val c = coordinator(runtime, ArmingDelay { })
        val before = c.snapshot.value
        assertEquals(CommandOutcome.REJECTED, c.changeSensitivity("s0", 0).outcome)
        assertEquals(CommandOutcome.REJECTED, c.changeSensitivity("s11", 11).outcome)
        assertEquals(before.sensitivityLevel, c.snapshot.value.sensitivityLevel)
        org.junit.Assert.assertNull(runtime.appliedSensitivity)
    }

    @Test
    fun heartbeatWritesRunningAndTimestampInOneRevision() {
        val c = coordinator(FakeRuntime(ReadinessReport(emptySet(), emptySet())), ArmingDelay { })
        val before = c.snapshot.value.revision
        c.recordServiceHeartbeat(12_345L)
        assertTrue(c.snapshot.value.serviceRunning)
        assertEquals(12_345L, c.snapshot.value.lastServiceHeartbeatAtMs)
        assertEquals(before + 1L, c.snapshot.value.revision)
    }

    @Test
    fun incidentSummaryUsesSecurityIncidentTypeNotUuidText() {
        val c = coordinator(FakeRuntime(ReadinessReport(emptySet(), emptySet())), ArmingDelay { })
        val inc = SecurityIncident(
            id = "550e8400-e29b-41d4-a716-446655440000",
            type = IncidentType.AUDIO,
            severity = IncidentSeverity.CRITICAL,
            lifecycle = IncidentLifecycle.OPEN,
            openedAtMs = 2_000L,
            updatedAtMs = 2_000L,
            closedAtMs = null,
            protectionState = ProtectionState.ARMED_HEALTHY,
            evidence = emptyList(),
            deliveryState = DeliveryState.PENDING,
        )
        c.recordIncident(inc)
        assertEquals(IncidentType.AUDIO, c.snapshot.value.lastIncident?.type)
    }

    @Test
    fun armCoroutineCancellationRollsBackToDisarmedOnlineAndStopsDetectors() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = healthyVibration(),
        )
        val coordinator = coordinator(runtime, ArmingDelay { gate.await() })

        val armJob = launch { coordinator.arm("cancel-cmd", CommandOrigin.LOCAL) }
        runCurrent()

        assertEquals(ProtectionState.ARMING, coordinator.snapshot.value.state)
        assertTrue(runtime.detectorsRunning)
        assertNotNull(coordinator.currentArmedSessionId())

        armJob.cancel()
        runCurrent()

        assertEquals(ProtectionState.DISARMED_ONLINE, coordinator.snapshot.value.state)
        assertFalse(runtime.detectorsRunning)
        assertNull(coordinator.currentArmedSessionId())
    }

    @Test
    fun armAcknowledgementWaitsForDurableArmedSnapshot() = runTest {
        val persistStarted = CompletableDeferred<ProtectionSnapshot>()
        val allowPersistence = CompletableDeferred<Unit>()
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = healthyVibration(),
        )
        val coordinator = ProtectionCoordinator(
            initialSnapshot = ProtectionSnapshot.offline(0L).copy(
                state = ProtectionState.DISARMED_ONLINE,
                serviceRunning = true,
                telegramPolling = true,
                telegramReachable = true,
                lastTelegramContactAtMs = 900L,
            ),
            runtime = runtime,
            armingDelay = ArmingDelay { },
            clock = ProtectionClock { 1_000L },
            durableSnapshotWriter = { snapshot ->
                persistStarted.complete(snapshot)
                allowPersistence.await()
            },
        )

        val arm = async { coordinator.arm("durable-arm", CommandOrigin.LOCAL) }
        val persisted = persistStarted.await()

        assertEquals(ProtectionState.ARMED_HEALTHY, persisted.state)
        assertFalse(arm.isCompleted)
        allowPersistence.complete(Unit)
        val result = arm.await()
        assertEquals(CommandOutcome.APPLIED, result.outcome)
    }

    @Test
    fun durableArmFailureReturnsUnknownAndRecordsPersistenceFailure() = runTest {
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = healthyVibration(),
        )
        val coordinator = ProtectionCoordinator(
            initialSnapshot = ProtectionSnapshot.offline(0L).copy(
                state = ProtectionState.DISARMED_ONLINE,
                serviceRunning = true,
                telegramPolling = true,
                telegramReachable = true,
                lastTelegramContactAtMs = 900L,
            ),
            runtime = runtime,
            armingDelay = ArmingDelay { },
            clock = ProtectionClock { 1_000L },
            durableSnapshotWriter = { error("disk failure") },
        )

        val result = coordinator.arm("failed-durable-arm", CommandOrigin.LOCAL)

        assertEquals(CommandOutcome.UNKNOWN, result.outcome)
        assertEquals(ProtectionState.ARMED_DEGRADED, result.resultingState)
        assertEquals("Protection armed, but durable state could not be confirmed", result.reason)
        assertTrue(coordinator.snapshot.value.degradationReasons.contains("SNAPSHOT persistence unavailable"))
    }

    @Test
    fun gpsAvailableStateDoesNotDegradeArmedCoordinator() = runTest {
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = mapOf(
                SensorKind.VIBRATION to SensorHealth(
                    state = SensorHealthState.HEALTHY,
                    lastSampleAtMs = 900L,
                ),
                SensorKind.LOCATION to SensorHealth(
                    state = SensorHealthState.AVAILABLE,
                    lastSampleAtMs = null,
                ),
            ),
        )
        val coordinator = coordinator(runtime, ArmingDelay { })
        val result = coordinator.arm("arm-with-gps-avail", CommandOrigin.LOCAL)

        assertEquals(CommandOutcome.APPLIED, result.outcome)
        assertEquals(ProtectionState.ARMED_HEALTHY, result.resultingState)
        assertFalse(coordinator.snapshot.value.degradationReasons.contains("LOCATION not healthy"))

        coordinator.recordServiceHeartbeat(1_000L)
        coordinator.recordTelegramContact(1_000L)
        coordinator.evaluateFreshness(1_000L)
        assertEquals(ProtectionState.ARMED_HEALTHY, coordinator.snapshot.value.state)
        assertFalse(coordinator.snapshot.value.degradationReasons.contains("LOCATION not healthy"))
    }

    @Test
    fun disarmedStateDoesNotIncludeUnhealthySensorDegradations() {
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = mapOf(
                SensorKind.VIBRATION to SensorHealth(
                    state = SensorHealthState.UNAVAILABLE,
                ),
            ),
        )
        val coordinator = coordinator(
            runtime = runtime,
            armingDelay = ArmingDelay { },
            healthPolicy = ProtectionHealthPolicy(5_000L, 10_000L, 20_000L),
        )
        coordinator.recordServiceHeartbeat(1_000L)
        coordinator.recordTelegramContact(1_000L)

        coordinator.evaluateFreshness(10_000L)

        assertEquals(ProtectionState.DISARMED_ONLINE, coordinator.snapshot.value.state)
        assertFalse(coordinator.snapshot.value.degradationReasons.contains("VIBRATION not healthy"))
    }

    @Test
    fun supersededArmStopsDetectorsWhenStateChangedBeforeGraceCompletes() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = healthyVibration(),
        )
        val coordinator = coordinator(runtime, ArmingDelay { gate.await() })
        val arm = async { coordinator.arm("superseded-arm", CommandOrigin.LOCAL) }
        runCurrent()

        assertTrue(runtime.detectorsRunning)
        coordinator.disarm("disarm-intervene", CommandOrigin.LOCAL)
        assertFalse(runtime.detectorsRunning)

        gate.complete(Unit)
        val armResult = arm.await()

        assertEquals(CommandOutcome.UNKNOWN, armResult.outcome)
        assertFalse(runtime.detectorsRunning)
        assertNull(coordinator.currentArmedSessionId())
    }

    @Test
    fun armRejectsWhenRequiredPrimarySourceHealthFailsOrTimesOut() = runTest {
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = healthyVibration(),
            sourceHealthMap = mapOf(
                SensorSource.ACCELEROMETER to SensorHealthState.FAILED,
            ),
        )
        val coordinator = coordinator(runtime, ArmingDelay { })

        val result = coordinator.arm("cmd-fail-source", CommandOrigin.LOCAL)

        assertEquals(CommandOutcome.REJECTED, result.outcome)
        assertEquals(ProtectionState.DISARMED_ONLINE, result.resultingState)
        assertTrue(result.reason.contains("primary sensors not available or calibrated"))
    }

    @Test
    fun armFreezesSelectedProfileConfigurationBeforeDetectorStart() = runTest {
        val profilePolicy = ProtectionProfilePolicy(nowMs = { 1_000L })
        val selectedState = profilePolicy.newStoreState().copy(selectedProfile = ProtectionProfile.VEHICLE)
        val profileRepository = InMemoryProtectionProfileRepository(selectedState)
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = healthyVibration(),
        )
        val coordinator = coordinator(
            runtime,
            ArmingDelay { },
            profileRepository = profileRepository,
            profilePolicy = profilePolicy,
        )

        val result = coordinator.arm("arm-1", CommandOrigin.LOCAL)

        assertEquals(CommandOutcome.APPLIED, result.outcome)
        val armed = coordinator.snapshot.value.armedProfileSnapshot
        assertNotNull(armed)
        assertEquals(ProtectionProfile.VEHICLE, armed!!.profile)
        val expectedConfig =
            profilePolicy.resolve(selectedState, ProtectionProfile.VEHICLE).sensorConfiguration
        assertEquals(expectedConfig, armed.effectiveConfiguration)
        assertEquals(expectedConfig, runtime.startedConfiguration)
        assertEquals(armed.armedSessionId, runtime.startedSessionId)

        // Editing the stored profile after Arm must not change the frozen snapshot.
        profileRepository.save(
            profilePolicy.updateProfile(
                profileRepository.load(),
                profileRepository.load().profiles.getValue(ProtectionProfile.VEHICLE).copy(
                    sensorOverrides = SensorFusionProfileOverrides(
                        capabilities = mapOf(
                            SensorCapability.MOVEMENT to SensorCapabilityProfileOverrides(sensitivity = 9),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            expectedConfig,
            coordinator.snapshot.value.armedProfileSnapshot!!.effectiveConfiguration,
        )
    }

    @Test
    fun settingsEditWhileArmedDoesNotReconfigureRunningDetectors() = runTest {
        val profilePolicy = ProtectionProfilePolicy(nowMs = { 1_000L })
        val selectedState = profilePolicy.newStoreState().copy(selectedProfile = ProtectionProfile.VEHICLE)
        val profileRepository = InMemoryProtectionProfileRepository(selectedState)
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = healthyVibration(),
        )
        val coordinator = coordinator(runtime, ArmingDelay { }, profileRepository = profileRepository)
        coordinator.arm("arm-1", CommandOrigin.LOCAL)
        val originalArmedConfig =
            coordinator.snapshot.value.armedProfileSnapshot!!.effectiveConfiguration

        val result = coordinator.updateSelectedProfile("settings-1") { state ->
            state.profiles.getValue(ProtectionProfile.VEHICLE).copy(
                sensorOverrides = SensorFusionProfileOverrides(
                    capabilities = mapOf(
                        SensorCapability.MOVEMENT to SensorCapabilityProfileOverrides(sensitivity = 9),
                    ),
                ),
            )
        }

        assertEquals(CommandOutcome.APPLIED, result.outcome)
        assertTrue(result.reason.contains("next Arm"))
        assertTrue(runtime.appliedConfigurations.isEmpty())
        assertEquals(
            originalArmedConfig,
            coordinator.snapshot.value.armedProfileSnapshot!!.effectiveConfiguration,
        )
    }

    @Test
    fun settingsEditWhileDisarmedAppliesToEditableRuntime() = runTest {
        val profilePolicy = ProtectionProfilePolicy(nowMs = { 1_000L })
        val selectedState = profilePolicy.newStoreState().copy(selectedProfile = ProtectionProfile.VEHICLE)
        val profileRepository = InMemoryProtectionProfileRepository(selectedState)
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = healthyVibration(),
        )
        val coordinator = coordinator(
            runtime,
            ArmingDelay { },
            profileRepository = profileRepository,
            profilePolicy = profilePolicy,
        )

        val result = coordinator.updateSelectedProfile("settings-disarmed") { state ->
            state.profiles.getValue(ProtectionProfile.VEHICLE).copy(
                sensorOverrides = SensorFusionProfileOverrides(
                    capabilities = mapOf(
                        SensorCapability.MOVEMENT to SensorCapabilityProfileOverrides(sensitivity = 8),
                    ),
                ),
            )
        }

        assertEquals(CommandOutcome.APPLIED, result.outcome)
        assertEquals(1, runtime.appliedConfigurations.size)
        assertEquals(
            profilePolicy.resolve(profileRepository.load(), ProtectionProfile.VEHICLE).sensorConfiguration,
            runtime.appliedConfigurations.single(),
        )
    }

    @Test
    fun legacyUnselectedCustomerKeepsExistingArmBehavior() = runTest {
        val profilePolicy = ProtectionProfilePolicy(nowMs = { 1_000L })
        val legacyState = profilePolicy.newStoreState(
            SensorConfigurationPolicy().forPreset(SensorPreset.BALANCED, 1_000L),
        )
        val profileRepository = InMemoryProtectionProfileRepository(legacyState)
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = healthyVibration(),
        )
        val coordinator = coordinator(runtime, ArmingDelay { }, profileRepository = profileRepository)

        val result = coordinator.arm("arm-legacy", CommandOrigin.LOCAL)

        assertEquals(CommandOutcome.APPLIED, result.outcome)
        assertNull(coordinator.snapshot.value.armedProfileSnapshot)
    }

    @Test
    fun armedSwitchPersistsStopIntentBeforeRuntimeEffects() = runTest {
        val profilePolicy = ProtectionProfilePolicy(nowMs = { 1_000L })
        val selectedState = profilePolicy.newStoreState().copy(selectedProfile = ProtectionProfile.VEHICLE)
        val eventLog = mutableListOf<String>()
        val profileRepository = InMemoryProtectionProfileRepository(selectedState, eventLog)
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = healthyVibration(),
            eventLog = eventLog,
        )
        val coordinator = coordinator(
            runtime,
            ArmingDelay { },
            profileRepository = profileRepository,
            profilePolicy = profilePolicy,
        )
        coordinator.arm("arm-before-switch", CommandOrigin.LOCAL)
        eventLog.clear()

        val result = coordinator.changeProfile("switch-1", ProtectionProfile.ENTRY, confirmed = true)

        assertEquals(CommandOutcome.APPLIED, result.outcome)
        assertTrue(
            "STOP_REQUESTED must be persisted before stopping detectors",
            eventLog.indexOf("save:STOP_REQUESTED") in 0 until eventLog.indexOf("stop"),
        )
        assertEquals(ProtectionState.DISARMED_ONLINE, coordinator.snapshot.value.state)
        assertNull(coordinator.snapshot.value.armedProfileSnapshot)
        assertEquals(ProtectionProfile.ENTRY, profileRepository.load().selectedProfile)
        assertNull(profileRepository.load().switchTransaction)
    }

    @Test
    fun switchNeverAutoArmsTargetProfile() = runTest {
        val profilePolicy = ProtectionProfilePolicy(nowMs = { 1_000L })
        val selectedState = profilePolicy.newStoreState().copy(selectedProfile = ProtectionProfile.VEHICLE)
        val profileRepository = InMemoryProtectionProfileRepository(selectedState)
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = healthyVibration(),
        )
        val coordinator = coordinator(
            runtime,
            ArmingDelay { },
            profileRepository = profileRepository,
            profilePolicy = profilePolicy,
        )

        val result = coordinator.changeProfile("switch-power", ProtectionProfile.POWER, confirmed = true)

        assertEquals(CommandOutcome.APPLIED, result.outcome)
        assertEquals(0, runtime.startCalls)
        assertEquals(ProtectionState.DISARMED_ONLINE, coordinator.snapshot.value.state)
    }

    @Test
    fun unconfirmedSwitchChangesNothing() = runTest {
        val profilePolicy = ProtectionProfilePolicy(nowMs = { 1_000L })
        val selectedState = profilePolicy.newStoreState().copy(selectedProfile = ProtectionProfile.VEHICLE)
        val profileRepository = InMemoryProtectionProfileRepository(selectedState)
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = healthyVibration(),
        )
        val coordinator = coordinator(
            runtime,
            ArmingDelay { },
            profileRepository = profileRepository,
            profilePolicy = profilePolicy,
        )

        val result = coordinator.changeProfile("switch-no-confirm", ProtectionProfile.ENTRY, confirmed = false)

        assertEquals(CommandOutcome.REJECTED, result.outcome)
        assertEquals(ProtectionProfile.VEHICLE, profileRepository.load().selectedProfile)
        assertNull(profileRepository.load().switchTransaction)
    }

    @Test
    fun resumeProfileSwitchIfNeededConvergesInterruptedSwitch() = runTest {
        val profilePolicy = ProtectionProfilePolicy(nowMs = { 1_000L })
        val selectedState = profilePolicy.newStoreState().copy(selectedProfile = ProtectionProfile.VEHICLE)
        val interrupted = selectedState.copy(
            switchTransaction = ProfileSwitchTransaction(
                transactionId = "txn-crash",
                oldArmedSessionId = null,
                targetProfile = ProtectionProfile.ENTRY,
                phase = ProfileSwitchPhase.SNAPSHOT_CLEARED,
            ),
        )
        val profileRepository = InMemoryProtectionProfileRepository(interrupted)
        val runtime = FakeRuntime(
            readiness = ReadinessReport(emptySet(), emptySet()),
            health = healthyVibration(),
        )
        val coordinator = coordinator(
            runtime,
            ArmingDelay { },
            profileRepository = profileRepository,
            profilePolicy = profilePolicy,
        )

        val resumed = coordinator.resumeProfileSwitchIfNeeded()

        assertTrue(resumed)
        assertEquals(ProtectionProfile.ENTRY, profileRepository.load().selectedProfile)
        assertNull(profileRepository.load().switchTransaction)
        assertEquals(ProtectionState.DISARMED_ONLINE, coordinator.snapshot.value.state)
        assertFalse(runtime.detectorsRunning)

        // Nothing left to resume.
        assertFalse(coordinator.resumeProfileSwitchIfNeeded())
    }
}

private class InMemoryProtectionProfileRepository(
    initialState: ProtectionProfileStoreState,
    private val eventLog: MutableList<String>? = null,
) : ProtectionProfileRepository {
    private var state: ProtectionProfileStoreState = initialState
    val savedStates = mutableListOf<ProtectionProfileStoreState>()

    override fun load(): ProtectionProfileStoreState = state

    override fun save(state: ProtectionProfileStoreState): Result<Unit> {
        this.state = state
        savedStates += state
        state.switchTransaction?.let { transaction ->
            eventLog?.add("save:${transaction.phase.name}")
        }
        return Result.success(Unit)
    }

    override fun update(
        transform: (ProtectionProfileStoreState) -> ProtectionProfileStoreState,
    ): Result<ProtectionProfileStoreState> {
        // Mirrors the real repository: update() persists through save().
        val result = save(transform(this.state))
        return result.map { this.state }
    }
}

private fun coordinator(
    runtime: FakeRuntime,
    armingDelay: ArmingDelay,
    clock: ProtectionClock = ProtectionClock { 1_000L },
    healthPolicy: ProtectionHealthPolicy = ProtectionHealthPolicy(),
    incidentCloser: suspend (String) -> Boolean = { true },
    durableSnapshotWriter: suspend (ProtectionSnapshot) -> Unit = { },
    profileRepository: ProtectionProfileRepository? = null,
    profilePolicy: ProtectionProfilePolicy = ProtectionProfilePolicy(),
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
    clock = clock,
    healthPolicy = healthPolicy,
    incidentCloser = incidentCloser,
    durableSnapshotWriter = durableSnapshotWriter,
    profileRepository = profileRepository,
    profilePolicy = profilePolicy,
)

private fun healthyVibration(): Map<SensorKind, SensorHealth> = mapOf(
    SensorKind.VIBRATION to SensorHealth(
        state = SensorHealthState.HEALTHY,
        lastSampleAtMs = 900L,
    ),
)

private class FakeRuntime(
    private val readiness: ReadinessReport,
    private val health: Map<SensorKind, SensorHealth> = mapOf(SensorKind.VIBRATION to SensorHealth(SensorHealthState.HEALTHY)),
    private val startResult: DetectorStartResult = DetectorStartResult(started = true),
    private val sourceHealthMap: Map<SensorSource, SensorHealthState> = emptyMap(),
    private val effectiveConfiguration: SensorFusionConfiguration = SensorConfigurationPolicy().forPreset(SensorPreset.BALANCED),
    private val eventLog: MutableList<String>? = null,
) : ProtectionRuntime {
    var started = false
        private set
    var startCalls = 0
        private set
    var stopCalls = 0
        private set
    var detectorsRunning = false
        private set
    var appliedSensitivity: Int? = null
        private set

    override fun readiness(): ReadinessReport = readiness

    var startedSessionId: String? = null
        private set
    var startedConfiguration: SensorFusionConfiguration? = null
        private set
    val appliedConfigurations = mutableListOf<SensorFusionConfiguration>()

    override fun startDetectors(): DetectorStartResult {
        startCalls += 1
        started = true
        detectorsRunning = startResult.started
        return startResult
    }

    override fun startDetectors(
        armedSessionId: String,
        configuration: SensorFusionConfiguration,
    ): DetectorStartResult {
        startedSessionId = armedSessionId
        startedConfiguration = configuration
        return startDetectors(armedSessionId)
    }

    override fun applySensorConfiguration(
        config: SensorFusionConfiguration,
    ): SensorConfigurationApplyResult {
        appliedConfigurations += config
        return SensorConfigurationApplyResult(
            status = SensorConfigurationApplyResult.Status.APPLIED,
            affectedCapabilities = emptySet(),
        )
    }

    override fun stopDetectors() {
        stopCalls += 1
        detectorsRunning = false
        eventLog?.add("stop")
    }

    override fun applySensitivity(level: Int) {
        appliedSensitivity = level
    }

    override fun currentSensorHealth(): Map<SensorKind, SensorHealth> = health

    override fun effectiveSensorConfiguration(): SensorFusionConfiguration = effectiveConfiguration

    override fun sourceHealth(source: SensorSource): SensorHealthState =
        sourceHealthMap[source] ?: super.sourceHealth(source)

}
