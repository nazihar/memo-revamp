package com.memo.memo.internal

import com.memo.common.authz.SignedIn
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Following a memo (design E05 §4, flow J): readers of the memo only; others get 404. */
@RestController
@RequestMapping("/api/v1/memos/{id}/watch")
@Tag(name = "memo")
class MemoWatchController(
    private val watches: MemoWatches,
    private val transactions: TenantTransactions,
) {
    @GetMapping
    @SignedIn
    @Operation(operationId = "getMemoWatch", summary = "Whether I follow this memo, and why")
    fun status(
        @PathVariable id: UUID,
    ): ResponseEntity<WatchStatus> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(transactions.readInTenant(TenantContext.current()) { watches.status(id) })

    @PutMapping
    @SignedIn
    @Operation(operationId = "watchMemo", summary = "Follow this memo")
    fun watch(
        @PathVariable id: UUID,
    ): ResponseEntity<WatchStatus> = ResponseEntity.ok(transactions.inTenant(TenantContext.current()) { watches.watch(id) })

    @DeleteMapping
    @SignedIn
    @Operation(operationId = "unwatchMemo", summary = "Stop following this memo")
    fun unwatch(
        @PathVariable id: UUID,
    ): ResponseEntity<Unit> {
        transactions.inTenant(TenantContext.current()) { watches.unwatch(id) }
        return ResponseEntity.noContent().build()
    }
}

/** The Watching tab, bulk actions on the inbox, and how a person follows memos (design E05 §4, flow J). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "memo")
class InboxWatchController(
    private val watches: MemoWatches,
    private val bulk: InboxBulk,
    private val transactions: TenantTransactions,
) {
    @GetMapping("/inbox/watching")
    @SignedIn
    @Operation(operationId = "listWatching", summary = "Memos I follow that I may still read, newest first")
    fun watching(
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "25") limit: Int,
    ): ResponseEntity<WatchingPage> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(transactions.readInTenant(TenantContext.current()) { watches.watching(cursor, limit) })

    @PostMapping("/inbox/bulk")
    @SignedIn
    @Operation(
        operationId = "inboxBulk",
        summary = "Watch, unwatch, claim, release or reassign up to 100 items, each as if alone (never a decision)",
    )
    fun bulk(
        @RequestBody body: BulkRequest,
    ): ResponseEntity<BulkResponse> = ResponseEntity.ok(bulk.perform(body))

    @GetMapping("/me/watch-settings")
    @SignedIn
    @Operation(operationId = "getWatchSettings", summary = "Whether I follow memos I initiate or act on, and how I get email about them")
    fun settings(): ResponseEntity<WatchSettings> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(transactions.readInTenant(TenantContext.current()) { watches.settings() })

    @PutMapping("/me/watch-settings")
    @SignedIn
    @Operation(operationId = "saveWatchSettings", summary = "Change how I follow memos")
    fun saveSettings(
        @RequestBody body: WatchSettings,
    ): ResponseEntity<WatchSettings> = ResponseEntity.ok(transactions.inTenant(TenantContext.current()) { watches.saveSettings(body) })
}
