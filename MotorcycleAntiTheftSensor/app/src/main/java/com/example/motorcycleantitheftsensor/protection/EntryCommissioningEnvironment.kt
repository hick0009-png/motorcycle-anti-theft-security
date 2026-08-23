package com.example.motorcycleantitheftsensor.protection

import android.os.Build

/**
 * Device-side commissioning context for Entry Guard. The same values are baked into
 * the hinge model at commissioning time and compared at Arm time, so any change to
 * the sensor identity, mount signature, orientation-source policy, or algorithm
 * version forces recommissioning per spec section 5.
 */
object EntryCommissioningEnvironment {

    /** Stable description of the orientation-source/fusion policy used for Entry. */
    const val ORIENTATION_SOURCE_POLICY: String = "game-rotation-vector-primary-v1"

    /** Identity of the rotation source the armed session listens to. */
    fun sensorIdentity(): String = "game-rotation-vector/${Build.MANUFACTURER}/${Build.MODEL}"

    /**
     * Mount signature placeholder. Gravity-based remount capture arrives with the
     * commissioning UI slice; until then this stays constant so Arm never
     * spuriously decommissions a valid model.
     */
    fun mountSignature(): String = "default-mount"

    fun currentContext(entryUseContinuous: Boolean): EntryCommissioningPolicy.CommissioningContext =
        EntryCommissioningPolicy.CommissioningContext(
            sensorIdentity = sensorIdentity(),
            mountSignature = mountSignature(),
            orientationSourcePolicy = ORIENTATION_SOURCE_POLICY,
            algorithmVersion = EntryCommissioningPolicy.ALGORITHM_VERSION,
            entryUseContinuous = entryUseContinuous,
            alertAngleDeg = 15,
            openConfirmationMs = 750L,
        )
}
