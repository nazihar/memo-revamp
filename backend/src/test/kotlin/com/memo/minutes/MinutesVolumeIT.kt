package com.memo.minutes

import com.memo.forum.MinutesApproval
import com.memo.forum.MinutesPolicy
import com.memo.forum.OutcomeKind
import com.memo.meeting.AttendanceMode
import com.memo.meeting.MeetingViewers
import com.memo.meeting.MeetingVolume
import com.memo.meeting.MeetingVolumeIT
import com.memo.meeting.MeetingVolumeSupport
import com.memo.meeting.internal.AgendaNoticeScheduler
import com.memo.meeting.internal.MeetingLifecycle
import com.memo.meeting.internal.MeetingReads
import com.memo.meeting.internal.MeetingStore
import com.memo.meeting.internal.MeetingSweep
import com.memo.meeting.internal.attendance.Attendance
import com.memo.meeting.internal.invite.InvitationScheduler
import com.memo.minutes.internal.ConsentDecisions
import com.memo.minutes.internal.CopyLookup
import com.memo.minutes.internal.DecisionInput
import com.memo.minutes.internal.DecisionRecorder
import com.memo.minutes.internal.Dissent
import com.memo.minutes.internal.MinutesAccess
import com.memo.minutes.internal.MinutesJobs
import com.memo.minutes.internal.MinutesPages
import com.memo.minutes.internal.MinutesPeople
import com.memo.minutes.internal.MinutesReading
import com.memo.minutes.internal.MinutesReleaseScheduler
import com.memo.minutes.internal.MinutesStore
import com.memo.minutes.internal.MinutesSweep
import com.memo.minutes.internal.ResolutionAccess
import com.memo.minutes.internal.VoteMode
import com.memo.testsupport.Latency
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * E06's read and write paths at the design's volume (test plan E06 T9 `MinutesVolumeIT`, design E06 §8): [MeetingVolume]
 * with E05's rows and [MinutesVolume] over it (55,000 decisions, 70,000 entries, 30,000 resolutions, 3,240 minutes with
 * 7,000 copies and 20,000 comments; the weekly `load` stage sets `MEETING_VOLUME`, and the test is skipped without it),
 * measured through the services each endpoint calls. Every statement a path runs is recorded and `EXPLAIN ANALYZE`d: none
 * may scan a minutes table, or one of the meeting volume's large tables, sequentially. Budgets have 2x headroom for
 * shared CI runners; the p95 values are printed for the epic log.
 *
 * The writes (a decision in progress, a consent section, a decision numbered at once after the meeting) run in a
 * transaction that is rolled back, so each run decides the same items on the same volume; the rollback is timed with them
 * (a commit is not). The jobs at their own budget sizes (the release at 60 decisions, the draft, the copies, the record)
 * are [MinutesJobsVolumeIT]'s.
 */
@EnabledIfEnvironmentVariable(named = "MEETING_VOLUME", matches = "[1-9][0-9]*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MinutesVolumeIT : MeetingVolumeSupport() {
    @Autowired lateinit var json: JsonMapper

    @Autowired lateinit var decisionAccess: MinutesAccess

    @Autowired lateinit var decisionRecorder: DecisionRecorder

    @Autowired lateinit var consent: ConsentDecisions

    @Autowired lateinit var resolutions: ResolutionAccess

    @Autowired lateinit var reads: MeetingReads

    @Autowired lateinit var pages: MinutesPages

    @Autowired lateinit var minutesStore: MinutesStore

    @Autowired lateinit var minutesPeople: MinutesPeople

    @Autowired lateinit var reading: MinutesReading

    @Autowired lateinit var minutesSweep: MinutesSweep

    @Autowired lateinit var meetingSweep: MeetingSweep

    @Autowired lateinit var meetingViewers: MeetingViewers

    @Autowired lateinit var lifecycle: MeetingLifecycle

    @Autowired lateinit var meetingStore: MeetingStore

    @Autowired lateinit var attendance: Attendance

    @Autowired lateinit var invitationJobs: InvitationScheduler

    @Autowired lateinit var agendaJobs: AgendaNoticeScheduler

    @Autowired lateinit var minutesJobs: MinutesJobs

    @Autowired lateinit var releaseJobs: MinutesReleaseScheduler

    override val large: List<String> = MeetingVolumeIT.LARGE + E05_LARGE + MINUTES_LARGE

    /** The first committee's last held meeting (50 items, minutes circulated) and its next one, in progress. */
    private lateinit var held: UUID
    private lateinit var focal: UUID

    @BeforeAll
    fun generate() {
        // Nothing here waits for the jobs, and other test contexts share the scheduler.
        jobs(false)
        generate("minutesvol", e05 = true)
        held = volume.held
        focal = volume.focal
        val started = System.nanoTime()
        val stages =
            MinutesVolume(
                tenant.id.value,
                people,
                focal,
                held,
                json.writeValueAsString(MinutesPolicy()),
                json.writeValueAsString(MinutesPolicy(approval = MinutesApproval.BY_CIRCULATION)),
            ).generate()
        // The first committee's seats were moved by SQL (their start dates): its cached roster is stale.
        invalidation.invalidate("forum.roster", tenant.id, focalForum.toString())
        println("MinutesVolumeIT generated the minutes in ${(System.nanoTime() - started) / NANOS_PER_MILLI} ms: $stages")
        transactions.readInTenant(tenant.id) {
            val rows = MinutesVolume.TABLES.associateWith { tenantDsl.current().fetchValue("select count(*) from $it") }
            println("MinutesVolumeIT rows: $rows")
        }
        startFocal()
        directorsCopy()
    }

    @AfterAll
    fun jobsBack() = jobs(true)

    @Test
    @Order(1)
    fun `the decisions of a held meeting and of one in progress answer within budget`() {
        val decided = asUser(people.secretary) { decisionAccess.decisions(held) }.decisions
        assertThat(decided).hasSizeGreaterThan(HELD_DECISIONS)
        assertThat(decided.mapNotNull { it.resolutionNumber }).isNotEmpty
        measure("decisions list (held, 50 items, secretary)", DECISIONS_BUDGET_MS, people.secretary) { decisionAccess.decisions(held) }
        measure("decisions list (held, director)", DECISIONS_BUDGET_MS, people.director) { decisionAccess.decisions(held) }
        measure("decisions list (held, chair)", DECISIONS_BUDGET_MS, people.chair) { decisionAccess.decisions(held) }
        assertThat(asUser(people.secretary) { decisionAccess.decisions(focal) }.decisions).hasSizeGreaterThan(FOCAL_DECISIONS)
        measure("decisions list (in progress, 60 items, secretary)", DECISIONS_BUDGET_MS, people.secretary) {
            decisionAccess.decisions(focal)
        }
    }

    @Test
    @Order(2)
    fun `recording a decision, a consent section and a decision after the meeting answer within budget`() {
        val item = undecided(focal, "SUBMISSION")
        val input =
            DecisionInput(
                outcome = OutcomeKind.APPROVED_WITH_CONDITIONS,
                text = "It was RESOLVED that the term loan of BDT 60 million be approved.",
                conditions = listOf("Security perfected before disbursement."),
                voteMode = VoteMode.VOTE,
                votesFor = VOTES_FOR,
                votesAgainst = 1,
                votesAbstain = 1,
                dissent = listOf(Dissent(member(DISSENTING), "The sector outlook is too weak.")),
                abstentions = listOf(member(ABSTAINING)),
            )
        // As the endpoint answers: the decision recorded, then the item's decision as the secretary sees it.
        measureRolledBack("record a decision (in progress, 60 items, 40 people)", RECORD_BUDGET_MS, people.secretary) {
            decisionRecorder.record(focal, item, input, null)
            decisionAccess.decision(focal, item)
        }
        val section = volume.generated("mvs$focal-2")
        val consentDecided = rolledBack(people.secretary) { consent.decide(focal, section, null, false) }
        assertThat(checkNotNull(consentDecided)).hasSizeGreaterThanOrEqualTo(CONSENT_ITEMS)
        measureRolledBack("consent section (in progress, ${consentDecided.size} items)", CONSENT_BUDGET_MS, people.secretary) {
            consent.decide(focal, section, null, false)
        }
        val late = undecided(held, "ITEM")
        val at = checkNotNull(asUser(people.secretary) { meetingStore.find(held) }).startsAt.plus(Duration.ofMinutes(DECIDED_AFTER))
        val after = DecisionInput(OutcomeKind.APPROVED, text = "It was RESOLVED that the policy be adopted.", decidedAt = at)
        val numbered = rolledBack(people.secretary) { decisionRecorder.record(held, late, after.copy(returnMinutesToDraft = true), null) }
        assertThat(checkNotNull(numbered).decision.itemId).isEqualTo(late)
        measureRolledBack(
            "record after the meeting, numbered at once (minutes back to the draft)",
            AFTER_HELD_BUDGET_MS,
            people.secretary,
        ) {
            decisionRecorder.record(held, late, after.copy(returnMinutesToDraft = true), null)
            decisionAccess.decision(held, late)
        }
    }

    @Test
    @Order(3)
    fun `the resolution register and its check answer within budget`() {
        val first = asUser(people.secretary) { resolutions.register(focalForum, null, null, null, PAGE) }
        assertThat(first.entries).hasSize(PAGE)
        assertThat(first.entries.count { !it.restricted }).isPositive()
        // The page's meetings read together are the views read one by one, for an officer and for a member.
        val pageMeetings = first.entries.map { it.meetingId }.distinct() + held + focal
        listOf(people.secretary, people.director).forEach { user ->
            asUser(user) {
                val oneByOne = pageMeetings.mapNotNull { id -> meetingViewers.find(id)?.let { id to it } }.toMap()
                assertThat(meetingViewers.findAll(pageMeetings)).isEqualTo(oneByOne)
            }
        }
        measure("register, latest page of 50 (secretary)", REGISTER_BUDGET_MS, people.secretary) {
            resolutions.register(focalForum, null, null, null, PAGE)
        }
        val older = checkNotNull(first.next).toLong()
        measure("register, an older page (chair)", REGISTER_BUDGET_MS, people.chair) {
            resolutions.register(focalForum, first.period, null, older, PAGE)
        }
        val all = asUser(people.secretary) { resolutions.check(second(), "ALL") }
        // The design's 1,200 numbers in a committee's period at its volume (fewer when a smaller volume is asked for).
        if (meetings >= DESIGN_MEETINGS) assertThat(all.lastNumber).isGreaterThan(CHECK_NUMBERS)
        assertThat(all.complete).isTrue()
        measure("register check (${all.lastNumber} numbers in one period)", CHECK_BUDGET_MS, people.secretary) {
            resolutions.check(second(), "ALL")
        }
        measure("register check (the first committee's year)", CHECK_BUDGET_MS, people.secretary) { resolutions.check(focalForum, null) }
    }

    @Test
    @Order(4)
    fun `my meetings with the minutes line and the minutes page answer within budget`() {
        val mine = asUser(people.director) { reads.mine(null) }
        assertThat(mine.single { it.id == held }.minutes?.myAction).isEqualTo("COMMENT")
        measure("my meetings with minutes (director)", MINE_BUDGET_MS, people.director) { reads.mine(null) }
        assertThat(asUser(people.secretary) { reads.mine(null) }.mapNotNull { it.minutes }.size).isGreaterThan(MINE_MINUTES)
        measure("my meetings with minutes (secretary of $FORUMS)", MINE_BUDGET_MS, people.secretary) { reads.mine(null) }
        assertThat(asUser(people.director) { pages.view(held) }.mayComment).isTrue()
        // The readers' sets planned together are the sets planned one by one.
        asUser(people.secretary) {
            val row = checkNotNull(minutesStore.find(held))
            val readers = minutesPeople.readers(row)
            val oneByOne = readers.mapValues { (user, kind) -> minutesPeople.setsOf(row, mapOf(user to kind)).getValue(user) }
            assertThat(minutesPeople.setsOf(row, readers)).isEqualTo(oneByOne)
        }
        measure("minutes page (circulated, director)", PAGE_BUDGET_MS, people.director) { pages.view(held) }
        measure("minutes page (circulated, secretary)", PAGE_BUDGET_MS, people.secretary) { pages.view(held) }
        measure("minutes page (circulated, chair)", PAGE_BUDGET_MS, people.chair) { pages.view(held) }
        assertThat(asUser(people.director, write = true) { reading.document(held) }.second).isInstanceOf(CopyLookup.Ready::class.java)
        measure("the reader's copy chosen (…/minutes/document before streaming)", DOCUMENT_BUDGET_MS, people.director, write = true) {
            reading.document(held)
        }
    }

    @Test
    @Order(5)
    fun `a pass of the hourly sweep with the minutes' steps answers within budget`() {
        measure(
            "minutes' sweep steps (i)–(l), one tenant",
            SWEEP_BUDGET_MS,
            people.admin,
            write = true,
        ) { minutesSweep.sweep(Instant.now()) }
        measure("sweep pass with the minutes' steps (one tenant)", SWEEP_BUDGET_MS, people.admin, write = true) { meetingSweep.sweep() }
    }

    /**
     * [measure] for a write in a transaction that is rolled back: the plans of the statements it runs (no sequential scan
     * of a large table), then its p95, the rollback included, under 2x [budget].
     */
    private fun measureRolledBack(
        name: String,
        budget: Long,
        user: UUID,
        call: () -> Any,
    ) {
        repeat(WARM_UP) { rolledBack(user, call) }
        val statements = checkNotNull(rolledBack(user) { recorder.record(call).second }).filter { SELECT.containsMatchIn(it) }
        assertThat(statements).describedAs("$name: statements recorded").isNotEmpty
        println("MinutesVolumeIT $name: ${statements.size} statements")
        val scans =
            statements.mapIndexedNotNull { i, sql ->
                val plan = checkNotNull(rolledBack(user) { explain(sql) })
                println("MinutesVolumeIT $name, statement ${i + 1}: ${sql.take(SQL_SHOWN)}\n$plan")
                large
                    .filter { table -> Regex("Seq Scan on $table\\b").containsMatchIn(plan) }
                    .takeIf { it.isNotEmpty() }
                    ?.let { "statement ${i + 1} scans $it:\n$sql\n$plan" }
            }
        assertThat(scans).describedAs("$name: sequential scans of large tables").isEmpty()
        // The best of three rounds (Latency, #241): one burst of the shared machine or a collector pause does not fail it.
        val p95 = Latency.p95(0, RUNS) { rolledBack(user, call) }
        println("MinutesVolumeIT $meetings meetings, $name: p95 $p95 ms (budget $budget ms, CI limit ${budget * 2} ms)")
        assertThat(p95).describedAs(name).isLessThan(budget * 2)
    }

    /** [call] as [user] in a write transaction that is then rolled back; its result. */
    private fun <T> rolledBack(
        user: UUID,
        call: () -> T,
    ): T? {
        var result: T? = null
        try {
            asUser(user, write = true) {
                result = call()
                throw RolledBack()
            }
        } catch (_: RolledBack) {
            // As intended: nothing of the call stays.
        }
        return result
    }

    private class RolledBack : RuntimeException("rolled back by the test")

    private fun explain(sql: String): String =
        tenantDsl
            .current()
            .fetch("explain (analyze, buffers) $sql")
            .joinToString("\n") { it.get(0, String::class.java) }

    /** The first committee's next meeting started (it began 40 minutes ago) with its members, the chair and the director. */
    private fun startFocal() {
        val row = checkNotNull(asUser(people.secretary) { meetingStore.find(focal) })
        asUser(people.secretary, write = true) { lifecycle.start(focal, row.version) }
        // Those who sent apologies stay away (E05's replies: one in five).
        val members = (1..MeetingVolume.FOCAL_MEMBERS).filter { it % APOLOGIES_EVERY != APOLOGIES_AT }.map(::member)
        asUser(people.secretary, write = true) {
            attendance.arrivals(
                focal,
                members + people.chair + people.secretary + people.director,
                AttendanceMode.IN_PERSON,
                Instant.now().minus(Duration.ofMinutes(ARRIVED_AGO)),
            )
        }
    }

    /** The director's own copy of the circulated minutes, built (as the copies job leaves it) for their redaction set. */
    private fun directorsCopy() {
        val set = asUser(people.director, write = true) { reading.ownSet(reading.reader(held)) }
        asUser(people.admin, write = true) {
            tenantDsl.current().execute(
                """
                insert into minutes_copy (id, tenant_id, meeting_id, stage, redacted, set_key, source_version_id, state, file_id,
                                          file_version_id, sha256, created_at, built_at, version)
                select md5('mncp-director')::uuid, '${tenant.id.value}'::uuid, '$held'::uuid, 'DRAFT', ?::jsonb, ?, md5('mnv' || '$held')::uuid,
                       'DONE', md5('mncf-director')::uuid, md5('mncv-director')::uuid, md5('e-director') || md5('f-director'), now(), now(), 1
                """,
                json.writeValueAsString(set.redacted),
                set.key,
            )
        }
    }

    /** An item of [meetingId] of [kind] without a decision, open to the secretary (not restricted, at the low level). */
    private fun undecided(
        meetingId: UUID,
        kind: String,
    ): UUID =
        asUser(people.secretary) {
            tenantDsl.current().fetchValue(
                """
                select mi.id from meeting_item mi
                where mi.meeting_id = ? and mi.kind = ? and mi.parent_id is null and mi.restricted_to is null
                  and mi.classification_id = ? and not exists (select 1 from minutes_decision d where d.item_id = mi.id)
                order by mi.position limit 1
                """,
                meetingId,
                kind,
                levels.low,
            ) as UUID
        }

    /** The second committee: numbered without a reset (`ALL`). */
    private fun second(): UUID =
        asUser(people.secretary) { tenantDsl.current().fetchValue("select id from forum where code = 'C2'") as UUID }

    private fun member(u: Int): UUID = volume.generated("mvu1-$u")

    private fun jobs(on: Boolean) {
        invitationJobs.jobs = on
        agendaJobs.jobs = on
        minutesJobs.jobs = on
        releaseJobs.jobs = on
    }

    private companion object {
        const val PAGE = 50
        const val HELD_DECISIONS = 40
        const val FOCAL_DECISIONS = 35
        const val CONSENT_ITEMS = 10
        const val CHECK_NUMBERS = 1_200L
        const val MINE_MINUTES = 15
        const val VOTES_FOR = 20
        const val DISSENTING = 20
        const val ABSTAINING = 21
        const val APOLOGIES_EVERY = 5
        const val APOLOGIES_AT = 3
        const val ARRIVED_AGO = 35L

        /** Minutes after the held meeting's start when everyone was in the room (between E05's departures). */
        const val DECIDED_AFTER = 45L

        // Design E06 §8. The minutes page has no row of its own: held to the decisions list's 150 ms; the copy's first byte
        // is 100 ms, of which this is choosing the reader's copy (the streaming is the files pipeline's).
        const val DECISIONS_BUDGET_MS = 150L
        const val RECORD_BUDGET_MS = 200L
        const val AFTER_HELD_BUDGET_MS = 400L
        const val CONSENT_BUDGET_MS = 800L
        const val REGISTER_BUDGET_MS = 100L
        const val CHECK_BUDGET_MS = 300L
        const val MINE_BUDGET_MS = 100L
        const val PAGE_BUDGET_MS = 150L
        const val DOCUMENT_BUDGET_MS = 100L
        const val SWEEP_BUDGET_MS = 5_000L

        /** E05's large tables (design E05 §8), as [com.memo.meeting.InvitationVolumeIT] checks them. */
        val E05_LARGE = MeetingVolume.E05_TABLES - listOf("forum_member", "notification_mail_outbox", "meeting_item_progress")

        /**
         * The minutes tables the design's volume makes large (design E06 §8): all but the counters (one per committee and
         * period), the requests for comments and the reminders (a few hundred rows each), which a plan may read whole.
         */
        val MINUTES_LARGE = MinutesVolume.TABLES - listOf("minutes_resolution_counter", "minutes_comment_request", "minutes_reminder")
    }
}
