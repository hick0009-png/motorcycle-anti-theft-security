package com.example.motorcycleantitheftsensor.protection

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

interface BlackBoxScheduler {
    val isShutdown: Boolean

    fun scheduleAtFixedRate(initialDelayMs: Long, periodMs: Long, task: () -> Unit)

    fun shutdownNow()
}

/**
 * One thread, and it is not the sensor thread. Everything the black box does to a disk
 * happens here.
 */
private class ExecutorBlackBoxScheduler(
    private val executor: ScheduledExecutorService,
) : BlackBoxScheduler {
    override val isShutdown: Boolean get() = executor.isShutdown

    override fun scheduleAtFixedRate(initialDelayMs: Long, periodMs: Long, task: () -> Unit) {
        executor.scheduleAtFixedRate(task, initialDelayMs, periodMs, TimeUnit.MILLISECONDS)
    }

    override fun shutdownNow() {
        executor.shutdownNow()
    }
}

/**
 * Writes the minute rows, and the rows that say what changed between them.
 *
 * The minute row is the whole point of the phase. It carries little information and it
 * carries it constantly, which is what turns its absence into information: a run of minutes
 * with no row, bracketed by rows on either side and no `stop` row between them, is this
 * app being killed by the phone. That question — was there protection at three in the
 * morning, or only the belief in it — has no other answer available, because every other
 * signal the app could send requires the app to still be alive to send it.
 *
 * A `stop` row is therefore load-bearing rather than tidy. Without it every ordinary
 * shutdown reads exactly like a kill.
 */
class BlackBoxRecorder(
    private val writer: BlackBoxWriter,
    private val elapsedMs: () -> Long,
    private val wallClockMs: () -> Long,
    private var scheduler: BlackBoxScheduler? = null,
    private val periodMs: Long = MINUTE_MS,
    /**
     * What the sensors delivered since the last minute row, taken and reset here rather than
     * accumulated anywhere else. Absent until the tap is wired, and absent for every row that
     * is not a minute row: a state row that drained the counters would take a minute's
     * evidence and file it under an arm.
     */
    private val sensors: (() -> BlackBoxSensorSummary)? = null,
    /**
     * Leaves the current state with the system, so that a kill can be described by the run
     * that was killed. Called on every minute row and nowhere else: republishing it on state
     * rows too would double the calls to buy a freshness no reader of an `X` row can use, and
     * the value of the blob is that a recent one always exists, not that the newest one does.
     */
    private val publishStateSummary: ((BlackBoxState, Long) -> Unit)? = null,
) {

    private var lastState: BlackBoxState = BlackBoxState.UNKNOWN
    private var started = false

    /** The last point at which the two clocks were known to agree. */
    private var anchorWallMs: Long = 0L
    private var anchorElapsedMs: Long = 0L

    /** Time rows written in the current hour, and when that hour began on the elapsed clock. */
    private var timeRowsThisHour = 0
    private var timeRowHourStartedAtMs = 0L
    private var timeRowsSuppressed = 0

    @Synchronized
    fun start(state: BlackBoxState) {
        if (started && scheduler?.isShutdown == false) return
        lastState = state
        reanchorClocks()
        timeRowHourStartedAtMs = elapsedMs()
        write(BlackBoxRowType.STATE, state, note = NOTE_START)
        if (scheduler == null || scheduler?.isShutdown == true) {
            scheduler = ExecutorBlackBoxScheduler(Executors.newSingleThreadScheduledExecutor())
        }
        scheduler?.scheduleAtFixedRate(periodMs, periodMs) { tick() }
        started = true
    }

    /**
     * Takes the newest state, and marks it only when something worth marking moved.
     *
     * Battery and charging change constantly and are already carried by every minute row, so
     * a state row for them would bury the four transitions a reader actually looks for.
     */
    @Synchronized
    fun observe(state: BlackBoxState) {
        val note = blackBoxStateChange(previous = lastState, current = state)
        lastState = state
        if (note != null) write(BlackBoxRowType.STATE, state, note = note)
    }

    @Synchronized
    fun stop(state: BlackBoxState? = null) {
        if (!started) return
        val closing = state ?: lastState
        lastState = closing
        write(BlackBoxRowType.STATE, closing, note = NOTE_STOP)
        scheduler?.shutdownNow()
        scheduler = null
        started = false
    }

    /**
     * Records that the wall clock was set, at the moment the system said so.
     *
     * The broadcast is what makes this worth having over reading the drift back out of two
     * neighbouring rows. Those rows are a minute apart, so a clock moved and moved back
     * between them nets to nothing and leaves no trace at all — and moving it back is what
     * somebody covering an hour would do. The broadcast arrives on the change itself.
     */
    @Synchronized
    fun noteClockChange(cause: String) {
        writeTimeRow("$NOTE_CLOCK:$cause")
    }

    @Synchronized
    private fun tick() {
        // Checked before the minute row so the anchor is repaired before anything is stamped
        // against it. This is the backstop for a clock that moved without announcing itself.
        val drift = (wallClockMs() - anchorWallMs) - (elapsedMs() - anchorElapsedMs)
        if (kotlin.math.abs(drift) > CLOCK_TOLERANCE_MS) writeTimeRow("$NOTE_CLOCK:$NOTE_CAUSE_DRIFT")

        val summary = runCatching { sensors?.invoke() }.getOrNull() ?: BlackBoxSensorSummary()
        val now = elapsedMs()
        write(BlackBoxRowType.MINUTE, lastState, note = "", sensors = summary)
        // After the row, not before: if only one of the two can happen this minute, the row on
        // disk is worth more than the blob, because the blob is only ever read to explain the
        // absence of rows.
        runCatching { publishStateSummary?.invoke(lastState, now) }
    }

    /**
     * Writes the new anchor and how far the clock moved to reach it, under an hourly ceiling.
     *
     * The ceiling is the lesson of the source mask flicker written down as a mechanism: a row
     * type meant to report that something is wrong must never be able to fill the day's file
     * and silence the record it belongs to. A device whose clock is being corrected in a loop
     * gets twelve rows an hour and then one line saying how many were held back.
     */
    private fun writeTimeRow(note: String) {
        val nowElapsed = elapsedMs()
        if (nowElapsed - timeRowHourStartedAtMs >= HOUR_MS) {
            if (timeRowsSuppressed > 0) {
                writeAnchorRow("$NOTE_CLOCK:$NOTE_CAUSE_CAPPED:$timeRowsSuppressed")
            }
            timeRowHourStartedAtMs = nowElapsed
            timeRowsThisHour = 0
            timeRowsSuppressed = 0
        }
        if (timeRowsThisHour >= MAX_TIME_ROWS_PER_HOUR) {
            timeRowsSuppressed += 1
            // Still re-anchored: dropping the row must not also drop the correction, or every
            // later timestamp stays measured against a frame we know is wrong.
            reanchorClocks()
            return
        }
        timeRowsThisHour += 1
        val drift = (wallClockMs() - anchorWallMs) - (nowElapsed - anchorElapsedMs)
        writeAnchorRow("$note:${drift / 1000L}")
    }

    private fun writeAnchorRow(note: String) {
        write(BlackBoxRowType.TIME, lastState, note = note)
        reanchorClocks()
    }

    private fun reanchorClocks() {
        anchorWallMs = wallClockMs()
        anchorElapsedMs = elapsedMs()
    }

    private fun write(
        type: BlackBoxRowType,
        state: BlackBoxState,
        note: String,
        sensors: BlackBoxSensorSummary = BlackBoxSensorSummary(),
    ) {
        writer.append(
            BlackBoxRow(
                type = type,
                // Both clocks, because neither is enough on its own: the elapsed clock matches
                // the sensor timestamps but resets at boot and names no hour, and the wall
                // clock names the hour the owner will ask about but jumps whenever the phone
                // syncs time.
                elapsedMs = elapsedMs(),
                wallMs = wallClockMs(),
                state = state,
                sensors = sensors,
                note = note,
            ),
        )
    }

    companion object {
        const val MINUTE_MS = 60_000L
        const val NOTE_START = "start"
        const val NOTE_STOP = "stop"
        const val NOTE_CLOCK = "clock"

        /** The system announced a change; the strongest kind of evidence available here. */
        const val NOTE_CAUSE_SET = "set"
        const val NOTE_CAUSE_TIMEZONE = "tz"

        /** Nobody announced anything and the clocks had drifted apart anyway. */
        const val NOTE_CAUSE_DRIFT = "drift"
        const val NOTE_CAUSE_CAPPED = "capped"

        /**
         * Well clear of scheduler jitter, which measured under a second across a full day on
         * the test phone. Anything past this is the clock being set, not the tick being late.
         */
        const val CLOCK_TOLERANCE_MS = 5_000L

        const val MAX_TIME_ROWS_PER_HOUR = 12
        private const val HOUR_MS = 60L * 60L * 1000L
    }
}

/**
 * What changed between two states, in the words an `S` row carries, or null if nothing did.
 *
 * Only the owner's decisions count: arm, disarm, mode, settings. The source mask is left out
 * on purpose, and it was left out after measuring rather than on taste — on the test phone it
 * flickers between healthy and stale about every five seconds as sensors cross the freshness
 * window, which is roughly thirty-five thousand state rows a day. That fills the day's file
 * ceiling in around fourteen hours, and a full file stops recording, so a row meant to say
 * "a sensor went quiet" would have silenced the record overnight — the one stretch it exists
 * to cover. The mask still rides on every minute row, which answers the same question at the
 * resolution this layer actually claims.
 */
internal fun blackBoxStateChange(previous: BlackBoxState, current: BlackBoxState): String? {
    val reasons = buildList {
        if (previous.armed != current.armed) add(if (current.armed) "arm" else "disarm")
        if (previous.mode != current.mode) add("mode")
        if (previous.configFingerprint != current.configFingerprint) add("config")
    }
    return reasons.takeIf { it.isNotEmpty() }?.joinToString("+")
}

/**
 * Turns the live protection snapshot into the few fields the black box keeps.
 *
 * Stateful only for the incident count, which the snapshot cannot give directly: it names
 * the last incident, not how many there have been. Counting distinct ids as they appear
 * makes the column something a reader can subtract across two rows to see what a minute held.
 */
class BlackBoxStateMapper {

    private var lastIncidentId: String? = null
    private var incidents: Int = 0

    fun map(snapshot: ProtectionSnapshot): BlackBoxState {
        val incidentId = snapshot.lastIncident?.id
        if (incidentId != null && incidentId != lastIncidentId) {
            lastIncidentId = incidentId
            incidents += 1
        }
        return BlackBoxState(
            armed = snapshot.state in ARMED_STATES,
            mode = snapshot.armedProfileSnapshot?.profile?.name ?: BlackBoxState.MODE_NONE,
            sourceMask = sourceMaskOf(snapshot),
            incidents = incidents,
            batteryPercent = snapshot.batteryLevelPercent,
            charging = snapshot.chargingState.chargingConnected,
            configFingerprint = snapshot.armedProfileSnapshot?.configurationFingerprint,
        )
    }

    /**
     * One bit per sensor kind, set while that kind is delivering fresh data.
     *
     * Hardware that exists but is not registered is left out on purpose: the question this
     * column answers is what was actually watching, not what the phone owns.
     *
     * The health map alone cannot answer it. `getEffectiveHealth` returns HEALTHY for a
     * source whose role is OFF, and the runtime publishes VIBRATION with `isRegistered = true`
     * on every start regardless of profile, so a kind the armed profile never registered
     * still reports itself well. Read straight, that put VIBRATION and LOCATION in the mask
     * for 564 of the 570 minutes of one Power Guard session on the test phone — while the
     * sample tap counted a flat 8.4 Hz from a single sensor and not one acceleration value
     * was written in the whole stretch. Two columns of the same row disagreeing is worse than
     * a blank column: a reader has no way to know which one lied.
     *
     * So the profile's own contract decides who is eligible to appear. `usedSensorKinds` is
     * the same definition the coordinator filters its degradation reasons through, which is
     * why arming was never fooled by this and only the record was.
     *
     * Disarmed there is no profile in force and nothing to filter by, so every kind stays
     * eligible. That case has its own untrustworthy edge — a controller that is not running
     * also answers HEALTHY — which belongs to `getEffectiveHealth` and is not fixed here.
     */
    private fun sourceMaskOf(snapshot: ProtectionSnapshot): Int {
        // The entry level is left to its default: both levels of the door watch leave all
        // five kinds in use, so the two answers cannot differ for the profile that has one.
        val eligible = snapshot.armedProfileSnapshot?.profile
            ?.let(ProtectionProfilePolicy::usedSensorKinds)
            ?: SensorKind.entries.toSet()
        var mask = 0
        SensorKind.entries.forEach { kind ->
            if (kind in eligible && snapshot.sensorHealth[kind]?.state == SensorHealthState.HEALTHY) {
                mask = mask or (1 shl kind.ordinal)
            }
        }
        return mask
    }

    private companion object {
        val ARMED_STATES = setOf(
            ProtectionState.ARMING,
            ProtectionState.ARMED_HEALTHY,
            ProtectionState.ARMED_DEGRADED,
            ProtectionState.ALERT_ACTIVE,
        )
    }
}
