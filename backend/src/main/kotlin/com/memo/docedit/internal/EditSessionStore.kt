package com.memo.docedit.internal

import com.memo.common.tenant.TenantDsl
import com.memo.docedit.EditMode
import com.memo.jooq.tables.records.DoceditSessionRecord
import com.memo.jooq.tables.references.DOCEDIT_SESSION
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * How an edit session ended, or that it is still open (design E02 §2). `EXPIRED`: left open past its lifetime; it no
 * longer blocks submit, and a late save may still land.
 */
enum class SessionState { OPEN, SAVED, CLOSED, FAILED, EXPIRED }

data class EditSession(
    val id: UUID,
    val tenantId: UUID,
    val fileId: UUID,
    val baseVersionId: UUID,
    val documentKey: String,
    val mode: EditMode,
    val userId: UUID,
    val state: SessionState,
    val reason: String?,
    val savedVersionId: UUID?,
    val expiresAt: Instant,
)

/** Edit sessions (design E02 §2). Every method runs inside a tenant transaction. */
@Repository
class EditSessionStore(
    private val tenantDsl: TenantDsl,
) {
    fun insert(
        session: EditSession,
        now: Instant,
    ) {
        tenantDsl
            .current()
            .insertInto(DOCEDIT_SESSION)
            .set(DOCEDIT_SESSION.ID, session.id)
            .set(DOCEDIT_SESSION.TENANT_ID, session.tenantId)
            .set(DOCEDIT_SESSION.FILE_ID, session.fileId)
            .set(DOCEDIT_SESSION.BASE_VERSION_ID, session.baseVersionId)
            .set(DOCEDIT_SESSION.DOCUMENT_KEY, session.documentKey)
            .set(DOCEDIT_SESSION.MODE, session.mode.name)
            .set(DOCEDIT_SESSION.USER_ID, session.userId)
            .set(DOCEDIT_SESSION.STATE, session.state.name)
            .set(DOCEDIT_SESSION.CREATED_AT, utc(now))
            .set(DOCEDIT_SESSION.LAST_ACTIVITY_AT, utc(now))
            .set(DOCEDIT_SESSION.EXPIRES_AT, utc(session.expiresAt))
            .execute()
    }

    fun find(id: UUID): EditSession? =
        tenantDsl
            .current()
            .selectFrom(DOCEDIT_SESSION)
            .where(DOCEDIT_SESSION.ID.eq(id))
            .fetchOne()
            ?.toSession()

    /** The session row locked until commit, so two callbacks of one session are handled one after the other. */
    fun lock(id: UUID): EditSession? =
        tenantDsl
            .current()
            .selectFrom(DOCEDIT_SESSION)
            .where(DOCEDIT_SESSION.ID.eq(id))
            .forUpdate()
            .fetchOne()
            ?.toSession()

    /** [user]'s open edit session of [file], expired or not. */
    fun openEdit(
        file: UUID,
        user: UUID,
    ): EditSession? =
        tenantDsl
            .current()
            .selectFrom(DOCEDIT_SESSION)
            .where(DOCEDIT_SESSION.FILE_ID.eq(file), DOCEDIT_SESSION.USER_ID.eq(user))
            .and(DOCEDIT_SESSION.STATE.eq(SessionState.OPEN.name))
            .and(DOCEDIT_SESSION.MODE.eq(EditMode.EDIT.name))
            .fetchOne()
            ?.toSession()

    /** Whether any edit session of [file] is open and not expired at [now]. */
    fun anyOpenEdit(
        file: UUID,
        now: Instant,
    ): Boolean =
        tenantDsl.current().fetchExists(
            DOCEDIT_SESSION,
            DOCEDIT_SESSION.FILE_ID
                .eq(file)
                .and(DOCEDIT_SESSION.STATE.eq(SessionState.OPEN.name))
                .and(DOCEDIT_SESSION.MODE.eq(EditMode.EDIT.name))
                .and(DOCEDIT_SESSION.EXPIRES_AT.gt(utc(now))),
        )

    /** Open, unexpired edit sessions in the tenant (the cap of design E02 §10). */
    fun openEdits(now: Instant): Int =
        tenantDsl.current().fetchCount(
            DOCEDIT_SESSION,
            DOCEDIT_SESSION.STATE
                .eq(SessionState.OPEN.name)
                .and(DOCEDIT_SESSION.MODE.eq(EditMode.EDIT.name))
                .and(DOCEDIT_SESSION.EXPIRES_AT.gt(utc(now))),
        )

    fun touch(
        id: UUID,
        now: Instant,
    ) {
        tenantDsl
            .current()
            .update(DOCEDIT_SESSION)
            .set(DOCEDIT_SESSION.LAST_ACTIVITY_AT, utc(now))
            .where(DOCEDIT_SESSION.ID.eq(id))
            .execute()
    }

    /** Open sessions past their lifetime, for the expiry job. */
    fun due(
        now: Instant,
        limit: Int,
    ): List<UUID> =
        tenantDsl
            .current()
            .select(DOCEDIT_SESSION.ID)
            .from(DOCEDIT_SESSION)
            .where(DOCEDIT_SESSION.STATE.eq(SessionState.OPEN.name), DOCEDIT_SESSION.EXPIRES_AT.le(utc(now)))
            .limit(limit)
            .fetch(DOCEDIT_SESSION.ID)
            .filterNotNull()

    /** After a save that keeps the editor open (force save): later saves build on [version]. */
    fun rebase(
        id: UUID,
        version: UUID,
        now: Instant,
    ): Boolean =
        tenantDsl
            .current()
            .update(DOCEDIT_SESSION)
            .set(DOCEDIT_SESSION.BASE_VERSION_ID, version)
            .set(DOCEDIT_SESSION.LAST_ACTIVITY_AT, utc(now))
            .set(DOCEDIT_SESSION.VERSION, DOCEDIT_SESSION.VERSION.plus(1))
            .where(DOCEDIT_SESSION.ID.eq(id), DOCEDIT_SESSION.STATE.`in`(LIVE))
            .execute() == 1

    /** Ends the session as [state]; [savedVersion] with `SAVED`. Only an open or expired session changes. */
    fun finish(
        id: UUID,
        state: SessionState,
        reason: String?,
        savedVersion: UUID?,
        now: Instant,
    ): Boolean =
        tenantDsl
            .current()
            .update(DOCEDIT_SESSION)
            .set(DOCEDIT_SESSION.STATE, state.name)
            .set(DOCEDIT_SESSION.REASON, reason)
            .set(DOCEDIT_SESSION.SAVED_VERSION_ID, savedVersion)
            .set(DOCEDIT_SESSION.LAST_ACTIVITY_AT, utc(now))
            .set(DOCEDIT_SESSION.VERSION, DOCEDIT_SESSION.VERSION.plus(1))
            .where(DOCEDIT_SESSION.ID.eq(id), DOCEDIT_SESSION.STATE.`in`(LIVE))
            .execute() == 1

    private companion object {
        /** States a save may still end: an expired editor may have been open overnight (design E02 §2). */
        val LIVE = listOf(SessionState.OPEN.name, SessionState.EXPIRED.name)
    }
}

private fun utc(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)

private fun DoceditSessionRecord.toSession() =
    EditSession(
        id = id,
        tenantId = tenantId,
        fileId = fileId,
        baseVersionId = baseVersionId,
        documentKey = documentKey,
        mode = EditMode.valueOf(mode),
        userId = userId,
        state = SessionState.valueOf(state),
        reason = reason,
        savedVersionId = savedVersionId,
        expiresAt = expiresAt.toInstant(),
    )
