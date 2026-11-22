package com.memo.minutes

import com.memo.common.events.TenantScopedEvent
import com.memo.minutes.internal.ResolutionChecks
import com.memo.minutes.internal.ResolutionCounters
import com.memo.minutes.internal.ResolutionRow
import com.memo.minutes.internal.ResolutionStore
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** Whether a resolution number stands (ADR-0065): a void number stays in the register and is never given again. */
enum class ResolutionState { IN_FORCE, VOID }

/**
 * A resolution number as other modules read it (design E06 §2; E08 indexes it): the decision it belongs to, its committee,
 * meeting, period and sequence. [voidReason] is business content (the correction's reason): the caller decides who reads it.
 */
data class ResolutionRef(
    val id: UUID,
    val decisionId: UUID,
    val forumId: UUID,
    val meetingId: UUID,
    val period: String,
    val sequence: Long,
    val number: String,
    val numberedAt: Instant,
    val state: ResolutionState,
    val voidReason: String?,
    val voidedAt: Instant?,
)

/**
 * The register check of one committee and period (design E06 §1.3): every number from the counter's first to its last is
 * given exactly once, in force or void. [missing] lists the first numbers no resolution has (none expected).
 */
data class ResolutionCheck(
    val forumId: UUID,
    val period: String,
    /** The first number of the period: 1, or the starting number an administrator set. */
    val firstNumber: Long,
    /** The counter's last number given; [firstNumber] − 1 when none was. */
    val lastNumber: Long,
    val inForce: Int,
    val void: Int,
    /** Numbers between the first and the last that no resolution has, at most [ResolutionChecks.MAX_LISTED]. */
    val missing: List<Long>,
    val missingCount: Long,
    /** Numbers given twice (impossible behind the unique key; listed so a check can never pass over one). */
    val duplicates: List<Long>,
    /** Numbers outside the counter's range. */
    val outOfRange: List<Long>,
) {
    /** Every number accounted for: the count equals the counter, with no hole, duplicate or stray. */
    val complete: Boolean
        get() =
            missingCount == 0L && duplicates.isEmpty() && outOfRange.isEmpty() &&
                (inForce + void).toLong() == lastNumber - firstNumber + 1
}

/**
 * Resolution numbers for other modules (design E06 §2; E08 design §11): one by id, a decision's (in force, and all), a
 * memo's, and the register check of a committee's period. Inside a tenant transaction; no access decision is made here.
 * Every number given or voided is told by [ResolutionChanged].
 */
@Component
class Resolutions(
    private val store: ResolutionStore,
    private val counters: ResolutionCounters,
) {
    fun find(resolutionId: UUID): ResolutionRef? = store.find(resolutionId)?.ref()

    /** [decisionId]'s number in force; null when it has none. */
    fun inForce(decisionId: UUID): ResolutionRef? = store.inForce(decisionId)?.ref()

    /** Every number [decisionId] was given, the first first (a void one, then a new one after it became a resolution again). */
    fun ofDecision(decisionId: UUID): List<ResolutionRef> = store.ofDecision(decisionId).map { it.ref() }

    /** The numbers of the decisions on [memoId]'s submissions, the first first. */
    fun ofMemo(memoId: UUID): List<ResolutionRef> = store.ofMemo(memoId).map { it.ref() }

    /** The register check of [forumId]'s [period]; null when the committee has no counter for it. */
    fun check(
        forumId: UUID,
        period: String,
    ): ResolutionCheck? {
        val counter = counters.counter(forumId, period) ?: return null
        return ResolutionChecks.of(forumId, counter, counters.sequences(forumId, period))
    }

    /**
     * The void numbers of [forumId]'s [period] in number order, at most [MAX_VOIDS] (the register's number check, E08 T3).
     * Each carries its reason: the caller shows it only to those who see the decision in full.
     */
    fun voids(
        forumId: UUID,
        period: String,
    ): List<ResolutionRef> = store.page(forumId, period, ResolutionState.VOID, null, MAX_VOIDS).map { it.ref() }.sortedBy { it.sequence }

    private fun ResolutionRow.ref() =
        ResolutionRef(id, decisionId, forumId, meetingId, period, sequence, number, numberedAt, state, voidReason, voidedAt)

    companion object {
        /** How many void numbers of a period [voids] lists (a period has a handful). */
        const val MAX_VOIDS = 500
    }
}

/** What happened to a resolution number. */
enum class ResolutionChange {
    /** A number was given (at the meeting's end, or to a decision recorded or corrected into a resolution later). */
    NUMBERED,

    /** A correction made the decision something other than a resolution: its number is void, never given again. */
    VOIDED,
}

/**
 * A resolution number was given or voided (design E06 §2; asked by E08 for its register's number columns), published in the
 * changing transaction, one event per write. A decision that becomes a resolution again after a void gets a second
 * NUMBERED event with its new number. Ids only (ADR-0017).
 */
data class ResolutionChanged(
    override val tenantId: UUID,
    val decisionId: UUID,
    val resolutionId: UUID,
    val change: ResolutionChange,
) : TenantScopedEvent

/**
 * A decision was sent to its memo (ADR-0065 "Back to the memo"): its submission decided, deferred or referred, in the
 * releasing transaction. Ids only (ADR-0017).
 */
data class DecisionReleased(
    override val tenantId: UUID,
    val meetingId: UUID,
    val decisionId: UUID,
) : TenantScopedEvent
