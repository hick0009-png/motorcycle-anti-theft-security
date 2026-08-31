package com.example.motorcycleantitheftsensor.sensor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import com.example.motorcycleantitheftsensor.protection.ChargingState
import com.example.motorcycleantitheftsensor.protection.SensorKind
import com.example.motorcycleantitheftsensor.protection.SensorObservation

data class PowerThermalStatus(
    val sourceAvailable: Boolean,
    val isRegistered: Boolean,
    val batteryLevelPercent: Int?,
    val temperatureCelsius: Float?,
    val chargingState: ChargingState,
    val observedAtWallClockMs: Long,
)

/**
 * SEN-03: PowerThermalMonitor
 * Reports battery level, measured temperature, and charger disconnection observations.
 */
class PowerThermalMonitor(
    private val context: Context,
    private val onObservation: (SensorObservation) -> Unit,
    private val onStatusChanged: (PowerThermalStatus) -> Unit = {},
) {

    private var isRegistered = false
    private var lastStatus: PowerThermalStatus? = null

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
                    val base = lastStatus ?: queryInitialStatus(context)
                    val currentStatus = base.copy(
                        chargingState = ChargingState.DISCHARGING,
                        observedAtWallClockMs = System.currentTimeMillis(),
                        isRegistered = isRegistered,
                    )
                    lastStatus = currentStatus
                    onStatusChanged(currentStatus)
                }

                Intent.ACTION_BATTERY_CHANGED -> {
                    val status = parseBatteryIntent(intent, isRegistered)
                    if (status.temperatureCelsius != null) {
                        onObservation(
                            observation(
                                normalizedValue = status.temperatureCelsius.toDouble(),
                                diagnostic = "temperature_celsius",
                                valid = true,
                            ),
                        )
                    }
                    if (status.batteryLevelPercent != null) {
                        onObservation(
                            observation(
                                normalizedValue = status.batteryLevelPercent.toDouble(),
                                diagnostic = "battery_level_percent",
                            ),
                        )
                    }
                    lastStatus = status
                    onStatusChanged(status)
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
        val stickyIntent = context.registerReceiver(powerReceiver, filter)
        isRegistered = true
        val status = if (stickyIntent != null) {
            parseBatteryIntent(stickyIntent, isRegistered = true)
        } else {
            queryInitialStatus(context).copy(isRegistered = true)
        }
        lastStatus = status
        onStatusChanged(status)
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
        val status = (lastStatus ?: queryInitialStatus(context)).copy(isRegistered = false)
        lastStatus = status
        onStatusChanged(status)
    }

    /**
     * Gets the current battery temperature in Celsius.
     */
    fun getCurrentBatteryTemperature(): Float {
        val status = queryInitialStatus(context)
        return status.temperatureCelsius ?: 0f
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

    companion object {
        private const val TAG = "PowerThermalMonitor"

        fun resolveChargingState(rawStatus: Int, plugged: Int, batteryPercent: Int?): ChargingState {
            val isPlugged = plugged > 0
            if (isPlugged) {
                return if (
                    rawStatus == BatteryManager.BATTERY_STATUS_FULL ||
                    batteryPercent != null && batteryPercent >= 95
                ) {
                    ChargingState.FULL
                } else {
                    ChargingState.CHARGING
                }
            }

            return when (rawStatus) {
                BatteryManager.BATTERY_STATUS_DISCHARGING -> ChargingState.DISCHARGING
                BatteryManager.BATTERY_STATUS_NOT_CHARGING,
                BatteryManager.BATTERY_STATUS_CHARGING,
                BatteryManager.BATTERY_STATUS_FULL -> ChargingState.NOT_CHARGING
                else -> ChargingState.UNKNOWN
            }
        }

        fun parseBatteryIntent(intent: Intent, isRegistered: Boolean): PowerThermalStatus {
            val rawTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            val tempCelsius = if (rawTemp != Int.MIN_VALUE) rawTemp / 10.0f else null
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val batteryPercent: Int? = if (level >= 0 && scale > 0) {
                ((level * 100) / scale).coerceIn(0, 100)
            } else null

            val rawStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            val chargingState = resolveChargingState(rawStatus, plugged, batteryPercent)

            return PowerThermalStatus(
                sourceAvailable = true,
                isRegistered = isRegistered,
                batteryLevelPercent = batteryPercent,
                temperatureCelsius = tempCelsius,
                chargingState = chargingState,
                observedAtWallClockMs = System.currentTimeMillis(),
            )
        }

        fun queryInitialStatus(context: Context): PowerThermalStatus {
            return try {
                val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                if (intent != null) {
                    parseBatteryIntent(intent, isRegistered = false)
                } else {
                    PowerThermalStatus(
                        sourceAvailable = true,
                        isRegistered = false,
                        batteryLevelPercent = null,
                        temperatureCelsius = null,
                        chargingState = ChargingState.UNKNOWN,
                        observedAtWallClockMs = System.currentTimeMillis(),
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to query initial battery intent", e)
                PowerThermalStatus(
                    sourceAvailable = true,
                    isRegistered = false,
                    batteryLevelPercent = null,
                    temperatureCelsius = null,
                    chargingState = ChargingState.UNKNOWN,
                    observedAtWallClockMs = System.currentTimeMillis(),
                )
            }
        }
    }
}
