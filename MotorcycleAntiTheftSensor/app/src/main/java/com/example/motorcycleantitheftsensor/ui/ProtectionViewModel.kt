package com.example.motorcycleantitheftsensor.ui

import androidx.lifecycle.ViewModel
import com.example.motorcycleantitheftsensor.protection.CommandOrigin
import com.example.motorcycleantitheftsensor.protection.CommandOutcome
import com.example.motorcycleantitheftsensor.protection.IncidentRepository
import com.example.motorcycleantitheftsensor.protection.ProtectionCommandResult
import com.example.motorcycleantitheftsensor.protection.ProtectionCoordinator
import com.example.motorcycleantitheftsensor.protection.SecurityIncident
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
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val callbackDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val commandMutex = Mutex()
    private val commandSequence = AtomicLong(0L)
    private val settingsReadVersion = AtomicLong(0L)
    private val destination = MutableStateFlow(ProtectionDestination.PROTECTION)
    private val settingsSummary = MutableStateFlow(emptySettingsSummary())
    private val presentation = MutableStateFlow(PresentationInputs())
    private val currentTimeMs = MutableStateFlow(nowMs())

    val uiState: StateFlow<ProtectionUiState> = combine(
        coordinator.snapshot,
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
            operationInFlight = inputs.operationInFlight,
            message = inputs.message,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = ProtectionUiState.from(
            snapshot = coordinator.snapshot.value,
            incidents = emptyList(),
            settings = settingsSummary.value,
            nowMs = currentTimeMs.value,
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

    fun arm() = runCommand("Unable to arm protection") {
        publishResult(coordinator.arm(nextCommandId(), CommandOrigin.LOCAL))
    }

    fun disarm() = runCommand("Unable to disarm protection") {
        publishResult(coordinator.disarm(nextCommandId(), CommandOrigin.LOCAL))
    }

    fun changeSensitivity(level: Int) = runCommand("Unable to change sensitivity") {
        val result = coordinator.changeSensitivity(nextCommandId(), level)
        publishResult(result)
        if (result.outcome == CommandOutcome.APPLIED) {
            settings.saveSensitivity(level)
            readSettings(settingsSummary.value.missingPermissions)
        }
    }

    fun clearHistory() = runCommand("Unable to clear event history") {
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

    fun updateMissingPermissions(permissions: Set<String>) = runCommand("Unable to update permissions") {
        readSettings(permissions)
    }

    fun replaceBotToken(token: String) {
        if (token.isBlank()) {
            publishMessage("Bot token is required", isError = true)
            return
        }
        runCommand("Unable to update bot token") {
            publishSettingsResult(settings.replaceBotToken(token))
        }
    }

    fun configureSmsFallback(destination: String, aesKey: String) = runCommand(
        "Unable to configure SMS fallback",
    ) {
        publishSettingsResult(settings.saveSmsFallback(destination, aesKey))
    }

    fun beginAuthenticatorSetup(onComplete: (String?) -> Unit): () -> Unit = runSensitiveCommand(
        failureMessage = "Unable to start authenticator setup",
        failureValue = null,
        onComplete = onComplete,
    ) {
        settings.beginAuthenticatorSetup()
    }

    fun verifyAuthenticator(code: String, onComplete: (Boolean) -> Unit): () -> Unit = runSensitiveCommand(
        failureMessage = "Unable to verify authenticator code",
        failureValue = false,
        onComplete = onComplete,
    ) {
        val verified = settings.verifyAuthenticator(code)
        if (verified) readSettings(settingsSummary.value.missingPermissions)
        verified
    }

    fun retry() = runCommand("Unable to refresh event history") {
        refreshEvents()
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

    private fun runCommand(failureMessage: String, action: suspend () -> Unit) {
        scope.launch {
            commandMutex.withLock {
                presentation.update { it.copy(operationInFlight = true) }
                try {
                    action()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Throwable) {
                    publishMessage(failureMessage, isError = true)
                } finally {
                    presentation.update { it.copy(operationInFlight = false) }
                }
            }
        }
    }

    private fun <T> runSensitiveCommand(
        failureMessage: String,
        failureValue: T,
        onComplete: (T) -> Unit,
        action: suspend () -> T,
    ): () -> Unit {
        val job = scope.launch {
            val result = commandMutex.withLock {
                presentation.update { it.copy(operationInFlight = true) }
                try {
                    action()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Throwable) {
                    publishMessage(failureMessage, isError = true)
                    failureValue
                } finally {
                    presentation.update { it.copy(operationInFlight = false) }
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
        val summary = withContext(dispatcher) { settings.read(missingPermissions) }
        if (settingsReadVersion.get() == requestVersion) {
            settingsSummary.value = summary
        }
    }

    private suspend fun publishSettingsResult(result: SettingsOperationResult) {
        publishMessage(result.message, isError = !result.applied)
        if (result.applied) readSettings(settingsSummary.value.missingPermissions)
    }

    private fun publishResult(result: ProtectionCommandResult) {
        publishMessage(result.reason, isError = result.outcome != CommandOutcome.APPLIED)
    }

    private fun publishMessage(text: String, isError: Boolean) {
        presentation.update {
            it.copy(
                message = ProtectionUiMessage(
                    id = commandSequence.incrementAndGet(),
                    text = text,
                    isError = isError,
                ),
            )
        }
    }

    private fun nextCommandId(): String = "ui-${UUID.randomUUID()}"
}

private data class PresentationInputs(
    val incidents: List<SecurityIncident> = emptyList(),
    val eventsLoading: Boolean = false,
    val eventsError: String? = null,
    val operationInFlight: Boolean = false,
    val message: ProtectionUiMessage? = null,
)

private fun emptySettingsSummary(): ProtectionSettingsSummary = ProtectionSettingsSummary(
    tokenConfigured = false,
    pairedOwnerCount = 0,
    pairingCode = null,
    authenticatorConfigured = false,
    sensitivity = 1,
    smsFallbackConfigured = false,
    missingPermissions = emptySet(),
)
