package com.example.motorcycleantitheftsensor.protection

data class SensorObservation(
    val kind: SensorKind = SensorKind.VIBRATION,
    val source: SensorSource? = null,
    val capability: SensorCapability? = null,
    val role: SensorRole? = null,
    val generationId: Long = 0L,
    val unit: SensorUnit = SensorUnit.METERS_PER_SECOND_SQUARED,
    val eventElapsedMs: Long,
    val wallClockMs: Long,
    val normalizedValue: Double,
    val baselineDelta: Double,
    val valid: Boolean,
    val diagnostic: String? = null,
    val diagnosticCode: SensorDiagnosticCode? = null,
    val audioThreat: AudioThreatMetadata? = null,
    /**
     * Set on a door verdict whose own orientation stream turned fast enough to prove something
     * physically moved, which is corroboration the accelerometer cannot supply for a door.
     *
     * A door on its hinge rotates the phone without accelerating it: a smooth opening reads as
     * little more than gravity, so the movement detector never fires and a watch that demanded a
     * shake stayed silent through every real opening. Rotation *rate* separates the two cases the
     * shake was there to separate — drift crawls at thousandths of a degree a second and a door
     * turns thousands of times faster — so the door watch answers its own corroboration question
     * with the one quantity drift can never fake. See [EntryArmedSessionController].
     */
    val doorMotionCorroborated: Boolean = false,
)

data class SensorBaseline(
    val average: Double,
    val sampleCount: Int,
)

sealed interface ObservationDecision {
    data class Rejected(val reason: String) : ObservationDecision

    data object BaselineUpdated : ObservationDecision

    data object Debounced : ObservationDecision

    data class Accepted(val observation: SensorObservation) : ObservationDecision
}

/**
 * A live read of the residual and direction gates the door watch applies before it ever looks at
 * the angle. Reported to the owner's status command because those two gates decide, silently and
 * ahead of everything else, whether an opening is a door or a displaced mount.
 */
data class DoorGateReading(
    val swingResidualDeg: Double,
    val toleranceDeg: Double,
    val twistSignedDeg: Double,
)
