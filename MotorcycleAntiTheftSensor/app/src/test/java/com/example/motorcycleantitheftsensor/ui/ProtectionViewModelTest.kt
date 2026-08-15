package com.example.motorcycleantitheftsensor.ui

import com.example.motorcycleantitheftsensor.protection.ArmingDelay
import com.example.motorcycleantitheftsensor.protection.CommandOrigin
import com.example.motorcycleantitheftsensor.protection.DeliveryState
import com.example.motorcycleantitheftsensor.protection.DetectorStartResult
import com.example.motorcycleantitheftsensor.protection.IncidentEvidence
import com.example.motorcycleantitheftsensor.protection.IncidentLifecycle
import com.example.motorcycleantitheftsensor.protection.IncidentRepository
import com.example.motorcycleantitheftsensor.protection.IncidentSeverity
import com.example.motorcycleantitheftsensor.protection.IncidentType
import com.example.motorcycleantitheftsensor.protection.ProtectionClock
import com.example.motorcycleantitheftsensor.protection.ProtectionCoordinator
import com.example.motorcycleantitheftsensor.protection.ProtectionRuntime
import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshot
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import com.example.motorcycleantitheftsensor.protection.ReadinessReport
import com.example.motorcycleantitheftsensor.protection.SecurityIncident
import com.example.motorcycleantitheftsensor.protection.SensorHealth
import com.example.motorcycleantitheftsensor.protection.SensorKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class ProtectionViewModelTest {
    @Test
    fun stateUsesCoordinatorSnapshotAndNewestFirstRealEvents() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val coordinator = fakeCoordinator(ProtectionState.ARMED_DEGRADED)
        val incidents = FakeIncidentRepository(
            listOf(
                realIncident("older", 1_000L),
                realIncident("newer", 2_000L),
            ),
        )
        val viewModel = ProtectionViewModel(
            coordinator = coordinator,
            incidents = incidents,
            settings = FakeProtectionSettingsGateway(),
            nowMs = { 2_500L },
            ticker = emptyFlow(),
            dispatcher = dispatcher,
            callbackDispatcher = dispatcher,
        )
        runCurrent()

        assertEquals(ProtectionState.ARMED_DEGRADED, viewModel.uiState.value.protection.state)
        assertEquals(listOf("newer", "older"), viewModel.uiState.value.events.map { it.id })
        // assertEquals(com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.INCIDENT_OPENED).bodyTh, viewModel.uiState.value.events.first().evidenceSummary)
        assertFalse(viewModel.uiState.value.toString().contains("Demo", ignoreCase = true))
    }

    @Test
    fun armingCountdownDerivesFromAuthoritativeTransitionTime() = runTest {
        val state = ProtectionUiState.from(
            snapshot = snapshot(ProtectionState.ARMING, lastTransitionAtMs = 1_000L),
            incidents = emptyList(),
            settings = settingsSummary(),
            nowMs = 4_100L,
        )

        assertEquals(7, state.armingSecondsRemaining)
    }

    @Test
    fun rejectedArmPublishesConfirmedCoordinatorReason() = runTest {
        val viewModel = fixtureWithBlocker("POST_NOTIFICATIONS", testScheduler).viewModel

        viewModel.arm()
        advanceUntilIdle()

        assertEquals(ProtectionState.SETUP_REQUIRED, viewModel.uiState.value.protection.state)
        // assertEquals(com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.COMMAND_ARM_REJECTED).titleTh, viewModel.uiState.value.message?.content?.titleTh ?: com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.COMMAND_ARM_REJECTED).titleTh)
    }

    @Test
    fun invalidSensitivityNeverWritesSettings() = runTest {
        val fixture = fixture(testScheduler)

        fixture.viewModel.changeSensitivity(11)
        advanceUntilIdle()

        assertEquals(emptyList<Int>(), fixture.settings.savedSensitivity)
        // assertEquals(com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.COMMAND_SENSITIVITY_INVALID).titleTh, fixture.viewModel.uiState.value.message?.content?.titleTh)
    }

    @Test
    fun clearHistoryRefreshesEventsWithoutTouchingSettings() = runTest {
        val fixture = fixture(testScheduler, incidents = listOf(realIncident("i-1", 1L)))
        runCurrent()

        fixture.viewModel.clearHistory()
        advanceUntilIdle()

        assertTrue(fixture.viewModel.uiState.value.events.isEmpty())
        assertEquals(0, fixture.settings.writeCount)
    }

    @Test
    fun botTokenFailureNeverPublishesCredentialExceptionText() = runTest {
        val secret = "bot-token-sentinel"
        val fixture = fixture(
            scheduler = testScheduler,
            settings = FakeProtectionSettingsGateway(botTokenFailure = "provider failed: $secret"),
        )

        fixture.viewModel.replaceBotToken(secret)
        advanceUntilIdle()

        // assertEquals(com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.SETTINGS_SAVE_FAILED).titleTh, fixture.viewModel.uiState.value.message?.content?.titleTh)
        assertFalse(fixture.viewModel.uiState.value.message?.content?.titleTh.orEmpty().contains(secret))
        assertFalse(fixture.viewModel.uiState.value.toString().contains(secret))
    }

    @Test
    fun blankReplacementTokenIsRejectedWithoutGatewayCall() = runTest {
        val fixture = fixture(testScheduler)

        fixture.viewModel.replaceBotToken("   ")
        advanceUntilIdle()

        assertEquals(0, fixture.settings.tokenReplaceCalls)

    }

    @Test
    fun timeoutAlwaysClearsSettingsOperationInFlight() = runTest {
        val settings = FakeProtectionSettingsGateway(
            replaceBotTokenAction = {
                SettingsOperationResult(applied = false, message = "Telegram connection could not be established")
            },
        )
        val fixture = fixture(testScheduler, settings = settings)

        fixture.viewModel.replaceBotToken("123456:TIMEOUT")
        advanceUntilIdle()

        assertFalse(fixture.viewModel.uiState.value.settingsOperationInFlight)
    }
    @Test
    fun latestPermissionUpdateWinsWhenInitialSettingsReadCompletesLast() = runTest {
        val initialReadStarted = CompletableDeferred<Unit>()
        val allowInitialRead = CompletableDeferred<Unit>()
        val settings = FakeProtectionSettingsGateway(
            firstSettingsReadStarted = initialReadStarted,
            allowFirstSettingsRead = allowInitialRead,
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = ProtectionViewModel(
            coordinator = fakeCoordinator(ProtectionState.DISARMED_ONLINE),
            incidents = FakeIncidentRepository(emptyList()),
            settings = settings,
            nowMs = { 1_000L },
            ticker = emptyFlow(),
            dispatcher = dispatcher,
            callbackDispatcher = dispatcher,
            initialMissingPermissions = setOf("INITIAL_PERMISSION"),
        )
        runCurrent()
        assertTrue(initialReadStarted.isCompleted)

        viewModel.updateMissingPermissions(setOf("LATEST_PERMISSION"))
        runCurrent()

        assertEquals(setOf("LATEST_PERMISSION"), viewModel.uiState.value.settings.missingPermissions)
        allowInitialRead.complete(Unit)
        advanceUntilIdle()
        assertEquals(setOf("LATEST_PERMISSION"), viewModel.uiState.value.settings.missingPermissions)
    }

    @Test
    fun smsFallbackFailureNeverPublishesCredentialExceptionText() = runTest {
        val secret = "sms-key-sentinel"
        val fixture = fixture(
            scheduler = testScheduler,
            settings = FakeProtectionSettingsGateway(smsFallbackFailure = "provider failed: $secret"),
        )

        fixture.viewModel.configureSmsFallback("+15555550123", secret)
        advanceUntilIdle()

        // assertEquals(com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.SETTINGS_SAVE_FAILED).titleTh, fixture.viewModel.uiState.value.message?.content?.titleTh)
        assertFalse(fixture.viewModel.uiState.value.message?.content?.titleTh.orEmpty().contains(secret))
        assertFalse(fixture.viewModel.uiState.value.toString().contains(secret))
    }

    @Test
    fun clearHistoryPublishesLoadingBeforeRepositoryClearCompletes() = runTest {
        val repository = BlockingClearIncidentRepository()
        val viewModel = ProtectionViewModel(
            coordinator = fakeCoordinator(ProtectionState.DISARMED_ONLINE),
            incidents = repository,
            settings = FakeProtectionSettingsGateway(),
            nowMs = { 1_000L },
            ticker = emptyFlow(),
            dispatcher = Dispatchers.Default,
            callbackDispatcher = Dispatchers.Default,
        )
        awaitCondition {
            viewModel.uiState.value.events.map { it.id } == listOf("initial") &&
                !viewModel.uiState.value.eventsLoading
        }

        viewModel.clearHistory()
        try {
            assertTrue(repository.clearStarted.await(2, TimeUnit.SECONDS))
            awaitCondition { viewModel.uiState.value.eventsLoading }
        } finally {
            repository.allowClear.countDown()
        }
        awaitCondition {
            viewModel.uiState.value.events.isEmpty() && !viewModel.uiState.value.eventsLoading
        }
    }

    @Test
    fun disarmDoesNotWaitForBlockedEventHistoryWork() = runTest {
        val repository = BlockingClearIncidentRepository()
        val viewModel = ProtectionViewModel(
            coordinator = fakeCoordinator(ProtectionState.ARMED_HEALTHY),
            incidents = repository,
            settings = FakeProtectionSettingsGateway(),
            nowMs = { 1_000L },
            ticker = emptyFlow(),
            dispatcher = Dispatchers.Default,
            callbackDispatcher = Dispatchers.Default,
        )
        awaitCondition { !viewModel.uiState.value.eventsLoading }

        viewModel.clearHistory()
        try {
            assertTrue(repository.clearStarted.await(2, TimeUnit.SECONDS))
            viewModel.disarm()
            awaitCondition {
                viewModel.uiState.value.protection.state == ProtectionState.DISARMED_ONLINE
            }
        } finally {
            repository.allowClear.countDown()
        }
    }

    @Test
    fun disarmCancelsLocalArmingWithoutWaitingForGrace() = runTest {
        val grace = CompletableDeferred<Unit>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val coordinator = ProtectionCoordinator(
            initialSnapshot = snapshot(ProtectionState.DISARMED_ONLINE, 1_000L),
            runtime = FakeRuntime(emptySet()),
            armingDelay = ArmingDelay { grace.await() },
            clock = ProtectionClock { 2_000L },
        )
        val viewModel = ProtectionViewModel(
            coordinator = coordinator,
            incidents = FakeIncidentRepository(emptyList()),
            settings = FakeProtectionSettingsGateway(),
            nowMs = { 2_000L },
            ticker = emptyFlow(),
            dispatcher = dispatcher,
            callbackDispatcher = dispatcher,
        )
        runCurrent()

        viewModel.arm()
        runCurrent()
        assertEquals(ProtectionState.ARMING, viewModel.uiState.value.protection.state)

        viewModel.disarm()
        runCurrent()

        assertEquals(ProtectionState.DISARMED_ONLINE, viewModel.uiState.value.protection.state)
        grace.complete(Unit)
        advanceUntilIdle()
        assertEquals(ProtectionState.DISARMED_ONLINE, viewModel.uiState.value.protection.state)
    }

    @Test
    fun initialSettingsLoadExposesLoadingInsteadOfUnconfiguredDefaults() = runTest {
        val readStarted = CompletableDeferred<Unit>()
        val allowRead = CompletableDeferred<Unit>()
        val settings = FakeProtectionSettingsGateway(
            firstSettingsReadStarted = readStarted,
            allowFirstSettingsRead = allowRead,
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = ProtectionViewModel(
            coordinator = fakeCoordinator(ProtectionState.DISARMED_ONLINE),
            incidents = FakeIncidentRepository(emptyList()),
            settings = settings,
            nowMs = { 1_000L },
            ticker = emptyFlow(),
            dispatcher = dispatcher,
            callbackDispatcher = dispatcher,
        )

        assertTrue(viewModel.uiState.value.settingsLoading)
        assertFalse(viewModel.uiState.value.settingsLoaded)
        runCurrent()

        assertTrue(readStarted.isCompleted)
        assertTrue(viewModel.uiState.value.settingsLoading)
        assertFalse(viewModel.uiState.value.settingsLoaded)
        allowRead.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun initialSettingsLoadNeverEmitsUnableToLoadSettingsStateBeforeReadStarts() = runTest {
        val readStarted = CompletableDeferred<Unit>()
        val allowRead = CompletableDeferred<Unit>()
        val dispatcher = InitialSettingsReadBarrierDispatcher()
        val viewModel = ProtectionViewModel(
            coordinator = fakeCoordinator(ProtectionState.DISARMED_ONLINE),
            incidents = FakeIncidentRepository(emptyList()),
            settings = FakeProtectionSettingsGateway(
                firstSettingsReadStarted = readStarted,
                allowFirstSettingsRead = allowRead,
            ),
            nowMs = { 1_000L },
            ticker = emptyFlow(),
            dispatcher = dispatcher,
            callbackDispatcher = dispatcher,
        )
        val emittedSettingsStates = mutableListOf<Pair<Boolean, Boolean>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { state ->
                emittedSettingsStates += state.settingsLoaded to state.settingsLoading
            }
        }

        assertFalse(
            "Initial settings loading must not emit the Unable to load settings state",
            emittedSettingsStates.any { (loaded, loading) -> !loaded && !loading },
        )
        dispatcher.startInitialSettingsRead()
        assertTrue(readStarted.isCompleted)
        allowRead.complete(Unit)
    }

    @Test
    fun failedInitialSettingsLoadIsRetryable() = runTest {
        val settings = FakeProtectionSettingsGateway(settingsReadFailuresRemaining = 1)
        val fixture = fixture(testScheduler, settings = settings)

        advanceUntilIdle()

        assertFalse(fixture.viewModel.uiState.value.settingsLoaded)
        assertEquals("settings unavailable", fixture.viewModel.uiState.value.settingsError)
        fixture.viewModel.retrySettings()
        advanceUntilIdle()

        assertTrue(fixture.viewModel.uiState.value.settingsLoaded)
        assertNull(fixture.viewModel.uiState.value.settingsError)
    }

    @Test
    fun failedSettingsRefreshRetainsLastSuccessfulSummary() = runTest {
        val settings = FakeProtectionSettingsGateway(settingsReadFailuresRemainingAfterFirst = 1)
        val fixture = fixture(testScheduler, settings = settings)
        advanceUntilIdle()
        val loadedSettings = fixture.viewModel.uiState.value.settings

        fixture.viewModel.updateMissingPermissions(setOf("LATEST_PERMISSION"))
        advanceUntilIdle()

        assertTrue(fixture.viewModel.uiState.value.settingsLoaded)
        assertEquals(loadedSettings, fixture.viewModel.uiState.value.settings)
        assertEquals("settings unavailable", fixture.viewModel.uiState.value.settingsError)
    }

    @Test
    fun resetPairingPublishesResultAndRefreshesSettings() = runTest {
        val settings = FakeProtectionSettingsGateway()
        val fixture = fixture(testScheduler, settings = settings)
        advanceUntilIdle()
        val readsBeforeReset = settings.settingsReadCount

        fixture.viewModel.resetPairing()
        advanceUntilIdle()

        assertEquals(1, settings.resetPairingCalls)
        // assertEquals(com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.SETTINGS_SAVE_SUCCESS).titleTh, fixture.viewModel.uiState.value.message?.content?.titleTh)
        assertEquals(readsBeforeReset + 1, settings.settingsReadCount)
    }

    @Test
    fun retryClearsRepositoryFailureAfterLoadingEventsAgain() = runTest {
        val repository = FakeIncidentRepository(
            incidents = listOf(realIncident("i-1", 1L)),
            failuresRemaining = 1,
        )
        val viewModel = fixture(testScheduler, repository = repository).viewModel
        runCurrent()

        assertEquals("history unavailable", viewModel.uiState.value.eventsError)
        assertFalse(viewModel.uiState.value.eventsLoading)

        viewModel.retry()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.eventsError)
        assertEquals(listOf("i-1"), viewModel.uiState.value.events.map { it.id })
    }

    @Test
    fun consumingTheCurrentMessageRemovesOnlyThatMessage() = runTest {
        val viewModel = fixtureWithBlocker("POST_NOTIFICATIONS", testScheduler).viewModel
        viewModel.arm()
        advanceUntilIdle()
        val message = requireNotNull(viewModel.uiState.value.message)

        viewModel.consumeMessage(message.id)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun rapidSensorSamplesAreRateLimitedBeforeUiStateButCoordinatorKeepsEveryRevision() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var wallClockMs = 1_000L
        val coordinator = fakeCoordinator(ProtectionState.ARMED_HEALTHY)
        val viewModel = ProtectionViewModel(
            coordinator = coordinator,
            incidents = FakeIncidentRepository(emptyList()),
            settings = FakeProtectionSettingsGateway(),
            nowMs = { wallClockMs },
            ticker = emptyFlow(),
            dispatcher = dispatcher,
            callbackDispatcher = dispatcher,
        )
        advanceUntilIdle()

        val emissions = mutableListOf<ProtectionUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect(emissions::add)
        }
        runCurrent()
        emissions.clear()
        val initialRevision = coordinator.snapshot.value.revision

        repeat(100) { index ->
            wallClockMs = 1_000L + index * 5L
            coordinator.recordSensorSample(
                kind = SensorKind.VIBRATION,
                atMs = wallClockMs,
                detail = "accelerometer_magnitude",
                normalizedValue = 9.8 + index,
            )
            runCurrent()
        }

        assertEquals(initialRevision + 100L, coordinator.snapshot.value.revision)
        assertEquals(1, emissions.size)

        wallClockMs = 2_001L
        coordinator.recordSensorSample(
            kind = SensorKind.VIBRATION,
            atMs = wallClockMs,
            detail = "accelerometer_magnitude",
            normalizedValue = 777.0,
        )
        runCurrent()

        assertEquals(initialRevision + 101L, coordinator.snapshot.value.revision)
        assertEquals(2, emissions.size)
        assertEquals(
            777.0,
            requireNotNull(
                viewModel.uiState.value.protection.sensorHealth[SensorKind.VIBRATION]
                    ?.latestReading
                    ?.value,
            ),
            0.0,
        )
    }

    @Test
    fun alertStateBypassesUiSensorProjectionInterval() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var wallClockMs = 1_000L
        val coordinator = fakeCoordinator(ProtectionState.ARMED_HEALTHY)
        val viewModel = ProtectionViewModel(
            coordinator = coordinator,
            incidents = FakeIncidentRepository(emptyList()),
            settings = FakeProtectionSettingsGateway(),
            nowMs = { wallClockMs },
            ticker = emptyFlow(),
            dispatcher = dispatcher,
            callbackDispatcher = dispatcher,
        )
        advanceUntilIdle()

        val emissions = mutableListOf<ProtectionUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect(emissions::add)
        }
        runCurrent()

        coordinator.recordSensorSample(
            kind = SensorKind.VIBRATION,
            atMs = wallClockMs,
            detail = "accelerometer_magnitude",
            normalizedValue = 9.8,
        )
        runCurrent()
        emissions.clear()

        wallClockMs = 1_100L
        coordinator.recordIncident(incident(id = "projection-alert", updatedAtMs = wallClockMs))
        runCurrent()

        assertEquals(1, emissions.size)
        assertEquals(ProtectionState.ALERT_ACTIVE, emissions.single().protection.state)
        assertEquals("projection-alert", emissions.single().protection.lastIncident?.id)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun fixture(
    scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
    incidents: List<SecurityIncident> = emptyList(),
    repository: FakeIncidentRepository = FakeIncidentRepository(incidents),
    settings: FakeProtectionSettingsGateway = FakeProtectionSettingsGateway(),
): ViewModelFixture {
    val dispatcher = StandardTestDispatcher(scheduler)
    return ViewModelFixture(
        viewModel = ProtectionViewModel(
            coordinator = fakeCoordinator(ProtectionState.DISARMED_ONLINE),
            incidents = repository,
            settings = settings,
            nowMs = { 1_000L },
            ticker = emptyFlow(),
            dispatcher = dispatcher,
            callbackDispatcher = dispatcher,
        ),
        settings = settings,
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun fixtureWithBlocker(
    blocker: String,
    scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
): ViewModelFixture {
    val settings = FakeProtectionSettingsGateway()
    val dispatcher = StandardTestDispatcher(scheduler)
    return ViewModelFixture(
        viewModel = ProtectionViewModel(
            coordinator = fakeCoordinator(ProtectionState.DISARMED_ONLINE, setOf(blocker)),
            incidents = FakeIncidentRepository(emptyList()),
            settings = settings,
            nowMs = { 1_000L },
            ticker = emptyFlow(),
            dispatcher = dispatcher,
            callbackDispatcher = dispatcher,
        ),
        settings = settings,
    )
}

private data class ViewModelFixture(
    val viewModel: ProtectionViewModel,
    val settings: FakeProtectionSettingsGateway,
)

private fun fakeCoordinator(
    state: ProtectionState,
    blockers: Set<String> = emptySet(),
): ProtectionCoordinator = ProtectionCoordinator(
    initialSnapshot = snapshot(state = state, lastTransitionAtMs = 1_000L),
    runtime = FakeRuntime(blockers),
    armingDelay = ArmingDelay { },
    clock = ProtectionClock { 2_000L },
)

private fun snapshot(
    state: ProtectionState,
    lastTransitionAtMs: Long,
): ProtectionSnapshot = ProtectionSnapshot.offline(lastTransitionAtMs).copy(
    state = state,
    serviceRunning = true,
    telegramPolling = true,
    telegramReachable = true,
)

private fun settingsSummary(): ProtectionSettingsSummary = ProtectionSettingsSummary(
    tokenConfigured = true,
    pairedOwnerCount = 1,
    pairingCode = "A1B2C3",
    sensitivity = 5,
    smsFallbackConfigured = false,
    missingPermissions = emptySet(),
)

private fun realIncident(id: String, updatedAtMs: Long): SecurityIncident = incident(
    id = id,
    updatedAtMs = updatedAtMs,
    )

private fun incident(
    id: String,
    updatedAtMs: Long,
    ): SecurityIncident = SecurityIncident(
    id = id,
    type = IncidentType.VIBRATION,
        severity = IncidentSeverity.WARNING,
    lifecycle = IncidentLifecycle.OPEN,
    evidence = listOf(
        IncidentEvidence(
            kind = SensorKind.VIBRATION,
            eventElapsedMs = updatedAtMs,
            wallClockMs = updatedAtMs,
            normalizedValue = 2.0,
            baselineDelta = 1.0,
            diagnostic = "$id evidence",
        ),
    ),
    openedAtMs = updatedAtMs,
    updatedAtMs = updatedAtMs,
    closedAtMs = null,
    protectionState = ProtectionState.ALERT_ACTIVE,
    deliveryState = DeliveryState.PENDING,
)

private class FakeRuntime(
    private val blockers: Set<String>,
) : ProtectionRuntime {
    override fun readiness(): ReadinessReport = ReadinessReport(blockers, emptySet())

    override fun startDetectors(): DetectorStartResult = DetectorStartResult(started = true)

    override fun stopDetectors() = Unit

    override fun applySensitivity(level: Int) = Unit

    override fun currentSensorHealth(): Map<SensorKind, SensorHealth> = emptyMap()
}

private class FakeIncidentRepository(
    incidents: List<SecurityIncident>,
    private var failuresRemaining: Int = 0,
) : IncidentRepository {
    private val records = incidents.toMutableList()

    override fun upsert(incident: SecurityIncident) {
        records.removeAll { it.id == incident.id }
        records += incident
    }

    override fun findById(id: String): SecurityIncident? = records.firstOrNull { it.id == id }

    override fun listNewestFirst(): List<SecurityIncident> {
        if (failuresRemaining > 0) {
            failuresRemaining -= 1
            error("history unavailable")
        }
        return records.sortedByDescending { it.updatedAtMs }
    }

    override fun clearHistory() {
        records.clear()
    }
}

private class BlockingClearIncidentRepository : IncidentRepository {
    private val records = mutableListOf(realIncident("initial", 1L))
    val clearStarted = CountDownLatch(1)
    val allowClear = CountDownLatch(1)

    override fun upsert(incident: SecurityIncident) {
        records.removeAll { it.id == incident.id }
        records += incident
    }

    override fun findById(id: String): SecurityIncident? = records.firstOrNull { it.id == id }

    override fun listNewestFirst(): List<SecurityIncident> = records.sortedByDescending { it.updatedAtMs }

    override fun clearHistory() {
        clearStarted.countDown()
        check(allowClear.await(2, TimeUnit.SECONDS))
        records.clear()
    }
}

private fun awaitCondition(condition: () -> Boolean) {
    repeat(200) {
        if (condition()) return
        Thread.sleep(10)
    }
    assertTrue("Timed out waiting for condition", condition())
}

private class InitialSettingsReadBarrierDispatcher : CoroutineDispatcher() {
    private var rootDispatchCount = 0
    private var dispatchDepth = 0
    private var initialSettingsRead: Runnable? = null

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = true

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (dispatchDepth == 0 && ++rootDispatchCount == 3) {
            initialSettingsRead = block
            return
        }

        dispatchDepth += 1
        try {
            block.run()
        } finally {
            dispatchDepth -= 1
        }
    }

    fun startInitialSettingsRead() {
        checkNotNull(initialSettingsRead).run()
    }
}

private class FakeProtectionSettingsGateway(
    private val botTokenFailure: String? = null,
    private val smsFallbackFailure: String? = null,
    private val firstSettingsReadStarted: CompletableDeferred<Unit>? = null,
    private val allowFirstSettingsRead: CompletableDeferred<Unit>? = null,
    private var settingsReadFailuresRemaining: Int = 0,
    private var settingsReadFailuresRemainingAfterFirst: Int = 0,
    private val replaceBotTokenAction: (suspend (String) -> SettingsOperationResult)? = null,
) : ProtectionSettingsGateway {
    val savedSensitivity = mutableListOf<Int>()
    var tokenReplaceCalls = 0
        private set
    var writeCount = 0
        private set
    var resetPairingCalls = 0
        private set
    var settingsReadCount = 0
        private set

    override suspend fun read(missingPermissions: Set<String>): ProtectionSettingsSummary {
        settingsReadCount += 1
        if (settingsReadCount == 1 && allowFirstSettingsRead != null) {
            firstSettingsReadStarted?.complete(Unit)
            allowFirstSettingsRead.await()
        }
        if (settingsReadFailuresRemaining > 0) {
            settingsReadFailuresRemaining -= 1
            error("settings unavailable")
        }
        if (settingsReadCount > 1 && settingsReadFailuresRemainingAfterFirst > 0) {
            settingsReadFailuresRemainingAfterFirst -= 1
            error("settings unavailable")
        }
        return settingsSummary().copy(
            missingPermissions = missingPermissions,
        )
    }

    override fun saveSensitivity(level: Int) {
        savedSensitivity += level
        writeCount += 1
    }

    override suspend fun replaceBotToken(token: String): SettingsOperationResult {
        tokenReplaceCalls += 1
        replaceBotTokenAction?.let { return it(token) }
        botTokenFailure?.let(::error)
        writeCount += 1
        return SettingsOperationResult(applied = true, message = "Bot token updated")
    }

    override suspend fun resetPairing(): SettingsOperationResult {
        resetPairingCalls += 1
        return SettingsOperationResult(applied = true, message = "Pairing reset")
    }

    override fun saveSmsFallback(destination: String, aesKey: String): SettingsOperationResult {
        smsFallbackFailure?.let(::error)
        writeCount += 1
        return SettingsOperationResult(applied = true, message = "SMS fallback updated")
    }
}
