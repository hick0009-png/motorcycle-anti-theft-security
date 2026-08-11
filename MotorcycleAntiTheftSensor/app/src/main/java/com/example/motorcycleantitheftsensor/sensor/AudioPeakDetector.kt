package com.example.motorcycleantitheftsensor.sensor

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.example.motorcycleantitheftsensor.protection.SensorKind
import com.example.motorcycleantitheftsensor.protection.SensorObservation
import java.util.concurrent.atomic.AtomicLong

/**
 * SEN-04: AudioPeakDetector
 * Reports normalized relative microphone amplitude using AudioRecord.
 */
class AudioPeakDetector(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val onObservation: (SensorObservation) -> Unit,
) {

    companion object {
        private const val SAMPLE_RATE = 8000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    @Volatile
    private var isRecording = false
    private val sessionEpoch = AtomicLong(0L)
    @Volatile
    private var recordingThread: Thread? = null

    @SuppressLint("MissingPermission")
    @Synchronized
    fun startListening(): Boolean {
        if (isRecording || recordingThread?.isAlive == true) return true

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBufferSize <= 0) return false

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            minBufferSize * 2
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord?.release()
            audioRecord = null
            return false
        }

        audioRecord?.startRecording()
        val epoch = sessionEpoch.incrementAndGet()
        isRecording = true

        val recorder = audioRecord ?: return false
        recordingThread = Thread {
            val buffer = ShortArray(minBufferSize)
            try {
                while (isRecording && epoch == sessionEpoch.get()) {
                    val readSize = recorder.read(buffer, 0, buffer.size)
                    if (readSize > 0) {
                        var sum = 0.0
                        for (i in 0 until readSize) {
                            sum += buffer[i] * buffer[i]
                        }
                        val amplitude = Math.sqrt(sum / readSize) / Short.MAX_VALUE.toDouble()
                        onObservation(
                            SensorObservation(
                                kind = SensorKind.MICROPHONE,
                                eventElapsedMs = SystemClock.elapsedRealtime(),
                                wallClockMs = System.currentTimeMillis(),
                                normalizedValue = amplitude.coerceIn(0.0, 1.0),
                                baselineDelta = 0.0,
                                valid = amplitude.isFinite(),
                                diagnostic = "relative_amplitude",
                            ),
                        )
                    }
                    Thread.sleep(200)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: IllegalStateException) {
                // stopListening can stop AudioRecord to unblock a pending read.
            } finally {
                recorder.release()
                synchronized(this@AudioPeakDetector) {
                    if (epoch == sessionEpoch.get()) isRecording = false
                    if (audioRecord === recorder) audioRecord = null
                    if (recordingThread === Thread.currentThread()) recordingThread = null
                }
            }
        }
        recordingThread?.start()
        return true
    }

    @Synchronized
    fun stopListening() {
        sessionEpoch.incrementAndGet()
        isRecording = false
        val worker = recordingThread
        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) {
            // The recorder may already have stopped on its worker thread.
        }
        worker?.interrupt()
        if (worker == null) {
            audioRecord?.release()
            audioRecord = null
        }
    }
}
