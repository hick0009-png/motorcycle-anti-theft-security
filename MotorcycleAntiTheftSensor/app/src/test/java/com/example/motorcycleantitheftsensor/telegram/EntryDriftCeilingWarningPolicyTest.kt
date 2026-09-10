package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.protection.ArmedProfileSnapshot
import com.example.motorcycleantitheftsensor.protection.EntryArmedCalibrationSnapshot
import com.example.motorcycleantitheftsensor.protection.EntryDriftVerdict
import com.example.motorcycleantitheftsensor.protection.EntryModeFacts
import com.example.motorcycleantitheftsensor.protection.EntryProfileSettings
import com.example.motorcycleantitheftsensor.protection.EntryWatchLevel
import com.example.motorcycleantitheftsensor.protection.ProtectionModeContext
import com.example.motorcycleantitheftsensor.protection.ProtectionProfile
import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshot
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import com.example.motorcycleantitheftsensor.protection.SensorConfigurationPolicy
import com.example.motorcycleantitheftsensor.protection.SensorPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The warning an owner gets without asking, when the door watch they armed last night has
 * outlived the hours this phone measured itself good for.
 */
class EntryDriftCeilingWarningPolicyTest {

    private val nowMs = 1_700_000_000_000L
    private val config = SensorConfigurationPolicy().forPreset(SensorPreset.BALANCED, 1_000L)

    private fun armedSnapshot(
        sessionId: String = "session-1",
        armedForMs: Long = 9 * 3_600_000L,
        verdict: EntryDriftVerdict = EntryDriftVerdict.Limited(hoursToThreshold = 8.0),
        level: EntryWatchLevel = EntryWatchLevel.DOOR_ANGLE,
        profile: ProtectionProfile = ProtectionProfile.ENTRY,
    ): ProtectionSnapshot = ProtectionSnapshot.offline(nowMs - armedForMs).copy(
        state = ProtectionState.ARMED_HEALTHY,
        protectionActivatedAtMs = nowMs - armedForMs,
        armedProfileSnapshot = ArmedProfileSnapshot(
            armedSessionId = sessionId,
            profile = profile,
            resolvedPresetVersion = 1,
            effectiveConfiguration = config,
            configurationFingerprint = "fp",
            commissionedModelFingerprint = "model-fp",
            armedCalibrationSnapshot = EntryArmedCalibrationSnapshot(1L, "model-fp"),
            entryLevel = level.takeIf { profile == ProtectionProfile.ENTRY },
        ),
        modeContext = ProtectionModeContext(
            selectedProfile = profile,
            entryLevel = level.takeIf { profile == ProtectionProfile.ENTRY },
            modeFacts = EntryModeFacts(
                angleThresholdDegrees = EntryProfileSettings().angleThresholdDegrees,
                openConfirmationMs = 750L,
                closeThresholdDegrees = 3,
                closeConfirmationMs = 5_000L,
                hingeModelCommissioned = true,
                driftVerdict = verdict,
            ),
        ),
    )

    @Test
    fun ceilingWarningFiresOncePerArmedSession() {
        // Ten ticks of the fifteen-minute heartbeat inside one session. An owner woken
        // repeatedly by the same advice learns to ignore the app, not the drift.
        val snapshot = armedSnapshot()
        var warned: String? = null
        var sends = 0
        repeat(10) {
            EntryDriftCeilingWarningPolicy.evaluate(snapshot, nowMs, warned)?.let {
                sends++
                warned = EntryDriftCeilingWarningPolicy.sessionIdToRecord(snapshot)
            }
        }
        assertEquals(1, sends)
    }

    @Test
    fun ceilingWarningReturnsAfterReArm() {
        // /disarm then /arm mints a new session with a fresh reference angle and a fresh
        // ceiling, so it is warnable again — which is right, not a duplicate.
        val first = armedSnapshot(sessionId = "session-1")
        assertNotNull(EntryDriftCeilingWarningPolicy.evaluate(first, nowMs, null))

        val second = armedSnapshot(sessionId = "session-2")
        assertNotNull(EntryDriftCeilingWarningPolicy.evaluate(second, nowMs, "session-1"))
    }

    @Test
    fun ceilingWarningSurvivesProcessRestart() {
        // The session lasts a night; the process does not. Reading the warned session id
        // back from durable storage is the only thing that stops a second 03:00 message.
        val snapshot = armedSnapshot(sessionId = "session-1")
        val durable = EntryDriftCeilingWarningPolicy.sessionIdToRecord(snapshot)

        assertNull(EntryDriftCeilingWarningPolicy.evaluate(snapshot, nowMs, durable))
    }

    @Test
    fun ceilingWarningOnlyForLimitedVerdict() {
        // Trustworthy has no ceiling to cross, NotMeasured has no number to quote, and
        // Unusable was refused at Arm — a warning about it would describe nothing.
        val verdicts = listOf(
            EntryDriftVerdict.Limited(hoursToThreshold = 8.0) to true,
            EntryDriftVerdict.Trustworthy(hoursToThreshold = 200.0) to false,
            EntryDriftVerdict.Unusable(hoursToThreshold = 1.0) to false,
            EntryDriftVerdict.NotMeasured to false,
        )
        verdicts.forEach { (verdict, expected) ->
            val message = EntryDriftCeilingWarningPolicy.evaluate(
                armedSnapshot(verdict = verdict),
                nowMs,
                null,
            )
            assertEquals("verdict $verdict", expected, message != null)
        }
    }

    @Test
    fun aSessionStillInsideItsCeilingIsNotWarned() {
        val message = EntryDriftCeilingWarningPolicy.evaluate(
            armedSnapshot(armedForMs = 3 * 3_600_000L),
            nowMs,
            null,
        )
        assertNull(message)
    }

    @Test
    fun onlyTheAngleLevelOfTheDoorWatchHasACeiling() {
        // Nothing at the sound-and-movement level measures an angle, so no amount of
        // elapsed time makes its evidence drift.
        assertNull(
            EntryDriftCeilingWarningPolicy.evaluate(
                armedSnapshot(level = EntryWatchLevel.SOUND_AND_MOVEMENT),
                nowMs,
                null,
            ),
        )
        assertNull(
            EntryDriftCeilingWarningPolicy.evaluate(
                armedSnapshot(profile = ProtectionProfile.VEHICLE),
                nowMs,
                null,
            ),
        )
    }

    @Test
    fun theWarningSaysTheWatchIsStillRunning() {
        // An owner who reads this as "the watch has stopped" goes home. It has not: it is
        // running, and less trustworthy than it was.
        val message = requireNotNull(
            EntryDriftCeilingWarningPolicy.evaluate(armedSnapshot(), nowMs, null),
        )
        assertTrue(message, message.contains("ยังเฝ้าอยู่ตามปกติระหว่างนี้"))
        assertTrue(message, message.contains("ราว 8 ชั่วโมง"))
        assertTrue(message, message.contains("อาร์มมาแล้ว 9 ชั่วโมง"))
        assertTrue(message, message.contains("/disarm"))
    }

    @Test
    fun aDisarmedPhoneIsNeverWarned() {
        val disarmed = armedSnapshot().copy(
            state = ProtectionState.DISARMED_ONLINE,
            protectionActivatedAtMs = null,
        )
        assertNull(EntryDriftCeilingWarningPolicy.evaluate(disarmed, nowMs, null))
    }
}
