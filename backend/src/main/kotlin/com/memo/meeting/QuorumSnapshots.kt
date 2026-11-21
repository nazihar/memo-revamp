package com.memo.meeting

import com.memo.audit.AuditCanonicalForm
import com.memo.audit.AuditLog
import com.memo.common.authz.ForbiddenException
import com.memo.common.id.IdGenerator
import com.memo.common.web.FieldProblem
import com.memo.common.web.RefusedException
import com.memo.common.web.refuseIf
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumPolicies
import com.memo.forum.ForumRole
import com.memo.forum.Quorum
import com.memo.forum.QuorumOverride
import com.memo.forum.QuorumPresence
import com.memo.forum.QuorumResult
import com.memo.forum.QuorumRule
import com.memo.forum.QuorumSeat
import com.memo.meeting.internal.MeetingGuard
import com.memo.meeting.internal.MeetingLifecycle
import com.memo.meeting.internal.MeetingNotFoundException
import com.memo.meeting.internal.MeetingNotifier
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.MeetingStore
import com.memo.meeting.internal.actorOrNull
import com.memo.meeting.internal.attendance.AttendanceRules
import com.memo.meeting.internal.attendance.RegisterChecks
import com.memo.meeting.internal.quorum.LiveQuorum
import com.memo.meeting.internal.quorum.QuorumAt
import com.memo.meeting.internal.quorum.SnapshotRow
import com.memo.meeting.internal.quorum.SnapshotStore
import com.memo.meeting.internal.requireNoProblems
import com.memo.meeting.internal.rsvp.RsvpRules
import com.memo.notification.NotificationKind
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** A roster seat in office on the meeting's date, as a quorum snapshot keeps it. */
data class QuorumSnapshotSeat(
    val memberId: UUID,
    val userId: UUID,
    val role: ForumRole,
    val voting: Boolean,
    val attributes: List<String>,
)

/** A seat filled at the snapshot's instant: by whom (the holder or the alternate standing in) and how. */
data class QuorumSnapshotPresence(
    val memberId: UUID,
    val userId: UUID,
    val remote: Boolean,
    val byAlternate: Boolean,
    /** The holder is recused from the item (counted only with the rule's `recusedCount`). */
    val recused: Boolean,
)

/**
 * What a snapshot evaluated (ADR-0064, design E05 §1.7): the meeting, the item, the instant, the pinned type version and
 * its rule, the roster, who filled which seat, the people recused from the item (an alternate of a recused member with
 * them) and those out of the room for it. [canonical] is the audit module's canonical JSON of it (sorted, so the order of
 * the lists does not matter) and [sha256] its hash; [evaluate] gives the result again.
 */
data class QuorumSnapshotInputs(
    val meetingId: UUID,
    val itemId: UUID?,
    val at: Instant,
    val typeId: UUID,
    val typeVersion: Int,
    val rule: QuorumRule,
    val roster: List<QuorumSnapshotSeat>,
    val presence: List<QuorumSnapshotPresence>,
    val recused: List<UUID>,
    val steppedOut: List<UUID>,
) {
    /** The result these inputs give: [Quorum.evaluate] over the roster and the presence. */
    fun evaluate(): QuorumResult =
        Quorum.evaluate(
            rule,
            roster.map { QuorumSeat(it.memberId, it.role, it.voting, it.attributes.toSet()) },
            presence.associate { it.memberId to QuorumPresence(it.remote, it.byAlternate, it.recused) },
        )

    /** The canonical JSON (ADR-0019's form): keys sorted, lists sorted by id, the instant in microseconds. */
    fun canonical(): String =
        AuditCanonicalForm.json(
            mapOf(
                "meetingId" to meetingId.toString(),
                "itemId" to itemId?.toString(),
                "at" to AuditCanonicalForm.timestamp(at),
                "typeId" to typeId.toString(),
                "typeVersion" to typeVersion,
                "rule" to canonical(rule),
                "roster" to
                    roster.sortedBy { it.memberId.toString() }.map {
                        mapOf(
                            "memberId" to it.memberId.toString(),
                            "userId" to it.userId.toString(),
                            "role" to it.role.name,
                            "voting" to it.voting,
                            "attributes" to it.attributes.distinct().sorted(),
                        )
                    },
                "presence" to
                    presence.sortedBy { it.memberId.toString() }.map {
                        mapOf(
                            "memberId" to it.memberId.toString(),
                            "userId" to it.userId.toString(),
                            "remote" to it.remote,
                            "byAlternate" to it.byAlternate,
                            "recused" to it.recused,
                        )
                    },
                "recused" to recused.map { it.toString() }.distinct().sorted(),
                "steppedOut" to steppedOut.map { it.toString() }.distinct().sorted(),
            ),
        )

    /** SHA-256 of [canonical], in lower-case hex. */
    fun sha256(): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(canonical().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        /** The inputs of [quorum] (at [at], for its item) of [meetingId], with the pinned type version [typeId] [typeVersion]. */
        fun of(
            meetingId: UUID,
            quorum: QuorumAt,
            typeId: UUID,
            typeVersion: Int,
            at: Instant,
        ) = QuorumSnapshotInputs(
            meetingId = meetingId,
            itemId = quorum.itemId,
            at = at,
            typeId = typeId,
            typeVersion = typeVersion,
            rule = quorum.rule,
            roster =
                quorum.roster
                    .map { QuorumSnapshotSeat(it.memberId, it.userId, it.role, it.voting, it.attributes.sorted()) }
                    .sortedBy { it.memberId.toString() },
            presence =
                quorum.seats
                    .map { QuorumSnapshotPresence(it.memberId, it.userId, it.remote, it.byAlternate, it.recused) }
                    .sortedBy { it.memberId.toString() },
            recused = quorum.recused.sortedBy { it.toString() },
            steppedOut = quorum.steppedOut.sortedBy { it.toString() },
        )

        private fun canonical(rule: QuorumRule): Map<String, Any?> =
            mapOf(
                "counts" to rule.counts.name,
                "basis" to rule.basis.name,
                "seats" to rule.seats,
                "threshold" to
                    mapOf(
                        "kind" to rule.threshold.kind.name,
                        "numerator" to rule.threshold.numerator,
                        "denominator" to rule.threshold.denominator,
                        "count" to rule.threshold.count,
                    ),
                "minimum" to rule.minimum,
                "required" to rule.required.map { roles -> roles.map { it.name } },
                "composition" to
                    rule.composition.map {
                        mapOf("attribute" to it.attribute, "atLeast" to it.atLeast, "majority" to it.majority)
                    },
                "alternatesCount" to rule.alternatesCount,
                "remoteCounts" to rule.remoteCounts,
                "recusedCount" to rule.recusedCount,
            )
    }
}

/** A decision recorded without a quorum (ADR-0064): by whom, why; to be ratified at a quorate meeting. */
data class QuorumOverrideRecord(
    val by: UUID,
    val reason: String,
)

/** The quorum a decision was taken with (ADR-0064, design E05 §1.7), as E06 keeps it with the decision. */
data class QuorumSnapshot(
    val id: UUID,
    val meetingId: UUID,
    /** Null: a decision of the meeting as a whole. */
    val itemId: UUID?,
    val at: Instant,
    val inputs: QuorumSnapshotInputs,
    val result: QuorumResult,
    /** Set only when the meeting was not quorate and the committee type allows an override. */
    val override: QuorumOverrideRecord?,
    /** "To be ratified at a quorate meeting": an override. */
    val ratificationRequired: Boolean,
    val inputsSha256: String,
    val takenBy: UUID?,
    val takenAt: Instant,
) {
    val quorate: Boolean get() = result.quorate
}

/**
 * A snapshot compared with what is known now (design E05 §1.7): the same instant and item evaluated again with the
 * register as corrected since (and the recusals and roster as they are now). [drifted]: the inputs differ ("attendance was
 * corrected after this decision"); [now]: the result they give now.
 */
data class QuorumDrift(
    val snapshotId: UUID,
    val drifted: Boolean,
    val now: QuorumResult,
)

/** A decision cannot be recorded without a quorum (409 `NOT_QUORATE`), with the numbers and the conditions not met. */
class NotQuorateException(
    val result: QuorumResult,
) : RefusedException(
        "NOT_QUORATE",
        "Not quorate: ${result.present} of ${result.needed} needed.",
        mapOf("needed" to result.needed, "present" to result.present, "unmet" to result.unmet),
    )

/**
 * The quorum recorded with each decision (ADR-0064, design E05 §1.7), for E06. Inside the caller's tenant transaction.
 *  - [take] evaluates the meeting's (or an item's) quorum at an instant exactly as `GET …/quorum?itemId=&at=` does, and
 *    stores it with its inputs and their SHA-256, append-only. Not quorate: 409 `NOT_QUORATE`, nothing stored; unless an
 *    override reason is given and the pinned type version's `meetings.quorumOverride` is WITH_REASON (else 409
 *    `OVERRIDE_NOT_ALLOWED`) and the actor is not recused from the item (403 `RECUSED`): then the snapshot is not quorate,
 *    overridden and to be ratified, audited, and the chair and the other secretaries are told.
 *  - [find] and [ofMeeting] read them back; [drifted] compares one with the register now.
 * [take] needs a secretary of the committee or `forums.manage` (403; 404 for a meeting the actor does not see) and locks
 * the meeting row, as attendance writes do: E06 calls it after locking its submission rows and before the agenda row.
 */
@Component
class QuorumSnapshots(
    private val guard: MeetingGuard,
    private val meetings: MeetingStore,
    private val checks: RegisterChecks,
    private val live: LiveQuorum,
    private val directory: ForumDirectory,
    private val policies: ForumPolicies,
    private val recusals: Recusals,
    private val store: SnapshotStore,
    private val notifier: MeetingNotifier,
    private val ids: IdGenerator,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    /**
     * The quorum of [meetingId] for [itemId] (null: the meeting) at [at] (default now), stored; [overrideReason] records
     * the decision without a quorum when the committee type allows it (ignored when the meeting is quorate).
     */
    fun take(
        meetingId: UUID,
        itemId: UUID? = null,
        at: Instant? = null,
        overrideReason: String? = null,
    ): QuorumSnapshot {
        val seen = guard.requireManaged(meetingId)
        val meeting = meetings.lock(seen.id) ?: throw MeetingNotFoundException()
        refuseIf(meeting.status !in DECIDING, "WRONG_STATE") {
            "A decision's quorum is recorded while the meeting is in progress, or corrected once it is held."
        }
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val time = (at ?: now).truncatedTo(ChronoUnit.MICROS)
        val (reason, reasonProblem) = RsvpRules.optional("reason", overrideReason, AttendanceRules.MAX_REASON)
        requireNoProblems(
            listOfNotNull(
                FieldProblem("at", "TIME_OUT_OF_RANGE", "Give a time during the meeting, not in the future.")
                    .takeIf { time.isAfter(now.plus(AttendanceRules.CLOCK_SKEW)) || time.isBefore(meeting.startsAt.minus(EARLY)) },
                checks.itemProblem(meeting, itemId, required = false),
                reasonProblem,
            ),
        )
        val forum = directory.forum(meeting.forumId) ?: throw MeetingNotFoundException("forum")
        val inputs = QuorumSnapshotInputs.of(meeting.id, live.evaluate(meeting, time, itemId), forum.typeId, meeting.typeVersion, time)
        val result = inputs.evaluate()
        val override = if (result.quorate) null else override(meeting, forum.typeId, itemId, overrideReason, reason, result)
        val row =
            SnapshotRow(
                id = ids.next(),
                meetingId = meeting.id,
                itemId = itemId,
                at = time,
                inputs = inputs,
                result = result,
                overrideReason = override?.reason,
                inputsSha256 = inputs.sha256(),
                takenBy = actorOrNull(),
                takenAt = now,
            )
        store.insert(row)
        audited(meeting, row)
        if (override != null) told(meeting, row)
        return snapshot(row)
    }

    /** The snapshot [snapshotId]; null when there is none. No access decision is made here. */
    fun find(snapshotId: UUID): QuorumSnapshot? = store.find(snapshotId)?.let(::snapshot)

    /** Every snapshot of [meetingId], the oldest first (E06's minutes). No access decision is made here. */
    fun ofMeeting(meetingId: UUID): List<QuorumSnapshot> = store.ofMeeting(meetingId).map(::snapshot)

    /** [snapshotId] evaluated again at its instant with the register, the recusals and the roster as they are now. */
    fun drifted(snapshotId: UUID): QuorumDrift? =
        store.find(snapshotId)?.let { row ->
            meetings.find(row.meetingId)?.let { meeting ->
                val now =
                    QuorumSnapshotInputs.of(
                        meeting.id,
                        live.evaluate(meeting, row.at, row.itemId),
                        row.inputs.typeId,
                        row.inputs.typeVersion,
                        row.at,
                    )
                QuorumDrift(row.id, now.sha256() != row.inputsSha256, now.evaluate())
            }
        }

    /** The override of a missing quorum: the type's policy, an actor not recused from the item, a reason. */
    private fun override(
        meeting: MeetingRow,
        typeId: UUID,
        itemId: UUID?,
        requested: String?,
        reason: String?,
        result: QuorumResult,
    ): QuorumOverrideRecord {
        if (requested == null) throw NotQuorateException(result)
        val policy = policies.version(typeId, meeting.typeVersion)?.meetings?.quorumOverride ?: QuorumOverride.NEVER
        refuseIf(policy != QuorumOverride.WITH_REASON, "OVERRIDE_NOT_ALLOWED") {
            "This committee's type does not allow a decision without a quorum."
        }
        // The guard let a secretary or forums.manage through: a signed-in user.
        val actor = checkNotNull(actorOrNull()) { "an override needs a signed-in user" }
        if (itemId != null && actor in recusals.recusedFrom(itemId)) {
            throw ForbiddenException(null, "RECUSED", "You declared an interest in this item: another officer records the override.")
        }
        requireNoProblems(listOfNotNull(RegisterChecks.reasonRequired(reason == null)))
        return QuorumOverrideRecord(actor, checkNotNull(reason))
    }

    private fun audited(
        meeting: MeetingRow,
        row: SnapshotRow,
    ) {
        val objectId = meeting.id.toString()
        audit.record(
            "meeting.quorum.snapshot_taken",
            MeetingLifecycle.OBJECT_TYPE,
            objectId,
            after =
                mapOf(
                    "snapshotId" to row.id.toString(),
                    "itemId" to row.itemId?.toString(),
                    "at" to row.at.toString(),
                    "quorate" to row.result.quorate,
                    "needed" to row.result.needed,
                    "present" to row.result.present,
                    "overridden" to (row.overrideReason != null),
                    "inputsSha256" to row.inputsSha256,
                ),
        )
        val reason = row.overrideReason ?: return
        audit.record(
            "meeting.quorum.overridden",
            MeetingLifecycle.OBJECT_TYPE,
            objectId,
            after =
                mapOf(
                    "snapshotId" to row.id.toString(),
                    "itemId" to row.itemId?.toString(),
                    "by" to row.takenBy?.toString(),
                    "reasonSha256" to RsvpRules.sha256(reason),
                    "needed" to row.result.needed,
                    "present" to row.result.present,
                    "unmet" to row.result.unmet.map { it.kind.name },
                ),
        )
    }

    /** The chair (the vice-chair when there is none) and the secretaries, but the actor, in the app: never the item. */
    private fun told(
        meeting: MeetingRow,
        row: SnapshotRow,
    ) {
        val forum = directory.forum(meeting.forumId) ?: return
        val details = mapOf("present" to row.result.present.toString(), "needed" to row.result.needed.toString())
        val told = notifier.secretaries(forum.id) + setOfNotNull(notifier.chairOf(forum.id))
        (told - setOfNotNull(actorOrNull())).forEach {
            notifier.person(it, NotificationKind.MEETING_QUORUM_OVERRIDDEN, meeting, forum, details)
        }
    }

    private fun snapshot(row: SnapshotRow) =
        QuorumSnapshot(
            id = row.id,
            meetingId = row.meetingId,
            itemId = row.itemId,
            at = row.at,
            inputs = row.inputs,
            result = row.result,
            override = row.overrideReason?.let { QuorumOverrideRecord(checkNotNull(row.takenBy), it) },
            ratificationRequired = row.overrideReason != null,
            inputsSha256 = row.inputsSha256,
            takenBy = row.takenBy,
            takenAt = row.takenAt,
        )

    private companion object {
        /** Decisions are recorded at the meeting, and corrected (a new snapshot) once it is held. */
        val DECIDING = setOf(MeetingStatus.IN_PROGRESS, MeetingStatus.HELD)

        /** As attendance (design E05 §1.6): an instant from an hour before the start. */
        val EARLY: Duration = Duration.ofHours(1)
    }
}
