package com.memo.search

import com.memo.common.authz.Permission
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.forum.ForumRole
import com.memo.forum.internal.MemberInput
import com.memo.jooq.tables.references.MEETING_DECLARATION
import com.memo.meeting.DeclarationState
import com.memo.meeting.MeetingViewers
import com.memo.meeting.RecusalFixture
import com.memo.meeting.internal.ItemChange
import com.memo.meeting.internal.MeetingRow
import com.memo.minutes.DecisionPlanting
import com.memo.minutes.internal.DecisionSight
import com.memo.minutes.internal.DecisionStore
import com.memo.minutes.internal.MinutesAccess
import com.memo.minutes.internal.NoteStore
import com.memo.search.internal.decision.DecisionIndex
import com.memo.testsupport.Person
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.random.Random

/**
 * The register's rule against E06's (ADR-0069, test plan E08 T1 property): over an agenda with every shape the item rule
 * tells apart (restricted, restricted with a hidden title, a hidden title, a confidential level, a members-only section,
 * a parent and its sub-items, an item attendee's item) with a decision on every item, and random declarations in every
 * state (on items, sub-items and parents; rolled back after each run), for every kind of viewer (secretaries, the chair,
 * the vice-chair with a chair in office, members cleared and not, an observer, a guest, an item attendee, `forum.read`,
 * `forums.manage`, a former member, someone with no role):
 *  - nothing for a viewer the register is not for (an observer, a guest, an item attendee, a former member, someone
 *    with no role: design E08 §1.3), whatever E06's meeting page shows them;
 *  - for the others (an officer, a seat in office, `forum.read`, `forums.manage`), a row is FULL exactly when E06's
 *    `MinutesAccess` shows the viewer the decision in full, otherwise a LINE when it is within their clearance, else nothing;
 *  - the recusal is read live: the reason of a recused line is RECUSED;
 *  - totals and facets count exactly the rows returned, outcome facets the FULL ones; a content filter never matches a LINE.
 */
class DecisionRegisterEquivalenceProperties : RecusalFixture() {
    @Autowired lateinit var viewers: MeetingViewers

    @Autowired lateinit var minutesAccess: MinutesAccess

    @Autowired lateinit var decisionSearch: DecisionSearch

    @Autowired lateinit var decisionIndex: DecisionIndex

    @Autowired lateinit var decisionStore: DecisionStore

    @Autowired lateinit var noteStore: NoteStore

    private class Rollback : RuntimeException()

    /** A viewer: whether the register is for them (design E08 §1.3) and their clearance rank. */
    private class Viewer(
        val person: Person,
        val name: String,
        val inScope: Boolean,
        val clearance: Int,
    )

    @Test
    fun `the register's FULL, LINE and NONE are E06's rule and the line rule, for every viewer, with live recusals`() {
        val manager = person(clearance = 1).also { grant(it, Permission.FORUMS_MANAGE) }
        val former = formerMember()
        val meeting = meeting()
        val parents = agenda(meeting)
        val planting = DecisionPlanting(transactions, decisionStore, noteStore)
        parents.keys.forEach { item ->
            val id = planting.decision(tenant.id, meeting.id, brc, item, secretary.id, "Resolved on item $item")
            transactions.inTenant(tenant.id) { assertThat(decisionIndex.put(id)).isTrue() }
        }
        // The register's scope by role, as design E08 §1.3 states it (the vice-chair is a member: there is a chair).
        val viewers =
            listOf(
                Viewer(secretary, "secretary", true, 1),
                Viewer(secretary2, "secretary2", true, 1),
                Viewer(chair, "chair", true, 1),
                Viewer(vice, "vice", true, 1),
                Viewer(d1, "d1", true, 1),
                Viewer(d2, "d2", true, 1),
                Viewer(d3, "d3 (not cleared)", true, 0),
                Viewer(auditor, "auditor (forum.read)", true, 0),
                Viewer(manager, "forums.manage", true, 1),
                Viewer(observer, "observer", false, 1),
                Viewer(guest, "guest", false, 1),
                Viewer(presenter, "item attendee", false, 1),
                Viewer(outsider, "outsider", false, 1),
                Viewer(former, "former member", false, 1),
            )
        repeat(RUNS) { run ->
            val random = Random(SEED + run)
            val declared =
                viewers.associate { v ->
                    v.person to
                        parents.keys.filter { random.nextDouble() < DECLARING }.associateWith { STATES[random.nextInt(STATES.size)] }
                }
            try {
                transactions.inTenant(tenant.id) {
                    declared.forEach { (person, items) -> items.forEach { (item, state) -> declare(meeting, person, item, state) } }
                    viewers.forEach { v ->
                        val effective = declared.getValue(v.person).filterValues { it.effective }.keys
                        check("run $run, ${v.name}", v, meeting, parents, effective)
                    }
                    throw Rollback()
                }
            } catch (_: Rollback) {
                // Each run's declarations are rolled back.
            }
        }
    }

    /** [v]'s register against E06's rule on every item, with [effective] the items they hold an effective declaration on. */
    private fun check(
        case: String,
        v: Viewer,
        meeting: MeetingRow,
        parents: Map<UUID, UUID?>,
        effective: Set<UUID>,
    ) {
        val e06 = asActor(v.person) { e06(meeting, parents.keys) }
        val page = asActor(v.person) { decisionSearch.find(DecisionQuery(forumIds = setOf(brc), limit = PAGE)) }
        val register = page.rows.associateBy { it.itemId }
        for (item in parents.keys) {
            val rank = if (item == confidentialItem) 1 else 0
            val expected =
                when {
                    !v.inScope -> null
                    e06[item] == DecisionSight.FULL -> RegisterLevel.FULL
                    rank <= v.clearance -> RegisterLevel.LINE
                    else -> null
                }
            val row = register[item]
            assertThat(row?.level).describedAs("$case, ${titles[item]}").isEqualTo(expected)
            val recused = item in effective || parents[item] in effective
            if (row?.level == RegisterLevel.FULL) assertThat(recused).describedAs("$case, ${titles[item]}: FULL though recused").isFalse()
            if (row?.level == RegisterLevel.LINE && recused) {
                assertThat(row.reason).describedAs("$case, ${titles[item]}").isEqualTo(LineReason.RECUSED)
            }
        }
        counts(case, v.person, page)
    }

    /** A member of the committee whose term ended yesterday. */
    private fun formerMember(): Person =
        person(clearance = 1).also { former ->
            asUser(admin) {
                members.add(
                    brc,
                    MemberInput(
                        userId = former.id,
                        role = ForumRole.MEMBER,
                        startsOn =
                            java.time.LocalDate
                                .now()
                                .minusYears(2),
                        termEndsOn =
                            java.time.LocalDate
                                .now()
                                .minusDays(1),
                    ),
                )
            }
        }

    /** Totals and facets are the rows returned; an outcome filter matches FULL rows only; LINE rows carry no content. */
    private fun counts(
        case: String,
        person: Person,
        page: DecisionPage,
    ) {
        val full = page.rows.count { it.level == RegisterLevel.FULL }.toLong()
        assertThat(page.total).describedAs("total, $case").isEqualTo(page.rows.size)
        assertThat(page.facets.forums[brc] ?: 0L).describedAs("committee facet, $case").isEqualTo(page.rows.size.toLong())
        assertThat(
            page.facets.outcomes.values
                .sum(),
        ).describedAs("outcome facet, $case").isEqualTo(full)
        page.rows.filter { it.level == RegisterLevel.LINE }.forEach { line ->
            assertThat(listOf(line.title, line.outcomeKind, line.text, line.conditions, line.memoId, line.hasDissent))
                .describedAs("line content, $case")
                .containsOnlyNulls()
        }
        val approved =
            asActor(person) { decisionSearch.find(DecisionQuery(forumIds = setOf(brc), outcomes = setOf("APPROVED"), limit = PAGE)) }
        assertThat(approved.rows).describedAs("outcome filter, $case").allMatch { it.level == RegisterLevel.FULL }
        assertThat(approved.rows.size.toLong()).describedAs("outcome filter count, $case").isEqualTo(full)
        val words = asActor(person) { decisionSearch.find(DecisionQuery(text = "resolved", forumIds = setOf(brc), limit = PAGE)) }
        assertThat(words.rows.map { it.decisionId }.toSet())
            .describedAs("text, $case")
            .isEqualTo(
                page.rows
                    .filter { it.level == RegisterLevel.FULL }
                    .map { it.decisionId }
                    .toSet(),
            )
    }

    /** What E06 shows [itemIds]' decisions to the actor as (absent: nothing). */
    private fun e06(
        meeting: MeetingRow,
        itemIds: Set<UUID>,
    ): Map<UUID, DecisionSight> {
        val view = viewers.find(meeting.id) ?: return emptyMap()
        return itemIds.associateWith { minutesAccess.sight(view, it) }.filterValues { it != DecisionSight.NONE }
    }

    private fun <T> asActor(
        person: Person,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(person.id), null)) { transactions.inTenant(tenant.id, block) }

    private fun grant(
        person: Person,
        permission: Permission,
    ) = asUser(admin) { access.grantCustom(person.id, "Register $permission", setOf(permission)) }

    private val titles = mutableMapOf<UUID, String>()
    private var confidentialItem: UUID? = null

    /** Items in every shape the rule tells apart, published as revision 1; each item's parent (null at the top). */
    private fun agenda(meeting: MeetingRow): Map<UUID, UUID?> {
        val parents = mutableMapOf<UUID, UUID?>()

        fun item(
            title: String,
            change: ItemChange? = null,
            section: String = "For decision",
            sectionId: UUID? = null,
            parent: UUID? = null,
        ): UUID {
            val id = if (parent == null) addItem(meeting, title, section, sectionId) else subItem(meeting, parent, title)
            change?.let { change(meeting, id, it) }
            parents[id] = parent
            titles[id] = title
            return id
        }
        item("Open item")
        val parent = item("Parent item")
        item("First sub-item", parent = parent)
        item("Second sub-item", parent = parent)
        item("Restricted item", ItemChange("Restricted item", restrictedTo = listOf(d1.id, d2.id)))
        item("Hidden item", ItemChange("Hidden item", restrictedTo = listOf(d1.id), titleHidden = true))
        item("Hidden open item", ItemChange("Hidden open item", titleHidden = true))
        confidentialItem = item("Confidential item", ItemChange("Confidential item", classificationId = levels()[1].id))
        item("In camera item", sectionId = inCamera(meeting))
        item("Attended item", ItemChange("Attended item", attendees = listOf(presenter.id)), section = "Consent items")
        publish(meeting)
        return parents
    }

    private fun declare(
        meeting: MeetingRow,
        person: Person,
        item: UUID,
        state: DeclarationState,
    ) {
        val d = MEETING_DECLARATION
        tenantDsl
            .current()
            .insertInto(d)
            .set(d.ID, UUID.randomUUID())
            .set(d.TENANT_ID, tenant.id.value)
            .set(d.MEETING_ID, meeting.id)
            .set(d.ITEM_ID, item)
            .set(d.FORUM_ID, meeting.forumId)
            .set(d.USER_ID, person.id)
            .set(d.NATURE, "An interest")
            .set(d.INTENT, if (state == DeclarationState.AWAITING_RULING) "ASK_CHAIR" else "LEAVE")
            .set(d.SOURCE, "SELF")
            .set(d.HOW, "IN_APP")
            .set(d.DECLARED_AT, OffsetDateTime.now())
            .set(d.AT_MEETING, false)
            .set(d.STATE, state.name)
            .execute()
    }

    private companion object {
        const val RUNS = 25
        const val SEED = 20261122
        const val DECLARING = 0.2
        const val PAGE = 100
        val STATES = DeclarationState.entries
    }
}
