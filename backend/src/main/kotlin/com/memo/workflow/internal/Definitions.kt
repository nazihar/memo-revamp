package com.memo.workflow.internal

import com.memo.common.cache.TenantCache
import com.memo.common.cache.TenantCaches
import com.memo.common.context.ActorContext
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.jooq.tables.references.WORKFLOW_DEFINITION
import com.memo.jooq.tables.references.WORKFLOW_DEFINITION_VERSION
import com.memo.tenancy.TenantCreated
import com.memo.tenancy.TenantRegistry
import com.memo.workflow.definition.Definition
import com.memo.workflow.definition.DefinitionCompiler
import org.jooq.JSONB
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.event.EventListener
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Workflow definitions (ADR-0035). E01 has one built-in definition per tenant, `sequential-approval` v1: the steps come
 * from the initiator's route. E03 adds tenant-authored definitions and their review lifecycle on the same tables.
 */
@Component
class DefinitionStore(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    /** Creates the built-in definitions if missing. Idempotent; inside a tenant transaction. */
    fun ensureBuiltIn() {
        val dsl = tenantDsl.current()
        val tenant = tenantDsl.tenant().value
        val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        dsl
            .insertInto(WORKFLOW_DEFINITION)
            .set(WORKFLOW_DEFINITION.ID, ids.next())
            .set(WORKFLOW_DEFINITION.TENANT_ID, tenant)
            .set(WORKFLOW_DEFINITION.KEY, SEQUENTIAL_APPROVAL)
            .set(WORKFLOW_DEFINITION.NAME, "Sequential approval")
            .set(WORKFLOW_DEFINITION.BUILT_IN, true)
            .set(WORKFLOW_DEFINITION.CREATED_AT, now)
            .onConflictDoNothing()
            .execute()
        val definitionId =
            dsl
                .select(WORKFLOW_DEFINITION.ID)
                .from(WORKFLOW_DEFINITION)
                .where(WORKFLOW_DEFINITION.KEY.eq(SEQUENTIAL_APPROVAL))
                .fetchSingle(WORKFLOW_DEFINITION.ID)
        dsl
            .insertInto(WORKFLOW_DEFINITION_VERSION)
            .set(WORKFLOW_DEFINITION_VERSION.ID, ids.next())
            .set(WORKFLOW_DEFINITION_VERSION.TENANT_ID, tenant)
            .set(WORKFLOW_DEFINITION_VERSION.DEFINITION_ID, definitionId)
            .set(WORKFLOW_DEFINITION_VERSION.NUMBER, 1)
            .set(WORKFLOW_DEFINITION_VERSION.BODY, JSONB.valueOf(SEQUENTIAL_BODY))
            .set(WORKFLOW_DEFINITION_VERSION.SCHEMA_VERSION, 1)
            .set(WORKFLOW_DEFINITION_VERSION.STATE, PUBLISHED)
            .set(WORKFLOW_DEFINITION_VERSION.CREATED_AT, now)
            .onConflictDoNothing()
            .execute()
    }

    /** The newest published version of [key]; instances pin it when they start (ADR-0035). */
    fun publishedVersion(key: String): UUID =
        tenantDsl
            .current()
            .select(WORKFLOW_DEFINITION_VERSION.ID)
            .from(WORKFLOW_DEFINITION_VERSION)
            .join(WORKFLOW_DEFINITION)
            .on(WORKFLOW_DEFINITION.ID.eq(WORKFLOW_DEFINITION_VERSION.DEFINITION_ID))
            .where(WORKFLOW_DEFINITION.KEY.eq(key))
            .and(WORKFLOW_DEFINITION_VERSION.STATE.eq(PUBLISHED))
            .orderBy(WORKFLOW_DEFINITION_VERSION.NUMBER.desc())
            .limit(1)
            .fetchOne(WORKFLOW_DEFINITION_VERSION.ID)
            ?: throw IllegalStateException("no published version of workflow definition '$key'")

    companion object {
        const val SEQUENTIAL_APPROVAL = "sequential-approval"
        private const val PUBLISHED = "PUBLISHED"
        private const val SEQUENTIAL_BODY = """{"kind":"sequential-approval","route":"initiator-defined","resubmit":"restart"}"""
    }
}

/** Gives every tenant the built-in definitions: new tenants in their creating transaction, existing ones at start-up. */
@Component
class WorkflowBootstrap(
    private val definitions: DefinitionStore,
    private val transactions: TenantTransactions,
) {
    @EventListener
    fun onTenantCreated(event: TenantCreated) = transactions.inTenant(TenantId(event.tenantId)) { definitions.ensureBuiltIn() }
}

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class WorkflowBootstrapOnStartup(
    private val definitions: DefinitionStore,
    private val registry: TenantRegistry,
    private val transactions: TenantTransactions,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        ActorContext.asSystem("workflow-bootstrap") {
            registry.listActiveIds().forEach { tenant -> transactions.inTenant(tenant) { definitions.ensureBuiltIn() } }
        }
    }
}

/**
 * Compiled definitions by version id, as instances run them (ADR-0038). Only published versions are pinned, and those
 * never change, so entries need no eviction. Schema-1 bodies (the E01 built-in) read as the sequential definition.
 */
@Component
class CompiledDefinitions(
    private val tenantDsl: TenantDsl,
    private val json: JsonMapper,
    caches: TenantCaches,
) {
    private val cache: TenantCache<UUID, Definition> = caches.register(CACHE)

    fun get(versionId: UUID): Definition =
        cache.get(versionId) {
            val row =
                tenantDsl
                    .current()
                    .select(
                        WORKFLOW_DEFINITION_VERSION.SCHEMA_VERSION,
                        WORKFLOW_DEFINITION_VERSION.BODY,
                        WORKFLOW_DEFINITION_VERSION.COMPILED,
                    ).from(WORKFLOW_DEFINITION_VERSION)
                    .where(WORKFLOW_DEFINITION_VERSION.ID.eq(versionId))
                    .fetchSingle()
            val body = json.readTree((row.value3() ?: row.value2()!!).data())
            DefinitionCompiler.read(body, if (row.value3() != null) Definition.SCHEMA_VERSION else row.value1()!!)
        }

    private companion object {
        const val CACHE = "workflow.definitions"
    }
}
