package com.memo.audit

import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.OwnerJdbc
import com.memo.testsupport.query
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.sql.SQLException
import java.time.LocalDate
import java.time.ZoneOffset

/** ADR-0019: monthly partitions exist ahead of time, are secured like the parent, and only the migrator creates them. */
class AuditPartitionIT : IntegrationTest() {
    @Autowired lateinit var flyway: Flyway

    private fun partitions(): Map<String, Pair<Boolean, Boolean>> =
        OwnerJdbc.connect { c ->
            c
                .query(
                    """
                select c.relname, c.relrowsecurity, c.relforcerowsecurity from pg_inherits i
                join pg_class c on c.oid = i.inhrelid join pg_class p on p.oid = i.inhparent
                where p.relname = 'audit_event'
                """,
                ) { it.getString(1) to (it.getBoolean(2) to it.getBoolean(3)) }
                .toMap()
        }

    private fun name(month: LocalDate) = "audit_event_y%04dm%02d".format(month.year, month.monthValue)

    @Test
    fun `partitions exist for this month and twelve ahead, each with RLS enabled and forced`() {
        val thisMonth = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)
        val expected = (0L..12L).map { name(thisMonth.plusMonths(it)) } + "audit_event_default"
        val existing = partitions()
        assertThat(existing.keys).containsAll(expected)
        assertThat(existing.values).allSatisfy { assertThat(it).isEqualTo(true to true) }
    }

    @Test
    fun `the app role cannot create partitions`() {
        AppRoleJdbc.connect { c ->
            assertThatThrownBy { c.query("select audit_ensure_partitions(1)") { it.getInt(1) } }.isInstanceOf(SQLException::class.java)
        }
    }

    @Test
    fun `the migration callback is registered and recreates a missing future partition`() {
        assertThat(flyway.configuration.callbacks.map { it.javaClass.simpleName }).contains("AuditPartitionCallback")
        val farMonth = name(LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).plusMonths(12))
        OwnerJdbc.connect { c -> c.createStatement().use { it.execute("drop table $farMonth") } }
        assertThat(partitions()).doesNotContainKey(farMonth)
        val created =
            OwnerJdbc.connect { c ->
                com.memo.audit.internal.AuditPartitionCallback
                    .ensurePartitions(c, 12)
            }
        assertThat(created).isEqualTo(1)
        assertThat(partitions()[farMonth]).isEqualTo(true to true)
    }
}
