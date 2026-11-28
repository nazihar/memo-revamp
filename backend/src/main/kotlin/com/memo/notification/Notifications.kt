package com.memo.notification

import java.time.Instant
import java.util.UUID

/** A group of notifications that users switch on or off per channel; [mandatory] ones cannot be switched off. */
enum class NotificationCategory(
    val mandatory: Boolean,
) {
    SECURITY(mandatory = true),
    ADMINISTRATION(mandatory = false),

    /** Memo approvals (Phase 2 E01): users may switch email off; the in-app item stays the inbox's companion. */
    WORKFLOW(mandatory = false),

    /** Memos the user follows (Phase 3 E05, flow J): what others did; the email is the user's watch setting (digest by default). */
    WATCHING(mandatory = false),

    /**
     * Meeting invitations, updates and uninvitations with their calendar files (Phase 4 E05, ADR-0063): on by default; a
     * member may switch the email off and keep the in-app item, and then answers and adds meetings in memo.
     */
    CALENDAR(mandatory = false),
}

/** Where a notification goes. `PUSH` is reserved for the mobile apps (Phase 6a) and not delivered yet. */
enum class NotificationChannel { IN_APP, EMAIL, PUSH }

/**
 * Every kind of notification (ADR-0030): its category, the email it sends (null: in-app only), whether it leaves an
 * in-app item ([inApp]; false for an email only, such as a digest) and, implicitly, the web message
 * `notification.kind.<NAME>` that renders it in-app. Parameters are display data only, never secrets.
 */
enum class NotificationKind(
    val category: NotificationCategory,
    val email: MailTemplate?,
    val inApp: Boolean = true,
) {
    SIGN_IN_ALERT(NotificationCategory.SECURITY, MailTemplate.SIGN_IN_ALERT),
    PASSWORD_CHANGED(NotificationCategory.SECURITY, MailTemplate.PASSWORD_CHANGED),
    MFA_RESET(NotificationCategory.SECURITY, MailTemplate.MFA_RESET),
    ROLE_GRANTED(NotificationCategory.ADMINISTRATION, MailTemplate.ROLE_GRANTED),

    // Workflow (design E01 §12): parameters `reference` and `organisation`; the link opens the memo.
    TASK_ASSIGNED(NotificationCategory.WORKFLOW, MailTemplate.TASK_ASSIGNED),
    MEMO_RETURNED(NotificationCategory.WORKFLOW, MailTemplate.MEMO_RETURNED),
    MEMO_REJECTED(NotificationCategory.WORKFLOW, MailTemplate.MEMO_REJECTED),
    MEMO_APPROVED(NotificationCategory.WORKFLOW, MailTemplate.MEMO_APPROVED),
    TASK_REASSIGNED(NotificationCategory.WORKFLOW, MailTemplate.TASK_REASSIGNED),
    TASK_BLOCKED(NotificationCategory.WORKFLOW, MailTemplate.TASK_BLOCKED),
    MEMO_WITHDRAWN(NotificationCategory.WORKFLOW, MailTemplate.MEMO_WITHDRAWN),

    // A definition's notify step (design E03 §2): parameters as above plus `message`, the step's message key.
    MEMO_UPDATE(NotificationCategory.WORKFLOW, MailTemplate.MEMO_UPDATE),

    // Copy-to (Phase 3 E03, ADR-0050): a copy for information; parameters as for the workflow kinds.
    MEMO_COPIED(NotificationCategory.WORKFLOW, MailTemplate.MEMO_COPIED),

    // The note sheet (Phase 3 E03, ADR-0049): someone mentioned the user in a note; parameters as above.
    MEMO_MENTIONED(NotificationCategory.WORKFLOW, MailTemplate.MEMO_MENTIONED),

    // SLAs (design E05 §4.3): a reminder to the holder, and the task overdue (holder and initiator); parameters as above.
    TASK_REMINDER(NotificationCategory.WORKFLOW, MailTemplate.TASK_REMINDER),
    TASK_OVERDUE(NotificationCategory.WORKFLOW, MailTemplate.TASK_OVERDUE),

    // In-browser editing (design E02 §3): a save that could not land; in-app only, parameter `name` (the file's name).
    EDIT_NOT_SAVED(NotificationCategory.WORKFLOW, null),

    // Following (Phase 3 E05 T4): what happened to a followed memo, batched per memo; in-app only (the email is a digest
    // or an update by the user's watch setting). Parameters `reference`, `count`, `reason` (why the user follows it).
    MEMO_WATCHED(NotificationCategory.WATCHING, null),

    // Committees (Phase 4 E01, design E01 §9): a membership changed (the chair, the secretaries, the member), and a term of
    // office ends soon (the secretaries). Parameters `committee`, `organisation`, `link`; `member` for the term reminder.
    FORUM_MEMBERSHIP_CHANGED(NotificationCategory.ADMINISTRATION, MailTemplate.FORUM_MEMBERSHIP_CHANGED),
    FORUM_TERM_ENDING(NotificationCategory.ADMINISTRATION, MailTemplate.FORUM_TERM_ENDING),

    // Submissions (Phase 4 E02, ADR-0059): a paper reached the committee (its secretaries), and it was triaged or decided
    // (its author and presenter). Parameters `committee`, `status`, `organisation`, `link`.
    FORUM_SUBMISSION_RECEIVED(NotificationCategory.WORKFLOW, MailTemplate.FORUM_SUBMISSION_RECEIVED),
    FORUM_SUBMISSION_TRIAGED(NotificationCategory.WORKFLOW, MailTemplate.FORUM_SUBMISSION_TRIAGED),
    FORUM_SUBMISSION_WAITING(NotificationCategory.WORKFLOW, MailTemplate.FORUM_SUBMISSION_WAITING),

    // Meetings (Phase 4 E03, design E03 §1.1): a scheduled meeting was cancelled (its audience). Parameters `committee`,
    // `date` (the meeting's date, ISO), `organisation`, `link`.
    MEETING_CANCELLED(NotificationCategory.WORKFLOW, MailTemplate.MEETING_CANCELLED),

    // A late paper waits for the chair's agreement (design E03 §1.4): to the chair (the vice-chair when there is none).
    // Parameters `committee`, `date`, `organisation`, `link`; never the item's title (ADR-0030).
    MEETING_LATE_PAPER(NotificationCategory.WORKFLOW, MailTemplate.MEETING_LATE_PAPER),

    // Agenda revisions (design E03 §9): the first publication, and a later revision changing what the person sees; to each
    // person of the audience once per revision. Parameters `committee`, `date`, `organisation`, `link`; never an item's title.
    MEETING_AGENDA_PUBLISHED(NotificationCategory.WORKFLOW, MailTemplate.MEETING_AGENDA_PUBLISHED),
    MEETING_AGENDA_CHANGED(NotificationCategory.WORKFLOW, MailTemplate.MEETING_AGENDA_CHANGED),

    // An item of the published agenda left it (its submission was returned or withdrawn): to the secretaries, to publish a
    // revision. Parameters as above.
    MEETING_ITEM_WITHDRAWN(NotificationCategory.WORKFLOW, MailTemplate.MEETING_ITEM_WITHDRAWN),

    // The chair commented on the draft agenda: to the secretaries, in the app only (the comment is on the meeting).
    // Parameters `committee`, `date`.
    MEETING_CHAIR_COMMENTED(NotificationCategory.WORKFLOW, null),

    // A member asked to lift a consent item for discussion (design E03 §1.5): to the secretaries and the chair. Parameters
    // `committee`, `date`, `organisation`, `link`; never the item's title (ADR-0030).
    MEETING_DISCUSSION_REQUESTED(NotificationCategory.WORKFLOW, MailTemplate.MEETING_DISCUSSION_REQUESTED),

    // The hourly meeting sweep (design E03 §9), to the secretaries (the holders of `forums.manage` when there are none),
    // once per meeting date: the papers were due and the agenda is not published; the meeting ended a day ago and is not
    // marked as held. Parameters `committee`, `date`, `organisation`, `link`.
    MEETING_AGENDA_DUE(NotificationCategory.WORKFLOW, MailTemplate.MEETING_AGENDA_DUE),
    MEETING_NOT_HELD(NotificationCategory.WORKFLOW, MailTemplate.MEETING_NOT_HELD),

    // Declarations of interest (Phase 4 E04, ADR-0062, design E04 §2): one recorded or withdrawn for the declarant by
    // someone else (RECORDED); one made or withdrawn, to the officers not recused from the item (MADE); a ruling asked of
    // the chair or vice-chair (RULING_NEEDED); the ruling, to the declarant (RULED); the reminder before the meeting
    // (DUE). Parameters `committee`, `date`, `organisation`, `link`; in the app also `item` (its number) and `change`
    // (DECLARED, RECORDED or WITHDRAWN) or `outcome` (LEAVE or TAKE_PART). Never an item's title or the nature.
    MEETING_DECLARATION_RECORDED(NotificationCategory.WORKFLOW, MailTemplate.MEETING_DECLARATION_RECORDED),
    MEETING_DECLARATION_MADE(NotificationCategory.WORKFLOW, MailTemplate.MEETING_DECLARATION_MADE),
    MEETING_RULING_NEEDED(NotificationCategory.WORKFLOW, MailTemplate.MEETING_RULING_NEEDED),
    MEETING_DECLARATION_RULED(NotificationCategory.WORKFLOW, MailTemplate.MEETING_DECLARATION_RULED),
    MEETING_DECLARATIONS_DUE(NotificationCategory.WORKFLOW, MailTemplate.MEETING_DECLARATIONS_DUE),

    // Meeting invitations (Phase 4 E05, ADR-0063): the invitation, an update (`changes`: what changed, in the recipient's
    // words) and an uninvitation, each email with its iCalendar file; MEETING_CANCELLED carries the CANCEL file for those
    // who were invited. Parameters `committee` (the committee's name, or the type's neutral title), `date`,
    // `organisation`, `link`; never the agenda, an item, a paper or a reason (design E05 §1.2.1).
    MEETING_INVITATION(NotificationCategory.CALENDAR, MailTemplate.MEETING_INVITATION),
    MEETING_UPDATED(NotificationCategory.CALENDAR, MailTemplate.MEETING_UPDATED),
    MEETING_UNINVITED(NotificationCategory.CALENDAR, MailTemplate.MEETING_UNINVITED),

    // Replies (Phase 4 E05 T3, design E05 §1.4, §1.5): an answer recorded on the person's behalf by a secretary (to them);
    // an answer of apologies, or one changed within two working days of the meeting (to the secretaries, in the app only);
    // an alternate asked to attend in a member's place, and released (to the alternate). Parameters `committee`, `date`,
    // `organisation`, `link`; `member` (the member's name) for the alternate and the secretaries; in the app also `answer`
    // (IN_PERSON, REMOTE or APOLOGIES) and `how` (EMAIL, PHONE, IN_PERSON or OTHER). Never a note.
    MEETING_RSVP_RECORDED(NotificationCategory.WORKFLOW, MailTemplate.MEETING_RSVP_RECORDED),
    MEETING_RSVP_CHANGED(NotificationCategory.WORKFLOW, null),
    MEETING_ALTERNATE_ASKED(NotificationCategory.WORKFLOW, MailTemplate.MEETING_ALTERNATE_ASKED),
    MEETING_ALTERNATE_RELEASED(NotificationCategory.WORKFLOW, MailTemplate.MEETING_ALTERNATE_RELEASED),

    // Live quorum (Phase 4 E05 T4, design E05 §1.7): the meeting in progress lost its quorum, or has it again, to the chair
    // and the secretaries, in the app only (they are in the room). Parameters `committee`, `date`, `organisation`, `link`,
    // `present` and `needed` (numbers). Never an item.
    MEETING_QUORUM_LOST(NotificationCategory.WORKFLOW, null),
    MEETING_QUORUM_RESTORED(NotificationCategory.WORKFLOW, null),

    // A decision recorded without a quorum (Phase 4 E05 T5, ADR-0064): to the chair and the other secretaries, in the app
    // only, "to be ratified at a quorate meeting". Parameters as the two above. Never the item or the reason.
    MEETING_QUORUM_OVERRIDDEN(NotificationCategory.WORKFLOW, null),

    // The sweep's reminders (Phase 4 E05 T6, design E05 §9): on the reply deadline, to those who have not answered
    // (REPLY_REMINDER); the working day before, to those who said yes or did not answer, with the reply and E04's
    // declarations lines where they apply (REMINDER); the forecast not quorate after the deadline, to the secretaries and
    // the chair (QUORUM_AT_RISK). Parameters `committee`, `date`, `organisation`, `link`; for the reminder also `reply`
    // and `declarations` (the email's sentences, empty when they do not apply) and, for the app, `unanswered` ("true"
    // without an answer for the current time) and `toReview` (items to review for declarations). Never an item or a paper.
    MEETING_REPLY_REMINDER(NotificationCategory.WORKFLOW, MailTemplate.MEETING_REPLY_REMINDER),
    MEETING_REMINDER(NotificationCategory.WORKFLOW, MailTemplate.MEETING_REMINDER),
    MEETING_QUORUM_AT_RISK(NotificationCategory.WORKFLOW, MailTemplate.MEETING_QUORUM_AT_RISK),

    // Minutes (Phase 4 E06 T6, design E06 §2): the draft to review (the reviewer), sent back with comments or agreed (the
    // secretaries), circulated for comments (the readers, with `until`), comments asked of someone (with `until`), the
    // deadline today (readers who have not answered, with `until`), ready to approve (the confirmer, also the sweep's
    // reminder), approved (the readers). In the app also: a comment added (the secretaries and the chair; `kind` NO_COMMENTS
    // or SUGGESTION), the draft overdue (the secretaries), the draft made (the requester), decisions not sent to their memos
    // after the last retry (the secretaries; `count`). Parameters `committee`, `date` (the meeting's), `organisation`, `link`;
    // never an item's title, a comment or a word of the minutes.
    MINUTES_REVIEW_REQUESTED(NotificationCategory.WORKFLOW, MailTemplate.MINUTES_REVIEW_REQUESTED),
    MINUTES_RETURNED(NotificationCategory.WORKFLOW, MailTemplate.MINUTES_RETURNED),
    MINUTES_AGREED(NotificationCategory.WORKFLOW, null),
    MINUTES_CIRCULATED(NotificationCategory.WORKFLOW, MailTemplate.MINUTES_CIRCULATED),
    MINUTES_COMMENTS_REQUESTED(NotificationCategory.WORKFLOW, MailTemplate.MINUTES_COMMENTS_REQUESTED),
    MINUTES_COMMENTS_CLOSING(NotificationCategory.WORKFLOW, MailTemplate.MINUTES_COMMENTS_CLOSING),
    MINUTES_CONFIRMATION_REQUESTED(NotificationCategory.WORKFLOW, MailTemplate.MINUTES_CONFIRMATION_REQUESTED),
    MINUTES_APPROVED(NotificationCategory.WORKFLOW, MailTemplate.MINUTES_APPROVED),

    // A correction to approved minutes proposed by a secretary (E06 T8), to the approver (the chair, else the vice-chair):
    // the same parameters; never the correction's text, reason or item.
    MINUTES_CORRECTION_PROPOSED(NotificationCategory.WORKFLOW, MailTemplate.MINUTES_CORRECTION_PROPOSED),
    MINUTES_COMMENT_ADDED(NotificationCategory.WORKFLOW, null),
    MINUTES_DRAFT_OVERDUE(NotificationCategory.WORKFLOW, null),
    MINUTES_DRAFT_READY(NotificationCategory.WORKFLOW, null),

    // The approved minutes' record, or a reader's approved copy of it, failed after its job's last try (to the committee's
    // secretaries not recused from any item, else all of them; once per record version, in the app only): the sweep asks
    // again daily and a secretary can "Make the record again". The minutes' parameters; never an item or a word of them.
    MINUTES_RECORD_FAILED(NotificationCategory.WORKFLOW, null),
    DECISIONS_RELEASE_FAILED(NotificationCategory.WORKFLOW, null),

    // Action items (Phase 4 E07, design E07 §2.3): a committee gave the person an action (to the holder of the owner's
    // task, when it is issued); the decision an action came from was corrected (to the committee's secretaries not recused
    // from the item, in the app only). Parameters `committee`, `reference`, `organisation`, `link`; `date` (the due date,
    // ISO) for the new action. Never the action's title, text or the item (ADR-0067).
    ACTION_ASSIGNED(NotificationCategory.WORKFLOW, MailTemplate.ACTION_ASSIGNED),
    ACTION_SOURCE_CORRECTED(NotificationCategory.WORKFLOW, null),

    // A follow-up memo of an action was decided (Phase 4 E07 T6, design E07 §1.11): to the holder of the owner's task, in
    // the app (the email comes in the daily digest, T5). Parameters `committee`, `reference`, `organisation`, `link` and
    // `outcome` (APPROVED, REJECTED, WITHDRAWN or CANCELLED). Never the memo's or the action's text.
    ACTION_MEMO_DECIDED(NotificationCategory.WORKFLOW, null),

    // Progress, dates, owners and endings (E07 T4, design E07 §2.3): the owner of an action changed (to the previous and
    // the new owner, in the app and by email); a secretary recorded the risk accepted on the chair's behalf (to the chair,
    // by email at once). In the app only, their emails in the daily digest from T5: the action was cancelled or reopened
    // (to its owner and contributors), a new date was asked for (to whoever decides), the request was decided (to the
    // owner; `decision` AGREED or DECLINED), the date changed (to the owner and contributors; `date` the new date, ISO).
    ACTION_OWNER_CHANGED(NotificationCategory.WORKFLOW, MailTemplate.ACTION_OWNER_CHANGED),
    ACTION_RISK_ACCEPTED(NotificationCategory.WORKFLOW, MailTemplate.ACTION_RISK_ACCEPTED),
    ACTION_CANCELLED(NotificationCategory.WORKFLOW, null),
    ACTION_REOPENED(NotificationCategory.WORKFLOW, null),
    ACTION_DUE_REQUESTED(NotificationCategory.WORKFLOW, null),
    ACTION_DUE_DECIDED(NotificationCategory.WORKFLOW, null),
    ACTION_DUE_CHANGED(NotificationCategory.WORKFLOW, null),

    // Reminders, escalation and closure (E07 T5, design E07 §1.8, §1.9). In the app only, their emails in the daily
    // digest: due soon (the holder, 5 working days before by default); overdue (`stage` DUE: the holder and the
    // secretaries; REMINDER: the holder again; ESCALATED: the secretaries, when the escalation was sent); to verify (the
    // verifiers, when the owner marks it done, and their reminders); closed (the owner and contributors); no running
    // workflow (the secretaries, found by the daily check). Parameters `committee`, `reference`, `organisation`, `link`,
    // `date` (the due date, ISO) and, for the overdue notice, `stage`.
    ACTION_DUE_SOON(NotificationCategory.WORKFLOW, null),
    ACTION_OVERDUE(NotificationCategory.WORKFLOW, null),
    ACTION_TO_VERIFY(NotificationCategory.WORKFLOW, null),
    ACTION_CLOSED(NotificationCategory.WORKFLOW, null),
    ACTION_NOT_RUNNING(NotificationCategory.WORKFLOW, null),

    // In the app and by email at once: an overdue action escalated (to the holder's supervisor by the reporting line, or
    // the definition's target such as the chair; `holder` the holder's name with the owner position's title, `days` the
    // working days overdue); the owner's work sent back by the verifier (to the holder; never the remark).
    ACTION_ESCALATED(NotificationCategory.WORKFLOW, MailTemplate.ACTION_ESCALATED),
    ACTION_SENT_BACK(NotificationCategory.WORKFLOW, MailTemplate.ACTION_SENT_BACK),

    // The daily digest (E07 T5, design E07 §9): one email a day at most with the references of the action notices since the
    // last one, by kind (`lines`, sentences in the recipient's language); email only.
    ACTIONS_DIGEST(NotificationCategory.WORKFLOW, MailTemplate.ACTIONS_DIGEST, inApp = false),

    // An update asked for before the committee's next papers (E07 T7, design E07 §1.12, §9 (b)), in the app and by email at
    // once, to the holder of an open action with no update since the previous meeting: by the daily task the committee
    // type's `updateRequestWorkingDays` before the next meeting's agenda deadline, or by a secretary's "Ask owners for an
    // update". Parameters `committee`, `reference`, `organisation`, `link`, `date` (the due date, ISO), `when` (DEADLINE or
    // NEXT) and `deadline` (the agenda deadline, ISO, when there is one).
    ACTION_UPDATE_REQUESTED(NotificationCategory.WORKFLOW, MailTemplate.ACTION_UPDATE_REQUESTED),
}

/** Who receives a notification; the producer already holds the user, so `notification` never looks people up. */
data class Recipient(
    val userId: UUID,
    val email: String,
    val displayName: String,
    /** BCP 47 tag, or null for the tenant default. */
    val locale: String?,
)

/**
 * Notifies one user (ADR-0030): an in-app item and an email, each as the user's preferences allow (mandatory categories
 * always). Call inside the producer's tenant transaction: a rolled-back change notifies nobody. A [calendar] part goes
 * with the email only (a kind with an email template); with the email switched off there is no mail and no part.
 */
interface Notifications {
    fun notify(
        recipient: Recipient,
        kind: NotificationKind,
        params: Map<String, String> = emptyMap(),
        link: String? = null,
        calendar: CalendarPart? = null,
    ): NotificationResult

    /**
     * Each of [requests] as [notify] would notify it, in the caller's transaction, the recipients' preferences read and the
     * items and emails written a chunk at a time (a daily digest to thousands of people); the results in order.
     */
    fun notifyAll(requests: List<NotificationRequest>): List<NotificationResult> =
        requests.map { notify(it.recipient, it.kind, it.params, it.link, it.calendar) }
}

/** One notification for [Notifications.notifyAll]: as [Notifications.notify]'s arguments. */
data class NotificationRequest(
    val recipient: Recipient,
    val kind: NotificationKind,
    val params: Map<String, String> = emptyMap(),
    val link: String? = null,
    val calendar: CalendarPart? = null,
)

/** One in-app notification as it was written, for a module that sums up its own kinds (a digest). */
data class SentNotification(
    val userId: UUID,
    val kind: NotificationKind,
    val params: Map<String, String>,
    val createdAt: Instant,
)

/**
 * The in-app notifications written in the tenant (ADR-0030), read by a module that sends a digest of its own kinds (E07
 * T5's daily digest of action notices). Inside a tenant transaction; ids and display parameters only.
 */
fun interface NotificationLog {
    /** The items of [kinds] written in [from, to), oldest first, at most [limit]. */
    fun written(
        kinds: Set<NotificationKind>,
        from: Instant,
        to: Instant,
        limit: Int,
    ): List<SentNotification>
}

/** Finds a user as a recipient; implemented by `identity`, which depends on this module (no cycle). */
fun interface RecipientLookup {
    fun recipient(userId: UUID): Recipient?

    /** Each of [userIds] found, as [recipient] finds them; an implementation may read them in one query. */
    fun recipients(userIds: Collection<UUID>): Map<UUID, Recipient> = userIds.mapNotNull { id -> recipient(id)?.let { id to it } }.toMap()
}
