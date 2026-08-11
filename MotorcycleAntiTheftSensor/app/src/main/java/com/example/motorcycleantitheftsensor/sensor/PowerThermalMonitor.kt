package com.example.motorcycleantitheftsensor.sensor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import com.example.motorcycleantitheftsensor.protection.SensorKind
import com.example.motorcycleantitheftsensor.protection.SensorObservation

/**
 * SEN-03: PowerThermalMonitor
 * Reports battery level, measured temperature, and charger disconnection observations.
 */
class PowerThermalMonitor(
    private val context: Context,
    private val onObservation: (SensorObservation) -> Unit,
) {

    private var isRegistered = false

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent == null) return

            when (intent.action) {
                Intent.ACTION_POWER_DISCONNECTED -> {
                    onObservation(
                        observation(
                            normalizedValue = 1.0,
                            diagnostic = "charger_disconnected",
                        ),
                    )
                }

                Intent.ACTION_BATTERY_CHANGED -> {
                    val rawTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                    val tempCelsius = rawTemp / 10.0
                    onObservation(
                        observation(
                            normalizedValue = tempCelsius,
                            diagnostic = "temperature_celsius",
                            valid = rawTemp != 0,
                        ),
                    )
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level >= 0 && scale > 0) {
                        onObservation(
                            observation(
                                normalizedValue = level * 100.0 / scale,
                                diagnostic = "battery_level_percent",
                            ),
                        )
                    }
                }
            }
        }
    }

    fun startMonitoring(): Boolean {
        if (isRegistered) return true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        context.registerReceiver(powerReceiver, filter)
        isRegistered = true
        return true
    }

    fun stopMonitoring() {
        if (!isRegistered) return
        try {
            context.unregisterReceiver(powerReceiver)
        } catch (_: Exception) {
            Log.w(TAG, "Power/thermal receiver was already unavailable")
        }
        isRegistered = false
    }

    /**
     * Gets the current battery temperature in Celsius.
     */
    fun getCurrentBatteryTemperature(): Float {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val rawTemp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        return rawTemp / 10.0f
    }

    private fun observation(
        normalizedValue: Double,
        diagnostic: String,
        valid: Boolean = true,
    ): SensorObservation = SensorObservation(
        kind = SensorKind.POWER_THERMAL,
        eventElapsedMs = SystemClock.elapsedRealtime(),
        wallClockMs = System.currentTimeMillis(),
        normalizedValue = normalizedValue,
        baselineDelta = 0.0,
        valid = valid && normalizedValue.isFinite(),
        diagnostic = diagnostic,
    )

    private companion object {
        const val TAG = "PowerThermalMonitor"
    }
}
