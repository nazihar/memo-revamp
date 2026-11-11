package com.memo.docedit.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.ForbiddenException
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.common.web.refuseIf
import com.memo.docedit.DocumentEditing
import com.memo.docedit.EditMode
import com.memo.docedit.EditableFilePurpose
import com.memo.files.FilePurpose
import com.memo.files.Files
import com.memo.identity.UserSummaries
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

/** The file does not exist, is not editable, or the actor may not see it (404, no existence leak). */
class EditNotFoundException : RuntimeException("not found")

/** Editing is not configured on this installation (404 on every editing endpoint). */
class EditingOffException : RuntimeException("editing is off")

/** An opened editor: the session and the signed configuration for the browser. */
data class OpenedEditor(
    val sessionId: UUID,
    val config: Map<String, Any?>,
)

/**
 * Edit sessions (design E02 §3): the file's purpose decides who may edit or view; a user has at most one open edit
 * session per file, reused while it is on the current version. Inside a tenant transaction.
 */
@Service
class EditSessions(
    private val properties: DocEditProperties,
    private val store: EditSessionStore,
    private val purposes: List<FilePurpose>,
    private val files: Files,
    private val users: UserSummaries,
    private val adapter: EditorAdapter,
    private val keys: DocumentKeys,
    private val tenantDsl: TenantDsl,
    private val audit: AuditLog,
    private val ids: IdGenerator,
    private val clock: Clock,
) : DocumentEditing {
    override val enabled: Boolean get() = properties.enabled

    override fun beingEdited(fileId: UUID): Boolean = enabled && store.anyOpenEdit(fileId, clock.instant())

    fun open(
        fileId: UUID,
        mode: EditMode,
        language: String,
    ): OpenedEditor {
        if (!enabled) throw EditingOffException()
        val user = currentUser()
        val file = files.info(fileId) ?: notFound()
        val purpose =
            purposes.filterIsInstance<EditableFilePurpose>().singleOrNull { it.key == file.purpose } ?: notFound()
        if (!purpose.mayRead(file)) notFound()
        if (mode == EditMode.EDIT && !purpose.mayEdit(file)) throw ForbiddenException(null)
        val version = file.currentVersionId?.let { files.version(it) } ?: notFound()
        val now = clock.instant()
        val reusable = if (mode == EditMode.EDIT) usableOpenEdit(fileId, user, version.id) else null
        val session =
            reusable ?: run {
                refuseIf(mode == EditMode.EDIT && store.openEdits(now) >= properties.maxSessionsPerTenant, "TOO_MANY_EDITORS") {
                    "Too many documents are open for editing in your organisation. Try again later."
                }
                val tenant = tenantDsl.tenant().value
                EditSession(
                    id = ids.next(),
                    tenantId = tenant,
                    fileId = fileId,
                    baseVersionId = version.id,
                    documentKey = keys.documentKey(tenant, fileId, version.id),
                    mode = mode,
                    userId = user,
                    state = SessionState.OPEN,
                    reason = null,
                    savedVersionId = null,
                    expiresAt = now.plus(properties.sessionLifetime),
                ).also {
                    store.insert(it, now)
                    audit.record(
                        "docedit.session.opened",
                        OBJECT_TYPE,
                        it.id.toString(),
                        after = mapOf("fileId" to fileId.toString(), "versionId" to version.id.toString(), "mode" to mode.name),
                    )
                }
            }
        val name = users.of(user)?.displayName.orEmpty()
        return OpenedEditor(session.id, adapter.config(EditorSubject(session, version.name, name, language)))
    }

    /** The actor's own session, for the editor page to learn how the save went. */
    fun own(id: UUID): EditSession = store.find(id)?.takeIf { it.userId == currentUser() } ?: notFound()

    /**
     * The user's open edit session of the file if it can be resumed: on the current version and not expired. Any other
     * open one is closed first (expired, or left behind by a newer upload), so the one-open-session rule holds.
     */
    private fun usableOpenEdit(
        file: UUID,
        user: UUID,
        current: UUID,
    ): EditSession? {
        val open = store.openEdit(file, user)
        val now = clock.instant()
        val usable = open?.takeIf { it.baseVersionId == current && it.expiresAt.isAfter(now) }
        if (open != null && usable == null) {
            val reason = if (open.expiresAt.isAfter(now)) "SUPERSEDED" else "EXPIRED"
            store.finish(open.id, SessionState.CLOSED, reason, null, now)
            audit.record(
                "docedit.session.closed",
                OBJECT_TYPE,
                open.id.toString(),
                after = mapOf("outcome" to "CLOSED", "reason" to reason),
            )
        }
        return usable
    }

    private fun notFound(): Nothing = throw EditNotFoundException()

    private fun currentUser(): UUID = (ActorContext.current().actor as? Actor.User)?.userId ?: notFound()

    companion object {
        const val OBJECT_TYPE = "docedit.session"
    }
}
