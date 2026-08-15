package com.example.motorcycleantitheftsensor.ui

import androidx.lifecycle.ViewModel
import com.example.motorcycleantitheftsensor.protection.CommandOrigin
import com.example.motorcycleantitheftsensor.protection.CommandOutcome
import com.example.motorcycleantitheftsensor.protection.IncidentRepository
import com.example.motorcycleantitheftsensor.protection.ProtectionCommandResult
import com.example.motorcycleantitheftsensor.protection.ProtectionCoordinator
import com.example.motorcycleantitheftsensor.protection.SecurityIncident
import com.example.motorcycleantitheftsensor.protection.SnapshotProjectionGate
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class ProtectionViewModel(
    private val coordinator: ProtectionCoordinator,
    private val incidents: IncidentRepository,
    private val settings: ProtectionSettingsGateway,
    private val initialMissingPermissions: Set<String> = emptySet(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val ticker: Flow<Unit> = flow {
        while (coroutineContext.isActive) {
            emit(Unit)
            delay(1_000L)
        }
    },
    private val snapshotProjectionGate: SnapshotProjectionGate =
        SnapshotProjectionGate(UI_SNAPSHOT_PROJECTION_INTERVAL_MS),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val callbackDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val settingsMutex = Mutex()
    private val eventsMutex = Mutex()
    private val commandSequence = AtomicLong(0L)
    private val activeProtectionOperations = AtomicLong(0L)
    private val settingsReadVersion = AtomicLong(0L)
    private val destination = MutableStateFlow(ProtectionDestination.PROTECTION)
    private val settingsSummary = MutableStateFlow(emptySettingsSummary())
    private val presentation = MutableStateFlow(PresentationInputs(settingsLoading = true))
    private val currentTimeMs = MutableStateFlow(nowMs())
    private val projectedSnapshots = coordinator.snapshot.filter { snapshot ->
        snapshotProjectionGate.shouldProject(snapshot, nowMs())
    }

    val uiState: StateFlow<ProtectionUiState> = combine(
        projectedSnapshots,
        destination,
        settingsSummary,
        presentation,
        currentTimeMs,
    ) { snapshot, selectedDestination, currentSettings, inputs, currentTime ->
        ProtectionUiState.from(
            snapshot = snapshot,
            incidents = inputs.incidents,
            settings = currentSettings,
            nowMs = currentTime,
            destination = selectedDestination,
            eventsLoading = inputs.eventsLoading,
            eventsError = inputs.eventsError,
            operationInFlight = inputs.anyOperationInFlight,
            message = inputs.message,
            settingsLoading = inputs.settingsLoading,
            settingsLoaded = inputs.settingsLoaded,
            settingsError = inputs.settingsError,
            protectionOperationInFlight = inputs.protectionOperationInFlight,
            settingsOperationInFlight = inputs.settingsOperationInFlight,
            eventsOperationInFlight = inputs.eventsOperationInFlight,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = ProtectionUiState.from(
            snapshot = coordinator.snapshot.value,
            incidents = emptyList(),
            settings = settingsSummary.value,
            nowMs = currentTimeMs.value,
            settingsLoading = true,
            settingsLoaded = false,
        ),
    )

    init {
        scope.launch { refreshEvents() }
        scope.launch { readSettings(initialMissingPermissions) }
        scope.launch {
            ticker.collect { currentTimeMs.value = nowMs() }
        }
    }

    fun selectDestination(destination: ProtectionDestination) {
        this.destination.value = destination
    }

    fun arm() = runProtectionCommand(com.example.motorcycleantitheftsensor.protection.GuidanceCode.COMMAND_ARM_REJECTED) {
        publishResult(coordinator.arm(nextCommandId(), CommandOrigin.LOCAL))
    }

    fun disarm() = runProtectionCommand(com.example.motorcycleantitheftsensor.protection.GuidanceCode.COMMAND_DISARM_REJECTED) {
        publishResult(coordinator.disarm(nextCommandId(), CommandOrigin.LOCAL))
    }

    fun changeSensitivity(level: Int) = runSettingsCommand(com.example.motorcycleantitheftsensor.protection.GuidanceCode.COMMAND_UNKNOWN) {
        val result = coordinator.changeSensitivity(nextCommandId(), level)
        publishResult(result)
        if (result.outcome == CommandOutcome.APPLIED) {
            settings.saveSensitivity(level)
            readSettings(settingsSummary.value.missingPermissions)
        }
    }

    fun clearHistory() = runEventsCommand(com.example.motorcycleantitheftsensor.protection.GuidanceCode.COMMAND_UNKNOWN) {
        presentation.update { it.copy(eventsLoading = true, eventsError = null) }
        try {
            withContext(dispatcher) { incidents.clearHistory() }
            refreshEvents()
        } catch (exception: CancellationException) {
            presentation.update { it.copy(eventsLoading = false) }
            throw exception
        } catch (exception: Throwable) {
            presentation.update { it.copy(eventsLoading = false) }
            throw exception
        }
    }

    fun updateMissingPermissions(permissions: Set<String>) = runSettingsCommand(com.example.motorcycleantitheftsensor.protection.GuidanceCode.SETTINGS_SAVE_FAILED) {
        readSettings(permissions)
    }

    fun replaceBotToken(token: String) {
        if (token.isBlank()) {
            publishMessage(com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.BOT_TOKEN_INVALID))
            return
        }
        runSettingsCommand(com.example.motorcycleantitheftsensor.protection.GuidanceCode.SETTINGS_SAVE_FAILED) {
            val result = settings.replaceBotToken(token)
            if (result.applied) {
                publishMessage(
                    com.example.motorcycleantitheftsensor.protection.GuidanceContent(
                        titleTh = "บันทึกและเชื่อมต่อ Bot สำเร็จ",
                        bodyTh = result.message,
                        telegramTh = null,
                        severity = com.example.motorcycleantitheftsensor.protection.GuidanceSeverity.SUCCESS,
                        action = com.example.motorcycleantitheftsensor.protection.GuidanceAction.NONE,
                        persistent = false,
                    )
                )
                readSettings(settingsSummary.value.missingPermissions)
            } else {
                publishMessage(
                    com.example.motorcycleantitheftsensor.protection.GuidanceContent(
                        titleTh = "บันทึก Bot Token ไม่สำเร็จ",
                        bodyTh = result.message,
                        telegramTh = null,
                        severity = com.example.motorcycleantitheftsensor.protection.GuidanceSeverity.WARNING,
                        action = com.example.motorcycleantitheftsensor.protection.GuidanceAction.RETRY_NON_SENSITIVE_SETTINGS,
                        persistent = false,
                    )
                )
            }
        }
    }

    fun configureSmsFallback(destination: String, aesKey: String) = runSettingsCommand(com.example.motorcycleantitheftsensor.protection.GuidanceCode.SETTINGS_SAVE_FAILED) {
        val result = settings.saveSmsFallback(destination, aesKey)
        if (result.applied) {
            publishMessage(
                com.example.motorcycleantitheftsensor.protection.GuidanceContent(
                    titleTh = "บันทึก SMS สำรองสำเร็จ",
                    bodyTh = "บันทึกเบอร์ปลายทางและคีย์เข้ารหัสเรียบร้อยแล้ว",
                    telegramTh = null,
                    severity = com.example.motorcycleantitheftsensor.protection.GuidanceSeverity.SUCCESS,
                    action = com.example.motorcycleantitheftsensor.protection.GuidanceAction.NONE,
                    persistent = false,
                )
            )
            readSettings(settingsSummary.value.missingPermissions)
        } else {
            publishMessage(
                com.example.motorcycleantitheftsensor.protection.GuidanceContent(
                    titleTh = "บันทึก SMS สำรองไม่สำเร็จ",
                    bodyTh = "ตรวจสอบข้อมูลแล้วลองใหม่",
                    telegramTh = null,
                    severity = com.example.motorcycleantitheftsensor.protection.GuidanceSeverity.WARNING,
                    action = com.example.motorcycleantitheftsensor.protection.GuidanceAction.RETRY_NON_SENSITIVE_SETTINGS,
                    persistent = false,
                )
            )
        }
    }
    fun retry() = runEventsCommand(com.example.motorcycleantitheftsensor.protection.GuidanceCode.COMMAND_UNKNOWN) {
        refreshEvents()
    }

    fun retrySettings() = runSettingsCommand(com.example.motorcycleantitheftsensor.protection.GuidanceCode.COMMAND_UNKNOWN) {
        readSettings(settingsSummary.value.missingPermissions)
    }

    fun resetPairing() = runSettingsCommand(com.example.motorcycleantitheftsensor.protection.GuidanceCode.SETTINGS_SAVE_FAILED) {
        val result = settings.resetPairing()
        if (result.applied) {
            publishMessage(
                com.example.motorcycleantitheftsensor.protection.GuidanceContent(
                    titleTh = "รีเซ็ตการจับคู่สำเร็จ",
                    bodyTh = "กรุณาใช้รหัสจับคู่ใหม่บน Telegram",
                    telegramTh = null,
                    severity = com.example.motorcycleantitheftsensor.protection.GuidanceSeverity.SUCCESS,
                    action = com.example.motorcycleantitheftsensor.protection.GuidanceAction.NONE,
                    persistent = false,
                )
            )
            readSettings(settingsSummary.value.missingPermissions)
        } else {
            publishMessage(
                com.example.motorcycleantitheftsensor.protection.GuidanceContent(
                    titleTh = "รีเซ็ตการจับคู่ไม่สำเร็จ",
                    bodyTh = result.message,
                    telegramTh = null,
                    severity = com.example.motorcycleantitheftsensor.protection.GuidanceSeverity.WARNING,
                    action = com.example.motorcycleantitheftsensor.protection.GuidanceAction.RETRY_NON_SENSITIVE_SETTINGS,
                    persistent = false,
                )
            )
        }
    }

    fun consumeMessage(id: Long) {
        presentation.update { current ->
            if (current.message?.id == id) current.copy(message = null) else current
        }
    }

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }

    private fun runProtectionCommand(failureCode: com.example.motorcycleantitheftsensor.protection.GuidanceCode, action: suspend () -> Unit) {
        scope.launch {
            activeProtectionOperations.incrementAndGet()
            presentation.update { it.copy(protectionOperationInFlight = true) }
            try {
                action()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Throwable) {
                publishMessage(com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(failureCode))
            } finally {
                if (activeProtectionOperations.decrementAndGet() == 0L) {
                    presentation.update { it.copy(protectionOperationInFlight = false) }
                }
            }
        }
    }

    private fun runSettingsCommand(failureCode: com.example.motorcycleantitheftsensor.protection.GuidanceCode, action: suspend () -> Unit) {
        scope.launch {
            settingsMutex.withLock {
                presentation.update { it.copy(settingsOperationInFlight = true) }
                try {
                    action()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Throwable) {
                    publishMessage(com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(failureCode))
                } finally {
                    presentation.update { it.copy(settingsOperationInFlight = false) }
                }
            }
        }
    }

    private fun runEventsCommand(failureCode: com.example.motorcycleantitheftsensor.protection.GuidanceCode, action: suspend () -> Unit) {
        scope.launch {
            eventsMutex.withLock {
                presentation.update { it.copy(eventsOperationInFlight = true) }
                try {
                    action()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Throwable) {
                    publishMessage(com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(failureCode))
                } finally {
                    presentation.update { it.copy(eventsOperationInFlight = false) }
                }
            }
        }
    }

    private fun <T> runSensitiveSettingsCommand(
        failureCode: com.example.motorcycleantitheftsensor.protection.GuidanceCode,
        failureValue: T,
        onComplete: (T) -> Unit,
        action: suspend () -> T,
    ): () -> Unit {
        val job = scope.launch {
            val result = settingsMutex.withLock {
                presentation.update { it.copy(settingsOperationInFlight = true) }
                try {
                    action()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Throwable) {
                    publishMessage(com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(failureCode))
                    failureValue
                } finally {
                    presentation.update { it.copy(settingsOperationInFlight = false) }
                }
            }
            withContext(callbackDispatcher) { onComplete(result) }
        }
        return job::cancel
    }

    private suspend fun refreshEvents() {
        presentation.update { it.copy(eventsLoading = true, eventsError = null) }
        try {
            val records = withContext(dispatcher) { incidents.listNewestFirst() }
            presentation.update {
                it.copy(incidents = records, eventsLoading = false, eventsError = null)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Throwable) {
            presentation.update {
                it.copy(
                    eventsLoading = false,
                    eventsError = exception.message ?: "Unable to load event history",
                )
            }
        }
    }

    private suspend fun readSettings(missingPermissions: Set<String>) {
        val requestVersion = settingsReadVersion.incrementAndGet()
        presentation.update { it.copy(settingsLoading = true, settingsError = null) }
        try {
            val summary = withContext(dispatcher) { settings.read(missingPermissions) }
            if (settingsReadVersion.get() == requestVersion) {
                settingsSummary.value = summary
                presentation.update {
                    it.copy(settingsLoading = false, settingsLoaded = true, settingsError = null)
                }
            }
        } catch (exception: CancellationException) {
            if (settingsReadVersion.get() == requestVersion) {
                presentation.update { it.copy(settingsLoading = false) }
            }
            throw exception
        } catch (exception: Throwable) {
            if (settingsReadVersion.get() == requestVersion) {
                presentation.update {
                    it.copy(
                        settingsLoading = false,
                        settingsError = exception.message ?: "Unable to load settings",
                    )
                }
            }
        }
    }

    private suspend fun publishSettingsResult(result: SettingsOperationResult) {
        val code = if (result.applied) com.example.motorcycleantitheftsensor.protection.GuidanceCode.SETTINGS_SAVE_SUCCESS else com.example.motorcycleantitheftsensor.protection.GuidanceCode.SETTINGS_SAVE_FAILED
        publishMessage(com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(code))
        if (result.applied) readSettings(settingsSummary.value.missingPermissions)
    }

    private fun publishResult(result: ProtectionCommandResult) {
        val code = if (result.outcome == CommandOutcome.APPLIED) {
            when (result.resultingState) {
                com.example.motorcycleantitheftsensor.protection.ProtectionState.ARMING, com.example.motorcycleantitheftsensor.protection.ProtectionState.ARMED_HEALTHY, com.example.motorcycleantitheftsensor.protection.ProtectionState.ARMED_DEGRADED -> com.example.motorcycleantitheftsensor.protection.GuidanceCode.COMMAND_ARM_APPLIED
                com.example.motorcycleantitheftsensor.protection.ProtectionState.DISARMED_ONLINE -> com.example.motorcycleantitheftsensor.protection.GuidanceCode.COMMAND_DISARM_APPLIED
                com.example.motorcycleantitheftsensor.protection.ProtectionState.ALERT_ACTIVE -> com.example.motorcycleantitheftsensor.protection.GuidanceCode.ALERT_ACTIVE
                else -> com.example.motorcycleantitheftsensor.protection.GuidanceCode.COMMAND_STATUS_SUCCESS
            }
        } else {
            if (result.reason.contains("Arm", ignoreCase = true)) com.example.motorcycleantitheftsensor.protection.GuidanceCode.COMMAND_ARM_REJECTED
            else if (result.reason.contains("Disarm", ignoreCase = true)) com.example.motorcycleantitheftsensor.protection.GuidanceCode.COMMAND_DISARM_REJECTED
            else if (result.reason.contains("Sensitivity", ignoreCase = true)) com.example.motorcycleantitheftsensor.protection.GuidanceCode.COMMAND_SENSITIVITY_INVALID
            else com.example.motorcycleantitheftsensor.protection.GuidanceCode.COMMAND_UNKNOWN
        }
        publishMessage(com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(code))
    }

    private fun publishMessage(guidanceContent: com.example.motorcycleantitheftsensor.protection.GuidanceContent) {
        presentation.update {
            it.copy(
                message = ProtectionUiMessage(
                    id = commandSequence.incrementAndGet(),
                    content = guidanceContent,
                ),
            )
        }
    }

    private fun nextCommandId(): String = "ui-${UUID.randomUUID()}"

    private companion object {
        const val UI_SNAPSHOT_PROJECTION_INTERVAL_MS = 1_000L
    }
}

private data class PresentationInputs(
    val incidents: List<SecurityIncident> = emptyList(),
    val eventsLoading: Boolean = false,
    val eventsError: String? = null,
    val protectionOperationInFlight: Boolean = false,
    val settingsOperationInFlight: Boolean = false,
    val eventsOperationInFlight: Boolean = false,
    val settingsLoading: Boolean = false,
    val settingsLoaded: Boolean = false,
    val settingsError: String? = null,
    val message: ProtectionUiMessage? = null,
) {
    val anyOperationInFlight: Boolean
        get() = protectionOperationInFlight || settingsOperationInFlight || eventsOperationInFlight
}

private fun emptySettingsSummary(): ProtectionSettingsSummary = ProtectionSettingsSummary(
    tokenConfigured = false,
    pairedOwnerCount = 0,
    pairingCode = null,
    sensitivity = 1,
    smsFallbackConfigured = false,
    missingPermissions = emptySet(),
)
