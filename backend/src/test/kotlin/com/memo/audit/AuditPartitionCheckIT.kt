package com.memo.audit

import com.memo.audit.internal.AuditPartitionCallback
import com.memo.audit.internal.AuditPartitionCheck
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.OwnerJdbc
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import java.time.ZoneOffset

/** Threat model D2: the daily check reports how many future partitions exist and alerts below three. */
class AuditPartitionCheckIT : IntegrationTest() {
    @Autowired internal lateinit var check: AuditPartitionCheck

    @Autowired lateinit var meters: MeterRegistry

    private fun gauge() = meters.find("memo.audit.partitions.future").gauge()!!.value()

    @Test
    fun `reports the future partitions and alerts when fewer than three remain`() {
        assertThat(check.check()).isGreaterThanOrEqualTo(12)
        val thisMonth = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)
        val dropped = (3L..12L).map { thisMonth.plusMonths(it) }.map { "audit_event_y%04dm%02d".format(it.year, it.monthValue) }
        try {
            OwnerJdbc.connect { c -> dropped.forEach { name -> c.createStatement().use { it.execute("drop table if exists $name") } } }
            assertThat(check.check()).isEqualTo(2).isLessThan(AuditPartitionCheck.MIN_FUTURE_MONTHS)
            assertThat(gauge()).isEqualTo(2.0)
        } finally {
            OwnerJdbc.connect { c -> AuditPartitionCallback.ensurePartitions(c, 12) }
        }
        assertThat(check.check()).isGreaterThanOrEqualTo(12)
    }
}
