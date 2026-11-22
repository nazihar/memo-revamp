package com.memo.search

import com.memo.testsupport.TestDatabase
import java.sql.DriverManager
import java.util.UUID

/**
 * Reads `search_decision` as its owner (the application role holds no privilege on it, ADR-0054): the register's tests
 * check what a row holds without going through the reader function.
 */
object RegisterTable {
    /** [decisionId]'s row of [tenant] by column name; null when there is none. */
    fun row(
        tenant: UUID,
        decisionId: UUID,
    ): Map<String, Any?>? = rows(tenant, "decision_id = ?::uuid", decisionId.toString()).singleOrNull()

    /** The rows of [tenant] matching [where] (with `?` for [args]). */
    fun rows(
        tenant: UUID,
        where: String = "true",
        vararg args: String,
    ): List<Map<String, Any?>> =
        DriverManager.getConnection(TestDatabase.jdbcUrl, "memo_migrator", TestDatabase.migratorPassword).use { c ->
            c.prepareStatement("select * from search_decision where tenant_id = ?::uuid and ($where) order by decision_id").use { st ->
                st.setString(1, tenant.toString())
                args.forEachIndexed { i, a -> st.setString(i + 2, a) }
                st.executeQuery().use { rs ->
                    val meta = rs.metaData
                    buildList {
                        while (rs.next()) add((1..meta.columnCount).associate { meta.getColumnName(it) to rs.getObject(it) })
                    }
                }
            }
        }
}
