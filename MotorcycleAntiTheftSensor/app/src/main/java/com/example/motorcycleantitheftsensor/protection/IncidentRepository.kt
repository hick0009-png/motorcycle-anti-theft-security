package com.example.motorcycleantitheftsensor.protection

interface IncidentRepository {
    fun upsert(incident: SecurityIncident)

    fun findById(id: String): SecurityIncident?

    fun listNewestFirst(): List<SecurityIncident>

    fun clearHistory()
}
