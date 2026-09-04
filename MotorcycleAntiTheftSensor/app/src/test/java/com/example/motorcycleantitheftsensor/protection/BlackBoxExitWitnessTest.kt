package com.example.motorcycleantitheftsensor.protection

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BlackBoxExitWitnessTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val nowMs = 1_756_800_000_000L
    private lateinit var directory: File
    private val mark = FakeMarkStore()

    @Test
    fun theDyingMessageSurvivesTheDeathItDescribes() {
        val original = BlackBoxState(
            armed = true,
            mode = ProtectionProfile.POWER.name,
            sourceMask = 6,
            incidents = 12,
            batteryPercent = 41,
            charging = true,
        )
        val decoded = BlackBoxProcessStateSummary.decode(
            BlackBoxProcessStateSummary.encode(
                state = original,
                elapsedMs = 3_600_000L,
                bootIdHash = 4321,
                profileOrdinal = ProtectionProfile.POWER.ordinal,
            ),
        )

        assertEquals(4321, decoded?.bootIdHash)
        assertEquals(3_600_000L, decoded?.elapsedMs)
        assertEquals(original, decoded?.state)
    }

    @Test
    fun anUnknownLayoutIsRefusedRatherThanMisread() {
        val valid = BlackBoxProcessStateSummary.encode(
            state = BlackBoxState.UNKNOWN,
            elapsedMs = 0L,
            bootIdHash = 0,
            profileOrdinal = null,
        )
        val futureVersion = valid.copyOf().also { bytes -> bytes[0] = 99 }

        assertNull(BlackBoxProcessStateSummary.decode(null))
        assertNull(BlackBoxProcessStateSummary.decode(ByteArray(8)))
        assertNull(BlackBoxProcessStateSummary.decode(futureVersion))
        assertEquals(BlackBoxState.MODE_NONE, BlackBoxProcessStateSummary.decode(valid)?.state?.mode)
    }

    @Test
    fun aKillIsDescribedByTheRunThatWasKilled() {
        val writer = writer()
        val dying = BlackBoxState(
            armed = true,
            mode = ProtectionProfile.POWER.name,
            sourceMask = 6,
            incidents = 3,
            batteryPercent = 88,
            charging = false,
        )
        val witness = witness(
            writer,
            exit(
                timestampMs = nowMs - 120_000L,
                reasonCode = 9,
                summary = BlackBoxProcessStateSummary.encode(dying, 900_000L, BOOT_HASH, ProtectionProfile.POWER.ordinal),
            ),
        )

        assertEquals(1, witness.recordNewExits())

        val row = rows().single()
        assertEquals(BlackBoxRowType.EXIT, row.type)
        assertEquals(nowMs - 120_000L, row.wallMs)
        // The elapsed clock is the dead run's, not ours: the distance to the death timestamp
        // is how long it went unheard before it went.
        assertEquals(900_000L, row.elapsedMs)
        assertEquals(dying, row.state)
        assertTrue(row.note.startsWith("exit:EXCESSIVE_RESOURCE_USAGE:"))
        assertTrue(row.note.contains(":samboot"))
    }

    @Test
    fun aDeathOnTheOtherSideOfABootIsNotReportedAsAKill() {
        val writer = writer()
        val witness = witness(
            writer,
            exit(
                timestampMs = nowMs - 60_000L,
                reasonCode = 2,
                summary = BlackBoxProcessStateSummary.encode(BlackBoxState.UNKNOWN, 0L, BOOT_HASH - 1, null),
            ),
        )
        witness.recordNewExits()

        assertTrue(rows().single().note.contains(":reboot"))
    }

    @Test
    fun aDeathWithNoDyingMessageSaysSoInsteadOfInventingAState() {
        val writer = writer()
        witness(writer, exit(timestampMs = nowMs - 60_000L, reasonCode = 3, summary = null)).recordNewExits()

        val row = rows().single()
        assertEquals(BlackBoxState.UNKNOWN, row.state)
        assertTrue(row.note.startsWith("exit:LOW_MEMORY:"))
        assertTrue(row.note.contains(":nostate"))
    }

    @Test
    fun deathsAlreadyDescribedAreNotToldAgainOnTheNextStart() {
        val writer = writer()
        val records = arrayOf(
            exit(timestampMs = nowMs - 300_000L, reasonCode = 10, summary = null),
            exit(timestampMs = nowMs - 200_000L, reasonCode = 3, summary = null),
        )

        assertEquals(2, witness(writer, *records).recordNewExits())
        assertEquals(0, witness(writer, *records).recordNewExits())

        // A death the system reports later still lands, because the mark is a high-water mark
        // and not a "seen it all" flag.
        val later = exit(timestampMs = nowMs - 100_000L, reasonCode = 6, summary = null)
        assertEquals(1, witness(writer, *records, later).recordNewExits())
        assertEquals(3, rows().size)
    }

    @Test
    fun deathsAreWrittenOldestFirstHoweverTheSystemOrdersThem() {
        val writer = writer()
        // The platform hands these back newest first.
        witness(
            writer,
            exit(timestampMs = nowMs - 100_000L, reasonCode = 6, summary = null),
            exit(timestampMs = nowMs - 300_000L, reasonCode = 10, summary = null),
            exit(timestampMs = nowMs - 200_000L, reasonCode = 3, summary = null),
        ).recordNewExits()

        assertEquals(
            listOf(nowMs - 300_000L, nowMs - 200_000L, nowMs - 100_000L),
            rows().map { row -> row.wallMs },
        )
    }

    @Test
    fun aSourceThatThrowsLeavesTheRecordAloneRatherThanTakingTheServiceDown() {
        val writer = writer()
        val witness = BlackBoxExitWitness(
            writer = writer,
            source = { throw IllegalStateException("ActivityManager said no") },
            markStore = mark,
            elapsedMs = { 1_000L },
            currentBootIdHash = BOOT_HASH,
        )

        assertEquals(0, witness.recordNewExits())
        assertTrue(rows().isEmpty())
    }

    @Test
    fun anUnrecognisedReasonIsPrintedRatherThanSwallowed() {
        assertEquals("FREEZER", BlackBoxExitWitness.reasonName(14))
        assertEquals("CODE_99", BlackBoxExitWitness.reasonName(99))
    }

    private fun writer(): BlackBoxWriter {
        directory = temporaryFolder.newFolder()
        return BlackBoxWriter(
            directory = directory,
            header = BlackBoxHeader(
                device = "Huawei/INE-LX2",
                androidSdk = 30,
                appVersion = "1.0",
                bootId = "boot-1",
                wallAnchorMs = nowMs,
                elapsedAtAnchorMs = 0L,
                sensors = "acc:LSM6DSL:ST:0.001",
            ),
            wallClockMs = { nowMs },
        )
    }

    private fun witness(writer: BlackBoxWriter, vararg records: BlackBoxExitRecord) = BlackBoxExitWitness(
        writer = writer,
        source = { records.toList() },
        markStore = mark,
        elapsedMs = { 1_000L },
        currentBootIdHash = BOOT_HASH,
    )

    private fun exit(timestampMs: Long, reasonCode: Int, summary: ByteArray?) = BlackBoxExitRecord(
        timestampMs = timestampMs,
        reasonCode = reasonCode,
        importance = 125,
        pssKb = 184_320L,
        description = null,
        stateSummary = summary,
    )

    private fun rows(): List<BlackBoxRow> =
        directory.listFiles().orEmpty().sortedBy { file -> file.name }.flatMap(BlackBoxCsv::readRows)

    private class FakeMarkStore : BlackBoxExitMarkStore {
        private var mark = 0L

        override fun lastRecordedExitMs(): Long = mark

        override fun record(timestampMs: Long) {
            mark = timestampMs
        }
    }

    private companion object {
        const val BOOT_HASH = 987_654
    }
}
