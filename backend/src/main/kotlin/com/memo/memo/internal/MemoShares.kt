package com.memo.memo.internal

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import com.memo.audit.AuditLog
import com.memo.common.authz.Clearances
import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.authz.SignedIn
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.id.IdGenerator
import com.memo.common.jobs.ActiveTenants
import com.memo.common.jobs.Jobs
import com.memo.common.jobs.TenantScopedPayload
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.identity.UserStatus
import com.memo.identity.UserSummaries
import com.memo.jooq.tables.records.MemoShareRecord
import com.memo.jooq.tables.references.MEMO_PARTICIPANT
import com.memo.jooq.tables.references.MEMO_SHARE
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** An explicit share of a memo with one person (ADR-0050). */
data class MemoShare(
    val id: UUID,
    val memoId: UUID,
    val userId: UUID,
    val grantedBy: UUID,
    val reason: String,
    val grantedAt: Instant,
    val expiresAt: Instant?,
    val revokedAt: Instant?,
)

/** A share to write. */
data class NewShare(
    val memoId: UUID,
    val userId: UUID,
    val grantedBy: UUID,
    val reason: String,
    val expiresAt: OffsetDateTime?,
    val at: OffsetDateTime,
)

/**
 * `memo_share` and the participant row each person's shares give (V13). A share's row is the person's `SHARED`
 * participant row: it lapses at the latest expiry of their active shares and is revoked with the last of them. A row of
 * any other kind is never touched. Inside a tenant transaction.
 */
@Repository
class MemoShareStore(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
) {
    fun insert(share: NewShare): UUID {
        val id = ids.next()
        tenantDsl
            .current()
            .insertInto(MEMO_SHARE)
            .set(MEMO_SHARE.ID, id)
            .set(MEMO_SHARE.TENANT_ID, tenantDsl.tenant().value)
            .set(MEMO_SHARE.MEMO_ID, share.memoId)
            .set(MEMO_SHARE.USER_ID, share.userId)
            .set(MEMO_SHARE.GRANTED_BY, share.grantedBy)
            .set(MEMO_SHARE.REASON, share.reason)
            .set(MEMO_SHARE.GRANTED_AT, share.at)
            .set(MEMO_SHARE.EXPIRES_AT, share.expiresAt)
            .execute()
        return id
    }

    fun get(id: UUID): MemoShare? =
        tenantDsl
            .current()
            .selectFrom(MEMO_SHARE)
            .where(MEMO_SHARE.ID.eq(id))
            .fetchOne(::share)

    /** The shares of [memoId] that count now: not revoked and not past their expiry, oldest first. */
    fun active(
        memoId: UUID,
        now: OffsetDateTime,
    ): List<MemoShare> =
        tenantDsl
            .current()
            .selectFrom(MEMO_SHARE)
            .where(
                MEMO_SHARE.MEMO_ID.eq(memoId),
                MEMO_SHARE.REVOKED_AT.isNull,
                MEMO_SHARE.EXPIRES_AT.isNull.or(MEMO_SHARE.EXPIRES_AT.gt(now)),
            ).orderBy(MEMO_SHARE.GRANTED_AT, MEMO_SHARE.ID)
            .fetch(::share)

    fun revoke(
        id: UUID,
        by: UUID?,
        reason: String,
        now: OffsetDateTime,
    ): Boolean =
        tenantDsl
            .current()
            .update(MEMO_SHARE)
            .set(MEMO_SHARE.REVOKED_AT, now)
            .set(MEMO_SHARE.REVOKED_BY, by)
            .set(MEMO_SHARE.REVOKE_REASON, reason)
            .where(MEMO_SHARE.ID.eq(id), MEMO_SHARE.REVOKED_AT.isNull)
            .execute() == 1

    /** Shares past their expiry that are not yet recorded as expired, at most [limit]. */
    fun lapsed(
        now: OffsetDateTime,
        limit: Int,
    ): List<MemoShare> =
        tenantDsl
            .current()
            .selectFrom(MEMO_SHARE)
            .where(MEMO_SHARE.REVOKED_AT.isNull, MEMO_SHARE.EXPIRES_AT.le(now))
            .orderBy(MEMO_SHARE.EXPIRES_AT)
            .limit(limit)
            .fetch(::share)

    /** The kind of [userId]'s active participant row of [memoId], if any. */
    fun activeKind(
        memoId: UUID,
        userId: UUID,
        now: OffsetDateTime,
    ): ParticipantKind? =
        tenantDsl
            .current()
            .select(MEMO_PARTICIPANT.KIND)
            .from(MEMO_PARTICIPANT)
            .where(
                MEMO_PARTICIPANT.MEMO_ID.eq(memoId),
                MEMO_PARTICIPANT.USER_ID.eq(userId),
                MEMO_PARTICIPANT.REVOKED_AT.isNull,
                MEMO_PARTICIPANT.EXPIRES_AT.isNull.or(MEMO_PARTICIPANT.EXPIRES_AT.gt(now)),
            ).fetchOne(MEMO_PARTICIPANT.KIND)
            ?.let(ParticipantKind::valueOf)

    /** Brings [userId]'s `SHARED` row in line with their active shares: the latest expiry, or revoked when none is left. */
    fun follow(
        memoId: UUID,
        userId: UUID,
        now: OffsetDateTime,
    ) {
        val left = active(memoId, now).filter { it.userId == userId }
        val update =
            tenantDsl
                .current()
                .update(MEMO_PARTICIPANT)
        val changed =
            if (left.isEmpty()) {
                update.set(MEMO_PARTICIPANT.REVOKED_AT, now)
            } else {
                val until = if (left.any { it.expiresAt == null }) null else left.maxOf { it.expiresAt!! }.atOffset(ZoneOffset.UTC)
                update.set(MEMO_PARTICIPANT.EXPIRES_AT, until).set(MEMO_PARTICIPANT.REVOKED_AT, null as OffsetDateTime?)
            }
        changed
            .where(
                MEMO_PARTICIPANT.MEMO_ID.eq(memoId),
                MEMO_PARTICIPANT.USER_ID.eq(userId),
                MEMO_PARTICIPANT.KIND.eq(ParticipantKind.SHARED.name),
            ).execute()
    }

    private fun share(r: MemoShareRecord) =
        MemoShare(
            id = r.id,
            memoId = r.memoId,
            userId = r.userId,
            grantedBy = r.grantedBy,
            reason = r.reason,
            grantedAt = r.grantedAt.toInstant(),
            expiresAt = r.expiresAt?.toInstant(),
            revokedAt = r.revokedAt?.toInstant(),
        )
}

/**
 * Shares (ADR-0050, design E03 §5): a participant who may act on the memo (initiator, co-author, approver) shares it
 * with one person cleared for it, with a reason and an optional expiry of at most a year. Whoever gave it, the
 * initiator and holders of `memo.share.manage` revoke it; a daily job records expiries. Every change is audited.
 */
@Service
class MemoShares(
    private val memos: Memos,
    private val store: MemoStore,
    private val shares: MemoShareStore,
    private val clearances: Clearances,
    private val users: UserSummaries,
    private val policy: Policy,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    /** The shares of [memoId] that count now, for its readers. */
    fun list(memoId: UUID): List<MemoShare> {
        memos.get(memoId)
        return shares.active(memoId, now())
    }

    fun grant(
        memoId: UUID,
        userId: UUID,
        reason: String,
        expiresAt: Instant?,
    ): MemoShare {
        val actor = currentUser()
        val memo = memos.get(memoId)
        store.lock(memo.id)
        val now = now()
        if (shares.activeKind(memo.id, actor, now) !in SHARERS) throw ForbiddenException(null)
        checkGrant(memo, actor, userId, reason, expiresAt)
        val kind = shares.activeKind(memo.id, userId, now)
        refuseIf(kind != null && kind != ParticipantKind.SHARED, "ALREADY_READER") { "This person can already read the memo." }
        val until = expiresAt?.atOffset(ZoneOffset.UTC)
        val id = shares.insert(NewShare(memo.id, userId, actor, reason.trim(), until, now))
        store.addParticipant(memo.id, userId, ParticipantKind.SHARED, now, grantedBy = actor, expiresAt = until)
        shares.follow(memo.id, userId, now)
        audit.record(
            "memo.share.granted",
            Memos.OBJECT_TYPE,
            memo.id.toString(),
            after =
                mapOf(
                    "share" to id.toString(),
                    "user" to userId.toString(),
                    "reasonLength" to reason.trim().length,
                    "expiresAt" to expiresAt?.toString(),
                ),
        )
        return checkNotNull(shares.get(id))
    }

    fun revoke(
        memoId: UUID,
        shareId: UUID,
    ) {
        val actor = currentUser()
        val memo = memos.get(memoId)
        store.lock(memo.id)
        val share = shares.get(shareId)?.takeIf { it.memoId == memo.id && it.revokedAt == null } ?: throw MemoNotFoundException("share")
        if (!mayRevoke(memo, share, actor)) throw ForbiddenException(Permission.MEMO_SHARE_MANAGE)
        val now = now()
        if (shares.revoke(share.id, actor, REVOKED, now)) {
            shares.follow(memo.id, share.userId, now)
            audit.record(
                "memo.share.revoked",
                Memos.OBJECT_TYPE,
                memo.id.toString(),
                after = mapOf("share" to share.id.toString(), "user" to share.userId.toString()),
            )
        }
    }

    /** Whether the actor may revoke [share] of [memo]: its giver, the initiator, or a holder of `memo.share.manage`. */
    fun mayRevoke(
        memo: Memo,
        share: MemoShare,
        actor: UUID,
    ): Boolean = share.grantedBy == actor || memo.initiatorId == actor || policy.allows(Permission.MEMO_SHARE_MANAGE)

    /** Records the shares that lapsed (the read rule already ignores them); run by the daily job as the system. */
    fun expireDue(): Int {
        val now = now()
        val due = shares.lapsed(now, BATCH)
        due.forEach { share ->
            if (shares.revoke(share.id, null, EXPIRED, now)) {
                shares.follow(share.memoId, share.userId, now)
                audit.record(
                    "memo.share.expired",
                    Memos.OBJECT_TYPE,
                    share.memoId.toString(),
                    after = mapOf("share" to share.id.toString(), "user" to share.userId.toString()),
                )
            }
        }
        return due.size
    }

    private fun checkGrant(
        memo: Memo,
        actor: UUID,
        userId: UUID,
        reason: String,
        expiresAt: Instant?,
    ) {
        val now = now()
        val person = users.of(listOf(userId))[userId]
        val problems =
            listOfNotNull(
                FieldProblem("userId", "UNKNOWN", "Choose a person of this organisation.")
                    .takeIf { person == null || person.status != UserStatus.ACTIVE },
                FieldProblem("userId", "SELF", "You can already read this memo.").takeIf { userId == actor },
                FieldProblem("userId", "NOT_CLEARED", "This person is not cleared for the memo's classification.")
                    .takeIf { person != null && (clearances.rankOf(listOf(userId))[userId] ?: 0) < memo.classificationRank },
                FieldProblem("reason", "REQUIRED", "Say why you share the memo (at most $MAX_REASON characters).")
                    .takeIf { reason.trim().length !in 1..MAX_REASON },
                FieldProblem("expiresAt", "INVALID", "Choose a date in the next year.")
                    .takeIf {
                        expiresAt != null &&
                            (!expiresAt.isAfter(now.toInstant()) || expiresAt.isAfter(now.toInstant().plus(MAX_SHARE)))
                    },
            )
        if (problems.isNotEmpty()) throw MemoRejectedException(problems)
    }

    private fun currentUser(): UUID = checkNotNull((ActorContext.current().actor as? Actor.User)?.userId) { "shares need a signed-in user" }

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private companion object {
        const val MAX_REASON = 500
        const val BATCH = 500
        const val REVOKED = "REVOKED"
        const val EXPIRED = "EXPIRED"
        val MAX_SHARE: Duration = Duration.ofDays(366)

        /** Who may share (design E03 §7): those who take part in deciding, not copy recipients, sharees or unit readers. */
        val SHARERS = setOf(ParticipantKind.INITIATOR, ParticipantKind.COAUTHOR, ParticipantKind.APPROVER)
    }
}

data class ShareRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val userId: UUID,
    /** Why the person needs the memo; 1 to 500 characters. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val reason: String,
    /** When the share ends; at most a year ahead. Left out: until revoked. */
    val expiresAt: Instant? = null,
)

data class ShareView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val person: Named,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val grantedBy: Named,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val reason: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val grantedAt: Instant,
    val expiresAt: Instant? = null,
    /** Whether the reader may revoke it: its giver, the initiator, or a holder of `memo.share.manage`. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val canRevoke: Boolean,
)

@RestController
@Tag(name = "memo")
class MemoSharesController(
    private val shares: MemoShares,
    private val memos: Memos,
    private val users: UserSummaries,
    private val transactions: TenantTransactions,
) {
    @GetMapping("/api/v1/memos/{id}/shares")
    @SignedIn
    @Operation(operationId = "listMemoShares", summary = "Who the memo is shared with now, by whom and why")
    fun list(
        @PathVariable id: UUID,
    ): ResponseEntity<List<ShareView>> {
        val views = transactions.readInTenant(TenantContext.current()) { views(id, shares.list(id)) }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(views)
    }

    @PostMapping("/api/v1/memos/{id}/shares")
    @SignedIn
    @Operation(operationId = "shareMemo", summary = "Share the memo with one person, with a reason and an optional end")
    fun share(
        @PathVariable id: UUID,
        @RequestBody body: ShareRequest,
    ): ResponseEntity<ShareView> {
        val view =
            transactions.inTenant(TenantContext.current()) {
                views(id, listOf(shares.grant(id, body.userId, body.reason, body.expiresAt))).single()
            }
        return ResponseEntity.status(HttpStatus.CREATED).body(view)
    }

    @DeleteMapping("/api/v1/memos/{id}/shares/{shareId}")
    @SignedIn
    @Operation(operationId = "revokeMemoShare", summary = "Revoke a share")
    fun revoke(
        @PathVariable id: UUID,
        @PathVariable shareId: UUID,
    ): ResponseEntity<Unit> {
        transactions.inTenant(TenantContext.current()) { shares.revoke(id, shareId) }
        return ResponseEntity.noContent().build()
    }

    private fun views(
        memoId: UUID,
        list: List<MemoShare>,
    ): List<ShareView> {
        val memo = memos.get(memoId)
        val actor = (ActorContext.current().actor as? Actor.User)?.userId
        val names = users.of(list.flatMap { listOf(it.userId, it.grantedBy) }.toSet()).mapValues { it.value.displayName }
        return list.map {
            ShareView(
                it.id,
                Named(it.userId, names[it.userId].orEmpty()),
                Named(it.grantedBy, names[it.grantedBy].orEmpty()),
                it.reason,
                it.grantedAt,
                it.expiresAt,
                actor != null && shares.mayRevoke(memo, it, actor),
            )
        }
    }
}

/** Task data of the per-tenant share expiry task. */
data class MemoShareExpiryTask(
    override val tenantId: UUID,
) : TenantScopedPayload

/** The share expiry tasks (ADR-0018): a daily platform sweep fans out to one task per active tenant. */
@Configuration(proxyBeanMethods = false)
class MemoShareJobs {
    @Bean
    fun memoShareExpiryTask(
        jobs: Jobs,
        shares: MemoShares,
        transactions: TenantTransactions,
    ): OneTimeTask<MemoShareExpiryTask> =
        jobs.tenantTask(EXPIRY, MemoShareExpiryTask::class.java) {
            transactions.inTenant(TenantContext.current()) { shares.expireDue() }
        }

    @Bean
    fun memoShareExpirySweep(
        jobs: Jobs,
        tenants: ActiveTenants,
        scheduler: ObjectProvider<SchedulerClient>,
        task: ObjectProvider<OneTimeTask<MemoShareExpiryTask>>,
    ): RecurringTask<Void> =
        jobs.platformTask("memo-share-expiry-sweep", Schedules.fixedDelay(EVERY)) {
            val client = scheduler.getObject()
            val expire = task.getObject()
            tenants.ids().forEach { tenant ->
                client.scheduleIfNotExists(expire.schedulableInstance(tenant.toString(), MemoShareExpiryTask(tenant.value)))
            }
        }

    private companion object {
        const val EXPIRY = "memo-share-expiry"
        val EVERY: Duration = Duration.ofDays(1)
    }
}
