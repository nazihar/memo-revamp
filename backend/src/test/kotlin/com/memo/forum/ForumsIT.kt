package com.memo.forum

import com.memo.common.authz.AccessAdministration
import com.memo.common.authz.Clearances
import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Permission
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.RefusedException
import com.memo.forum.internal.CloneInput
import com.memo.forum.internal.EndReason
import com.memo.forum.internal.ForumInput
import com.memo.forum.internal.ForumMembers
import com.memo.forum.internal.ForumNotFoundException
import com.memo.forum.internal.ForumRejectedException
import com.memo.forum.internal.ForumTypePreset
import com.memo.forum.internal.ForumTypes
import com.memo.forum.internal.ForumViews
import com.memo.forum.internal.Forums
import com.memo.forum.internal.MemberEdit
import com.memo.forum.internal.MemberInput
import com.memo.forum.internal.MemberState
import com.memo.identity.UserAccount
import com.memo.identity.UserAccounts
import com.memo.org.internal.Assignments
import com.memo.org.internal.LegalEntities
import com.memo.org.internal.OrgUnits
import com.memo.org.internal.Period
import com.memo.org.internal.Positions
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.AppRoleJdbc.update
import com.memo.testsupport.Audit
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assumptions.assumeThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.sql.SQLException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Forums and terms of office (test plan E01, T2; ADR-0056): forums are created, edited, dissolved and cloned by
 * administrators; members are added, renewed, re-roled, ended and withdrawn as rows of history under the forum lock;
 * secretaries manage within their limits; a forum is visible only to its members and readers, up to their clearance.
 */
class ForumsIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var types: ForumTypes

    @Autowired lateinit var forums: Forums

    @Autowired lateinit var members: ForumMembers

    @Autowired lateinit var views: ForumViews

    @Autowired lateinit var policies: ForumPolicies

    @Autowired lateinit var codes: ForumCodes

    @Autowired lateinit var directory: ForumDirectory

    @Autowired lateinit var access: AccessAdministration

    @Autowired lateinit var clearances: Clearances

    @Autowired lateinit var entities: LegalEntities

    @Autowired lateinit var units: OrgUnits

    @Autowired lateinit var positions: Positions

    @Autowired lateinit var assignments: Assignments

    private lateinit var tenant: TestTenant
    private lateinit var admin: UserAccount
    private lateinit var secretary: UserAccount
    private lateinit var chair: UserAccount
    private lateinit var member: UserAccount
    private lateinit var outsider: UserAccount
    private lateinit var typeId: UUID

    @BeforeEach
    fun setUp() {
        tenant = registry.aTestTenant(transactions, settings, "forums", mfaRequired = false)
        admin = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        secretary = accounts.anActiveUser(transactions, tenant, aPassword())
        chair = accounts.anActiveUser(transactions, tenant, aPassword())
        member = accounts.anActiveUser(transactions, tenant, aPassword())
        outsider = accounts.anActiveUser(transactions, tenant, aPassword())
        typeId = asUser(admin) { types.create("BC", "Board committee", ForumTypePreset.BOARD_COMMITTEE, publish = true) }.type.id
    }

    private fun brc(key: String = "board-risk") =
        asUser(admin) { forums.create(ForumInput(key, key.take(3).uppercase(), "Board Risk Committee", "Risk", typeId)) }

    private fun add(
        forumId: UUID,
        input: MemberInput,
        by: UserAccount = admin,
    ) = asUser(by) { members.add(forumId, input) }.row

    @Test
    fun `a forum is created, edited, dissolved and cloned, audited each time`() {
        val forum = brc()
        Audit.assertAudited(tenant.id, "forum.created", forum.id.toString(), admin.id.value.toString())
        assertThat(problem { forums.create(ForumInput("board-risk", "BRX", "Again", "Again", typeId)) }).isEqualTo("key:KEY_TAKEN")
        assertThat(problem { forums.create(ForumInput("Bad Key", "brc", "", "x", typeId)) })
            .isEqualTo("key:FORMAT, code:FORMAT, name:LENGTH")

        val edited =
            asUser(admin) { forums.update(forum.id, forum.version, ForumInput("board-risk", "BRC2", "Risk Committee", "Risk", typeId)) }
        assertThat(edited.code).isEqualTo("BRC2")
        Audit.assertAudited(tenant.id, "forum.updated", forum.id.toString(), admin.id.value.toString())
        refused("KEY_FIXED") { asUser(admin) { forums.update(forum.id, edited.version, ForumInput("other", "BRC2", "x", "x", typeId)) } }
        assertThat(
            problem { forums.update(forum.id, edited.version, ForumInput("board-risk", "BRC2", "x", "x", typeId, parentId = forum.id)) },
        ).isEqualTo("parentId:PARENT_CYCLE")

        add(forum.id, MemberInput(userId = chair.id.value, role = ForumRole.CHAIR))
        add(forum.id, MemberInput(userId = member.id.value))
        val clone =
            asUser(admin) {
                forums.clone(forum.id, CloneInput("board-audit", "BAC", "Board Audit Committee", "Audit", withMembers = true))
            }
        Audit.assertAudited(tenant.id, "forum.cloned", clone.id.toString(), admin.id.value.toString())
        assertThat(asUser(admin) { directory.membersOn(clone.id) }.map { it.userId to it.role })
            .containsExactlyInAnyOrder(chair.id.value to ForumRole.CHAIR, member.id.value to ForumRole.MEMBER)

        val dissolved = asUser(admin) { forums.dissolve(forum.id, edited.version, null, "Merged into the audit committee") }
        assertThat(dissolved.status).isEqualTo(ForumStatus.DISSOLVED)
        Audit.assertAudited(tenant.id, "forum.dissolved", forum.id.toString(), admin.id.value.toString())
        refused("FORUM_DISSOLVED") { add(forum.id, MemberInput(userId = outsider.id.value)) }
    }

    @Test
    fun `terms of office are rows of history - renew, change role, end and withdraw`() {
        val forum = brc()
        val start = LocalDate.now(ZoneOffset.UTC).minusYears(4)
        val first = add(forum.id, MemberInput(userId = member.id.value, startsOn = start, authority = "Board resolution 1"))
        Audit.assertAudited(tenant.id, "forum.member.added", first.id.toString(), admin.id.value.toString())
        // 36 months from the preset's term policy.
        assertThat(first.termEndsOn).isEqualTo(start.plusMonths(36).minusDays(1))
        assertThat(first.voting).isTrue()

        val renewed = asUser(admin) { members.renew(forum.id, first.id, null, "Board resolution 2") }
        assertThat(renewed.row.termNumber).isEqualTo(2)
        assertThat(renewed.row.startsOn).isEqualTo(first.termEndsOn!!.plusDays(1))
        assertThat(renewed.row.previousId).isEqualTo(first.id)
        Audit.assertAudited(tenant.id, "forum.member.renewed", renewed.row.id.toString(), admin.id.value.toString())

        val firstNow = asUser(admin) { views.row(forum.id, first.id) }
        assertThat(firstNow.endReason).isEqualTo(EndReason.RENEWED)
        val today = LocalDate.now(ZoneOffset.UTC)
        val vice = asUser(admin) { members.changeRole(forum.id, renewed.row.id, ForumRole.VICE_CHAIR, null, null) }.row
        assertThat(vice.role).isEqualTo(ForumRole.VICE_CHAIR)
        assertThat(vice.termNumber).isEqualTo(2)
        Audit.assertAudited(tenant.id, "forum.member.role_changed", vice.id.toString(), admin.id.value.toString())

        val edited =
            asUser(admin) {
                members.edit(
                    forum.id,
                    vice.id,
                    vice.version,
                    MemberEdit(false, setOf("independent"), chair.id.value, vice.termEndsOn, null),
                )
            }
        assertThat(edited.row.voting).isFalse()
        assertThat(edited.row.attributes).containsExactly("independent")
        Audit.assertAudited(tenant.id, "forum.member.updated", vice.id.toString(), admin.id.value.toString())
        assertThat(problem { members.edit(forum.id, vice.id, edited.row.version, MemberEdit(true, setOf("unknown"), null, null, null)) })
            .isEqualTo("attributes:UNKNOWN_ATTRIBUTE")

        val ended = asUser(admin) { members.end(forum.id, vice.id, edited.row.version, today, EndReason.RESIGNED, "Moved abroad") }
        Audit.assertAudited(tenant.id, "forum.member.ended", vice.id.toString(), admin.id.value.toString())
        assertThat(ended.row.endedOn).isEqualTo(today)

        val upcoming = add(forum.id, MemberInput(userId = outsider.id.value, startsOn = today.plusMonths(1)))
        assertThat(upcoming.state(today)).isEqualTo(MemberState.UPCOMING)
        asUser(admin) { members.withdraw(forum.id, upcoming.id) }
        Audit.assertAudited(tenant.id, "forum.member.withdrawn", upcoming.id.toString(), admin.id.value.toString())
        refused("NOT_UPCOMING") { asUser(admin) { members.withdraw(forum.id, first.id) } }

        // History keeps every row, newest first; nothing was deleted.
        val history = asUser(admin) { views.members(forum, null, history = true) }
        assertThat(history).hasSize(4)
        assertThat(asUser(admin) { directory.membersOn(forum.id, start.plusDays(1)) }.map { it.userId }).containsExactly(member.id.value)
        assertThat(asUser(admin) { directory.membersOn(forum.id, today.plusDays(1)) }).isEmpty()
    }

    @Test
    fun `one chair at a time, one row per person at a time, and an unrenewed term stops counting`() {
        val forum = brc()
        val today = LocalDate.now(ZoneOffset.UTC)
        add(forum.id, MemberInput(userId = chair.id.value, role = ForumRole.CHAIR))
        refused("ONE_CHAIR") { add(forum.id, MemberInput(userId = member.id.value, role = ForumRole.CHAIR)) }
        // A chair from after the current one's term ends is fine.
        add(forum.id, MemberInput(userId = member.id.value, role = ForumRole.CHAIR, startsOn = today.plusYears(4)))
        refused("ALREADY_MEMBER") { add(forum.id, MemberInput(userId = chair.id.value, startsOn = today.plusDays(1))) }

        val lapsed = add(forum.id, MemberInput(userId = outsider.id.value, startsOn = today.minusYears(1), termEndsOn = today.minusDays(1)))
        assertThat(lapsed.state(today)).isEqualTo(MemberState.TERM_ENDED)
        assertThat(asUser(admin) { directory.membersOn(forum.id) }.map { it.userId }).doesNotContain(outsider.id.value)
        assertThat(asUser(admin) { views.forum(forum) }.termWarnings).isEqualTo(1)
    }

    @Test
    fun `two parallel chairs - exactly one is appointed`() {
        val forum = brc()
        val pool = Executors.newFixedThreadPool(2)
        val barrier = CyclicBarrier(2)
        val results =
            listOf(chair, member)
                .map { candidate ->
                    pool.submit<Result<Any>> {
                        barrier.await()
                        runCatching { add(forum.id, MemberInput(userId = candidate.id.value, role = ForumRole.CHAIR)) }
                    }
                }.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(results.single { it.isFailure }.exceptionOrNull()).isInstanceOfSatisfying(RefusedException::class.java) {
            assertThat(it.reason).isEqualTo("ONE_CHAIR")
        }
    }

    @Test
    fun `a secretary manages members but not chairs, secretaries or their own row`() {
        val forum = brc()
        add(forum.id, MemberInput(userId = secretary.id.value, role = ForumRole.SECRETARY))
        val added = add(forum.id, MemberInput(userId = member.id.value), by = secretary)
        assertThat(added.createdBy).isEqualTo(secretary.id.value)
        refused("SECRETARY_LIMIT") { add(forum.id, MemberInput(userId = chair.id.value, role = ForumRole.CHAIR), by = secretary) }
        refused("SECRETARY_LIMIT") { add(forum.id, MemberInput(userId = outsider.id.value, role = ForumRole.SECRETARY), by = secretary) }
        val own = asUser(admin) { directory.membersOn(forum.id) }.single { it.userId == secretary.id.value }
        refused("SECRETARY_LIMIT") { asUser(secretary) { members.withdraw(forum.id, own.memberId) } }
        refused("SECRETARY_LIMIT") {
            asUser(secretary) { members.changeRole(forum.id, added.id, ForumRole.CHAIR, LocalDate.now().plusDays(1), null) }
        }
        // A plain member manages nothing.
        assertThatThrownBy {
            add(
                forum.id,
                MemberInput(userId = outsider.id.value),
                by = member,
            )
        }.isInstanceOf(ForbiddenException::class.java)
        // Members, the chair and the secretary are told; the actor is not.
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            assertThat(
                c.long("select count(*) from notification_item where user_id = ? and kind = 'FORUM_MEMBERSHIP_CHANGED'", member.id.value),
            ).isGreaterThanOrEqualTo(1)
        }
    }

    @Test
    fun `only members and readers see a forum, up to their clearance, and former members only in my forums`() {
        val forum = brc()
        val today = LocalDate.now(ZoneOffset.UTC)
        add(forum.id, MemberInput(userId = member.id.value))
        assertThat(asUser(member) { forums.visible() }.map { it.id }).containsExactly(forum.id)
        assertThatThrownBy { asUser(outsider) { forums.get(forum.id) } }.isInstanceOf(ForumNotFoundException::class.java)
        assertThat(asUser(outsider) { forums.visible() }).isEmpty()

        // A member reads the type's published policy and the quorum on a date, without the type permissions.
        val policy = asUser(member) { policies.policy(forums.get(forum.id), null) }
        assertThat(policy.typeName).isEqualTo("Board committee")
        assertThat(policy.memberAttributes.map { it.key }).containsExactly("independent", "nonExecutive")
        assertThat(policy.quorumBasis to policy.quorumNeeded).isEqualTo(1 to 2)
        // What the decision screen needs (E06 T10): the override and when memos move on, as the type says.
        assertThat(policy.quorumOverride to policy.memoResumes).isEqualTo(QuorumOverride.NEVER to MemoResumes.AT_MEETING_END)
        assertThat(asUser(member) { policies.policy(forums.get(forum.id), today.minusDays(1)) }.quorumBasis).isZero()

        // A former member keeps only their card under "My committees".
        val former = add(forum.id, MemberInput(userId = chair.id.value, startsOn = today.minusDays(7)))
        asUser(admin) { members.end(forum.id, former.id, former.version, today.minusDays(1), EndReason.RESIGNED, null) }
        assertThatThrownBy { asUser(chair) { forums.get(forum.id) } }.isInstanceOf(ForumNotFoundException::class.java)
        val card = asUser(chair) { views.mine() }.single()
        assertThat(card.state).isEqualTo(MemberState.ENDED)
        assertThat(card.canOpen).isFalse()

        asUser(admin) { access.grantCustom(outsider.id.value, "Committee readers", setOf(Permission.FORUM_READ)) }
        assertThat(asUser(outsider) { forums.get(forum.id) }.id).isEqualTo(forum.id)

        // Classified above a reader's clearance, the committee is gone for them, administrators included.
        val levels = asUser(admin) { clearances.levels() }
        assumeThat(levels.size).isGreaterThan(1)
        val top = levels.last()
        val secret =
            asUser(admin) {
                forums.update(
                    forum.id,
                    forum.version,
                    ForumInput(forum.key, forum.code, forum.name, forum.shortName, typeId, classificationId = top.id),
                )
            }
        assertThatThrownBy { asUser(outsider) { forums.get(secret.id) } }.isInstanceOf(ForumNotFoundException::class.java)
        assertThatThrownBy { asUser(admin) { forums.get(secret.id) } }.isInstanceOf(ForumNotFoundException::class.java)
        asUser(admin) { access.ensureClearance(outsider.id.value, top.rank) }
        assertThat(asUser(outsider) { forums.get(secret.id) }.id).isEqualTo(forum.id)
    }

    @Test
    fun `a position seat follows its holder`() {
        val forum = brc()
        val jan1 = LocalDate.of(2026, 1, 1)
        val position =
            asUser(admin) {
                val entity = entities.create("Bank ${UUID.randomUUID().toString().take(6)}", null, null, jan1)
                val unit = units.createUnit(entity.id, null, "RK${(100..999).random()}", "Risk", jan1)
                positions.create(unit.id, null, "Chief Risk Officer", false, jan1).also {
                    assignments.assign(it.id, chair.id.value, Period(jan1, null))
                }
            }
        add(forum.id, MemberInput(positionId = position.id, attributes = setOf("independent")))
        assertThat(asUser(admin) { directory.membersOn(forum.id) }.single().userId).isEqualTo(chair.id.value)
        assertThat(asUser(chair) { directory.rolesOf(chair.id.value) }).containsEntry(forum.id, ForumRole.MEMBER)
        refused("ALREADY_MEMBER") { add(forum.id, MemberInput(positionId = position.id)) }
    }

    @Test
    fun `the code locks at the first numbered meeting, and the rest stays editable`() {
        val forum = brc()
        assertThat(asUser(admin) { views.forum(forums.get(forum.id)) }.codeLocked).isFalse()
        assertThat(asUser(admin) { codes.lock(forum.id) }).isEqualTo(forum.code)
        Audit.assertAudited(tenant.id, "forum.code_locked", forum.id.toString(), admin.id.value.toString())
        assertThat(asUser(admin) { codes.lock(forum.id) }).isEqualTo(forum.code)
        assertThat(asUser(admin) { views.forum(forums.get(forum.id)) }.codeLocked).isTrue()
        refused("CODE_LOCKED") {
            asUser(admin) { forums.update(forum.id, forum.version, ForumInput(forum.key, "NEW", forum.name, forum.shortName, typeId)) }
        }
        val renamed =
            asUser(admin) { forums.update(forum.id, forum.version, ForumInput(forum.key, forum.code, "Risk Committee", "Risk", typeId)) }
        assertThat(renamed.name).isEqualTo("Risk Committee")
    }

    @Test
    fun `another tenant neither sees nor writes forums or members`() {
        val forum = brc()
        add(forum.id, MemberInput(userId = member.id.value))
        val other = registry.aTestTenant(transactions, settings, "forumsother", mfaRequired = false)
        AppRoleJdbc.inTransaction(other.id) { c ->
            listOf("forum", "forum_member", "forum_type", "forum_type_version", "forum_term_reminder").forEach { table ->
                assertThat(c.long("select count(*) from $table where tenant_id = ?", tenant.id.value)).describedAs(table).isZero()
            }
        }
        val written =
            runCatching {
                AppRoleJdbc.inTransaction(other.id) { c ->
                    c.update(
                        """
                        insert into forum_member (id, tenant_id, forum_id, kind, user_id, role, voting, starts_on, created_at)
                        values (?, ?, ?, 'PERSON', ?, 'MEMBER', true, current_date, ?)
                        """,
                        UUID.randomUUID(),
                        tenant.id.value,
                        forum.id,
                        outsider.id.value,
                        OffsetDateTime.now(ZoneOffset.UTC),
                    )
                }
            }
        assertThat(written.exceptionOrNull()).isInstanceOf(SQLException::class.java)
        // The app role cannot delete a term of office even in its own tenant.
        val deleted =
            runCatching { AppRoleJdbc.inTransaction(tenant.id) { c -> c.update("delete from forum_member where forum_id = ?", forum.id) } }
        assertThat(deleted.exceptionOrNull()).isInstanceOf(SQLException::class.java)
    }

    private fun refused(
        reason: String,
        block: () -> Any,
    ) = assertThatThrownBy { block() }.isInstanceOfSatisfying(RefusedException::class.java) { assertThat(it.reason).isEqualTo(reason) }

    private fun problem(block: () -> Any): String {
        val rejected = runCatching { asUser(admin, block) }.exceptionOrNull() as? ForumRejectedException ?: error("expected a rejection")
        return rejected.problems.joinToString { "${it.field}:${it.code}" }
    }

    private fun <T> asUser(
        user: UserAccount,
        block: () -> T,
    ): T =
        ActorContext.call(ActorScope(Actor.User(user.id.value), null)) {
            TenantContext.call(tenant.id) { transactions.inTenant(tenant.id, block) }
        }
}
