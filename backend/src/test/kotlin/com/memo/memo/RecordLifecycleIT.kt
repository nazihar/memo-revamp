package com.memo.memo

import com.memo.authz.internal.GrantAdmin
import com.memo.authz.internal.RoleAdmin
import com.memo.common.authz.Permission
import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.files.Files
import com.memo.identity.MfaSeeding
import com.memo.identity.mfa.Totp
import com.memo.memo.internal.MemoTypeFields
import com.memo.memo.internal.MemoTypeLifecycle
import com.memo.memo.internal.MemoTypes
import com.memo.memo.internal.Retention
import com.memo.memo.internal.RetentionFrom
import com.memo.memo.internal.rendition.MemoRenditionPurpose
import com.memo.memo.internal.rendition.MemoRenditionStore
import com.memo.memo.internal.rendition.MemoRenditions
import com.memo.memo.internal.rendition.RecordBuilder
import com.memo.memo.internal.rendition.RecordFixity
import com.memo.memo.internal.rendition.RecordLifecycle
import com.memo.memo.internal.rendition.RenditionState
import com.memo.memo.internal.rendition.RenditionTrigger
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * After the record is made (ADR-0053; test plan E04 T5): a new version on purpose (permission, step-up, a reason) keeps
 * the first one; a failed version is tried again; closure extends the lock when the type counts retention from closure;
 * the fixity check finds a hash that no longer matches.
 */
class RecordLifecycleIT : MemoIntegrationTest() {
    @Autowired lateinit var renditions: MemoRenditions

    @Autowired lateinit var store: MemoRenditionStore

    @Autowired lateinit var builder: RecordBuilder

    @Autowired lateinit var records: RecordLifecycle

    @Autowired lateinit var fixity: RecordFixity

    @Autowired lateinit var files: Files

    @Autowired lateinit var types: MemoTypes

    @Autowired lateinit var typeLifecycle: MemoTypeLifecycle

    @Autowired lateinit var mfaSeeding: MfaSeeding

    @Autowired lateinit var roles: RoleAdmin

    @Autowired lateinit var grants: GrantAdmin

    @BeforeEach
    fun noScheduler() {
        renditions.jobs = false
    }

    @AfterEach
    fun scheduler() {
        renditions.jobs = true
    }

    /** A person signed in with password and TOTP (so a step-up is fresh), holding [permissions] through a role. */
    private fun withTotp(vararg permissions: Permission): Person {
        val password = aPassword()
        val account = accounts.anActiveUser(transactions, tenant, password)
        asUser(admin) {
            val role = roles.create("Role ${UUID.randomUUID()}", null, permissions.toSet())
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

    private fun approved(
        initiator: Person,
        type: UUID? = null,
    ): String {
        val approver = person()
        val memo =
            if (type == null) {
                aDraft(initiator)
            } else {
                create(initiator.session, """{"subject":"Facility","typeId":"$type"}""").json()["id"].asString()
            }
        uploadDocument(memo, initiator.session)
        route(memo, initiator, approver)
        post("/api/v1/memos/$memo/submit", initiator.session)
        val task = get(memo, approver.session).json()["myTask"]["id"].asString()
        assertThat(post("/api/v1/tasks/$task/approve", approver.session)).hasStatusOk()
        return memo
    }

    private fun <T> asJob(block: () -> T): T = TenantContext.call(tenant.id) { ActorContext.asSystem(MemoRenditionPurpose.JOB, block) }

    private fun versions(memo: String) = transactions.inTenant(tenant.id) { store.of(UUID.fromString(memo)) }

    private fun buildOpen(memo: String) = asJob { builder.build(versions(memo).first { it.state == RenditionState.PENDING }.id) }

    @Test
    fun `a new version needs the permission, a reason and a step-up, and the first version stays`() {
        val alice = person()
        val memo = approved(alice)
        buildOpen(memo)
        val first = versions(memo).single()

        assertThat(post("/api/v1/memos/$memo/rendition/regenerate", alice.session, """{"reason":"Table lost"}"""))
            .hasStatus(HttpStatus.FORBIDDEN)
        val keeper = withTotp(Permission.MEMO_RENDITION_REGENERATE)
        // A keeper who cannot read the memo is told it does not exist.
        assertThat(
            post("/api/v1/memos/$memo/rendition/regenerate", keeper.session, """{"reason":"Table lost"}"""),
        ).hasStatus(HttpStatus.NOT_FOUND)
        // Give the keeper the memo through a share.
        assertThat(
            post("/api/v1/memos/$memo/shares", alice.session, """{"userId":"${keeper.id}","reason":"Records"}"""),
        ).hasStatus(HttpStatus.CREATED)
        assertThat(
            post("/api/v1/memos/$memo/rendition/regenerate", keeper.session, """{"reason":" "}"""),
        ).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(
            post("/api/v1/memos/$memo/rendition/regenerate", keeper.session, """{"reason":"The converter lost a table on page 2."}"""),
        ).hasStatus(HttpStatus.ACCEPTED)

        buildOpen(memo)
        val all = versions(memo)
        assertThat(all.map { it.version to it.state }).containsExactly(2 to RenditionState.READY, 1 to RenditionState.READY)
        assertThat(all.first().trigger).isEqualTo(RenditionTrigger.REGENERATE)
        assertThat(all.first().fileId).isEqualTo(first.fileId)
        assertThat(all.first().fileVersionId).isNotEqualTo(first.fileVersionId)
        Audit.assertAudited(tenant.id, "memo.rendition.superseded", memo)
        val view = read("/api/v1/memos/$memo/rendition", alice.session).json()
        assertThat(view["current"]["version"].asInt()).isEqualTo(2)
        assertThat(view["versions"].size()).isEqualTo(2)
        // The first version is still there to open.
        val v1 = view["versions"][1]
        assertThat(
            read("/api/v1/files/${v1["fileId"].asString()}/versions/${v1["versionId"].asString()}/content", alice.session),
        ).hasStatusOk()
    }

    @Test
    fun `a failed version is tried again from the start`() {
        val alice = person()
        val memo = approved(alice)
        OwnerJdbc.connect { c ->
            c.autoCommit = false
            c.prepareStatement("select set_config('app.tenant_id', ?, true)").use {
                it.setString(1, tenant.id.value.toString())
                it.execute()
            }
            c.prepareStatement("update memo_rendition set state = 'FAILED', error = 'CONVERTER_DOWN', attempts = 5 where memo_id = ?").use {
                it.setObject(1, UUID.fromString(memo))
                it.executeUpdate()
            }
            c.commit()
        }
        val keeper = withTotp(Permission.MEMO_RENDITION_REGENERATE)
        post("/api/v1/memos/$memo/shares", alice.session, """{"userId":"${keeper.id}","reason":"Records"}""")
        assertThat(post("/api/v1/memos/$memo/rendition/retry", keeper.session)).hasStatus(HttpStatus.ACCEPTED)
        val retried = versions(memo).single()
        assertThat(retried.state).isEqualTo(RenditionState.PENDING)
        assertThat(retried.attempts).isZero()
        buildOpen(memo)
        assertThat(versions(memo).single().state).isEqualTo(RenditionState.READY)
    }

    @Test
    fun `closure extends the lock to closure plus the type's 25 years (beyond the default 10) when they count from closure`() {
        val checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        val type = asUser(admin) { types.create(MemoTypeFields("Kept ${UUID.randomUUID().toString().take(6)}", null, false, true)) }
        val published = asUser(admin) { types.published(type.id)!! }
        val draft =
            asUser(admin) { typeLifecycle.newDraft(type.id, published.settings.copy(retention = Retention(YEARS, RetentionFrom.CLOSURE))) }
        asUser(admin) { typeLifecycle.submit(type.id, draft.number, draft.revision) }
        asUser(checker) { typeLifecycle.approve(type.id, draft.number, null) }

        val alice = person()
        val memo = approved(alice, type.id)
        buildOpen(memo)
        val record = versions(memo).single()
        val before = transactions.inTenant(tenant.id) { files.version(record.fileVersionId!!)!!.retainUntil!! }
        assertThat(post("/api/v1/memos/$memo/close", alice.session)).hasStatusOk()
        assertThat(asJob { records.extendRetention(UUID.fromString(memo)) }).isEqualTo(1)
        val after = transactions.inTenant(tenant.id) { files.version(record.fileVersionId!!)!!.retainUntil!! }
        assertThat(after).isAfter(before)
        assertThat(after).isAfter(Instant.now().plus(YEARS * DAYS_A_YEAR - 2, ChronoUnit.DAYS))
        Audit.assertAudited(tenant.id, "memo.rendition.retention_extended", memo)
        Audit.assertAudited(tenant.id, "file.retention.extended", record.fileVersionId.toString())
        // Again: nothing to extend, the lock is already that long.
        assertThat(asJob { records.extendRetention(UUID.fromString(memo)) }).isZero()
    }

    @Test
    fun `the fixity check passes for stored records and names one whose hash no longer matches`() {
        val alice = person()
        val memo = approved(alice)
        buildOpen(memo)
        assertThat(asJob { fixity.verify() }.mismatched).isZero()
        Audit.assertAudited(tenant.id, "memo.rendition.verified", null)

        OwnerJdbc.connect { c ->
            c.autoCommit = false
            c.prepareStatement("select set_config('app.tenant_id', ?, true)").use {
                it.setString(1, tenant.id.value.toString())
                it.execute()
            }
            c.prepareStatement("update memo_rendition set sha256 = repeat('0', 64) where memo_id = ?").use {
                it.setObject(1, UUID.fromString(memo))
                it.executeUpdate()
            }
            c.commit()
        }
        assertThat(asJob { fixity.verify() }.mismatched).isEqualTo(1)
        Audit.assertAudited(tenant.id, "memo.rendition.mismatch", memo)
    }

    private companion object {
        const val YEARS = 25
        const val DAYS_A_YEAR = 365L
    }
}
