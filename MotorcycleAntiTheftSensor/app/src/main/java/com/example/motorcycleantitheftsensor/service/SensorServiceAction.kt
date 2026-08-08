package com.example.motorcycleantitheftsensor.service

enum class SensorServiceAction {
    Arm,
    Disarm,
    Start,
    Stop,
    Ignore;

    companion object {
        fun from(action: String?): SensorServiceAction = when (action) {
            SensorService.ACTION_ARM -> Arm
            SensorService.ACTION_DISARM -> Disarm
            SensorService.ACTION_START_SERVICE -> Start
            SensorService.ACTION_STOP_SERVICE -> Stop
            else -> Ignore
        }
    }
}
