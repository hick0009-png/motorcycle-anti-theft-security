package com.example.motorcycleantitheftsensor.telegram

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Keeps queue intake responsive while giving Disarm priority over an in-flight Arm reply. */
class PrioritizedCommandDispatcher<T>(
    private val scope: CoroutineScope,
    private val isArm: (T) -> Boolean,
    private val isDisarm: (T) -> Boolean,
    private val execute: suspend (T) -> Unit,
) {
    private var activeArm: Job? = null

    @Synchronized
    fun submit(command: T) {
        when {
            isArm(command) -> {
                activeArm?.cancel()
                activeArm = scope.launch {
                    execute(command)
                }
            }

            isDisarm(command) -> {
                activeArm?.cancel()
                activeArm = null
                scope.launch {
                    execute(command)
                }
            }

            else -> scope.launch { execute(command) }
        }
    }
}
