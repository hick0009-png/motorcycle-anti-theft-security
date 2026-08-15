package com.example.motorcycleantitheftsensor.protection

import android.os.SystemClock
import com.example.motorcycleantitheftsensor.location.ConflatedLocationFixIngress
import com.example.motorcycleantitheftsensor.location.LiveLocationHandle
import com.example.motorcycleantitheftsensor.location.LocationFixIngress
import com.example.motorcycleantitheftsensor.location.LocationLabelResolver
import com.example.motorcycleantitheftsensor.location.LocationPresentation
import com.example.motorcycleantitheftsensor.location.LocationPresentationFactory
import com.example.motorcycleantitheftsensor.location.MovementDecision
import com.example.motorcycleantitheftsensor.location.MovementDisplacementPolicy
import com.example.motorcycleantitheftsensor.location.MovementTrackingState
import com.example.motorcycleantitheftsensor.location.MovementTrackingStore
import com.example.motorcycleantitheftsensor.location.ParkingAnchor
import com.example.motorcycleantitheftsensor.location.PersistedLivePursuitSession
import com.example.motorcycleantitheftsensor.location.TrackedLocationFix
import com.example.motorcycleantitheftsensor.sensor.MovementLocationTracking
import com.example.motorcycleantitheftsensor.telegram.TelegramCallResult
import com.example.motorcycleantitheftsensor.telegram.TelegramFailureCode
import com.example.motorcycleantitheftsensor.telegram.TelegramLiveLocationTransport
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

interface LivePursuitCoordinator {
    suspend fun onProtectionStateChanged(newState: ProtectionState, proposedSessionId: String?)
    suspend fun onLocationFix(fix: TrackedLocationFix)
    suspend fun prepareForStop()
    fun abortLocal()
    fun shutdown()
}

class DefaultLivePursuitCoordinator(
    private val locationTracking: MovementLocationTracking,
    private val store: MovementTrackingStore,
    private val displacementPolicy: MovementDisplacementPolicy,
    private val transport: TelegramLiveLocationTransport,
    private val labelResolver: LocationLabelResolver,
    private val expiryScheduler: PursuitExpiryScheduler,
    private val scope: CoroutineScope,
    private val coordinator: ProtectionCoordinator? = null,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val elapsedClockMs: () -> Long = SystemClock::elapsedRealtime,
    private val armedSessionIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val presentationFactory: LocationPresentationFactory = LocationPresentationFactory(labelResolver),
) : LivePursuitCoordinator {

    private val stateMutex = Mutex()
    private val remoteMutex = Mutex()
    private val lifecycleGeneration = AtomicLong(0L)
    private var currentState: ProtectionState = ProtectionState.DISARMED_ONLINE
    private var activeSessionId: String? = null
    private var currentAnchor: ParkingAnchor? = null
    private var currentSession: PersistedLivePursuitSession? = null
    private var anchorSet = false
    private var pursuitAttempted = false
    private var activeHandles: List<LiveLocationHandle> = emptyList()
    private val handlePublicationStates = mutableMapOf<LiveLocationHandle, HandlePublicationState>()
    private var expiryHandle: PursuitExpiryHandle? = null
    private var fixIngress: LocationFixIngress? = null

    private data class HandlePublicationState(
        val handle: LiveLocationHandle,
        val lastSuccessfulFix: TrackedLocationFix,
        val consecutiveRetryableFailures: Int = 0,
        val retryNotBeforeElapsedMs: Long = 0L,
        val lastAttemptElapsedMs: Long = 0L,
    )

    private data class StartPursuitRequest(
        val fix: TrackedLocationFix,
        val sid: String,
        val generation: Long,
        val expiresAtMs: Long,
    )

    private data class UpdatePursuitRequest(
        val handle: LiveLocationHandle,
        val fix: TrackedLocationFix,
        val sid: String,
        val generation: Long,
    )

    private data class StopOutcome(
        val stopped: List<LiveLocationHandle>,
        val retainedForRecovery: List<LiveLocationHandle>,
    )

    private fun ProtectionState.isPursuitEligible(): Boolean =
        this == ProtectionState.ARMED_HEALTHY ||
            this == ProtectionState.ARMED_DEGRADED ||
            this == ProtectionState.ALERT_ACTIVE

    private fun isCurrentAttemptLocked(sid: String, generation: Long): Boolean =
        currentState.isPursuitEligible() &&
            activeSessionId == sid &&
            lifecycleGeneration.get() == generation

    private fun formatMovementAlert(presentation: LocationPresentation?): String {
        val baseAlert = "🚨 ตรวจพบว่ารถหรืออุปกรณ์กำลังถูกเคลื่อนย้าย\nเริ่มติดตามตำแหน่งแบบสดเป็นเวลา 15 นาที"
        return if (presentation != null) {
            val labelLine = presentation.labelTh?.takeIf { it.isNotBlank() }?.let { "\n📍 บริเวณโดยประมาณ: $it" } ?: ""
            "$baseAlert$labelLine\n🗺️ แผนที่: ${presentation.mapsUrl} (ความแม่นยำ ~${presentation.accuracyMeters}m)"
        } else {
            baseAlert
        }
    }

    private fun recordStoreFailure() {
        coordinator?.recordPersistenceFailure(PersistenceSource.MOVEMENT_TRACKING)
    }

    private fun recordStoreSuccess() {
        coordinator?.recordPersistenceRecovered(PersistenceSource.MOVEMENT_TRACKING)
    }

    private suspend fun safeStoreLoad(): MovementTrackingState {
        return try {
            val state = store.load()
            recordStoreSuccess()
            state
        } catch (_: Exception) {
            recordStoreFailure()
            MovementTrackingState()
        }
    }

    private suspend fun safeStoreSave(state: MovementTrackingState): Boolean {
        return try {
            val ok = store.save(state)
            if (ok) {
                recordStoreSuccess()
            } else {
                recordStoreFailure()
            }
            ok
        } catch (_: Exception) {
            recordStoreFailure()
            false
        }
    }

    private suspend fun safeStoreClear(): Boolean {
        return try {
            val ok = store.clear()
            if (ok) {
                recordStoreSuccess()
            } else {
                recordStoreFailure()
            }
            ok
        } catch (_: Exception) {
            recordStoreFailure()
            false
        }
    }

    override suspend fun onProtectionStateChanged(newState: ProtectionState, proposedSessionId: String?) {
        var handlesToStop: List<LiveLocationHandle> = emptyList()
        var shouldStartArmedTracking = false
        var shouldEnterPursuit = false
        var sessionToSave: PersistedLivePursuitSession? = null
        var anchorToSave: ParkingAnchor? = null
        var shouldClearStore = false

        val loadedState = if (newState.isPursuitEligible() && !currentState.isPursuitEligible()) {
            safeStoreLoad()
        } else {
            null
        }

        stateMutex.withLock {
            val previousState = currentState
            currentState = newState

            when {
                newState.isPursuitEligible() && !previousState.isPursuitEligible() -> {
                    expiryHandle?.cancel()
                    expiryHandle = null

                    val state = loadedState ?: MovementTrackingState()
                    val storedAnchor = state.anchor
                    val storedSession = state.session
                    val nowWall = wallClockMs()
                    val storedAnchorValid = storedAnchor?.let {
                        displacementPolicy.isValidStoredFix(it.fix)
                    } == true

                    val matchesProposedId = proposedSessionId == null ||
                        (storedAnchor?.armedSessionId == proposedSessionId || storedSession?.armedSessionId == proposedSessionId)

                    when {
                        storedAnchorValid && storedSession != null && storedAnchor.armedSessionId == storedSession.armedSessionId && matchesProposedId -> {
                            activeSessionId = storedSession.armedSessionId
                            currentAnchor = storedAnchor
                            currentSession = storedSession
                            displacementPolicy.reset(storedAnchor)
                            anchorSet = true
                            pursuitAttempted = true

                            val remainingMs = (storedSession.expiresAtMs - nowWall).coerceIn(0L, 900_000L)
                            if (remainingMs > 0L && storedSession.handles.isNotEmpty()) {
                                activeHandles = storedSession.handles
                                for (h in storedSession.handles) {
                                    handlePublicationStates[h] = HandlePublicationState(h, storedAnchor.fix)
                                }
                                val currentGen = lifecycleGeneration.incrementAndGet()
                                expiryHandle = expiryScheduler.schedule(remainingMs) {
                                    scope.launch { onExpiry(currentGen) }
                                }
                                shouldStartArmedTracking = true
                                shouldEnterPursuit = true
                            } else {
                                if (storedSession.handles.isNotEmpty()) {
                                    handlesToStop = storedSession.handles
                                    val updatedSession = storedSession.copy(handles = emptyList())
                                    currentSession = updatedSession
                                    sessionToSave = updatedSession
                                    anchorToSave = storedAnchor
                                }
                                activeHandles = emptyList()
                                shouldStartArmedTracking = true
                            }
                        }

                        storedAnchorValid && storedSession == null && (proposedSessionId == null || storedAnchor.armedSessionId == proposedSessionId) -> {
                            activeSessionId = storedAnchor.armedSessionId
                            currentAnchor = storedAnchor
                            currentSession = null
                            displacementPolicy.reset(storedAnchor)
                            anchorSet = true
                            pursuitAttempted = false
                            activeHandles = emptyList()
                            shouldStartArmedTracking = true
                        }

                        storedSession != null && (proposedSessionId == null || storedSession.armedSessionId == proposedSessionId) -> {
                            activeSessionId = storedSession.armedSessionId
                            currentAnchor = null
                            currentSession = storedSession
                            pursuitAttempted = true
                            anchorSet = false
                            displacementPolicy.clear()
                            if (storedSession.handles.isNotEmpty()) {
                                handlesToStop = storedSession.handles
                                val updatedSession = storedSession.copy(handles = emptyList())
                                currentSession = updatedSession
                                sessionToSave = updatedSession
                                anchorToSave = null
                            }
                            activeHandles = emptyList()
                            shouldStartArmedTracking = true
                        }

                        else -> {
                            activeSessionId = proposedSessionId ?: armedSessionIdFactory()
                            currentAnchor = null
                            currentSession = null
                            pursuitAttempted = false
                            anchorSet = false
                            activeHandles = emptyList()
                            displacementPolicy.clear()
                            if (proposedSessionId != null) {
                                shouldClearStore = true
                            }
                            shouldStartArmedTracking = true
                        }
                    }
                }

                newState == ProtectionState.ARMING -> {
                    // Calibration period (no GPS tracking / anchor)
                }

                newState.isPursuitEligible() && previousState.isPursuitEligible() -> {
                    // State transition among eligible states: keep tracking active, preserve state
                }

                newState == ProtectionState.DISARMED_ONLINE -> {
                    expiryHandle?.cancel()
                    expiryHandle = null
                    lifecycleGeneration.incrementAndGet()
                    handlesToStop = activeHandles
                    activeHandles = emptyList()
                    handlePublicationStates.clear()
                    anchorSet = false
                    pursuitAttempted = false
                    currentAnchor = null
                    currentSession = null
                    activeSessionId = null
                    displacementPolicy.clear()
                    shouldClearStore = true
                }

                newState == ProtectionState.SETUP_REQUIRED || newState == ProtectionState.OFFLINE -> {
                    expiryHandle?.cancel()
                    expiryHandle = null
                    lifecycleGeneration.incrementAndGet()
                    handlesToStop = activeHandles
                    activeHandles = emptyList()
                    handlePublicationStates.clear()
                    displacementPolicy.clear()
                    if (activeSessionId != null) {
                        val nowWall = wallClockMs()
                        val session = PersistedLivePursuitSession(
                            armedSessionId = activeSessionId!!,
                            attemptedAtMs = nowWall,
                            expiresAtMs = nowWall,
                            handles = emptyList()
                        )
                        currentSession = session
                        sessionToSave = session
                        anchorToSave = currentAnchor
                    }
                    anchorSet = false
                    activeSessionId = null
                }
            }
        }

        // Side effects strictly outside stateMutex
        if (sessionToSave != null) {
            safeStoreSave(MovementTrackingState(anchor = anchorToSave, session = sessionToSave))
        }
        if (shouldClearStore) {
            safeStoreClear()
        }

        if (newState == ProtectionState.DISARMED_ONLINE ||
            newState == ProtectionState.SETUP_REQUIRED ||
            newState == ProtectionState.OFFLINE) {
            locationTracking.stopTracking()
            fixIngress?.cancelPending()
            fixIngress?.awaitClosed()
            fixIngress = null
            if (handlesToStop.isNotEmpty()) {
                val outcome = stopHandlesRemotely(handlesToStop)
                persistStopOutcome(outcome)
            }
        } else {
            if (handlesToStop.isNotEmpty()) {
                val outcome = stopHandlesRemotely(handlesToStop)
                persistStopOutcome(outcome)
            }
            if (newState.isPursuitEligible() && (shouldStartArmedTracking || !locationTracking.isTracking())) {
                fixIngress?.cancelPending()
                fixIngress?.awaitClosed()
                val ingress = ConflatedLocationFixIngress(scope, ::onLocationFix)
                fixIngress = ingress
                val started = locationTracking.startArmedTracking { fix ->
                    ingress.offer(fix)
                }
                if (started) {
                    if (shouldEnterPursuit) {
                        locationTracking.enterPursuitMode()
                    }
                } else {
                    ingress.cancelPending()
                    ingress.awaitClosed()
                    fixIngress = null
                }
            }
        }
    }

    override suspend fun onLocationFix(fix: TrackedLocationFix) {
        var anchorToSave: ParkingAnchor? = null
        var startRequest: StartPursuitRequest? = null
        var updateRequests: List<UpdatePursuitRequest> = emptyList()
        var currentGen = 0L

        stateMutex.withLock {
            if (!currentState.isPursuitEligible()) {
                return
            }

            val sid = activeSessionId ?: return
            val nowElapsed = elapsedClockMs()
            currentGen = lifecycleGeneration.get()

            if (!anchorSet) {
                if (displacementPolicy.isUsableFix(fix, nowElapsed)) {
                    anchorToSave = ParkingAnchor(fix, sid)
                }
            } else {
                val eval = displacementPolicy.evaluate(fix, nowElapsed)
                if (eval is MovementDecision.Confirmed && !pursuitAttempted) {
                    pursuitAttempted = true
                    val nowWall = wallClockMs()
                    val expiresAtMs = nowWall + 900_000L
                    startRequest = StartPursuitRequest(
                        fix = fix,
                        sid = sid,
                        generation = currentGen,
                        expiresAtMs = expiresAtMs
                    )
                } else if (activeHandles.isNotEmpty() && displacementPolicy.isUsableFix(fix, nowElapsed)) {
                    val eligibleUpdates = mutableListOf<UpdatePursuitRequest>()
                    for (handle in activeHandles) {
                        val pubState = handlePublicationStates[handle] ?: HandlePublicationState(handle, fix)
                        val lastFix = pubState.lastSuccessfulFix
                        val timeSinceSuccess = fix.elapsedRealtimeMs - lastFix.elapsedRealtimeMs
                        val dist = MovementDisplacementPolicy.calculateHaversineDistance(
                            lastFix.latitude, lastFix.longitude,
                            fix.latitude, fix.longitude
                        )
                        val normalGateOpen = timeSinceSuccess >= 10_000L || dist >= 10.0
                        val attemptFloorPassed = (nowElapsed - pubState.lastAttemptElapsedMs) >= 5_000L
                        val retryFloorPassed = nowElapsed >= pubState.retryNotBeforeElapsedMs

                        if (normalGateOpen && attemptFloorPassed && retryFloorPassed) {
                            handlePublicationStates[handle] = pubState.copy(lastAttemptElapsedMs = nowElapsed)
                            eligibleUpdates.add(
                                UpdatePursuitRequest(
                                    handle = handle,
                                    fix = fix,
                                    sid = sid,
                                    generation = currentGen,
                                )
                            )
                        }
                    }
                    updateRequests = eligibleUpdates
                }
            }
        }

        // Side effects outside stateMutex:
        if (anchorToSave != null) {
            val anchor = anchorToSave
            val saved = safeStoreSave(MovementTrackingState(anchor = anchor, session = null))
            if (saved) {
                stateMutex.withLock {
                    if (currentState.isPursuitEligible() &&
                        activeSessionId == anchor.armedSessionId &&
                        lifecycleGeneration.get() == currentGen &&
                        !anchorSet
                    ) {
                        currentAnchor = anchor
                        displacementPolicy.reset(anchor)
                        anchorSet = true
                    }
                }
            }
            return
        }

        startRequest?.let { req ->
            executePursuitStart(req)
        }

        if (updateRequests.isNotEmpty()) {
            executePursuitUpdates(updateRequests)
        }
    }

    private suspend fun executePursuitStart(req: StartPursuitRequest) {
        val emptySession = PersistedLivePursuitSession(
            armedSessionId = req.sid,
            attemptedAtMs = req.expiresAtMs - 900_000L,
            expiresAtMs = req.expiresAtMs,
            handles = emptyList()
        )
        val saved = safeStoreSave(MovementTrackingState(anchor = currentAnchor, session = emptySession))
        if (!saved) {
            stateMutex.withLock {
                if (activeSessionId == req.sid && lifecycleGeneration.get() == req.generation) {
                    pursuitAttempted = false
                }
            }
            return
        }

        stateMutex.withLock {
            if (activeSessionId == req.sid && lifecycleGeneration.get() == req.generation) {
                currentSession = emptySession
            } else {
                return
            }
        }

        var handles: List<LiveLocationHandle> = emptyList()
        var enterPursuit = false
        var staleHandles: List<LiveLocationHandle> = emptyList()

        remoteMutex.withLock {
            val allowed = stateMutex.withLock {
                isCurrentAttemptLocked(req.sid, req.generation)
            }
            if (!allowed) return

            handles = transport.startForOwners(req.fix, 900)

            if (handles.isNotEmpty()) {
                val session = PersistedLivePursuitSession(
                    armedSessionId = req.sid,
                    attemptedAtMs = req.expiresAtMs - 900_000L,
                    expiresAtMs = req.expiresAtMs,
                    handles = handles
                )
                val sessionSaved = safeStoreSave(MovementTrackingState(anchor = currentAnchor, session = session))
                if (sessionSaved) {
                    stateMutex.withLock {
                        if (isCurrentAttemptLocked(req.sid, req.generation)) {
                            currentSession = session
                            activeHandles = handles
                            for (h in handles) {
                                handlePublicationStates[h] = HandlePublicationState(
                                    handle = h,
                                    lastSuccessfulFix = req.fix,
                                    consecutiveRetryableFailures = 0,
                                    retryNotBeforeElapsedMs = 0L,
                                    lastAttemptElapsedMs = elapsedClockMs(),
                                )
                            }
                            val remainingMs = maxOf(0L, req.expiresAtMs - wallClockMs())
                            val gen = req.generation
                            expiryHandle = expiryScheduler.schedule(remainingMs) {
                                scope.launch { onExpiry(gen) }
                            }
                            enterPursuit = true
                        } else {
                            staleHandles = handles
                        }
                    }
                } else {
                    staleHandles = handles
                }
            }

            for (handle in staleHandles) {
                transport.stop(handle)
            }
        }

        if (enterPursuit) {
            locationTracking.enterPursuitMode()
        }

        if (handles.isNotEmpty() && enterPursuit) {
            scope.launch {
                val presentation = try {
                    presentationFactory.create(req.fix)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                remoteMutex.withLock {
                    val allowed = stateMutex.withLock {
                        isCurrentAttemptLocked(req.sid, req.generation) && activeHandles.isNotEmpty()
                    }
                    if (allowed) {
                        transport.alertOwners(formatMovementAlert(presentation))
                    }
                }
            }
        }
    }

    private suspend fun executePursuitUpdates(requests: List<UpdatePursuitRequest>) {
        remoteMutex.withLock {
            for (req in requests) {
                val allowed = stateMutex.withLock {
                    isCurrentAttemptLocked(req.sid, req.generation) && activeHandles.contains(req.handle)
                }
                if (!allowed) continue

                val result = transport.update(req.handle, req.fix)
                val nowElapsed = elapsedClockMs()
                var handleToRemove: LiveLocationHandle? = null
                var shouldExitPursuit = false

                stateMutex.withLock {
                    if (!isCurrentAttemptLocked(req.sid, req.generation)) return@withLock
                    when (result) {
                        is TelegramCallResult.Success -> {
                            handlePublicationStates[req.handle] = HandlePublicationState(
                                handle = req.handle,
                                lastSuccessfulFix = req.fix,
                                consecutiveRetryableFailures = 0,
                                retryNotBeforeElapsedMs = 0L,
                                lastAttemptElapsedMs = nowElapsed,
                            )
                        }
                        is TelegramCallResult.Retryable -> {
                            val prev = handlePublicationStates[req.handle]
                            val failures = (prev?.consecutiveRetryableFailures ?: 0) + 1
                            val expBackoffMs = (5_000L * (1L shl (failures - 1).coerceAtMost(4))).coerceIn(5_000L, 60_000L)
                            val serverDelayMs = result.retryAfterMs ?: 0L
                            val delayMs = maxOf(serverDelayMs, expBackoffMs, 5_000L)
                            val notBefore = nowElapsed + delayMs
                            handlePublicationStates[req.handle] = prev?.copy(
                                consecutiveRetryableFailures = failures,
                                retryNotBeforeElapsedMs = notBefore,
                                lastAttemptElapsedMs = nowElapsed,
                            ) ?: HandlePublicationState(
                                handle = req.handle,
                                lastSuccessfulFix = req.fix,
                                consecutiveRetryableFailures = failures,
                                retryNotBeforeElapsedMs = notBefore,
                                lastAttemptElapsedMs = nowElapsed,
                            )
                        }
                        is TelegramCallResult.Terminal -> {
                            activeHandles = activeHandles.filter { it != req.handle }
                            handlePublicationStates.remove(req.handle)
                            handleToRemove = req.handle
                            if (activeHandles.isEmpty()) {
                                shouldExitPursuit = true
                            }
                        }
                    }
                }

                if (handleToRemove != null) {
                    val remainingHandles = stateMutex.withLock { activeHandles }
                    val remainingSession = currentSession?.copy(handles = remainingHandles)
                    if (remainingSession != null) {
                        currentSession = remainingSession
                        safeStoreSave(MovementTrackingState(anchor = currentAnchor, session = remainingSession))
                    }
                }
                if (shouldExitPursuit) {
                    locationTracking.exitPursuitMode()
                }
            }
        }
    }

    private suspend fun onExpiry(generation: Long) {
        var handlesToStop: List<LiveLocationHandle> = emptyList()
        var shouldExitPursuit = false
        var sessionToSave: PersistedLivePursuitSession? = null

        stateMutex.withLock {
            if (lifecycleGeneration.get() == generation && currentState.isPursuitEligible()) {
                lifecycleGeneration.incrementAndGet()
                handlesToStop = activeHandles
                activeHandles = emptyList()
                handlePublicationStates.clear()
                expiryHandle = null
                shouldExitPursuit = true

                activeSessionId?.let { sid ->
                    val nowWall = wallClockMs()
                    val session = PersistedLivePursuitSession(
                        armedSessionId = sid,
                        attemptedAtMs = nowWall - 900_000L,
                        expiresAtMs = nowWall,
                        handles = emptyList()
                    )
                    currentSession = session
                    sessionToSave = session
                }
            }
        }

        if (sessionToSave != null) {
            safeStoreSave(MovementTrackingState(anchor = currentAnchor, session = sessionToSave))
        }

        if (shouldExitPursuit) {
            locationTracking.exitPursuitMode()
        }

        if (handlesToStop.isNotEmpty()) {
            val outcome = stopHandlesRemotely(handlesToStop)
            persistStopOutcome(outcome)
        }
    }

    private suspend fun stopHandlesRemotely(handles: List<LiveLocationHandle>): StopOutcome {
        val stopped = mutableListOf<LiveLocationHandle>()
        val retained = mutableListOf<LiveLocationHandle>()
        remoteMutex.withLock {
            for (handle in handles) {
                val res = transport.stop(handle)
                when (res) {
                    is TelegramCallResult.Success -> stopped.add(handle)
                    is TelegramCallResult.Retryable -> retained.add(handle)
                    is TelegramCallResult.Terminal -> {
                        if (res.code == TelegramFailureCode.MESSAGE_UNAVAILABLE) {
                            stopped.add(handle)
                        } else {
                            val nowWall = wallClockMs()
                            val expiresAt = currentSession?.expiresAtMs ?: 0L
                            if (nowWall < expiresAt) {
                                retained.add(handle)
                            } else {
                                stopped.add(handle)
                            }
                        }
                    }
                }
            }
        }
        return StopOutcome(stopped = stopped, retainedForRecovery = retained)
    }

    private suspend fun persistStopOutcome(outcome: StopOutcome) {
        val nowWall = wallClockMs()
        val validRetained = outcome.retainedForRecovery.filter {
            val expiresAt = currentSession?.expiresAtMs ?: 0L
            nowWall < expiresAt
        }
        val session = currentSession?.copy(handles = validRetained)
        if (session != null) {
            currentSession = session
            safeStoreSave(MovementTrackingState(anchor = currentAnchor, session = session))
        }
    }

    override suspend fun prepareForStop() {
        withTimeoutOrNull(10_000L) {
            abortLocal()
            fixIngress?.awaitClosed()
            fixIngress = null

            var handlesToStop: List<LiveLocationHandle> = emptyList()

            stateMutex.withLock {
                expiryHandle?.cancel()
                expiryHandle = null
                handlesToStop = activeHandles
                activeHandles = emptyList()
                handlePublicationStates.clear()
            }

            if (handlesToStop.isNotEmpty()) {
                val outcome = stopHandlesRemotely(handlesToStop)
                persistStopOutcome(outcome)
            } else {
                val session = currentSession?.copy(handles = emptyList())
                if (session != null) {
                    currentSession = session
                    safeStoreSave(MovementTrackingState(anchor = currentAnchor, session = session))
                }
            }
        }
    }

    override fun abortLocal() {
        lifecycleGeneration.incrementAndGet()
        locationTracking.stopTracking()
        fixIngress?.cancelPending()
    }

    override fun shutdown() {
        abortLocal()
        scope.launch {
            prepareForStop()
        }
    }
}
