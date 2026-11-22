package com.memo.actions

import com.memo.actions.internal.ActionClosure
import com.memo.actions.internal.ActionFields
import com.memo.actions.internal.ActionIssuing
import com.memo.actions.internal.ActionProgress
import com.memo.actions.internal.ActionRaising
import com.memo.actions.internal.ActionReportReads
import com.memo.actions.internal.ActionReports
import com.memo.actions.internal.ActionSource
import com.memo.actions.internal.ActionStore
import com.memo.actions.internal.ActionTracker
import com.memo.actions.internal.ActionTrackerWorkbook
import com.memo.actions.internal.ActionsDaily
import com.memo.actions.internal.Confidence
import com.memo.actions.internal.MyActionsTab
import com.memo.actions.internal.OwnerInput
import com.memo.actions.internal.OwnerKind
import com.memo.actions.internal.RaiseInput
import com.memo.actions.internal.TrackerFilter
import com.memo.actions.internal.UpdateInput
import com.memo.actions.internal.VerifyDecision
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.meeting.MeetingStatus
import com.memo.meeting.MeetingVolume
import com.memo.meeting.MeetingVolumeIT
import com.memo.meeting.MeetingVolumeSupport
import com.memo.meeting.Meetings
import com.memo.org.OrgDirectory
import com.memo.org.OrgSeeding
import com.memo.testsupport.Latency
import com.memo.workflow.WorkingDays
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Action items at the design's volume (test plan E07 T9, design E07 §8): [MeetingVolume] (with "Matters arising" on every
 * agenda) and [ActionsVolume] over it at `ACTIONS_VOLUME` meetings (the design's 3,600: about 29,000 actions, 3,400 open,
 * 157,000 timeline entries, 3,200 reports with 220,000 rows, 5,000 people with notices for the daily digest; the weekly
 * `load` stage sets it, and the test is skipped without it), measured through the services each endpoint and job calls.
 * Budgets have 2x headroom for shared CI runners; the p95 values are printed for the epic log. Every statement of "My
 * actions" (each tab, with its counts), the tracker (with and without text, with its totals), its export, an action with
 * its timeline, the report (building and reading) and the daily task is recorded and `EXPLAIN ANALYZE`d on the volume:
 * none may scan an action table sequentially. The writes run after the reads; the daily task is run rolled back, so each
 * run does the whole day's work.
 */
@EnabledIfEnvironmentVariable(named = "ACTIONS_VOLUME", matches = "[1-9][0-9]*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ActionsVolumeIT : MeetingVolumeSupport() {
    override val meetings: Int = System.getenv("ACTIONS_VOLUME")?.toIntOrNull() ?: DESIGN_MEETINGS

    @Autowired lateinit var raising: ActionRaising

    @Autowired lateinit var issuing: ActionIssuing

    @Autowired lateinit var progress: ActionProgress

    @Autowired lateinit var closure: ActionClosure

    @Autowired lateinit var store: ActionStore

    @Autowired lateinit var access: com.memo.actions.internal.ActionAccess

    @Autowired lateinit var tracker: ActionTracker

    @Autowired lateinit var workbook: ActionTrackerWorkbook

    @Autowired lateinit var reports: ActionReports

    @Autowired lateinit var reportReads: ActionReportReads

    @Autowired lateinit var daily: ActionsDaily

    @Autowired lateinit var meetingRefs: Meetings

    @Autowired lateinit var org: OrgDirectory

    @Autowired lateinit var orgSeeding: OrgSeeding

    @Autowired lateinit var workingDays: WorkingDays

    override val large: List<String> = MeetingVolumeIT.LARGE + LARGE

    private lateinit var actions: ActionsVolume
    private lateinit var runAt: Instant

    /** Forum 1's held meetings, the latest first: the writes raise on them. */
    private lateinit var held: List<UUID>

    @BeforeAll
    fun generate() {
        // Other test contexts share the scheduler table; the volume's day is run by the test, rolled back.
        daily.jobs = false
        generate("actvol", e05 = false, mattersArising = true)
        val zone = asUser(people.admin) { org.zone() }
        val today = asUser(people.admin) { org.today() }
        val runDay = checkNotNull(asUser(people.admin) { workingDays.before(today, 1) })
        runAt = runDay.atTime(RUN_TIME).atZone(zone).toInstant()
        val started = System.nanoTime()
        actions =
            ActionsVolume(
                tenant = tenant.id.value,
                forums = committees,
                perForum = meetings / FORUMS,
                people = people,
                levels = levels,
                position = directorPosition(today),
                zone = zone.id,
                runDay = runDay,
                focal = volume.focal,
                persons = (ActionsVolume.PERSONS.toLong() * meetings / DESIGN_MEETINGS).toInt().coerceAtLeast(MIN_PERSONS),
            )
        actions.generate()
        held =
            asUser(people.admin) { meetingRefs.ofForum(focalForum) }
                .filter { it.status == MeetingStatus.HELD }
                .sortedByDescending { it.startsAt }
                .map { it.id }
        val underway = template(today, done = false)
        val done = template(today, done = true)
        actions.workflows(underway, done)
        actions.settle()
        println("ActionsVolumeIT generated the actions in ${(System.nanoTime() - started) / NANOS_PER_MILLI} ms")
        println("ActionsVolumeIT rows: ${actions.counts()}")
    }

    @Test
    @Order(1)
    fun `my actions answer within budget, each tab with its counts`() {
        val mine = asUser(people.director) { access.mine(MyActionsTab.MINE, null, null, PAGE) }
        assertThat(mine.counts.mine).isGreaterThanOrEqualTo(ActionsVolume.DIRECTOR_MINE)
        assertThat(mine.counts.contributing).isGreaterThanOrEqualTo(ActionsVolume.DIRECTOR_CONTRIBUTES)
        assertThat(mine.items).hasSize(PAGE)
        measure("my actions, to do (director: ${mine.counts.mine})", MINE_BUDGET_MS, people.director) {
            access.mine(MyActionsTab.MINE, null, null, PAGE)
        }
        measure("my actions, contributing (director: ${mine.counts.contributing})", MINE_BUDGET_MS, people.director) {
            access.mine(MyActionsTab.CONTRIBUTING, null, null, PAGE)
        }
        val verify = asUser(people.secretary) { access.mine(MyActionsTab.TO_VERIFY, null, null, PAGE) }
        assertThat(verify.counts.toVerify).isGreaterThan(PAGE)
        measure("my actions, to verify (secretary of $FORUMS: ${verify.counts.toVerify})", MINE_BUDGET_MS, people.secretary) {
            access.mine(MyActionsTab.TO_VERIFY, null, null, PAGE)
        }
    }

    @Test
    @Order(2)
    fun `the tracker answers within budget, with and without text, with its totals`() {
        val all = asUser(people.secretary) { tracker.page(focalForum, TrackerFilter(), null, PAGE) }
        assertThat(all.items).hasSize(PAGE)
        assertThat(all.totals.all).isGreaterThan(PAGE)
        println("ActionsVolumeIT tracker of the first committee: ${all.totals}")
        measure("tracker (secretary, ${all.totals.all} actions)", TRACKER_BUDGET_MS, people.secretary) {
            tracker.page(focalForum, TrackerFilter(), null, PAGE)
        }
        measure("tracker (member)", TRACKER_BUDGET_MS, people.director) { tracker.page(focalForum, TrackerFilter(), null, PAGE) }
        measure("tracker, overdue (secretary)", TRACKER_BUDGET_MS, people.secretary) {
            tracker.page(focalForum, TrackerFilter(overdue = true), null, PAGE)
        }
        val text = TrackerFilter(text = "liquidity contingency")
        assertThat(asUser(people.secretary) { tracker.page(focalForum, text, null, PAGE) }.matching).isPositive()
        measure("tracker with text (secretary)", TEXT_BUDGET_MS, people.secretary) { tracker.page(focalForum, text, null, PAGE) }
        measure("tracker with text (member)", TEXT_BUDGET_MS, people.director) { tracker.page(focalForum, text, null, PAGE) }
    }

    @Test
    @Order(3)
    fun `the tracker's export answers within budget`() {
        val rows = asUser(people.secretary, write = true) { tracker.export(focalForum, TrackerFilter()) }.rows.size
        assertThat(rows).isGreaterThan(PAGE)
        measure("tracker export ($rows rows, with the workbook)", EXPORT_BUDGET_MS, people.secretary, write = true) {
            workbook.write(tracker.export(focalForum, TrackerFilter()), OutputStream.nullOutputStream())
        }
    }

    @Test
    @Order(4)
    fun `an action with its timeline answers within budget`() {
        val id = actions.timelineAction()
        assertThat(asUser(people.director) { access.entries(id) }.entries).hasSize(ActionsVolume.TIMELINE)
        measure("action with its timeline (${ActionsVolume.TIMELINE} entries)", ACTION_BUDGET_MS, people.director) {
            access.action(id) to access.entries(id)
        }
    }

    @Test
    @Order(5)
    fun `the action taken report is built and read within budget`() {
        val revision = MeetingVolume.FOCAL_REVISIONS
        val recorded = rolledBack(people.secretary) { recorder.record { reports.build(volume.focal, revision) }.second }
        plans("report build", recorded, people.secretary, write = true)
        // Rolled back each time, so every call builds the report of the same revision.
        val build = Latency.p95(WARM_UP, RUNS) { rolledBack(people.secretary) { checkNotNull(reports.build(volume.focal, revision)) } }
        val header = checkNotNull(asUser(people.secretary, write = true) { reports.build(volume.focal, revision) })
        assertThat(header.rowCount).isGreaterThan(PAGE)
        budget("report build (${header.rowCount} rows, ${header.heldCount} held back)", build, BUILD_BUDGET_MS)
        val seen = asUser(people.director, write = true) { reportReads.report(volume.focal, null) }
        assertThat(seen.sections.sumOf { it.count }).isGreaterThan(PAGE)
        measure("report (member)", REPORT_BUDGET_MS, people.director, write = true) { reportReads.report(volume.focal, null) }
        measure("report (secretary)", REPORT_BUDGET_MS, people.secretary, write = true) { reportReads.report(volume.focal, null) }
        measure("report PDF (member)", PDF_BUDGET_MS, people.director, write = true) { reportReads.pdf(volume.focal, null) }
    }

    @Test
    @Order(6)
    fun `the daily task answers within budget, a digest for each person`() {
        val open = asUser(people.admin) { store.where(com.memo.actions.internal.OPEN_ACTIONS).size }
        val first = rolledBack(null) { daily.run(runAt) }
        println("ActionsVolumeIT daily task: $first")
        assertThat(first.digests).isEqualTo(actions.persons)
        assertThat(first.safety.checked).isGreaterThanOrEqualTo(open)
        assertThat(first.safety.reported + first.safety.reconciled + first.safety.replanned).isZero()
        // Each kind of statement once (the task repeats its statements per person and per action).
        val recorded = rolledBack(null) { recorder.record { daily.run(runAt) }.second }
        val shapes = recorded.distinctBy { sql -> sql.replace(LITERAL, "?").replace(NUMBER, "?") }
        println("ActionsVolumeIT daily task: ${recorded.size} statements, ${shapes.size} kinds; most repeated:\n  ${kinds(recorded)}")
        plans("daily task", shapes, people.admin, write = true)
        // One run a round, rolled back so each does the whole day's work; the best of the rounds.
        val run = Latency.p95(0, 1) { rolledBack(null) { check(daily.run(runAt).digests == actions.persons) } }
        budget("daily task (${actions.persons} people, $open open actions)", run, DAILY_BUDGET_MS)
    }

    @Test
    @Order(7)
    fun `raising, issuing, updating, marking done and verifying answer within budget`() {
        val meeting = held.first()
        val item = volume.generated("mvi$meeting-4-1")
        val draft = asUser(people.secretary, write = true) { raising.raise(meeting, raise(item)) }
        val issued = asUser(people.secretary, write = true) { raising.raise(meeting, raise(item)) }
        asUser(people.secretary, write = true) { issuing.issue(issued, version(issued)) }
        val done = asUser(people.secretary, write = true) { raising.raise(meeting, raise(item)) }
        asUser(people.secretary, write = true) { issuing.issue(done, version(done)) }
        asUser(people.director, write = true) { closure.done(done, version(done), "Done as asked.", emptyList()) }
        // Each write is rolled back, so every call makes the same change to the same action.
        val (draftVersion, issuedVersion, doneVersion) = listOf(draft, issued, done).map(::version)
        written("raise a draft", DRAFT_BUDGET_MS, people.secretary) { raising.raise(meeting, raise(item)) }
        written("issue one (a workflow started)", ISSUE_BUDGET_MS, people.secretary) { issuing.issue(draft, draftVersion) }
        written("post an update", WRITE_BUDGET_MS, people.director) {
            progress.update(issued, issuedVersion, UpdateInput("On track.", Confidence.ON_TRACK))
        }
        written("mark as done", WRITE_BUDGET_MS, people.director) { closure.done(issued, issuedVersion, "Done as asked.", emptyList()) }
        written("verify (close)", WRITE_BUDGET_MS, people.secretary) { closure.verify(done, doneVersion, VerifyDecision.CLOSE, "Agreed.") }
        val bulkMeeting = held[1]
        val bulkItem = volume.generated("mvi$bulkMeeting-4-1")
        repeat(BULK) { asUser(people.secretary, write = true) { raising.raise(bulkMeeting, raise(bulkItem)) } }
        val bulk =
            Latency.p95(0, 1) {
                val drafts = asUser(people.secretary, write = true) { issuing.issuable(bulkMeeting) }
                assertThat(drafts).hasSize(BULK)
                // As the bulk endpoint does: one transaction per draft (each rolled back here).
                drafts.forEach { d -> rolledBack(people.secretary) { issuing.issue(d.id, d.version) } }
            }
        budget("issue a meeting's $BULK drafts", bulk, BULK_BUDGET_MS)
    }

    /** An action issued through the services on forum 1's last held meeting, owned by the director; done with [done]. */
    private fun template(
        today: LocalDate,
        done: Boolean,
    ): ActionsVolume.Template {
        val meeting = held.first()
        val id = asUser(people.secretary, write = true) { raising.raise(meeting, raise(volume.generated("mvi$meeting-4-1"), today)) }
        asUser(people.secretary, write = true) { issuing.issue(id, version(id)) }
        val issued = asUser(people.admin) { checkNotNull(store.find(id)) }
        if (done) asUser(people.director, write = true) { closure.done(id, issued.version, "Done as asked.", emptyList()) }
        val row = asUser(people.admin) { checkNotNull(store.find(id)) }
        return ActionsVolume.Template(
            actionId = id,
            instanceId = checkNotNull(row.workflowInstanceId),
            holder = people.director,
            dueDate = checkNotNull(row.dueDate),
            stepName = row.stepName,
            ownerStep = issued.ownerStep,
            holderNow = row.holderUserId?.takeIf { it != people.director },
        )
    }

    private fun raise(
        item: UUID,
        today: LocalDate = asUser(people.admin) { org.today() },
    ) = RaiseInput(
        ActionSource.ITEM,
        item,
        null,
        null,
        ActionFields(
            "Report the revised collateral valuation",
            "Details",
            null,
            OwnerInput(OwnerKind.PERSON, userId = people.director),
            emptyList(),
            today.plusDays(DUE_DAYS),
        ),
    )

    /**
     * The write [call] as [user], each call in a transaction rolled back afterwards (the same change every time): its
     * statements recorded once, their count and the most repeated kinds printed (where a write spends its round trips),
     * then its p95 by [Latency] against [budget].
     */
    private fun written(
        name: String,
        budget: Long,
        user: UUID,
        call: () -> Any,
    ) {
        val statements = rolledBack(user) { recorder.record(call).second }
        println("ActionsVolumeIT $name: ${statements.size} statements; most repeated:\n  ${kinds(statements)}")
        budget(name, Latency.p95(WARM_UP, RUNS) { rolledBack(user, call) }, budget)
    }

    /** The most repeated kinds of [statements], with how many of each. */
    private fun kinds(statements: List<String>): String =
        statements
            .groupingBy { it.replace(LITERAL, "?").replace(NUMBER, "?").take(KIND_SHOWN) }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(KINDS_SHOWN)
            .joinToString("\n  ")

    private fun version(id: UUID) = asUser(people.admin) { checkNotNull(store.find(id)).version }

    /** "Head of Credit Risk", held by the director (a position owner's holder in "My actions"). */
    private fun directorPosition(today: LocalDate): UUID {
        val position = UUID.randomUUID()
        val entity = UUID.randomUUID()
        val unit = UUID.randomUUID()
        val start = today.minusYears(1)
        asUser(people.admin, write = true) {
            orgSeeding.legalEntity(entity, "Bank", start)
            orgSeeding.unit(unit, entity, null, "CR", "Credit Risk", start)
            orgSeeding.position(position, unit, "Head of Credit Risk", head = true, start)
            orgSeeding.assignment(position, people.director, start)
        }
        return position
    }

    /**
     * [block] in a write transaction as [user] (the system when null) that is rolled back afterwards, so a write measured
     * many times does the same work each time; its result.
     */
    private fun <T> rolledBack(
        user: UUID?,
        block: () -> T,
    ): T {
        var result: Result<T>? = null
        runCatching {
            val inTenant = {
                TenantContext.call(tenant.id) {
                    transactions.inTenant(tenant.id) {
                        result = Result.success(block())
                        throw RolledBack()
                    }
                }
            }
            if (user ==
                null
            ) {
                ActorContext.asSystem("actions-daily") { inTenant() }
            } else {
                ActorContext.call(ActorScope(Actor.User(user))) { inTenant() }
            }
        }.onFailure { if (it !is RolledBack) throw it }
        return checkNotNull(result).getOrThrow()
    }

    private class RolledBack : RuntimeException()

    companion object {
        const val PAGE = 50
        const val MIN_PERSONS = 200
        const val DUE_DAYS = 40L
        const val BULK = 30
        const val KIND_SHOWN = 160
        const val KINDS_SHOWN = 8

        val RUN_TIME: LocalTime = LocalTime.of(9, 30)
        private val LITERAL = Regex("'[^']*'")
        private val NUMBER = Regex("\\b\\d+\\b")

        // Design E07 §8.
        const val DRAFT_BUDGET_MS = 150L
        const val ISSUE_BUDGET_MS = 300L
        const val BULK_BUDGET_MS = 3_000L
        const val MINE_BUDGET_MS = 150L
        const val TRACKER_BUDGET_MS = 200L
        const val TEXT_BUDGET_MS = 300L
        const val EXPORT_BUDGET_MS = 3_000L
        const val ACTION_BUDGET_MS = 120L
        const val WRITE_BUDGET_MS = 250L
        const val BUILD_BUDGET_MS = 400L
        const val REPORT_BUDGET_MS = 150L
        const val PDF_BUDGET_MS = 2_000L
        const val DAILY_BUDGET_MS = 30_000L

        /** The action tables the volume makes large (the counters, holds and update asks stay small by nature). */
        val LARGE =
            listOf(
                "actions_item",
                "actions_entry",
                "actions_contributor",
                "actions_due_request",
                "actions_digest",
                "actions_report",
                "actions_report_row",
            )
    }
}
