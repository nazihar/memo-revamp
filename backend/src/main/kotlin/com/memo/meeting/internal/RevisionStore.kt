package com.memo.meeting.internal

import com.memo.common.tenant.TenantDsl
import com.memo.forum.AgendaSectionKind
import com.memo.forum.StandingKind
import com.memo.forum.SubmissionPurpose
import com.memo.jooq.tables.records.MeetingRevisionItemRecord
import com.memo.jooq.tables.records.MeetingRevisionPaperRecord
import com.memo.jooq.tables.records.MeetingRevisionRecord
import com.memo.jooq.tables.references.MEETING_REVISION
import com.memo.jooq.tables.references.MEETING_REVISION_ITEM
import com.memo.jooq.tables.references.MEETING_REVISION_NOTICE
import com.memo.jooq.tables.references.MEETING_REVISION_PAPER
import com.memo.jooq.tables.references.MEETING_REVISION_SECTION
import org.jooq.JSONB
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** A published revision's head (design E03 §1.3): who published it when, its note and whether the audience was told. */
data class RevisionHead(
    val id: UUID,
    val meetingId: UUID,
    val number: Int,
    val publishedAt: Instant,
    val publishedBy: UUID?,
    val note: String?,
    val notified: Boolean,
    val chairAgreement: ChairAgreement?,
)

/**
 * Published revisions (ADR-0060), inside a tenant transaction: written once at publication and never changed (the app
 * role has no UPDATE or DELETE on them). With `PaperAccess` (T4), the only reader of the revision tables (ADR-0061).
 */
@Repository
class RevisionStore(
    private val tenantDsl: TenantDsl,
    private val json: JsonMapper,
) {
    /** Writes revision [head] of its meeting with the copy [snapshot] (flagged, removed rows included). */
    fun insert(
        head: RevisionHead,
        snapshot: Snapshot,
    ) {
        val dsl = tenantDsl.current()
        val tenant = tenantDsl.tenant().value
        dsl
            .insertInto(MEETING_REVISION)
            .set(MEETING_REVISION.ID, head.id)
            .set(MEETING_REVISION.TENANT_ID, tenant)
            .set(MEETING_REVISION.MEETING_ID, head.meetingId)
            .set(MEETING_REVISION.NUMBER, head.number)
            .set(MEETING_REVISION.PUBLISHED_AT, head.publishedAt.atOffset(ZoneOffset.UTC))
            .set(MEETING_REVISION.PUBLISHED_BY, head.publishedBy)
            .set(MEETING_REVISION.NOTE, head.note)
            .set(MEETING_REVISION.NOTIFIED, head.notified)
            .set(MEETING_REVISION.CHAIR_AGREEMENT, head.chairAgreement?.let { JSONB.valueOf(json.writeValueAsString(it)) })
            .execute()
        val sections =
            snapshot.sections.map { s ->
                dsl.newRecord(MEETING_REVISION_SECTION).apply {
                    tenantId = tenant
                    revisionId = head.id
                    sectionId = s.sectionId
                    position = s.position
                    title = s.title
                    kind = s.kind.name
                    membersOnly = s.membersOnly
                }
            }
        val items = snapshot.items.map { record(it, tenant, head.id) }
        val papers = snapshot.papers.map { record(it, tenant, head.id) }
        val rows = sections + items + papers
        if (rows.isNotEmpty()) dsl.batchInsert(rows).execute()
    }

    /** The revisions of [meetingId], the first first. */
    fun heads(meetingId: UUID): List<RevisionHead> =
        tenantDsl
            .current()
            .selectFrom(MEETING_REVISION)
            .where(MEETING_REVISION.MEETING_ID.eq(meetingId))
            .orderBy(MEETING_REVISION.NUMBER)
            .fetch { head(it) }

    /** Everything revision [revisionId] carries, in agenda order: the body and the removed rows. */
    fun snapshot(revisionId: UUID): Snapshot {
        val dsl = tenantDsl.current()
        val sections =
            dsl
                .selectFrom(MEETING_REVISION_SECTION)
                .where(MEETING_REVISION_SECTION.REVISION_ID.eq(revisionId))
                .orderBy(MEETING_REVISION_SECTION.POSITION)
                .fetch {
                    RevisionSection(it.sectionId, it.position, it.title, AgendaSectionKind.valueOf(it.kind), it.membersOnly)
                }
        val items =
            dsl
                .selectFrom(MEETING_REVISION_ITEM)
                .where(MEETING_REVISION_ITEM.REVISION_ID.eq(revisionId))
                .orderBy(MEETING_REVISION_ITEM.REMOVED, MEETING_REVISION_ITEM.POSITION, MEETING_REVISION_ITEM.ITEM_ID)
                .fetch { item(it) }
        val papers =
            dsl
                .selectFrom(MEETING_REVISION_PAPER)
                .where(MEETING_REVISION_PAPER.REVISION_ID.eq(revisionId))
                .orderBy(MEETING_REVISION_PAPER.REMOVED, MEETING_REVISION_PAPER.ITEM_ID, MEETING_REVISION_PAPER.POSITION)
                .fetch { paper(it) }
        return Snapshot(sections, items, papers)
    }

    /** Whether [itemId] is in the body of [meetingId]'s latest revision. */
    fun inLatest(
        meetingId: UUID,
        itemId: UUID,
    ): Boolean {
        val dsl = tenantDsl.current()
        val latest =
            dsl
                .select(MEETING_REVISION.ID)
                .from(MEETING_REVISION)
                .where(MEETING_REVISION.MEETING_ID.eq(meetingId))
                .orderBy(MEETING_REVISION.NUMBER.desc())
                .limit(1)
        return dsl.fetchExists(
            dsl
                .selectOne()
                .from(MEETING_REVISION_ITEM)
                .where(
                    MEETING_REVISION_ITEM.REVISION_ID.eq(latest),
                    MEETING_REVISION_ITEM.ITEM_ID.eq(itemId),
                    MEETING_REVISION_ITEM.REMOVED.isFalse,
                ),
        )
    }

    /**
     * Records that [userId] is told of [revisionId] as [kind]: the dedupe key of the per-recipient notifications (design E03
     * §9). False when they already were.
     */
    fun claim(
        revisionId: UUID,
        userId: UUID,
        kind: String,
        at: Instant,
    ): Boolean =
        tenantDsl
            .current()
            .insertInto(MEETING_REVISION_NOTICE)
            .set(MEETING_REVISION_NOTICE.TENANT_ID, tenantDsl.tenant().value)
            .set(MEETING_REVISION_NOTICE.REVISION_ID, revisionId)
            .set(MEETING_REVISION_NOTICE.USER_ID, userId)
            .set(MEETING_REVISION_NOTICE.KIND, kind)
            .set(MEETING_REVISION_NOTICE.NOTIFIED_AT, at.atOffset(ZoneOffset.UTC))
            .onConflictDoNothing()
            .execute() == 1

    private fun head(r: MeetingRevisionRecord) =
        RevisionHead(
            id = r.id,
            meetingId = r.meetingId,
            number = r.number,
            publishedAt = r.publishedAt.toInstant(),
            publishedBy = r.publishedBy,
            note = r.note,
            notified = r.notified,
            chairAgreement = r.chairAgreement?.let { json.readValue(it.data(), ChairAgreement::class.java) },
        )

    private fun record(
        item: RevisionItem,
        tenant: UUID,
        revision: UUID,
    ) = tenantDsl.current().newRecord(MEETING_REVISION_ITEM).apply {
        tenantId = tenant
        revisionId = revision
        itemId = item.itemId
        sectionId = item.sectionId
        parentItemId = item.parentId
        position = item.position
        number = item.number
        previousNumber = item.previousNumber
        kind = item.kind.name
        standingKind = item.standingKind?.name
        submissionId = item.submissionId
        memoId = item.memoId
        title = item.title
        description = item.description
        purpose = item.purpose?.name
        resolutionSought = item.resolutionSought
        minutes = item.minutes
        startsAt = item.startsAt?.atOffset(ZoneOffset.UTC)
        presenters = JSONB.valueOf(json.writeValueAsString(item.presenters))
        attendees = item.attendees.sorted().toTypedArray<UUID?>()
        restrictedTo = item.restrictedTo?.toTypedArray<UUID?>()
        titleHidden = item.titleHidden
        membersOnly = item.membersOnly
        classificationId = item.classificationId
        late = item.late.name
        removed = item.removed
        changes =
            item.changes
                .map { it.name }
                .sorted()
                .toTypedArray<String?>()
    }

    private fun record(
        paper: RevisionPaper,
        tenant: UUID,
        revision: UUID,
    ) = tenantDsl.current().newRecord(MEETING_REVISION_PAPER).apply {
        tenantId = tenant
        revisionId = revision
        paperId = paper.paperId
        itemId = paper.itemId
        position = paper.position
        source = paper.source.name
        fileId = paper.fileId
        fileVersionId = paper.fileVersionId
        title = paper.title
        restrictedTo = paper.restrictedTo?.toTypedArray<UUID?>()
        classificationId = paper.classificationId
        late = paper.late.name
        pages = paper.pages
        removed = paper.removed
        changes =
            paper.changes
                .map { it.name }
                .sorted()
                .toTypedArray<String?>()
    }

    private fun item(r: MeetingRevisionItemRecord) =
        RevisionItem(
            itemId = r.itemId,
            sectionId = r.sectionId,
            parentId = r.parentItemId,
            position = r.position,
            number = r.number,
            kind = ItemKind.valueOf(r.kind),
            standingKind = r.standingKind?.let { StandingKind.valueOf(it) },
            submissionId = r.submissionId,
            memoId = r.memoId,
            title = r.title,
            description = r.description,
            purpose = r.purpose?.let { SubmissionPurpose.valueOf(it) },
            resolutionSought = r.resolutionSought,
            minutes = r.minutes,
            startsAt = r.startsAt?.toInstant(),
            presenters = json.readValue(r.presenters.data(), PRESENTERS),
            attendees = r.attendees.filterNotNull().toSet(),
            restrictedTo = r.restrictedTo?.filterNotNull(),
            titleHidden = r.titleHidden,
            membersOnly = r.membersOnly,
            classificationId = r.classificationId,
            late = PublishedLate.valueOf(r.late),
            removed = r.removed == true,
            previousNumber = r.previousNumber,
            changes =
                r.changes
                    .orEmpty()
                    .filterNotNull()
                    .map { ItemFlag.valueOf(it) }
                    .toSet(),
        )

    private fun paper(r: MeetingRevisionPaperRecord) =
        RevisionPaper(
            paperId = r.paperId,
            itemId = r.itemId,
            position = r.position,
            source = PaperSource.valueOf(r.source),
            fileId = r.fileId,
            fileVersionId = r.fileVersionId,
            title = r.title,
            restrictedTo = r.restrictedTo?.filterNotNull(),
            classificationId = r.classificationId,
            late = PublishedLate.valueOf(r.late),
            pages = r.pages,
            removed = r.removed == true,
            changes =
                r.changes
                    .orEmpty()
                    .filterNotNull()
                    .map { PaperFlag.valueOf(it) }
                    .toSet(),
        )

    private companion object {
        val PRESENTERS = object : TypeReference<List<Presenter>>() {}
    }
}
