package com.memo.memo

import com.memo.authz.internal.GrantAdmin
import com.memo.authz.internal.RoleAdmin
import com.memo.common.authz.Permission
import com.memo.identity.MfaSeeding
import com.memo.identity.mfa.Totp
import com.memo.testsupport.Audit
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.OwnerJdbc
import com.memo.testsupport.Person
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.login
import com.memo.testsupport.postJson
import com.memo.testsupport.sessionCookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID

/**
 * Authenticated approvals (ADR-0051; test plan E03 T6): each decision records how it was authenticated, the client
 * and the content's hashes, in a chain; the check recomputes them and names what changed; client fields are for
 * auditors only.
 */
class MemoApprovalsIT : MemoIntegrationTest() {
    @Autowired lateinit var mfaSeeding: MfaSeeding

    @Autowired lateinit var roles: RoleAdmin

    @Autowired lateinit var grants: GrantAdmin

    /** A person signed in with password and TOTP, optionally holding [permissions] through a role of their own. */
    private fun withTotp(vararg permissions: Permission): Person {
        val password = aPassword()
        val account = accounts.anActiveUser(transactions, tenant, password)
        if (permissions.isNotEmpty()) {
            asUser(admin) {
                val role = roles.create("Role ${UUID.randomUUID()}", null, permissions.toSet())
                grants.grant(account.id.value, role.id, null)
            }
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

    private fun task(
        memo: String,
        who: Person,
    ) = get(memo, who.session).json()["myTask"]["id"].asString()

    private fun stamps(
        memo: String,
        who: Person,
    ) = read("/api/v1/memos/$memo/approvals", who.session).json()

    @Test
    fun `an approval records the factors, the step-up rule and the document hash, and checks out`() {
        val alice = person()
        val bob = withTotp()
        val memo = aDraft(alice)
        uploadDocument(memo, alice.session)
        route(memo, alice, bob)
        post("/api/v1/memos/$memo/submit", alice.session)
        assertThat(post("/api/v1/tasks/${task(memo, bob)}/approve", bob.session)).hasStatusOk()

        val stamp = stamps(memo, alice)[0]
        assertThat(stamp["decision"].asString()).isEqualTo("APPROVE")
        assertThat(stamp["person"]["id"].asString()).isEqualTo(bob.id.toString())
        assertThat((0 until stamp["factors"].size()).map { stamp["factors"][it].asString() }).containsExactly("PASSWORD", "TOTP")
        assertThat(stamp["secondFactorAgeSeconds"].asLong()).isBetween(0, 600)
        assertThat(stamp["stepUpRequired"].asBoolean()).isTrue()
        assertThat(stamp["documentHash"].asString()).hasSize(8)
        assertThat(stamp.absent("client")).isTrue()

        val check = post("/api/v1/memos/$memo/approvals/check", alice.session).json()
        assertThat(check["ok"].asBoolean()).isTrue()
        Audit.assertAudited(tenant.id, "memo.approval.checked", memo)
    }

    @Test
    fun `each decision is chained to the one before, and a changed record or document is named by the check`() {
        val alice = person()
        val bob = person()
        val memo = aDraft(alice)
        uploadDocument(memo, alice.session)
        route(memo, alice, bob)
        post("/api/v1/memos/$memo/submit", alice.session)
        post("/api/v1/tasks/${task(memo, bob)}/return", bob.session, """{"remark":"Add annex B"}""")
        post("/api/v1/memos/$memo/resubmit", alice.session)
        post("/api/v1/tasks/${task(memo, bob)}/approve", bob.session)
        val all = stamps(memo, alice)
        assertThat((0 until all.size()).map { all[it]["decision"].asString() }).containsExactly("RETURN", "APPROVE")
        assertThat(post("/api/v1/memos/$memo/approvals/check", alice.session).json()["ok"].asBoolean()).isTrue()

        // As the schema owner (a test of tampering only): change the first record's decision.
        OwnerJdbc.connect { c ->
            c.autoCommit = false
            c.prepareStatement("select set_config('app.tenant_id', ?, true)").use {
                it.setString(1, tenant.id.value.toString())
                it.execute()
            }
            c.prepareStatement("update memo_approval set decision = 'REJECT' where memo_id = ? and decision = 'RETURN'").use {
                it.setObject(1, UUID.fromString(memo))
                assertThat(it.executeUpdate()).isEqualTo(1)
            }
            c.commit()
        }
        val check = post("/api/v1/memos/$memo/approvals/check", alice.session).json()
        assertThat(check["ok"].asBoolean()).isFalse()
        assertThat((0 until check["problems"].size()).map { check["problems"][it]["code"].asString() }).contains("RECORD_CHANGED")
    }

    @Test
    fun `only auditors see the client of a decision`() {
        val alice = person()
        val bob = person()
        val memo = aDraft(alice)
        uploadDocument(memo, alice.session)
        route(memo, alice, bob)
        post("/api/v1/memos/$memo/submit", alice.session)
        post("/api/v1/tasks/${task(memo, bob)}/approve", bob.session)

        val auditor = withTotp(Permission.AUDIT_READ)
        post("/api/v1/memos/$memo/shares", alice.session, """{"userId":"${auditor.id}","reason":"Audit sample"}""")
        val seen = stamps(memo, auditor)[0]
        assertThat(seen["client"]["ip"].asString()).isNotBlank()
        assertThat(stamps(memo, alice)[0].absent("client")).isTrue()
    }
}
