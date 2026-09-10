package com.example.motorcycleantitheftsensor.protection

import android.os.Build

/**
 * Device-side commissioning context for Entry Guard. The same values are baked into
 * the hinge model at commissioning time and compared at Arm time, so any change to
 * the sensor identity, mount signature, orientation-source policy, or algorithm
 * version forces recommissioning per spec section 5.
 */
object EntryCommissioningEnvironment {

    /**
     * The orientation-source policy for a phone that never told us what it uses.
     *
     * Kept as the game rotation vector's own string: a device that cannot answer is far
     * more likely to be one that simply was not asked than one that fell back, and
     * assuming otherwise would decommission working models for nothing.
     */
    const val ORIENTATION_SOURCE_POLICY: String =
        "game-rotation-vector-primary-v1"

    /**
     * Identity of the *light* sensor's commissioning environment, used by the Power Guard
     * witness flow.
     *
     * It spells a rotation vector for historical reasons — Power Guard borrowed Entry's
     * helper — and it is left exactly as it was on purpose: the string is a per-device
     * constant there, and changing it would decommission every witness baseline an owner
     * has already calibrated. Entry no longer uses it; see [orientationIdentity].
     */
    fun sensorIdentity(): String = "game-rotation-vector/${Build.MANUFACTURER}/${Build.MODEL}"

    /**
     * Identity of the rotation source an armed session actually listens to.
     *
     * The runtime registers the best source the phone has and may fall back twice. Naming
     * the source it really got is what makes a change of source force a recommissioning,
     * which is the whole promise of the fingerprint. A null source means the runtime could
     * not say, and then the historical string stands unchanged.
     */
    fun orientationIdentity(source: EntryOrientationSource?): String =
        EntryOrientationSourcePolicy.identity(
            source ?: EntryOrientationSource.GAME_ROTATION_VECTOR,
            // Interpolated, not passed through: off a device these are null, and the string
            // this produces has to stay character-for-character what the hardcoded one
            // produced or every commissioned model is invalidated on the next arm.
            manufacturer = "${Build.MANUFACTURER}",
            model = "${Build.MODEL}",
        )

    /** The orientation-source policy string for the source actually in use. */
    fun orientationSourcePolicy(source: EntryOrientationSource?): String =
        source?.let { EntryOrientationSourcePolicy.sourcePolicy(it) } ?: ORIENTATION_SOURCE_POLICY

    fun currentContext(
        entryUseContinuous: Boolean,
        source: EntryOrientationSource? = null,
    ): EntryCommissioningPolicy.CommissioningContext =
        EntryCommissioningPolicy.CommissioningContext(
            sensorIdentity = orientationIdentity(source),
            orientationSourcePolicy = orientationSourcePolicy(source),
            algorithmVersion = EntryCommissioningPolicy.ALGORITHM_VERSION,
            entryUseContinuous = entryUseContinuous,
            alertAngleDeg = 15,
            openConfirmationMs = 750L,
        )
}
