package com.memo.meeting.internal

import com.memo.common.authz.SignedIn
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import com.memo.meeting.MeetingKind
import com.memo.meeting.MeetingMode
import com.memo.meeting.MeetingStatus
import com.memo.system.Idempotency
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

data class MeetingRequest(
    val kind: MeetingKind = MeetingKind.ORDINARY,
    val title: String? = null,
    /** On the clock of [timeZone]. */
    val startsAt: LocalDateTime,
    val endsAt: LocalDateTime,
    /** An IANA zone name such as `Asia/Dhaka`; null: the organisation's zone. */
    val timeZone: String? = null,
    val mode: MeetingMode,
    /** Required in person and hybrid. */
    val location: String? = null,
    /** An `https` link; required online and hybrid. */
    val joinUrl: String? = null,
    /** Meeting id, passcode, dial-in numbers. */
    val joinDetails: String? = null,
    /** Null: the committee's level; never below it. */
    val classificationId: UUID? = null,
)

data class MeetingEditRequest(
    val kind: MeetingKind = MeetingKind.ORDINARY,
    val title: String? = null,
    val mode: MeetingMode,
    val location: String? = null,
    val joinUrl: String? = null,
    val joinDetails: String? = null,
    val classificationId: UUID? = null,
)

data class RescheduleRequest(
    val startsAt: LocalDateTime,
    val endsAt: LocalDateTime,
    val timeZone: String? = null,
    /** Required once the meeting is scheduled. */
    val reason: String? = null,
)

data class CancelMeetingRequest(
    val reason: String? = null,
)

data class GuestRequest(
    val userId: UUID,
    /** For example "Chief Financial Officer". */
    val note: String? = null,
)

/**
 * Meetings (ADR-0060, design E03 §3). An invisible meeting or committee is 404; one the actor sees but may not run is
 * 403. Creating needs an `Idempotency-Key`; every change of the meeting itself needs `If-Match` with its version.
 */
@RestController
@Tag(name = "meeting")
class MeetingsController(
    private val lifecycle: MeetingLifecycle,
    private val reads: MeetingReads,
    private val calls: MeetingCalls,
    private val idempotency: Idempotency,
    private val json: JsonMapper,
) {
    @GetMapping("/api/v1/forums/{forumId}/meetings")
    @SignedIn
    @Operation(operationId = "listForumMeetings", summary = "A committee's meetings by start time, with a cursor")
    fun list(
        @PathVariable forumId: UUID,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
        @RequestParam(required = false) status: Set<MeetingStatus>?,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<MeetingPageResponse> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(calls.read { reads.ofForum(forumId, from, to, status.orEmpty(), cursor) })

    @PostMapping("/api/v1/forums/{forumId}/meetings")
    @SignedIn
    @Operation(operationId = "createMeeting", summary = "Create a draft meeting of a committee (its secretary or forums.manage)")
    fun create(
        @PathVariable forumId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: MeetingRequest,
    ): ResponseEntity<MeetingResponse> {
        val created =
            calls.write {
                val request = fingerprint("$forumId\n${json.writeValueAsString(body)}")
                val id =
                    idempotency.once("meeting.create", key, request) {
                        lifecycle.create(forumId, body.input(), TimesInput(body.startsAt, body.endsAt, body.timeZone)).id.toString()
                    }
                reads.get(UUID.fromString(id))
            }
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etagOf(created.version)).body(created)
    }

    @GetMapping("/api/v1/meetings/{meetingId}")
    @SignedIn
    @Operation(operationId = "getMeeting", summary = "One meeting, as the viewer may see it")
    fun get(
        @PathVariable meetingId: UUID,
    ): ResponseEntity<MeetingResponse> {
        val meeting = calls.read { reads.get(meetingId) }
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .eTag(etagOf(meeting.version))
            .body(meeting)
    }

    @PatchMapping("/api/v1/meetings/{meetingId}")
    @SignedIn
    @Operation(operationId = "updateMeeting", summary = "Change a meeting's title, kind, place, link or classification")
    fun update(
        @PathVariable meetingId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: MeetingEditRequest,
    ): ResponseEntity<MeetingResponse> {
        val version = ifMatchVersion(ifMatch)
        val input =
            MeetingInput(
                body.kind,
                MeetingPlace(body.title, body.mode, body.location, body.joinUrl, body.joinDetails),
                body.classificationId,
            )
        return calls.changed { lifecycle.edit(meetingId, version, input) }
    }

    @GetMapping("/api/v1/me/meetings")
    @SignedIn
    @Operation(operationId = "listMyMeetings", summary = "Upcoming and recent meetings the user attends or runs")
    fun mine(
        @RequestParam(required = false) from: LocalDate?,
    ): ResponseEntity<List<MeetingSummaryResponse>> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(calls.read { reads.mine(from) })
}

/**
 * Moving a meeting along its states, and its guests (design E03 §3): each change needs `If-Match` with the meeting's
 * version (guests excepted: the unique guest index decides a double add).
 */
@RestController
@Tag(name = "meeting")
class MeetingStatusController(
    private val lifecycle: MeetingLifecycle,
    private val guests: MeetingGuests,
    private val calls: MeetingCalls,
) {
    @PostMapping("/api/v1/meetings/{meetingId}/schedule")
    @SignedIn
    @Operation(operationId = "scheduleMeeting", summary = "Schedule a draft meeting: its audience sees it")
    fun schedule(
        @PathVariable meetingId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<MeetingResponse> {
        val version = ifMatchVersion(ifMatch)
        return calls.changed { lifecycle.schedule(meetingId, version) }
    }

    @PostMapping("/api/v1/meetings/{meetingId}/reschedule")
    @SignedIn
    @Operation(operationId = "rescheduleMeeting", summary = "Move a meeting to other times (a reason once scheduled)")
    fun reschedule(
        @PathVariable meetingId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: RescheduleRequest,
    ): ResponseEntity<MeetingResponse> {
        val version = ifMatchVersion(ifMatch)
        return calls.changed {
            lifecycle.reschedule(
                meetingId,
                version,
                TimesInput(body.startsAt, body.endsAt, body.timeZone),
                body.reason,
            )
        }
    }

    @PostMapping("/api/v1/meetings/{meetingId}/start")
    @SignedIn
    @Operation(operationId = "startMeeting", summary = "Mark a scheduled meeting as in progress")
    fun start(
        @PathVariable meetingId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<MeetingResponse> {
        val version = ifMatchVersion(ifMatch)
        return calls.changed { lifecycle.start(meetingId, version) }
    }

    @PostMapping("/api/v1/meetings/{meetingId}/end")
    @SignedIn
    @Operation(operationId = "endMeeting", summary = "Mark a meeting in progress as held")
    fun end(
        @PathVariable meetingId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<MeetingResponse> {
        val version = ifMatchVersion(ifMatch)
        return calls.changed { lifecycle.end(meetingId, version) }
    }

    @PostMapping("/api/v1/meetings/{meetingId}/cancel")
    @SignedIn
    @Operation(operationId = "cancelMeeting", summary = "Discard a draft or cancel a scheduled meeting, with a reason")
    fun cancel(
        @PathVariable meetingId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: CancelMeetingRequest,
    ): ResponseEntity<MeetingResponse> {
        val version = ifMatchVersion(ifMatch)
        return calls.changed { lifecycle.cancel(meetingId, version, body.reason) }
    }

    @PostMapping("/api/v1/meetings/{meetingId}/guests")
    @SignedIn
    @Operation(operationId = "addMeetingGuest", summary = "Add a guest who attends the whole meeting")
    fun addGuest(
        @PathVariable meetingId: UUID,
        @RequestBody body: GuestRequest,
    ): ResponseEntity<MeetingResponse> = calls.changed { guests.add(meetingId, body.userId, body.note) }

    @PostMapping("/api/v1/meetings/{meetingId}/guests/{userId}/remove")
    @SignedIn
    @Operation(operationId = "removeMeetingGuest", summary = "Remove a guest from a meeting")
    fun removeGuest(
        @PathVariable meetingId: UUID,
        @PathVariable userId: UUID,
    ): ResponseEntity<MeetingResponse> = calls.changed { guests.remove(meetingId, userId) }
}

/** The transactions of the meeting endpoints, and the answer to a change: the meeting as the actor now sees it. */
@Component
class MeetingCalls(
    private val reads: MeetingReads,
    private val access: MeetingAccess,
    private val transactions: TenantTransactions,
) {
    /** Runs [change] and answers the meeting with its new version as the ETag. */
    fun changed(change: () -> MeetingRow): ResponseEntity<MeetingResponse> {
        val meeting =
            write {
                val row = change()
                reads.detail(row, access.relation(row) ?: MeetingRelation.OFFICER)
            }
        return ResponseEntity.ok().eTag(etagOf(meeting.version)).body(meeting)
    }

    fun <T> read(block: () -> T): T = transactions.readInTenant(TenantContext.current(), block)

    fun <T> write(block: () -> T): T = transactions.inTenant(TenantContext.current(), block)
}

private fun MeetingRequest.input() = MeetingInput(kind, MeetingPlace(title, mode, location, joinUrl, joinDetails), classificationId)
