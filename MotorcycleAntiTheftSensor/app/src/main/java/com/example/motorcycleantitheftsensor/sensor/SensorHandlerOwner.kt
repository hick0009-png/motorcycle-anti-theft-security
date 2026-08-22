package com.example.motorcycleantitheftsensor.sensor

import android.os.Handler
import android.os.HandlerThread

class SensorHandlerOwner(threadName: String = "SensorAdapterThread") {
    private val handlerThread: HandlerThread = HandlerThread(threadName).apply { start() }
    val handler: Handler = Handler(handlerThread.looper)

    fun quit() {
        try {
            handlerThread.quitSafely()
        } catch (_: Exception) {
            handlerThread.quit()
        }
    }
}
