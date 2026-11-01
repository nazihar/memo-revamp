package com.memo.org

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.RefusedException
import com.memo.identity.UserAccount
import com.memo.identity.UserAccounts
import com.memo.identity.UserLifecycle
import com.memo.org.internal.Assignment
import com.memo.org.internal.Assignments
import com.memo.org.internal.LegalEntities
import com.memo.org.internal.OrgPosition
import com.memo.org.internal.OrgUnits
import com.memo.org.internal.OrgViews
import com.memo.org.internal.Period
import com.memo.org.internal.Positions
import com.memo.org.internal.RecordStatus
import com.memo.org.internal.ReportingLines
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.Audit
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Positions, effective-dated assignments and reporting lines (ADR-0026; test plan T4). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssignmentsIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var lifecycle: UserLifecycle

    @Autowired lateinit var entities: LegalEntities

    @Autowired lateinit var units: OrgUnits

    @Autowired lateinit var positions: Positions

    @Autowired lateinit var assignments: Assignments

    @Autowired lateinit var lines: ReportingLines

    @Autowired lateinit var views: OrgViews

    private val jan1: LocalDate = LocalDate.of(2026, 1, 1)
    private val jun1: LocalDate = LocalDate.of(2026, 6, 1)

    private val tenant: TestTenant by lazy { registry.aTestTenant(transactions, settings, "assign") }

    private val admin: UserAccount by lazy { accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true) }

    private val unitId: UUID by lazy {
        asAdmin {
            val entity = entities.create("Bank ${code()}", null, null, jan1)
            units.createUnit(entity.id, null, code(), "Credit", jan1).id
        }
    }

    private fun <T> asAdmin(block: () -> T): T =
        ActorContext.call(ActorScope(Actor.User(admin.id.value), null)) {
            TenantContext.call(tenant.id) { transactions.inTenant(tenant.id, block) }
        }

    private fun code() = "P" + UUID.randomUUID().toString().take(8)

    private fun aPosition(title: String = "Officer"): OrgPosition = asAdmin { positions.create(unitId, null, title, false, jan1) }

    private fun aPerson(): UserAccount = accounts.anActiveUser(transactions, tenant, aPassword())

    private fun refusal(block: () -> Any?): String =
        (runCatching { block() }.exceptionOrNull() as? RefusedException ?: error("expected a refusal")).reason

    @Test
    fun `one holder per position at a time, and the directory answers who held it on a date`() {
        val seat = aPosition()
        val first = aPerson()
        val second = aPerson()
        val a = asAdmin { assignments.assign(seat.id, first.id.value, Period(jan1, null)) }

        assertThat(refusal { asAdmin { assignments.assign(seat.id, second.id.value, Period(jun1, null)) } }).isEqualTo("OVERLAP")

        val ended = asAdmin { assignments.changeDates(a.id, a.version, Period(jan1, jun1)) }
        asAdmin { assignments.assign(seat.id, second.id.value, Period(jun1, null)) }

        asAdmin {
            assertThat(views.holderOn(seat.id, LocalDate.of(2026, 5, 15))?.person?.id).isEqualTo(first.id.value)
            assertThat(views.holderOn(seat.id, LocalDate.of(2026, 6, 15))?.person?.id).isEqualTo(second.id.value)
            assertThat(views.holderOn(seat.id, LocalDate.of(2025, 12, 31))).isNull()
            assertThat(views.positionDetail(seat.id).assignments).hasSize(2)
        }
        Audit.assertAudited(tenant.id, "org.assignment.updated", ended.id.toString(), admin.id.toString())
    }

    @Test
    fun `a person holds one primary position at a time, within the position's dates`() {
        val one = aPosition()
        val two = aPosition()
        val person = aPerson()
        asAdmin { assignments.assign(one.id, person.id.value, Period(jan1, null)) }

        assertThat(refusal { asAdmin { assignments.assign(two.id, person.id.value, Period(jun1, null)) } }).isEqualTo("USER_OVERLAP")
        assertThat(refusal { asAdmin { assignments.assign(two.id, aPerson().id.value, Period(jan1.minusDays(1), null)) } })
            .isEqualTo("DATES")
    }

    @Test
    fun `a cancelled assignment stays in the history and frees the seat, but cannot change again`() {
        val seat = aPosition()
        val wrong = asAdmin { assignments.assign(seat.id, aPerson().id.value, Period(jan1, null)) }
        val cancelled = asAdmin { assignments.cancel(wrong.id, wrong.version) }
        assertThat(cancelled.status).isEqualTo(RecordStatus.CANCELLED)

        asAdmin { assignments.assign(seat.id, aPerson().id.value, Period(jan1, null)) }
        assertThat(refusal { asAdmin { assignments.changeDates(wrong.id, cancelled.version, Period(jan1, jun1)) } }).isEqualTo("CANCELLED")
        assertThat(refusal { asAdmin { assignments.cancel(wrong.id, cancelled.version) } }).isEqualTo("CANCELLED")
        asAdmin { assertThat(views.positionDetail(seat.id).assignments.map { it.status }).contains(RecordStatus.CANCELLED) }
    }

    @Test
    fun `two administrators assigning the same seat at once, exactly one succeeds`() {
        val seat = aPosition()
        val people = listOf(aPerson(), aPerson())
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val results =
            people.map { person ->
                pool.submit<Result<Assignment>> {
                    ready.countDown()
                    go.await()
                    runCatching { asAdmin { assignments.assign(seat.id, person.id.value, Period(jan1, null)) } }
                }
            }
        ready.await(5, TimeUnit.SECONDS)
        go.countDown()
        val outcomes = results.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertThat(outcomes.count { it.isSuccess }).isEqualTo(1)
        assertThat((outcomes.single { it.isFailure }.exceptionOrNull() as RefusedException).reason).isEqualTo("OVERLAP")
    }

    @Test
    fun `deactivating a person ends their running assignment today and cancels a future one`() {
        val now = aPosition()
        val later = aPosition()
        val person = aPerson()
        val running = asAdmin { assignments.assign(now.id, person.id.value, Period(jan1, LocalDate.of(2099, 1, 1))) }
        val future = asAdmin { assignments.assign(later.id, person.id.value, Period(LocalDate.of(2099, 1, 1), null)) }

        asAdmin { lifecycle.deactivate(person.id, person.version, admin.id) }

        asAdmin {
            val ended = assignments.get(running.id)
            assertThat(ended.status).isEqualTo(RecordStatus.ACTIVE)
            assertThat(ended.period.to).isNotNull().isBefore(LocalDate.of(2099, 1, 1))
            assertThat(assignments.get(future.id).status).isEqualTo(RecordStatus.CANCELLED)
        }
        Audit.assertAudited(tenant.id, "org.assignment.ended", running.id.toString())
    }

    @Test
    fun `reporting lines are one per position at a time, never to itself or round in a circle`() {
        val chief = aPosition("Chief")
        val manager = aPosition("Manager")
        val clerk = aPosition("Clerk")
        asAdmin {
            lines.create(manager.id, chief.id, Period(jan1, null))
            lines.create(clerk.id, manager.id, Period(jan1, null))
        }

        assertThat(refusal { asAdmin { lines.create(chief.id, clerk.id, Period(jun1, null)) } }).isEqualTo("CYCLE")
        assertThat(refusal { asAdmin { lines.create(clerk.id, chief.id, Period(jun1, null)) } }).isEqualTo("OVERLAP")
        assertThat(refusal { asAdmin { lines.create(clerk.id, clerk.id, Period(jun1, null)) } }).isEqualTo("CYCLE")
    }

    @Test
    fun `a position closes only when nobody holds it afterwards, and a unit has one head`() {
        val seat = aPosition()
        val a = asAdmin { assignments.assign(seat.id, aPerson().id.value, Period(jan1, null)) }
        assertThat(refusal { asAdmin { positions.close(seat.id, seat.version, jun1) } }).isEqualTo("NOT_EMPTY")
        asAdmin { assignments.changeDates(a.id, a.version, Period(jan1, jun1)) }
        asAdmin { positions.close(seat.id, seat.version, jun1) }

        asAdmin { positions.create(unitId, null, "Head of credit", true, jan1) }
        assertThat(refusal { asAdmin { positions.create(unitId, null, "Second head", true, jan1) } }).isEqualTo("HEAD_TAKEN")
    }

    @Test
    fun `the history of a position lists its changes with who made them`() {
        val seat = aPosition("Analyst")
        asAdmin { positions.update(seat.id, seat.version, null, "Senior analyst", false) }

        val history = asAdmin { views.history("position", seat.id, 10) }

        assertThat(history.map { it.action }).containsExactly("org.position.updated", "org.position.created")
        assertThat(history.first().before?.get("title")).isEqualTo("Analyst")
        assertThat(history.first().after?.get("title")).isEqualTo("Senior analyst")
        assertThat(history.first().actor).isEqualTo(admin.displayName)
    }
}
