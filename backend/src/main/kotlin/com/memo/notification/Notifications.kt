package com.memo.notification

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
 * Every kind of notification (ADR-0030): its category, the email it sends (null: in-app only) and, implicitly, the web
 * message `notification.kind.<NAME>` that renders it in-app. Parameters are display data only, never secrets.
 */
enum class NotificationKind(
    val category: NotificationCategory,
    val email: MailTemplate?,
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
}

/** Finds a user as a recipient; implemented by `identity`, which depends on this module (no cycle). */
fun interface RecipientLookup {
    fun recipient(userId: UUID): Recipient?
}
