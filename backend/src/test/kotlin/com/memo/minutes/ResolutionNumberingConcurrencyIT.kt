package com.memo.minutes

import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.forum.ForumRole
import com.memo.forum.OutcomeKind
import com.memo.forum.internal.ForumInput
import com.memo.forum.internal.MemberInput
import com.memo.meeting.DeclarationFixture
import com.memo.meeting.MeetingKind
import com.memo.meeting.MeetingMode
import com.memo.meeting.MeetingStatus
import com.memo.meeting.internal.AgendaOrigin
import com.memo.meeting.internal.ItemInput
import com.memo.meeting.internal.ItemKind
import com.memo.meeting.internal.MeetingInput
import com.memo.meeting.internal.MeetingPlace
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.TimesInput
import com.memo.minutes.internal.DecisionReads
import com.memo.minutes.internal.DecisionRow
import com.memo.minutes.internal.DecisionStore
import com.memo.minutes.internal.EntryRow
import com.memo.minutes.internal.MinutesRelease
import com.memo.minutes.internal.MinutesReleaseScheduler
import com.memo.minutes.internal.ReleaseTrigger
import com.memo.minutes.internal.ResolutionCounters
import com.memo.minutes.internal.ResolutionNumbers
import com.memo.minutes.internal.ResolutionStore
import com.memo.minutes.internal.VoteMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The Phase-4 special test on resolution numbering (test plan E06 T4, ADR-0065, ADR-0044): twenty committees' meetings
 * ending at once (their `minutes-release` jobs) and fifty decisions numbered after the end, on ten threads. For each
 * committee and period the numbers are 1..n with no gap and no duplicate, each meeting's batch in agenda order; a numbering
 * rolled back leaves no gap; a void is recorded and its number never given again; the register check equals the counter.
 * The decisions are written through the store (as the leak suites plant them) so the test is about the counter.
 */
class ResolutionNumberingConcurrencyIT : DeclarationFixture() {
    @Autowired lateinit var decisionStore: DecisionStore

    @Autowired lateinit var decisionReads: DecisionReads

    @Autowired lateinit var resolutionStore: ResolutionStore

    @Autowired lateinit var resolutionCounters: ResolutionCounters

    @Autowired lateinit var numbers: ResolutionNumbers

    @Autowired lateinit var minutesRelease: MinutesRelease

    @Autowired lateinit var releaseScheduler: MinutesReleaseScheduler

    @Autowired lateinit var resolutions: Resolutions

    @BeforeEach
    fun releaseByHand() {
        releaseScheduler.jobs = false
    }

    @AfterEach
    fun releaseJobsBack() {
        releaseScheduler.jobs = true
    }

    /** A committee's meeting with three published items, in agenda order. */
    private data class Committee(
        val forumId: UUID,
        val meeting: MeetingRow,
        val items: List<UUID>,
    )

    @Test
    fun `parallel ends and decisions give each committee 1 to n in agenda order, a rollback no gap, a void no reuse`() {
        val type = asUser(admin) { forums.get(brc) }.typeId
        val committees =
            (1..COMMITTEES).map { n ->
                val forum = if (n == 1) brc else newCommittee(type, n)
                val meeting = meetingOf(forum)
                val items = (1..3).map { i -> addItemTo(meeting, "Item $i of committee $n") }
                publish(meeting)
                Committee(forum, meeting, items)
            }
        // Each meeting's three decisions recorded in the reverse of agenda order; fifty more, to number after the end.
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        committees.forEach { c -> c.items.reversed().forEachIndexed { i, item -> plant(c, item, now.plusSeconds(i.toLong())) } }
        val later =
            (0 until LATE).map { i ->
                committees[i % COMMITTEES].let { c -> c to plant(c, UUID.randomUUID(), now.plusSeconds(60)) }
            }

        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            val work =
                committees.map { c -> Callable { job(c.meeting.id).let { } } } +
                    later.map { (_, decision) -> Callable { afterHeld(decision) } }
            pool.invokeAll(work.shuffled()).forEach { it.get(2, TimeUnit.MINUTES) }
        } finally {
            pool.shutdown()
        }

        committees.forEach { c ->
            val period = period(c)
            val given = transactions.readInTenant(tenant.id) { resolutionCounters.sequences(c.forumId, period) }.map { it.sequence }
            val expected = 3 + later.count { it.first == c }
            assertThat(given).describedAs("committee ${c.forumId}").containsExactlyElementsOf((1L..expected).toList())
            // The meeting's batch is in agenda order, whatever came between.
            val batch = c.items.map { item -> sequenceOf(item) }
            assertThat(batch).describedAs("agenda order").isSorted()
            assertThat(check(c).complete).isTrue()
        }

        rollbackAndVoid(committees[1], now)
    }

    /** A numbering rolled back (a release failing after it, in the same transaction) gives its number back; a void is kept. */
    private fun rollbackAndVoid(
        c: Committee,
        now: Instant,
    ) {
        val counterBefore = counter(c)
        val failing = plant(c, UUID.randomUUID(), now.plusSeconds(120))
        val thrown =
            runCatching {
                TenantContext.call(tenant.id) {
                    ActorContext.asSystem("minutes-release") {
                        transactions.inTenant(tenant.id) {
                            val current = checkNotNull(decisionReads.currentById(checkNotNull(decisionStore.lockById(failing)).id))
                            checkNotNull(numbers.assign(current))
                            error("the release failed")
                        }
                    }
                }
            }.exceptionOrNull()
        assertThat(thrown).hasMessage("the release failed")
        assertThat(counter(c)).isEqualTo(counterBefore)
        afterHeld(failing)
        assertThat(counter(c)).isEqualTo(counterBefore + 1)

        // A void: recorded with its reason, the number never given again.
        val voided = c.items.first()
        correctToNoted(voided)
        val void =
            checkNotNull(decision(voided)).let { d ->
                transactions.readInTenant(tenant.id) { resolutions.ofDecision(d.decision.id) }
            }
        assertThat(void.single().state).isEqualTo(ResolutionState.VOID)
        val next = plant(c, UUID.randomUUID(), now.plusSeconds(180))
        afterHeld(next)
        val nextNumber = checkNotNull(transactions.readInTenant(tenant.id) { resolutionStore.inForce(next) })
        assertThat(nextNumber.sequence).isEqualTo(counterBefore + 2)
        assertThat(nextNumber.number).isNotEqualTo(void.single().number)
        val checked = check(c)
        assertThat(Triple(checked.void, checked.lastNumber, checked.complete)).isEqualTo(Triple(1, counterBefore + 2, true))
        assertThat(checked.inForce + checked.void).isEqualTo((checked.lastNumber - checked.firstNumber + 1).toInt())
    }

    private fun newCommittee(
        type: UUID,
        n: Int,
    ): UUID {
        val forum = asUser(admin) { forums.create(ForumInput("committee-$n", "C%02d".format(n), "Committee $n", "Committee $n", type)) }.id
        asUser(admin) {
            members.add(forum, MemberInput(userId = secretary.id, role = ForumRole.SECRETARY))
            members.add(forum, MemberInput(userId = chair.id, role = ForumRole.CHAIR))
        }
        return forum
    }

    /** A scheduled meeting of [forumId] five days ahead, its agenda started from the template. */
    private fun meetingOf(forumId: UUID): MeetingRow {
        val day = LocalDate.now().plusDays(5)
        return asUser(secretary.account) {
            val created =
                lifecycle.create(
                    forumId,
                    MeetingInput(MeetingKind.ORDINARY, MeetingPlace(null, MeetingMode.IN_PERSON, "Board room", null, null), null),
                    TimesInput(day.atTime(LocalTime.of(10, 0)), day.atTime(LocalTime.of(13, 0)), "Asia/Dhaka"),
                )
            starts.start(created.id, AgendaOrigin.TEMPLATE, null)
            lifecycle.schedule(created.id, reads.get(created.id).version)
        }
    }

    private fun addItemTo(
        meeting: MeetingRow,
        title: String,
    ): UUID = asUser(secretary.account) { items.add(meeting.id, version(meeting), ItemInput(ItemKind.ITEM, title = title, minutes = 10)) }

    /** A decision APPROVED (a resolution) on [itemId] of [c]'s meeting, taken at [at]; its id. */
    private fun plant(
        c: Committee,
        itemId: UUID,
        at: Instant,
    ): UUID {
        val id = UUID.randomUUID()
        val head = DecisionRow(id, c.meeting.id, itemId, c.forumId, null, null, 1, null, null, null, 0)
        transactions.inTenant(tenant.id) { check(decisionStore.insert(head, entry(id, 1, OutcomeKind.APPROVED, true, at, null))) }
        return id
    }

    private fun entry(
        decisionId: UUID,
        seq: Int,
        kind: OutcomeKind,
        resolution: Boolean,
        at: Instant,
        reason: String?,
    ) = EntryRow(
        decisionId = decisionId,
        seq = seq,
        outcomeKind = kind,
        outcomeLabel = kind.name.lowercase(),
        resolution = resolution,
        text = "It was resolved.",
        conditions = emptyList(),
        validUntil = null,
        deferTo = null,
        referToForumId = null,
        referReason = null,
        voteMode = VoteMode.CONSENSUS,
        votesFor = null,
        votesAgainst = null,
        votesAbstain = null,
        dissent = emptyList(),
        abstentions = emptyList(),
        tookNoPart = emptyList(),
        snapshotId = UUID.randomUUID(),
        ratificationRequired = false,
        ratifiesDecisionId = null,
        decidedAt = at,
        consentBatchId = null,
        correction = seq > 1,
        reason = reason,
        recordedBy = secretary.id,
        recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS),
    )

    /** The meeting's end as db-scheduler runs it: no transaction, the job's system actor. */
    private fun job(meetingId: UUID) =
        TenantContext.call(tenant.id) { ActorContext.asSystem("minutes-release") { minutesRelease.run(meetingId, ReleaseTrigger.HELD) } }

    /** What follows a decision recorded once the meeting is held, in its own transaction with its row locked. */
    private fun afterHeld(decisionId: UUID) =
        asUser(secretary.account) {
            val row = checkNotNull(decisionStore.lockById(decisionId))
            minutesRelease.afterChange(checkNotNull(decisionReads.currentById(row.id)), null, MeetingStatus.HELD)
        }

    /** A correction of [itemId]'s decision to NOTED (not a resolution) with a reason, and what follows it. */
    private fun correctToNoted(itemId: UUID) =
        asUser(secretary.account) {
            val row = checkNotNull(decisionStore.lock(itemId))
            check(decisionStore.append(row, row.version, entry(row.id, 2, OutcomeKind.NOTED, false, Instant.now(), "Recorded as noted")))
            minutesRelease.afterChange(checkNotNull(decisionReads.current(itemId)), null, MeetingStatus.HELD)
        }

    private fun decision(itemId: UUID) = transactions.readInTenant(tenant.id) { decisionReads.current(itemId) }

    private fun sequenceOf(itemId: UUID): Long =
        transactions.readInTenant(tenant.id) {
            checkNotNull(resolutionStore.inForce(checkNotNull(decisionReads.current(itemId)).decision.id)).sequence
        }

    private fun period(c: Committee): String =
        c.meeting.startsAt
            .atZone(c.meeting.zone)
            .year
            .toString()

    private fun counter(c: Committee): Long =
        checkNotNull(transactions.readInTenant(tenant.id) { resolutionCounters.counter(c.forumId, period(c)) }).lastNumber

    private fun check(c: Committee) = checkNotNull(transactions.readInTenant(tenant.id) { resolutions.check(c.forumId, period(c)) })

    private companion object {
        const val COMMITTEES = 20
        const val LATE = 50
        const val THREADS = 10
    }
}
