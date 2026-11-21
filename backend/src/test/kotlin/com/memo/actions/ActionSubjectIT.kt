package com.memo.actions

import com.memo.actions.internal.ActionPriority
import com.memo.actions.internal.ActionSource
import com.memo.actions.internal.ActionStatus
import com.memo.actions.subject.ActionItemSubject
import com.memo.forum.ActionsPolicy
import com.memo.forum.OutcomeKind
import com.memo.minutes.internal.DecisionInput
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import com.memo.workflow.BlockedReason
import com.memo.workflow.SlotHolder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * The `action-item` subject (test plan E07 T3 `ActionSubjectIT`; ADR-0068, design E07 §1.8, §1.9): who each slot names,
 * the due time, the attributes for CEL, who may not hold a task, the people with an interest, the summaries, and the
 * action kept in step with the engine's events in the same transaction (holder, step name, status), with the action's
 * lock taken before the instance's.
 */
class ActionSubjectIT : ActionFixture() {
    @Autowired lateinit var subject: ActionItemSubject

    override fun label() = "actsubject"

    private fun <T> asSystem(block: () -> T): T = transactions.readInTenant(tenant.id, block)

    @Test
    fun `the slots name the owner, a verifier who is none of the people involved, the chair and the secretaries not recused`() {
        val secretary2 = secondSecretary()
        val d = deciding { m, items -> declareOn(m, items.getValue(CREDIT), secretary2) }
        val byPerson = row(raise(d.meeting.id, d.credit, fields(owner = person(director2), contributors = listOf(others[0].id.value))))
        val byPosition = row(raise(d.meeting.id, d.appetite, fields(owner = position())))
        asSystem {
            assertThat(subject.slot(byPerson.id, ActionItemSubject.OWNER)).containsExactly(SlotHolder.Person(director2.id.value))
            assertThat(subject.slot(byPosition.id, ActionItemSubject.OWNER)).containsExactly(SlotHolder.Position(headOfTreasury))
            // secretary2 is recused from the credit item: the verifier is the other secretary, never the owner or a contributor.
            assertThat(subject.slot(byPerson.id, ActionItemSubject.VERIFIER)).containsExactly(SlotHolder.Person(secretary.id.value))
            assertThat(subject.slot(byPosition.id, ActionItemSubject.VERIFIER))
                .containsExactlyInAnyOrder(SlotHolder.Person(secretary.id.value), SlotHolder.Person(secretary2.id.value))
            assertThat(subject.slot(byPerson.id, ActionItemSubject.SECRETARIES)).containsExactly(SlotHolder.Person(secretary.id.value))
            assertThat(subject.slot(byPerson.id, ActionItemSubject.CHAIR)).containsExactly(SlotHolder.Person(chair.id.value))
            assertThat(subject.slot(byPerson.id, "nobody")).isEmpty()
            // Recused people are never candidates for its steps, but an owner may be recused (design E07 §1.3).
            assertThat(subject.interested(byPerson.id)).containsExactly(secretary2.id.value)
        }
        // The owner who is the only secretary not recused: verification falls to the chair.
        val ownSecretary = row(raise(d.meeting.id, d.credit, fields(owner = person(secretary))))
        asSystem {
            assertThat(subject.slot(ownSecretary.id, ActionItemSubject.VERIFIER)).containsExactly(SlotHolder.Person(chair.id.value))
            assertThat(subject.slot(ownSecretary.id, ActionItemSubject.CHAIR)).containsExactly(SlotHolder.Person(chair.id.value))
        }
        val ownChair = row(raise(d.meeting.id, d.appetite, fields(owner = person(chair))))
        asSystem { assertThat(subject.slot(ownChair.id, ActionItemSubject.CHAIR)).isEmpty() }
    }

    @Test
    fun `the due time, the attributes, eligibility, summaries and snapshot come from the action`() {
        val d = deciding()
        arrive(d.meeting, listOf(chair, director, director2, others[0], others[1]))
        val decided = record(d, d.appetite, DecisionInput(OutcomeKind.APPROVED, text = "Resolved."))
        val fromResolution =
            raise(
                d.meeting.id,
                null,
                fields().copy(priority = ActionPriority.HIGH),
                source = ActionSource.DECISION,
                decisionId = decided.decision.id,
            )
        val inactive = accounts.anActiveUser(transactions, tenant, aPassword())
        asUser(admin) { userLifecycle.deactivate(inactive.id, checkNotNull(accounts.find(inactive.id)).version, admin.id) }
        val confidential = levels()[1]
        asUser(admin) { access.ensureClearance(secretary.id.value, confidential.rank) }
        val secret = raise(d.meeting.id, d.credit, fields(owner = person(secretary), classificationId = confidential.id))
        asSystem {
            assertThat(subject.dueAt(fromResolution, "owner")).isEqualTo(due.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())
            val values = subject.attributeValues(fromResolution)
            assertThat(values).containsEntry("priority", "HIGH").containsEntry("fromResolution", true).containsEntry("source", "DECISION")
            assertThat(
                values,
            ).containsEntry("dueRevisions", 0).containsEntry("reopened", false).containsEntry("committeeType", "Board committee")
            assertThat(subject.attributes.keys).containsAll(values.keys)
            assertThat(subject.ineligible(secret, listOf(inactive.id.value, director.id.value, secretary.id.value)))
                .isEqualTo(mapOf(inactive.id.value to BlockedReason.INACTIVE, director.id.value to BlockedReason.NO_CLEARANCE))
            val summary = subject.summaries(listOf(secret)).getValue(secret)
            assertThat(summary.reference to summary.classificationRank).isEqualTo(null to confidential.rank)
            assertThat(subject.snapshot(secret)).containsKeys("reference", "dueDate", "version")
        }
    }

    @Test
    fun `the engine's events keep the holder, the step and the status, and the action's lock comes first`() {
        val d = deciding()
        val id = raise(d.meeting.id, d.credit, fields(owner = person(director2)))
        val issued = issue(id)
        assertThat(issued.holderUserId to issued.stepName).isEqualTo(director2.id.value to "Owner")
        assertThat(issued.ownerStep).isEqualTo("owner")

        // The owner recommends (marks it done): the verification step opens for the secretary.
        val ownerTask = checkNotNull(asUser(director2) { workflowQueries.myTask(ActionsPolicy.SUBJECT, id, director2.id.value) })
        asUser(director2) { workflows.approve(ownerTask.id, "Valuation attached") }
        val done = row(id)
        assertThat(done.status to done.stepName).isEqualTo(ActionStatus.DONE_PENDING to "Verification")
        assertThat(done.doneAt).isNotNull()
        assertThat(done.holderUserId).isEqualTo(director2.id.value)

        // The secretary approves: the instance ends approved and the action is closed.
        val verifyTask = checkNotNull(asUser(secretary) { workflowQueries.myTask(ActionsPolicy.SUBJECT, id, secretary.id.value) })
        asUser(secretary) { workflows.approve(verifyTask.id, null) }
        val closed = row(id)
        assertThat(closed.status to closed.stepName).isEqualTo(ActionStatus.CLOSED to null)
        assertThat(closed.closedAt).isNotNull()

        // Three rounds of an edit racing an issue on one draft: no deadlock, one outcome each.
        repeat(3) { round ->
            val draft = raise(d.meeting.id, d.appetite, fields(title = "Round $round"))
            val version = row(draft).version
            val pool = Executors.newFixedThreadPool(2)
            val outcomes =
                try {
                    pool
                        .invokeAll(
                            listOf(
                                Callable { runCatching { asUser(secretary) { issuing.issue(draft, version) } } },
                                Callable {
                                    runCatching {
                                        asUser(
                                            secretary,
                                        ) { raising.edit(draft, version, fields(title = "Edited $round")) }
                                    }
                                },
                            ),
                        ).map { it.get() }
                } finally {
                    pool.shutdown()
                }
            assertThat(outcomes.count { it.isSuccess }).describedAs("round $round").isEqualTo(1)
        }
    }

    @Autowired lateinit var userLifecycle: com.memo.identity.UserLifecycle
}
