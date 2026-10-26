package com.memo.identity.session

import org.springframework.beans.factory.ObjectProvider
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.Session
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

/** One of a user's sessions as the user sees it: a handle, never the session id (which is the cookie value). */
data class UserSession(
    val handle: String,
    val client: SessionClient?,
    val lastAccessedAt: Instant,
)

/**
 * Lists and revokes a user's sessions through Spring Session's principal index (ADR-0023). Sessions exist only in the
 * web application, so the repository is optional (migrate mode has none).
 */
@Component
class SessionRevocation(
    private val repository: ObjectProvider<FindByIndexNameSessionRepository<out Session>>,
    private val store: SessionStore,
) {
    fun list(indexName: String): List<UserSession> =
        sessions(indexName).map { (id, session) ->
            UserSession(handle(id), store.client(session.getAttribute(SessionStore.CLIENT)), session.lastAccessedTime)
        }

    /** Revokes every session of the user except [keepSessionId] (the current one, when the user acts on their own account). */
    fun revokeAll(
        indexName: String,
        keepSessionId: String? = null,
    ): Int {
        val ids = sessions(indexName).keys.filter { it != keepSessionId }
        ids.forEach { repository.ifAvailable { r -> r.deleteById(it) } }
        return ids.size
    }

    /** Revokes the user's session with [handle]; false when the user has no such session. */
    fun revoke(
        indexName: String,
        handle: String,
    ): Boolean {
        val id = sessions(indexName).keys.firstOrNull { handle(it) == handle }
        id?.let { repository.ifAvailable { r -> r.deleteById(it) } }
        return id != null
    }

    private fun sessions(indexName: String): Map<String, Session> = repository.ifAvailable?.findByPrincipalName(indexName).orEmpty()

    companion object {
        private const val HANDLE_BYTES = 16

        /** A stable, non-reversible handle of a session id. */
        fun handle(sessionId: String): String =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray(Charsets.UTF_8)).copyOf(HANDLE_BYTES))
    }
}
