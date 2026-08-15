package com.example.motorcycleantitheftsensor.protection

import com.example.motorcycleantitheftsensor.location.LocationLabelResolver
import com.example.motorcycleantitheftsensor.location.LocationPresentation
import com.example.motorcycleantitheftsensor.location.LocationPresentationFactory
import com.example.motorcycleantitheftsensor.location.TrackedLocationFix
import kotlinx.coroutines.CancellationException

fun interface IncidentTransport {
    suspend fun send(message: String): Boolean
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
) {
    constructor(
        repository: IncidentRepository,
        formatter: IncidentMessageFormatter,
        telegram: IncidentTransport,
        sms: IncidentTransport,
        labelResolver: LocationLabelResolver?,
    ) : this(
        repository = repository,
        formatter = formatter,
        telegram = telegram,
        sms = sms,
        presentationFactory = labelResolver?.let { LocationPresentationFactory(it) },
    )

    suspend fun deliver(
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
        val telegramMessage = formatter.formatTelegram(pending, presentation)
        val telegramSent = telegram.send(telegramMessage)
        val telegramAttempt = DeliveryAttempt(
            channel = DeliveryChannel.TELEGRAM,
            state = if (telegramSent) DeliveryState.SENT else DeliveryState.FAILED,
            attemptedAtMs = incident.updatedAtMs,
        )

        val smsEligible = !telegramSent &&
            pending.severity == IncidentSeverity.CRITICAL &&
            configuration.smsConfigured
        val smsSent = if (smsEligible) {
            val smsMessage = formatter.formatSms(pending)
            sms.send(smsMessage)
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
}
