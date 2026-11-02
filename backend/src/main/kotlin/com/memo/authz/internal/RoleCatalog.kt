package com.memo.authz.internal

import com.memo.common.authz.BuiltInRole
import com.memo.common.authz.Permission
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.AUTHZ_GRANT
import com.memo.jooq.tables.references.AUTHZ_ROLE
import com.memo.jooq.tables.references.AUTHZ_ROLE_PERMISSION
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class Role(
    val id: UUID,
    /** Built-in role, or null for a custom one. */
    val builtIn: BuiltInRole?,
    val name: String,
    val description: String?,
    val permissions: Set<Permission>,
    val grants: Int,
    val version: Long,
)

/** Roles with their permissions (ADR-0027). Every method runs inside a tenant transaction. */
@Repository
class RoleCatalog(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    fun all(): List<Role> {
        val dsl = tenantDsl.current()
        val counts =
            dsl
                .select(AUTHZ_GRANT.ROLE_ID, DSL.count())
                .from(AUTHZ_GRANT)
                .groupBy(AUTHZ_GRANT.ROLE_ID)
                .fetchMap({ it.value1()!! }, { it.value2() })
        val permissions = permissionsOfCustomRoles()
        return dsl
            .selectFrom(AUTHZ_ROLE)
            .orderBy(AUTHZ_ROLE.KEY.isNull, DSL.lower(AUTHZ_ROLE.NAME))
            .fetch { r ->
                val builtIn = r.key?.let { BuiltInRole.ofKey(it) }
                Role(
                    r.id,
                    builtIn,
                    r.name,
                    r.description,
                    builtIn?.permissions ?: permissions[r.id].orEmpty(),
                    counts[r.id] ?: 0,
                    r.version!!,
                )
            }
    }

    fun find(id: UUID): Role? = all().firstOrNull { it.id == id }

    fun nameTaken(
        name: String,
        except: UUID?,
    ): Boolean =
        tenantDsl.current().fetchExists(
            AUTHZ_ROLE,
            DSL.lower(AUTHZ_ROLE.NAME).eq(name.lowercase()),
            except?.let { AUTHZ_ROLE.ID.ne(it) } ?: DSL.noCondition(),
        )

    fun insert(
        name: String,
        description: String?,
        permissions: Set<Permission>,
    ): UUID {
        val id = ids.next()
        val now = now()
        tenantDsl
            .current()
            .insertInto(AUTHZ_ROLE)
            .set(AUTHZ_ROLE.ID, id)
            .set(AUTHZ_ROLE.TENANT_ID, tenantDsl.tenant().value)
            .set(AUTHZ_ROLE.NAME, name)
            .set(AUTHZ_ROLE.DESCRIPTION, description)
            .set(AUTHZ_ROLE.CREATED_AT, now)
            .set(AUTHZ_ROLE.UPDATED_AT, now)
            .execute()
        writePermissions(id, permissions)
        return id
    }

    /** Guarded update of a custom role on [expectedVersion]; false when it moved on (or is built-in). */
    fun update(
        id: UUID,
        expectedVersion: Long,
        name: String,
        description: String?,
        permissions: Set<Permission>,
    ): Boolean {
        val updated =
            tenantDsl
                .current()
                .update(AUTHZ_ROLE)
                .set(AUTHZ_ROLE.NAME, name)
                .set(AUTHZ_ROLE.DESCRIPTION, description)
                .set(AUTHZ_ROLE.VERSION, AUTHZ_ROLE.VERSION.plus(1))
                .set(AUTHZ_ROLE.UPDATED_AT, now())
                .where(AUTHZ_ROLE.ID.eq(id), AUTHZ_ROLE.VERSION.eq(expectedVersion), AUTHZ_ROLE.KEY.isNull)
                .execute() == 1
        if (updated) {
            tenantDsl
                .current()
                .deleteFrom(AUTHZ_ROLE_PERMISSION)
                .where(AUTHZ_ROLE_PERMISSION.ROLE_ID.eq(id))
                .execute()
            writePermissions(id, permissions)
        }
        return updated
    }

    /** Deletes an unused custom role on [expectedVersion]; false when it moved on. */
    fun delete(
        id: UUID,
        expectedVersion: Long,
    ): Boolean {
        val dsl = tenantDsl.current()
        dsl.deleteFrom(AUTHZ_ROLE_PERMISSION).where(AUTHZ_ROLE_PERMISSION.ROLE_ID.eq(id)).execute()
        return dsl
            .deleteFrom(AUTHZ_ROLE)
            .where(AUTHZ_ROLE.ID.eq(id), AUTHZ_ROLE.VERSION.eq(expectedVersion), AUTHZ_ROLE.KEY.isNull)
            .execute() == 1
    }

    private fun writePermissions(
        role: UUID,
        permissions: Set<Permission>,
    ) {
        val dsl = tenantDsl.current()
        permissions.forEach {
            dsl
                .insertInto(AUTHZ_ROLE_PERMISSION)
                .set(AUTHZ_ROLE_PERMISSION.TENANT_ID, tenantDsl.tenant().value)
                .set(AUTHZ_ROLE_PERMISSION.ROLE_ID, role)
                .set(AUTHZ_ROLE_PERMISSION.PERMISSION, it.key)
                .execute()
        }
    }

    private fun permissionsOfCustomRoles(): Map<UUID, Set<Permission>> =
        tenantDsl
            .current()
            .select(AUTHZ_ROLE_PERMISSION.ROLE_ID, AUTHZ_ROLE_PERMISSION.PERMISSION)
            .from(AUTHZ_ROLE_PERMISSION)
            .fetch()
            .mapNotNull { row -> Permission.ofKey(row.value2()!!)?.let { row.value1()!! to it } }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
}
