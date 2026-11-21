package com.memo.minutes

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.forum.OutcomeKind
import com.memo.identity.UserAccount
import com.memo.minutes.internal.DecisionInput
import com.memo.minutes.internal.ResolutionAccess
import com.memo.minutes.internal.ResolutionNumbers
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
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Another tenant and the decisions (test plan E06 T3 `MinutesCrossTenantIT`, AGENTS §3.2): B's administrator gets 404 for
 * every read and write of A's decisions, history, notes, consent section, committee and resolutions through the services
 * (E06 T4: the register, its check, one resolution, the starting number); as the app role B sees none of A's decisions,
 * entries, notes, numbers or counters, through joins either, writes none for A or pointing at A's rows,
 * and changes none.
 */
class MinutesCrossTenantIT : DecisionFixture() {
    @Autowired lateinit var resolutionNumbers: ResolutionNumbers

    @Autowired lateinit var resolutionAccess: ResolutionAccess

    @Autowired lateinit var resolutionsApi: Resolutions

    override fun label() = "minutesa"

    @Test
    fun `another tenant neither sees nor writes the decisions, their entries or the notes`() {
        val d = deciding()
        arrive(d.meeting, listOf(chair, director, director2, others[0], others[1]))
        val decided = record(d, d.appetite, DecisionInput(OutcomeKind.NOTED))
        asUser(secretary) { notes.save(d.meeting.id, d.appetite, 0, "Kept by A") }
        // E06 T4: a resolution numbered, as at the meeting's end.
        val resolved = record(d, d.credit, DecisionInput(OutcomeKind.APPROVED, text = "It was resolved."))
        val number = checkNotNull(asUser(secretary) { resolutionNumbers.assign(resolved) })

        val b = registry.aTestTenant(transactions, settings, "minutesb", mfaRequired = false)
        val bAdmin = accounts.anActiveUser(transactions, b, aPassword(), tenantAdmin = true)
        listOf<() -> Any>(
            { minutesAccess.decisions(d.meeting.id) },
            { minutesAccess.history(d.meeting.id, d.appetite) },
            { minutesAccess.toBeRatified(brc) },
            { recorder.record(d.meeting.id, d.appetite, DecisionInput(OutcomeKind.APPROVED, text = "Resolved", reason = "x"), 0) },
            { consent.decide(d.meeting.id, d.consentSection, null, false) },
            { notes.read(d.meeting.id, d.appetite) ?: "none" },
            { notes.save(d.meeting.id, d.appetite, 1, "Written by B") },
            { resolutionAccess.register(brc, null, null, null, 50) },
            { resolutionAccess.check(brc, null) },
            { resolutionAccess.resolution(number.id) },
            { resolutionAccess.start(brc, number.period, 7) },
        ).forEach { call ->
            val thrown = runCatching { inTenant(b, bAdmin, call) }.exceptionOrNull()
            assertThat(thrown?.javaClass?.simpleName).isIn("MeetingNotFoundException", "MinutesNotFoundException")
        }
        // The published reads find nothing of A in B.
        assertThat(inTenant(b, bAdmin) { decisionsApi.find(decided.decision.id) ?: "none" }).isEqualTo("none")
        assertThat(inTenant(b, bAdmin) { decisionsApi.of(d.meeting.id) }).isEmpty()
        assertThat(inTenant(b, bAdmin) { resolutionsApi.find(number.id) ?: "none" }).isEqualTo("none")
        assertThat(inTenant(b, bAdmin) { resolutionsApi.check(brc, number.period) ?: "none" }).isEqualTo("none")

        val tables =
            listOf("minutes_decision", "minutes_decision_entry", "minutes_item_note", "minutes_resolution", "minutes_resolution_counter")
        AppRoleJdbc.inTransaction(b.id) { c ->
            tables.forEach { assertThat(c.long("select count(*) from $it where tenant_id = ?", tenant.id.value)).describedAs(it).isZero() }
            assertThat(
                c.long(
                    "select count(*) from minutes_decision d join minutes_decision_entry e on e.decision_id = d.id where d.meeting_id = ?",
                    d.meeting.id,
                ),
            ).isZero()
            assertThat(c.update("update minutes_decision set version = version + 1 where id = ?", decided.decision.id)).isZero()
            assertThat(c.update("update minutes_resolution_counter set last_number = last_number + 1 where forum_id = ?", brc)).isZero()
            assertThat(c.update("update minutes_resolution set state = 'VOID' where id = ?", number.id)).isZero()
        }
        refusedWrites(b, bAdmin, d, decided.decision.id, resolved.decision.id, number.period)
        // A's rows are intact.
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            assertThat(c.long("select count(*) from minutes_decision_entry where decision_id = ?", decided.decision.id)).isEqualTo(1)
            assertThat(c.long("select count(*) from minutes_item_note where item_id = ?", d.appetite)).isEqualTo(1)
            assertThat(c.long("select count(*) from minutes_resolution where id = ? and state = 'IN_FORCE'", number.id)).isEqualTo(1)
        }
    }

    /** As the app role in [b], no row is written for A or pointing at A's rows. */
    private fun refusedWrites(
        b: TestTenant,
        bAdmin: UserAccount,
        d: Deciding,
        decisionId: UUID,
        resolvedId: UUID,
        period: String,
    ) {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        listOf(
            "insert into minutes_decision (id, tenant_id, meeting_id, item_id, forum_id, current_seq) values (?, ?, ?, ?, ?, 1)" to
                listOf<Any>(UUID.randomUUID(), tenant.id.value, d.meeting.id, UUID.randomUUID(), brc),
            "insert into minutes_item_note (tenant_id, meeting_id, item_id, seq, text, saved_by, saved_at) " +
                "values (?, ?, ?, 2, 'x', ?, ?)" to
                listOf<Any>(tenant.id.value, d.meeting.id, d.appetite, bAdmin.id.value, now),
            // B's own decision row pointing at A's decision is refused by the composite foreign key.
            "insert into minutes_decision_entry (tenant_id, decision_id, seq, outcome_kind, outcome_label, resolution, vote_mode, " +
                "snapshot_id, ratification_required, decided_at, correction, recorded_by, recorded_at) " +
                "values (?, ?, 2, 'NOTED', 'Noted', false, 'CONSENSUS', ?, false, ?, true, ?, ?)" to
                listOf<Any>(b.id.value, decisionId, UUID.randomUUID(), now, bAdmin.id.value, now),
            // A's counter, and B's number pointing at A's decision.
            "insert into minutes_resolution_counter (tenant_id, forum_id, period, last_number) values (?, ?, '2099', 1)" to
                listOf<Any>(tenant.id.value, brc),
            "insert into minutes_resolution (id, tenant_id, decision_id, forum_id, meeting_id, period, sequence, number, numbered_at) " +
                "values (?, ?, ?, ?, ?, ?, 99, 'B/99', ?)" to
                listOf<Any>(UUID.randomUUID(), b.id.value, resolvedId, brc, d.meeting.id, period, now),
        ).forEach { (sql, params) ->
            val written = runCatching { AppRoleJdbc.inTransaction(b.id) { c -> c.update(sql, *params.toTypedArray()) } }
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
