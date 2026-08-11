package com.example.motorcycleantitheftsensor.protection

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
import com.example.motorcycleantitheftsensor.security.TotpAuthenticator
import com.example.motorcycleantitheftsensor.telegram.TelegramBotClient
import com.example.motorcycleantitheftsensor.telephony.SmsFallbackManager
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

object ProtectionRuntimeGraph {
    @Volatile
    private var instance: Graph? = null

    fun from(context: Context): Graph = instance ?: synchronized(this) {
        instance ?: buildGraph(context.applicationContext).also { graph -> instance = graph }
    }

    data class Graph(
        val coordinator: ProtectionCoordinator,
        val incidents: IncidentRepository,
        val delivery: IncidentDeliveryCoordinator,
        val runtime: ProtectionRuntime,
        val snapshotStore: ProtectionSnapshotStore,
        val scope: CoroutineScope,
    )

    private fun buildGraph(context: Context): Graph {
        lateinit var coordinator: ProtectionCoordinator
        val wallClock = ProtectionClock(System::currentTimeMillis)
        val elapsedClock = ProtectionClock(SystemClock::elapsedRealtime)
        val snapshotStore = ProtectionSnapshotStore(context, wallClock)
        val repository = FileIncidentRepository(
            file = File(context.filesDir, "protection_incidents.bin"),
            maxRecords = 200,
        )
        val preferences = EncryptedPrefsManager(context)
        val telegram = TelegramBotClient(
            context = context,
            prefsManager = preferences,
            totpAuthenticator = TotpAuthenticator(preferences),
            onTelegramContact = { atMs -> coordinator.recordTelegramContact(atMs) },
        )
        val sms = SmsFallbackManager(context, preferences)
        val delivery = IncidentDeliveryCoordinator(
            repository = repository,
            formatter = IncidentMessageFormatter(),
            telegram = IncidentTransport(telegram::sendTelegramAlert),
            sms = IncidentTransport { message ->
                val destination = preferences.getSmsDestination()
                if (destination.isNullOrBlank() || preferences.getSmsAesKey().isNullOrBlank()) {
                    false
                } else {
                    sms.sendEncryptedSmsAlert(destination, "SECURITY_INCIDENT", message)
                }
            },
        )
        val processor = SensorObservationProcessor(
            staleAfterMs = 5_000L,
            debounceSamples = mapOf(
                SensorKind.VIBRATION to 3,
                SensorKind.LIGHT to 1,
                SensorKind.POWER_THERMAL to 1,
                SensorKind.MICROPHONE to 2,
            ),
            thresholdDeltas = mapOf(
                SensorKind.VIBRATION to 2.0,
                SensorKind.LIGHT to 10.0,
                SensorKind.POWER_THERMAL to 5.0,
                SensorKind.MICROPHONE to 0.25,
            ),
            absoluteMinimumsByDiagnostic = mapOf(
                "temperature_celsius" to 45.0,
            ),
        )
        val incidentEngine = IncidentEngine(
            idGenerator = IncidentIdGenerator { UUID.randomUUID().toString() },
            correlationWindowMs = 15_000L,
        )
        val deliveryPolicy = IncidentUpdateDeliveryPolicy()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val incidentMutex = Mutex()
        suspend fun process(update: IncidentUpdate) {
            val incident = update.incidentOrNull() ?: return
            when (deliveryPolicy.action(update)) {
                DeliveryAction.NONE -> Unit
                DeliveryAction.PERSIST_ONLY -> withContext(Dispatchers.IO) {
                    repository.upsert(incident)
                }.also { coordinator.recordIncident(incident) }

                DeliveryAction.SEND,
                DeliveryAction.SEND_CLOSE_SUMMARY,
                -> {
                    coordinator.recordIncident(incident)
                    val delivered = withContext(Dispatchers.IO) {
                        delivery.deliver(
                            incident = incident,
                            configuration = DeliveryConfiguration(
                                smsConfigured = !preferences.getSmsDestination().isNullOrBlank() &&
                                    !preferences.getSmsAesKey().isNullOrBlank(),
                            ),
                        )
                    }
                    coordinator.recordIncident(delivered)
                    if (delivered.deliveryAttempts.any { attempt ->
                            attempt.channel == DeliveryChannel.LOCAL_STORAGE &&
                                attempt.state == DeliveryState.FAILED
                        }
                    ) {
                        coordinator.recordPersistenceFailure()
                    }
                }
            }
        }
        val incidentCloseDispatcher = IncidentCloseDispatcher(
            scope = scope,
            persistLocal = { incident ->
                withContext(Dispatchers.IO) { repository.upsert(incident) }
                coordinator.recordIncident(incident)
            },
            deliverExternal = ::process,
            onPersistenceFailure = { coordinator.recordPersistenceFailure() },
            onExternalFailure = { Log.w(TAG, "Incident close delivery failed") },
        )
        val runtime = AndroidProtectionRuntime(
            readinessProvider = AndroidRuntimeReadiness(context) {
                RemoteControlReadiness(
                    botTokenConfigured = !preferences.getBotToken().isNullOrBlank(),
                    ownerPaired = preferences.getAllowedChatIds().isNotEmpty(),
                    totpConfigured = !preferences.getTotpSeed().isNullOrBlank(),
                )
            }::report,
            detectorFactory = { callback -> PlatformAndroidDetectorSet(context, callback) },
            observationProcessor = processor,
            elapsedClock = elapsedClock,
            stateProvider = { coordinator.snapshot.value.state },
            sensorSampleRecorder = { kind, atMs, detail, normalizedValue ->
                coordinator.recordSensorSample(kind, atMs, detail, normalizedValue)
            },
            incidentConsumer = { batch ->
                val sessionEpoch = coordinator.currentIncidentEpoch()
                scope.launch {
                    try {
                        incidentMutex.lock()
                        val accepted = try {
                            val primaryUpdate = if (coordinator.acceptsIncident(sessionEpoch)) {
                                incidentEngine.accept(
                                    observation = batch.primary,
                                    protectionState = coordinator.snapshot.value.state,
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
                                    )
                                    evidenceUpdate.incidentOrNull()?.let { latest -> update.withIncident(latest) } ?: update
                                }
                            }
                            enrichedUpdate.also { update -> process(update) }
                        } finally {
                            incidentMutex.unlock()
                        }
                        if (accepted == IncidentUpdate.Ignored) return@launch
                        delay(INCIDENT_QUIET_WINDOW_MS)
                        incidentMutex.lock()
                        try {
                            incidentEngine.closeIfQuiet(
                                nowElapsedMs = elapsedClock.nowMs(),
                                quietWindowMs = INCIDENT_QUIET_WINDOW_MS,
                                source = batch.primary.source,
                            )?.let { closed -> process(closed) }
                        } finally {
                            incidentMutex.unlock()
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        coordinator.recordPersistenceFailure()
                        Log.e(TAG, "Incident processing failed", error)
                    }
                }
            },
        )
        coordinator = ProtectionCoordinator(
            initialSnapshot = ProtectionSnapshot.offline(wallClock.nowMs()),
            runtime = runtime,
            armingDelay = ArmingDelay { delay(10_000L) },
            clock = wallClock,
            incidentCloser = { reason ->
                incidentMutex.lock()
                val closed = try {
                    incidentEngine.close(
                        nowMs = wallClock.nowMs(),
                        reason = reason,
                    )
                } finally {
                    incidentMutex.unlock()
                }
                closed?.let { incidentCloseDispatcher.persistAndDispatch(it) }
            },
            durableSnapshotWriter = { snapshot ->
                withContext(Dispatchers.IO) {
                    snapshotStore.save(snapshot, wallClock.nowMs())
                }
            },
        )
        runtime.applySensitivity(preferences.getSensitivity())
        return Graph(
            coordinator = coordinator,
            incidents = repository,
            delivery = delivery,
            runtime = runtime,
            snapshotStore = snapshotStore,
            scope = scope,
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
        is IncidentUpdate.Opened -> IncidentUpdate.Opened(incident)
        is IncidentUpdate.Updated -> IncidentUpdate.Updated(incident)
        is IncidentUpdate.Escalated -> IncidentUpdate.Escalated(incident)
        is IncidentUpdate.Closed -> IncidentUpdate.Closed(incident)
    }

    private const val INCIDENT_QUIET_WINDOW_MS = 30_000L
    private const val TAG = "ProtectionRuntime"
}
