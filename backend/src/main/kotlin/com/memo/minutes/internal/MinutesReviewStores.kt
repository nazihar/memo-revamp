package com.memo.minutes.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.records.MinutesApprovalRecord
import com.memo.jooq.tables.records.MinutesCommentRecord
import com.memo.jooq.tables.records.MinutesCopyRecord
import com.memo.jooq.tables.references.MINUTES_APPROVAL
import com.memo.jooq.tables.references.MINUTES_COMMENT
import com.memo.jooq.tables.references.MINUTES_COMMENT_REQUEST
import com.memo.jooq.tables.references.MINUTES_COPY
import com.memo.jooq.tables.references.MINUTES_REMINDER
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** A reader's answer to circulated minutes (design E06 §1.6.3): "No comments" once, or a suggestion. */
enum class CommentKind { NO_COMMENTS, SUGGESTION }

/** What the secretary did with a suggestion, once. */
enum class CommentResponse { ADOPTED, NOT_ADOPTED }

/** One comment as stored; [text] and [responseNote] are free text, never audited. */
data class CommentRow(
    val id: UUID,
    val meetingId: UUID,
    val userId: UUID,
    val itemId: UUID?,
    val kind: CommentKind,
    val text: String?,
    val at: Instant,
    val response: CommentResponse? = null,
    val responseNote: String? = null,
    val respondedBy: UUID? = null,
    val respondedAt: Instant? = null,
)

/** Someone asked for comments on named items of the minutes. */
data class CommentRequestRow(
    val id: UUID,
    val meetingId: UUID,
    val userId: UUID,
    val itemIds: List<UUID>,
    val askedBy: UUID,
    val askedAt: Instant,
)

/** How an item is redacted in a reader's copy (ADR-0066). */
enum class RedactionKind {
    /** The reader declared an interest: the item's heading, "You declared an interest …" and the decision. */
    RECUSED,

    /** The reader may not see it: the heading when they see its title, else "Item n — restricted". */
    RESTRICTED,

    /** Not even its title: "Item n — restricted". */
    HIDDEN,
}

/** One item of a redaction set. */
data class Redaction(
    val itemId: UUID,
    val kind: RedactionKind,
)

/** Which copy a reader gets (ADR-0066): the draft's while in review and circulation, the approved one afterwards (T7). */
enum class CopyStage { DRAFT, APPROVED }

enum class CopyState { PENDING, DONE, FAILED }

/** A reader's copy for one redaction set and version of the draft. */
data class CopyRow(
    val id: UUID,
    val meetingId: UUID,
    val stage: CopyStage,
    val redacted: List<Redaction>,
    val setKey: String,
    val sourceVersionId: UUID,
    val state: CopyState,
    val error: String?,
    val fileId: UUID?,
    val fileVersionId: UUID?,
    val sha256: String?,
    val createdAt: Instant,
    val builtAt: Instant?,
    val version: Long,
    /** The record version an APPROVED copy belongs to (E06 T7); null for a draft's copy. */
    val recordVersion: Int? = null,
    /** veraPDF's verdict on an APPROVED copy (a PDF/A record of its own), once built. */
    val compliant: Boolean? = null,
    val validation: Map<String, Any?>? = null,
    /** When an APPROVED copy last failed (null: never, or before the sweep kept it). */
    val failedAt: Instant? = null,
    /** How many times an APPROVED copy was asked for again after failing (the sweep, a secretary). */
    val retries: Int = 0,
)

/** The chair's authenticated confirmation of the minutes (ADR-0051), as stored. */
data class ApprovalRow(
    val id: UUID,
    val meetingId: UUID,
    val forumId: UUID,
    val kind: String,
    val actorId: UUID,
    val role: MinutesRole,
    val authentication: JsonNode,
    val client: JsonNode,
    val docxVersionId: UUID,
    val docxSha256: String,
    val previousHash: String?,
    val recordHash: String,
    val at: Instant,
    /** A CORRECTION approval (E06 T8): the correction it approves and the SHA-256 of what it says. */
    val correctionId: UUID? = null,
    val correctionSha256: String? = null,
)

/** The sweep's reminders on minutes (design E06 §9 (j)–(l)). */
enum class MinutesReminderKind { COMMENTS_CLOSING, DRAFT_OVERDUE, CONFIRMATION, RECORD_FAILED }

/** Comments and the requests for them (design E06 §1.5), inside a tenant transaction. */
@Repository
class MinutesCommentStore(
    private val tenantDsl: TenantDsl,
) {
    /** Writes [row]; false when it is a second "No comments" of the reader (the unique index decides). */
    fun insert(row: CommentRow): Boolean {
        val c = MINUTES_COMMENT
        return tenantDsl
            .current()
            .insertInto(c)
            .set(c.ID, row.id)
            .set(c.TENANT_ID, tenantDsl.tenant().value)
            .set(c.MEETING_ID, row.meetingId)
            .set(c.USER_ID, row.userId)
            .set(c.ITEM_ID, row.itemId)
            .set(c.KIND, row.kind.name)
            .set(c.TEXT, row.text)
            .set(c.AT, row.at.atOffset(ZoneOffset.UTC))
            .onConflictDoNothing()
            .execute() == 1
    }

    fun find(id: UUID): CommentRow? =
        tenantDsl
            .current()
            .selectFrom(MINUTES_COMMENT)
            .where(MINUTES_COMMENT.ID.eq(id))
            .fetchOne { it.row() }

    /** Sets [id]'s response once; false when it was answered already. */
    fun respond(
        id: UUID,
        response: CommentResponse,
        note: String?,
        by: UUID,
        at: Instant,
    ): Boolean {
        val c = MINUTES_COMMENT
        return tenantDsl
            .current()
            .update(c)
            .set(c.RESPONSE, response.name)
            .set(c.RESPONSE_NOTE, note)
            .set(c.RESPONDED_BY, by)
            .set(c.RESPONDED_AT, at.atOffset(ZoneOffset.UTC))
            .where(c.ID.eq(id), c.KIND.eq(CommentKind.SUGGESTION.name), c.RESPONSE.isNull)
            .execute() == 1
    }

    /** [meetingId]'s comments, oldest first; only [userId]'s when given. */
    fun of(
        meetingId: UUID,
        userId: UUID? = null,
    ): List<CommentRow> =
        tenantDsl
            .current()
            .selectFrom(MINUTES_COMMENT)
            .where(MINUTES_COMMENT.MEETING_ID.eq(meetingId))
            .and(
                userId?.let { MINUTES_COMMENT.USER_ID.eq(it) } ?: org.jooq.impl.DSL
                    .noCondition(),
            ).orderBy(MINUTES_COMMENT.AT, MINUTES_COMMENT.ID)
            .fetch { it.row() }

    /** Who of [meetingIds]' readers answered (any comment), by meeting. */
    fun answered(meetingIds: Collection<UUID>): Map<UUID, Set<UUID>> =
        if (meetingIds.isEmpty()) {
            emptyMap()
        } else {
            tenantDsl
                .current()
                .selectDistinct(MINUTES_COMMENT.MEETING_ID, MINUTES_COMMENT.USER_ID)
                .from(MINUTES_COMMENT)
                .where(MINUTES_COMMENT.MEETING_ID.`in`(meetingIds))
                .fetch()
                .groupBy({ checkNotNull(it.value1()) }, { checkNotNull(it.value2()) })
                .mapValues { it.value.toSet() }
        }

    fun insertRequest(row: CommentRequestRow) {
        val r = MINUTES_COMMENT_REQUEST
        tenantDsl
            .current()
            .insertInto(r)
            .set(r.ID, row.id)
            .set(r.TENANT_ID, tenantDsl.tenant().value)
            .set(r.MEETING_ID, row.meetingId)
            .set(r.USER_ID, row.userId)
            .set(r.ITEM_IDS, row.itemIds.toTypedArray())
            .set(r.ASKED_BY, row.askedBy)
            .set(r.ASKED_AT, row.askedAt.atOffset(ZoneOffset.UTC))
            .execute()
    }

    /** [meetingId]'s requests for comments, oldest first. */
    fun requests(meetingId: UUID): List<CommentRequestRow> =
        tenantDsl
            .current()
            .selectFrom(MINUTES_COMMENT_REQUEST)
            .where(MINUTES_COMMENT_REQUEST.MEETING_ID.eq(meetingId))
            .orderBy(MINUTES_COMMENT_REQUEST.ASKED_AT, MINUTES_COMMENT_REQUEST.ID)
            .fetch {
                CommentRequestRow(
                    it.id,
                    it.meetingId,
                    it.userId,
                    it.itemIds.filterNotNull(),
                    it.askedBy,
                    it.askedAt.toInstant(),
                )
            }

    private fun MinutesCommentRecord.row() =
        CommentRow(
            id = id,
            meetingId = meetingId,
            userId = userId,
            itemId = itemId,
            kind = CommentKind.valueOf(kind),
            text = text,
            at = at.toInstant(),
            response = response?.let(CommentResponse::valueOf),
            responseNote = responseNote,
            respondedBy = respondedBy,
            respondedAt = respondedAt?.toInstant(),
        )
}

/** The readers' copies (ADR-0066), inside a tenant transaction; a copy changes only by a guarded update on its version. */
@Repository
class MinutesCopyStore(
    private val tenantDsl: TenantDsl,
    private val json: JsonMapper,
) {
    /** Writes [row] PENDING; false when the copy for that stage, version and set exists. */
    fun insert(row: CopyRow): Boolean {
        val c = MINUTES_COPY
        return tenantDsl
            .current()
            .insertInto(c)
            .set(c.ID, row.id)
            .set(c.TENANT_ID, tenantDsl.tenant().value)
            .set(c.MEETING_ID, row.meetingId)
            .set(c.STAGE, row.stage.name)
            .set(c.REDACTED, JSONB.jsonb(json.writeValueAsString(row.redacted)))
            .set(c.SET_KEY, row.setKey)
            .set(c.SOURCE_VERSION_ID, row.sourceVersionId)
            .set(c.RECORD_VERSION, row.recordVersion)
            .set(c.STATE, CopyState.PENDING.name)
            .set(c.CREATED_AT, row.createdAt.atOffset(ZoneOffset.UTC))
            .set(c.VERSION, 0L)
            .onConflictDoNothing()
            .execute() == 1
    }

    /** The copies of [meetingId]'s [stage] built from [sourceVersionId]. */
    fun ofVersion(
        meetingId: UUID,
        stage: CopyStage,
        sourceVersionId: UUID,
    ): List<CopyRow> =
        tenantDsl
            .current()
            .selectFrom(MINUTES_COPY)
            .where(
                MINUTES_COPY.MEETING_ID.eq(meetingId),
                MINUTES_COPY.STAGE.eq(stage.name),
                MINUTES_COPY.SOURCE_VERSION_ID.eq(sourceVersionId),
            ).orderBy(MINUTES_COPY.CREATED_AT, MINUTES_COPY.ID)
            .fetch { it.row() }

    /** The latest built copy of [meetingId]'s [stage] for [setKey], of any version of the draft (the newest first). */
    fun latestDone(
        meetingId: UUID,
        stage: CopyStage,
        setKey: String,
    ): CopyRow? =
        tenantDsl
            .current()
            .selectFrom(MINUTES_COPY)
            .where(
                MINUTES_COPY.MEETING_ID.eq(meetingId),
                MINUTES_COPY.STAGE.eq(stage.name),
                MINUTES_COPY.SET_KEY.eq(setKey),
                MINUTES_COPY.STATE.eq(CopyState.DONE.name),
            ).orderBy(MINUTES_COPY.BUILT_AT.desc(), MINUTES_COPY.ID.desc())
            .limit(1)
            .fetchOne { it.row() }

    /** The approved copies of [meetingId]'s record version [recordVersion] (E06 T7). */
    fun ofRecord(
        meetingId: UUID,
        recordVersion: Int,
    ): List<CopyRow> =
        tenantDsl
            .current()
            .selectFrom(MINUTES_COPY)
            .where(
                MINUTES_COPY.MEETING_ID.eq(meetingId),
                MINUTES_COPY.STAGE.eq(CopyStage.APPROVED.name),
                MINUTES_COPY.RECORD_VERSION.eq(recordVersion),
            ).orderBy(MINUTES_COPY.CREATED_AT, MINUTES_COPY.ID)
            .fetch { it.row() }

    /** The built approved copies after [after] in id order, at most [limit] (the weekly fixity run). */
    fun approvedAfter(
        after: UUID?,
        limit: Int,
    ): List<CopyRow> =
        tenantDsl
            .current()
            .selectFrom(MINUTES_COPY)
            .where(
                MINUTES_COPY.STAGE.eq(CopyStage.APPROVED.name),
                MINUTES_COPY.STATE.eq(CopyState.DONE.name),
                after?.let { MINUTES_COPY.ID.gt(it) } ?: DSL.noCondition(),
            ).orderBy(MINUTES_COPY.ID)
            .limit(limit)
            .fetch { it.row() }

    /** The failed approved copies asked for again fewer than [below] times, in id order, at most [limit] (the sweep). */
    fun failedApproved(
        below: Int,
        limit: Int,
    ): List<CopyRow> =
        tenantDsl
            .current()
            .selectFrom(MINUTES_COPY)
            .where(
                MINUTES_COPY.STAGE.eq(CopyStage.APPROVED.name),
                MINUTES_COPY.STATE.eq(CopyState.FAILED.name),
                MINUTES_COPY.RETRIES.lt(below),
            ).orderBy(MINUTES_COPY.ID)
            .limit(limit)
            .fetch { it.row() }

    /** [id], locked to the end of the transaction. */
    fun lock(id: UUID): CopyRow? =
        tenantDsl
            .current()
            .selectFrom(MINUTES_COPY)
            .where(MINUTES_COPY.ID.eq(id))
            .forUpdate()
            .fetchOne { it.row() }

    /** Writes [after]'s build over [before]; false when the copy moved on in between. */
    fun update(
        before: CopyRow,
        after: CopyRow,
    ): Boolean {
        val c = MINUTES_COPY
        return tenantDsl
            .current()
            .update(c)
            .set(c.STATE, after.state.name)
            .set(c.ERROR, after.error)
            .set(c.FILE_ID, after.fileId)
            .set(c.FILE_VERSION_ID, after.fileVersionId)
            .set(c.SHA256, after.sha256)
            .set(c.BUILT_AT, after.builtAt?.atOffset(ZoneOffset.UTC))
            .set(c.COMPLIANT, after.compliant)
            .set(c.VALIDATION, after.validation?.let { JSONB.jsonb(json.writeValueAsString(it)) })
            .set(c.FAILED_AT, after.failedAt?.atOffset(ZoneOffset.UTC))
            .set(c.RETRIES, after.retries)
            .set(c.VERSION, before.version + 1)
            .where(c.ID.eq(before.id), c.VERSION.eq(before.version))
            .execute() == 1
    }

    private fun MinutesCopyRecord.row() =
        CopyRow(
            id = id,
            meetingId = meetingId,
            stage = CopyStage.valueOf(stage),
            redacted = json.readValue(redacted.data(), REDACTED),
            setKey = setKey,
            sourceVersionId = sourceVersionId,
            state = CopyState.valueOf(state ?: CopyState.PENDING.name),
            error = error,
            fileId = fileId,
            fileVersionId = fileVersionId,
            sha256 = sha256,
            createdAt = createdAt.toInstant(),
            builtAt = builtAt?.toInstant(),
            version = version ?: 0,
            recordVersion = recordVersion,
            compliant = compliant,
            validation = validation?.let { json.readValue(it.data(), VALIDATION) },
            failedAt = failedAt?.toInstant(),
            retries = retries ?: 0,
        )

    private companion object {
        val REDACTED = object : TypeReference<List<Redaction>>() {}
        val VALIDATION = object : TypeReference<Map<String, Any?>>() {}
    }
}

/** The authenticated confirmations (ADR-0051), append-only; the committee's chain is written under its advisory lock. */
@Repository
class MinutesApprovalStore(
    private val tenantDsl: TenantDsl,
    private val json: JsonMapper,
) {
    /** Serialises writers of [forumId]'s chain to the end of the transaction, so it never forks. */
    fun lockChain(forumId: UUID) {
        tenantDsl.current().fetch("select pg_advisory_xact_lock(hashtextextended(?, 0))", "minutes.approval:${tenantDsl.tenant()}:$forumId")
    }

    fun insert(row: ApprovalRow) {
        val a = MINUTES_APPROVAL
        tenantDsl
            .current()
            .insertInto(a)
            .set(a.ID, row.id)
            .set(a.TENANT_ID, tenantDsl.tenant().value)
            .set(a.MEETING_ID, row.meetingId)
            .set(a.FORUM_ID, row.forumId)
            .set(a.KIND, row.kind)
            .set(a.ACTOR_ID, row.actorId)
            .set(a.ROLE, row.role.name)
            .set(a.AUTHENTICATION, JSONB.jsonb(json.writeValueAsString(row.authentication)))
            .set(a.CLIENT, JSONB.jsonb(json.writeValueAsString(row.client)))
            .set(a.DOCX_VERSION_ID, row.docxVersionId)
            .set(a.DOCX_SHA256, row.docxSha256)
            .set(a.PREVIOUS_HASH, row.previousHash)
            .set(a.RECORD_HASH, row.recordHash)
            .set(a.AT, row.at.atOffset(ZoneOffset.UTC))
            .set(a.CORRECTION_ID, row.correctionId)
            .set(a.CORRECTION_SHA256, row.correctionSha256)
            .execute()
    }

    /** [forumId]'s approvals in the order they were written (the chain). */
    fun ofForum(forumId: UUID): List<ApprovalRow> =
        tenantDsl
            .current()
            .selectFrom(MINUTES_APPROVAL)
            .where(MINUTES_APPROVAL.FORUM_ID.eq(forumId))
            .orderBy(MINUTES_APPROVAL.AT, MINUTES_APPROVAL.ID)
            .fetch { it.row() }

    /** [meetingId]'s approvals, oldest first. */
    fun ofMeeting(meetingId: UUID): List<ApprovalRow> =
        tenantDsl
            .current()
            .selectFrom(MINUTES_APPROVAL)
            .where(MINUTES_APPROVAL.MEETING_ID.eq(meetingId))
            .orderBy(MINUTES_APPROVAL.AT, MINUTES_APPROVAL.ID)
            .fetch { it.row() }

    /** The record hash ending [forumId]'s chain; null before its first approval. */
    fun head(forumId: UUID): String? =
        tenantDsl
            .current()
            .select(MINUTES_APPROVAL.RECORD_HASH)
            .from(MINUTES_APPROVAL)
            .where(MINUTES_APPROVAL.FORUM_ID.eq(forumId))
            .orderBy(MINUTES_APPROVAL.AT.desc(), MINUTES_APPROVAL.ID.desc())
            .limit(1)
            .fetchOne { it.value1() }

    private fun MinutesApprovalRecord.row() =
        ApprovalRow(
            id = id,
            meetingId = meetingId,
            forumId = forumId,
            kind = kind,
            actorId = actorId,
            role = MinutesRole.valueOf(role),
            authentication = json.readTree(authentication.data()),
            client = json.readTree(client.data()),
            docxVersionId = docxVersionId,
            docxSha256 = docxSha256,
            previousHash = previousHash,
            recordHash = recordHash,
            at = at.toInstant(),
            correctionId = correctionId,
            correctionSha256 = correctionSha256,
        )
}

/** Marks the sweep's reminders on minutes sent (design E06 §9), written before the reminder is sent. */
@Repository
class MinutesReminderStore(
    private val tenantDsl: TenantDsl,
) {
    /** Marks [userId]'s [kind] reminder of [meetingId] for [key]; false when it already was. */
    fun mark(
        meetingId: UUID,
        kind: MinutesReminderKind,
        userId: UUID,
        key: String,
        at: Instant,
    ): Boolean {
        val r = MINUTES_REMINDER
        return tenantDsl
            .current()
            .insertInto(r)
            .set(r.TENANT_ID, tenantDsl.tenant().value)
            .set(r.MEETING_ID, meetingId)
            .set(r.KIND, kind.name)
            .set(r.USER_ID, userId)
            .set(r.KEY, key)
            .set(r.SENT_AT, at.atOffset(ZoneOffset.UTC))
            .onConflictDoNothing()
            .execute() == 1
    }
}
