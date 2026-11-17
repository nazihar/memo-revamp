package com.memo.memo

import com.memo.common.web.RefusedException
import com.memo.identity.UserAccount
import com.memo.memo.internal.FinancialYearStyle
import com.memo.memo.internal.MemoNumberingAdmin
import com.memo.memo.internal.MemoRejectedException
import com.memo.memo.internal.MemoTypeChecks
import com.memo.memo.internal.MemoTypeFields
import com.memo.memo.internal.MemoTypeLifecycle
import com.memo.memo.internal.MemoTypeSettings
import com.memo.memo.internal.MemoTypes
import com.memo.memo.internal.NumberCountPer
import com.memo.memo.internal.NumberKey
import com.memo.memo.internal.NumberMoment
import com.memo.memo.internal.NumberPart
import com.memo.memo.internal.NumberPartKind
import com.memo.memo.internal.NumberReset
import com.memo.memo.internal.Numbering
import com.memo.memo.internal.RoutePolicy
import com.memo.org.OrgDirectory
import com.memo.org.internal.Assignments
import com.memo.org.internal.LegalEntities
import com.memo.org.internal.OrgUnits
import com.memo.org.internal.Period
import com.memo.org.internal.Positions
import com.memo.testsupport.Audit
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.Person
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.time.LocalDate
import java.util.UUID

/**
 * Memo numbering (test plan E01, T4; ADR-0044): numbers follow the type's format at submit or at final approval, drafts
 * take none, a resubmission keeps its number, a unit format needs the origin unit, and the register shows every number
 * with void ones marked and none missing; a counter can be started once before it gives a number; series cannot overlap
 * or disagree.
 */
class NumberingIT : MemoIntegrationTest() {
    @Autowired lateinit var types: MemoTypes

    @Autowired lateinit var lifecycle: MemoTypeLifecycle

    @Autowired lateinit var checks: MemoTypeChecks

    @Autowired lateinit var numbering: MemoNumberingAdmin

    @Autowired lateinit var directory: OrgDirectory

    @Autowired lateinit var entities: LegalEntities

    @Autowired lateinit var units: OrgUnits

    @Autowired lateinit var positions: Positions

    @Autowired lateinit var assignments: Assignments

    private lateinit var checker: UserAccount
    private lateinit var today: LocalDate
    private lateinit var unitId: UUID

    private val credit =
        Numbering(
            series = "credit",
            parts =
                listOf(
                    NumberPart(NumberPartKind.TYPE_CODE),
                    NumberPart(NumberPartKind.TEXT, text = "/"),
                    NumberPart(NumberPartKind.UNIT_CODE),
                    NumberPart(NumberPartKind.TEXT, text = "/"),
                    NumberPart(NumberPartKind.FINANCIAL_YEAR, style = FinancialYearStyle.LONG),
                    NumberPart(NumberPartKind.TEXT, text = "/"),
                    NumberPart(NumberPartKind.SEQUENCE, digits = 4),
                ),
            reset = NumberReset.FINANCIAL_YEAR,
            countPer = NumberCountPer.UNIT,
            fyStartMonth = 7,
        )

    @BeforeEach
    fun organisation() {
        checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        today = asUser(admin) { directory.today() }
        unitId =
            asUser(admin) {
                val entity = entities.create("Bank ${UUID.randomUUID().toString().take(6)}", null, null, today)
                units.createUnit(entity.id, null, "HO-CR", "Credit", today).id
            }
    }

    @Test
    fun `numbers follow the format at submit, drafts take none, and a resubmission keeps its number`() {
        val type = aType("Credit facility", "CR", credit)
        val alice = seated()
        val bob = person()
        val first = draft(alice, type)
        assertThat(get(first, alice.session).json().absent("reference")).isTrue()
        submit(first, alice, bob)
        val fy = financialYear(today)
        val number = get(first, alice.session).json()["reference"].asString()
        assertThat(number).isEqualTo("CR/HO-CR/$fy/0001")
        Audit.assertAudited(tenant.id, "memo.numbered", first, alice.id.toString())

        val second = draft(alice, type)
        submit(second, alice, bob)
        assertThat(get(second, alice.session).json()["reference"].asString()).isEqualTo("CR/HO-CR/$fy/0002")

        val task = get(first, bob.session).json()["myTask"]["id"].asString()
        assertThat(post("/api/v1/tasks/$task/return", bob.session, """{"remark":"Add the collateral"}""")).hasStatusOk()
        assertThat(post("/api/v1/memos/$first/resubmit", alice.session)).hasStatusOk()
        assertThat(get(first, alice.session).json()["reference"].asString()).isEqualTo(number)
    }

    @Test
    fun `a type that numbers on final approval leaves memos in review unnumbered`() {
        val approvals =
            Numbering(
                series = "approvals",
                parts =
                    listOf(
                        NumberPart(NumberPartKind.TEXT, text = "AP-"),
                        NumberPart(NumberPartKind.YEAR, digits = 4),
                        NumberPart(NumberPartKind.TEXT, text = "-"),
                        NumberPart(NumberPartKind.SEQUENCE, digits = 4),
                    ),
                moment = NumberMoment.ON_APPROVAL,
            )
        val type = aType("Policy approval", "PA", approvals)
        val alice = person()
        val bob = person()
        val id = draft(alice, type)
        submit(id, alice, bob)
        val inReview = get(id, alice.session).json()
        assertThat(inReview.absent("reference")).isTrue()
        assertThat(inReview["numberedWhen"].asString()).isEqualTo("ON_APPROVAL")

        val task = get(id, bob.session).json()["myTask"]["id"].asString()
        assertThat(post("/api/v1/tasks/$task/approve", bob.session)).hasStatusOk()
        assertThat(get(id, alice.session).json()["reference"].asString()).isEqualTo("AP-${today.year}-0001")
        Audit.assertAudited(tenant.id, "memo.numbered", id, bob.id.toString())
    }

    @Test
    fun `a number with the unit needs the initiator's unit`() {
        val type = aType("Credit facility", "CR", credit)
        val homeless = person()
        val id = draft(homeless, type)
        assertThat(uploadDocument(id, homeless.session)).hasStatus(HttpStatus.CREATED)
        assertThat(route(id, homeless, person())).hasStatusOk()
        assertThat(post("/api/v1/memos/$id/submit", homeless.session).errors()).containsExactly("originUnit:NO_ORIGIN_UNIT")
    }

    @Test
    fun `the register lists every number, marks void ones, finds none missing, and a counter starts once`() {
        val type = aType("Credit facility", "CR", credit)
        val alice = seated()
        val bob = person()
        val kept = draft(alice, type)
        submit(kept, alice, bob)
        val withdrawn = draft(alice, type)
        submit(withdrawn, alice, bob)
        assertThat(post("/api/v1/memos/$withdrawn/withdraw", alice.session, """{"remark":"Duplicate request"}""")).hasStatusOk()

        val key = NumberKey("credit", "FY${financialYear(today)}", unitId.toString())
        val register = asUser(admin) { numbering.register(key, 0, 50, admin.id.value) }
        assertThat(register.entries.map { it.sequence to it.void }).containsExactly(1L to false, 2L to true)
        assertThat(register.entries.map { it.subject }).containsOnlyNulls()
        assertThat(register.missing).isEmpty()
        assertThat(register.lastNumber).isEqualTo(2)

        val nextYear = NumberKey("credit", "FY${financialYear(today.plusYears(1))}", unitId.toString())
        asUser(checker) { numbering.start(nextYear, 501) }
        Audit.assertAudited(tenant.id, "memo.numbering.started", "credit", checker.id.value.toString())
        assertThat(refusal { asUser(checker) { numbering.start(nextYear, 900) } }).isEqualTo("ALREADY_STARTED")
        assertThat(refusal { asUser(checker) { numbering.start(key, 10) } }).isEqualTo("ALREADY_STARTED")
        val wrong = runCatching { asUser(checker) { numbering.start(NumberKey("credit", "2027", ""), 1) } }.exceptionOrNull()
        assertThat((wrong as MemoRejectedException).problems.map { it.field }).containsExactly("period", "scope")
    }

    @Test
    fun `series cannot overlap another, and types sharing a series count the same way`() {
        aType("Credit facility", "CR", credit)
        val other = asUser(admin) { types.create(MemoTypeFields("Expense", null, stepUpOnApprove = false, active = true)) }

        fun problems(numbering: Numbering) =
            asUser(admin) { checks.problems(settings(other.id, "EX", numbering), other.id) }.map { "${it.field}:${it.code}" }
        // The default M-YYYY-NNNNN format in a series of its own would repeat the general series' numbers.
        assertThat(problems(Numbering(series = "expense"))).containsExactly("numbering/parts:SERIES_OVERLAP")
        assertThat(problems(credit.copy(countPer = NumberCountPer.SERIES))).containsExactly("numbering/series:SERIES_MISMATCH")
        // Sharing the credit series and its counting is fine: the type code keeps the numbers apart.
        assertThat(problems(credit)).isEmpty()
    }

    private fun aType(
        name: String,
        code: String,
        numbering: Numbering,
    ): UUID {
        val id = asUser(admin) { types.create(MemoTypeFields(name, null, stepUpOnApprove = false, active = true)) }.id
        val draft = asUser(admin) { lifecycle.newDraft(id, settings(id, code, numbering)) }
        val submitted = asUser(admin) { lifecycle.submit(id, draft.number, draft.revision) }
        asUser(checker) { lifecycle.approve(id, submitted.number, null) }
        return id
    }

    private fun settings(
        id: UUID,
        code: String,
        numbering: Numbering,
    ) = MemoTypeSettings(
        asUser(admin) { types.get(id) }.name,
        code,
        null,
        "sequential-approval",
        RoutePolicy.INITIATOR_DEFINED,
        stepUpOnApprove = false,
        numbering = numbering,
    )

    /** A person holding a position in the Credit unit (HO-CR). */
    private fun seated(): Person {
        val person = person()
        asUser(admin) {
            assignments.assign(
                positions.create(unitId, null, "Officer ${UUID.randomUUID()}", false, today).id,
                person.id,
                Period(today, null),
            )
        }
        return person
    }

    private fun draft(
        initiator: Person,
        type: UUID,
    ): String = create(initiator.session, """{"subject":"Facility","typeId":"$type"}""").json()["id"].asString()

    private fun submit(
        id: String,
        initiator: Person,
        approver: Person,
    ) {
        assertThat(uploadDocument(id, initiator.session)).hasStatus(HttpStatus.CREATED)
        assertThat(route(id, initiator, approver)).hasStatusOk()
        assertThat(post("/api/v1/memos/$id/submit", initiator.session)).hasStatusOk()
    }

    /** `2026-27` for a date in the financial year starting in July 2026. */
    private fun financialYear(date: LocalDate): String {
        val start = if (date.monthValue >= 7) date.year else date.year - 1
        return "$start-${((start + 1) % 100).toString().padStart(2, '0')}"
    }

    private fun refusal(block: () -> Any?): String =
        (
            runCatching { block() }.exceptionOrNull() as? RefusedException
                ?: error("expected a refusal")
        ).reason
}
