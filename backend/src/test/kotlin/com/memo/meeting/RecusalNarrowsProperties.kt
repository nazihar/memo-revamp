package com.memo.meeting

import com.memo.jooq.tables.references.MEETING_DECLARATION
import com.memo.jooq.tables.references.MEETING_REVISION
import com.memo.meeting.internal.DeclarationExclusions
import com.memo.meeting.internal.ItemChange
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.MeetingStore
import com.memo.meeting.internal.PaperViewers
import com.memo.testsupport.Person
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.random.Random

/**
 * Recusal only ever narrows (test plan E04, T2; ADR-0062): for random declarations (every state, on items, sub-items and
 * their parents) over an agenda with sections, sub-items, restrictions, hidden titles, a members-only section, a
 * confidential item and an item attendee, what each person sees with the declarations' rule is what they see without it,
 * less exactly the items they are effectively recused from (or whose parent they are): the same lines, those lines never
 * in full and marked recused, the same lines and titles otherwise, and the same papers but those items'. For the latest
 * revision and the draft, officers, members, the guest, the attendee and a `forum.read` holder alike. Each run's
 * declarations are rolled back.
 */
class RecusalNarrowsProperties : RecusalFixture() {
    @Autowired lateinit var paperViewers: PaperViewers

    @Autowired lateinit var meetingStore: MeetingStore

    private class Rollback : RuntimeException()

    @Test
    fun `the rule with recusals is the rule without them, less exactly the items each person is recused from`() {
        val meeting = meeting()
        val parents = agendaWithEverything(meeting)
        val people = listOf(secretary, secretary2, chair, vice, d1, d2, d3, observer, guest, presenter, auditor)
        val withRecusals = PaperAccess(tenantDsl, DeclarationExclusions())
        val without = PaperAccess(tenantDsl, NoExclusions())
        repeat(RUNS) { run ->
            val random = Random(SEED + run)
            val declared =
                people.associateWith { person ->
                    parents.keys.filter { random.nextDouble() < DECLARING }.associateWith { STATES[random.nextInt(STATES.size)] }
                }
            try {
                transactions.inTenant(tenant.id) {
                    val row = checkNotNull(meetingStore.find(meeting.id))
                    declared.forEach { (person, items) -> items.forEach { (item, state) -> insert(row, person, item, state) } }
                    val revision =
                        tenantDsl
                            .current()
                            .select(MEETING_REVISION.ID)
                            .from(MEETING_REVISION)
                            .where(MEETING_REVISION.MEETING_ID.eq(meeting.id))
                            .orderBy(MEETING_REVISION.NUMBER.desc())
                            .limit(1)
                            .fetchSingle()
                            .value1()!!
                    for (person in people) {
                        val viewer = paperViewers.of(row, person.id).let { if (person == auditor) it.copy(reader = true) else it }
                        val effective = declared.getValue(person).filterValues { it.effective }.keys
                        val recused = parents.filter { (item, parent) -> item in effective || parent in effective }.keys
                        val case = "run $run, ${person.id}"
                        narrows(case, without.revision(viewer, revision), withRecusals.revision(viewer, revision), recused)
                        narrows("$case (draft)", without.draft(viewer, meeting.id), withRecusals.draft(viewer, meeting.id), recused)
                    }
                    throw Rollback()
                }
            } catch (_: Rollback) {
                // Each run's declarations are rolled back.
            }
        }
    }

    /** [narrowed] is [base] less exactly the items in [recused]: their lines kept but never in full, their papers gone. */
    private fun narrows(
        case: String,
        base: Visibility,
        narrowed: Visibility,
        recused: Set<UUID>,
    ) {
        assertThat(narrowed.lines.keys).describedAs("lines, $case").isEqualTo(base.lines.keys)
        assertThat(narrowed.recused).describedAs("recused lines, $case").isEqualTo(recused intersect base.lines.keys)
        for ((item, access) in base.lines) {
            val now = narrowed.lines.getValue(item)
            if (item in recused) {
                assertThat(now).describedAs("recused line $item, $case").isNotEqualTo(LineAccess.FULL)
                if (access != LineAccess.FULL) assertThat(now).describedAs("line $item, $case").isEqualTo(access)
            } else {
                assertThat(now).describedAs("line $item, $case").isEqualTo(access)
            }
        }
        assertThat(base.papers).describedAs("papers, $case").containsAll(narrowed.papers)
        val lost = base.papers - narrowed.papers
        assertThat(lost.map { paperItems.getValue(it) }).describedAs("papers withheld, $case").allMatch { it in recused }
        assertThat(base.papers.filter { paperItems.getValue(it) in recused }).describedAs("papers kept, $case").noneMatch {
            it in narrowed.papers
        }
    }

    /** Each paper's item (draft and revision papers share the paper's id). */
    private val paperItems = mutableMapOf<UUID, UUID>()

    /**
     * Items in every shape the rule distinguishes, with papers, published as revision 1, and one more in the draft only;
     * every item's parent (null at the top).
     */
    private fun agendaWithEverything(meeting: MeetingRow): Map<UUID, UUID?> {
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
            return id
        }

        fun paper(
            item: UUID,
            restrictedTo: List<UUID>? = null,
        ) {
            paperItems[paper(meeting, item, upload(meeting, "paper.pdf"), restrictedTo = restrictedTo)] = item
        }

        val open = item("Open item")
        paper(open)
        paper(open, restrictedTo = listOf(d1.id))
        val parent = item("Parent item")
        paper(parent)
        paper(item("First sub-item", parent = parent))
        item("Second sub-item", parent = parent)
        paper(item("Restricted item", ItemChange("Restricted item", restrictedTo = listOf(d1.id, d2.id))))
        paper(item("Hidden item", ItemChange("Hidden item", restrictedTo = listOf(d1.id), titleHidden = true)))
        paper(item("Hidden open item", ItemChange("Hidden open item", titleHidden = true)))
        paper(item("Confidential item", ItemChange("Confidential item", classificationId = levels()[1].id)))
        paper(item("In camera item", sectionId = inCamera(meeting)))
        paper(item("Attended item", ItemChange("Attended item", attendees = listOf(presenter.id)), section = "Consent items"))
        publish(meeting)
        paper(item("Draft only item"))
        return parents
    }

    private fun insert(
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
        const val RUNS = 40
        const val SEED = 20261119
        const val DECLARING = 0.2
        val STATES = DeclarationState.entries
    }
}
