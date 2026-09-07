package com.example.motorcycleantitheftsensor.protection

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

@JvmInline
value class RecoveryGenerationToken(val value: Long)

/** Degradation reason recorded when the per-arm Power witness challenge was skipped. */
internal const val POWER_CHALLENGE_DEGRADED = "Power witness placement not revalidated"

class ProtectionCoordinator(
    initialSnapshot: ProtectionSnapshot,
    private val runtime: ProtectionRuntime,
    private val armingDelay: ArmingDelay,
    private val clock: ProtectionClock,
    private val healthPolicy: ProtectionHealthPolicy = ProtectionHealthPolicy(),
    private val incidentCloser: suspend (String) -> Boolean = { true },
    private val durableSnapshotWriter: suspend (ProtectionSnapshot) -> Unit = { },
    private val sensorRepository: SensorConfigurationRepository? = null,
    private val profileRepository: ProtectionProfileRepository? = null,
    private val profilePolicy: ProtectionProfilePolicy = ProtectionProfilePolicy(),
    private val entryCommissioningContextProvider:
        (() -> EntryCommissioningPolicy.CommissioningContext)? = null,
    private val powerCommissioningContextProvider:
        (() -> PowerWitnessCommissioningPolicy.CommissioningContext)? = null,
    private val powerIntegrityChallenge: (() -> Boolean)? = null,
    private val recoveredPowerIntegrityChallenge: (() -> Boolean?)? = null,
    /**
     * What this device can carry, per profile. Defaults to "anything", so a caller
     * without a sensor catalog behaves exactly as before.
     */
    private val deviceSupport: (ProtectionProfile) -> ProfileDeviceSupport = {
        ProfileDeviceSupport.Supported
    },
    /**
     * What this phone measured about its own orientation drift, as the verdict rather
     * than as the hours derived from it. The door watch's status has to state the same
     * sentence the settings screen states, and a caller with no measurement layer keeps
     * the honest default of having measured nothing.
     */
    private val entryDriftVerdict: () -> EntryDriftVerdict = { EntryDriftVerdict.NotMeasured },
) {
    private val mutableSnapshot = MutableStateFlow(initialSnapshot)
    private val commandMutex = Mutex()
    private val disarmPending = AtomicBoolean(false)
    private val armingEpoch = AtomicLong(0L)
    private val incidentEpoch = AtomicLong(0L)
    private val recoveryGeneration = AtomicLong(0L)
    private val currentArmedSessionId = AtomicReference<String?>(null)
    private val armedSignalRoles = AtomicReference<Map<SensorKind, SensorRole>>(emptyMap())

    /**
     * The door watch's level for the running session, or null when the door watch is not the
     * armed use. The engine needs it to type what it opens: the same coincidence of sound and
     * movement is a blow to a vehicle and an opening at a door, and only the caller knows
     * which one is being watched.
     */
    private val armedEntryLevel = AtomicReference<EntryWatchLevel?>(null)
    @Volatile private var lastServiceHeartbeatAtMs: Long? = null
    @Volatile private var stateBeforeAlert: ProtectionState? = null
    @Volatile private var stateBeforeOffline: ProtectionState? = null
    @Volatile private var baseDegradationReasons: Set<String> = initialSnapshot.degradationReasons
    private val unavailablePersistence = AtomicReference<Set<PersistenceSource>>(emptySet())
    private val runtimeDegradations = AtomicReference<Set<String>>(emptySet())

    val snapshot: StateFlow<ProtectionSnapshot> = mutableSnapshot.asStateFlow()
    val audioTelemetry: StateFlow<AudioTelemetry> = runtime.audioTelemetry

    init {
        // The report must be able to name the mode from the first moment, not only after
        // the first command. A phone that boots into a status question has had no
        // transition yet.
        refreshModeContext()
    }

    fun currentArmedSessionId(): String? = currentArmedSessionId.get()

    /**
     * Re-reads the durable profile state into [ProtectionSnapshot.modeContext].
     *
     * Called on every transition and after every path that can change the selection or a
     * profile's settings. It is deliberately not on the five-second freshness tick: none
     * of these facts change without an owner action, and re-decoding the store that often
     * would spend battery to learn nothing.
     */
    fun refreshModeContext() {
        val context = buildModeContext() ?: return
        if (context == snapshot.value.modeContext) return
        updateSnapshot { current -> current.copy(modeContext = context) }
    }

    /**
     * @return null when there is nothing to say — no profile layer at all, or a store that
     *   could not be read. Null leaves whatever the snapshot already carried: a failed read
     *   is not evidence that the owner deselected their mode, and reporting it as such would
     *   tell them nothing is being watched while it is.
     */
    private fun buildModeContext(): ProtectionModeContext? {
        val repository = profileRepository ?: return null
        return try {
            val state = repository.load()
            val selected = state.selectedProfile
                ?: return ProtectionModeContext(
                    selectedProfile = null,
                    switchingTo = state.switchTransaction?.targetProfile,
                )
            modeContextFrom(state, selected)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The same durable facts, for a mode that is not the one the owner has selected.
     *
     * `/status ประตู` asked while the vehicle watch is armed has to answer about the door:
     * whether its hinge is still calibrated, what angle it would alert on, whether this
     * phone can carry it at all. None of that is in [ProtectionSnapshot], which speaks for
     * the selected mode only, and none of it changes with the state of the running watch.
     *
     * The returned context describes that mode; it never claims that mode is running.
     * Saying so is the caller's job, and the report built from this must open by saying
     * the mode is not the one watching.
     *
     * @return null when there is no profile layer, or the store could not be read.
     */
    fun modeContextFor(profile: ProtectionProfile): ProtectionModeContext? {
        val repository = profileRepository ?: return null
        return try {
            modeContextFrom(repository.load(), profile)
        } catch (_: Exception) {
            null
        }
    }

    private fun modeContextFrom(
        state: ProtectionProfileStoreState,
        selected: ProtectionProfile,
    ): ProtectionModeContext {
        val resolved = profilePolicy.resolve(state, selected)
        val stored = state.profiles.getValue(selected)
        val entrySettings = resolved.specificSettings as? EntryProfileSettings
        return ProtectionModeContext(
            selectedProfile = selected,
            entryLevel = entrySettings?.level,
            setupState = resolved.setupState,
            support = deviceSupport(selected),
            switchingTo = state.switchTransaction?.targetProfile,
            modeFacts = when (selected) {
                ProtectionProfile.VEHICLE -> VehicleModeFacts
                ProtectionProfile.ENTRY -> {
                    val settings = entrySettings ?: EntryProfileSettings()
                    val model = stored.entryHingeModel
                    EntryModeFacts(
                        angleThresholdDegrees = settings.angleThresholdDegrees,
                        openConfirmationMs = settings.openConfirmationMs,
                        closeThresholdDegrees = settings.closeThresholdDegrees,
                        closeConfirmationMs = settings.closeConfirmationMs,
                        hingeModelCommissioned = model != null,
                        hingeOrientationSourceLabel = model?.orientationSourcePolicy,
                        hingeCommissionedAtWallMs = model?.commissionedAtWallMs,
                        // A property of this phone, not of any one session, so it is the
                        // same answer whether or not the door watch is the one running.
                        driftVerdict = entryDriftVerdict(),
                    )
                }
                ProtectionProfile.POWER -> {
                    val settings = resolved.specificSettings as? PowerProfileSettings
                        ?: PowerProfileSettings()
                    val model = stored.powerWitnessModel
                    PowerModeFacts(
                        lossConfirmationMs = settings.lossConfirmationMs,
                        recoveryConfirmationMs = settings.recoveryConfirmationMs,
                        witnessCommissioned = model != null,
                        witnessDarkThresholdLux = model?.witnessDarkThresholdLux,
                        witnessLitThresholdLux = model?.witnessLitThresholdLux,
                        witnessCommissionedAtWallMs = model?.commissionedAtWallMs,
                    )
                }
            },
        )
    }


    suspend fun arm(
        commandId: String,
        origin: CommandOrigin,
        recoveryToken: RecoveryGenerationToken? = null,
    ): ProtectionCommandResult {
        if (origin != CommandOrigin.RECOVERY) invalidateRecovery()
        if (disarmPending.get()) return result(commandId, CommandOutcome.REJECTED, "Disarm in progress")
        var epoch = -1L
        var armingDegradations = emptySet<String>()
        val frozenSnapshotRef = AtomicReference<ArmedProfileSnapshot?>(null)
        val immediateResult = commandMutex.withLock {
            if (!recoveryIsCurrent(origin, recoveryToken)) {
                return@withLock result(
                    commandId,
                    CommandOutcome.REJECTED,
                    "Recovery superseded by owner command",
                )
            }
            if (disarmPending.get()) {
                return@withLock result(commandId, CommandOutcome.REJECTED, "Disarm in progress")
            }
            if (snapshot.value.state == ProtectionState.ARMING) {
                return@withLock result(commandId, CommandOutcome.REJECTED, "Arming already in progress")
            }
            incidentEpoch.incrementAndGet()
            val readiness = runtime.readiness()
            if (readiness.blockers.isNotEmpty()) {
                currentArmedSessionId.set(null)
                armingEpoch.incrementAndGet()
                runtime.stopDetectors()
                transition(
                    state = ProtectionState.SETUP_REQUIRED,
                    blockers = readiness.blockers,
                    degradations = readiness.degradations,
                )
                return@withLock result(
                    commandId = commandId,
                    outcome = CommandOutcome.REJECTED,
                    reason = "Missing required: ${readiness.blockers.sorted().joinToString()}",
                )
            }

            val sensorConfig = runtime.effectiveSensorConfiguration()
            val eligibility = SensorConfigurationPolicy().armEligibility(sensorConfig)
            if (eligibility != SensorArmEligibility.Eligible) {
                currentArmedSessionId.set(null)
                armingEpoch.incrementAndGet()
                runtime.stopDetectors()
                transition(
                    state = ProtectionState.SETUP_REQUIRED,
                    blockers = setOf("No primary sensor configured"),
                    degradations = readiness.degradations,
                    setupBlocker = SetupBlocker.NO_PRIMARY_SENSOR,
                )
                return@withLock result(
                    commandId = commandId,
                    outcome = CommandOutcome.REJECTED,
                    reason = "No primary sensor configured",
                )
            }

            armingDegradations = readiness.degradations
            epoch = armingEpoch.incrementAndGet()
            transition(
                state = ProtectionState.ARMING,
                blockers = emptySet(),
                degradations = readiness.degradations,
            )

            // Freeze the selected profile once, under the command mutex, before any
            // detector starts. A legacy customer with no selected profile keeps the
            // previous mutable behavior and arms without an armed-profile snapshot.
            val profileState = profileRepository?.load()
            val selectedProfile = profileState?.selectedProfile
            var frozenConfiguration: SensorFusionConfiguration? = null
            if (profileState != null && selectedProfile != null) {
                // Second gate. A profile can become unsupported after it was chosen —
                // restored settings, a replaced device — and arming into a use nothing
                // can detect is the failure that looks exactly like protection.
                val support = deviceSupport(selectedProfile)
                if (support is ProfileDeviceSupport.Unsupported) {
                    currentArmedSessionId.set(null)
                    armingEpoch.incrementAndGet()
                    runtime.stopDetectors()
                    transition(
                        state = ProtectionState.SETUP_REQUIRED,
                        blockers = setOf("Device cannot support the selected profile"),
                        degradations = readiness.degradations,
                        setupBlocker = SetupBlocker.PROFILE_UNSUPPORTED,
                    )
                    return@withLock result(
                        commandId,
                        CommandOutcome.REJECTED,
                        "Device cannot support $selectedProfile: ${support.reason}",
                        unsupported = support,
                    )
                }
                val resolved = profilePolicy.resolve(profileState, selectedProfile)
                if (resolved.setupState != ProfileSetupState.READY) {
                    currentArmedSessionId.set(null)
                    armingEpoch.incrementAndGet()
                    runtime.stopDetectors()
                    transition(
                        state = ProtectionState.SETUP_REQUIRED,
                        blockers = setOf("Selected profile setup required"),
                        degradations = readiness.degradations,
                        setupBlocker = SetupBlocker.PROFILE_SETUP_REQUIRED,
                    )
                    return@withLock result(
                        commandId,
                        CommandOutcome.REJECTED,
                        "Selected profile is not ready: setup required",
                    )
                }

                val entryLevel = (resolved.specificSettings as? EntryProfileSettings)?.level
                    ?: EntryWatchLevel.DOOR_ANGLE
                // Entry Guard: Arm requires a commissioned hinge model whose fingerprint
                // still matches the current commissioning context (spec sections 5-6).
                // Only the angle level does: the sound-and-movement level measures no angle,
                // so a model it will never read must not be the thing that refuses the arm.
                var entryHingeModel: EntryHingeModel? = null
                if (selectedProfile == ProtectionProfile.ENTRY && entryLevel == EntryWatchLevel.DOOR_ANGLE) {
                    val storedModel = profileState.profiles.getValue(ProtectionProfile.ENTRY).entryHingeModel
                    if (storedModel == null) {
                        currentArmedSessionId.set(null)
                        armingEpoch.incrementAndGet()
                        runtime.stopDetectors()
                        transition(
                            state = ProtectionState.SETUP_REQUIRED,
                            blockers = setOf("Selected profile setup required"),
                            degradations = readiness.degradations,
                            setupBlocker = SetupBlocker.PROFILE_SETUP_REQUIRED,
                        )
                        return@withLock result(
                            commandId,
                            CommandOutcome.REJECTED,
                            "Selected profile is not ready: setup required",
                        )
                    }
                    val currentContext = entryCommissioningContextProvider?.invoke()
                    if (currentContext != null &&
                        EntryCommissioningPolicy.requiresRecommission(
                            storedModel.toCommissioningContext(resolved.specificSettings),
                            currentContext,
                        )
                    ) {
                        // Fingerprint invalidated: decommission durably and refuse to arm.
                        profileRepository.update { profilePolicy.decommissionEntry(it) }
                        currentArmedSessionId.set(null)
                        armingEpoch.incrementAndGet()
                        runtime.stopDetectors()
                        transition(
                            state = ProtectionState.SETUP_REQUIRED,
                            blockers = setOf("Entry commissioning invalidated"),
                            degradations = readiness.degradations,
                            setupBlocker = SetupBlocker.RECOMMISSION_REQUIRED,
                        )
                        return@withLock result(
                            commandId,
                            CommandOutcome.REJECTED,
                            "Entry commissioning invalidated; recommission required",
                        )
                    }
                    entryHingeModel = storedModel
                }

                // Power Guard: Arm requires a commissioned witness model whose fingerprint
                // still matches the current commissioning context (spec section 4.3).
                var powerWitnessModel: PowerWitnessModel? = null
                var powerChallengePassed: Boolean? = null
                if (selectedProfile == ProtectionProfile.POWER) {
                    val storedModel = profileState.profiles.getValue(ProtectionProfile.POWER).powerWitnessModel
                    if (storedModel == null) {
                        currentArmedSessionId.set(null)
                        armingEpoch.incrementAndGet()
                        runtime.stopDetectors()
                        transition(
                            state = ProtectionState.SETUP_REQUIRED,
                            blockers = setOf("Selected profile setup required"),
                            degradations = readiness.degradations,
                            setupBlocker = SetupBlocker.PROFILE_SETUP_REQUIRED,
                        )
                        return@withLock result(
                            commandId,
                            CommandOutcome.REJECTED,
                            "Selected profile is not ready: setup required",
                        )
                    }
                    val currentContext = powerCommissioningContextProvider?.invoke()
                    if (currentContext != null &&
                        PowerWitnessCommissioningPolicy.requiresRecommission(
                            PowerWitnessCommissioningPolicy.storedContextOf(storedModel),
                            currentContext,
                        )
                    ) {
                        // Fingerprint invalidated: decommission durably and refuse to arm.
                        profileRepository.update { profilePolicy.decommissionPower(it) }
                        currentArmedSessionId.set(null)
                        armingEpoch.incrementAndGet()
                        runtime.stopDetectors()
                        transition(
                            state = ProtectionState.SETUP_REQUIRED,
                            blockers = setOf("Power commissioning invalidated"),
                            degradations = readiness.degradations,
                            setupBlocker = SetupBlocker.RECOMMISSION_REQUIRED,
                        )
                        return@withLock result(
                            commandId,
                            CommandOutcome.REJECTED,
                            "Power commissioning invalidated; recommission required",
                        )
                    }
                    powerWitnessModel = storedModel
                    // Full Healthy readiness requires the per-arm lamp off/on integrity
                    // challenge; skipping arms degraded without any incident.
                    val challengePassed = if (origin == CommandOrigin.RECOVERY) {
                        recoveredPowerIntegrityChallenge?.invoke() ?: false
                    } else {
                        powerIntegrityChallenge?.invoke() ?: false
                    }
                    powerChallengePassed = challengePassed
                    if (!challengePassed) {
                        armingDegradations = armingDegradations + setOf(POWER_CHALLENGE_DEGRADED)
                    }
                }

                val sessionId = UUID.randomUUID().toString()
                val modelFingerprint = when {
                    entryHingeModel != null -> EntryCommissioningPolicy.fingerprint(entryHingeModel)
                    powerWitnessModel != null -> PowerWitnessCommissioningPolicy.fingerprint(powerWitnessModel)
                    else -> null
                }
                val calibrationSnapshot: ArmedCalibrationSnapshot = when {
                    entryHingeModel != null -> EntryArmedCalibrationSnapshot(
                        generation = runtime.currentGenerationId(),
                        modelFingerprint = modelFingerprint!!,
                    )
                    powerWitnessModel != null -> PowerArmedCalibrationSnapshot(
                        generation = runtime.currentGenerationId(),
                        modelFingerprint = modelFingerprint!!,
                        witnessPlacementValidated = powerChallengePassed == true,
                    )
                    else -> VehicleArmedCalibrationSnapshot(generation = runtime.currentGenerationId())
                }
                val armedSnapshot = ArmedProfileSnapshot(
                    armedSessionId = sessionId,
                    profile = selectedProfile,
                    resolvedPresetVersion = resolved.presetVersion,
                    effectiveConfiguration = resolved.sensorConfiguration,
                    configurationFingerprint = ConfigurationFingerprint.sha256(
                        resolved.sensorConfiguration,
                        resolved.specificSettings,
                    ),
                    commissionedModelFingerprint = modelFingerprint,
                    armedCalibrationSnapshot = calibrationSnapshot,
                    entryLevel = entryLevel.takeIf { selectedProfile == ProtectionProfile.ENTRY },
                )
                // Persist the frozen snapshot with owner intent BEFORE detector start.
                try {
                    durableSnapshotWriter(snapshot.value.copy(armedProfileSnapshot = armedSnapshot))
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    currentArmedSessionId.set(null)
                    armingEpoch.incrementAndGet()
                    recordPersistenceFailure(PersistenceSource.SNAPSHOT)
                    transition(
                        state = ProtectionState.DISARMED_ONLINE,
                        blockers = emptySet(),
                        degradations = persistenceDegradations(),
                        baseDegradations = emptySet(),
                    )
                    return@withLock result(
                        commandId,
                        CommandOutcome.REJECTED,
                        "Failed to persist armed profile; protection remains disarmed",
                    )
                }
                frozenSnapshotRef.set(armedSnapshot)
                currentArmedSessionId.set(sessionId)
                frozenConfiguration = armedSnapshot.effectiveConfiguration
                armedSignalRoles.set(ProtectionProfilePolicy.signalRoles(selectedProfile, entryLevel))
                armedEntryLevel.set(entryLevel.takeIf { selectedProfile == ProtectionProfile.ENTRY })
                val startResult = runtime.startDetectors(
                    sessionId,
                    armedSnapshot.effectiveConfiguration,
                    ProtectionProfilePolicy.usedSensorKinds(selectedProfile, entryLevel),
                    ProtectionProfilePolicy.signalRoles(selectedProfile, entryLevel),
                )
                if (!startResult.started) {
                    currentArmedSessionId.set(null)
                    armingEpoch.incrementAndGet()
                    runtime.stopDetectors()
                    // Startup failed after the durable freeze: clear it durably so no
                    // orphaned armed snapshot survives.
                    try {
                        durableSnapshotWriter(snapshot.value.copy(armedProfileSnapshot = null))
                    } catch (_: Exception) {
                        recordPersistenceFailure(PersistenceSource.SNAPSHOT)
                    }
                    val reason = startResult.failureReason ?: "Detector startup failed"
                    transition(
                        state = ProtectionState.DISARMED_ONLINE,
                        blockers = emptySet(),
                        degradations = setOf(reason),
                    )
                    return@withLock result(commandId, CommandOutcome.REJECTED, reason)
                }
                // Entry Guard: freeze the armed-session baseline for the commissioned model.
                entryHingeModel?.let { model ->
                    runtime.beginEntrySession(
                        sessionId,
                        model,
                        resolved.specificSettings as EntryProfileSettings,
                    )
                }
                // Power Guard: freeze the armed-session arbiter for the commissioned model.
                powerWitnessModel?.let { model ->
                    runtime.beginPowerSession(
                        sessionId,
                        model,
                        resolved.specificSettings as PowerProfileSettings,
                    )
                }
            } else {
                val sessionId = UUID.randomUUID().toString()
                currentArmedSessionId.set(sessionId)
                val startResult = runtime.startDetectors(sessionId)
                if (!startResult.started) {
                    currentArmedSessionId.set(null)
                    armingEpoch.incrementAndGet()
                    runtime.stopDetectors()
                    val reason = startResult.failureReason ?: "Detector startup failed"
                    transition(
                        state = ProtectionState.DISARMED_ONLINE,
                        blockers = emptySet(),
                        degradations = setOf(reason),
                    )
                    return@withLock result(commandId, CommandOutcome.REJECTED, reason)
                }
            }
            null
        }
        if (immediateResult != null) return immediateResult

        val sensorConfig = runtime.effectiveSensorConfiguration()
        val configuredPrimarySources = SensorSource.entries.filter { sensorConfig.source(it).role == SensorRole.PRIMARY }
        val requiredPrimarySources = if (configuredPrimarySources.isNotEmpty()) {
            configuredPrimarySources.toSet()
        } else {
            setOf(SensorSource.ACCELEROMETER)
        }

        try {
            armingDelay.await()
            if (!hasReadyPrimary(requiredPrimarySources)) {
                withTimeoutOrNull(PRIMARY_READINESS_GRACE_MS) {
                    while (!hasReadyPrimary(requiredPrimarySources)) {
                        delay(PRIMARY_READINESS_POLL_MS)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            commandMutex.withLock {
                if (snapshot.value.state == ProtectionState.ARMING && epoch == armingEpoch.get()) {
                    currentArmedSessionId.set(null)
                    runtime.stopDetectors()
                    runtime.clearEntryBaseline()
                    runtime.clearPowerSession()
                    clearFrozenArmedSnapshotDurably(frozenSnapshotRef.get())
                    transition(
                        state = ProtectionState.DISARMED_ONLINE,
                        blockers = emptySet(),
                        degradations = persistenceDegradations(),
                        baseDegradations = emptySet(),
                    )
                }
            }
            throw cancelled
        }
        return commandMutex.withLock {
            if (!recoveryIsCurrent(origin, recoveryToken)) {
                currentArmedSessionId.set(null)
                runtime.stopDetectors()
                runtime.clearEntryBaseline()
                runtime.clearPowerSession()
                clearFrozenArmedSnapshotDurably(frozenSnapshotRef.get())
                if (snapshot.value.state == ProtectionState.ARMING) {
                    transition(
                        state = ProtectionState.DISARMED_ONLINE,
                        blockers = emptySet(),
                        degradations = persistenceDegradations(),
                        baseDegradations = emptySet(),
                    )
                }
                return@withLock result(commandId, CommandOutcome.UNKNOWN, "Arming was cancelled")
            }
            if (epoch != armingEpoch.get() || snapshot.value.state != ProtectionState.ARMING) {
                currentArmedSessionId.set(null)
                runtime.stopDetectors()
                runtime.clearEntryBaseline()
                runtime.clearPowerSession()
                clearFrozenArmedSnapshotDurably(frozenSnapshotRef.get())
                return@withLock result(commandId, CommandOutcome.UNKNOWN, "Arming was cancelled")
            }

            val health = runtime.currentSensorHealth()

            val hasReadyPrimary = hasReadyPrimary(requiredPrimarySources)

            if (!hasReadyPrimary) {
                currentArmedSessionId.set(null)
                runtime.stopDetectors()
                runtime.clearEntryBaseline()
                runtime.clearPowerSession()
                clearFrozenArmedSnapshotDurably(frozenSnapshotRef.get())
                transition(
                    state = ProtectionState.DISARMED_ONLINE,
                    blockers = emptySet(),
                    degradations = setOf("Primary sensors not available or calibrated"),
                    baseDegradations = emptySet(),
                )
                return@withLock result(commandId, CommandOutcome.REJECTED, "Arming rejected: primary sensors not available or calibrated")
            }

            val finalDegradations = armingDegradations +
                unhealthySensorReasons(
                    health = health,
                    usedSensorKinds = frozenSnapshotRef.get()?.usedSensorKinds()
                        ?: SensorKind.entries.toSet(),
                ) +
                telegramDegradationReasons(snapshot.value) +
                persistenceDegradations()
            val finalState = if (finalDegradations.isEmpty()) {
                ProtectionState.ARMED_HEALTHY
            } else {
                ProtectionState.ARMED_DEGRADED
            }
            transition(
                state = finalState,
                blockers = emptySet(),
                degradations = finalDegradations,
                sensorHealth = health,
                baseDegradations = armingDegradations,
            )
            // Publish the frozen armed snapshot with the final armed state so the
            // durable write below carries the authoritative frozen policy.
            frozenSnapshotRef.get()?.let { frozen ->
                updateSnapshot { current -> current.copy(armedProfileSnapshot = frozen) }
            }
            try {
                durableSnapshotWriter(snapshot.value)
                result(commandId, CommandOutcome.APPLIED, "Protection active")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                recordPersistenceFailure(PersistenceSource.SNAPSHOT)
                result(
                    commandId,
                    CommandOutcome.UNKNOWN,
                    "Protection armed, but durable state could not be confirmed",
                )
            }
        }
    }

    suspend fun disarm(
        commandId: String,
        origin: CommandOrigin,
        recoveryToken: RecoveryGenerationToken? = null,
    ): ProtectionCommandResult {
        val explicitOwnerCommand = origin != CommandOrigin.RECOVERY
        if (explicitOwnerCommand) {
            invalidateRecovery()
            disarmPending.set(true)
            armingEpoch.incrementAndGet()
        }
        return try {
            commandMutex.withLock {
                if (!recoveryIsCurrent(origin, recoveryToken)) {
                    return@withLock result(
                        commandId,
                        CommandOutcome.REJECTED,
                        "Recovery superseded by owner command",
                    )
                }
                if (!explicitOwnerCommand) armingEpoch.incrementAndGet()
                incidentEpoch.incrementAndGet()
                currentArmedSessionId.set(null)
                runtime.stopDetectors()
                runtime.clearEntryBaseline()
                runtime.clearPowerSession()
                val incidentHistoryPersisted = incidentCloser(IncidentCloseReason.OWNER_DISARMED)
                if (!incidentHistoryPersisted) {
                    recordPersistenceFailure(PersistenceSource.INCIDENT_HISTORY)
                }
                stateBeforeAlert = null
                stateBeforeOffline = null
                transition(
                    state = ProtectionState.DISARMED_ONLINE,
                    blockers = emptySet(),
                    degradations = persistenceDegradations(),
                    baseDegradations = emptySet(),
                )
                updateSnapshot { current -> current.copy(armedProfileSnapshot = null) }
                try {
                    durableSnapshotWriter(snapshot.value)
                    if (incidentHistoryPersisted) {
                        result(commandId, CommandOutcome.APPLIED, "Protection disarmed; remote control remains online")
                    } else {
                        result(
                            commandId,
                            CommandOutcome.UNKNOWN,
                            "Protection stopped, but incident history could not be confirmed",
                        )
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    recordPersistenceFailure(PersistenceSource.SNAPSHOT)
                    result(
                        commandId,
                        CommandOutcome.UNKNOWN,
                        "Protection stopped, but durable disarm could not be confirmed",
                    )
                }
            }
        } finally {
            if (explicitOwnerCommand) disarmPending.set(false)
        }
    }

    fun captureRecoveryToken(): RecoveryGenerationToken =
        RecoveryGenerationToken(recoveryGeneration.get())

    fun invalidateRecovery() {
        recoveryGeneration.incrementAndGet()
    }

    suspend fun changeSensitivity(
        commandId: String,
        level: Int,
    ): ProtectionCommandResult = commandMutex.withLock {
        if (level !in 1..10) {
            return@withLock result(commandId, CommandOutcome.REJECTED, "Sensitivity must be 1-10")
        }
        val currentConfig = snapshot.value.sensorFusionConfiguration ?: SensorConfigurationPolicy().forPreset(SensorPreset.BALANCED)
        val updatedConfig = SensorConfigurationPolicy().withGroupSensitivity(currentConfig, SensorCapability.MOVEMENT, level)
        val saveResult = sensorRepository?.saveConfiguration(updatedConfig)
        if (saveResult != null && saveResult.isFailure) {
            recordPersistenceFailure(PersistenceSource.SNAPSHOT)
            val errMessage = saveResult.exceptionOrNull()?.message ?: "Configuration persistence failed"
            return@withLock result(commandId, CommandOutcome.REJECTED, "Failed to persist sensitivity configuration: $errMessage")
        }
        runtime.applySensitivity(level)
        if (saveResult != null && saveResult.isSuccess) {
            recordPersistenceRecovered(PersistenceSource.SNAPSHOT)
        }
        updateSnapshot { current ->
            current.copy(
                sensitivityLevel = level,
                sensorFusionConfiguration = updatedConfig,
            )
        }
        result(commandId, CommandOutcome.APPLIED, "Sensitivity applied: $level")
    }

    /**
     * Selects the profile the owner is preparing. While disarmed the resolved sensor
     * configuration is applied to the editable runtime; while armed nothing running is
     * touched and the selection takes effect at the next Arm.
     */
    suspend fun selectProfile(
        commandId: String,
        profile: ProtectionProfile,
    ): ProtectionCommandResult = commandMutex.withLock {
        val repository = profileRepository
            ?: return@withLock result(commandId, CommandOutcome.REJECTED, "Profiles are not available")
        val support = deviceSupport(profile)
        if (support is ProfileDeviceSupport.Unsupported) {
            // Persisting it would let the next Arm report "protecting" for a use this
            // hardware cannot detect at all.
            return@withLock result(
                commandId,
                CommandOutcome.REJECTED,
                "Device cannot support $profile: ${support.reason}",
                unsupported = support,
            )
        }
        val updateResult = repository.update { state ->
            profilePolicy.updateProfile(state, state.profiles.getValue(profile))
                .copy(selectedProfile = profile)
        }
        if (updateResult.isFailure) {
            return@withLock result(
                commandId,
                CommandOutcome.REJECTED,
                "Failed to persist profile selection",
            )
        }
        val resolved = profilePolicy.resolve(updateResult.getOrThrow(), profile)
        if (!isArmedOrArming()) {
            runtime.applySensorConfiguration(resolved.sensorConfiguration)
            updateSnapshot { current ->
                current.copy(sensorFusionConfiguration = resolved.sensorConfiguration)
            }
            refreshModeContext()
            result(commandId, CommandOutcome.APPLIED, "Profile selected: $profile")
        } else {
            refreshModeContext()
            result(commandId, CommandOutcome.APPLIED, "Profile saved for next Arm")
        }
    }

    /**
     * Persists an edit to the currently selected profile only. While armed this never
     * calls [ProtectionRuntime.applySensorConfiguration]; the frozen armed snapshot and
     * the running detectors stay untouched until the next controlled Arm.
     */
    suspend fun updateSelectedProfile(
        commandId: String,
        transform: (ProtectionProfileStoreState) -> StoredProfileConfiguration,
    ): ProtectionCommandResult = commandMutex.withLock {
        val repository = profileRepository
            ?: return@withLock result(commandId, CommandOutcome.REJECTED, "Profiles are not available")
        val currentState = repository.load()
        val selected = currentState.selectedProfile
            ?: return@withLock result(commandId, CommandOutcome.REJECTED, "No profile is selected")
        val updated = transform(currentState)
        if (updated.profile != selected) {
            return@withLock result(
                commandId,
                CommandOutcome.REJECTED,
                "Edited profile does not match the selected profile",
            )
        }
        val updateResult = repository.update { state -> profilePolicy.updateProfile(state, updated) }
        if (updateResult.isFailure) {
            return@withLock result(
                commandId,
                CommandOutcome.REJECTED,
                "Failed to persist profile settings",
            )
        }
        val resolved = profilePolicy.resolve(updateResult.getOrThrow(), selected)
        refreshModeContext()
        if (!isArmedOrArming()) {
            runtime.applySensorConfiguration(resolved.sensorConfiguration)
            updateSnapshot { current ->
                current.copy(sensorFusionConfiguration = resolved.sensorConfiguration)
            }
            result(commandId, CommandOutcome.APPLIED, "Profile settings applied")
        } else {
            result(commandId, CommandOutcome.APPLIED, "Profile settings saved for next Arm")
        }
    }

    /**
     * Confirmed profile change. While armed, STOP_REQUESTED is persisted before any
     * runtime effect; every later phase converges to disarmed/selected-target even after
     * a crash, and the target profile is never auto-armed.
     */
    suspend fun changeProfile(
        commandId: String,
        targetProfile: ProtectionProfile,
        confirmed: Boolean,
    ): ProtectionCommandResult = commandMutex.withLock {
        val repository = profileRepository
            ?: return@withLock result(commandId, CommandOutcome.REJECTED, "Profiles are not available")
        if (!confirmed) {
            return@withLock result(
                commandId,
                CommandOutcome.REJECTED,
                "Profile change requires explicit confirmation",
            )
        }
        val currentState = repository.load()
        if (currentState.selectedProfile == targetProfile && currentState.switchTransaction == null) {
            return@withLock result(commandId, CommandOutcome.APPLIED, "Profile already selected: $targetProfile")
        }

        val transaction = ProfileSwitchTransaction(
            transactionId = UUID.randomUUID().toString(),
            oldArmedSessionId = currentArmedSessionId.get(),
            targetProfile = targetProfile,
            phase = ProfileSwitchPhase.STOP_REQUESTED,
        )
        // Phase 1: durable owner stop intent BEFORE invalidating recovery or stopping detectors.
        val stopPersisted = repository.update { it.copy(switchTransaction = transaction) }
        if (stopPersisted.isFailure) {
            return@withLock result(
                commandId,
                CommandOutcome.REJECTED,
                "Failed to persist profile switch intent",
            )
        }

        // Runtime effects only after the durable stop intent exists. Owner Stop always wins.
        invalidateRecovery()
        disarmPending.set(true)
        try {
            armingEpoch.incrementAndGet()
            incidentEpoch.incrementAndGet()
            currentArmedSessionId.set(null)
            runtime.stopDetectors()
            runtime.clearEntryBaseline()
            val incidentHistoryPersisted = incidentCloser(IncidentCloseReason.OWNER_CHANGED_PROFILE)
            if (!incidentHistoryPersisted) {
                recordPersistenceFailure(PersistenceSource.INCIDENT_HISTORY)
            }
            stateBeforeAlert = null
            stateBeforeOffline = null
            transition(
                state = ProtectionState.DISARMED_ONLINE,
                blockers = emptySet(),
                degradations = persistenceDegradations(),
                baseDegradations = emptySet(),
            )
            updateSnapshot { current -> current.copy(armedProfileSnapshot = null) }
            try {
                durableSnapshotWriter(snapshot.value)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                recordPersistenceFailure(PersistenceSource.SNAPSHOT)
            }
        } finally {
            disarmPending.set(false)
        }

        // Phase 2: old runtime quiesced (generation fenced by the epoch bumps above).
        repository.update {
            it.copy(switchTransaction = transaction.copy(phase = ProfileSwitchPhase.OLD_RUNTIME_QUIESCED))
        }

        // Phase 3: armed snapshot cleared durably.
        repository.update {
            it.copy(switchTransaction = transaction.copy(phase = ProfileSwitchPhase.SNAPSHOT_CLEARED))
        }

        // Phase 4: select the target, leave it disarmed, clear the transaction.
        val selected = repository.update { state ->
            profilePolicy.updateProfile(state, state.profiles.getValue(targetProfile))
                .copy(selectedProfile = targetProfile, switchTransaction = null)
        }
        if (selected.isFailure) {
            return@withLock result(
                commandId,
                CommandOutcome.UNKNOWN,
                "Profile switch interrupted; recovery will converge to disarmed with the target selected",
            )
        }
        val resolved = profilePolicy.resolve(selected.getOrThrow(), targetProfile)
        runtime.applySensorConfiguration(resolved.sensorConfiguration)
        updateSnapshot { current ->
            current.copy(sensorFusionConfiguration = resolved.sensorConfiguration)
        }
        refreshModeContext()
        result(commandId, CommandOutcome.APPLIED, "Profile switched to $targetProfile; arm to activate")
    }

    /**
     * Converges an interrupted switch transaction toward disarmed/selected-target.
     * Returns true when a transaction was found and resolved.
     */
    suspend fun resumeProfileSwitchIfNeeded(): Boolean = commandMutex.withLock {
        val repository = profileRepository ?: return@withLock false
        val state = repository.load()
        val transaction = state.switchTransaction ?: return@withLock false

        val recovery = ProfileSwitchPolicy().resume(transaction)
        invalidateRecovery()
        currentArmedSessionId.set(null)
        runtime.stopDetectors()
        runtime.clearEntryBaseline()
        val selected = repository.update { current ->
            profilePolicy.updateProfile(current, current.profiles.getValue(recovery.selectedProfile))
                .copy(selectedProfile = recovery.selectedProfile, switchTransaction = null)
        }
        if (selected.isFailure) {
            // Keep the transaction; a later resume retries the same convergence.
            return@withLock true
        }
        val resolved = profilePolicy.resolve(selected.getOrThrow(), recovery.selectedProfile)
        runtime.applySensorConfiguration(resolved.sensorConfiguration)
        transition(
            state = recovery.protectionState,
            blockers = emptySet(),
            degradations = persistenceDegradations(),
            baseDegradations = emptySet(),
        )
        updateSnapshot { current ->
            current.copy(
                armedProfileSnapshot = null,
                sensorFusionConfiguration = resolved.sensorConfiguration,
            )
        }
        try {
            durableSnapshotWriter(snapshot.value)
        } catch (_: Exception) {
            recordPersistenceFailure(PersistenceSource.SNAPSHOT)
        }
        true
    }

    private fun isArmedOrArming(): Boolean = snapshot.value.state in setOf(
        ProtectionState.ARMING,
        ProtectionState.ARMED_HEALTHY,
        ProtectionState.ARMED_DEGRADED,
        ProtectionState.ALERT_ACTIVE,
    )

    /** Rebuilds the commissioning context recorded inside a stored hinge model. */
    private fun EntryHingeModel.toCommissioningContext(
        settings: ProfileSpecificSettings,
    ): EntryCommissioningPolicy.CommissioningContext {
        val entrySettings = settings as? EntryProfileSettings
        return EntryCommissioningPolicy.CommissioningContext(
            sensorIdentity = sensorIdentity,
            orientationSourcePolicy = orientationSourcePolicy,
            algorithmVersion = algorithmVersion,
            entryUseContinuous = true,
            alertAngleDeg = entrySettings?.angleThresholdDegrees ?: 15,
            openConfirmationMs = entrySettings?.openConfirmationMs ?: 750L,
        )
    }

    /**
     * Best-effort durable clear of a frozen armed snapshot after a failed or cancelled
     * arming attempt so no orphaned armed snapshot survives without a running runtime.
     */
    private suspend fun clearFrozenArmedSnapshotDurably(frozen: ArmedProfileSnapshot?) {
        if (frozen == null) return
        try {
            durableSnapshotWriter(snapshot.value.copy(armedProfileSnapshot = null))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            recordPersistenceFailure(PersistenceSource.SNAPSHOT)
        }
    }

    suspend fun updateSensorConfiguration(
        commandId: String,
        config: SensorFusionConfiguration,
    ): ProtectionCommandResult = commandMutex.withLock {
        val validation = SensorConfigurationPolicy().validateForApply(config)
        if (validation is SensorConfigurationValidation.Invalid) {
            val reason = "Sensor configuration validation failed: ${validation.issues.joinToString { "${it.source ?: it.capability ?: "CONFIG"}: ${it.code}" }}"
            return@withLock result(commandId, CommandOutcome.REJECTED, reason)
        }

        val saveResult = sensorRepository?.saveConfiguration(config)
        if (saveResult != null && saveResult.isFailure) {
            recordPersistenceFailure(PersistenceSource.SNAPSHOT)
            val errMessage = saveResult.exceptionOrNull()?.message ?: "Configuration persistence failed"
            return@withLock result(commandId, CommandOutcome.REJECTED, "Failed to persist sensor configuration: $errMessage")
        }

        val applyResult = runtime.applySensorConfiguration(config)
        if (applyResult.status == com.example.motorcycleantitheftsensor.sensor.SensorConfigurationApplyResult.Status.REJECTED) {
            val reason = "Sensor configuration rejected: ${applyResult.issues.joinToString { "${it.source ?: it.capability ?: "CONFIG"}: ${it.code}" }}"
            return@withLock result(commandId, CommandOutcome.REJECTED, reason)
        }

        if (saveResult != null && saveResult.isSuccess) {
            recordPersistenceRecovered(PersistenceSource.SNAPSHOT)
        }

        val currentGenId = runtime.currentGenerationId()
        val health = runtime.currentSensorHealth()

        val degradedReasons = if (applyResult.status == com.example.motorcycleantitheftsensor.sensor.SensorConfigurationApplyResult.Status.APPLIED_DEGRADED) {
            applyResult.degradedSources.map { "$it not healthy" }.toSet()
        } else {
            emptySet()
        }

        val movementSensitivity = config.capability(SensorCapability.MOVEMENT).sensitivity
        updateSnapshot { current ->
            val updatedDegradations = (current.degradationReasons - applyResult.degradedSources.map { "$it not healthy" }.toSet()) + degradedReasons
            val nextState = when {
                current.state in ARMED_STATES && updatedDegradations.isNotEmpty() -> ProtectionState.ARMED_DEGRADED
                current.state == ProtectionState.ARMED_DEGRADED && updatedDegradations.isEmpty() -> ProtectionState.ARMED_HEALTHY
                else -> current.state
            }
            current.copy(
                state = nextState,
                sensitivityLevel = movementSensitivity,
                sensorFusionConfiguration = config,
                sensorGenerationId = currentGenId,
                sensorHealth = current.sensorHealth + health,
                degradationReasons = updatedDegradations,
            )
        }

        val outcomeReason = if (applyResult.status == com.example.motorcycleantitheftsensor.sensor.SensorConfigurationApplyResult.Status.APPLIED_DEGRADED) {
            "Sensor configuration applied with degraded sources: ${applyResult.degradedSources.joinToString()}"
        } else {
            "Sensor configuration applied"
        }
        result(commandId, CommandOutcome.APPLIED, outcomeReason)
    }

    fun recordServiceHeartbeat(atMs: Long) {
        lastServiceHeartbeatAtMs = atMs
        updateSnapshot { current -> current.copy(serviceRunning = true, lastServiceHeartbeatAtMs = atMs) }
    }

    fun recordPersistenceFailure(source: PersistenceSource) {
        if (updatePersistenceSources { current -> current + source }) {
            refreshPersistenceDegradations()
        }
    }

    fun recordPersistenceRecovered(source: PersistenceSource) {
        if (updatePersistenceSources { current -> current - source }) {
            refreshPersistenceDegradations()
        }
    }

    private fun refreshPersistenceDegradations() {
        val persistenceReasons = persistenceDegradations()
        updateSnapshot { current ->
            val remaining = current.degradationReasons - PERSISTENCE_REASON_LABELS
            val updated = remaining + persistenceReasons
            current.copy(
                state = when {
                    current.state == ProtectionState.ARMED_HEALTHY && updated.isNotEmpty() ->
                        ProtectionState.ARMED_DEGRADED
                    current.state == ProtectionState.ARMED_DEGRADED &&
                        updated.isEmpty() &&
                        current.sensorHealth[SensorKind.VIBRATION]?.state == SensorHealthState.HEALTHY ->
                        ProtectionState.ARMED_HEALTHY
                    else -> current.state
                },
                degradationReasons = updated,
            )
        }
    }

    fun recordLocationForegroundRestriction(restricted: Boolean) {
        val reason = "Background location foreground start restricted"
        var changed = false
        while (true) {
            val current = runtimeDegradations.get()
            val updated = if (restricted) current + reason else current - reason
            if (updated == current) break
            if (runtimeDegradations.compareAndSet(current, updated)) {
                changed = true
                break
            }
        }
        if (changed) {
            refreshRuntimeDegradations()
        }
    }

    private fun refreshRuntimeDegradations() {
        val runtimeReasons = runtimeDegradations.get()
        updateSnapshot { current ->
            val remaining = current.degradationReasons - setOf("Background location foreground start restricted")
            val updated = remaining + runtimeReasons
            current.copy(
                state = when {
                    current.state == ProtectionState.ARMED_HEALTHY && updated.isNotEmpty() ->
                        ProtectionState.ARMED_DEGRADED
                    current.state == ProtectionState.ARMED_DEGRADED &&
                        updated.isEmpty() &&
                        current.sensorHealth[SensorKind.VIBRATION]?.state == SensorHealthState.HEALTHY ->
                        ProtectionState.ARMED_HEALTHY
                    else -> current.state
                },
                degradationReasons = updated,
            )
        }
    }

    fun recordTelegramContact(atMs: Long) {
        updateSnapshot { current ->
            current.copy(
                telegramReachable = true,
                lastTelegramContactAtMs = atMs,
            )
        }
    }

    fun recordTelegramPolling(active: Boolean) {
        updateSnapshot { current ->
            current.copy(
                telegramPolling = active,
                telegramReachable = if (active) current.telegramReachable else false,
            )
        }
    }

    fun recordServiceStopped() {
        armingEpoch.incrementAndGet()
        incidentEpoch.incrementAndGet()
        stateBeforeAlert = null
        stateBeforeOffline = null
        baseDegradationReasons = emptySet()
        updateSnapshot { current ->
            current.copy(
                state = ProtectionState.OFFLINE,
                lastTransitionAtMs = clock.nowMs(),
                serviceRunning = false,
                telegramPolling = false,
                telegramReachable = false,
            )
        }
    }

    fun recordSensorHealth(kind: SensorKind, health: SensorHealth) {
        updateSnapshot { current ->
            val power = health.powerThermalDetail.takeIf { kind == SensorKind.POWER_THERMAL }
            current.copy(
                sensorHealth = current.sensorHealth + (kind to health),
                batteryLevelPercent = power?.batteryLevelPercent ?: current.batteryLevelPercent,
                batteryTemperatureCelsius = power?.temperatureCelsius ?: current.batteryTemperatureCelsius,
                chargingState = if (power != null && power.chargingState != ChargingState.UNKNOWN) power.chargingState else current.chargingState,
            )
        }
    }

    fun recordSensorHealthSnapshot(healthByKind: Map<SensorKind, SensorHealth>) {
        updateSnapshot { current ->
            val power = healthByKind[SensorKind.POWER_THERMAL]?.powerThermalDetail
            current.copy(
                sensorHealth = current.sensorHealth + healthByKind,
                batteryLevelPercent = power?.batteryLevelPercent ?: current.batteryLevelPercent,
                batteryTemperatureCelsius = power?.temperatureCelsius ?: current.batteryTemperatureCelsius,
                chargingState = if (power != null && power.chargingState != ChargingState.UNKNOWN) power.chargingState else current.chargingState,
            )
        }
    }

    fun recordSensorSample(
        kind: SensorKind,
        atMs: Long,
        detail: String? = null,
        normalizedValue: Double? = null,
    ) {
        updateSnapshot { current ->
            val existing = current.sensorHealth[kind]
            val updatedHealth = (existing ?: SensorHealth(SensorHealthState.HEALTHY)).copy(
                state = SensorHealthState.HEALTHY,
                lastSampleAtMs = atMs,
                detail = detail ?: existing?.detail,
                latestReading = createReadingSummary(kind, normalizedValue) ?: existing?.latestReading,
            )
            current.copy(
                sensorHealth = current.sensorHealth + (kind to updatedHealth),
                batteryLevelPercent = if (
                    kind == SensorKind.POWER_THERMAL &&
                    detail == "battery_level_percent" &&
                    normalizedValue?.isFinite() == true
                ) {
                    normalizedValue.roundToInt().takeIf { it in 0..100 }
                        ?: current.batteryLevelPercent
                } else {
                    current.batteryLevelPercent
                },
                batteryTemperatureCelsius = if (
                    kind == SensorKind.POWER_THERMAL &&
                    detail == "temperature_celsius" &&
                    normalizedValue?.isFinite() == true
                ) {
                    normalizedValue.toFloat()
                } else {
                    current.batteryTemperatureCelsius
                },
            )
        }
    }

    fun currentIncidentEpoch(): Long = incidentEpoch.get()

    fun acceptsIncident(epoch: Long): Boolean = epoch == incidentEpoch.get() &&
        snapshot.value.state in ACTIVE_INCIDENT_STATES

    @Synchronized
    fun recordIncident(incident: SecurityIncident) {
        updateSnapshot { current ->
            val canChangeProtectionState = current.state in ARMED_STATES ||
                current.state == ProtectionState.ALERT_ACTIVE
            val nextState = if (!canChangeProtectionState) {
                current.state
            } else when (incident.lifecycle) {
                IncidentLifecycle.OPEN -> {
                    if (current.state != ProtectionState.ALERT_ACTIVE) {
                        stateBeforeAlert = current.state.takeIf { state -> state in ARMED_STATES }
                    }
                    ProtectionState.ALERT_ACTIVE
                }

                IncidentLifecycle.CLOSED,
                IncidentLifecycle.INTERRUPTED,
                -> stateBeforeAlert ?: armedStateFrom(current)
            }
            if (incident.lifecycle != IncidentLifecycle.OPEN || !canChangeProtectionState) {
                stateBeforeAlert = null
            }
            current.copy(
                state = nextState,
                lastTransitionAtMs = clock.nowMs(),
                lastIncident = IncidentSummary(
                    id = incident.id,
                    severity = incident.severity,
                    lifecycle = incident.lifecycle,
                    updatedAtMs = incident.updatedAtMs,
                    deliveryState = incident.deliveryState,
                    type = incident.type,
                ),
                lastDeliveryState = incident.deliveryState,
            )
        }
    }

    fun evaluateFreshness(nowMs: Long) {
        updateSnapshot { current ->
            val evaluatedSensors = current.sensorHealth.mapValues { (kind, health) ->
                health.copy(state = healthPolicy.sensorState(kind, health, nowMs))
            }
            val serviceFresh = healthPolicy.serviceIsFresh(
                lastServiceHeartbeatAtMs = lastServiceHeartbeatAtMs,
                nowMs = nowMs,
            )
            val telegramReachable = healthPolicy.telegramReachable(
                lastContactAtMs = current.lastTelegramContactAtMs,
                nowMs = nowMs,
            )
            val evaluatedChannels = current.copy(telegramReachable = telegramReachable)
            val liveState = if (current.state == ProtectionState.OFFLINE) {
                stateBeforeOffline ?: ProtectionState.DISARMED_ONLINE
            } else {
                current.state
            }
            val channelDegradations = if (liveState in ARMED_STATES) {
                telegramDegradationReasons(evaluatedChannels)
            } else {
                emptySet()
            }
            val sensorDegradations = if (liveState in ARMED_STATES || liveState == ProtectionState.ARMING) {
                unhealthySensorReasons(
                    health = evaluatedSensors,
                    usedSensorKinds = current.armedProfileSnapshot?.usedSensorKinds()
                        ?: SensorKind.entries.toSet(),
                )
            } else {
                emptySet()
            }
            val degradations = baseDegradationReasons +
                sensorDegradations +
                channelDegradations +
                persistenceDegradations() +
                runtimeDegradations.get()
            val evaluatedState = when {
                !serviceFresh -> {
                    if (current.state != ProtectionState.OFFLINE) stateBeforeOffline = current.state
                    ProtectionState.OFFLINE
                }
                liveState == ProtectionState.ALERT_ACTIVE -> ProtectionState.ALERT_ACTIVE
                liveState in ARMED_STATES && degradations.isNotEmpty() -> ProtectionState.ARMED_DEGRADED
                liveState in ARMED_STATES -> ProtectionState.ARMED_HEALTHY
                else -> liveState
            }
            if (serviceFresh) stateBeforeOffline = null

            current.copy(
                state = evaluatedState,
                serviceRunning = serviceFresh,
                telegramReachable = telegramReachable,
                sensorHealth = evaluatedSensors,
                degradationReasons = degradations,
            )
        }
    }

    private fun transition(
        state: ProtectionState,
        blockers: Set<String>,
        degradations: Set<String>,
        sensorHealth: Map<SensorKind, SensorHealth> = snapshot.value.sensorHealth,
        baseDegradations: Set<String> = degradations,
        setupBlocker: SetupBlocker? = null,
    ) {
        baseDegradationReasons = baseDegradations
        val now = clock.nowMs()
        updateSnapshot { current ->
            val nextActivatedAt = when (state) {
                ProtectionState.ARMED_HEALTHY, ProtectionState.ARMED_DEGRADED ->
                    current.protectionActivatedAtMs ?: now
                ProtectionState.ALERT_ACTIVE ->
                    current.protectionActivatedAtMs ?: now
                ProtectionState.ARMING ->
                    current.protectionActivatedAtMs ?: now
                ProtectionState.DISARMED_ONLINE, ProtectionState.SETUP_REQUIRED, ProtectionState.OFFLINE ->
                    null
            }
            current.copy(
                state = state,
                lastTransitionAtMs = now,
                protectionActivatedAtMs = nextActivatedAt,
                permissionBlockers = blockers,
                // Carried only where it means something; a state that is not asking for setup
                // must not keep yesterday's reason for having asked.
                setupBlocker = setupBlocker.takeIf { state == ProtectionState.SETUP_REQUIRED },
                degradationReasons = degradations,
                sensorHealth = sensorHealth,
            )
        }
        refreshModeContext()
    }

    private fun result(
        commandId: String,
        outcome: CommandOutcome,
        reason: String,
        unsupported: ProfileDeviceSupport.Unsupported? = null,
    ): ProtectionCommandResult = ProtectionCommandResult(
        commandId = commandId,
        outcome = outcome,
        resultingState = snapshot.value.state,
        reason = reason,
        unsupported = unsupported,
    )

    private inline fun updateSnapshot(transform: (ProtectionSnapshot) -> ProtectionSnapshot) {
        mutableSnapshot.update { current ->
            transform(current).copy(revision = current.revision + 1L)
        }
    }

    private fun recoveryIsCurrent(
        origin: CommandOrigin,
        token: RecoveryGenerationToken?,
    ): Boolean = origin != CommandOrigin.RECOVERY ||
        (token != null && token.value == recoveryGeneration.get())

    private fun updatePersistenceSources(
        transform: (Set<PersistenceSource>) -> Set<PersistenceSource>,
    ): Boolean {
        while (true) {
            val current = unavailablePersistence.get()
            val updated = transform(current)
            if (updated == current) return false
            if (unavailablePersistence.compareAndSet(current, updated)) return true
        }
    }

    private fun persistenceDegradations(): Set<String> = unavailablePersistence.get()
        .mapTo(mutableSetOf()) { source ->
            when (source) {
                PersistenceSource.SNAPSHOT -> SNAPSHOT_PERSISTENCE_DEGRADATION
                PersistenceSource.INCIDENT_HISTORY -> INCIDENT_PERSISTENCE_DEGRADATION
                PersistenceSource.MOVEMENT_TRACKING -> MOVEMENT_TRACKING_PERSISTENCE_DEGRADATION
            }
        }

    private fun armedStateFrom(snapshot: ProtectionSnapshot): ProtectionState = if (
        snapshot.degradationReasons.isEmpty() &&
        snapshot.sensorHealth[SensorKind.VIBRATION]?.state == SensorHealthState.HEALTHY
    ) {
        ProtectionState.ARMED_HEALTHY
    } else {
        ProtectionState.ARMED_DEGRADED
    }


    /**
     * The role the armed use gave a signal the configuration cannot name.
     *
     * Signals that reach the engine outside the detector set — the confirmed-movement fix
     * is the only one — have to be stamped from the same table, or the use would have one
     * host on paper and another in practice.
     */
    fun currentSignalRole(kind: SensorKind): SensorRole? = armedSignalRoles.get()[kind]

    /** Whether the running session is the door watch listening for sound and movement. */
    fun soundAndMovementDoorWatchArmed(): Boolean =
        armedEntryLevel.get() == EntryWatchLevel.SOUND_AND_MOVEMENT

    /**
     * Whether the running session is the door watch measuring an angle. At this level the
     * orientation verdict from the dedicated Entry listener is the only host; a raw movement
     * sample reaching the engine is corroboration, never an alarm of its own. The engine needs
     * to be told, because the source role stamped upstream still reads PRIMARY for the
     * orientation sensors the general detector set also samples.
     */
    fun doorAngleWatchArmed(): Boolean =
        armedEntryLevel.get() == EntryWatchLevel.DOOR_ANGLE

    /**
     * Whether this armed session has a movement signal that could vouch for a door verdict.
     *
     * The door watch reads an angle, and an angle moves on its own: the orientation a still
     * phone reports drifts, the baseline is frozen for the whole session, and a session that
     * lasts a working day gives the drift all day to reach a threshold meant for a door. So a
     * door claim is asked whether anything shook — but only where the question can be
     * answered. A use that runs no movement signal, or a phone with no movement sensor,
     * answers false here and is never asked, because a corroboration that cannot arrive would
     * silence the watch entirely rather than sharpen it.
     */
    fun movementCorroborationArmed(): Boolean {
        val role = armedSignalRoles.get()[SensorKind.VIBRATION] ?: return false
        if (role == SensorRole.OFF) return false
        return runtime.sourceHealth(SensorSource.ACCELEROMETER) != SensorHealthState.UNAVAILABLE
    }

    private fun hasReadyPrimary(primarySources: Set<SensorSource>): Boolean =
        primarySources.any { source -> runtime.sourceHealth(source) == SensorHealthState.HEALTHY }

    private fun telegramDegradationReasons(snapshot: ProtectionSnapshot): Set<String> = buildSet {
        if (!snapshot.telegramPolling) add("TELEGRAM polling inactive")
        if (!snapshot.telegramReachable) add("TELEGRAM unreachable")
    }

    private fun createReadingSummary(kind: SensorKind, value: Double?): SensorReadingSummary? = when (kind) {
        SensorKind.VIBRATION -> SensorReadingSummary(value, "m/s²", "Acceleration")
        SensorKind.LIGHT -> SensorReadingSummary(value, "lux", "Ambient Light")
        SensorKind.POWER_THERMAL -> SensorReadingSummary(null, null, "Power Status")
        SensorKind.MICROPHONE -> SensorReadingSummary(null, null, "Signal Received")
        SensorKind.LOCATION -> SensorReadingSummary(null, null, "Fix Acquired")
    }

    private companion object {
        val ARMED_STATES = setOf(
            ProtectionState.ARMED_HEALTHY,
            ProtectionState.ARMED_DEGRADED,
        )
        val ACTIVE_INCIDENT_STATES = ARMED_STATES + ProtectionState.ALERT_ACTIVE
        const val SNAPSHOT_PERSISTENCE_DEGRADATION = "SNAPSHOT persistence unavailable"
        const val INCIDENT_PERSISTENCE_DEGRADATION = "INCIDENT history unavailable"
        const val MOVEMENT_TRACKING_PERSISTENCE_DEGRADATION = "Movement tracking persistence unavailable"
        const val PRIMARY_READINESS_GRACE_MS = 1_000L
        const val PRIMARY_READINESS_POLL_MS = 50L
        val PERSISTENCE_REASON_LABELS = setOf(
            SNAPSHOT_PERSISTENCE_DEGRADATION,
            INCIDENT_PERSISTENCE_DEGRADATION,
            MOVEMENT_TRACKING_PERSISTENCE_DEGRADATION,
        )
    }
}

/**
 * A sensor the armed profile does not detect with must never degrade the armed state.
 * Power Guard deliberately runs on the light sensor and the charging signal alone, so
 * counting the microphone, location or movement there produced a permanent
 * "limited" state that also masked degradations that do matter.
 */
internal fun unhealthySensorReasons(
    health: Map<SensorKind, SensorHealth>,
    usedSensorKinds: Set<SensorKind>,
): Set<String> = health
    .filterKeys { kind -> kind in usedSensorKinds }
    .filterValues { item ->
        item.state == SensorHealthState.UNAVAILABLE ||
            item.state == SensorHealthState.STALE ||
            item.state == SensorHealthState.FAILED
    }
    .keys
    .mapTo(mutableSetOf()) { kind -> "$kind not healthy" }

