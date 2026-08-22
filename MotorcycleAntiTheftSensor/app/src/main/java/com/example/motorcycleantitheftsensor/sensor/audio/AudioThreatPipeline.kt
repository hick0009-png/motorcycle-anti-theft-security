package com.example.motorcycleantitheftsensor.sensor.audio

import android.os.SystemClock
import com.example.motorcycleantitheftsensor.protection.AUDIO_SAMPLE_RATE_HZ
import com.example.motorcycleantitheftsensor.protection.AudioGateState
import com.example.motorcycleantitheftsensor.protection.AudioRuntimeState
import com.example.motorcycleantitheftsensor.protection.AudioTelemetry
import com.example.motorcycleantitheftsensor.protection.AudioThreatMetadata
import com.example.motorcycleantitheftsensor.protection.SensorKind
import com.example.motorcycleantitheftsensor.protection.SensorObservation
import com.example.motorcycleantitheftsensor.protection.YAMNET_INPUT_SAMPLES
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

interface AudioRecorderBackend {
    val initialized: Boolean
    val recording: Boolean
    fun start()
    fun read(target: ShortArray): Int
    fun stop()
    fun release()
}

class AudioThreatPipeline(
    private val recorderBackendFactory: () -> AudioRecorderBackend,
    private val classifierFactory: () -> AudioThreatClassifier,
    private val candidateBuffer: AudioThreatCandidateBuffer,
    private val calibrator: RobustAudioCalibrator = RobustAudioCalibrator(),
    private val signalGate: AudioSignalGate = AudioSignalGate(),
    private val labelMapper: AudioThreatLabelMapper = AudioThreatLabelMapper(),
    private val onCandidate: (SensorObservation) -> Unit,
    private val onTelemetry: (AudioTelemetry) -> Unit,
    private val onHealthFailure: (String) -> Unit = {},
    private val onCandidatesReset: () -> Unit = {},
    private val timeProvider: () -> Long = { SystemClock.elapsedRealtime() },
    private val wallClockProvider: () -> Long = { System.currentTimeMillis() },
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val inferenceDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val delayProvider: suspend (Long) -> Unit = { delay(it) },
) {
    companion object {
        fun createCaptureDispatcher(): CoroutineDispatcher =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "AudioCaptureThread").apply {
                    isDaemon = true
                }
            }.asCoroutineDispatcher()
    }

    private data class ActiveRun(
        val generation: Long,
        val sessionId: String,
    )

    private data class InferenceFrame(
        val samples: FloatArray,
        val onsetElapsedMs: Long,
        val capturedLevelDbfs: Double,
    )

    private var generationSequence = 0L
    @Volatile
    private var activeRun: ActiveRun? = null
    private var activeJob: Job? = null
    @Volatile
    private var activeBackend: AudioRecorderBackend? = null

    private val droppedFramesCounter = AtomicLong(0L)
    private val restartCounter = AtomicLong(0L)

    @Volatile
    private var inferenceTotalTimeMs = 0L
    @Volatile
    private var inferenceCount = 0L
    @Volatile
    private var lastInferenceMs: Long? = null
    @Volatile
    private var lastSampleAtMs: Long? = null
    @Volatile
    private var currentLevelDbfs: Double? = null
    @Volatile
    private var adaptationFrozenUntilMs = 0L

    @Volatile
    var currentTelemetry: AudioTelemetry = AudioTelemetry.off()
        private set

    fun freezeAdaptation(nowElapsedMs: Long) {
        adaptationFrozenUntilMs = max(adaptationFrozenUntilMs, nowElapsedMs + 30_000L)
    }

    private fun isCurrent(generation: Long, sessionId: String): Boolean =
        activeRun?.let { it.generation == generation && it.sessionId == sessionId } ?: false

    @Synchronized
    fun start(armedSessionId: String): Boolean {
        try {
            activeBackend?.stop()
        } catch (_: Exception) {}
        activeBackend = null

        generationSequence += 1
        val nextGen = generationSequence
        activeRun = ActiveRun(nextGen, armedSessionId)

        candidateBuffer.beginSession(armedSessionId)
        calibrator.reset()
        onCandidatesReset()

        updateTelemetry(
            state = AudioRuntimeState.STARTING,
            gateState = AudioGateState.DISABLED,
            modelReady = false,
        )

        val priorJob = activeJob
        activeJob = null
        priorJob?.cancel()

        activeJob = coroutineScope.launch(dispatcher) {
            runGeneration(nextGen, armedSessionId)
        }

        return true
    }

    @Synchronized
    fun stop() {
        activeRun = null
        try {
            activeBackend?.stop()
        } catch (_: Exception) {}
        activeBackend = null

        val job = activeJob
        activeJob = null
        job?.cancel()

        candidateBuffer.clear()
        calibrator.reset()
        onCandidatesReset()
        lastSampleAtMs = null
        currentLevelDbfs = null
        lastInferenceMs = null
        updateTelemetry(AudioRuntimeState.OFF, gateState = AudioGateState.DISABLED, modelReady = false)
    }

    private suspend fun runGeneration(generation: Long, sessionId: String) {
        val classifierInstance = try {
            classifierFactory()
        } catch (e: Exception) {
            if (isCurrent(generation, sessionId)) {
                updateTelemetry(AudioRuntimeState.FAILED, detailCode = "CLASSIFIER_INIT_EXCEPTION", modelReady = false)
                onHealthFailure("Failed to initialize audio classifier: ${e.message}")
            }
            return
        }

        val inferenceChannel = Channel<InferenceFrame>(capacity = 3)
        try {
            kotlinx.coroutines.coroutineScope {
                val inferenceJob = launch(inferenceDispatcher) {
                    runInferenceLoop(generation, sessionId, classifierInstance, inferenceChannel)
                }

                try {
                    runCaptureWithRecovery(generation, sessionId, inferenceChannel)
                } finally {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        inferenceChannel.close()
                        inferenceJob.cancel()
                        try {
                            inferenceJob.join()
                        } catch (_: Exception) {}
                    }
                }
            }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                try {
                    classifierInstance.close()
                } catch (_: Exception) {}
            }
        }
    }

    private suspend fun runCaptureWithRecovery(
        generation: Long,
        sessionId: String,
        inferenceChannel: Channel<InferenceFrame>,
    ) {
        var failureAttempt = 0

        while (isCurrent(generation, sessionId) && coroutineScope.isActive) {
            var backend: AudioRecorderBackend? = null
            var outcome: String? = null
            var initFailed = false
            try {
                try {
                    backend = recorderBackendFactory()
                    activeBackend = backend
                } catch (e: Exception) {
                    if (!isCurrent(generation, sessionId)) return
                    failureAttempt++
                    restartCounter.incrementAndGet()
                    onCandidatesReset()
                    updateTelemetry(AudioRuntimeState.FAILED, detailCode = "BACKEND_INIT_EXCEPTION", modelReady = true)
                    onHealthFailure("Failed to initialize recorder: ${e.message}")
                    delayProvider(getRetryDelayMs(failureAttempt))
                    continue
                }

                if (!backend.initialized) {
                    if (!isCurrent(generation, sessionId)) return
                    failureAttempt++
                    restartCounter.incrementAndGet()
                    onCandidatesReset()
                    updateTelemetry(AudioRuntimeState.FAILED, detailCode = "NOT_INITIALIZED", modelReady = true)
                    onHealthFailure("Audio recorder failed initialization")
                    initFailed = true
                } else {
                    try {
                        backend.start()
                    } catch (e: Exception) {
                        if (!isCurrent(generation, sessionId)) return
                        failureAttempt++
                        restartCounter.incrementAndGet()
                        onCandidatesReset()
                        updateTelemetry(AudioRuntimeState.FAILED, detailCode = "START_ERROR", modelReady = true)
                        onHealthFailure("Audio recorder start error: ${e.message}")
                        initFailed = true
                    }

                    if (!initFailed) {
                        if (!backend.recording) {
                            if (!isCurrent(generation, sessionId)) return
                            failureAttempt++
                            restartCounter.incrementAndGet()
                            onCandidatesReset()
                            updateTelemetry(AudioRuntimeState.FAILED, detailCode = "START_RECORDING_FAILED", modelReady = true)
                            onHealthFailure("Audio recorder is not recording")
                            initFailed = true
                        } else {
                            outcome = runActiveCaptureLoop(generation, sessionId, backend, inferenceChannel)
                        }
                    }
                }
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) {
                    if (activeBackend === backend) {
                        activeBackend = null
                    }
                    try {
                        backend?.stop()
                    } catch (_: Exception) {}
                    try {
                        backend?.release()
                    } catch (_: Exception) {}
                }
            }

            if (!isCurrent(generation, sessionId)) return

            if (initFailed) {
                delayProvider(getRetryDelayMs(failureAttempt))
                continue
            }

            if (outcome != null) {
                failureAttempt++
                restartCounter.incrementAndGet()
                onCandidatesReset()
                updateTelemetry(AudioRuntimeState.DEGRADED, detailCode = outcome, modelReady = true)
                onHealthFailure("Audio recorder error: $outcome")
                delayProvider(getRetryDelayMs(failureAttempt))
            }
        }
    }

    private suspend fun runActiveCaptureLoop(
        generation: Long,
        sessionId: String,
        backend: AudioRecorderBackend,
        inferenceChannel: Channel<InferenceFrame>,
    ): String? {
        val ringBuffer = ShortArray(YAMNET_INPUT_SAMPLES)
        var ringHead = 0
        val frameBuffer = ShortArray(1600) // 100ms at 16kHz

        val sessionStartTime = timeProvider()
        var lastPositiveSampleTime = sessionStartTime
        var currentState = AudioRuntimeState.CALIBRATING
        var currentGateState = AudioGateState.DISABLED
        var lastTelemetryTime = 0L
        var lastAdaptationTime = sessionStartTime

        while (isCurrent(generation, sessionId) && coroutineScope.isActive) {
            val now = timeProvider()
            val readSize = backend.read(frameBuffer)

            when {
                readSize > 0 -> {
                    lastPositiveSampleTime = now
                    lastSampleAtMs = wallClockProvider()

                    for (i in 0 until readSize) {
                        ringBuffer[(ringHead + i) % YAMNET_INPUT_SAMPLES] = frameBuffer[i]
                    }
                    ringHead = (ringHead + readSize) % YAMNET_INPUT_SAMPLES

                    val features = calibrator.extractFeatures(frameBuffer, readSize, now)
                    currentLevelDbfs = features.rmsDbfs
                    calibrator.feedFrame(features)

                    val elapsedSinceStart = now - sessionStartTime
                    val baseline = if (elapsedSinceStart < 10_000L) {
                        currentState = AudioRuntimeState.CALIBRATING
                        currentGateState = AudioGateState.DISABLED
                        null
                    } else if (currentState == AudioRuntimeState.CALIBRATING) {
                        val evaluated = calibrator.evaluateCalibration(now)
                        if (evaluated != null && evaluated.stable) {
                            currentState = AudioRuntimeState.LISTENING
                            currentGateState = AudioGateState.QUIET
                        } else if (elapsedSinceStart >= 30_000L) {
                            currentState = AudioRuntimeState.DEGRADED
                            currentGateState = AudioGateState.QUIET
                        }
                        evaluated
                    } else {
                        if (now - lastAdaptationTime >= 30_000L) {
                            lastAdaptationTime = now
                            val isFrozen = now < adaptationFrozenUntilMs
                            calibrator.adaptWindow(now, isActivityFrozen = isFrozen)
                        } else {
                            calibrator.evaluateCalibration(now)
                        }
                    }

                    if (currentState == AudioRuntimeState.LISTENING || currentState == AudioRuntimeState.DEGRADED) {
                        val decision = signalGate.evaluate(features, baseline)
                        if (decision is AudioGateDecision.Classify) {
                            currentGateState = AudioGateState.OPEN
                            val floatSamples = FloatArray(YAMNET_INPUT_SAMPLES)
                            for (i in 0 until YAMNET_INPUT_SAMPLES) {
                                val sample = ringBuffer[(ringHead + i) % YAMNET_INPUT_SAMPLES].toInt()
                                floatSamples[i] = (sample / 32768.0f).coerceIn(-1.0f, 1.0f)
                            }
                            val frame = InferenceFrame(
                                samples = floatSamples,
                                onsetElapsedMs = decision.onsetElapsedMs,
                                capturedLevelDbfs = features.rmsDbfs,
                            )
                            val offered = inferenceChannel.trySend(frame).isSuccess
                            if (!offered) {
                                inferenceChannel.tryReceive().getOrNull()
                                droppedFramesCounter.incrementAndGet()
                                inferenceChannel.trySend(frame)
                            }
                        } else {
                            currentGateState = AudioGateState.QUIET
                        }
                    }

                    if (now - lastTelemetryTime >= 250L) {
                        lastTelemetryTime = now
                        val activeCandidate = candidateBuffer.candidates(sessionId, now).maxByOrNull { it.confidence }
                        updateTelemetry(
                            state = currentState,
                            gateState = currentGateState,
                            detailCode = if (currentState == AudioRuntimeState.DEGRADED) "MICROPHONE noisy environment" else null,
                            modelReady = true,
                            baselineMedian = baseline?.medianDbfs,
                            baselineP95 = baseline?.p95Dbfs,
                            candidate = activeCandidate,
                        )
                    }
                }
                readSize == -6 -> return "DEAD_OBJECT"
                readSize == -3 -> return "INVALID_OPERATION"
                readSize == -2 -> return "BAD_VALUE"
                readSize == -1 -> return "AUDIO_RECORD_ERROR"
                readSize < 0 -> return "READ_ERROR_$readSize"
                now - lastPositiveSampleTime >= 5_000L -> return "STALE_MIC_DATA"
            }

            if (readSize <= 0) {
                delayProvider(100L)
            } else {
                delayProvider(10L)
            }
        }

        return null
    }

    private suspend fun runInferenceLoop(
        generation: Long,
        sessionId: String,
        classifier: AudioThreatClassifier,
        channel: Channel<InferenceFrame>,
    ) {
        for (frame in channel) {
            if (!isCurrent(generation, sessionId) || !coroutineScope.isActive) break

            val startInference = timeProvider()
            val nowWallClock = wallClockProvider()

            val priorState = currentTelemetry.state
            if (isCurrent(generation, sessionId)) {
                updateTelemetry(
                    state = AudioRuntimeState.CLASSIFYING,
                    gateState = AudioGateState.OPEN,
                    modelReady = true,
                    baselineMedian = currentTelemetry.baselineMedianDbfs,
                    baselineP95 = currentTelemetry.baselineP95Dbfs,
                    candidate = currentTelemetry.currentCandidate,
                )
            }

            val scores = try {
                classifier.classify(frame.samples)
            } catch (e: Exception) {
                onHealthFailure("Classifier exception: ${e.message}")
                emptyList()
            }

            val elapsedInference = timeProvider() - startInference
            lastInferenceMs = elapsedInference
            inferenceTotalTimeMs += elapsedInference
            inferenceCount++

            if (!isCurrent(generation, sessionId)) break

            val mapped = labelMapper.map(scores)
            for ((category, confidence) in mapped) {
                val baselineMedian = calibrator.evaluateCalibration(startInference)?.medianDbfs ?: -50.0
                val deltaDb = max(0.0, frame.capturedLevelDbfs - baselineMedian)

                val metadata = AudioThreatMetadata(
                    category = category,
                    confidence = confidence,
                    loudnessDeltaDb = deltaDb,
                    firstDetectedElapsedMs = startInference,
                    lastDetectedElapsedMs = startInference,
                    occurrenceCount = 1,
                    onsetElapsedMs = frame.onsetElapsedMs,
                )

                val qualified = candidateBuffer.record(sessionId, metadata)
                if (qualified != null && isCurrent(generation, sessionId)) {
                    freezeAdaptation(startInference)
                    onCandidate(
                        SensorObservation(
                            kind = SensorKind.MICROPHONE,
                            eventElapsedMs = startInference,
                            wallClockMs = nowWallClock,
                            normalizedValue = confidence,
                            baselineDelta = deltaDb,
                            valid = true,
                            diagnostic = "audio_threat_${category.name.lowercase()}",
                            audioThreat = qualified,
                        )
                    )
                }
            }

            if (isCurrent(generation, sessionId)) {
                val activeCandidate = candidateBuffer.candidates(sessionId, timeProvider()).maxByOrNull { it.confidence }
                updateTelemetry(
                    state = if (priorState == AudioRuntimeState.DEGRADED) AudioRuntimeState.DEGRADED else AudioRuntimeState.LISTENING,
                    gateState = AudioGateState.QUIET,
                    modelReady = true,
                    baselineMedian = currentTelemetry.baselineMedianDbfs,
                    baselineP95 = currentTelemetry.baselineP95Dbfs,
                    candidate = activeCandidate,
                )
            }
        }
    }

    private fun getRetryDelayMs(failureCount: Int): Long = when (failureCount) {
        1 -> 1_000L
        2 -> 2_000L
        3 -> 4_000L
        else -> 60_000L
    }

    private fun updateTelemetry(
        state: AudioRuntimeState,
        gateState: AudioGateState = AudioGateState.DISABLED,
        detailCode: String? = null,
        modelReady: Boolean = true,
        baselineMedian: Double? = null,
        baselineP95: Double? = null,
        candidate: AudioThreatMetadata? = null,
    ) {
        val avgInference = if (inferenceCount > 0) inferenceTotalTimeMs / inferenceCount else null
        val telemetry = AudioTelemetry(
            state = state,
            detailCode = detailCode,
            modelReady = modelReady,
            lastSampleAtMs = lastSampleAtMs,
            approximateLevelDbfs = currentLevelDbfs,
            baselineMedianDbfs = baselineMedian,
            baselineP95Dbfs = baselineP95,
            gateState = gateState,
            lastInferenceMs = lastInferenceMs,
            averageInferenceMs = avgInference,
            droppedFrames = droppedFramesCounter.get(),
            restartCount = restartCounter.get().toInt(),
            currentCandidate = candidate,
        )
        currentTelemetry = telemetry
        onTelemetry(telemetry)
    }
}
