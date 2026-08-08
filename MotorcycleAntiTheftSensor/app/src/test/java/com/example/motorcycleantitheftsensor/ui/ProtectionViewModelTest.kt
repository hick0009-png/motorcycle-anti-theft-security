package com.example.motorcycleantitheftsensor.ui

import com.example.motorcycleantitheftsensor.protection.ArmingDelay
import com.example.motorcycleantitheftsensor.protection.CommandOrigin
import com.example.motorcycleantitheftsensor.protection.DeliveryState
import com.example.motorcycleantitheftsensor.protection.DetectorStartResult
import com.example.motorcycleantitheftsensor.protection.IncidentEvidence
import com.example.motorcycleantitheftsensor.protection.IncidentLifecycle
import com.example.motorcycleantitheftsensor.protection.IncidentRepository
import com.example.motorcycleantitheftsensor.protection.IncidentSeverity
import com.example.motorcycleantitheftsensor.protection.IncidentSource
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertEquals("VIBRATION: newer evidence", viewModel.uiState.value.events.first().evidenceSummary)
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
        assertEquals("Missing required: POST_NOTIFICATIONS", viewModel.uiState.value.message?.text)
        assertTrue(viewModel.uiState.value.message?.isError == true)
    }

    @Test
    fun invalidSensitivityNeverWritesSettings() = runTest {
        val fixture = fixture(testScheduler)

        fixture.viewModel.changeSensitivity(11)
        advanceUntilIdle()

        assertEquals(emptyList<Int>(), fixture.settings.savedSensitivity)
        assertTrue(fixture.viewModel.uiState.value.message?.isError == true)
        assertEquals("Sensitivity must be 1-10", fixture.viewModel.uiState.value.message?.text)
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

        assertEquals("Unable to update bot token", fixture.viewModel.uiState.value.message?.text)
        assertFalse(fixture.viewModel.uiState.value.message?.text.orEmpty().contains(secret))
        assertFalse(fixture.viewModel.uiState.value.toString().contains(secret))
    }

    @Test
    fun blankReplacementTokenIsRejectedWithoutGatewayCall() = runTest {
        val fixture = fixture(testScheduler)

        fixture.viewModel.replaceBotToken("   ")
        advanceUntilIdle()

        assertEquals(0, fixture.settings.tokenReplaceCalls)
        assertTrue(fixture.viewModel.uiState.value.message?.isError == true)
    }

    @Test
    fun authenticatorSetupRunsAsynchronouslyWithoutEnteringUiState() = runTest {
        val secret = "transient-authenticator-secret"
        val settings = FakeProtectionSettingsGateway(authenticatorSetupSecret = secret)
        val fixture = fixture(testScheduler, settings = settings)
        var completedSecret: String? = null

        fixture.viewModel.beginAuthenticatorSetup { completedSecret = it }

        assertEquals(0, settings.authenticatorSetupCalls)
        assertNull(completedSecret)
        advanceUntilIdle()
        assertEquals(1, settings.authenticatorSetupCalls)
        assertEquals(secret, completedSecret)
        assertFalse(fixture.viewModel.uiState.value.toString().contains(secret))
    }

    @Test
    fun successfulAuthenticatorVerificationRefreshesConfiguredStateBeforeCompletion() = runTest {
        val verification = CompletableDeferred<Boolean>()
        val settings = FakeProtectionSettingsGateway(
            authenticatorConfigured = false,
            authenticatorVerification = verification,
        )
        val fixture = fixture(testScheduler, settings = settings)
        runCurrent()
        var completion: Boolean? = null

        fixture.viewModel.verifyAuthenticator("123456") { verified -> completion = verified }
        runCurrent()

        assertFalse(fixture.viewModel.uiState.value.settings.authenticatorConfigured)
        assertNull(completion)
        verification.complete(true)
        advanceUntilIdle()
        assertTrue(fixture.viewModel.uiState.value.settings.authenticatorConfigured)
        assertEquals(true, completion)
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

        assertEquals("Unable to configure SMS fallback", fixture.viewModel.uiState.value.message?.text)
        assertFalse(fixture.viewModel.uiState.value.message?.text.orEmpty().contains(secret))
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
    authenticatorConfigured = true,
    sensitivity = 5,
    smsFallbackConfigured = false,
    missingPermissions = emptySet(),
)

private fun realIncident(id: String, updatedAtMs: Long): SecurityIncident = incident(
    id = id,
    updatedAtMs = updatedAtMs,
    source = IncidentSource.REAL,
)

private fun incident(
    id: String,
    updatedAtMs: Long,
    source: IncidentSource,
): SecurityIncident = SecurityIncident(
    id = id,
    type = IncidentType.VIBRATION,
    source = source,
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

private class FakeProtectionSettingsGateway(
    private val botTokenFailure: String? = null,
    private val smsFallbackFailure: String? = null,
    private var authenticatorConfigured: Boolean = true,
    private val authenticatorSetupSecret: String? = null,
    private val authenticatorVerification: CompletableDeferred<Boolean>? = null,
    private val firstSettingsReadStarted: CompletableDeferred<Unit>? = null,
    private val allowFirstSettingsRead: CompletableDeferred<Unit>? = null,
) : ProtectionSettingsGateway {
    val savedSensitivity = mutableListOf<Int>()
    var tokenReplaceCalls = 0
        private set
    var writeCount = 0
        private set
    var authenticatorSetupCalls = 0
        private set
    private var settingsReadCount = 0

    override suspend fun read(missingPermissions: Set<String>): ProtectionSettingsSummary {
        settingsReadCount += 1
        if (settingsReadCount == 1 && allowFirstSettingsRead != null) {
            firstSettingsReadStarted?.complete(Unit)
            allowFirstSettingsRead.await()
        }
        return settingsSummary().copy(
            authenticatorConfigured = authenticatorConfigured,
            missingPermissions = missingPermissions,
        )
    }

    override fun saveSensitivity(level: Int) {
        savedSensitivity += level
        writeCount += 1
    }

    override suspend fun replaceBotToken(token: String): SettingsOperationResult {
        tokenReplaceCalls += 1
        botTokenFailure?.let(::error)
        writeCount += 1
        return SettingsOperationResult(applied = true, message = "Bot token updated")
    }

    override fun saveSmsFallback(destination: String, aesKey: String): SettingsOperationResult {
        smsFallbackFailure?.let(::error)
        writeCount += 1
        return SettingsOperationResult(applied = true, message = "SMS fallback updated")
    }

    override suspend fun beginAuthenticatorSetup(): String? {
        authenticatorSetupCalls += 1
        return authenticatorSetupSecret
    }

    override suspend fun verifyAuthenticator(code: String): Boolean {
        val verified = authenticatorVerification?.await() ?: false
        if (verified) authenticatorConfigured = true
        return verified
    }
}
