package com.example.motorcycleantitheftsensor.protection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface IncidentRepository {
    /**
     * Advances after every persisted change so readers can re-read the history instead of
     * polling it. Implementations that never change may keep the constant default.
     */
    val revision: StateFlow<Long>
        get() = CONSTANT_REVISION

    fun upsert(incident: SecurityIncident)

    fun findById(id: String): SecurityIncident?

    fun listNewestFirst(): List<SecurityIncident>

    fun clearHistory()

    private companion object {
        val CONSTANT_REVISION: StateFlow<Long> = MutableStateFlow(0L).asStateFlow()
    }
}
