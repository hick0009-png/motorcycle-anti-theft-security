package com.example.motorcycleantitheftsensor.sensor

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.example.motorcycleantitheftsensor.protection.AUDIO_SAMPLE_RATE_HZ
import com.example.motorcycleantitheftsensor.protection.AudioTelemetry
import com.example.motorcycleantitheftsensor.protection.SensorObservation
import com.example.motorcycleantitheftsensor.sensor.audio.AudioRecorderBackend
import com.example.motorcycleantitheftsensor.sensor.audio.AudioThreatCandidateBuffer
import com.example.motorcycleantitheftsensor.sensor.audio.AudioThreatClassifier
import com.example.motorcycleantitheftsensor.sensor.audio.AudioThreatPipeline
import com.example.motorcycleantitheftsensor.sensor.audio.YamNetAudioThreatClassifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class AndroidAudioRecorderBackend(
    private val sampleRate: Int = AUDIO_SAMPLE_RATE_HZ,
) : AudioRecorderBackend {

    private val channel = AudioFormat.CHANNEL_IN_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT
    private val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
    private var audioRecord: AudioRecord? = null

    init {
        if (minBufferSize > 0) {
            try {
                @SuppressLint("MissingPermission")
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channel,
                    encoding,
                    minBufferSize * 2
                )
            } catch (_: Exception) {
                audioRecord = null
            }
        }
    }

    override val initialized: Boolean
        get() = audioRecord?.state == AudioRecord.STATE_INITIALIZED

    override val recording: Boolean
        get() = audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING

    @Synchronized
    override fun start() {
        try {
            val recorder = audioRecord
            if (recorder != null && recorder.state == AudioRecord.STATE_INITIALIZED && recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                recorder.startRecording()
            }
        } catch (_: Exception) {}
    }

    override fun read(target: ShortArray): Int {
        val recorder = audioRecord ?: return AudioRecord.ERROR_INVALID_OPERATION
        return recorder.read(
            target,
            0,
            target.size,
            AudioRecord.READ_BLOCKING,
        )
    }

    @Synchronized
    override fun stop() {
        try {
            val recorder = audioRecord
            if (recorder != null && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
        } catch (_: Exception) {}
    }

    @Synchronized
    override fun release() {
        try {
            val recorder = audioRecord
            if (recorder != null) {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    try {
                        recorder.stop()
                    } catch (_: Exception) {}
                }
                recorder.release()
            }
        } catch (_: Exception) {} finally {
            audioRecord = null
        }
    }
}

/**
 * SEN-04: AudioPeakDetector
 * Manages 16 kHz audio capture and integrates with AudioThreatPipeline.
 */
class AudioPeakDetector(
    private val context: Context,
    private val onObservation: (SensorObservation) -> Unit,
    classifierFactory: (() -> AudioThreatClassifier)? = null,
    candidateBuffer: AudioThreatCandidateBuffer? = null,
    onHealthFailure: (String) -> Unit = {},
    onCandidatesReset: () -> Unit = {},
    var onTelemetryChanged: ((AudioTelemetry) -> Unit)? = null,
) {
    private val _telemetry = MutableStateFlow(AudioTelemetry.off())
    val telemetry: StateFlow<AudioTelemetry> = _telemetry.asStateFlow()

    private val resolvedClassifierFactory: () -> AudioThreatClassifier =
        classifierFactory ?: { YamNetAudioThreatClassifier(context) }

    val candidateBuffer = candidateBuffer ?: AudioThreatCandidateBuffer()

    private val pipeline: AudioThreatPipeline by lazy {
        AudioThreatPipeline(
            recorderBackendFactory = { AndroidAudioRecorderBackend() },
            classifierFactory = resolvedClassifierFactory,
            candidateBuffer = this.candidateBuffer,
            onCandidate = onObservation,
            onTelemetry = {
                _telemetry.value = it
                onTelemetryChanged?.invoke(it)
            },
            onHealthFailure = onHealthFailure,
            onCandidatesReset = onCandidatesReset,
        )
    }

    fun startListening(armedSessionId: String = UUID.randomUUID().toString()): Boolean {
        return pipeline.start(armedSessionId)
    }

    fun stopListening() {
        pipeline.stop()
    }

    fun freezeAdaptation(nowElapsedMs: Long) {
        pipeline.freezeAdaptation(nowElapsedMs)
    }
}
