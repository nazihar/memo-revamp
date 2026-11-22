package com.memo.actions

import com.memo.actions.internal.ActionReportHolds
import com.memo.actions.internal.ActionReportReads
import com.memo.actions.internal.ActionSource
import com.memo.actions.internal.ActionUpdateRequests
import com.memo.actions.internal.RaiseInput
import com.memo.actions.internal.ReportHoldRequest
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.identity.UserAccount
import com.memo.meeting.internal.AgendaOrigin
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.AppRoleJdbc.update
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.sql.SQLException
import java.time.LocalDate
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

    @Autowired lateinit var reportReads: ActionReportReads

    @Autowired lateinit var reportHolds: ActionReportHolds

    @Autowired lateinit var updateRequests: ActionUpdateRequests

    @Test
    fun `another tenant reads no action taken report, row or hold of A's, and asks no owner of A's for an update`() {
        val (next, held, kept) = reported()
        val b = registry.aTestTenant(transactions, settings, "actionsrb", mfaRequired = false)
        val bAdmin = accounts.anActiveUser(transactions, b, aPassword(), tenantAdmin = true)
        listOf<() -> Any>(
            { reportReads.report(next, null) },
            { reportReads.report(next, 1) },
            { reportReads.pdf(next, null) },
            { reportHolds.hold(kept, ReportHoldRequest("Mine now")) },
            { updateRequests.askNow(brc) },
        ).forEach { call ->
            val thrown = runCatching { inTenant(b, bAdmin, call) }.exceptionOrNull()
            assertThat(thrown?.javaClass?.simpleName).isIn("MeetingNotFoundException", "ActionNotFoundException")
        }
        val tables = listOf("actions_report", "actions_report_row", "actions_report_hold", "actions_update_ask")
        AppRoleJdbc.inTransaction(b.id) { c ->
            tables.forEach { assertThat(c.long("select count(*) from $it where tenant_id = ?", tenant.id.value)).describedAs(it).isZero() }
            val joined = "select count(*) from actions_report r join actions_report_row w on w.report_id = r.id where r.meeting_id = ?"
            assertThat(c.long(joined, next)).isZero()
        }
        reportsStayAsBuilt(b, bAdmin, next, held, kept)
    }

    /** A report of the next meeting with one action held back and one listed, and an update asked for: (meeting, held, kept). */
    private fun reported(): Triple<UUID, UUID, UUID> {
        val d = deciding()
        val held = issue(raise(d.meeting.id, d.credit, fields()))
        val created = asUser(secretary) { lifecycle.create(brc, place(), times(LocalDate.now(ZoneOffset.UTC).plusDays(14))) }
        asUser(secretary) { agendaStart.start(created.id, AgendaOrigin.TEMPLATE, null) }
        asUser(secretary) { reportHolds.hold(held.id, ReportHoldRequest("Discussed in camera", created.id)) }
        val kept = issue(raise(d.meeting.id, d.appetite, fields()))
        val next = asUser(secretary) { meeting(created.id).let { lifecycle.schedule(it.id, it.version) } }
        asUser(chair) { chairing.agree(next.id, drafts.draft(next.id).version, false, null) }
        asUser(secretary) { publisher.publish(next.id, drafts.draft(next.id).version, null, true) }
        assertThat(asUser(secretary) { reportReads.report(next.id, null) }.sections.flatMap { it.rows }.map { it.actionId })
            .containsExactly(kept.id)
        asUser(secretary) { updateRequests.askNow(brc) }
        return Triple(next.id, held.id, kept.id)
    }

    /** B's own rows pointing at A's are refused by the composite foreign keys; A's report and holds are insert only. */
    private fun reportsStayAsBuilt(
        b: TestTenant,
        bAdmin: UserAccount,
        next: UUID,
        held: UUID,
        kept: UUID,
    ) {
        val insert =
            "insert into actions_report_hold (id, tenant_id, action_id, meeting_id, reason, held_by, held_at) values (?, ?, ?, ?, 'x', ?, ?)"
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val written =
            runCatching {
                AppRoleJdbc.inTransaction(
                    b.id,
                ) { c -> c.update(insert, UUID.randomUUID(), b.id.value, kept, next, bAdmin.id.value, now) }
            }
        assertThat(written.exceptionOrNull()).isInstanceOf(SQLException::class.java)
        listOf(
            "update actions_report_row set section = 'OPEN' where action_id = ?",
            "delete from actions_report_row where action_id = ?",
            "delete from actions_report_hold where action_id = ?",
        ).forEach { sql ->
            val changed = runCatching { AppRoleJdbc.inTransaction(tenant.id) { c -> c.update(sql, held) } }
            assertThat(changed.exceptionOrNull()).describedAs(sql).isInstanceOf(SQLException::class.java)
        }
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
