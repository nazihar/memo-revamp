package com.memo.actions

import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.org.OrgDirectory
import com.memo.org.OrgHolderChanges
import com.memo.org.internal.Assignments
import com.memo.org.internal.OrgCalendar
import com.memo.org.internal.Period
import com.memo.testsupport.MutableClock
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import com.memo.workflow.BlockedReason
import com.memo.workflow.WorkflowSubject
import com.memo.workflow.internal.DatedHolderChanges
import com.memo.workflow.internal.HolderSyncStore
import com.memo.workflow.internal.WorkflowRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * An action owned by a position follows holder changes that take effect on a date (ADR-0068 amendment, 2026-11-28), in a
 * tenant on Asia/Dhaka: at the run just after midnight the owner's task and the action's holder move to the successor
 * recorded for that day, and when the next term ends with nobody after it, the task waits for an administrator and the
 * action has no holder.
 */
class OwnerPositionDatesIT : ActionFixture() {
    override fun label() = "actdates"

    @Autowired lateinit var assignments: Assignments

    @Autowired lateinit var directory: OrgDirectory

    @Autowired lateinit var holderDsl: TenantDsl

    @Autowired lateinit var syncStore: HolderSyncStore

    @Autowired lateinit var holderChanges: OrgHolderChanges

    @Autowired lateinit var subjects: List<WorkflowSubject>

    @Autowired lateinit var runner: WorkflowRunner

    @Autowired lateinit var holderJson: JsonMapper

    private val clock = MutableClock()
    private lateinit var dhakaToday: LocalDate

    @BeforeEach
    fun dhaka() {
        transactions.inTenant(tenant.id) { settings.put(OrgCalendar.TIME_ZONE, DHAKA.id) }
        dhakaToday = asUser(admin) { directory.today() }
    }

    @Test
    fun `the owner's task and the action's holder move to the successor at the day's run, and wait when nobody follows`() {
        val successor = accounts.anActiveUser(transactions, tenant, aPassword())
        val id = issued(owner = position()).id
        assertThat(row(id).holderUserId).isEqualTo(treasurer.id.value)
        // Recorded today: the treasurer hands over tomorrow, and the successor's term ends the day after.
        asUser(admin) {
            val current = assignments.get(UUID.nameUUIDFromBytes("assignment:$headOfTreasury".toByteArray()))
            assignments.changeDates(current.id, current.version, Period(current.period.from, dhakaToday.plusDays(1)))
            assignments.assign(headOfTreasury, successor.id.value, Period(dhakaToday.plusDays(1), dhakaToday.plusDays(2)))
        }
        assertThat(row(id).holderUserId).isEqualTo(treasurer.id.value)
        assertThat(taskOf(id, treasurer)).isNotNull()

        clock.now =
            dhakaToday
                .plusDays(1)
                .atStartOfDay(DHAKA)
                .toInstant()
                .plusSeconds(30)
        assertThat(daily()).isEqualTo(1)
        assertThat(row(id).holderUserId).isEqualTo(successor.id.value)
        assertThat(taskOf(id, successor)).isNotNull()
        assertThat(taskOf(id, treasurer)).isNull()
        assertThat(row(id).stepName).isEqualTo("Owner")

        clock.now =
            dhakaToday
                .plusDays(2)
                .atStartOfDay(DHAKA)
                .toInstant()
                .plusSeconds(30)
        assertThat(daily()).isEqualTo(1)
        assertThat(row(id).holderUserId).isNull()
        assertThat(taskOf(id, successor)).isNull()
        val view = asUser(admin) { checkNotNull(workflowQueries.viewOf(com.memo.forum.ActionsPolicy.SUBJECT, id)) }
        assertThat(view.openTaskBlocked).isEqualTo(BlockedReason.UNRESOLVED)
    }

    private fun daily(): Int =
        ActorContext.asSystem("workflow-position-holders") {
            TenantContext.call(tenant.id) {
                DatedHolderChanges(
                    holderDsl,
                    transactions,
                    syncStore,
                    directory,
                    holderChanges,
                    subjects,
                    runner,
                    holderJson,
                    clock,
                ).daily()
            }
        }

    private companion object {
        val DHAKA: ZoneId = ZoneId.of("Asia/Dhaka")
    }
}
