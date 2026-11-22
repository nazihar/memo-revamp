package com.memo.forum

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.FORUM_SUBMISSION
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A submission's chain (E08 design §1.1, §2.1 M3): the submissions before it along `previous_id` (a memo resubmitted to the
 * committee, or referred on from another), so a decision's time from submission counts from the first. A deferral keeps
 * its submission, so it is in the chain too. Inside a tenant transaction; no access decision is made here.
 *
 * (The design calls this `ForumSubmissions.chainStart`; the forum module's internal service already has that name.)
 */
@Component
class SubmissionChains(
    private val tenantDsl: TenantDsl,
) {
    /** [submissionId] and the submissions before it, the first first; empty when there is no such submission. */
    fun chain(submissionId: UUID): List<UUID> = steps(submissionId).map { it.first }

    /** When the first submission of [submissionId]'s chain was submitted; null when there is no such submission. */
    fun chainStart(submissionId: UUID): Instant? = steps(submissionId).firstOrNull()?.second

    /** [id] and the submissions before it with when each was submitted, the first first; at most [MAX_CHAIN] steps back. */
    private fun steps(id: UUID): List<Pair<UUID, Instant>> {
        val s = FORUM_SUBMISSION
        val chain = DSL.name("chain")
        val cId = DSL.field(DSL.name("chain", "id"), UUID::class.java)
        val cPrevious = DSL.field(DSL.name("chain", "previous_id"), UUID::class.java)
        val cAt = DSL.field(DSL.name("chain", "submitted_at"), OffsetDateTime::class.java)
        val cDepth = DSL.field(DSL.name("chain", "depth"), Int::class.java)
        val cte =
            chain
                .fields("id", "previous_id", "submitted_at", "depth")
                .`as`(
                    DSL
                        .select(s.ID, s.PREVIOUS_ID, s.SUBMITTED_AT, DSL.inline(0))
                        .from(s)
                        .where(s.ID.eq(id))
                        .unionAll(
                            DSL
                                .select(s.ID, s.PREVIOUS_ID, s.SUBMITTED_AT, cDepth.plus(1))
                                .from(s)
                                .join(chain)
                                .on(s.ID.eq(cPrevious))
                                .where(cDepth.lt(MAX_CHAIN)),
                        ),
                )
        return tenantDsl
            .current()
            .withRecursive(cte)
            .select(cId, cAt)
            .from(chain)
            .orderBy(cDepth.desc())
            .fetch { checkNotNull(it.value1()) to checkNotNull(it.value2()).toInstant() }
    }

    private companion object {
        /** How far back a chain is followed (a submission deferred, referred or resubmitted that often is not real). */
        const val MAX_CHAIN = 100
    }
}
