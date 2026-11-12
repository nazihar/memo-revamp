package com.memo.workflow

import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.workflow.internal.DefinitionLifecycle
import com.memo.workflow.internal.DefinitionVersions
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper

/**
 * Definitions for seeders (the demo tenant, test plan E03 T11), through the same lifecycle as the API: written and
 * submitted by one person, approved by another, as the user bound to the current unit of work. Never called from
 * requests. Each call is its own tenant transaction.
 */
@Service
class WorkflowSeeding(
    private val versions: DefinitionVersions,
    private val lifecycle: DefinitionLifecycle,
    private val transactions: TenantTransactions,
    private val json: JsonMapper,
) {
    /** Whether the tenant has a definition called [key]. */
    fun exists(key: String): Boolean = tx { versions.definition(key) != null }

    /** [body] as the first version of a new definition [key], submitted for review by the current user. */
    fun submit(
        key: String,
        name: String,
        body: String,
    ) {
        tx {
            val draft = lifecycle.create(key, name, null, false, json.readTree(body))
            lifecycle.submit(key, draft.version.number, draft.version.revision)
        }
    }

    /** Publishes the version of [key] in review, approved by the current user (not the one who submitted it). */
    fun approve(
        key: String,
        number: Int = 1,
    ) {
        tx { lifecycle.approve(key, number, null) }
    }

    private fun <T> tx(block: () -> T): T = transactions.inTenant(TenantContext.current(), block)
}
