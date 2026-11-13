package com.memo.workflow

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.identity.MfaSeeding
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.adminSession
import com.memo.workflow.internal.DefinitionLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * The builder's catalogue (design E06 §5, test plan T3): the attributes conditions may read per item type, the tenant's
 * roles and queues, and only published sub-workflows; never `sign`.
 */
class BuilderCatalogueIT : MemoIntegrationTest() {
    @Autowired lateinit var mfaSeeding: MfaSeeding

    @Autowired lateinit var lifecycle: DefinitionLifecycle

    @Autowired lateinit var seeding: WorkflowSeeding

    @Test
    fun `the catalogue lists attributes, roles, queues and published fragments only`() {
        val session = mvc.adminSession(tenant, accounts, transactions, mfaSeeding)
        act(admin.id.value) { seeding.queue("credit-desk", "Credit desk", listOf(admin.id.value)) }
        fragment("checks", "Compliance checks", publish = true)
        fragment("draft-only", "Not published yet", publish = false)

        val catalogue = read("/api/v1/admin/workflow-builder/catalogue", session).json()
        val memo = catalogue["subjectTypes"].toList().first { it["type"].asString() == "memo" }
        assertThat(memo["attributes"].toList().associate { it["name"].asString() to it["type"].asString() })
            .containsEntry("classificationRank", "int")
            .containsEntry("memoTypeId", "string")
        assertThat(catalogue["roles"].toList().map { it["name"].asString() }).isNotEmpty()
        assertThat(catalogue["queues"].toList().map { it["key"].asString() }).containsExactly("credit-desk")
        assertThat(catalogue["fragments"].toList().map { it["key"].asString() }).containsExactly("checks")
        val types = catalogue["stepTypes"].toList().map { it.asString() }
        assertThat(types).contains("approve", "branch", "doa").doesNotContain("sign")
    }

    private fun fragment(
        key: String,
        name: String,
        publish: Boolean,
    ) {
        val body =
            json.readTree(
                """
                {"schemaVersion":2,"start":"check","steps":[
                  {"id":"check","type":"approve","name":"Check","assignee":{"kind":"person","userId":"${admin.id.value}"},
                   "actions":["APPROVE","RETURN"],"next":"end"}]}
                """.trimIndent(),
            )
        val draft = act(admin.id.value) { lifecycle.create(key, name, null, true, body) }
        if (!publish) return
        act(admin.id.value) { lifecycle.submit(key, 1, draft.version.revision) }
        act(UUID.randomUUID()) { lifecycle.approve(key, 1, null) }
    }

    private fun <T> act(
        user: UUID,
        block: () -> T,
    ): T =
        ActorContext.call(ActorScope(Actor.User(user), null)) {
            TenantContext.call(tenant.id) { transactions.inTenant(tenant.id, block) }
        }
}
