package com.memo.minutes.internal

import com.memo.common.tenant.TenantDsl
import com.memo.forum.OutcomeKind
import com.memo.jooq.tables.records.MinutesDecisionEntryRecord
import com.memo.jooq.tables.records.MinutesDecisionRecord
import com.memo.jooq.tables.references.MINUTES_DECISION
import com.memo.jooq.tables.references.MINUTES_DECISION_ENTRY
import com.memo.jooq.tables.references.MINUTES_ITEM_NOTE
import org.jooq.Condition
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** How a decision was taken (design E06 §1.1): by consensus (no vote, the default), or by a vote with optional counts. */
enum class VoteMode { CONSENSUS, VOTE }

/** Where a DEFERRED item goes: a named meeting of the committee, a date, or "the next meeting". */
data class DeferTarget(
    val meetingId: UUID? = null,
    val date: LocalDate? = null,
    val next: Boolean = false,
)

/** A member who asked that their dissent be recorded, with the reason they gave (optional). */
data class Dissent(
    val userId: UUID,
    val reason: String? = null,
)

/** A decision's head row (design E06 §1.1): the item it decides and where it stands. */
data class DecisionRow(
    val id: UUID,
    val meetingId: UUID,
    val itemId: UUID,
    val forumId: UUID,
    val submissionId: UUID?,
    val memoId: UUID?,
    val currentSeq: Int,
    val releasedAt: Instant?,
    val releasedKind: OutcomeKind?,
    val finalAt: Instant?,
    val version: Long,
)

/** One entry of a decision: the first is the decision as recorded, each later one a correction with its reason. */
data class EntryRow(
    val decisionId: UUID,
    val seq: Int,
    val outcomeKind: OutcomeKind,
    val outcomeLabel: String,
    val resolution: Boolean,
    val text: String?,
    val conditions: List<String>,
    val validUntil: LocalDate?,
    val deferTo: DeferTarget?,
    val referToForumId: UUID?,
    val referReason: String?,
    val voteMode: VoteMode,
    val votesFor: Int?,
    val votesAgainst: Int?,
    val votesAbstain: Int?,
    val dissent: List<Dissent>,
    val abstentions: List<UUID>,
    val tookNoPart: List<UUID>,
    val snapshotId: UUID,
    val ratificationRequired: Boolean,
    val ratifiesDecisionId: UUID?,
    val decidedAt: Instant,
    val consentBatchId: UUID?,
    val correction: Boolean,
    val reason: String?,
    val recordedBy: UUID,
    val recordedAt: Instant,
    /** On a "Minutes of the previous meeting" item: the meetings whose minutes it approves (AT_NEXT_MEETING, T6). */
    val approvesMinutesIds: List<UUID> = emptyList(),
)

/** A decision with its current entry. */
data class CurrentDecision(
    val decision: DecisionRow,
    val entry: EntryRow,
)

/** One saved version of the secretary's notes on an item (design E06 §1.2). */
data class NoteRow(
    val meetingId: UUID,
    val itemId: UUID,
    val seq: Int,
    val text: String,
    val savedBy: UUID,
    val savedAt: Instant,
)

/**
 * Decisions and their entries (design E06 §1.1), inside a tenant transaction: the locks and the writes. Entries are
 * append-only (the app role has no UPDATE or DELETE on them); the head row changes only by a guarded update on its
 * version, and the trigger repeats the rules. Reads are [DecisionReads].
 */
@Repository
class DecisionStore(
    private val tenantDsl: TenantDsl,
    private val json: JsonMapper,
) {
    /** The decision on [itemId], locked to the end of the transaction; null when there is none. */
    fun lock(itemId: UUID): DecisionRow? =
        tenantDsl
            .current()
            .selectFrom(MINUTES_DECISION)
            .where(MINUTES_DECISION.ITEM_ID.eq(itemId))
            .forUpdate()
            .fetchOne { it.decisionRow() }

    /** The decision [decisionId], locked to the end of the transaction; null when there is none. */
    fun lockById(decisionId: UUID): DecisionRow? =
        tenantDsl
            .current()
            .selectFrom(MINUTES_DECISION)
            .where(MINUTES_DECISION.ID.eq(decisionId))
            .forUpdate()
            .fetchOne { it.decisionRow() }

    /**
     * Marks [decision] (as seen at its version) sent to its memo with [kind], once (ADR-0065): the version moves on; false
     * when the decision moved on in between or was released already.
     */
    fun released(
        decision: DecisionRow,
        kind: OutcomeKind,
        at: Instant,
    ): Boolean {
        val d = MINUTES_DECISION
        return tenantDsl
            .current()
            .update(d)
            .set(d.RELEASED_AT, at.atOffset(ZoneOffset.UTC))
            .set(d.RELEASED_KIND, kind.name)
            .set(d.VERSION, decision.version + 1)
            .where(d.ID.eq(decision.id), d.VERSION.eq(decision.version), d.RELEASED_AT.isNull)
            .execute() == 1
    }

    /**
     * Every decision of [meetingId] made final at [at] (the minutes approved, design E06 §1.6.4): set once, each row locked
     * by the update (the first of the confirmation's locks); how many.
     */
    fun finalizeMeeting(
        meetingId: UUID,
        at: Instant,
    ): Int {
        val d = MINUTES_DECISION
        return tenantDsl
            .current()
            .update(d)
            .set(d.FINAL_AT, at.atOffset(ZoneOffset.UTC))
            .set(d.VERSION, d.VERSION.plus(1))
            .where(d.MEETING_ID.eq(meetingId), d.FINAL_AT.isNull)
            .execute()
    }

    /** The decisions on [itemIds], locked in id order (one statement, so two callers never lock them crosswise). */
    fun lockAll(itemIds: Collection<UUID>): List<DecisionRow> =
        if (itemIds.isEmpty()) {
            emptyList()
        } else {
            tenantDsl
                .current()
                .selectFrom(MINUTES_DECISION)
                .where(MINUTES_DECISION.ITEM_ID.`in`(itemIds))
                .orderBy(MINUTES_DECISION.ID)
                .forUpdate()
                .fetch { it.decisionRow() }
        }

    /** Whether [itemId] has a decision. */
    fun decided(itemId: UUID): Boolean =
        tenantDsl.current().fetchExists(DSL.selectOne().from(MINUTES_DECISION).where(MINUTES_DECISION.ITEM_ID.eq(itemId)))

    /**
     * Writes a new decision with its first entry; false when the item got one first (the unique key on the item decides
     * between two first records).
     */
    fun insert(
        decision: DecisionRow,
        entry: EntryRow,
    ): Boolean {
        val d = MINUTES_DECISION
        val inserted =
            tenantDsl
                .current()
                .insertInto(d)
                .set(d.ID, decision.id)
                .set(d.TENANT_ID, tenantDsl.tenant().value)
                .set(d.MEETING_ID, decision.meetingId)
                .set(d.ITEM_ID, decision.itemId)
                .set(d.FORUM_ID, decision.forumId)
                .set(d.SUBMISSION_ID, decision.submissionId)
                .set(d.MEMO_ID, decision.memoId)
                .set(d.CURRENT_SEQ, entry.seq)
                .set(d.VERSION, 0L)
                .onConflictDoNothing()
                .execute() == 1
        if (inserted) insertEntry(entry)
        return inserted
    }

    /** Appends [entry] to [decision] as seen at [version]; false when the decision moved on in between. */
    fun append(
        decision: DecisionRow,
        version: Long,
        entry: EntryRow,
    ): Boolean {
        val d = MINUTES_DECISION
        val moved =
            tenantDsl
                .current()
                .update(d)
                .set(d.CURRENT_SEQ, entry.seq)
                .set(d.VERSION, version + 1)
                .where(d.ID.eq(decision.id), d.VERSION.eq(version), d.CURRENT_SEQ.eq(entry.seq - 1))
                .execute() == 1
        if (moved) insertEntry(entry)
        return moved
    }

    private fun insertEntry(entry: EntryRow) {
        val e = MINUTES_DECISION_ENTRY
        tenantDsl
            .current()
            .insertInto(e)
            .set(e.TENANT_ID, tenantDsl.tenant().value)
            .set(e.DECISION_ID, entry.decisionId)
            .set(e.SEQ, entry.seq)
            .set(e.OUTCOME_KIND, entry.outcomeKind.name)
            .set(e.OUTCOME_LABEL, entry.outcomeLabel)
            .set(e.RESOLUTION, entry.resolution)
            .set(e.TEXT, entry.text)
            .set(e.CONDITIONS, JSONB.jsonb(json.writeValueAsString(entry.conditions)))
            .set(e.VALID_UNTIL, entry.validUntil)
            .set(e.DEFER_TO, entry.deferTo?.let { JSONB.jsonb(json.writeValueAsString(it)) })
            .set(e.REFER_TO_FORUM_ID, entry.referToForumId)
            .set(e.REFER_REASON, entry.referReason)
            .set(e.VOTE_MODE, entry.voteMode.name)
            .set(e.VOTES_FOR, entry.votesFor)
            .set(e.VOTES_AGAINST, entry.votesAgainst)
            .set(e.VOTES_ABSTAIN, entry.votesAbstain)
            .set(e.DISSENT, JSONB.jsonb(json.writeValueAsString(entry.dissent)))
            .set(e.ABSTENTIONS, entry.abstentions.toTypedArray<UUID?>())
            .set(e.TOOK_NO_PART, entry.tookNoPart.toTypedArray<UUID?>())
            .set(e.SNAPSHOT_ID, entry.snapshotId)
            .set(e.RATIFICATION_REQUIRED, entry.ratificationRequired)
            .set(e.RATIFIES_DECISION_ID, entry.ratifiesDecisionId)
            .set(e.APPROVES_MINUTES_IDS, entry.approvesMinutesIds.toTypedArray<UUID?>())
            .set(e.DECIDED_AT, entry.decidedAt.atOffset(ZoneOffset.UTC))
            .set(e.CONSENT_BATCH_ID, entry.consentBatchId)
            .set(e.CORRECTION, entry.correction)
            .set(e.REASON, entry.reason)
            .set(e.RECORDED_BY, entry.recordedBy)
            .set(e.RECORDED_AT, entry.recordedAt.atOffset(ZoneOffset.UTC))
            .execute()
    }
}

/**
 * Decisions as they are read (design E06 §1.1, §8), inside a tenant transaction: the current entries joined on
 * `(tenant_id, meeting_id)`, the history, and the ratifications. Readers reach them through [MinutesAccess].
 */
@Repository
class DecisionReads(
    private val tenantDsl: TenantDsl,
    private val json: JsonMapper,
) {
    /** The current decision on [itemId]; null when there is none. */
    fun current(itemId: UUID): CurrentDecision? = currentWhere(MINUTES_DECISION.ITEM_ID.eq(itemId)).firstOrNull()

    /** The current decision [decisionId]; null when there is none. */
    fun currentById(decisionId: UUID): CurrentDecision? = currentWhere(MINUTES_DECISION.ID.eq(decisionId)).firstOrNull()

    /** The current decisions [decisionIds] that exist, in one query (a page of the resolution register). */
    fun currentByIds(decisionIds: Collection<UUID>): List<CurrentDecision> =
        if (decisionIds.isEmpty()) emptyList() else currentWhere(MINUTES_DECISION.ID.`in`(decisionIds))

    /** [meetingId]'s current decisions: one query on `(tenant_id, meeting_id)` joined to the current entries. */
    fun currentOf(meetingId: UUID): List<CurrentDecision> = currentWhere(MINUTES_DECISION.MEETING_ID.eq(meetingId))

    /** The current decisions on the submissions of [submissionIds]. */
    fun currentOfSubmissions(submissionIds: Collection<UUID>): List<CurrentDecision> =
        if (submissionIds.isEmpty()) emptyList() else currentWhere(MINUTES_DECISION.SUBMISSION_ID.`in`(submissionIds))

    /** Up to [limit] decision ids after [after] (from the first when null), in id order. */
    fun ids(
        after: UUID?,
        limit: Int,
    ): List<UUID> =
        tenantDsl
            .current()
            .select(MINUTES_DECISION.ID)
            .from(MINUTES_DECISION)
            .where(if (after == null) DSL.noCondition() else MINUTES_DECISION.ID.gt(after))
            .orderBy(MINUTES_DECISION.ID)
            .limit(limit)
            .fetch(MINUTES_DECISION.ID)
            .filterNotNull()

    /** [forumId]'s current decisions taken without a quorum and not yet ratified by a later decision. */
    fun toBeRatified(forumId: UUID): List<CurrentDecision> {
        val later = MINUTES_DECISION_ENTRY.`as`("later")
        val head = MINUTES_DECISION.`as`("later_head")
        val ratified =
            DSL.exists(
                DSL
                    .selectOne()
                    .from(later)
                    .join(head)
                    .on(head.TENANT_ID.eq(later.TENANT_ID), head.ID.eq(later.DECISION_ID), head.CURRENT_SEQ.eq(later.SEQ))
                    .where(later.RATIFIES_DECISION_ID.eq(MINUTES_DECISION.ID)),
            )
        return currentWhere(MINUTES_DECISION.FORUM_ID.eq(forumId), MINUTES_DECISION_ENTRY.RATIFICATION_REQUIRED.isTrue, ratified.not())
    }

    /** For each of [decisionIds] that some current decision ratifies, the decisions ratifying it. */
    fun ratifiers(decisionIds: Collection<UUID>): Map<UUID, Set<UUID>> {
        if (decisionIds.isEmpty()) return emptyMap()
        val d = MINUTES_DECISION
        val e = MINUTES_DECISION_ENTRY
        return tenantDsl
            .current()
            .select(e.RATIFIES_DECISION_ID, e.DECISION_ID)
            .from(e)
            .join(d)
            .on(d.TENANT_ID.eq(e.TENANT_ID), d.ID.eq(e.DECISION_ID), d.CURRENT_SEQ.eq(e.SEQ))
            .where(e.RATIFIES_DECISION_ID.`in`(decisionIds))
            .fetch()
            .groupBy({ checkNotNull(it.value1()) }, { checkNotNull(it.value2()) })
            .mapValues { it.value.toSet() }
    }

    /** Every entry of [decisionId], the first first. */
    fun entries(decisionId: UUID): List<EntryRow> =
        tenantDsl
            .current()
            .selectFrom(MINUTES_DECISION_ENTRY)
            .where(MINUTES_DECISION_ENTRY.DECISION_ID.eq(decisionId))
            .orderBy(MINUTES_DECISION_ENTRY.SEQ)
            .fetch { it.entryRow(json) }

    private fun currentWhere(vararg conditions: Condition): List<CurrentDecision> {
        val d = MINUTES_DECISION
        val e = MINUTES_DECISION_ENTRY
        return tenantDsl
            .current()
            .select(d.asterisk(), e.asterisk())
            .from(d)
            .join(e)
            .on(e.TENANT_ID.eq(d.TENANT_ID), e.DECISION_ID.eq(d.ID), e.SEQ.eq(d.CURRENT_SEQ))
            .where(*conditions)
            .orderBy(d.ID)
            .fetch { CurrentDecision(it.into(d).decisionRow(), it.into(e).entryRow(json)) }
    }
}

private fun MinutesDecisionRecord.decisionRow() =
    DecisionRow(
        id = id,
        meetingId = meetingId,
        itemId = itemId,
        forumId = forumId,
        submissionId = submissionId,
        memoId = memoId,
        currentSeq = currentSeq,
        releasedAt = releasedAt?.toInstant(),
        releasedKind = releasedKind?.let(OutcomeKind::valueOf),
        finalAt = finalAt?.toInstant(),
        version = version ?: 0,
    )

private val STRINGS = object : TypeReference<List<String>>() {}
private val DISSENT = object : TypeReference<List<Dissent>>() {}

private fun MinutesDecisionEntryRecord.entryRow(json: JsonMapper) =
    EntryRow(
        decisionId = decisionId,
        seq = seq,
        outcomeKind = OutcomeKind.valueOf(outcomeKind),
        outcomeLabel = outcomeLabel,
        resolution = resolution,
        text = text,
        conditions = conditions?.let { json.readValue(it.data(), STRINGS) }.orEmpty(),
        validUntil = validUntil,
        deferTo = deferTo?.let { json.readValue(it.data(), DeferTarget::class.java) },
        referToForumId = referToForumId,
        referReason = referReason,
        voteMode = VoteMode.valueOf(voteMode),
        votesFor = votesFor,
        votesAgainst = votesAgainst,
        votesAbstain = votesAbstain,
        dissent = dissent?.let { json.readValue(it.data(), DISSENT) }.orEmpty(),
        abstentions = abstentions.orEmpty().filterNotNull(),
        tookNoPart = tookNoPart.orEmpty().filterNotNull(),
        snapshotId = snapshotId,
        ratificationRequired = ratificationRequired,
        ratifiesDecisionId = ratifiesDecisionId,
        decidedAt = decidedAt.toInstant(),
        consentBatchId = consentBatchId,
        correction = correction,
        reason = reason,
        recordedBy = recordedBy,
        recordedAt = recordedAt.toInstant(),
        approvesMinutesIds = approvesMinutesIds.orEmpty().filterNotNull(),
    )

/** The secretary's notes per item (design E06 §1.2), append-only versions inside a tenant transaction. */
@Repository
class NoteStore(
    private val tenantDsl: TenantDsl,
) {
    /** The latest version of the note on [itemId]; null when none was saved. */
    fun latest(itemId: UUID): NoteRow? =
        tenantDsl
            .current()
            .selectFrom(MINUTES_ITEM_NOTE)
            .where(MINUTES_ITEM_NOTE.ITEM_ID.eq(itemId))
            .orderBy(MINUTES_ITEM_NOTE.SEQ.desc())
            .limit(1)
            .fetchOne { NoteRow(it.meetingId, it.itemId, it.seq, it.text, it.savedBy, it.savedAt.toInstant()) }

    /** The latest note on each item of [meetingId] that has one, by item (the draft's "key points discussed"). */
    fun latestOf(meetingId: UUID): Map<UUID, NoteRow> =
        tenantDsl
            .current()
            .selectFrom(MINUTES_ITEM_NOTE)
            .where(MINUTES_ITEM_NOTE.MEETING_ID.eq(meetingId))
            .orderBy(MINUTES_ITEM_NOTE.ITEM_ID, MINUTES_ITEM_NOTE.SEQ.desc())
            .fetch { NoteRow(it.meetingId, it.itemId, it.seq, it.text, it.savedBy, it.savedAt.toInstant()) }
            .groupBy { it.itemId }
            .mapValues { it.value.first() }

    /** Saves [row] as the next version; false when that version exists already (someone saved first). */
    fun insert(row: NoteRow): Boolean {
        val n = MINUTES_ITEM_NOTE
        return tenantDsl
            .current()
            .insertInto(n)
            .set(n.TENANT_ID, tenantDsl.tenant().value)
            .set(n.MEETING_ID, row.meetingId)
            .set(n.ITEM_ID, row.itemId)
            .set(n.SEQ, row.seq)
            .set(n.TEXT, row.text)
            .set(n.SAVED_BY, row.savedBy)
            .set(n.SAVED_AT, row.savedAt.atOffset(ZoneOffset.UTC))
            .onConflictDoNothing()
            .execute() == 1
    }
}
