package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionStateTelegramNotifierTest {
    @Test
    fun generatesExpectedMessagesForStateTransitions() {
        val notifier = ProtectionStateTelegramNotifier()

        assertEquals(
            listOf("ℹ️ กำลังเปิดการป้องกัน รอการปรับเทียบเซนเซอร์"),
            notifier.messagesFor(ProtectionState.DISARMED_ONLINE, ProtectionState.ARMING)
        )
        assertEquals(
            listOf("ℹ️ กำลังเปิดการป้องกัน รอการปรับเทียบเซนเซอร์"),
            notifier.messagesFor(ProtectionState.SETUP_REQUIRED, ProtectionState.ARMING)
        )
        assertEquals(
            listOf("✅ การป้องกันทำงานปกติ"),
            notifier.messagesFor(ProtectionState.ARMING, ProtectionState.ARMED_HEALTHY)
        )
        // Location and microphone faults reach the owner like any other; see
        // aWarmingUpSensorNeverProducedAReasonToDrop for why they used not to.
        assertEquals(
            listOf(
                "⚠️ การป้องกันทำงานแบบจำกัด: " +
                    "ระบบระบุตำแหน่ง GPS ไม่พร้อมใช้งาน, ไมโครโฟนไม่พร้อมใช้งาน"
            ),
            notifier.messagesFor(ProtectionState.ARMING, ProtectionState.ARMED_DEGRADED, setOf("LOCATION not healthy", "MICROPHONE not healthy"))
        )
        // Hard failure reasons format in Thai
        assertEquals(
            listOf("⚠️ การป้องกันทำงานแบบจำกัด: เซนเซอร์แรงสั่นไม่พร้อมใช้งาน"),
            notifier.messagesFor(ProtectionState.ARMING, ProtectionState.ARMED_DEGRADED, setOf("VIBRATION not healthy"))
        )
        // Disarm from ARMING with failure degradation reasons reports failure
        assertEquals(
            listOf("⚠️ การเปิดระบบล้มเหลว: SENSOR_INIT_FAILED"),
            notifier.messagesFor(ProtectionState.ARMING, ProtectionState.DISARMED_ONLINE, setOf("SENSOR_INIT_FAILED"))
        )
        // Disarm from ARMING without degradation reasons reports normal disarm
        assertEquals(
            listOf("ℹ️ ปิดการป้องกันแล้ว"),
            notifier.messagesFor(ProtectionState.ARMING, ProtectionState.DISARMED_ONLINE)
        )
        assertEquals(
            listOf("ℹ️ ปิดการป้องกันแล้ว"),
            notifier.messagesFor(ProtectionState.ARMED_HEALTHY, ProtectionState.DISARMED_ONLINE)
        )
        assertEquals(
            listOf("ℹ️ ปิดการป้องกันแล้ว"),
            notifier.messagesFor(ProtectionState.ALERT_ACTIVE, ProtectionState.DISARMED_ONLINE)
        )
        assertEquals(
            emptyList<String>(),
            notifier.messagesFor(null, ProtectionState.DISARMED_ONLINE)
        )
        assertEquals(
            emptyList<String>(),
            notifier.messagesFor(ProtectionState.ARMED_HEALTHY, ProtectionState.ARMED_HEALTHY)
        )
        // Flapping between ARMED_HEALTHY and ARMED_DEGRADED must be silent to prevent message loops
        assertEquals(
            emptyList<String>(),
            notifier.messagesFor(ProtectionState.ARMED_HEALTHY, ProtectionState.ARMED_DEGRADED, setOf("TELEGRAM unreachable"))
        )
        assertEquals(
            emptyList<String>(),
            notifier.messagesFor(ProtectionState.ARMED_DEGRADED, ProtectionState.ARMED_HEALTHY)
        )
    }

    private fun context(
        profile: ProtectionProfile?,
        entryLevel: EntryWatchLevel? = null,
        switchingTo: ProtectionProfile? = null,
    ) = ProtectionModeContext(
        selectedProfile = profile,
        entryLevel = entryLevel,
        switchingTo = switchingTo,
    )

    private fun armedMessage(
        context: ProtectionModeContext?,
        reasons: Set<String> = emptySet(),
    ): String = ProtectionStateTelegramNotifier().messagesFor(
        previous = ProtectionState.ARMING,
        current = if (reasons.isEmpty()) ProtectionState.ARMED_HEALTHY else ProtectionState.ARMED_DEGRADED,
        degradationReasons = reasons,
        context = context,
    ).single()

    /**
     * The point of the change: the alert that says protection is running has to say what is
     * being protected. An owner with three modes set up cannot tell from "การป้องกันทำงานปกติ"
     * whether the mode that armed is the one they meant.
     */
    @Test
    fun armedHealthyNamesTheModeAndWhatItWatches() {
        assertEquals(
            "✅ การป้องกันทำงานปกติ\n" +
                "🛡️ โหมดยานพาหนะ\n" +
                "แจ้งเตือนเมื่อรถยนต์หรือรถจักรยานยนต์ถูกกระทบ ขยับ หรือเคลื่อนย้าย",
            armedMessage(context(ProtectionProfile.VEHICLE)),
        )
    }

    /**
     * The two door levels give completely different answers. An alert that named the mode
     * but not the level would promise an angle to the level that cannot measure one.
     */
    @Test
    fun entryLevelDecidesWhatTheAlertPromises() {
        val angle = armedMessage(context(ProtectionProfile.ENTRY, EntryWatchLevel.DOOR_ANGLE))
        val sound = armedMessage(context(ProtectionProfile.ENTRY, EntryWatchLevel.SOUND_AND_MOVEMENT))

        assertTrue(angle.contains(PresentationTextCatalog.entryLevelLabel(EntryWatchLevel.DOOR_ANGLE)))
        assertTrue(sound.contains(PresentationTextCatalog.entryLevelLabel(EntryWatchLevel.SOUND_AND_MOVEMENT)))
        // The angle level's promise is stated against a threshold in degrees; nothing at the
        // sound level measures one, so that promise must not appear there.
        assertTrue(angle.contains("มุมที่กำหนด"))
        assertFalse(sound.contains("มุมที่กำหนด"))
    }

    /**
     * At angle level the orientation verdict rides in as a vibration-kind observation, so
     * this reason means the angle stopped working, not that a shock sensor did.
     */
    @Test
    fun doorAngleModeNamesTheOrientationFaultNotVibration() {
        val message = armedMessage(
            context(ProtectionProfile.ENTRY, EntryWatchLevel.DOOR_ANGLE),
            reasons = setOf("VIBRATION not healthy"),
        )
        assertTrue(message.contains("เซนเซอร์ทิศทาง/มุมประตูไม่พร้อมใช้งาน"))
        assertFalse(message.contains("เซนเซอร์แรงสั่นไม่พร้อมใช้งาน"))
    }

    /** The lamp is the whole detector in this mode; "วัดแสง" hides which lamp is meant. */
    @Test
    fun powerModeNamesTheWitnessLampForALightFault() {
        val message = armedMessage(
            context(ProtectionProfile.POWER),
            reasons = setOf("LIGHT not healthy"),
        )
        assertTrue(message.contains("เซนเซอร์แสง (ไฟยืนยัน) ไม่พร้อมใช้งาน"))
    }

    /**
     * Mid-switch the old mode has stopped and the new one is not armed. Naming either as
     * the thing being watched would claim a watch that is not running.
     */
    @Test
    fun switchingModeSaysNothingIsWatching() {
        val message = ProtectionStateTelegramNotifier().messagesFor(
            previous = ProtectionState.ARMED_HEALTHY,
            current = ProtectionState.DISARMED_ONLINE,
            context = context(ProtectionProfile.VEHICLE, switchingTo = ProtectionProfile.POWER),
        ).single()

        assertTrue(message.contains("🔄 กำลังสลับโหมด: ยานพาหนะ → ไฟเลี้ยงจุดติดตั้ง"))
        assertTrue(message.contains("ระหว่างนี้ยังไม่มีการเฝ้า"))
        assertFalse(message.contains("🛡️"))
    }

    /** Arm and disarm carry the mode too; only the armed alert spends a line on the promise. */
    @Test
    fun armingAndDisarmNameTheMode() {
        val notifier = ProtectionStateTelegramNotifier()
        val vehicle = context(ProtectionProfile.VEHICLE)

        assertEquals(
            listOf("ℹ️ กำลังเปิดการป้องกัน รอการปรับเทียบเซนเซอร์\n🛡️ โหมดยานพาหนะ"),
            notifier.messagesFor(
                previous = ProtectionState.DISARMED_ONLINE,
                current = ProtectionState.ARMING,
                context = vehicle,
            ),
        )
        assertEquals(
            listOf("ℹ️ ปิดการป้องกันแล้ว\n🛡️ โหมดยานพาหนะ"),
            notifier.messagesFor(
                previous = ProtectionState.ARMED_HEALTHY,
                current = ProtectionState.DISARMED_ONLINE,
                context = vehicle,
            ),
        )
    }

    /**
     * The customer who upgraded from before modes existed and has never chosen one. Their
     * alerts stay word for word what they were: inferring the mode from whichever sensors
     * happen to be running would be a guess, and a guess in the line that names what is
     * being protected is worse than the silence it replaces.
     */
    @Test
    fun noSelectedProfileKeepsTheOldMessagesWordForWord() {
        val notifier = ProtectionStateTelegramNotifier()
        for (ctx in listOf(null, context(profile = null))) {
            assertEquals(
                listOf("✅ การป้องกันทำงานปกติ"),
                notifier.messagesFor(ProtectionState.ARMING, ProtectionState.ARMED_HEALTHY, context = ctx),
            )
            assertEquals(
                listOf("ℹ️ ปิดการป้องกันแล้ว"),
                notifier.messagesFor(ProtectionState.ARMED_HEALTHY, ProtectionState.DISARMED_ONLINE, context = ctx),
            )
        }
    }

    /**
     * Why nothing is filtered out any more.
     *
     * The dropped reasons were called transient warm-up — a GPS waiting for its first fix,
     * an audio classifier still finding its noise floor. Neither condition can produce a
     * reason at all: both report AVAILABLE, and `unhealthySensorReasons` writes a reason
     * only for UNAVAILABLE, STALE and FAILED. This test states that contract from the
     * producing side, so that a change making AVAILABLE reportable has to come past here.
     */
    @Test
    fun aWarmingUpSensorNeverProducedAReasonToDrop() {
        val warmingUp = mapOf(
            SensorKind.LOCATION to SensorHealth(SensorHealthState.AVAILABLE),
            SensorKind.MICROPHONE to SensorHealth(SensorHealthState.AVAILABLE),
        )
        assertEquals(
            emptySet<String>(),
            unhealthySensorReasons(warmingUp, SensorKind.entries.toSet()),
        )

        // The states that do reach the notifier: nothing about them is transient.
        for (state in listOf(
            SensorHealthState.UNAVAILABLE,
            SensorHealthState.STALE,
            SensorHealthState.FAILED,
        )) {
            assertEquals(
                setOf("MICROPHONE not healthy"),
                unhealthySensorReasons(
                    mapOf(SensorKind.MICROPHONE to SensorHealth(state)),
                    setOf(SensorKind.MICROPHONE),
                ),
            )
        }
    }

    /**
     * A denied location permission on the vehicle watch used to arrive as "✅ ทำงานปกติ".
     * The owner is still standing next to the vehicle at this moment, which is the only
     * moment the advice is cheap to act on.
     */
    @Test
    fun aDeniedPermissionIsToldAtTheMomentItCanStillBeFixed() {
        val message = armedMessage(
            context(ProtectionProfile.VEHICLE),
            reasons = setOf("LOCATION not healthy"),
        )
        assertTrue(message.startsWith("⚠️ การป้องกันทำงานแบบจำกัด: ระบบระบุตำแหน่ง GPS ไม่พร้อมใช้งาน"))
        assertFalse(message.contains("การป้องกันทำงานปกติ"))
    }

    /**
     * The test that keeps the fault from coming back. Every mode-dependent word in an alert
     * has to be the catalog's word, so a class that grows its own copy of the mode table
     * fails here rather than in a chat six months from now.
     */
    @Test
    fun everyModeWordComesFromTheCatalog() {
        val cases = listOf(
            ProtectionProfile.VEHICLE to null,
            ProtectionProfile.ENTRY to EntryWatchLevel.DOOR_ANGLE,
            ProtectionProfile.ENTRY to EntryWatchLevel.SOUND_AND_MOVEMENT,
            ProtectionProfile.POWER to null,
        )
        for ((profile, level) in cases) {
            val lines = armedMessage(context(profile, level)).lines()
            assertEquals(3, lines.size)
            assertEquals("🛡️ " + PresentationTextCatalog.modeLabel(profile, level), lines[1])
            assertEquals(PresentationTextCatalog.profilePromise(profile, level), lines[2])
        }
    }
}
