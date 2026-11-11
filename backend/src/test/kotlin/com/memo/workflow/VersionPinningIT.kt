package com.memo.workflow

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.jooq.tables.references.WORKFLOW_INSTANCE
import com.memo.tenancy.TenantRegistry
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestWorkflowSubject
import com.memo.testsupport.aTenant
import com.memo.workflow.internal.DefinitionLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Version pinning (ADR-0038, test plan T7): an instance keeps the version it started on when a newer one is published,
 * and walks that version's steps; instances started afterwards use the new one.
 */
class VersionPinningIT : IntegrationTest() {
    @Autowired lateinit var workflows: Workflows

    @Autowired lateinit var lifecycle: DefinitionLifecycle

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var tenantDsl: TenantDsl

    @Autowired lateinit var json: JsonMapper

    private var tenant = TenantId(UUID(0, 0))
    private val maker = UUID.randomUUID()
    private val checker = UUID.randomUUID()
    private val initiator = UUID.randomUUID()
    private val first = UUID.randomUUID()
    private val second = UUID.randomUUID()
    private val reviewer = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        tenant = registry.aTenant("pinning")
    }

    @Test
    fun `an instance stays on the version it started on, and new instances take the newer one`() {
        val v1 = act(maker) { lifecycle.create("pinned", "Pinned", null, false, json.readTree(ROUTED)) }
        act(maker) { lifecycle.submit("pinned", 1, v1.version.revision) }
        act(checker) { lifecycle.approve("pinned", 1, null) }
        val old =
            act(initiator) { workflows.start(TestWorkflowSubject.TYPE, UUID.randomUUID(), listOf(first, second), definitionKey = "pinned") }

        val v2 = act(maker) { lifecycle.newDraft("pinned", json.readTree(reviewed())) }
        act(maker) { lifecycle.submit("pinned", 2, v2.version.revision) }
        act(checker) { lifecycle.approve("pinned", 2, null) }
        val new =
            act(initiator) { workflows.start(TestWorkflowSubject.TYPE, UUID.randomUUID(), listOf(first, second), definitionKey = "pinned") }

        assertThat(pinned(old.instanceId)).isEqualTo(v1.version.id)
        assertThat(pinned(new.instanceId)).isEqualTo(v2.version.id)
        assertThat(new.currentAssignees).containsExactly(reviewer)
        // The old instance walks version 1: the route continues, no review step appears.
        val next = act(first) { workflows.approve(old.openTaskId!!) }
        assertThat(next.currentAssignees).containsExactly(second)
        assertThat(act(second) { workflows.approve(next.openTaskId!!) }.status).isEqualTo(InstanceStatus.APPROVED)
    }

    private fun pinned(instanceId: UUID): UUID? =
        act(initiator) {
            tenantDsl
                .current()
                .select(WORKFLOW_INSTANCE.DEFINITION_VERSION_ID)
                .from(WORKFLOW_INSTANCE)
                .where(WORKFLOW_INSTANCE.ID.eq(instanceId))
                .fetchSingle(WORKFLOW_INSTANCE.DEFINITION_VERSION_ID)
        }

    private fun reviewed() =
        """{"schemaVersion":2,"start":"review","steps":[
          {"id":"review","type":"review","assignee":{"kind":"person","userId":"$reviewer"},"next":"route"},
          {"id":"route","type":"route","next":"end"}]}"""

    private fun <T> act(
        user: UUID,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(user), null)) { transactions.inTenant(tenant, block) }

    private companion object {
        const val ROUTED = """{"schemaVersion":2,"start":"route","steps":[{"id":"route","type":"route","next":"end"}]}"""
    }
}
