package com.memo.meeting.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.records.MeetingDeclarationRecord
import com.memo.jooq.tables.references.MEETING_DECLARATION
import com.memo.jooq.tables.references.MEETING_DECLARATION_EVENT
import com.memo.jooq.tables.references.MEETING_DECLARATION_REVIEW
import com.memo.meeting.DeclarationHow
import com.memo.meeting.DeclarationIntent
import com.memo.meeting.DeclarationSource
import com.memo.meeting.DeclarationState
import com.memo.meeting.RulingOutcome
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The chair's latest ruling on a declaration (design E04 §1.2): by the chair or vice-chair ([by]), given in the app or
 * recorded by a secretary on their behalf ([onBehalfBy], with [how] it was given). Defaults for the JSON reader only.
 */
data class Ruling(
    val outcome: RulingOutcome = RulingOutcome.LEAVE,
    val by: UUID? = null,
    val onBehalfBy: UUID? = null,
    val how: DeclarationHow = DeclarationHow.IN_APP,
    val note: String? = null,
    val at: Instant = Instant.EPOCH,
)

/** One declaration of interest (design E04 §1.1). */
data class DeclarationRow(
    val id: UUID,
    val meetingId: UUID,
    val itemId: UUID,
    val forumId: UUID,
    val userId: UUID,
    val nature: String,
    val intent: DeclarationIntent,
    val source: DeclarationSource,
    val sourceRef: UUID?,
    val recordedBy: UUID?,
    val how: DeclarationHow,
    val howNote: String?,
    val declaredAt: Instant,
    val atMeeting: Boolean,
    val correction: Boolean,
    val revision: Int?,
    val state: DeclarationState,
    val ruling: Ruling?,
    val version: Long,
)

/** One transition in a declaration's history (design E04 §1.2). */
data class DeclarationEvent(
    val id: UUID,
    val declarationId: UUID,
    val kind: DeclarationEventKind,
    val stateAfter: DeclarationState,
    val actorId: UUID?,
    val onBehalfBy: UUID?,
    val how: DeclarationHow,
    val note: String?,
    val correction: Boolean,
    val at: Instant,
)

/**
 * Declarations, their history and the review confirmations (design E04 §1.9), inside a tenant transaction. A declaration
 * changes only by a guarded update of its state, intent and ruling; the history and the reviews are written once.
 */
@Repository
class DeclarationStore(
    private val tenantDsl: TenantDsl,
    private val json: JsonMapper,
) {
    /** Writes [row] with its first event; false when the person already holds an open declaration on the item (`ONE_OPEN`). */
    fun insert(
        row: DeclarationRow,
        event: DeclarationEvent,
    ): Boolean {
        val inserted =
            tenantDsl
                .current()
                .insertInto(MEETING_DECLARATION)
                .set(MEETING_DECLARATION.ID, row.id)
                .set(MEETING_DECLARATION.TENANT_ID, tenantDsl.tenant().value)
                .set(MEETING_DECLARATION.MEETING_ID, row.meetingId)
                .set(MEETING_DECLARATION.ITEM_ID, row.itemId)
                .set(MEETING_DECLARATION.FORUM_ID, row.forumId)
                .set(MEETING_DECLARATION.USER_ID, row.userId)
                .set(MEETING_DECLARATION.NATURE, row.nature)
                .set(MEETING_DECLARATION.INTENT, row.intent.name)
                .set(MEETING_DECLARATION.SOURCE, row.source.name)
                .set(MEETING_DECLARATION.SOURCE_REF, row.sourceRef)
                .set(MEETING_DECLARATION.RECORDED_BY, row.recordedBy)
                .set(MEETING_DECLARATION.HOW, row.how.name)
                .set(MEETING_DECLARATION.HOW_NOTE, row.howNote)
                .set(MEETING_DECLARATION.DECLARED_AT, row.declaredAt.atOffset(ZoneOffset.UTC))
                .set(MEETING_DECLARATION.AT_MEETING, row.atMeeting)
                .set(MEETING_DECLARATION.CORRECTION, row.correction)
                .set(MEETING_DECLARATION.REVISION, row.revision)
                .set(MEETING_DECLARATION.STATE, row.state.name)
                .onConflictDoNothing()
                .execute() == 1
        if (inserted) addEvent(event)
        return inserted
    }

    fun find(id: UUID): DeclarationRow? =
        tenantDsl
            .current()
            .selectFrom(MEETING_DECLARATION)
            .where(MEETING_DECLARATION.ID.eq(id))
            .fetchOne { row(it) }

    /** [userId]'s open (not withdrawn) declaration on [itemId], if any. */
    fun open(
        itemId: UUID,
        userId: UUID,
    ): DeclarationRow? =
        tenantDsl
            .current()
            .selectFrom(MEETING_DECLARATION)
            .where(
                MEETING_DECLARATION.ITEM_ID.eq(itemId),
                MEETING_DECLARATION.USER_ID.eq(userId),
                MEETING_DECLARATION.STATE.ne(DeclarationState.WITHDRAWN.name),
            ).fetchOne { row(it) }

    /** The declarations of [meetingId] (of [userId] only, when given), the oldest first. */
    fun ofMeeting(
        meetingId: UUID,
        userId: UUID? = null,
    ): List<DeclarationRow> =
        tenantDsl
            .current()
            .selectFrom(MEETING_DECLARATION)
            .where(MEETING_DECLARATION.MEETING_ID.eq(meetingId))
            .and(if (userId == null) DSL.noCondition() else MEETING_DECLARATION.USER_ID.eq(userId))
            .orderBy(MEETING_DECLARATION.DECLARED_AT, MEETING_DECLARATION.ID)
            .fetch { row(it) }

    /**
     * Moves [row] to [state] (with [intent] and [ruling]) and writes [event]: a guarded update on the state and version
     * the caller read. False when someone else changed it in the meantime.
     */
    fun transition(
        row: DeclarationRow,
        state: DeclarationState,
        intent: DeclarationIntent,
        ruling: Ruling?,
        event: DeclarationEvent,
    ): Boolean {
        val changed =
            tenantDsl
                .current()
                .update(MEETING_DECLARATION)
                .set(MEETING_DECLARATION.STATE, state.name)
                .set(MEETING_DECLARATION.INTENT, intent.name)
                .set(MEETING_DECLARATION.RULING, ruling?.let { JSONB.valueOf(json.writeValueAsString(it)) })
                .set(MEETING_DECLARATION.VERSION, row.version + 1)
                .where(
                    MEETING_DECLARATION.ID.eq(row.id),
                    MEETING_DECLARATION.STATE.eq(row.state.name),
                    MEETING_DECLARATION.VERSION.eq(row.version),
                ).execute() == 1
        if (changed) addEvent(event)
        return changed
    }

    /** The history of [declarationId], the first first. */
    fun events(declarationId: UUID): List<DeclarationEvent> =
        tenantDsl
            .current()
            .selectFrom(MEETING_DECLARATION_EVENT)
            .where(MEETING_DECLARATION_EVENT.DECLARATION_ID.eq(declarationId))
            .orderBy(MEETING_DECLARATION_EVENT.AT, MEETING_DECLARATION_EVENT.ID)
            .fetch {
                DeclarationEvent(
                    id = it.id,
                    declarationId = it.declarationId,
                    kind = DeclarationEventKind.valueOf(it.kind),
                    stateAfter = DeclarationState.valueOf(it.stateAfter),
                    actorId = it.actorId,
                    onBehalfBy = it.onBehalfBy,
                    how = DeclarationHow.valueOf(it.how),
                    note = it.note,
                    correction = it.correction == true,
                    at = it.at.toInstant(),
                )
            }

    /** Records that [userId] confirmed their review of [meetingId]'s revision [revision]; false when they already had. */
    fun review(
        meetingId: UUID,
        userId: UUID,
        revision: Int,
        at: Instant,
    ): Boolean =
        tenantDsl
            .current()
            .insertInto(MEETING_DECLARATION_REVIEW)
            .set(MEETING_DECLARATION_REVIEW.TENANT_ID, tenantDsl.tenant().value)
            .set(MEETING_DECLARATION_REVIEW.MEETING_ID, meetingId)
            .set(MEETING_DECLARATION_REVIEW.USER_ID, userId)
            .set(MEETING_DECLARATION_REVIEW.REVISION, revision)
            .set(MEETING_DECLARATION_REVIEW.REVIEWED_AT, at.atOffset(ZoneOffset.UTC))
            .onConflictDoNothing()
            .execute() == 1

    /** The latest revision of [meetingId] that [userId] confirmed; null before a first confirmation. */
    fun reviewed(
        meetingId: UUID,
        userId: UUID,
    ): Int? =
        tenantDsl
            .current()
            .select(DSL.max(MEETING_DECLARATION_REVIEW.REVISION))
            .from(MEETING_DECLARATION_REVIEW)
            .where(MEETING_DECLARATION_REVIEW.MEETING_ID.eq(meetingId), MEETING_DECLARATION_REVIEW.USER_ID.eq(userId))
            .fetchOne()
            ?.value1()

    private fun addEvent(event: DeclarationEvent) {
        tenantDsl
            .current()
            .insertInto(MEETING_DECLARATION_EVENT)
            .set(MEETING_DECLARATION_EVENT.ID, event.id)
            .set(MEETING_DECLARATION_EVENT.TENANT_ID, tenantDsl.tenant().value)
            .set(MEETING_DECLARATION_EVENT.DECLARATION_ID, event.declarationId)
            .set(MEETING_DECLARATION_EVENT.KIND, event.kind.name)
            .set(MEETING_DECLARATION_EVENT.STATE_AFTER, event.stateAfter.name)
            .set(MEETING_DECLARATION_EVENT.ACTOR_ID, event.actorId)
            .set(MEETING_DECLARATION_EVENT.ON_BEHALF_BY, event.onBehalfBy)
            .set(MEETING_DECLARATION_EVENT.HOW, event.how.name)
            .set(MEETING_DECLARATION_EVENT.NOTE, event.note)
            .set(MEETING_DECLARATION_EVENT.CORRECTION, event.correction)
            .set(MEETING_DECLARATION_EVENT.AT, event.at.atOffset(ZoneOffset.UTC))
            .execute()
    }

    /** A declaration as read (the registers read rows with their meeting, [DeclarationRegisterStore]). */
    fun row(r: MeetingDeclarationRecord) =
        DeclarationRow(
            id = r.id,
            meetingId = r.meetingId,
            itemId = r.itemId,
            forumId = r.forumId,
            userId = r.userId,
            nature = r.nature,
            intent = DeclarationIntent.valueOf(r.intent),
            source = DeclarationSource.valueOf(r.source),
            sourceRef = r.sourceRef,
            recordedBy = r.recordedBy,
            how = DeclarationHow.valueOf(r.how),
            howNote = r.howNote,
            declaredAt = r.declaredAt.toInstant(),
            atMeeting = r.atMeeting,
            correction = r.correction == true,
            revision = r.revision,
            state = DeclarationState.valueOf(r.state),
            ruling = r.ruling?.let { json.readValue(it.data(), Ruling::class.java) },
            version = r.version ?: 0,
        )
}
