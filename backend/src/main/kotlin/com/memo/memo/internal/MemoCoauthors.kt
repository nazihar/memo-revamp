package com.memo.memo.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.SignedIn
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.identity.UserStatus
import com.memo.identity.UserSummaries
import com.memo.jooq.tables.references.MEMO_PARTICIPANT
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Co-authors are memo participants of kind `COAUTHOR` (ADR-0047). Inside a tenant transaction. */
@Repository
class MemoCoauthorStore(
    private val tenantDsl: TenantDsl,
    private val memos: MemoStore,
) {
    /** The memo's co-authors (ADR-0047), in the order they were named. */
    fun coauthors(memo: UUID): List<UUID> =
        tenantDsl
            .current()
            .select(MEMO_PARTICIPANT.USER_ID)
            .from(MEMO_PARTICIPANT)
            .where(MEMO_PARTICIPANT.MEMO_ID.eq(memo), MEMO_PARTICIPANT.KIND.eq(ParticipantKind.COAUTHOR.name))
            .orderBy(MEMO_PARTICIPANT.CREATED_AT, MEMO_PARTICIPANT.USER_ID)
            .fetch(MEMO_PARTICIPANT.USER_ID)
            .filterNotNull()

    fun isCoauthor(
        memo: UUID,
        user: UUID,
    ): Boolean =
        tenantDsl.current().fetchExists(
            MEMO_PARTICIPANT,
            MEMO_PARTICIPANT.MEMO_ID.eq(memo),
            MEMO_PARTICIPANT.USER_ID.eq(user),
            MEMO_PARTICIPANT.KIND.eq(ParticipantKind.COAUTHOR.name),
        )

    /** Makes [users] the co-authors: others named before are no longer participants of a draft. */
    fun setCoauthors(
        memo: UUID,
        users: List<UUID>,
        now: OffsetDateTime,
    ) {
        tenantDsl
            .current()
            .deleteFrom(MEMO_PARTICIPANT)
            .where(
                MEMO_PARTICIPANT.MEMO_ID.eq(memo),
                MEMO_PARTICIPANT.KIND.eq(ParticipantKind.COAUTHOR.name),
                MEMO_PARTICIPANT.USER_ID.notIn(users),
            ).execute()
        users.forEach { memos.addParticipant(memo, it, ParticipantKind.COAUTHOR, now) }
    }
}

/**
 * Co-authors of a draft (ADR-0047): the initiator names up to [MAX] active people of the organisation, who may then edit
 * the document and the attachments with them (in one editing session) and read the memo. They cannot submit, discard or
 * change the route, the subject or the details; they stay readers after submit. Inside a tenant transaction.
 */
@Service
class MemoCoauthors(
    private val memos: Memos,
    private val store: MemoCoauthorStore,
    private val users: UserSummaries,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    fun set(
        memoId: UUID,
        userIds: List<UUID>,
    ): Memo {
        val memo = memos.own(memoId)
        refuseIf(memo.status != MemoStatus.DRAFT, "WRONG_STATE") { "Co-authors can be named only while the memo is a draft." }
        val wanted = userIds.distinct()
        val known = users.of(wanted).filterValues { it.status == UserStatus.ACTIVE }
        val problems =
            listOfNotNull(
                FieldProblem("userIds", "TOO_MANY", "At most $MAX co-authors.").takeIf { wanted.size > MAX },
                FieldProblem("userIds", "INITIATOR", "You are the memo's author already.").takeIf { memo.initiatorId in wanted },
                FieldProblem("userIds", "UNKNOWN", "Choose active people of your organisation.").takeIf { wanted.any { it !in known } },
            )
        if (problems.isNotEmpty()) throw MemoRejectedException(problems)
        val before = store.coauthors(memoId)
        if (before.toSet() == wanted.toSet()) return memo
        store.setCoauthors(memoId, wanted, OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))
        audit.record(
            "memo.coauthors.changed",
            Memos.OBJECT_TYPE,
            memoId.toString(),
            before = mapOf("coauthors" to before.map { it.toString() }),
            after = mapOf("coauthors" to wanted.map { it.toString() }),
        )
        return memos.get(memoId)
    }

    companion object {
        const val MAX = 10
    }
}

/** The co-authors a draft should have, replacing those named before. */
data class CoauthorsRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val userIds: List<UUID>,
)

@RestController
@RequestMapping("/api/v1/memos/{id}")
@Tag(name = "memo")
class MemoCoauthorsController(
    private val coauthors: MemoCoauthors,
    private val memos: Memos,
    private val views: MemoViews,
    private val transactions: TenantTransactions,
) {
    @PutMapping("/coauthors")
    @SignedIn
    @Operation(operationId = "setMemoCoauthors", summary = "Name the people who may edit a draft's document and attachments")
    fun set(
        @PathVariable id: UUID,
        @RequestBody body: CoauthorsRequest,
    ): MemoView =
        transactions.inTenant(TenantContext.current()) {
            coauthors.set(id, body.userIds)
            views.of(memos.get(id))
        }
}
