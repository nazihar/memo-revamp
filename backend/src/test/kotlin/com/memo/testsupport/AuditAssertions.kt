package com.memo.testsupport

import com.memo.audit.AuditChainVerifier
import com.memo.audit.AuditColumns
import com.memo.audit.AuditVerification
import com.memo.audit.ChainPoint
import com.memo.audit.StoredAuditEntry
import com.memo.common.tenant.TenantId
import org.assertj.core.api.Assertions.assertThat
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/** An audit row as tests read it (through the app role, inside the tenant, like production reads). */
data class AuditRow(
    val seq: Long,
    val action: String,
    val objectType: String,
    val objectId: String?,
    val actorType: String,
    val actorId: String?,
    val correlationId: String?,
    val canonical: String,
)

private const val SELECT_ENTRIES = """
    select seq, canonical, prev_hash, hash, tenant_id, occurred_at, actor_type, actor_id, acting_as_id, client_ip,
           client_user_agent, correlation_id, action, object_type, object_id
    from audit_event order by seq
"""

private fun ResultSet.toStored() =
    StoredAuditEntry(
        seq = getLong("seq"),
        canonical = getString("canonical"),
        prevHash = getBytes("prev_hash"),
        hash = getBytes("hash"),
        columns =
            AuditColumns(
                tenantId = getObject("tenant_id", java.util.UUID::class.java),
                occurredAt = getObject("occurred_at", java.time.OffsetDateTime::class.java).toInstant(),
                actorType = getString("actor_type"),
                actorId = getString("actor_id"),
                actingAsId = getString("acting_as_id"),
                clientIp = getString("client_ip"),
                clientUserAgent = getString("client_user_agent"),
                correlationId = getString("correlation_id"),
                action = getString("action"),
                objectType = getString("object_type"),
                objectId = getString("object_id"),
            ),
    )

/** Shared audit helpers for every module's tests (TESTING_STRATEGY §3.5 `assertAudited`). */
object Audit {
    fun storedEntries(tenant: TenantId): List<StoredAuditEntry> =
        AppRoleJdbc.inTransaction(tenant) { c -> c.query(SELECT_ENTRIES) { it.toStored() } }

    fun entries(tenant: TenantId): List<AuditRow> =
        storedEntries(tenant).map {
            AuditRow(
                it.seq,
                it.columns.action,
                it.columns.objectType,
                it.columns.objectId,
                it.columns.actorType,
                it.columns.actorId,
                it.columns.correlationId,
                it.canonical,
            )
        }

    fun assertAudited(
        tenant: TenantId,
        action: String,
        objectId: String?,
        actorId: String? = null,
    ): AuditRow {
        val matches = entries(tenant).filter { it.action == action && it.objectId == objectId }
        assertThat(matches).describedAs("audit entries for $action on $objectId").isNotEmpty()
        if (actorId != null) assertThat(matches.last().actorId).isEqualTo(actorId)
        return matches.last()
    }

    /** Verifies the tenant's whole chain and that the head agrees with the last entry. */
    fun assertChainValid(tenant: TenantId) {
        val result = AuditChainVerifier.verify(tenant.value, ChainPoint.genesis(tenant.value), storedEntries(tenant).asSequence())
        assertThat(result).describedAs("chain verification").isInstanceOf(AuditVerification.Ok::class.java)
        val head = AppRoleJdbc.inTransaction(tenant) { c -> c.query("select last_seq from audit_chain_head") { it.getLong(1) } }.single()
        assertThat((result as AuditVerification.Ok).last.seq).isEqualTo(head)
    }
}

fun <T> Connection.query(
    sql: String,
    vararg params: Any,
    map: (ResultSet) -> T,
): List<T> =
    prepareStatement(sql).use { st ->
        params.forEachIndexed { i, p -> st.setObject(i + 1, p) }
        st.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
    }

/** The schema owner, for tamper tests and catalog checks only (never used by application code). */
object OwnerJdbc {
    fun <T> connect(block: (Connection) -> T): T =
        DriverManager.getConnection(TestDatabase.jdbcUrl, "memo_migrator", TestDatabase.migratorPassword).use(block)
}
