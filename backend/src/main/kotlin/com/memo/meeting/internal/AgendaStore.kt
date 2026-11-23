package com.memo.meeting.internal

import com.memo.common.tenant.TenantDsl
import com.memo.forum.AgendaSectionKind
import com.memo.forum.StandingKind
import com.memo.forum.SubmissionPurpose
import com.memo.jooq.tables.records.MeetingAgendaRecord
import com.memo.jooq.tables.records.MeetingItemRecord
import com.memo.jooq.tables.records.MeetingPaperRecord
import com.memo.jooq.tables.references.MEETING_AGENDA
import com.memo.jooq.tables.references.MEETING_ITEM
import com.memo.jooq.tables.references.MEETING_ITEM_ATTENDEE
import com.memo.jooq.tables.references.MEETING_PAPER
import com.memo.jooq.tables.references.MEETING_SECTION
import org.jooq.Field
import org.jooq.JSONB
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** How an agenda was started (design E03 §1.7). */
enum class AgendaOrigin { TEMPLATE, MEETING }

/** Whether any revision of the agenda was published (design E03 §1.3). */
enum class AgendaState { DRAFT, PUBLISHED }

/**
 * The chair's agreement to the agenda's first publication (design E03 §1.4): by the chair ([by]), recorded in the app or
 * by a secretary on the chair's behalf ([onBehalfBy], with how it was given in [note], "by email on 4 Dec").
 */
data class ChairAgreement(
    val by: UUID? = null,
    val at: Instant = Instant.EPOCH,
    val onBehalfBy: UUID? = null,
    val note: String? = null,
)

/** A meeting's agenda: the working draft's head row, locked by every builder change (ADR-0060). */
data class AgendaRow(
    val meetingId: UUID,
    val origin: AgendaOrigin,
    val sourceMeetingId: UUID?,
    /** The last published revision; 0 before the first. */
    val currentRevision: Int,
    /** Changes since the last revision. */
    val dirty: Boolean,
    val version: Long,
    val state: AgendaState = AgendaState.DRAFT,
    val chairAgreement: ChairAgreement? = null,
)

/** An unnumbered heading of the agenda. */
data class SectionRow(
    val id: UUID,
    val meetingId: UUID,
    val position: Int,
    val title: String,
    val kind: AgendaSectionKind,
    val membersOnly: Boolean,
    val receivesSubmissions: Boolean,
)

/** Someone presenting an item: a user, or a typed name (an outside adviser). */
data class Presenter(
    val userId: UUID? = null,
    val name: String? = null,
)

/** An item or sub-item of the draft (design E03 §1.2). Sub-items have a [parentId] and no minutes of their own. */
data class ItemRow(
    val id: UUID,
    val meetingId: UUID,
    val sectionId: UUID,
    val parentId: UUID?,
    val position: Int,
    val kind: ItemKind,
    val standingKind: StandingKind?,
    val submissionId: UUID?,
    val memoId: UUID?,
    val title: String,
    val description: String?,
    /** Null only for breaks. */
    val purpose: SubmissionPurpose?,
    val resolutionSought: String?,
    val minutes: Int?,
    val presenters: List<Presenter>,
    /** "Only these people"; null: no restriction. */
    val restrictedTo: List<UUID>?,
    val titleHidden: Boolean,
    val classificationId: UUID,
    val late: Lateness,
    val addedAfterRevision: Int,
) {
    /** The item and, for a sub-item, its parent: a declaration on either recuses from it (design E04 §1.6). */
    val scope: List<UUID> get() = listOfNotNull(id, parentId)
}

/** A paper of an item: a memo's file or an upload, by reference (ADR-0061). */
data class PaperRow(
    val id: UUID,
    val meetingId: UUID,
    val itemId: UUID,
    val position: Int,
    val source: PaperSource,
    val fileId: UUID,
    val title: String,
    val restrictedTo: List<UUID>?,
    val classificationId: UUID,
    val late: Lateness,
)

/** The live rows of one agenda, as the builder and the draft view work on them. */
data class AgendaRows(
    val agenda: AgendaRow,
    val sections: List<SectionRow>,
    val items: List<ItemRow>,
    val papers: List<PaperRow>,
    /** Item attendees by item (user presenters included). */
    val attendees: Map<UUID, Set<UUID>>,
)

/**
 * The agenda head row (ADR-0060), inside a tenant transaction: every builder change locks it ([lock]) and moves its
 * version ([touch]), so two secretaries' changes never interleave.
 */
@Repository
class AgendaStore(
    private val tenantDsl: TenantDsl,
    private val clock: Clock,
    private val json: JsonMapper,
) {
    fun find(meetingId: UUID): AgendaRow? =
        tenantDsl
            .current()
            .selectFrom(MEETING_AGENDA)
            .where(MEETING_AGENDA.MEETING_ID.eq(meetingId))
            .fetchOne { row(it) }

    /** The agenda of [meetingId] locked for this transaction (`SELECT … FOR UPDATE`); null when it has none. */
    fun lock(meetingId: UUID): AgendaRow? =
        tenantDsl
            .current()
            .selectFrom(MEETING_AGENDA)
            .where(MEETING_AGENDA.MEETING_ID.eq(meetingId))
            .forUpdate()
            .fetchOne { row(it) }

    /** Starts the agenda of [meetingId]; false when it already has one (the primary key decides under concurrency). */
    fun insert(
        meetingId: UUID,
        origin: AgendaOrigin,
        sourceMeetingId: UUID?,
        by: UUID?,
    ): Boolean {
        val now = now()
        return tenantDsl
            .current()
            .insertInto(MEETING_AGENDA)
            .set(MEETING_AGENDA.MEETING_ID, meetingId)
            .set(MEETING_AGENDA.TENANT_ID, tenantDsl.tenant().value)
            .set(MEETING_AGENDA.STARTED_FROM, origin.name)
            .set(MEETING_AGENDA.SOURCE_MEETING_ID, sourceMeetingId)
            .set(MEETING_AGENDA.CREATED_BY, by)
            .set(MEETING_AGENDA.CREATED_AT, now)
            .set(MEETING_AGENDA.UPDATED_AT, now)
            .onConflictDoNothing()
            .execute() == 1
    }

    /** A change was made under the lock: the version moves on and the draft differs from the last revision. */
    fun touch(meetingId: UUID) {
        tenantDsl
            .current()
            .update(MEETING_AGENDA)
            .set(MEETING_AGENDA.VERSION, MEETING_AGENDA.VERSION.plus(1))
            .set(MEETING_AGENDA.DIRTY, true)
            .set(MEETING_AGENDA.UPDATED_AT, now())
            .where(MEETING_AGENDA.MEETING_ID.eq(meetingId))
            .execute()
    }

    /** Records the chair's agreement to the first publication (under the lock; the caller then [touch]es). */
    fun agree(
        meetingId: UUID,
        agreement: ChairAgreement,
    ) {
        tenantDsl
            .current()
            .update(MEETING_AGENDA)
            .set(MEETING_AGENDA.CHAIR_AGREEMENT, JSONB.valueOf(json.writeValueAsString(agreement)))
            .where(MEETING_AGENDA.MEETING_ID.eq(meetingId))
            .execute()
    }

    /** Revision [revision] was published (under the lock): the draft is clean and the version moves on. */
    fun published(
        meetingId: UUID,
        revision: Int,
    ) {
        tenantDsl
            .current()
            .update(MEETING_AGENDA)
            .set(MEETING_AGENDA.STATE, AgendaState.PUBLISHED.name)
            .set(MEETING_AGENDA.CURRENT_REVISION, revision)
            .set(MEETING_AGENDA.DIRTY, false)
            .set(MEETING_AGENDA.VERSION, MEETING_AGENDA.VERSION.plus(1))
            .set(MEETING_AGENDA.UPDATED_AT, now())
            .where(MEETING_AGENDA.MEETING_ID.eq(meetingId))
            .execute()
    }

    fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private fun row(r: MeetingAgendaRecord) =
        AgendaRow(
            meetingId = r.meetingId,
            origin = AgendaOrigin.valueOf(r.startedFrom),
            sourceMeetingId = r.sourceMeetingId,
            currentRevision = r.currentRevision ?: 0,
            dirty = r.dirty == true,
            version = r.version ?: 0,
            state = AgendaState.valueOf(r.state ?: AgendaState.DRAFT.name),
            chairAgreement = r.chairAgreement?.let { json.readValue(it.data(), ChairAgreement::class.java) },
        )
}

/** Sections of agendas, inside a tenant transaction; a removal records its time. */
@Repository
class SectionStore(
    private val tenantDsl: TenantDsl,
    private val agendas: AgendaStore,
) {
    fun live(meetingId: UUID): List<SectionRow> = liveOf(listOf(meetingId))[meetingId].orEmpty()

    /** The live sections of each of [meetingIds] in agenda order, in one query (E06's resolution register, design E06 §8). */
    fun liveOf(meetingIds: Collection<UUID>): Map<UUID, List<SectionRow>> =
        if (meetingIds.isEmpty()) {
            emptyMap()
        } else {
            liveRows(meetingIds).groupBy { it.meetingId }
        }

    private fun liveRows(meetingIds: Collection<UUID>): List<SectionRow> =
        tenantDsl
            .current()
            .selectFrom(MEETING_SECTION)
            .where(MEETING_SECTION.MEETING_ID.`in`(meetingIds), MEETING_SECTION.REMOVED_AT.isNull)
            .orderBy(MEETING_SECTION.MEETING_ID, MEETING_SECTION.POSITION, MEETING_SECTION.ID)
            .fetch {
                SectionRow(
                    id = it.id,
                    meetingId = it.meetingId,
                    position = it.position,
                    title = it.title,
                    kind = AgendaSectionKind.valueOf(it.kind),
                    membersOnly = it.membersOnly == true,
                    receivesSubmissions = it.receivesSubmissions == true,
                )
            }

    fun insert(row: SectionRow) {
        tenantDsl
            .current()
            .insertInto(MEETING_SECTION)
            .set(MEETING_SECTION.ID, row.id)
            .set(MEETING_SECTION.TENANT_ID, tenantDsl.tenant().value)
            .set(MEETING_SECTION.MEETING_ID, row.meetingId)
            .set(MEETING_SECTION.CREATED_AT, agendas.now())
            .set(fields(row))
            .execute()
    }

    fun update(row: SectionRow) {
        tenantDsl
            .current()
            .update(MEETING_SECTION)
            .set(fields(row))
            .where(MEETING_SECTION.ID.eq(row.id))
            .execute()
    }

    fun remove(
        id: UUID,
        by: UUID?,
    ) {
        tenantDsl
            .current()
            .update(MEETING_SECTION)
            .set(MEETING_SECTION.REMOVED_AT, agendas.now())
            .set(MEETING_SECTION.REMOVED_BY, by)
            .set(MEETING_SECTION.RECEIVES_SUBMISSIONS, false)
            .where(MEETING_SECTION.ID.eq(id), MEETING_SECTION.REMOVED_AT.isNull)
            .execute()
    }

    private fun fields(row: SectionRow): Map<Field<*>, Any?> =
        mapOf(
            MEETING_SECTION.POSITION to row.position,
            MEETING_SECTION.TITLE to row.title,
            MEETING_SECTION.KIND to row.kind.name,
            MEETING_SECTION.MEMBERS_ONLY to row.membersOnly,
            MEETING_SECTION.RECEIVES_SUBMISSIONS to row.receivesSubmissions,
        )
}

/** Items, sub-items and their attendees, inside a tenant transaction; a removal records its time and why. */
@Repository
class ItemStore(
    private val tenantDsl: TenantDsl,
    private val agendas: AgendaStore,
    private val json: JsonMapper,
) {
    fun live(meetingId: UUID): List<ItemRow> = liveOf(listOf(meetingId))[meetingId].orEmpty()

    /** The live items of each of [meetingIds] in position order, in one query (E06's resolution register, design E06 §8). */
    fun liveOf(meetingIds: Collection<UUID>): Map<UUID, List<ItemRow>> =
        if (meetingIds.isEmpty()) {
            emptyMap()
        } else {
            tenantDsl
                .current()
                .selectFrom(MEETING_ITEM)
                .where(MEETING_ITEM.MEETING_ID.`in`(meetingIds), MEETING_ITEM.REMOVED_AT.isNull)
                .orderBy(MEETING_ITEM.MEETING_ID, MEETING_ITEM.POSITION, MEETING_ITEM.ID)
                .fetch { row(it) }
                .groupBy { it.meetingId }
        }

    /** The live item [id] (read without a lock: callers lock its submission, then the agenda, and read it again). */
    fun find(id: UUID): ItemRow? =
        tenantDsl
            .current()
            .selectFrom(MEETING_ITEM)
            .where(MEETING_ITEM.ID.eq(id), MEETING_ITEM.REMOVED_AT.isNull)
            .fetchOne { row(it) }

    /** The live item [submissionId] is placed on, if any (at most one: `ONE_PLACEMENT`). */
    fun ofSubmission(submissionId: UUID): ItemRow? =
        tenantDsl
            .current()
            .selectFrom(MEETING_ITEM)
            .where(MEETING_ITEM.SUBMISSION_ID.eq(submissionId), MEETING_ITEM.REMOVED_AT.isNull)
            .fetchOne { row(it) }

    fun insert(
        row: ItemRow,
        by: UUID?,
    ) {
        val now = agendas.now()
        tenantDsl
            .current()
            .insertInto(MEETING_ITEM)
            .set(MEETING_ITEM.ID, row.id)
            .set(MEETING_ITEM.TENANT_ID, tenantDsl.tenant().value)
            .set(MEETING_ITEM.MEETING_ID, row.meetingId)
            .set(MEETING_ITEM.KIND, row.kind.name)
            .set(MEETING_ITEM.STANDING_KIND, row.standingKind?.name)
            .set(MEETING_ITEM.SUBMISSION_ID, row.submissionId)
            .set(MEETING_ITEM.MEMO_ID, row.memoId)
            .set(MEETING_ITEM.ADDED_AFTER_REVISION, row.addedAfterRevision)
            .set(MEETING_ITEM.CREATED_BY, by)
            .set(MEETING_ITEM.CREATED_AT, now)
            .set(MEETING_ITEM.UPDATED_AT, now)
            .set(fields(row))
            .execute()
    }

    /** Writes [row]'s place and editable fields. */
    fun update(row: ItemRow) {
        tenantDsl
            .current()
            .update(MEETING_ITEM)
            .set(fields(row))
            .set(MEETING_ITEM.UPDATED_AT, agendas.now())
            .where(MEETING_ITEM.ID.eq(row.id), MEETING_ITEM.REMOVED_AT.isNull)
            .execute()
    }

    /** Takes [id] off the agenda for [reason], with its attendees; false when it was already off. */
    fun remove(
        id: UUID,
        by: UUID?,
        reason: RemovalReason,
    ): Boolean {
        val now = agendas.now()
        val removed =
            tenantDsl
                .current()
                .update(MEETING_ITEM)
                .set(MEETING_ITEM.REMOVED_AT, now)
                .set(MEETING_ITEM.REMOVED_BY, by)
                .set(MEETING_ITEM.REMOVED_REASON, reason.name)
                .where(MEETING_ITEM.ID.eq(id), MEETING_ITEM.REMOVED_AT.isNull)
                .execute() == 1
        if (removed) setAttendees(id, emptySet())
        return removed
    }

    /** The current attendees of the live items of [meetingId], by item. */
    fun attendees(meetingId: UUID): Map<UUID, Set<UUID>> =
        tenantDsl
            .current()
            .select(MEETING_ITEM_ATTENDEE.ITEM_ID, MEETING_ITEM_ATTENDEE.USER_ID)
            .from(MEETING_ITEM_ATTENDEE)
            .join(MEETING_ITEM)
            .on(MEETING_ITEM.TENANT_ID.eq(MEETING_ITEM_ATTENDEE.TENANT_ID), MEETING_ITEM.ID.eq(MEETING_ITEM_ATTENDEE.ITEM_ID))
            .where(MEETING_ITEM.MEETING_ID.eq(meetingId), MEETING_ITEM.REMOVED_AT.isNull, MEETING_ITEM_ATTENDEE.REMOVED_AT.isNull)
            .fetch()
            .groupBy({ checkNotNull(it.value1()) }, { checkNotNull(it.value2()) })
            .mapValues { it.value.toSet() }

    /** Makes [users] the current attendees of [itemId]: added (or back), and every other current one removed. */
    fun setAttendees(
        itemId: UUID,
        users: Set<UUID>,
    ) {
        val dsl = tenantDsl.current()
        val now = agendas.now()
        users.forEach { user ->
            dsl
                .insertInto(MEETING_ITEM_ATTENDEE)
                .set(MEETING_ITEM_ATTENDEE.TENANT_ID, tenantDsl.tenant().value)
                .set(MEETING_ITEM_ATTENDEE.ITEM_ID, itemId)
                .set(MEETING_ITEM_ATTENDEE.USER_ID, user)
                .set(MEETING_ITEM_ATTENDEE.ADDED_AT, now)
                .onConflict(MEETING_ITEM_ATTENDEE.TENANT_ID, MEETING_ITEM_ATTENDEE.ITEM_ID, MEETING_ITEM_ATTENDEE.USER_ID)
                .doUpdate()
                .setNull(MEETING_ITEM_ATTENDEE.REMOVED_AT)
                .execute()
        }
        dsl
            .update(MEETING_ITEM_ATTENDEE)
            .set(MEETING_ITEM_ATTENDEE.REMOVED_AT, now)
            .where(
                MEETING_ITEM_ATTENDEE.ITEM_ID.eq(itemId),
                MEETING_ITEM_ATTENDEE.REMOVED_AT.isNull,
                MEETING_ITEM_ATTENDEE.USER_ID.notIn(users),
            ).execute()
    }

    private fun fields(row: ItemRow): Map<Field<*>, Any?> =
        mapOf(
            MEETING_ITEM.SECTION_ID to row.sectionId,
            MEETING_ITEM.PARENT_ID to row.parentId,
            MEETING_ITEM.POSITION to row.position,
            MEETING_ITEM.TITLE to row.title,
            MEETING_ITEM.DESCRIPTION to row.description,
            MEETING_ITEM.PURPOSE to row.purpose?.name,
            MEETING_ITEM.RESOLUTION_SOUGHT to row.resolutionSought,
            MEETING_ITEM.MINUTES to row.minutes,
            MEETING_ITEM.PRESENTERS to JSONB.valueOf(json.writeValueAsString(row.presenters)),
            MEETING_ITEM.RESTRICTED_TO to row.restrictedTo?.toTypedArray<UUID?>(),
            MEETING_ITEM.TITLE_HIDDEN to row.titleHidden,
            MEETING_ITEM.CLASSIFICATION_ID to row.classificationId,
            MEETING_ITEM.LATE to row.late.name,
        )

    private fun row(r: MeetingItemRecord) =
        ItemRow(
            id = r.id,
            meetingId = r.meetingId,
            sectionId = r.sectionId,
            parentId = r.parentId,
            position = r.position,
            kind = ItemKind.valueOf(r.kind),
            standingKind = r.standingKind?.let { StandingKind.valueOf(it) },
            submissionId = r.submissionId,
            memoId = r.memoId,
            title = r.title,
            description = r.description,
            purpose = r.purpose?.let { SubmissionPurpose.valueOf(it) },
            resolutionSought = r.resolutionSought,
            minutes = r.minutes,
            presenters = r.presenters?.let { json.readValue(it.data(), PRESENTERS) }.orEmpty(),
            restrictedTo = r.restrictedTo?.filterNotNull(),
            titleHidden = r.titleHidden == true,
            classificationId = r.classificationId,
            late = Lateness.valueOf(r.late ?: Lateness.NONE.name),
            addedAfterRevision = r.addedAfterRevision ?: 0,
        )
}

/** Papers of items, inside a tenant transaction; a removal records its time. */
@Repository
class PaperStore(
    private val tenantDsl: TenantDsl,
    private val agendas: AgendaStore,
) {
    fun live(meetingId: UUID): List<PaperRow> =
        tenantDsl
            .current()
            .selectFrom(MEETING_PAPER)
            .where(MEETING_PAPER.MEETING_ID.eq(meetingId), MEETING_PAPER.REMOVED_AT.isNull)
            .orderBy(MEETING_PAPER.POSITION, MEETING_PAPER.ID)
            .fetch { row(it) }

    /** The live papers carrying [fileId], on any agenda (the `meeting.paper` purpose, a new version of an upload). */
    fun ofFile(fileId: UUID): List<PaperRow> =
        tenantDsl
            .current()
            .selectFrom(MEETING_PAPER)
            .where(MEETING_PAPER.FILE_ID.eq(fileId), MEETING_PAPER.REMOVED_AT.isNull)
            .fetch { row(it) }

    fun insert(
        row: PaperRow,
        addedAfterRevision: Int,
        by: UUID?,
    ) {
        val now = agendas.now()
        tenantDsl
            .current()
            .insertInto(MEETING_PAPER)
            .set(MEETING_PAPER.ID, row.id)
            .set(MEETING_PAPER.TENANT_ID, tenantDsl.tenant().value)
            .set(MEETING_PAPER.MEETING_ID, row.meetingId)
            .set(MEETING_PAPER.ITEM_ID, row.itemId)
            .set(MEETING_PAPER.SOURCE, row.source.name)
            .set(MEETING_PAPER.FILE_ID, row.fileId)
            .set(MEETING_PAPER.ADDED_AFTER_REVISION, addedAfterRevision)
            .set(MEETING_PAPER.CREATED_BY, by)
            .set(MEETING_PAPER.CREATED_AT, now)
            .set(MEETING_PAPER.UPDATED_AT, now)
            .set(fields(row))
            .execute()
    }

    fun update(row: PaperRow) {
        tenantDsl
            .current()
            .update(MEETING_PAPER)
            .set(fields(row))
            .set(MEETING_PAPER.UPDATED_AT, agendas.now())
            .where(MEETING_PAPER.ID.eq(row.id), MEETING_PAPER.REMOVED_AT.isNull)
            .execute()
    }

    /** Takes the papers [ids] out; the number removed. */
    fun remove(
        ids: Collection<UUID>,
        by: UUID?,
    ): Int =
        if (ids.isEmpty()) {
            0
        } else {
            tenantDsl
                .current()
                .update(MEETING_PAPER)
                .set(MEETING_PAPER.REMOVED_AT, agendas.now())
                .set(MEETING_PAPER.REMOVED_BY, by)
                .where(MEETING_PAPER.ID.`in`(ids), MEETING_PAPER.REMOVED_AT.isNull)
                .execute()
        }

    private fun fields(row: PaperRow): Map<Field<*>, Any?> =
        mapOf(
            MEETING_PAPER.POSITION to row.position,
            MEETING_PAPER.TITLE to row.title,
            MEETING_PAPER.RESTRICTED_TO to row.restrictedTo?.toTypedArray<UUID?>(),
            MEETING_PAPER.CLASSIFICATION_ID to row.classificationId,
            MEETING_PAPER.LATE to row.late.name,
        )

    private fun row(r: MeetingPaperRecord) =
        PaperRow(
            id = r.id,
            meetingId = r.meetingId,
            itemId = r.itemId,
            position = r.position,
            source = PaperSource.valueOf(r.source),
            fileId = r.fileId,
            title = r.title,
            restrictedTo = r.restrictedTo?.filterNotNull(),
            classificationId = r.classificationId,
            late = Lateness.valueOf(r.late ?: Lateness.NONE.name),
        )
}

private val PRESENTERS = object : TypeReference<List<Presenter>>() {}
