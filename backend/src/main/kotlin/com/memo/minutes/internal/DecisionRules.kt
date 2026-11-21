package com.memo.minutes.internal

import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.forum.Outcome
import com.memo.forum.OutcomeKind
import com.memo.meeting.MeetingStatus
import com.memo.meeting.QuorumSnapshotInputs
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A decision as the secretary records or corrects it (design E06 §1.1, §3). */
data class DecisionInput(
    val outcome: OutcomeKind?,
    val text: String? = null,
    val conditions: List<String>? = null,
    val validUntil: LocalDate? = null,
    val deferTo: DeferTarget? = null,
    val referToForumId: UUID? = null,
    val referReason: String? = null,
    val voteMode: VoteMode = VoteMode.CONSENSUS,
    val votesFor: Int? = null,
    val votesAgainst: Int? = null,
    val votesAbstain: Int? = null,
    val dissent: List<Dissent>? = null,
    val abstentions: List<UUID>? = null,
    /** When the item was taken; default the item's last start, else now while the meeting is in progress. */
    val decidedAt: Instant? = null,
    val ratifiesDecisionId: UUID? = null,
    /** E05's override of a missing quorum, when the committee type allows it. */
    val overrideReason: String? = null,
    /** Required for a correction, 1 to 500 characters. */
    val reason: String? = null,
    /** A correction takes a new quorum snapshot (else it keeps the earlier one unless the time changes). */
    val recheckQuorum: Boolean = false,
    /** The secretary confirms that the change sends minutes out of the draft back to it. */
    val returnMinutesToDraft: Boolean = false,
)

/** The content of a decision as checked, before the quorum snapshot: what its entry carries. */
data class DecisionContent(
    val outcome: Outcome,
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
)

/**
 * The pure rules of a decision (ADR-0065, design E06 §1.1): the content's checks with their problem codes, the people
 * named against the quorum snapshot (present, voting, not recused; the counts add up), "took no part" derived from the
 * snapshot, and the windows by the meeting's status and the minutes' stage.
 */
object DecisionRules {
    const val MAX_TEXT = 4000
    const val MAX_CONDITIONS = 20
    const val MAX_CONDITION = 1000
    const val MAX_DISSENT_REASON = 1000
    const val MAX_REASON = 500
    const val MAX_NAMED = 100

    /** A decision is taken from an hour before the meeting's start to six hours after its end. */
    val EARLY: Duration = Duration.ofHours(1)
    val LATE: Duration = Duration.ofHours(6)

    /** As attendance (design E05 §1.6): a time a minute ahead of the server's clock is still now. */
    val CLOCK_SKEW: Duration = Duration.ofMinutes(1)

    /**
     * The people named in [content] against the snapshot's [inputs] (design E06 §1.1 `PEOPLE_PRESENT`, `COUNTS`): each one
     * present for the item in a voting seat (or standing in for one), not recused and not stepped out; the counts not more
     * than the voting seats filled by people not recused, and the named not more than their count.
     */
    fun people(
        content: DecisionContent,
        inputs: QuorumSnapshotInputs,
    ): List<FieldProblem> {
        val named =
            content.dissent.mapIndexedNotNull {
                i,
                d,
                ->
                person(d.userId, inputs)?.let { FieldProblem("dissent/$i/userId", it, it.words()) }
            } +
                content.abstentions.mapIndexedNotNull { i, u -> person(u, inputs)?.let { FieldProblem("abstentions/$i", it, it.words()) } }
        val seats = voters(inputs).size
        val counted = listOfNotNull(content.votesFor, content.votesAgainst, content.votesAbstain).sum()
        return named +
            listOfNotNull(
                FieldProblem("votes", "TOO_MANY_VOTES", "There are only $seats voting members present who may vote on this item.")
                    .takeIf { counted > seats },
                FieldProblem("dissent", "TOO_MANY_NAMED", "More people dissent than voted against.")
                    .takeIf { content.dissent.size > (content.votesAgainst ?: Int.MAX_VALUE) },
                FieldProblem("abstentions", "TOO_MANY_NAMED", "More people abstain than the count of abstentions.")
                    .takeIf { content.abstentions.size > (content.votesAbstain ?: Int.MAX_VALUE) },
            )
    }

    /** The people present for the item in a voting seat (or standing in for one) and not recused: who may vote. */
    fun voters(inputs: QuorumSnapshotInputs): Set<UUID> {
        val voting =
            inputs.roster
                .filter { it.voting }
                .map { it.memberId }
                .toSet()
        return inputs.presence
            .filter { it.memberId in voting && !it.recused }
            .map { it.userId }
            .toSet()
    }

    /**
     * "Took no part" (design E06 §1.1), from the snapshot only: the members in the room recused from the item, and the
     * members who stepped out of it. Sorted, so equal snapshots give equal lists.
     */
    fun tookNoPart(inputs: QuorumSnapshotInputs): List<UUID> {
        val members = inputs.roster.map { it.userId }.toSet()
        return (inputs.presence.filter { it.recused }.map { it.userId } + inputs.steppedOut.filter { it in members })
            .distinct()
            .sortedBy { it.toString() }
    }

    /**
     * The windows (design E06 §1.1): decisions change while the meeting is in progress or held (409 `WRONG_STATE` before it
     * starts and for a cancelled one), never once the minutes are approved (409 `FROZEN`); a change once the minutes are out
     * of the draft needs [returnToDraft] (409 `MINUTES_IN_REVIEW`). True when the change sends the minutes back to the draft.
     */
    fun window(
        status: MeetingStatus,
        stage: MinutesStage?,
        decision: DecisionRow?,
        returnToDraft: Boolean,
    ): Boolean {
        refuseIf(status in FINAL || stage == MinutesStage.APPROVED || decision?.finalAt != null, "FROZEN") {
            "The minutes have been approved: the decisions are final."
        }
        refuseIf(status !in DECIDING, "WRONG_STATE") {
            "Decisions are recorded while the meeting is in progress, and corrected once it is held."
        }
        val inReview = stage?.inReview == true
        refuseIf(inReview && !returnToDraft, "MINUTES_IN_REVIEW") {
            "The minutes are with the chair or circulated: changing a decision sends them back to the draft. Confirm to go on."
        }
        return inReview
    }

    /** Once the memo has moved on, the outcome is fixed (409 `RELEASED`); the wording, conditions, vote and names are not. */
    fun released(
        decision: DecisionRow?,
        current: EntryRow?,
        kind: OutcomeKind,
    ) {
        refuseIf(decision?.releasedAt != null && current != null && current.outcomeKind != kind, "RELEASED") {
            "The memo has already moved on. Record the correction in the minutes; the author can resubmit the memo."
        }
    }

    /**
     * When the item was taken: as given, else as the decision had it, else when the item was last started, else now while
     * the meeting is in progress (422 `TIME_REQUIRED` once it is held); between an hour before the meeting's start and six
     * hours after its end, and not in the future (422 `TIME_OUT_OF_RANGE`).
     */
    fun decidedAt(
        given: Instant?,
        fallback: Instant?,
        status: MeetingStatus,
        window: Pair<Instant, Instant>,
        now: Instant,
    ): Pair<Instant?, FieldProblem?> {
        val at = given ?: fallback ?: now.takeIf { status == MeetingStatus.IN_PROGRESS }
        val problem =
            when {
                at == null -> {
                    FieldProblem("decidedAt", "TIME_REQUIRED", "When was this item taken?")
                }

                at.isAfter(now.plus(CLOCK_SKEW)) || at.isBefore(window.first.minus(EARLY)) || at.isAfter(window.second.plus(LATE)) -> {
                    FieldProblem("decidedAt", "TIME_OUT_OF_RANGE", "Give a time during the meeting, not in the future.")
                }

                else -> {
                    null
                }
            }
        return at to problem
    }

    /** A correction's reason: required, 1 to [MAX_REASON] characters (422 `REASON_REQUIRED`, `LENGTH`). */
    fun reason(text: String?): Pair<String?, FieldProblem?> {
        val clean = text?.trim()?.ifEmpty { null }
        val problem =
            when {
                clean == null -> FieldProblem("reason", "REASON_REQUIRED", "Say why the decision is corrected.")
                clean.length > MAX_REASON -> FieldProblem("reason", "LENGTH", "At most $MAX_REASON characters.")
                else -> null
            }
        return clean to problem
    }

    /** The SHA-256 of business text, hex: the audit carries it instead of the text (design E06 §5). */
    fun sha256(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Why [userId] may not be named as voting on the item, or null when they may. */
    private fun person(
        userId: UUID,
        inputs: QuorumSnapshotInputs,
    ): String? {
        val seat = inputs.presence.firstOrNull { it.userId == userId }
        val voting =
            inputs.roster
                .filter { it.voting }
                .map { it.memberId }
                .toSet()
        return when {
            userId in inputs.recused || seat?.recused == true -> "RECUSED_NAMED"
            seat != null -> "NOT_VOTING".takeIf { seat.memberId !in voting }
            userId in inputs.steppedOut || inputs.roster.any { it.userId == userId } -> "NOT_PRESENT"
            else -> "NOT_VOTING"
        }
    }

    private fun String.words(): String =
        when (this) {
            "RECUSED_NAMED" -> "They declared an interest in this item and took no part."
            "NOT_PRESENT" -> "They were not present for this item."
            else -> "They do not hold a voting seat on this committee."
        }

    /** Decisions are recorded while the meeting runs and corrected once it is held. */
    private val DECIDING = setOf(MeetingStatus.IN_PROGRESS, MeetingStatus.HELD)

    /** Once the minutes are approved (or the meeting closed), nothing changes. */
    private val FINAL = setOf(MeetingStatus.MINUTES_APPROVED, MeetingStatus.CLOSED)
}

/** The checks of a decision's content as given (design E06 §1.1 `OUTCOME_OF_TYPE`, `TEXT_REQUIRED`, the vote and the names). */
object DecisionChecks {
    /** The outcome, its text, conditions, validity, target and vote as given, checked against the type's [outcomes]. */
    fun content(
        input: DecisionInput,
        outcomes: List<Outcome>,
        meetingDate: LocalDate,
    ): Pair<DecisionContent?, List<FieldProblem>> {
        val outcome = input.outcome?.let { kind -> outcomes.firstOrNull { it.kind == kind } }
        val text = input.text?.trim()?.ifEmpty { null }
        val conditions = input.conditions.orEmpty().map { it.trim() }
        val dissent = input.dissent.orEmpty().map { Dissent(it.userId, it.reason?.trim()?.ifEmpty { null }) }
        val abstentions = input.abstentions.orEmpty()
        val problems =
            listOfNotNull(
                FieldProblem("outcome", "OUTCOME_NOT_OFFERED", "Choose one of the committee's outcomes.").takeIf { outcome == null },
                textProblem(outcome, text),
                FieldProblem("validUntil", "RANGE", "Not before the meeting's date.")
                    .takeIf { input.validUntil?.isBefore(meetingDate) == true },
            ) + conditionProblems(outcome, conditions) + targetProblems(input, meetingDate) + voteProblems(input) +
                namedProblems(dissent, abstentions)
        if (outcome == null || problems.isNotEmpty()) return null to problems
        val kind = outcome.kind
        val vote = input.voteMode == VoteMode.VOTE
        return DecisionContent(
            outcome = outcome,
            text = text,
            conditions = conditions,
            validUntil = input.validUntil,
            deferTo = input.deferTo.takeIf { kind == OutcomeKind.DEFERRED },
            referToForumId = input.referToForumId.takeIf { kind == OutcomeKind.REFERRED },
            referReason = input.referReason?.trim()?.takeIf { kind == OutcomeKind.REFERRED },
            voteMode = input.voteMode,
            votesFor = input.votesFor.takeIf { vote },
            votesAgainst = input.votesAgainst.takeIf { vote },
            votesAbstain = input.votesAbstain.takeIf { vote },
            dissent = dissent,
            abstentions = abstentions,
        ) to emptyList()
    }

    private fun textProblem(
        outcome: Outcome?,
        text: String?,
    ): FieldProblem? =
        when {
            text != null && text.length > DecisionRules.MAX_TEXT -> {
                FieldProblem(
                    "text",
                    "LENGTH",
                    "At most $DecisionRules.MAX_TEXT characters.",
                )
            }

            outcome?.resolution == true && text == null -> {
                FieldProblem(
                    "text",
                    "TEXT_REQUIRED",
                    "Write the resolution as it will be minuted.",
                )
            }

            else -> {
                null
            }
        }

    private fun conditionProblems(
        outcome: Outcome?,
        conditions: List<String>,
    ): List<FieldProblem> =
        listOfNotNull(
            FieldProblem("conditions", "CONDITIONS_REQUIRED", "Add at least one condition.")
                .takeIf { outcome?.kind == OutcomeKind.APPROVED_WITH_CONDITIONS && conditions.isEmpty() },
            FieldProblem("conditions", "RANGE", "At most $DecisionRules.MAX_CONDITIONS conditions.").takeIf {
                conditions.size >
                    DecisionRules.MAX_CONDITIONS
            },
        ) +
            conditions.mapIndexedNotNull { i, line ->
                FieldProblem("conditions/$i", "LENGTH", "1 to $DecisionRules.MAX_CONDITION characters.")
                    .takeIf { line.isEmpty() || line.length > DecisionRules.MAX_CONDITION }
            }

    /** DEFERRED and REFERRED carry their target: its shape here; the meeting and the committee are the service's. */
    private fun targetProblems(
        input: DecisionInput,
        meetingDate: LocalDate,
    ): List<FieldProblem> {
        val defer = input.deferTo
        val referReason = input.referReason?.trim()?.ifEmpty { null }
        return when (input.outcome) {
            OutcomeKind.DEFERRED -> {
                val given = listOfNotNull(defer?.meetingId, defer?.date, defer?.next?.takeIf { it }).size
                listOfNotNull(
                    FieldProblem("deferTo", "TARGET_REQUIRED", "Say to which meeting or date the item is deferred.").takeIf { given != 1 },
                    FieldProblem(
                        "deferTo/date",
                        "RANGE",
                        "A date after the meeting.",
                    ).takeIf { defer?.date?.isAfter(meetingDate) == false },
                )
            }

            OutcomeKind.REFERRED -> {
                listOfNotNull(
                    FieldProblem("referToForumId", "TARGET_REQUIRED", "Choose the committee it is referred to.")
                        .takeIf { input.referToForumId == null },
                    FieldProblem("referReason", "REASON_REQUIRED", "Say why it is referred.").takeIf { referReason == null },
                    FieldProblem("referReason", "LENGTH", "At most $DecisionRules.MAX_REASON characters.")
                        .takeIf { (referReason?.length ?: 0) > DecisionRules.MAX_REASON },
                )
            }

            else -> {
                emptyList()
            }
        }
    }

    private fun voteProblems(input: DecisionInput): List<FieldProblem> {
        val counts = listOf("votesFor" to input.votesFor, "votesAgainst" to input.votesAgainst, "votesAbstain" to input.votesAbstain)
        return counts.mapNotNull { (field, n) -> FieldProblem(field, "RANGE", "Not below zero.").takeIf { n != null && n < 0 } } +
            listOfNotNull(
                FieldProblem("voteMode", "VOTE_MODE", "Counts are given with a vote, not with a consensus.")
                    .takeIf { input.voteMode == VoteMode.CONSENSUS && counts.any { it.second != null } },
            )
    }

    private fun namedProblems(
        dissent: List<Dissent>,
        abstentions: List<UUID>,
    ): List<FieldProblem> {
        val dissenters = dissent.map { it.userId }
        return listOfNotNull(
            FieldProblem("dissent", "RANGE", "At most $DecisionRules.MAX_NAMED people.").takeIf { dissent.size > DecisionRules.MAX_NAMED },
            FieldProblem("abstentions", "RANGE", "At most $DecisionRules.MAX_NAMED people.").takeIf {
                abstentions.size >
                    DecisionRules.MAX_NAMED
            },
            FieldProblem("dissent", "DUPLICATE", "Each person once.").takeIf { dissenters.toSet().size != dissenters.size },
            FieldProblem("abstentions", "DUPLICATE", "Each person once.").takeIf { abstentions.toSet().size != abstentions.size },
            FieldProblem("abstentions", "BOTH", "Nobody both dissents and abstains.").takeIf { dissenters.any { it in abstentions } },
        ) +
            dissent.mapIndexedNotNull { i, d ->
                FieldProblem("dissent/$i/reason", "LENGTH", "At most $DecisionRules.MAX_DISSENT_REASON characters.")
                    .takeIf { (d.reason?.length ?: 0) > DecisionRules.MAX_DISSENT_REASON }
            }
    }
}
