package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.protection.AudioRuntimeState
import com.example.motorcycleantitheftsensor.protection.ChargingState
import com.example.motorcycleantitheftsensor.protection.EntryDriftVerdict
import com.example.motorcycleantitheftsensor.protection.EntryModeFacts
import com.example.motorcycleantitheftsensor.protection.EntryWatchLevel
import com.example.motorcycleantitheftsensor.protection.LightHealthDetail
import com.example.motorcycleantitheftsensor.protection.LocationHealthDetail
import com.example.motorcycleantitheftsensor.protection.LocationTrackingState
import com.example.motorcycleantitheftsensor.protection.MicrophoneHealthDetail
import com.example.motorcycleantitheftsensor.protection.PowerModeFacts
import com.example.motorcycleantitheftsensor.protection.PowerThermalHealthDetail
import com.example.motorcycleantitheftsensor.protection.PresentationTextCatalog
import com.example.motorcycleantitheftsensor.protection.ProtectionModeContext
import com.example.motorcycleantitheftsensor.protection.ProtectionProfile
import com.example.motorcycleantitheftsensor.protection.ProtectionProfilePolicy
import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshot
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import com.example.motorcycleantitheftsensor.protection.SensorHealth
import com.example.motorcycleantitheftsensor.protection.SensorHealthState
import com.example.motorcycleantitheftsensor.protection.SensorKind
import com.example.motorcycleantitheftsensor.protection.SensorRole
import com.example.motorcycleantitheftsensor.protection.VehicleModeFacts
import com.example.motorcycleantitheftsensor.protection.VibrationHealthDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What `/status` is allowed to say once the owner has chosen what they are guarding.
 *
 * The report used to answer from one fixed template of five sensor kinds, so a Power Guard
 * owner was told their GPS had failed and shown two working sensors out of five — in a mode
 * that switches three of those five off on purpose and is at full health with two.
 */
class ProtectionStatusModeReportTest {

    private val formatter = ProtectionStatusFormatter()
    private val nowMs = 1_700_000_000_000L

    // region fixtures

    private fun healthOf(vararg kinds: SensorKind): Map<SensorKind, SensorHealth> =
        kinds.associateWith { kind ->
            when (kind) {
                SensorKind.VIBRATION -> SensorHealth(
                    state = SensorHealthState.HEALTHY,
                    vibrationDetail = VibrationHealthDetail(
                        isRegistered = true,
                        lastSampleElapsedMs = nowMs - 1_000L,
                    ),
                )
                SensorKind.LIGHT -> SensorHealth(
                    state = SensorHealthState.HEALTHY,
                    lightDetail = LightHealthDetail(
                        hardwareSupported = true,
                        isRegistered = true,
                        lastLux = 126.0,
                        lastSampleElapsedMs = nowMs - 2_000L,
                    ),
                )
                SensorKind.MICROPHONE -> SensorHealth(
                    state = SensorHealthState.HEALTHY,
                    microphoneDetail = MicrophoneHealthDetail(
                        audioState = AudioRuntimeState.LISTENING,
                        isRegistered = true,
                        modelReady = true,
                        lastAudioSampleElapsedMs = nowMs - 1_000L,
                    ),
                )
                SensorKind.LOCATION -> SensorHealth(
                    state = SensorHealthState.HEALTHY,
                    locationDetail = LocationHealthDetail(
                        trackingState = LocationTrackingState.TRACKING,
                        isRegistered = true,
                        lastFixWallClockMs = nowMs - 6_000L,
                        lastFixElapsedMs = nowMs - 6_000L,
                        accuracyMeters = 19.0f,
                    ),
                )
                SensorKind.POWER_THERMAL -> SensorHealth(
                    state = SensorHealthState.HEALTHY,
                    powerThermalDetail = PowerThermalHealthDetail(
                        sourceAvailable = true,
                        isRegistered = true,
                    ),
                )
            }
        }

    /**
     * @param sensorKinds deliberately defaults to only the kinds a mode uses. A mode that
     *   switches a sensor off never registers it, so its health map has no key for it —
     *   which is the exact condition that made the old report call it broken.
     */
    private fun armedSnapshot(
        context: ProtectionModeContext,
        armedForMs: Long = 6 * 3_600_000L,
        sensorKinds: Set<SensorKind> =
            context.usedSensorKinds() ?: SensorKind.entries.toSet(),
    ): ProtectionSnapshot = ProtectionSnapshot.offline(nowMs - armedForMs).copy(
        state = ProtectionState.ARMED_HEALTHY,
        protectionActivatedAtMs = nowMs - armedForMs,
        sensitivityLevel = 8,
        serviceRunning = true,
        lastServiceHeartbeatAtMs = nowMs - 1_000L,
        telegramPolling = true,
        telegramReachable = true,
        lastTelegramContactAtMs = nowMs - 3_000L,
        sensorHealth = healthOf(*sensorKinds.toTypedArray()),
        batteryLevelPercent = 78,
        batteryTemperatureCelsius = 30.2f,
        chargingState = ChargingState.CHARGING,
        modeContext = context,
    )

    private fun vehicleContext() = ProtectionModeContext(
        selectedProfile = ProtectionProfile.VEHICLE,
        modeFacts = VehicleModeFacts,
    )

    private fun powerContext() = ProtectionModeContext(
        selectedProfile = ProtectionProfile.POWER,
        modeFacts = PowerModeFacts(
            lossConfirmationMs = 10_000L,
            recoveryConfirmationMs = 10_000L,
            witnessCommissioned = true,
            witnessDarkThresholdLux = 42.0,
            witnessLitThresholdLux = 96.0,
            witnessCommissionedAtWallMs = nowMs - 4 * 86_400_000L,
        ),
    )

    private fun entryContext(
        level: EntryWatchLevel = EntryWatchLevel.DOOR_ANGLE,
        drift: EntryDriftVerdict = EntryDriftVerdict.Trustworthy(hoursToThreshold = 200.0),
    ) = ProtectionModeContext(
        selectedProfile = ProtectionProfile.ENTRY,
        entryLevel = level,
        modeFacts = EntryModeFacts(
            angleThresholdDegrees = 15,
            openConfirmationMs = 750L,
            closeThresholdDegrees = 3,
            closeConfirmationMs = 5_000L,
            hingeModelCommissioned = level == EntryWatchLevel.DOOR_ANGLE,
            hingeOrientationSourceLabel = "game-rotation-vector",
            hingeCommissionedAtWallMs = nowMs - 2 * 86_400_000L,
            driftVerdict = drift,
        ),
    )

    // endregion

    @Test
    fun powerModeNeverReportsGpsAsAFault() {
        // The mode registers no location listener at all, so the health map has no LOCATION
        // key. The old report read the missing key as a broken sensor and told the owner to
        // go and check the positioning system of a phone that is watching a lamp.
        val output = formatter.format(armedSnapshot(powerContext()), nowMs, nowMs)

        assertFalse("Power Guard must not mention GPS as a fault: $output", output.contains("❌ GPS"))
        assertFalse(output.contains("ตรวจสอบระบบพิกัดตำแหน่งของเครื่อง"))
        assertTrue(
            "The switched-off sensors must be named as deliberate: $output",
            output.contains("โหมดนี้ไม่ใช้:") && output.contains("ปิดไว้ตั้งใจ ไม่ใช่ความผิดปกติ"),
        )
    }

    @Test
    fun powerModeCountsOutOfTwo() {
        // Full health here is two of two. Reported as two of five, an owner reads it as
        // three fifths of their system having failed, and disarms something that works.
        val output = formatter.format(armedSnapshot(powerContext()), nowMs, nowMs)

        assertTrue("Expected a count out of two: $output", output.contains("ทำงาน 2/2"))
        assertFalse(output.contains("/5"))
    }

    @Test
    fun powerStatusReportsArmedScaledThresholdsNotCommissioned() {
        // Armed in daylight, the arbiter judges against the commissioned bands re-expressed
        // for the light present at Arm. /status must print that live boundary, not the frozen
        // commissioned pair the detector is no longer using — the exact mismatch that made a
        // "635 lux = ดับ" line meaningless while the running cutoff sat far higher.
        val base = armedSnapshot(powerContext())
        val light = base.sensorHealth.getValue(SensorKind.LIGHT)
        val armed = base.copy(
            sensorHealth = base.sensorHealth + (
                SensorKind.LIGHT to light.copy(
                    lightDetail = light.lightDetail!!.copy(
                        armedWitnessDarkThresholdLux = 1035.0,
                        armedWitnessLitThresholdLux = 4926.0,
                    ),
                )
            ),
        )
        val output = formatter.format(armed, nowMs, nowMs)

        assertTrue("Expected the armed/scaled dark cutoff: $output", output.contains("ต่ำกว่า 1035 lux = ดับ"))
        assertTrue("Expected the armed/scaled lit cutoff: $output", output.contains("สูงกว่า 4926 lux = สว่าง"))
        assertFalse("Commissioned 42 must not be shown while armed: $output", output.contains("ต่ำกว่า 42 lux"))
    }

    @Test
    fun powerThresholdTextPrefersArmedScaledPairOverCommissioned() {
        // Armed pair present: it wins.
        assertEquals(
            "เกณฑ์: ต่ำกว่า 1035 lux = ดับ · สูงกว่า 4926 lux = สว่าง",
            powerThresholdText(1035.0, 4926.0, 42.0, 96.0),
        )
        // Disarmed: no armed pair, the commissioned calibration is the honest answer.
        assertEquals(
            "เกณฑ์: ต่ำกว่า 42 lux = ดับ · สูงกว่า 96 lux = สว่าง",
            powerThresholdText(null, null, 42.0, 96.0),
        )
        // A lone armed value is treated as absent, never mixed with a commissioned partner.
        assertEquals(
            "เกณฑ์: ต่ำกว่า 42 lux = ดับ · สูงกว่า 96 lux = สว่าง",
            powerThresholdText(1035.0, null, 42.0, 96.0),
        )
        // Nothing calibrated at all.
        assertTrue(powerThresholdText(null, null, null, null).contains("ยังไม่ได้ปรับเทียบ"))
    }

    @Test
    fun entryDoorAngleModeNeverPrintsSensitivityScale() {
        // The 1-10 scale tunes the vibration detector. The door watch decides on degrees
        // and dwell time and never reads it, so printing it invites the owner to spend
        // /sensitivity on a control that changes nothing they are watching.
        val output = formatter.format(armedSnapshot(entryContext()), nowMs, nowMs)

        assertFalse("Door watch must not print the sensitivity scale: $output",
            output.contains("ความไวการตรวจจับ"))
        assertFalse(output.contains("[ 🏃 การเคลื่อนไหว ]"))
    }

    @Test
    fun entryLevelOneNeverPrintsAnAngleThreshold() {
        // Nothing at the sound-and-movement level measures an angle. A threshold in degrees
        // printed here is a promise no part of the running system can keep.
        val snapshot = armedSnapshot(entryContext(level = EntryWatchLevel.SOUND_AND_MOVEMENT))
        val projection = ProtectionStatusProjection.evaluate(snapshot, nowMs, nowMs)
        // The battery temperature is degrees Celsius and belongs to every mode, so the
        // claim is about what this mode says of the door, not about the character itself.
        val doorLines = projection.modeSections.flatMap { listOf(it.titleTh) + it.lines } +
            (projection.watchScope?.lines ?: emptyList())

        doorLines.forEach { line ->
            assertFalse("Level one must not state an angle: $line", line.contains("°"))
        }
        val output = formatter.format(snapshot, nowMs, nowMs)
        assertTrue(output.contains("ระดับการเฝ้า: เสียงและการขยับ"))
        assertTrue(output.contains("ปรับเทียบบานพับ"))
    }

    @Test
    fun entrySessionPastDriftCeilingRaisesAnIssue() {
        // Nine hours armed on a phone that measured itself trustworthy for eight. Every
        // alert from here may be the phone's own drift, and today the owner cannot know.
        val output = formatter.format(
            armedSnapshot(
                entryContext(drift = EntryDriftVerdict.Limited(hoursToThreshold = 8.0)),
                armedForMs = 9 * 3_600_000L + 40 * 60_000L,
            ),
            nowMs,
            nowMs,
        )

        assertTrue("Past the ceiling must raise an issue: $output", output.contains(EntryCeilingPolicy.ISSUE_TH))
        assertTrue(output.contains(EntryCeilingPolicy.GUIDANCE_TH))
        assertTrue(output.contains("เกินเพดานแล้ว"))
    }

    @Test
    fun anEntrySessionInsideItsCeilingRaisesNothing() {
        val output = formatter.format(
            armedSnapshot(
                entryContext(drift = EntryDriftVerdict.Limited(hoursToThreshold = 8.0)),
                armedForMs = 2 * 3_600_000L,
            ),
            nowMs,
            nowMs,
        )

        assertFalse(output.contains(EntryCeilingPolicy.ISSUE_TH))
        assertTrue("The ceiling is still stated before it is reached: $output",
            output.contains("⏳ เพดานเวลาที่เชื่อได้"))
    }

    @Test
    fun entryDoorAngleListsOrientationAsHostNotVibration() {
        // The host is an orientation verdict, which is no SensorKind and arrives as a
        // vibration-kind observation. Without its own row the owner reads a mode with no
        // host at all while raw vibration sits there marked as corroboration.
        val output = formatter.format(armedSnapshot(entryContext()), nowMs, nowMs)

        val orientation = output.indexOf("${ModeStatusSections.ORIENTATION_ROW_NAME} (หลัก)")
        val vibration = output.indexOf("การสั่น (ประกอบ)")
        assertTrue("Orientation must be listed as the host: $output", orientation >= 0)
        assertTrue("Vibration must stay corroboration: $output", vibration >= 0)
        assertTrue("Hosts are listed before corroboration", orientation < vibration)
    }

    @Test
    fun noSelectedProfileListsEverySensorAndSaysWhy() {
        // An upgraded customer who has never opened the picker. Guessing their mode from
        // whichever sensors happen to be running would be wrong exactly where they are
        // least able to check it, so nothing is filtered and the report says why.
        val snapshot = armedSnapshot(
            ProtectionModeContext(selectedProfile = null),
            sensorKinds = SensorKind.entries.toSet(),
        )
        val output = formatter.format(snapshot, nowMs, nowMs)

        assertTrue(output.contains("⚠️ ยังไม่ได้เลือกโหมดการใช้งาน"))
        assertTrue("Every sensor kind is still listed: $output",
            output.contains("🔎 เซ็นเซอร์ทั้งหมด: ทำงาน 5/5"))
        assertTrue(output.contains("✅ GPS: กำลังติดตาม"))
    }

    @Test
    fun switchingProfileSaysNothingIsWatching() {
        // Mid-switch the old mode has stopped and the new one is not armed. Naming either
        // as the current watch claims protection that is not running.
        val snapshot = armedSnapshot(
            vehicleContext().copy(switchingTo = ProtectionProfile.ENTRY),
        )
        val output = formatter.format(snapshot, nowMs, nowMs)

        assertTrue(output.contains("กำลังสลับโหมด: ยานพาหนะ → ประตูและทางเข้า"))
        assertTrue(output.contains("ระหว่างนี้ยังไม่มีการเฝ้า"))
    }

    @Test
    fun everyModeSectionHasAnUnknownRendering() {
        // A line that vanishes when its value cannot be read is a line the owner has no way
        // of knowing existed. Reading nothing must cost the same number of lines as reading
        // everything, and say so.
        val full = LiveStatusReadings(
            doorAngleDeg = 0.8,
            witnessLux = 148.0,
            witnessLit = true,
            confirmationCountdownMs = 4_000L,
            metersFromParking = 3.0,
            parkingThresholdMeters = 100.0,
            pursuitActive = false,
        )
        listOf(vehicleContext(), entryContext(), powerContext()).forEach { context ->
            val snapshot = armedSnapshot(context)
            val withLive = ProtectionStatusProjection
                .evaluate(snapshot, nowMs, nowMs, full)
                .modeSections
            val withoutLive = ProtectionStatusProjection
                .evaluate(snapshot, nowMs, nowMs, null)
                .modeSections

            assertEquals(
                "Same blocks with and without live readings for ${context.selectedProfile}",
                withLive.map { it.titleTh },
                withoutLive.map { it.titleTh },
            )
            withLive.zip(withoutLive).forEach { (live, blank) ->
                assertEquals(
                    "Same line count in ${live.titleTh} for ${context.selectedProfile}",
                    live.lines.size,
                    blank.lines.size,
                )
            }
        }
    }

    @Test
    fun noModeExceedsOneTelegramMessageInTheHealthyCase() {
        // Telegram cuts at 4096 characters and splitting mid-report would push the answer
        // to "what is being watched" into a second message the owner may not open.
        listOf(vehicleContext(), entryContext(), powerContext()).forEach { context ->
            val output = formatter.format(armedSnapshot(context), nowMs, nowMs)
            assertTrue(
                "${context.selectedProfile} healthy report is ${output.length} characters",
                output.length < 4_096,
            )
        }
    }

    @Test
    fun sensorRolesInStatusMatchProtectionProfilePolicy() {
        // The one test that stops this coming back. Every symptom this work fixed came from
        // the report keeping its own table of which sensors a mode uses; this compares the
        // rendered rows against the policy that the runtime itself obeys.
        val cases = listOf(
            vehicleContext(),
            powerContext(),
            entryContext(level = EntryWatchLevel.DOOR_ANGLE),
            entryContext(level = EntryWatchLevel.SOUND_AND_MOVEMENT),
        )
        cases.forEach { context ->
            val profile = requireNotNull(context.selectedProfile)
            val level = context.entryLevel ?: EntryWatchLevel.DOOR_ANGLE
            val expected = ProtectionProfilePolicy.signalRoles(profile, level)
                .filterValues { it != SensorRole.OFF }
            val projection = ProtectionStatusProjection
                .evaluate(armedSnapshot(context), nowMs, nowMs)

            assertEquals(
                "Divisor for $profile/$level",
                expected.size,
                projection.sensorSummary.totalCount,
            )
            assertEquals(
                "Rendered kinds for $profile/$level",
                expected.keys,
                projection.sensorSummary.sensors.keys,
            )
            expected.forEach { (kind, role) ->
                val label = requireNotNull(PresentationTextCatalog.evidenceRoleShortLabel(role))
                val name = ModeStatusSections.sensorName(kind, profile)
                assertTrue(
                    "$profile/$level must render $name as ($label)",
                    projection.sensorSummary.orderedItems.any {
                        it.modeLineTh?.contains("$name ($label):") == true
                    },
                )
            }
        }
    }

    @Test
    fun theSmsFallbackIsNamedButNeverDialable() {
        // The owner has to be able to confirm they set the right number without the chat
        // history — kept on every signed-in device — carrying the whole thing.
        val masked = PresentationTextCatalog.maskedSmsDestination("0812345689")
        assertEquals("08x-xxx-xx89", masked)

        val output = formatter.format(
            armedSnapshot(vehicleContext()),
            nowMs,
            nowMs,
            LiveStatusReadings(smsFallbackMasked = masked),
        )
        assertTrue(output.contains("SMS สำรอง: ตั้งไว้แล้ว (08x-xxx-xx89)"))
        assertFalse(output.contains("0812345689"))
    }

    @Test
    fun aReportWithNoSmsFallbackSaysSoRatherThanOmittingTheLine() {
        val output = formatter.format(armedSnapshot(vehicleContext()), nowMs, nowMs)
        assertTrue(output.contains("SMS สำรอง: ยังไม่ได้ตั้งไว้"))
    }

    @Test
    fun theModeAndTheDurationAreTheFirstTwoLines() {
        // Half of owners read the notification preview and close it. What is being watched
        // and for how long has to be in it, not the state of the Telegram connection that
        // just delivered the message.
        val lines = formatter.format(armedSnapshot(vehicleContext()), nowMs, nowMs).lines()

        assertEquals("🛡️ โหมดยานพาหนะ · กำลังป้องกัน", lines[0])
        assertEquals("เฝ้ามาแล้ว 6 ชั่วโมง", lines[1])
        assertTrue(
            "The channels block must sit below the mode's own answer",
            lines.indexOfFirst { it.startsWith("📡") } > lines.indexOfFirst { it.startsWith("🔎") },
        )
    }
}
