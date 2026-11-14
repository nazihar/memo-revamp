package com.memo.demo

import com.memo.common.authz.AccessAdministration
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.MfaSeeding
import com.memo.identity.UserAccounts
import com.memo.identity.operator.OperatorMfa
import com.memo.memo.MemoSeeding
import com.memo.memo.internal.MemoTypes
import com.memo.org.OrgSeeding
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.aPassword
import com.memo.workflow.WorkflowDefinitions
import com.memo.workflow.WorkflowSeeding
import com.memo.workflow.internal.DefinitionVersions
import com.memo.workflow.internal.DoaLifecycle
import com.memo.workflow.internal.Queues
import com.memo.workflow.internal.VersionState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import java.util.UUID

/**
 * The demo workflows (test plan E03 T11, E04 T10): published through maker-checker, chosen by FIXED memo types, with the
 * credit desk queue and a published DoA, seeded once. */
class DemoWorkflowsIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var environment: Environment

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var mfaSeeding: MfaSeeding

    @Autowired lateinit var access: AccessAdministration

    @Autowired lateinit var orgSeeding: OrgSeeding

    @Autowired lateinit var operators: OperatorMfa

    @Autowired lateinit var workflowSeeding: WorkflowSeeding

    @Autowired lateinit var memoSeeding: MemoSeeding

    @Autowired lateinit var definitions: WorkflowDefinitions

    @Autowired lateinit var types: MemoTypes

    @Autowired lateinit var doa: DoaLifecycle

    @Autowired lateinit var queues: Queues

    @Autowired lateinit var versions: DefinitionVersions

    @Test
    fun `the credit facility workflow is published and chosen by its memo type, once`() {
        DemoTenantSeeder(
            registry,
            settings,
            transactions,
            environment,
            accounts,
            aPassword(),
            mfaSeeding,
            "",
            access,
            orgSeeding,
            operators,
        ).seed()
        val admin = transactions.inTenant(DemoTenant.ID) { accounts.findByEmail(DemoWorkflowSeeder.ADMIN)!!.id.value }
        val upgraded = seededBeforeSlas(admin)
        val seeder = DemoWorkflowSeeder(accounts, workflowSeeding, memoSeeding, transactions)
        seeder.seed()
        seeder.seed()

        ActorContext.call(ActorScope(Actor.User(admin), null)) {
            TenantContext.call(DemoTenant.ID) {
                transactions.inTenant(DemoTenant.ID) {
                    // E05 T8: every demo definition has its deadlines; the one seeded without them got a version 2, once.
                    for (key in listOf(DemoWorkflowSeeder.KEY, DemoWorkflowSeeder.AUTHORITY_KEY)) {
                        val all = versions.versions(listOf(versions.definition(key)!!.id))
                        val published = all.single { it.state == VersionState.PUBLISHED }
                        assertThat(published.body).contains("\"sla\"")
                        if (upgraded && key == DemoWorkflowSeeder.KEY) assertThat(all.map { it.number }).containsExactlyInAnyOrder(1, 2)
                    }
                    val choice = definitions.choice(DemoWorkflowSeeder.KEY)
                    assertThat(choice?.routed).isFalse()
                    val typed = types.all().filter { it.name == DemoWorkflowSeeder.NAME }
                    assertThat(typed).hasSize(1)
                    assertThat(typed.single().definitionKey).isEqualTo(DemoWorkflowSeeder.KEY)
                    // E04 T10: the credit desk queue, the authority definition and a published DoA, once.
                    assertThat(definitions.choice(DemoWorkflowSeeder.AUTHORITY_KEY)?.routed).isFalse()
                    assertThat(types.all().filter { it.name == DemoWorkflowSeeder.AUTHORITY_NAME }).hasSize(1)
                    assertThat(doa.list().map { it.state }).containsExactly(VersionState.PUBLISHED)
                    assertThat(queues.find(DemoWorkflowSeeder.DESK)?.members).hasSize(2)
                    // Phase 3 E01 T8: representative bank memo types, each published once with its fields and numbering.
                    for ((name, series) in REPRESENTATIVE) {
                        val type = types.all().single { it.name == name }
                        val version = checkNotNull(types.published(type.id)).settings
                        assertThat(version.fields).describedAs(name).isNotEmpty()
                        assertThat(version.numbering.series).isEqualTo(series)
                        assertThat(type.publishedVersion).describedAs("$name published once").isEqualTo(2)
                    }
                }
            }
        }
    }

    /**
     * A stack seeded before E05: "Credit facility" version 1 published without deadlines, as the demo admin and the org
     * admin would have. Whether it was seeded here (false when an earlier test seeded the demo workflows already).
     */
    private fun seededBeforeSlas(admin: UUID): Boolean {
        val reviewer = transactions.inTenant(DemoTenant.ID) { accounts.findByEmail(DemoWorkflowSeeder.REVIEWER)!!.id.value }
        val head = transactions.inTenant(DemoTenant.ID) { accounts.findByEmail(DemoWorkflowSeeder.HEAD_CREDIT)!!.id.value }

        fun <T> asUser(
            user: UUID,
            block: () -> T,
        ): T = ActorContext.call(ActorScope(Actor.User(user), null)) { TenantContext.call(DemoTenant.ID, block) }
        if (asUser(admin) { workflowSeeding.exists(DemoWorkflowSeeder.KEY) }) return false
        val body =
            """
            {"schemaVersion":2,"start":"review","steps":[
              {"id":"review","type":"review","name":"Credit review","assignee":{"kind":"person","userId":"$head"},
               "actions":["RECOMMEND","RETURN"],"next":"end"}]}
            """.trimIndent()
        asUser(admin) { workflowSeeding.submit(DemoWorkflowSeeder.KEY, DemoWorkflowSeeder.NAME, body) }
        asUser(reviewer) { workflowSeeding.approve(DemoWorkflowSeeder.KEY) }
        return true
    }

    private companion object {
        val REPRESENTATIVE =
            listOf(
                "Credit proposal" to "credit",
                "Expense approval" to "expense",
                "Procurement" to "procurement",
                "Policy approval" to "policy",
            )
    }
}
