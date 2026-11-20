package com.memo.meeting.internal.attendance

import com.memo.common.authz.SignedIn
import com.memo.common.ratelimit.RateLimit
import com.memo.common.ratelimit.RateLimiter
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import com.memo.meeting.AttendanceEventKind
import com.memo.meeting.AttendanceMode
import com.memo.meeting.internal.actorOrNull
import com.memo.meeting.internal.fingerprint
import com.memo.system.Idempotency
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

data class AttendanceEventRequest(
    val userId: UUID,
    /** Any kind but VOIDED (voiding has its own action). */
    val kind: AttendanceEventKind,
    /** For ARRIVED and MODE_CHANGED. */
    val mode: AttendanceMode? = null,
    /** When it happened; default now. Arrivals and departures lie within the meeting's window. */
    val at: Instant? = null,
    /** Default: the item in progress then; required (or in progress) to step out or back. */
    val itemId: UUID? = null,
    /** Up to 300 characters. */
    val note: String? = null,
    /** Why the register is corrected: required once the meeting is held, up to 500 characters. */
    val reason: String? = null,
)

data class ArrivalsRequest(
    val userIds: List<UUID>,
    val mode: AttendanceMode? = null,
    val at: Instant? = null,
)

data class VoidAttendanceRequest(
    /** 1 to 500 characters. */
    val reason: String? = null,
)

data class ItemProgressRequest(
    val itemId: UUID,
)

/**
 * The attendance register and the live quorum (ADR-0064, design E05 §3): reading the register, the quorum and its
 * changes; recording an event (the person's row version as `If-Match`), everyone who said yes at once, voiding, and the
 * item in progress. Writes need an `Idempotency-Key` and share the agenda's per-person rate limit.
 */
@RestController
@Tag(name = "meeting")
class AttendanceController(
    private val attendance: Attendance,
    private val reads: AttendanceReads,
    private val transactions: TenantTransactions,
    private val idempotency: Idempotency,
    private val limiter: RateLimiter,
    private val json: JsonMapper,
) {
    @GetMapping("/api/v1/meetings/{meetingId}/attendance")
    @SignedIn
    @Operation(operationId = "getMeetingAttendance", summary = "The register: everyone for officers, the viewer's own row and counts")
    fun register(
        @PathVariable meetingId: UUID,
    ): ResponseEntity<AttendanceResponse> = noStore(transactions.readInTenant(TenantContext.current()) { reads.register(meetingId) })

    @PostMapping("/api/v1/meetings/{meetingId}/attendance/events")
    @SignedIn
    @Operation(operationId = "recordMeetingAttendance", summary = "Record an arrival, a departure, apologies... (secretaries)")
    fun record(
        @PathVariable meetingId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: AttendanceEventRequest,
    ): ResponseEntity<RecordedAttendanceResponse> {
        limit()
        val input = AttendanceInput(body.userId, body.kind, body.mode, body.at, body.itemId, body.note, body.reason)
        val response =
            write("meeting.attendance.record", key, "$meetingId\n$ifMatch\n${json.writeValueAsString(body)}", meetingId) {
                attendance.record(meetingId, ifMatch?.let(::ifMatchVersion), input)
            }
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .eTag(etagOf(checkNotNull(response.person.version)))
            .body(response)
    }

    @PostMapping("/api/v1/meetings/{meetingId}/attendance/arrivals")
    @SignedIn
    @Operation(operationId = "recordMeetingArrivals", summary = "Mark everyone who said yes as arrived, in one action (secretaries)")
    fun arrivals(
        @PathVariable meetingId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: ArrivalsRequest,
    ): ResponseEntity<AttendanceResponse> {
        limit()
        val register =
            transactions.inTenant(TenantContext.current()) {
                idempotency.once("meeting.attendance.arrivals", key, fingerprint("$meetingId\n${json.writeValueAsString(body)}")) {
                    attendance.arrivals(meetingId, body.userIds, body.mode, body.at)
                    "ok"
                }
                reads.register(meetingId)
            }
        return noStore(register)
    }

    @PostMapping("/api/v1/meetings/{meetingId}/attendance/events/{eventId}/void")
    @SignedIn
    @Operation(operationId = "voidMeetingAttendance", summary = "Void a wrong event of the register, with a reason (secretaries)")
    fun void(
        @PathVariable meetingId: UUID,
        @PathVariable eventId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: VoidAttendanceRequest,
    ): ResponseEntity<RecordedAttendanceResponse> {
        limit()
        val response =
            write("meeting.attendance.void", key, "$meetingId\n$eventId\n$ifMatch\n${json.writeValueAsString(body)}", meetingId) {
                attendance.void(meetingId, eventId, ifMatch?.let(::ifMatchVersion), body.reason)
            }
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .eTag(etagOf(checkNotNull(response.person.version)))
            .body(response)
    }

    @PostMapping("/api/v1/meetings/{meetingId}/progress")
    @SignedIn
    @Operation(operationId = "setMeetingItemInProgress", summary = "\"Now: item n\": the item in progress (secretaries)")
    fun progress(
        @PathVariable meetingId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: ItemProgressRequest,
    ): ResponseEntity<ItemProgressResponse> {
        limit()
        val progress =
            transactions.inTenant(TenantContext.current()) {
                val stored =
                    idempotency.once("meeting.item.progress", key, fingerprint("$meetingId\n${body.itemId}")) {
                        val row = attendance.progress(meetingId, body.itemId)
                        "${row.itemId}|${row.startedAt}"
                    }
                val (item, startedAt) = stored.split('|')
                ItemProgressResponse(UUID.fromString(item), Instant.parse(startedAt))
            }
        return noStore(progress)
    }

    @GetMapping("/api/v1/meetings/{meetingId}/quorum")
    @SignedIn
    @Operation(
        operationId = "getMeetingQuorum",
        summary = "The meeting's quorum now or at an instant: seats for officers, numbers for members",
    )
    fun quorum(
        @PathVariable meetingId: UUID,
        @RequestParam(required = false) at: Instant?,
    ): ResponseEntity<LiveQuorumResponse> = noStore(transactions.readInTenant(TenantContext.current()) { reads.quorum(meetingId, at) })

    @GetMapping("/api/v1/meetings/{meetingId}/quorum/changes")
    @SignedIn
    @Operation(operationId = "getMeetingQuorumChanges", summary = "The meeting's quorum reached, lost and restored (officers)")
    fun changes(
        @PathVariable meetingId: UUID,
    ): ResponseEntity<List<QuorumChangeResponse>> = noStore(transactions.readInTenant(TenantContext.current()) { reads.changes(meetingId) })

    /** One write under its idempotency key; a repeat answers the event it recorded, read again. */
    private fun write(
        operation: String,
        key: String,
        request: String,
        meetingId: UUID,
        work: () -> Recorded,
    ): RecordedAttendanceResponse =
        transactions.inTenant(TenantContext.current()) {
            var recorded: Recorded? = null
            val eventId =
                idempotency.once(operation, key, fingerprint(request)) {
                    work()
                        .also { recorded = it }
                        .event.id
                        .toString()
                }
            val done = recorded ?: attendance.recordedAgain(meetingId, UUID.fromString(eventId))
            reads.recorded(meetingId, done)
        }

    private fun <T : Any> noStore(body: T): ResponseEntity<T> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)

    private fun limit() {
        actorOrNull()?.let { limiter.consume(RateLimit.AGENDA_WRITE_PER_USER, "${TenantContext.current()}:$it") }
    }
}
