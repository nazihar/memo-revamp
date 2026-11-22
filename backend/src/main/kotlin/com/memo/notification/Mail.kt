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
 * - MEETING_AGENDA_DUE, MEETING_NOT_HELD: name, organisation, committee, date, link
 * - MEETING_DECLARATION_RECORDED, MEETING_DECLARATION_MADE, MEETING_RULING_NEEDED, MEETING_DECLARATION_RULED,
 *   MEETING_DECLARATIONS_DUE: name, organisation, committee, date, link (never an item's title or a declaration's nature)
 * - MEETING_INVITATION, MEETING_UNINVITED: name, organisation, committee, date, link (with the iCalendar file)
 * - MEETING_UPDATED: name, organisation, committee, date, changes (what changed, in words), link (with the file)
 * - MEETING_RSVP_RECORDED: name, organisation, committee, date, link (never the answer's note)
 * - MEETING_ALTERNATE_ASKED, MEETING_ALTERNATE_RELEASED: name, organisation, committee, date, member, link
 * - MEETING_REPLY_REMINDER, MEETING_QUORUM_AT_RISK: name, organisation, committee, date, link
 * - MEETING_REMINDER: name, organisation, committee, date, reply and declarations (sentences, empty when they do not
 *   apply), link (never an item or a paper)
 * - MINUTES_REVIEW_REQUESTED, MINUTES_RETURNED, MINUTES_CONFIRMATION_REQUESTED, MINUTES_APPROVED: name, organisation,
 *   committee, date (the meeting's), link (never an item or a word of the minutes, design E06 §2)
 * - MINUTES_CIRCULATED, MINUTES_COMMENTS_REQUESTED, MINUTES_COMMENTS_CLOSING: as above, and until (the comment deadline)
 * - ACTION_ASSIGNED: name, organisation, committee, reference, date (the due date), link (never the action's title or text)
 * - ACTION_OWNER_CHANGED: name, organisation, committee, reference, date (the due date), link (to the previous and the new owner)
 * - ACTION_RISK_ACCEPTED: name, organisation, committee, reference, link (to the chair, recorded on their behalf)
 * - ACTION_ESCALATED: name, organisation, committee, reference, date (the due date), holder (the name, and the owner
 *   position's title), days (working days overdue), link (to the holder's supervisor or the definition's target)
 * - ACTION_SENT_BACK: name, organisation, committee, reference, date, link (to the holder; never the verifier's remark)
 * - ACTIONS_DIGEST: name, organisation, lines (the references by kind, in the recipient's language), link (My actions)
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
    MEETING_AGENDA_DUE,
    MEETING_NOT_HELD,
    MEETING_DECLARATION_RECORDED,
    MEETING_DECLARATION_MADE,
    MEETING_RULING_NEEDED,
    MEETING_DECLARATION_RULED,
    MEETING_DECLARATIONS_DUE,
    MEETING_INVITATION,
    MEETING_UPDATED,
    MEETING_UNINVITED,
    MEETING_RSVP_RECORDED,
    MEETING_ALTERNATE_ASKED,
    MEETING_ALTERNATE_RELEASED,
    MEETING_REPLY_REMINDER,
    MEETING_REMINDER,
    MEETING_QUORUM_AT_RISK,
    MINUTES_REVIEW_REQUESTED,
    MINUTES_RETURNED,
    MINUTES_CIRCULATED,
    MINUTES_COMMENTS_REQUESTED,
    MINUTES_COMMENTS_CLOSING,
    MINUTES_CONFIRMATION_REQUESTED,
    MINUTES_APPROVED,
    ACTION_ASSIGNED,
    ACTION_OWNER_CHANGED,
    ACTION_RISK_ACCEPTED,
    ACTION_ESCALATED,
    ACTION_SENT_BACK,
    ACTIONS_DIGEST,
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
     * False only while a test delivers the outbox itself (`MailDelivery.send`): test contexts share the scheduler table, so
     * a delivery scheduled here could run in another context.
     */
    @Volatile internal var jobs: Boolean = true

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
        if (jobs) scheduler.ifAvailable?.scheduleIfNotExists(sendTask.schedulableInstance(id.toString(), MailSend(tenant.value, id)))
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
