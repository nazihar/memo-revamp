package com.memo.forum

import java.util.UUID

/** Which members a quorum counts (ADR-0057); observers and standing invitees never count. */
enum class QuorumCounts { VOTING, ALL }

/** What the needed number is taken from: the counting members in office that day, or a fixed number of seats. */
enum class QuorumBasis { IN_OFFICE, SEATS }

enum class ThresholdKind { MAJORITY, FRACTION, COUNT }

/** How many of the basis must attend: more than half, a fraction rounded up, or a fixed number. */
data class QuorumThreshold(
    val kind: ThresholdKind = ThresholdKind.MAJORITY,
    val numerator: Int? = null,
    val denominator: Int? = null,
    val count: Int? = null,
)

/** Of those counted present, at least [atLeast] (or, with [majority], more than half) carry [attribute]. */
data class QuorumComposition(
    val attribute: String,
    val atLeast: Int? = null,
    val majority: Boolean = false,
)

/** A forum type's quorum rule (ADR-0057): every condition must hold. */
data class QuorumRule(
    val counts: QuorumCounts = QuorumCounts.VOTING,
    val basis: QuorumBasis = QuorumBasis.IN_OFFICE,
    val seats: Int? = null,
    val threshold: QuorumThreshold = QuorumThreshold(),
    val minimum: Int = 2,
    /** Each set needs at least one of its roles present, for example `[[CHAIR, VICE_CHAIR]]`. */
    val required: List<List<ForumRole>> = emptyList(),
    val composition: List<QuorumComposition> = emptyList(),
    val alternatesCount: Boolean = true,
    val remoteCounts: Boolean = true,
    /** For an item: whether members recused from it count towards its quorum (Exim) or not (UK model articles). */
    val recusedCount: Boolean = false,
)

/** A member in office as a quorum sees them. */
data class QuorumSeat(
    val memberId: UUID,
    val role: ForumRole,
    val voting: Boolean,
    val attributes: Set<String> = emptySet(),
)

/** How a member is at the meeting; absent members are simply left out of the attendance. */
data class QuorumPresence(
    val remote: Boolean = false,
    /** Their alternate attends in their place: for the number only, never for a role or an attribute (ADR-0064). */
    val byAlternate: Boolean = false,
    /** Recused from the item being decided (E04). */
    val recused: Boolean = false,
)

enum class UnmetKind { COUNT, ROLE, COMPOSITION }

/** A condition of the rule that does not hold, with the numbers the screens show. */
data class UnmetCondition(
    val kind: UnmetKind,
    val needed: Int,
    val actual: Int,
    /** For ROLE: the role set of which nobody is present. */
    val roles: List<ForumRole> = emptyList(),
    /** For COMPOSITION: the attribute. */
    val attribute: String? = null,
    /** For COMPOSITION: whether the condition is "a majority". */
    val majority: Boolean = false,
)

data class QuorumResult(
    /** Counting members in office, or the fixed seats. */
    val basis: Int,
    val needed: Int,
    val present: Int,
    val quorate: Boolean,
    val unmet: List<UnmetCondition>,
)

/**
 * Quorum evaluation (ADR-0057): pure and deterministic, the same for the rule preview (E01), the live view and the
 * record of each decision (E05). [roster] is the members in office that day; [attendance] says who attends and how.
 * Amended by ADR-0064: a seat present by its alternate ([QuorumPresence.byAlternate]) counts towards the number when
 * [QuorumRule.alternatesCount] is set, and in the denominator of a composition majority, but never meets a required role
 * set or a composition attribute. Callers pass at most one seat per person (the meeting module's presence builder).
 */
object Quorum {
    fun evaluate(
        rule: QuorumRule,
        roster: List<QuorumSeat>,
        attendance: Map<UUID, QuorumPresence>,
    ): QuorumResult {
        val counting = roster.filter { counts(rule, it) }
        val basis = if (rule.basis == QuorumBasis.SEATS) rule.seats ?: 0 else counting.size
        val needed = maxOf(rule.minimum, threshold(rule.threshold, basis))
        val attending = roster.filter { seat -> attendance[seat.memberId]?.let { eligible(rule, it) } == true }
        val present = attending.filter { counts(rule, it) }

        // ADR-0064: a seat filled by its alternate counts for the number only; the roles and attributes are the holder's.
        fun byHolder(seat: QuorumSeat) = attendance[seat.memberId]?.byAlternate != true
        val unmet = mutableListOf<UnmetCondition>()
        if (present.size < needed) unmet += UnmetCondition(UnmetKind.COUNT, needed, present.size)
        rule.required.forEach { roles ->
            val here = attending.count { it.role in roles && byHolder(it) }
            if (here == 0) unmet += UnmetCondition(UnmetKind.ROLE, 1, 0, roles = roles)
        }
        rule.composition.forEach { condition ->
            // Those counted present, alternates included, are a majority's denominator; only holders carry the attribute.
            val with = present.count { condition.attribute in it.attributes && byHolder(it) }
            val wanted = if (condition.majority) present.size / 2 + 1 else condition.atLeast ?: 0
            if (with < wanted) {
                unmet += UnmetCondition(UnmetKind.COMPOSITION, wanted, with, attribute = condition.attribute, majority = condition.majority)
            }
        }
        return QuorumResult(basis, needed, present.size, unmet.isEmpty(), unmet)
    }

    /** The number a threshold asks of [basis]: MAJORITY `floor(n/2)+1`, FRACTION rounded up, COUNT as given. */
    fun threshold(
        threshold: QuorumThreshold,
        basis: Int,
    ): Int =
        when (threshold.kind) {
            ThresholdKind.MAJORITY -> {
                basis / 2 + 1
            }

            ThresholdKind.FRACTION -> {
                val numerator = threshold.numerator ?: 1
                val denominator = maxOf(1, threshold.denominator ?: 1)
                ((basis.toLong() * numerator + denominator - 1) / denominator).toInt()
            }

            ThresholdKind.COUNT -> {
                threshold.count ?: 0
            }
        }

    private fun counts(
        rule: QuorumRule,
        seat: QuorumSeat,
    ): Boolean = seat.role.countable && (rule.counts == QuorumCounts.ALL || seat.voting)

    private fun eligible(
        rule: QuorumRule,
        presence: QuorumPresence,
    ): Boolean =
        (rule.remoteCounts || !presence.remote) &&
            (rule.alternatesCount || !presence.byAlternate) &&
            (rule.recusedCount || !presence.recused)
}
