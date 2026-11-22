package com.memo.minutes

import com.memo.common.authz.ForbiddenException
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.web.RefusedException
import com.memo.files.FileContents
import com.memo.files.FileUploads
import com.memo.files.Files
import com.memo.forum.MinutesApproval
import com.memo.forum.OutcomeKind
import com.memo.forum.StandingKind
import com.memo.identity.UserAccount
import com.memo.meeting.MeetingItems
import com.memo.minutes.internal.DecisionInput
import com.memo.minutes.internal.DraftTask
import com.memo.minutes.internal.ItemNotes
import com.memo.minutes.internal.MinutesCopies
import com.memo.minutes.internal.MinutesDrafting
import com.memo.minutes.internal.MinutesDrafts
import com.memo.minutes.internal.MinutesJobs
import com.memo.minutes.internal.MinutesNotFoundException
import com.memo.minutes.internal.MinutesPeople
import com.memo.minutes.internal.MinutesRow
import com.memo.minutes.internal.MinutesStore
import com.memo.minutes.internal.MinutesStructureChecks
import com.memo.minutes.internal.MinutesSweep
import com.memo.testsupport.Person
import com.memo.testsupport.sendJson
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.test.web.servlet.assertj.MvcTestResult
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Held meetings of [ReleaseFixture]'s Board Risk Committee with their minutes drafted (the jobs run by hand: test contexts
 * share the scheduler table), and the steps of the minutes' path over HTTP with `If-Match` on the minutes' version.
 */
abstract class MinutesFixture : ReleaseFixture() {
    @Autowired lateinit var drafts: MinutesDrafts

    @Autowired lateinit var drafting: MinutesDrafting

    @Autowired lateinit var checks: MinutesStructureChecks

    @Autowired lateinit var minutesStore: MinutesStore

    @Autowired lateinit var minutesJobs: MinutesJobs

    @Autowired lateinit var minutesCopies: MinutesCopies

    @Autowired lateinit var minutesPeople: MinutesPeople

    @Autowired lateinit var minutesSweep: MinutesSweep

    @Autowired lateinit var itemNotes: ItemNotes

    @Autowired lateinit var meetingItems: MeetingItems

    @Autowired lateinit var files: Files

    @Autowired lateinit var contents: FileContents

    @Autowired lateinit var uploads: FileUploads

    @BeforeEach
    fun minutesJobsByHand() {
        minutesJobs.jobs = false
    }

    @AfterEach
    fun minutesJobsBack() {
        minutesJobs.jobs = true
    }

    /**
     * A meeting with memos on [subjects], each decided by [decisions] (approved by default), held, its draft made and its
     * structure checked.
     */
    fun drafted(
        vararg subjects: String,
        decisions: (Running) -> Unit = { r -> r.items.forEach { decide(r, it.item, APPROVED) } },
        before: (Running) -> Unit = {},
    ): Running {
        val r = running(*subjects)
        before(r)
        decisions(r)
        held(r)
        generate(r.meeting.id)
        checkNotNull(asSystem { checks.check(r.meeting.id) })
        return r
    }

    /** Asks for the whole draft as the secretary and runs the job as db-scheduler would. */
    fun generate(meetingId: UUID) {
        asUser(secretary.account) { drafts.request(meetingId, null, row(meetingId).fileId != null) }
        asSystem { drafting.run(DraftTask(tenant.id.value, meetingId, row(meetingId).workSeq)) }
    }

    /** The secretary's notes on [item]. */
    fun notesOn(
        r: Running,
        item: UUID,
        text: String,
    ) = asUser(secretary.account) { itemNotes.save(r.meeting.id, item, 0, text) }

    fun row(meetingId: UUID): MinutesRow = checkNotNull(transactions.readInTenant(tenant.id) { minutesStore.find(meetingId) })

    fun events(meetingId: UUID) = transactions.readInTenant(tenant.id) { minutesStore.events(meetingId) }

    /** The draft's current version. */
    fun current(meetingId: UUID): ByteArray {
        val fileId = checkNotNull(row(meetingId).fileId)
        return asSystem {
            val version = checkNotNull(contents.current(fileId, "minutes.draft"))
            ByteArrayOutputStream().also { contents.write(version, it) }.toByteArray()
        }
    }

    /** A new version of the draft saved by [by] (as the editor's callback saves it). */
    fun save(
        meetingId: UUID,
        bytes: ByteArray,
        by: UserAccount = secretary.account,
    ) {
        val fileId = checkNotNull(row(meetingId).fileId)
        val version = checkNotNull(transactions.readInTenant(tenant.id) { files.info(fileId) }).version
        asUserNoTx(by) { uploads.uploadVersion(fileId, version, "minutes.docx", bytes, UUID.randomUUID().toString()) }
        checkNotNull(asSystem { checks.check(meetingId) })
    }

    /** A step of the minutes over HTTP as [by], with `If-Match` on the minutes' version now. */
    fun step(
        meetingId: UUID,
        path: String,
        by: Person,
        body: String = "{}",
        method: HttpMethod = HttpMethod.POST,
    ): MvcTestResult =
        mvc.sendJson(
            method,
            tenant,
            "/api/v1/meetings/$meetingId/minutes/$path",
            by.session,
            body,
            mapOf("Idempotency-Key" to UUID.randomUUID().toString(), "If-Match" to "\"${row(meetingId).version}\""),
        )

    /** Sends the draft to the chair and has the reviewer agree: circulated. */
    fun circulated(meetingId: UUID): MinutesRow {
        check(step(meetingId, "send-for-review", secretary).response.status == OK) { "not sent for review" }
        val reviewer = checkNotNull(row(meetingId).reviewerId)
        val by = listOf(chair, vice).first { it.id == reviewer }
        val agreed = step(meetingId, "review", by, """{"decision":"AGREE"}""")
        check(agreed.response.status == OK) { "not agreed: ${agreed.response.contentAsString}" }
        return row(meetingId)
    }

    /** Builds the copies as the `minutes-copies` job would. */
    fun buildCopies(meetingId: UUID) = asSystem { minutesCopies.build(meetingId, last = true) }

    /** The text layer of [pdf]. */
    fun textOf(pdf: ByteArray): String = Loader.loadPDF(pdf).use { PDFTextStripper().getText(it) }

    /** The PDF copy [person] reads (the copies built first). */
    fun copyOf(
        meetingId: UUID,
        person: Person,
    ): MvcTestResult = read("/api/v1/meetings/$meetingId/minutes/document", person.session)

    /** The meeting's "Minutes of the previous meeting" item. */
    fun previousMinutesItem(r: Running): UUID =
        transactions
            .readInTenant(tenant.id) { meetingItems.agenda(r.meeting.id) }
            .items
            .first { it.standingKind == StandingKind.PREVIOUS_MINUTES }
            .id

    /** Publishes version 2 of the committee's type with minutes approved [approval] (reviewed by a second administrator). */
    fun minutesApproved(approval: MinutesApproval) {
        val type = asUser(admin) { forums.get(brc) }.typeId
        val draft = asUser(admin) { typeVersions.newDraft(type) }
        val settings = draft.settings.copy(minutes = draft.settings.minutes.copy(approval = approval))
        val saved = asUser(admin) { typeVersions.replace(type, draft.number, draft.revision, settings) }
        asUser(admin) { typeVersions.submit(type, draft.number, saved.revision) }
        asUser(checker) { typeVersions.approve(type, draft.number, null) }
    }

    fun <T> asSystem(block: () -> T): T = TenantContext.call(tenant.id) { ActorContext.asSystem("minutes-test") { block() } }

    fun <T> asUserNoTx(
        account: UserAccount,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(account.id.value), null)) { TenantContext.call(tenant.id, block) }

    /** The 409 reason of [block]. */
    fun refusal(block: () -> Any?): String =
        (runCatching { block() }.exceptionOrNull() as? RefusedException)?.reason ?: throw AssertionError("not refused")

    /** The 403 reason ("" for a plain 403), or "404". */
    fun deniedTo(
        account: UserAccount,
        block: () -> Any?,
    ): String =
        when (val e = runCatching { asUser(account, block) }.exceptionOrNull()) {
            is ForbiddenException -> e.reason.orEmpty()
            is MinutesNotFoundException -> "404"
            else -> if (e?.javaClass?.simpleName == "MeetingNotFoundException") "404" else throw AssertionError("not denied", e)
        }

    companion object {
        const val OK = 200
        val APPROVE_MINUTES = DecisionInput(OutcomeKind.APPROVED, text = "The minutes of the previous meeting were approved.")
        val DEFER =
            DecisionInput(
                OutcomeKind.DEFERRED,
                text = "Deferred.",
                deferTo =
                    com.memo.minutes.internal
                        .DeferTarget(next = true),
            )
    }
}
