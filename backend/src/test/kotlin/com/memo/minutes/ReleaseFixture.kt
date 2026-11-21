package com.memo.minutes

import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.forum.ForumRole
import com.memo.forum.MemoResumes
import com.memo.forum.OutcomeKind
import com.memo.forum.internal.ForumInput
import com.memo.forum.internal.ForumMemberStore
import com.memo.forum.internal.ForumTypeLifecycle
import com.memo.forum.internal.MemberEdit
import com.memo.forum.internal.MemberInput
import com.memo.meeting.AttendanceMode
import com.memo.meeting.CarryOverFixture
import com.memo.meeting.MeetingKind
import com.memo.meeting.MeetingMode
import com.memo.meeting.internal.AgendaOrigin
import com.memo.meeting.internal.LateDecision
import com.memo.meeting.internal.MeetingInput
import com.memo.meeting.internal.MeetingPlace
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.TimesInput
import com.memo.meeting.internal.attendance.Attendance
import com.memo.minutes.internal.CurrentDecision
import com.memo.minutes.internal.DecisionInput
import com.memo.minutes.internal.DecisionReads
import com.memo.minutes.internal.DecisionRecorder
import com.memo.minutes.internal.MinutesRelease
import com.memo.minutes.internal.MinutesReleaseScheduler
import com.memo.minutes.internal.ReleasePass
import com.memo.minutes.internal.ReleaseTrigger
import com.memo.minutes.internal.ResolutionStore
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.JsonNode
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Memos escalated to the Board Risk Committee of [CarryOverFixture] (review, the committee waiting for its decision, then
 * a final approval by `colleague`), placed on a meeting that started a minute ago with the four voting members present,
 * and the decisions recorded on them as the secretary. The `minutes-release` job is run by hand ([held], [approved]): test
 * contexts share the scheduler table.
 */
abstract class ReleaseFixture : CarryOverFixture() {
    @Autowired lateinit var attendance: Attendance

    @Autowired lateinit var recorder: DecisionRecorder

    @Autowired lateinit var decisionReads: DecisionReads

    @Autowired lateinit var resolutionStore: ResolutionStore

    @Autowired lateinit var minutesRelease: MinutesRelease

    @Autowired lateinit var releaseScheduler: MinutesReleaseScheduler

    @Autowired lateinit var decisionsApi: Decisions

    @Autowired lateinit var resolutionsApi: Resolutions

    @Autowired lateinit var typeVersions: ForumTypeLifecycle

    /** A meeting under way, its items by subject (memo, submission, item). */
    data class Running(
        val meeting: MeetingRow,
        val items: List<Placed>,
    ) {
        fun item(subject: String): Placed = items.single { it.subject == subject }
    }

    data class Placed(
        val subject: String,
        val memo: UUID,
        val submission: UUID,
        val item: UUID,
    )

    @Autowired lateinit var memberStore: ForumMemberStore

    @BeforeEach
    fun releaseByHand() {
        releaseScheduler.jobs = false
        // The Board committee preset needs a majority of independent members present: every seat is independent here.
        transactions.readInTenant(tenant.id) { memberStore.members(brc) }.forEach { seat ->
            asUser(
                admin,
            ) { members.edit(brc, seat.id, seat.version, MemberEdit(seat.voting, setOf("independent"), null, seat.termEndsOn, null)) }
        }
    }

    @AfterEach
    fun releaseJobsBack() {
        releaseScheduler.jobs = true
    }

    /**
     * Memos with [subjects] escalated, placed on a new meeting in that order (the agenda's order), published, and the
     * meeting started a minute ago with the chair, the vice-chair and both directors present.
     */
    fun running(
        vararg subjects: String,
        extra: (MeetingRow) -> Unit = {},
    ): Running {
        val placed = subjects.map { s -> escalated(s).let { (memo, submission) -> Triple(s, memo, submission) } }
        val zone = ZoneId.of("Asia/Dhaka")
        val start = LocalDateTime.now(zone).truncatedTo(ChronoUnit.MINUTES).minusMinutes(1)
        val meeting =
            asUser(secretary.account) {
                val created =
                    lifecycle.create(
                        brc,
                        MeetingInput(MeetingKind.ORDINARY, MeetingPlace(null, MeetingMode.IN_PERSON, "Board room", null, null), null),
                        TimesInput(start, start.plusHours(3), zone.id),
                    )
                starts.start(created.id, AgendaOrigin.TEMPLATE, null)
                created
            }
        val items = placed.map { (s, memo, submission) -> Placed(s, memo, submission, place(meeting, submission)) }
        extra(meeting)
        asUser(secretary.account) { lifecycle.schedule(meeting.id, reads.get(meeting.id).version) }
        // The meeting has begun, so the submissions came after its agenda deadline: the chair agrees to take them.
        items.forEach { asUser(chair.account) { chairing.decideItem(meeting.id, it.item, version(meeting), LateDecision(true)) } }
        publish(meeting)
        startMeeting(meeting)
        asUser(secretary.account) {
            attendance.arrivals(meeting.id, listOf(chair.id, vice.id, director.id, colleague.id), AttendanceMode.IN_PERSON, null)
        }
        return Running(meeting, items)
    }

    /** Records (or corrects) [input] on [item] as the secretary. */
    fun decide(
        r: Running,
        item: UUID,
        input: DecisionInput,
    ): CurrentDecision {
        val version = transactions.readInTenant(tenant.id) { decisionReads.current(item)?.decision?.version }
        return asUser(secretary.account) { recorder.record(r.meeting.id, item, input, version) }
    }

    /** The meeting marked as held, and the job of its end run by hand (as db-scheduler runs it: no transaction). */
    fun held(r: Running): ReleasePass {
        endMeeting(r.meeting)
        return job(r.meeting.id, ReleaseTrigger.HELD)
    }

    /** The job of the minutes' approval (T6 schedules it at the chair's confirmation). */
    fun approved(meetingId: UUID): ReleasePass = job(meetingId, ReleaseTrigger.APPROVED)

    fun job(
        meetingId: UUID,
        trigger: ReleaseTrigger,
    ): ReleasePass = TenantContext.call(tenant.id) { ActorContext.asSystem("minutes-release") { minutesRelease.run(meetingId, trigger) } }

    /** The current decision on [item]. */
    fun decision(item: UUID): CurrentDecision = checkNotNull(transactions.readInTenant(tenant.id) { decisionReads.current(item) })

    /** [item]'s resolution number in force, or null. */
    fun numberOf(item: UUID): String? =
        transactions.readInTenant(tenant.id) {
            decisionReads.current(item)?.let { resolutionStore.inForce(it.decision.id)?.number }
        }

    /** Publishes version 2 of the committee's type with memos moving on [resumes] (reviewed by a second administrator). */
    fun memosResume(resumes: MemoResumes) {
        val type = asUser(admin) { forums.get(brc) }.typeId
        val draft = asUser(admin) { typeVersions.newDraft(type) }
        val settings = draft.settings.copy(minutes = draft.settings.minutes.copy(memoResumes = resumes))
        val saved = asUser(admin) { typeVersions.replace(type, draft.number, draft.revision, settings) }
        asUser(admin) { typeVersions.submit(type, draft.number, saved.revision) }
        asUser(checker) { typeVersions.approve(type, draft.number, null) }
    }

    /** Another committee of the same type, to refer items to. */
    fun otherCommittee(): UUID {
        val type = asUser(admin) { forums.get(brc) }.typeId
        val forum = asUser(admin) { forums.create(ForumInput("board-audit", "BAC", "Board Audit Committee", "Audit", type)) }
        val secretary2 = accounts.anActiveUser(transactions, tenant, aPassword())
        asUser(admin) { members.add(forum.id, MemberInput(userId = secretary2.id.value, role = ForumRole.SECRETARY)) }
        return forum.id
    }

    /** A JSON array's elements. */
    fun JsonNode.items(): List<JsonNode> = (0 until size()).map { get(it) }

    companion object {
        val APPROVED_WITH_CONDITIONS =
            DecisionInput(
                outcome = OutcomeKind.APPROVED_WITH_CONDITIONS,
                text = "It was RESOLVED that the term loan of BDT 60 million be approved.",
                conditions = listOf("Security perfected before disbursement."),
            )
        val APPROVED = DecisionInput(OutcomeKind.APPROVED, text = "It was RESOLVED that the facility be renewed.")
        val NOTED = DecisionInput(OutcomeKind.NOTED, text = "Noted.")
    }
}
