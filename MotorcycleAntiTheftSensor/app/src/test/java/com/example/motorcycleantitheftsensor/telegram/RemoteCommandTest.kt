package com.example.motorcycleantitheftsensor.telegram

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
        assertEquals(RemoteCommand.Unknown, RemoteCommand.parse("/status extra"))
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
        assertEquals(RemoteCommand.Unknown, RemoteCommand.parse("/status@MyGuardBot extra"))
    }
}
