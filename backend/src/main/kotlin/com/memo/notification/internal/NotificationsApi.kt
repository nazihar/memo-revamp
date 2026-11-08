package com.memo.notification.internal

import com.memo.common.authz.SignedIn
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.jooq.tables.references.NOTIFICATION_ITEM
import com.memo.notification.NotificationCategory
import com.memo.notification.NotificationChannel
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.jooq.impl.DSL
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

data class NotificationView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val category: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val params: Map<String, String>,
    val link: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val createdAt: Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val read: Boolean,
)

data class NotificationPage(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val items: List<NotificationView>,
    /** Pass as `cursor` for older items; null at the end. */
    val nextCursor: String?,
)

data class UnreadCount(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val count: Int,
)

data class PreferenceView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val category: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val channel: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val enabled: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val mandatory: Boolean,
)

data class PreferenceChange(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val category: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val channel: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val enabled: Boolean,
)

/** The signed-in user's own notifications (ADR-0030): every query is limited to the actor, on top of tenant RLS. */
@Component
internal class Inbox(
    private val tenantDsl: TenantDsl,
    private val json: JsonMapper,
    private val clock: Clock,
) {
    private val dsl get() = tenantDsl.current()

    fun page(
        user: UUID,
        cursor: String?,
        limit: Int,
    ): NotificationPage {
        val after = cursor?.let(::decode)
        val rows =
            dsl
                .selectFrom(NOTIFICATION_ITEM)
                .where(NOTIFICATION_ITEM.USER_ID.eq(user))
                .and(
                    if (after == null) {
                        DSL.noCondition()
                    } else {
                        DSL.row(NOTIFICATION_ITEM.CREATED_AT, NOTIFICATION_ITEM.ID).lt(after.first.atOffset(ZoneOffset.UTC), after.second)
                    },
                ).orderBy(NOTIFICATION_ITEM.CREATED_AT.desc(), NOTIFICATION_ITEM.ID.desc())
                .limit(limit + 1)
                .fetch()
        val items =
            rows.take(limit).map {
                @Suppress("UNCHECKED_CAST")
                val params = json.readValue(it.params!!.data(), Map::class.java) as Map<String, String>
                NotificationView(it.id, it.kind, it.category, params, it.link, it.createdAt.toInstant(), it.readAt != null)
            }
        val next = if (rows.size > limit) items.last().let { encode(it.createdAt, it.id) } else null
        return NotificationPage(items, next)
    }

    fun unread(user: UUID): Int =
        dsl.fetchCount(NOTIFICATION_ITEM, NOTIFICATION_ITEM.USER_ID.eq(user).and(NOTIFICATION_ITEM.READ_AT.isNull))

    /** False when the item is not the user's (or does not exist). */
    fun markRead(
        user: UUID,
        id: UUID,
    ): Boolean {
        val mine = dsl.fetchExists(NOTIFICATION_ITEM, NOTIFICATION_ITEM.ID.eq(id).and(NOTIFICATION_ITEM.USER_ID.eq(user)))
        if (mine) {
            dsl
                .update(NOTIFICATION_ITEM)
                .set(NOTIFICATION_ITEM.READ_AT, now())
                .where(NOTIFICATION_ITEM.ID.eq(id))
                .and(NOTIFICATION_ITEM.USER_ID.eq(user))
                .and(NOTIFICATION_ITEM.READ_AT.isNull)
                .execute()
        }
        return mine
    }

    fun markAllRead(user: UUID): Int =
        dsl
            .update(NOTIFICATION_ITEM)
            .set(NOTIFICATION_ITEM.READ_AT, now())
            .where(NOTIFICATION_ITEM.USER_ID.eq(user))
            .and(NOTIFICATION_ITEM.READ_AT.isNull)
            .execute()

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private fun encode(
        at: Instant,
        id: UUID,
    ) = Base64.getUrlEncoder().withoutPadding().encodeToString("${at.epochSecond}~${at.nano}~$id".toByteArray())

    private fun decode(cursor: String): Pair<Instant, UUID> {
        val decoded =
            runCatching {
                val parts = String(Base64.getUrlDecoder().decode(cursor)).split('~')
                Instant.ofEpochSecond(parts[0].toLong(), parts[1].toLong()) to UUID.fromString(parts[2])
            }
        return decoded.getOrNull() ?: throw IllegalArgumentException("invalid cursor")
    }
}

/** Own notifications and preferences (design §5). Any signed-in user, always about themselves. */
@RestController
@Tag(name = "notifications")
internal class NotificationsController(
    private val inbox: Inbox,
    private val preferences: NotificationPreferences,
    private val transactions: TenantTransactions,
) {
    @GetMapping("/api/v1/me/notifications")
    @SignedIn
    @Operation(operationId = "listMyNotifications", summary = "My notifications, newest first")
    fun list(
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): NotificationPage = read { inbox.page(me(), cursor, limit.coerceIn(1, MAX_PAGE)) }

    @GetMapping("/api/v1/me/notifications/unread-count")
    @SignedIn
    @Operation(operationId = "getMyUnreadCount", summary = "How many of my notifications are unread")
    fun unread(): UnreadCount = read { UnreadCount(inbox.unread(me())) }

    @PostMapping("/api/v1/me/notifications/{id}/read")
    @SignedIn
    @Operation(operationId = "markNotificationRead", summary = "Mark one of my notifications as read")
    fun markRead(
        @PathVariable id: UUID,
    ): ResponseEntity<Unit> =
        if (write { inbox.markRead(me(), id) }) ResponseEntity.noContent().build() else throw NotificationNotFoundException()

    @PostMapping("/api/v1/me/notifications/read-all")
    @SignedIn
    @Operation(operationId = "markAllNotificationsRead", summary = "Mark all my notifications as read")
    fun markAllRead(): ResponseEntity<Unit> {
        write { inbox.markAllRead(me()) }
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/api/v1/me/notification-preferences")
    @SignedIn
    @Operation(operationId = "getMyNotificationPreferences", summary = "My notification channels per category")
    fun getPreferences(): List<PreferenceView> = read { views() }

    @PutMapping("/api/v1/me/notification-preferences")
    @SignedIn
    @Operation(operationId = "setMyNotificationPreferences", summary = "Change my notification channels")
    fun setPreferences(
        @RequestBody changes: List<PreferenceChange>,
    ): List<PreferenceView> =
        write {
            val user = me()
            changes.forEach { change ->
                val category = NotificationCategory.entries.firstOrNull { it.name == change.category }
                val channel = CHANNELS.firstOrNull { it.name == change.channel }
                require(category != null && channel != null) { "unknown category or channel" }
                preferences.set(user, category, channel, change.enabled)
            }
            views()
        }

    private fun views(): List<PreferenceView> {
        val stored = preferences.stored(me())
        return NotificationCategory.entries.flatMap { category ->
            CHANNELS.map { channel ->
                PreferenceView(category.name, channel.name, category.mandatory || stored[category to channel] ?: true, category.mandatory)
            }
        }
    }

    private fun me(): UUID = (ActorContext.current().actor as? Actor.User)?.userId ?: error("signed-in user expected")

    private fun <T> read(block: () -> T): T = transactions.readInTenant(TenantContext.current(), block)

    private fun <T> write(block: () -> T): T = transactions.inTenant(TenantContext.current(), block)

    private companion object {
        const val MAX_PAGE = 50

        /** Channels users choose in 1a; PUSH arrives with the mobile apps (Phase 6a). */
        val CHANNELS = listOf(NotificationChannel.IN_APP, NotificationChannel.EMAIL)
    }
}

/** An unknown notification, or another user's: 404. */
internal class NotificationNotFoundException : RuntimeException("notification not found")
