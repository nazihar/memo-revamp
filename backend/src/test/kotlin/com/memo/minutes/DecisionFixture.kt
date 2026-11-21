package com.memo.minutes

import com.memo.common.authz.ForbiddenException
import com.memo.common.web.RefusedException
import com.memo.forum.MeetingPolicy
import com.memo.forum.QuorumOverride
import com.memo.forum.SubmissionPurpose
import com.memo.forum.internal.ForumTypeLifecycle
import com.memo.identity.UserAccount
import com.memo.meeting.AttendanceTestSupport
import com.memo.meeting.MeetingKind
import com.memo.meeting.MeetingMode
import com.memo.meeting.internal.AgendaOrigin
import com.memo.meeting.internal.ConsentRequests
import com.memo.meeting.internal.Declarations
import com.memo.meeting.internal.DeclareInput
import com.memo.meeting.internal.ItemInput
import com.memo.meeting.internal.ItemKind
import com.memo.meeting.internal.MeetingInput
import com.memo.meeting.internal.MeetingPlace
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.TimesInput
import com.memo.minutes.internal.ConsentDecisions
import com.memo.minutes.internal.CurrentDecision
import com.memo.minutes.internal.DecisionInput
import com.memo.minutes.internal.DecisionReads
import com.memo.minutes.internal.DecisionRecorder
import com.memo.minutes.internal.DecisionStore
import com.memo.minutes.internal.ItemNotes
import com.memo.minutes.internal.MinutesAccess
import com.memo.minutes.internal.MinutesNotFoundException
import com.memo.minutes.internal.MinutesRejectedException
import com.memo.minutes.internal.MinutesReleaseScheduler
import com.memo.minutes.internal.NoteStore
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The E05 committee of seven voting members (all independent, the Board committee preset: 4 of 7 with the chair or a
 * vice-chair) and a meeting that started a minute ago, with two items for decision and three consent items, one of which
 * a member asked to discuss before the meeting. Helpers record decisions as the secretary through the services.
 */
abstract class DecisionFixture : AttendanceTestSupport() {
    @Autowired lateinit var recorder: DecisionRecorder

    @Autowired lateinit var consent: ConsentDecisions

    @Autowired lateinit var notes: ItemNotes

    @Autowired lateinit var minutesAccess: MinutesAccess

    @Autowired lateinit var decisionStore: DecisionStore

    @Autowired lateinit var decisionReads: DecisionReads

    @Autowired lateinit var noteStore: NoteStore

    @Autowired lateinit var decisionsApi: Decisions

    @Autowired lateinit var declarations: Declarations

    @Autowired lateinit var consentRequests: ConsentRequests

    @Autowired lateinit var typeVersions: ForumTypeLifecycle

    @Autowired lateinit var releaseScheduler: MinutesReleaseScheduler

    /** The `minutes-release` job is run by hand where a test needs it: test contexts share the scheduler table. */
    @BeforeEach
    fun noReleaseJobs() {
        releaseScheduler.jobs = false
    }

    @AfterEach
    fun releaseJobsBack() {
        releaseScheduler.jobs = true
    }

    /** A meeting with its items by title, and its consent section. */
    data class Deciding(
        val meeting: MeetingRow,
        val items: Map<String, UUID>,
        val consentSection: UUID,
    ) {
        val credit: UUID get() = items.getValue(CREDIT)
        val appetite: UUID get() = items.getValue(APPETITE)
    }

    /**
     * The meeting, started now: "Credit proposal" (for decision) and "Risk appetite" in "For decision"; "Compliance report"
     * (for noting), "Terms of reference" (for decision) and "Liquidity policy" (lifted for discussion by director2) in
     * "Consent items". [before] runs while it is scheduled (declarations, replies).
     */
    fun deciding(before: (MeetingRow, Map<String, UUID>) -> Unit = { _, _ -> }): Deciding {
        val zone = ZoneId.of("Asia/Dhaka")
        val start = LocalDateTime.now(zone).truncatedTo(ChronoUnit.MINUTES).minusMinutes(1)
        val created =
            asUser(secretary) {
                lifecycle.create(
                    brc,
                    MeetingInput(MeetingKind.ORDINARY, MeetingPlace(null, MeetingMode.IN_PERSON, "Board room", null, null), null),
                    TimesInput(start, start.plusHours(3), zone.id),
                )
            }
        val (items, consentSection) =
            asUser(secretary) {
                agendaStart.start(created.id, AgendaOrigin.TEMPLATE, null)
                val sections = drafts.draft(created.id).sections
                val forDecision = sections.single { it.title == "For decision" }.id
                val consentItems = sections.single { it.title == "Consent items" }.id
                val add = { section: UUID, title: String, purpose: SubmissionPurpose ->
                    agendaItems.add(
                        created.id,
                        drafts.draft(created.id).version,
                        ItemInput(ItemKind.ITEM, sectionId = section, title = title, purpose = purpose, minutes = 15),
                    )
                }
                mapOf(
                    CREDIT to add(forDecision, CREDIT, SubmissionPurpose.DECISION),
                    APPETITE to add(forDecision, APPETITE, SubmissionPurpose.DECISION),
                    COMPLIANCE to add(consentItems, COMPLIANCE, SubmissionPurpose.INFORMATION),
                    TERMS to add(consentItems, TERMS, SubmissionPurpose.DECISION),
                    LIQUIDITY to add(consentItems, LIQUIDITY, SubmissionPurpose.DECISION),
                ) to consentItems
            }
        asUser(secretary) { meeting(created.id).let { lifecycle.schedule(it.id, it.version) } }
        asUser(chair) { chairing.agree(created.id, drafts.draft(created.id).version, false, null) }
        asUser(secretary) { publisher.publish(created.id, drafts.draft(created.id).version, null, true) }
        asUser(director2) { consentRequests.ask(created.id, items.getValue(LIQUIDITY)) }
        before(meeting(created.id), items)
        val now = meeting(created.id)
        val started = asUser(secretary) { lifecycle.start(now.id, now.version) }
        return Deciding(started, items, consentSection)
    }

    /** [people] declare an interest in [item] (they leave the room for it). */
    fun declareOn(
        meeting: MeetingRow,
        item: UUID,
        vararg people: UserAccount,
    ) = people.forEach {
        asUser(
            it,
        ) { declarations.declare(meeting.id, item, DeclareInput("A family member is a director of the borrower")) }
    }

    /** Records [input] on [item] as [by] (the secretary by default), with the decision's current version once it exists. */
    fun record(
        d: Deciding,
        item: UUID,
        input: DecisionInput,
        by: UserAccount = secretary,
        version: Long? = currentVersion(item),
    ): CurrentDecision = asUser(by) { recorder.record(d.meeting.id, item, input, version) }

    fun currentVersion(item: UUID): Long? = transactions.readInTenant(tenant.id) { decisionReads.current(item)?.decision?.version }

    /** The 422 codes of [block] as the secretary (or [by]). */
    fun rejected(
        by: UserAccount = secretary,
        block: () -> Any,
    ): List<String> =
        runCatching { asUser(by, block) }.exceptionOrNull().let {
            (it as? MinutesRejectedException)?.problems?.map { p -> p.code } ?: throw AssertionError("not rejected", it)
        }

    /** The 409 reason of [block]. */
    fun refused(
        by: UserAccount = secretary,
        block: () -> Any,
    ): String = (runCatching { asUser(by, block) }.exceptionOrNull() as? RefusedException)?.reason ?: throw AssertionError("not refused")

    /** The 403 reason of [block] ("" for a plain 403); "404" when it answered not found. */
    fun denied(
        by: UserAccount,
        block: () -> Any,
    ): String =
        when (val e = runCatching { asUser(by, block) }.exceptionOrNull()) {
            is ForbiddenException -> e.reason.orEmpty()
            is MinutesNotFoundException -> "404"
            else -> if (e?.javaClass?.simpleName == "MeetingNotFoundException") "404" else throw AssertionError("not denied", e)
        }

    /** A second secretary of the committee. */
    fun secondSecretary(): UserAccount =
        accounts.anActiveUser(transactions, tenant, aPassword()).also { seat(it, com.memo.forum.ForumRole.SECRETARY) }

    /** Publishes version 2 of the committee's type with `quorumOverride` WITH_REASON (reviewed by a second administrator). */
    fun allowOverrides() {
        val checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        val draft = asUser(admin) { typeVersions.newDraft(typeId) }
        val saved =
            asUser(admin) {
                typeVersions.replace(
                    typeId,
                    2,
                    draft.revision,
                    draft.settings.copy(meetings = MeetingPolicy(quorumOverride = QuorumOverride.WITH_REASON)),
                )
            }
        asUser(admin) { typeVersions.submit(typeId, 2, saved.revision) }
        asUser(checker) { typeVersions.approve(typeId, 2, null) }
    }

    companion object {
        const val CREDIT = "Credit proposal"
        const val APPETITE = "Risk appetite"
        const val COMPLIANCE = "Compliance report"
        const val TERMS = "Terms of reference"
        const val LIQUIDITY = "Liquidity policy"
    }
}
