package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerArmedSessionControllerTest {

    @Test
    fun resumedDarkEpisodeDoesNotRepeatItsOpeningAlert() {
        val model = PowerWitnessModel(
            darkMinLux = 2.0,
            darkMaxLux = 4.0,
            litMinLux = 120.0,
            litMaxLux = 123.0,
            guardBandLux = 20.0,
            algorithmVersion = 1,
            sensorIdentity = "light#1",
            hoodSignature = "hood-A",
        )
        val controller = PowerArmedSessionController()
        controller.begin(
            generation = 1L,
            model = model,
            settings = PowerProfileSettings(lossConfirmationMs = 10_000L, recoveryConfirmationMs = 10_000L),
            resumedSemantic = PowerCompositeArbiter.SemanticState.WITNESS_LOST,
        )

        assertEquals(model, controller.activeWitnessModel())
        assertTrue(
            controller.onSample(
                PowerSignalSample(true, model.darkMaxLux, fresh = true, timestampMs = 1_000L),
                currentGeneration = 1L,
            ).isEmpty(),
        )
        assertTrue(
            controller.onSample(
                PowerSignalSample(true, model.darkMaxLux, fresh = true, timestampMs = 20_000L),
                currentGeneration = 1L,
            ).isEmpty(),
        )
    }

    @Test
    fun resumedPowerEpisodeClosesAfterBothSignalsStayHealthyForTenSeconds() {
        val model = PowerWitnessModel(
            darkMinLux = 2.0,
            darkMaxLux = 4.0,
            litMinLux = 120.0,
            litMaxLux = 123.0,
            guardBandLux = 20.0,
            algorithmVersion = 1,
            sensorIdentity = "light#1",
            hoodSignature = "hood-A",
        )
        val controller = PowerArmedSessionController()
        controller.begin(
            generation = 1L,
            model = model,
            settings = PowerProfileSettings(lossConfirmationMs = 10_000L, recoveryConfirmationMs = 10_000L),
            resumedSemantic = PowerCompositeArbiter.SemanticState.WITNESS_LOST,
        )

        assertTrue(
            controller.onSample(
                PowerSignalSample(true, model.litMinLux, fresh = true, timestampMs = 1_000L),
                currentGeneration = 1L,
            ).isEmpty(),
        )
        assertEquals(11_000L, controller.nextConfirmationAtMs())

        val verdicts = controller.onSample(
            PowerSignalSample(true, model.litMinLux, fresh = true, timestampMs = 11_000L),
            currentGeneration = 1L,
        )

        assertTrue(verdicts.single() is PowerArbiterVerdict.RecoveredClosed)
    }

    @Test
    fun armReferenceUsesTheCurrentLightLevelBeforeEvaluatingPowerSignals() {
        val model = PowerWitnessModel(
            darkMinLux = 2.0,
            darkMaxLux = 4.0,
            litMinLux = 120.0,
            litMaxLux = 123.0,
            guardBandLux = 20.0,
            algorithmVersion = 1,
            sensorIdentity = "light#1",
            hoodSignature = "hood-A",
        )
        val controller = PowerArmedSessionController()
        controller.begin(
            generation = 1L,
            model = model,
            settings = PowerProfileSettings(lossConfirmationMs = 10_000L, recoveryConfirmationMs = 10_000L),
        )

        controller.onSample(PowerSignalSample(true, 20.0, fresh = true, timestampMs = 1_000L), 1L)
        assertEquals(11_000L, controller.nextConfirmationAtMs())

        controller.onSample(PowerSignalSample(true, 20.0, fresh = true, timestampMs = 11_000L), 1L)
        controller.onSample(PowerSignalSample(false, 20.0, fresh = true, timestampMs = 12_000L), 1L)

        assertEquals(22_000L, controller.nextConfirmationAtMs())
    }

    @Test
    fun powerSessionExposesConfirmationDeadlinesWhenNoNewLightEventArrives() {
        val model = PowerWitnessModel(
            darkMinLux = 2.0,
            darkMaxLux = 4.0,
            litMinLux = 120.0,
            litMaxLux = 123.0,
            guardBandLux = 20.0,
            algorithmVersion = 1,
            sensorIdentity = "light#1",
            hoodSignature = "hood-A",
        )
        val controller = PowerArmedSessionController()
        controller.begin(
            generation = 1L,
            model = model,
            settings = PowerProfileSettings(
                lossConfirmationMs = 10_000L,
                recoveryConfirmationMs = 10_000L,
            ),
        )

        controller.onSample(
            PowerSignalSample(true, model.litMinLux, fresh = true, timestampMs = 1_000L),
            currentGeneration = 1L,
        )
        assertEquals(11_000L, controller.nextConfirmationAtMs())

        controller.onSample(
            PowerSignalSample(true, model.litMinLux, fresh = true, timestampMs = 11_000L),
            currentGeneration = 1L,
        )
        controller.onSample(
            PowerSignalSample(false, model.litMinLux, fresh = true, timestampMs = 12_000L),
            currentGeneration = 1L,
        )
        assertEquals(22_000L, controller.nextConfirmationAtMs())
    }

    @Test
    fun activeWitnessModelUsesThePerArmLightReference() {
        val calibrated = PowerWitnessModel(
            darkMinLux = 2.0,
            darkMaxLux = 4.0,
            litMinLux = 120.0,
            litMaxLux = 123.0,
            guardBandLux = 20.0,
            algorithmVersion = 1,
            sensorIdentity = "light#1",
            hoodSignature = "hood-A",
        )
        val controller = PowerArmedSessionController()
        controller.begin(
            generation = 1L,
            model = calibrated,
            settings = PowerProfileSettings(lossConfirmationMs = 10_000L, recoveryConfirmationMs = 10_000L),
        )

        controller.onSample(PowerSignalSample(true, 243.0, fresh = true, timestampMs = 0L), 1L)
        controller.onSample(PowerSignalSample(true, 243.0, fresh = true, timestampMs = 10_000L), 1L)

        val active = requireNotNull(controller.activeWitnessModel())
        assertEquals(8.0, active.darkMaxLux, 0.001)
        assertEquals(240.0, active.litMinLux, 0.001)
    }

    @Test
    fun generationChangeInvalidatesDarkWitnessBeforeGuardBandCableLoss() {
        val model = PowerWitnessModel(
            darkMinLux = 2.0,
            darkMaxLux = 4.0,
            litMinLux = 120.0,
            litMaxLux = 123.0,
            guardBandLux = 20.0,
            algorithmVersion = 1,
            sensorIdentity = "light#1",
            hoodSignature = "hood-A",
        )
        val controller = PowerArmedSessionController()
        controller.begin(
            generation = 1L,
            model = model,
            settings = PowerProfileSettings(lossConfirmationMs = 10_000L, recoveryConfirmationMs = 10_000L),
        )
        controller.onSample(PowerSignalSample(true, 121.0, fresh = true, timestampMs = 0L), 1L)
        controller.onSample(PowerSignalSample(true, 121.0, fresh = true, timestampMs = 10_000L), 1L)
        controller.onSample(PowerSignalSample(true, 3.0, fresh = true, timestampMs = 11_000L), 1L)

        assertTrue(
            controller.onSample(
                PowerSignalSample(false, 50.0, fresh = true, timestampMs = 12_000L),
                currentGeneration = 2L,
            ).isEmpty(),
        )
        assertEquals(22_000L, controller.nextConfirmationAtMs())

        val verdicts = controller.onSample(
            PowerSignalSample(false, 50.0, fresh = true, timestampMs = 22_000L),
            currentGeneration = 2L,
        )
        assertTrue(verdicts.single() is PowerArbiterVerdict.ChargingHealthAlert)
    }

    @Test
    fun guardBandSampleDoesNotRetuneTheActiveWitnessModel() {
        val calibrated = PowerWitnessModel(
            darkMinLux = 2.0,
            darkMaxLux = 4.0,
            litMinLux = 120.0,
            litMaxLux = 123.0,
            guardBandLux = 20.0,
            algorithmVersion = 1,
            sensorIdentity = "light#1",
            hoodSignature = "hood-A",
        )
        val controller = PowerArmedSessionController()
        controller.begin(
            generation = 1L,
            model = calibrated,
            settings = PowerProfileSettings(lossConfirmationMs = 10_000L, recoveryConfirmationMs = 10_000L),
        )
        controller.onSample(PowerSignalSample(true, 243.0, fresh = true, timestampMs = 0L), 1L)
        controller.onSample(PowerSignalSample(true, 243.0, fresh = true, timestampMs = 10_000L), 1L)
        val before = requireNotNull(controller.activeWitnessModel())

        controller.onSample(PowerSignalSample(true, 50.0, fresh = true, timestampMs = 11_000L), 1L)

        assertEquals(before, controller.activeWitnessModel())
    }
}
