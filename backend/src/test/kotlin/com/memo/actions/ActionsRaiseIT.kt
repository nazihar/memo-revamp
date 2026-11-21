package com.memo.actions

import com.memo.actions.internal.ActionAccess
import com.memo.actions.internal.ActionPeople
import com.memo.actions.internal.ActionSource
import com.memo.actions.internal.ActionStatus
import com.memo.actions.internal.CancelKind
import com.memo.actions.internal.ItemCopy
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.forum.ActionsPolicy
import com.memo.forum.OutcomeKind
import com.memo.meeting.internal.ItemChange
import com.memo.minutes.internal.DecisionInput
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.Audit
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.query
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Year
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Raising, issuing and seeing actions (test plan E07 T3 `ActionsRaiseIT`; ADR-0067, design E07 §1.1–§1.4, §1.14): T3's
 * acceptance criteria on the running BRC meeting (`director` recused from the credit item), the sources and their raise
 * windows, discarding, issuing one and all with the counter, the people's checks, and the item columns refreshed by a
 * later revision.
 */
class ActionsRaiseIT : ActionFixture() {
    @Autowired lateinit var tenantDsl: com.memo.common.tenant.TenantDsl

    @Autowired lateinit var resolutions: com.memo.minutes.Resolutions

    override fun label() = "actions"

    private val year get() = Year.now(ZoneOffset.UTC).value

    @Test
    fun `a draft is the secretaries' only, and issued it is numbered, its owner's holder has the task and is told without the title`() {
        val d = deciding { m, items -> declareOn(m, items.getValue(CREDIT), director) }
        val id = raise(d.meeting.id, d.credit, fields(title = "Report the revised collateral valuation", owner = position()))

        // A draft: the secretary sees it; the chair, members, the recused director and the owner do not.
        val draft = checkNotNull(seen(id, secretary))
        assertThat(draft.status to draft.reference).isEqualTo(ActionStatus.DRAFT to null)
        assertThat(draft.can.issue).isTrue()
        listOf(
            chair,
            others[0],
            director,
            treasurer,
            reader,
        ).forEach { assertThat(seen(id, it)).describedAs("draft for ${it.email}").isNull() }
        val drafted = Audit.assertAudited(tenant.id, "actions.action.drafted", id.toString(), secretary.id.value.toString())
        assertThat(drafted.canonical).contains("titleSha256", headOfTreasury.toString()).doesNotContain("collateral")

        val issued = issue(id)
        assertThat(issued.reference).isEqualTo("BRC A1/$year")
        assertThat(issued.status to issued.holderUserId).isEqualTo(ActionStatus.OPEN to treasurer.id.value)
        assertThat(issued.originalDueDate).isEqualTo(due)
        assertThat(issued.stepName).isEqualTo("Owner")
        assertThat(checkNotNull(issued.workflowInstanceId)).isNotNull()
        // The holder's task is due at the end of the due date in the tenant's zone.
        val dueAt = asUser(secretary) { workflowQueries.dueOf(ActionsPolicy.SUBJECT, id) }
        assertThat(dueAt).isEqualTo(due.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())
        val audited = Audit.assertAudited(tenant.id, "actions.action.issued", id.toString(), secretary.id.value.toString())
        assertThat(audited.canonical).contains("BRC A1/$year", issued.workflowInstanceId.toString())

        // Told in the app and by email, with the committee, the reference and the date; never the title.
        assertThat(items(treasurer, "ACTION_ASSIGNED")).singleElement().satisfies({
            assertThat(it).contains("BRC A1/$year", "Board Risk Committee").doesNotContain("collateral")
        })
        val outbox =
            AppRoleJdbc.inTransaction(tenant.id) { c ->
                c.query("select id from notification_mail_outbox where to_address = ? and template = 'ACTION_ASSIGNED'", treasurer.email) {
                    it.getObject(1, java.util.UUID::class.java)
                }
            }
        val params = params(outbox.single())
        assertThat(params["reference"] to params["date"]).isEqualTo("BRC A1/$year" to due.toString())
        assertThat(params.values.joinToString()).doesNotContain("collateral")

        // Issued: members see it by the item rule, the owner by being involved; the recused director nowhere.
        assertThat(seen(id, others[0])?.title).isEqualTo("Report the revised collateral valuation")
        val owner = checkNotNull(seen(id, treasurer))
        assertThat(owner.owner?.position?.title to owner.owner?.holder?.id).isEqualTo("Head of Treasury" to treasurer.id.value)
        assertThat(owner.source.label).startsWith("BRC 1/$year, item")
        assertThat(owner.can.edit).isFalse()
        assertThat(mine(treasurer).items.map { it.id } to mine(treasurer).counts.mine).isEqualTo(listOf(id) to 1)
        assertThat(seen(id, director)).isNull()
        assertThat(meetingActions(d.meeting.id, director)).isEmpty()
        assertThat(mine(director).counts.mine).isZero()
        assertThat(meetingActions(d.meeting.id, others[0])).containsExactly(id)
        assertThat(deniedAs(director) { asUser(director) { actionAccess.action(id) } }).isEqualTo("404")
    }

    @Test
    fun `a recused secretary cannot raise, a member cannot, a recused owner is allowed with a warning, a recused contributor is not`() {
        val secretary2 = secondSecretary()
        val d = deciding { m, items -> declareOn(m, items.getValue(CREDIT), director, secretary2) }
        assertThat(deniedAs(secretary2) { raise(d.meeting.id, d.credit, by = secretary2) }).isEqualTo("403 RECUSED")
        assertThat(deniedAs(director2) { raise(d.meeting.id, d.credit, by = director2) }).isEqualTo("403")
        assertThat(deniedAs(user) { raise(d.meeting.id, d.credit, by = user) }).isEqualTo("404")

        val id = raise(d.meeting.id, d.credit, fields(owner = person(director)))
        assertThat(checkNotNull(seen(id, secretary)).warnings.map { it.code to it.userId })
            .containsExactly(ActionAccess.OWNER_RECUSED to director.id.value)
        // The recused secretary neither sees the draft nor changes it.
        assertThat(seen(id, secretary2)).isNull()
        assertThat(deniedAs(secretary2) { issuing.issue(id, row(id).version) }).isEqualTo("404")
        issue(id)
        // The recused owner sees their action, told they are recused, and its item's line only as the title rule allows.
        val theirs = checkNotNull(seen(id, director))
        assertThat(theirs.viewerRecused to theirs.title).isEqualTo(true to "Report the revised collateral valuation")
        assertThat(mine(director).items.map { it.id }).containsExactly(id)
        assertThat(meetingActions(d.meeting.id, director)).containsExactly(id)

        assertThat(rejectedCodes { raise(d.meeting.id, d.credit, fields(contributors = listOf(director.id.value))) })
            .containsExactly("RECUSED_NAMED")
        assertThat(
            rejectedCodes { raise(d.meeting.id, d.appetite, fields(owner = person(director2), contributors = listOf(director2.id.value))) },
        ).containsExactly("SAME_PERSON")
        assertThat(rejectedCodes { raise(d.meeting.id, d.appetite, fields(contributors = (1..6).map { java.util.UUID.randomUUID() })) })
            .contains("TOO_MANY")
        // A confidential action: the secretary must be cleared for it, and so must its owner.
        val confidential = levels()[1]
        assertThat(
            rejectedCodes { raise(d.meeting.id, d.appetite, fields(classificationId = confidential.id)) },
        ).containsExactly("CLASSIFICATION")
        asUser(admin) { access.ensureClearance(secretary.id.value, confidential.rank) }
        assertThat(rejectedCodes { raise(d.meeting.id, d.appetite, fields(classificationId = confidential.id)) })
            .containsExactly(ActionPeople.OWNER_NOT_CLEARED)
        assertThat(rejectedCodes { raise(d.meeting.id, d.appetite, fields(dueDate = due.minusDays(60))) }).containsExactly("DUE_IN_PAST")
        assertThat(rejectedCodes { raise(d.meeting.id, d.appetite, fields(title = " ")) }).containsExactly("REQUIRED")
        // An inactive owner is refused at issue too.
        val leaver = accounts.anActiveUser(transactions, tenant, aPassword())
        val later = raise(d.meeting.id, d.appetite, fields(owner = person(leaver)))
        asUser(admin) { lifecycleOf(leaver) }
        assertThat(rejectedCodes { issuing.issue(later, row(later).version) }).containsExactly(ActionPeople.OWNER_NOT_CLEARED)
    }

    @Test
    fun `actions come from a decision and its conditions, an item, the meeting or the committee, within their windows`() {
        val d = deciding()
        arrive(d.meeting, listOf(chair, director, director2, others[0], others[1]))
        val decided =
            record(
                d,
                d.appetite,
                DecisionInput(
                    OutcomeKind.APPROVED_WITH_CONDITIONS,
                    text = "Resolved.",
                    conditions = listOf("Quarterly covenant report to the committee.", "Security before drawdown."),
                ),
            )
        // From a condition: its text fills the title and what the owner is told.
        val fromCondition =
            raise(
                d.meeting.id,
                null,
                fields(title = null),
                source = ActionSource.DECISION,
                decisionId = decided.decision.id,
                conditionIndex = 0,
            )
        val row = row(fromCondition)
        assertThat(
            row.title to row.context,
        ).isEqualTo("Quarterly covenant report to the committee." to "Quarterly covenant report to the committee.")
        assertThat(row.itemId to row.decisionSeq).isEqualTo(d.appetite to 1)
        assertThat(row.sourceLabel).endsWith("— decision, condition 1")
        assertThat(
            rejectedCodes {
                raise(d.meeting.id, null, source = ActionSource.DECISION, decisionId = decided.decision.id, conditionIndex = 5)
            },
        ).containsExactly("CONDITION")
        correctedDecision(d, fromCondition)
        // An item with a decision raises from its decision.
        assertThat(row(raise(d.meeting.id, d.appetite)).source).isEqualTo(ActionSource.DECISION)
        assertThat(row(raise(d.meeting.id, d.credit)).source).isEqualTo(ActionSource.ITEM)
        val meetingWide = row(raise(d.meeting.id, null))
        assertThat(meetingWide.source to meetingWide.itemId).isEqualTo(ActionSource.MEETING to null)
        val direct = asUser(secretary) { raising.raiseDirect(brc, fields().copy(sourceNote = "Chair's instruction of 3 December")) }
        assertThat(row(direct).source to row(direct).sourceLabel).isEqualTo(ActionSource.DIRECT to "Board Risk Committee")
        assertThat(deniedAs(director) { asUser(director) { raising.raiseDirect(brc, fields()) } }).isEqualTo("403")
        assertThat(deniedAs(user) { asUser(user) { raising.raiseDirect(brc, fields()) } }).isEqualTo("404")

        // A meeting that has not started takes no action yet.
        val (scheduled, items) = published()
        assertThat(refusedReason { raise(scheduled.id, items.getValue("Credit proposal")) }).isEqualTo("WRONG_STATE")

        // Discarded: kept, never numbered, never issued.
        val discarded = asUser(secretary) { raising.discard(meetingWide.id, meetingWide.version, "Raised twice") }
        assertThat(discarded.status to discarded.cancelKind).isEqualTo(ActionStatus.CANCELLED to CancelKind.DISCARDED)
        assertThat(refusedReason { issuing.issue(meetingWide.id, discarded.version) }).isEqualTo("WRONG_STATE")
        Audit.assertAudited(tenant.id, "actions.action.discarded", meetingWide.id.toString())

        // Edited with the version last seen: a stale one is 412.
        val edited = asUser(secretary) { raising.edit(direct, row(direct).version, fields(title = "Circulate the revised policy")) }
        assertThat(edited.title).isEqualTo("Circulate the revised policy")
        assertThat(Audit.assertAudited(tenant.id, "actions.action.edited", direct.toString()).canonical).contains("titleSha256", "fields")
        assertThatThrownBy {
            asUser(
                secretary,
            ) { raising.edit(direct, 0, fields()) }
        }.isInstanceOf(ConcurrentModificationException::class.java)
        // A draft without an owner or a date cannot be issued.
        val bare = raise(d.meeting.id, d.credit, fields(owner = null, dueDate = null))
        assertThat(rejectedCodes { issuing.issue(bare, row(bare).version) }).containsExactly("REQUIRED", "REQUIRED")
    }

    /** The decision of [d]'s appetite item corrected: [actionId], issued, records it and keeps its condition. */
    private fun correctedDecision(
        d: Deciding,
        actionId: java.util.UUID,
    ) {
        // The decision corrected once the meeting is held: the issued action records it, keeps its condition, and its label
        // takes the resolution number given at once (E06 T4); nothing else changes.
        issue(actionId)
        asUser(secretary) { meeting(d.meeting.id).let { lifecycle.end(it.id, it.version) } }
        record(
            d,
            d.appetite,
            DecisionInput(
                OutcomeKind.APPROVED_WITH_CONDITIONS,
                text = "Resolved, as corrected.",
                conditions = listOf("Quarterly covenant report to the committee."),
                reason = "The second condition was withdrawn",
            ),
        )
        val timeline = transactions.readInTenant(tenant.id) { actionLines.entries(actionId) }
        assertThat(timeline.first().kind to timeline.first().details["seq"]).isEqualTo("SOURCE_CORRECTED" to 2)
        Audit.assertAudited(tenant.id, "actions.action.source_corrected", actionId.toString(), secretary.id.value.toString())
        assertThat(row(actionId).let { it.conditionIndex to it.status }).isEqualTo(0 to ActionStatus.OPEN)
        val number = checkNotNull(transactions.readInTenant(tenant.id) { resolutions.inForce(row(actionId).decisionId!!) }).number
        assertThat(row(actionId).sourceLabel).endsWith("— Resolution $number, condition 1")
    }

    @Test
    fun `issuing all of a meeting's drafts answers per draft, and concurrent issues take numbers one to n without a gap`() {
        val d = deciding()
        val drafts = (1..4).map { raise(d.meeting.id, d.credit, fields(title = "Action $it")) }
        val bare = raise(d.meeting.id, null, fields(owner = null))
        val results = signedIn(secretary) { actionsController.issueAll(d.meeting.id, "issue-all-1").body!!.results }
        assertThat(
            results.filter { it.issued }.map { it.reference },
        ).containsExactlyInAnyOrder(*(1..4).map { "BRC A$it/$year" }.toTypedArray())
        assertThat(results.single { !it.issued }.let { it.actionId to it.reason }).isEqualTo(bare to "REQUIRED")
        // The same key replays the same results.
        val again = signedIn(secretary) { actionsController.issueAll(d.meeting.id, "issue-all-1").body!!.results }
        assertThat(
            again
                .filter {
                    it.issued
                }.map { it.reference },
        ).containsExactlyInAnyOrderElementsOf(results.filter { it.issued }.map { it.reference })
        assertThat(drafts.map { row(it).status }).containsOnly(ActionStatus.OPEN)

        // Four at once, each in its own transaction: 5 to 8, no duplicates.
        val more = (1..4).map { raise(d.meeting.id, d.appetite, fields(title = "Concurrent $it")) }
        val pool = Executors.newFixedThreadPool(4)
        val references =
            try {
                pool.invokeAll(more.map { id -> Callable { issue(id).reference } }).map { it.get() }
            } finally {
                pool.shutdown()
            }
        assertThat(references).containsExactlyInAnyOrder(*(5..8).map { "BRC A$it/$year" }.toTypedArray())
        // Issuing twice at once: one wins, the other is refused (the lock serialises them).
        val twice = raise(d.meeting.id, d.appetite)
        val version = row(twice).version
        val racing = Executors.newFixedThreadPool(2)
        val outcomes =
            try {
                racing
                    .invokeAll(
                        List(2) { Callable { runCatching { asUser(secretary) { issuing.issue(twice, version) } } } },
                    ).map { it.get() }
            } finally {
                racing.shutdown()
            }
        assertThat(outcomes.count { it.isSuccess }).isEqualTo(1)
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            assertThat(c.long("select last_number from actions_counter where forum_id = ? and year = ?", brc, year)).isEqualTo(9)
        }
    }

    @Test
    fun `a later revision of the meeting refreshes the item columns its actions copied`() {
        val (scheduled, items) = published()
        val credit = items.getValue("Credit proposal")
        val level = levels().first().id
        val planting = ActionPlanting(transactions, actionStore, tenantDsl)
        val id =
            planting.action(
                tenant.id,
                brc,
                scheduled.id,
                credit,
                ItemCopy(null, level, null, titleHidden = false, membersOnly = false),
                level,
                secretary.id.value,
                "Planted",
                treasurer.id.value,
            )
        assertThat(seen(id, others[0])).isNotNull()
        asUser(secretary) {
            agendaItems.update(
                scheduled.id,
                credit,
                drafts.draft(scheduled.id).version,
                ItemChange("Credit proposal", restrictedTo = listOf(director2.id.value)),
            )
            publisher.publish(scheduled.id, drafts.draft(scheduled.id).version, "Restricted", true)
        }
        assertThat(row(id).item?.restrictedTo).containsExactly(director2.id.value)
        // Now only the item's named member, the officers and the owner see it.
        assertThat(seen(id, others[0])).isNull()
        assertThat(seen(id, director2)).isNotNull()
        assertThat(seen(id, treasurer)).isNotNull()
    }

    private fun lifecycleOf(leaver: com.memo.identity.UserAccount) {
        userLifecycle.deactivate(leaver.id, checkNotNull(accounts.find(leaver.id)).version, admin.id)
    }

    @Autowired lateinit var userLifecycle: com.memo.identity.UserLifecycle
}
