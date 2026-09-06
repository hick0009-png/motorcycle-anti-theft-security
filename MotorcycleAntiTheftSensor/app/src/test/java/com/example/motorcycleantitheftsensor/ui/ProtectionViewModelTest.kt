package com.example.motorcycleantitheftsensor.ui

import com.example.motorcycleantitheftsensor.protection.ArmingDelay
import com.example.motorcycleantitheftsensor.protection.AudioGateState
import com.example.motorcycleantitheftsensor.protection.AudioRuntimeState
import com.example.motorcycleantitheftsensor.protection.AudioTelemetry
import com.example.motorcycleantitheftsensor.protection.AudioThreatCategory
import com.example.motorcycleantitheftsensor.protection.AudioThreatMetadata
import com.example.motorcycleantitheftsensor.protection.ChargingState
import com.example.motorcycleantitheftsensor.protection.CommandOrigin
import com.example.motorcycleantitheftsensor.protection.DeliveryState
import com.example.motorcycleantitheftsensor.protection.POWER_CHALLENGE_DEGRADED
import com.example.motorcycleantitheftsensor.protection.PowerArmChallengeRegistry
import com.example.motorcycleantitheftsensor.protection.PowerThermalHealthDetail
import com.example.motorcycleantitheftsensor.protection.PowerWitnessCommissioningPolicy
import com.example.motorcycleantitheftsensor.protection.PowerWitnessModel
import com.example.motorcycleantitheftsensor.protection.PowerWitnessSample
import com.example.motorcycleantitheftsensor.protection.DetectorStartResult
import com.example.motorcycleantitheftsensor.protection.EntryDriftMeasurement
import com.example.motorcycleantitheftsensor.protection.GuidanceCode
import com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog
import com.example.motorcycleantitheftsensor.protection.EntryDriftMeasurementStore
import com.example.motorcycleantitheftsensor.protection.EntryOrientationSample
import com.example.motorcycleantitheftsensor.protection.EntryProfileSettings
import com.example.motorcycleantitheftsensor.protection.EntryQuaternion
import com.example.motorcycleantitheftsensor.protection.IncidentEvidence
import com.example.motorcycleantitheftsensor.protection.IncidentLifecycle
import com.example.motorcycleantitheftsensor.protection.IncidentRepository
import com.example.motorcycleantitheftsensor.protection.IncidentSeverity
import com.example.motorcycleantitheftsensor.protection.IncidentType
import com.example.motorcycleantitheftsensor.protection.LightHealthDetail
import com.example.motorcycleantitheftsensor.protection.ProfileDeviceSupport
import com.example.motorcycleantitheftsensor.protection.ProfileSupportReason
import com.example.motorcycleantitheftsensor.protection.ProtectionClock
import com.example.motorcycleantitheftsensor.protection.ProtectionCoordinator
import com.example.motorcycleantitheftsensor.protection.ProtectionProfile
import com.example.motorcycleantitheftsensor.protection.ProtectionProfilePolicy
import com.example.motorcycleantitheftsensor.protection.ProtectionProfileRepository
import com.example.motorcycleantitheftsensor.protection.ProtectionProfileStoreState
import com.example.motorcycleantitheftsensor.protection.ProfileSetupState
import com.example.motorcycleantitheftsensor.protection.ProtectionRuntime
import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshot
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import com.example.motorcycleantitheftsensor.protection.ReadinessReport
import com.example.motorcycleantitheftsensor.protection.SecurityIncident
import com.example.motorcycleantitheftsensor.protection.SensorHealth
import com.example.motorcycleantitheftsensor.protection.SensorHealthState
import com.example.motorcycleantitheftsensor.protection.SensorKind
import com.example.motorcycleantitheftsensor.protection.SensorConfigurationPolicy
import com.example.motorcycleantitheftsensor.protection.SensorFusionConfiguration
import com.example.motorcycleantitheftsensor.protection.SensorPreset
import com.example.motorcycleantitheftsensor.protection.SensorSource
import com.example.motorcycleantitheftsensor.sensor.SensorCatalog
import com.example.motorcycleantitheftsensor.sensor.SensorDescriptor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        assertFalse(viewModel.uiState.value.toString().contains("Demo", ignoreCase = true))
    }

    @Test
    fun newlyPersistedIncidentReachesTheEventListWithoutAManualRetry() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val incidents = FakeIncidentRepository(listOf(realIncident("older", 1_000L)))
        val viewModel = ProtectionViewModel(
            coordinator = fakeCoordinator(ProtectionState.ARMED_HEALTHY),
            incidents = incidents,
            settings = FakeProtectionSettingsGateway(),
            nowMs = { 2_500L },
            ticker = emptyFlow(),
            dispatcher = dispatcher,
            callbackDispatcher = dispatcher,
        )
        advanceUntilIdle()
        assertEquals(listOf("older"), viewModel.uiState.value.events.map { it.id })

        incidents.upsert(realIncident("newer", 2_000L))
        advanceUntilIdle()

        assertEquals(listOf("newer", "older"), viewModel.uiState.value.events.map { it.id })
        assertFalse(viewModel.uiState.value.eventsLoading)
    }

    @Test
    fun aFailedBackgroundRefreshKeepsTheEventsAlreadyOnScreen() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val incidents = FakeIncidentRepository(listOf(realIncident("older", 1_000L)))
        val viewModel = ProtectionViewModel(
            coordinator = fakeCoordinator(ProtectionState.ARMED_HEALTHY),
            incidents = incidents,
            settings = FakeProtectionSettingsGateway(),
            nowMs = { 2_500L },
            ticker = emptyFlow(),
            dispatcher = dispatcher,
            callbackDispatcher = dispatcher,
        )
        advanceUntilIdle()

        incidents.failuresRemaining = 1
        incidents.upsert(realIncident("newer", 2_000L))
        advanceUntilIdle()

        // The owner never asked for this read, so a failure must not blank the list or
        // raise the retry banner over events that are still perfectly valid.
        assertEquals(listOf("older"), viewModel.uiState.value.events.map { it.id })
        assertNull(viewModel.uiState.value.eventsError)
        assertFalse(viewModel.uiState.value.eventsLoading)
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
    }

    @Test
    fun invalidSensitivityNeverWritesSettings() = runTest {
        val fixture = fixture(testScheduler)

        fixture.viewModel.changeSensitivity(11)
        advanceUntilIdle()

        assertEquals(emptyList<Int>(), fixture.settings.savedSensitivity)
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
    fun updateSensorConfigurationWritesSettingsAndPublishesApplied() = runTest {
        val fixture = fixture(testScheduler)
        val policy = SensorConfigurationPolicy()
        val config = policy.forPreset(SensorPreset.MAXIMUM_PROTECTION)

        fixture.viewModel.updateSensorConfiguration(config)
        advanceUntilIdle()

        assertEquals(listOf(config), fixture.settings.savedSensorConfigurations)
    }

    @Test
    fun applySensorPresetAppliesCorrespondingPresetConfiguration() = runTest {
        val fixture = fixture(testScheduler)

        fixture.viewModel.applySensorPreset(SensorPreset.BATTERY_SAVER)
        advanceUntilIdle()

        assertEquals(1, fixture.settings.savedSensorConfigurations.size)
        assertEquals(SensorPreset.BATTERY_SAVER, fixture.settings.savedSensorConfigurations.first().basePreset)
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

        fixture.viewModel.configureSmsFallback("+15555550123")
        advanceUntilIdle()

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

    @Test
    fun exposesAudioTelemetryFlow() = runTest {
        val fixture = fixture(testScheduler)
        val telemetry = fixture.viewModel.audioTelemetry.value
        assertEquals(AudioRuntimeState.OFF, telemetry.state)
    }

    // --- Task 2: Audio UI Projection and Microphone Health Tests ---

    @Test
    fun audioUiProjectionMapsOffWithoutCallingItAvailable() {
        val domain = AudioTelemetry.off()
        val ui = domain.toAudioUiTelemetry(elapsedNowMs = 1_000L)
        assertEquals(AudioRuntimeState.OFF, ui.state)
        assertFalse(ui.modelReady)
    }

    @Test
    fun audioUiProjectionCalculatesNonNegativeSampleAgeAndCandidateExpiry() {
        val domain = AudioTelemetry.off().copy(
            state = AudioRuntimeState.LISTENING,
            detailCode = "OK",
            modelReady = true,
            lastSampleAtMs = 5_000L,
            approximateLevelDbfs = -25.5,
            baselineMedianDbfs = -40.0,
            baselineP95Dbfs = -30.0,
            gateState = AudioGateState.OPEN,
            lastInferenceMs = 12L,
            averageInferenceMs = 14L,
            droppedFrames = 0L,
            restartCount = 0,
            currentCandidate = AudioThreatMetadata(
                category = AudioThreatCategory.IMPACT,
                confidence = 0.85,
                loudnessDeltaDb = 15.0,
                firstDetectedElapsedMs = 7_000L,
                lastDetectedElapsedMs = 8_000L,
                occurrenceCount = 1,
                onsetElapsedMs = 7_000L,
                onsetCoherent = true,
            ),
        )
        val ui = domain.toAudioUiTelemetry(elapsedNowMs = 10_000L)
        assertEquals(5L, ui.lastSampleAgeSeconds)
        assertEquals(13, ui.candidateExpiresInSeconds)
        assertEquals(AudioThreatCategory.IMPACT, ui.currentCandidate?.category)
    }

    @Test
    fun audioUiProjectionRejectsFutureMonotonicTimestamps() {
        val domain = AudioTelemetry.off().copy(
            state = AudioRuntimeState.LISTENING,
            detailCode = "OK",
            modelReady = true,
            lastSampleAtMs = 15_000L,
            approximateLevelDbfs = -25.5,
            baselineMedianDbfs = -40.0,
            baselineP95Dbfs = -30.0,
            gateState = AudioGateState.OPEN,
            lastInferenceMs = 12L,
            averageInferenceMs = 14L,
            droppedFrames = 0L,
            restartCount = 0,
            currentCandidate = AudioThreatMetadata(
                category = AudioThreatCategory.IMPACT,
                confidence = 0.85,
                loudnessDeltaDb = 15.0,
                firstDetectedElapsedMs = 12_000L,
                lastDetectedElapsedMs = 14_000L,
                occurrenceCount = 1,
                onsetElapsedMs = 12_000L,
                onsetCoherent = true,
            ),
        )
        val ui = domain.toAudioUiTelemetry(elapsedNowMs = 10_000L)
        assertNull(ui.lastSampleAgeSeconds)
        assertNull(ui.currentCandidate)
        assertNull(ui.candidateExpiresInSeconds)
    }

    @Test
    fun audioUiProjectionDropsExpiredCandidate() {
        val domain = AudioTelemetry.off().copy(
            state = AudioRuntimeState.LISTENING,
            detailCode = "OK",
            modelReady = true,
            lastSampleAtMs = 10_000L,
            approximateLevelDbfs = -25.5,
            baselineMedianDbfs = -40.0,
            baselineP95Dbfs = -30.0,
            gateState = AudioGateState.OPEN,
            lastInferenceMs = 12L,
            averageInferenceMs = 14L,
            droppedFrames = 0L,
            restartCount = 0,
            currentCandidate = AudioThreatMetadata(
                category = AudioThreatCategory.IMPACT,
                confidence = 0.85,
                loudnessDeltaDb = 15.0,
                firstDetectedElapsedMs = 1_000L,
                lastDetectedElapsedMs = 2_000L,
                occurrenceCount = 1,
                onsetElapsedMs = 1_000L,
                onsetCoherent = true,
            ),
        )
        val ui = domain.toAudioUiTelemetry(elapsedNowMs = 20_000L)
        assertNull(ui.currentCandidate)
        assertNull(ui.candidateExpiresInSeconds)
    }

    @Test
    fun microphoneHealthTextDistinguishesDetectedUnavailableStaleAndFailed() {
        assertEquals("ตรวจพบไมโครโฟน", microphoneHealthText(SensorHealth(SensorHealthState.AVAILABLE)))
                assertEquals("ตรวจพบไมโครโฟน", microphoneHealthText(SensorHealth(SensorHealthState.HEALTHY)))
                assertEquals("ไมโครโฟนไม่พร้อมใช้งาน", microphoneHealthText(SensorHealth(SensorHealthState.UNAVAILABLE)))
                assertEquals("ข้อมูลไมโครโฟนไม่ใหม่", microphoneHealthText(SensorHealth(SensorHealthState.STALE)))
                assertEquals("ไมโครโฟนทำงานผิดพลาด", microphoneHealthText(SensorHealth(SensorHealthState.FAILED)))
                assertEquals("สถานะไมโครโฟนไม่ทราบ", microphoneHealthText(null))
    }

    // --- Task 3: Operation Ownership & Self-Test Result Persistence Tests ---

    @Test
    fun smsSaveOwnsOnlySaveSmsFallbackOperation() {
        val smsStarted = CountDownLatch(1)
        val allowSmsSave = CountDownLatch(1)
        val settings = FakeProtectionSettingsGateway(
            smsFallbackStarted = smsStarted,
            allowSmsFallbackLatch = allowSmsSave,
        )
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return ProtectionViewModel(
                    coordinator = fakeCoordinator(ProtectionState.DISARMED_ONLINE),
                    incidents = FakeIncidentRepository(),
                    settings = settings,
                    nowMs = { 1_000L },
                    ticker = emptyFlow(),
                    dispatcher = Dispatchers.Default,
                    callbackDispatcher = Dispatchers.Default,
                ) as T
            }
        }
        val vm = ViewModelProvider(store, factory)[ProtectionViewModel::class.java]

        try {
            vm.configureSmsFallback("+15555550123")

            assertTrue("Fake SMS save should have started", smsStarted.await(2, TimeUnit.SECONDS))

            // Poll for settingsOperationInFlight — the combine StateFlow may lag slightly
            val inflightDeadline = System.currentTimeMillis() + 2_000L
            while (!vm.uiState.value.settingsOperationInFlight && System.currentTimeMillis() < inflightDeadline) {
                Thread.sleep(10)
            }
            assertTrue(vm.uiState.value.settingsOperationInFlight)
            assertEquals(SettingsOperation.SAVE_SMS_FALLBACK, vm.uiState.value.activeSettingsOperation)

            allowSmsSave.countDown()
            // Wait for the operation to complete
            val deadline = System.currentTimeMillis() + 2_000L
            while (vm.uiState.value.settingsOperationInFlight && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }

            assertFalse(vm.uiState.value.settingsOperationInFlight)
            assertNull(vm.uiState.value.activeSettingsOperation)
        } finally {
            allowSmsSave.countDown() // ensure no hang on failure
            store.clear()
        }
    }

    @Test
    fun botTokenSaveOwnsOnlyReplaceBotTokenOperation() = runTest {
        val gate = CompletableDeferred<Unit>()
        val settings = FakeProtectionSettingsGateway(
            allowBotToken = gate,
        )
        val fixture = fixture(testScheduler, settings = settings)
        fixture.viewModel.replaceBotToken("new-token")
        runCurrent()

        assertTrue(fixture.viewModel.uiState.value.settingsOperationInFlight)
        assertEquals(SettingsOperation.REPLACE_BOT_TOKEN, fixture.viewModel.uiState.value.activeSettingsOperation)

        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(fixture.viewModel.uiState.value.settingsOperationInFlight)
        assertNull(fixture.viewModel.uiState.value.activeSettingsOperation)
    }

    @Test
    fun audioTelemetryProjectsOnlyWhenUiTickerOrOtherUiInputAdvances() = runTest {
        val tickerFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var wallClockMs = 1_000L
        val runtime = FakeRuntime(emptySet())
        val coordinator = ProtectionCoordinator(
            initialSnapshot = snapshot(ProtectionState.ARMED_HEALTHY, 1_000L),
            runtime = runtime,
            armingDelay = ArmingDelay { },
            clock = ProtectionClock { wallClockMs },
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = ProtectionViewModel(
            coordinator = coordinator,
            incidents = FakeIncidentRepository(emptyList()),
            settings = FakeProtectionSettingsGateway(),
            nowMs = { wallClockMs },
            ticker = tickerFlow,
            dispatcher = dispatcher,
            callbackDispatcher = dispatcher,
            elapsedNowMs = { wallClockMs },
        )
        advanceUntilIdle()

        val audioEmissions = mutableListOf<AudioUiTelemetry>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { audioEmissions += it.audio }
        }
        runCurrent()
        audioEmissions.clear()

        // Rapidly emit 100 raw audio telemetry values without ticking the UI
        repeat(100) { i ->
            runtime.emitAudioTelemetry(
                AudioTelemetry.off().copy(
                    state = AudioRuntimeState.LISTENING,
                    approximateLevelDbfs = -30.0 + i,
                    lastSampleAtMs = wallClockMs,
                ),
            )
            runCurrent()
        }

        // UI state did not emit 100 times for high-frequency raw audio
        assertEquals(0, audioEmissions.size)

        // Now increment wall clock and trigger one UI ticker
        wallClockMs = 2_000L
        tickerFlow.emit(Unit)
        runCurrent()

        // Audio projection updated exactly once to the latest telemetry
        assertEquals(1, audioEmissions.size)
        assertEquals(-30.0 + 99, audioEmissions.single().approximateLevelDbfs!!, 0.001)
    }

    @Test
    fun rapidDoubleTapArmDropsSecondInvocationWithoutError() = runTest {
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
        viewModel.arm() // Rapid double-tap
        runCurrent()

        assertEquals(ProtectionState.ARMING, viewModel.uiState.value.protection.state)
        assertNull(viewModel.uiState.value.message) // No error message published for dropped double-tap

        grace.complete(Unit)
        advanceUntilIdle()

        assertEquals(ProtectionState.ARMED_HEALTHY, viewModel.uiState.value.protection.state)
        assertEquals("เปิดการป้องกันแล้ว", viewModel.uiState.value.message?.content?.titleTh)
    }

    @Test
    fun interruptedIncidentResolvesCorrectIncidentTypeString() = runTest {
        val incident = SecurityIncident(
            id = "incident-interrupted",
            type = IncidentType.TAMPER,
            severity = IncidentSeverity.CRITICAL,
            lifecycle = IncidentLifecycle.INTERRUPTED,
            evidence = emptyList(),
            openedAtMs = 1_000L,
            updatedAtMs = 2_000L,
            closedAtMs = null,
            protectionState = ProtectionState.ALERT_ACTIVE,
            deliveryState = DeliveryState.SENT,
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = ProtectionViewModel(
            coordinator = fakeCoordinator(ProtectionState.ALERT_ACTIVE),
            incidents = FakeIncidentRepository(listOf(incident)),
            settings = FakeProtectionSettingsGateway(),
            nowMs = { 2_500L },
            ticker = emptyFlow(),
            dispatcher = dispatcher,
            callbackDispatcher = dispatcher,
        )
        runCurrent()

        val eventRow = viewModel.uiState.value.events.first()
        assertEquals(IncidentLifecycle.INTERRUPTED, eventRow.lifecycle)
        assertEquals("การงัดแงะหรือเปิดเบาะ", eventRow.evidenceSummary)
    }

    @Test
    fun aRefusedArmSaysArmingFailedRatherThanTheCatchAll() = runTest {
        // This pinned the opposite: an arm blocked by a missing permission reported
        // "คำสั่งไม่สำเร็จ", because the refusal was classified by searching its English text
        // for the word "Arm" and a permission blocker does not contain it.
        val fixture = fixtureWithBlocker("POST_NOTIFICATIONS", testScheduler)
        fixture.viewModel.arm()
        advanceUntilIdle()

        val title = fixture.viewModel.uiState.value.message?.content?.titleTh
        assertNotEquals("คำสั่งไม่สำเร็จ", title)
        assertEquals(
            UserGuidanceCatalog.content(GuidanceCode.COMMAND_ARM_REJECTED).titleTh,
            title,
        )
        assertEquals("ไม่สามารถเปิดการป้องกันได้", title)
        assertEquals(
            "ไม่สามารถปลดการป้องกันได้",
            UserGuidanceCatalog.content(GuidanceCode.COMMAND_DISARM_REJECTED).titleTh,
        )
    }

    @Test
    fun refusedProfileSaysWhatThisPhoneCannotDoRatherThanCommandFailed() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = fakeProfileRepository(selectedProfile = null)
        val viewModel = ProtectionViewModel(
            coordinator = fakeCoordinator(
                state = ProtectionState.DISARMED_ONLINE,
                profileRepository = repository,
                deviceSupport = { profile ->
                    if (profile == ProtectionProfile.ENTRY) {
                        ProfileDeviceSupport.Unsupported(
                            missing = emptySet(),
                            reason = ProfileSupportReason.DRIFT_TOO_FAST,
                            trustedHours = 1,
                        )
                    } else {
                        ProfileDeviceSupport.Supported
                    }
                },
            ),
            incidents = FakeIncidentRepository(emptyList()),
            settings = FakeProtectionSettingsGateway(),
            profileRepository = repository,
            nowMs = { 1_000L },
            ticker = emptyFlow(),
            dispatcher = dispatcher,
            callbackDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.selectProfile(ProtectionProfile.ENTRY)
        advanceUntilIdle()

        val content = viewModel.uiState.value.message?.content
        assertNotNull(content)
        // The refusal used to arrive as the catalogue's catch-all, which named neither the
        // use nor the reason and left the owner with nothing to do about it.
        assertNotEquals("คำสั่งไม่สำเร็จ", content!!.titleTh)
        assertTrue(
            "Refusal body should carry the measured-drift explanation: ${content.bodyTh}",
            content.bodyTh.contains("ไหลเอง"),
        )
    }

    @Test
    fun pickerRefusesTheDoorWatchOnAPhoneThatMeasuredItselfDrifting() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = fakeProfileRepository(selectedProfile = null)
        val store = FakeDriftMeasurementStore(
            EntryDriftMeasurement(
                sourceLabel = "game-rotation-vector",
                // Reaches a 15 degree alert angle in a quarter of an hour: below the two-hour floor.
                degPerHour = 60.0,
                measuredMs = 8L * 3_600_000L,
                measuredAtWallMs = 1_000L,
            ),
        )
        val viewModel = ProtectionViewModel(
            coordinator = fakeCoordinator(
                state = ProtectionState.DISARMED_ONLINE,
                profileRepository = repository,
            ),
            incidents = FakeIncidentRepository(emptyList()),
            settings = FakeProtectionSettingsGateway(),
            profileRepository = repository,
            sensorCatalog = FullSensorCatalog(),
            driftMeasurementStore = store,
            nowMs = { 1_000L },
            ticker = emptyFlow(),
            dispatcher = dispatcher,
            callbackDispatcher = dispatcher,
        )
        advanceUntilIdle()

        val support = viewModel.uiState.value.profileDeviceSupport.getValue(ProtectionProfile.ENTRY)
        assertTrue("Expected the picker to refuse, but got $support", support is ProfileDeviceSupport.Unsupported)
        assertEquals(
            ProfileSupportReason.DRIFT_TOO_FAST,
            (support as ProfileDeviceSupport.Unsupported).reason,
        )
        assertFalse(support.selectable)
    }

    @Test
    fun clearingTheMeasurementOffersTheDoorWatchAgain() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = fakeProfileRepository(selectedProfile = null)
        val store = FakeDriftMeasurementStore(
            EntryDriftMeasurement(
                sourceLabel = "game-rotation-vector",
                degPerHour = 60.0,
                measuredMs = 8L * 3_600_000L,
                measuredAtWallMs = 1_000L,
            ),
        )
        val viewModel = ProtectionViewModel(
            coordinator = fakeCoordinator(
                state = ProtectionState.DISARMED_ONLINE,
                profileRepository = repository,
            ),
            incidents = FakeIncidentRepository(emptyList()),
            settings = FakeProtectionSettingsGateway(),
            profileRepository = repository,
            sensorCatalog = FullSensorCatalog(),
            driftMeasurementStore = store,
            nowMs = { 1_000L },
            ticker = emptyFlow(),
            dispatcher = dispatcher,
            callbackDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.clearEntryDriftMeasurement()
        advanceUntilIdle()

        assertNull(store.load())
        assertEquals(
            ProfileDeviceSupport.Supported,
            viewModel.uiState.value.profileDeviceSupport.getValue(ProtectionProfile.ENTRY),
        )
    }

    @Test
    fun choosingAUseIsReportedAsChoosingAUseNotAsDisarming() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = fakeProfileRepository(selectedProfile = null)
        val viewModel = ProtectionViewModel(
            coordinator = fakeCoordinator(
                state = ProtectionState.DISARMED_ONLINE,
                profileRepository = repository,
            ),
            incidents = FakeIncidentRepository(emptyList()),
            settings = FakeProtectionSettingsGateway(),
            profileRepository = repository,
            nowMs = { 1_000L },
            ticker = emptyFlow(),
            dispatcher = dispatcher,
            callbackDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.selectProfile(ProtectionProfile.ENTRY)
        advanceUntilIdle()

        // An applied command used to be named after the state it left behind, and choosing a
        // use while disarmed leaves the system disarmed.
        val content = viewModel.uiState.value.message?.content
        assertNotNull(content)
        assertNotEquals("ปลดการป้องกันสำเร็จ", content!!.titleTh)
        assertEquals(
            UserGuidanceCatalog.content(GuidanceCode.PROFILE_SELECTED).titleTh,
            content.titleTh,
        )
    }

    // --- Task 7: Profile picker and armed-change confirmation ---

    @Test
    fun unselectedCustomerSeesWhatAreYouProtectingPicker() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = ProtectionViewModel(
            coordinator = fakeCoordinator(ProtectionState.DISARMED_ONLINE),
            incidents = FakeIncidentRepository(emptyList()),
            settings = FakeProtectionSettingsGateway(),
            profileRepository = fakeProfileRepository(selectedProfile = null),
            nowMs = { 1_000L },
            ticker = emptyFlow(),
            dispatcher = dispatcher,
            callbackDispatcher = dispatcher,
        )
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.profile.showPicker)
        assertNull(viewModel.uiState.value.profile.selectedProfile)
    }

    @Test
    fun selectingEntryIsReadyToArmOnSoundAndMovementBeforeAnyCalibration() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = fakeProfileRepository(selectedProfile = null)
        val viewModel = ProtectionViewModel(
            coordinator = fakeCoordinator(
                state = ProtectionState.DISARMED_ONLINE,
                profileRepository = repository,
            ),
            incidents = FakeIncidentRepository(emptyList()),
            settings = FakeProtectionSettingsGateway(),
            profileRepository = repository,
            nowMs = { 1_000L },
            ticker = emptyFlow(),
            dispatcher = dispatcher,
            callbackDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.selectProfile(ProtectionProfile.ENTRY)
        advanceUntilIdle()

        // This asserted the opposite: choosing the door watch left the owner at
        // SETUP_REQUIRED, and arming was refused until two guided cycles had been performed.
        // The first night was therefore spent unwatched, which is the night a new owner is
        // most likely to want it. The angle level still refuses without a model; see
        // ProtectionCoordinatorTest.
        assertEquals(ProtectionProfile.ENTRY, viewModel.uiState.value.profile.selectedProfile)
        assertEquals(ProfileSetupState.READY, viewModel.uiState.value.profile.setupState)

        viewModel.arm()
        advanceUntilIdle()

        assertNotEquals(ProtectionState.SETUP_REQUIRED, viewModel.uiState.value.protection.state)
    }

    @Test
    fun armedProfileChangeRequiresConfirmation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = fakeProfileRepository(selectedProfile = ProtectionProfile.POWER)
        val viewModel = ProtectionViewModel(
            coordinator = fakeCoordinator(
                state = ProtectionState.ARMED_HEALTHY,
                profileRepository = repository,
            ),
            incidents = FakeIncidentRepository(emptyList()),
            settings = FakeProtectionSettingsGateway(),
            profileRepository = repository,
            nowMs = { 1_000L },
            ticker = emptyFlow(),
            dispatcher = dispatcher,
            callbackDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.selectProfile(ProtectionProfile.VEHICLE)
        advanceUntilIdle()

        assertEquals(ProtectionProfile.VEHICLE, viewModel.uiState.value.profile.pendingSwitchTarget)
        // Nothing persisted and no switch transaction started without explicit confirmation.
        assertNull(repository.load().switchTransaction)
        assertEquals(ProtectionProfile.POWER, repository.load().selectedProfile)
    }

    // -------------------------------------------------------------------------
    // Entry Guard commissioning + angle control (spec sections 5 and 9).
    // -------------------------------------------------------------------------

    private fun entryViewModelFixture(
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        state: ProtectionState = ProtectionState.DISARMED_ONLINE,
    ): Triple<ProtectionViewModel, ViewModelProfileRepositoryFake, FakeEntrySampleRuntime> {
        val dispatcher = StandardTestDispatcher(scheduler)
        val repository = ViewModelProfileRepositoryFake(
            ProtectionProfilePolicy(nowMs = { 1_000L })
                .newStoreState()
                .copy(selectedProfile = ProtectionProfile.ENTRY),
        )
        val runtime = FakeEntrySampleRuntime()
        val viewModel = ProtectionViewModel(
            coordinator = fakeCoordinator(state = state, profileRepository = repository),
            incidents = FakeIncidentRepository(emptyList()),
            settings = FakeProtectionSettingsGateway(),
            profileRepository = repository,
            entryRuntime = runtime,
            nowMs = { 1_000L },
            ticker = emptyFlow(),
            dispatcher = dispatcher,
            callbackDispatcher = dispatcher,
        )
        return Triple(viewModel, repository, runtime)
    }

    private fun resolvedEntryAngle(repository: ViewModelProfileRepositoryFake): Int {
        val settings = ProtectionProfilePolicy(nowMs = { 1_000L })
            .resolve(repository.load(), ProtectionProfile.ENTRY)
            .specificSettings as EntryProfileSettings
        return settings.angleThresholdDegrees
    }

    @Test
    fun entryCommissioningPersistsModelAndDrivesSetupToReady() = runTest {
        val (viewModel, repository, runtime) = entryViewModelFixture(testScheduler)
        advanceUntilIdle()

        viewModel.startEntryCommissioning(alertAngleDeg = 15)
        advanceUntilIdle()

        fun twist(degrees: Double, timestampMs: Long) = EntryOrientationSample(
            timestampMs = timestampMs,
            quaternion = EntryQuaternion(
                w = Math.cos(Math.toRadians(degrees / 2)),
                x = 0.0,
                y = 0.0,
                z = Math.sin(Math.toRadians(degrees / 2)),
            ),
            fresh = true,
        )

        // Still check: five continuous seconds near the closed position.
        runtime.emit(twist(0.0, 0L))
        var t = 1_000L
        while (t <= 5_000L) {
            runtime.emit(twist(0.0, t))
            t += 1_000L
        }
        // Cycle one: open past the threshold, then return below the close threshold.
        runtime.emit(twist(20.0, 5_500L))
        runtime.emit(twist(20.0, 6_000L))
        runtime.emit(twist(0.0, 6_500L))
        // Cycle two must agree with cycle one.
        runtime.emit(twist(20.0, 7_000L))
        runtime.emit(twist(20.0, 7_500L))
        runtime.emit(twist(0.0, 8_000L))
        advanceUntilIdle()

        val stored = repository.load().profiles.getValue(ProtectionProfile.ENTRY)
        assertEquals(ProfileSetupState.READY, stored.setupState)
        assertNotNull(stored.entryHingeModel)
        assertEquals(ProfileSetupState.READY, viewModel.uiState.value.profile.setupState)
        assertNull(viewModel.uiState.value.profile.commissioning)
    }

    /**
     * The number on the calibration screen has to answer the door, not the sample rate.
     *
     * The display used to carry its own closed reference and re-seat it on every sample that
     * read under three degrees. This stream is registered at game rate, so that asked for
     * more than 150 degrees a second before the reading would leave zero: a door pushed at
     * any human speed dragged the reference along with it, and an owner watching a live
     * angle sit at `0°` has no way to tell a working calibration from a dead sensor.
     */
    @Test
    fun entryCommissioningLiveAngleFollowsADoorOpenedAtHumanSpeed() = runTest {
        val (viewModel, _, runtime) = entryViewModelFixture(testScheduler)
        advanceUntilIdle()

        viewModel.startEntryCommissioning(alertAngleDeg = 15)
        advanceUntilIdle()

        fun twist(degrees: Double, timestampMs: Long) = EntryOrientationSample(
            timestampMs = timestampMs,
            quaternion = EntryQuaternion(
                w = Math.cos(Math.toRadians(degrees / 2)),
                x = 0.0,
                y = 0.0,
                z = Math.sin(Math.toRadians(degrees / 2)),
            ),
            fresh = true,
        )

        runtime.emit(twist(0.0, 0L))
        var still = 1_000L
        while (still <= 5_000L) {
            runtime.emit(twist(0.0, still))
            still += 1_000L
        }
        advanceUntilIdle()
        assertEquals(
            EntryCommissioningPhase.CYCLE_ONE,
            viewModel.uiState.value.profile.commissioning?.phase,
        )

        // One degree every twenty milliseconds: fifty degrees a second, an ordinary push on
        // a door, and a third of the speed the old display silently demanded.
        var degrees = 1.0
        var atMs = 5_020L
        while (degrees <= 20.0) {
            runtime.emit(twist(degrees, atMs))
            degrees += 1.0
            atMs += 20L
        }
        advanceUntilIdle()

        val live = viewModel.uiState.value.profile.commissioning?.liveAngleDeg
        assertNotNull(live)
        assertEquals(20.0, live!!, 0.5)
    }

    @Test
    fun entryCommissioningClampsCloseThresholdBelowAlertAngle() = runTest {
        val (viewModel, _, _) = entryViewModelFixture(testScheduler)
        advanceUntilIdle()

        // 5 - 2.0 = 3.0, so closeThreshold 4.0 must be clamped to 3.0
        viewModel.startEntryCommissioning(alertAngleDeg = 5, closeThresholdDeg = 4.0)
        advanceUntilIdle()
        assertEquals(3.0, viewModel.uiState.value.profile.commissioning?.closeThresholdDeg)

        // Lower bound 2.0
        viewModel.startEntryCommissioning(alertAngleDeg = 15, closeThresholdDeg = 1.0)
        advanceUntilIdle()
        assertEquals(2.0, viewModel.uiState.value.profile.commissioning?.closeThresholdDeg)

        // Upper bound 10.0
        viewModel.startEntryCommissioning(alertAngleDeg = 30, closeThresholdDeg = 15.0)
        advanceUntilIdle()
        assertEquals(10.0, viewModel.uiState.value.profile.commissioning?.closeThresholdDeg)
    }

    @Test
    fun entryCommissioningTareZeroDuringStillCheckRestartsTheWindow() = runTest {
        val (viewModel, _, runtime) = entryViewModelFixture(testScheduler)
        advanceUntilIdle()

        viewModel.startEntryCommissioning(alertAngleDeg = 15)
        advanceUntilIdle()

        // Four seconds of stillness: one short of the window.
        runtime.emit(entryTwist(0.0, 0L))
        var t = 1_000L
        while (t <= 4_000L) {
            runtime.emit(entryTwist(0.0, t))
            t += 1_000L
        }
        advanceUntilIdle()
        assertEquals(
            EntryCommissioningPhase.STILL_CHECK,
            viewModel.uiState.value.profile.commissioning?.phase,
        )

        // There is no cycle to keep and no closed reference to move yet, so the tare restarts
        // the five seconds: the four already served do not count towards the new window.
        viewModel.tareEntryCommissioningZero()
        advanceUntilIdle()

        runtime.emit(entryTwist(0.0, 5_000L))
        runtime.emit(entryTwist(0.0, 8_000L))
        advanceUntilIdle()
        assertEquals(
            EntryCommissioningPhase.STILL_CHECK,
            viewModel.uiState.value.profile.commissioning?.phase,
        )

        runtime.emit(entryTwist(0.0, 10_100L))
        advanceUntilIdle()
        assertEquals(
            EntryCommissioningPhase.CYCLE_ONE,
            viewModel.uiState.value.profile.commissioning?.phase,
        )
    }

    /**
     * The owner presses "this is zero" because the reading has drifted off the shut door, not
     * because they want to walk the flow again. Sending them back through the still check would
     * be tolerable; throwing away a cycle they already walked, without saying so, is not — and
     * that is what routing the button through `start()` did.
     */
    @Test
    fun entryCommissioningTareZeroKeepsTheCycleAlreadyProven() = runTest {
        val (viewModel, repository, runtime) = entryViewModelFixture(testScheduler)
        advanceUntilIdle()

        viewModel.startEntryCommissioning(alertAngleDeg = 15)
        advanceUntilIdle()

        runtime.emit(entryTwist(0.0, 0L))
        var t = 1_000L
        while (t <= 5_000L) {
            runtime.emit(entryTwist(0.0, t))
            t += 1_000L
        }
        // Cycle one: open past the threshold and shut again.
        runtime.emit(entryTwist(20.0, 5_500L))
        runtime.emit(entryTwist(0.0, 6_000L))
        advanceUntilIdle()
        assertEquals(
            EntryCommissioningPhase.CYCLE_TWO,
            viewModel.uiState.value.profile.commissioning?.phase,
        )

        // The source has drifted: the shut door now reads two degrees, and the tare says so.
        runtime.emit(entryTwist(2.0, 6_500L))
        advanceUntilIdle()
        viewModel.tareEntryCommissioningZero()
        advanceUntilIdle()

        val tared = viewModel.uiState.value.profile.commissioning
        assertEquals(EntryCommissioningPhase.CYCLE_TWO, tared?.phase)
        assertEquals(0.0, tared?.liveAngleDeg)
        assertEquals(0.0, tared?.peakAngleDeg)
        assertNull(tared?.failureReason)

        // One more cycle, measured from the new zero, is all that should be left to do.
        runtime.emit(entryTwist(22.0, 7_000L))
        runtime.emit(entryTwist(2.0, 7_500L))
        advanceUntilIdle()

        val stored = repository.load().profiles.getValue(ProtectionProfile.ENTRY)
        assertEquals(ProfileSetupState.READY, stored.setupState)
    }

    private fun entryTwist(degrees: Double, timestampMs: Long) = EntryOrientationSample(
        timestampMs = timestampMs,
        quaternion = EntryQuaternion(
            w = Math.cos(Math.toRadians(degrees / 2)),
            x = 0.0,
            y = 0.0,
            z = Math.sin(Math.toRadians(degrees / 2)),
        ),
        fresh = true,
    )

    @Test
    fun entryAngleQuickChoicesAndSliderBoundsAreEnforced() = runTest {
        val (viewModel, repository, _) = entryViewModelFixture(testScheduler)
        advanceUntilIdle()

        viewModel.setEntryAngle(4)
        advanceUntilIdle()
        assertEquals(5, resolvedEntryAngle(repository))

        viewModel.setEntryAngle(91)
        advanceUntilIdle()
        assertEquals(90, resolvedEntryAngle(repository))

        viewModel.setEntryAngle(30)
        advanceUntilIdle()
        assertEquals(30, resolvedEntryAngle(repository))
        assertEquals(30, viewModel.uiState.value.profile.entryAngleDegrees)
    }

    @Test
    fun armedEntryAngleChangeDefersWithControlledRearmRequest() = runTest {
        val (viewModel, repository, _) = entryViewModelFixture(
            testScheduler,
            state = ProtectionState.ARMED_HEALTHY,
        )
        advanceUntilIdle()

        viewModel.setEntryAngle(45)
        advanceUntilIdle()

        // Persisted for the next controlled arm; the running session keeps its frozen
        // threshold and the owner is directed through disarm/calibrate/re-arm.
        assertEquals(45, resolvedEntryAngle(repository))
        assertTrue(viewModel.uiState.value.profile.entryRequiresControlledRearm)

        viewModel.disarm()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.profile.entryRequiresControlledRearm)
    }

    // -------------------------------------------------------------------------
    // Power Guard commissioning + summary rows (spec sections 4.3 and 3.6).
    // -------------------------------------------------------------------------

    private fun powerViewModelFixture(
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        state: ProtectionState = ProtectionState.DISARMED_ONLINE,
        powerIntegrityChallenge: (() -> Boolean)? = null,
        powerArmChallenge: PowerArmChallengeRegistry? = null,
        ticker: Flow<Unit> = emptyFlow(),
        elapsedNowMs: () -> Long = { scheduler.currentTime },
    ): PowerViewModelFixture {
        val dispatcher = StandardTestDispatcher(scheduler)
        val repository = ViewModelProfileRepositoryFake(
            ProtectionProfilePolicy(nowMs = { 1_000L })
                .newStoreState()
                .copy(selectedProfile = ProtectionProfile.POWER),
        )
        val runtime = FakePowerSampleRuntime()
        val coordinator = fakeCoordinator(
                state = state,
                profileRepository = repository,
                powerIntegrityChallenge = powerIntegrityChallenge,
            )
        val viewModel = ProtectionViewModel(
            coordinator = coordinator,
            incidents = FakeIncidentRepository(emptyList()),
            settings = FakeProtectionSettingsGateway(),
            profileRepository = repository,
            powerRuntime = runtime,
            powerArmChallenge = powerArmChallenge,
            nowMs = { 1_000L },
            ticker = ticker,
            dispatcher = dispatcher,
            callbackDispatcher = dispatcher,
            elapsedNowMs = elapsedNowMs,
        )
        return PowerViewModelFixture(viewModel, repository, runtime, coordinator)
    }

    /** Continuous dark window (lux≈5) then lit window (lux≈200) inside the policy limits. */
    private fun darkThenLitWitnessSamples(): List<PowerWitnessSample> = buildList {
        var t = 0L
        while (t <= 10_000L) {
            add(PowerWitnessSample(lux = 5.0, timestampMs = t, fresh = true))
            t += 1_000L
        }
        t = 11_000L
        while (t <= 21_000L) {
            val lux = if (t % 2_000L == 0L) 140.0 else 200.0
            add(PowerWitnessSample(lux = lux, timestampMs = t, fresh = true))
            t += 1_000L
        }
    }

    @Test
    fun calibrationWithoutALightSourceFailsAtOnceInsteadOfWaitingForever() = runTest {
        val fixture = powerViewModelFixture(testScheduler)
        fixture.runtime.lightSourceAvailable = false
        advanceUntilIdle()

        fixture.viewModel.startPowerCommissioning()
        advanceUntilIdle()

        // A phone with no ambient-light hardware can never finish the guided flow, so
        // saying "step 1/2, switch the lamp off" would be a lie the owner acts on.
        val commissioning = fixture.viewModel.uiState.value.profile.powerCommissioning
        assertEquals(PowerCommissioningPhase.FAILED, commissioning?.phase)
        assertEquals(PowerCommissioningFailure.NO_LIGHT_SENSOR, commissioning?.failureReason)
        assertTrue(fixture.runtime.streamStopped)
    }

    @Test
    fun calibrationThatNeverReceivesALightSampleGivesUp() = runTest {
        val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        var elapsedMs = 0L
        val fixture = powerViewModelFixture(
            scheduler = testScheduler,
            ticker = ticks,
            elapsedNowMs = { elapsedMs },
        )
        advanceUntilIdle()
        fixture.viewModel.startPowerCommissioning()
        advanceUntilIdle()

        elapsedMs = 4_000L
        ticks.tryEmit(Unit)
        advanceUntilIdle()
        assertEquals(
            PowerCommissioningPhase.DARK_WINDOW,
            fixture.viewModel.uiState.value.profile.powerCommissioning?.phase,
        )

        // The source was acquired but nothing ever arrived — a listener that died
        // quietly, or hardware that vanished mid-session, as this device did once.
        elapsedMs = 5_000L
        ticks.tryEmit(Unit)
        advanceUntilIdle()

        val commissioning = fixture.viewModel.uiState.value.profile.powerCommissioning
        assertEquals(PowerCommissioningPhase.FAILED, commissioning?.phase)
        assertEquals(PowerCommissioningFailure.NO_LIGHT_SAMPLES, commissioning?.failureReason)
        assertTrue(fixture.runtime.streamStopped)
    }

    @Test
    fun calibrationKeepsWaitingWhileTheSensorIsStillReporting() = runTest {
        val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        var elapsedMs = 0L
        val fixture = powerViewModelFixture(
            scheduler = testScheduler,
            ticker = ticks,
            elapsedNowMs = { elapsedMs },
        )
        advanceUntilIdle()
        fixture.viewModel.startPowerCommissioning()
        advanceUntilIdle()
        fixture.runtime.emit(PowerWitnessSample(lux = 5.0, timestampMs = 0L, fresh = true))
        advanceUntilIdle()

        // A steady lamp on an on-change sensor emits nothing after the first reading;
        // the give-up rule must not mistake that for a dead source.
        elapsedMs = 9_000L
        ticks.tryEmit(Unit)
        advanceUntilIdle()

        val commissioning = fixture.viewModel.uiState.value.profile.powerCommissioning
        assertEquals(PowerCommissioningPhase.DARK_WINDOW, commissioning?.phase)
        assertNull(commissioning?.failureReason)
    }

    @Test
    fun powerCommissioningObservesEachLightStateForTenSeconds() = runTest {
        val (viewModel, _, runtime) = powerViewModelFixture(testScheduler)
        advanceUntilIdle()
        viewModel.startPowerCommissioning()
        advanceUntilIdle()

        (0L..9_000L step 1_000L).forEach { t ->
            runtime.emit(PowerWitnessSample(lux = 5.0, timestampMs = t, fresh = true))
        }
        advanceUntilIdle()
        assertEquals(
            PowerCommissioningPhase.DARK_WINDOW,
            viewModel.uiState.value.profile.powerCommissioning?.phase,
        )

        runtime.emit(PowerWitnessSample(lux = 5.0, timestampMs = 10_000L, fresh = true))
        advanceUntilIdle()
        assertEquals(
            PowerCommissioningPhase.LIT_WINDOW,
            viewModel.uiState.value.profile.powerCommissioning?.phase,
        )

        (11_000L..20_000L step 1_000L).forEach { t ->
            runtime.emit(PowerWitnessSample(lux = 200.0, timestampMs = t, fresh = true))
        }
        advanceUntilIdle()
        assertEquals(
            PowerCommissioningPhase.LIT_WINDOW,
            viewModel.uiState.value.profile.powerCommissioning?.phase,
        )

        runtime.emit(PowerWitnessSample(lux = 200.0, timestampMs = 21_000L, fresh = true))
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.profile.powerCommissioning)
    }

    @Test
    fun powerCommissioningClockAdvancesWhenStableOnChangeSensorDoesNotRepeat() = runTest {
        val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        var elapsedMs = 0L
        val (viewModel, repository, runtime) = powerViewModelFixture(
            scheduler = testScheduler,
            ticker = ticks,
            elapsedNowMs = { elapsedMs },
        )
        advanceUntilIdle()
        viewModel.startPowerCommissioning()
        advanceUntilIdle()

        runtime.emit(PowerWitnessSample(lux = 10.0, timestampMs = 0L, fresh = true))
        advanceUntilIdle()
        elapsedMs = 10_000L
        ticks.tryEmit(Unit)
        advanceUntilIdle()
        assertEquals(
            PowerCommissioningPhase.LIT_WINDOW,
            viewModel.uiState.value.profile.powerCommissioning?.phase,
        )

        runtime.emit(PowerWitnessSample(lux = 20.0, timestampMs = 11_000L, fresh = true))
        advanceUntilIdle()
        elapsedMs = 21_000L
        ticks.tryEmit(Unit)
        advanceUntilIdle()

        assertEquals(ProfileSetupState.READY, repository.load().profiles.getValue(ProtectionProfile.POWER).setupState)
        assertNull(viewModel.uiState.value.profile.powerCommissioning)
    }

    @Test
    fun powerCommissioningPersistsWitnessModelAndDrivesSetupToReady() = runTest {
        val (viewModel, repository, runtime) = powerViewModelFixture(testScheduler)
        advanceUntilIdle()

        viewModel.startPowerCommissioning()
        advanceUntilIdle()
        assertEquals(
            PowerCommissioningPhase.DARK_WINDOW,
            viewModel.uiState.value.profile.powerCommissioning?.phase,
        )

        darkThenLitWitnessSamples().forEach(runtime::emit)
        advanceUntilIdle()

        val stored = repository.load().profiles.getValue(ProtectionProfile.POWER)
        assertEquals(ProfileSetupState.READY, stored.setupState)
        assertNotNull(stored.powerWitnessModel)
        assertEquals(ProfileSetupState.READY, viewModel.uiState.value.profile.setupState)
        assertNull(viewModel.uiState.value.profile.powerCommissioning)
        assertTrue(runtime.streamStopped)
    }

    @Test
    fun powerCommissioningShowsFailureWhenWitnessModelCannotBePersisted() = runTest {
        val (viewModel, repository, runtime) = powerViewModelFixture(testScheduler)
        advanceUntilIdle()
        repository.failUpdates = true

        viewModel.startPowerCommissioning()
        advanceUntilIdle()
        darkThenLitWitnessSamples().forEach(runtime::emit)
        advanceUntilIdle()

        val stored = repository.load().profiles.getValue(ProtectionProfile.POWER)
        assertEquals(ProfileSetupState.SETUP_REQUIRED, stored.setupState)
        assertNull(stored.powerWitnessModel)
        assertEquals(
            PowerCommissioningPhase.FAILED,
            viewModel.uiState.value.profile.powerCommissioning?.phase,
        )
        assertEquals(
            "save-failed",
            viewModel.uiState.value.profile.powerCommissioning?.failureReason,
        )
        assertTrue(runtime.streamStopped)
    }

    @Test
    fun resetPowerCalibrationClearsOnlyTheWitnessModelAndRequiresRecommissioning() = runTest {
        val (viewModel, repository, _) = powerViewModelFixture(testScheduler)
        val policy = ProtectionProfilePolicy(nowMs = { 1_000L })
        repository.update {
            policy.commissionPower(
                it,
                PowerWitnessModel(
                    darkMinLux = 5.0,
                    darkMaxLux = 8.0,
                    litMinLux = 200.0,
                    litMaxLux = 220.0,
                    guardBandLux = 10.0,
                    algorithmVersion = PowerWitnessCommissioningPolicy.ALGORITHM_VERSION,
                    sensorIdentity = "test-sensor",
                    hoodSignature = "hood-test",
                ),
            )
        }
        advanceUntilIdle()

        viewModel.resetPowerCalibration()
        advanceUntilIdle()

        val stored = repository.load().profiles.getValue(ProtectionProfile.POWER)
        assertEquals(ProfileSetupState.SETUP_REQUIRED, stored.setupState)
        assertNull(stored.powerWitnessModel)
        assertEquals(ProtectionProfile.POWER, repository.load().selectedProfile)
        assertEquals(ProfileSetupState.SETUP_REQUIRED, viewModel.uiState.value.profile.setupState)
    }

    @Test
    fun skippedPowerChallengeArmsDegradedWithScopedWitnessCopy() = runTest {
        val (viewModel, repository, _) = powerViewModelFixture(
            testScheduler,
            powerIntegrityChallenge = { false },
        )
        val policy = ProtectionProfilePolicy(nowMs = { 1_000L })
        repository.update {
            policy.commissionPower(
                it,
                PowerWitnessModel(
                    darkMinLux = 5.0,
                    darkMaxLux = 8.0,
                    litMinLux = 200.0,
                    litMaxLux = 220.0,
                    guardBandLux = 10.0,
                    algorithmVersion = PowerWitnessCommissioningPolicy.ALGORITHM_VERSION,
                    sensorIdentity = "test-sensor",
                    hoodSignature = "hood-test",
                ),
            )
        }
        advanceUntilIdle()

        viewModel.arm()
        advanceUntilIdle()

        assertEquals(ProtectionState.ARMED_DEGRADED, viewModel.uiState.value.protection.state)
        assertEquals(ProtectionProfile.POWER, viewModel.uiState.value.profile.armedProfile)
        val summary = viewModel.uiState.value.profile.powerSummary
        assertNotNull(summary)
        assertEquals(WitnessRowState.UNAVAILABLE, summary?.witness)
    }

    @Test
    fun powerWitnessConfirmationIsShownAndKeepsTheNextArmHealthy() = runTest {
        val challenge = PowerArmChallengeRegistry()
        val (viewModel, repository, _) = powerViewModelFixture(
            testScheduler,
            powerIntegrityChallenge = { challenge.isSatisfied(1_000L) },
            powerArmChallenge = challenge,
        )
        val policy = ProtectionProfilePolicy(nowMs = { 1_000L })
        repository.update {
            policy.commissionPower(
                it,
                PowerWitnessModel(
                    darkMinLux = 5.0,
                    darkMaxLux = 8.0,
                    litMinLux = 200.0,
                    litMaxLux = 220.0,
                    guardBandLux = 10.0,
                    algorithmVersion = PowerWitnessCommissioningPolicy.ALGORITHM_VERSION,
                    sensorIdentity = "test-sensor",
                    hoodSignature = "hood-test",
                ),
            )
        }
        advanceUntilIdle()

        viewModel.markPowerChallengePassed()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.profile.powerWitnessPlacementConfirmed)

        viewModel.arm()
        advanceUntilIdle()

        assertEquals(ProtectionState.ARMED_HEALTHY, viewModel.uiState.value.protection.state)
    }

    @Test
    fun powerSummaryRowsDeriveIndependentlyFromEachSignal() {
        val witnessModel = PowerWitnessModel(
            darkMinLux = 5.0,
            darkMaxLux = 8.0,
            litMinLux = 200.0,
            litMaxLux = 220.0,
            guardBandLux = 10.0,
            algorithmVersion = PowerWitnessCommissioningPolicy.ALGORITHM_VERSION,
            sensorIdentity = "test-sensor",
            hoodSignature = "hood-test",
        )

        // A skipped per-arm placement check does not make a working light sensor
        // unavailable; it is reported through its own field instead.
        val connectedWitnessNotRevalidated = powerSummaryRows(
            chargingState = ChargingState.CHARGING,
            degradationReasons = setOf(POWER_CHALLENGE_DEGRADED),
            lightSensorHealth = SensorHealth(SensorHealthState.HEALTHY, lastSampleAtMs = 200L),
            powerSensorHealth = SensorHealth(SensorHealthState.HEALTHY, lastSampleAtMs = 300L),
        )
        assertEquals(ChargingRowState.CHARGING, connectedWitnessNotRevalidated.charging)
        assertEquals(WitnessRowState.AVAILABLE, connectedWitnessNotRevalidated.witness)
        assertTrue(connectedWitnessNotRevalidated.requiresWitnessPlacementRevalidation)
        assertEquals(300L, connectedWitnessNotRevalidated.lastUpdatedAtMs)
        assertFalse(connectedWitnessNotRevalidated.confirmedFault)

        // Sensor availability alone is not evidence that the witness lamp is lit.
        val chargerGoneWitnessUp = powerSummaryRows(
            chargingState = ChargingState.NOT_CHARGING,
            degradationReasons = emptySet(),
            lightSensorHealth = SensorHealth(SensorHealthState.AVAILABLE),
        )
        assertEquals(ChargingRowState.NOT_CHARGING, chargerGoneWitnessUp.charging)
        assertEquals(WitnessRowState.AVAILABLE, chargerGoneWitnessUp.witness)

        val detectedWitness = powerSummaryRows(
            chargingState = ChargingState.CHARGING,
            degradationReasons = emptySet(),
            lightSensorHealth = SensorHealth(
                state = SensorHealthState.HEALTHY,
                lightDetail = com.example.motorcycleantitheftsensor.protection.LightHealthDetail(lastLux = 205.0),
            ),
            witnessModel = witnessModel,
        )
        assertEquals(WitnessRowState.DETECTED, detectedWitness.witness)

        val darkWitness = powerSummaryRows(
            chargingState = ChargingState.DISCHARGING,
            degradationReasons = emptySet(),
            lightSensorHealth = SensorHealth(
                state = SensorHealthState.HEALTHY,
                lightDetail = com.example.motorcycleantitheftsensor.protection.LightHealthDetail(lastLux = 7.0),
            ),
            witnessModel = witnessModel,
        )
        assertEquals(WitnessRowState.DARK, darkWitness.witness)

        // Unknown charging plus stale light evidence stays honest on both rows.
        val unknownBoth = powerSummaryRows(
            chargingState = ChargingState.UNKNOWN,
            degradationReasons = emptySet(),
            lightSensorHealth = SensorHealth(SensorHealthState.STALE),
        )
        assertEquals(ChargingRowState.UNKNOWN, unknownBoth.charging)
        assertEquals(WitnessRowState.UNAVAILABLE, unknownBoth.witness)

        assertEquals(
            ChargingRowState.FULL,
            powerSummaryRows(ChargingState.FULL, emptySet(), null).charging,
        )
        assertEquals(
            ChargingRowState.DISCHARGING,
            powerSummaryRows(ChargingState.DISCHARGING, emptySet(), null).charging,
        )
        assertTrue(
            powerSummaryRows(
                chargingState = ChargingState.DISCHARGING,
                degradationReasons = emptySet(),
                lightSensorHealth = SensorHealth(SensorHealthState.HEALTHY),
                confirmedFault = true,
            ).confirmedFault,
        )
        assertEquals(
            400L,
            powerSummaryRows(
                chargingState = ChargingState.CHARGING,
                degradationReasons = emptySet(),
                lightSensorHealth = null,
                powerSensorHealth = SensorHealth(
                    state = SensorHealthState.AVAILABLE,
                    powerThermalDetail = com.example.motorcycleantitheftsensor.protection.PowerThermalHealthDetail(
                        lastUpdateWallClockMs = 400L,
                    ),
                ),
            ).lastUpdatedAtMs,
        )
    }

    @Test
    fun disarmedPowerProfileRefreshesChargingRowFromLiveStatus() = runTest {
        val repository = fakeProfileRepository(ProtectionProfile.POWER)
        val coordinator = fakeCoordinator(
            state = ProtectionState.DISARMED_ONLINE,
            profileRepository = repository,
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = ProtectionViewModel(
            coordinator = coordinator,
            incidents = FakeIncidentRepository(emptyList()),
            settings = FakeProtectionSettingsGateway(),
            profileRepository = repository,
            nowMs = { 1_000L },
            ticker = emptyFlow(),
            dispatcher = dispatcher,
            callbackDispatcher = dispatcher,
        )
        advanceUntilIdle()

        assertEquals(ProtectionState.DISARMED_ONLINE, viewModel.uiState.value.protection.state)
        coordinator.recordSensorHealth(
            SensorKind.POWER_THERMAL,
            SensorHealth(
                state = SensorHealthState.HEALTHY,
                powerThermalDetail = com.example.motorcycleantitheftsensor.protection.PowerThermalHealthDetail(
                    chargingState = ChargingState.DISCHARGING,
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(ChargingRowState.DISCHARGING, viewModel.uiState.value.profile.powerSummary?.charging)
    }

    @Test
    fun powerSummaryShowsWitnessDarkFromTheLiveLuxValueAndCalibration() {
        val model = PowerWitnessModel(
            darkMinLux = 2.0,
            darkMaxLux = 4.0,
            litMinLux = 120.0,
            litMaxLux = 123.0,
            guardBandLux = 20.0,
            algorithmVersion = 1,
            sensorIdentity = "light#1",
            hoodSignature = "hood-A",
        )

        val summary = powerSummaryRows(
            chargingState = ChargingState.CHARGING,
            degradationReasons = emptySet(),
            lightSensorHealth = SensorHealth(
                state = SensorHealthState.HEALTHY,
                lightDetail = LightHealthDetail(lastLux = 3.0),
            ),
            witnessModel = model,
        )

        assertEquals(WitnessRowState.DARK, summary.witness)
        assertEquals(3.0, summary.lastLux)
    }

    @Test
    fun powerSummaryUsesArmedWitnessThresholdsWhenTheyDifferFromCalibration() {
        val calibratedModel = PowerWitnessModel(
            darkMinLux = 2.0,
            darkMaxLux = 4.0,
            litMinLux = 120.0,
            litMaxLux = 123.0,
            guardBandLux = 20.0,
            algorithmVersion = 1,
            sensorIdentity = "light#1",
            hoodSignature = "hood-A",
        )

        val summary = powerSummaryRows(
            chargingState = ChargingState.CHARGING,
            degradationReasons = emptySet(),
            lightSensorHealth = SensorHealth(
                state = SensorHealthState.HEALTHY,
                lightDetail = LightHealthDetail(
                    lastLux = 90.0,
                    armedWitnessDarkThresholdLux = 100.0,
                    armedWitnessLitThresholdLux = 180.0,
                ),
            ),
            witnessModel = calibratedModel,
        )

        assertEquals(WitnessRowState.DARK, summary.witness)
    }

    @Test
    fun powerSummaryDoesNotCallARealButBelowCalibrationLightUnavailable() {
        val model = PowerWitnessModel(
            darkMinLux = 2.0,
            darkMaxLux = 4.0,
            litMinLux = 120.0,
            litMaxLux = 123.0,
            guardBandLux = 20.0,
            algorithmVersion = 1,
            sensorIdentity = "light#1",
            hoodSignature = "hood-A",
        )

        val summary = powerSummaryRows(
            chargingState = ChargingState.CHARGING,
            degradationReasons = emptySet(),
            lightSensorHealth = SensorHealth(
                state = SensorHealthState.HEALTHY,
                lightDetail = LightHealthDetail(lastLux = 60.0),
            ),
            witnessModel = model,
        )

        assertNotEquals(WitnessRowState.UNAVAILABLE, summary.witness)
    }

    @Test
    fun powerProfileStartsLiveStatusMonitoringAndStopsItWhenAnotherProfileIsSelected() = runTest {
        val (viewModel, _, runtime) = powerViewModelFixture(testScheduler)
        advanceUntilIdle()

        assertTrue(runtime.statusMonitoringStarted)

        viewModel.selectProfile(ProtectionProfile.ENTRY)
        advanceUntilIdle()

        assertTrue(runtime.statusMonitoringStopped)
    }

    @Test
    fun powerSummaryUpdatesWhenChargingStateChangesWhileDisarmed() = runTest {
        val fixture = powerViewModelFixture(testScheduler)
        advanceUntilIdle()

        fixture.coordinator.recordSensorHealth(
            SensorKind.POWER_THERMAL,
            SensorHealth(
                SensorHealthState.HEALTHY,
                powerThermalDetail = PowerThermalHealthDetail(
                    isRegistered = true,
                    chargingState = ChargingState.DISCHARGING,
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(
            ChargingRowState.DISCHARGING,
            fixture.viewModel.uiState.value.profile.powerSummary?.charging,
        )
    }

    @Test
    fun powerSummaryProjectedOnlyForSelectedPowerProfile() = runTest {
        val (viewModel, _, _) = powerViewModelFixture(testScheduler)
        advanceUntilIdle()
        assertEquals(ProtectionProfile.POWER, viewModel.uiState.value.profile.selectedProfile)
        assertNotNull(viewModel.uiState.value.profile.powerSummary)

        viewModel.selectProfile(ProtectionProfile.ENTRY)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.profile.powerSummary)
    }
}

private class FakeEntrySampleRuntime : ProtectionRuntime {
    private val samples = MutableSharedFlow<EntryOrientationSample>(extraBufferCapacity = 64)
    var streamStarted = false
        private set
    var streamStopped = false
        private set

    fun emit(sample: EntryOrientationSample) {
        samples.tryEmit(sample)
    }

    override fun readiness(): ReadinessReport = ReadinessReport(emptySet(), emptySet())

    override fun startDetectors(): DetectorStartResult = DetectorStartResult(true)

    override fun stopDetectors() = Unit

    override fun applySensitivity(level: Int) = Unit

    override fun currentSensorHealth(): Map<SensorKind, SensorHealth> =
        mapOf(SensorKind.VIBRATION to SensorHealth(SensorHealthState.HEALTHY))

    override fun entryOrientationSamples(): Flow<EntryOrientationSample> = samples

    override fun startEntryCommissioningStream() {
        streamStarted = true
    }

    override fun stopEntryCommissioningStream() {
        streamStopped = true
    }
}

private class FakePowerSampleRuntime : ProtectionRuntime {
    private val samples = MutableSharedFlow<PowerWitnessSample>(extraBufferCapacity = 64)
    var lightSourceAvailable = true
    var streamStarted = false
        private set
    var streamStopped = false
        private set
    var statusMonitoringStarted = false
        private set
    var statusMonitoringStopped = false
        private set

    fun emit(sample: PowerWitnessSample) {
        samples.tryEmit(sample)
    }

    override fun readiness(): ReadinessReport = ReadinessReport(emptySet(), emptySet())

    override fun startDetectors(): DetectorStartResult = DetectorStartResult(true)

    override fun stopDetectors() = Unit

    override fun applySensitivity(level: Int) = Unit

    override fun currentSensorHealth(): Map<SensorKind, SensorHealth> =
        mapOf(SensorKind.LIGHT to SensorHealth(SensorHealthState.HEALTHY))

    override fun powerWitnessSamples(): Flow<PowerWitnessSample> = samples

    override fun startPowerCommissioningStream(): Boolean {
        streamStarted = true
        return lightSourceAvailable
    }

    override fun stopPowerCommissioningStream() {
        streamStopped = true
    }

    override fun startPowerStatusMonitoring() {
        statusMonitoringStarted = true
    }

    override fun stopPowerStatusMonitoring() {
        statusMonitoringStopped = true
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

private data class PowerViewModelFixture(
    val viewModel: ProtectionViewModel,
    val repository: ViewModelProfileRepositoryFake,
    val runtime: FakePowerSampleRuntime,
    val coordinator: ProtectionCoordinator,
)

private fun fakeCoordinator(
    state: ProtectionState,
    blockers: Set<String> = emptySet(),
    profileRepository: ProtectionProfileRepository? = null,
    powerIntegrityChallenge: (() -> Boolean)? = null,
    deviceSupport: (ProtectionProfile) -> ProfileDeviceSupport = { ProfileDeviceSupport.Supported },
): ProtectionCoordinator = ProtectionCoordinator(
    initialSnapshot = snapshot(state = state, lastTransitionAtMs = 1_000L),
    runtime = FakeRuntime(blockers),
    armingDelay = ArmingDelay { },
    clock = ProtectionClock { 2_000L },
    profileRepository = profileRepository,
    powerIntegrityChallenge = powerIntegrityChallenge,
    deviceSupport = deviceSupport,
)

/** Every source present, so the support policy judges rather than short-circuiting. */
private class FullSensorCatalog : SensorCatalog {
    override fun descriptors(): Map<SensorSource, SensorDescriptor> =
        SensorSource.entries.associateWith(::descriptor)

    override fun descriptor(source: SensorSource): SensorDescriptor = SensorDescriptor(
        source = source,
        androidType = 1,
        name = source.name,
        vendor = "Fake",
        reportingMode = 1,
        isWakeUp = false,
        minDelayUs = 1_000,
        maxDelayUs = 200_000,
        maximumRange = 100f,
        resolution = 0.01f,
        powerMa = 0.5f,
        isAvailable = true,
    )

    override fun isAvailable(source: SensorSource): Boolean = true
}

private class FakeDriftMeasurementStore(
    private var measurement: EntryDriftMeasurement?,
) : EntryDriftMeasurementStore {
    override fun load(): EntryDriftMeasurement? = measurement

    override fun save(measurement: EntryDriftMeasurement) {
        this.measurement = measurement
    }

    override fun clear() {
        measurement = null
    }
}

private class ViewModelProfileRepositoryFake(
    private var state: ProtectionProfileStoreState,
) : ProtectionProfileRepository {
    var failUpdates: Boolean = false

    override fun load(): ProtectionProfileStoreState = state

    override fun save(state: ProtectionProfileStoreState): Result<Unit> {
        this.state = state
        return Result.success(Unit)
    }

    override fun update(
        transform: (ProtectionProfileStoreState) -> ProtectionProfileStoreState,
    ): Result<ProtectionProfileStoreState> {
        if (failUpdates) return Result.failure(IllegalStateException("simulated profile write failure"))
        this.state = transform(this.state)
        return Result.success(this.state)
    }
}

private fun fakeProfileRepository(selectedProfile: ProtectionProfile?): ProtectionProfileRepository =
    ViewModelProfileRepositoryFake(
        ProtectionProfilePolicy(nowMs = { 1_000L })
            .newStoreState()
            .copy(selectedProfile = selectedProfile),
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
    type = IncidentType.TAMPER,
    severity = IncidentSeverity.WARNING,
    lifecycle = IncidentLifecycle.OPEN,
    evidence = listOf(
        IncidentEvidence(
            kind = SensorKind.VIBRATION,
            eventElapsedMs = 0L,
            wallClockMs = updatedAtMs,
            normalizedValue = 1.0,
            baselineDelta = 0.5,
            diagnostic = "sensor",
        ),
    ),
    openedAtMs = updatedAtMs,
    updatedAtMs = updatedAtMs,
    closedAtMs = null,
    protectionState = ProtectionState.ARMED_HEALTHY,
    deliveryState = DeliveryState.SENT,
)

private suspend fun awaitCondition(timeoutMs: Long = 3_000L, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        kotlinx.coroutines.delay(10)
    }
    assertTrue("Condition not met within ${timeoutMs}ms", condition())
}

private class FakeRuntime(
    private val blockers: Set<String> = emptySet(),
    private val health: Map<SensorKind, SensorHealth> = mapOf(SensorKind.VIBRATION to SensorHealth(SensorHealthState.HEALTHY)),
    initialAudioTelemetry: AudioTelemetry = AudioTelemetry.off(),
) : ProtectionRuntime {
    private val mutableAudioTelemetry = MutableStateFlow(initialAudioTelemetry)
    override val audioTelemetry: StateFlow<AudioTelemetry> = mutableAudioTelemetry

    fun emitAudioTelemetry(value: AudioTelemetry) {
        mutableAudioTelemetry.value = value
    }

    override fun readiness(): ReadinessReport = ReadinessReport(
        blockers = blockers,
        degradations = emptySet(),
    )

    override fun startDetectors(): DetectorStartResult = DetectorStartResult(true)

    override fun stopDetectors() = Unit

    override fun applySensitivity(level: Int) = Unit

    override fun currentSensorHealth(): Map<SensorKind, SensorHealth> = health
}

private class FakeIncidentRepository(
    incidents: List<SecurityIncident> = emptyList(),
    var failuresRemaining: Int = 0,
) : IncidentRepository {
    private val incidents = incidents.toMutableList()
    private val revisions = MutableStateFlow(0L)

    override val revision: StateFlow<Long> = revisions.asStateFlow()

    override fun upsert(incident: SecurityIncident) {
        incidents.removeAll { it.id == incident.id }
        incidents.add(0, incident)
        revisions.value += 1L
    }

    override fun findById(id: String): SecurityIncident? = incidents.find { it.id == id }

    override fun listNewestFirst(): List<SecurityIncident> {
        if (failuresRemaining > 0) {
            failuresRemaining -= 1
            error("history unavailable")
        }
        return incidents.toList()
    }

    override fun clearHistory() {
        incidents.clear()
        revisions.value += 1L
    }
}

private class BlockingClearIncidentRepository : IncidentRepository {
    val clearStarted = CountDownLatch(1)
    val allowClear = CountDownLatch(1)
    private var cleared = false

    override fun upsert(incident: SecurityIncident) = Unit

    override fun findById(id: String): SecurityIncident? = null

    override fun listNewestFirst(): List<SecurityIncident> = if (cleared) {
        emptyList()
    } else {
        listOf(realIncident("initial", 1_000L))
    }

    override fun clearHistory() {
        clearStarted.countDown()
        allowClear.await()
        cleared = true
    }
}

private class InitialSettingsReadBarrierDispatcher : CoroutineDispatcher() {
    private val queue = mutableListOf<Runnable>()
    private var allowInitialSettingsRead = false

    fun startInitialSettingsRead() {
        allowInitialSettingsRead = true
        val queued = queue.toList()
        queue.clear()
        queued.forEach(Runnable::run)
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (allowInitialSettingsRead) {
            block.run()
        } else {
            queue.add(block)
        }
    }
}

private class FakeProtectionSettingsGateway(
    private val botTokenFailure: String? = null,
    private val smsFallbackFailure: String? = null,
    private val firstSettingsReadStarted: CompletableDeferred<Unit>? = null,
    private val allowFirstSettingsRead: CompletableDeferred<Unit>? = null,
    private val allowBotToken: CompletableDeferred<Unit>? = null,

    private val smsFallbackStarted: CountDownLatch? = null,
    private val allowSmsFallbackLatch: CountDownLatch? = null,
    private var settingsReadFailuresRemaining: Int = 0,
    private var settingsReadFailuresRemainingAfterFirst: Int = 0,
    private val replaceBotTokenAction: (suspend (String) -> SettingsOperationResult)? = null,
) : ProtectionSettingsGateway {
    val savedSensitivity = mutableListOf<Int>()
    val savedSensorConfigurations = mutableListOf<SensorFusionConfiguration>()
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

    override fun saveSensorConfiguration(config: SensorFusionConfiguration): SettingsOperationResult {
        savedSensorConfigurations += config
        writeCount += 1
        return SettingsOperationResult(applied = true, message = "Sensor configuration updated")
    }

    override suspend fun replaceBotToken(token: String): SettingsOperationResult {
        tokenReplaceCalls += 1
        allowBotToken?.await()
        replaceBotTokenAction?.let { return it(token) }
        botTokenFailure?.let(::error)
        writeCount += 1
        return SettingsOperationResult(applied = true, message = "Bot token updated")
    }

    override suspend fun resetPairing(): SettingsOperationResult {
        resetPairingCalls += 1
        return SettingsOperationResult(applied = true, message = "Pairing reset")
    }

    override fun saveSmsFallback(destination: String): SettingsOperationResult {
        smsFallbackStarted?.countDown()
        allowSmsFallbackLatch?.let { gate ->
            require(gate.await(5, TimeUnit.SECONDS)) { "SMS save gate timed out" }
        }
        smsFallbackFailure?.let(::error)
        writeCount += 1
        return SettingsOperationResult(applied = true, message = "SMS fallback updated")
    }
}
