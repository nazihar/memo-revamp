package com.memo.actions.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.records.ActionsReportRecord
import com.memo.jooq.tables.references.ACTIONS_REPORT
import com.memo.jooq.tables.references.ACTIONS_REPORT_HOLD
import com.memo.jooq.tables.references.ACTIONS_REPORT_ROW
import com.memo.jooq.tables.references.ACTIONS_UPDATE_ASK
import org.jooq.Condition
import org.jooq.JSONB
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** One action taken report as built (design E07 §1.12): frozen, insert only. */
data class ReportHeader(
    val id: UUID,
    val forumId: UUID,
    val meetingId: UUID,
    val revision: Int,
    /** The MATTERS_ARISING item it hangs on. */
    val itemId: UUID,
    /** The previous meeting, whose start bounds "since the last meeting"; null when there was none. */
    val previousMeetingId: UUID?,
    val since: Instant?,
    val builtAt: Instant,
    /** The rows shown (sections 1-6), at most [ActionReports.ROWS_MAX]. */
    val rowCount: Int,
    /** The rows left out beyond the cap. */
    val moreCount: Int,
    val heldCount: Int,
    val contentSha256: String,
)

/** A row to write: its section, the action it snapshots, the snapshot (JSON) and the action's item columns and level. */
data class ReportRowInput(
    val section: ActionReportSection,
    val actionId: UUID?,
    val snapshot: String,
    val item: ItemCopy?,
    val itemId: UUID?,
    val classificationId: UUID,
)

/**
 * The action taken reports, the actions held back from them, and the key of "Ask owners for an update" (design E07
 * §1.12, §1.15; `actions/V4`), inside a tenant transaction: all insert only. The rows a reader may see are
 * [ActionRuleConditions.report], read here with the reports' headers and the holds; the actions' latest
 * updates.
 */
@Repository
class ActionReportStore(
    private val tenantDsl: TenantDsl,
) {
    /** Whether [meetingId]'s revision [revision] has its report. */
    fun exists(
        meetingId: UUID,
        revision: Int,
    ): Boolean =
        tenantDsl.current().fetchExists(
            ACTIONS_REPORT,
            ACTIONS_REPORT.MEETING_ID.eq(meetingId).and(ACTIONS_REPORT.REVISION.eq(revision)),
        )

    /** Writes [header] and its [rows] (positions from 1, in order). */
    fun insert(
        header: ReportHeader,
        rows: List<ReportRowInput>,
    ) {
        val dsl = tenantDsl.current()
        val tenant = tenantDsl.tenant().value
        val h = ACTIONS_REPORT
        dsl
            .insertInto(h)
            .set(h.ID, header.id)
            .set(h.TENANT_ID, tenant)
            .set(h.FORUM_ID, header.forumId)
            .set(h.MEETING_ID, header.meetingId)
            .set(h.REVISION, header.revision)
            .set(h.ITEM_ID, header.itemId)
            .set(h.PREVIOUS_MEETING_ID, header.previousMeetingId)
            .set(h.SINCE, header.since?.atOffset(ZoneOffset.UTC))
            .set(h.BUILT_AT, header.builtAt.atOffset(ZoneOffset.UTC))
            .set(h.ROW_COUNT, header.rowCount)
            .set(h.MORE_COUNT, header.moreCount)
            .set(h.HELD_COUNT, header.heldCount)
            .set(h.CONTENT_SHA256, header.contentSha256)
            .execute()
        insertRows(header.id, rows)
    }

    private fun insertRows(
        reportId: UUID,
        rows: List<ReportRowInput>,
    ) {
        if (rows.isEmpty()) return
        val dsl = tenantDsl.current()
        val tenant = tenantDsl.tenant().value
        val r = ACTIONS_REPORT_ROW
        rows.chunked(BATCH).forEachIndexed { chunk, part ->
            var insert =
                dsl.insertInto(
                    r,
                    r.TENANT_ID,
                    r.REPORT_ID,
                    r.POSITION,
                    r.SECTION,
                    r.ACTION_ID,
                    r.SNAPSHOT,
                    r.ITEM_ID,
                    r.ITEM_PARENT_ID,
                    r.ITEM_CLASSIFICATION_ID,
                    r.ITEM_RESTRICTED_TO,
                    r.ITEM_TITLE_HIDDEN,
                    r.ITEM_MEMBERS_ONLY,
                    r.CLASSIFICATION_ID,
                )
            part.forEachIndexed { i, row ->
                insert =
                    insert.values(
                        tenant,
                        reportId,
                        chunk * BATCH + i + 1,
                        row.section.name,
                        row.actionId,
                        JSONB.jsonb(row.snapshot),
                        row.itemId,
                        row.item?.parentId,
                        row.item?.classificationId,
                        row.item?.restrictedTo?.toTypedArray<UUID?>(),
                        row.item?.titleHidden ?: false,
                        row.item?.membersOnly ?: false,
                        row.classificationId,
                    )
            }
            insert.execute()
        }
    }

    /** [meetingId]'s report of [revision], or of its latest revision with one when [revision] is null. */
    fun find(
        meetingId: UUID,
        revision: Int?,
    ): ReportHeader? =
        tenantDsl
            .current()
            .selectFrom(ACTIONS_REPORT)
            .where(
                ACTIONS_REPORT.MEETING_ID.eq(meetingId),
                revision?.let { ACTIONS_REPORT.REVISION.eq(it) } ?: org.jooq.impl.DSL
                    .noCondition(),
            ).orderBy(ACTIONS_REPORT.REVISION.desc())
            .limit(1)
            .fetchOne { it.header() }

    /** The revisions of [meetingId] that have a report, oldest first. */
    fun revisions(meetingId: UUID): List<Int> =
        tenantDsl
            .current()
            .select(ACTIONS_REPORT.REVISION)
            .from(ACTIONS_REPORT)
            .where(ACTIONS_REPORT.MEETING_ID.eq(meetingId))
            .orderBy(ACTIONS_REPORT.REVISION)
            .fetch { checkNotNull(it.value1()) }

    /** The actions held back from [meetingId]'s reports. */
    fun held(meetingId: UUID): Set<UUID> =
        tenantDsl
            .current()
            .select(ACTIONS_REPORT_HOLD.ACTION_ID)
            .from(ACTIONS_REPORT_HOLD)
            .where(ACTIONS_REPORT_HOLD.MEETING_ID.eq(meetingId))
            .fetchSet { checkNotNull(it.value1()) }

    /** Holds [actionId] back from [meetingId]'s reports; false when it already is. */
    fun hold(
        actionId: UUID,
        meetingId: UUID,
        reason: String,
        by: UUID,
        at: Instant,
    ): Boolean {
        val h = ACTIONS_REPORT_HOLD
        return tenantDsl
            .current()
            .insertInto(h)
            .set(h.ID, UUID.randomUUID())
            .set(h.TENANT_ID, tenantDsl.tenant().value)
            .set(h.ACTION_ID, actionId)
            .set(h.MEETING_ID, meetingId)
            .set(h.REASON, reason)
            .set(h.HELD_BY, by)
            .set(h.HELD_AT, at.atOffset(ZoneOffset.UTC))
            .onConflictDoNothing()
            .execute() == 1
    }

    /** The rows of [reportId] matching [visible] (the rule, [ActionRuleConditions.report]), in report order. */
    fun rows(
        reportId: UUID,
        visible: Condition,
    ): List<ReportRowSeen> {
        val r = ACTIONS_REPORT_ROW
        return tenantDsl
            .current()
            .selectFrom(r)
            .where(r.REPORT_ID.eq(reportId), visible)
            .orderBy(r.POSITION)
            .fetch {
                ReportRowSeen(
                    it.position,
                    ActionReportSection.valueOf(it.section),
                    it.actionId,
                    checkNotNull(it.snapshot).data(),
                    it.itemId,
                )
            }
    }

    /** Records "Ask owners for an update" on [forumId] for [day]; false when it was already asked that day. */
    fun asked(
        forumId: UUID,
        day: LocalDate,
        by: UUID,
        at: Instant,
        count: Int,
    ): Boolean {
        val k = ACTIONS_UPDATE_ASK
        return tenantDsl
            .current()
            .insertInto(k)
            .set(k.TENANT_ID, tenantDsl.tenant().value)
            .set(k.FORUM_ID, forumId)
            .set(k.DAY, day)
            .set(k.ASKED_BY, by)
            .set(k.ASKED_AT, at.atOffset(ZoneOffset.UTC))
            .set(k.ASKED, count)
            .onConflictDoNothing()
            .execute() == 1
    }

    /** Whether "Ask owners for an update" was pressed on [forumId] on [day]. */
    fun askedOn(
        forumId: UUID,
        day: LocalDate,
    ): Boolean =
        tenantDsl.current().fetchExists(ACTIONS_UPDATE_ASK, ACTIONS_UPDATE_ASK.FORUM_ID.eq(forumId), ACTIONS_UPDATE_ASK.DAY.eq(day))

    private fun ActionsReportRecord.header() =
        ReportHeader(
            id = id,
            forumId = forumId,
            meetingId = meetingId,
            revision = revision,
            itemId = itemId,
            previousMeetingId = previousMeetingId,
            since = since?.toInstant(),
            builtAt = builtAt.toInstant(),
            rowCount = rowCount,
            moreCount = moreCount,
            heldCount = heldCount,
            contentSha256 = contentSha256,
        )

    private companion object {
        const val BATCH = 200
    }
}
