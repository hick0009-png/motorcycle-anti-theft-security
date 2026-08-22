package com.example.motorcycleantitheftsensor.protection

import com.example.motorcycleantitheftsensor.location.LocationLabelResolver
import com.example.motorcycleantitheftsensor.location.LocationPresentation
import com.example.motorcycleantitheftsensor.location.LocationPresentationFactory
import com.example.motorcycleantitheftsensor.location.TrackedLocationFix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface IncidentTransport {
    suspend fun send(message: String): Boolean
}

interface IncidentProgressTransport {
    suspend fun open(incidentId: String, message: String): Boolean
    suspend fun update(incidentId: String, message: String): Boolean
    suspend fun clear(incidentId: String)
}

data class DeliveryConfiguration(
    val smsConfigured: Boolean,
)

class IncidentDeliveryCoordinator(
    private val repository: IncidentRepository,
    private val formatter: IncidentMessageFormatter,
    private val telegram: IncidentTransport,
    private val sms: IncidentTransport,
    private val presentationFactory: LocationPresentationFactory? = null,
    private val progressTelegram: IncidentProgressTransport? = null,
) {
    constructor(
        repository: IncidentRepository,
        formatter: IncidentMessageFormatter,
        telegram: IncidentTransport,
        sms: IncidentTransport,
        labelResolver: LocationLabelResolver?,
        progressTelegram: IncidentProgressTransport? = null,
    ) : this(
        repository = repository,
        formatter = formatter,
        telegram = telegram,
        sms = sms,
        presentationFactory = labelResolver?.let { LocationPresentationFactory(it) },
        progressTelegram = progressTelegram,
    )

    private val deliveryMutex = Mutex()
    private val deliveredResults = LinkedHashMap<String, SecurityIncident>()
    private val inFlightDeliveries = mutableMapOf<String, CompletableDeferred<SecurityIncident>>()

    suspend fun deliver(
        update: IncidentUpdate,
        configuration: DeliveryConfiguration,
    ): SecurityIncident {
        val incident = update.incidentOrNull() ?: error("Cannot deliver Ignored IncidentUpdate")
        val key = "${incident.id}:${update.eventKindName}:${incident.updatedAtMs}"

        var myDeferred: CompletableDeferred<SecurityIncident>? = null
        var existingDeferred: CompletableDeferred<SecurityIncident>? = null

        deliveryMutex.withLock {
            deliveredResults[key]?.let { return it }

            val inFlight = inFlightDeliveries[key]
            if (inFlight != null) {
                existingDeferred = inFlight
            } else {
                val deferred = CompletableDeferred<SecurityIncident>()
                inFlightDeliveries[key] = deferred
                myDeferred = deferred
            }
        }

        if (existingDeferred != null) {
            return existingDeferred!!.await()
        }

        val deferred = myDeferred!!
        try {
            val result = executeDelivery(update, incident, configuration)
            deliveryMutex.withLock {
                deliveredResults[key] = result
                if (deliveredResults.size > 500) {
                    val firstKey = deliveredResults.keys.first()
                    deliveredResults.remove(firstKey)
                }
                inFlightDeliveries.remove(key)
            }
            deferred.complete(result)
            return result
        } catch (error: Throwable) {
            deliveryMutex.withLock {
                inFlightDeliveries.remove(key)
            }
            deferred.completeExceptionally(error)
            throw error
        }
    }

    suspend fun deliver(
        incident: SecurityIncident,
        configuration: DeliveryConfiguration,
    ): SecurityIncident = deliver(incident.toDefaultUpdate(), configuration)

    suspend fun updateProgress(incident: SecurityIncident): Boolean {
        val transport = progressTelegram ?: return false
        return try {
            transport.update(incident.id, formatter.formatProgress(incident))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            false
        }
    }

    suspend fun sendContinuation(incident: SecurityIncident): Boolean = try {
        telegram.send(formatter.formatContinuation(incident))
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        false
    }

    private suspend fun executeDelivery(
        update: IncidentUpdate,
        incident: SecurityIncident,
        configuration: DeliveryConfiguration,
    ): SecurityIncident {
        val pending = incident.copy(deliveryState = DeliveryState.PENDING)
        try {
            repository.upsert(pending)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return pending.withStorageFailure(DeliveryState.FAILED)
        }

        val presentation = pending.location?.let { loc ->
            resolvePresentation(loc)
        }
        val telegramMessage = formatter.formatTelegram(update, presentation)
        val telegramSent = try {
            if (update is IncidentUpdate.Opened && progressTelegram != null) {
                progressTelegram.open(incident.id, telegramMessage)
            } else {
                telegram.send(telegramMessage)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            false
        }
        val telegramAttempt = DeliveryAttempt(
            channel = DeliveryChannel.TELEGRAM,
            state = if (telegramSent) DeliveryState.SENT else DeliveryState.FAILED,
            attemptedAtMs = incident.updatedAtMs,
        )

        val smsEligible = !telegramSent &&
            pending.severity == IncidentSeverity.CRITICAL &&
            configuration.smsConfigured
        val smsSent = if (smsEligible) {
            val smsMessage = formatter.formatSms(update)
            try {
                sms.send(smsMessage)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                false
            }
        } else {
            false
        }
        val attempts = if (smsEligible) {
            pending.deliveryAttempts + telegramAttempt + DeliveryAttempt(
                channel = DeliveryChannel.SMS,
                state = if (smsSent) DeliveryState.SENT else DeliveryState.FAILED,
                attemptedAtMs = incident.updatedAtMs,
            )
        } else {
            pending.deliveryAttempts + telegramAttempt
        }
        val delivered = pending.copy(
            deliveryState = if (telegramSent || smsSent) DeliveryState.SENT else DeliveryState.FAILED,
            deliveryAttempts = attempts,
        )
        if (update is IncidentUpdate.Closed) {
            progressTelegram?.clear(incident.id)
        }
        return try {
            repository.upsert(delivered)
            delivered
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            delivered.withStorageFailure(delivered.deliveryState)
        }
    }

    private suspend fun resolvePresentation(loc: IncidentLocation): LocationPresentation? {
        val factory = presentationFactory ?: return null
        val fix = TrackedLocationFix(
            latitude = loc.latitude,
            longitude = loc.longitude,
            elapsedRealtimeMs = 0L,
            wallClockMs = loc.capturedAtWallClockMs,
            accuracyMeters = loc.accuracyMeters,
        )
        return factory.create(fix)
    }

    private fun SecurityIncident.withStorageFailure(state: DeliveryState): SecurityIncident = copy(
        deliveryState = state,
        deliveryAttempts = deliveryAttempts + DeliveryAttempt(
            channel = DeliveryChannel.LOCAL_STORAGE,
            state = DeliveryState.FAILED,
            attemptedAtMs = updatedAtMs,
            detail = "Incident persistence unavailable",
        ),
    )

    private fun SecurityIncident.toDefaultUpdate(): IncidentUpdate = when (lifecycle) {
        IncidentLifecycle.CLOSED -> IncidentUpdate.Closed(this)
        else -> IncidentUpdate.Opened(this)
    }

    private val IncidentUpdate.eventKindName: String
        get() = when (this) {
            IncidentUpdate.Ignored -> "IGNORED"
            is IncidentUpdate.Opened -> "OPENED"
            is IncidentUpdate.Updated -> "UPDATED"
            is IncidentUpdate.Escalated -> "ESCALATED"
            is IncidentUpdate.Closed -> "CLOSED"
        }

    private fun IncidentUpdate.incidentOrNull(): SecurityIncident? = when (this) {
        IncidentUpdate.Ignored -> null
        is IncidentUpdate.Opened -> incident
        is IncidentUpdate.Updated -> incident
        is IncidentUpdate.Escalated -> incident
        is IncidentUpdate.Closed -> incident
    }
}
