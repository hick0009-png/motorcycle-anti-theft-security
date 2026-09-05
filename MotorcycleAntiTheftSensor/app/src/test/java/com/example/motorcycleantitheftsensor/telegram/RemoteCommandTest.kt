package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.protection.PresentationTextCatalog
import com.example.motorcycleantitheftsensor.protection.ProtectionProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteCommandTest {
    @Test
    fun parsesValidCommandsCorrectly() {
        assertEquals(RemoteCommand.Help, RemoteCommand.parse("/start"))
        assertEquals(RemoteCommand.Help, RemoteCommand.parse("/help"))
        assertEquals(RemoteCommand.Status, RemoteCommand.parse("/status"))
        assertEquals(RemoteCommand.Arm, RemoteCommand.parse("/arm"))
        assertEquals(RemoteCommand.Sensitivity(7), RemoteCommand.parse("/sensitivity 7"))
        assertEquals(RemoteCommand.Decode("payload_text"), RemoteCommand.parse("/decode payload_text"))
        assertEquals(RemoteCommand.Pair("CODE123"), RemoteCommand.parse("/pair CODE123"))
    }

    @Test
    fun parsesExactDisarmWithoutArgument() {
        assertEquals(RemoteCommand.Disarm, RemoteCommand.parse("/disarm"))
        assertEquals(RemoteCommand.Disarm, RemoteCommand.parse("/DISARM"))
    }

    @Test
    fun disarmWithAnyArgumentIsUnknown() {
        assertEquals(RemoteCommand.Unknown, RemoteCommand.parse("/disarm 123456"))
        assertEquals(RemoteCommand.Unknown, RemoteCommand.parse("/disarm anything"))
    }

    @Test
    fun parsesInvalidArgumentsSafely() {
        // Unknown arguments for parameterless commands
        assertEquals(RemoteCommand.Unknown, RemoteCommand.parse("/start extra"))
        assertEquals(RemoteCommand.Unknown, RemoteCommand.parse("/help extra"))
        // /status now takes a mode, so an argument it cannot read is a mode it does not
        // know rather than a command it does not know: the reply can then name the words
        // that work, which is the difference between a typo and a dead end.
        assertEquals(
            RemoteCommand.StatusForMode(profile = null, argument = "extra"),
            RemoteCommand.parse("/status extra"),
        )
        assertEquals(RemoteCommand.Unknown, RemoteCommand.parse("/arm extra"))

        // Missing or invalid arguments
        assertEquals(RemoteCommand.Sensitivity(null), RemoteCommand.parse("/sensitivity"))
        assertEquals(RemoteCommand.Sensitivity(null), RemoteCommand.parse("/sensitivity fast"))
        assertEquals(RemoteCommand.Decode(""), RemoteCommand.parse("/decode"))
        assertEquals(RemoteCommand.Pair(null), RemoteCommand.parse("/pair"))
    }

    @Test
    fun unknownCommandDoesNotMapToOperationalAction() {
        assertEquals(RemoteCommand.Unknown, RemoteCommand.parse("/shutdown"))
        assertEquals(RemoteCommand.Unknown, RemoteCommand.parse("/ping"))
    }

    @Test
    fun parsesCommandsWithBotMentionSuffix() {
        assertEquals(RemoteCommand.Help, RemoteCommand.parse("/start@MyGuardBot"))
        assertEquals(RemoteCommand.Help, RemoteCommand.parse("/help@MyGuardBot"))
        assertEquals(RemoteCommand.Status, RemoteCommand.parse("/status@MyGuardBot"))
        assertEquals(RemoteCommand.Arm, RemoteCommand.parse("/arm@MyGuardBot"))
        assertEquals(RemoteCommand.Disarm, RemoteCommand.parse("/disarm@MyGuardBot"))
        assertEquals(RemoteCommand.Disarm, RemoteCommand.parse("/DISARM@MyGuardBot"))
        assertEquals(RemoteCommand.Sensitivity(5), RemoteCommand.parse("/sensitivity@MyGuardBot 5"))
        assertEquals(RemoteCommand.Decode("sample_payload"), RemoteCommand.parse("/decode@MyGuardBot sample_payload"))
        assertEquals(RemoteCommand.Pair("CODE999"), RemoteCommand.parse("/pair@MyGuardBot CODE999"))

        // Disarm with extra arguments even with bot name must be Unknown
        assertEquals(RemoteCommand.Unknown, RemoteCommand.parse("/disarm@MyGuardBot 123456"))
        assertEquals(
            RemoteCommand.StatusForMode(profile = null, argument = "extra"),
            RemoteCommand.parse("/status@MyGuardBot extra"),
        )
    }

    @Test
    fun whereIsAcceptedUnderEitherSpellingAndOnlyWithoutArguments() {
        assertEquals(RemoteCommand.Where, RemoteCommand.parse("/where"))
        assertEquals(RemoteCommand.Where, RemoteCommand.parse("/locate"))
        assertEquals(RemoteCommand.Where, RemoteCommand.parse("/where@MyGuardBot"))
        assertEquals(RemoteCommand.Where, RemoteCommand.parse("  /WHERE  "))

        // Anything trailing is a typo rather than a request, and a typo must not move the radio.
        assertEquals(RemoteCommand.Unknown, RemoteCommand.parse("/where now"))
    }

    /**
     * The words an owner types, in Thai and in English. The enum constant is deliberately
     * not one of them: `VEHICLE` is not a word this product ever shows anyone.
     */
    @Test
    fun statusAcceptsEachModeByTheWordsAnOwnerWouldType() {
        val cases = mapOf(
            "รถ" to ProtectionProfile.VEHICLE,
            "ยานพาหนะ" to ProtectionProfile.VEHICLE,
            "car" to ProtectionProfile.VEHICLE,
            "Vehicle" to ProtectionProfile.VEHICLE,
            "ประตู" to ProtectionProfile.ENTRY,
            "ทางเข้า" to ProtectionProfile.ENTRY,
            "ประตูและทางเข้า" to ProtectionProfile.ENTRY,
            "DOOR" to ProtectionProfile.ENTRY,
            "ไฟเลี้ยง" to ProtectionProfile.POWER,
            "ไฟเลี้ยงจุดติดตั้ง" to ProtectionProfile.POWER,
            "power" to ProtectionProfile.POWER,
        )
        for ((word, expected) in cases) {
            assertEquals(
                RemoteCommand.StatusForMode(expected, word),
                RemoteCommand.parse("/status $word"),
            )
        }
        // Trailing space survives the split and must not stop the match.
        assertEquals(
            ProtectionProfile.VEHICLE,
            PresentationTextCatalog.profileFromOwnerWord("Vehicle  "),
        )
    }

    /** Every word /help offers has to be a word the parser takes. */
    @Test
    fun everyWordOfferedByHelpIsAWordTheParserAccepts() {
        for (profile in ProtectionProfile.entries) {
            val offered = PresentationTextCatalog.modeWord(profile)
            assertEquals(
                RemoteCommand.StatusForMode(profile, offered),
                RemoteCommand.parse("/status $offered"),
            )
        }
    }
}
