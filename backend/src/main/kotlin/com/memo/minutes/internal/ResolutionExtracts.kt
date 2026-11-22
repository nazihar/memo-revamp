package com.memo.minutes.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.ForbiddenException
import com.memo.common.id.IdGenerator
import com.memo.common.web.refuseIf
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumRole
import com.memo.identity.UserSummaries
import com.memo.meeting.MeetingItems
import com.memo.meeting.MeetingStatus
import com.memo.meeting.MeetingViewers
import com.memo.meeting.Meetings
import com.memo.minutes.Decisions
import com.memo.minutes.ResolutionState
import com.memo.minutes.Resolutions
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** One certified extract as its readers see it (design E06 §3, flow AC2–AC3). */
data class ResolutionExtractResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val resolutionId: UUID,
    /** `PENDING` while it is made, then `READY` (or `FAILED` with [error]). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val state: String,
    val error: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val issuedBy: MinutesPerson,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val issuedAt: Instant,
    /** Whether the minutes were approved when it was issued (the extract says so). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val minutesApproved: Boolean,
    val minutesApprovedAt: Instant?,
    /** The certification's fingerprint printed on the extract, to check a copy against. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val certificationHash: String,
    /** The file's SHA-256 (to check a download against), once made. */
    val sha256: String?,
    val pages: Int?,
    /** veraPDF's verdict (PDF/A-2u), once made. */
    val compliant: Boolean?,
)

/** What an extract certifies (read in one transaction): the resolution as it stands, its committee, meeting and item. */
data class ExtractContent(
    val resolutionId: UUID,
    val number: String,
    val committee: String,
    val meetingLabel: String,
    val meetingDate: java.time.LocalDate,
    val itemNumber: String?,
    /** The item's title, when anyone who may read the extract may read it (not hidden, restricted or members only). */
    val itemTitle: String?,
    val outcomeLabel: String,
    val text: String?,
    val conditions: List<String>,
    val validUntil: java.time.LocalDate?,
)

/**
 * Certified resolution extracts (design E06 §1.6.6, §1.9, flow AC3; the owner's answer 8): a secretary of the committee in
 * office, not recused from the item, issues one with a fresh step-up once the memo has moved on (the decision released;
 * for an item without a memo, once the meeting is held), never for a void number (409 `VOID`, `NOT_RELEASED`). The extract
 * says whether the minutes are approved yet, and shows only the resolution: the committee, the meeting, the item's number
 * (and its title when nobody who may read the extract is kept from it), the number, the outcome, the text, the conditions
 * and the validity; never the vote, names or discussion. The certification is ADR-0051's evidence of the secretary's
 * authentication with a hash over it and what it certifies. The `minutes-extract` job makes it as PDF/A through the memo
 * module's [RecordPdfs], stores it as a `minutes.extract` record (object-locked) and audits its SHA-256; its readers (the
 * committee's officers, `forums.manage`, `forum.read` and the memo's readers) stream it, each open audited.
 */
@Service
class ResolutionExtracts(
    private val extracts: MinutesExtractStore,
    private val access: ResolutionAccess,
    private val resolutions: Resolutions,
    private val decisions: Decisions,
    private val minutes: MinutesStore,
    private val meetings: Meetings,
    private val items: MeetingItems,
    private val viewers: MeetingViewers,
    private val directory: ForumDirectory,
    private val evidence: ApprovalEvidence,
    private val certification: ExtractCertification,
    private val jobs: MinutesJobs,
    private val users: UserSummaries,
    private val ids: IdGenerator,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    /**
     * Who may issue an extract of [resolutionId] and whether it can be issued (inside a transaction, before the step-up): 404
     * for a resolution the actor does not see, 403 for anyone but a secretary of the committee in office, 403 `RECUSED` for
     * one recused from the item, 409 `VOID` and `NOT_RELEASED`.
     */
    fun requireIssuer(resolutionId: UUID): ResolutionResponse {
        val shown = access.resolution(resolutionId)
        val user = checkNotNull(actorOrNull())
        val secretary = directory.membersOn(shown.forumId).any { it.userId == user && it.role == ForumRole.SECRETARY }
        if (!secretary) throw ForbiddenException(null, "NOT_SECRETARY", "Only a secretary of the committee issues certified extracts.")
        if (shown.viewerRecused) {
            throw ForbiddenException(null, "RECUSED", "You declared an interest in this item: another secretary issues its extracts.")
        }
        refuseIf(shown.state == ResolutionState.VOID, "VOID") { "This resolution number is void: it has no extract." }
        val decision = checkNotNull(decisions.find(shown.decisionId)) { "the resolution's decision is gone" }
        val meeting = checkNotNull(meetings.find(shown.meetingId))
        val released =
            if (decision.submissionId != null) {
                decision.releasedAt != null
            } else {
                meeting.status in setOf(MeetingStatus.HELD, MeetingStatus.MINUTES_APPROVED)
            }
        refuseIf(!released, "NOT_RELEASED") {
            "An extract is issued once the memo has moved on with the committee's decision (or the meeting is held)."
        }
        return shown
    }

    /** Issues an extract of [resolutionId] (inside a transaction, after [requireIssuer] and the step-up); audited. */
    fun issue(resolutionId: UUID): ResolutionExtractResponse {
        val shown = requireIssuer(resolutionId)
        val user = checkNotNull(actorOrNull())
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val row = minutes.find(shown.meetingId)
        val approvedAt = row?.approvedAt?.takeIf { row.state == MinutesStage.APPROVED }
        val content = content(resolutionId) ?: throw MinutesNotFoundException("resolution")
        val proof = evidence.of(now)
        val id = ids.next()
        val state = if (approvedAt != null) ExtractMinutesState.APPROVED else ExtractMinutesState.NOT_APPROVED
        val signed = evidence.tree(mapOf("authentication" to proof.authentication, "client" to proof.client, "role" to "SECRETARY"))
        val extract =
            ExtractRow(
                id = id,
                resolutionId = resolutionId,
                meetingId = shown.meetingId,
                forumId = shown.forumId,
                issuedBy = user,
                issuedAt = now,
                minutesState = state,
                minutesApprovedAt = approvedAt,
                certification = signed,
                certificationHash = "",
                state = RecordState.PENDING,
                error = null,
                fileId = null,
                fileVersionId = null,
                sha256 = null,
                sizeBytes = null,
                pages = null,
                compliant = null,
                validation = null,
                builtAt = null,
                version = 0,
            )
        val hashed = extract.copy(certificationHash = certification.certificationHash(extract, certification.contentSha256(content)))
        extracts.insert(hashed)
        audit.record(
            "minutes.extract.requested",
            DecisionEffects.OBJECT_TYPE,
            shown.meetingId.toString(),
            after =
                mapOf(
                    "extractId" to id.toString(),
                    "resolutionId" to resolutionId.toString(),
                    "number" to shown.number,
                    "minutesState" to state.name,
                    "certificationHash" to hashed.certificationHash,
                ),
        )
        jobs.extract(id)
        return response(hashed, users.names(setOf(user)))
    }

    /** The extracts of [resolutionId] for its extract readers (403, 404 otherwise), the latest first. */
    fun list(resolutionId: UUID): List<ResolutionExtractResponse> {
        requireReader(resolutionId)
        val rows = extracts.of(resolutionId)
        val names = users.names(rows.map { it.issuedBy }.toSet())
        return rows.map { response(it, names) }
    }

    /**
     * The extract [extractId] of [resolutionId] to stream (inside a transaction): its file version, or null while it is made
     * (202); 409 `EXTRACT_FAILED` when it could not be made.
     */
    fun serve(
        resolutionId: UUID,
        extractId: UUID,
    ): ExtractRow? {
        requireReader(resolutionId)
        val row = extracts.find(extractId)?.takeIf { it.resolutionId == resolutionId } ?: throw MinutesNotFoundException("extract")
        refuseIf(row.state == RecordState.FAILED, "EXTRACT_FAILED") { "This extract could not be made: issue another." }
        return row.takeIf { it.state == RecordState.READY }
    }

    /**
     * The extract's readers (design E06 §1.9, §4): the committee's officers and `forums.manage` and `forum.read` holders who
     * see its decision, and the readers of its memo; 404 for a resolution they do not see, 403 for its other viewers.
     */
    private fun requireReader(resolutionId: UUID): ResolutionResponse {
        val shown = access.resolution(resolutionId)
        val view = viewers.find(shown.meetingId)
        val byRole = view != null && (view.officer || view.manages || view.reader)
        if (!byRole && shown.memo == null) throw ForbiddenException(null)
        return shown
    }

    /** What the extract of [resolutionId] certifies now (no access check: the issuer's and the job's own reads). */
    fun content(resolutionId: UUID): ExtractContent? = resolutions.find(resolutionId)?.let(::contentOf)

    private fun contentOf(resolution: com.memo.minutes.ResolutionRef): ExtractContent? {
        val decision = decisions.find(resolution.decisionId)
        val meeting = meetings.find(resolution.meetingId)
        val forum = directory.forum(resolution.forumId)
        if (decision == null || meeting == null || forum == null) return null
        val item = items.agenda(resolution.meetingId).items.firstOrNull { it.id == decision.itemId }
        val e = decision.current
        return ExtractContent(
            resolutionId = resolution.id,
            number = resolution.number,
            committee = forum.name,
            meetingLabel = meeting.label ?: meeting.title ?: meeting.id.toString(),
            meetingDate = meeting.startsAt.atZone(meeting.timeZone).toLocalDate(),
            itemNumber = item?.number,
            itemTitle = item?.takeIf { !it.titleHidden && !it.restricted && !it.membersOnly }?.title,
            outcomeLabel = e.outcomeLabel,
            text = e.text,
            conditions = e.conditions,
            validUntil = e.validUntil,
        )
    }

    private fun response(
        row: ExtractRow,
        names: Map<UUID, String>,
    ) = ResolutionExtractResponse(
        id = row.id,
        resolutionId = row.resolutionId,
        state = row.state.name,
        error = row.error,
        issuedBy = MinutesPerson(row.issuedBy, names[row.issuedBy].orEmpty()),
        issuedAt = row.issuedAt,
        minutesApproved = row.minutesState == ExtractMinutesState.APPROVED,
        minutesApprovedAt = row.minutesApprovedAt,
        certificationHash = row.certificationHash,
        sha256 = row.sha256,
        pages = row.pages,
        compliant = row.compliant,
    )
}
