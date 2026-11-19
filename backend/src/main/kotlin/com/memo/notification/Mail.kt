package com.memo.notification

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.memo.common.crypto.DataKeys
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.NOTIFICATION_MAIL_OUTBOX
import com.memo.notification.internal.MailSend
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Transactional email templates (ADR-0030). Subject and body per language live in `notification/messages*.properties`;
 * subjects take no parameters and never carry sensitive content (AGENTS.md §3.4). Parameters each body uses:
 * - INVITATION: name, inviter, organisation, link
 * - PASSWORD_RESET: name, organisation, link
 * - PASSWORD_CHANGED, MFA_RESET: name, organisation
 * - SIGN_IN_ALERT: name, organisation, device, time
 * - ROLE_GRANTED: name, organisation, role
 * - TEST: name, organisation
 * - TASK_ASSIGNED, MEMO_RETURNED, MEMO_REJECTED, MEMO_APPROVED, TASK_REASSIGNED, TASK_BLOCKED, MEMO_WITHDRAWN, MEMO_UPDATE,
 *   MEMO_COPIED, MEMO_MENTIONED, TASK_REMINDER, TASK_OVERDUE: name,
 *   organisation, reference, link (never the memo's subject or a remark, design E01 §12)
 * - WATCH_UPDATE: name, organisation, reference, link (a followed memo changed; the user asked for updates at once)
 * - WATCH_DIGEST: name, organisation, count (followed memos with news), link (the Watching tab)
 * - FORUM_MEMBERSHIP_CHANGED: name, organisation, committee, link
 * - FORUM_TERM_ENDING: name, organisation, committee, member, link
 * - FORUM_SUBMISSION_RECEIVED, FORUM_SUBMISSION_TRIAGED, FORUM_SUBMISSION_WAITING: name, organisation, committee, status, link
 * - MEETING_CANCELLED: name, organisation, committee, date, link
 * - MEETING_LATE_PAPER, MEETING_AGENDA_PUBLISHED, MEETING_AGENDA_CHANGED, MEETING_ITEM_WITHDRAWN,
 *   MEETING_DISCUSSION_REQUESTED: name, organisation, committee, date, link (never an item's title)
 * - MEETING_DECLARATION_RECORDED, MEETING_DECLARATION_MADE, MEETING_RULING_NEEDED, MEETING_DECLARATION_RULED,
 *   MEETING_DECLARATIONS_DUE: name, organisation, committee, date, link (never an item's title or a declaration's nature)
 */
enum class MailTemplate {
    INVITATION,
    PASSWORD_RESET,
    PASSWORD_CHANGED,
    MFA_RESET,
    SIGN_IN_ALERT,
    ROLE_GRANTED,
    TEST,
    TASK_ASSIGNED,
    MEMO_RETURNED,
    MEMO_REJECTED,
    MEMO_APPROVED,
    TASK_REASSIGNED,
    TASK_BLOCKED,
    MEMO_WITHDRAWN,
    MEMO_UPDATE,
    MEMO_COPIED,
    MEMO_MENTIONED,
    TASK_REMINDER,
    TASK_OVERDUE,
    WATCH_UPDATE,
    WATCH_DIGEST,
    FORUM_MEMBERSHIP_CHANGED,
    FORUM_TERM_ENDING,
    FORUM_SUBMISSION_RECEIVED,
    FORUM_SUBMISSION_TRIAGED,
    FORUM_SUBMISSION_WAITING,
    MEETING_CANCELLED,
    MEETING_LATE_PAPER,
    MEETING_AGENDA_PUBLISHED,
    MEETING_AGENDA_CHANGED,
    MEETING_ITEM_WITHDRAWN,
    MEETING_DISCUSSION_REQUESTED,
    MEETING_DECLARATION_RECORDED,
    MEETING_DECLARATION_MADE,
    MEETING_RULING_NEEDED,
    MEETING_DECLARATION_RULED,
    MEETING_DECLARATIONS_DUE,
}

/**
 * Queues an email in the caller's tenant transaction and schedules its delivery in the same commit (db-scheduler joins
 * Spring transactions, ADR-0018): a rolled-back business change sends nothing. Parameters are encrypted at rest because
 * they may hold one-time links.
 */
@Service
class Mail(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val keys: DataKeys,
    private val json: JsonMapper,
    private val clock: Clock,
    @param:Qualifier("mailSendTask") private val sendTask: OneTimeTask<MailSend>,
    private val scheduler: ObjectProvider<SchedulerClient>,
) {
    /**
     * [locale] is the recipient's BCP 47 tag; null uses the tenant's default language. A [calendar] part is sent as iMIP
     * (ADR-0063), encrypted at rest like the parameters. Returns the outbox id, by which [MailStatuses] reports delivery.
     */
    fun enqueue(
        template: MailTemplate,
        to: String,
        params: Map<String, String>,
        locale: String? = null,
        calendar: CalendarPart? = null,
    ): UUID {
        val id = ids.next()
        val tenant = tenantDsl.tenant()
        val calendarEnc = calendar?.let { keys.encrypt(it.ics, calendarAad(tenant.toString(), id.toString())) }
        tenantDsl
            .current()
            .insertInto(NOTIFICATION_MAIL_OUTBOX)
            .set(NOTIFICATION_MAIL_OUTBOX.ID, id)
            .set(NOTIFICATION_MAIL_OUTBOX.TENANT_ID, tenant.value)
            .set(NOTIFICATION_MAIL_OUTBOX.TEMPLATE, template.name)
            .set(NOTIFICATION_MAIL_OUTBOX.TO_ADDRESS, to)
            .set(NOTIFICATION_MAIL_OUTBOX.LOCALE, locale)
            .set(NOTIFICATION_MAIL_OUTBOX.PARAMS_ENC, keys.encrypt(json.writeValueAsBytes(params), aad(tenant.toString(), id.toString())))
            .set(NOTIFICATION_MAIL_OUTBOX.CALENDAR_ENC, calendarEnc)
            .set(NOTIFICATION_MAIL_OUTBOX.CALENDAR_METHOD, calendar?.method?.name)
            .set(NOTIFICATION_MAIL_OUTBOX.STATUS, "PENDING")
            .set(NOTIFICATION_MAIL_OUTBOX.CREATED_AT, OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))
            .execute()
        scheduler.ifAvailable?.scheduleIfNotExists(sendTask.schedulableInstance(id.toString(), MailSend(tenant.value, id)))
        return id
    }

    companion object {
        internal fun aad(
            tenant: String,
            id: String,
        ) = "notification.mail:$tenant:$id"

        /** The calendar part's own binding, so it can never be swapped with the row's parameters. */
        internal fun calendarAad(
            tenant: String,
            id: String,
        ) = "notification.mail.calendar:$tenant:$id"
    }
}
