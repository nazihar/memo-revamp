package com.memo.actions

import com.memo.actions.internal.ActionSource
import com.memo.actions.internal.ActionStatus
import com.memo.actions.internal.ActionTotals
import com.memo.actions.internal.ActionTracker
import com.memo.actions.internal.ForumActionsResponse
import com.memo.actions.internal.RagColour
import com.memo.actions.internal.RagWord
import com.memo.actions.internal.TrackerCursor
import com.memo.actions.internal.TrackerFilter
import com.memo.actions.internal.TrackerKey
import com.memo.actions.internal.TrackerSort
import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.identity.UserAccount
import com.memo.minutes.DecisionFixture
import com.memo.org.OrgDirectory
import com.memo.testsupport.SqlRecorder
import com.memo.testsupport.TestDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.sql.DriverManager
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * The committee's action tracker (test plan E07 T8 `ActionTrackerIT`; design E07 §1.5, §1.14, §3, §8; flow AN) on the
 * running BRC meeting, `director` recused from the credit item:
 *  - the acceptance criterion: filtered "Overdue", the 3 overdue actions with the totals "Red 3, Amber 2, Green 7, To
 *    verify 2", each colour's rows agreeing with its total, and the recused director counting none of the credit item's;
 *  - keyset pages in every sort, and a cursor that is not one (422 `cursor INVALID`);
 *  - every filter (status, colour, overdue, owner, due range, meeting, item, source, text) and the checks on them;
 *  - who has a tracker and what they see: direct drafts for the secretaries, an owner outside the committee their own
 *    rows, `forum.read` the unrestricted rows, a stranger 404; who may raise and export;
 *  - E08's `ActionStats` from the same rule;
 *  - the plan: the tracker's query reads the committee's rows through an index, with and without text.
 */
class ActionTrackerIT : ActionFixture() {
    override fun label() = "acttrack"

    @Autowired lateinit var tracker: ActionTracker

    @Autowired lateinit var orgDirectory: OrgDirectory

    @Autowired lateinit var stats: ActionStats

    @Autowired lateinit var sqlRecorder: SqlRecorder

    private val tenantToday: LocalDate get() = asUser(admin) { orgDirectory.today() }

    /**
     * 14 issued actions of the running meeting: 3 overdue, 2 amber (one due soon, one at risk), 7 green and 2 done, to be
     * verified. The first overdue and the first green are on the credit item (the director is recused from it); the others
     * on "Risk appetite". Odd ones are owned by Head of Treasury (a position), even ones by director2.
     */
    private inner class Board(
        val d: DecisionFixture.Deciding,
    ) {
        val today = tenantToday
        val ids: List<UUID> =
            (0 until TOTAL).map { i ->
                val item = if (i == 0 || i == FIRST_GREEN) d.credit else d.appetite
                val owner = if (i % 2 == 0) person(director2) else position()
                issue(raise(d.meeting.id, item, fields(title = "Action ${i + 1}", owner = owner))).id
            }
        val overdue = ids.subList(0, 3)
        val amber = ids.subList(3, FIRST_GREEN)
        val green = ids.subList(FIRST_GREEN, FIRST_DONE)
        val toVerify = ids.subList(FIRST_DONE, TOTAL)
        val credit = listOf(ids[0], ids[FIRST_GREEN])

        init {
            overdue.forEachIndexed { i, id -> alter(id, "due_date = ?", today.minusDays(LATE[i])) }
            alter(amber[0], "amber_from = ?", today)
            alter(amber[1], "confidence = 'AT_RISK'")
            toVerify.forEach { alter(it, "status = 'DONE_PENDING', done_at = now()") }
        }
    }

    private fun board() = Board(deciding { m, items -> declareOn(m, items.getValue(DecisionFixture.CREDIT), director) })

    private fun track(
        by: UserAccount,
        filter: TrackerFilter = TrackerFilter(),
        cursor: String? = null,
        limit: Int = 50,
    ): ForumActionsResponse = asUser(by) { tracker.page(brc, filter, cursor, limit) }

    private fun ids(
        by: UserAccount,
        filter: TrackerFilter = TrackerFilter(),
    ) = track(by, filter).items.map { it.id }

    /** Every page of [filter] for [by], [limit] at a time, in order. */
    private fun pages(
        by: UserAccount,
        filter: TrackerFilter,
        limit: Int,
    ): List<UUID> {
        val found = mutableListOf<UUID>()
        var cursor: String? = null
        do {
            val page = track(by, filter, cursor, limit)
            assertThat(page.items.size).isLessThanOrEqualTo(limit)
            found += page.items.map { it.id }
            cursor = page.nextCursor
        } while (cursor != null)
        return found
    }

    @Test
    fun `filtered Overdue, the tracker lists the 3 overdue actions with the totals Red 3, Amber 2, Green 7, To verify 2`() {
        val b = board()

        val red = track(secretary, TrackerFilter(rag = RagColour.RED))
        assertThat(red.items.map { it.id }).containsExactlyInAnyOrderElementsOf(b.overdue)
        assertThat(red.items.map { it.rag.word }).containsOnly(RagWord.OVERDUE)
        assertThat(red.totals).isEqualTo(ActionTotals(red = 3, amber = 2, green = 7, toVerify = 2, closed = 0, drafts = 0, all = 14))
        assertThat(red.matching).isEqualTo(3)
        assertThat(ids(secretary, TrackerFilter(overdue = true))).containsExactlyInAnyOrderElementsOf(b.overdue)
        assertThat(ids(secretary, TrackerFilter(rag = RagColour.AMBER))).containsExactlyInAnyOrderElementsOf(b.amber)
        assertThat(ids(secretary, TrackerFilter(rag = RagColour.GREEN))).containsExactlyInAnyOrderElementsOf(b.green)
        assertThat(ids(secretary, TrackerFilter(rag = RagColour.BLUE))).containsExactlyInAnyOrderElementsOf(b.toVerify)
        // The totals do not move with the colour filter, and each colour's rows are its total (one predicate).
        val totals = track(secretary).totals
        assertThat(totals).isEqualTo(red.totals)
        mapOf(
            RagColour.RED to totals.red,
            RagColour.AMBER to totals.amber,
            RagColour.GREEN to totals.green,
            RagColour.BLUE to totals.toVerify,
        ).forEach { (colour, total) -> assertThat(track(secretary, TrackerFilter(rag = colour)).matching).isEqualTo(total) }

        // An override is the rule's: overridden amber, the action is no longer red, but it is still overdue (design §1.4).
        alter(
            b.overdue[2],
            "rag_override = 'AMBER', rag_override_reason = 'Agreed at the meeting', rag_override_by = ?",
            secretary.id.value,
        )
        assertThat(ids(secretary, TrackerFilter(rag = RagColour.RED))).containsExactlyInAnyOrderElementsOf(b.overdue.take(2))
        assertThat(ids(secretary, TrackerFilter(overdue = true))).containsExactlyInAnyOrderElementsOf(b.overdue)
        assertThat(track(secretary).totals.let { it.red to it.amber }).isEqualTo(2 to 3)

        // The director, recused from the credit item: neither its rows nor a count of them.
        val theirs = track(director)
        assertThat(theirs.items.map { it.id }).doesNotContainAnyElementsOf(b.credit).hasSize(12)
        assertThat(theirs.totals).isEqualTo(ActionTotals(red = 1, amber = 3, green = 6, toVerify = 2, closed = 0, drafts = 0, all = 12))
        assertThat(track(director, TrackerFilter(itemId = b.d.credit)).let { it.items.size to it.totals.all }).isEqualTo(0 to 0)
    }

    @Test
    fun `pages follow the keyset in every sort, and a cursor that is not one is refused`() {
        val b = board()
        // The newest update first; one a day before every action was raised sorts last.
        alter(
            b.green[3],
            "last_update_at = ?::timestamptz",
            java.time.OffsetDateTime
                .now()
                .plusMinutes(10)
                .toString(),
        )
        alter(
            b.amber[0],
            "last_update_at = ?::timestamptz",
            java.time.OffsetDateTime
                .now()
                .minusDays(1)
                .toString(),
        )
        for (sort in TrackerSort.entries) {
            val all = ids(secretary, TrackerFilter(sort = sort))
            assertThat(all).describedAs("$sort").hasSize(TOTAL).doesNotHaveDuplicates()
            assertThat(pages(secretary, TrackerFilter(sort = sort), limit = 4)).describedAs("$sort pages").isEqualTo(all)
        }
        val byDue = track(secretary).items
        assertThat(byDue.map { checkNotNull(it.dueDate) }).isSorted()
        assertThat(byDue.take(3).map { it.id }).containsExactly(b.overdue[2], b.overdue[1], b.overdue[0])
        val byReference = track(secretary, TrackerFilter(sort = TrackerSort.REFERENCE)).items.map { checkNotNull(it.reference) }
        assertThat(byReference.map { it.substringAfter(" A").substringBefore('/').toInt() }).containsExactlyElementsOf(1..TOTAL)
        val byUpdate = ids(secretary, TrackerFilter(sort = TrackerSort.UPDATED))
        assertThat(byUpdate.first() to byUpdate.last()).isEqualTo(b.green[3] to b.amber[0])

        // Pages of a colour keep the colour; a cursor of another sort, or none at all, is 422 `cursor INVALID`.
        assertThat(pages(secretary, TrackerFilter(rag = RagColour.GREEN), limit = 2)).containsExactlyInAnyOrderElementsOf(b.green)
        val due = TrackerCursor.encode(TrackerKey.Due(tenantToday, UUID.randomUUID()))
        assertThat(rejectedCodes { tracker.page(brc, TrackerFilter(sort = TrackerSort.REFERENCE), due, 50) }).containsExactly("INVALID")
        assertThat(rejectedCodes { tracker.page(brc, TrackerFilter(), "not-a-cursor", 50) }).containsExactly("INVALID")
        assertThat(rejectedCodes { tracker.page(brc, TrackerFilter(), "", 50) }).containsExactly("INVALID")
    }

    @Test
    fun `every filter narrows the rows and the totals alike, and the text matches words, stems and the reference`() {
        val b = board()
        val today = b.today
        val direct =
            asUser(secretary) {
                raising.raiseDirect(
                    brc,
                    fields(title = "Circulate the revised policy", dueDate = null).copy(sourceNote = "Chair's instruction"),
                )
            }
        val meetingDraft = raise(b.d.meeting.id, b.d.appetite, fields(title = "Not yet sent"))
        alter(b.green[0], "title = 'Revise the liquidity contingency plan'")
        alter(b.green[1], "description = 'Valuations of the collateral by an independent valuer'")
        val third = row(b.ids[2]).reference.orEmpty()

        val positionOwned = b.ids.filterIndexed { i, _ -> i % 2 == 1 }
        assertThat(
            ids(secretary, TrackerFilter(statuses = setOf(ActionStatus.DONE_PENDING))),
        ).containsExactlyInAnyOrderElementsOf(b.toVerify)
        assertThat(ids(secretary, TrackerFilter(owner = headOfTreasury))).containsExactlyInAnyOrderElementsOf(positionOwned + direct)
        // The position's holder is whoever holds the owner's task: the treasurer.
        assertThat(ids(secretary, TrackerFilter(owner = treasurer.id.value))).containsExactlyInAnyOrderElementsOf(positionOwned)
        assertThat(ids(secretary, TrackerFilter(owner = director2.id.value))).containsExactlyInAnyOrderElementsOf(b.ids - positionOwned)
        assertThat(ids(secretary, TrackerFilter(dueTo = today.minusDays(1)))).containsExactlyInAnyOrderElementsOf(b.overdue)
        assertThat(ids(secretary, TrackerFilter(dueFrom = today.minusDays(LATE[1]), dueTo = today.minusDays(LATE[0]))))
            .containsExactlyInAnyOrder(b.overdue[0], b.overdue[1])
        assertThat(ids(secretary, TrackerFilter(meetingId = b.d.meeting.id))).containsExactlyInAnyOrderElementsOf(b.ids)
        assertThat(ids(secretary, TrackerFilter(itemId = b.d.credit))).containsExactlyInAnyOrderElementsOf(b.credit)
        assertThat(ids(secretary, TrackerFilter(source = ActionSource.DIRECT))).containsExactly(direct)
        val narrowed = track(secretary, TrackerFilter(itemId = b.d.credit))
        assertThat(narrowed.totals).isEqualTo(ActionTotals(red = 1, amber = 0, green = 1, toVerify = 0, closed = 0, drafts = 0, all = 2))

        // Text: the words of the title or the description (English stems, "plans" finds "plan"), or the reference.
        assertThat(ids(secretary, TrackerFilter(text = "contingency plans"))).containsExactly(b.green[0])
        assertThat(ids(secretary, TrackerFilter(text = "\"independent valuer\""))).containsExactly(b.green[1])
        assertThat(ids(secretary, TrackerFilter(text = "  liquidity -policy  "))).containsExactly(b.green[0])
        assertThat(ids(secretary, TrackerFilter(text = third.substringAfter(' ')))).containsExactly(b.ids[2])
        assertThat(ids(secretary, TrackerFilter(text = "   "))).hasSize(TOTAL + 1)
        assertThat(rejectedCodes { tracker.page(brc, TrackerFilter(text = "x".repeat(201)), null, 50) }).containsExactly("LENGTH")
        assertThat(rejectedCodes { tracker.page(brc, TrackerFilter(dueFrom = today, dueTo = today.minusDays(1)), null, 50) })
            .containsExactly("RANGE")

        // The direct draft is the secretaries' (undated, it sorts last); a meeting's draft is on the meeting's page only.
        val bySecretary = track(secretary)
        assertThat(bySecretary.items.map { it.id }).contains(direct).doesNotContain(meetingDraft)
        assertThat(bySecretary.items.last().id).isEqualTo(direct)
        assertThat(bySecretary.totals.drafts to bySecretary.totals.all).isEqualTo(1 to TOTAL + 1)
        assertThat(ids(chair)).doesNotContain(direct, meetingDraft).hasSize(TOTAL)
        assertThat(ids(director2)).doesNotContain(direct)
    }

    @Test
    fun `who has a tracker sees the rows of the rule, an owner outside the committee their own, a stranger nothing`() {
        val b = board()
        val positionOwned = b.ids.filterIndexed { i, _ -> i % 2 == 1 }
        // The treasurer holds Head of Treasury, outside the committee: their own rows, counted alone; no export.
        val theirs = track(treasurer)
        assertThat(theirs.items.map { it.id }).containsExactlyInAnyOrderElementsOf(positionOwned)
        assertThat(theirs.totals.all).isEqualTo(positionOwned.size)
        assertThat(theirs.can.export to theirs.can.raise).isEqualTo(false to false)
        // forum.read reads the unrestricted items' actions and exports; members read and do not export.
        assertThat(ids(reader)).containsExactlyInAnyOrderElementsOf(b.ids)
        assertThat(track(reader).can.export).isTrue()
        assertThat(track(director2).can.let { it.export to it.raise }).isEqualTo(false to false)
        assertThat(track(chair).can.let { it.export to it.raise }).isEqualTo(true to false)
        assertThat(track(secretary).can.let { it.export to it.raise }).isEqualTo(true to true)
        assertThat(track(secretary).key.let { it.amberWorkingDays to it.staleWorkingDays }).isEqualTo(10 to 20)
        assertThat(track(secretary).committee).isEqualTo("Board Risk Committee")
        // Nobody else has one, and an unknown committee is the same 404.
        assertThat(deniedAs(user) { tracker.page(brc, TrackerFilter(), null, 50) }).isEqualTo("404")
        assertThat(deniedAs(secretary) { tracker.page(UUID.randomUUID(), TrackerFilter(), null, 50) }).isEqualTo("404")
    }

    @Test
    fun `E08 ActionStats count by the same rule, overdue by owner and age, and closed on time`() {
        val b = board()
        val today = b.today
        alter(b.overdue[1], "due_revisions = 1")
        // Closed in the period: one done in time; one done after its original date but before the agreed one.
        val zone = asUser(admin) { orgDirectory.zone() }
        val original = checkNotNull(row(b.toVerify[0]).originalDueDate)
        alter(b.toVerify[0], "status = 'CLOSED', closed_at = now(), done_at = now()")
        alter(
            b.toVerify[1],
            "status = 'CLOSED', closed_at = now(), due_date = ?, done_at = ?::timestamptz",
            original.plusDays(10),
            original
                .plusDays(5)
                .atTime(LocalTime.NOON)
                .atZone(zone)
                .toOffsetDateTime()
                .toString(),
        )

        val bySecretary = asUser(secretary) { stats.overdue(setOf(brc), today) }
        assertThat(bySecretary.sumOf { it.count }).isEqualTo(3)
        assertThat(bySecretary.sumOf { it.days1To30 } to bySecretary.sumOf { it.days31To90 }).isEqualTo(1 to 1)
        assertThat(bySecretary.sumOf { it.over90 } to bySecretary.sumOf { it.revised }).isEqualTo(1 to 1)
        assertThat(bySecretary.single { it.ownerPositionId == headOfTreasury }.let { it.count to it.holderUserId })
            .isEqualTo(1 to treasurer.id.value)
        assertThat(bySecretary.filter { it.ownerUserId == director2.id.value }.sumOf { it.count }).isEqualTo(2)
        // The director is recused from the credit item: its overdue action (3 days late) is not counted.
        val byDirector = asUser(director) { stats.overdue(setOf(brc), today) }
        assertThat(byDirector.sumOf { it.count } to byDirector.sumOf { it.days1To30 }).isEqualTo(2 to 0)
        assertThat(asUser(user) { stats.overdue(setOf(brc), today) }).isEmpty()

        val closed = asUser(secretary) { stats.closedOnTime(setOf(brc), today, today) }.single()
        assertThat(listOf(closed.forumId, closed.closed, closed.byOriginalDate, closed.byAgreedDate)).containsExactly(brc, 2, 1, 2)
        assertThat(asUser(secretary) { stats.closedOnTime(setOf(brc), today.minusDays(2), today.minusDays(1)) }).isEmpty()
        assertThat(asUser(secretary) { stats.overdue(emptySet(), today) }).isEmpty()
        val bySystem =
            ActorContext.asSystem("analytics") {
                TenantContext.call(tenant.id) { transactions.readInTenant(tenant.id) { stats.overdue(setOf(brc), today) } }
            }
        assertThat(bySystem).isEmpty()
    }

    @Test
    fun `the tracker reads the committee's rows through an index, with and without text`() {
        val b = board()
        val classification = row(b.ids[0]).classificationId
        // 3,000 issued actions of 30 other committees of the tenant, then the statistics the planner reads.
        transactions.inTenant(tenant.id) {
            actionsDsl.current().execute(
                """
                insert into actions_item (id, tenant_id, forum_id, number_year, sequence, reference, source, source_label, title,
                    description, classification_id, owner_kind, owner_user_id, holder_user_id, due_date, original_due_date, status,
                    created_by, created_at, issued_by, issued_at)
                select gen_random_uuid(), current_setting('app.tenant_id')::uuid,
                    ('00000000-0000-0000-0000-' || lpad((g % 30)::text, 12, '0'))::uuid, 2026, g, 'VOL A' || g || '/2026', 'DIRECT',
                    'Direct', 'Volume action ' || g, 'Details of the volume action', ?, 'PERSON', ?, ?, current_date + (g % 200),
                    current_date + (g % 200), 'OPEN', ?, now(), ?, now()
                from generate_series(1, 3000) g
                """.trimIndent(),
                classification,
                director2.id.value,
                director2.id.value,
                secretary.id.value,
                secretary.id.value,
            )
        }
        DriverManager.getConnection(TestDatabase.jdbcUrl, "memo_migrator", TestDatabase.migratorPassword).use { c ->
            c.createStatement().use { it.execute("analyze actions_item") }
        }
        listOf(TrackerFilter(), TrackerFilter(text = "liquidity plans"), TrackerFilter(rag = RagColour.RED, sort = TrackerSort.UPDATED))
            .forEach { filter ->
                val (page, statements) = asUser(secretary) { sqlRecorder.record { tracker.page(brc, filter, null, 50) } }
                assertThat(page.items).describedAs("$filter").isNotNull()
                val tracked = statements.filter { ("\"actions_item\"" in it && "count(" in it.lowercase()) || "\"rag\"" in it }
                assertThat(tracked).describedAs("the page and its totals").hasSize(2)
                tracked.forEach { sql ->
                    val plan =
                        transactions.readInTenant(tenant.id) {
                            actionsDsl.current().fetch("explain $sql").joinToString("\n") { it.get(0, String::class.java) }
                        }
                    println("ActionTrackerIT $filter:\n$plan")
                    assertThat(plan).describedAs("$filter: $sql").doesNotContainPattern("Seq Scan on actions_item\\b")
                }
            }
    }

    private companion object {
        const val TOTAL = 14
        const val FIRST_GREEN = 5
        const val FIRST_DONE = 12

        /** How late the three overdue actions are: one in each of E08's ageing bands. */
        val LATE = listOf(3L, 40L, 100L)
    }
}
