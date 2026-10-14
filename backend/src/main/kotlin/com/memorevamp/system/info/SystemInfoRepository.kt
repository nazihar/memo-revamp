package com.memorevamp.system.info

import org.jooq.DSLContext
import org.jooq.impl.DSL.field
import org.jooq.impl.DSL.name
import org.jooq.impl.DSL.table
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * Reads the platform marker row. Platform table (no tenant_id).
 * Uses jOOQ's plain-SQL name API until the code-generation pipeline lands (E02).
 */
@Repository
class SystemInfoRepository(
    private val dsl: DSLContext,
) {
    /** Every database access runs in a transaction (AGENTS.md §3.2), reads included. */
    @Transactional(readOnly = true)
    fun schemaMarker(): String? =
        dsl
            .select(SCHEMA_MARKER)
            .from(SYSTEM_INFO)
            .where(ID.eq(1.toShort()))
            .fetchOne(SCHEMA_MARKER)

    companion object {
        const val EXPECTED_MARKER = "memo-revamp"
        private val SYSTEM_INFO = table(name("system_info"))
        private val ID = field(name("id"), Short::class.java)
        private val SCHEMA_MARKER = field(name("schema_marker"), String::class.java)
    }
}
