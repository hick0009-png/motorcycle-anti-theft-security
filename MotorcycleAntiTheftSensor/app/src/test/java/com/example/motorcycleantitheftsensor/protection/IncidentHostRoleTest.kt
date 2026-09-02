package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Who is allowed to open an incident, and what happens to the one already open.
 *
 * The engine has a single active-incident slot. A missing role used to count as a host, so
 * the microphone, the location fix and the charging line could all take that slot without
 * anyone having chosen them — under the door watch that meant four hosts competing, and
 * whichever arrived last overwrote the others into permanent OPEN.
 *
 * The first proof here is the one to read before touching any of this: the vehicle watch's
 * movement alert works today *because* an unstamped fix counted as a host. If the default
 * flips without the vehicle table naming the location fix, that alert goes silent with no
 * error, no crash, and nothing to see until a bike actually disappears.
 */
class IncidentHostRoleTest {

    private lateinit var engine: IncidentEngine

    @Before
    fun setUp() {
        engine = IncidentEngine(
            idGenerator = IncidentIdGenerator { "incident-${System.nanoTime()}" },
            correlationWindowMs = 15_000L,
        )
    }

    private fun observation(
        kind: SensorKind,
        elapsedMs: Long,
        role: SensorRole?,
        diagnostic: String? = null,
        audioThreat: AudioThreatMetadata? = null,
        source: SensorSource? = null,
    ) = SensorObservation(
        kind = kind,
        role = role,
        source = source,
        eventElapsedMs = elapsedMs,
        wallClockMs = 1_700_000_000_000L + elapsedMs,
        normalizedValue = 1.0,
        baselineDelta = 1.0,
        valid = true,
        diagnostic = diagnostic,
        audioThreat = audioThreat,
    )

    private fun IncidentUpdate.incidentOrNull(): SecurityIncident? = when (this) {
        is IncidentUpdate.Opened -> incident
        is IncidentUpdate.Updated -> incident
        is IncidentUpdate.Escalated -> incident
        else -> null
    }

    private fun audio(elapsedMs: Long) = AudioThreatMetadata(
        category = AudioThreatCategory.BREAKING,
        confidence = 0.9,
        loudnessDeltaDb = 14.0,
        firstDetectedElapsedMs = elapsedMs,
        lastDetectedElapsedMs = elapsedMs,
        occurrenceCount = 1,
        onsetElapsedMs = elapsedMs,
    )

    @Test
    fun theVehicleWatchStillNamesTheLocationFixAndTheChargingLineAsHosts() {
        // The regression this whole change could cause. Sound alone never opens anything;
        // sound plus a fix does, and only because the fix is allowed to host.
        val vehicle = ProtectionProfilePolicy.signalRoles(ProtectionProfile.VEHICLE)

        assertEquals(SensorRole.PRIMARY, vehicle[SensorKind.LOCATION])
        assertEquals(SensorRole.PRIMARY, vehicle[SensorKind.POWER_THERMAL])
        assertEquals(SensorRole.PRIMARY, vehicle[SensorKind.VIBRATION])
    }

    @Test
    fun soundPlusAHostingFixStillOpensTheAlertItOpensToday() {
        engine.accept(
            observation(SensorKind.MICROPHONE, 1_000L, SensorRole.SUPPORTING, audioThreat = audio(1_000L)),
            ProtectionState.ARMED_HEALTHY,
        )
        val update = engine.onConfirmedMovement(
            observation(SensorKind.LOCATION, 2_000L, SensorRole.PRIMARY, diagnostic = "confirmed_movement"),
            ProtectionState.ARMED_HEALTHY,
        )

        val incident = update.incidentOrNull()
        assertNotNull("a hosting fix must still open the movement alert", incident)
        assertEquals(IncidentType.AUDIO, incident?.type)
        assertEquals(IncidentSeverity.CRITICAL, incident?.severity)
    }

    @Test
    fun theSameFixOpensNothingWhenTheUseDidNotMakeItAHost() {
        // The door watch's case: a fix is corroboration there, never a reason to alert.
        engine.accept(
            observation(SensorKind.MICROPHONE, 1_000L, SensorRole.SUPPORTING, audioThreat = audio(1_000L)),
            ProtectionState.ARMED_HEALTHY,
        )
        val update = engine.onConfirmedMovement(
            observation(SensorKind.LOCATION, 2_000L, SensorRole.SUPPORTING, diagnostic = "confirmed_movement"),
            ProtectionState.ARMED_HEALTHY,
        )

        assertNull(update.incidentOrNull())
    }

    @Test
    fun anUnstampedSignalCannotOpenAnything() {
        // "Nobody said" now reads as "may not". This is the safety property the whole
        // change exists for.
        engine.accept(
            observation(SensorKind.MICROPHONE, 1_000L, null, audioThreat = audio(1_000L)),
            ProtectionState.ARMED_HEALTHY,
        )
        val update = engine.accept(
            observation(SensorKind.VIBRATION, 2_000L, null),
            ProtectionState.ARMED_HEALTHY,
        )

        assertNull(update.incidentOrNull())
    }

    @Test
    fun aCorroboratingSampleCannotOpenButJoinsTheIncidentAHostOpens() {
        val opened = engine.accept(
            observation(
                SensorKind.VIBRATION,
                1_000L,
                SensorRole.SUPPORTING,
                source = SensorSource.GYROSCOPE,
            ),
            ProtectionState.ARMED_HEALTHY,
        )
        assertNull("a corroborating source must not start an alert", opened.incidentOrNull())

        val hosted = engine.accept(
            observation(
                SensorKind.VIBRATION,
                2_000L,
                SensorRole.PRIMARY,
                source = SensorSource.ACCELEROMETER,
            ),
            ProtectionState.ARMED_HEALTHY,
        )
        val incident = hosted.incidentOrNull()
        assertNotNull(incident)
        assertTrue(
            "the corroborating sample belongs to the incident the host opened",
            (incident?.evidence?.size ?: 0) >= 2,
        )
    }

    @Test
    fun takingTheActiveSlotClosesWhatHeldItInsteadOfOrphaningIt() {
        // The single slot had one occupant and no ceremony: an unannounced replacement left
        // the previous incident OPEN forever, invisible to every close path.
        val first = engine.accept(
            observation(
                SensorKind.VIBRATION,
                1_000L,
                SensorRole.PRIMARY,
                source = SensorSource.ACCELEROMETER,
            ),
            ProtectionState.ARMED_HEALTHY,
        ).incidentOrNull()
        assertNotNull(first)

        // A door verdict arriving while an unrelated incident holds the slot. This is the
        // real shape of the overwrite: the door path opens whenever the active incident is
        // not already a door incident, and used to drop whatever was there.
        val second = engine.accept(
            observation(
                SensorKind.VIBRATION,
                3_000L,
                SensorRole.PRIMARY,
                diagnostic = ProtectionDiagnostics.ENTRY_DOOR_OPEN,
                source = SensorSource.GAME_ROTATION_VECTOR,
            ),
            ProtectionState.ARMED_HEALTHY,
        )
        val displaced = (second as? IncidentUpdate.Opened)?.supersededIncident

        assertEquals(IncidentType.ENTRY_DOOR, second.incidentOrNull()?.type)

        assertNotNull("the displaced incident must be handed back, not dropped", displaced)
        assertEquals(IncidentLifecycle.CLOSED, displaced?.lifecycle)
        assertEquals(first?.id, displaced?.id)
        assertNotNull(displaced?.closedAtMs)
        assertNotNull(displaced?.closeReason)
    }

    @Test
    fun theDoorWatchHostsOnOrientationAloneAndNothingElse() {
        val entry = ProtectionProfilePolicy.signalRoles(ProtectionProfile.ENTRY)

        listOf(
            SensorKind.MICROPHONE,
            SensorKind.LOCATION,
            SensorKind.POWER_THERMAL,
            SensorKind.VIBRATION,
            SensorKind.LIGHT,
        ).forEach { kind ->
            assertEquals(
                "$kind must corroborate the door watch, never host it",
                SensorRole.SUPPORTING,
                entry[kind],
            )
        }
        assertEquals(emptyList<SensorKind>(), ProtectionProfilePolicy.hostKinds(ProtectionProfile.ENTRY))
    }

    @Test
    fun powerGuardHostsOnTwoSignalsAndRunsNothingElse() {
        val power = ProtectionProfilePolicy.signalRoles(ProtectionProfile.POWER)

        assertEquals(
            listOf(SensorKind.LIGHT, SensorKind.POWER_THERMAL),
            ProtectionProfilePolicy.hostKinds(ProtectionProfile.POWER),
        )
        listOf(SensorKind.VIBRATION, SensorKind.MICROPHONE, SensorKind.LOCATION).forEach { kind ->
            assertEquals(SensorRole.OFF, power[kind])
        }
    }

    @Test
    fun theHostsNamedOnScreenMatchTheHostsTheEngineEnforces() {
        // The screen states one set of hosts and the engine enforces another only if these
        // drift. The door watch is the deliberate exception, and it is spelled out rather
        // than left to a reader to notice.
        ProtectionProfile.entries
            .filter { it != ProtectionProfile.ENTRY }
            .forEach { profile ->
                val fromKinds = ProtectionProfilePolicy.hostKinds(profile).map { kind ->
                    when (kind) {
                        SensorKind.VIBRATION -> ProtectionHost.MOVEMENT
                        SensorKind.LIGHT -> ProtectionHost.LIGHT
                        SensorKind.LOCATION -> ProtectionHost.LOCATION
                        SensorKind.POWER_THERMAL -> ProtectionHost.CHARGING
                        SensorKind.MICROPHONE -> ProtectionHost.SOUND
                    }
                }
                assertEquals(fromKinds, ProtectionProfilePolicy.hosts(profile))
            }

        // The door watch hosts on the orientation verdict, which is not a kind at all.
        assertEquals(
            listOf(ProtectionHost.ORIENTATION),
            ProtectionProfilePolicy.hosts(ProtectionProfile.ENTRY),
        )
    }

    @Test
    fun everyUseNamesAtLeastOneHost() {
        // A use with no host cannot alert about anything, which is not a configuration the
        // screen should ever be able to state.
        ProtectionProfile.entries.forEach { profile ->
            assertTrue(
                "$profile names no host at all",
                ProtectionProfilePolicy.hosts(profile).isNotEmpty(),
            )
        }
    }

    @Test
    fun theKindsAUseRunsAreExactlyTheOnesItDidNotSwitchOff() {
        ProtectionProfile.entries.forEach { profile ->
            val roles = ProtectionProfilePolicy.signalRoles(profile)

            assertEquals(
                "$profile must describe every signal, so none defaults into hosting",
                SensorKind.entries.toSet(),
                roles.keys,
            )
            assertEquals(
                roles.filterValues { it != SensorRole.OFF }.keys,
                ProtectionProfilePolicy.usedSensorKinds(profile),
            )
        }
    }
}
