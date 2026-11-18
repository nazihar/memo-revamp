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
 * always). Call inside the producer's tenant transaction: a rolled-back change notifies nobody.
 */
interface Notifications {
    fun notify(
        recipient: Recipient,
        kind: NotificationKind,
        params: Map<String, String> = emptyMap(),
        link: String? = null,
    )
}

/** Finds a user as a recipient; implemented by `identity`, which depends on this module (no cycle). */
fun interface RecipientLookup {
    fun recipient(userId: UUID): Recipient?
}
