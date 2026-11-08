package com.memo.tenancy

import com.memo.audit.AuditLog
import com.memo.common.cache.CacheInvalidation
import com.memo.common.cache.TenantCache
import com.memo.common.cache.TenantCaches
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.TENANCY_TERM
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Terms a tenant may rename (ADR-0032). Messages take them as arguments; defaults live in the message catalogues. */
enum class TermKey(
    val key: String,
) {
    UNIT("unit"),
    POSITION("position"),
    MEMO("memo"),
    ;

    companion object {
        fun ofKey(key: String): TermKey? = entries.firstOrNull { it.key == key }
    }
}

enum class TermGender(
    val key: String,
) {
    MASCULINE("masculine"),
    FEMININE("feminine"),
    ;

    companion object {
        fun ofKey(key: String?): TermGender? = entries.firstOrNull { it.key == key }
    }
}

/** A tenant's wording of one term in one language; [gender] only where the language has it. */
data class TermOverride(
    val term: TermKey,
    val one: String,
    val other: String,
    val gender: TermGender?,
)

/** Why a term override was refused, per field (`terms[i].one`, …). */
class TermsRejectedException(
    val problems: List<Pair<String, String>>,
) : IllegalArgumentException("terms rejected")

/**
 * Tenant terminology overrides (ADR-0032). Inside a tenant transaction. Reads are cached per locale and evicted on every
 * node when a tenant admin saves.
 */
@Service
class Terminology(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val audit: AuditLog,
    private val invalidation: CacheInvalidation,
    caches: TenantCaches,
) {
    private val cache: TenantCache<String, List<TermOverride>> = caches.register(CACHE)

    /** The overrides for [locale]; terms without one use the catalogue's defaults. */
    fun overrides(locale: String): List<TermOverride> = cache.get(locale) { load(locale) }

    /** A version of [locale]'s overrides for `If-Match`; grows with every change (rows are never deleted). */
    fun version(locale: String): Long =
        tenantDsl
            .current()
            .select(TENANCY_TERM.VERSION)
            .from(TENANCY_TERM)
            .where(TENANCY_TERM.LOCALE.eq(locale))
            .fetch { it.value1()!! + 1 }
            .sum()

    /** Replaces [locale]'s overrides: terms not in [wanted] go back to their defaults. Audited per changed term. */
    fun replace(
        locale: String,
        wanted: List<TermOverride>,
        expectedVersion: Long,
    ): List<TermOverride> {
        require(locale in LOCALES) { "unsupported locale" }
        validate(locale, wanted)
        if (version(locale) != expectedVersion) throw ConcurrentModificationException("terms $locale", expectedVersion)
        val before = load(locale).associateBy { it.term }
        val after = wanted.associateBy { it.term }
        TermKey.entries.filter { before[it] != after[it] }.forEach { term ->
            store(term, locale, after[term])
            audit.record(
                "tenant.term.changed",
                "tenant.term",
                "${term.key}.$locale",
                before[term]?.let { forms(it) },
                after[term]?.let { forms(it) },
            )
        }
        invalidation.invalidate(CACHE, tenantDsl.tenant(), locale)
        return load(locale)
    }

    private fun store(
        term: TermKey,
        locale: String,
        value: TermOverride?,
    ) {
        val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        tenantDsl
            .current()
            .insertInto(TENANCY_TERM)
            .set(TENANCY_TERM.ID, ids.next())
            .set(TENANCY_TERM.TENANT_ID, tenantDsl.tenant().value)
            .set(TENANCY_TERM.TERM, term.key)
            .set(TENANCY_TERM.LOCALE, locale)
            .set(TENANCY_TERM.ONE, value?.one)
            .set(TENANCY_TERM.OTHER, value?.other)
            .set(TENANCY_TERM.GENDER, value?.gender?.key)
            .set(TENANCY_TERM.CREATED_AT, now)
            .set(TENANCY_TERM.UPDATED_AT, now)
            .onConflict(TENANCY_TERM.TENANT_ID, TENANCY_TERM.TERM, TENANCY_TERM.LOCALE)
            .doUpdate()
            .set(TENANCY_TERM.ONE, value?.one)
            .set(TENANCY_TERM.OTHER, value?.other)
            .set(TENANCY_TERM.GENDER, value?.gender?.key)
            .set(TENANCY_TERM.VERSION, TENANCY_TERM.VERSION.plus(1))
            .set(TENANCY_TERM.UPDATED_AT, now)
            .execute()
    }

    private fun load(locale: String): List<TermOverride> =
        tenantDsl
            .current()
            .selectFrom(TENANCY_TERM)
            .where(TENANCY_TERM.LOCALE.eq(locale))
            .and(TENANCY_TERM.ONE.isNotNull)
            .fetch()
            .mapNotNull { row ->
                TermKey.ofKey(row.term)?.let { TermOverride(it, row.one!!, row.other!!, TermGender.ofKey(row.gender)) }
            }.sortedBy { it.term.ordinal }

    private fun validate(
        locale: String,
        wanted: List<TermOverride>,
    ) {
        val problems =
            wanted.flatMapIndexed { i, term ->
                buildList {
                    if (!plausible(term.one)) add("terms[$i].one" to "INVALID")
                    if (!plausible(term.other)) add("terms[$i].other" to "INVALID")
                    if (locale in GENDERED && term.gender == null) add("terms[$i].gender" to "REQUIRED")
                }
            } +
                wanted
                    .groupBy { it.term }
                    .filterValues { it.size > 1 }
                    .keys
                    .map { "terms" to "DUPLICATE_${it.name}" }
        if (problems.isNotEmpty()) throw TermsRejectedException(problems)
    }

    /** Plain words only: no ICU or markup syntax, no control characters (threat model T4). */
    private fun plausible(form: String) =
        form.trim().length in 1..MAX_FORM && form == form.trim() && form.none { it in FORBIDDEN || it.isISOControl() }

    private fun forms(term: TermOverride) = mapOf("one" to term.one, "other" to term.other, "gender" to term.gender?.key)

    companion object {
        const val CACHE = "tenancy.terms"
        val LOCALES = setOf("en", "ar")

        /** Languages whose messages agree with a term's gender. */
        val GENDERED = setOf("ar")
        private const val MAX_FORM = 40
        private val FORBIDDEN = setOf('{', '}', '<', '>', '#')
    }
}
