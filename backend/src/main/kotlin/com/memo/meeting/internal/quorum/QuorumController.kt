package com.memo.meeting.internal.quorum

import com.memo.common.authz.SignedIn
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The quorum forecast from the replies and the recorded snapshots (ADR-0064, design E05 §3). The live quorum per meeting
 * and per item is the attendance controller's `GET …/quorum`; snapshots are taken by E06's decisions, not over HTTP.
 */
@RestController
@Tag(name = "meeting")
class QuorumController(
    private val reads: QuorumReads,
    private val transactions: TenantTransactions,
) {
    @GetMapping("/api/v1/meetings/{meetingId}/quorum/forecast")
    @SignedIn
    @Operation(
        operationId = "getMeetingQuorumForecast",
        summary = "If everyone who said yes attends: the meeting's and each published item's quorum (officers)",
    )
    fun forecast(
        @PathVariable meetingId: UUID,
    ): ResponseEntity<QuorumForecastResponse> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(transactions.readInTenant(TenantContext.current()) { reads.forecast(meetingId) })

    @GetMapping("/api/v1/meetings/{meetingId}/quorum/snapshots/{snapshotId}")
    @SignedIn
    @Operation(operationId = "getMeetingQuorumSnapshot", summary = "A decision's recorded quorum (officers, forum.read)")
    fun snapshot(
        @PathVariable meetingId: UUID,
        @PathVariable snapshotId: UUID,
    ): ResponseEntity<QuorumSnapshotResponse> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(transactions.readInTenant(TenantContext.current()) { reads.snapshot(meetingId, snapshotId) })
}
