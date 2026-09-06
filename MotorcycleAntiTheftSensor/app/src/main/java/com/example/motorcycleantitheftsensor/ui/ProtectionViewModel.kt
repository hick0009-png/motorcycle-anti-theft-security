package com.example.motorcycleantitheftsensor.ui

import androidx.lifecycle.ViewModel
import com.example.motorcycleantitheftsensor.protection.ChargingState
import com.example.motorcycleantitheftsensor.protection.CommandOutcome
import com.example.motorcycleantitheftsensor.protection.CommandOrigin
import com.example.motorcycleantitheftsensor.protection.GuidanceAction
import com.example.motorcycleantitheftsensor.protection.GuidanceCode
import com.example.motorcycleantitheftsensor.protection.GuidanceContent
import com.example.motorcycleantitheftsensor.protection.GuidanceDetail
import com.example.motorcycleantitheftsensor.protection.GuidanceSeverity
import com.example.motorcycleantitheftsensor.protection.EntryCommissioningEnvironment
import com.example.motorcycleantitheftsensor.protection.EntryCommissioningPolicy
import com.example.motorcycleantitheftsensor.protection.EntryDriftBudgetPolicy
import com.example.motorcycleantitheftsensor.protection.EntryDriftMeasurementStore
import com.example.motorcycleantitheftsensor.protection.EntryDriftVerdict
import com.example.motorcycleantitheftsensor.protection.EntryHingeModel
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
import com.example.motorcycleantitheftsensor.sensor.SensorAvailabilityPolicy
import com.example.motorcycleantitheftsensor.protection.SensorSource
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
    private val sensorCatalog: com.example.motorcycleantitheftsensor.sensor.SensorCatalog? = null,
    /**
     * What this phone measured about its own drift, read by the picker for the same reason
     * the coordinator reads it: a card the owner can press must be a card that will be
     * accepted. Null leaves the picker exactly as it was — drift-blind, which is what a
     * phone that never measured deserves anyway.
     */
    private val driftMeasurementStore: EntryDriftMeasurementStore? = null,
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
    private val loadedEventsRevision = AtomicLong(Long.MIN_VALUE)
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
    @Volatile private var commissioningPolicy: EntryCommissioningPolicy? = null
    @Volatile private var commissioningPolicyState = EntryCommissioningPolicy.State()
    @Volatile private var commissioningJob: kotlinx.coroutines.Job? = null

    /**
     * Serializes every read-modify-write of [commissioningPolicyState]. Samples arrive on the
     * commissioning job while the owner's taps arrive on the main thread, and that stream is
     * registered at game rate: without this, a tap lands between a sample's read and its write
     * and the sample puts the pre-tap state straight back. `@Volatile` publishes the field to
     * the other thread; it does not make the pair of operations one.
     */
    private val commissioningMutex = Mutex()

    /**
     * Last sample the commissioning job saw. A tare re-zeroes onto a reading, and the only
     * honest reading to use is the one the policy just judged.
     */
    @Volatile private var lastCommissioningSample: EntryOrientationSample? = null
    private val powerCommissioningState = MutableStateFlow<PowerCommissioningUiState?>(null)
    private var powerCommissioningPolicy: PowerWitnessCommissioningPolicy? = null
    private var powerCommissioningPolicyState = PowerWitnessCommissioningPolicy.State()
    private var powerCommissioningJob: kotlinx.coroutines.Job? = null
    private var powerCommissioningWitnessSampleSeen = false
    private var powerCommissioningStartedElapsedMs = 0L

    /**
     * The hardware inventory never changes while the process lives, so it is read once
     * rather than recomputed on every snapshot.
     */
    private val sensorAvailability: Map<SensorSource, SensorAvailabilityUiModel> by lazy {
        val catalog = sensorCatalog ?: return@lazy emptyMap()
        catalog.descriptors().mapValues { (source, descriptor) ->
            SensorAvailabilityUiModel(
                source = source,
                availability = SensorAvailabilityPolicy.availability(descriptor),
                vendor = descriptor.vendor.takeIf { descriptor.isAvailable },
                powerMa = descriptor.powerMa.takeIf { descriptor.isAvailable },
            )
        }
    }

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
                sensorAvailability = sensorAvailability,
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
            sensorAvailability = sensorAvailability,
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
        scope.launch { eventsMutex.withLock { refreshEvents() } }
        scope.launch {
            // The history is written by the runtime, not by this screen, so nothing here
            // learns about a new incident unless the repository says it changed.
            incidents.revision.collect { revision ->
                eventsMutex.withLock {
                    if (revision != loadedEventsRevision.get()) {
                        refreshEvents(announceLoading = false)
                    }
                }
            }
        }
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
        cancelEntryCommissioning()
        cancelPowerCommissioning()
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
        publishProfileSelection(coordinator.selectProfile(nextCommandId(), profile))
        pendingSwitchTarget = null
        refreshProfile()
    }

    /**
     * Reports a profile selection as a profile selection.
     *
     * [publishResult] names an applied command after the state it left behind, and choosing
     * a use while disarmed leaves the system disarmed — so picking the door watch announced
     * "ปลดการป้องกันสำเร็จ", which is true of the state and says nothing about what the
     * owner just did. A refusal still goes the ordinary way; only the success is renamed.
     */
    private fun publishProfileSelection(result: ProtectionCommandResult) {
        if (result.outcome != CommandOutcome.APPLIED) {
            publishResult(result)
            return
        }
        publishMessage(UserGuidanceCatalog.content(GuidanceCode.PROFILE_SELECTED))
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

    /** Starts the guided two-cycle เข็มทิศประตู commissioning flow with user compensation settings. */
    fun startEntryCommissioning(
        alertAngleDeg: Int,
        closeThresholdDeg: Double = 4.0,
        axisToleranceDeg: Double = 16.0,
    ) {
        val runtime = entryRuntime ?: return
        val repository = profileRepository ?: return
        val selectedAngle = alertAngleDeg.coerceIn(5, 90)
        val selectedClose = closeThresholdDeg.coerceIn(2.0, 10.0).coerceAtMost(selectedAngle - 2.0)
        val selectedAxis = axisToleranceDeg.coerceIn(5.0, 30.0)
        val policy = EntryCommissioningPolicy(
            stillRequiredMs = 5_000L,
            stillToleranceDeg = 2.0,
            minPeakAngleDeg = selectedAngle.toDouble(),
            closeThresholdDeg = selectedClose,
            axisAgreementToleranceDeg = selectedAxis,
            // The source this phone will actually arm on, not the one it usually has: a model
            // commissioned here is compared against this string at every arm.
            sensorIdentity = EntryCommissioningEnvironment.orientationIdentity(runtime.entryOrientationSource()),
            mountSignature = EntryCommissioningEnvironment.mountSignature(),
            orientationSourcePolicy = EntryCommissioningEnvironment.orientationSourcePolicy(
                runtime.entryOrientationSource(),
            ),
        )
        commissioningPolicy = policy
        // start() enters STILL_CHECK; a bare State() stays IDLE and drops every sample.
        commissioningPolicyState = policy.start()
        // A tare belongs to the run it was pressed in: never re-zero onto a previous run's reading.
        lastCommissioningSample = null
        entryCommissioningState.value = EntryCommissioningUiState(
            phase = EntryCommissioningPhase.STILL_CHECK,
            selectedAngleDeg = selectedAngle,
            closeThresholdDeg = selectedClose,
            axisToleranceDeg = selectedAxis,
        )
        runtime.startEntryCommissioningStream()
        commissioningJob = scope.launch {
            runtime.entryOrientationSamples().collect { sample ->
                advanceEntryCommissioning(repository, runtime, sample)
            }
        }
    }

    /**
     * Moves the closed reference to wherever the door is now, keeping the cycles the owner has
     * already walked. A phone whose orientation source has drifted reads several degrees while
     * the door is genuinely shut, and the cycle can then never come back "below closed"; this
     * is the way out of that. It is not a way to lose a cycle that was already proven, which is
     * what `start()` did here — the button says the reading is zero, not that the flow restarts.
     *
     * The still check is the exception: there is no cycle to keep and no closed reference to
     * move yet, so the useful thing is to start the five seconds over.
     */
    fun tareEntryCommissioningZero() {
        scope.launch {
            commissioningMutex.withLock {
                val policy = commissioningPolicy ?: return@withLock
                val current = entryCommissioningState.value ?: return@withLock
                val sample = lastCommissioningSample
                commissioningPolicyState = if (
                    sample == null ||
                    commissioningPolicyState.phase == EntryCommissioningPolicy.Phase.STILL_CHECK
                ) {
                    policy.start()
                } else {
                    policy.tareBaseline(commissioningPolicyState, sample)
                }
                entryCommissioningState.value = current.copy(
                    phase = uiPhaseOf(commissioningPolicyState.phase),
                    liveAngleDeg = 0.0,
                    peakAngleDeg = 0.0,
                    failureReason = null,
                )
            }
        }
    }

    fun cancelEntryCommissioning() {
        commissioningJob?.cancel()
        commissioningJob = null
        commissioningPolicy = null
        commissioningPolicyState = EntryCommissioningPolicy.State()
        lastCommissioningSample = null
        entryRuntime?.stopEntryCommissioningStream()
        entryCommissioningState.value = null
        scope.launch { refreshProfile() }
    }

    private suspend fun advanceEntryCommissioning(
        repository: ProtectionProfileRepository,
        runtime: ProtectionRuntime,
        sample: EntryOrientationSample,
    ) {
        var commissioned = false
        var commissionedModel: EntryHingeModel? = null

        commissioningMutex.withLock {
            val policy = commissioningPolicy ?: return
            val current = entryCommissioningState.value ?: return
            // A cancel that landed while this sample waited for the lock has already torn the
            // flow down; the write below would put the card back on a screen that left it.
            if (!coroutineContext.isActive) return
            lastCommissioningSample = sample
            commissioningPolicyState = policy.onSample(commissioningPolicyState, sample)

            // Live angle for the compass display: the same closed reference the policy judges
            // this cycle against, so the number on screen and the verdict cannot disagree.
            val closed = commissioningPolicyState.cycleBaseline
            val liveDeg = if (closed != null) {
                EntryOrientationMath.totalRotationDeg(
                    EntryOrientationMath.relativeRotation(closed, sample.quaternion),
                )
            } else {
                0.0
            }

            entryCommissioningState.value = current.copy(
                phase = uiPhaseOf(commissioningPolicyState.phase),
                liveAngleDeg = liveDeg,
                peakAngleDeg = commissioningPolicyState.peakAngleDeg,
                failureReason = commissioningPolicyState.rejectionReason,
            )

            if (commissioningPolicyState.phase == EntryCommissioningPolicy.Phase.COMMISSIONED) {
                commissioned = true
                commissionedModel = commissioningPolicyState.model
                commissioningPolicy = null
                entryCommissioningState.value = null
            }
        }

        if (!commissioned) return
        // Storing the model and refreshing the profile touch the repository and other locks,
        // and nothing about them needs to be serialized against the next sample — which is why
        // they run after the commissioning lock is released rather than inside it.
        commissionedModel?.let { model ->
            repository.update { profilePolicy.commissionEntry(it, model) }
        }
        runtime.stopEntryCommissioningStream()
        // Refresh before cancelling: this runs inside the commissioning job and a
        // self-cancel here would abort the profile-state refresh below.
        refreshProfile()
        commissioningJob?.cancel()
        commissioningJob = null
    }

    private fun uiPhaseOf(phase: EntryCommissioningPolicy.Phase): EntryCommissioningPhase =
        when (phase) {
            EntryCommissioningPolicy.Phase.STILL_CHECK -> EntryCommissioningPhase.STILL_CHECK
            EntryCommissioningPolicy.Phase.AWAITING_CYCLE_ONE -> EntryCommissioningPhase.CYCLE_ONE
            EntryCommissioningPolicy.Phase.AWAITING_CYCLE_TWO -> EntryCommissioningPhase.CYCLE_TWO
            EntryCommissioningPolicy.Phase.COMMISSIONED -> EntryCommissioningPhase.COMMISSIONED
            EntryCommissioningPolicy.Phase.IDLE -> EntryCommissioningPhase.FAILED
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
        if (!runtime.startPowerCommissioningStream()) {
            // Nothing can observe the lamp, so the guided flow would sit on step 1 for
            // ever. Say why instead of pretending to wait for the owner.
            runtime.stopPowerCommissioningStream()
            powerCommissioningPolicy = null
            powerCommissioningState.value = PowerCommissioningUiState(
                phase = PowerCommissioningPhase.FAILED,
                failureReason = PowerCommissioningFailure.NO_LIGHT_SENSOR,
            )
            return
        }
        powerCommissioningPolicy = policy
        // start() enters DARK_WINDOW; a bare State() stays IDLE and drops every sample.
        powerCommissioningPolicyState = policy.start()
        powerCommissioningWitnessSampleSeen = false
        powerCommissioningStartedElapsedMs = elapsedNowMs()
        powerCommissioningState.value = PowerCommissioningUiState(
            phase = PowerCommissioningPhase.DARK_WINDOW,
        )
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
        powerCommissioningWitnessSampleSeen = true
        powerCommissioningPolicyState = policy.onSample(powerCommissioningPolicyState, sample)
        applyPowerCommissioningState(repository, runtime, current, liveLux = sample.lux)
    }

    private suspend fun advancePowerCommissioningClock() {
        val repository = profileRepository ?: return
        val runtime = powerRuntime ?: return
        val policy = powerCommissioningPolicy ?: return
        val current = powerCommissioningState.value ?: return
        if (
            !powerCommissioningWitnessSampleSeen &&
            elapsedNowMs() - powerCommissioningStartedElapsedMs >= POWER_COMMISSIONING_FIRST_SAMPLE_TIMEOUT_MS
        ) {
            // The source was acquired and then delivered nothing: a listener that died
            // quietly, or hardware that disappeared underneath us. Either way the owner
            // is holding a lamp for a window that will never close.
            failPowerCommissioning(runtime, current, PowerCommissioningFailure.NO_LIGHT_SAMPLES)
            return
        }
        val previousPhase = powerCommissioningPolicyState.phase
        powerCommissioningPolicyState = policy.onTick(
            powerCommissioningPolicyState,
            elapsedNowMs(),
        )
        if (powerCommissioningPolicyState.phase == previousPhase) return

        applyPowerCommissioningState(repository, runtime, current, liveLux = current.liveLux)
    }

    private fun failPowerCommissioning(
        runtime: ProtectionRuntime,
        current: PowerCommissioningUiState,
        reason: String,
    ) {
        powerCommissioningPolicy = null
        runtime.stopPowerCommissioningStream()
        powerCommissioningJob?.cancel()
        powerCommissioningJob = null
        powerCommissioningState.value = current.copy(
            phase = PowerCommissioningPhase.FAILED,
            failureReason = reason,
        )
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
                    failureReason = PowerCommissioningFailure.SAVE_FAILED,
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

    /**
     * This phone's drift verdict, read the same way the arm path reads it.
     *
     * A measurement taken on a source the phone no longer uses is discarded rather than
     * trusted, which is why the current source is asked for here rather than assumed.
     */
    private fun entryDriftVerdict(alertAngleDeg: Int): EntryDriftVerdict {
        val store = driftMeasurementStore ?: return EntryDriftVerdict.NotMeasured
        return runCatching {
            EntryDriftBudgetPolicy.verdict(
                measurement = store.load(),
                alertAngleDeg = alertAngleDeg,
                currentSource = entryRuntime?.entryOrientationSource(),
            )
        }.getOrDefault(EntryDriftVerdict.NotMeasured)
    }

    /**
     * Throws away what this phone measured about itself, so it can measure again.
     *
     * The store keeps the longer recording rather than the newer one, which is right when
     * both were honest and wrong when the first was taken with the phone in someone's hand:
     * without this the owner can never replace a contaminated overnight measurement, and the
     * door watch stays refused on a phone that is fine.
     */
    fun clearEntryDriftMeasurement() = runProtectionCommand(GuidanceCode.COMMAND_UNKNOWN) {
        val store = driftMeasurementStore ?: return@runProtectionCommand
        withContext(dispatcher) { store.clear() }
        refreshProfile()
    }

    private suspend fun refreshProfile() {
        // The commissioning flows write the profile store directly, so the coordinator
        // would otherwise carry yesterday's calibration facts into /status until the next
        // state transition happened to re-read them.
        coordinator.refreshModeContext()
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
        val entrySettings = resolved?.specificSettings as? EntryProfileSettings
        val entryAngle = entrySettings?.angleThresholdDegrees
        // The door watch is offered against the angle the owner set for it, whether or not
        // it is the use currently selected — the picker has to judge every card, not the
        // one already chosen.
        val entryAlertAngle = runCatching {
            (profilePolicy.resolve(state, ProtectionProfile.ENTRY).specificSettings as? EntryProfileSettings)
                ?.angleThresholdDegrees
        }.getOrNull() ?: DEFAULT_ENTRY_ALERT_ANGLE_DEG
        val driftVerdict = entryDriftVerdict(entryAlertAngle)
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
            entryLevel = entrySettings?.level,
            entryDriftVerdict = driftVerdict,
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
        publishResult(
            coordinator.arm(nextCommandId(), CommandOrigin.LOCAL),
            rejectionFallback = GuidanceCode.COMMAND_ARM_REJECTED,
        )
    }

    fun disarm() = runProtectionCommand(GuidanceCode.COMMAND_DISARM_REJECTED) {
        publishResult(
            coordinator.disarm(nextCommandId(), CommandOrigin.LOCAL),
            rejectionFallback = GuidanceCode.COMMAND_DISARM_REJECTED,
        )
        pendingEntryRearm = false
        refreshProfile()
    }

    fun changeSensitivity(level: Int) = runSettingsCommand(
        SettingsOperation.CHANGE_SENSITIVITY,
        GuidanceCode.COMMAND_SENSITIVITY_INVALID,
    ) {
        val result = coordinator.changeSensitivity(nextCommandId(), level)
        publishResult(result, rejectionFallback = GuidanceCode.COMMAND_SENSITIVITY_INVALID)
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

    fun configureSmsFallback(destination: String) = runSettingsCommand(SettingsOperation.SAVE_SMS_FALLBACK, GuidanceCode.SETTINGS_SAVE_FAILED) {
        val result = settings.saveSmsFallback(destination)
        if (result.applied) {
            publishMessage(
                GuidanceContent(
                    titleTh = "บันทึก SMS สำรองสำเร็จ",
                    bodyTh = "บันทึกเบอร์ปลายทางแล้ว กุญแจเข้ารหัสถูกสร้างในเครื่องให้อัตโนมัติ",
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
        cancelEntryCommissioning()
        cancelPowerCommissioning()
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

    /**
     * @param announceLoading false for automatic reads the owner never asked for. Such a
     * refresh must not flash the spinner, and must not replace events that are still on
     * screen with a retry banner the owner never asked for.
     */
    private suspend fun refreshEvents(announceLoading: Boolean = true) {
        // Claim the revision before reading it: a read that fails must leave the retry
        // banner standing rather than re-reading the same unchanged history in a loop.
        loadedEventsRevision.set(incidents.revision.value)
        if (announceLoading) {
            presentation.update { it.copy(eventsLoading = true, eventsError = null) }
        }
        try {
            val records = withContext(dispatcher) { incidents.listNewestFirst() }
            presentation.update {
                it.copy(incidents = records, eventsLoading = false, eventsError = null)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Throwable) {
            if (!announceLoading) return
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

    /**
     * @param rejectionFallback what to say when a refusal carries no typed reason. The caller
     *   knows which command it issued; this used to be guessed by searching the refusal's
     *   English text for the word "Arm", so an arm blocked by anything else — a missing
     *   permission, an uncalibrated profile — announced itself as "คำสั่งไม่สำเร็จ".
     */
    private fun publishResult(
        result: ProtectionCommandResult,
        rejectionFallback: GuidanceCode = GuidanceCode.COMMAND_UNKNOWN,
    ) {
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
                // A device-support refusal says something the owner can act on, and it is the
                // only refusal that arrives typed. Everything below is still string matching.
                result.unsupported?.let { support ->
                    publishMessage(
                        UserGuidanceCatalog.content(
                            GuidanceCode.PROFILE_UNSUPPORTED,
                            GuidanceDetail.ProfileSupportValue(support),
                        ),
                    )
                    return
                }
                // A second press while arming is already under way is not news.
                if (result.reason.equals("Arming already in progress", ignoreCase = true)) return
                UserGuidanceCatalog.content(rejectionFallback)
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

        /** Matches the arm path's default when no angle has been stored yet. */
        const val DEFAULT_ENTRY_ALERT_ANGLE_DEG = 15

        /**
         * An on-change light sensor reports its current value as soon as it is
         * registered, so silence this long means the source is not really there.
         */
        const val POWER_COMMISSIONING_FIRST_SAMPLE_TIMEOUT_MS = 5_000L

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
