package com.example.motorcycleantitheftsensor.protection

import java.io.File
import java.util.Locale

/**
 * What a single black box line is.
 *
 * `M` is written every minute whether or not the phone is armed — the point of the minute
 * row is not what it says but that it exists. Forty missing `M` rows at three in the morning
 * is the record that the app was killed, and no other mechanism in the app can state that,
 * because a process that has been killed cannot write down that it was killed.
 *
 * `S` marks the moment a state actually changed, so an investigator does not have to infer
 * an arm from the minute it first appears in an `M` row.
 *
 * `E` refers an opened incident back to `protection_incidents.bin`. Nothing emits it yet;
 * it is named here so that adding it later does not reshape a file already on phones.
 */
enum class BlackBoxRowType(val code: String) {
    MINUTE("M"),
    STATE("S"),
    EVENT("E"),
}

/**
 * The sensor statistics of one minute.
 *
 * Empty for the whole of B1: this phase writes no sensor data at all, so that the recorder
 * cannot possibly slow the detection path while it is being proven. B2 fills these in from
 * the fan-out point that already exists, rather than by registering a listener of its own.
 */
data class BlackBoxSensorSummary(
    val samples: Int? = null,
    val accelerationMaxG: Double? = null,
    val accelerationRmsG: Double? = null,
    val rotationMaxDeg: Double? = null,
    val lux: Double? = null,
    val accuracyMin: Int? = null,
)

/**
 * A protection state as the black box records it.
 *
 * Deliberately small and free of anything identifying: no chat id, no token, no coordinate.
 * The file sits in private storage, but a phone that is stolen is a phone whose private
 * storage is in someone else's hands, and a minute-by-minute record of where the owner was
 * is not worth the forensic value it would add here. Location belongs to the event clips of
 * layer C, which exist only for the minutes something actually happened.
 *
 * [configFingerprint] has no column of its own. It is carried so that a settings change can
 * be noticed and marked with an `S` row; the fingerprint itself would say nothing to a reader.
 */
data class BlackBoxState(
    val armed: Boolean,
    val mode: String,
    val sourceMask: Int,
    val incidents: Int,
    val batteryPercent: Int?,
    val charging: Boolean?,
    val configFingerprint: String? = null,
) {
    companion object {
        const val MODE_NONE = "-"

        val UNKNOWN = BlackBoxState(
            armed = false,
            mode = MODE_NONE,
            sourceMask = 0,
            incidents = 0,
            batteryPercent = null,
            charging = null,
        )
    }
}

data class BlackBoxRow(
    val type: BlackBoxRowType,
    val elapsedMs: Long,
    val wallMs: Long,
    val state: BlackBoxState,
    val sensors: BlackBoxSensorSummary = BlackBoxSensorSummary(),
    /** Why an `S` row was written, or which incident an `E` row refers to. Empty on `M`. */
    val note: String = "",
)

/** The identifying block at the top of every day file. */
data class BlackBoxHeader(
    val device: String,
    val androidSdk: Int,
    val appVersion: String,
    /**
     * Identifies the boot, not the process. Two runs of the app separated by a kill share a
     * boot id, which is what makes the gap between them readable as a kill rather than as a
     * reboot. Derived from the wall clock at boot rather than randomly generated, because a
     * random id would be new after every kill — exactly the case it has to survive.
     */
    val bootId: String,
    val wallAnchorMs: Long,
    val elapsedAtAnchorMs: Long,
    val sensors: String,
)

/**
 * Line-by-line CSV, append only.
 *
 * The format is chosen for how the file ends, not for how it is written: this app is killed
 * mid-air by design (that is the whole of item 2), so the last line on disk is routinely a
 * partial one. A JSON array loses the entire file to a missing bracket and SQLite loses it
 * to a damaged journal, while a torn CSV line costs exactly that line.
 */
object BlackBoxCsv {

    const val VERSION = 1

    const val COLUMN_HEADER =
        "type,elapsedMs,wallMs,armed,mode,srcMask,samples,accMaxG,accRmsG," +
            "rotMaxDeg,lux,accuracyMin,incidents,battPct,charging,note"

    private const val COLUMN_COUNT = 16

    fun header(header: BlackBoxHeader): String = buildString {
        append("# blackbox v").append(VERSION).append('\n')
        append("# device=").append(sanitize(header.device))
            .append(" android=").append(header.androidSdk)
            .append(" app=").append(sanitize(header.appVersion)).append('\n')
        append("# bootId=").append(sanitize(header.bootId))
            .append(" wallAnchorMs=").append(header.wallAnchorMs)
            .append(" elapsedAtAnchorMs=").append(header.elapsedAtAnchorMs).append('\n')
        append("# sensors=").append(sanitize(header.sensors)).append('\n')
        append(COLUMN_HEADER).append('\n')
    }

    fun format(row: BlackBoxRow): String = buildString {
        append(row.type.code).append(',')
        append(row.elapsedMs).append(',')
        append(row.wallMs).append(',')
        append(if (row.state.armed) 1 else 0).append(',')
        append(sanitize(row.state.mode)).append(',')
        append(row.state.sourceMask).append(',')
        append(row.sensors.samples.orBlank()).append(',')
        append(row.sensors.accelerationMaxG.orBlank()).append(',')
        append(row.sensors.accelerationRmsG.orBlank()).append(',')
        append(row.sensors.rotationMaxDeg.orBlank()).append(',')
        append(row.sensors.lux.orBlank()).append(',')
        append(row.sensors.accuracyMin.orBlank()).append(',')
        append(row.state.incidents).append(',')
        append(row.state.batteryPercent.orBlank()).append(',')
        append(row.state.charging.orBlank()).append(',')
        append(sanitize(row.note))
    }

    /**
     * Returns null for anything that is not a whole row, which is how a torn final line is
     * dropped without taking the rows before it down with it.
     */
    fun parse(line: String): BlackBoxRow? {
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("type,")) return null
        val fields = line.split(",")
        if (fields.size != COLUMN_COUNT) return null
        val type = BlackBoxRowType.entries.firstOrNull { entry -> entry.code == fields[0] }
            ?: return null
        val elapsedMs = fields[1].toLongOrNull() ?: return null
        val wallMs = fields[2].toLongOrNull() ?: return null
        val armed = fields[3].toFlag() ?: return null
        val sourceMask = fields[5].toIntOrNull() ?: return null
        val incidents = fields[12].toIntOrNull() ?: return null
        return BlackBoxRow(
            type = type,
            elapsedMs = elapsedMs,
            wallMs = wallMs,
            state = BlackBoxState(
                armed = armed,
                mode = fields[4],
                sourceMask = sourceMask,
                incidents = incidents,
                batteryPercent = fields[13].toIntOrNull(),
                charging = fields[14].toFlag(),
            ),
            sensors = BlackBoxSensorSummary(
                samples = fields[6].toIntOrNull(),
                accelerationMaxG = fields[7].toDoubleOrNull(),
                accelerationRmsG = fields[8].toDoubleOrNull(),
                rotationMaxDeg = fields[9].toDoubleOrNull(),
                lux = fields[10].toDoubleOrNull(),
                accuracyMin = fields[11].toIntOrNull(),
            ),
            note = fields[15],
        )
    }

    /**
     * Reads back every complete row. A file whose last line was cut off mid-write — the
     * ordinary ending for a process that was killed — still yields every row before it.
     */
    fun readRows(file: File): List<BlackBoxRow> {
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        if (text.isEmpty()) return emptyList()
        return text.split('\n').mapNotNull(::parse)
    }

    private fun String.toFlag(): Boolean? = when (this) {
        "1" -> true
        "0" -> false
        else -> null
    }

    private fun Int?.orBlank(): String = this?.toString() ?: ""

    private fun Boolean?.orBlank(): String = when (this) {
        true -> "1"
        false -> "0"
        null -> ""
    }

    private fun Double?.orBlank(): String =
        if (this == null || isNaN() || isInfinite()) "" else String.format(Locale.US, "%.3f", this)

    /** A comma or a newline inside a value would silently shift every column after it. */
    private fun sanitize(value: String): String =
        value.replace(',', ' ').replace('\n', ' ').replace('\r', ' ').trim()
}
