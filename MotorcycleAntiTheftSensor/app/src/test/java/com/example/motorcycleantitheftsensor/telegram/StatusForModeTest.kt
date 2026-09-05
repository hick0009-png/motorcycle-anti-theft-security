package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.protection.ArmingDelay
import com.example.motorcycleantitheftsensor.protection.DetectorStartResult
import com.example.motorcycleantitheftsensor.protection.EntryDriftVerdict
import com.example.motorcycleantitheftsensor.protection.EntryProfileOverrides
import com.example.motorcycleantitheftsensor.protection.EntryWatchLevel
import com.example.motorcycleantitheftsensor.protection.PresentationTextCatalog
import com.example.motorcycleantitheftsensor.protection.ProtectionClock
import com.example.motorcycleantitheftsensor.protection.ProtectionCoordinator
import com.example.motorcycleantitheftsensor.protection.ProtectionProfile
import com.example.motorcycleantitheftsensor.protection.ProtectionProfilePolicy
import com.example.motorcycleantitheftsensor.protection.ProtectionProfileRepository
import com.example.motorcycleantitheftsensor.protection.ProtectionProfileStoreState
import com.example.motorcycleantitheftsensor.protection.ProtectionRuntime
import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshot
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import com.example.motorcycleantitheftsensor.protection.ReadinessReport
import com.example.motorcycleantitheftsensor.protection.SensorHealth
import com.example.motorcycleantitheftsensor.protection.SensorHealthState
import com.example.motorcycleantitheftsensor.protection.SensorKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/status <โหมด>` — the report for a mode the owner keeps set up but is not using.
 *
 * The question it answers is a real one for anyone with two modes configured: hours after
 * switching to the vehicle watch, is the door watch still calibrated, and could it be armed
 * if I went back? Until this existed the only answer available was the running mode's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatusForModeTest {

    private val nowMs = 1_700_000_000_000L

    @Test
    fun theOtherModeReportSaysAtOnceThatItIsNotTheOneWatching() = runTest {
        val reply = ask("/status ประตู")

        val lines = reply.lines()
        // The level is not pinned here: whichever one the door watch is set to, the two
        // claims that matter are which mode this is about and that it is not watching.
        assertTrue(lines[0].startsWith("🛡️ โหมดประตูและทางเข้า"))
        assertTrue(lines[0].endsWith(" · ไม่ได้เฝ้าอยู่ตอนนี้"))
        // Nothing is armed here, so the line names the selection. "watching with the
        // vehicle mode · not watching" is a sentence that argues with itself.
        assertTrue(lines[1].startsWith("โหมดที่เลือกไว้ตอนนี้: ยานพาหนะ · "))

        val whileArmed = ask("/status ประตู", armedForMs = 2 * 3_600_000L).lines()[1]
        assertTrue(whileArmed.startsWith("ตอนนี้เฝ้าด้วยโหมดยานพาหนะ · "))
        assertTrue(whileArmed.endsWith(" · เฝ้ามาแล้ว 2 ชั่วโมง"))
    }

    /**
     * The point of a separate report rather than the full one with different numbers.
     *
     * The vehicle watch has been armed for nine hours and the door watch's drift verdict
     * says eight. The full report would take the running session's elapsed time, compare it
     * to the door's ceiling and announce that the door watch has outlived its budget — a
     * sentence about a watch that is not running, built from another watch's clock.
     */
    @Test
    fun theOtherModeReportNeverBorrowsTheRunningSessionsClock() = runTest {
        val reply = ask(
            "/status ประตู",
            armedForMs = 9 * 3_600_000L,
            driftVerdict = EntryDriftVerdict.Limited(hoursToThreshold = 8.0),
            profileRepository = repositoryWithDoorAngleLevel(),
        )

        assertFalse(reply.contains("เกินเพดานเวลาแล้ว"))
        // The nine hours belong to the vehicle watch and appear once, on the line that names
        // it. Nothing in the door watch's own sections may be built from that clock.
        val doorSections = reply.lines().drop(2).joinToString("\n")
        assertFalse(doorSections.contains("9 ชั่วโมง"))
        // The rate itself is a property of the phone, not of a session, so it is still said.
        assertTrue(doorSections.contains("เพดานเวลาที่เชื่อได้: "))
        assertTrue(doorSections.contains("8 ชั่วโมง"))
    }

    /**
     * The sound-and-movement level measures no angle, and its own section in `/status`
     * exists to keep every degree out. The report for a mode that is not running has to
     * keep the same rule, or it becomes the one place that promises a measurement the
     * level cannot take.
     */
    @Test
    fun theSoundLevelDoorWatchIsNeverGivenAnAngleThreshold() = runTest {
        val reply = ask("/status ประตู")

        assertFalse(reply.contains("°"))
        assertTrue(reply.contains("ระดับนี้บอกไม่ได้ว่าประตูเปิดกว้างแค่ไหน"))
    }

    /** No line may claim a present-tense reading for a mode holding none of the sensors. */
    @Test
    fun theOtherModeReportPromisesNoLiveReadings() = runTest {
        val reply = ask("/status ไฟเลี้ยง")

        assertFalse(reply.contains("ค่าที่วัดได้ขณะนี้:"))
        assertFalse(reply.contains("กำลังนับถอยหลัง:"))
        assertFalse(reply.contains("สถานะ: สว่าง"))
        assertTrue(reply.contains("ℹ️ ค่าที่วัดได้ขณะนี้มีเฉพาะโหมดที่กำลังเฝ้าอยู่"))
    }

    /** What the owner asked for: is this mode ready, and what would it alert on. */
    @Test
    fun theOtherModeReportStatesReadinessAndThresholds() = runTest {
        val reply = ask("/status ไฟเลี้ยง")

        assertTrue(reply.contains("⚙️ ความพร้อมของโหมดนี้"))
        assertTrue(reply.contains("🟢 เครื่องนี้ใช้ได้"))
        assertTrue(reply.contains("ยืนยันไฟดับเมื่อค้างครบ:"))
        assertTrue(reply.contains("โมเดลไฟยืนยัน:"))
    }

    /**
     * Asking about the running mode gets the running report. It is strictly more than the
     * other-mode report and every line of it is true, so there is nothing to gain by
     * answering with less.
     */
    @Test
    fun askingAboutTheRunningModeGivesTheRunningReport() = runTest {
        val direct = ask("/status")
        val byName = ask("/status รถ")

        assertEquals(direct, byName)
        assertFalse(byName.contains("ไม่ได้เฝ้าอยู่ตอนนี้"))
    }

    /** A typo is answered with the words that work, not with "unknown command". */
    @Test
    fun aWordThatMatchesNoModeIsAnsweredWithTheWordsThatDo() = runTest {
        val reply = ask("/status ประตุ")

        assertTrue(reply.startsWith("❓ ไม่รู้จักโหมด \"ประตุ\""))
        for (profile in ProtectionProfile.entries) {
            assertTrue(reply.contains("/status " + PresentationTextCatalog.modeWord(profile)))
        }
    }

    /** A pasted essay must not come back as one. */
    @Test
    fun anAbsurdlyLongArgumentIsEchoedBackTrimmed() = runTest {
        val reply = ask("/status " + "ก".repeat(500))

        assertTrue(reply.startsWith("❓ ไม่รู้จักโหมด \"" + "ก".repeat(32) + "\""))
    }

    /** A build with no profile layer says so rather than inventing a mode's settings. */
    @Test
    fun withNoProfileStoreTheCommandSaysItCannotRead() = runTest {
        val reply = ask("/status ประตู", profileRepository = null)

        assertEquals("⚠️ อ่านการตั้งค่าของโหมดนี้ไม่ได้ ลองใหม่อีกครั้ง หรือดูในแอป", reply)
    }

    // -----------------------------------------------------------------

    private suspend fun ask(
        text: String,
        armedForMs: Long? = null,
        driftVerdict: EntryDriftVerdict = EntryDriftVerdict.NotMeasured,
        profileRepository: ProtectionProfileRepository? = defaultRepository(),
    ): String {
        val replies = mutableListOf<String>()
        handler(armedForMs, driftVerdict, profileRepository)
            .handle("tg-1", RemoteCommand.parse(text)) { replies += it }
        return replies.single()
    }

    /** The door watch set to the level that can measure degrees. */
    private fun repositoryWithDoorAngleLevel(): ProtectionProfileRepository {
        val policy = ProtectionProfilePolicy(nowMs = { nowMs })
        val base = policy.newStoreState().copy(selectedProfile = ProtectionProfile.VEHICLE)
        val entry = base.profiles.getValue(ProtectionProfile.ENTRY)
        return InMemoryProfileRepository(
            base.copy(
                profiles = base.profiles + (
                    ProtectionProfile.ENTRY to entry.copy(
                        specificOverrides = EntryProfileOverrides(
                            level = EntryWatchLevel.DOOR_ANGLE,
                        ),
                    )
                    ),
            ),
        )
    }

    private fun defaultRepository(): ProtectionProfileRepository {
        val policy = ProtectionProfilePolicy(nowMs = { nowMs })
        return InMemoryProfileRepository(
            policy.newStoreState().copy(selectedProfile = ProtectionProfile.VEHICLE),
        )
    }

    private fun handler(
        armedForMs: Long?,
        driftVerdict: EntryDriftVerdict,
        profileRepository: ProtectionProfileRepository?,
    ): TelegramCommandHandler {
        val runtime = object : ProtectionRuntime {
            override fun readiness(): ReadinessReport = ReadinessReport(emptySet(), emptySet())
            override fun startDetectors(): DetectorStartResult = DetectorStartResult(true)
            override fun stopDetectors() = Unit
            override fun applySensitivity(level: Int) = Unit
            override fun currentSensorHealth(): Map<SensorKind, SensorHealth> = mapOf(
                SensorKind.VIBRATION to SensorHealth(SensorHealthState.HEALTHY, nowMs),
            )
        }
        val coordinator = ProtectionCoordinator(
            initialSnapshot = ProtectionSnapshot.offline(nowMs).copy(
                state = if (armedForMs == null) {
                    ProtectionState.DISARMED_ONLINE
                } else {
                    ProtectionState.ARMED_HEALTHY
                },
                protectionActivatedAtMs = armedForMs?.let { nowMs - it },
                serviceRunning = true,
                telegramPolling = true,
                telegramReachable = true,
                lastTelegramContactAtMs = nowMs,
            ),
            runtime = runtime,
            armingDelay = ArmingDelay { },
            clock = ProtectionClock { nowMs },
            profileRepository = profileRepository,
            profilePolicy = ProtectionProfilePolicy(nowMs = { nowMs }),
            entryDriftVerdict = { driftVerdict },
        )
        return TelegramCommandHandler(
            coordinator = coordinator,
            statusFormatter = ProtectionStatusFormatter(wallClock = { nowMs }),
        )
    }
}

private class InMemoryProfileRepository(
    initialState: ProtectionProfileStoreState,
) : ProtectionProfileRepository {
    private var state: ProtectionProfileStoreState = initialState

    override fun load(): ProtectionProfileStoreState = state

    override fun save(state: ProtectionProfileStoreState): Result<Unit> {
        this.state = state
        return Result.success(Unit)
    }

    override fun update(
        transform: (ProtectionProfileStoreState) -> ProtectionProfileStoreState,
    ): Result<ProtectionProfileStoreState> = save(transform(state)).map { state }
}

