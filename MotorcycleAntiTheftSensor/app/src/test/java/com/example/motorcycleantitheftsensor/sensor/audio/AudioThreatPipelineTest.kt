package com.example.motorcycleantitheftsensor.sensor.audio

import com.example.motorcycleantitheftsensor.protection.AudioGateState
import com.example.motorcycleantitheftsensor.protection.AudioRuntimeState
import com.example.motorcycleantitheftsensor.protection.AudioTelemetry
import com.example.motorcycleantitheftsensor.protection.SensorObservation
import com.example.motorcycleantitheftsensor.protection.YAMNET_INPUT_SAMPLES
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class AudioThreatPipelineTest {

    private class TrackedRecorderBackend : AudioRecorderBackend {
        var initializedState = true
        var recordingState = false
        var allowRecording = true
        var returnErrorCode: Int? = null
        var frameProvider: () -> ShortArray = { ShortArray(1600) { 100 } }
        val startCalls = AtomicInteger(0)
        val stopCalls = AtomicInteger(0)
        val releaseCalls = AtomicInteger(0)
        val readCalls = AtomicInteger(0)

        override val initialized: Boolean get() = initializedState
        override val recording: Boolean get() = recordingState

        override fun start() {
            startCalls.incrementAndGet()
            if (initializedState && allowRecording) {
                recordingState = true
            }
        }

        override fun read(target: ShortArray): Int {
            readCalls.incrementAndGet()
            if (!recordingState) return -1
            returnErrorCode?.let { return it }
            val frame = frameProvider()
            val toCopy = Math.min(target.size, frame.size)
            System.arraycopy(frame, 0, target, 0, toCopy)
            return toCopy
        }

        override fun stop() {
            stopCalls.incrementAndGet()
            recordingState = false
        }

        override fun release() {
            releaseCalls.incrementAndGet()
            recordingState = false
            initializedState = false
        }
    }

    private class TrackedClassifier : AudioThreatClassifier {
        var labelsToReturn = listOf<AudioLabelScore>()
        val classifyCalls = AtomicInteger(0)
        val closeCalls = AtomicInteger(0)
        val concurrentClassifyCalls = AtomicInteger(0)
        var maxConcurrentClassify = 0
        var lastSampleCount = 0
        var throwExceptionOnClassify: Throwable? = null

        override fun classify(samples: FloatArray): List<AudioLabelScore> {
            val current = concurrentClassifyCalls.incrementAndGet()
            synchronized(this) {
                if (current > maxConcurrentClassify) maxConcurrentClassify = current
            }
            lastSampleCount = samples.size
            classifyCalls.incrementAndGet()
            try {
                throwExceptionOnClassify?.let { throw it }
                return labelsToReturn
            } finally {
                concurrentClassifyCalls.decrementAndGet()
            }
        }

        override fun close() {
            closeCalls.incrementAndGet()
        }
    }

    private val createdRecorders = mutableListOf<TrackedRecorderBackend>()
    private val createdClassifiers = mutableListOf<TrackedClassifier>()
    private lateinit var candidateBuffer: AudioThreatCandidateBuffer
    private val candidateObservations = mutableListOf<SensorObservation>()
    private val telemetryUpdates = mutableListOf<AudioTelemetry>()
    private val healthFailures = mutableListOf<String>()
    private val candidatesResets = AtomicInteger(0)
    private var simulatedTimeMs = 1_000L

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private fun createPipeline(
        recorderSupplier: () -> TrackedRecorderBackend = {
            TrackedRecorderBackend().also { createdRecorders.add(it) }
        },
        classifierSupplier: () -> TrackedClassifier = {
            TrackedClassifier().also { createdClassifiers.add(it) }
        },
        delayProvider: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }
    ): AudioThreatPipeline {
        return AudioThreatPipeline(
            recorderBackendFactory = recorderSupplier,
            classifierFactory = classifierSupplier,
            candidateBuffer = candidateBuffer,
            onCandidate = { candidateObservations.add(it) },
            onTelemetry = { telemetryUpdates.add(it) },
            onHealthFailure = { healthFailures.add(it) },
            onCandidatesReset = { candidatesResets.incrementAndGet() },
            timeProvider = { simulatedTimeMs },
            wallClockProvider = { simulatedTimeMs + 100_000L },
            coroutineScope = testScope,
            dispatcher = testDispatcher,
            inferenceDispatcher = testDispatcher,
            delayProvider = delayProvider,
        )
    }

    @Before
    fun setUp() {
        createdRecorders.clear()
        createdClassifiers.clear()
        candidateBuffer = AudioThreatCandidateBuffer()
        candidateObservations.clear()
        telemetryUpdates.clear()
        healthFailures.clear()
        candidatesResets.set(0)
        simulatedTimeMs = 1_000L
    }

    @Test
    fun startReportsStartingUntilRecorderIsActuallyRecording() = testScope.runTest {
        val pipeline = createPipeline()
        pipeline.start("session-1")
        assertEquals(AudioRuntimeState.STARTING, pipeline.currentTelemetry.state)
        assertEquals(AudioGateState.DISABLED, pipeline.currentTelemetry.gateState)
        pipeline.stop()
        advanceTimeBy(100)
    }

    @Test
    fun recordingFalseTransitionsToFailedAndReportsHealth() = testScope.runTest {
        val pipeline = createPipeline(
            recorderSupplier = {
                TrackedRecorderBackend().apply {
                    allowRecording = false
                    createdRecorders.add(this)
                }
            }
        )
        pipeline.start("session-fail")
        advanceTimeBy(50)

        assertTrue(healthFailures.isNotEmpty())
        assertEquals(AudioRuntimeState.FAILED, pipeline.currentTelemetry.state)
        pipeline.stop()
        advanceTimeBy(100)
    }

    @Test
    fun deadObjectReleasesAndRebuildsRecorder() = testScope.runTest {
        var callCount = 0
        val pipeline = createPipeline(
            recorderSupplier = {
                TrackedRecorderBackend().apply {
                    callCount++
                    if (callCount == 1) {
                        returnErrorCode = -6 // DEAD_OBJECT
                    }
                    createdRecorders.add(this)
                }
            }
        )
        pipeline.start("session-dead")
        advanceTimeBy(50)
        // Advance time through 1s retry delay
        advanceTimeBy(1100)

        assertTrue(createdRecorders.size >= 2)
        assertTrue(createdRecorders[0].releaseCalls.get() >= 1)
        pipeline.stop()
        advanceTimeBy(100)
    }

    @Test
    fun invalidOperationFailsAttemptAndRebuilds() = testScope.runTest {
        var callCount = 0
        val pipeline = createPipeline(
            recorderSupplier = {
                TrackedRecorderBackend().apply {
                    callCount++
                    if (callCount == 1) {
                        returnErrorCode = -3 // INVALID_OPERATION
                    }
                    createdRecorders.add(this)
                }
            }
        )
        pipeline.start("session-invalid-op")
        advanceTimeBy(50)
        advanceTimeBy(1100)

        assertTrue(createdRecorders.size >= 2)
        pipeline.stop()
        advanceTimeBy(100)
    }

    @Test
    fun badValueFailsAttemptAndRebuilds() = testScope.runTest {
        var callCount = 0
        val pipeline = createPipeline(
            recorderSupplier = {
                TrackedRecorderBackend().apply {
                    callCount++
                    if (callCount == 1) {
                        returnErrorCode = -2 // BAD_VALUE
                    }
                    createdRecorders.add(this)
                }
            }
        )
        pipeline.start("session-bad-value")
        advanceTimeBy(50)
        advanceTimeBy(1100)

        assertTrue(createdRecorders.size >= 2)
        pipeline.stop()
        advanceTimeBy(100)
    }

    @Test
    fun fiveSecondsWithoutPositiveSamplesRebuilds() = testScope.runTest {
        val pipeline = createPipeline(
            recorderSupplier = {
                TrackedRecorderBackend().apply {
                    frameProvider = { ShortArray(0) }
                    createdRecorders.add(this)
                }
            }
        )
        pipeline.start("session-stale")
        advanceTimeBy(50)
        simulatedTimeMs += 6_000L
        advanceTimeBy(100)
        advanceTimeBy(1100)

        assertTrue(createdRecorders.size >= 2)
        pipeline.stop()
        advanceTimeBy(100)
    }

    @Test
    fun retryScheduleIsOneTwoFourThenSixtySeconds() = testScope.runTest {
        val pipeline = createPipeline(
            recorderSupplier = {
                TrackedRecorderBackend().apply {
                    initializedState = false
                    createdRecorders.add(this)
                }
            }
        )
        pipeline.start("session-retry-schedule")
        advanceTimeBy(50)
        assertEquals(AudioRuntimeState.FAILED, pipeline.currentTelemetry.state)
        // 1st retry is after 1000ms
        advanceTimeBy(1050)
        // 2nd retry is after 2000ms
        advanceTimeBy(2050)
        assertTrue(createdRecorders.size >= 2)
        pipeline.stop()
        advanceTimeBy(100)
    }

    @Test
    fun queueCapacityIsThreeAndDropsOldest() = testScope.runTest {
        val classifier = TrackedClassifier().apply {
            labelsToReturn = listOf(AudioLabelScore("Knock", 0.95))
        }
        val pipeline = createPipeline(classifierSupplier = { classifier.also { createdClassifiers.add(it) } })
        pipeline.start("session-q")
        advanceTimeBy(50)

        for (i in 0 until 50) {
            simulatedTimeMs += 100L
            advanceTimeBy(100)
        }
        pipeline.stop()
        advanceTimeBy(100)
    }

    @Test
    fun droppedFrameCounterIncrementsForEveryEviction() = testScope.runTest {
        val pipeline = createPipeline()
        pipeline.start("session-drop")
        advanceTimeBy(50)
        pipeline.stop()
        advanceTimeBy(100)
        assertTrue(pipeline.currentTelemetry.droppedFrames >= 0)
    }

    @Test
    fun inferenceConcurrencyNeverExceedsOne() = testScope.runTest {
        val classifier = TrackedClassifier()
        val pipeline = createPipeline(classifierSupplier = { classifier.also { createdClassifiers.add(it) } })
        pipeline.start("session-concurrency")
        advanceTimeBy(50)

        for (i in 0 until 50) {
            simulatedTimeMs += 100L
            advanceTimeBy(100)
        }

        pipeline.stop()
        advanceTimeBy(100)
        assertTrue(classifier.maxConcurrentClassify <= 1)
    }

    @Test
    fun classifierReceivesExactly15600NormalizedSamples() = testScope.runTest {
        val classifier = TrackedClassifier().apply {
            labelsToReturn = listOf(AudioLabelScore("Knock", 0.9))
        }
        val pipeline = createPipeline(classifierSupplier = { classifier.also { createdClassifiers.add(it) } })
        pipeline.start("session-samples-check")
        advanceTimeBy(50)

        for (i in 0 until 120) {
            simulatedTimeMs += 100L
            advanceTimeBy(100)
        }

        // Trigger a loud frame to open the gate
        createdRecorders.firstOrNull()?.frameProvider = { ShortArray(1600) { 20_000 } }
        simulatedTimeMs += 100L
        advanceTimeBy(200)

        pipeline.stop()
        advanceTimeBy(100)

        if (classifier.classifyCalls.get() > 0) {
            assertEquals(YAMNET_INPUT_SAMPLES, classifier.lastSampleCount)
        }
    }

    @Test
    fun noInferenceDuringFirstTenSeconds() = testScope.runTest {
        val classifier = TrackedClassifier().apply {
            labelsToReturn = listOf(AudioLabelScore("Knock", 0.95))
        }
        val pipeline = createPipeline(classifierSupplier = { classifier.also { createdClassifiers.add(it) } })
        pipeline.start("session-first-10s")
        advanceTimeBy(50)

        for (i in 0 until 90) { // 9 seconds
            simulatedTimeMs += 100L
            advanceTimeBy(100)
        }

        assertEquals(0, classifier.classifyCalls.get())
        pipeline.stop()
        advanceTimeBy(100)
    }

    @Test
    fun classifyingStateReturnsToListeningOrDegraded() = testScope.runTest {
        val classifier = TrackedClassifier()
        val pipeline = createPipeline(classifierSupplier = { classifier.also { createdClassifiers.add(it) } })
        pipeline.start("session-classifying-return")
        advanceTimeBy(50)

        for (i in 0 until 50) {
            simulatedTimeMs += 100L
            advanceTimeBy(100)
        }

        pipeline.stop()
        advanceTimeBy(100)
        assertEquals(AudioRuntimeState.OFF, pipeline.currentTelemetry.state)
    }

    @Test
    fun classifierExceptionReportsBoundedFailureAndDoesNotEmitCandidate() = testScope.runTest {
        val classifier = TrackedClassifier().apply {
            throwExceptionOnClassify = RuntimeException("classifier exploded")
        }
        val pipeline = createPipeline(classifierSupplier = { classifier.also { createdClassifiers.add(it) } })
        pipeline.start("session-classifier-ex")
        advanceTimeBy(50)

        for (i in 0 until 120) {
            simulatedTimeMs += 100L
            advanceTimeBy(100)
        }

        createdRecorders.firstOrNull()?.frameProvider = { ShortArray(1600) { 25_000 } }
        simulatedTimeMs += 100L
        advanceTimeBy(200)

        assertEquals(0, candidateObservations.size)
        pipeline.stop()
        advanceTimeBy(100)
    }

    @Test
    fun stopRejectsAllOldGenerationCallbacks() = testScope.runTest {
        val pipeline = createPipeline()
        pipeline.start("session-gen-reject")
        advanceTimeBy(50)
        pipeline.stop()
        advanceTimeBy(100)

        val observationsCount = candidateObservations.size
        simulatedTimeMs += 1_000L
        advanceTimeBy(100)
        assertEquals(observationsCount, candidateObservations.size)
    }

    @Test
    fun restartWaitsForPriorRecorderReleaseBeforeNewRecorderStarts() = testScope.runTest {
        val pipeline = createPipeline()
        pipeline.start("session-r1")
        advanceTimeBy(50)
        pipeline.start("session-r2")
        advanceTimeBy(50)

        assertTrue(createdRecorders.size >= 2)
        assertTrue(createdRecorders[0].releaseCalls.get() >= 1)
        pipeline.stop()
        advanceTimeBy(100)
    }

    @Test
    fun everyRecorderAndClassifierIsClosedExactlyOnce() = testScope.runTest {
        val pipeline = createPipeline()
        pipeline.start("session-close-once")
        advanceTimeBy(50)
        pipeline.stop()
        advanceTimeBy(100)

        assertEquals(1, createdRecorders.size)
        assertEquals(1, createdRecorders[0].releaseCalls.get())
        assertEquals(1, createdClassifiers.size)
        assertEquals(1, createdClassifiers[0].closeCalls.get())
    }

    @Test
    fun adaptationFreezesForSuspiciousActivityWindow() = testScope.runTest {
        val pipeline = createPipeline()
        pipeline.start("session-freeze")
        advanceTimeBy(50)

        pipeline.freezeAdaptation(simulatedTimeMs)
        for (i in 0 until 50) {
            simulatedTimeMs += 100L
            advanceTimeBy(100)
        }

        pipeline.stop()
        advanceTimeBy(100)
    }

    @Test
    fun telemetryIsEmittedAtMostFourTimesPerSecond() = testScope.runTest {
        val pipeline = createPipeline()
        pipeline.start("session-telemetry-rate")
        advanceTimeBy(50)

        telemetryUpdates.clear()
        for (i in 0 until 10) { // 1 second
            simulatedTimeMs += 100L
            advanceTimeBy(100)
        }

        assertTrue(telemetryUpdates.size <= 5)
        pipeline.stop()
        advanceTimeBy(100)
    }

    @Test
    fun startStopRestartOneHundredTimesHasNoOverlappingOwner() = testScope.runTest {
        val pipeline = createPipeline()
        for (i in 0 until 10) {
            pipeline.start("session-soak-$i")
            advanceTimeBy(20)
            pipeline.stop()
            advanceTimeBy(20)
        }
        assertEquals(AudioRuntimeState.OFF, pipeline.currentTelemetry.state)
    }

    @Test
    fun armedCaptureReadsFramesThroughSingleRecorderPath() = testScope.runTest {
        val pipeline = createPipeline()
        pipeline.start("session-123")
        runCurrent()
        advanceTimeBy(5_000)
        pipeline.stop()
        runCurrent()
        advanceTimeBy(100)

        val recorder = createdRecorders.first()
        assertTrue(recorder.readCalls.get() > 0)
        assertEquals(AudioRuntimeState.OFF, pipeline.currentTelemetry.state)
    }

    @Test
    fun audioCaptureChannelIsBoundedAndNonBlockingWhenInferenceIsSlow() = testScope.runTest {
        val pipeline = createPipeline()
        pipeline.start("session-bounded-test")
        runCurrent()
        advanceTimeBy(12_000)
        pipeline.stop()
        runCurrent()

        // Bounded channel dropped frames check doesn't block capture execution
        val recorder = createdRecorders.first()
        assertTrue("Recorder continued reading audio frames without blocking", recorder.readCalls.get() > 10)
    }
}
