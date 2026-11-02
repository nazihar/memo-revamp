package com.memo.identity

import com.memo.common.authz.AccessAdministration
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.IDENTITY_MFA
import com.memo.jooq.tables.references.IDENTITY_USER
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** One row of the admin user list. */
data class UserListItem(
    val id: UUID,
    val email: String,
    val displayName: String,
    val status: UserStatus,
    val tenantAdmin: Boolean,
    val mfaEnrolled: Boolean,
    val lastLoginAt: Instant?,
    val version: Long,
)

data class UserPage(
    val items: List<UserListItem>,
    /** Opaque; pass back as `cursor` for the next page, null at the end. */
    val nextCursor: String?,
)

/**
 * The tenant's accounts for administration (design E03 §3): keyset pagination on `(lower(display_name), id)`, served by
 * the `(tenant_id, lower(display_name), id)` index, so page cost does not grow with the offset. Inside a tenant transaction.
 */
@Component
class UserDirectory(
    private val tenantDsl: TenantDsl,
    private val access: AccessAdministration,
) {
    fun page(
        cursor: String?,
        status: UserStatus?,
        query: String?,
        limit: Int = DEFAULT_LIMIT,
    ): UserPage {
        val size = limit.coerceIn(1, MAX_LIMIT)
        val sortName = DSL.lower(IDENTITY_USER.DISPLAY_NAME)
        val after = cursor?.let { decode(it) }
        val search =
            query
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() }
                ?.let { escapeLike(it) + "%" }
        val enrolled =
            DSL.field(
                DSL.exists(
                    DSL
                        .selectOne()
                        .from(IDENTITY_MFA)
                        .where(IDENTITY_MFA.USER_ID.eq(IDENTITY_USER.ID))
                        .and(IDENTITY_MFA.SECRET_ENC.isNotNull),
                ),
            )
        val rows =
            tenantDsl
                .current()
                .select(
                    IDENTITY_USER.ID,
                    IDENTITY_USER.EMAIL,
                    IDENTITY_USER.DISPLAY_NAME,
                    IDENTITY_USER.STATUS,
                    IDENTITY_USER.LAST_LOGIN_AT,
                    IDENTITY_USER.VERSION,
                    sortName.`as`("sort_name"),
                    enrolled.`as`("mfa_enrolled"),
                ).from(IDENTITY_USER)
                .where(status?.let { IDENTITY_USER.STATUS.eq(it.name) } ?: DSL.noCondition())
                .and(search?.let { sortName.like(it, '\\').or(IDENTITY_USER.EMAIL.like(it, '\\')) } ?: DSL.noCondition())
                .and(
                    after?.let { DSL.row(sortName, IDENTITY_USER.ID).gt(DSL.row(DSL.value(it.first), DSL.value(it.second))) }
                        ?: DSL.noCondition(),
                ).orderBy(sortName, IDENTITY_USER.ID)
                .limit(size + 1)
                .fetch()
        val page = rows.take(size)
        val admins = access.tenantAdmins(page.map { it.get(IDENTITY_USER.ID)!! })
        val items = page.map { item(it, admins) }
        val next =
            if (rows.size >
                size
            ) {
                rows[size - 1].let { encode(it.get("sort_name", String::class.java)!!, it.get(IDENTITY_USER.ID)!!) }
            } else {
                null
            }
        return UserPage(items, next)
    }

    private fun item(
        row: org.jooq.Record,
        admins: Set<UUID>,
    ): UserListItem =
        UserListItem(
            id = row.get(IDENTITY_USER.ID)!!,
            email = row.get(IDENTITY_USER.EMAIL)!!,
            displayName = row.get(IDENTITY_USER.DISPLAY_NAME)!!,
            status = UserStatus.valueOf(row.get(IDENTITY_USER.STATUS)!!),
            tenantAdmin = row.get(IDENTITY_USER.ID) in admins,
            mfaEnrolled = row.get("mfa_enrolled", Boolean::class.java) ?: false,
            lastLoginAt = row.get(IDENTITY_USER.LAST_LOGIN_AT)?.toInstant(),
            version = row.get(IDENTITY_USER.VERSION)!!,
        )

    private fun encode(
        name: String,
        id: UUID,
    ): String = Base64.getUrlEncoder().withoutPadding().encodeToString("$id\u0000$name".toByteArray(Charsets.UTF_8))

    private fun decode(cursor: String): Pair<String, UUID> {
        val text = runCatching { String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8) }.getOrNull()
        val id = text?.substringBefore('\u0000')?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        require(text != null && id != null && '\u0000' in text) { "invalid cursor" }
        return text.substringAfter('\u0000') to id
    }

    private fun escapeLike(text: String) = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 100
    }
}
