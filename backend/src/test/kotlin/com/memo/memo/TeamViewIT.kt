package com.memo.memo

import com.memo.common.authz.AccessAdministration
import com.memo.common.authz.Permission
import com.memo.common.tenant.TenantDsl
import com.memo.identity.MfaSeeding
import com.memo.identity.mfa.Totp
import com.memo.org.OrgSeeding
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.Person
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.login
import com.memo.testsupport.postJson
import com.memo.testsupport.sessionCookie
import com.memo.testsupport.signIn
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The team view and the approvals dashboard (design E05 §5, §6; test plan E05 T5): a head of a unit sees the open tasks of
 * the people seated in its subtree, by due time, with memos they may not read restricted, and reassigns within it; a
 * holder of `inbox.team.read` sees their grant's scope; others get 403. The dashboard counts pending tasks by age in
 * working days, overdue tasks, cycle time and throughput, for `me` or a unit the viewer oversees.
 */
class TeamViewIT : MemoIntegrationTest() {
    @Autowired lateinit var seeding: OrgSeeding

    @Autowired lateinit var access: AccessAdministration

    @Autowired lateinit var mfaSeeding: MfaSeeding

    @Autowired lateinit var tenantDsl: TenantDsl

    private val entity = UUID.randomUUID()
    private val credit = UUID.randomUUID()
    private val loans = UUID.randomUUID()
    private val ops = UUID.randomUUID()
    private lateinit var head: Person
    private lateinit var officer: Person
    private lateinit var operator: Person
    private lateinit var alice: Person
    private val headPassword = aPassword()

    @BeforeEach
    fun organisation() {
        val account = accounts.anActiveUser(transactions, tenant, headPassword)
        head = Person(account, mvc.signIn(tenant, account.email, headPassword))
        officer = person()
        operator = person()
        alice = person()
        val since = LocalDate.now().minusYears(1)
        asUser(admin) {
            seeding.legalEntity(entity, "Bank", since)
            seeding.unit(credit, entity, null, "CRD", "Credit Division", since)
            seeding.unit(loans, entity, credit, "LNS", "Loans", since)
            seeding.unit(ops, entity, null, "OPS", "Operations", since)
            seat(head, credit, "Head of Credit", head = true, since)
            seat(officer, loans, "Loans officer", head = false, since)
            seat(alice, loans, "Loans analyst", head = false, since)
            seat(operator, ops, "Operations officer", head = false, since)
        }
    }

    @Test
    fun `a head sees the open tasks of their subtree by due time, restricted where they may not read, and others get 403`() {
        val later = submitted(officer)
        val overdue = submitted(officer)
        val elsewhere = submitted(operator)
        due(later, officer, "now() + interval '2 days'")
        due(overdue, officer, "now() - interval '1 day'")

        val page = read("/api/v1/inbox/team", head.session).json()
        val items = page["items"]
        assertThat((0 until items.size()).map { items[it]["memo"]["id"].asString() }).containsExactly(overdue, later)
        assertThat(items[0]["overdue"].asBoolean()).isTrue()
        assertThat(items[0]["memo"]["restricted"].asBoolean()).isTrue()
        assertThat(items[0]["memo"].absent("subject")).isTrue()
        assertThat(items[0]["holder"]["id"].asString()).isEqualTo(officer.id.toString())
        assertThat(items[0]["unit"]["name"].asString()).isEqualTo("Loans")
        assertThat((0 until page["units"].size()).map { page["units"][it]["name"].asString() }).containsExactly("Credit Division", "Loans")

        assertThat(read("/api/v1/inbox/team?status=overdue", head.session).json()["items"].size()).isEqualTo(1)
        assertThat(read("/api/v1/inbox/team?person=${operator.id}", head.session).json()["items"].size()).isZero()
        assertThat(read("/api/v1/inbox/team?unit=$ops", head.session)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(read("/api/v1/inbox/team", officer.session)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(elsewhere).isNotNull()
    }

    @Test
    fun `a holder of inbox team read sees their grant's scope only`() {
        val registry = person()
        asUser(admin) { access.grantCustom(registry.id, "Operations registry", setOf(Permission.INBOX_TEAM_READ), ops) }
        submitted(officer)
        val theirs = submitted(operator)

        val items = read("/api/v1/inbox/team", registry.session).json()["items"]
        assertThat((0 until items.size()).map { items[it]["memo"]["id"].asString() }).containsExactly(theirs)
    }

    @Test
    fun `a head reassigns a task within their scope, not outside it`() {
        val inScope = submitted(officer)
        val outside = submitted(operator)
        val dana = person()
        val session = steppedUp()
        val moved =
            post(
                "/api/v1/inbox/bulk",
                session,
                """{"action":"REASSIGN","ids":["${task(
                    inScope,
                    officer,
                )}","${task(outside, operator)}"],"to":"${dana.id}","reason":"Away"}""",
            ).json()["results"]
        assertThat((0 until moved.size()).map { moved[it]["outcome"].asString() }).containsExactly("OK", "FORBIDDEN")
        assertThat(get(inScope, dana.session).json()["myTask"].isNull).isFalse()
    }

    @Test
    fun `the dashboard counts pending by age, overdue, cycle time and throughput for me or a unit overseen`() {
        val pending = submitted(officer)
        due(pending, officer, "now() - interval '1 day'")
        val decided = submitted(officer)
        assertThat(post("/api/v1/tasks/${task(decided, officer)}/approve", officer.session)).hasStatusOk()

        val mine = read("/api/v1/dashboards/approvals", officer.session).json()
        assertThat(mine["scope"].asString()).isEqualTo("me")
        assertThat((0 until mine["pendingByAge"].size()).sumOf { mine["pendingByAge"][it]["count"].asInt() }).isEqualTo(1)
        assertThat(mine["pendingByAge"][0]["bucket"].asString()).isEqualTo("0-2")
        assertThat(mine["pendingByAge"][0]["count"].asInt()).isEqualTo(1)
        assertThat(mine["overdue"].asInt()).isEqualTo(1)
        assertThat(mine["cycleTimes"][0]["type"]["name"].asString()).isEqualTo("General memo")
        assertThat(mine["cycleTimes"][0]["decided"].asInt()).isEqualTo(1)
        val weeks = mine["throughput"]
        assertThat((0 until weeks.size()).sumOf { weeks[it]["created"].asInt() }).isEqualTo(2)
        assertThat((0 until weeks.size()).sumOf { weeks[it]["completed"].asInt() }).isEqualTo(1)

        // The head of Credit sees the unit: pending by person; memo figures only for memos they may read.
        val unit = read("/api/v1/dashboards/approvals?scope=unit:$credit", head.session).json()
        assertThat(unit["pendingByPerson"][0]["person"]["id"].asString()).isEqualTo(officer.id.toString())
        assertThat(unit["pendingByPerson"][0]["overdue"].asInt()).isEqualTo(1)
        assertThat(unit["cycleTimes"].size()).isZero()
        asUser(admin) { access.grantCustom(head.id, "Credit reader", setOf(Permission.MEMO_READ_UNIT), credit) }
        assertThat(read("/api/v1/dashboards/approvals?scope=unit:$credit", head.session).json()["cycleTimes"][0]["decided"].asInt())
            .isEqualTo(1)

        assertThat(read("/api/v1/dashboards/approvals?scope=unit:$ops", head.session)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(read("/api/v1/dashboards/approvals?scope=unit:$credit", officer.session)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(read("/api/v1/dashboards/approvals?scope=everything", officer.session)).hasStatus(HttpStatus.BAD_REQUEST)
    }

    private fun seat(
        who: Person,
        unit: UUID,
        title: String,
        head: Boolean,
        since: LocalDate,
    ) {
        val position = UUID.randomUUID()
        seeding.position(position, unit, title, head, since)
        seeding.assignment(position, who.id, since)
    }

    /** A memo of Alice's, waiting for [approver]. */
    private fun submitted(approver: Person): String {
        val id = aDraft(alice)
        uploadDocument(id, alice.session)
        route(id, alice, approver)
        assertThat(post("/api/v1/memos/$id/submit", alice.session)).hasStatusOk()
        return id
    }

    private fun task(
        memo: String,
        approver: Person,
    ): String = get(memo, approver.session).json()["myTask"]["id"].asString()

    private fun due(
        memo: String,
        assignee: Person,
        at: String,
    ) {
        val task = task(memo, assignee)
        asUser(admin) { tenantDsl.current().execute("update workflow_task set due_at = $at where id = '$task'") }
    }

    /** The head signed in again with a second factor just now, as step-up asks. */
    private fun steppedUp(): Cookie {
        val secret = Totp.base32(Totp.newSecret())
        transactions.inTenant(tenant.id) { mfaSeeding.ensureTotp(head.account.id, secret) }
        val partial = requireNotNull(mvc.login(tenant, head.account.email, headPassword).sessionCookie())
        val code = Totp.code(Totp.decodeBase32(secret), Totp.step(Instant.now()))
        return requireNotNull(mvc.postJson(tenant, "/api/v1/auth/mfa", partial, """{"code":"$code"}""").sessionCookie(partial))
    }
}
