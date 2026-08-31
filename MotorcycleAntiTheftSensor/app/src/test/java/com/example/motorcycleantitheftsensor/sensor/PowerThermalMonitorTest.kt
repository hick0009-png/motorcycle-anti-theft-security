package com.example.motorcycleantitheftsensor.sensor

import android.os.BatteryManager
import com.example.motorcycleantitheftsensor.protection.ChargingState
import org.junit.Assert.assertEquals
import org.junit.Test

class PowerThermalMonitorTest {

    @Test
    fun resolveChargingState_pluggedAt100PercentWithNotChargingStatus_returnsFull() {
        // Huawei/OEM behavior: status = NOT_CHARGING (4) when plugged at 100% capacity
        val state = PowerThermalMonitor.resolveChargingState(
            rawStatus = BatteryManager.BATTERY_STATUS_NOT_CHARGING,
            plugged = BatteryManager.BATTERY_PLUGGED_USB,
            batteryPercent = 100,
        )
        assertEquals(ChargingState.FULL, state)
    }

    @Test
    fun resolveChargingState_pluggedAt96PercentWithNotChargingStatus_returnsFull() {
        val state = PowerThermalMonitor.resolveChargingState(
            rawStatus = BatteryManager.BATTERY_STATUS_NOT_CHARGING,
            plugged = BatteryManager.BATTERY_PLUGGED_AC,
            batteryPercent = 96,
        )
        assertEquals(ChargingState.FULL, state)
    }

    @Test
    fun resolveChargingState_pluggedAt50PercentWithNotChargingStatus_returnsCharging() {
        // Smart charge / bypass / trickle while plugged in
        val state = PowerThermalMonitor.resolveChargingState(
            rawStatus = BatteryManager.BATTERY_STATUS_NOT_CHARGING,
            plugged = BatteryManager.BATTERY_PLUGGED_USB,
            batteryPercent = 50,
        )
        assertEquals(ChargingState.CHARGING, state)
    }

    @Test
    fun resolveChargingState_pluggedWithChargingStatus_returnsCharging() {
        val state = PowerThermalMonitor.resolveChargingState(
            rawStatus = BatteryManager.BATTERY_STATUS_CHARGING,
            plugged = BatteryManager.BATTERY_PLUGGED_AC,
            batteryPercent = 45,
        )
        assertEquals(ChargingState.CHARGING, state)
    }

    @Test
    fun resolveChargingState_statusFullExplicit_returnsFull() {
        val state = PowerThermalMonitor.resolveChargingState(
            rawStatus = BatteryManager.BATTERY_STATUS_FULL,
            plugged = BatteryManager.BATTERY_PLUGGED_AC,
            batteryPercent = 100,
        )
        assertEquals(ChargingState.FULL, state)
    }

    @Test
    fun resolveChargingState_unpluggedDischarging_returnsDischarging() {
        val state = PowerThermalMonitor.resolveChargingState(
            rawStatus = BatteryManager.BATTERY_STATUS_DISCHARGING,
            plugged = 0,
            batteryPercent = 80,
        )
        assertEquals(ChargingState.DISCHARGING, state)
    }

    @Test
    fun resolveChargingState_unpluggedNotCharging_returnsNotCharging() {
        val state = PowerThermalMonitor.resolveChargingState(
            rawStatus = BatteryManager.BATTERY_STATUS_NOT_CHARGING,
            plugged = 0,
            batteryPercent = 80,
        )
        assertEquals(ChargingState.NOT_CHARGING, state)
    }

    @Test
    fun resolveChargingState_unpluggedFull_returnsNotCharging() {
        val state = PowerThermalMonitor.resolveChargingState(
            rawStatus = BatteryManager.BATTERY_STATUS_FULL,
            plugged = 0,
            batteryPercent = 100,
        )
        assertEquals(ChargingState.NOT_CHARGING, state)
    }
}
