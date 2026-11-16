package com.memo.memo.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.MEMO_WATCH
import com.memo.jooq.tables.references.MEMO_WATCH_SETTING
import org.jooq.Condition
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import com.memo.jooq.tables.references.MEMO_ as MEMO

/** Why someone follows a memo (flow J: "you approved step 2", "you follow this"). */
enum class WatchReason { MANUAL, INITIATED, ACTED, MENTIONED }

/** What happened to a followed memo. */
enum class WatchEventKind { STEP_DECIDED, NOTE_ADDED, CLOSED, RECORD_READY }

/** How a person hears about followed memos by email (flow J): a daily digest (default), at once, or never. */
enum class WatchEmail { DIGEST, IMMEDIATE, OFF }

/** A person's watch settings. */
data class WatchSettings(
    val autoWatch: Boolean = true,
    val email: WatchEmail = WatchEmail.DIGEST,
)

/** One watch of a person, for the Watching list. */
data class WatchRow(
    val memoId: UUID,
    val reason: WatchReason,
    val createdAt: Instant,
)

/** News on one followed memo for one person, not told yet in the app. */
data class PendingWatch(
    val userId: UUID,
    val memoId: UUID,
    val events: Int,
    val reason: WatchReason,
)

/** `memo_watch` and `memo_watch_setting`. Every method runs inside a tenant transaction. */
@Repository
class MemoWatchStore(
    private val tenantDsl: TenantDsl,
    private val clock: Clock,
) {
    /** Starts [user] following [memo] unless they already do; true when the watch is new. */
    fun add(
        memo: UUID,
        user: UUID,
        reason: WatchReason,
    ): Boolean =
        tenantDsl
            .current()
            .insertInto(MEMO_WATCH)
            .set(MEMO_WATCH.TENANT_ID, tenantDsl.tenant().value)
            .set(MEMO_WATCH.MEMO_ID, memo)
            .set(MEMO_WATCH.USER_ID, user)
            .set(MEMO_WATCH.REASON, reason.name)
            .set(MEMO_WATCH.CREATED_AT, now())
            .onConflictDoNothing()
            .execute() == 1

    /** Whether [user] follows any memo. */
    fun followsAny(user: UUID): Boolean = tenantDsl.current().fetchExists(MEMO_WATCH, MEMO_WATCH.USER_ID.eq(user))

    /** An automatic watch (initiated, acted, mentioned), unless the person turned automatic following off. */
    fun auto(
        memo: UUID,
        user: UUID,
        reason: WatchReason,
    ): Boolean = settings(user).autoWatch && add(memo, user, reason)

    fun remove(
        memo: UUID,
        user: UUID,
    ): Boolean =
        tenantDsl
            .current()
            .deleteFrom(MEMO_WATCH)
            .where(MEMO_WATCH.MEMO_ID.eq(memo), MEMO_WATCH.USER_ID.eq(user))
            .execute() == 1

    fun reason(
        memo: UUID,
        user: UUID,
    ): WatchReason? =
        tenantDsl
            .current()
            .select(MEMO_WATCH.REASON)
            .from(MEMO_WATCH)
            .where(MEMO_WATCH.MEMO_ID.eq(memo), MEMO_WATCH.USER_ID.eq(user))
            .fetchOne(MEMO_WATCH.REASON)
            ?.let(WatchReason::valueOf)

    fun watchers(memo: UUID): List<UUID> =
        tenantDsl
            .current()
            .select(MEMO_WATCH.USER_ID)
            .from(MEMO_WATCH)
            .where(MEMO_WATCH.MEMO_ID.eq(memo))
            .fetch(MEMO_WATCH.USER_ID)
            .filterNotNull()

    /** [user]'s watches of memos that [readable] allows, newest first, after the keyset position [after]. */
    fun watching(
        user: UUID,
        readable: Condition,
        after: Pair<Instant, UUID>?,
        limit: Int,
    ): List<WatchRow> =
        tenantDsl
            .current()
            .select(MEMO_WATCH.MEMO_ID, MEMO_WATCH.REASON, MEMO_WATCH.CREATED_AT)
            .from(MEMO_WATCH)
            .join(MEMO)
            .on(MEMO.ID.eq(MEMO_WATCH.MEMO_ID))
            .where(MEMO_WATCH.USER_ID.eq(user), readable)
            .and(
                after?.let { (at, id) -> DSL.row(MEMO_WATCH.CREATED_AT, MEMO_WATCH.MEMO_ID).lt(at.atOffset(ZoneOffset.UTC), id) }
                    ?: DSL.noCondition(),
            ).orderBy(MEMO_WATCH.CREATED_AT.desc(), MEMO_WATCH.MEMO_ID.desc())
            .limit(limit)
            .fetch {
                WatchRow(
                    requireNotNull(it.value1()),
                    WatchReason.valueOf(requireNotNull(it.value2())),
                    requireNotNull(it.value3()).toInstant(),
                )
            }

    /** Memos [user] follows that [readable] does not allow any more (the nightly sweep). */
    fun unreadable(
        user: UUID,
        readable: Condition,
    ): List<UUID> =
        tenantDsl
            .current()
            .select(MEMO_WATCH.MEMO_ID)
            .from(MEMO_WATCH)
            .join(MEMO)
            .on(MEMO.ID.eq(MEMO_WATCH.MEMO_ID))
            .where(MEMO_WATCH.USER_ID.eq(user), DSL.not(readable))
            .fetch(MEMO_WATCH.MEMO_ID)
            .filterNotNull()

    fun allWatchers(): List<UUID> =
        tenantDsl
            .current()
            .selectDistinct(MEMO_WATCH.USER_ID)
            .from(MEMO_WATCH)
            .fetch(MEMO_WATCH.USER_ID)
            .filterNotNull()

    fun settings(user: UUID): WatchSettings =
        tenantDsl
            .current()
            .selectFrom(MEMO_WATCH_SETTING)
            .where(MEMO_WATCH_SETTING.USER_ID.eq(user))
            .fetchOne()
            ?.let { WatchSettings(it.autoWatch != false, it.email?.let(WatchEmail::valueOf) ?: WatchEmail.DIGEST) } ?: WatchSettings()

    fun saveSettings(
        user: UUID,
        settings: WatchSettings,
    ) {
        tenantDsl
            .current()
            .insertInto(MEMO_WATCH_SETTING)
            .set(MEMO_WATCH_SETTING.TENANT_ID, tenantDsl.tenant().value)
            .set(MEMO_WATCH_SETTING.USER_ID, user)
            .set(MEMO_WATCH_SETTING.AUTO_WATCH, settings.autoWatch)
            .set(MEMO_WATCH_SETTING.EMAIL, settings.email.name)
            .set(MEMO_WATCH_SETTING.UPDATED_AT, now())
            .onConflict(MEMO_WATCH_SETTING.TENANT_ID, MEMO_WATCH_SETTING.USER_ID)
            .doUpdate()
            .set(MEMO_WATCH_SETTING.AUTO_WATCH, settings.autoWatch)
            .set(MEMO_WATCH_SETTING.EMAIL, settings.email.name)
            .set(MEMO_WATCH_SETTING.UPDATED_AT, now())
            .execute()
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
}
