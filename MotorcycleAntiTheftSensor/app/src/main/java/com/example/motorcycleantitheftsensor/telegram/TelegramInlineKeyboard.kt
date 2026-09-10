package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.protection.PresentationTextCatalog
import com.example.motorcycleantitheftsensor.protection.ProtectionProfile
import org.json.JSONArray
import org.json.JSONObject

/**
 * What a tapped button asks for.
 *
 * **Reads only, on purpose.** A button grants no authority a typed command does not
 * already have — it arrives in the same chat and passes the same authorization check — but
 * a button is tapped by accident in a way `/disarm` is not. Turning a vehicle's protection
 * off by a mis-tap is not a failure mode worth adding to save eight characters of typing,
 * so arm and disarm stay typed until the owner asks for them here.
 *
 * @param data what Telegram sends back when the button is tapped. Telegram caps this at 64
 *   bytes, and it is stored in the chat forever, so it is a short opaque token rather than
 *   anything meaningful — an old message's button still works after an upgrade, and an
 *   upgrade that drops a token gets [fromData] returning null rather than a crash.
 * @param profile set on the buttons that ask about one mode, so that the mapping from a
 *   button to a mode is stated once and cannot disagree with itself in two directions.
 */
enum class InlineAction(
    val data: String,
    val profile: ProtectionProfile? = null,
) {
    STATUS("st"),
    WHERE("wh"),
    STATUS_VEHICLE("sv", ProtectionProfile.VEHICLE),
    STATUS_ENTRY("se", ProtectionProfile.ENTRY),
    STATUS_POWER("sp", ProtectionProfile.POWER),
    ;

    fun toCommand(): RemoteCommand = when {
        this == WHERE -> RemoteCommand.Where
        // The word is the one /help offers, so a button and a typed command reach the
        // handler as the same request and cannot answer differently.
        profile != null -> RemoteCommand.StatusForMode(
            profile = profile,
            argument = PresentationTextCatalog.modeWord(profile),
        )
        else -> RemoteCommand.Status
    }

    companion object {
        /**
         * @return null when this build does not know the token. Buttons live in the chat
         *   history forever; one from a future build, or from a feature since removed, must
         *   be ignored rather than guessed at.
         */
        fun fromData(data: String): InlineAction? = entries.firstOrNull { it.data == data }

        /** Throws for a mode with no button, which `everyModeHasAButton` catches at build time. */
        fun forProfile(profile: ProtectionProfile): InlineAction =
            entries.first { it.profile == profile }
    }
}

/**
 * The button menu that rides under a status report.
 *
 * Two rows: what the owner asks about the running watch, then one button per mode. The
 * mode row is built from [ProtectionProfile.entries], so a mode added later gets a button
 * without anyone remembering to add one.
 */
object TelegramInlineKeyboards {

    /** The `reply_markup` value for a status reply, as the JSON Telegram expects. */
    fun statusMenu(): String {
        val firstRow = JSONArray()
            .put(button("🔄 สถานะตอนนี้", InlineAction.STATUS))
            .put(button("📍 อยู่ที่ไหน", InlineAction.WHERE))
        val modeRow = JSONArray()
        for (profile in ProtectionProfile.entries) {
            modeRow.put(
                button(
                    PresentationTextCatalog.modeButtonLabel(profile),
                    InlineAction.forProfile(profile),
                ),
            )
        }
        return JSONObject()
            .put("inline_keyboard", JSONArray().put(firstRow).put(modeRow))
            .toString()
    }

    private fun button(label: String, action: InlineAction): JSONObject =
        JSONObject().put("text", label).put("callback_data", action.data)
}
