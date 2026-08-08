package com.example.motorcycleantitheftsensor.protection

enum class DeliveryAction {
    NONE,
    PERSIST_ONLY,
    SEND,
    SEND_CLOSE_SUMMARY,
}

class IncidentUpdateDeliveryPolicy {
    fun action(update: IncidentUpdate): DeliveryAction = when (update) {
        IncidentUpdate.Ignored -> DeliveryAction.NONE
        is IncidentUpdate.Opened -> DeliveryAction.SEND
        is IncidentUpdate.Updated -> DeliveryAction.PERSIST_ONLY
        is IncidentUpdate.Escalated -> DeliveryAction.SEND
        is IncidentUpdate.Closed -> DeliveryAction.SEND_CLOSE_SUMMARY
    }
}
