package com.memo.memo.internal

import com.memo.common.cache.CacheInvalidation
import com.memo.common.cache.TenantCaches
import com.memo.common.tenant.TenantContext
import com.memo.common.web.FieldProblem
import com.memo.org.OrgDirectory
import com.memo.workflow.AttributeType
import com.memo.workflow.WorkflowDefinitions
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

/**
 * The item attributes of memos for workflow conditions and DoA rules (ADR-0043): the built-ins, and the fields marked
 * `attribute` in the published version of every memo type. An amount is offered in major units as `double`, with its
 * currency as `<key>Currency`; a date as a `timestamp` at the start of the day in the tenant's time zone. Cached per
 * tenant; the transaction that publishes a version calls [changed]. Inside a tenant transaction.
 */
@Component
class MemoAttributes(
    private val versions: MemoTypeVersionStore,
    private val directory: OrgDirectory,
    private val invalidation: CacheInvalidation,
    caches: TenantCaches,
) {
    private val cache = caches.register<String, Map<String, AttributeType>>(CACHE)

    /** Every attribute memos offer in this tenant now, by name. */
    fun catalogue(): Map<String, AttributeType> =
        cache.get(ALL) {
            BUILT_IN +
                versions.publishedAll().flatMap { of(it.settings.fields).entries }.associate { it.toPair() }
        }

    /** The catalogue changes when this transaction commits (a version was published). */
    fun changed() = invalidation.invalidate(CACHE, TenantContext.current())

    /** [memo]'s values of the attribute [fields] of its version, in the form conditions read them; absent values are null. */
    fun values(
        memo: Memo,
        fields: List<MemoField>,
    ): Map<String, Any?> =
        fields
            .filter { it.attribute }
            .flatMap { field ->
                val value = memo.metadata[field.key]
                when (field.type) {
                    FieldType.NUMBER -> {
                        listOf(field.key to (value as? String)?.toDoubleOrNull())
                    }

                    FieldType.AMOUNT -> {
                        val amount = value as? Map<*, *>
                        listOf(
                            field.key to (amount?.get("value") as? String)?.toDoubleOrNull(),
                            currencyKey(field.key) to amount?.get("currency") as? String,
                        )
                    }

                    FieldType.DATE -> {
                        listOf(field.key to (value as? String)?.let { LocalDate.parse(it).atStartOfDay(directory.zone()).toInstant() })
                    }

                    else -> {
                        listOf(field.key to value as? String)
                    }
                }
            }.toMap()

    companion object {
        const val CACHE = "memo.attributes"
        private const val ALL = "all"

        val BUILT_IN = mapOf("memoTypeId" to AttributeType.STRING, "classificationRank" to AttributeType.INT)

        /** The attributes [fields] offer, by name. */
        fun of(fields: List<MemoField>): Map<String, AttributeType> =
            fields
                .filter { it.attribute && it.type.attributable }
                .flatMap { field ->
                    when (field.type) {
                        FieldType.NUMBER -> listOf(field.key to AttributeType.DOUBLE)
                        FieldType.AMOUNT -> listOf(field.key to AttributeType.DOUBLE, currencyKey(field.key) to AttributeType.STRING)
                        FieldType.DATE -> listOf(field.key to AttributeType.TIMESTAMP)
                        else -> listOf(field.key to AttributeType.STRING)
                    }
                }.toMap()

        fun currencyKey(key: String) = "${key}Currency"
    }
}

/**
 * What a memo type's attribute fields must satisfy (ADR-0043): a name offered by several types has one type across the
 * tenant (`ATTRIBUTE_CONFLICT`), an amount's currency companion takes no other field's name, and a version must not stop
 * offering, or retype, an attribute memos offer now that a published definition or DoA rule uses (`ATTRIBUTE_IN_USE`).
 * Inside a tenant transaction.
 */
@Component
class MemoAttributeChecks(
    private val versions: MemoTypeVersionStore,
    private val attributes: MemoAttributes,
    private val definitions: WorkflowDefinitions,
) {
    fun problems(
        settings: MemoTypeSettings,
        typeId: UUID?,
    ): List<FieldProblem> {
        val others = versions.publishedAll().filter { it.typeId != typeId }
        val offered =
            others
                .flatMap { v ->
                    MemoAttributes.of(v.settings.fields).map { (name, type) -> name to (type to v.settings.name) }
                }.toMap()
        val keys = settings.fields.map { it.key }.toSet()
        val own =
            settings.fields.withIndex().filter { it.value.attribute }.flatMap { (i, field) ->
                val conflicts =
                    MemoAttributes.of(listOf(field)).mapNotNull { (name, type) ->
                        offered[name]?.takeIf { it.first != type }?.let { (other, typeName) ->
                            FieldProblem(
                                "fields/$i/attribute",
                                "ATTRIBUTE_CONFLICT",
                                "'$name' is a ${other.json} in $typeName; give this field another key or the same type.",
                            )
                        }
                    }
                val companion = MemoAttributes.currencyKey(field.key)
                conflicts +
                    listOfNotNull(
                        FieldProblem(
                            "fields/$i/key",
                            "ATTRIBUTE_CONFLICT",
                            "'$companion' holds this amount's currency in rules; rename the other field.",
                        ).takeIf { field.type == FieldType.AMOUNT && (companion in keys || companion in MemoAttributes.BUILT_IN) },
                    )
            }
        return own + inUse(settings, others)
    }

    /** The attributes a published definition or DoA rule reads that memos would stop offering, or offer with another type. */
    private fun inUse(
        settings: MemoTypeSettings,
        others: List<MemoTypeVersionRow>,
    ): List<FieldProblem> {
        val now = attributes.catalogue()
        val after =
            MemoAttributes.BUILT_IN + others.flatMap { MemoAttributes.of(it.settings.fields).entries }.associate { it.toPair() } +
                MemoAttributes.of(settings.fields)
        return definitions.attributesInUse(MemoWorkflowSubject.TYPE).mapNotNull { (name, declared) ->
            val before = now[name] ?: return@mapNotNull null
            val type = after[name]
            FieldProblem(
                "fields",
                "ATTRIBUTE_IN_USE",
                "'$name' is read by a published workflow or DoA rule; keep a field offering it as ${(declared ?: before).json}.",
            ).takeIf { type == null || type != (declared ?: before) }
        }
    }
}
