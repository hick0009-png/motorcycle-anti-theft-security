package com.example.motorcycleantitheftsensor.protection

/**
 * The sensors a door angle can be read from, and the order the door watch takes them in.
 *
 * They are not interchangeable. The game rotation vector leaves the compass out by design,
 * so it never jumps when a steel door passes the phone — and pays for it by having nothing
 * to hold its heading, which is the drift the door watch has to survive. The other two are
 * pinned by the compass: steadier over hours, and disturbed by exactly the metal a door is
 * likely to be made of. Which one a phone ends up on therefore changes both how it drifts
 * and what a commissioned hinge model means, and that is why the choice is named here once
 * rather than being spelled out at each place that registers a listener.
 *
 * Which of those two costs to pay is the question [EntryOrientationSourcePolicy.PREFERENCE]
 * answers, and it answers it the same way for every phone.
 */
enum class EntryOrientationSource(
    /** The name that goes into a commissioning fingerprint. Never change one of these. */
    val label: String,
    /** Whether the fused heading is held by the compass, and so cannot walk away from it. */
    val compassPinned: Boolean,
) {
    GAME_ROTATION_VECTOR("game-rotation-vector", compassPinned = false),
    ROTATION_VECTOR("rotation-vector", compassPinned = true),
    GEOMAGNETIC_ROTATION_VECTOR("geomagnetic-rotation-vector", compassPinned = true),
}

/**
 * One place that decides which orientation source a phone will use, and what to call it.
 *
 * Two things went wrong for want of this. The armed watch fell back from the game rotation
 * vector to the plain one while the commissioning fingerprint went on saying
 * `game-rotation-vector` regardless — so a phone that had to fall back carried a model
 * commissioned against a sensor it was not using, and no change of source could ever force
 * a recommissioning. And the drift recorder, written later, accepted a third fallback the
 * armed watch did not, so on a phone with only the geomagnetic vector it would measure a
 * source the door watch would refuse to arm on.
 */
object EntryOrientationSourcePolicy {

    /**
     * Best first. The armed watch, the recorder and the fingerprint all read this list.
     *
     * A door on a vertical hinge turns about the gravity axis, so the only component of the
     * orientation that moves when it opens is the one component gravity cannot hold: yaw.
     * The game rotation vector is defined as the rotation vector *without* the geomagnetic
     * field, and its reference is documented as free to drift with the gyroscope's own drift
     * about that axis — which is to say it is the one source whose error lands squarely on
     * the one quantity this use measures. Leading with it made drift a permanent condition to
     * be measured, budgeted and refused around, rather than a property of a choice.
     *
     * The plain rotation vector is pinned by the compass and does not accumulate that error,
     * at the cost of noise near the steel a door is often made of. Bounded noise is the
     * cheaper failure: the residual gate and the movement corroboration already absorb it,
     * and only a sustained UNRELIABLE or NO_CONTACT complaint is treated as a lost source, so
     * an ordinarily low-accuracy compass keeps the watch running instead of silencing it.
     *
     * The game vector stays as the fallback for a phone that has no fused rotation vector,
     * and the drift budget stays in place for it. The geomagnetic vector remains last: it has
     * no gyroscope behind it, so it answers slowly and noisily even though it does not drift.
     */
    val PREFERENCE: List<EntryOrientationSource> = listOf(
        EntryOrientationSource.ROTATION_VECTOR,
        EntryOrientationSource.GAME_ROTATION_VECTOR,
        EntryOrientationSource.GEOMAGNETIC_ROTATION_VECTOR,
    )

    /** The source this phone will actually use, or null when it has none of them. */
    fun choose(available: Collection<EntryOrientationSource>): EntryOrientationSource? =
        PREFERENCE.firstOrNull { it in available }

    /**
     * The fingerprint a commissioned model is compared against at arm time.
     *
     * [EntryOrientationSource.GAME_ROTATION_VECTOR] must keep producing the string the
     * hardcoded one produced, or every hinge model commissioned before this existed would
     * be invalidated on the next arm for no reason at all.
     */
    fun identity(
        source: EntryOrientationSource,
        manufacturer: String,
        model: String,
    ): String = "${source.label}/$manufacturer/$model"

    /** The orientation-source policy string, which changes when the source does. */
    fun sourcePolicy(source: EntryOrientationSource): String = "${source.label}-primary-v1"
}
