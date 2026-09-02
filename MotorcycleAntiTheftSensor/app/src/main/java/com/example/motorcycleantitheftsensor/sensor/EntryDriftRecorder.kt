package com.example.motorcycleantitheftsensor.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.SystemClock
import com.example.motorcycleantitheftsensor.protection.EntryDriftSampler
import com.example.motorcycleantitheftsensor.protection.EntryOrientationSource
import com.example.motorcycleantitheftsensor.protection.EntryOrientationSourcePolicy
import com.example.motorcycleantitheftsensor.protection.EntryQuaternion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What a running (or finished) drift recording has produced so far. */
data class EntryDriftStatus(
    val recording: Boolean,
    val sensorName: String,
    val fileName: String,
    val rowCount: Int,
    val elapsedMs: Long,
    val maxTotalDeg: Double,
    val maxTwistDeg: Double,
)

/**
 * Records how far this phone's orientation reading walks while it sits still.
 *
 * A diagnostic, not a protection feature: it answers whether the door watch can hold a
 * frozen baseline overnight on this chipset, which decides whether the orientation
 * source can be trusted alone or has to be pinned by the compass. The numbers differ
 * enough between chipsets that the only honest way to know is to leave a phone on a
 * table and read the file afterwards.
 *
 * It registers the same sensor the door watch registers, with the same fallback order,
 * so the drift measured is the drift the door watch would actually suffer — and it
 * records which one it got, because the fallbacks do not drift alike.
 */
class EntryDriftRecorder(
    private val context: Context,
    private val sensorManager: SensorManager?,
    private val handler: Handler,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
) {
    private val statusState = MutableStateFlow<EntryDriftStatus?>(null)
    val status: StateFlow<EntryDriftStatus?> = statusState.asStateFlow()

    private var listener: SensorEventListener? = null
    private var sampler: EntryDriftSampler? = null
    private var file: File? = null
    private var startedAtMs: Long = 0L

    val isRecording: Boolean get() = listener != null

    private fun sensorTypeOf(source: EntryOrientationSource): Int = when (source) {
        EntryOrientationSource.GAME_ROTATION_VECTOR -> Sensor.TYPE_GAME_ROTATION_VECTOR
        EntryOrientationSource.ROTATION_VECTOR -> Sensor.TYPE_ROTATION_VECTOR
        EntryOrientationSource.GEOMAGNETIC_ROTATION_VECTOR -> Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR
    }

    @Synchronized
    fun start(): Boolean {
        if (listener != null) return true
        val manager = sensorManager ?: return false
        // The door watch's own order. Measuring a sensor it would not use would answer a
        // question nobody asked.
        val chosen = EntryOrientationSourcePolicy.choose(
            EntryOrientationSource.entries.filter { manager.getDefaultSensor(sensorTypeOf(it)) != null },
        )
        val sensor = chosen?.let { manager.getDefaultSensor(sensorTypeOf(it)) }
            ?: return false

        val target = newFile(sensor)
        val recorder = EntryDriftSampler()
        val started = elapsedMs()
        val eventListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.size < 4) return
                val row = recorder.onSample(
                    timestampMs = elapsedMs(),
                    quaternion = EntryQuaternion(
                        w = event.values[3].toDouble(),
                        x = event.values[0].toDouble(),
                        y = event.values[1].toDouble(),
                        z = event.values[2].toDouble(),
                    ),
                ) ?: return
                appendLine(target, EntryDriftSampler.formatRow(row))
                publish(recording = true, sensor = sensor, file = target, sampler = recorder)
            }

            // Recorded rather than ignored: a source that drops to unreliable explains a
            // jump in the numbers that would otherwise look like real drift.
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                appendLine(target, "# accuracy=$accuracy atMs=${elapsedMs() - started}")
            }
        }

        // Set before registering: the listener runs on its own thread and the first event
        // can arrive before this call returns.
        startedAtMs = started
        sampler = recorder
        file = target

        val registered = try {
            manager.registerListener(eventListener, sensor, SensorManager.SENSOR_DELAY_GAME, handler)
        } catch (_: RuntimeException) {
            false
        }
        if (!registered) {
            sampler = null
            file = null
            return false
        }

        listener = eventListener
        publish(recording = true, sensor = sensor, file = target, sampler = recorder)
        return true
    }

    @Synchronized
    fun stop() {
        val active = listener ?: return
        try {
            sensorManager?.unregisterListener(active)
        } catch (_: RuntimeException) {
            // Already detached; the file is closed either way.
        }
        listener = null
        file?.let { appendLine(it, "# stopped afterMs=${elapsedMs() - startedAtMs}") }
        statusState.value = statusState.value?.copy(recording = false)
    }

    private fun newFile(sensor: Sensor): File {
        val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(wallClockMs()))
        return File(directory, "entry-drift-$stamp.csv").apply {
            writeText(
                buildString {
                    appendLine("# entry-drift v1")
                    appendLine("# sensor=${sensor.name} type=${sensor.type} vendor=${sensor.vendor}")
                    appendLine("# startedWallMs=${wallClockMs()} axis=0,0,1")
                    appendLine(EntryDriftSampler.CSV_HEADER)
                },
            )
        }
    }

    private fun appendLine(target: File, line: String) {
        try {
            target.appendText(line + "\n")
        } catch (_: Exception) {
            // A failed write must not take the service down; the rows already on disk
            // still answer the question.
        }
    }

    private fun publish(recording: Boolean, sensor: Sensor, file: File, sampler: EntryDriftSampler) {
        statusState.value = EntryDriftStatus(
            recording = recording,
            sensorName = sensor.name,
            fileName = file.name,
            rowCount = sampler.rowCount,
            elapsedMs = elapsedMs() - startedAtMs,
            maxTotalDeg = sampler.maxTotalDeg,
            maxTwistDeg = sampler.maxTwistDeg,
        )
    }

    companion object {
        const val DIRECTORY = "diagnostics"
    }
}
