package com.memo.notification.internal

import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.NOTIFICATION_ITEM
import com.memo.jooq.tables.references.NOTIFICATION_PREFERENCE
import com.memo.notification.CalendarPart
import com.memo.notification.Mail
import com.memo.notification.NotificationCategory
import com.memo.notification.NotificationChannel
import com.memo.notification.NotificationKind
import com.memo.notification.NotificationLog
import com.memo.notification.NotificationResult
import com.memo.notification.Notifications
import com.memo.notification.Recipient
import com.memo.notification.SentNotification
import org.flywaydb.core.api.callback.Callback
import org.flywaydb.core.api.callback.Context
import org.flywaydb.core.api.callback.Event
import org.jooq.JSONB
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Per user, category and channel; a missing row means enabled, mandatory categories are always on (ADR-0030). */
@Component
internal class NotificationPreferences(
    private val tenantDsl: TenantDsl,
    private val clock: Clock,
) {
    fun enabled(
        user: UUID,
        category: NotificationCategory,
        channel: NotificationChannel,
    ): Boolean = category.mandatory || stored(user)[category to channel] ?: true

    /** The user's explicit choices; everything absent is enabled. */
    fun stored(user: UUID): Map<Pair<NotificationCategory, NotificationChannel>, Boolean> =
        tenantDsl
            .current()
            .select(NOTIFICATION_PREFERENCE.CATEGORY, NOTIFICATION_PREFERENCE.CHANNEL, NOTIFICATION_PREFERENCE.ENABLED)
            .from(NOTIFICATION_PREFERENCE)
            .where(NOTIFICATION_PREFERENCE.USER_ID.eq(user))
            .fetch()
            .mapNotNull { (category, channel, enabled) ->
                val c = NotificationCategory.entries.firstOrNull { it.name == category }
                val ch = NotificationChannel.entries.firstOrNull { it.name == channel }
                if (c == null || ch == null) null else (c to ch) to enabled!!
            }.toMap()

    /** Sets one choice; refuses to turn off a mandatory category. */
    fun set(
        user: UUID,
        category: NotificationCategory,
        channel: NotificationChannel,
        enabled: Boolean,
    ) {
        if (category.mandatory && !enabled) throw MandatoryCategoryException()
        val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        tenantDsl
            .current()
            .insertInto(NOTIFICATION_PREFERENCE)
            .set(NOTIFICATION_PREFERENCE.TENANT_ID, tenantDsl.tenant().value)
            .set(NOTIFICATION_PREFERENCE.USER_ID, user)
            .set(NOTIFICATION_PREFERENCE.CATEGORY, category.name)
            .set(NOTIFICATION_PREFERENCE.CHANNEL, channel.name)
            .set(NOTIFICATION_PREFERENCE.ENABLED, enabled)
            .set(NOTIFICATION_PREFERENCE.UPDATED_AT, now)
            .onConflict(
                NOTIFICATION_PREFERENCE.TENANT_ID,
                NOTIFICATION_PREFERENCE.USER_ID,
                NOTIFICATION_PREFERENCE.CATEGORY,
                NOTIFICATION_PREFERENCE.CHANNEL,
            ).doUpdate()
            .set(NOTIFICATION_PREFERENCE.ENABLED, enabled)
            .set(NOTIFICATION_PREFERENCE.UPDATED_AT, now)
            .execute()
    }
}

/** A mandatory category (security) cannot be switched off: 409 `MANDATORY`. */
internal class MandatoryCategoryException : RuntimeException("mandatory notification category")

/** ADR-0030: the in-app item and the email, as the recipient's preferences allow, in the caller's transaction. */
@Service
internal class NotificationService(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val preferences: NotificationPreferences,
    private val mail: Mail,
    private val json: JsonMapper,
    private val clock: Clock,
) : Notifications {
    override fun notify(
        recipient: Recipient,
        kind: NotificationKind,
        params: Map<String, String>,
        link: String?,
        calendar: CalendarPart?,
    ): NotificationResult {
        require(link == null || LINK.matches(link)) { "notification links are app routes" }
        val template = kind.email
        require(calendar == null || template != null) { "a calendar part needs a kind that sends email" }
        val category = kind.category
        val itemId =
            ids.next().takeIf { kind.inApp && preferences.enabled(recipient.userId, category, NotificationChannel.IN_APP) }
        if (itemId != null) {
            tenantDsl
                .current()
                .insertInto(NOTIFICATION_ITEM)
                .set(NOTIFICATION_ITEM.ID, itemId)
                .set(NOTIFICATION_ITEM.TENANT_ID, tenantDsl.tenant().value)
                .set(NOTIFICATION_ITEM.USER_ID, recipient.userId)
                .set(NOTIFICATION_ITEM.KIND, kind.name)
                .set(NOTIFICATION_ITEM.CATEGORY, category.name)
                .set(NOTIFICATION_ITEM.PARAMS, JSONB.valueOf(json.writeValueAsString(params)))
                .set(NOTIFICATION_ITEM.LINK, link)
                .set(NOTIFICATION_ITEM.CREATED_AT, OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))
                .execute()
        }
        val outboxId =
            if (template != null && preferences.enabled(recipient.userId, category, NotificationChannel.EMAIL)) {
                mail.enqueue(template, recipient.email, params + ("name" to recipient.displayName), recipient.locale, calendar)
            } else {
                null
            }
        return NotificationResult(itemId, outboxId)
    }

    private companion object {
        val LINK = Regex("^/[A-Za-z0-9/_.~%?=&-]{0,499}$")
    }
}

/**
 * The in-app items of some kinds written in a period (ADR-0030), for a module's digest of its own notices: one query on the
 * period's partitions, the tenant's rows by RLS.
 */
@Component
internal class NotificationLogStore(
    private val tenantDsl: TenantDsl,
    private val json: JsonMapper,
) : NotificationLog {
    override fun written(
        kinds: Set<NotificationKind>,
        from: Instant,
        to: Instant,
        limit: Int,
    ): List<SentNotification> {
        if (kinds.isEmpty()) return emptyList()
        val n = NOTIFICATION_ITEM
        return tenantDsl
            .current()
            .select(n.USER_ID, n.KIND, n.PARAMS, n.CREATED_AT)
            .from(n)
            .where(
                n.KIND.`in`(kinds.map { it.name }),
                n.CREATED_AT.ge(from.atOffset(ZoneOffset.UTC)),
                n.CREATED_AT.lt(to.atOffset(ZoneOffset.UTC)),
            ).orderBy(n.CREATED_AT, n.ID)
            .limit(limit)
            .fetch { r ->
                SentNotification(
                    checkNotNull(r.value1()),
                    NotificationKind.valueOf(checkNotNull(r.value2())),
                    r
                        .value3()
                        ?.data()
                        ?.let { json.readValue(it, STRINGS) }
                        .orEmpty(),
                    checkNotNull(r.value4()).toInstant(),
                )
            }
    }

    private companion object {
        val STRINGS = object : TypeReference<Map<String, String>>() {}
    }
}

/**
 * Creates monthly notification partitions ahead and drops expired ones on every migration run (ADR-0030), on Flyway's
 * migrator connection: the app role has no DDL. The audit store does the same (ADR-0019).
 */
@Component
internal class NotificationPartitionCallback(
    @param:Value("\${memo.notification.partitions-ahead:3}") private val monthsAhead: Int,
    @param:Value("\${memo.notification.retention-months:13}") private val retentionMonths: Int,
) : Callback {
    override fun supports(
        event: Event,
        context: Context?,
    ): Boolean = event == Event.AFTER_MIGRATE

    override fun canHandleInTransaction(
        event: Event,
        context: Context?,
    ): Boolean = true

    override fun handle(
        event: Event,
        context: Context,
    ) {
        ensurePartitions(context.connection, monthsAhead, retentionMonths)
    }

    override fun getCallbackName(): String = "notificationPartitions"

    companion object {
        /** Partitions created plus dropped; 0 before the notification migration has run. */
        fun ensurePartitions(
            connection: java.sql.Connection,
            monthsAhead: Int,
            retentionMonths: Int,
        ): Int {
            val exists =
                connection.prepareStatement("select to_regprocedure(?) is not null").use { st ->
                    st.setString(1, "notification_ensure_partitions(integer, integer)")
                    st.executeQuery().use { it.next() && it.getBoolean(1) }
                }
            if (!exists) return 0
            return connection.prepareStatement("select notification_ensure_partitions(?, ?)").use { st ->
                st.setInt(1, monthsAhead)
                st.setInt(2, retentionMonths)
                st.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }
    }
}
