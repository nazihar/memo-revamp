package com.memo.memo

import com.memo.authz.internal.GrantAdmin
import com.memo.authz.internal.RoleAdmin
import com.memo.common.authz.Permission
import com.memo.identity.MfaSeeding
import com.memo.identity.mfa.Totp
import com.memo.org.internal.Assignments
import com.memo.org.internal.LegalEntities
import com.memo.org.internal.OrgUnits
import com.memo.org.internal.Period
import com.memo.org.internal.Positions
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.Audit
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.Person
import com.memo.testsupport.aPassword
import com.memo.testsupport.adminSession
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.login
import com.memo.testsupport.postJson
import com.memo.testsupport.sendJson
import com.memo.testsupport.sessionCookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Copy-to and read receipts (ADR-0050; test plan E03 T5): copies to people, positions and lists go at submit or after
 * approval, each person once, not to those not cleared; copy recipients read only; receipts record first opens; a
 * rejected memo sends none of its "after approval" copies; distribution lists are kept by their managers.
 */
class MemoCopiesIT : MemoIntegrationTest() {
    @Autowired lateinit var mfaSeeding: MfaSeeding

    @Autowired lateinit var entities: LegalEntities

    @Autowired lateinit var units: OrgUnits

    @Autowired lateinit var positions: Positions

    @Autowired lateinit var assignments: Assignments

    @Autowired lateinit var roles: RoleAdmin

    @Autowired lateinit var grants: GrantAdmin

    /** A registry clerk: `memo.copy.add` and `memo.read.unit` tenant-wide, signed in with TOTP (both are privileged). */
    private fun clerk(): Person {
        val password = aPassword()
        val account = accounts.anActiveUser(transactions, tenant, password)
        asUser(admin) {
            val role = roles.create("Registry ${UUID.randomUUID()}", null, setOf(Permission.MEMO_COPY_ADD, Permission.MEMO_READ_UNIT))
            grants.grant(account.id.value, role.id, null)
        }
        val secret = Totp.base32(Totp.newSecret())
        transactions.inTenant(tenant.id) { mfaSeeding.ensureTotp(account.id, secret) }
        val partial = requireNotNull(mvc.login(tenant, account.email, password).sessionCookie())
        val code = Totp.code(Totp.decodeBase32(secret), Totp.step(Instant.now()))
        return Person(
            account,
            requireNotNull(mvc.postJson(tenant, "/api/v1/auth/mfa", partial, """{"code":"$code"}""").sessionCookie(partial)),
        )
    }

    private fun kinds(user: Person): List<String> =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.prepareStatement("select kind from notification_item where user_id = ? order by created_at, id").use {
                it.setObject(1, user.id)
                it.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
        }

    private fun setCopies(
        memo: String,
        who: Person,
        body: String,
    ) = mvc.sendJson(
        HttpMethod.PUT,
        tenant,
        "/api/v1/memos/$memo/copies",
        who.session,
        """{"copies":[$body]}""",
        mapOf("If-Match" to "\"${get(memo, who.session).json()["version"].asLong()}\""),
    )

    private fun task(
        memo: String,
        who: Person,
    ) = get(memo, who.session).json()["myTask"]["id"].asString()

    private fun submit(
        memo: String,
        alice: Person,
        approver: Person,
    ) = uploadDocument(memo, alice.session).let {
        route(memo, alice, approver)
        post("/api/v1/memos/$memo/submit", alice.session)
    }

    /** A position held by [holder] today. */
    private fun positionHeldBy(holder: Person): UUID {
        val jan1 = LocalDate.of(2026, 1, 1)
        return asUser(admin) {
            val entity = entities.create("Bank ${UUID.randomUUID().toString().take(6)}", null, null, jan1)
            val unit = units.createUnit(entity.id, null, "OP${(100..999).random()}", "Operations", jan1)
            val position = positions.create(unit.id, null, "Head of Operations", false, jan1)
            assignments.assign(position.id, holder.id, Period(jan1, null))
            position.id
        }
    }

    @Test
    fun `copies after approval reach a person, a position's holder and a list's members once each, and receipts follow`() {
        val alice = person()
        val bob = person()
        val carol = person()
        val dave = person()
        val erin = person()
        val position = positionHeldBy(dave)
        val admin = mvc.adminSession(tenant, accounts, transactions, mfaSeeding)
        val list =
            post(
                "/api/v1/distribution-lists",
                admin,
                """{"name":"Branch managers","members":[{"kind":"USER","id":"${carol.id}"},{"kind":"USER","id":"${erin.id}"}]}""",
            )
        assertThat(list).hasStatus(HttpStatus.CREATED)
        Audit.assertAudited(tenant.id, "distribution.list.created", list.json()["id"].asString())

        val memo = aDraft(alice)
        val listId = list.json()["id"].asString()
        val set =
            setCopies(
                memo,
                alice,
                """{"kind":"USER","id":"${carol.id}"},{"kind":"POSITION","id":"$position"},{"kind":"LIST","id":"$listId"}""",
            )
        assertThat(set).hasStatusOk()
        assertThat(set.json()["copies"].size()).isEqualTo(3)
        assertThat(set.json()["copies"][0]["sendWhen"].asString()).isEqualTo("AFTER_APPROVAL")
        Audit.assertAudited(tenant.id, "memo.copy.changed", memo)
        val outsider =
            mvc.sendJson(
                HttpMethod.PUT,
                tenant,
                "/api/v1/memos/$memo/copies",
                carol.session,
                """{"copies":[]}""",
                mapOf("If-Match" to "\"0\""),
            )
        assertThat(outsider).hasStatus(HttpStatus.NOT_FOUND)

        assertThat(submit(memo, alice, bob)).hasStatusOk()
        // Nothing goes before approval.
        assertThat(get(memo, carol.session)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(post("/api/v1/tasks/${task(memo, bob)}/approve", bob.session)).hasStatusOk()
        Audit.assertAudited(tenant.id, "memo.copy.sent", memo)

        // Carol is reached directly and through the list: one notification.
        assertThat(kinds(carol)).containsExactly("MEMO_COPIED")
        assertThat(kinds(dave)).containsExactly("MEMO_COPIED")
        assertThat(kinds(erin)).containsExactly("MEMO_COPIED")
        val seen = get(memo, carol.session)
        assertThat(seen).hasStatusOk()
        assertThat(seen.json()["readingAs"].asString()).isEqualTo("COPY")
        assertThat(seen.json().absent("myTask")).isTrue()

        val receipts = read("/api/v1/memos/$memo/receipts", alice.session).json()
        val opened = (0 until receipts["copies"].size()).map { receipts["copies"][it] }.filter { !it.absent("openedAt") }
        assertThat(opened.map { it["person"]["id"].asString() }.toSet()).containsExactly(carol.id.toString())
        assertThat(receipts["approvers"][0]["person"]["id"].asString()).isEqualTo(bob.id.toString())
        assertThat(read("/api/v1/memos/$memo/receipts", carol.session)).hasStatus(HttpStatus.FORBIDDEN)
        val copies = read("/api/v1/memos/$memo/copies", alice.session).json()["copies"]
        assertThat((0 until copies.size()).sumOf { copies[it]["read"].asInt() }).isGreaterThanOrEqualTo(1)
        // A sent list stays as it is.
        assertThat(setCopies(memo, alice, "")).hasStatus(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `a copy at submit goes during review, and one not cleared refuses the submit with the name`() {
        val alice = person(clearance = 1)
        val bob = person(clearance = 1)
        val carol = person()
        val memo = aDraft(alice, rank = 1)
        setCopies(memo, alice, """{"kind":"USER","id":"${carol.id}","sendWhen":"AT_SUBMIT"}""")
        val refused = submit(memo, alice, bob)
        assertThat(refused.errors()).containsExactly("copies:NOT_CLEARED")

        val cleared = person(clearance = 1)
        setCopies(memo, alice, """{"kind":"USER","id":"${cleared.id}","sendWhen":"AT_SUBMIT"}""")
        assertThat(post("/api/v1/memos/$memo/submit", alice.session)).hasStatusOk()
        assertThat(kinds(cleared)).containsExactly("MEMO_COPIED")
        assertThat(get(memo, cleared.session)).hasStatusOk()
    }

    @Test
    fun `a rejected memo sends none of its copies, and after approval only memo copy add holders copy it further`() {
        val alice = person()
        val bob = person()
        val carol = person()
        val memo = aDraft(alice)
        setCopies(memo, alice, """{"kind":"USER","id":"${carol.id}"}""")
        submit(memo, alice, bob)
        assertThat(post("/api/v1/tasks/${task(memo, bob)}/reject", bob.session, """{"remark":"No budget"}""")).hasStatusOk()
        assertThat(kinds(carol)).isEmpty()
        assertThat(read("/api/v1/memos/$memo/copies", alice.session).json()["copies"][0].absent("sentAt")).isTrue()

        val approved = aDraft(alice)
        submit(approved, alice, bob)
        post("/api/v1/tasks/${task(approved, bob)}/approve", bob.session)
        val more = post("/api/v1/memos/$approved/copies/additions", alice.session, """{"copies":[{"kind":"USER","id":"${carol.id}"}]}""")
        assertThat(more).hasStatus(HttpStatus.FORBIDDEN)

        val registry = clerk()
        assertThat(get(approved, registry.session).json()["allowedActions"].toString()).contains("ADD_COPIES")
        val added =
            post("/api/v1/memos/$approved/copies/additions", registry.session, """{"copies":[{"kind":"USER","id":"${carol.id}"}]}""")
        assertThat(added).hasStatusOk()
        Audit.assertAudited(tenant.id, "memo.copy.added_after_approval", approved)
        assertThat(kinds(carol)).containsExactly("MEMO_COPIED")
        assertThat(get(approved, carol.session)).hasStatusOk()
    }

    @Test
    fun `distribution lists are read by everyone and kept by their managers, never deleted`() {
        val alice = person()
        val admin = mvc.adminSession(tenant, accounts, transactions, mfaSeeding)
        val created =
            post(
                "/api/v1/distribution-lists",
                admin,
                """{"name":"Auditors","members":[{"kind":"USER","id":"${alice.id}"}]}""",
            ).json()
        val id = created["id"].asString()
        assertThat(post("/api/v1/distribution-lists", alice.session, """{"name":"Mine","members":[]}""")).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(read("/api/v1/distribution-lists/$id", alice.session).json()["members"][0]["name"].asString()).isNotBlank()
        assertThat(post("/api/v1/distribution-lists", admin, """{"name":"auditors","members":[]}""")).hasStatus(HttpStatus.CONFLICT)
        val unknown =
            post("/api/v1/distribution-lists", admin, """{"name":"Ghosts","members":[{"kind":"USER","id":"${UUID.randomUUID()}"}]}""")
        assertThat(unknown.errors()).containsExactly("members[0].id:UNKNOWN")

        val renamed =
            mvc.sendJson(
                HttpMethod.PUT,
                tenant,
                "/api/v1/distribution-lists/$id",
                admin,
                """{"name":"Internal audit","members":[]}""",
                mapOf("If-Match" to "\"0\""),
            )
        assertThat(renamed.json()["name"].asString()).isEqualTo("Internal audit")
        Audit.assertAudited(tenant.id, "distribution.list.changed", id)
        val off = mvc.sendJson(HttpMethod.DELETE, tenant, "/api/v1/distribution-lists/$id", admin, "{}", mapOf("If-Match" to "\"1\""))
        assertThat(off).hasStatus(HttpStatus.NO_CONTENT)
        Audit.assertAudited(tenant.id, "distribution.list.deactivated", id)
        assertThat(read("/api/v1/distribution-lists/$id", alice.session)).hasStatus(HttpStatus.NOT_FOUND)
    }
}
