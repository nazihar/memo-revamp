package com.memo.docedit.internal

import com.memo.files.Files
import com.memo.notification.NotificationKind
import com.memo.notification.Notifications
import com.memo.notification.RecipientLookup
import org.springframework.stereotype.Component

/**
 * Tells the editor's user that their changes did not land (design E02 §3), in-app only: they may have left the editor
 * page before the document server sent the save. Inside the tenant transaction that marks the session failed.
 */
@Component
class EditNotifier(
    private val files: Files,
    private val recipients: RecipientLookup,
    private val notifications: Notifications,
) {
    fun notSaved(session: EditSession) {
        val recipient = recipients.recipient(session.userId) ?: return
        val name = files.version(session.baseVersionId)?.name.orEmpty()
        notifications.notify(recipient, NotificationKind.EDIT_NOT_SAVED, mapOf("name" to name))
    }
}
