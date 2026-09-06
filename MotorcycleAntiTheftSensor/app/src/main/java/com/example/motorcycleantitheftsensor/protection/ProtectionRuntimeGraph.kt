package com.example.motorcycleantitheftsensor.protection

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
import com.example.motorcycleantitheftsensor.telegram.TelegramBotClient
import com.example.motorcycleantitheftsensor.telephony.SmsFallbackManager
import com.example.motorcycleantitheftsensor.telephony.SmsSendOutcome
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.example.motorcycleantitheftsensor.location.EncryptedMovementTrackingStore
import com.example.motorcycleantitheftsensor.location.MovementDisplacementPolicy
import com.example.motorcycleantitheftsensor.sensor.LocationObservationProvider
import com.example.motorcycleantitheftsensor.sensor.audio.AudioThreatCandidateBuffer

object ProtectionRuntimeGraph {

    /**
     * The angle this owner set for the door watch, which is what drift has to be measured
     * against: a watch set to thirty degrees tolerates twice the drift of one set to fifteen.
     * Falls back to the commissioning default when the profile has never been resolved.
     */
    private fun entryAlertAngleDeg(profileRepository: ProtectionProfileRepository): Int =
        runCatching {
            val settings = ProtectionProfilePolicy()
                .resolve(profileRepository.load(), ProtectionProfile.ENTRY)
                .specificSettings as? EntryProfileSettings
            settings?.angleThresholdDegrees
        }.getOrNull() ?: DEFAULT_ENTRY_ALERT_ANGLE_DEG

    private const val DEFAULT_ENTRY_ALERT_ANGLE_DEG = 15

    @Volatile
    private var instance: Graph? = null

    fun from(context: Context): Graph = instance ?: synchronized(this) {
        instance ?: buildGraph(context.applicationContext).also { graph -> instance = graph }
    }

    data class Graph(
        val coordinator: ProtectionCoordinator,
        val incidents: IncidentRepository,
        val delivery: IncidentDeliveryCoordinator,
        /**
         * Sends incidents that were recorded but never reached the owner, once something says
         * the path may work again. Held here rather than built by the caller: it reads the same
         * repository and sends through the same coordinator as a first attempt does.
         */
        val incidentRedeliverer: IncidentRedeliverer,
        /**
         * Where the graph's own Telegram traffic reports itself. Attached by the service when
         * the black box opens and detached when it closes, because the graph outlives both.
         */
        val breadcrumbRelay: BreadcrumbRelay,
        val runtime: ProtectionRuntime,
        val snapshotStore: ProtectionSnapshotStore,
        val statePersistence: ProtectionStatePersistenceArbiter,
        val scope: CoroutineScope,
        val livePursuitCoordinator: LivePursuitCoordinator,
        val sensorRepository: SensorConfigurationRepository? = null,
        val profileRepository: ProtectionProfileRepository? = null,
        val powerArmChallenge: PowerArmChallengeRegistry = PowerArmChallengeRegistry(),
        /** Hardware inventory of this device, for the screens that must state it. */
        val sensorCatalog: com.example.motorcycleantitheftsensor.sensor.SensorCatalog? = null,
        /**
         * What this phone measured about its own orientation drift. Shared rather than
         * rebuilt: the graph opens the encrypted preferences, and a second opener of the same
         * file by name would be reading someone else's ciphertext.
         */
        val driftMeasurementStore: EntryDriftMeasurementStore? = null,
        /**
         * Counts sensor samples for the black box's minute rows. Shared rather than rebuilt:
         * it is filled by the one controller that owns the sensor registrations and emptied
         * by the recorder in the service, and a second instance would be filled by nobody.
         */
        val blackBoxSensorTap: BlackBoxSensorTap? = null,
        /** Where the black box's day files are written, and the only handle allowed to copy them. */
        val blackBoxWriter: BlackBoxWriter? = null,
        /** Answers `/where` without taking fixes away from whatever is already tracking. */
        val onDemandLocationFinder: com.example.motorcycleantitheftsensor.location.OnDemandLocationFinder? = null,
        /**
         * Reads the values a status answer needs that are only true at the moment it is
         * asked. Built here because it is the only place holding the armed session, the
         * parking anchor and the encrypted preferences at once.
         */
        val liveStatusReader: com.example.motorcycleantitheftsensor.telegram.LiveStatusReader? = null,
        /**
         * This boot, hashed, from the same string the day file's header carries. Taken from
         * the header rather than derived again so the two can never disagree about which boot
         * they are describing.
         */
        val blackBoxBootIdHash: Int = 0,
    )

    private fun buildGraph(context: Context): Graph {
        lateinit var coordinator: ProtectionCoordinator
        val wallClock = ProtectionClock(System::currentTimeMillis)
        val elapsedClock = ProtectionClock(SystemClock::elapsedRealtime)
        val snapshotStore = ProtectionSnapshotStore(context, wallClock)
        val directBootStore = DirectBootProtectionStore(context)
        val secureKeyManager = com.example.motorcycleantitheftsensor.security.SecureKeyManager(context)
        val repository = FileIncidentRepository(
            file = File(context.filesDir, "protection_incidents.bin"),
            maxRecords = 200,
            secureKeyManager = secureKeyManager,
        )
        val startupRecoveryState = runCatching { snapshotStore.loadForRecovery() }.getOrNull()
        val shouldResumePowerRuntime = startupRecoveryState?.let { recovery ->
            recovery.continuityValid &&
                recovery.continuityIntent.desiredProtection == DesiredProtection.ARMED &&
                recovery.liveSnapshot.armedProfileSnapshot?.profile == ProtectionProfile.POWER
        } == true
        val restoredPowerRuntime = if (shouldResumePowerRuntime) {
            runCatching { restorePowerRuntimeState(repository.listNewestFirst()) }
                .onFailure { error -> Log.w(TAG, "Unable to restore open Power incident", error) }
                .getOrNull()
        } else {
            null
        }
        val preferences = EncryptedPrefsManager(context)
        val statePersistence = ProtectionStatePersistenceArbiter(
            writeCompatibilityArmed = { armed ->
                withContext(Dispatchers.IO) {
                    check(preferences.commitSystemArmed(armed)) {
                        "Unable to persist armed compatibility state"
                    }
                }
            },
            writeSnapshot = { snapshot, heartbeat ->
                withContext(Dispatchers.IO) {
                    val armed = snapshot.state in setOf(
                        ProtectionState.ARMING,
                        ProtectionState.ARMED_HEALTHY,
                        ProtectionState.ARMED_DEGRADED,
                        ProtectionState.ALERT_ACTIVE,
                    )
                    snapshotStore.save(
                        snapshot = snapshot,
                        lastServiceHeartbeatAtMs = heartbeat,
                        continuityIntent = ProtectionContinuityIntent(
                            desiredService = if (snapshot.state == ProtectionState.OFFLINE) {
                                DesiredService.STOPPED_BY_OWNER
                            } else {
                                DesiredService.RUNNING
                            },
                            desiredProtection = if (armed) DesiredProtection.ARMED else DesiredProtection.DISARMED,
                            autoRecoveryAfterBoot = preferences.isAutoRecoveryAfterBootEnabled(),
                            armedSessionId = if (armed) coordinator.currentArmedSessionId() else null,
                        ),
                    )
                    val markerSaved = directBootStore.save(
                        DirectBootProtectionMarker(
                            armed = armed,
                            autoRecoveryAfterBoot = preferences.isAutoRecoveryAfterBootEnabled(),
                        ),
                    )
                    if (!markerSaved) {
                        Log.w(TAG, "Unable to persist direct-boot recovery marker")
                    }
                }
            },
        )
        val breadcrumbRelay = BreadcrumbRelay()
        val telegram = TelegramBotClient(
            prefsManager = preferences,
            onTelegramContact = { atMs -> coordinator.recordTelegramContact(atMs) },
            // This is the client incidents go out on. Without a sink here the black box saw
            // only the heartbeat's traffic and could not say whether an alert was ever tried.
            breadcrumb = { event, details ->
                breadcrumbRelay.note(BreadcrumbDomain.TELEGRAM, event, details)
            },
        )
        val progressTelegram = com.example.motorcycleantitheftsensor.telegram.TelegramIncidentProgressTransport(
            httpClient = com.example.motorcycleantitheftsensor.network.TlsPinningClient.client,
            prefs = preferences,
            onTelegramContact = { atMs -> coordinator.recordTelegramContact(atMs) },
        )
        val sms = SmsFallbackManager(context, preferences)
        val labelResolver = com.example.motorcycleantitheftsensor.location.AndroidLocationLabelResolver(context)
        // Built here rather than inside the provider so the on-demand lookup can share it.
        // Sharing the client is not sharing a registration: each `register` call returns
        // its own, which is what keeps a `/where` from disturbing an armed tracker.
        val locationClient = com.example.motorcycleantitheftsensor.sensor.AndroidLocationUpdatesClient(
            context.applicationContext,
        )
        val locationProvider = LocationObservationProvider(locationClient)
        val delivery = IncidentDeliveryCoordinator(
            repository = repository,
            formatter = IncidentMessageFormatter { coordinator.snapshot.value },
            telegram = IncidentTransport(telegram::sendTelegramAlert),
            sms = IncidentTransport { message ->
                val destination = preferences.getSmsDestination()
                if (destination.isNullOrBlank()) {
                    breadcrumbRelay.note(
                        BreadcrumbDomain.SMS,
                        BreadcrumbEvent.DENIED,
                        listOf(BreadcrumbDetail.NOT_CONFIGURED),
                    )
                    false
                } else {
                    val outcome = sms.send(destination, message)
                    breadcrumbRelay.note(
                        BreadcrumbDomain.SMS,
                        outcome.breadcrumbEvent(),
                        outcome.breadcrumbDetails(),
                    )
                    outcome == SmsSendOutcome.SENT
                }
            },
            labelResolver = labelResolver,
            progressTelegram = progressTelegram,
        )
        val incidentRedeliverer = IncidentRedeliverer(
            history = { withContext(Dispatchers.IO) { repository.listNewestFirst() } },
            delivery = delivery,
            configuration = {
                withContext(Dispatchers.IO) {
                    DeliveryConfiguration(
                        smsConfigured = !preferences.getSmsDestination().isNullOrBlank(),
                    )
                }
            },
            nowMs = System::currentTimeMillis,
        )
        val processor = SensorObservationProcessor(
            staleAfterMs = 5_000L,
            debounceSamples = mapOf(
                SensorKind.VIBRATION to 3,
                SensorKind.LIGHT to 1,
                SensorKind.POWER_THERMAL to 1,
            ),
            thresholdDeltas = mapOf(
                SensorKind.VIBRATION to 2.0,
                SensorKind.LIGHT to 10.0,
                SensorKind.POWER_THERMAL to 5.0,
            ),
            absoluteMinimumsByDiagnostic = mapOf(
                "temperature_celsius" to 45.0,
            ),
        )
        val incidentEngine = IncidentEngine(
            idGenerator = IncidentIdGenerator { UUID.randomUUID().toString() },
            correlationWindowMs = 15_000L,
            restoredActiveIncident = restoredPowerRuntime?.incident,
            restoredAtElapsedMs = elapsedClock.nowMs(),
        )
        val deliveryPolicy = IncidentUpdateDeliveryPolicy()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val incidentMutex = Mutex()
        // Persists a copy the engine produced, which always says PENDING with no attempts
        // whatever became of the message. Anything already on file about that delivery is kept.
        suspend fun persistPreservingDeliveryRecord(incident: SecurityIncident) {
            withContext(Dispatchers.IO) {
                repository.upsert(incident.withDeliveryRecordOf(repository.findById(incident.id)))
            }
        }
        suspend fun process(update: IncidentUpdate) {
            (update as? IncidentUpdate.Opened)?.supersededIncident?.let { superseded ->
                // Already settled and already notified at its own opening: persist the
                // closure so history has no orphan, but never notify a second time.
                persistPreservingDeliveryRecord(superseded)
                coordinator.recordIncident(superseded)
            }
            val incident = update.incidentOrNull() ?: return
            when (deliveryPolicy.action(update)) {
                DeliveryAction.NONE -> Unit
                DeliveryAction.PERSIST_ONLY -> {
                    persistPreservingDeliveryRecord(incident)
                    coordinator.recordPersistenceRecovered(PersistenceSource.INCIDENT_HISTORY)
                    coordinator.recordIncident(incident)
                }

                DeliveryAction.SUPPRESS_REPEAT -> {
                    persistPreservingDeliveryRecord(incident)
                    coordinator.recordPersistenceRecovered(PersistenceSource.INCIDENT_HISTORY)
                    coordinator.recordIncident(incident)
                    // A silence the app chose. Recorded so that a reader asking why an
                    // ongoing incident went quiet is not left to guess between a rule and
                    // a fault — the two look identical from outside and are not the same.
                    breadcrumbRelay.note(
                        BreadcrumbDomain.TELEGRAM,
                        BreadcrumbEvent.DENIED,
                        listOf(BreadcrumbDetail.RATE_LIMITED),
                    )
                }

                DeliveryAction.PERSIST_AND_EDIT -> {
                    persistPreservingDeliveryRecord(incident)
                    coordinator.recordPersistenceRecovered(PersistenceSource.INCIDENT_HISTORY)
                    coordinator.recordIncident(incident)
                    delivery.updateProgress(incident)
                }

                DeliveryAction.SEND_CONTINUATION -> {
                    persistPreservingDeliveryRecord(incident)
                    coordinator.recordPersistenceRecovered(PersistenceSource.INCIDENT_HISTORY)
                    coordinator.recordIncident(incident)
                    delivery.updateProgress(incident)
                    delivery.sendContinuation(incident)
                }

                DeliveryAction.SEND,
                DeliveryAction.SEND_CLOSE_SUMMARY,
                -> {
                    coordinator.recordIncident(incident)
                    // The key is generated on demand, so a destination is the only thing the
                    // owner still has to supply.
                    val smsConfigured = withContext(Dispatchers.IO) {
                        !preferences.getSmsDestination().isNullOrBlank()
                    }
                    val delivered = withContext(Dispatchers.IO) {
                        delivery.deliver(
                            update = update,
                            configuration = DeliveryConfiguration(smsConfigured = smsConfigured),
                        )
                    }
                    val smsWasTried = delivered.deliveryAttempts.any { attempt ->
                        attempt.channel == DeliveryChannel.SMS
                    }
                    if (delivered.deliveryState == DeliveryState.FAILED && !smsWasTried) {
                        // Nothing reached the owner and the fallback was never even called, so
                        // nothing downstream can report why. Read off the record rather than by
                        // asking the rule again: this says what happened, not what should have.
                        // A silent night explained by an absent or a withheld fallback is
                        // exactly what a reader of this file comes looking for.
                        breadcrumbRelay.note(
                            BreadcrumbDomain.SMS,
                            BreadcrumbEvent.DENIED,
                            listOf(
                                if (smsConfigured) {
                                    BreadcrumbDetail.BELOW_THRESHOLD
                                } else {
                                    BreadcrumbDetail.NOT_CONFIGURED
                                },
                            ),
                        )
                    }
                    coordinator.recordIncident(delivered)
                    val localAttempt = delivered.deliveryAttempts.lastOrNull { attempt ->
                        attempt.channel == DeliveryChannel.LOCAL_STORAGE
                    }
                    if (localAttempt?.state == DeliveryState.FAILED) {
                        coordinator.recordPersistenceFailure(PersistenceSource.INCIDENT_HISTORY)
                    } else if (localAttempt != null) {
                        coordinator.recordPersistenceRecovered(PersistenceSource.INCIDENT_HISTORY)
                    }
                }
            }
        }
        val incidentCloseDispatcher = IncidentCloseDispatcher(
            scope = scope,
            persistLocal = { incident ->
                persistPreservingDeliveryRecord(incident)
                coordinator.recordPersistenceRecovered(PersistenceSource.INCIDENT_HISTORY)
                coordinator.recordIncident(incident)
            },
            deliverExternal = ::process,
            onPersistenceFailure = {
                coordinator.recordPersistenceFailure(PersistenceSource.INCIDENT_HISTORY)
            },
            onExternalFailure = { Log.w(TAG, "Incident close delivery failed") },
        )
        val candidateBuffer = AudioThreatCandidateBuffer()
        val onAudioCandidatesReset: () -> Unit = {
            scope.launch {
                incidentMutex.withLock {
                    incidentEngine.clearPendingAudio()
                }
            }
        }
        var quietWatchdogJob: Job? = null
        val watchdogLock = Any()

        fun scheduleQuietWatchdog() {
            synchronized(watchdogLock) {
                quietWatchdogJob?.cancel()
                quietWatchdogJob = null
                // A power episode is never closed by silence, so a watchdog over one
                // would wake every quiet window only to decline. Leave it unscheduled;
                // the next non-power update schedules it again.
                if (incidentEngine.activeIncidentType == IncidentType.POWER) {
                    return
                }
                quietWatchdogJob = scope.launch {
                    while (isActive) {
                        delay(INCIDENT_QUIET_WINDOW_MS)
                        val closed = incidentMutex.withLock {
                            if (!incidentEngine.hasActiveIncident) {
                                return@withLock null
                            }
                            incidentEngine.closeIfQuiet(
                                nowElapsedMs = elapsedClock.nowMs(),
                                quietWindowMs = INCIDENT_QUIET_WINDOW_MS,
                            )
                        }
                        if (closed != null) {
                            process(closed)
                            break
                        } else {
                            val stillActive = incidentMutex.withLock { incidentEngine.hasActiveIncident }
                            if (!stillActive) {
                                break
                            }
                        }
                    }
                }
            }
        }
        val incidentChannel = PriorityChannel<Pair<Long, IncidentObservationBatch>>(
            capacity = 1000,
            priorityOf = { (_, batch) ->
                var score = 0
                if (batch.primary.audioThreat != null) score += 10
                if (batch.location != null) score += 5
                if (batch.supplementalEvidence.any { it.audioThreat != null }) score += 10
                score
            }
        )

        scope.launch {
            while (isActive) {
                val (sessionEpoch, batch) = incidentChannel.receive()
                try {
                    val updateToProcess = incidentMutex.withLock {
                        val primaryUpdate = if (coordinator.acceptsIncident(sessionEpoch)) {
                            incidentEngine.accept(
                                observation = batch.primary,
                                protectionState = coordinator.snapshot.value.state,
                                location = batch.location,
                                movementCorroborationArmed = coordinator.movementCorroborationArmed(),
                                soundAndMovementDoorWatch = coordinator.soundAndMovementDoorWatchArmed(),
                            )
                        } else {
                            IncidentUpdate.Ignored
                        }
                        val enrichedUpdate = if (primaryUpdate == IncidentUpdate.Ignored) {
                            primaryUpdate
                        } else {
                            batch.supplementalEvidence.fold(primaryUpdate) { update, evidence ->
                                val evidenceUpdate = incidentEngine.accept(
                                    observation = evidence,
                                    protectionState = coordinator.snapshot.value.state,
                                    location = batch.location,
                                    movementCorroborationArmed = coordinator.movementCorroborationArmed(),
                                    soundAndMovementDoorWatch = coordinator.soundAndMovementDoorWatchArmed(),
                                )
                                evidenceUpdate.incidentOrNull()?.let { latest -> update.withIncident(latest) } ?: update
                            }
                        }
                        val incident = enrichedUpdate.incidentOrNull()
                        val currentArmedSession = coordinator.currentArmedSessionId()
                        if (incident != null && currentArmedSession != null) {
                            incident.evidence.forEach { ev ->
                                val threat = ev.audioThreat
                                if (threat != null) {
                                    candidateBuffer.consume(threat.category, currentArmedSession)
                                }
                            }
                        }
                        enrichedUpdate
                    }
                    if (updateToProcess != IncidentUpdate.Ignored) {
                        process(updateToProcess)
                        scheduleQuietWatchdog()
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    coordinator.recordPersistenceFailure(PersistenceSource.INCIDENT_HISTORY)
                    Log.e(TAG, "Incident processing failed", error)
                }
            }
        }

        val consumeIncidentBatch: (IncidentObservationBatch) -> Unit = { batch ->
            val sessionEpoch = coordinator.currentIncidentEpoch()
            incidentChannel.trySend(sessionEpoch to batch)
        }

        val movementTrackingStore = EncryptedMovementTrackingStore(preferences)
        val displacementPolicy = MovementDisplacementPolicy()
        val liveLocationTransport = com.example.motorcycleantitheftsensor.telegram.TelegramLiveLocationTransportImpl(
            httpClient = com.example.motorcycleantitheftsensor.network.TlsPinningClient.client,
            prefs = preferences
        )
        val expiryScheduler = CoroutinePursuitExpiryScheduler(scope)
        val livePursuitCoordinator = DefaultLivePursuitCoordinator(
            locationTracking = locationProvider,
            store = movementTrackingStore,
            displacementPolicy = displacementPolicy,
            transport = liveLocationTransport,
            labelResolver = labelResolver,
            expiryScheduler = expiryScheduler,
            scope = scope,
            coordinatorProvider = { coordinator },
            onMovementConfirmed = { fix ->
                val sessionEpoch = coordinator.currentIncidentEpoch()
                val armedSessionId = coordinator.currentArmedSessionId()
                scope.launch {
                    try {
                        val updateToProcess = incidentMutex.withLock {
                            if (!coordinator.acceptsIncident(sessionEpoch) || armedSessionId.isNullOrBlank()) return@withLock null
                            val obs = SensorObservation(
                                kind = SensorKind.LOCATION,
                                // This fix reaches the engine without passing the detector
                                // set, so it is stamped from the same table the armed use
                                // gave the runtime. Falling back to primary keeps the
                                // pre-declaration behaviour of a movement alert that opens
                                // on its own rather than muting it.
                                role = coordinator.currentSignalRole(SensorKind.LOCATION)
                                    ?: SensorRole.PRIMARY,
                                eventElapsedMs = fix.elapsedRealtimeMs,
                                wallClockMs = fix.wallClockMs,
                                normalizedValue = 1.0,
                                baselineDelta = 0.0,
                                valid = true,
                                diagnostic = "confirmed_movement",
                            )
                            val update = incidentEngine.onConfirmedMovement(
                                observation = obs,
                                protectionState = coordinator.snapshot.value.state,
                                location = IncidentLocation(
                                    latitude = fix.latitude,
                                    longitude = fix.longitude,
                                    accuracyMeters = fix.accuracyMeters,
                                    capturedAtWallClockMs = fix.wallClockMs,
                                ),
                            )
                            val incident = update.incidentOrNull()
                            if (incident != null) {
                                incident.evidence.forEach { ev ->
                                    val threat = ev.audioThreat
                                    if (threat != null) {
                                        candidateBuffer.consume(threat.category, armedSessionId)
                                    }
                                }
                                update
                            } else {
                                null
                            }
                        }
                        if (updateToProcess != null) {
                            process(updateToProcess)
                            scheduleQuietWatchdog()
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        coordinator.recordPersistenceFailure(PersistenceSource.INCIDENT_HISTORY)
                        Log.e(TAG, "Confirmed movement processing failed", error)
                    }
                }
            },
        )

        val sensorHandlerOwner = com.example.motorcycleantitheftsensor.sensor.SensorHandlerOwner("SensorThread")
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
        val sensorCatalog = com.example.motorcycleantitheftsensor.sensor.AndroidSensorCatalog(sensorManager)
        val sensorNormalizer = com.example.motorcycleantitheftsensor.sensor.SensorObservationNormalizer()
        val sensorCalibrationManager = com.example.motorcycleantitheftsensor.sensor.SensorCalibrationManager()
        val sensorPolicy = SensorConfigurationPolicy()
        val blackBoxSensorTap = BlackBoxSensorTap()
        // Built here rather than in the service because two things need the same instance:
        // the recorder that appends to it, and the export that has to copy it under the very
        // lock the recorder appends with.
        val blackBoxFileHeader = blackBoxHeader(context, sensorCatalog)
        val blackBoxWriter = BlackBoxWriter(
            directory = File(context.filesDir, BlackBoxWriter.DIRECTORY),
            header = blackBoxFileHeader,
            wallClockMs = System::currentTimeMillis,
        )
        val sensorController = com.example.motorcycleantitheftsensor.sensor.DefaultSensorCapabilityController(
            sensorManager = sensorManager,
            catalog = sensorCatalog,
            calibrationManager = sensorCalibrationManager,
            normalizer = sensorNormalizer,
            policy = sensorPolicy,
            handlerOwner = sensorHandlerOwner,
            // Values are copied out inside the tap; the array this hands over is the
            // platform's own and is rewritten by the next event.
            sampleTap = { sample ->
                blackBoxSensorTap.onSample(sample.source, sample.values, sample.accuracy)
            },
        )
        val sharedPrefs = try {
            androidx.security.crypto.EncryptedSharedPreferences.create(
                context,
                "motorcycle_anti_theft_encrypted_prefs",
                com.example.motorcycleantitheftsensor.security.SecureKeyManager(context).masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            context.getSharedPreferences("motorcycle_anti_theft_encrypted_prefs", Context.MODE_PRIVATE)
        }
        val sensorRepository = EncryptedPrefsSensorConfigurationRepository(sharedPrefs)

        // One process-shared profile aggregate writer used by both the UI layer and
        // SensorService recovery; credential-protected storage only.
        val profileRepository = SharedPreferencesProtectionProfileRepository(
            preferences = sharedPrefs,
            legacyRepository = sensorRepository,
        )

        // What this particular phone measured about its own orientation drift. Read at the
        // moment a use is offered, which is why it lives outside any session.
        val driftMeasurementStore = SharedPreferencesEntryDriftMeasurementStore(sharedPrefs)

        val runtime = AndroidProtectionRuntime(
            readinessProvider = AndroidRuntimeReadiness(context) {
                RemoteControlReadiness(
                    botTokenConfigured = !preferences.getBotToken().isNullOrBlank(),
                    ownerPaired = preferences.getAllowedChatIds().isNotEmpty(),
                )
            }::report,
            detectorFactory = { callback ->
                PlatformAndroidDetectorSet(
                    context = context,
                    location = locationProvider,
                    onObservation = callback,
                    audioCandidateBuffer = candidateBuffer,
                    onAudioCandidatesReset = onAudioCandidatesReset,
                    handlerOwner = sensorHandlerOwner,
                    controller = sensorController,
                    resumedPowerSemantic = restoredPowerRuntime?.semantic,
                )
            },
            observationProcessor = processor,
            elapsedClock = elapsedClock,
            stateProvider = { coordinator.snapshot.value.state },
            sensorSampleRecorder = { kind, atMs, detail, normalizedValue ->
                coordinator.recordSensorSample(kind, atMs, detail, normalizedValue)
            },
            incidentConsumer = consumeIncidentBatch,
        )

        val initialConfig = sensorRepository.loadConfiguration()
        runtime.applySensorConfiguration(initialConfig)

        val configuredSensitivity = initialConfig.capability(SensorCapability.MOVEMENT).sensitivity
        preferences.setSensitivity(configuredSensitivity)
        runtime.applySensitivity(configuredSensitivity)
        val graphPowerArmChallenge = PowerArmChallengeRegistry()
        coordinator = ProtectionCoordinator(
            initialSnapshot = ProtectionSnapshot.offline(wallClock.nowMs()).copy(
                sensitivityLevel = configuredSensitivity,
                sensorFusionConfiguration = initialConfig,
            ),
            runtime = runtime,
            armingDelay = ArmingDelay { delay(10_000L) },
            clock = wallClock,
            incidentCloser = { reason ->
                synchronized(watchdogLock) {
                    quietWatchdogJob?.cancel()
                    quietWatchdogJob = null
                }
                incidentMutex.lock()
                val closed = try {
                    incidentEngine.close(
                        nowMs = wallClock.nowMs(),
                        reason = reason,
                    )
                } finally {
                    incidentMutex.unlock()
                }
                closed?.let { incidentCloseDispatcher.persistAndDispatch(it) } ?: true
            },
            durableSnapshotWriter = { snapshot ->
                statePersistence.persist(
                    ProtectionPersistenceRequest(snapshot, wallClock.nowMs()),
                )
            },
            sensorRepository = sensorRepository,
            profileRepository = profileRepository,
            entryCommissioningContextProvider = {
                EntryCommissioningEnvironment.currentContext(
                    entryUseContinuous = true,
                    source = runtime.entryOrientationSource(),
                )
            },
            powerCommissioningContextProvider = {
                PowerWitnessCommissioningPolicy.CommissioningContext(
                    sensorIdentity = EntryCommissioningEnvironment.sensorIdentity(),
                    hoodSignature = PowerWitnessCommissioningPolicy.DEFAULT_HOOD_SIGNATURE,
                    algorithmVersion = PowerWitnessCommissioningPolicy.ALGORITHM_VERSION,
                    powerUseContinuous = true,
                )
            },
            deviceSupport = { profile ->
                ProfileDeviceSupportPolicy.support(
                    profile,
                    com.example.motorcycleantitheftsensor.sensor.SensorAvailabilityPolicy
                        .availability(sensorCatalog.descriptors()),
                    // The door watch is offered on the strength of what this phone measured
                    // about itself, against the angle this owner actually set. A phone that
                    // measured nothing is unaffected.
                    entryDrift = EntryDriftBudgetPolicy.verdict(
                        measurement = driftMeasurementStore.load(),
                        alertAngleDeg = entryAlertAngleDeg(profileRepository),
                        currentSource = runtime.entryOrientationSource(),
                    ),
                )
            },
            entryDriftVerdict = {
                EntryDriftBudgetPolicy.verdict(
                    measurement = driftMeasurementStore.load(),
                    alertAngleDeg = entryAlertAngleDeg(profileRepository),
                    currentSource = runtime.entryOrientationSource(),
                )
            },
            powerIntegrityChallenge = { graphPowerArmChallenge.isSatisfied(wallClock.nowMs()) },
            recoveredPowerIntegrityChallenge = {
                val calibration = startupRecoveryState
                    ?.liveSnapshot
                    ?.armedProfileSnapshot
                    ?.armedCalibrationSnapshot as? PowerArmedCalibrationSnapshot
                calibration?.witnessPlacementValidated
            },
        )
        runtime.applySensitivity(configuredSensitivity)
        coordinator.recordSensorHealthSnapshot(runtime.currentSensorHealth())
        scope.launch {
            runtime.sensorHealth.collect { healthMap ->
                val healthWithLocation = healthMap.toMutableMap()
                healthWithLocation[SensorKind.LOCATION] = locationProvider.trackingHealth.value.toSensorHealth()
                coordinator.recordSensorHealthSnapshot(healthWithLocation)
            }
        }
        coordinator.recordSensorHealth(SensorKind.LOCATION, locationProvider.trackingHealth.value.toSensorHealth())
        scope.launch {
            locationProvider.trackingHealth.collect { health ->
                coordinator.recordSensorHealth(SensorKind.LOCATION, health.toSensorHealth())
            }
        }
        var previousNotifiedState: ProtectionState? = null
        val stateNotifier = ProtectionStateTelegramNotifier()
        scope.launch {
            coordinator.snapshot.collect { snapshot ->
                livePursuitCoordinator.onProtectionStateChanged(snapshot.state, coordinator.currentArmedSessionId())
                val current = snapshot.state
                val prev = previousNotifiedState
                previousNotifiedState = current

                val messages = stateNotifier.messagesFor(
                    previous = prev,
                    current = current,
                    degradationReasons = snapshot.degradationReasons,
                    // Read from the same snapshot that carries the state, so the alert
                    // cannot name a mode the transition did not happen under.
                    context = snapshot.modeContext,
                )
                if (messages.isNotEmpty()) {
                    scope.launch(Dispatchers.IO) {
                        for (msg in messages) {
                            try {
                                telegram.sendTelegramAlert(msg)
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        }
        return Graph(
            coordinator = coordinator,
            incidents = repository,
            delivery = delivery,
            incidentRedeliverer = incidentRedeliverer,
            breadcrumbRelay = breadcrumbRelay,
            runtime = runtime,
            snapshotStore = snapshotStore,
            statePersistence = statePersistence,
            scope = scope,
            livePursuitCoordinator = livePursuitCoordinator,
            sensorRepository = sensorRepository,
            profileRepository = profileRepository,
            powerArmChallenge = graphPowerArmChallenge,
            sensorCatalog = sensorCatalog,
            driftMeasurementStore = driftMeasurementStore,
            blackBoxSensorTap = blackBoxSensorTap,
            blackBoxWriter = blackBoxWriter,
            blackBoxBootIdHash = blackBoxFileHeader.bootId.hashCode(),
            liveStatusReader = { profile ->
                // Never a measurement, only a reading of state that already exists. An
                // owner whose vehicle has just been taken sends this command over and over,
                // and the battery left in the phone is the entire budget for finding it, so
                // no branch here may wake the radio or register a listener.
                val nowElapsedMs = SystemClock.elapsedRealtime()
                val movement = if (profile == ProtectionProfile.VEHICLE) {
                    runCatching { movementTrackingStore.load() }.getOrNull()
                } else {
                    null
                }
                val anchor = movement?.anchor
                val fix = anchor?.let { locationProvider.currentUsableFix(nowElapsedMs) }
                com.example.motorcycleantitheftsensor.telegram.LiveStatusReadings(
                    doorAngleDeg = runtime.liveDoorAngleDeg(),
                    witnessLit = runtime.liveWitnessLit(),
                    confirmationCountdownMs = runtime.liveConfirmationCountdownMs(nowElapsedMs),
                    metersFromParking = if (anchor != null && fix != null) {
                        MovementDisplacementPolicy.calculateHaversineDistance(
                            anchor.fix.latitude,
                            anchor.fix.longitude,
                            fix.latitude,
                            fix.longitude,
                        )
                    } else {
                        null
                    },
                    // The threshold that would actually be applied to this pair of fixes,
                    // computed the way MovementDisplacementPolicy computes it. Printing the
                    // base constant instead would quote 100 metres while a pair of coarse
                    // fixes was really being judged at 180, which is the kind of number that
                    // teaches an owner their app is guessing.
                    parkingThresholdMeters = if (anchor != null && fix != null) {
                        MovementDisplacementPolicy.displacementThresholdMeters(
                            anchor.fix.accuracyMeters,
                            fix.accuracyMeters,
                        )
                    } else {
                        null
                    },
                    pursuitActive = movement?.let { it.session != null },
                    smsFallbackMasked = PresentationTextCatalog.maskedSmsDestination(
                        preferences.getSmsDestination(),
                    ),
                )
            },
            onDemandLocationFinder = com.example.motorcycleantitheftsensor.location.OnDemandLocationFinder(
                tracking = locationProvider,
                client = locationClient,
                elapsedMs = SystemClock::elapsedRealtime,
            ),
        )
    }

    /**
     * The cover page of every day file.
     *
     * Raw numbers with no statement of which sensor produced them, at what resolution, cannot
     * be interpreted afterwards by anyone, us included. The boot is identified rather than the
     * process: two runs separated by a kill share a boot id, and that is what separates "the
     * app was killed" from "the phone rebooted".
     */
    private fun blackBoxHeader(
        context: Context,
        catalog: com.example.motorcycleantitheftsensor.sensor.SensorCatalog,
    ): BlackBoxHeader {
        val wallMs = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        val sensors = runCatching {
            catalog.descriptors()
                .filterValues { descriptor -> descriptor.isAvailable }
                .entries
                .joinToString(";") { (source, descriptor) ->
                    "${source.name}:${descriptor.name}:${descriptor.vendor}:${descriptor.resolution}"
                }
        }.getOrNull().orEmpty()
        return BlackBoxHeader(
            device = "${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL}",
            androidSdk = android.os.Build.VERSION.SDK_INT,
            appVersion = version,
            bootId = ((wallMs - elapsed) / 1_000L).toString(),
            wallAnchorMs = wallMs,
            elapsedAtAnchorMs = elapsed,
            sensors = sensors,
        )
    }

    private fun IncidentUpdate.incidentOrNull(): SecurityIncident? = when (this) {
        IncidentUpdate.Ignored -> null
        is IncidentUpdate.Opened -> incident
        is IncidentUpdate.Updated -> incident
        is IncidentUpdate.Escalated -> incident
        is IncidentUpdate.Closed -> incident
    }

    private fun IncidentUpdate.withIncident(incident: SecurityIncident): IncidentUpdate = when (this) {
        IncidentUpdate.Ignored -> this
        is IncidentUpdate.Opened -> IncidentUpdate.Opened(incident, supersededIncident)
        is IncidentUpdate.Updated -> IncidentUpdate.Updated(incident)
        is IncidentUpdate.Escalated -> IncidentUpdate.Escalated(incident)
        is IncidentUpdate.Closed -> IncidentUpdate.Closed(incident)
    }

    private const val INCIDENT_QUIET_WINDOW_MS = 30_000L
    private const val TAG = "ProtectionRuntime"
}

internal data class RestoredPowerRuntimeState(
    val incident: SecurityIncident,
    val semantic: PowerCompositeArbiter.SemanticState,
)

internal fun restorePowerRuntimeState(
    incidents: List<SecurityIncident>,
): RestoredPowerRuntimeState? = incidents
    .asSequence()
    .filter { incident ->
        incident.type == IncidentType.POWER && incident.lifecycle == IncidentLifecycle.OPEN
    }
    .sortedByDescending(SecurityIncident::updatedAtMs)
    .mapNotNull { incident ->
        val semantic = incident.evidence.asReversed()
            .mapNotNull(IncidentEvidence::diagnostic)
            .firstNotNullOfOrNull(::powerSemanticForDiagnostic)
        semantic?.let { RestoredPowerRuntimeState(incident, it) }
    }
    .firstOrNull()

private fun powerSemanticForDiagnostic(
    diagnostic: String,
): PowerCompositeArbiter.SemanticState? = when (diagnostic) {
    ProtectionDiagnostics.POWER_CHARGING_HEALTH -> PowerCompositeArbiter.SemanticState.CHARGING_LOST
    ProtectionDiagnostics.POWER_WITNESS_DARK -> PowerCompositeArbiter.SemanticState.WITNESS_LOST
    ProtectionDiagnostics.POWER_CONFIRMED_LOSS -> PowerCompositeArbiter.SemanticState.DUAL_LOST
    ProtectionDiagnostics.POWER_PARTIAL_WITNESS_DARK -> PowerCompositeArbiter.SemanticState.WITNESS_LOST
    ProtectionDiagnostics.POWER_PARTIAL_CHARGING_LOST -> PowerCompositeArbiter.SemanticState.CHARGING_LOST
    else -> null
}
