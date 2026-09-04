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
    fun aBreadcrumbBecomesItsOwnRowCarryingTheStateItHappenedIn() {
        val recorder = recorder()
        recorder.start(state(armed = true, mode = "ENTRY"))

        recorder.note(
            BreadcrumbDomain.TELEGRAM,
            BreadcrumbEvent.FAILED,
            listOf(BreadcrumbDetail.HTTP_429),
        )

        val crumb = rows().single { row -> row.type == BlackBoxRowType.BREADCRUMB }
        assertEquals("tg:failed:http429", crumb.note)
        // The state columns are the point: "the send failed" is half an answer without
        // "while armed in ENTRY".
        assertTrue(crumb.state.armed)
        assertEquals("ENTRY", crumb.state.mode)
    }

    @Test
    fun aBreadcrumbIsStampedWhenItHappenedNotWhenItWasWritten() {
        // The write is handed to the black box's thread and can land later; a row dated at the
        // write would put a crumb in the wrong minute, which is the only thing it is read by.
        val recorder = recorder()
        recorder.start(state(armed = true))
        val raisedAtElapsed = elapsedMs

        recorder.note(BreadcrumbDomain.NET, BreadcrumbEvent.LOST, listOf(BreadcrumbDetail.WIFI))
        elapsedMs += 30_000L
        nowMs += 30_000L

        val crumb = rows().single { row -> row.type == BlackBoxRowType.BREADCRUMB }
        assertEquals(raisedAtElapsed, crumb.elapsedMs)
    }

    @Test
    fun breadcrumbsBeforeTheRecorderStartsAreNotWritten() {
        // There is no file to write into and no state to describe them with.
        val recorder = recorder()

        recorder.note(BreadcrumbDomain.BOOT, BreadcrumbEvent.START)

        assertTrue(rows().isEmpty())
    }

    @Test
    fun aBreadcrumbRowSurvivesBeingReadBack() {
        val recorder = recorder()
        recorder.start(state(armed = false))

        recorder.note(
            BreadcrumbDomain.PERMISSION,
            BreadcrumbEvent.HAVE,
            listOf(BreadcrumbDetail.PERM_LOCATION, BreadcrumbDetail.PERM_MICROPHONE),
        )

        val crumb = rows().single { row -> row.type == BlackBoxRowType.BREADCRUMB }
        assertEquals("perm:have:loc.mic", crumb.note)
    }

    @Test
    fun aSaturatedDayStaysWellInsideTheDayFileCeiling() {
        // §7 of the design sized the disk budget on this: a day spent at the hourly ceiling
        // must not be able to cap the file and silence the record it belongs to.
        val recorder = recorder()
        recorder.start(state(armed = true, mode = "ENTRY"))
        repeat(24 * BreadcrumbDomain.TOTAL_PER_HOUR) { index ->
            elapsedMs += 1_000L
            nowMs += 1_000L
            recorder.note(
                BreadcrumbDomain.entries[index % BreadcrumbDomain.entries.size],
                BreadcrumbEvent.FAILED,
                listOf(BreadcrumbDetail.entries[index % BreadcrumbDetail.entries.size]),
            )
        }

        val written = temporaryFolder.root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertTrue("wrote $written bytes", written < BlackBoxWriter.MAX_FILE_BYTES / 2)
    }

    @Test
    fun theBatteryColumnsGoBlankWhileNothingIsReadingTheBattery() {
        // `startPowerStatusMonitoring` is called from the view model and nowhere else, so a
        // phone sitting disarmed with no UI open has nothing registered for
        // ACTION_BATTERY_CHANGED and the snapshot carries its last value forward. On the test
        // phone that was 306 rows claiming 100% and not charging across five hours in which
        // the battery actually fell to 92% on a charger.
        val mapper = BlackBoxStateMapper()

        val mapped = mapper.map(
            snapshot().copy(
                sensorHealth = mapOf(
                    SensorKind.VIBRATION to SensorHealth(state = SensorHealthState.HEALTHY),
                    SensorKind.LIGHT to SensorHealth(state = SensorHealthState.HEALTHY),
                ),
                batteryLevelPercent = 100,
                chargingState = ChargingState.CHARGING,
            ),
        )

        assertNull(mapped.batteryPercent)
        assertNull(mapped.charging)
    }

    @Test
    fun theBatteryColumnsAreWrittenWhileThePowerSourceIsReporting() {
        val mapper = BlackBoxStateMapper()

        val mapped = mapper.map(
            snapshot().copy(
                state = ProtectionState.ARMED_HEALTHY,
                sensorHealth = SensorKind.entries.associateWith {
                    SensorHealth(state = SensorHealthState.HEALTHY)
                },
                armedProfileSnapshot = armedProfile(ProtectionProfile.POWER),
                batteryLevelPercent = 54,
                chargingState = ChargingState.CHARGING,
            ),
        )

        assertEquals(54, mapped.batteryPercent)
        assertEquals(true, mapped.charging)
    }

    @Test
    fun aProfileThatSwitchesAKindOffNeverReportsItAsWatching() {
        val mapper = BlackBoxStateMapper()
        // What Power Guard actually looked like on the test phone: the health map calls every
        // kind healthy, because a source whose role is OFF answers HEALTHY.
        val allHealthy = SensorKind.entries.associateWith { SensorHealth(state = SensorHealthState.HEALTHY) }

        val power = mapper.map(
            snapshot().copy(
                state = ProtectionState.ARMED_HEALTHY,
                sensorHealth = allHealthy,
                armedProfileSnapshot = armedProfile(ProtectionProfile.POWER),
            ),
        )

        // Power Guard hosts on the lamp and the charging line and runs nothing else at all.
        assertEquals(
            (1 shl SensorKind.LIGHT.ordinal) or (1 shl SensorKind.POWER_THERMAL.ordinal),
            power.sourceMask,
        )
    }

    @Test
    fun aProfileThatUsesEveryKindStillReportsThemAll() {
        val mapper = BlackBoxStateMapper()
        val allHealthy = SensorKind.entries.associateWith { SensorHealth(state = SensorHealthState.HEALTHY) }

        val vehicle = mapper.map(
            snapshot().copy(
                state = ProtectionState.ARMED_HEALTHY,
                sensorHealth = allHealthy,
                armedProfileSnapshot = armedProfile(ProtectionProfile.VEHICLE),
            ),
        )

        assertEquals(SensorKind.entries.fold(0) { mask, kind -> mask or (1 shl kind.ordinal) }, vehicle.sourceMask)
    }

    @Test
    fun aClockThatIsSetLeavesAnAnchorRowNamingWhatMovedIt() {
        val recorder = recorder()
        recorder.start(state(armed = true))

        nowMs += 3_600_000L
        recorder.noteClockChange(BlackBoxRecorder.NOTE_CAUSE_SET)

        val row = rows().last()
        assertEquals(BlackBoxRowType.TIME, row.type)
        // The row is the new anchor: its own two clocks, and how far they had drifted apart.
        assertEquals(nowMs, row.wallMs)
        assertEquals(elapsedMs, row.elapsedMs)
        assertEquals("clock:set:3600", row.note)
    }

    @Test
    fun aClockMovedAndMovedBackInsideOneMinuteIsStillRecorded() {
        val recorder = recorder()
        recorder.start(state(armed = true))

        // What the tick comparison cannot see: the endpoints agree.
        nowMs -= 3_600_000L
        recorder.noteClockChange(BlackBoxRecorder.NOTE_CAUSE_SET)
        nowMs += 3_600_000L
        recorder.noteClockChange(BlackBoxRecorder.NOTE_CAUSE_SET)
        elapsedMs += 60_000L
        nowMs += 60_000L
        scheduler.advance()

        val times = rows().filter { row -> row.type == BlackBoxRowType.TIME }
        assertEquals(listOf("clock:set:-3600", "clock:set:3600"), times.map { row -> row.note })
    }

    @Test
    fun theMinuteTickCatchesAClockThatMovedWithoutAnnouncingItself() {
        val recorder = recorder()
        recorder.start(state(armed = true))

        elapsedMs += 60_000L
        nowMs += 60_000L + 600_000L
        scheduler.advance()

        val rows = rows()
        val time = rows.single { row -> row.type == BlackBoxRowType.TIME }
        assertEquals("clock:drift:600", time.note)
        // Written before the minute row, so the row is stamped against a repaired anchor.
        assertTrue(rows.indexOf(time) < rows.indexOfFirst { row -> row.type == BlackBoxRowType.MINUTE })
    }

    @Test
    fun anOrdinaryMinuteWritesNoTimeRowAtAll() {
        val recorder = recorder()
        recorder.start(state(armed = true))
        repeat(5) {
            elapsedMs += 60_000L
            // A few milliseconds of scheduler jitter, which is not a clock being set.
            nowMs += 59_996L
            scheduler.advance()
        }

        assertTrue(rows().none { row -> row.type == BlackBoxRowType.TIME })
    }

    @Test
    fun aClockCorrectedInALoopCannotFillTheDayWithRowsSayingSo() {
        val recorder = recorder()
        recorder.start(state(armed = true))

        repeat(40) {
            nowMs += 10_000L
            recorder.noteClockChange(BlackBoxRecorder.NOTE_CAUSE_SET)
        }

        val times = rows().filter { row -> row.type == BlackBoxRowType.TIME }
        assertEquals(BlackBoxRecorder.MAX_TIME_ROWS_PER_HOUR, times.size)

        // The hour rolls, and what was held back is stated rather than lost.
        elapsedMs += 60L * 60L * 1000L
        nowMs += 60L * 60L * 1000L
        recorder.noteClockChange(BlackBoxRecorder.NOTE_CAUSE_SET)
        val capped = rows().filter { row -> row.type == BlackBoxRowType.TIME }
        assertTrue(capped.any { row -> row.note.startsWith("clock:capped:28") })
    }

    @Test
    fun anArmedSnapshotIsRecordedAsArmedUnderItsOwnMode() {
        val mapper = BlackBoxStateMapper()

        val disarmed = mapper.map(snapshot())
        val armed = mapper.map(
            snapshot().copy(
                state = ProtectionState.ARMED_HEALTHY,
                // Reporting, so the battery columns are a reading rather than a memory.
                sensorHealth = mapOf(
                    SensorKind.POWER_THERMAL to SensorHealth(state = SensorHealthState.HEALTHY),
                ),
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

    private fun armedProfile(profile: ProtectionProfile) = ArmedProfileSnapshot(
        armedSessionId = "session-1",
        profile = profile,
        resolvedPresetVersion = 1,
        effectiveConfiguration = SensorConfigurationPolicy().forPreset(SensorPreset.BALANCED, nowMs),
        configurationFingerprint = "fp-1",
        commissionedModelFingerprint = null,
        armedCalibrationSnapshot = VehicleArmedCalibrationSnapshot(generation = 1L),
    )

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

        /**
         * Inline, so a test sees the row the moment it offers the crumb. On the device this
         * hops to the black box's thread; what the tests are about is what gets written, not
         * which thread wrote it.
         */
        override fun execute(task: () -> Unit) {
            if (!isShutdown) task()
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
