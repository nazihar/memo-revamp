package com.memo.memo

import com.memo.authz.internal.GrantAdmin
import com.memo.authz.internal.RoleAdmin
import com.memo.common.authz.Permission
import com.memo.identity.MfaSeeding
import com.memo.identity.mfa.Totp
import com.memo.memo.internal.MemoShareStore
import com.memo.memo.internal.MemoShares
import com.memo.memo.internal.MemoStore
import com.memo.memo.internal.NewShare
import com.memo.memo.internal.ParticipantKind
import com.memo.org.internal.Assignments
import com.memo.org.internal.LegalEntities
import com.memo.org.internal.OrgUnits
import com.memo.org.internal.Period
import com.memo.org.internal.Positions
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
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Who may read a memo (ADR-0050; test plan E03 T4): shares with a reason and an expiry, revoked by their giver or the
 * initiator, never lifting classification; a lapsed share reads as no access everywhere; a stronger way in survives a
 * revoked share; unit readers read their subtree up to their clearance; tenant admins do not read others' memos.
 */
class MemoAccessIT : MemoIntegrationTest() {
    @Autowired lateinit var shares: MemoShares

    @Autowired lateinit var shareStore: MemoShareStore

    @Autowired lateinit var memoStore: MemoStore

    @Autowired lateinit var roles: RoleAdmin

    @Autowired lateinit var grants: GrantAdmin

    @Autowired lateinit var entities: LegalEntities

    @Autowired lateinit var units: OrgUnits

    @Autowired lateinit var mfaSeeding: MfaSeeding

    @Autowired lateinit var positions: Positions

    @Autowired lateinit var assignments: Assignments

    private fun share(
        memo: String,
        by: Person,
        to: Person,
        reason: String = "Audit sample Q4",
        expiresAt: Instant? = null,
    ) = post(
        "/api/v1/memos/$memo/shares",
        by.session,
        """{"userId":"${to.id}","reason":"$reason"${expiresAt?.let { ""","expiresAt":"$it"""" } ?: ""}}""",
    )

    private fun revoke(
        memo: String,
        by: Person,
        shareId: String,
    ) = mvc.sendJson(HttpMethod.DELETE, tenant, "/api/v1/memos/$memo/shares/$shareId", by.session, "{}", emptyMap())

    /**
     * A person holding `memo.read.unit` for [unit]. The permission is privileged, so the grant ends their sessions and
     * they sign in again with TOTP (ADR-0024).
     */
    private fun unitReader(unit: UUID): Person {
        val password = aPassword()
        val account = accounts.anActiveUser(transactions, tenant, password)
        asUser(admin) {
            val role = roles.create("Division reader ${UUID.randomUUID()}", null, setOf(Permission.MEMO_READ_UNIT))
            grants.grant(account.id.value, role.id, unit)
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

    @Test
    fun `a share gives read access with its reason, and its giver or the initiator revokes it`() {
        val alice = person()
        val bob = person()
        val carol = person()
        val memo = aDraft(alice)
        assertThat(get(memo, bob.session)).hasStatus(HttpStatus.NOT_FOUND)

        val until = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
        val shared = share(memo, alice, bob, expiresAt = until)
        assertThat(shared).hasStatus(HttpStatus.CREATED)
        assertThat(shared.json()["reason"].asString()).isEqualTo("Audit sample Q4")
        assertThat(get(memo, bob.session)).hasStatusOk()
        Audit.assertAudited(tenant.id, "memo.share.granted", memo)

        val list = read("/api/v1/memos/$memo/shares", bob.session).json()
        assertThat(list[0]["person"]["id"].asString()).isEqualTo(bob.id.toString())
        assertThat(list[0]["canRevoke"].asBoolean()).isFalse()
        assertThat(read("/api/v1/memos/$memo/shares", alice.session).json()[0]["canRevoke"].asBoolean()).isTrue()
        // A sharee reads but does not pass the memo on.
        assertThat(share(memo, bob, carol)).hasStatus(HttpStatus.FORBIDDEN)

        assertThat(revoke(memo, bob, list[0]["id"].asString())).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(revoke(memo, alice, list[0]["id"].asString())).hasStatus(HttpStatus.NO_CONTENT)
        assertThat(get(memo, bob.session)).hasStatus(HttpStatus.NOT_FOUND)
        Audit.assertAudited(tenant.id, "memo.share.revoked", memo)
        // Shared again, it is readable again.
        assertThat(share(memo, alice, bob)).hasStatus(HttpStatus.CREATED)
        assertThat(get(memo, bob.session)).hasStatusOk()
    }

    @Test
    fun `a share needs a reason, a cleared person and an end within a year`() {
        val alice = person(clearance = 1)
        val bob = person()
        val memo = aDraft(alice, rank = 1)
        assertThat(share(memo, alice, bob).errors()).containsExactly("userId:NOT_CLEARED")
        val cleared = person(clearance = 1)
        assertThat(share(memo, alice, cleared, reason = " ").errors()).containsExactly("reason:REQUIRED")
        assertThat(share(memo, alice, cleared, expiresAt = Instant.now().plus(400, ChronoUnit.DAYS)).errors())
            .containsExactly("expiresAt:INVALID")
        assertThat(share(memo, alice, cleared, expiresAt = Instant.now().minusSeconds(60)).errors()).containsExactly("expiresAt:INVALID")
        assertThat(share(memo, alice, alice).errors()).containsExactly("userId:SELF")
    }

    @Test
    fun `a lapsed share reads as no access at once, and the daily job records it`() {
        val alice = person()
        val bob = person()
        val memo = aDraft(alice)
        val id = UUID.fromString(memo)
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        asUser(alice.account) {
            shareStore.insert(NewShare(id, bob.id, alice.id, "Expired", now.minusMinutes(1), now.minusDays(2)))
            memoStore.addParticipant(id, bob.id, ParticipantKind.SHARED, now.minusDays(2), alice.id, now.minusMinutes(1))
        }
        assertThat(get(memo, bob.session)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(read("/api/v1/memos/$memo/shares", alice.session).json().size()).isZero()

        assertThat(asUser(admin) { shares.expireDue() }).isGreaterThanOrEqualTo(1)
        Audit.assertAudited(tenant.id, "memo.share.expired", memo)
        assertThat(get(memo, bob.session)).hasStatus(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `someone who becomes an approver keeps access when their share is revoked`() {
        val alice = person()
        val bob = person()
        val memo = aDraft(alice)
        val shareId = share(memo, alice, bob).json()["id"].asString()
        uploadDocument(memo, alice.session)
        route(memo, alice, bob)
        assertThat(post("/api/v1/memos/$memo/submit", alice.session)).hasStatusOk()
        assertThat(revoke(memo, alice, shareId)).hasStatus(HttpStatus.NO_CONTENT)
        assertThat(get(memo, bob.session)).hasStatusOk()
    }

    @Test
    fun `a unit reader reads memos raised in their subtree up to their clearance, and tenant admins read none of them`() {
        val jan1 = LocalDate.of(2026, 1, 1)
        val (division, credit, branch) =
            asUser(admin) {
                val entity = entities.create("Bank ${UUID.randomUUID().toString().take(6)}", null, null, jan1)
                val head = units.createUnit(entity.id, null, "HO", "Head office", jan1)
                val division = units.createUnit(entity.id, head.id, "CD", "Credit Division", jan1)
                val credit = units.createUnit(entity.id, division.id, "CD-CR", "Credit risk", jan1)
                val branch = units.createUnit(entity.id, head.id, "BR-1", "Branch 1", jan1)
                Triple(division, credit, branch)
            }
        val insider = person(clearance = 1)
        val outsider = person()
        asUser(admin) {
            assignments.assign(positions.create(credit.id, null, "Credit officer", false, jan1).id, insider.id, Period(jan1, null))
            assignments.assign(positions.create(branch.id, null, "Teller", false, jan1).id, outsider.id, Period(jan1, null))
        }
        val inDivision = aDraft(insider)
        val secret = aDraft(insider, rank = 1)
        val elsewhere = aDraft(outsider)

        val auditor = unitReader(division.id)
        assertThat(get(inDivision, auditor.session)).hasStatusOk()
        assertThat(get(secret, auditor.session)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(get(elsewhere, auditor.session)).hasStatus(HttpStatus.NOT_FOUND)
        // A unit reader is not a participant: they do not share the memo on.
        assertThat(share(inDivision, auditor, outsider)).hasStatus(HttpStatus.FORBIDDEN)

        val tenantAdmin = mvc.adminSession(tenant, accounts, transactions, mfaSeeding)
        assertThat(get(inDivision, tenantAdmin)).hasStatus(HttpStatus.NOT_FOUND)
    }
}
