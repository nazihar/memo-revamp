package com.memo.search

import com.memo.search.internal.decision.DecisionRegisterAccess
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * One decision on a SUBMISSION item that the viewer sees in FULL in the register (ADR-0069 §1.3), with what analytics'
 * time to decision and items deferred need (ADR-0070, design E08 §2.1 M3, M4) and nothing else: no title, text or
 * names. A restricted line's decision is never one of these, so no measure counts it.
 */
data class DecisionFact(
    val forumId: UUID,
    val memoTypeId: UUID?,
    val memoId: UUID?,
    val submissionId: UUID,
    /** The meeting's local date (in its zone). */
    val meetingDate: LocalDate,
    val decidedAt: Instant,
    /** The first submission of the item's chain (a deferral re-queued or a referral keeps counting from it). */
    val chainSubmittedAt: Instant?,
    /** APPROVED, APPROVED_WITH_CONDITIONS, REJECTED, DEFERRED, RETURNED, REFERRED, NOTED or WITHDRAWN. */
    val outcomeKind: String,
    /** DEFERRED decisions earlier in the same chain. */
    val priorDeferrals: Int,
)

/**
 * The register's decisions for analytics (ADR-0070, design E08 §2.2): `search_decision_stats` under the one rule with the
 * signed-in actor's parameters computed from live state (DecisionRegisterAccess: committees, clearance, `forum.read`,
 * `forums.manage`, and the items they are recused from right now), FULL rows only. Inside a tenant transaction.
 */
@Component
class DecisionStats(
    private val access: DecisionRegisterAccess,
) {
    /**
     * The decisions on SUBMISSION items of [forumIds] (empty: every committee in the actor's register) whose meeting date is
     * from [from] to [to] (at most ten years), that the actor sees in full; empty when the register is not theirs.
     */
    fun of(
        forumIds: Set<UUID>,
        from: LocalDate,
        to: LocalDate,
    ): List<DecisionFact> = access.stats(forumIds, from, to)
}
