package com.memo.search.internal.decision

import com.memo.common.authz.SignedIn
import com.memo.common.context.ActorContext
import com.memo.common.ratelimit.RateLimit
import com.memo.common.ratelimit.RateLimiter
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The decision register across committees (ADR-0069, design E08 §3; flow AS): committees' officers and members (their
 * committees), `forums.manage` and `forum.read` (every committee); 403 for anyone else. Searches are POST bodies (words
 * never in a URL), rate-limited per person (`DECISION_SEARCH_PER_USER`, 30 a minute, before any transaction) and audited.
 * Problems of the search package: 422 `validation` with `errors` (`TEXT_TOO_LONG`, `TOO_MANY_TERMS`, `FORUM_OUT_OF_SCOPE`,
 * `RANGE`, `SORT_NEEDS_ONE_FORUM`, `TOO_MANY`, `cursor INVALID`), 503 when a search takes too long.
 */
@RestController
@RequestMapping("/api/v1/decision-register")
@Tag(name = "search")
class DecisionRegisterController(
    private val register: DecisionRegister,
    private val transactions: TenantTransactions,
    private val limiter: RateLimiter,
) {
    @GetMapping("/scope")
    @SignedIn
    @Operation(
        operationId = "getDecisionRegisterScope",
        summary = "The committees, committee types, outcomes and memo types the viewer may filter the register by",
    )
    fun scope(): ResponseEntity<RegisterScopeView> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(transactions.readInTenant(TenantContext.current()) { register.scope() })

    @PostMapping("/search")
    @SignedIn
    @Operation(
        operationId = "searchDecisionRegister",
        summary = "Decisions and resolutions the viewer may see, in full or as restricted lines, with totals and facets",
    )
    fun search(
        @RequestBody body: DecisionSearchRequest,
    ): ResponseEntity<DecisionSearchView> {
        val actor =
            ActorContext
                .current()
                .actor.id
                .orEmpty()
        limiter.consume(RateLimit.DECISION_SEARCH_PER_USER, "${TenantContext.current()}:$actor")
        val page = transactions.inTenant(TenantContext.current()) { register.search(body) }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page)
    }
}
