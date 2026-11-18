package com.memo.notification.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.NOTIFICATION_MAIL_OUTBOX
import com.memo.notification.CalendarMethod
import com.memo.notification.MailState
import com.memo.notification.MailStatus
import com.memo.notification.MailStatuses
import com.memo.notification.SendingAddress
import org.springframework.stereotype.Component
import java.util.UUID

/** Delivery states from the outbox (ADR-0030's delivery log), in one query; inside the caller's tenant transaction. */
@Component
internal class OutboxStatuses(
    private val tenantDsl: TenantDsl,
) : MailStatuses {
    override fun of(outboxIds: Collection<UUID>): Map<UUID, MailStatus> {
        if (outboxIds.isEmpty()) return emptyMap()
        val o = NOTIFICATION_MAIL_OUTBOX
        return tenantDsl
            .current()
            .select(o.ID, o.STATUS, o.ATTEMPTS, o.SMTP_CODE, o.LAST_ATTEMPT_AT, o.SENT_AT, o.FAILED_AT, o.CALENDAR_METHOD)
            .from(o)
            .where(o.ID.`in`(outboxIds.toSet()))
            .fetch { r ->
                MailStatus(
                    outboxId = r.get(o.ID)!!,
                    state = MailState.valueOf(r.get(o.STATUS)!!),
                    attempts = r.get(o.ATTEMPTS) ?: 0,
                    smtpCode = r.get(o.SMTP_CODE),
                    lastAttemptAt = r.get(o.LAST_ATTEMPT_AT)?.toInstant(),
                    sentAt = r.get(o.SENT_AT)?.toInstant(),
                    failedAt = r.get(o.FAILED_AT)?.toInstant(),
                    calendarMethod = r.get(o.CALENDAR_METHOD)?.let(CalendarMethod::valueOf),
                )
            }.associateBy { it.outboxId }
    }
}

/** The tenant's relay's from address, else the platform's (as [MailDelivery] sends), inside a tenant transaction. */
@Component
internal class TenantSendingAddress(
    private val relays: TenantRelays,
    private val settings: MailSettings,
) : SendingAddress {
    override fun address(): String = relays.current()?.fromAddress ?: platformFrom(settings)
}
