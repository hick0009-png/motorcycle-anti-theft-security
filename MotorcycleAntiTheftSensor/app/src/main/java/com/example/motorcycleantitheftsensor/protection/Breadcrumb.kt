package com.example.motorcycleantitheftsensor.protection

import com.example.motorcycleantitheftsensor.telephony.SmsSendOutcome

/**
 * The subsystem a breadcrumb came from, and how many rows an hour belong to it alone.
 *
 * The reservation is the whole reason this is an enum with a number on it rather than a
 * string. Rationing every domain from one shared hourly bucket — which is what the layer was
 * first sketched as — lets a flapping network and a retrying Telegram client spend the entire
 * hour before a revoked permission writes a single row, and a revoked permission is the most
 * common reason an owner says the app "just stopped working". A domain draws its own
 * reservation first and the shared pool second, so the noisy ones can take everything except
 * what belongs to somebody else.
 */
enum class BreadcrumbDomain(val code: String, val reservedPerHour: Int) {
    TELEGRAM("tg", 6),
    NET("net", 4),
    SMS("sms", 3),
    PERMISSION("perm", 4),
    POWER("pwr", 4),
    FOREGROUND("fgs", 4),
    LOCATION("gps", 4),
    MICROPHONE("mic", 3),
    THERMAL("therm", 2),
    BOOT("boot", 2),
    ;

    companion object {
        /** Left over after every reservation, first come first served. */
        const val SHARED_PER_HOUR = 24

        /** What §7 of the design sized the disk budget against. */
        val TOTAL_PER_HOUR: Int get() = entries.sumOf { it.reservedPerHour } + SHARED_PER_HOUR
    }
}

/** What happened. Closed, so that a caller cannot describe it in prose. */
enum class BreadcrumbEvent(val code: String) {
    SEND("send"),
    OK("ok"),
    FAILED("failed"),
    DENIED("denied"),
    WHERE("where"),
    LOST("lost"),
    GAINED("gained"),
    HAVE("have"),
    GRANTED("granted"),
    REVOKED("revoked"),
    ENTER("enter"),
    EXIT("exit"),
    START("start"),
    STOP("stop"),
    FIX("fix"),

    /**
     * The once-per-session warning that an armed door watch has outlived its measured
     * drift ceiling. Distinct from [SEND] so the file can answer the one question an
     * owner asks about it: whether it was ever sent at all.
     */
    CEILING("ceiling"),

    /**
     * A backlog of incidents that failed to send when they happened has now gone out.
     * Distinct from [OK] so that a reader can tell a message sent on time from one that
     * spent the night waiting for a network — the file is where that question gets settled.
     */
    RESEND("resend"),
}

/**
 * How one SMS fallback attempt reads in the file.
 *
 * The split that matters is [BreadcrumbEvent.DENIED] against [BreadcrumbEvent.FAILED]: a
 * fallback that decided not to send is a working fallback, and a reader who cannot tell that
 * from a refused radio goes hunting a fault that never happened.
 */
fun SmsSendOutcome.breadcrumbEvent(): BreadcrumbEvent = when (this) {
    SmsSendOutcome.SENT -> BreadcrumbEvent.OK
    SmsSendOutcome.NO_KEY,
    SmsSendOutcome.NO_DESTINATION,
    SmsSendOutcome.RATE_LIMITED,
    -> BreadcrumbEvent.DENIED
    SmsSendOutcome.TIMED_OUT,
    SmsSendOutcome.NO_SERVICE,
    SmsSendOutcome.RADIO_OFF,
    SmsSendOutcome.CARRIER_LIMIT,
    SmsSendOutcome.FAILED,
    -> BreadcrumbEvent.FAILED
}

fun SmsSendOutcome.breadcrumbDetails(): List<BreadcrumbDetail> = when (this) {
    SmsSendOutcome.SENT -> emptyList()
    SmsSendOutcome.NO_KEY -> listOf(BreadcrumbDetail.NO_KEY)
    SmsSendOutcome.NO_DESTINATION -> listOf(BreadcrumbDetail.NOT_CONFIGURED)
    SmsSendOutcome.RATE_LIMITED -> listOf(BreadcrumbDetail.RATE_LIMITED)
    SmsSendOutcome.TIMED_OUT -> listOf(BreadcrumbDetail.TIMEOUT)
    SmsSendOutcome.NO_SERVICE -> listOf(BreadcrumbDetail.NO_NETWORK)
    SmsSendOutcome.RADIO_OFF -> listOf(BreadcrumbDetail.RADIO_OFF)
    // A refusal for volume, not a fault: the row says failed because the message did not go,
    // and limited because the reason is a rate rather than anything broken.
    SmsSendOutcome.CARRIER_LIMIT -> listOf(BreadcrumbDetail.RATE_LIMITED)
    SmsSendOutcome.FAILED -> listOf(BreadcrumbDetail.UNKNOWN)
}

/**
 * A breadcrumb destination chosen later than the code that writes to it.
 *
 * The runtime graph is built before there is a file to write into: the recorder belongs to the
 * service, starts after the graph, stops with the service, and the graph is a singleton that
 * outlives any one of those runs. Anything the graph builds therefore has no sink to be given
 * at construction, and a client built without one writes nothing for the life of the process.
 *
 * That is how incident sends came to be the only Telegram traffic the black box never saw. A
 * night of `tg:failed:timeout` looked like a complete account of what the phone tried to send
 * and was in fact only the heartbeat: whether the three incidents of that night were ever
 * attempted is a question the file could not answer either way.
 *
 * Deliberately does nothing until something attaches, and never throws: a crumb must not be
 * able to take down the thing it describes.
 */
class BreadcrumbRelay {

    @Volatile
    private var sink: ((BreadcrumbDomain, BreadcrumbEvent, List<BreadcrumbDetail>) -> Unit)? = null

    fun attach(sink: (BreadcrumbDomain, BreadcrumbEvent, List<BreadcrumbDetail>) -> Unit) {
        this.sink = sink
    }

    fun detach() {
        sink = null
    }

    fun note(
        domain: BreadcrumbDomain,
        event: BreadcrumbEvent,
        details: List<BreadcrumbDetail> = emptyList(),
    ) {
        runCatching { sink?.invoke(domain, event, details) }
    }
}

/**
 * Every value allowed to reach the file, and there is no other way in.
 *
 * The rule this replaces was a sentence in a design document saying not to log tokens, chat
 * ids or coordinates. A `String` parameter turns that sentence into something the next person
 * has to remember, and the first raw Telegram error anyone logs carries a chat id to disk —
 * on a phone whose private storage is in a thief's hands the moment it is stolen, which is
 * the case the file exists for.
 *
 * So there is no free text. An accuracy arrives as a band and never as metres, a failure as a
 * class and never as a message, a delay as a bucket and never as a number. Reviewing this
 * layer for leaks is reading one enum rather than every call site, and it stays that way.
 */
enum class BreadcrumbDetail(val code: String) {
    // How the phone was connected, or was not.
    WIFI("wifi"),
    MOBILE("mobile"),
    OFFLINE("offline"),

    // Why something failed, as a class.
    HTTP_429("http429"),
    HTTP_4XX("http4xx"),
    HTTP_5XX("http5xx"),
    TIMEOUT("timeout"),
    NO_NETWORK("nonet"),
    UNKNOWN("unknown"),

    // Why a fallback did not carry a message, as a class. A reader who finds no SMS after a
    // failed alert has to be able to tell "never set up" from "refused on purpose" from
    // "tried and did not go", because those are three different things to do about it.
    NOT_CONFIGURED("noconf"),
    NO_KEY("nokey"),
    RATE_LIMITED("limited"),
    BELOW_THRESHOLD("lowsev"),
    RADIO_OFF("radiooff"),

    // Which capability. Named for the permission, not for the API that grants it.
    PERM_LOCATION("loc"),
    PERM_MICROPHONE("mic"),
    PERM_NOTIFICATION("notif"),
    PERM_SMS("sms"),
    PERM_BATTERY_UNRESTRICTED("battopt"),

    // How long it took.
    UNDER_2S("lt2s"),
    UNDER_10S("lt10s"),
    OVER_10S("gt10s"),

    // How good a location fix was, in bands.
    ACCURACY_UNDER_10M("lt10"),
    ACCURACY_10_TO_50M("10_50"),
    ACCURACY_OVER_50M("gt50"),
    ;

    companion object {
        /**
         * An HTTP status as a class, which is all a reader can act on and all this may carry.
         *
         * 429 keeps its own value because it is the one an owner can do something about — it
         * means the app is being told to slow down, not that anything is broken. `null` is a
         * request that never got a status: a timeout, a refused socket, no route.
         */
        fun httpClass(code: Int?): BreadcrumbDetail = when {
            code == null -> TIMEOUT
            code == 429 -> HTTP_429
            code in 400..499 -> HTTP_4XX
            code in 500..599 -> HTTP_5XX
            else -> UNKNOWN
        }

        /** How long something took, in the only granularity worth a row. */
        fun latency(ms: Long): BreadcrumbDetail = when {
            ms < 2_000L -> UNDER_2S
            ms < 10_000L -> UNDER_10S
            else -> OVER_10S
        }

        /** How good a fix was. Metres never reach the file; the band does. */
        fun accuracy(metres: Float): BreadcrumbDetail = when {
            metres < 10f -> ACCURACY_UNDER_10M
            metres <= 50f -> ACCURACY_10_TO_50M
            else -> ACCURACY_OVER_50M
        }
    }
}

/**
 * One thing worth remembering about a subsystem, stamped when it happened.
 *
 * Both clocks are taken at the moment the crumb is offered rather than when it reaches the
 * disk. The write is handed to the black box's own thread and may land a moment later; the
 * row must still say when the thing occurred.
 */
data class Breadcrumb(
    val domain: BreadcrumbDomain,
    val event: BreadcrumbEvent,
    val details: List<BreadcrumbDetail> = emptyList(),
    val elapsedMs: Long,
    val wallMs: Long,
) {
    /**
     * The note without a repeat count — also the identity two crumbs are coalesced by.
     *
     * No commas: `BlackBoxCsv` would turn one into a column boundary, and a note that can
     * shift the columns of its own row is a note that can corrupt the file.
     */
    val note: String
        get() = buildString {
            append(domain.code).append(':').append(event.code)
            if (details.isNotEmpty()) {
                append(':')
                details.joinTo(this, separator = ".") { detail -> detail.code }
            }
        }

    companion object {
        /** `net:lost:wifi x40` — the same crumb, and how many more times it happened. */
        fun repeatNote(note: String, repeats: Int): String = "$note x$repeats"

        /** `cap:net:23` — what the hour's ceiling cost, and which domain paid it. */
        fun capNote(domain: BreadcrumbDomain, suppressed: Int): String =
            "cap:${domain.code}:$suppressed"

        /** `drop:queue:7` — crumbs that never reached the ledger because the queue was full. */
        fun queueDropNote(dropped: Int): String = "drop:queue:$dropped"
    }
}
