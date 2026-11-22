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
import com.memo.identity.UserAccount
import com.memo.identity.UserSummaries
import com.memo.minutes.internal.DecisionInput
import com.memo.minutes.internal.Dissent
import com.memo.minutes.internal.DraftTask
import com.memo.minutes.internal.MinutesDrafting
import com.memo.minutes.internal.MinutesDrafts
import com.memo.minutes.internal.MinutesEventKind
import com.memo.minutes.internal.MinutesJobs
import com.memo.minutes.internal.MinutesNotFoundException
import com.memo.minutes.internal.MinutesPages
import com.memo.minutes.internal.MinutesRow
import com.memo.minutes.internal.MinutesStage
import com.memo.minutes.internal.MinutesStep
import com.memo.minutes.internal.MinutesStore
import com.memo.minutes.internal.MinutesStructure
import com.memo.minutes.internal.MinutesStructureChecks
import com.memo.minutes.internal.StructureProblemKind
import com.memo.minutes.internal.VoteMode
import com.memo.minutes.internal.WorkState
import com.memo.templating.DocxControls
import com.memo.testsupport.Audit
import com.memo.testsupport.Docx
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Drafting the minutes (test plan E06 T5 `MinutesDraftIT`; ADR-0066, design E06 §1.5, §1.6.1): the minutes created when the
 * meeting is marked as held with the type's policy pinned; the draft generated from the meeting (attendance, declarations,
 * decisions with their vote, dissent and conditions, notes, numbers) with one locked control per item; a saved version
 * without an item's block blocking the next step (409 `STRUCTURE`, naming it) until "Restore item n"; filling one item
 * again keeping the edits elsewhere; the whole draft again only with `replaceEdits`; a corrected decision sending minutes
 * in review back to the draft; who may draft. The jobs are run by hand (test contexts share the scheduler table).
 */
class MinutesDraftIT : ReleaseFixture() {
    @Autowired lateinit var drafts: MinutesDrafts

    @Autowired lateinit var drafting: MinutesDrafting

    @Autowired lateinit var checks: MinutesStructureChecks

    @Autowired lateinit var pages: MinutesPages

    @Autowired lateinit var minutesStore: MinutesStore

    @Autowired lateinit var minutesJobs: MinutesJobs

    @Autowired lateinit var files: Files

    @Autowired lateinit var contents: FileContents

    @Autowired lateinit var uploads: FileUploads

    @Autowired lateinit var controls: DocxControls

    @Autowired lateinit var users: UserSummaries

    @BeforeEach
    fun jobsByHand() {
        minutesJobs.jobs = false
    }

    @AfterEach
    fun minutesJobsBack() {
        minutesJobs.jobs = true
    }

    @Test
    fun `the held meeting's draft has one locked control per item, the attendance, the declaration, the decision and DRAFT`() {
        val r = running("Term loan, Delta Textiles", "Facility renewal, Acme", "Working capital, Mill")
        val (loan, renewal, _) = r.items
        declare(director, r.meeting, loan.item)
        decide(
            r,
            loan.item,
            APPROVED_WITH_CONDITIONS.copy(
                voteMode = VoteMode.VOTE,
                votesFor = 2,
                votesAgainst = 1,
                votesAbstain = 0,
                dissent = listOf(Dissent(colleague.id, "The sector outlook is too weak")),
            ),
        )
        decide(r, renewal.item, APPROVED)
        asUser(secretary.account) { notesOf(r, loan.item, "Members challenged the sector outlook.") }
        assertThat(transactions.readInTenant(tenant.id) { minutesStore.find(r.meeting.id) }).describedAs("no minutes before HELD").isNull()
        held(r)

        // Marked as held: the minutes exist in DRAFT, with the type's policy pinned.
        val created = row(r)
        assertThat(created.state to created.fileId).isEqualTo(MinutesStage.DRAFT to null)
        assertThat(created.policy.approval).isEqualTo(MinutesApproval.AT_NEXT_MEETING)
        Audit.assertAudited(tenant.id, "minutes.created", r.meeting.id.toString(), secretary.id.toString())

        generate(r.meeting.id)
        Audit.assertAudited(tenant.id, "minutes.draft.requested", r.meeting.id.toString(), secretary.id.toString())
        val made = row(r)
        assertThat(made.fileId).isNotNull()
        assertThat(made.workState).isNull()
        val generated = Audit.assertAudited(tenant.id, "minutes.draft.generated", r.meeting.id.toString(), secretary.id.toString())
        assertThat(generated.canonical).contains(made.fileId.toString(), "sha256").doesNotContain("sector outlook")
        assertThat(events(r).map { it.kind }).containsExactly(MinutesEventKind.GENERATED)

        val docx = current(r.meeting.id)
        val agenda = minutedItems(r)
        val found = controls.scan(docx.inputStream())
        assertThat(found.map { it.tag }).containsExactlyElementsOf(agenda.map { MinutesStructure.tag(it) })
        assertThat(found).allSatisfy { assertThat(it.lock to it.bodyLevel).isEqualTo("sdtLocked" to true) }
        val text = Docx.text(docx)
        val names = transactions.readInTenant(tenant.id) { users.names(listOf(chair.id, vice.id, director.id, colleague.id)) }
        assertThat(text).contains(
            "DRAFT",
            "Board Risk Committee",
            names.getValue(chair.id),
            "${names.getValue(director.id)} declared an interest ($NATURE_IN_MINUTES) and left the meeting for this item.",
            "${names.getValue(director.id)} took no part in this item.",
            "APPROVED WITH CONDITIONS",
            "1. Security perfected before disbursement.",
            "For 2, against 1, abstaining 0",
            "${names.getValue(colleague.id)} asked that their dissent be recorded: The sector outlook is too weak.",
            "Members challenged the sector outlook.",
            "BRC/${year(r)}/001",
        )
        assertThat(text).doesNotContain("{{", "[name]")
        soundAndShown(r, checkNotNull(made.fileId))
    }

    /** The structure check finds the fresh draft sound, and the secretary's page shows it (in the service and over HTTP). */
    private fun soundAndShown(
        r: Running,
        fileId: UUID,
    ) {
        val checked = checkNotNull(asSystem { checks.check(r.meeting.id) })
        assertThat(checked.ok to checked.trackedChanges).isEqualTo(true to 0)
        Audit.assertAudited(tenant.id, "minutes.structure.checked", r.meeting.id.toString())
        val page = asUser(secretary.account) { pages.view(r.meeting.id) }
        assertThat(page.draft?.fileId).isEqualTo(fileId)
        assertThat(page.structure.ok to page.structure.pending).isEqualTo(true to false)
        assertThat(page.mayGenerate).isTrue()
        // Over HTTP: the page, and the whole draft again refused without the confirmation.
        val http = read("/api/v1/meetings/${r.meeting.id}/minutes", secretary.session)
        assertThat(http).hasStatusOk()
        assertThat(http.json()["state"].asString()).isEqualTo("DRAFT")
        val again = post("/api/v1/meetings/${r.meeting.id}/minutes/draft", secretary.session)
        assertThat(again).hasStatus(HttpStatus.CONFLICT)
        assertThat(again.json()["reason"]?.asString() ?: again.response.contentAsString).contains("DRAFT_EXISTS")
    }

    @Test
    fun `a saved version without an item's block blocks the next step until it is restored, and edits elsewhere are kept`() {
        val r = running("Term loan, Delta Textiles", "Facility renewal, Acme")
        val (loan, renewal) = r.items
        decide(r, loan.item, APPROVED_WITH_CONDITIONS)
        decide(r, renewal.item, APPROVED)
        asUser(secretary.account) { notesOf(r, loan.item, "Members challenged the sector outlook.") }
        held(r)
        generate(r.meeting.id)
        assertThat(asSystem { checks.check(r.meeting.id) }?.ok).isTrue()

        // The secretary saves a version without the renewal's block, with edits inside the loan's and outside every block.
        val renewalTag = MinutesStructure.tag(renewal.item)
        save(
            r.meeting.id,
            Docx.editText(current(r.meeting.id), "word/document.xml") { xml ->
                val at = xml.indexOf("""w:val="$renewalTag"""")
                val sdt = xml.substring(xml.lastIndexOf("<w:sdt>", at), xml.indexOf("</w:sdt>", at) + "</w:sdt>".length)
                xml
                    .replace(sdt, "")
                    .replace("Business of the meeting", "Business of the meeting EDITED-OUTSIDE")
                    .replace("Members challenged the sector outlook.", "EDITED-IN-LOAN")
            },
        )
        val checked = checkNotNull(asSystem { checks.check(r.meeting.id) })
        val number = minutedNumber(r, renewal.item)
        assertThat(checked.problems.map { it.problem to it.number }).containsExactly(StructureProblemKind.MISSING to number)
        val page = asUser(secretary.account) { pages.view(r.meeting.id) }
        assertThat(page.structure.problems.map { it.itemId }).containsExactly(renewal.item)
        // Sending for the chair's review (T6) is refused, naming the item.
        assertThatThrownBy { asUserNoTx(secretary.account) { checks.requireReady(r.meeting.id, MinutesStep.REVIEW) } }
            .isInstanceOfSatisfying(RefusedException::class.java) { e ->
                assertThat(e.reason).isEqualTo("STRUCTURE")
                assertThat(e.details["items"].toString()).contains(renewal.item.toString(), "MISSING")
            }

        // "Restore item n": the block is back after the loan's, the edits are kept.
        generate(r.meeting.id, renewal.item)
        assertThat(asSystem { checks.check(r.meeting.id) }?.ok).isTrue()
        Audit.assertAudited(tenant.id, "minutes.draft.item_restored", r.meeting.id.toString(), secretary.id.toString())
        val restored = current(r.meeting.id)
        val tags = controls.scan(restored.inputStream()).map { it.tag }
        assertThat(tags.indexOf(renewalTag)).isEqualTo(tags.indexOf(MinutesStructure.tag(loan.item)) + 1)
        assertThat(Docx.text(restored)).contains("EDITED-OUTSIDE", "EDITED-IN-LOAN")
        asUserNoTx(secretary.account) { checks.requireReady(r.meeting.id, MinutesStep.REVIEW) }

        // Filling the loan's block again replaces the edit inside it only.
        generate(r.meeting.id, loan.item)
        Audit.assertAudited(tenant.id, "minutes.draft.item_regenerated", r.meeting.id.toString(), secretary.id.toString())
        val refilled = Docx.text(current(r.meeting.id))
        assertThat(refilled).contains("EDITED-OUTSIDE", "Members challenged the sector outlook.").doesNotContain("EDITED-IN-LOAN")
        assertThat(events(r).map { it.kind }).containsExactly(
            MinutesEventKind.GENERATED,
            MinutesEventKind.ITEM_RESTORED,
            MinutesEventKind.ITEM_REGENERATED,
        )

        // The whole draft again: only with the confirmation, and every edit goes.
        assertThat(refusal { generate(r.meeting.id) }).isEqualTo("DRAFT_EXISTS")
        generate(r.meeting.id, replaceEdits = true)
        assertThat(Docx.text(current(r.meeting.id))).doesNotContain("EDITED-OUTSIDE")
        assertThat(
            files
                .let { f ->
                    transactions.readInTenant(tenant.id) { f.info(checkNotNull(row(r).fileId)) }
                }?.versionCount,
        ).isEqualTo(5)
    }

    @Test
    fun `a request waits for the one before it, and a block that cannot be filled again gives up with a code`() {
        val r = running("Term loan, Delta Textiles", "Facility renewal, Acme")
        val (loan, renewal) = r.items
        decide(r, loan.item, APPROVED_WITH_CONDITIONS)
        decide(r, renewal.item, APPROVED)
        held(r)
        asUser(secretary.account) { drafts.request(r.meeting.id, null, false) }
        assertThat(refusal { asUser(secretary.account) { drafts.request(r.meeting.id, null, true) } }).isEqualTo("GENERATING")
        assertThat(refusal { asUser(secretary.account) { drafts.request(r.meeting.id, loan.item, false) } }).isEqualTo("GENERATING")
        runJob(r.meeting.id)
        // The loan's block saved twice: it cannot be filled again (the secretary makes the whole draft again).
        val loanTag = MinutesStructure.tag(loan.item)
        save(
            r.meeting.id,
            Docx.editText(current(r.meeting.id), "word/document.xml") { xml ->
                val at = xml.indexOf("""w:val="$loanTag"""")
                val sdt = xml.substring(xml.lastIndexOf("<w:sdt>", at), xml.indexOf("</w:sdt>", at) + "</w:sdt>".length)
                xml.replace(sdt, sdt + sdt)
            },
        )
        assertThat(asSystem { checks.check(r.meeting.id) }?.problems?.map { it.problem }).containsExactly(StructureProblemKind.DUPLICATED)
        generate(r.meeting.id, loan.item)
        val failed = row(r)
        assertThat(failed.workState to failed.workError).isEqualTo(WorkState.FAILED to "CONTROLS")
        Audit.assertAudited(tenant.id, "minutes.draft.failed", r.meeting.id.toString())
        assertThat(asUser(secretary.account) { pages.view(r.meeting.id) }.work?.error).isEqualTo("CONTROLS")
        generate(r.meeting.id, replaceEdits = true)
        assertThat(asSystem { checks.check(r.meeting.id) }?.ok).isTrue()
    }

    @Test
    fun `the minutes cannot leave the draft while an item from a memo has no decision`() {
        val r = running("Term loan, Delta Textiles", "Facility renewal, Acme")
        val (loan, renewal) = r.items
        decide(r, loan.item, APPROVED)
        held(r)
        assertThat(
            refusal { asUserNoTx(secretary.account) { checks.requireReady(r.meeting.id, MinutesStep.REVIEW) } },
        ).isEqualTo("NO_DRAFT")
        generate(r.meeting.id)
        assertThatThrownBy { asUserNoTx(secretary.account) { checks.requireReady(r.meeting.id, MinutesStep.REVIEW) } }
            .isInstanceOfSatisfying(RefusedException::class.java) { e ->
                assertThat(e.reason).isEqualTo("UNDECIDED_ITEMS")
                assertThat(e.details["items"].toString()).contains(renewal.item.toString()).doesNotContain(loan.item.toString())
            }
        assertThat(asUser(secretary.account) { pages.view(r.meeting.id) }.undecided.map { it.itemId }).containsExactly(renewal.item)
        // Deferred, as an item not reached is minuted: the step may be taken.
        decide(
            r,
            renewal.item,
            DecisionInput(
                com.memo.forum.OutcomeKind.DEFERRED,
                deferTo =
                    com.memo.minutes.internal
                        .DeferTarget(next = true),
                decidedAt = decision(loan.item).entry.decidedAt,
            ),
        )
        generate(r.meeting.id, renewal.item)
        asUserNoTx(secretary.account) { checks.requireReady(r.meeting.id, MinutesStep.REVIEW) }
    }

    @Test
    fun `a corrected decision sends minutes in review back to the draft, once it is confirmed`() {
        val r = running("Term loan, Delta Textiles")
        val loan = r.items.single()
        decide(r, loan.item, APPROVED)
        held(r)
        generate(r.meeting.id)
        // T6 sends them to the chair; here the stage is set as T6 will.
        transactions.inTenant(tenant.id) {
            val row = checkNotNull(minutesStore.lock(r.meeting.id))
            check(minutesStore.update(row, row.copy(state = MinutesStage.CHAIR_REVIEW)))
        }
        val correction = APPROVED.copy(text = "It was RESOLVED that the facility be renewed for a year.", reason = "Wording")
        assertThat(refusal { decide(r, loan.item, correction) }).isEqualTo("MINUTES_IN_REVIEW")
        decide(r, loan.item, correction.copy(returnMinutesToDraft = true))
        assertThat(row(r).state).isEqualTo(MinutesStage.DRAFT)
        assertThat(events(r).map { it.kind to it.itemId }.last()).isEqualTo(MinutesEventKind.RETURNED_TO_DRAFT to loan.item)
        Audit.assertAudited(tenant.id, "minutes.returned_to_draft", r.meeting.id.toString(), secretary.id.toString())
        // Approved minutes freeze the decisions.
        transactions.inTenant(tenant.id) {
            val row = checkNotNull(minutesStore.lock(r.meeting.id))
            // (as T6's confirmation leaves them: approved, with the time and the approval)
            val approved =
                row.copy(
                    state = MinutesStage.APPROVED,
                    approvedAt = java.time.Instant.now(),
                    confirmedApprovalId = UUID.randomUUID(),
                )
            check(minutesStore.update(row, approved))
        }
        assertThat(refusal { decide(r, loan.item, correction.copy(reason = "Again")) }).isEqualTo("FROZEN")
    }

    @Test
    fun `only the committee's secretaries and forums manage not recused from any item draft the minutes, once the meeting is held`() {
        val r = running("Term loan, Delta Textiles", "Facility renewal, Acme")
        val (loan, renewal) = r.items
        val secretary2 = person().also { seat(it, com.memo.forum.ForumRole.SECRETARY) }
        declare(secretary2, r.meeting, loan.item)
        decide(r, loan.item, APPROVED)
        decide(r, renewal.item, APPROVED)
        // Not held yet: no minutes, nothing to draft.
        assertThat(refusal { asUser(secretary.account) { drafts.request(r.meeting.id, null, false) } }).isEqualTo("WRONG_STATE")
        assertThat(denied(secretary.account) { pages.view(r.meeting.id) }).isEqualTo("404")
        held(r)
        listOf(
            chair,
            director,
            reader,
        ).forEach { assertThat(denied(it.account) { drafts.request(r.meeting.id, null, false) }).isEqualTo("") }
        assertThat(denied(outsider.account) { drafts.request(r.meeting.id, null, false) }).isEqualTo("404")
        assertThat(denied(secretary2.account) { drafts.request(r.meeting.id, null, false) }).isEqualTo("RECUSED")
        // The page: secretaries only; the recused one sees it without the draft and may not draft or edit.
        listOf(chair, director, reader).forEach { assertThat(denied(it.account) { pages.view(r.meeting.id) }).isEqualTo("404") }
        generate(r.meeting.id)
        val recused = asUser(secretary2.account) { pages.view(r.meeting.id) }
        assertThat(listOf(recused.viewerRecused, recused.mayGenerate, recused.mayEdit)).containsExactly(true, false, false)
        assertThat(recused.draft).isNull()
        // An item of another meeting is not one of these minutes.
        assertThat(denied(secretary.account) { drafts.request(r.meeting.id, UUID.randomUUID(), false) }).isEqualTo("404")
        // Over HTTP: the chair is 404 on the page, 403 on a request.
        assertThat(read("/api/v1/meetings/${r.meeting.id}/minutes", chair.session)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(post("/api/v1/meetings/${r.meeting.id}/minutes/items/${loan.item}/regenerate", chair.session))
            .hasStatus(HttpStatus.FORBIDDEN)
        assertThat(post("/api/v1/meetings/${r.meeting.id}/minutes/items/${loan.item}/regenerate", secretary2.session))
            .hasStatus(HttpStatus.FORBIDDEN)
        val accepted = post("/api/v1/meetings/${r.meeting.id}/minutes/items/${loan.item}/regenerate", secretary.session)
        assertThat(accepted).hasStatus(HttpStatus.ACCEPTED)
        assertThat(accepted.json()["work"]["state"].asString()).isEqualTo("PENDING")
    }

    // Helpers

    private fun notesOf(
        r: Running,
        item: UUID,
        text: String,
    ) = notes.save(r.meeting.id, item, 0, text)

    @Autowired lateinit var notes: com.memo.minutes.internal.ItemNotes

    private fun row(r: Running): MinutesRow = checkNotNull(transactions.readInTenant(tenant.id) { minutesStore.find(r.meeting.id) })

    private fun events(r: Running) = transactions.readInTenant(tenant.id) { minutesStore.events(r.meeting.id) }

    private fun minutedItems(r: Running): List<UUID> =
        transactions
            .readInTenant(tenant.id) { meetingItems.agenda(r.meeting.id) }
            .items
            .filter { it.number != null }
            .map { it.id }

    private fun minutedNumber(
        r: Running,
        item: UUID,
    ): String = checkNotNull(transactions.readInTenant(tenant.id) { meetingItems.agenda(r.meeting.id) }.item(item)?.number)

    @Autowired lateinit var meetingItems: com.memo.meeting.MeetingItems

    /** Asks for the whole draft (or [item] filled again) as [by], and runs the job as db-scheduler would. */
    private fun generate(
        meetingId: UUID,
        item: UUID? = null,
        replaceEdits: Boolean = false,
        by: UserAccount = secretary.account,
    ) {
        asUser(by) { drafts.request(meetingId, item, replaceEdits) }
        runJob(meetingId)
    }

    private fun runJob(meetingId: UUID) {
        val seq = checkNotNull(transactions.readInTenant(tenant.id) { minutesStore.find(meetingId) }).workSeq
        asSystem { drafting.run(DraftTask(tenant.id.value, meetingId, seq)) }
    }

    /** The draft's current version. */
    private fun current(meetingId: UUID): ByteArray {
        val fileId = checkNotNull(transactions.readInTenant(tenant.id) { minutesStore.find(meetingId) }?.fileId)
        return asSystem {
            val version = checkNotNull(contents.current(fileId, "minutes.draft"))
            ByteArrayOutputStream().also { contents.write(version, it) }.toByteArray()
        }
    }

    /** A new version of the draft, saved by the secretary (as the editor's callback saves it). */
    private fun save(
        meetingId: UUID,
        bytes: ByteArray,
    ) {
        val fileId = checkNotNull(transactions.readInTenant(tenant.id) { minutesStore.find(meetingId) }?.fileId)
        val version = checkNotNull(transactions.readInTenant(tenant.id) { files.info(fileId) }).version
        asUserNoTx(secretary.account) { uploads.uploadVersion(fileId, version, "minutes.docx", bytes, UUID.randomUUID().toString()) }
    }

    private fun <T> asSystem(block: () -> T): T = TenantContext.call(tenant.id) { ActorContext.asSystem("minutes-draft") { block() } }

    private fun <T> asUserNoTx(
        account: UserAccount,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(account.id.value), null)) { TenantContext.call(tenant.id, block) }

    private fun refusal(block: () -> Any?): String =
        (runCatching { block() }.exceptionOrNull() as? RefusedException)?.reason ?: throw AssertionError("not refused")

    /** The 403 reason ("" for a plain 403), or "404". */
    private fun denied(
        account: UserAccount,
        block: () -> Any?,
    ): String =
        when (val e = runCatching { asUser(account, block) }.exceptionOrNull()) {
            is ForbiddenException -> e.reason.orEmpty()
            is MinutesNotFoundException -> "404"
            else -> if (e?.javaClass?.simpleName == "MeetingNotFoundException") "404" else throw AssertionError("not denied", e)
        }

    private fun year(r: Running): Int =
        r.meeting.startsAt
            .atZone(r.meeting.zone)
            .year

    private companion object {
        /** The fixture's nature, as the minutes print it (its full stop dropped). */
        const val NATURE_IN_MINUTES = "My brother-in-law is a director of Delta Textiles"
    }
}
