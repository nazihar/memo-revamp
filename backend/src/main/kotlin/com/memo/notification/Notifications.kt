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

    // In-browser editing (design E02 §3): a save that could not land; in-app only, parameter `name` (the file's name).
    EDIT_NOT_SAVED(NotificationCategory.WORKFLOW, null),
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
