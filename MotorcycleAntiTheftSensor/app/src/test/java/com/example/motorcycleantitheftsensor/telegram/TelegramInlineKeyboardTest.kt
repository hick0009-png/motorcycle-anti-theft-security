package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.protection.PresentationTextCatalog
import com.example.motorcycleantitheftsensor.protection.ProtectionProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramInlineKeyboardTest {

    @Test
    fun theMenuIsTwoRowsTelegramWillAccept() {
        val rows = JSONObject(TelegramInlineKeyboards.statusMenu()).getJSONArray("inline_keyboard")

        assertEquals(2, rows.length())
        assertEquals(2, rows.getJSONArray(0).length())
        assertEquals(ProtectionProfile.entries.size, rows.getJSONArray(1).length())
    }

    /**
     * The mode row is built from the profile list, so a mode added later arrives with a
     * button rather than with a silent gap where its button should be.
     */
    @Test
    fun everyModeHasAButtonLabelledTheWayItIsTyped() {
        val modeRow = JSONObject(TelegramInlineKeyboards.statusMenu())
            .getJSONArray("inline_keyboard")
            .getJSONArray(1)

        for ((index, profile) in ProtectionProfile.entries.withIndex()) {
            val button = modeRow.getJSONObject(index)
            assertEquals(PresentationTextCatalog.modeButtonLabel(profile), button.getString("text"))
            assertEquals(InlineAction.forProfile(profile).data, button.getString("callback_data"))
            // The label carries the word /help offers, so tapping and typing look like one command.
            assertTrue(button.getString("text").endsWith(PresentationTextCatalog.modeWord(profile)))
        }
    }

    /** Telegram refuses callback data over 64 bytes, and the message is then never sent. */
    @Test
    fun everyCallbackTokenFitsInsideTelegramsLimit() {
        for (action in InlineAction.entries) {
            assertTrue(action.data.toByteArray(Charsets.UTF_8).size <= 64)
            assertTrue(action.data.isNotBlank())
        }
        assertEquals(
            InlineAction.entries.size,
            InlineAction.entries.map { it.data }.toSet().size,
        )
    }

    /**
     * A button lives in the chat history forever. One carrying a token this build does not
     * know — from a newer build, or a feature since removed — is ignored rather than guessed
     * at, because the owner tapped something specific and would not recognise the answer to
     * a different question.
     */
    @Test
    fun anUnknownTokenIsIgnoredRatherThanGuessedAt() {
        assertNull(InlineAction.fromData("zz"))
        assertNull(InlineAction.fromData(""))
        assertNull(InlineAction.fromData("st "))
        for (action in InlineAction.entries) {
            assertEquals(action, InlineAction.fromData(action.data))
        }
    }

    /** A tap and the typed command it stands for have to reach the handler as one request. */
    @Test
    fun aTappedButtonBecomesTheCommandItStandsFor() {
        assertEquals(RemoteCommand.Status, InlineAction.STATUS.toCommand())
        assertEquals(RemoteCommand.Where, InlineAction.WHERE.toCommand())
        for (profile in ProtectionProfile.entries) {
            val word = PresentationTextCatalog.modeWord(profile)
            assertEquals(
                RemoteCommand.parse("/status $word"),
                InlineAction.forProfile(profile).toCommand(),
            )
        }
    }

    /**
     * Arm and disarm are typed, not tapped. A button grants no authority a typed command
     * lacks, but it is tapped by accident in a way a typed command is not, and the accident
     * here is a vehicle left unprotected.
     */
    @Test
    fun noButtonChangesTheProtectionState() {
        for (action in InlineAction.entries) {
            val command = action.toCommand()
            assertTrue(
                "$action must not be a state change",
                command != RemoteCommand.Arm && command != RemoteCommand.Disarm,
            )
        }
        assertNotNull(InlineAction.fromData(InlineAction.STATUS.data))
    }
}
