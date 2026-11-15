package com.memo.memo

import com.memo.authz.internal.GrantAdmin
import com.memo.authz.internal.RoleAdmin
import com.memo.common.authz.Permission
import com.memo.common.tenant.TenantDsl
import com.memo.identity.UserAccount
import com.memo.jooq.tables.references.MEMO_PARTICIPANT
import com.memo.memo.internal.MemoStore
import com.memo.memo.internal.ParticipantKind
import com.memo.org.internal.LegalEntities
import com.memo.org.internal.OrgUnits
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Random
import java.util.UUID
import com.memo.jooq.tables.references.MEMO_ as MEMO

/**
 * The read rule as a property (ADR-0050, confirmation): for random memos (classification, origin unit), random ways in
 * (participants of every kind, revoked rows, lapsed and running shares) and random readers (clearance, unit grants),
 * [MemoStore.visible] answers exactly what a plain Kotlin reference of the rule answers. Several seeds, one tenant each.
 */
class MemoReadableIT : MemoIntegrationTest() {
    @Autowired lateinit var store: MemoStore

    @Autowired lateinit var dsl: TenantDsl

    @Autowired lateinit var roles: RoleAdmin

    @Autowired lateinit var grants: GrantAdmin

    @Autowired lateinit var entities: LegalEntities

    @Autowired lateinit var units: OrgUnits

    private enum class Way { NONE, APPROVER, COPY, SHARED_RUNNING, SHARED_LAPSED, REVOKED }

    private data class Reader(
        val account: UserAccount,
        val clearance: Int,
        /** The unit subtree root of their memo.read.unit grant, if any. */
        val scope: UUID?,
    )

    @Test
    fun `the SQL read rule equals the reference for random memos, ways in and readers`() {
        listOf(7L, 42L, 2026L).forEach { seed -> scenario(Random(seed)) }
    }

    private fun scenario(random: Random) {
        val jan1 = LocalDate.of(2026, 1, 1)
        // A tree: head > division > desk, and a branch beside the division.
        val tree =
            asUser(admin) {
                val entity = entities.create("Bank ${UUID.randomUUID().toString().take(6)}", null, null, jan1)
                val head = units.createUnit(entity.id, null, "H${random.nextInt(900) + 100}", "Head", jan1)
                val division = units.createUnit(entity.id, head.id, "D${random.nextInt(900) + 100}", "Division", jan1)
                val desk = units.createUnit(entity.id, division.id, "K${random.nextInt(900) + 100}", "Desk", jan1)
                val branch = units.createUnit(entity.id, head.id, "B${random.nextInt(900) + 100}", "Branch", jan1)
                mapOf(head.id to null, division.id to head.id, desk.id to division.id, branch.id to head.id)
            }
        val unitIds = tree.keys.toList()
        val initiator = person(clearance = 2)
        val memos =
            (1..MEMOS).map {
                val rank = random.nextInt(LEVELS)
                val id = UUID.fromString(aDraft(initiator, rank))
                val origin = unitIds[random.nextInt(unitIds.size)].takeIf { random.nextInt(4) > 0 }
                transactions.inTenant(
                    tenant.id,
                ) {
                    dsl
                        .current()
                        .update(MEMO)
                        .set(MEMO.ORIGIN_UNIT_ID, origin)
                        .where(MEMO.ID.eq(id))
                        .execute()
                }
                Triple(id, rank, origin)
            }
        val readerRole = asUser(admin) { roles.create("Reader ${UUID.randomUUID()}", null, setOf(Permission.MEMO_READ_UNIT)) }
        val readers =
            (1..READERS).map {
                val account = accounts.anActiveUser(transactions, tenant, aPassword())
                val clearance = random.nextInt(LEVELS)
                if (clearance > 0) clear(account.id.value, clearance)
                val scope = unitIds[random.nextInt(unitIds.size)].takeIf { random.nextInt(3) == 0 }
                if (scope != null) asUser(admin) { grants.grant(account.id.value, readerRole.id, scope) }
                Reader(account, clearance, scope)
            }
        val ways =
            readers
                .flatMap { r ->
                    memos.map { m -> (r.account.id.value to m.first) to Way.entries[random.nextInt(Way.entries.size)] }
                }.toMap()
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        transactions.inTenant(tenant.id) {
            ways.forEach { (key, way) -> plant(key.second, key.first, way, now) }
        }

        fun covers(
            scope: UUID?,
            unit: UUID?,
        ): Boolean = scope != null && unit != null && generateSequence(unit) { tree[it] }.any { it == scope }

        readers.forEach { reader ->
            memos.forEach { (memo, rank, origin) ->
                val way = ways.getValue(reader.account.id.value to memo)
                val participant = way in setOf(Way.APPROVER, Way.COPY, Way.SHARED_RUNNING)
                val expected = rank <= reader.clearance && (participant || covers(reader.scope, origin))
                val actual = asUser(reader.account) { store.visible(memo, reader.account.id.value, reader.clearance) != null }
                assertThat(actual).describedAs("reader ${reader.clearance}/${reader.scope}, memo $rank/$origin, $way").isEqualTo(expected)
            }
        }
    }

    private fun plant(
        memo: UUID,
        user: UUID,
        way: Way,
        now: OffsetDateTime,
    ) {
        when (way) {
            Way.NONE -> {}

            Way.APPROVER -> {
                store.addParticipant(memo, user, ParticipantKind.APPROVER, now)
            }

            Way.COPY -> {
                store.addParticipant(memo, user, ParticipantKind.COPY, now)
            }

            Way.SHARED_RUNNING -> {
                store.addParticipant(memo, user, ParticipantKind.SHARED, now, user, now.plusDays(1))
            }

            Way.SHARED_LAPSED -> {
                store.addParticipant(memo, user, ParticipantKind.SHARED, now.minusDays(2), user, now.minusMinutes(1))
            }

            Way.REVOKED -> {
                store.addParticipant(memo, user, ParticipantKind.APPROVER, now)
                dsl
                    .current()
                    .update(MEMO_PARTICIPANT)
                    .set(MEMO_PARTICIPANT.REVOKED_AT, now)
                    .where(MEMO_PARTICIPANT.MEMO_ID.eq(memo), MEMO_PARTICIPANT.USER_ID.eq(user))
                    .execute()
            }
        }
    }

    private companion object {
        const val MEMOS = 8
        const val READERS = 6
        const val LEVELS = 3
    }
}
