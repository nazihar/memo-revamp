package com.memo.audit

import com.memo.audit.internal.AuditIntegrity
import com.memo.common.tenant.TenantId
import com.memo.tenancy.TenantRegistry
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.OwnerJdbc
import com.memo.testsupport.aTenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Synthetic volume V5 (nfr.md) and the verification budget (E02 design §10). Nightly only: `-PauditVolume=200000`.
 * The synthetic chain is valid (real canonical form and hashes) and is written in bulk by the owner, bypassing the
 * transactional flush for speed; this is test tooling, never a production path.
 */
@EnabledIfSystemProperty(named = "memo.test.audit-volume", matches = "\\d+")
class AuditVerificationThroughputIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired internal lateinit var integrity: AuditIntegrity

    private val log = LoggerFactory.getLogger(javaClass)

    @Test
    fun `verification keeps up with a large chain`() {
        val volume = System.getProperty("memo.test.audit-volume").toLong()
        val a = registry.aTenant("volume")
        generate(a, volume)
        val started = System.nanoTime()
        val result = integrity.verify(a.value, full = true)
        val seconds = (System.nanoTime() - started) / 1e9
        val rate = (volume + 1) / seconds
        log.info("audit verification: {} entries in {} s = {} entries/s", volume + 1, "%.1f".format(seconds), "%.0f".format(rate))
        assertThat(result).isInstanceOf(AuditVerification.Ok::class.java)
        assertThat(rate).describedAs("entries verified per second (budget 20,000)").isGreaterThan(MIN_RATE)
    }

    /** Appends [count] valid entries after the tenant's current head, in batches, as the owner (RLS still applies). */
    private fun generate(
        tenant: TenantId,
        count: Long,
    ) = OwnerJdbc.connect { c ->
        c.autoCommit = false
        c.prepareStatement("select set_config('app.tenant_id', ?, true)").use {
            it.setString(1, tenant.toString())
            it.execute()
        }
        var (seq, prev) =
            c.prepareStatement("select last_seq, last_hash from audit_chain_head where tenant_id = ?").use { st ->
                st.setObject(1, tenant.value)
                st.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1) to rs.getBytes(2)
                }
            }
        val base = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val insert =
            c.prepareStatement(
                """
                insert into audit_event (id, tenant_id, seq, occurred_at, actor_type, actor_id, action, object_type, object_id,
                                         canonical, prev_hash, hash)
                values (?, ?, ?, ?, 'SYSTEM', 'volume', 'volume.generated', 'volume', ?, ?, ?, ?)
                """,
            )
        insert.use { st ->
            for (i in 1..count) {
                seq += 1
                val at = base.plusNanos(i * 1_000)
                val canonical = AuditCanonicalForm.encode(syntheticEntry(tenant, seq, at, "o$i"))
                val hash = AuditCanonicalForm.hash(prev, canonical)
                st.setObject(1, UUID.randomUUID())
                st.setObject(2, tenant.value)
                st.setLong(3, seq)
                st.setTimestamp(4, Timestamp.from(at))
                st.setString(5, "o$i")
                st.setString(6, canonical)
                st.setBytes(7, prev)
                st.setBytes(8, hash)
                st.addBatch()
                prev = hash
                if (i % BATCH == 0L || i == count) st.executeBatch()
            }
        }
        c.prepareStatement("update audit_chain_head set last_seq = ?, last_hash = ? where tenant_id = ?").use {
            it.setLong(1, seq)
            it.setBytes(2, prev)
            it.setObject(3, tenant.value)
            it.executeUpdate()
        }
        c.commit()
    }

    private fun syntheticEntry(
        tenant: TenantId,
        seq: Long,
        at: Instant,
        objectId: String,
    ) = AuditEntry(tenant.value, seq, at, "SYSTEM", "volume", null, null, null, null, "volume.generated", "volume", objectId, null, null)

    private companion object {
        const val BATCH = 5_000L

        // Budget is 20,000/s on the dev VM (design §10); CI runners are slower and shared.
        const val MIN_RATE = 5_000.0
    }
}
