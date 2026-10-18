package com.memo.audit.internal

import org.flywaydb.core.api.callback.Callback
import org.flywaydb.core.api.callback.Context
import org.flywaydb.core.api.callback.Event
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Creates monthly audit partitions ahead of time on every migration run (ADR-0019). Runs on Flyway's migrator connection,
 * because the app role has no DDL: in local dev at startup, and in the deploy-time migrate job (ADR-0016).
 */
@Component
internal class AuditPartitionCallback(
    @param:Value("\${memo.audit.partitions-ahead:12}") private val monthsAhead: Int,
) : Callback {
    override fun supports(
        event: Event,
        context: Context?,
    ): Boolean = event == Event.AFTER_MIGRATE

    override fun canHandleInTransaction(
        event: Event,
        context: Context?,
    ): Boolean = true

    override fun handle(
        event: Event,
        context: Context,
    ) {
        ensurePartitions(context.connection, monthsAhead)
    }

    override fun getCallbackName(): String = "auditPartitions"

    companion object {
        /** Returns the number of partitions created; 0 before the audit module's migration has run. */
        fun ensurePartitions(
            connection: java.sql.Connection,
            monthsAhead: Int,
        ): Int {
            val exists =
                connection.prepareStatement("select to_regprocedure('audit_ensure_partitions(integer)') is not null").use { st ->
                    st.executeQuery().use { it.next() && it.getBoolean(1) }
                }
            if (!exists) return 0
            return connection.prepareStatement("select audit_ensure_partitions(?)").use { st ->
                st.setInt(1, monthsAhead)
                st.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }
    }
}
