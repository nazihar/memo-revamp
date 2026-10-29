package com.memo.common.ratelimit

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import com.memo.common.jobs.Jobs
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy
import io.github.bucket4j.distributed.proxy.ProxyManager
import io.github.bucket4j.postgresql.Bucket4jPostgreSQL
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import javax.sql.DataSource

/** A named limit: [capacity] requests per [period], refilled smoothly (ADR-0025). The table below is the configuration. */
@Suppress("MagicNumber")
enum class RateLimit(
    val capacity: Long,
    val period: Duration,
) {
    LOGIN_PER_IP(20, Duration.ofMinutes(1)),
    LOGIN_PER_ACCOUNT(10, Duration.ofMinutes(15)),
    OTP_PER_USER(5, Duration.ofMinutes(1)),
    PASSWORD_RESET_PER_IP(5, Duration.ofHours(1)),
    PASSWORD_RESET_PER_ACCOUNT(3, Duration.ofHours(1)),
    INVITATION_PER_IP(10, Duration.ofHours(1)),
    ;

    internal val configuration: BucketConfiguration by lazy {
        BucketConfiguration
            .builder()
            .addLimit(
                Bandwidth
                    .builder()
                    .capacity(capacity)
                    .refillGreedy(capacity, period)
                    .build(),
            ).build()
    }
}

/** The limit is exhausted; retry after [retryAfter]. Rendered as 429 with `Retry-After`. */
class RateLimitedException(
    val retryAfter: Duration,
) : RuntimeException("Rate limit exceeded")

/**
 * Cluster-wide rate limits in PostgreSQL (Bucket4j, ADR-0025). Keys are the first 8 bytes of SHA-256 of
 * `<limit>:<subject>`, so no IP address or email reaches the table. Each check is one short transaction on its own
 * connection: call it before opening a business transaction.
 */
@Component
class RateLimiter(
    dataSource: DataSource,
) {
    private val buckets: ProxyManager<Long> =
        Bucket4jPostgreSQL
            .selectForUpdateBasedBuilder(dataSource)
            .table(TABLE)
            .idColumn("id")
            .stateColumn("state")
            .expiresAtColumn("expires_at")
            .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(1)))
            .build()

    /** Takes one token for [subject] under [limit], or throws [RateLimitedException]. */
    fun consume(
        limit: RateLimit,
        subject: String,
    ) {
        val probe = buckets.getProxy(key(limit, subject)) { limit.configuration }.tryConsumeAndReturnRemaining(1)
        if (!probe.isConsumed) throw RateLimitedException(Duration.ofNanos(probe.nanosToWaitForRefill))
    }

    companion object {
        const val TABLE = "identity_rate_limit_bucket"

        internal fun key(
            limit: RateLimit,
            subject: String,
        ): Long = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest("${limit.name}:$subject".toByteArray(Charsets.UTF_8))).long
    }
}

@Configuration(proxyBeanMethods = false)
class RateLimitTasks {
    /** Removes buckets that have refilled completely (their `expires_at`, epoch milliseconds, has passed). */
    @Bean
    fun rateLimitCleanupTask(
        jobs: Jobs,
        dataSource: DataSource,
        clock: Clock,
    ): RecurringTask<Void> =
        jobs.platformTask("rate-limit-cleanup", Schedules.fixedDelay(Duration.ofDays(1))) {
            JdbcTemplate(dataSource).update("delete from identity_rate_limit_bucket where expires_at < ?", clock.millis())
        }
}
