package com.memo.actions

import com.memo.actions.internal.ActionSource
import com.memo.actions.internal.RaiseInput
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.identity.UserAccount
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.AppRoleJdbc.update
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Another tenant and the actions (test plan E07 T3 `ActionsCrossTenantIT`, AGENTS §3.2): B's administrator gets 404 for
 * every read and write of A's action, its meeting's actions and its committee through the services, and "My actions"
 * shows none of A's; as the app role B sees none of A's actions, contributors, entries or counters, through joins either,
 * writes none for A or pointing at A's rows and changes none; A's workflow instance is not B's subject. The app role
 * deletes nothing, rewrites no timeline entry, and cannot change an action's reference, source or original date.
 */
class ActionsCrossTenantIT : ActionFixture() {
    override fun label() = "actionsa"

    @Test
    fun `another tenant neither sees nor writes the actions, their contributors, entries or counters`() {
        val d = deciding()
        val id = raise(d.meeting.id, d.credit, fields(contributors = listOf(others[0].id.value)))
        val issued = issue(id)

        val b = registry.aTestTenant(transactions, settings, "actionsb", mfaRequired = false)
        val bAdmin = accounts.anActiveUser(transactions, b, aPassword(), tenantAdmin = true)
        listOf<() -> Any>(
            { actionAccess.action(id) },
            { actionAccess.ofMeeting(d.meeting.id) },
            { raising.raise(d.meeting.id, RaiseInput(ActionSource.MEETING, fields = fields())) },
            { raising.raiseDirect(brc, fields()) },
            { raising.edit(id, issued.version, fields()) },
            { raising.discard(id, issued.version, null) },
            { issuing.issue(id, issued.version) },
            { issuing.issuable(d.meeting.id) },
        ).forEach { call ->
            val thrown = runCatching { inTenant(b, bAdmin, call) }.exceptionOrNull()
            assertThat(thrown?.javaClass?.simpleName).isIn("MeetingNotFoundException", "ActionNotFoundException")
        }
        assertThat(inTenant(b, bAdmin) { actionAccess.mine(com.memo.actions.internal.MyActionsTab.MINE, null, null, 50) }.items).isEmpty()
        assertThat(inTenant(b, bAdmin) { workflowQueries.subjectOfInstance(checkNotNull(issued.workflowInstanceId)) ?: "none" })
            .isEqualTo("none")

        val tables = listOf("actions_item", "actions_contributor", "actions_entry", "actions_counter")
        AppRoleJdbc.inTransaction(b.id) { c ->
            tables.forEach { assertThat(c.long("select count(*) from $it where tenant_id = ?", tenant.id.value)).describedAs(it).isZero() }
            assertThat(
                c.long(
                    "select count(*) from actions_item a join actions_entry e on e.action_id = a.id where a.meeting_id = ?",
                    d.meeting.id,
                ),
            ).isZero()
            assertThat(c.update("update actions_item set title = 'B', version = version + 1 where id = ?", id)).isZero()
            assertThat(c.update("update actions_counter set last_number = last_number + 1 where forum_id = ?", brc)).isZero()
        }
        bWritesNothing(b, bAdmin, id)
        aIsIntact(id)
    }

    /** B writes no row for A or pointing at A's rows. */
    private fun bWritesNothing(
        b: TestTenant,
        bAdmin: UserAccount,
        id: UUID,
    ) {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        listOf(
            "insert into actions_item (id, tenant_id, forum_id, source, source_label, title, classification_id, status, created_by, " +
                "created_at) values (?, ?, ?, 'DIRECT', 'x', 'x', ?, 'DRAFT', ?, ?)" to
                listOf<Any>(UUID.randomUUID(), tenant.id.value, brc, UUID.randomUUID(), bAdmin.id.value, now),
            // B's own rows pointing at A's action are refused by the composite foreign keys.
            "insert into actions_entry (tenant_id, action_id, seq, kind, at) values (?, ?, 99, 'EDITED', ?)" to
                listOf<Any>(b.id.value, id, now),
            "insert into actions_contributor (id, tenant_id, action_id, user_id, added_by, added_at) values (?, ?, ?, ?, ?, ?)" to
                listOf<Any>(UUID.randomUUID(), b.id.value, id, bAdmin.id.value, bAdmin.id.value, now),
        ).forEach { (sql, params) ->
            val written = runCatching { AppRoleJdbc.inTransaction(b.id) { c -> c.update(sql, *params.toTypedArray()) } }
            assertThat(written.exceptionOrNull()).describedAs(sql).isInstanceOf(SQLException::class.java)
        }
    }

    /** A's rows are intact, and the app role deletes nothing and rewrites no entry, reference, source or date. */
    private fun aIsIntact(id: UUID) {
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            assertThat(c.long("select count(*) from actions_entry where action_id = ?", id)).isEqualTo(2)
            assertThat(c.long("select count(*) from actions_contributor where action_id = ?", id)).isEqualTo(1)
        }
        listOf(
            "delete from actions_item where id = ?",
            "update actions_entry set kind = 'X' where action_id = ?",
            "update actions_item set reference = 'BRC A99/2026', version = version + 1 where id = ?",
            "update actions_item set source = 'DIRECT', version = version + 1 where id = ?",
            "update actions_item set original_due_date = original_due_date + 1, version = version + 1 where id = ?",
        ).forEach { sql ->
            val written = runCatching { AppRoleJdbc.inTransaction(tenant.id) { c -> c.update(sql, id) } }
            assertThat(written.exceptionOrNull()).describedAs(sql).isInstanceOf(SQLException::class.java)
        }
    }

    private fun <T> inTenant(
        tenant: TestTenant,
        account: UserAccount,
        block: () -> T,
    ): T =
        ActorContext.call(ActorScope(Actor.User(account.id.value), null)) {
            TenantContext.call(tenant.id) { transactions.inTenant(tenant.id, block) }
        }
}
