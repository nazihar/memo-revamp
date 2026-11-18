package com.memo.notification

import java.time.Instant
import java.util.UUID

/** The iTIP method of a calendar part sent by mail (ADR-0063): invitations and updates, and cancellations. */
enum class CalendarMethod { REQUEST, CANCEL }

/**
 * An iCalendar file to send with a mail as iMIP (RFC 6047, ADR-0063): the outbox keeps it encrypted until the mail is
 * sent, and delivery puts it in the message as `text/calendar; method=…` and as `invite.ics`. The producer builds and
 * validates the file; [method] must equal its METHOD.
 */
class CalendarPart(
    val method: CalendarMethod,
    ics: ByteArray,
) {
    private val bytes = ics.copyOf()

    init {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "a calendar part of 1 to $MAX_BYTES bytes" }
    }

    /** A copy of the file's bytes (UTF-8). */
    val ics: ByteArray get() = bytes.copyOf()

    companion object {
        const val MAX_BYTES = 131_072
    }
}

/** What [Notifications.notify] wrote: the in-app item and the queued mail, each null when the preferences said no. */
data class NotificationResult(
    val itemId: UUID?,
    val outboxId: UUID?,
)

enum class MailState { PENDING, SENT, FAILED }

/** Where a queued mail stands (ADR-0030's delivery log), for producers that show it, such as the invitations panel. */
data class MailStatus(
    val outboxId: UUID,
    val state: MailState,
    val attempts: Int,
    /** The relay's last SMTP reply code when it refused or deferred the mail. */
    val smtpCode: Int?,
    val lastAttemptAt: Instant?,
    val sentAt: Instant?,
    val failedAt: Instant?,
    val calendarMethod: CalendarMethod?,
)

/** Delivery states of queued mails by outbox id (design E05 §2); inside a tenant transaction. Unknown ids are left out. */
fun interface MailStatuses {
    fun of(outboxIds: Collection<UUID>): Map<UUID, MailStatus>
}

/**
 * The address the tenant's mail is sent from (its relay's, else the platform's), inside a tenant transaction: the
 * ORGANIZER of meeting invitations (ADR-0063), so calendar replies go to an address the tenant controls.
 */
fun interface SendingAddress {
    fun address(): String
}
