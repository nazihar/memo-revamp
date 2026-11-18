package com.memo.meeting.internal

import com.memo.common.authz.SignedIn
import com.memo.common.web.refuseIf
import com.memo.files.FileServing
import com.memo.files.OpenAudit
import com.memo.files.ServeOutcome
import com.memo.files.ServedContent
import com.memo.system.Idempotency
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class SeenRequest(
    /** The published revision the viewer has read. */
    val revision: Int,
)

/**
 * The agenda as its audience reads it (ADR-0061, design E03 §3, flow P): a published revision through the paper rule,
 * with "What changed since you last looked"; the revision list; the last revision seen; the officers' preview of the
 * draft as one person would see it; paper opens; and consent "Ask to discuss". Every read path applies `PaperAccess`.
 */
@RestController
@Tag(name = "meeting")
class MemberAgendaController(
    private val agendas: MemberAgendas,
    private val consent: ConsentRequests,
    private val opens: PaperOpens,
    private val serving: FileServing,
    private val idempotency: Idempotency,
    private val calls: MeetingCalls,
) {
    @GetMapping("/api/v1/meetings/{meetingId}/agenda")
    @SignedIn
    @Operation(
        operationId = "getMeetingAgenda",
        summary = "A published revision of the agenda as the viewer may see it (the latest by default)",
    )
    fun agenda(
        @PathVariable meetingId: UUID,
        @RequestParam(required = false) revision: Int?,
    ): ResponseEntity<MemberAgendaResponse> = fresh(calls.read { agendas.agenda(meetingId, revision) })

    @GetMapping("/api/v1/meetings/{meetingId}/agenda/revisions")
    @SignedIn
    @Operation(operationId = "listAgendaRevisions", summary = "The published revisions of the agenda")
    fun revisions(
        @PathVariable meetingId: UUID,
    ): ResponseEntity<List<PublishedRevisionResponse>> = fresh(calls.read { agendas.revisions(meetingId) })

    @PostMapping("/api/v1/meetings/{meetingId}/seen")
    @SignedIn
    @Operation(operationId = "markAgendaSeen", summary = "Record the last revision of the agenda the viewer has read")
    fun seen(
        @PathVariable meetingId: UUID,
        @RequestBody body: SeenRequest,
    ): ResponseEntity<Void> {
        calls.write { agendas.seen(meetingId, body.revision) }
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/api/v1/meetings/{meetingId}/agenda/preview")
    @SignedIn
    @Operation(operationId = "previewAgendaAs", summary = "The draft agenda as one person of the audience would see it if published now")
    fun preview(
        @PathVariable meetingId: UUID,
        @RequestParam("as") userId: UUID,
    ): ResponseEntity<MemberAgendaResponse> = fresh(calls.read { agendas.preview(meetingId, userId) })

    @GetMapping("/api/v1/meetings/{meetingId}/papers/{paperId}/content")
    @SignedIn
    @Operation(
        operationId = "getMeetingPaperContent",
        summary = "A paper as its revision carried it: the PDF to read (supports Range), or the original for secretaries",
    )
    fun paper(
        @PathVariable meetingId: UUID,
        @PathVariable paperId: UUID,
        @RequestParam(required = false) revision: Int?,
        @RequestParam(required = false, defaultValue = "PDF") kind: ServedContent,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val paper = calls.read { opens.open(meetingId, paperId, revision, kind == ServedContent.ORIGINAL) }
        val audit =
            OpenAudit(
                action = "meeting.paper.opened",
                objectType = MeetingLifecycle.OBJECT_TYPE,
                objectId = meetingId.toString(),
                details = mapOf("paperId" to paperId.toString(), "itemId" to paper.itemId.toString(), "revision" to paper.revision),
            )
        val outcome = serving.serve(paper.fileVersionId, kind, request, response, audit)
        if (outcome == ServeOutcome.MISSING) throw MeetingNotFoundException("paper")
        refuseIf(outcome == ServeOutcome.NOT_READY, "PAPER_NOT_READY") { "The paper is being prepared. Try again shortly." }
        calls.write { opens.opened(paperId) }
    }

    @PostMapping("/api/v1/meetings/{meetingId}/agenda/items/{itemId}/discussion-requests")
    @SignedIn
    @Operation(operationId = "askToDiscuss", summary = "Ask for a consent item to be lifted for discussion")
    fun ask(
        @PathVariable meetingId: UUID,
        @PathVariable itemId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
    ): ResponseEntity<MemberAgendaResponse> {
        val agenda =
            calls.write {
                idempotency.once("meeting.discussion.request", key, fingerprint("$meetingId\n$itemId")) {
                    consent.ask(meetingId, itemId)
                    itemId.toString()
                }
                agendas.agenda(meetingId, null)
            }
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(agenda)
    }

    @PostMapping("/api/v1/meetings/{meetingId}/agenda/items/{itemId}/discussion-requests/mine/withdraw")
    @SignedIn
    @Operation(operationId = "withdrawDiscussionRequest", summary = "Withdraw one's request to discuss a consent item")
    fun withdraw(
        @PathVariable meetingId: UUID,
        @PathVariable itemId: UUID,
    ): ResponseEntity<MemberAgendaResponse> =
        fresh(
            calls.write {
                consent.withdraw(meetingId, itemId)
                agendas.agenda(meetingId, null)
            },
        )

    private fun <T : Any> fresh(body: T): ResponseEntity<T> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
}
