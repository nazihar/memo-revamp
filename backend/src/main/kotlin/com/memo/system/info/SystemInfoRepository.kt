package com.memo.system.info

import com.memo.jooq.tables.references.SYSTEM_INFO
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/** Reads the platform marker row. Platform table (no tenant_id), so no tenant context is needed. */
@Repository
class SystemInfoRepository(
    private val dsl: DSLContext,
) {
    /** Every database access runs in a transaction (AGENTS.md §3.2), reads included. */
    @Transactional(readOnly = true)
    fun schemaMarker(): String? =
        dsl
            .select(SYSTEM_INFO.SCHEMA_MARKER)
            .from(SYSTEM_INFO)
            .where(SYSTEM_INFO.ID.eq(1.toShort()))
            .fetchOne(SYSTEM_INFO.SCHEMA_MARKER)

    companion object {
        const val EXPECTED_MARKER = "memo"
    }
}
