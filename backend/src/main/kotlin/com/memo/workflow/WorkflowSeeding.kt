package com.memo.workflow

import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.org.OrgDirectory
import com.memo.workflow.internal.DefinitionLifecycle
import com.memo.workflow.internal.DefinitionVersions
import com.memo.workflow.internal.DoaLifecycle
import com.memo.workflow.internal.DoaMember
import com.memo.workflow.internal.DoaRule
import com.memo.workflow.internal.DoaRuleGroup
import com.memo.workflow.internal.QueueMember
import com.memo.workflow.internal.Queues
import com.memo.workflow.internal.VersionState
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** One approval group of a seeded DoA rule: [needed] approvals from the holders of [positions] (design E04 §3). */
data class DoaSeedGroup(
    val needed: Int,
    val positions: List<UUID>,
)

/**
 * Definitions for seeders (the demo tenant, test plan E03 T11), through the same lifecycle as the API: written and
 * submitted by one person, approved by another, as the user bound to the current unit of work. Never called from
 * requests. Each call is its own tenant transaction.
 */
@Service
class WorkflowSeeding(
    private val versions: DefinitionVersions,
    private val lifecycle: DefinitionLifecycle,
    private val queues: Queues,
    private val doa: DoaLifecycle,
    private val directory: OrgDirectory,
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

    /**
     * [body] as version 2 of [key], submitted for review by the current user, when the only version is the published
     * version 1 and [stale] says its body needs it (a seeder's upgrade, E05 T8). Returns the new version's number, or
     * null when nothing was submitted: a person has changed the definition since, or it is up to date.
     */
    fun revise(
        key: String,
        body: String,
        stale: (String) -> Boolean,
    ): Int? =
        tx {
            val definition = versions.definition(key)
            val only = definition?.let { versions.versions(listOf(it.id)).singleOrNull() }
            val seeded = only?.takeIf { it.number == 1 && it.state == VersionState.PUBLISHED }
            if (seeded != null && stale(seeded.body)) {
                val draft = lifecycle.newDraft(key, json.readTree(body))
                lifecycle.submit(key, draft.version.number, draft.version.revision).version.number
            } else {
                null
            }
        }

    /** Publishes the version of [key] in review, approved by the current user (not the one who submitted it). */
    fun approve(
        key: String,
        number: Int = 1,
    ) {
        tx { lifecycle.approve(key, number, null) }
    }

    /** A queue of [people] called [key], unless the tenant has one (design E04 §4.2). */
    fun queue(
        key: String,
        name: String,
        people: List<UUID>,
    ) {
        tx { if (queues.find(key) == null) queues.create(key, name, people.map { QueueMember(userId = it) }) }
    }

    /** Whether the tenant has a DoA version yet. */
    fun doaSeeded(): Boolean = tx { doa.list().isNotEmpty() }

    /**
     * A DoA version taking effect today with one rule, [name], for every [subjectType] item, asking [groups] in order;
     * submitted for review by the current user. Returns the version's number.
     */
    fun submitDoa(
        subjectType: String,
        name: String,
        groups: List<DoaSeedGroup>,
    ): Int =
        tx {
            val members = groups.map { g -> DoaRuleGroup(g.needed, g.positions.map { DoaMember("position", positionId = it) }) }
            val draft = doa.create(directory.today(), listOf(DoaRule(name, subjectType, groups = members)))
            doa.submit(draft.version.number, draft.version.revision).version.number
        }

    /** Publishes DoA version [number], approved by the current user (not the one who submitted it). */
    fun approveDoa(number: Int) {
        tx { doa.approve(number, null) }
    }

    private fun <T> tx(block: () -> T): T = transactions.inTenant(TenantContext.current(), block)
}
