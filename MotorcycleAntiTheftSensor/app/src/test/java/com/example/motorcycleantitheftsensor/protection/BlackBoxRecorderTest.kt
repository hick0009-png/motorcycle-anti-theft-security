package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BlackBoxRecorderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private var nowMs = 1_756_800_000_000L
    private var elapsedMs = 5_000L
    private val scheduler = FakeScheduler()

    @Test
    fun aStoppedServiceLeavesARowSayingSoAndAKilledOneDoesNot() {
        val recorder = recorder()
        recorder.start(state(armed = true))
        recorder.stop()

        val notes = rows().map { row -> row.note }
        assertEquals(listOf(BlackBoxRecorder.NOTE_START, BlackBoxRecorder.NOTE_STOP), notes)
        assertTrue(scheduler.isShutdown)
    }

    @Test
    fun minuteRowsKeepArrivingWhileNothingChanges() {
        val recorder = recorder()
        recorder.start(state(armed = true))
        repeat(3) {
            elapsedMs += 60_000L
            nowMs += 60_000L
            scheduler.advance()
        }

        val minutes = rows().filter { row -> row.type == BlackBoxRowType.MINUTE }
        assertEquals(3, minutes.size)
        assertEquals(listOf(65_000L, 125_000L, 185_000L), minutes.map { row -> row.elapsedMs })
        assertTrue(minutes.all { row -> row.state.armed })
    }

    @Test
    fun armingAndSwitchingModeEachLeaveTheirOwnStateRow() {
        val recorder = recorder()
        recorder.start(state(armed = false, mode = BlackBoxState.MODE_NONE))
        recorder.observe(state(armed = true, mode = "ENTRY"))
        recorder.observe(state(armed = true, mode = "POWER"))
        recorder.observe(state(armed = false, mode = BlackBoxState.MODE_NONE))

        val states = rows().filter { row -> row.type == BlackBoxRowType.STATE }
        assertEquals(
            listOf(BlackBoxRecorder.NOTE_START, "arm+mode", "mode", "disarm+mode"),
            states.map { row -> row.note },
        )
    }

    @Test
    fun aBatteryReadingMovingIsNotAStateChange() {
        val recorder = recorder()
        recorder.start(state(armed = true, batteryPercent = 90))
        recorder.observe(state(armed = true, batteryPercent = 89))
        recorder.observe(state(armed = true, batteryPercent = 88))

        assertEquals(1, rows().count { row -> row.type == BlackBoxRowType.STATE })
    }

    /**
     * Measured on the test phone, not imagined: sensors cross the freshness window every few
     * seconds, so a state row per mask change is tens of thousands of rows a day and a day
     * file that fills — and stops recording — before the night is over.
     */
    @Test
    fun aSourceMaskFlickeringDoesNotWriteAStateRow() {
        val recorder = recorder()
        recorder.start(state(armed = true, sourceMask = 7))
        repeat(20) { index ->
            recorder.observe(state(armed = true, sourceMask = if (index % 2 == 0) 4 else 7))
        }

        assertEquals(1, rows().count { row -> row.type == BlackBoxRowType.STATE })
    }

    @Test
    fun theMinuteRowStillCarriesWhicheverSourcesWereLastDelivering() {
        val recorder = recorder()
        recorder.start(state(armed = true, sourceMask = 7))
        recorder.observe(state(armed = true, sourceMask = 4))
        scheduler.advance()

        assertEquals(4, rows().single { row -> row.type == BlackBoxRowType.MINUTE }.state.sourceMask)
    }

    @Test
    fun theMinuteRowCarriesTheSensorSummaryAndTakesItOnlyOnce() {
        val drains = mutableListOf<Int>()
        var minute = 0
        val recorder = recorder(
            sensors = {
                minute += 1
                drains.add(minute)
                BlackBoxSensorSummary(samples = minute * 10, lux = 4.5)
            },
        )
        recorder.start(state(armed = true))
        // A state row in the middle must not take the minute's evidence and file it under an arm.
        recorder.observe(state(armed = false))
        scheduler.advance()

        val minuteRow = rows().single { row -> row.type == BlackBoxRowType.MINUTE }
        assertEquals(listOf(1), drains)
        assertEquals(10, minuteRow.sensors.samples)
        assertEquals(4.5, minuteRow.sensors.lux!!, 0.001)
        assertEquals(null, rows().first { row -> row.type == BlackBoxRowType.STATE }.sensors.samples)
    }

    @Test
    fun aSensorSummaryThatThrowsCostsItsColumnsAndNotTheRow() {
        val recorder = recorder(sensors = { throw IllegalStateException("tap broke") })
        recorder.start(state(armed = true))
        scheduler.advance()

        val minuteRow = rows().single { row -> row.type == BlackBoxRowType.MINUTE }
        assertEquals(null, minuteRow.sensors.samples)
        assertEquals(true, minuteRow.state.armed)
    }

    @Test
    fun theNewestObservedStateIsWhatTheNextMinuteRowCarries() {
        val recorder = recorder()
        recorder.start(state(armed = true, batteryPercent = 90))
        recorder.observe(state(armed = true, batteryPercent = 61))
        scheduler.advance()

        val minute = rows().single { row -> row.type == BlackBoxRowType.MINUTE }
        assertEquals(61, minute.state.batteryPercent)
    }

    @Test
    fun aSettingsChangeIsMarkedEvenThoughNoColumnShowsIt() {
        val recorder = recorder()
        recorder.start(state(armed = true).copy(configFingerprint = "fp-1"))
        recorder.observe(state(armed = true).copy(configFingerprint = "fp-2"))

        assertEquals("config", rows().last().note)
    }

    @Test
    fun nothingChangedIsNotAStateChange() {
        assertNull(blackBoxStateChange(state(armed = true), state(armed = true)))
    }

    @Test
    fun everyDistinctIncidentIsCountedOnceHoweverOftenItIsRepublished() {
        val mapper = BlackBoxStateMapper()
        val first = snapshot().copy(lastIncident = incident("i-1"))
        val second = snapshot().copy(lastIncident = incident("i-2"))

        assertEquals(0, mapper.map(snapshot()).incidents)
        assertEquals(1, mapper.map(first).incidents)
        assertEquals(1, mapper.map(first).incidents)
        assertEquals(2, mapper.map(second).incidents)
    }

    @Test
    fun theSourceMaskCountsOnlyTheKindsActuallyDeliveringData() {
        val mapper = BlackBoxStateMapper()
        val mapped = mapper.map(
            snapshot().copy(
                sensorHealth = mapOf(
                    SensorKind.VIBRATION to SensorHealth(state = SensorHealthState.HEALTHY),
                    SensorKind.LIGHT to SensorHealth(state = SensorHealthState.AVAILABLE),
                    SensorKind.LOCATION to SensorHealth(state = SensorHealthState.STALE),
                ),
            ),
        )

        assertEquals(1 shl SensorKind.VIBRATION.ordinal, mapped.sourceMask)
    }

    @Test
    fun anArmedSnapshotIsRecordedAsArmedUnderItsOwnMode() {
        val mapper = BlackBoxStateMapper()

        val disarmed = mapper.map(snapshot())
        val armed = mapper.map(
            snapshot().copy(
                state = ProtectionState.ARMED_HEALTHY,
                batteryLevelPercent = 77,
                chargingState = ChargingState.CHARGING,
            ),
        )

        assertEquals(false, disarmed.armed)
        assertEquals(BlackBoxState.MODE_NONE, disarmed.mode)
        assertEquals(true, armed.armed)
        assertEquals(77, armed.batteryPercent)
        assertEquals(true, armed.charging)
    }

    private fun recorder(sensors: (() -> BlackBoxSensorSummary)? = null) = BlackBoxRecorder(
        writer = BlackBoxWriter(
            directory = temporaryFolder.newFolder(),
            header = BlackBoxHeader(
                device = "Huawei/INE-LX2",
                androidSdk = 29,
                appVersion = "1.0",
                bootId = "boot-1",
                wallAnchorMs = nowMs,
                elapsedAtAnchorMs = elapsedMs,
                sensors = "",
            ),
            wallClockMs = { nowMs },
        ).also { created -> writer = created },
        elapsedMs = { elapsedMs },
        wallClockMs = { nowMs },
        scheduler = scheduler,
        sensors = sensors,
    )

    private lateinit var writer: BlackBoxWriter

    private fun rows(): List<BlackBoxRow> =
        writer.files().flatMap { file -> BlackBoxCsv.readRows(file) }

    private fun state(
        armed: Boolean,
        mode: String = if (armed) "ENTRY" else BlackBoxState.MODE_NONE,
        batteryPercent: Int? = 90,
        sourceMask: Int = 1,
    ) = BlackBoxState(
        armed = armed,
        mode = mode,
        sourceMask = sourceMask,
        incidents = 0,
        batteryPercent = batteryPercent,
        charging = false,
    )

    private fun snapshot(): ProtectionSnapshot = ProtectionSnapshot.offline(nowMs)

    private fun incident(id: String) = IncidentSummary(
        id = id,
        severity = IncidentSeverity.WARNING,
        lifecycle = IncidentLifecycle.OPEN,
        updatedAtMs = nowMs,
        deliveryState = DeliveryState.PENDING,
    )

    private class FakeScheduler : BlackBoxScheduler {
        private var task: (() -> Unit)? = null
        override var isShutdown: Boolean = false
            private set

        override fun scheduleAtFixedRate(initialDelayMs: Long, periodMs: Long, task: () -> Unit) {
            this.task = task
        }

        override fun shutdownNow() {
            isShutdown = true
            task = null
        }

        fun advance() {
            task?.invoke()
        }
    }
}
