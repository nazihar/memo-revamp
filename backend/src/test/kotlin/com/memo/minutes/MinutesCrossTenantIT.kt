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
 * entries, notes, numbers or counters, through joins either, writes none for A or pointing at A's rows (E06 T5: nor the
 * minutes, their page, their drafting or their history),
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

    @Autowired lateinit var minutesDrafts: com.memo.minutes.internal.MinutesDrafts

    @Autowired lateinit var minutesPages: com.memo.minutes.internal.MinutesPages

    @Autowired lateinit var minutesStore: com.memo.minutes.internal.MinutesStore

    @Test
    fun `another tenant neither sees nor drafts the minutes, nor reads or writes their rows and history`() {
        val d = deciding()
        arrive(d.meeting, listOf(chair, director, director2, others[0], others[1]))
        val held = meeting(d.meeting.id).let { asUser(secretary) { lifecycle.end(it.id, it.version) } }
        // E06 T5: the minutes exist from the meeting's end, with a line of history.
        asUser(secretary) {
            minutesStore.insertEvent(
                com.memo.minutes.internal.MinutesEventRow(
                    UUID.randomUUID(),
                    held.id,
                    com.memo.minutes.internal.MinutesEventKind.RETURNED_TO_DRAFT,
                    d.credit,
                    secretary.id.value,
                    null,
                    null,
                    java.time.Instant.now(),
                ),
            )
        }
        assertThat(asUser(secretary) { minutesPages.view(held.id) }.state.name).isEqualTo("DRAFT")

        val b = registry.aTestTenant(transactions, settings, "minutesc", mfaRequired = false)
        val bAdmin = accounts.anActiveUser(transactions, b, aPassword(), tenantAdmin = true)
        listOf<() -> Any>(
            { minutesPages.view(held.id) },
            { minutesDrafts.request(held.id, null, true) },
            { minutesDrafts.request(held.id, d.credit, false) },
        ).forEach { call ->
            val thrown = runCatching { inTenant(b, bAdmin, call) }.exceptionOrNull()
            assertThat(thrown?.javaClass?.simpleName).isIn("MeetingNotFoundException", "MinutesNotFoundException")
        }
        AppRoleJdbc.inTransaction(b.id) { c ->
            listOf("minutes", "minutes_event").forEach {
                assertThat(c.long("select count(*) from $it where tenant_id = ?", tenant.id.value)).describedAs(it).isZero()
            }
            assertThat(c.update("update minutes set version = version + 1 where meeting_id = ?", held.id)).isZero()
        }
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        listOf(
            "insert into minutes (tenant_id, meeting_id, forum_id, policy, held_at, created_at) values (?, ?, ?, '{}'::jsonb, ?, ?)" to
                listOf<Any>(tenant.id.value, UUID.randomUUID(), brc, now, now),
            // B's history pointing at A's minutes is refused by the composite foreign key.
            "insert into minutes_event (id, tenant_id, meeting_id, kind, at) values (?, ?, ?, 'GENERATED', ?)" to
                listOf<Any>(UUID.randomUUID(), b.id.value, held.id, now),
        ).forEach { (sql, params) ->
            val written = runCatching { AppRoleJdbc.inTransaction(b.id) { c -> c.update(sql, *params.toTypedArray()) } }
            assertThat(written.exceptionOrNull()).describedAs(sql).isInstanceOf(SQLException::class.java)
        }
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            assertThat(c.long("select count(*) from minutes_event where meeting_id = ?", held.id)).isEqualTo(1)
            assertThat(c.long("select version from minutes where meeting_id = ?", held.id)).isZero()
        }
    }

    @Autowired lateinit var minutesReview: com.memo.minutes.internal.MinutesReview

    @Autowired lateinit var minutesComments: com.memo.minutes.internal.MinutesComments

    @Autowired lateinit var minutesReading: com.memo.minutes.internal.MinutesReading

    @Autowired lateinit var minutesConfirmation: com.memo.minutes.internal.MinutesConfirmation

    @Autowired lateinit var minutesRecordReading: com.memo.minutes.internal.MinutesRecordReading

    @Test
    fun `another tenant neither reviews, reads, comments on nor confirms the minutes, nor sees their T6 rows`() {
        val d = deciding()
        arrive(d.meeting, listOf(chair, director, director2, others[0], others[1]))
        val held = meeting(d.meeting.id).let { asUser(secretary) { lifecycle.end(it.id, it.version) } }
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val hash = "a".repeat(64)
        // E06 T6: A's comment, request for comments, copy, approval and reminder (as the app role in A).
        val rows = t6Rows(held.id, d.credit, now, hash)
        rows.values.forEach { (sql, params) ->
            committed(sql, params)
        }

        val b = registry.aTestTenant(transactions, settings, "minutesd", mfaRequired = false)
        val bAdmin = accounts.anActiveUser(transactions, b, aPassword(), tenantAdmin = true)
        listOf<() -> Any>(
            { minutesReview.sendForReview(held.id, 0) },
            { minutesReview.circulate(held.id, 0, null, "No chair") },
            { minutesReview.withdraw(held.id, 0, "x") },
            { minutesReview.endCirculation(held.id, 0, "x") },
            { minutesComments.comment(held.id, com.memo.minutes.internal.CommentKind.NO_COMMENTS, null, null) },
            { minutesComments.commentsFor(held.id) },
            { minutesReading.document(held.id) },
            { minutesReading.preview(held.id, director.id.value) },
            { minutesConfirmation.requireConfirmer(held.id) },
            { minutesConfirmation.approvalsOf(held.id) },
            // E06 T7: the record, its versions and its check.
            { minutesRecordReading.serve(held.id, null) ?: "building" },
            { minutesRecordReading.versions(held.id) },
            { minutesRecordReading.approvedReader(held.id) },
        ).forEach { call ->
            val thrown = runCatching { inTenant(b, bAdmin, call) }.exceptionOrNull()
            assertThat(thrown?.javaClass?.simpleName).isIn("MeetingNotFoundException", "MinutesNotFoundException")
        }
        AppRoleJdbc.inTransaction(b.id) { c ->
            rows.keys.forEach {
                assertThat(
                    c.long("select count(*) from $it where tenant_id = ?", tenant.id.value),
                ).describedAs(it).isZero()
            }
            assertThat(c.update("update minutes_comment set response = 'ADOPTED' where meeting_id = ?", held.id)).isZero()
            assertThat(
                c.update("update minutes_copy set state = 'FAILED', error = 'X', version = version + 1 where meeting_id = ?", held.id),
            ).isZero()
        }
        // B's rows pointing at A's minutes are refused by the composite foreign keys.
        rows.values.forEach { (sql, params) ->
            val forB =
                params
                    .map {
                        if (it ==
                            tenant.id.value
                        ) {
                            b.id.value
                        } else {
                            it
                        }
                    }.map { if (it is UUID && it != held.id && it != b.id.value) UUID.randomUUID() else it }
            val written = runCatching { AppRoleJdbc.inTransaction(b.id) { c -> c.update(sql, *forB.toTypedArray()) } }
            assertThat(written.exceptionOrNull()).describedAs(sql).isInstanceOf(SQLException::class.java)
        }
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            rows.keys.forEach { assertThat(c.long("select count(*) from $it where meeting_id = ?", held.id)).describedAs(it).isOne() }
        }
    }

    @Autowired lateinit var recordRetries: com.memo.minutes.internal.RecordRetries

    @Autowired lateinit var minutesCorrections: com.memo.minutes.internal.MinutesCorrectionsService

    @Autowired lateinit var resolutionExtracts: com.memo.minutes.internal.ResolutionExtracts

    @Test
    fun `another tenant neither proposes, decides nor reads corrections or extracts, nor sees or writes their rows`() {
        val d = deciding()
        arrive(d.meeting, listOf(chair, director, director2, others[0], others[1]))
        val resolved = record(d, d.credit, DecisionInput(OutcomeKind.APPROVED, text = "It was resolved."))
        val number = checkNotNull(asUser(secretary) { resolutionNumbers.assign(resolved) })
        val held = meeting(d.meeting.id).let { asUser(secretary) { lifecycle.end(it.id, it.version) } }
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        // E06 T8: A's correction and extract (as the app role in A).
        val rows = t8Rows(held.id, number.id, now)
        rows.values.forEach { (sql, params) -> committed(sql, params) }

        val b = registry.aTestTenant(transactions, settings, "minutese", mfaRequired = false)
        val bAdmin = accounts.anActiveUser(transactions, b, aPassword(), tenantAdmin = true)
        listOf<() -> Any>(
            { minutesCorrections.list(held.id) },
            {
                minutesCorrections.propose(
                    held.id,
                    com.memo.minutes.internal
                        .MinutesCorrectionInput(null, "Written by B", "B"),
                )
            },
            { minutesCorrections.requireApprover(held.id, UUID.randomUUID()) },
            { resolutionExtracts.list(number.id) },
            { resolutionExtracts.requireIssuer(number.id) },
            { resolutionExtracts.serve(number.id, UUID.randomUUID()) ?: "building" },
            // The fix after E08 T10: "Make the record again".
            { recordRetries.retry(held.id) },
        ).forEach { call ->
            val thrown = runCatching { inTenant(b, bAdmin, call) }.exceptionOrNull()
            assertThat(thrown?.javaClass?.simpleName).isIn("MeetingNotFoundException", "MinutesNotFoundException")
        }
        // The published read finds nothing of A in B.
        assertThat(inTenant(b, bAdmin) { minutesCorrections.since(brc, java.time.Instant.EPOCH) }).isEmpty()
        AppRoleJdbc.inTransaction(b.id) { c ->
            rows.keys.forEach {
                assertThat(c.long("select count(*) from $it where tenant_id = ?", tenant.id.value)).describedAs(it).isZero()
            }
            assertThat(
                c.update(
                    "update minutes_correction set state = 'DECLINED', decided_by = ?, decided_at = now(), version = version + 1",
                    bAdmin.id.value,
                ),
            ).isZero()
            assertThat(c.update("update minutes_extract set state = 'FAILED', error = 'X', version = version + 1")).isZero()
        }
        // B's rows pointing at A's minutes or resolution are refused by the composite foreign keys.
        rows.values.forEach { (sql, params) ->
            val forB = params.map { if (it == tenant.id.value) b.id.value else it }
            val written = runCatching { AppRoleJdbc.inTransaction(b.id) { c -> c.update(sql, *forB.toTypedArray()) } }
            assertThat(written.exceptionOrNull()).describedAs(sql).isInstanceOf(SQLException::class.java)
        }
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            rows.keys.forEach { assertThat(c.long("select count(*) from $it where meeting_id = ?", held.id)).describedAs(it).isOne() }
        }
    }

    /** Writes [sql] with [params] as the app role in A, committed (AppRoleJdbc's transactions roll back). */
    private fun committed(
        sql: String,
        params: List<Any>,
    ) {
        AppRoleJdbc.connect { c ->
            c.autoCommit = false
            c.prepareStatement("select set_config('app.tenant_id', ?, true)").use {
                it.setString(1, tenant.id.toString())
                it.execute()
            }
            assertThat(c.update(sql, *params.toTypedArray())).isOne()
            c.commit()
        }
    }

    /** A row of each T6 table for [held]'s minutes as the app role in A writes it: the SQL and its parameters, by table. */
    private fun t6Rows(
        held: UUID,
        credit: UUID,
        now: OffsetDateTime,
        hash: String,
    ): Map<String, Pair<String, List<Any>>> =
        mapOf(
            "minutes_comment" to
                (
                    "insert into minutes_comment (id, tenant_id, meeting_id, user_id, kind, at) " +
                        "values (?, ?, ?, ?, 'NO_COMMENTS', ?)" to
                        listOf<Any>(UUID.randomUUID(), tenant.id.value, held, director.id.value, now)
                ),
            "minutes_comment_request" to
                (
                    "insert into minutes_comment_request (id, tenant_id, meeting_id, user_id, item_ids, asked_by, asked_at) " +
                        "values (?, ?, ?, ?, array[?]::uuid[], ?, ?)" to
                        listOf<Any>(
                            UUID.randomUUID(),
                            tenant.id.value,
                            held,
                            director.id.value,
                            credit,
                            secretary.id.value,
                            now,
                        )
                ),
            "minutes_copy" to
                (
                    "insert into minutes_copy (id, tenant_id, meeting_id, stage, redacted, set_key, source_version_id, created_at) " +
                        "values (?, ?, ?, 'DRAFT', '[]'::jsonb, ?, ?, ?)" to
                        listOf<Any>(UUID.randomUUID(), tenant.id.value, held, hash, UUID.randomUUID(), now)
                ),
            "minutes_approval" to
                (
                    "insert into minutes_approval (id, tenant_id, meeting_id, forum_id, kind, actor_id, role, authentication, " +
                        "client, " +
                        "docx_version_id, docx_sha256, record_hash, at) values (?, ?, ?, ?, 'CONFIRMATION', ?, 'CHAIR', '{}'::jsonb, " +
                        "'{}'::jsonb, ?, ?, ?, ?)" to
                        listOf<Any>(
                            UUID.randomUUID(),
                            tenant.id.value,
                            held,
                            brc,
                            chair.id.value,
                            UUID.randomUUID(),
                            hash,
                            hash,
                            now,
                        )
                ),
            "minutes_reminder" to
                (
                    "insert into minutes_reminder (tenant_id, meeting_id, kind, user_id, key, sent_at) " +
                        "values (?, ?, 'DRAFT_OVERDUE', ?, 'once', ?)" to
                        listOf<Any>(tenant.id.value, held, secretary.id.value, now)
                ),
        )

    /** A row of each T8 table for [held]'s minutes and [resolution] as the app role in A writes it, by table. */
    private fun t8Rows(
        held: UUID,
        resolution: UUID,
        now: OffsetDateTime,
    ): Map<String, Pair<String, List<Any>>> =
        mapOf(
            "minutes_correction" to
                (
                    "insert into minutes_correction (id, tenant_id, meeting_id, forum_id, text, reason, proposed_by, proposed_at) " +
                        "values (?, ?, ?, ?, 'Kept by A', 'A reason', ?, ?)" to
                        listOf<Any>(UUID.randomUUID(), tenant.id.value, held, brc, secretary.id.value, now)
                ),
            "minutes_extract" to
                (
                    "insert into minutes_extract (id, tenant_id, resolution_id, meeting_id, forum_id, issued_by, issued_at, " +
                        "minutes_state, certification, certification_hash) values (?, ?, ?, ?, ?, ?, ?, 'NOT_APPROVED', " +
                        "'{}'::jsonb, ?)" to
                        listOf<Any>(
                            UUID.randomUUID(),
                            tenant.id.value,
                            resolution,
                            held,
                            brc,
                            secretary.id.value,
                            now,
                            "a".repeat(64),
                        )
                ),
        )

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
