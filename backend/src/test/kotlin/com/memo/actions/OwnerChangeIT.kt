package com.memo.actions

import com.memo.actions.internal.ActionPriority
import com.memo.actions.internal.ActionStatus
import com.memo.actions.internal.OwnerKind
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.Audit
import com.memo.testsupport.query
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Who an issued action names (test plan E07 T4 `OwnerChangeIT`; design E07 §1.3, §3): a person owner to a position and
 * back, the owner's task moving with it (the slot resolved again), both people told; edits after issue with a reason,
 * the level only up, the contributors added and removed, and a contributor added while it waits for verification left
 * out of the verifier pool.
 */
class OwnerChangeIT : ActionFixture() {
    override fun label() = "actowner"

    @Test
    fun `a new owner gets the owner's task, a position's holder included, and both people are told`() {
        val action = issued(owner = person(director2))
        val id = action.id
        assertThat(taskOf(id, director2)).isNotNull()

        asUser(secretary) { ownership.changeOwner(id, row(id).version, position(), "Treasury leads the valuation now") }
        val toPosition = row(id)
        assertThat(toPosition.owner?.kind to toPosition.holderUserId).isEqualTo(OwnerKind.POSITION to treasurer.id.value)
        assertThat(taskOf(id, treasurer)).isNotNull()
        assertThat(taskOf(id, director2)).isNull()
        assertThat(toPosition.stepName).isEqualTo("Owner")
        assertThat(timeline(id).first()).isEqualTo("OWNER_CHANGED")
        val audited = Audit.assertAudited(tenant.id, "actions.action.owner_changed", id.toString(), secretary.id.value.toString())
        assertThat(audited.canonical).contains(headOfTreasury.toString(), "reasonSha256").doesNotContain("Treasury leads")
        // Both people are told, in the app and by email, with the reference only.
        listOf(director2, treasurer).forEach { person ->
            assertThat(items(person, "ACTION_OWNER_CHANGED")).describedAs(person.email).singleElement().satisfies({
                assertThat(it).contains(checkNotNull(action.reference)).doesNotContain("collateral")
            })
        }
        val mails =
            AppRoleJdbc.inTransaction(tenant.id) { c ->
                c.query("select to_address from notification_mail_outbox where template = 'ACTION_OWNER_CHANGED'") { it.getString(1) }
            }
        assertThat(mails).containsExactlyInAnyOrder(director2.email, treasurer.email)
        // The new owner sees it; the previous one, a member of the committee, still does by the item rule.
        assertThat(seen(id, treasurer)?.owner?.position?.title).isEqualTo("Head of Treasury")

        // Back to a person.
        asUser(secretary) { ownership.changeOwner(id, row(id).version, person(others[1]), "Back to the risk team") }
        assertThat(row(id).holderUserId).isEqualTo(others[1].id.value)
        assertThat(taskOf(id, others[1])).isNotNull()
        assertThat(taskOf(id, treasurer)).isNull()

        // The checks: someone else, with a reason, not a contributor; only an officer changes it, never the owner.
        assertThat(outcome(secretary) { ownership.changeOwner(id, row(id).version, person(others[1]), "Same") }).isEqualTo("422 SAME_OWNER")
        assertThat(outcome(secretary) { ownership.changeOwner(id, row(id).version, person(director), null) }).isEqualTo("422 REQUIRED")
        assertThat(outcome(others[1]) { ownership.changeOwner(id, row(id).version, person(director), "Passing it on") }).isEqualTo("403")
        assertThat(outcome(chair) { ownership.changeOwner(id, row(id).version, person(director), "Chair's call") }).isEqualTo("403")
    }

    @Test
    fun `an issued action is edited with a reason, its level only up, its contributors added and removed`() {
        val id = issued(contributors = listOf(others[0].id.value)).id
        val current = row(id)
        val fields = fields(title = "Report the revised valuation", owner = person(director2), dueDate = current.dueDate)
        assertThat(outcome(secretary) { raising.edit(id, row(id).version, fields, null) }).isEqualTo("422 REQUIRED")
        assertThat(outcome(secretary) { raising.edit(id, row(id).version, fields.copy(dueDate = due.plusDays(3)), "x") })
            .isEqualTo("422 NOT_EDITABLE")
        assertThat(outcome(secretary) { raising.edit(id, row(id).version, fields.copy(owner = person(others[1])), "x") })
            .isEqualTo("422 NOT_EDITABLE")

        val edited =
            asUser(secretary) {
                raising.edit(
                    id,
                    row(id).version,
                    fields.copy(contributors = listOf(others[1].id.value), priority = ActionPriority.HIGH),
                    "The committee asked for the whole valuation",
                )
            }
        assertThat(edited.title to edited.priority).isEqualTo("Report the revised valuation" to ActionPriority.HIGH)
        assertThat(transactions.readInTenant(tenant.id) { actionLines.contributorsOf(id) }).containsExactly(others[1].id.value)
        assertThat(timeline(id).first()).isEqualTo("EDITED")
        val audited = Audit.assertAudited(tenant.id, "actions.action.edited", id.toString(), secretary.id.value.toString())
        assertThat(audited.canonical).contains("reasonSha256", "titleSha256").doesNotContain("whole valuation")
        Audit.assertAudited(tenant.id, "actions.action.contributor_added", id.toString())
        Audit.assertAudited(tenant.id, "actions.action.contributor_removed", id.toString())

        // The level goes up, never down.
        val confidential = levels()[1]
        val lowest = levels()[0]
        asUser(admin) { access.ensureClearance(secretary.id.value, confidential.rank) }
        asUser(admin) { access.ensureClearance(director2.id.value, confidential.rank) }
        asUser(admin) { access.ensureClearance(others[1].id.value, confidential.rank) }
        val raised = fields.copy(contributors = listOf(others[1].id.value), classificationId = confidential.id)
        asUser(secretary) { raising.edit(id, row(id).version, raised, "Now confidential") }
        assertThat(row(id).classificationId).isEqualTo(confidential.id)
        assertThat(outcome(secretary) { raising.edit(id, row(id).version, raised.copy(classificationId = lowest.id), "Lower") })
            .isEqualTo("422 CLASSIFICATION")
        assertThat(outcome(director2) { raising.edit(id, row(id).version, raised, "Mine") }).isEqualTo("403")
    }

    @Test
    fun `a contributor added while the action waits for verification is left out of the verifier pool`() {
        val id = issued(owner = person(director2)).id
        val ownerTask = checkNotNull(taskOf(id, director2))
        asUser(director2) { workflows.approve(ownerTask.id, "Valuation attached") }
        assertThat(row(id).status).isEqualTo(ActionStatus.DONE_PENDING)
        assertThat(taskOf(id, secretary)).isNotNull()
        val fields = fields(owner = person(director2), dueDate = row(id).dueDate, contributors = listOf(secretary.id.value))
        // The secretary verifying becomes a contributor: the pool is drawn again, without them (design E07 §1.9).
        val secretary2 = secondSecretary()
        asUser(secretary2) { raising.edit(id, row(id).version, fields, "The secretary drafted the report") }
        assertThat(taskOf(id, secretary)).isNull()
        assertThat(taskOf(id, secretary2)).isNotNull()
    }
}
