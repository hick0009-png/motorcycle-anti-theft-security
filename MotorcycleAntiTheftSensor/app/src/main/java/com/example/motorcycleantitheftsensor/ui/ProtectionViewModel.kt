package com.example.motorcycleantitheftsensor.ui

import androidx.lifecycle.ViewModel
import com.example.motorcycleantitheftsensor.protection.ChargingState
import com.example.motorcycleantitheftsensor.protection.CommandOutcome
import com.example.motorcycleantitheftsensor.protection.CommandOrigin
import com.example.motorcycleantitheftsensor.protection.GuidanceAction
import com.example.motorcycleantitheftsensor.protection.GuidanceCode
import com.example.motorcycleantitheftsensor.protection.GuidanceContent
import com.example.motorcycleantitheftsensor.protection.GuidanceSeverity
import com.example.motorcycleantitheftsensor.protection.EntryCommissioningEnvironment
import com.example.motorcycleantitheftsensor.protection.EntryCommissioningPolicy
import com.example.motorcycleantitheftsensor.protection.EntryOrientationMath
import com.example.motorcycleantitheftsensor.protection.EntryOrientationSample
import com.example.motorcycleantitheftsensor.protection.EntryProfileOverrides
import com.example.motorcycleantitheftsensor.protection.EntryProfileSettings
import com.example.motorcycleantitheftsensor.protection.IncidentRepository
import com.example.motorcycleantitheftsensor.protection.IncidentLifecycle
import com.example.motorcycleantitheftsensor.protection.IncidentType
import com.example.motorcycleantitheftsensor.protection.POWER_CHALLENGE_DEGRADED
import com.example.motorcycleantitheftsensor.protection.PowerArmChallengeRegistry
import com.example.motorcycleantitheftsensor.protection.PowerWitnessCommissioningPolicy
import com.example.motorcycleantitheftsensor.protection.PowerWitnessModel
import com.example.motorcycleantitheftsensor.protection.PowerWitnessSample
import com.example.motorcycleantitheftsensor.protection.ProtectionCommandResult
import com.example.motorcycleantitheftsensor.protection.ProtectionCoordinator
import com.example.motorcycleantitheftsensor.protection.ProtectionProfile
import com.example.motorcycleantitheftsensor.protection.ProtectionProfilePolicy
import com.example.motorcycleantitheftsensor.protection.ProtectionProfileRepository
import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshot
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import com.example.motorcycleantitheftsensor.protection.ProtectionRuntime
import com.example.motorcycleantitheftsensor.protection.SecurityIncident
import com.example.motorcycleantitheftsensor.protection.SensorConfigurationPolicy
import com.example.motorcycleantitheftsensor.protection.SensorFusionConfiguration
import com.example.motorcycleantitheftsensor.protection.SensorHealth
import com.example.motorcycleantitheftsensor.protection.SensorHealthState
import com.example.motorcycleantitheftsensor.protection.SensorPreset
import com.example.motorcycleantitheftsensor.protection.SensorKind
import com.example.motorcycleantitheftsensor.protection.SnapshotProjectionGate
import com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog
import com.example.motorcycleantitheftsensor.protection.AudioTelemetry
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class ProtectionViewModel(
    private val coordinator: ProtectionCoordinator,
    private val incidents: IncidentRepository,
    private val settings: ProtectionSettingsGateway,
    private val profileRepository: ProtectionProfileRepository? = null,
    private val profilePolicy: ProtectionProfilePolicy = ProtectionProfilePolicy(),
    private val entryRuntime: ProtectionRuntime? = null,
    private val powerRuntime: ProtectionRuntime? = null,
    private val powerArmChallenge: PowerArmChallengeRegistry? = null,
    private val initialMissingPermissions: Set<String> = emptySet(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val ticker: Flow<Unit> = flow {
        while (coroutineContext.isActive) {
            delay(1_000L)
            emit(Unit)
        }
    },
    private val snapshotProjectionGate: SnapshotProjectionGate =
        SnapshotProjectionGate(UI_SNAPSHOT_PROJECTION_INTERVAL_MS),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val callbackDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val elapsedNowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val settingsMutex = Mutex()
    private val eventsMutex = Mutex()
    private val protectionMutex = Mutex()
    private val commandSequence = AtomicLong(0L)
    private val activeProtectionOperations = AtomicLong(0L)
    private val settingsReadVersion = AtomicLong(0L)
    private val destination = MutableStateFlow(ProtectionDestination.PROTECTION)
    private val settingsSummary = MutableStateFlow(emptySettingsSummary())
    private val presentation = MutableStateFlow(PresentationInputs(settingsLoading = true))
    private val currentTimeMs = MutableStateFlow(nowMs())
    private val projectedSnapshots = coordinator.snapshot.filter { snapshot ->
        snapshotProjectionGate.shouldProject(snapshot, nowMs())
    }
    @Volatile private var pendingSwitchTarget: ProtectionProfile? = null
    @Volatile private var pendingEntryRearm: Boolean = false
    @Volatile private var previousSelectedProfile: ProtectionProfile? = null
    private val profileState = MutableStateFlow(ProtectionProfileUiState())
    private val selectedPowerWitnessModel = MutableStateFlow<PowerWitnessModel?>(null)
    private val entryCommissioningState = MutableStateFlow<EntryCommissioningUiState?>(null)
    private var commissioningPolicy: EntryCommissioningPolicy? = null
    private var commissioningPolicyState = EntryCommissioningPolicy.State()
    private var commissioningClosedBaseline: EntryOrientationSample? = null
    private var commissioningJob: kotlinx.coroutines.Job? = null
    private val powerCommissioningState = MutableStateFlow<PowerCommissioningUiState?>(null)
    private var powerCommissioningPolicy: PowerWitnessCommissioningPolicy? = null
    private var powerCommissioningPolicyState = PowerWitnessCommissioningPolicy.State()
    private var powerCommissioningJob: kotlinx.coroutines.Job? = null

    val audioTelemetry: StateFlow<AudioTelemetry> = coordinator.audioTelemetry

    val uiState: StateFlow<ProtectionUiState> = combine(
        combine(
            projectedSnapshots,
            destination,
            settingsSummary,
            presentation,
            currentTimeMs,
        ) { snapshot, selectedDestination, currentSettings, inputs, currentTime ->
            ProtectionUiState.from(
                snapshot = snapshot,
                incidents = inputs.incidents,
                settings = currentSettings,
                nowMs = currentTime,
                destination = selectedDestination,
                eventsLoading = inputs.eventsLoading,
                eventsError = inputs.eventsError,
                operationInFlight = inputs.anyOperationInFlight,
                message = inputs.message,
                settingsLoading = inputs.settingsLoading,
                settingsLoaded = inputs.settingsLoaded,
                settingsError = inputs.settingsError,
                protectionOperationInFlight = inputs.protectionOperationInFlight,
                settingsOperationInFlight = inputs.settingsOperationInFlight,
                eventsOperationInFlight = inputs.eventsOperationInFlight,
                activeSettingsOperation = inputs.activeSettingsOperation,
                audio = coordinator.audioTelemetry.value.toAudioUiTelemetry(
                    elapsedNowMs = elapsedNowMs(),
                ),
            )
        },
        profileState,
        entryCommissioningState,
        powerCommissioningState,
    ) { base, profile, commissioning, powerCommissioning ->
        base.copy(
            profile = profile.copy(
                commissioning = commissioning,
                powerCommissioning = powerCommissioning,
            ),
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = ProtectionUiState.from(
            snapshot = coordinator.snapshot.value,
            incidents = emptyList(),
            settings = settingsSummary.value,
            nowMs = nowMs(),
            settingsLoading = true,
            settingsLoaded = false,
            audio = coordinator.audioTelemetry.value.toAudioUiTelemetry(
                elapsedNowMs = elapsedNowMs(),
            ),
        ),
    )

    init {
        scope.launch {
            ticker.collect {
                currentTimeMs.value = nowMs()
                refreshPowerWitnessPlacementConfirmation()
                advancePowerCommissioningClock()
            }
        }
        scope.launch { refreshEvents() }
        scope.launch { readSettings(initialMissingPermissions) }
        scope.launch { refreshProfile() }
        scope.launch {
            // Both inputs matter: a new snapshot changes the live signals, and a newly
            // commissioned witness model changes how those signals are classified.
            combine(coordinator.snapshot, selectedPowerWitnessModel) { snapshot, witnessModel ->
                snapshot to witnessModel
            }
                .distinctUntilChanged()
                .collect { (snapshot, witnessModel) -> updatePowerSummary(snapshot, witnessModel) }
        }
    }

    fun selectDestination(destination: ProtectionDestination) {
        this.destination.value = destination
    }

    /**
     * Selects a protection profile. While disarmed the selection applies immediately;
     * while armed with an existing selection it only stages [pendingSwitchTarget] and
     * never touches the running runtime until explicit confirmation.
     */
    fun selectProfile(profile: ProtectionProfile) = runProtectionCommand(GuidanceCode.COMMAND_UNKNOWN) {
        val repository = profileRepository ?: return@runProtectionCommand
        val armed = coordinator.snapshot.value.state in setOf(
            ProtectionState.ARMING,
            ProtectionState.ARMED_HEALTHY,
            ProtectionState.ARMED_DEGRADED,
            ProtectionState.ALERT_ACTIVE,
        )
        val currentlySelected = runCatching { repository.load().selectedProfile }.getOrNull()
        if (armed && currentlySelected != null && currentlySelected != profile) {
            // Armed change requires explicit confirmation; stage only, persist nothing.
            pendingSwitchTarget = profile
            refreshProfile()
            return@runProtectionCommand
        }
        publishResult(coordinator.selectProfile(nextCommandId(), profile))
        pendingSwitchTarget = null
        refreshProfile()
    }

    fun confirmProfileSwitch() = runProtectionCommand(GuidanceCode.COMMAND_UNKNOWN) {
        val target = pendingSwitchTarget ?: return@runProtectionCommand
        publishResult(coordinator.changeProfile(nextCommandId(), target, confirmed = true))
        pendingSwitchTarget = null
        refreshProfile()
    }

    fun cancelProfileSwitch() {
        pendingSwitchTarget = null
        scope.launch { refreshProfile() }
    }

    /**
     * Sets the Entry alert angle. Values outside 5-90 are clamped (quick choices and
     * slider bounds per spec section 9). While armed the change is persisted for the
     * next controlled arm and the owner is directed through disarm/calibrate/re-arm;
     * the running session keeps its frozen threshold.
     */
    fun setEntryAngle(degrees: Int) = runSettingsCommand(
        SettingsOperation.UPDATE_SENSOR_CONFIG,
        GuidanceCode.SETTINGS_SAVE_FAILED,
    ) {
        val clamped = degrees.coerceIn(5, 90)
        val result = coordinator.updateSelectedProfile(nextCommandId()) { state ->
            val entry = state.profiles.getValue(ProtectionProfile.ENTRY)
            entry.copy(specificOverrides = EntryProfileOverrides(angleThresholdDegrees = clamped))
        }
        publishResult(result)
        if (result.outcome == CommandOutcome.APPLIED) {
            val armed = coordinator.snapshot.value.state in setOf(
                ProtectionState.ARMING,
                ProtectionState.ARMED_HEALTHY,
                ProtectionState.ARMED_DEGRADED,
                ProtectionState.ALERT_ACTIVE,
            )
            pendingEntryRearm = armed
            refreshProfile()
        }
    }

    /** Starts the guided two-cycle เข็มทิศประตู commissioning flow. */
    fun startEntryCommissioning(alertAngleDeg: Int) {
        val runtime = entryRuntime ?: return
        val repository = profileRepository ?: return
        val selectedAngle = alertAngleDeg.coerceIn(5, 90)
        val policy = EntryCommissioningPolicy(
            stillRequiredMs = 5_000L,
            stillToleranceDeg = 2.0,
            minPeakAngleDeg = selectedAngle.toDouble(),
            closeThresholdDeg = 3.0,
            axisAgreementToleranceDeg = 10.0,
            sensorIdentity = EntryCommissioningEnvironment.sensorIdentity(),
            mountSignature = EntryCommissioningEnvironment.mountSignature(),
            orientationSourcePolicy = EntryCommissioningEnvironment.ORIENTATION_SOURCE_POLICY,
        )
        commissioningPolicy = policy
        // start() enters STILL_CHECK; a bare State() stays IDLE and drops every sample.
        commissioningPolicyState = policy.start()
        commissioningClosedBaseline = null
        entryCommissioningState.value = EntryCommissioningUiState(
            phase = EntryCommissioningPhase.STILL_CHECK,
            selectedAngleDeg = selectedAngle,
        )
        runtime.startEntryCommissioningStream()
        commissioningJob = scope.launch {
            runtime.entryOrientationSamples().collect { sample ->
                advanceEntryCommissioning(repository, runtime, sample)
            }
        }
    }

    fun cancelEntryCommissioning() {
        commissioningJob?.cancel()
        commissioningJob = null
        commissioningPolicy = null
        commissioningClosedBaseline = null
        entryRuntime?.stopEntryCommissioningStream()
        entryCommissioningState.value = null
        scope.launch { refreshProfile() }
    }

    private suspend fun advanceEntryCommissioning(
        repository: ProtectionProfileRepository,
        runtime: ProtectionRuntime,
        sample: EntryOrientationSample,
    ) {
        val policy = commissioningPolicy ?: return
        val current = entryCommissioningState.value ?: return
        val previousPhase = commissioningPolicyState.phase
        commissioningPolicyState = policy.onSample(commissioningPolicyState, sample)

        // Live angle for the compass display: relative to the most recent closed reading.
        val closed = commissioningClosedBaseline
        val liveDeg = if (closed != null) {
            EntryOrientationMath.totalRotationDeg(
                EntryOrientationMath.relativeRotation(closed.quaternion, sample.quaternion),
            )
        } else {
            0.0
        }
        if (
            commissioningPolicyState.phase == EntryCommissioningPolicy.Phase.AWAITING_CYCLE_ONE ||
            commissioningPolicyState.phase == EntryCommissioningPolicy.Phase.AWAITING_CYCLE_TWO ||
            previousPhase == EntryCommissioningPolicy.Phase.STILL_CHECK
        ) {
            if (liveDeg <= 3.0) commissioningClosedBaseline = sample
        }

        val nextPhase = when (commissioningPolicyState.phase) {
            EntryCommissioningPolicy.Phase.STILL_CHECK -> EntryCommissioningPhase.STILL_CHECK
            EntryCommissioningPolicy.Phase.AWAITING_CYCLE_ONE -> EntryCommissioningPhase.CYCLE_ONE
            EntryCommissioningPolicy.Phase.AWAITING_CYCLE_TWO -> EntryCommissioningPhase.CYCLE_TWO
            EntryCommissioningPolicy.Phase.COMMISSIONED -> EntryCommissioningPhase.COMMISSIONED
            EntryCommissioningPolicy.Phase.IDLE -> EntryCommissioningPhase.FAILED
        }
        entryCommissioningState.value = current.copy(phase = nextPhase, liveAngleDeg = liveDeg)

        if (commissioningPolicyState.phase == EntryCommissioningPolicy.Phase.COMMISSIONED) {
            val model = commissioningPolicyState.model
            if (model != null) {
                repository.update { profilePolicy.commissionEntry(it, model) }
            }
            commissioningPolicy = null
            runtime.stopEntryCommissioningStream()
            entryCommissioningState.value = null
            // Refresh before cancelling: this runs inside the commissioning job and a
            // self-cancel here would abort the profile-state refresh below.
            refreshProfile()
            commissioningJob?.cancel()
            commissioningJob = null
        }
    }

    /** Starts the guided lamp off/on witness commissioning flow (charger connected). */
    fun startPowerCommissioning() {
        val runtime = powerRuntime ?: return
        val repository = profileRepository ?: return
        val policy = PowerWitnessCommissioningPolicy(
            windowDurationMs = 10_000L,
            maxSampleGapMs = 2_000L,
            maxRangeSpanLux = 20.0,
            guardBandLux = 10.0,
            sensorIdentity = EntryCommissioningEnvironment.sensorIdentity(),
            hoodSignature = PowerWitnessCommissioningPolicy.DEFAULT_HOOD_SIGNATURE,
        )
        powerCommissioningPolicy = policy
        // start() enters DARK_WINDOW; a bare State() stays IDLE and drops every sample.
        powerCommissioningPolicyState = policy.start()
        powerCommissioningState.value = PowerCommissioningUiState(
            phase = PowerCommissioningPhase.DARK_WINDOW,
        )
        runtime.startPowerCommissioningStream()
        powerCommissioningJob = scope.launch {
            runtime.powerWitnessSamples().collect { sample ->
                advancePowerCommissioning(repository, runtime, sample)
            }
        }
    }

    fun cancelPowerCommissioning() {
        powerCommissioningJob?.cancel()
        powerCommissioningJob = null
        powerCommissioningPolicy = null
        powerRuntime?.stopPowerCommissioningStream()
        powerCommissioningState.value = null
        scope.launch { refreshProfile() }
    }

    /** Clears only Power Guard witness calibration so the owner can run the guided flow again. */
    fun resetPowerCalibration() = runProtectionCommand(GuidanceCode.SETTINGS_SAVE_FAILED) {
        val repository = profileRepository ?: return@runProtectionCommand
        val result = repository.update { profilePolicy.decommissionPower(it) }
        if (result.isSuccess) {
            selectedPowerWitnessModel.value = null
            refreshProfile()
        } else {
            publishMessage(UserGuidanceCatalog.content(GuidanceCode.SETTINGS_SAVE_FAILED))
        }
    }

    /** Records the per-arm lamp off/on integrity challenge as just passed. */
    fun markPowerChallengePassed() {
        val now = nowMs()
        powerArmChallenge?.markPassed(now)
        refreshPowerWitnessPlacementConfirmation(now)
    }

    /** Keeps the UI confirmation truthful when its pre-Arm validity window expires. */
    private fun refreshPowerWitnessPlacementConfirmation(now: Long = nowMs()) {
        val confirmed = powerArmChallenge?.isSatisfied(now) == true
        profileState.update { current ->
            if (current.powerWitnessPlacementConfirmed == confirmed) current
            else current.copy(powerWitnessPlacementConfirmed = confirmed)
        }
    }

    private suspend fun advancePowerCommissioning(
        repository: ProtectionProfileRepository,
        runtime: ProtectionRuntime,
        sample: PowerWitnessSample,
    ) {
        val policy = powerCommissioningPolicy ?: return
        val current = powerCommissioningState.value ?: return
        powerCommissioningPolicyState = policy.onSample(powerCommissioningPolicyState, sample)
        applyPowerCommissioningState(repository, runtime, current, liveLux = sample.lux)
    }

    private suspend fun advancePowerCommissioningClock() {
        val repository = profileRepository ?: return
        val runtime = powerRuntime ?: return
        val policy = powerCommissioningPolicy ?: return
        val current = powerCommissioningState.value ?: return
        val previousPhase = powerCommissioningPolicyState.phase
        powerCommissioningPolicyState = policy.onTick(
            powerCommissioningPolicyState,
            elapsedNowMs(),
        )
        if (powerCommissioningPolicyState.phase == previousPhase) return

        applyPowerCommissioningState(repository, runtime, current, liveLux = current.liveLux)
    }

    private suspend fun applyPowerCommissioningState(
        repository: ProtectionProfileRepository,
        runtime: ProtectionRuntime,
        current: PowerCommissioningUiState,
        liveLux: Double?,
    ) {
        val nextPhase = when (powerCommissioningPolicyState.phase) {
            PowerWitnessCommissioningPolicy.Phase.DARK_WINDOW -> PowerCommissioningPhase.DARK_WINDOW
            PowerWitnessCommissioningPolicy.Phase.LIT_WINDOW -> PowerCommissioningPhase.LIT_WINDOW
            PowerWitnessCommissioningPolicy.Phase.COMMISSIONED -> PowerCommissioningPhase.COMMISSIONED
            PowerWitnessCommissioningPolicy.Phase.IDLE -> PowerCommissioningPhase.FAILED
        }
        powerCommissioningState.value = current.copy(
            phase = nextPhase,
            liveLux = liveLux,
            failureReason = powerCommissioningPolicyState.rejectionReason,
        )

        if (powerCommissioningPolicyState.phase == PowerWitnessCommissioningPolicy.Phase.COMMISSIONED) {
            val model = powerCommissioningPolicyState.model ?: return
            val saved = repository.update { profilePolicy.commissionPower(it, model) }
            powerCommissioningPolicy = null
            runtime.stopPowerCommissioningStream()
            if (saved.isSuccess) {
                powerCommissioningState.value = null
                // Refresh before cancelling: this can run inside the sample collector,
                // where cancelling first would abort the durable profile refresh.
                refreshProfile()
            } else {
                powerCommissioningState.value = current.copy(
                    phase = PowerCommissioningPhase.FAILED,
                    liveLux = liveLux,
                    failureReason = POWER_COMMISSIONING_SAVE_FAILED,
                )
                publishMessage(UserGuidanceCatalog.content(GuidanceCode.SETTINGS_SAVE_FAILED))
            }
            powerCommissioningJob?.cancel()
            powerCommissioningJob = null
        }
    }

    fun restoreRecommendedProfile() = runProtectionCommand(GuidanceCode.SETTINGS_SAVE_FAILED) {
        val repository = profileRepository ?: return@runProtectionCommand
        val state = runCatching { repository.load() }.getOrNull() ?: return@runProtectionCommand
        val selected = state.selectedProfile ?: return@runProtectionCommand
        val restored = profilePolicy.restoreRecommended(state, selected)
        val saved = repository.save(restored)
        if (saved.isSuccess) {
            publishResult(coordinator.selectProfile(nextCommandId(), selected))
        } else {
            publishMessage(UserGuidanceCatalog.content(GuidanceCode.SETTINGS_SAVE_FAILED))
        }
        refreshProfile()
    }

    private suspend fun refreshProfile() {
        val repository = profileRepository ?: return
        val state = try {
            withContext(dispatcher) { repository.load() }
        } catch (_: Exception) {
            return
        }
        val selected = state.selectedProfile
        val resolved = selected?.let { candidate ->
            runCatching { profilePolicy.resolve(state, candidate) }.getOrNull()
        }
        val entryAngle = (resolved?.specificSettings as? EntryProfileSettings)?.angleThresholdDegrees
        val powerWitnessModel = state.profiles[selected]?.powerWitnessModel
        selectedPowerWitnessModel.value = powerWitnessModel
        val snapshot = coordinator.snapshot.value
                if (previousSelectedProfile == ProtectionProfile.POWER && selected != ProtectionProfile.POWER) {
                    powerRuntime?.stopPowerStatusMonitoring()
                }
                if (selected == ProtectionProfile.POWER) {
                    powerRuntime?.startPowerStatusMonitoring()
                }
                previousSelectedProfile = selected
                profileState.value = ProtectionProfileUiState(
            selectedProfile = selected,
            armedProfile = snapshot.armedProfileSnapshot?.profile,
            setupState = resolved?.setupState,
            customized = resolved?.customized ?: false,
            showPicker = selected == null,
            pendingSwitchTarget = pendingSwitchTarget,
            entryAngleDegrees = entryAngle,
            entryRequiresControlledRearm = pendingEntryRearm,
            powerSummary = if (selected == ProtectionProfile.POWER) {
                powerSummaryRows(
                    chargingState = snapshot.chargingState,
                    degradationReasons = snapshot.degradationReasons,
                    lightSensorHealth = snapshot.sensorHealth[SensorKind.LIGHT],
                    powerSensorHealth = snapshot.sensorHealth[SensorKind.POWER_THERMAL],
                    witnessModel = powerWitnessModel,
                    confirmedFault = snapshot.hasConfirmedPowerFault(),
                )
            } else {
                null
            },
        )
    }

    private fun updatePowerSummary(snapshot: ProtectionSnapshot, witnessModel: PowerWitnessModel?) {
        profileState.update { current ->
            if (current.selectedProfile != ProtectionProfile.POWER) {
                return@update current
            }
            val updatedSummary = powerSummaryRows(
                chargingState = snapshot.chargingState,
                degradationReasons = snapshot.degradationReasons,
                lightSensorHealth = snapshot.sensorHealth[SensorKind.LIGHT],
                powerSensorHealth = snapshot.sensorHealth[SensorKind.POWER_THERMAL],
                witnessModel = witnessModel,
                confirmedFault = snapshot.hasConfirmedPowerFault(),
            )
            val armedProfile = snapshot.armedProfileSnapshot?.profile
            if (current.powerSummary == updatedSummary && current.armedProfile == armedProfile) current
            else current.copy(
                armedProfile = armedProfile,
                powerSummary = updatedSummary,
            )
        }
    }

    private var activeProtectionJob: kotlinx.coroutines.Job? = null

    fun arm() = runProtectionCommand(GuidanceCode.COMMAND_ARM_REJECTED) {
        if (coordinator.snapshot.value.state == ProtectionState.ARMING) return@runProtectionCommand
        publishResult(coordinator.arm(nextCommandId(), CommandOrigin.LOCAL))
    }

    fun disarm() = runProtectionCommand(GuidanceCode.COMMAND_DISARM_REJECTED) {
        publishResult(coordinator.disarm(nextCommandId(), CommandOrigin.LOCAL))
        pendingEntryRearm = false
        refreshProfile()
    }

    fun changeSensitivity(level: Int) = runSettingsCommand(SettingsOperation.CHANGE_SENSITIVITY, GuidanceCode.COMMAND_UNKNOWN) {
        val result = coordinator.changeSensitivity(nextCommandId(), level)
        publishResult(result)
        if (result.outcome == CommandOutcome.APPLIED) {
            settings.saveSensitivity(level)
            readSettings(settingsSummary.value.missingPermissions)
        }
    }

    fun updateSensorConfiguration(config: SensorFusionConfiguration) = runSettingsCommand(
        SettingsOperation.UPDATE_SENSOR_CONFIG,
        GuidanceCode.SETTINGS_SAVE_FAILED,
    ) {
        val result = coordinator.updateSensorConfiguration(nextCommandId(), config)
        publishResult(result)
        if (result.outcome == CommandOutcome.APPLIED) {
            val saveResult = settings.saveSensorConfiguration(config)
            if (saveResult.applied) {
                readSettings(settingsSummary.value.missingPermissions)
            }
        }
    }

    fun applySensorPreset(preset: SensorPreset) {
        val config = SensorConfigurationPolicy().forPreset(preset)
        updateSensorConfiguration(config)
    }

    fun clearHistory() = runEventsCommand(GuidanceCode.COMMAND_UNKNOWN) {
        presentation.update { it.copy(eventsLoading = true, eventsError = null) }
        try {
            withContext(dispatcher) { incidents.clearHistory() }
            refreshEvents()
        } catch (exception: CancellationException) {
            presentation.update { it.copy(eventsLoading = false) }
            throw exception
        } catch (exception: Throwable) {
            presentation.update { it.copy(eventsLoading = false) }
            throw exception
        }
    }

    fun updateMissingPermissions(permissions: Set<String>) = runSettingsCommand(SettingsOperation.REFRESH_PERMISSIONS, GuidanceCode.SETTINGS_SAVE_FAILED) {
        readSettings(permissions)
    }

    fun replaceBotToken(token: String) {
        if (token.isBlank()) {
            publishMessage(UserGuidanceCatalog.content(GuidanceCode.BOT_TOKEN_INVALID))
            return
        }
        runSettingsCommand(SettingsOperation.REPLACE_BOT_TOKEN, GuidanceCode.SETTINGS_SAVE_FAILED) {
            val result = settings.replaceBotToken(token)
            if (result.applied) {
                publishMessage(
                    GuidanceContent(
                        titleTh = "บันทึกและเชื่อมต่อ Bot สำเร็จ",
                        bodyTh = result.message,
                        telegramTh = null,
                        severity = GuidanceSeverity.SUCCESS,
                        action = GuidanceAction.NONE,
                        persistent = false,
                    )
                )
                readSettings(settingsSummary.value.missingPermissions)
            } else {
                publishMessage(
                    GuidanceContent(
                        titleTh = "บันทึก Bot Token ไม่สำเร็จ",
                        bodyTh = result.message,
                        telegramTh = null,
                        severity = GuidanceSeverity.WARNING,
                        action = GuidanceAction.RETRY_NON_SENSITIVE_SETTINGS,
                        persistent = false,
                    )
                )
            }
        }
    }

    fun configureSmsFallback(destination: String, aesKey: String) = runSettingsCommand(SettingsOperation.SAVE_SMS_FALLBACK, GuidanceCode.SETTINGS_SAVE_FAILED) {
        val result = settings.saveSmsFallback(destination, aesKey)
        if (result.applied) {
            publishMessage(
                GuidanceContent(
                    titleTh = "บันทึก SMS สำรองสำเร็จ",
                    bodyTh = "บันทึกเบอร์ปลายทางและคีย์เข้ารหัสเรียบร้อยแล้ว",
                    telegramTh = null,
                    severity = GuidanceSeverity.SUCCESS,
                    action = GuidanceAction.NONE,
                    persistent = false,
                )
            )
            readSettings(settingsSummary.value.missingPermissions)
        } else {
            publishMessage(
                GuidanceContent(
                    titleTh = "บันทึก SMS สำรองไม่สำเร็จ",
                    bodyTh = "ตรวจสอบข้อมูลแล้วลองใหม่",
                    telegramTh = null,
                    severity = GuidanceSeverity.WARNING,
                    action = GuidanceAction.RETRY_NON_SENSITIVE_SETTINGS,
                    persistent = false,
                )
            )
        }
    }

    fun retry() = runEventsCommand(GuidanceCode.COMMAND_UNKNOWN) {
        refreshEvents()
    }

    fun retrySettings() = runSettingsCommand(SettingsOperation.RETRY_SETTINGS, GuidanceCode.COMMAND_UNKNOWN) {
        readSettings(settingsSummary.value.missingPermissions)
    }

    fun resetPairing() = runSettingsCommand(SettingsOperation.RESET_PAIRING, GuidanceCode.SETTINGS_SAVE_FAILED) {
        val result = settings.resetPairing()
        if (result.applied) {
            publishMessage(
                GuidanceContent(
                    titleTh = "รีเซ็ตการจับคู่สำเร็จ",
                    bodyTh = "กรุณาใช้รหัสจับคู่ใหม่บน Telegram",
                    telegramTh = null,
                    severity = GuidanceSeverity.SUCCESS,
                    action = GuidanceAction.NONE,
                    persistent = false,
                )
            )
            readSettings(settingsSummary.value.missingPermissions)
        } else {
            publishMessage(
                GuidanceContent(
                    titleTh = "รีเซ็ตการจับคู่ไม่สำเร็จ",
                    bodyTh = result.message,
                    telegramTh = null,
                    severity = GuidanceSeverity.WARNING,
                    action = GuidanceAction.RETRY_NON_SENSITIVE_SETTINGS,
                    persistent = false,
                )
            )
        }
    }

    fun consumeMessage(id: Long) {
        presentation.update { current ->
            if (current.message?.id == id) current.copy(message = null) else current
        }
    }

    override fun onCleared() {
        powerRuntime?.stopPowerStatusMonitoring()
        scope.cancel()
        super.onCleared()
    }

    private fun runProtectionCommand(
        failureCode: GuidanceCode,
        action: suspend () -> Unit,
    ) {
        scope.launch {
            activeProtectionOperations.incrementAndGet()
            presentation.update { it.copy(protectionOperationInFlight = true) }
            try {
                action()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Throwable) {
                publishMessage(UserGuidanceCatalog.content(failureCode))
            } finally {
                if (activeProtectionOperations.decrementAndGet() == 0L) {
                    presentation.update { it.copy(protectionOperationInFlight = false) }
                }
            }
        }
    }

    private fun runSettingsCommand(
        operation: SettingsOperation,
        failureCode: GuidanceCode,
        action: suspend () -> Unit,
    ) {
        scope.launch {
            settingsMutex.withLock {
                presentation.update {
                    it.copy(
                        settingsOperationInFlight = true,
                        activeSettingsOperation = operation,
                    )
                }
                try {
                    action()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Throwable) {
                    publishMessage(UserGuidanceCatalog.content(failureCode))
                } finally {
                    presentation.update {
                        it.copy(
                            settingsOperationInFlight = false,
                            activeSettingsOperation = null,
                        )
                    }
                }
            }
        }
    }

    private fun runEventsCommand(failureCode: GuidanceCode, action: suspend () -> Unit) {
        scope.launch {
            eventsMutex.withLock {
                presentation.update { it.copy(eventsOperationInFlight = true) }
                try {
                    action()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Throwable) {
                    publishMessage(UserGuidanceCatalog.content(failureCode))
                } finally {
                    presentation.update { it.copy(eventsOperationInFlight = false) }
                }
            }
        }
    }

    private fun <T> runSensitiveSettingsCommand(
        operation: SettingsOperation,
        failureCode: GuidanceCode,
        failureValue: T,
        onComplete: (T) -> Unit,
        action: suspend () -> T,
    ): () -> Unit {
        val job = scope.launch {
            val result = settingsMutex.withLock {
                presentation.update {
                    it.copy(
                        settingsOperationInFlight = true,
                        activeSettingsOperation = operation,
                    )
                }
                try {
                    action()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Throwable) {
                    publishMessage(UserGuidanceCatalog.content(failureCode))
                    failureValue
                } finally {
                    presentation.update {
                        it.copy(
                            settingsOperationInFlight = false,
                            activeSettingsOperation = null,
                        )
                    }
                }
            }
            withContext(callbackDispatcher) { onComplete(result) }
        }
        return job::cancel
    }

    private suspend fun refreshEvents() {
        presentation.update { it.copy(eventsLoading = true, eventsError = null) }
        try {
            val records = withContext(dispatcher) { incidents.listNewestFirst() }
            presentation.update {
                it.copy(incidents = records, eventsLoading = false, eventsError = null)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Throwable) {
            presentation.update {
                it.copy(
                    eventsLoading = false,
                    eventsError = exception.message ?: "Unable to load event history",
                )
            }
        }
    }

    private suspend fun readSettings(permissions: Set<String>) {
        val version = settingsReadVersion.incrementAndGet()
        presentation.update { it.copy(settingsLoading = true, settingsError = null) }
        try {
            val summary = withContext(dispatcher) { settings.read(permissions) }
            if (version != settingsReadVersion.get()) return
            settingsSummary.value = summary
            presentation.update {
                it.copy(
                    settingsLoading = false,
                    settingsLoaded = true,
                    settingsError = null,
                )
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Throwable) {
            if (version != settingsReadVersion.get()) return
            presentation.update { current ->
                current.copy(
                    settingsLoading = false,
                    settingsLoaded = current.settingsLoaded,
                    settingsError = exception.message ?: "Unable to load settings",
                )
            }
        }
    }

    private fun publishResult(result: ProtectionCommandResult) {
        val content = when (result.outcome) {
            CommandOutcome.APPLIED -> when (result.resultingState) {
                com.example.motorcycleantitheftsensor.protection.ProtectionState.ARMING,
                com.example.motorcycleantitheftsensor.protection.ProtectionState.ARMED_HEALTHY,
                com.example.motorcycleantitheftsensor.protection.ProtectionState.ARMED_DEGRADED -> UserGuidanceCatalog.content(GuidanceCode.COMMAND_ARM_APPLIED)
                com.example.motorcycleantitheftsensor.protection.ProtectionState.DISARMED_ONLINE -> UserGuidanceCatalog.content(GuidanceCode.COMMAND_DISARM_APPLIED)
                com.example.motorcycleantitheftsensor.protection.ProtectionState.ALERT_ACTIVE -> UserGuidanceCatalog.content(GuidanceCode.ALERT_ACTIVE)
                else -> UserGuidanceCatalog.content(GuidanceCode.COMMAND_STATUS_SUCCESS)
            }
            CommandOutcome.REJECTED -> {
                if (result.reason.equals("Arming already in progress", ignoreCase = true)) return
                if (result.reason.contains("Arm", ignoreCase = true)) UserGuidanceCatalog.content(GuidanceCode.COMMAND_ARM_REJECTED)
                else if (result.reason.contains("Disarm", ignoreCase = true)) UserGuidanceCatalog.content(GuidanceCode.COMMAND_DISARM_REJECTED)
                else if (result.reason.contains("Sensitivity", ignoreCase = true)) UserGuidanceCatalog.content(GuidanceCode.COMMAND_SENSITIVITY_INVALID)
                else UserGuidanceCatalog.content(GuidanceCode.COMMAND_UNKNOWN)
            }
            CommandOutcome.RECEIVED -> UserGuidanceCatalog.content(GuidanceCode.COMMAND_STATUS_SUCCESS)
            CommandOutcome.UNKNOWN -> UserGuidanceCatalog.content(GuidanceCode.COMMAND_UNKNOWN)
        }
        publishMessage(content)
    }

    private fun publishMessage(content: GuidanceContent) {
        presentation.update { current ->
            current.copy(message = ProtectionUiMessage(id = commandSequence.incrementAndGet(), content = content))
        }
    }

    private fun nextCommandId(): String = "ui-${UUID.randomUUID()}"

    private data class PresentationInputs(
        val incidents: List<SecurityIncident> = emptyList(),
        val eventsLoading: Boolean = false,
        val eventsError: String? = null,
        val message: ProtectionUiMessage? = null,
        val settingsLoading: Boolean = false,
        val settingsLoaded: Boolean = false,
        val settingsError: String? = null,
        val protectionOperationInFlight: Boolean = false,
        val settingsOperationInFlight: Boolean = false,
        val eventsOperationInFlight: Boolean = false,
        val activeSettingsOperation: SettingsOperation? = null,
    ) {
        val anyOperationInFlight: Boolean
            get() = protectionOperationInFlight || settingsOperationInFlight || eventsOperationInFlight
    }

    private companion object {
        const val UI_SNAPSHOT_PROJECTION_INTERVAL_MS = 1_000L
        const val POWER_COMMISSIONING_SAVE_FAILED = "save-failed"

        fun emptySettingsSummary() = ProtectionSettingsSummary(
            tokenConfigured = false,
            pairedOwnerCount = 0,
            pairingCode = null,
            sensitivity = 1,
            smsFallbackConfigured = false,
            missingPermissions = emptySet(),
        )
    }
}

/**
 * Projects the two independent POWER signal rows (spec sections 3.6/5) from
 * coordinator-owned data only. Neither row alone may claim an outage: a charger loss
 * is a health condition, a dark/unavailable witness is a health condition, and only
 * their dual confirmation belongs to the arbiter's incident pipeline.
 */
internal fun powerSummaryRows(
    chargingState: ChargingState,
    degradationReasons: Set<String>,
    lightSensorHealth: SensorHealth?,
    powerSensorHealth: SensorHealth? = null,
    witnessModel: PowerWitnessModel? = null,
    confirmedFault: Boolean = false,
): PowerSummaryRows {
    val charging = when (chargingState) {
        ChargingState.CHARGING -> ChargingRowState.CHARGING
        ChargingState.DISCHARGING -> ChargingRowState.DISCHARGING
        ChargingState.FULL -> ChargingRowState.FULL
        ChargingState.NOT_CHARGING -> ChargingRowState.NOT_CHARGING
        ChargingState.UNKNOWN -> ChargingRowState.UNKNOWN
    }
    val lightUsable = lightSensorHealth != null &&
        (
            lightSensorHealth.state == SensorHealthState.HEALTHY ||
                lightSensorHealth.state == SensorHealthState.AVAILABLE
            )
    val lastLux = lightSensorHealth?.lightDetail?.lastLux
    val armedDarkThreshold = lightSensorHealth?.lightDetail?.armedWitnessDarkThresholdLux
    val armedLitThreshold = lightSensorHealth?.lightDetail?.armedWitnessLitThresholdLux
    val darkThreshold = armedDarkThreshold ?: witnessModel?.witnessDarkThresholdLux
    val litThreshold = armedLitThreshold ?: witnessModel?.witnessLitThresholdLux
    val requiresWitnessPlacementRevalidation = POWER_CHALLENGE_DEGRADED in degradationReasons
    val witness = when {
        // A skipped per-arm placement check is reported through
        // requiresWitnessPlacementRevalidation, never by calling a working light
        // sensor unavailable.
        !lightUsable -> WitnessRowState.UNAVAILABLE
        lastLux != null && darkThreshold != null && lastLux <= darkThreshold -> WitnessRowState.DARK
        lastLux != null && litThreshold != null && lastLux >= litThreshold -> WitnessRowState.DETECTED
        lastLux != null && darkThreshold != null && litThreshold != null -> WitnessRowState.AMBIGUOUS
        // Sensor readable but no calibrated classification is possible yet: say so
        // instead of claiming the witness lamp was detected.
        lightUsable -> WitnessRowState.AVAILABLE
        else -> WitnessRowState.UNAVAILABLE
    }
    return PowerSummaryRows(
        charging = charging,
        witness = witness,
        lastUpdatedAtMs = listOfNotNull(
            powerSensorHealth?.lastSampleAtMs,
            powerSensorHealth?.powerThermalDetail?.lastUpdateWallClockMs,
            lightSensorHealth?.lastSampleAtMs,
            lightSensorHealth?.lightDetail?.lastSampleWallClockMs,
        ).maxOrNull(),
        confirmedFault = confirmedFault,
        lastLux = lastLux,
        requiresWitnessPlacementRevalidation = requiresWitnessPlacementRevalidation,
    )
}

private fun ProtectionSnapshot.hasConfirmedPowerFault(): Boolean =
    state == ProtectionState.ALERT_ACTIVE &&
        lastIncident?.type == IncidentType.POWER &&
        lastIncident.lifecycle == IncidentLifecycle.OPEN
