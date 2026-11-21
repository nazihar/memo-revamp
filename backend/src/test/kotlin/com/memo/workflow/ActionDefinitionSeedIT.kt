package com.memo.workflow

import com.memo.actions.subject.ActionDefinitionRules
import com.memo.identity.MfaSeeding
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.Audit
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTenant
import com.memo.testsupport.adminSession
import com.memo.testsupport.anActiveUser
import com.memo.workflow.internal.DefinitionChecks
import com.memo.workflow.internal.DefinitionInvalidException
import com.memo.workflow.internal.DefinitionLifecycle
import com.memo.workflow.internal.DefinitionReads
import com.memo.workflow.internal.SubjectDefinitionSeeds
import com.memo.workflow.internal.VersionState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.node.ObjectNode

/**
 * The built-in `action-item` definition (design E07 §1.8; test plan T2 `ActionDefinitionSeedIT`): every tenant has it as
 * a published version 1 bound to action items, it passes the engine's checks and the subject's, it is not offered to memo
 * types, seeding again changes nothing, and the tenant changes it as a new version reviewed by a second administrator,
 * the subject's rules applying to the change.
 */
class ActionDefinitionSeedIT : MemoIntegrationTest() {
    @Autowired lateinit var reads: DefinitionReads

    @Autowired lateinit var checks: DefinitionChecks

    @Autowired lateinit var lifecycle: DefinitionLifecycle

    @Autowired lateinit var seeds: SubjectDefinitionSeeds

    @Autowired lateinit var definitions: WorkflowDefinitions

    @Autowired lateinit var mfaSeeding: MfaSeeding

    @Test
    fun `every tenant has the published action-item definition, bound to action items and passing every check`() {
        val (definition, versions) = asUser(admin) { reads.definition(KEY) }
        assertThat(definition.name).isEqualTo("Action items")
        assertThat(definition.builtIn).describedAs("the tenant may change it").isFalse()
        assertThat(definition.fragment).isFalse()
        val v1 = versions.single()
        assertThat(v1.number).isEqualTo(1)
        assertThat(v1.state).isEqualTo(VersionState.PUBLISHED)
        assertThat(v1.publishedAt).isNotNull()
        val body = json.readTree(v1.body)
        assertThat(body["subject"].asString()).isEqualTo("action-item")
        assertThat(body["start"].asString()).isEqualTo("owner")
        val validation = asUser(admin) { checks.validate(body) }
        assertThat(validation.problems).isEmpty()
        assertThat(validation.valid).isTrue()

        assertThat(asUser(admin) { definitions.bound("action-item") }.map { it.key }).containsExactly(KEY)
        assertThat(asUser(admin) { definitions.published("memo") }.map { it.key }).doesNotContain(KEY).contains("sequential-approval")
        val session = mvc.adminSession(tenant, accounts, transactions, mfaSeeding)
        val offered = read("/api/v1/admin/memo-types/definitions", session).json()
        assertThat((0 until offered.size()).map { offered[it]["key"].asString() }).doesNotContain(KEY)
        val shown = read("/api/v1/admin/workflow-definitions/$KEY/versions/1", session).json()
        assertThat(shown["version"]["state"].asString()).isEqualTo("PUBLISHED")
        assertThat(shown["body"]["steps"][1]["assignee"]["slot"].asString()).isEqualTo("verifier")

        // A tenant created later gets it in its creating transaction; seeding again leaves what is there.
        val other = registry.aTenant("actions-seed")
        AppRoleJdbc.inTransaction(other) { c ->
            assertThat(c.long("select count(*) from workflow_definition where key = ?", KEY)).isEqualTo(1)
            assertThat(c.long(PUBLISHED_COMPILED, KEY)).isEqualTo(1)
        }
        asUser(admin) { seeds.ensure() }
        asUser(admin) { seeds.ensure() }
        assertThat(asUser(admin) { reads.definition(KEY) }.second).hasSize(1)
    }

    @Test
    fun `the tenant changes it as a new version reviewed by a second administrator, within the subject's rules`() {
        val checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        val draft = asUser(admin) { lifecycle.newDraft(KEY, null) }
        assertThat(draft.version.number).isEqualTo(2)

        // Rejecting an action is refused by the subject's own rule.
        val rejecting = json.readTree(draft.version.body) as ObjectNode
        (rejecting["steps"][1] as ObjectNode)
            .putArray("actions")
            .add("APPROVE")
            .add("REJECT")
            .add("SEND_BACK")
        val refused = asUser(admin) { lifecycle.replace(KEY, 2, draft.version.revision, rejecting) }
        val problems =
            runCatching { asUser(admin) { lifecycle.submit(KEY, 2, refused.version.revision) } }.exceptionOrNull()
                as DefinitionInvalidException
        assertThat(problems.problems.map { "${it.field} ${it.code}" })
            .containsExactly("/steps/1/actions ${ActionDefinitionRules.NOT_REJECTED}")

        // Reminding the owner 3 working days before instead of 5 is the tenant's to choose.
        val sooner = json.readTree(draft.version.body) as ObjectNode
        ((sooner["steps"][0] as ObjectNode)["sla"]["remind"] as ObjectNode).put("before", "P3D")
        val saved = asUser(admin) { lifecycle.replace(KEY, 2, refused.version.revision, sooner) }
        asUser(admin) { lifecycle.submit(KEY, 2, saved.version.revision) }
        val published = asUser(checker) { lifecycle.approve(KEY, 2, null) }
        assertThat(published.version.state).isEqualTo(VersionState.PUBLISHED)
        Audit.assertAudited(tenant.id, "workflow.definition.published", published.version.id.toString(), checker.id.value.toString())
        val states = asUser(admin) { reads.definition(KEY) }.second.associate { it.number to it.state }
        assertThat(states).containsEntry(1, VersionState.RETIRED).containsEntry(2, VersionState.PUBLISHED)
        assertThat(asUser(admin) { definitions.bound("action-item") }.map { it.key }).containsExactly(KEY)
    }

    private companion object {
        const val KEY = "action-item"
        const val PUBLISHED_COMPILED =
            "select count(*) from workflow_definition_version v join workflow_definition d on d.id = v.definition_id " +
                "where d.key = ? and v.state = 'PUBLISHED' and v.compiled is not null"
    }
}
