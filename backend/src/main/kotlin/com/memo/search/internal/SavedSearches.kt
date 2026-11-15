package com.memo.search.internal

import com.memo.audit.AuditLog
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.jooq.tables.records.SearchSavedRecord
import com.memo.jooq.tables.references.SEARCH_SAVED
import org.jooq.JSONB
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** A saved search (flow H3): its name and criteria, never results. Private to its owner. */
data class SavedSearch(
    val id: UUID,
    val name: String,
    val query: SearchQuery,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Not there, or someone else's: the same 404 either way. */
class SavedSearchNotFoundException : RuntimeException("saved search not found")

/** `search_saved`, always narrowed to the owner. Every method runs inside a tenant transaction. */
@Repository
class SavedSearchStore(
    private val tenantDsl: TenantDsl,
    private val json: JsonMapper,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    fun list(owner: UUID): List<SavedSearch> =
        tenantDsl
            .current()
            .selectFrom(SEARCH_SAVED)
            .where(SEARCH_SAVED.OWNER_ID.eq(owner))
            .orderBy(SEARCH_SAVED.NAME, SEARCH_SAVED.ID)
            .fetch(::row)

    fun find(
        owner: UUID,
        id: UUID,
    ): SavedSearch? =
        tenantDsl
            .current()
            .selectFrom(SEARCH_SAVED)
            .where(SEARCH_SAVED.ID.eq(id), SEARCH_SAVED.OWNER_ID.eq(owner))
            .fetchOne(::row)

    fun count(owner: UUID): Int = tenantDsl.current().fetchCount(SEARCH_SAVED, SEARCH_SAVED.OWNER_ID.eq(owner))

    fun insert(
        owner: UUID,
        name: String,
        query: SearchQuery,
    ): UUID {
        val id = ids.next()
        val now = now()
        tenantDsl
            .current()
            .insertInto(SEARCH_SAVED)
            .set(SEARCH_SAVED.ID, id)
            .set(SEARCH_SAVED.TENANT_ID, tenantDsl.tenant().value)
            .set(SEARCH_SAVED.OWNER_ID, owner)
            .set(SEARCH_SAVED.NAME, name)
            .set(SEARCH_SAVED.CRITERIA, criteria(query))
            .set(SEARCH_SAVED.CREATED_AT, now)
            .set(SEARCH_SAVED.UPDATED_AT, now)
            .execute()
        return id
    }

    /** Renames or changes [id], guarded on [version]; false when it changed meanwhile or is not the owner's. */
    fun update(
        owner: UUID,
        id: UUID,
        version: Long,
        name: String,
        query: SearchQuery,
    ): Boolean =
        tenantDsl
            .current()
            .update(SEARCH_SAVED)
            .set(SEARCH_SAVED.NAME, name)
            .set(SEARCH_SAVED.CRITERIA, criteria(query))
            .set(SEARCH_SAVED.VERSION, SEARCH_SAVED.VERSION.plus(1))
            .set(SEARCH_SAVED.UPDATED_AT, now())
            .where(SEARCH_SAVED.ID.eq(id), SEARCH_SAVED.OWNER_ID.eq(owner), SEARCH_SAVED.VERSION.eq(version))
            .execute() == 1

    fun delete(
        owner: UUID,
        id: UUID,
        version: Long,
    ): Boolean =
        tenantDsl
            .current()
            .deleteFrom(SEARCH_SAVED)
            .where(SEARCH_SAVED.ID.eq(id), SEARCH_SAVED.OWNER_ID.eq(owner), SEARCH_SAVED.VERSION.eq(version))
            .execute() == 1

    /** The criteria as stored: at most [MAX_CRITERIA_BYTES] (a CHECK in the table). */
    fun criteria(query: SearchQuery): JSONB = JSONB.valueOf(json.writeValueAsString(query))

    private fun row(r: SearchSavedRecord): SavedSearch =
        SavedSearch(
            id = r.id,
            name = r.name,
            query = json.readValue(r.criteria.data(), SearchQuery::class.java),
            version = r.version ?: 0,
            createdAt = r.createdAt.toInstant(),
            updatedAt = r.updatedAt.toInstant(),
        )

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    companion object {
        const val MAX_CRITERIA_BYTES = 4096
    }
}

/**
 * Saved searches of the signed-in person (design E05 §3, flow H3): at most [MAX_PER_PERSON], named 1–100 characters,
 * criteria checked as a search's. Changes are audited. Inside a tenant transaction.
 */
@Service
class SavedSearches(
    private val store: SavedSearchStore,
    private val audit: AuditLog,
) {
    fun list(): List<SavedSearch> = store.list(owner())

    fun create(
        name: String,
        query: SearchQuery,
    ): SavedSearch {
        val owner = owner()
        val clean = check(name, query)
        refuseIf(store.count(owner) >= MAX_PER_PERSON, "SAVED_SEARCH_LIMIT") { "You can keep at most $MAX_PER_PERSON saved searches." }
        val id = store.insert(owner, clean, query)
        audit.record("search.saved", OBJECT_TYPE, id.toString(), after = mapOf("name" to clean))
        return requireNotNull(store.find(owner, id))
    }

    fun update(
        id: UUID,
        version: Long,
        name: String,
        query: SearchQuery,
    ): SavedSearch {
        val owner = owner()
        val before = store.find(owner, id) ?: throw SavedSearchNotFoundException()
        val clean = check(name, query)
        if (!store.update(owner, id, version, clean, query)) throw ConcurrentModificationException("saved search $id", version)
        audit.record("search.updated", OBJECT_TYPE, id.toString(), before = mapOf("name" to before.name), after = mapOf("name" to clean))
        return requireNotNull(store.find(owner, id))
    }

    fun delete(
        id: UUID,
        version: Long,
    ) {
        val owner = owner()
        val before = store.find(owner, id) ?: throw SavedSearchNotFoundException()
        if (!store.delete(owner, id, version)) throw ConcurrentModificationException("saved search $id", version)
        audit.record("search.deleted", OBJECT_TYPE, id.toString(), before = mapOf("name" to before.name))
    }

    private fun check(
        name: String,
        query: SearchQuery,
    ): String {
        val clean = name.trim()
        val problems =
            listOfNotNull(
                FieldProblem("name", "REQUIRED", "Enter a name.").takeIf { clean.isEmpty() },
                FieldProblem("name", "TOO_LONG", "At most $MAX_NAME characters.").takeIf { clean.length > MAX_NAME },
                FieldProblem("criteria", "TOO_LARGE", "The search is too large to save.")
                    .takeIf {
                        store
                            .criteria(query)
                            .data()
                            .toByteArray()
                            .size > SavedSearchStore.MAX_CRITERIA_BYTES
                    },
            ) + SearchQueries.problems(query)
        if (problems.isNotEmpty()) throw SearchRejectedException(problems)
        return clean
    }

    private fun owner(): UUID =
        checkNotNull((ActorContext.current().actor as? Actor.User)?.userId) { "saved searches need a signed-in user" }

    companion object {
        const val MAX_PER_PERSON = 50
        const val MAX_NAME = 100
        const val OBJECT_TYPE = "search.saved"
    }
}
