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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment

/** The demo workflow (test plan E03 T11): published through maker-checker, chosen by a FIXED memo type, seeded once. */
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
        val seeder = DemoWorkflowSeeder(accounts, workflowSeeding, memoSeeding, transactions)
        seeder.seed()
        seeder.seed()

        val admin = transactions.inTenant(DemoTenant.ID) { accounts.findByEmail(DemoWorkflowSeeder.ADMIN)!!.id.value }
        ActorContext.call(ActorScope(Actor.User(admin), null)) {
            TenantContext.call(DemoTenant.ID) {
                transactions.inTenant(DemoTenant.ID) {
                    val choice = definitions.choice(DemoWorkflowSeeder.KEY)
                    assertThat(choice?.routed).isFalse()
                    val typed = types.all().filter { it.name == DemoWorkflowSeeder.NAME }
                    assertThat(typed).hasSize(1)
                    assertThat(typed.single().definitionKey).isEqualTo(DemoWorkflowSeeder.KEY)
                }
            }
        }
    }
}
