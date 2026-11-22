package com.memo.analytics

import com.memo.forum.OutcomeKind
import com.memo.meeting.internal.ItemChange
import com.memo.minutes.internal.DecisionInput
import com.memo.minutes.internal.DeferTarget
import com.memo.testsupport.Person
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.ZoneId

/**
 * The leak suite's ANALYTICS surface, its differential probe (ADR-0070 "Confirmation"; test plan E08 §4): for a recused or
 * restricted viewer, every item-level measure equals the measure computed on the data without the items they may not see
 * in full. The data without them is real: the viewers' analytics are read after a first held meeting (one decided item,
 * an overdue action of the meeting); then a second meeting is held whose only item the viewers may not see in full: a
 * member and the secretary declared an interest in it (recused), and it is restricted to the chair and the vice-chair (so
 * `forum.read` sees its line only). It is deferred, late, and carries an overdue action. After it:
 *  - the recused member, the recused secretary and `forum.read` read the same time to decision, deferrals, overdue and
 *    closed actions and late items and papers as before, in the overview and in the detail: nothing of the item is counted;
 *  - meeting-level measures (meetings held, attendance, papers on time) may change: the meeting itself is not hidden;
 *  - the chair (named, not recused) reads the item's decision, deferral, lateness and action: the hidden data is there.
 */
class AnalyticsLeakIT : AnalyticsFixture() {
    /** The item-level measures of a viewer's overview block and detail, which must not move for the hidden item. */
    private fun itemLevel(person: Person): Map<String, JsonNode?> {
        val block = analytics(person).json()["committees"].items().single { it["forumId"].asString() == brc.toString() }
        val detail = detail(person).json()
        return mapOf(
            "summary time to decision" to block["timeToDecision"],
            "summary deferrals" to block["deferrals"],
            "summary actions" to block["actions"],
            "summary late items" to block["papers"]["lateItems"],
            "summary late papers" to block["papers"]["latePapers"],
            "detail time to decision" to detail["timeToDecision"],
            "detail deferrals" to detail["deferrals"],
            "detail actions" to detail["actions"],
            "detail late items" to detail["papers"]["summary"]["lateItems"],
            "detail late papers" to detail["papers"]["summary"]["latePapers"],
        )
    }

    @Test
    fun `a recused or restricted viewer's measures equal those computed without the items they may not see`() {
        val first = meetingWith(listOf("Term loan, Karnaphuli Steel"), listOf(chair, vice, director, colleague))
        decide(first, first.items.single().item, APPROVED_WITH_CONDITIONS)
        held(first)
        overdueAction(first.meeting, null, colleague, daysAgo = 20)

        val viewers = listOf(director, secretary, reader)
        val before = viewers.associateWith { itemLevel(it) }
        val chairBefore = itemLevel(chair)

        val subject = "Term loan, Delta Textiles"
        val second =
            meetingWith(
                listOf(subject),
                listOf(chair, vice, director, colleague),
                before = { meeting, items ->
                    change(meeting, items.single().item, ItemChange(subject, restrictedTo = listOf(chair.id, vice.id)))
                },
                published = { meeting, items ->
                    declare(director, meeting, items.single().item)
                    declare(secretary, meeting, items.single().item)
                },
            )
        val hidden = second.items.single().item
        val target = LocalDate.now(ZoneId.of("Asia/Dhaka")).plusDays(30)
        asUser(admin) {
            recorder.record(second.meeting.id, hidden, DecisionInput(OutcomeKind.DEFERRED, deferTo = DeferTarget(date = target)), null)
        }
        endMeeting(second.meeting)
        overdueAction(second.meeting, hidden, colleague, daysAgo = 120, title = "Perfect the security of Delta Textiles")

        for (viewer in viewers) {
            assertThat(itemLevel(viewer)).describedAs("the item-level measures of ${viewer.id}").isEqualTo(before.getValue(viewer))
        }
        // The meeting itself is not hidden: it was held, and its attendance counts.
        assertThat(
            analytics(director)
                .json()["committees"]
                .items()
                .single()["attendance"]["heldMeetings"]
                .asInt(),
        ).isEqualTo(2)

        // The control: the chair, named and not recused, counts the item's decision, deferral, lateness and action.
        val chairAfter = itemLevel(chair)
        assertThat(
            chairAfter["summary deferrals"]!!["deferred"].asInt(),
        ).isEqualTo(chairBefore["summary deferrals"]!!["deferred"].asInt() + 1)
        assertThat(
            chairAfter["summary actions"]!!["overdueOver90"].asInt(),
        ).isEqualTo(chairBefore["summary actions"]!!["overdueOver90"].asInt() + 1)
        assertThat(chairAfter["summary late items"]!!.asInt()).isGreaterThan(chairBefore["summary late items"]!!.asInt())
    }
}
