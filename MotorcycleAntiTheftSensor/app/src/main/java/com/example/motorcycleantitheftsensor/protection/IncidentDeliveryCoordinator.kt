package com.example.motorcycleantitheftsensor.protection

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
) {
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
        val message = formatter.format(pending)
        val telegramSent = telegram.send(message)
        val telegramAttempt = DeliveryAttempt(
            channel = DeliveryChannel.TELEGRAM,
            state = if (telegramSent) DeliveryState.SENT else DeliveryState.FAILED,
            attemptedAtMs = incident.updatedAtMs,
        )

        val smsEligible = !telegramSent &&
            pending.source == IncidentSource.REAL &&
            pending.severity == IncidentSeverity.CRITICAL &&
            configuration.smsConfigured
        val smsSent = smsEligible && sms.send(message)
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
