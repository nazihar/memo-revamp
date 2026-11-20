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
import org.jooq.Condition
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.impl.DSL
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
    fun heads(meetingId: UUID): List<RevisionHead> = heads(listOf(meetingId))[meetingId].orEmpty()

    /** The revisions of each of [meetingIds], the first first, in one query ("My meetings", design E03 §8). */
    fun heads(meetingIds: Collection<UUID>): Map<UUID, List<RevisionHead>> =
        if (meetingIds.isEmpty()) {
            emptyMap()
        } else {
            tenantDsl
                .current()
                .selectFrom(MEETING_REVISION)
                .where(MEETING_REVISION.MEETING_ID.`in`(meetingIds))
                .orderBy(MEETING_REVISION.MEETING_ID, MEETING_REVISION.NUMBER)
                .fetch { head(it, json) }
                .groupBy { it.meetingId }
        }

    /**
     * Everything revision [revisionId] carries, in agenda order: the body and the removed rows. With [changedOnly], only
     * the items and papers that carry a change flag, and no sections: what "What changed" lists (design E03 §8).
     */
    fun snapshot(
        revisionId: UUID,
        changedOnly: Boolean = false,
    ): Snapshot = snapshots(listOf(revisionId), changedOnly).getValue(revisionId)

    /** [snapshot] of each of [revisionIds], three queries for them all (two with [changedOnly]). */
    fun snapshots(
        revisionIds: Collection<UUID>,
        changedOnly: Boolean = false,
    ): Map<UUID, Snapshot> {
        if (revisionIds.isEmpty()) return emptyMap()
        val dsl = tenantDsl.current()
        val s = MEETING_REVISION_SECTION
        val i = MEETING_REVISION_ITEM
        val p = MEETING_REVISION_PAPER
        val sections =
            if (changedOnly) {
                emptyMap()
            } else {
                dsl
                    .selectFrom(s)
                    .where(s.REVISION_ID.`in`(revisionIds))
                    .orderBy(s.REVISION_ID, s.POSITION)
                    .fetchGroups(s.REVISION_ID) {
                        RevisionSection(it.sectionId, it.position, it.title, AgendaSectionKind.valueOf(it.kind), it.membersOnly)
                    }
            }
        val paperRows =
            dsl
                .selectFrom(p)
                .where(p.REVISION_ID.`in`(revisionIds), if (changedOnly) DSL.cardinality(p.CHANGES).gt(0) else DSL.noCondition())
                .orderBy(p.REVISION_ID, p.REMOVED, p.ITEM_ID, p.POSITION)
                .fetch()
        // Changed only: an item that is flagged or carries a flagged paper.
        val changed =
            if (changedOnly) {
                DSL.cardinality(i.CHANGES).gt(0).or(
                    DSL.row(i.REVISION_ID, i.ITEM_ID).`in`(paperRows.map { DSL.row(it.revisionId, it.itemId) }.distinct()),
                )
            } else {
                DSL.noCondition()
            }
        val items =
            dsl
                .selectFrom(i)
                .where(i.REVISION_ID.`in`(revisionIds), changed)
                .orderBy(i.REVISION_ID, i.REMOVED, i.POSITION, i.ITEM_ID)
                .fetchGroups(i.REVISION_ID) { item(it, json) }
        val papers = paperRows.groupBy({ it.revisionId }, { paper(it) })
        return revisionIds.associateWith { id ->
            Snapshot(sections[id].orEmpty(), items[id].orEmpty(), papers[id].orEmpty())
        }
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
     * Each of [itemIds] as the latest revision carrying it (not removed there) published it, for the register of
     * declarations (E04 T4); the caller applies the title rule to it. Items never published are absent.
     */
    fun latestPublished(itemIds: Collection<UUID>): Map<UUID, RegisterItem> {
        val ri = MEETING_REVISION_ITEM
        val rows =
            if (itemIds.isEmpty()) {
                emptyList()
            } else {
                tenantDsl
                    .current()
                    .select(
                        ri.ITEM_ID,
                        ri.REVISION_ID,
                        ri.NUMBER,
                        ri.TITLE,
                        ri.TITLE_HIDDEN,
                        ri.CLASSIFICATION_ID,
                        ri.RESTRICTED_TO,
                        ri.MEMBERS_ONLY,
                    ).from(ri)
                    .where(ri.ITEM_ID.`in`(itemIds), ri.REMOVED.isFalse)
                    .fetch()
            }
        // The revisions' numbers by primary key: a join would hash the whole revision table (design E04 §8).
        val numbers =
            tenantDsl
                .current()
                .select(MEETING_REVISION.ID, MEETING_REVISION.NUMBER)
                .from(MEETING_REVISION)
                .where(MEETING_REVISION.ID.`in`(rows.mapNotNull { it.value2() }.toSet()))
                .fetch()
                .associate { checkNotNull(it.value1()) to checkNotNull(it.value2()) }
        return rows
            .groupBy { checkNotNull(it.value1()) }
            .mapValues { (_, carried) ->
                val latest = carried.maxBy { numbers[it.value2()] ?: 0 }
                RegisterItem(
                    number = latest.value3(),
                    title = checkNotNull(latest.value4()),
                    titleHidden = latest.value5() == true,
                    classificationId = checkNotNull(latest.value6()),
                    restricted = latest.value7() != null,
                    membersOnly = latest.value8() == true,
                    published = true,
                )
            }
    }

    /** Conditions on the revision tables for other queries' `WHERE` clauses (the paper rule's architecture test allows them here). */
    object Conditions {
        /** True for an item (the [item] column, in the row's [tenant]) that some revision published and did not remove. */
        fun everPublished(
            tenant: Field<UUID?>,
            item: Field<UUID?>,
        ): Condition =
            DSL.exists(
                DSL
                    .selectOne()
                    .from(MEETING_REVISION_ITEM)
                    .where(
                        MEETING_REVISION_ITEM.TENANT_ID.eq(tenant),
                        MEETING_REVISION_ITEM.ITEM_ID.eq(item),
                        MEETING_REVISION_ITEM.REMOVED.isFalse,
                    ),
            )
    }

    /** For each item ever in the body of a revision of [meetingId], the first revision that carried it (E04's review). */
    fun firstPublished(meetingId: UUID): Map<UUID, Int> {
        val first = DSL.min(MEETING_REVISION.NUMBER)
        return tenantDsl
            .current()
            .select(MEETING_REVISION_ITEM.ITEM_ID, first)
            .from(MEETING_REVISION_ITEM)
            .join(MEETING_REVISION)
            .on(MEETING_REVISION.TENANT_ID.eq(MEETING_REVISION_ITEM.TENANT_ID), MEETING_REVISION.ID.eq(MEETING_REVISION_ITEM.REVISION_ID))
            .where(MEETING_REVISION.MEETING_ID.eq(meetingId), MEETING_REVISION_ITEM.REMOVED.isFalse)
            .groupBy(MEETING_REVISION_ITEM.ITEM_ID)
            .fetch()
            .associate { checkNotNull(it.value1()) to checkNotNull(it.value2()) }
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

    private companion object {
        val PRESENTERS = object : TypeReference<List<Presenter>>() {}

        fun head(
            r: MeetingRevisionRecord,
            json: JsonMapper,
        ) = RevisionHead(
            id = r.id,
            meetingId = r.meetingId,
            number = r.number,
            publishedAt = r.publishedAt.toInstant(),
            publishedBy = r.publishedBy,
            note = r.note,
            notified = r.notified,
            chairAgreement = r.chairAgreement?.let { json.readValue(it.data(), ChairAgreement::class.java) },
        )

        fun item(
            r: MeetingRevisionItemRecord,
            json: JsonMapper,
        ) = RevisionItem(
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

        fun paper(r: MeetingRevisionPaperRecord) =
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
    }
}
