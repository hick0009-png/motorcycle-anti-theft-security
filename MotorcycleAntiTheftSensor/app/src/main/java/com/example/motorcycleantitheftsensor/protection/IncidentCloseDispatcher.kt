package com.example.motorcycleantitheftsensor.protection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class IncidentCloseDispatcher(
    private val scope: CoroutineScope,
    private val persistLocal: suspend (SecurityIncident) -> Unit,
    private val deliverExternal: suspend (IncidentUpdate.Closed) -> Unit,
    private val onPersistenceFailure: () -> Unit = {},
    private val onExternalFailure: () -> Unit = {},
) {
    suspend fun persistAndDispatch(update: IncidentUpdate.Closed) {
        try {
            persistLocal(update.incident)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            onPersistenceFailure()
            return
        }
        scope.launch {
            try {
                deliverExternal(update)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                onExternalFailure()
            }
        }
    }
}
