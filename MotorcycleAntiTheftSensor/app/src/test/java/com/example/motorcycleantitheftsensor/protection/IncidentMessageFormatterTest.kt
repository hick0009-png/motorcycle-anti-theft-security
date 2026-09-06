package com.example.motorcycleantitheftsensor.protection

import com.example.motorcycleantitheftsensor.location.LocationPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncidentMessageFormatterTest {
    private val formatter = IncidentMessageFormatter {
        ProtectionSnapshot.offline(0L).copy(
            batteryLevelPercent = 82,
            batteryTemperatureCelsius = 31.5f
        )
    }

    @Test
    fun formatsIncidentWithEvidenceCorrectly() {
        val evidenceList = listOf(
            IncidentEvidence(
                kind = SensorKind.LIGHT,
                eventElapsedMs = 0L,
                wallClockMs = 0L,
                normalizedValue = 20.0,
                baselineDelta = 15.2,
                diagnostic = "ambient_lux"
            ),
            IncidentEvidence(
                kind = SensorKind.VIBRATION,
                eventElapsedMs = 0L,
                wallClockMs = 0L,
                normalizedValue = 12.3,
                baselineDelta = 2.5,
                diagnostic = "accelerometer"
            )
        )
        val incident = criticalIncident().copy(
            type = IncidentType.TAMPER,
            evidence = evidenceList,
            lifecycle = IncidentLifecycle.OPEN
        )

        val message = formatter.format(incident)
        assertTrue(message.contains("การงัดแงะหรือเปิดเบาะ"))
        assertTrue(message.contains("รายละเอียด:"))
        assertTrue(message.contains("แสงบริเวณจุดติดตั้ง: ตรวจพบความสว่างเปลี่ยนไป Δ 15.20"))
        assertTrue(message.contains("รถถูกขยับหรือมุมเอียงเปลี่ยนไป: ตรวจพบการเอียง/สั่น Δ 2.50"))
        assertTrue(message.contains("🔋 แบตเตอรี่: 82% (31.5°C)"))
        assertFalse(message.contains("TAMPER"))
        assertFalse(message.contains("ambient_lux"))
        assertFalse(message.contains("accelerometer"))
    }

    @Test
    fun formatsOpenedIncidentCorrectly() {
        val incident = criticalIncident().copy(
            type = IncidentType.TAMPER,
            lifecycle = IncidentLifecycle.OPEN
        )

        val message = formatter.format(incident)
        assertTrue(message.contains("การงัดแงะหรือเปิดเบาะ"))
        assertFalse(message.contains("TAMPER"))
    }

    @Test
    fun formatsInterruptedIncidentCorrectly() {
        val incident = criticalIncident().copy(
            type = IncidentType.AUDIO,
            lifecycle = IncidentLifecycle.INTERRUPTED
        )

        val message = formatter.format(incident)
        assertTrue(message.contains("เสียงผิดปกติบริเวณจุดติดตั้ง"))
        assertFalse(message.contains("AUDIO"))
    }

    @Test
    fun formatsClosedIncidentCorrectly() {
        val incident = criticalIncident().copy(
            type = IncidentType.TAMPER,
            lifecycle = IncidentLifecycle.CLOSED
        )

        val message = formatter.format(incident)
        assertEquals(UserGuidanceCatalog.content(GuidanceCode.INCIDENT_CLOSED).telegramTh!!, message)
    }

    @Test
    fun noGpsDataInOrdinaryIncident() {
        val evidenceList = listOf(
            IncidentEvidence(
                kind = SensorKind.VIBRATION,
                eventElapsedMs = 0L,
                wallClockMs = 0L,
                normalizedValue = 12.3,
                baselineDelta = 2.5,
                diagnostic = "accelerometer"
            )
        )
        val incident = criticalIncident().copy(
            type = IncidentType.TAMPER,
            evidence = evidenceList,
            lifecycle = IncidentLifecycle.OPEN
        )

        val message = formatter.format(incident)
        assertTrue("Ordinary incident must not contain GPS", !message.contains("ตำแหน่ง"))
        assertTrue("Ordinary incident must not contain lat/lon", !message.contains("lat="))
    }

    @Test
    fun formatTelegramWithPresentationIncludesMapsUrlAndAccuracyAndLabel() {
        val incident = criticalIncident().copy(
            type = IncidentType.TAMPER,
            lifecycle = IncidentLifecycle.OPEN
        )
        val presentation = LocationPresentation(
            labelTh = "ถ.สุขุมวิท, วัฒนา, กรุงเทพฯ",
            mapsUrl = "https://maps.google.com/?q=13.756300,100.501800",
            accuracyMeters = 8,
        )

        val message = formatter.formatTelegram(incident, presentation)
        assertTrue(message.contains("📍 ตำแหน่ง: ถ.สุขุมวิท, วัฒนา, กรุงเทพฯ"))
        assertTrue(message.contains("🗺️ แผนที่: https://maps.google.com/?q=13.756300,100.501800 (ความแม่นยำ ~8m)"))
    }

    @Test
    fun formatTelegramWithoutPresentationExcludesMapsUrlAndLabel() {
        val incident = criticalIncident().copy(
            type = IncidentType.TAMPER,
            lifecycle = IncidentLifecycle.OPEN
        )

        val message = formatter.formatTelegram(incident, null)
        assertFalse(message.contains("🗺️ แผนที่:"))
        assertFalse(message.contains("📍 ตำแหน่ง:"))
        assertFalse(message.contains("maps.google.com"))
    }

    @Test
    fun formatSmsCarriesCoordinatesButNeitherMapsUrlNorGeocodedLabel() {
        val incident = criticalIncident().copy(
            type = IncidentType.TAMPER,
            evidence = listOf(
                IncidentEvidence(
                    kind = SensorKind.VIBRATION,
                    eventElapsedMs = 0L,
                    wallClockMs = 0L,
                    normalizedValue = 12.3,
                    baselineDelta = 2.5,
                    diagnostic = "accelerometer"
                ),
                IncidentEvidence(
                    kind = SensorKind.LOCATION,
                    eventElapsedMs = 0L,
                    wallClockMs = 0L,
                    normalizedValue = 1.0,
                    baselineDelta = 0.0,
                    diagnostic = "fix age_ms=100 accuracy_m=8.0"
                )
            ),
            lifecycle = IncidentLifecycle.OPEN,
            location = IncidentLocation(13.7563, 100.5018, 8f, 1000L)
        )

        val message = formatter.formatSms(incident)
        // Coordinates are the point of an offline alert; the reverse-geocoded label and the
        // maps URL are not, because resolving one needs the network this channel replaces.
        assertTrue(message.contains("📍 พิกัด: 13.75630,100.50180 (~8m)"))
        assertFalse(message.contains("maps.google.com"))
        assertFalse(message.contains("ตำแหน่ง:"))
    }

    @Test
    fun formatSmsCarriesCoordinatesThroughTheTypedEntryAndPowerCopy() {
        val entry = criticalIncident().copy(
            type = IncidentType.ENTRY_DOOR,
            evidence = listOf(
                IncidentEvidence(
                    kind = SensorKind.VIBRATION,
                    eventElapsedMs = 0L,
                    wallClockMs = 0L,
                    normalizedValue = 25.0,
                    baselineDelta = 2.5,
                    diagnostic = ProtectionDiagnostics.ENTRY_DOOR_OPEN,
                ),
            ),
            lifecycle = IncidentLifecycle.OPEN,
            location = IncidentLocation(13.7563, 100.5018, 8f, 1000L),
        )

        val message = formatter.formatSms(entry)
        assertTrue(message.startsWith("ประตูเปิด 25° จากตำแหน่งปิด"))
        assertTrue(message.contains("📍 พิกัด: 13.75630,100.50180 (~8m)"))
    }

    @Test
    fun formatSmsWithoutAFixSaysNothingAboutWhereTheVehicleIs() {
        val incident = criticalIncident().copy(
            type = IncidentType.TAMPER,
            lifecycle = IncidentLifecycle.OPEN,
            location = null,
        )

        assertFalse(formatter.formatSms(incident).contains("พิกัด"))
    }

    @Test
    fun formatSmsDropsCoordinatesOnceTheIncidentIsClosed() {
        val closed = criticalIncident().copy(
            type = IncidentType.TAMPER,
            lifecycle = IncidentLifecycle.CLOSED,
            location = IncidentLocation(13.7563, 100.5018, 8f, 1000L),
        )

        assertFalse(formatter.formatSms(closed).contains("พิกัด"))
    }

    @Test
    fun formatsTypedAudioThreatEvidenceCorrectly() {
        val audioThreat = AudioThreatMetadata(
            category = AudioThreatCategory.POWER_TOOL,
            confidence = 0.92,
            loudnessDeltaDb = 14.5,
            firstDetectedElapsedMs = 1000L,
            lastDetectedElapsedMs = 2000L,
            occurrenceCount = 2,
            onsetElapsedMs = 1000L,
            onsetCoherent = true,
        )
        val evidenceList = listOf(
            IncidentEvidence(
                kind = SensorKind.MICROPHONE,
                eventElapsedMs = 1000L,
                wallClockMs = 1000L,
                normalizedValue = 0.92,
                baselineDelta = 14.5,
                diagnostic = "power_tool",
                audioThreat = audioThreat,
            )
        )
        val incident = criticalIncident().copy(
            type = IncidentType.AUDIO,
            evidence = evidenceList,
            lifecycle = IncidentLifecycle.OPEN,
        )

        val message = formatter.format(incident)
        assertTrue(message.contains("เสียง: เสียงเครื่องมือช่าง/หินเจียร์ (ความมั่นใจ 92%, +14.5 dB, 2 ครั้ง) [ตรงกับการสั่น]"))
    }

    @Test
    fun formatsEscalatedIncidentWithDistinctHeaderAndUrgencyMarker() {
        val incident = criticalIncident().copy(
            type = IncidentType.TAMPER,
            evidence = listOf(
                IncidentEvidence(
                    kind = SensorKind.VIBRATION,
                    eventElapsedMs = 1000L,
                    wallClockMs = 1000L,
                    normalizedValue = 15.0,
                    baselineDelta = 5.0,
                    diagnostic = "accelerometer",
                )
            ),
            lifecycle = IncidentLifecycle.OPEN,
        )
        val update = IncidentUpdate.Escalated(incident)

        val message = formatter.format(update)
        assertTrue("Message must contain escalated header", message.contains("🚨 เหตุยกระดับเป็นวิกฤต"))
        assertTrue("Message must contain urgency marker", message.contains("⚠️ ระดับความรุนแรง: วิกฤต"))
        assertTrue("Message must contain evidence chain", message.contains("รถถูกขยับหรือมุมเอียงเปลี่ยนไป"))
        assertFalse("Message must not expose incident enum", message.contains("TAMPER"))
        assertFalse("Message must not expose severity enum", message.contains("CRITICAL"))
    }

    @Test
    fun progressAndContinuationHideIncidentEnumsAndDiagnostics() {
        val incident = criticalIncident().copy(
            type = IncidentType.TAMPER,
            evidence = listOf(
                IncidentEvidence(
                    kind = SensorKind.POWER_THERMAL,
                    eventElapsedMs = 1_000L,
                    wallClockMs = 1_000L,
                    normalizedValue = 1.0,
                    baselineDelta = 1.0,
                    diagnostic = "charger_disconnected",
                ),
            ),
        )

        listOf(formatter.formatProgress(incident), formatter.formatContinuation(incident)).forEach { message ->
            assertFalse(message.contains("TAMPER"))
            assertFalse(message.contains("POWER"))
            assertFalse(message.contains("charger_disconnected"))
        }
    }

    // -------------------------------------------------------------------------
    // Entry Guard typed delivery text (spec section 8, all six pinned strings).
    // -------------------------------------------------------------------------

    private fun entryEvidence(diagnostic: String, angleDeg: Double = 0.0) = IncidentEvidence(
        kind = SensorKind.VIBRATION,
        eventElapsedMs = 0L,
        wallClockMs = 0L,
        normalizedValue = angleDeg,
        baselineDelta = angleDeg,
        diagnostic = diagnostic,
    )

    @Test
    fun entryDoorOpenRendersPeakAngleCopy() {
        val incident = criticalIncident().copy(
            type = IncidentType.ENTRY_DOOR,
            evidence = listOf(entryEvidence("entry_door_open", angleDeg = 23.7)),
            lifecycle = IncidentLifecycle.OPEN,
        )

        val message = formatter.format(incident)
        assertEquals("ประตูเปิด 24° จากตำแหน่งปิด", message)
    }

    @Test
    fun entryDoorStillOpenUpdateKeepsPeakAngleCopy() {
        val incident = criticalIncident().copy(
            type = IncidentType.ENTRY_DOOR,
            evidence = listOf(
                entryEvidence("entry_door_open", angleDeg = 20.0),
                entryEvidence("entry_door_still_open", angleDeg = 31.0),
            ),
            lifecycle = IncidentLifecycle.OPEN,
        )

        val message = formatter.format(IncidentUpdate.Updated(incident))
        assertEquals("ประตูเปิด 31° จากตำแหน่งปิด", message)
    }

    @Test
    fun entryDoorClosedRendersStillCopy() {
        val incident = criticalIncident().copy(
            type = IncidentType.ENTRY_DOOR,
            evidence = listOf(
                entryEvidence("entry_door_open", angleDeg = 18.0),
                entryEvidence("entry_door_closed"),
            ),
            lifecycle = IncidentLifecycle.CLOSED,
            closeReason = "entry door closed confirmed",
        )

        val message = formatter.format(IncidentUpdate.Closed(incident))
        assertEquals("ประตูปิดและนิ่งแล้ว", message)
    }

    @Test
    fun entrySourceDropoutRendersWaitingCopy() {
        val incident = criticalIncident().copy(
            type = IncidentType.ENTRY_DOOR,
            evidence = listOf(entryEvidence("entry_source_unavailable")),
            lifecycle = IncidentLifecycle.OPEN,
        )

        val message = formatter.format(incident)
        assertEquals("ข้อมูลมุมประตูขาดหาย กำลังรอเซนเซอร์กลับมาทำงาน", message)
    }

    @Test
    fun entryMountMovedOutranksDoorOpenCopy() {
        val incident = criticalIncident().copy(
            type = IncidentType.ENTRY_DOOR,
            severity = IncidentSeverity.CRITICAL,
            evidence = listOf(
                entryEvidence("entry_door_open", angleDeg = 25.0),
                entryEvidence("entry_mount_moved"),
            ),
            lifecycle = IncidentLifecycle.OPEN,
        )

        val message = formatter.format(incident)
        assertEquals("โทรศัพท์หรือขายึดถูกขยับ กรุณาตรวจสอบและปรับเทียบใหม่", message)
    }

    @Test
    fun confirmedImpactEvidenceRendersImpactCopy() {
        val incident = criticalIncident().copy(
            type = IncidentType.ENTRY_DOOR,
            evidence = listOf(
                entryEvidence("entry_door_open", angleDeg = 22.0),
                IncidentEvidence(
                    kind = SensorKind.MICROPHONE,
                    eventElapsedMs = 0L,
                    wallClockMs = 0L,
                    normalizedValue = 1.0,
                    baselineDelta = 9.0,
                    diagnostic = "impact_corroboration",
                ),
            ),
            lifecycle = IncidentLifecycle.OPEN,
        )

        val message = formatter.format(incident)
        assertEquals("ตรวจพบแรงกระแทกที่ประตู", message)
    }

    @Test
    fun evidenceInterruptedOwnerStopRendersOwnerStopCopy() {
        val incident = criticalIncident().copy(
            type = IncidentType.ENTRY_DOOR,
            evidence = listOf(
                entryEvidence("entry_door_open", angleDeg = 19.0),
                entryEvidence("entry_source_unavailable"),
            ),
            lifecycle = IncidentLifecycle.CLOSED,
            closeReason = "owner disarmed while door evidence interrupted",
        )

        val message = formatter.format(IncidentUpdate.Closed(incident))
        assertEquals("หยุดการเฝ้าระวัง—หลักฐานตำแหน่งประตูขาดหาย", message)
    }

    // -------------------------------------------------------------------------
    // Mode-aware vocabulary: nothing sent while Entry Guard is armed may say "รถ".
    // The word is chosen from the armed profile, not the sensor/incident type, so a
    // door-mode event classified as VIBRATION still speaks about a door. This is the
    // closed-vocabulary guard for the leak the owner saw in door mode — it turns the
    // rule from something to remember into something the build enforces.
    // -------------------------------------------------------------------------

    private fun formatterForProfile(profile: ProtectionProfile) = IncidentMessageFormatter {
        ProtectionSnapshot.offline(0L).copy(
            batteryLevelPercent = 82,
            batteryTemperatureCelsius = 31.5f,
            armedProfileSnapshot = armedProfile(profile),
        )
    }

    private fun armedProfile(profile: ProtectionProfile) = ArmedProfileSnapshot(
        armedSessionId = "session-1",
        profile = profile,
        resolvedPresetVersion = 1,
        effectiveConfiguration = SensorConfigurationPolicy().forPreset(SensorPreset.BALANCED, 0L),
        configurationFingerprint = "fp-1",
        commissionedModelFingerprint = null,
        armedCalibrationSnapshot = VehicleArmedCalibrationSnapshot(generation = 1L),
    )

    @Test
    fun noEntryModeMessageEverSaysVehicleWord() {
        val entryFormatter = formatterForProfile(ProtectionProfile.ENTRY)

        // A door-mode event does not always arrive typed ENTRY_DOOR: a hinge swing that the
        // fusion layer classifies as generic movement reaches the untyped branch that once
        // hard-coded "รถ". Cover the typed door path, the generic-fallback path, and the
        // type-agnostic progress/continuation copy that runs every minute an event stays open.
        val movementEvidence = IncidentEvidence(
            kind = SensorKind.VIBRATION,
            eventElapsedMs = 0L,
            wallClockMs = 0L,
            normalizedValue = 12.3,
            baselineDelta = 2.5,
            diagnostic = "accelerometer",
        )
        val entryDoorIncident = criticalIncident().copy(
            type = IncidentType.ENTRY_DOOR,
            evidence = listOf(entryEvidence("entry_door_open", angleDeg = 23.7)),
            lifecycle = IncidentLifecycle.OPEN,
        )
        val genericDuringEntry = criticalIncident().copy(
            type = IncidentType.VIBRATION,
            evidence = listOf(movementEvidence),
            lifecycle = IncidentLifecycle.OPEN,
        )

        val messages = listOf(
            entryFormatter.formatTelegram(IncidentUpdate.Opened(entryDoorIncident)),
            entryFormatter.formatTelegram(IncidentUpdate.Opened(genericDuringEntry)),
            entryFormatter.formatSms(IncidentUpdate.Opened(genericDuringEntry), genericDuringEntry.location),
            entryFormatter.formatDelayed(IncidentUpdate.Opened(genericDuringEntry), null, 3_600_000L),
            entryFormatter.formatProgress(genericDuringEntry),
            entryFormatter.formatContinuation(genericDuringEntry),
            entryFormatter.formatContinuation(entryDoorIncident),
        )

        messages.forEach { message ->
            assertFalse("Entry mode must never say the vehicle word: $message", message.contains("รถ"))
        }
    }

    @Test
    fun entryContinuationTellsOwnerToCheckTheDoor() {
        val entryFormatter = formatterForProfile(ProtectionProfile.ENTRY)
        val incident = criticalIncident().copy(
            type = IncidentType.ENTRY_DOOR,
            evidence = listOf(entryEvidence("entry_door_open", angleDeg = 20.0)),
            lifecycle = IncidentLifecycle.OPEN,
        )

        val message = entryFormatter.formatContinuation(incident)
        assertTrue(message.contains("ตรวจสอบประตูและตำแหน่งล่าสุดทันที"))
    }

    @Test
    fun vehicleModeStillSaysVehicleWord() {
        val vehicleFormatter = formatterForProfile(ProtectionProfile.VEHICLE)
        val incident = criticalIncident().copy(
            type = IncidentType.VIBRATION,
            evidence = listOf(
                IncidentEvidence(
                    kind = SensorKind.VIBRATION,
                    eventElapsedMs = 0L,
                    wallClockMs = 0L,
                    normalizedValue = 12.3,
                    baselineDelta = 2.5,
                    diagnostic = "accelerometer",
                ),
            ),
            lifecycle = IncidentLifecycle.OPEN,
        )

        val message = vehicleFormatter.formatTelegram(IncidentUpdate.Opened(incident))
        assertTrue(message.contains("รถถูกขยับหรือมุมเอียงเปลี่ยนไป"))
        assertTrue(vehicleFormatter.formatContinuation(incident).contains("ตรวจสอบรถและตำแหน่งล่าสุดทันที"))
    }

    private fun criticalIncident() = SecurityIncident(
        id = "incident-1",
        severity = IncidentSeverity.CRITICAL,
        type = IncidentType.TAMPER,
        lifecycle = IncidentLifecycle.OPEN,
        evidence = emptyList(),
        openedAtMs = 0L,
        closedAtMs = null,
        protectionState = ProtectionState.ALERT_ACTIVE,
        updatedAtMs = 0L,
        deliveryState = DeliveryState.PENDING,
    )
}
